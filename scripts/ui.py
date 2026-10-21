"""Reads a uiautomator dump, so device.sh can tap things by name instead of by coordinates.

    python3 -I scripts/ui.py find <dump.xml> <label> [nth]   centre of a match, as "x y"
    python3 -I scripts/ui.py find-text <dump.xml> <label> [nth]   the same, visible text first
    python3 -I scripts/ui.py has <dump.xml> <text>           exit 0 if any text or label contains it
    python3 -I scripts/ui.py read <dump.xml>                 every visible text and label, in order
    python3 -I scripts/ui.py screen <dump.xml>               screen size, as "width height"

Called by device.sh, not meant to be run by hand. Standard library only.

A label is an element's text or its accessibility label (content-desc). The app names its
controls for TalkBack, so nearly everything can be found this way: a checkbox by its item's
name, the pencil as "Edit item", a drag handle as "Move Jam". Each element's attributes are
read on their own, because an empty `text=""` routinely sits in front of the label that matters.
"""

import re
import sys
import xml.etree.ElementTree as ElementTree


def nodes(path):
    """Every element in the dump that is on screen, as (text, label, (x1, y1, x2, y2))."""
    out = []
    for node in ElementTree.parse(path).iter("node"):
        bounds = [int(n) for n in re.findall(r"\d+", node.get("bounds", ""))]
        if len(bounds) != 4 or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
            continue
        out.append((node.get("text", ""), node.get("content-desc", ""), tuple(bounds)))
    return out


def find(path, label, nth=1, text_first=False):
    """
    The nth element whose text or label is exactly [label], or failing any, the nth whose text or
    label contains it. Exact first, so "Save" never lands on "Saved on this phone."

    [text_first] prefers visible text over accessibility labels. A long press needs the row, and an
    item's checkbox carries the item's name as its label and comes first, so it would be pressed
    instead — which ticks the item.
    """
    all_nodes = nodes(path)
    if text_first:
        tiers = [
            [b for t, _, b in all_nodes if t == label],
            [b for _, d, b in all_nodes if d == label],
            [b for t, _, b in all_nodes if label in t],
            [b for _, d, b in all_nodes if label in d],
        ]
    else:
        tiers = [
            [b for t, d, b in all_nodes if label in (t, d)],
            [b for t, d, b in all_nodes if label in t or label in d],
        ]
    matches = next((tier for tier in tiers if tier), [])
    if len(matches) < nth:
        return None
    x1, y1, x2, y2 = matches[nth - 1]
    return (x1 + x2) // 2, (y1 + y2) // 2


def main():
    command, path = sys.argv[1], sys.argv[2]
    if command in ("find", "find-text"):
        at = find(path, sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 1, text_first=command == "find-text")
        if at is None:
            sys.exit(1)
        print(*at)
    elif command == "has":
        needle = sys.argv[3]
        sys.exit(0 if any(needle in t or needle in d for t, d, _ in nodes(path)) else 1)
    elif command == "read":
        last = None
        for text, label, _ in nodes(path):
            for value in (text, label):
                if value and value != last:
                    print(value)
                    last = value
    elif command == "screen":
        widths = [b[2] for _, _, b in nodes(path)]
        heights = [b[3] for _, _, b in nodes(path)]
        print(max(widths), max(heights))
    else:
        sys.exit(f"unknown command {command!r}")


main()
