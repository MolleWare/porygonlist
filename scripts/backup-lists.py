"""Read-only backup of every list on a phone, from the screen, and a comparison of two backups.

    python3 -I scripts/backup-lists.py backup <adb serial> <output dir>
    python3 -I scripts/backup-lists.py compare <backup dir> <backup dir>

A release build has no run-as, so the lists are read the way a person would: each list opened in
turn, its items recorded in screen order (name, quantity, ticked), a screenshot of every page, and
a paste-able PL1 line per list in paste.txt for Share → Paste a list. Taps only list cards, tabs
and "All lists" — never a checkbox.

Built for a phone holding someone's real lists, so it prints counts only, and the output folder
must be outside this repository. compare names lists by position, never by title.

Honours ANDROID_ADB_SERVER_PORT (the host's adb server, from the dev container). Standard library
only.
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "io.github.molleware.porygonlist"
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COUNT = re.compile(r"^\d+ items?$")
QTY = re.compile(r"^(.*) ×(\d+)$")


class Phone:
    def __init__(self, serial):
        self.adb_args = ["adb", "-s", serial]
        w, h = re.search(r"(\d+)x(\d+)", self.adb("shell", "wm", "size")).groups()
        self.w, self.h = int(w), int(h)

    def adb(self, *args, binary=False):
        return subprocess.run(self.adb_args + list(args), capture_output=True, text=not binary, check=False).stdout

    def dump(self):
        raw = self.adb("exec-out", "uiautomator", "dump", "--compressed", "/dev/tty")
        return ET.fromstring(raw[: raw.find("</hierarchy>") + len("</hierarchy>")])

    def tap(self, x, y):
        self.adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1.2)

    def swipe_up(self):
        # From the lower middle, never the top edge: a swipe from the top opens the notification shade.
        self.adb("shell", "input", "swipe", str(self.w // 2), str(int(self.h * 0.72)),
                 str(self.w // 2), str(int(self.h * 0.40)), "400")
        time.sleep(0.8)

    def swipe_down(self):
        self.adb("shell", "input", "swipe", str(self.w // 2), str(int(self.h * 0.40)),
                 str(self.w // 2), str(int(self.h * 0.88)), "150")
        time.sleep(0.4)


def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))


def label(node):
    return node.get("text") or node.get("content-desc") or ""


def texts(node):
    return [label(n) for n in node.iter("node") if label(n)]


def find(root, text):
    return next((n for n in root.iter("node") if label(n) == text and n.get("package") == PKG), None)


def tap_centre(phone, node):
    x1, y1, x2, y2 = bounds(node)
    phone.tap((x1 + x2) // 2, (y1 + y2) // 2)


def go_lists(phone):
    phone.adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(1.5)
    root = phone.dump()
    # Not `or`: an element without children is falsy, so "All lists" would be skipped.
    target = find(root, "All lists")
    if target is None:
        target = find(root, "Lists")
    if target is not None:
        tap_centre(phone, target)
    for _ in range(4):
        phone.swipe_down()


def cards(phone, root):
    """Fully visible list cards: (their whole text, as a key that tells same-named lists apart, node)."""
    out = []
    for n in root.iter("node"):
        if n.get("clickable") != "true" and n.get("long-clickable") != "true":
            continue
        t = texts(n)
        if len(t) < 2 or not any(COUNT.match(x) for x in t) or COUNT.match(t[0]) or t[0] == "·":
            continue
        _, y1, _, y2 = bounds(n)
        # A card cut off at an edge has lost its name; it is read whole on another page.
        if y1 < int(phone.h * 0.04) or y2 > int(phone.h * 0.80):
            continue
        out.append((tuple(t), n))
    return out


def all_list_keys(phone):
    go_lists(phone)
    keys, idle = [], 0
    for _ in range(30):
        new = [key for key, _ in cards(phone, phone.dump()) if key not in keys]
        keys += new
        idle = 0 if new else idle + 1
        if idle >= 2:
            break
        phone.swipe_up()
    return keys


def open_list(phone, key):
    go_lists(phone)
    for _ in range(30):
        for k, node in cards(phone, phone.dump()):
            if k == key:
                x1, y1, x2, y2 = bounds(node)
                # Left of centre, on the name: away from the avatars at the right edge.
                phone.tap(x1 + (x2 - x1) // 3, y1 + (y2 - y1) // 3)
                return True
        phone.swipe_up()
    return False


def rows(root):
    """Item rows: a checkable node named for the item, and the label beside it carrying ×n."""
    found = []
    labels = [(label(n), bounds(n)) for n in root.iter("node") if n.get("text")]
    for n in root.iter("node"):
        if n.get("checkable") != "true" or n.get("package") != PKG:
            continue
        name = next((x for x in texts(n) if x), "")
        if not name:
            continue
        qty_in_name = QTY.match(name)
        _, y1, _, y2 = bounds(n)
        qty = 1
        for text, (_, ly1, _, ly2) in labels:
            m = QTY.match(text)
            if m and m.group(1) == name and ly1 < y2 and ly2 > y1:
                qty = int(m.group(2))
        if qty_in_name:
            name, qty = qty_in_name.group(1), int(qty_in_name.group(2))
        found.append({"name": name, "qty": qty, "ticked": n.get("checked") == "true", "top": y1})
    return found


def read_list(phone, out, index):
    items, seen, idle, title = [], set(), 0, ""
    for page in range(120):
        root = phone.dump()
        if page == 0:
            # A list that has been cleared shows a History button beside "All lists", before the title.
            heading = [label(n) for n in root.iter("node") if n.get("text") and label(n) != "History"]
            title = heading[1] if len(heading) > 1 and heading[0] == "All lists" else ""
        with open(os.path.join(out, f"list{index:02d}-page{page:02d}.png"), "wb") as f:
            f.write(phone.adb("exec-out", "screencap", "-p", binary=True))
        new = 0
        for r in sorted(rows(root), key=lambda r: r["top"]):
            key = (r["name"], r["qty"], r["ticked"])
            if key in seen:
                continue
            seen.add(key)
            items.append({k: r[k] for k in ("name", "qty", "ticked")})
            new += 1
        idle = 0 if new else idle + 1
        if idle >= 2:
            break
        phone.swipe_up()
    return items, title


def pl1(name, items):
    body = ", ".join((f"{i['qty']} " if i["qty"] > 1 else "") + i["name"].lower() for i in items if not i["ticked"])
    return f"PL1 · {name} · {body}"


def backup(serial, out):
    out = os.path.abspath(out)
    if out == REPO or out.startswith(REPO + os.sep):
        sys.exit("The backup holds someone's lists: put it outside the repository.")
    if os.path.exists(os.path.join(out, "lists.json")):
        sys.exit(f"{out} already holds a backup; give a new folder.")
    os.makedirs(out, mode=0o700, exist_ok=True)
    phone = Phone(serial)

    keys = all_list_keys(phone)
    summary, saved = [], []
    for i, key in enumerate(keys, 1):
        if not open_list(phone, key):
            summary.append(f"list {i}: could not open")
            continue
        items, title = read_list(phone, out, i)
        card_count = next(int(x.split()[0]) for x in key if COUNT.match(x))
        saved.append({"name": key[0], "card": list(key), "items": items})
        checks = ("title ok" if title == key[0] else "TITLE MISMATCH") + (
            ", count ok" if card_count == len(items) else f", COUNT {len(items)} vs card {card_count}")
        summary.append(f"list {i}: {len(items)} items, {sum(it['ticked'] for it in items)} ticked; {checks}")

    with open(os.path.join(out, "lists.json"), "w") as f:
        json.dump(saved, f, ensure_ascii=False, indent=2)
    with open(os.path.join(out, "paste.txt"), "w") as f:
        for entry in saved:
            f.write(pl1(entry["name"], entry["items"]) + "\n")
            ticked = [it["name"] for it in entry["items"] if it["ticked"]]
            if ticked:
                f.write(f"#   ticked, not in the line above: {', '.join(ticked)}\n")
    go_lists(phone)
    print(f"{len(keys)} lists")
    print("\n".join(summary))


def compare(a, b):
    def load(folder):
        with open(os.path.join(folder, "lists.json")) as f:
            return [(e["name"], e["items"]) for e in json.load(f)]

    before, after = load(a), load(b)
    print(f"{len(before)} lists, then {len(after)}")
    same = True
    for i in range(max(len(before), len(after))):
        if i >= len(before) or i >= len(after):
            print(f"list {i + 1}: only in {'the second' if i >= len(before) else 'the first'}")
            same = False
            continue
        (name_a, items_a), (name_b, items_b) = before[i], after[i]
        diffs = []
        if name_a != name_b:
            diffs.append("name differs")
        if items_a != items_b:
            diffs.append(f"items differ ({len(items_a)} then {len(items_b)})")
        if diffs:
            same = False
            print(f"list {i + 1}: " + ", ".join(diffs))
    print("identical: same lists, order, names, items, quantities and ticks" if same else "NOT identical")
    sys.exit(0 if same else 1)


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "backup":
        backup(sys.argv[2], sys.argv[3])
    elif len(sys.argv) == 4 and sys.argv[1] == "compare":
        compare(sys.argv[2], sys.argv[3])
    else:
        sys.exit(__doc__)
