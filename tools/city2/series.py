"""Floor plans of the panel-house series, one character a block (spec §3.2). Drawn after the drawings in
/mnt/project-files/cities/plans/ (1-464-006.jpg: typical floor of 1-464A), rounded to whole blocks with 1-block walls.

Legend
    #  bearing wall or facade          -  partition
    W  window (in a facade)             B  balcony door (a window on the ground floor)
    D  room door                        E  flat entrance door (off the landing)
    s  staircase (flights, landings, the well: laid out by house.py)
    letters: rooms. ROOMS maps each letter to (flat, kind); a flat is "L" or "R" of the section.

Facade A (row 0) faces the courtyard and has the entrances; facade B (the last row) faces the street. A section's first
column is the wall it shares with the previous section; the house closes with one more wall column at the end.
"""

SERIES = {}

# 1-464, ordinary section: 20 x 16. Flat L has four rooms (a living room with a balcony on the courtyard, a room with a
# balcony on the street, a small bedroom and a corner room through it), flat R three. Kitchens 3x3 with a window,
# combined bathroom 3x2, hall 3x3. Rooms 3 x 6-7 m (the drawing's 3.05 x 5.55).
SERIES["1-464"] = {
    "plan": [
        "#BWW##WW#####WW##WWB",  # 0  facade A: balconies of a/A, kitchen windows, the stairwell (its windows are offset)
        "#aaa#kkk#sss#KKK#AAA",  # 1
        "#aaa#kkk#sss#KKK#AAA",  # 2
        "#aaa#kkk#sss#KKK#AAA",  # 3
        "#aaa#-D-#sss#-D-#AAA",  # 4  kitchen doors
        "#aaaDhhh#sss#HHHDAAA",  # 5  living-room doors off the halls
        "#aaa#hhh#sss#HHH#AAA",  # 6
        "#aaa#hhhEsssEHHH#AAA",  # 7  flat doors off the landing
        "##D###D#######D###D#",  # 8  middle bearing wall: doors to the back rooms and the bathrooms
        "#bbb#ttt#ddd#TTT#QQQ",  # 9
        "#bbb#ttt#ddd#TTT#QQQ",  # 10
        "#bbb#---#ddd#---#QQQ",  # 11
        "#bbb#ccc#ddd#CCC#QQQ",  # 12
        "#bbbDcccDddd#CCCDQQQ",  # 13 rooms in a row ("проходные")
        "#bbb#ccc#ddd#CCC#QQQ",  # 14
        "#BWW##WW##WW#WW##WWB",  # 15 facade B
    ],
    "rooms": {
        "a": ("L", "living"), "k": ("L", "kitchen"), "h": ("L", "hall"), "t": ("L", "bath"), "b": ("L", "room"),
        "c": ("L", "bedroom"), "d": ("L", "room"),
        "A": ("R", "living"), "K": ("R", "kitchen"), "H": ("R", "hall"), "T": ("R", "bath"), "Q": ("R", "room"),
        "C": ("R", "bedroom"),
    },
    "stair": (9, 1),  # first column and first row of the staircase (3 wide: flight up, the well, flight down; 7 deep)
}


def check():
    for name, s in SERIES.items():
        widths = {len(r) for r in s["plan"]}
        assert len(widths) == 1, (name, widths)
        chars = set("".join(s["plan"]))
        unknown = chars - set("#-WBDEs") - set(s["rooms"])
        assert not unknown, (name, unknown)


check()
