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
    "vents": [(8, 9), (20, 9)],
    "floors": 5,
}


def mirrored(left, centre, letters):
    """A section drawn by its left half: the right half is the left one mirrored, its room letters renamed. The mirrored
    half ends before its outer wall: that is the next section's first column (or the wall that closes the house)."""
    out = []
    for l, c in zip(left, centre):
        out.append(l + c + "".join(letters.get(ch, ch) for ch in reversed(l[1:])))
    return out


# 1-515/9, ordinary section (1-515-2-024.jpg: 4 flats on a landing, the lift by the staircase, the garbage chute on the
# half-landings). 42 x 22: four 2-room flats round a core of the staircase (5 x 8 as in 1-464, by facade A, the entrance
# under the first half-landing), a lift hall behind the floor landing and the lift's shaft "Z" (2 x 2) behind the hall's
# back wall, where lift.place sets the doors. Each flat: a corner living room with a balcony, a kitchen and a room on the
# facade, a hall along the core, a bathroom and (the front flats) a pantry in the middle band.
_515_LEFT = [
    "#BWWWW###WW###WWW#",  # 0  facade A
    "#aaaaaa#kkkk#bbbbb",  # 1
    "#aaaaaa#kkkk#bbbbb",  # 2
    "#aaaaaa#kkkk#bbbbb",  # 3
    "#aaaaaa#kkkk#bbbbb",  # 4
    "#aaaaaa#kkkk#bbbbb",  # 5
    "#aaaaaa##D####D###",  # 6  doors to the kitchen and the room
    "#aaaaaaDhhhhhhhhhh",  # 7  the flat's door off the floor landing
    "#aaaaaa#hhhhhhhhhh",  # 8
    "###D#####D########",  # 9  the pantry off the living room, the bathroom off the hall
    "#pppppp#tttt#vvvvv",  # 10 the middle band: pantry, the front flat's bathroom, the back flat's
    "#pppppp#tttt#vvvvv",  # 11
    "###############D##",  # 12
    "#eeeeee#gggggggggg",  # 13 the back flat's door off the lift hall
    "#eeeeeeDgggggggggg",  # 14
    "#eeeeee##D####D###",  # 15
    "#eeeeee#mmmm#ddddd",  # 16
    "#eeeeee#mmmm#ddddd",  # 17
    "#eeeeee#mmmm#ddddd",  # 18
    "#eeeeee#mmmm#ddddd",  # 19
    "#eeeeee#mmmm#ddddd",  # 20
    "#BWWWW###WW###WWW#",  # 21 facade B
]
_515_CORE = [
    "#SSSSS#",   # 0  the staircase's window and entrance
    "#sssss#", "#sssss#", "#sssss#", "#sssss#", "#sssss#", "#sssss#",
    "EsssssE",   # 7  the floor landing: the front flats' doors
    "#sssss#",   # 8
    "#sssss#", "#sssss#", "#sssss#", "#sssss#",   # 9..12 the lift hall
    "EsssssE",   # 13 the back flats' doors
    "#sssss#",   # 14
    "#######",   # 15 the lift's doors go into this wall
    "##ZZ###", "##ZZ###",   # 16, 17 the shaft
    "#######", "#######", "#######", "#######",
]
_515_R = dict(zip("akbhtpemdgv", "norijuxyzfc"))
SERIES["1-515-9"] = {
    "plan": mirrored(_515_LEFT, _515_CORE, _515_R),
    "rooms": {
        "a": ("L", "living"), "k": ("L", "kitchen"), "b": ("L", "bedroom"), "h": ("L", "hall"), "t": ("L", "bath"),
        "p": ("L", "pantry"),
        "e": ("L2", "living"), "m": ("L2", "kitchen"), "d": ("L2", "room"), "g": ("L2", "hall"), "v": ("L2", "bath"),
        "n": ("R", "living"), "o": ("R", "kitchen"), "r": ("R", "room"), "i": ("R", "hall"), "j": ("R", "bath"),
        "u": ("R", "pantry"),
        "x": ("R2", "living"), "y": ("R2", "kitchen"), "z": ("R2", "bedroom"), "f": ("R2", "hall"), "c": ("R2", "bath"),
    },
    "stair": (19, 1),
    "lift": (20, 16, "north"),    # the shaft's corner and where its doors look
    "vents": [(9, 10), (15, 10), (27, 10), (33, 10)],
    "floors": 9,
}


def check():
    for name, s in SERIES.items():
        widths = {len(r) for r in s["plan"]}
        assert len(widths) == 1, (name, widths)
        chars = set("".join(s["plan"]))
        unknown = chars - set("#-WBDEsSZ") - set(s["rooms"])
        assert not unknown, (name, unknown)


check()
