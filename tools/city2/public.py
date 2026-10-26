"""Public buildings (spec §3.2, public.py) by the same machine as the houses: a plan per floor in ASCII (series.py's
characters, X a front door), room kinds of their own furnished here, blocks of different height composed (compose.py).

    t, rooms = public.school("silicate")     # a typical Soviet school of the 60s-70s: three floors and a gym
    t, rooms = public.kindergarten("white")  # a kindergarten of two floors for four groups
    t, rooms = public.polyclinic("brick")    # a district polyclinic of three floors

The school: a block 61 x 22 of three floors - a corridor along the north side with the toilets, the staff room and the
library off it, five classrooms 11 x 11 on the south (the light from the pupils' left), the vestibule with the front door
and the cloakroom, the head's office and the doctor's on the ground floor, the canteen and its kitchen in place of two
classrooms there, the assembly hall in place of two on the third floor; the gym block (one storey of seven, 22 x 10 and two
changing rooms) abuts the east end, a pair of doors through the joint from the corridor.
"""
import house as H
import compose
import series as S

_SCHOOL = [
    "#SSSSS##W##W###W##W###W##W##W#####W##W##W##W####W##W###W##W##",   # 0  north: the side entrance, toilets, staff room, library
    "#sssss#bbbbbb#gggggg#ttttttttttt#LLLLLLLLLLLLL#pppppp#uuuuuu#",
    "#sssss#bbbbbb#gggggg#ttttttttttt#LLLLLLLLLLLLL#pppppp#uuuuuu#",
    "#sssss#bbbbbb#gggggg#ttttttttttt#LLLLLLLLLLLLL#pppppp#uuuuuu#",
    "#sssss###D######D#########D############D#########D######D####",   # 4  doors off the corridor
    "#sssss#ccccccccccccccccccccccccccccccccccccccccccccccccccccc#",   # 5  the corridor along the whole block
    "#sssss#ccccccccccccccccccccccccccccccccccccccccccccccccccccc#",
    "#sssssDccccccccccccccccccccccccccccccccccccccccccccccccccccc#",
    "#sssss#ccccccccccccccccccccccccccccccccccccccccccccccccccccc#",
    "########D#########D###########D###########D###########D######",   # 9  the classrooms' doors
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",   # 10 five classrooms 11 x 11, windows south
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "##WWWW#WWWW###WWWW#WWWW###WWWW#WWWW###WWWW#WWWW###WWWW#WWWW##",   # 21 the south side
]
_SCHOOL_GROUND = [
    "#SSSSS##W##W###W##W###WW##XX##WW##W##W##W##W####W##W###W##W##",   # 0  the front door (X X) into the vestibule
    "#sssss#bbbbbb#gggggg#vvvvvvvvvvvvv#rrrrrrrrrrr#dddddd#hhhhhh#",   # 1  vestibule, cloakroom, head, doctor
    "#sssss#bbbbbb#gggggg#vvvvvvvvvvvvvDrrrrrrrrrrr#dddddd#hhhhhh#",
    "#sssss#bbbbbb#gggggg#vvvvvvvvvvvvv#rrrrrrrrrrr#dddddd#hhhhhh#",
    "#sssss###D######D#########DD###########D#########D######D####",
    "#sssss#ccccccccccccccccccccccccccccccccccccccccccccccccccccc#",
    "#sssss#ccccccccccccccccccccccccccccccccccccccccccccccccccccc#",
    "#sssssDcccccccccccccccccccccccccccccccccccccccccccccccccccccD",   # 7  the door to the gym
    "#sssss#ccccccccccccccccccccccccccccccccccccccccccccccccccccc#",
    "########D#########D###########D###########D###########D######",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#eeeeeeeeeee#qqqqqqqqqqq#",   # 10 the canteen and its kitchen
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#eeeeeeeeeee#qqqqqqqqqqq#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#eeeeeeeeeee#qqqqqqqqqqq#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#eeeeeeeeeee#qqqqqqqqqqq#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#eeeeeeeeeee######D######",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#eeeeeeeeeeeeeeeeeeeeeee#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#eeeeeeeeeeeeeeeeeeeeeee#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#eeeeeeeeeeeeeeeeeeeeeee#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#eeeeeeeeeeeeeeeeeeeeeee#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#eeeeeeeeeeeeeeeeeeeeeee#",
    "#kkkkkkkkkkk#lllllllllll#mmmmmmmmmmm#eeeeeeeeeeeeeeeeeeeeeee#",
    "##WWWW#WWWW###WWWW#WWWW###WWWW#WWWW###WWWW#WWWW###WWWW#WWWW##",
]
_SCHOOL_THIRD = [
    "#SSSSS##W##W###W##W###W##W##W#####W##W##W##W####W##W###W##W##",
    "#sssss#bbbbbb#gggggg#ttttttttttt#LLLLLLLLLLLLL#pppppp#uuuuuu#",
    "#sssss#bbbbbb#gggggg#ttttttttttt#LLLLLLLLLLLLL#pppppp#uuuuuu#",
    "#sssss#bbbbbb#gggggg#ttttttttttt#LLLLLLLLLLLLL#pppppp#uuuuuu#",
    "#sssss###D######D#########D############D#########D######D####",
    "#sssss#ccccccccccccccccccccccccccccccccccccccccccccccccccccc#",
    "#sssss#ccccccccccccccccccccccccccccccccccccccccccccccccccccc#",
    "#sssssDccccccccccccccccccccccccccccccccccccccccccccccccccccc#",
    "#sssss#ccccccccccccccccccccccccccccccccccccccccccccccccccccc#",
    "########D#########D###########D###########D###########D######",
    "#aaaaaaaaaaaaaaaaaaaaaaa#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",   # 10 the assembly hall
    "#aaaaaaaaaaaaaaaaaaaaaaa#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#aaaaaaaaaaaaaaaaaaaaaaa#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#aaaaaaaaaaaaaaaaaaaaaaa#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#aaaaaaaaaaaaaaaaaaaaaaa#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#aaaaaaaaaaaaaaaaaaaaaaa#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#aaaaaaaaaaaaaaaaaaaaaaa#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#aaaaaaaaaaaaaaaaaaaaaaa#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#aaaaaaaaaaaaaaaaaaaaaaa#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#aaaaaaaaaaaaaaaaaaaaaaa#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "#aaaaaaaaaaaaaaaaaaaaaaa#mmmmmmmmmmm#nnnnnnnnnnn#ooooooooooo#",
    "##WWWW#WWWW###WWWW#WWWW###WWWW#WWWW###WWWW#WWWW###WWWW#WWWW##",
]
_GYM = [
    "##WWWWWWW####WWWWWWWW###",   # 0  the gym's tall windows
    "#yyyyyyyyyyyyyyyyyyyyyy#",
    "#yyyyyyyyyyyyyyyyyyyyyyW",
    "#yyyyyyyyyyyyyyyyyyyyyyW",
    "Dyyyyyyyyyyyyyyyyyyyyyy#",   # 4  the door from the school's corridor
    "#yyyyyyyyyyyyyyyyyyyyyy#",
    "#yyyyyyyyyyyyyyyyyyyyyy#",
    "#yyyyyyyyyyyyyyyyyyyyyy#",
    "#yyyyyyyyyyyyyyyyyyyyyyW",
    "#yyyyyyyyyyyyyyyyyyyyyyW",
    "#yyyyyyyyyyyyyyyyyyyyyy#",
    "#####D###########D######",
    "#wwwwwwwwww#xxxxxxxxxxx#",   # 12 the changing rooms
    "#wwwwwwwwww#xxxxxxxxxxx#",
    "#wwwwwwwwww#xxxxxxxxxxx#",
    "###WW##WW#####WW###WW###",
]
# room kinds of the public buildings: the walls' finish, the floor, the ceiling lamps
KINDS = {
    "classroom": ("paint_stair", "floor_parquet"), "corridor": ("paint_stair", "floor_terrazzo"),
    "vestibule": ("paint_stair", "floor_terrazzo"), "cloakroom": ("paint_stair", "floor_terrazzo"),
    "wc_pub": ("tile_bath", "floor_tile"), "staff": ("paper_stripe", "floor_parquet"), "library": ("paper_wood", "floor_parquet"),
    "store": ("plaster", "floor_lino_brown"), "office": ("paper_damask", "floor_parquet"), "medical": ("tile_bath", "floor_lino_beige"),
    "canteen": ("tile_kitchen", "floor_tile"), "kitchen_pub": ("tile_kitchen", "floor_tile"), "assembly": ("paint_stair", "floor_parquet"),
    "gym": ("plaster", "floor_parquet"), "changing": ("paint_stair", "floor_lino_brown"),
}
for _k, (_fin, _floor) in KINDS.items():
    H.FINISH[_k] = _fin
    H.FLOOR[_k] = _floor
    H.LAMPS[_k] = ["pub_tube_lamp"]

_ROOMS = {
    "c": ("S", "corridor"), "k": ("S", "classroom"), "l": ("S", "classroom"), "m": ("S", "classroom"), "n": ("S", "classroom"),
    "o": ("S", "classroom"), "b": ("S", "wc_pub"), "g": ("S", "wc_pub"), "t": ("S", "staff"), "L": ("S", "library"),
    "p": ("S", "store"), "u": ("S", "store"), "v": ("S", "vestibule"), "r": ("S", "cloakroom"), "d": ("S", "office"),
    "h": ("S", "medical"), "e": ("S", "canteen"), "q": ("S", "kitchen_pub"), "a": ("S", "assembly"),
}
S.SERIES["school"] = {
    "plan": _SCHOOL, "floor_plans": {1: _SCHOOL_GROUND, 3: _SCHOOL_THIRD}, "rooms": _ROOMS, "whole": True, "stair": (1, 1),
    "chute": False, "curtains": False, "frame": "pvc", "public": True, "joints": [(60, 3, 60, 18)], "floors": 3,
}
S.SERIES["school-gym"] = {
    "plan": _GYM, "rooms": {"y": ("S", "gym"), "w": ("S", "changing"), "x": ("S", "changing")}, "whole": True, "storey": 7,
    "curtains": False, "frame": "pvc", "public": True, "joints": [(0, 0, 0, 15)], "floors": 1,
}
GYM_AT = 3     # the gym block's north wall is in line with the school's row 3; the doors through the joint at the school's row 7


def school(colour="silicate", seed=0):
    main = H.House("school", 1, 3, colour, seed=seed + 1)
    main.build()
    gym = H.House("school-gym", 1, 1, colour, seed=seed + 2)
    gym.build()
    a, b = compose.Part(main, 0, 0, 0), compose.Part(gym, 0, main.length, GYM_AT)
    t, flats = compose.compose([a, b])
    # the doors through the joint: one in the school's east wall, one in the gym's west wall, a pair of room doors
    x0 = min(x for x, _ in a.foot | b.foot) - compose.scale.MARGIN
    z0 = min(z for _, z in a.foot | b.foot) - compose.scale.MARGIN
    for x, z in ((main.length - 1, 7), (main.length, 7)):
        for n, half in enumerate(("lower", "upper")):
            t.set(x - x0, main.slab(1) + 1 + n, z - z0, "door_room", {"facing": "east", "half": half, "hinge": "left", "open": "true",
                                                                    "powered": "false"})
    return t, flats


# ---------------------------------------------------------------- the kindergarten
# A typical Soviet kindergarten (детский сад) of two floors for four groups, 44 x 22: a corridor along the middle, each
# group's sleeping room on the north (the beds, the toilet and the changing room with the children's lockers by it) and
# its play room on the south in the sun; the staircase, the vestibule with the front door and, behind the corridor, the
# kitchen and the nurse's room in the middle of the ground floor, the methodist's office and the music hall over them.
_KG_WEST = [            # the west group (x 0..15); the east one is its mirror (_kg below)
    "##WW##WW###WW###",   # 0  north: the sleeping room, the toilet
    "#nnnnnnnnn#gggg#",
    "#nnnnnnnnn#gggg#",
    "#nnnnnnnnn#gggg#",
    "Wnnnnnnnnn##D###",   # 4  the toilet off the changing room
    "Wnnnnnnnnn#rrrr#",   # 5  the changing room with the lockers
    "#nnnnnnnnn#rrrr#",
    "#nnnnnnnnn#rrrr#",
    "#nnnnnnnnn#rrrr#",
    "######D#####D###",   # 9  doors to the corridor
    "#ccccccccccccccc",   # 10 the corridor along the whole building
    "Wccccccccccccccc",
    "#ccccccccccccccc",
    "#######D########",   # 13 the play room's door
    "#yyyyyyyyyyyyyy#",   # 14 the play room, windows south and west
    "#yyyyyyyyyyyyyy#",
    "Wyyyyyyyyyyyyyy#",
    "Wyyyyyyyyyyyyyy#",
    "#yyyyyyyyyyyyyy#",
    "#yyyyyyyyyyyyyy#",
    "#yyyyyyyyyyyyyy#",
    "##WWW##WWW##WW##",   # 21 south
]
_KG_MID = {             # the middle (x 16..27): the staircase, the vestibule or the office, the kitchen or the music hall
    1: ["SSSSS###XX##"] + ["sssss#vvvvvv"] * 8 + ["#D######DD##"] + ["cccccccccccc"] * 3 + ["##D######D##"] +
       ["qqqqqq#hhhhh"] * 7 + ["#WW#WW##WW##"],
    2: ["SSSSS###WW##"] + ["sssss#dddddd"] * 8 + ["#D######D###"] + ["cccccccccccc"] * 3 + ["#####D######"] +
       ["aaaaaaaaaaaa"] * 7 + ["#WWW#WW#WWW#"],
}


def _kg(k):
    east = str.maketrans("ngry", "NGRY")
    return [w + m + w[::-1].translate(east) for w, m in zip(_KG_WEST, _KG_MID[k])]


_KG_ROOMS = {"n": ("S", "kid_sleep"), "N": ("S", "kid_sleep"), "g": ("S", "kid_wc"), "G": ("S", "kid_wc"),
             "r": ("S", "kid_lockers"), "R": ("S", "kid_lockers"), "y": ("S", "kid_play"), "Y": ("S", "kid_play"),
             "c": ("S", "kg_corridor"), "v": ("S", "kg_vestibule"), "d": ("S", "office"), "q": ("S", "kitchen_pub"),
             "h": ("S", "medical"), "a": ("S", "music")}
for _k, (_fin, _floor) in {"kid_sleep": ("paper_blue", "floor_lino_beige"), "kid_play": ("paper_green", "floor_parquet"),
                           "kid_lockers": ("paint_stair", "floor_lino_brown"), "kid_wc": ("tile_bath", "floor_tile"),
                           "kg_corridor": ("paint_stair", "floor_lino_brown"), "kg_vestibule": ("paint_stair", "floor_terrazzo"),
                           "music": ("paper_stripe", "floor_parquet")}.items():
    H.FINISH[_k], H.FLOOR[_k] = _fin, _floor
    H.LAMPS[_k] = ["apt_ceiling_plate"] if _k in ("kid_sleep", "kid_play") else ["pub_tube_lamp"]
S.SERIES["kindergarten"] = {
    "plan": _kg(2), "floor_plans": {1: _kg(1)}, "rooms": _KG_ROOMS, "whole": True, "stair": (16, 1), "chute": False,
    "curtains": {"kid_sleep": "apt_curtain_gold", "kid_play": "apt_curtain_olive", "music": "apt_curtain_gold"},
    "frame": "wood", "public": True, "floors": 2,
}


def kindergarten(colour="white", seed=0):
    h = H.House("kindergarten", 1, 2, colour, seed=seed + 5)
    h.build()
    return compose.compose([compose.Part(h, 0, 0, 0)])


# ---------------------------------------------------------------- the polyclinic
# A district polyclinic (поликлиника) of three floors, 49 x 17: a corridor down the middle with the patients waiting along
# it, the doctors' rooms both sides; on the ground floor the vestibule with the front door, the cloakroom, the registry
# with its card index and a chemist's kiosk on the street side. The staircase at the west end.
_CL_NORTH = [(7, 11), (13, 17), (19, 23), (25, 29), (31, 35), (37, 41), (43, 47)]   # the rooms off the corridor's north side
_CL_SOUTH = [(1, 5), (7, 11), (13, 17), (19, 23), (25, 29), (31, 35), (37, 41), (43, 47)]


def _clinic(k):
    """The plan of floor k: rooms north (rows 1..5) and south (rows 11..15) of the corridor (rows 7..9)."""
    L, D = 49, 17
    g = [["#"] * L for _ in range(D)]
    if k == 1:      # the ground floor: the vestibule (two bays), the registry, the cloakroom, the chemist's
        north_rooms = [((7, 17), "v"), ((19, 23), "r"), ((25, 29), "w"), ((31, 35), "f"), ((37, 41), "p"), ((43, 47), "q")]
    else:
        north_rooms = [(span, c) for span, c in zip(_CL_NORTH, "klmnopq")]
    south_rooms = [(span, c) for span, c in zip(_CL_SOUTH, "tuxyzijh")]
    for (x0, x1), c in north_rooms:
        for x in range(x0, x1 + 1):
            for z in range(1, 6):
                g[z][x] = c
        if c == "v":
            g[0][11], g[0][12] = "X", "X"               # the front door in the middle of the vestibule
            for x in (8, 9, 15, 16):
                g[0][x] = "W"
            g[6][11], g[6][12] = "D", "D"
        else:
            g[0][x0 + 1], g[0][x0 + 3] = "W", "W"
            g[0][x0 + 2] = "W"
            g[6][x0 + 2] = "D"
    for (x0, x1), c in south_rooms:
        for x in range(x0, x1 + 1):
            for z in range(11, 16):
                g[z][x] = c
        g[16][x0 + 1], g[16][x0 + 2], g[16][x0 + 3] = "W", "W", "W"
        g[10][x0 + 2] = "D"
    for x in range(7, 48):                               # the corridor, a window at its east end
        for z in range(7, 10):
            g[z][x] = "c"
    g[8][48] = "W"
    g[10][3], g[13][6] = "#", "D"                        # the room under the staircase: through the next one (a treatment room)
    for x in range(1, 6):                                # the staircase, its door off its floor landing to the corridor
        g[0][x] = "S"
        for z in range(1, 9):
            g[z][x] = "s"
    g[8][6] = "D"
    return ["".join(r) for r in g]


_CL_ROOMS = {c: ("S", "doctor") for c in "klmnopqtuxyzijh"} | {
    "c": ("S", "clinic_corridor"), "v": ("S", "clinic_vestibule"), "r": ("S", "registry"), "w": ("S", "cloakroom"),
    "f": ("S", "chemist")}
for _k, (_fin, _floor) in {"doctor": ("tile_bath", "floor_lino_beige"), "clinic_corridor": ("paint_stair", "floor_terrazzo"),
                           "clinic_vestibule": ("paint_stair", "floor_terrazzo"), "registry": ("paint_stair", "floor_lino_brown"),
                           "chemist": ("paint_stair", "floor_tile")}.items():
    H.FINISH[_k], H.FLOOR[_k] = _fin, _floor
    H.LAMPS[_k] = ["pub_tube_lamp"]
S.SERIES["polyclinic"] = {
    "plan": _clinic(2), "floor_plans": {1: _clinic(1)}, "rooms": _CL_ROOMS, "whole": True, "stair": (1, 1), "chute": False,
    "curtains": {"doctor": "apt_curtain_olive"}, "frame": "wood", "public": True, "floors": 3,
}


def polyclinic(colour="white", seed=0):
    h = H.House("polyclinic", 1, 3, colour, seed=seed + 7)
    h.build()
    return compose.compose([compose.Part(h, 0, 0, 0)])


# ---------------------------------------------------------------- furnishing (rooms.py calls these by the room's kind)

def _box(rm):
    xs = [x for x, _ in rm.cells]
    zs = [z for _, z in rm.cells]
    return min(xs), min(zs), max(xs), max(zs)


def _lamps(rm, step=4):
    """More tube lamps under the ceiling in a grid (fixtures put one in the middle)."""
    x0, z0, x1, z1 = _box(rm)
    y = rm.s + rm.h.top
    for x in range(x0 + 2, x1, step):
        for z in range(z0 + 2, z1, step):
            if (x, z) in rm.cells and rm.h.tpl.get(rm.h.ox + x, y, rm.h.oz + z) is None:
                rm.h.at(x, y, z, "pub_tube_lamp", {"facing": "north", "lit": "true"})


def classroom(rm, flat):
    """The blackboard on the west wall (the pupils face it with the windows on their left), the teacher's desk before it,
    three columns of two-seat desks, a bookcase and posters on the back wall."""
    x0, z0, x1, z1 = _box(rm)
    zc = (z0 + z1) // 2
    if rm.fits("pub_blackboard", x0, rm.s + 2, zc, "east"):
        rm.write("pub_blackboard", x0, rm.s + 2, zc, "east")
    if rm.fits("pub_teacher_desk", x0 + 2, rm.s + 1, zc + 1, "west", backed=False):
        rm.write("pub_teacher_desk", x0 + 2, rm.s + 1, zc + 1, "west")
        rm.write("apt_chair", x0 + 1, rm.s + 1, zc + 1, "east")
    for x in range(x0 + 4, x1, 2):
        for z in (z0 + 1, z0 + 4, z0 + 7):
            if z + 1 <= z1 and rm.fits("pub_school_desk", x, rm.s + 1, z, "east", backed=False):
                rm.write("pub_school_desk", x, rm.s + 1, z, "east")
    for pid in rm.r.sample(["pub_poster_table", "pub_poster_abc"], 2):
        for x, z, f in rm.spots():
            if f == "west" and x == x1 and rm.fits(pid, x, rm.s + 2, z, f):
                rm.write(pid, x, rm.s + 2, z, f)
                break
    rm.against("apt_bookcase")
    _lamps(rm)


def corridor(rm, flat):
    if not flat.get("honours"):
        flat["honours"] = rm.against("pub_honours_board", height=1)
    for _ in range(4):
        rm.against("pub_gym_bench")
    for _ in range(3):
        rm.against(rm.r.choice(["apt_ficus", "apt_palm_tub"]))
    _lamps(rm, 5)


def vestibule(rm, flat):
    rm.against("pub_honours_board", height=1)
    flat["honours"] = True
    for _ in range(2):
        rm.against("pub_gym_bench")
    rm.against("apt_palm_tub")
    _lamps(rm)


def cloakroom(rm, flat):
    x0, z0, x1, z1 = _box(rm)
    for _ in range(8):
        if not rm.against("pub_coat_rack_long"):
            break
    _lamps(rm)


def wc_pub(rm, flat):
    for _ in range(4):
        rm.against("apt_toilet")
    for _ in range(2):
        rm.against("apt_washbasin")


def staff(rm, flat):
    rm.table("apt_dining_table", "apt_chair", chairs=4)
    for pid in ("apt_bookcase", "apt_bookcase", "apt_sideboard", "apt_wall_clock"):
        rm.against(pid, height=1 if pid == "apt_wall_clock" else 0)
    rm.against("apt_ficus")
    _lamps(rm)


def library(rm, flat):
    for _ in range(10):
        if not rm.against(rm.r.choice(["apt_bookcase_tall", "apt_bookcase"])):
            break
    if rm.against("apt_desk", name="desk"):
        rm.front("apt_chair", "desk", facing_back=True)
    _lamps(rm)


def office(rm, flat):
    if rm.against("apt_desk", name="desk"):
        rm.front("apt_chair", "desk", facing_back=True)
    for pid in ("apt_bookcase", "apt_sideboard", "apt_wall_phone", "apt_painting_rye"):
        rm.against(pid, height=1 if pid in ("apt_wall_phone", "apt_painting_rye") else 0)


def medical(rm, flat):
    rm.against("apt_bed_iron")
    for pid in ("apt_washbasin", "apt_sideboard"):
        rm.against(pid)
    if rm.against("apt_desk", name="desk"):
        rm.front("apt_chair", "desk", facing_back=True)


def store(rm, flat):
    for _ in range(3):
        rm.against(rm.r.choice(["apt_bookcase", "apt_suitcases", "apt_toy_box"]))


def canteen(rm, flat):
    for _ in range(14):
        if not rm.table("pub_canteen_table", "apt_stool", chairs=4):
            break
    _lamps(rm)


def kitchen_pub(rm, flat):
    rm.against("apt_kitchen_sink_white", name="sink", row=True)
    for pid in ("apt_stove", "apt_stove", "apt_stove_kettle", "apt_kitchen_base_white", "apt_kitchen_base_white", "apt_fridge"):
        rm.run(pid, "sink")


def assembly(rm, flat):
    """Rows of chairs facing west, the piano by the west wall, a gangway down the middle."""
    x0, z0, x1, z1 = _box(rm)
    rm.against("apt_piano")
    zc = (z0 + z1) // 2
    for x in range(x0 + 4, x1, 2):
        for z in range(z0 + 1, z1):
            if abs(z - zc) > 1 and rm.fits("apt_chair", x, rm.s + 1, z, "west", backed=False):
                rm.write("apt_chair", x, rm.s + 1, z, "west")
    _lamps(rm)


def gym(rm, flat):
    """Wall bars along the walls, a basketball hoop high on each end wall, mats and benches."""
    x0, z0, x1, z1 = _box(rm)
    zc = (z0 + z1) // 2
    for x, f in ((x0, "east"), (x1, "west")):
        for z in (zc, zc + 1, zc - 1):
            if rm.fits("pub_basket_hoop", x, rm.s + 3, z, f):
                rm.write("pub_basket_hoop", x, rm.s + 3, z, f)
                break
    for _ in range(8):
        rm.against("pub_wall_bars")
    for _ in range(4):
        rm.against("pub_gym_bench")
    for _ in range(3):
        rm.rug("pub_gym_mat")
    _lamps(rm, 5)


def changing(rm, flat):
    for _ in range(3):
        rm.against("pub_gym_bench")
    for _ in range(3):
        rm.against("apt_coat_rack")


# ---------------------------------------------------------------- the kindergarten's rooms

def kid_sleep(rm, flat):
    """Rows of small beds with a gangway between them, all made up alike in one group's colour."""
    x0, z0, x1, z1 = _box(rm)
    pid = "pub_kid_bed_" + rm.r.choice(["blue", "red", "green", "yellow"])
    for x in range(x0 + 1, x1, 2):
        for z in range(z0 + 1, z1 + 1, 3):
            if rm.fits(pid, x, rm.s + 1, z, "south", backed=False):
                rm.write(pid, x, rm.s + 1, z, "south")
    rm.against("apt_picture_bears", height=1)


def kid_play(rm, flat):
    """The group's tables with the little chairs, the toy shelves and the toy box by the walls, a rocking horse, a rug."""
    for _ in range(4):
        if not rm.table("pub_kid_table", "pub_kid_chair_" + rm.r.choice(["red", "yellow", "green", "blue"]), chairs=4):
            break
    for pid in ("pub_toy_shelf", "pub_toy_shelf", "apt_toy_box", "apt_rocking_horse", "apt_piano", "apt_aloe"):
        rm.against(pid)
    rm.against("apt_picture_bears", height=1)
    rm.rug("apt_rug")


def kid_lockers(rm, flat):
    for _ in range(4):
        if not rm.against("pub_kid_lockers"):
            break
    rm.against("apt_notice_board", height=1)


def kid_wc(rm, flat):
    for _ in range(3):
        rm.against("apt_toilet")
    for _ in range(2):
        rm.against("apt_washbasin")
    rm.against("apt_towel_rail", height=1)


def kg_corridor(rm, flat):
    for _ in range(3):
        rm.against(rm.r.choice(["apt_ficus", "apt_palm_tub", "apt_aloe"]))
    for _ in range(3):
        rm.against("apt_picture_bears", height=1)
    _lamps(rm, 6)


def kg_vestibule(rm, flat):
    rm.against("apt_notice_board", height=1)
    for _ in range(2):
        rm.against("pub_gym_bench")
    rm.against("apt_palm_tub")
    _lamps(rm)


def music(rm, flat):
    """The music hall: the piano, little chairs along the walls, the floor free for the dances."""
    if rm.against("apt_piano", name="piano"):
        rm.front("apt_piano_stool", "piano")
    for _ in range(14):
        if not rm.against("pub_kid_chair_" + rm.r.choice(["red", "yellow", "green", "blue"])):
            break
    _lamps(rm)


# ---------------------------------------------------------------- the polyclinic's rooms

def doctor(rm, flat):
    """A doctor's room: the desk with the doctor's chair and the patient's, the couch behind a screen, the washbasin, the
    medicine cabinet; some rooms the eye chart (the ophthalmologist) or the scales (the paediatrician, the therapist)."""
    if rm.against("apt_desk", name="desk"):
        rm.front("apt_chair", "desk", facing_back=True)
    rm.against("apt_chair")
    if rm.against("pub_couch", name="couch"):
        rm.front("pub_med_screen", "couch", dist=2)
    for pid in ("apt_washbasin", "pub_medicine_cabinet"):
        rm.against(pid)
    extra = rm.r.random()
    if extra < 0.3:
        rm.against("pub_eye_chart", height=1)
    elif extra < 0.6:
        rm.against("pub_med_scales")
    rm.against("apt_wall_clock", height=1)


def clinic_corridor(rm, flat):
    for _ in range(10):
        if not rm.against("pub_waiting_seats"):
            break
    for _ in range(3):
        rm.against(rm.r.choice(["apt_ficus", "apt_palm_tub", "apt_aloe"]))
    rm.against("apt_notice_board", height=1)
    _lamps(rm, 5)


def clinic_vestibule(rm, flat):
    rm.against("apt_notice_board", height=1)
    for _ in range(3):
        rm.against("pub_waiting_seats")
    rm.against("apt_palm_tub")
    _lamps(rm)


def registry(rm, flat):
    """The registry: the counter across the room by its door, the card index behind it, a desk with the telephone."""
    x0, z0, x1, z1 = _box(rm)
    for x in range(x0, x1):
        if rm.fits("pub_registry_counter", x, rm.s + 1, z1 - 1, "south", backed=False):
            rm.write("pub_registry_counter", x, rm.s + 1, z1 - 1, "south")
            break
    for _ in range(4):
        rm.against("pub_card_files")
    rm.against("apt_wall_phone", height=1)
    _lamps(rm)


def chemist(rm, flat):
    for _ in range(4):
        rm.against("pub_medicine_cabinet")
    x0, z0, x1, z1 = _box(rm)
    for x in range(x0, x1):
        if rm.fits("pub_registry_counter", x, rm.s + 1, z1 - 1, "south", backed=False):
            rm.write("pub_registry_counter", x, rm.s + 1, z1 - 1, "south")
            break


FURNISH = {"classroom": classroom, "corridor": corridor, "vestibule": vestibule, "cloakroom": cloakroom, "wc_pub": wc_pub,
           "staff": staff, "library": library, "office": office, "medical": medical, "store": store, "canteen": canteen,
           "kitchen_pub": kitchen_pub, "assembly": assembly, "gym": gym, "changing": changing, "kid_sleep": kid_sleep,
           "kid_play": kid_play, "kid_lockers": kid_lockers, "kid_wc": kid_wc, "kg_corridor": kg_corridor,
           "kg_vestibule": kg_vestibule, "music": music, "doctor": doctor, "clinic_corridor": clinic_corridor,
           "clinic_vestibule": clinic_vestibule, "registry": registry, "chemist": chemist}
