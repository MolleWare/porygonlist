"""Looks at throwaway test lists only, on a phone that also holds someone's real lists.

    python3 -I scripts/testlists.py cards <adb serial> <prefix>         the cards on screen named <prefix>…
    python3 -I scripts/testlists.py open <adb serial> <name> [nth]      open the nth card named <name>, print its items

A test list's name starts with a session prefix such as "S6"; anything else is refused, so a real
list's name or items never reach the output. Use these on the other person's phone instead of
`device.sh read` or a screenshot of the Lists screen.

Honours ANDROID_ADB_SERVER_PORT (the host's adb server, from the dev container). Standard library
only.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PREFIX = re.compile(r"^S\d+\b")
COUNT = re.compile(r"^\d+ items?$")


def adb(serial, *args):
    return subprocess.run(["adb", "-s", serial] + list(args), capture_output=True, text=True, check=False).stdout


def dump(serial):
    raw = adb(serial, "exec-out", "uiautomator", "dump", "--compressed", "/dev/tty")
    return ET.fromstring(raw[: raw.find("</hierarchy>") + len("</hierarchy>")])


def label(node):
    return node.get("text") or node.get("content-desc") or ""


def card_nodes(root, test):
    """Clickable list cards whose name passes [test], with their texts."""
    out = []
    for n in root.iter("node"):
        if n.get("clickable") != "true" and n.get("long-clickable") != "true":
            continue
        t = [label(k) for k in n.iter("node") if label(k)]
        if t and test(t[0]) and any(COUNT.match(x) for x in t):
            out.append((n, t))
    return out


def require_prefix(text):
    if not PREFIX.match(text):
        sys.exit(f'Test lists only: "{text}" does not start with a session prefix such as S6.')


def cards(serial, prefix):
    require_prefix(prefix)
    found = card_nodes(dump(serial), lambda name: name.startswith(prefix))
    for _, t in found:
        print(" | ".join(t))
    if not found:
        print(f"no card named {prefix}… on screen")


def open_card(serial, name, nth):
    require_prefix(name)
    matches = card_nodes(dump(serial), lambda n: n == name)
    if len(matches) < nth:
        sys.exit(f"only {len(matches)} cards named {name} on screen")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", matches[nth - 1][0].get("bounds")))
    # Left of centre, on the name: away from the avatars at the right edge.
    adb(serial, "shell", "input", "tap", str(x1 + (x2 - x1) // 3), str(y1 + (y2 - y1) // 3))
    time.sleep(1.5)
    root = dump(serial)
    title = [label(n) for n in root.iter("node") if n.get("text")][:3]
    if name not in title:
        sys.exit("the list did not open")
    items = [next((label(k) for k in n.iter("node") if label(k)), "")
             for n in root.iter("node") if n.get("checkable") == "true"]
    print(f"opened card {nth}: {' / '.join(title)} — items: {', '.join(items)}")


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "cards":
        cards(sys.argv[2], sys.argv[3])
    elif len(sys.argv) in (4, 5) and sys.argv[1] == "open":
        open_card(sys.argv[2], sys.argv[3], int(sys.argv[4]) if len(sys.argv) == 5 else 1)
    else:
        sys.exit(__doc__)
