"""Around a sync window, records each phone's app process, listening ports and TCP connections.

    python3 -I scripts/watch-sockets.py <start HH:MM:SS> <seconds> <serial:uid> [<serial:uid> …]

Waits until <start> by this machine's clock, then for <seconds> prints a line whenever a phone's
PorygonList pid or sockets change. The uid is the app's (`dumpsys package
io.github.molleware.porygonlist | grep userId`); sockets come from /proc/net/tcp and tcp6, which
the app's own logs do not give. It samples about once a second per phone, so a connection that
opens and closes between samples is missed: confirm an exchange by what arrived, not by this.

Honours ANDROID_ADB_SERVER_PORT (the host's adb server, from the dev container). Standard library
only.
"""
import datetime
import subprocess
import sys
import time

STATES = {"01": "ESTABLISHED", "02": "SYN_SENT", "03": "SYN_RECV", "06": "TIME_WAIT", "08": "CLOSE_WAIT", "0A": "LISTEN"}


def now():
    return datetime.datetime.now().strftime("%H:%M:%S")


def address(hexaddr):
    host, port = hexaddr.split(":")
    if len(host) == 32 and host[:24] in ("0000000000000000FFFF0000", "00000000000000000000FFFF"):
        host = host[24:]
    host = ".".join(str(int(host[i:i + 2], 16)) for i in (6, 4, 2, 0)) if len(host) == 8 else "v6"
    return f"{host}:{int(port, 16)}"


def look(serial, uid):
    shell = ["adb", "-s", serial, "shell"]
    pid = subprocess.run(shell + ["pidof", "io.github.molleware.porygonlist"],
                         capture_output=True, text=True).stdout.strip() or "-"
    seen = []
    for table in ("tcp", "tcp6"):
        lines = subprocess.run(shell + ["cat", f"/proc/net/{table}"], capture_output=True, text=True).stdout.splitlines()
        for line in lines[1:]:
            f = line.split()
            if len(f) > 7 and f[7] == uid:
                state = STATES.get(f[3], f[3])
                seen.append(f"{state} {address(f[1])}" + ("" if state == "LISTEN" else f" <-> {address(f[2])}"))
    return pid, sorted(set(seen))


if __name__ == "__main__":
    if len(sys.argv) < 4 or not all(":" in p for p in sys.argv[3:]):
        sys.exit(__doc__)
    start, seconds, phones = sys.argv[1], int(sys.argv[2]), [p.split(":") for p in sys.argv[3:]]
    while now() < start:
        time.sleep(1)
    end, last = time.time() + seconds, {}
    while time.time() < end:
        for serial, uid in phones:
            pid, seen = look(serial, uid)
            line = f"pid {pid}: " + ("; ".join(seen) or "no sockets")
            if last.get(serial) != line:
                print(f"{now()} {serial[:6]} {line}", flush=True)
                last[serial] = line
        time.sleep(0.5)
    print(f"{now()} done", flush=True)
