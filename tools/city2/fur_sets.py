"""Finish sets of the flats' rooms (fur_variants.py makes the variants, this says which go together).

A set is a palette over the variant families: every piece of the room takes the tag its family has in the set (no entry or None:
the piece stays as it was drawn). One set per room, chosen by the flat's seed, so a kitchen is never half mint and half grey,
and a room never mixes woods. Rooms are independent of each other. Kinds without sets (hall, bath, wc, pantry) keep the base.

    python fur_sets.py OUT.json     # the review rows for the M2 stand: one row of a room's pieces per set
"""

import json
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# family -> tag; "curtain" -> the apt_curtain_<tag> of the room's windows. An empty set is the base look.
SETS = {
    "kitchen": {
        "base": {},
        "mint": {
            "kitchen": "mint",
            "kitwood": "whitewash",
            "cloth": "green",
            "stool": "white",
            "wood": "birch",
            "curtain": "sand",
        },
        "butter": {
            "kitchen": "butter",
            "kitwood": "oak",
            "cloth": "yellow",
            "stool": "red",
            "curtain": "olive",
        },
        "sky": {
            "kitchen": "sky",
            "kitwood": "whitewash",
            "cloth": "blue",
            "stool": "white",
            "wood": "birch",
            "curtain": "blue",
        },
        "grey": {
            "kitchen": "grey",
            "kitwood": "oak",
            "stool": "red",
            "wood": "mahogany",
            "curtain": "wine",
        },
    },
    "living": {
        "base": {},
        "birch_mustard": {"wood": "birch", "fabric": "mustard", "curtain": "sand"},
        "mahogany_grey": {"wood": "mahogany", "fabric": "grey", "curtain": "blue"},
        "wenge_teal": {"wood": "wenge", "fabric": "teal", "curtain": "sand"},
        "walnut_plum": {"fabric": "plum", "curtain": "gold"},
        "birch_rust": {"wood": "birch", "fabric": "rust", "curtain": "olive"},
    },
    "bedroom": {
        "base": {},
        "birch_blue": {
            "wood": "birch",
            "blanket": "blue",
            "spread": "blue",
            "curtain": "sand",
        },
        "mahogany_green": {
            "wood": "mahogany",
            "blanket": "green",
            "spread": "mint",
            "curtain": "olive",
        },
        "wenge_grey": {
            "wood": "wenge",
            "blanket": "grey",
            "spread": "rose",
            "curtain": "gold",
        },
        "birch_purple": {
            "wood": "birch",
            "blanket": "purple",
            "spread": "rose",
            "curtain": "wine",
        },
    },
    "room": {
        "base": {},
        "birch_green": {
            "wood": "birch",
            "blanket": "green",
            "spread": "mint",
            "curtain": "gold",
        },
        "mahogany_blue": {
            "wood": "mahogany",
            "blanket": "blue",
            "spread": "blue",
            "curtain": "sand",
        },
        "wenge_grey": {
            "wood": "wenge",
            "blanket": "grey",
            "spread": "rose",
            "curtain": "olive",
        },
    },
}

# what the review rows show: a room's pieces, ground ones at the floor, wall ones on the wall (kind of the piece decides)
MOCK = {
    "kitchen-white": ("kitchen", ["apt_kitchen_sink_white", "apt_kitchen_base_white", "apt_kitchen_jars_white", "apt_wall_cupboard_white",
                                  "apt_kitchen_table", "apt_chair", "apt_stool", "apt_wall_clock", "apt_curtain"]),
    "kitchen-wood": ("kitchen", ["apt_kitchen_sink_wood", "apt_kitchen_base_wood", "apt_kitchen_jars_wood", "apt_wall_cupboard_wood",
                                 "apt_kitchen_table", "apt_chair", "apt_stool", "apt_wall_clock", "apt_curtain"]),
    "living": ("living", ["apt_sofa_red", "apt_armchair_red", "apt_wall_unit", "apt_sideboard", "apt_coffee_table", "apt_bookcase",
                          "apt_chair", "apt_wall_clock", "apt_curtain"]),
    "bedroom": ("bedroom", ["apt_bed", "apt_bed_iron", "apt_wardrobe", "apt_dressing_table", "apt_wall_clock", "apt_curtain"]),
    "room": ("room", ["apt_bed", "apt_desk", "apt_chair", "apt_bookcase_tall", "apt_wardrobe", "apt_curtain"]),
}

def pick(fin, letter, kind):
    """The set (a palette dict) of a room, or None when its kind has none. Seeded by the flat and the room."""
    names = SETS.get(kind)
    if not names:
        return None
    name = random.Random(f"{fin}:{letter}:{kind}:set").choice(sorted(names))
    return SETS[kind][name]


def resolve(pid, pal, variants):
    """The id of `pid` in the palette: a variant of it, or `pid` itself."""
    if not pal:
        return pid
    base = variants["aliases"].get(pid, pid)
    ent = variants["bases"].get(base)
    if not ent:
        return pid
    for fam in sorted(ent):
        parts = fam.split("+")
        if len(parts) > 1 and all(pal.get(p) for p in parts):
            tag = "_".join(pal[p] for p in parts)
            if tag in ent[fam]:
                return ent[fam][tag]
    for fam in sorted(ent):
        if "+" in fam:
            continue
        tag = pal.get(fam)
        if tag and tag in ent[fam]:
            return ent[fam][tag]
    return pid


def curtain(pal, default):
    return f"apt_curtain_{pal['curtain']}" if pal and pal.get("curtain") else default


def rows():
    with open(os.path.join(HERE, "variants.json"), encoding="utf-8") as f:
        variants = json.load(f)
    out = []
    for label, (kind, pieces) in MOCK.items():
        for name, pal in SETS[kind].items():
            ids = [curtain(pal, "apt_curtain_wine") if pid == "apt_curtain" else resolve(pid, pal, variants) for pid in pieces]
            out.append({"name": f"{label}-{name}", "ids": ids})
    return out


if __name__ == "__main__":
    with open(sys.argv[1], "w", encoding="utf-8") as f:
        json.dump(rows(), f, ensure_ascii=False, indent=1)
    print(len(rows()), "rows")
