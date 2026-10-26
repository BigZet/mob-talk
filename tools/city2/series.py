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


# 1-335, ordinary section (the frame-panel khrushchyovka): 38 x 21, five storeys, four small flats round the staircase -
# the front ones a 2-room (a living room with a balcony on the courtyard, a small bedroom, the kitchen) and the back ones a
# 2-room with the balcony on the street; combined bathrooms, a pantry off the front living room, no lift. The staircase is
# the 1-464 one, its window a strip of glass blocks up the whole facade (the series' mark), the floor landing opens into a
# small hall with the back flats' doors.
_335_LEFT = [
    "#BWWW##WW###WW#",   # 0  facade A
    "#aaaa#kkkk#bbbb",   # 1
    "#aaaa#kkkk#bbbb",   # 2
    "#aaaa#kkkk#bbbb",   # 3
    "#aaaa#kkkk#bbbb",   # 4
    "#aaaa#kkkk#bbbb",   # 5
    "#aaaa##D####D##",   # 6
    "#aaaaDhhhhhhhhh",   # 7  the front flat's door off the floor landing
    "#aaaa#hhhhhhhhh",   # 8
    "##D#####D######",   # 9  the pantry off the living room, the bathroom off the hall
    "#pppp#tttt#vvvv",   # 10
    "#pppp#tttt#vvvv",   # 11
    "############D##",   # 12
    "#eeee#ggggggggg",   # 13 the back flat's door off the hall behind the landing
    "#eeeeDggggggggg",   # 14
    "#eeee##D####D##",   # 15
    "#eeee#mmmm#dddd",   # 16
    "#eeee#mmmm#dddd",   # 17
    "#eeee#mmmm#dddd",   # 18
    "#eeee#mmmm#dddd",   # 19
    "#BWWW##WW###WW#",   # 20 facade B
]
_335_CORE = [
    "#SSSSS#",
    "#sssss#", "#sssss#", "#sssss#", "#sssss#", "#sssss#", "#sssss#",
    "EsssssE",   # 7
    "#sssss#",   # 8
    "#sssss#", "#sssss#", "#sssss#", "#sssss#",   # 9..12 the hall behind the landing
    "EsssssE",   # 13
    "#sssss#",   # 14
    "#######", "#######", "#######", "#######", "#######", "#######",
]
SERIES["1-335"] = {
    "plan": mirrored(_335_LEFT, _335_CORE, dict(zip("akbhtpemdgv", "norijuxyzfc"))),
    "rooms": {
        "a": ("L", "living"), "k": ("L", "kitchen"), "b": ("L", "bedroom"), "h": ("L", "hall"), "t": ("L", "bath"),
        "p": ("L", "pantry"),
        "e": ("L2", "living"), "m": ("L2", "kitchen"), "d": ("L2", "room"), "g": ("L2", "hall"), "v": ("L2", "bath"),
        "n": ("R", "living"), "o": ("R", "kitchen"), "r": ("R", "room"), "i": ("R", "hall"), "j": ("R", "bath"),
        "u": ("R", "pantry"),
        "x": ("R2", "living"), "y": ("R2", "kitchen"), "z": ("R2", "bedroom"), "f": ("R2", "hall"), "c": ("R2", "bath"),
    },
    "stair": (16, 1),
    "hall_lamp": (18, 11),        # the hall behind the landing: a lamp under its ceiling
    "stair_glass": True,          # the staircase window: glass blocks the height of the house
    "vents": [(8, 10), (13, 10), (24, 10), (29, 10)],
    "floors": 5,
}


# И-209А (i-209a), the Moscow 14-storey block tower (gsps.ru i209a-001: typical floor; 1970-84, keramzite blocks): one
# entrance, seven flats on a floor off a corridor through the middle of the house - a 3-room flat over the west end, 2-room
# ones in the east corners (two rooms each along the east end wall, windows in it) and two on the north side, two 1-room
# ones either side of the staircase on the south side; two lifts behind the corridor opposite the staircase, the garbage
# chute on the half-landings. Drawn turned round (the staircase on facade A) and roomy: 56 x 22 for the drawing's 30 x 14.5.
# Rooms entered through another one ("1" off "2" in the 3-room flat, the north flat's small room) are as in the drawing.
SERIES["i-209a"] = {
    "plan": [
        "#BWWW######WW###WW##BWWW##SSSSS##WWWB##WW###WW######WWWB",  # 0  facade A
        "#aaaaa#hh#kkkk#oooo#qqqqq#sssss#uuuuu#xxxx#AAAA#HH#FFFFF",  # 1
        "#aaaaa#hhDkkkk#oooo#qqqqq#sssss#uuuuu#xxxx#AAAADHH#FFFFF",  # 2
        "#aaaaaDhh#kkkk#oooo#qqqqq#sssss#uuuuu#xxxx#AAAA#HHDFFFFF",  # 3
        "#aaaaa#hh#kkkk#-D--#qqqqq#sssss#uuuuu#--D-#AAAA#HH#FFFFF",  # 4
        "#aaaaa#hh#----#ppppDqqqqq#sssss#uuuuuDwwww#----#HH#FFFFF",  # 5
        "#-----#hh#tttt#pppp#-----#sssss#-----#wwww#CCCC#HH#-----",  # 6
        "#bbbbb#hhDtttt#ppppDrrrrr#sssss#vvvvvDwwww#CCCCDHH#GGGGG",  # 7
        "WbbbbbDhh#tttt#pppp#rrrrr#sssss#vvvvv#wwww#CCCC#HH#GGGGG",  # 8
        "Wbbbbb##E#######E#########sssss#########E#######HHDGGGGG",  # 9  the south flats' doors; the landing opens
        "#bbbbb#ssssssssssssssssssssssssssssssssssssssssEHH#GGGGG",  # 10 the corridor, common like the stairs
        "#######ssssssssssssssssssssssssssssssssssssssss#HH#GGGGG",  # 11
        "#ccccc##E#############E##########E##############HH#GGGGG",  # 12 the north flats' doors, the lifts' doors
        "WcccccDii#mmmm#KKKKKDMMMM#ZZ#ZZ#QQQQDTTTTT#VVVV#HH#-----",  # 13 the two lift shafts
        "Wccccc#iiDmmmm#KKKKK#MMMM#ZZ#ZZ#QQQQ#TTTTT#VVVV#HHDJJJJJ",  # 14
        "#ccccc#ii#mmmm#-----#MMMM#######QQQQ#-----#VVVV#D-#JJJJJ",  # 15
        "#-----#ii#----#LLLLLDMMMMDOOOOO#QQQQDUUUUU#VVVV#PP#JJJJJ",  # 16
        "#eeeee#ii#nnnn#LLLLL#-D--#OOOOO#-D--#UUUUU#VVVV#PP#JJJJJ",  # 17
        "#eeeeeDiiDnnnn#LLLLL#NNNN#OOOOO#RRRR#UUUUUDVVVV#PP#JJJJJ",  # 18
        "#eeeee#ii#nnnn#LLLLL#NNNN#OOOOO#RRRR#UUUUU#VVVV#PP#JJJJJ",  # 19
        "#eeeee#ii#nnnn#LLLLL#NNNN#OOOOO#RRRR#UUUUU#VVVV#PP#JJJJJ",  # 20
        "#BWWW######WW###WWWB##WW###WWW###WW###BWWW##WW######WWW#",  # 21 facade B
    ],
    "end": "##WWW###WWWW####WWW###",   # the closing wall (west end): windows of the 3-room flat's rooms, the series' mark
    "rooms": {
        "a": ("SE", "living"), "b": ("SE", "bedroom"), "h": ("SE", "hall"), "k": ("SE", "kitchen"), "t": ("SE", "bath"),
        "c": ("NE", "bedroom"), "e": ("NE", "living"), "i": ("NE", "hall"), "m": ("NE", "bath"), "n": ("NE", "kitchen"),
        "o": ("S2", "kitchen"), "p": ("S2", "hall"), "q": ("S2", "living"), "r": ("S2", "bath"),
        "u": ("S1", "living"), "v": ("S1", "bath"), "w": ("S1", "hall"), "x": ("S1", "kitchen"),
        "A": ("W", "kitchen"), "C": ("W", "bath"), "H": ("W", "hall"), "F": ("W", "bedroom"), "G": ("W", "living"),
        "J": ("W", "room"), "P": ("W", "pantry"),
        "K": ("N2", "bath"), "L": ("N2", "living"), "M": ("N2", "hall"), "N": ("N2", "bedroom"), "O": ("N2", "kitchen"),
        "Q": ("N1", "hall"), "R": ("N1", "kitchen"), "T": ("N1", "bath"), "U": ("N1", "living"), "V": ("N1", "bedroom"),
    },
    "stair": (26, 1),
    "lifts": [(26, 13, "north"), (29, 13, "north")],    # two lifts side by side, the shafts' corners
    "hall_lamp": [(10, 10), (16, 11), (22, 10), (35, 10), (41, 11), (45, 10)],   # along the corridor
    "vents": [(11, 7), (11, 14), (22, 7), (34, 7), (44, 7), (17, 13), (39, 13)],
    "floors": 14,
}


# П-44Т (p-44), the Moscow panel tower of the 90s-2000s (p-44t-25-01.jpg, bti-p-44t-004: a section 24.6 x 17.1 m, four flats
# on a floor, two lifts, loggias): 17 storeys. The core runs through the middle of the section - the staircase by facade A
# (the entrance under its first half-landing), behind the floor landing the lift hall with the back flats' doors, the two
# shafts behind its back wall - with a flat either side of it at the front and at the back: a 2-room and a 3-room at the
# front (the 3-room's corner room entered through the living room, windows in the end wall), two 2-room ones at the back.
# Drawn roomy, 44 x 24, like 1-515/9.
_P44_LEFT = [
    "#BWWWW###WW###WWW#",  # 0  facade A
    "#aaaaaa#kkkk#bbbbb", "#aaaaaa#kkkk#bbbbb", "Waaaaaa#kkkk#bbbbb", "Waaaaaa#kkkk#bbbbb", "#aaaaaa#kkkk#bbbbb", "#aaaaaa#kkkk#bbbbb",
    "#aaaaaa##D####D###",  # 7
    "#aaaaaaDhhhhhhhhhh",  # 8  the flat's door off the floor landing
    "#aaaaaa#hhhhhhhhhh",
    "###D#####D########",  # 10 the pantry off the living room, the bathroom off the hall
    "#pppppp#tttt#vvvvv", "#pppppp#tttt#vvvvv",
    "###############D##",  # 13
    "#eeeeee#gggggggggg",  # 14 the back flat's door off the lift hall
    "#eeeeeeDgggggggggg",
    "#eeeeee##D####D###",  # 16
    "#eeeeee#cccc#mmmmm", "#eeeeee#cccc#mmmmm", "Weeeeee#cccc#mmmmm", "Weeeeee#cccc#mmmmm", "#eeeeee#cccc#mmmmm", "#eeeeee#cccc#mmmmm",
    "#BWWWW###WW###WWW#",  # 23 facade B
]
_P44_CORE = ["##SSSSS##"] + ["##sssss##"] * 7 + ["E" + "s" * 7 + "E"] + ["#sssssss#"] * 5 + \
    ["E" + "s" * 7 + "E", "#sssssss#", "#########", "##ZZ#ZZ##", "##ZZ#ZZ##"] + ["#########"] * 5
_P44_RIGHT = [   # drawn from the house's end inwards, like a left half; turned round below
    "#BWWWW###WW###WWW#",
    "#CCCCCC#KKKK#OOOOO", "#CCCCCC#KKKK#OOOOO", "#CCCCCC#KKKK#OOOOO", "#CCCCCC#KKKK#OOOOO",
    "###D####KKKK#OOOOO",  # 5  the corner room off the living room
    "#LLLLLL#KKKK#OOOOO",
    "#LLLLLL##D####D###",
    "#LLLLLLDHHHHHHHHHH",
    "#LLLLLL#HHHHHHHHHH",
    "#LLLLLL##D########",
    "#LLLLLL#TTTT#VVVVV", "#LLLLLL#TTTT#VVVVV",
    "#LLLLLL#TTTT###D##",
    "#XXXXXX#GGGGGGGGGG",
    "#XXXXXXDGGGGGGGGGG",
    "#XXXXXX##D####D###",
    "#XXXXXX#MMMM#QQQQQ", "#XXXXXX#MMMM#QQQQQ", "#XXXXXX#MMMM#QQQQQ", "#XXXXXX#MMMM#QQQQQ", "#XXXXXX#MMMM#QQQQQ", "#XXXXXX#MMMM#QQQQQ",
    "#BWWWW###WW###WWW#",
]
SERIES["p-44"] = {
    "plan": [l + c + r[1:][::-1] for l, c, r in zip(_P44_LEFT, _P44_CORE, _P44_RIGHT)],
    "end": "##WW####WWWW######WWW###",   # the closing wall: the 3-room flat's corner room and living room, the back living room
    "rooms": {
        "a": ("A", "living"), "k": ("A", "kitchen"), "b": ("A", "bedroom"), "h": ("A", "hall"), "t": ("A", "bath"),
        "p": ("A", "pantry"),
        "e": ("B", "living"), "c": ("B", "bedroom"), "m": ("B", "kitchen"), "g": ("B", "hall"), "v": ("B", "bath"),
        "C": ("C", "bedroom"), "L": ("C", "living"), "O": ("C", "room"), "K": ("C", "kitchen"), "H": ("C", "hall"),
        "T": ("C", "bath"),
        "X": ("D", "living"), "M": ("D", "bedroom"), "Q": ("D", "kitchen"), "G": ("D", "hall"), "V": ("D", "bath"),
    },
    "stair": (20, 1),
    "lifts": [(20, 17, "north"), (23, 17, "north")],
    "hall_lamp": [(22, 10), (22, 14)],
    "vents": [(9, 11), (15, 11), (34, 12), (29, 11)],
    "floors": 17,
}



def _baths(plan, rooms):
    """A vent stack over each bathroom and WC of a section: its cell nearest the middle of the room."""
    out = []
    for letter, (_, kind) in rooms.items():
        cells = [(x, z) for z, row in enumerate(plan) for x, ch in enumerate(row) if ch == letter]
        if kind in ("bath", "wc") and cells:
            cx, cz = sum(c[0] for c in cells) / len(cells), sum(c[1] for c in cells) / len(cells)
            out.append(min(cells, key=lambda c: (c[0] - cx) ** 2 + (c[1] - cz) ** 2))
    return sorted(out)


# 1-447 (the brick khrushchyovka, Giprogor, 1958-1992; gsps.ru 1-447-plan-001, the facade of 1-447-3): five storeys, no
# lift, no chute, combined bathrooms, ceilings 2.48 m, a pitched roof; red or white (silicate) brick. Four flats on the
# floor landing - two on its side walls, two behind its back wall - with walk-through rooms, as in the drawing: at the
# front a 2-room flat either side (the bedroom through the living room, the balcony off it on the courtyard), at the back
# a 2-room one with a pantry (the bedroom through the living room, the balcony on the street) and a 3-room one, its third
# room off the hall in place of the pantry. The back flats' halls start behind the landing's doors and turn along the
# middle of the house. 36 x 22.
_447_LEFT = [
    "##WW##WWBW##WW#",   # 0  facade A
    "#bbbb#aaaaa#kkk", "#bbbb#aaaaa#kkk",
    "#bbbbDaaaaa#kkk",   # 3  the bedroom through the living room
    "#bbbb#aaaaa#kkk", "#bbbb#aaaaa#kkk",
    "#bbbb#aaaaa##D#",   # 6  the kitchen's door
    "#bbbb#aaaaaDhhh",   # 7  the flat's door off the floor landing, the living room off the hall
    "#bbbb#aaaaa#hhh",
    "#############D#",   # 9  the bathroom off the hall
    "#qqqq#vvvvv#ttt", "#qqqq#vvvvv#ttt",
    "#qqqq###D######",   # 12 the back flat's bathroom off its hall
    "#qqqqDggggggggg",   # 13 the hall along the middle; the pantry (the 3-room flat: a room) off it
    "#qqqq#ggggggggg",
    "#######D#####D#",   # 15 the living room and the kitchen off the hall
    "#cccc#eeeee#mmm", "#cccc#eeeee#mmm",
    "#ccccDeeeee#mmm",   # 18 the bedroom through the living room
    "#cccc#eeeee#mmm", "#cccc#eeeee#mmm",
    "##WW##WWBW##WW#",   # 21 facade B
]
_447_CORE = [
    "#SSSSS#",
    "#sssss#", "#sssss#", "#sssss#", "#sssss#", "#sssss#", "#sssss#",
    "EsssssE",           # 7  the front flats' doors
    "#sssss#",
    "##E#E##",           # 9  the back flats' doors in the landing's back wall
    "#gg#ff#", "#gg#ff#", "#gg#ff#",
    "ggg#fff", "ggg#fff",   # 13, 14 the halls turn along the middle of the house
    "#######", "#######", "#######", "#######", "#######", "#######", "#######",
]
_447_RIGHT = [l.translate(str.maketrans("abkhtqvgcem", "nroijuyfxwp")) for l in _447_LEFT]
SERIES["1-447"] = {
    "plan": [l + c + r[1:][::-1] for l, c, r in zip(_447_LEFT, _447_CORE, _447_RIGHT)],
    "rooms": {
        "a": ("L", "living"), "b": ("L", "bedroom"), "k": ("L", "kitchen"), "h": ("L", "hall"), "t": ("L", "bath"),
        "e": ("L2", "living"), "c": ("L2", "bedroom"), "m": ("L2", "kitchen"), "g": ("L2", "hall"), "v": ("L2", "bath"),
        "q": ("L2", "pantry"),
        "n": ("R", "living"), "r": ("R", "bedroom"), "o": ("R", "kitchen"), "i": ("R", "hall"), "j": ("R", "bath"),
        "w": ("R2", "living"), "x": ("R2", "bedroom"), "p": ("R2", "kitchen"), "f": ("R2", "hall"), "y": ("R2", "bath"),
        "u": ("R2", "room"),
    },
    "stair": (16, 1),
    "chute": False,
    "roof": "minecraft:polished_andesite",     # asbestos-cement sheets (шифер)
    "floors": 5,
}
SERIES["1-447"]["vents"] = _baths(SERIES["1-447"]["plan"], SERIES["1-447"]["rooms"])


# II-03 (ii-03), the brick stalinka of the 1950s (gsps.ru ii-03-plan-002, the photo ii-03-foto-100): three to five storeys
# of red or silicate brick, a pitched iron roof, no lift and no chute; ceilings about 3 m - here a storey of five blocks,
# four rows of room; separate bathroom and WC, big kitchens, balconies from the third floor (the second has none), only on
# the street side. Two big flats on a landing: a 4-room one (the living room on the courtyard, a long hall to a room and the
# bedroom at the back, a study through the room) and a 3-room one the same with the room and the study made one. 34 x 22.
_II03_LEFT = [
    "##WW#WW##WWW##",    # 0  facade A
    "#aaaaaa#kkkkkk", "#aaaaaa#kkkkkk", "#aaaaaa#kkkkkk", "#aaaaaa#kkkkkk", "#aaaaaa#kkkkkk",
    "#aaaaaa#####D#",    # 6  the kitchen's door
    "#aaaaaaDhhhhhh",    # 7  the flat's door off the floor landing, the living room off the hall
    "#aaaaaa#hhhhhh",
    "########hhh###",    # 9  the hall turns back along the middle
    "#bbbbbb#hhhDtt",    # 10 the bathroom
    "#bbbbbb#hhh#tt",
    "#bbbbbbDhhh###",    # 12 the room off the hall
    "#bbbbbb#hhhDuu",    # 13 the WC
    "#bbbbbb#hhh#uu",
    "###D#####D####",    # 15 the study through the room, the bedroom off the hall
    "#dddddd#cccccc", "#dddddd#cccccc", "#dddddd#cccccc", "#dddddd#cccccc", "#dddddd#cccccc",
    "##WW#WW##WBW##",    # 21 facade B: the bedroom's balcony
]
_II03_CORE = [
    "#SSSSS#",
    "#sssss#", "#sssss#", "#sssss#", "#sssss#", "#sssss#", "#sssss#",
    "EsssssE",
    "#sssss#",
] + ["#######"] * 13
_II03_RIGHT = [l.translate(str.maketrans("akhtubdc", "noijyrrx")) for l in _II03_LEFT]
_II03_RIGHT[15] = "#rrrrrr##D####"   # the 3-room flat: the room and the study one big room
SERIES["ii-03"] = {
    "plan": [l + c + r[1:][::-1] for l, c, r in zip(_II03_LEFT, _II03_CORE, _II03_RIGHT)],
    "rooms": {
        "a": ("L", "living"), "k": ("L", "kitchen"), "h": ("L", "hall"), "t": ("L", "bath"), "u": ("L", "wc"),
        "b": ("L", "room"), "d": ("L", "room"), "c": ("L", "bedroom"),
        "n": ("R", "living"), "o": ("R", "kitchen"), "i": ("R", "hall"), "j": ("R", "bath"), "y": ("R", "wc"),
        "r": ("R", "room"), "x": ("R", "bedroom"),
    },
    "stair": (15, 1),
    "storey": 5,                  # high ceilings: four rows of room
    "balcony_from": 3,
    "glazed": 0.0,
    "chute": False,
    "roof": "minecraft:waxed_oxidized_cut_copper",   # roofing iron painted green
    "cornice": True,
    "floors": 3,
}
SERIES["ii-03"]["vents"] = _baths(SERIES["ii-03"]["plan"], SERIES["ii-03"]["rooms"])

def check():
    for name, s in SERIES.items():
        widths = {len(r) for r in s["plan"]}
        assert len(widths) == 1, (name, widths)
        chars = set("".join(s["plan"]))
        unknown = chars - set("#-WBDEsSZ") - set(s["rooms"])
        assert not unknown, (name, unknown)
        assert len(s.get("end", "#" * len(s["plan"]))) == len(s["plan"]), name


check()
