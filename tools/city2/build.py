"""Builds the rebuilt cities' content: blocks (blocks.py) and house templates (house.py).

    python3 -I tools/city2/build.py [--only blocks|houses] [--maps OUTDIR]

Houses go to data/mobtalk/structure/city2/house/<series>_s<sections>_f<floors>_<colour>.nbt; /city house sets them down.
--maps writes a top-down slice of every floor of the first house, for a quick look at the plan without the game.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import blocks  # noqa: E402
import house  # noqa: E402

# The city sets down 2..6 sections in every colour; one section only as a sample.
COLOURS = ("grey", "beige", "white", "blue")
HOUSES = [("1-464", 1, 5, "grey")] + [("1-464", s, 5, c) for s in range(2, 7) for c in COLOURS] + \
    [("1-515-9", s, 9, c) for s in range(1, 5) for c in COLOURS] + [("1-335", s, 5, c) for s in range(1, 5) for c in COLOURS] + \
    [("i-209a", s, 14, c) for s in range(1, 3) for c in COLOURS]


def build_houses(maps=None):
    out = os.path.join(blocks.DATA, "structure", "city2", "house")
    for series, sections, floors, colour in HOUSES:
        h = house.House(series, sections, floors, colour)
        t = h.build()
        t.save(os.path.join(out, f"{series}_s{sections}_f{floors}_{colour}.nbt"))
        print(f"house {series} s{sections} f{floors} {colour}: {t.size}, {len(h.flats)} flats")
        if maps and (series, sections, floors, colour) == ("1-464", 4, 5, "grey"):
            import preview
            preview.slices(t, maps)


def main():
    args = sys.argv[1:]
    only = args[args.index("--only") + 1] if "--only" in args else None
    maps = args[args.index("--maps") + 1] if "--maps" in args else None
    if only in (None, "blocks"):
        blocks.main()
    if only in (None, "houses"):
        build_houses(maps)


if __name__ == "__main__":
    main()
