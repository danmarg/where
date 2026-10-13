"""Prints the "x y" centre of a button in one friend's row of the friends sheet.

Usage: friend-row-button.py <maestro hierarchy JSON> <friend name> <button content description>

Maestro's relative selectors (rightOf etc.) don't tie a row's icon buttons to the name in that
row, so pick the button by geometry: the friend's row starts at its name, and the row's buttons
are vertically centred in it, so they're the nearest ones whose centre is below the name's top.
"""
import json
import re
import sys

path, friend, button = sys.argv[1:4]
text = open(path).read()
tree = json.loads(text[text.index("{"):])


def nodes(n):
    yield n
    for c in n.get("children") or []:
        yield from nodes(c)


def bounds(n):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", (n.get("attributes") or {}).get("bounds", ""))
    return tuple(map(int, m.groups())) if m else None


def label(n, key):
    return (n.get("attributes") or {}).get(key) or ""


names = [
    b for n in nodes(tree)
    if (b := bounds(n)) and friend in label(n, "text").splitlines()
]
buttons = [b for n in nodes(tree) if (b := bounds(n)) and label(n, "accessibilityText") == button]
if len(names) != 1 or not buttons:
    sys.exit(f"{path}: {len(names)} rows named {friend!r}, {len(buttons)} {button!r} buttons")
top = names[0][1]
below = [b for b in buttons if (b[1] + b[3]) / 2 > top]
if not below:
    sys.exit(f"{path}: no {button!r} button in {friend!r}'s row")
x1, y1, x2, y2 = min(below, key=lambda b: (b[1] + b[3]) / 2 - top)
# A row cut off at the bottom of the screen still lists its buttons, clipped to a sliver.
if y2 - y1 < (x2 - x1) * 3 // 4:
    sys.exit(f"{path}: {friend!r}'s {button!r} button is cut off at {[x1, y1, x2, y2]}")
print((x1 + x2) // 2, (y1 + y2) // 2)
