"""Floor plans of the panel-house series, one character a block (spec §3.2). Drawn after the drawings in
/mnt/project-files/cities/plans/ (1-464-006.jpg: typical floor of 1-464A), rounded to whole blocks with 1-block walls.

Legend
    #  bearing wall or facade          -  partition
    W  window (in a facade)             B  balcony door (a window on the ground floor)
    D  room door                        E  flat entrance door (off the landing)
    s  staircase (flights, landings, the well: laid out by house.py)    S  the staircase's facade (entrance, windows)
    letters: rooms. ROOMS maps each letter to (flat, kind); a flat is "L" or "R" of the section.

Facade A (row 0) faces the courtyard and has the entrances; facade B (the last row) faces the street. A section's first
column is the wall it shares with the previous section; the house closes with one more wall column at the end.
"""

SERIES = {}

# 1-464, ordinary section: 28 x 19 (drawn roomier than the drawing, see scale.py). Flat L has four rooms (a living room with
# a balcony on the courtyard, a room with a balcony on the street, a bedroom and a corner room through it), flat R three.
# Kitchens 5x4 with a window, bathrooms 5x3, halls 5x3, rooms 4 x 8. The staircase is 5 wide (two flights of 2, the well
# between) and 8 deep: a half-landing of 3 rows by facade A (its window, the entrance under the first one), the flights,
# the floor landing of 2 rows with the flats' doors. "S" marks the staircase's bit of facade A: stairs() lays it out.
SERIES["1-464"] = {
    "plan": [
        "#BWWW##WWW##SSSSS##WWW##WWWB",  # 0  facade A: balconies of a/A, kitchen windows, the staircase
        "#aaaa#kkkkk#sssss#KKKKK#AAAA",  # 1
        "#aaaa#kkkkk#sssss#KKKKK#AAAA",  # 2
        "#aaaa#kkkkk#sssss#KKKKK#AAAA",  # 3
        "#aaaa#kkkkk#sssss#KKKKK#AAAA",  # 4
        "#aaaa#--D--#sssss#--D--#AAAA",  # 5  kitchen doors
        "#aaaa#hhhhh#sssss#HHHHH#AAAA",  # 6
        "#aaaaDhhhhhEsssssEHHHHHDAAAA",  # 7  flat doors off the landing, living-room doors off the halls
        "#aaaa#hhhhh#sssss#HHHHH#AAAA",  # 8
        "##D#####D###########D####D##",  # 9  middle bearing wall: doors to the back rooms and the bathrooms
        "#bbbb#ttttt#ddddd#TTTTT#QQQQ",  # 10
        "#bbbb#ttttt#ddddd#TTTTT#QQQQ",  # 11
        "#bbbb#ttttt#ddddd#TTTTT#QQQQ",  # 12
        "#bbbb#-----#ddddd#-----#QQQQ",  # 13
        "#bbbb#ccccc#ddddd#CCCCC#QQQQ",  # 14
        "#bbbbDcccccDddddd#CCCCCDQQQQ",  # 15 rooms in a row ("проходные")
        "#bbbb#ccccc#ddddd#CCCCC#QQQQ",  # 16
        "#bbbb#ccccc#ddddd#CCCCC#QQQQ",  # 17
        "#BWWW##WWW###WWW###WWW##WWWB",  # 18 facade B
    ],
    "rooms": {
        "a": ("L", "living"), "k": ("L", "kitchen"), "h": ("L", "hall"), "t": ("L", "bath"), "b": ("L", "room"),
        "c": ("L", "bedroom"), "d": ("L", "room"),
        "A": ("R", "living"), "K": ("R", "kitchen"), "H": ("R", "hall"), "T": ("R", "bath"), "Q": ("R", "room"),
        "C": ("R", "bedroom"),
    },
    "stair": (12, 1),  # first column and first row of the staircase (5 wide, 8 deep)
}


def check():
    for name, s in SERIES.items():
        widths = {len(r) for r in s["plan"]}
        assert len(widths) == 1, (name, widths)
        chars = set("".join(s["plan"]))
        unknown = chars - set("#-WBDEsS") - set(s["rooms"])
        assert not unknown, (name, unknown)


check()
