"""Builds the rebuilt cities' content: blocks (blocks.py) and house templates (house.py).

    python3 -I tools/city2/build.py [--only blocks|houses] [--maps OUTDIR]

Houses go to data/mobtalk/structure/city2/house/<series>_s<sections>_f<floors>_<colour>.nbt; /city house sets them down.
L-shaped houses are <series>-l, courtyard blocks <series>-yard (compose.py).
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
BRICKS = ("brick", "silicate")     # the brick houses: red or white (silicate) brick
HOUSES = [("1-464", 1, 5, "grey")] + [("1-464", s, 5, c) for s in range(2, 7) for c in COLOURS] + \
    [("1-515-9", s, 9, c) for s in range(1, 5) for c in COLOURS] + [("1-335", s, 5, c) for s in range(1, 5) for c in COLOURS] + \
    [("i-209a", s, 14, c) for s in range(1, 3) for c in COLOURS] + \
    [("p-44", s, 17, c) for s in range(1, 4) for c in COLOURS] + \
    [("1-447", s, 5, c) for s in range(1, 5) for c in BRICKS] + \
    [("ii-03", s, f, c) for s in range(1, 4) for f in (3, 4) for c in BRICKS]
# houses of several blocks (compose.py): L-shaped (wings of `sections` each round a corner block), courtyard blocks
# (four corners, wings of `sections` between them; 0: a well 16 x 16)
COMPOSED = [("1-447-l", s, 5, c) for s in range(1, 4) for c in BRICKS] + \
    [("ii-03-l", s, f, c) for s in range(1, 3) for f in (3, 4) for c in BRICKS] + \
    [("ii-03-yard", s, f, c) for s in range(0, 2) for f in (4, 5) for c in BRICKS]
PUBLIC_COLOURS = ("silicate", "brick", "white")    # the school, the kindergarten, the polyclinic


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
    import compose
    for name, sections, floors, colour in COMPOSED:
        base, kind = name.rsplit("-", 1)
        t, flats = (compose.l_house if kind == "l" else compose.yard_house)(base, sections, floors, colour)
        t.save(os.path.join(out, f"{name}_s{sections}_f{floors}_{colour}.nbt"))
        print(f"house {name} s{sections} f{floors} {colour}: {t.size}, {len(flats)} flats")
    import public
    import civic
    for colour in PUBLIC_COLOURS:      # the public buildings: /city house "school" 1 3 <colour>
        t, _ = civic.school(colour)
        t.save(os.path.join(out, f"school_s1_f3_{colour}.nbt"))
        print(f"school {colour}: {t.size}")
        t, _ = civic.kindergarten(colour)
        t.save(os.path.join(out, f"kindergarten_s1_f2_{colour}.nbt"))
        print(f"kindergarten {colour}: {t.size}")
        t, _ = civic.polyclinic(colour)
        t.save(os.path.join(out, f"polyclinic_s1_f5_{colour}.nbt"))
        print(f"polyclinic {colour}: {t.size}")


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
