"""The public buildings' furniture (spec §3.2 public.py): a Soviet school first - the pupils' desks with their benches, the
teacher's desk, the blackboard, posters, the honours board, fluorescent tube lamps, the canteen's tables, the cloakroom's
long coat racks, the gym's wall bars, basketball hoops, mats and benches.
Same conventions as fur_models.py (north space, front at low z, the back against the wall at z 16, x to the viewer's left)."""
import random

import fur_paint as P
from fur_paint import rgb, shade, ramp  # noqa: F401
from furniture import Furn, Model, Pic, mat
from fur_models import OAK, WALNUT, panel  # noqa: F401

DESK_GREEN = rgb("3e5a46")
BOARD = rgb("2c4636")
GYM_WOOD = rgb("c89a5a")


def textures():
    mat("desk_green", P.enamel(32, 32, DESK_GREEN, "deskg", chips=3))
    mat("desk_frame", P.wood(32, 32, rgb("b88a54"), "deskf", vertical=True, polish=0.5))
    mat("board_frame", P.wood(32, 32, rgb("6a4a2c"), "boardf", vertical=False, polish=0.4))
    mat("gym_wood", P.wood(32, 32, GYM_WOOD, "gymw", vertical=True, polish=1.0, knots=False))
    mat("gym_wood_h", P.wood(32, 32, GYM_WOOD, "gymwh", vertical=False, polish=1.0, knots=False))
    mat("mat_blue", P.fabric(32, 32, rgb("3a5a9a"), "matb", weave=2, nap=False))
    mat("mat_blue_side", P.fabric(32, 32, rgb("2e4a80"), "matbs", weave=1, nap=False))
    mat("hoop_red", P.enamel(32, 32, rgb("c84a2a"), "hoop"))
    mat("net", net_tex())
    mat("tube_lit", P.enamel(32, 32, rgb("f4f8ff"), "tube"))
    mat("tube_off", P.enamel(32, 32, rgb("c8ccd0"), "tubeo"))
    mat("lamp_body", P.enamel(32, 32, rgb("e4e4dc"), "lampb"))
    mat("formica", P.enamel(32, 32, rgb("d8d0b8"), "formica", chips=2))
    mat("tube_steel", P.metal(32, 32, rgb("8a9096"), vertical=False))
    for _c in ("red", "yellow", "green", "blue"):
        mat(f"kidbl_{_c}", P.fabric(32, 32, shade(KID_COLOURS[_c], 0.85), f"kbe{_c}", weave=1))
    mat("kid_top", P.enamel(32, 32, rgb("f0d060"), "kidtop", chips=1))
    mat("kid_locker", P.enamel(32, 32, rgb("e8dcb8"), "kidlock", chips=2))
    mat("plywood", P.wood(32, 32, rgb("9a6234"), "plyw", vertical=False, polish=0.8, knots=False))
    mat("oilcloth_brown", P.enamel(32, 32, rgb("6a3a24"), "oilb", chips=0))


def net_tex():
    cv = P.Canvas(32, 32, (0, 0, 0, 0))
    for y in range(32):
        for x in range(32):
            if (x + y) % 6 == 0 or (x - y) % 6 == 0:
                cv.set(x, y, rgb("f0f0ea"))
    return cv


# ---------------------------------------------------------------- the classroom

def school_desk():
    """The two-seat school desk (парта) of the 60s-80s: a top in dark green paint sloping towards the pupils, a flat strip
    with the inkwell groove at the far edge, a shelf for the satchels under it, the bench in one piece with it, all on a
    light wooden frame. The pupils sit at the front (low z) and look over the desk towards its back."""
    def top(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, DESK_GREEN)
        t = ramp(DESK_GREEN, 5, 0.7, 1.25)
        r = random.Random("desktop")
        for _ in range(26):                        # scratches and ink
            x, y = r.randrange(cv.w), r.randrange(cv.h)
            cv.set(x, y, t[4] if r.random() < 0.7 else rgb("1e2a5a"))
        cv.hline(0, cv.w, 0, t[4])
        cv.vline(cv.w // 2, 0, cv.h, t[1])         # the two halves' lids

    m = Model()
    for x0 in (0, 16):                             # the sloped lids, one per seat (a rotated element keeps to its cell)
        m.box((x0 + 0.5, 12, 7), (x0 + 15.5, 13, 14), "desk_green", up=Pic(f"desk_top{x0}", top),
              rot={"origin": [x0 + 8, 12.5, 14], "axis": "x", "angle": -22.5})
    m.box((0, 13, 14), (32, 14, 16), "desk_green")                          # the flat strip at the back
    m.box((1, 9, 9), (31, 10, 15), "desk_frame")                           # the satchel shelf
    m.box((1, 10, 15), (31, 13, 15.5), "desk_frame", faces=("north", "south"))
    for x0 in (0, 15):                                                      # the side frames
        m.box((x0, 0, 2), (x0 + 1, 1.5, 16), "desk_frame")
        m.box((x0, 1.5, 13), (x0 + 1, 13, 14), "desk_frame")
        m.box((x0, 1.5, 3), (x0 + 1, 7, 4), "desk_frame")
    for x0 in (16, 31):
        m.box((x0, 0, 2), (x0 + 1, 1.5, 16), "desk_frame")
        m.box((x0, 1.5, 13), (x0 + 1, 13, 14), "desk_frame")
        m.box((x0, 1.5, 3), (x0 + 1, 7, 4), "desk_frame")
    m.box((0, 7, 1), (32, 8, 6), "desk_frame")                             # the bench
    m.box((0, 8, 0.5), (32, 13, 1.5), "desk_frame")                        # its back rest
    return m


def teacher_desk():
    """The teacher's desk: a pedestal of drawers on the right, a modesty panel to the class, a writing top, a class
    register and a globe on it."""
    def pedestal(cv, W, H):
        panel(cv, 0, 0, cv.w, cv.h, WALNUT, "tdp", vertical=True)
        for k in range(3):
            y0 = 2 + k * (cv.h - 4) // 3
            cv.outline(2, y0, cv.w - 2, y0 + (cv.h - 4) // 3 - 1, shade(WALNUT, 0.6))
            cv.rect(cv.w // 2 - 2, y0 + 3, cv.w // 2 + 2, y0 + 4, rgb("c8a860"))

    m = Model()
    m.box((0, 0, 4), (12, 12, 15), "walnut", north=Pic("td_ped", pedestal), down=None)
    m.box((12, 0, 13), (30, 12, 14), "walnut", down=None)                  # the modesty panel to the class
    m.box((30, 0, 4), (31.5, 12, 14), "walnut", down=None)
    m.box((-0.5, 12, 3), (32, 13.5, 16), "walnut")
    m.box((18, 13.5, 6), (25, 14.5, 11), "lacquer_red")                    # the class register
    m.box((5, 13.5, 8), (7, 14.5, 10), "brass")                            # the globe on its stand
    m.box((5.5, 14.5, 8.5), (6.5, 16, 9.5), "brass")
    m.box((3.5, 16, 6.5), (8.5, 21, 11.5), "globe")
    return m


def blackboard():
    """The blackboard: three blocks of dark green board in a wooden frame, a chalk tray, the date and «Классная работа»
    written up in chalk, a sum half rubbed out, the duster on the tray."""
    def board(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, BOARD)
        r = random.Random("board")
        t = ramp(BOARD, 5, 0.8, 1.3)
        for _ in range(240):                       # old rubbed chalk
            x, y = r.randrange(cv.w), r.randrange(cv.h)
            cv.set(x, y, t[r.choice((2, 3, 4))])
        chalk = rgb("e8ece4")
        P.text(cv, cv.w // 2 - P.text_width("26 ОКТЯБРЯ") // 2, 6, "26 ОКТЯБРЯ", chalk)
        P.text(cv, cv.w // 2 - P.text_width("КЛАССНАЯ РАБОТА") // 2, 14, "КЛАССНАЯ РАБОТА", chalk)
        P.text(cv, 10, 26, "125 - 48 = 77", chalk)
        P.text(cv, 10, 34, "36 - 9 = 27", chalk)
        for k in range(18):                        # a column rubbed out in a swirl
            cv.set(cv.w - 30 + k, 28 + (k * 7) % 9, t[4])
        cv.hline(cv.w // 2 + 4, cv.w - 8, 44, chalk)

    m = Model()
    m.box((-15, 6, 15), (31, 30, 16), "board_frame", north=Pic("board", board, area=None), up=None, down=None)
    m.box((-16, 5, 14.5), (32, 6, 16), "board_frame")
    m.box((-16, 30, 14.5), (32, 31, 16), "board_frame")
    m.box((-16, 6, 14.5), (-15, 30, 16), "board_frame", up=None, down=None)
    m.box((31, 6, 14.5), (32, 30, 16), "board_frame", up=None, down=None)
    m.box((-15, 4, 12.5), (31, 5, 16), "board_frame")                     # the chalk tray
    m.box((-15, 5, 12.5), (31, 5.5, 13), "board_frame", up=None, down=None)
    m.box((10, 5, 13.25), (14, 5.75, 14.25), "felt_grey")                         # the duster
    m.box((2, 5, 14), (4, 5.5, 14.5), "paper")                             # chalk sticks
    m.box((5, 5, 14), (6.5, 5.5, 14.5), "paper")
    return m


def poster(kind):
    def table(cv, W, H):                           # Mendeleev's table: the coloured blocks of the periods and groups
        cv.rect(0, 0, cv.w, cv.h, rgb("f0ece0"))
        P.text(cv, cv.w // 2 - P.text_width("ТАБЛИЦА МЕНДЕЛЕЕВА") // 2, 2, "ТАБЛИЦА МЕНДЕЛЕЕВА", rgb("8a2020"))
        cols = [rgb("e8a0a0"), rgb("f0d080"), rgb("a0c8e8"), rgb("b0d8a0")]
        for row in range(7):
            for c in range(9):
                if row < 1 and 0 < c < 8 or row < 3 and 1 < c < 7 and c != 2:
                    continue
                x, y = 2 + c * (cv.w - 4) // 9, 10 + row * (cv.h - 13) // 7
                cv.rect(x, y, x + (cv.w - 4) // 9 - 1, y + (cv.h - 13) // 7 - 1, cols[(row + c) % 4])
                cv.set(x + 1, y + 1, rgb("303030"))
        cv.outline(0, 0, cv.w, cv.h, rgb("6a4a2c"))

    def abc(cv, W, H):                             # the alphabet poster of the first form
        cv.rect(0, 0, cv.w, cv.h, rgb("f6f0dc"))
        letters = "АБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЭЮЯ"
        cols = [rgb("c83030"), rgb("2a5aa8"), rgb("2a8a3a"), rgb("c87a20")]
        for i, ch in enumerate(letters):
            x, y = 3 + (i % 6) * (cv.w - 4) // 6, 3 + (i // 6) * (cv.h - 4) // 5
            P.text(cv, x, y, ch, cols[i % 4])
        cv.outline(0, 0, cv.w, cv.h, rgb("6a4a2c"))

    def honours(cv, W, H):                         # Доска почёта: the title, rows of small photos with name plates
        cv.rect(0, 0, cv.w, cv.h, rgb("a82820"))
        P.text(cv, cv.w // 2 - P.text_width("ДОСКА ПОЧЁТА".replace("Ё", "Е")) // 2, 3, "ДОСКА ПОЧЕТА", rgb("f0d060"))
        r = random.Random("honours")
        for row in range(2):
            for c in range(5):
                x, y = 4 + c * (cv.w - 6) // 5, 13 + row * (cv.h - 15) // 2
                w, h = (cv.w - 6) // 5 - 3, (cv.h - 15) // 2 - 5
                cv.rect(x, y, x + w, y + h, rgb("e8e4d8"))
                cv.rect(x + 1, y + 1, x + w - 1, y + h - 1, rgb("8a8a86"))
                face = rgb("d8b8a0") if r.random() < 0.8 else rgb("c8a088")
                cv.disc(x + w / 2, y + h * 0.45, w * 0.28, face)
                cv.rect(x + 1, int(y + h * 0.75), x + w - 1, y + h - 1, rgb("3a3a40"))
                cv.hline(x, x + w, y + h + 2, rgb("f0e8c8"))
        cv.outline(0, 0, cv.w, cv.h, rgb("d8b040"))

    painters = {"table": table, "abc": abc, "honours": honours}
    wide = kind in ("honours", "table")

    def build():
        m = Model()
        x0, x1 = (-14, 30) if wide else (1, 31)
        m.box((x0, 4, 15.25), (x1, 26 if wide else 22, 16), "paper", north=Pic(f"poster_{kind}", painters[kind]), faces=("north",),
              shade=False)
        m.box((x0, 4, 15.5), (x1, 26 if wide else 22, 16), "board_frame", faces=("up", "down", "east", "west"))
        return m
    return build


def tube_lamp(on):
    lit = "tube_lit" if on else "tube_off"

    def build():
        m = Model()
        # a ЛПО fluorescent fitting: a white steel body on the ceiling, two tubes under a slatted grid
        m.box((2, 14, 1), (14, 16, 15), "lamp_body", up=None)
        for x in (4.5, 10):
            m.box((x, 13, 1.5), (x + 1.5, 14, 14.5), lit, shade=not on, up=None)
        for z in (3, 6, 9, 12):
            m.box((2.5, 12.5, z), (13.5, 13, z + 0.5), "lamp_body")
        return m
    return build


# ---------------------------------------------------------------- the canteen, the cloakroom

def canteen_table():
    """A canteen table for four, two blocks long: a formica top on steel tube legs."""
    m = Model()
    m.box((0, 11, 2), (32, 12, 14), "formica")
    m.box((0, 10.5, 2), (32, 11, 14), "tube_steel", up=None)
    for x in (1, 29.5):
        for z in (3, 11.5):
            m.box((x, 0, z), (x + 1.5, 10.5, z + 1.5), "tube_steel", up=None)
    m.box((6, 12, 5), (8, 13, 7), "glass_tea")                             # glasses of kompot
    m.box((22, 12, 8), (24, 13, 10), "glass_tea")
    m.box((13, 12, 6), (19, 12.5, 10), "plate")
    return m


def coat_rack_long():
    """The school cloakroom's rack: two blocks of steel tube with hooks, coats and satchels on it."""
    m = Model()
    m.box((0, 0, 12), (32, 1, 15), "tube_steel")
    for x in (0.5, 15.5, 30.5):
        m.box((x, 1, 13), (x + 1, 28, 14), "tube_steel", up=None, down=None)
    m.box((0, 28, 12.5), (32, 29, 14.5), "tube_steel")
    m.box((0, 24, 12.75), (32, 25, 14.25), "tube_steel")
    coats = ["coat_brown", "coat_grey", "coat_red", "coat_grey", "coat_brown", "coat_red"]
    for k, c in enumerate(coats):
        x = 1.5 + k * 5
        m.box((x, 12, 10), (x + 4, 24, 15), c)
        m.box((x + 1, 24, 12), (x + 3, 26, 14), c, down=None)
    m.box((3, 1, 9), (7, 5, 13), "leatherette")                            # a satchel left on the floor
    m.box((20, 1, 10), (24, 4, 13), "canvas_blue")
    return m


# ---------------------------------------------------------------- the gym

def wall_bars():
    """Wall bars (шведская стенка): two uprights to 3 m, rungs every 15 cm, against the wall."""
    m = Model()
    for x in (1, 14):
        m.box((x, 0, 13), (x + 1, 48, 15.5), "gym_wood")
    for y in range(3, 48, 4):
        m.box((2, y, 13.75), (14, y + 1, 14.75), "gym_wood_h")
    return m


def basket_hoop():
    """A basketball backboard on the end wall: a varnished plywood board in a steel edge on brackets standing off the wall, a thick red
    border and the red shooting square, the black pad along its bottom, the red ring on its plate and the net."""
    def bb(cv, W, H):
        # varnished plywood, as the school boards were: a white board would vanish against the gym's white end wall
        cv.paste(P.wood(cv.w, cv.h, rgb("c8945a"), "backboard", vertical=False, polish=0.5, knots=False, spacing=6), 0, 0)
        for k in range(3):
            cv.outline(k, k, cv.w - k, cv.h - k, rgb("c03a22"))
        x0, x1 = cv.w // 2 - 8, cv.w // 2 + 8
        for k in range(2):
            cv.outline(x0 + k, cv.h - 16 + k, x1 - k, cv.h - 3 - k, rgb("c03a22"))

    m = Model()
    m.box((-8, 9.5, 10), (24, 30, 11.5), "iron_grey", north=Pic("backboard", bb))
    m.box((-8, 8, 9.5), (24, 9.5, 12), "black")                              # the pad along the bottom
    for x in (-4, 19):                                                       # the brackets off the wall
        m.box((x, 12, 11.5), (x + 1.5, 13.5, 16), "tube_steel")
        m.box((x, 25, 11.5), (x + 1.5, 26.5, 16), "tube_steel")
    m.box((6, 5, 8.5), (10, 9.5, 9.5), "tube_steel")                         # the ring's plate
    m.box((4, 6, 2), (12, 7, 8.5), "hoop_red", up=None, down=None)
    m.box((5, 0, 3), (11, 6, 7.5), "net", faces=("north", "south", "east", "west"), shade=False)
    return m


def gym_mat():
    m = Model()
    m.box((0, 0, 0), (32, 3, 16), "mat_blue_side", up="mat_blue")
    return m


def gym_bench():
    """The long gym bench: a board on two trestles."""
    m = Model()
    m.box((0, 6, 5), (32, 7.5, 11), "gym_wood_h")
    for x in (3, 27):
        m.box((x, 0, 5.5), (x + 2, 6, 10.5), "gym_wood")
    m.box((3.5, 2, 7.5), (28.5, 3, 8.5), "gym_wood_h")
    return m


# ---------------------------------------------------------------- the kindergarten

KID_COLOURS = {"red": rgb("c8402e"), "yellow": rgb("e8b830"), "green": rgb("4a9a48"), "blue": rgb("3a74b8")}


def kid_bed(blanket):
    """A kindergarten bed for a child of three to seven: a low frame of light varnished wood with a solid head and foot,
    a white sheet, a flannel blanket folded back and a pillow set up on its corner, as the nannies do it."""
    def spread(cv, W, H):
        base = KID_COLOURS[blanket]
        cv.paste(P.fabric(cv.w, cv.h, base, f"kb{blanket}", weave=2), 0, 0)
        t = ramp(base, 5, 0.75, 1.3)
        for y in range(0, cv.h, 6):                # the flannel's checks
            cv.hline(0, cv.w, y, t[4])
        for x in range(0, cv.w, 6):
            cv.vline(x, 0, cv.h, t[4])

    m = Model()
    for z0, h in ((1, 11), (14.5, 8)):             # the head (at the wall side) and the foot, solid boards
        m.box((1, 0, z0), (15, h, z0 + 1.5), "lightwood")
    for x in (1, 14):                              # the side rails
        m.box((x, 2, 2.5), (x + 1, 4, 14.5), "lightwood_v", down=None)
    m.box((2, 3, 2.5), (14, 5.5, 14.5), "linen", down=None)                  # the mattress in its sheet
    m.box((1.75, 5.5, 7), (14.25, 6.5, 14.25), f"kidbl_{blanket}", down=None, up=Pic(f"kidbl_{blanket}_top", spread))
    m.box((1.75, 2.5, 7), (2.25, 5.5, 14.25), f"kidbl_{blanket}", up=None, down=None, east=None)
    m.box((13.75, 2.5, 7), (14.25, 5.5, 14.25), f"kidbl_{blanket}", up=None, down=None, west=None)
    m.box((4, 5.5, 3), (12, 9.5, 4.5), "pillow", down=None)                  # the pillow on its edge, a «sail»
    return m


def kid_table():
    """The low group table for four (a child's height, 50 cm): a top of yellow plastic laminate on beech legs, paper and
    crayons on it."""
    m = Model()
    m.box((0.5, 8, 1.5), (31.5, 9, 14.5), "kid_top")
    m.box((0.5, 7, 1.5), (31.5, 8, 14.5), "lightwood", up=None)
    for x in (1.5, 29):
        for z in (2.5, 12):
            m.box((x, 0, z), (x + 1.5, 7, z + 1.5), "lightwood_v", up=None)
    m.box((5, 9, 4), (11, 9.5, 12), "paper")                              # an album sheet and the crayons
    for i, c in enumerate(("red_paint", "blue_paint", "green_paint", "yellow_paint")):
        m.box((13 + i * 1.25, 9, 6), (14 + i * 1.25, 9.75, 11), c)
    m.box((21, 9, 5), (27, 9.5, 13), "paper")
    m.box((22, 9.5, 7), (25, 10, 10), "blue_paint")  # a drawing begun on it
    return m


def kid_chair(colour):
    """A child's chair: a beech frame, the seat and the bent back painted bright."""
    def build():
        m = Model()
        paint = f"{colour}_paint"
        for x in (3, 12):
            for z in (3, 12):
                m.box((x, 0, z), (x + 1, 5, z + 1), "lightwood_v", up=None)
        m.box((2.5, 5, 2.5), (13.5, 6, 13.5), paint)
        for x in (3, 12):
            m.box((x, 6, 12.5), (x + 1, 12.5, 13.5), "lightwood_v", up=None)
        m.box((2.5, 9, 12.75), (13.5, 13, 13.75), paint)
        return m
    return build


def kid_lockers():
    """The changing room's lockers: three narrow cabinets of painted plywood per block, a bench under them, each door with
    its child's picture (a mushroom, a cherry, a car, a sun, a fir tree, a boat) so that the little ones know their own."""
    pictures = ("mushroom", "cherry", "car", "sun", "fir", "boat")

    def door(name):
        def paint(cv, W, H):
            body = rgb("e8dcb8")
            t = ramp(body, 5, 0.7, 1.15)
            cv.rect(0, 0, cv.w, cv.h, t[3])
            cv.bevel(0, 0, cv.w, cv.h, t[4], t[1])
            cx, cy = cv.w // 2, cv.h // 4
            red, green, yellow, blue, brown, white = (rgb("c83a2a"), rgb("3a8a3a"), rgb("e8b830"), rgb("3a6ab8"),
                                                      rgb("7a4a2a"), rgb("f4f0e0"))
            if name == "mushroom":
                cv.rect(cx - 1, cy, cx + 1, cy + 4, white)
                cv.rect(cx - 4, cy - 2, cx + 4, cy, red)
                cv.set(cx - 2, cy - 1, white); cv.set(cx + 2, cy - 2, white)
            elif name == "cherry":
                cv.disc(cx - 2, cy + 2, 1.6, red); cv.disc(cx + 2, cy + 2, 1.6, red)
                cv.vline(cx - 1, cy - 2, cy + 1, green); cv.vline(cx + 1, cy - 2, cy + 1, green); cv.hline(cx - 1, cx + 2, cy - 2, green)
            elif name == "car":
                cv.rect(cx - 4, cy, cx + 4, cy + 2, blue); cv.rect(cx - 2, cy - 2, cx + 2, cy, blue)
                cv.set(cx - 3, cy + 2, rgb("202020")); cv.set(cx + 2, cy + 2, rgb("202020"))
            elif name == "sun":
                cv.disc(cx, cy + 1, 2.2, yellow)
                for dx, dy in ((0, -4), (0, 4), (-4, 0), (4, 0), (-3, -3), (3, -3), (-3, 3), (3, 3)):
                    cv.set(cx + dx, cy + 1 + dy, yellow)
            elif name == "fir":
                for k in range(5):
                    cv.hline(cx - k, cx + k + 1, cy - 2 + k, green)
                cv.rect(cx, cy + 3, cx + 1, cy + 5, brown)
            else:
                cv.rect(cx - 4, cy + 2, cx + 4, cy + 4, brown); cv.vline(cx, cy - 3, cy + 2, brown)
                for k in range(4):
                    cv.hline(cx + 1, cx + 1 + k, cy - 3 + k, white)
            cv.rect(cv.w - 3, cv.h // 2, cv.w - 2, cv.h // 2 + 2, rgb("8a8a86"))   # the knob
        return paint

    m = Model()
    m.box((0, 0, 3), (32, 3, 16), "lightwood")                               # the shoe bench along the bottom
    m.box((0.5, 3, 7), (31.5, 4.5, 16), "lightwood")
    m.box((0, 4.5, 7.5), (32, 28, 16), "kid_locker", north=None, down=None)
    for k, name in enumerate(pictures):
        x0 = k * 32 / 6
        m.box((x0 + 0.25, 5, 7), (x0 + 32 / 6 - 0.25, 27.5, 7.5), "kid_locker", north=Pic(f"kidlock_{name}", door(name)),
              up=None, down=None)
    return m


def toy_shelf():
    """An open shelf of toys in the play room: a doll, a tumbler, wooden blocks, a ring pyramid, a truck, a ball."""
    m = Model()
    for x in (0, 30.5):
        m.box((x, 0, 6), (x + 1.5, 20, 16), "lightwood_v")
    for y in (0, 6.5, 13, 19):
        m.box((1.5, y, 6.5), (30.5, y + 1, 16), "lightwood")
    m.box((1.5, 1, 15.5), (30.5, 19, 16), "lightwood_v", faces=("north",))
    # the bottom shelf: a truck and the blocks
    m.box((3, 1, 9), (11, 3.5, 13), "blue_paint", down=None)
    m.box((8, 3.5, 9), (11, 5.5, 13), "red_paint", down=None)
    for x in (3.5, 6.5):
        m.box((x, 1, 8.5), (x + 1.5, 2.5, 9), "rubber_black")
    for i, c in enumerate(("red_paint", "yellow_paint", "green_paint", "blue_paint", "yellow_paint")):
        m.box((15 + (i % 3) * 3.5, 1 + (i // 3) * 3, 9 + (i % 2)), (18 + (i % 3) * 3.5, 4 + (i // 3) * 3, 12 + (i % 2)), c)
    m.box((25, 1, 9), (30, 6, 14), "ball", down=None)
    # the middle shelf: the ring pyramid, a tumbler, a bear
    for i, (c, w) in enumerate((("red_paint", 4), ("yellow_paint", 3.5), ("green_paint", 3), ("blue_paint", 2.5))):
        x0 = 5 - w / 2
        m.box((x0, 7.5 + i * 1.25, 11 - w / 2), (x0 + w, 8.75 + i * 1.25, 11 + w / 2), c, down=None)
    m.box((14, 7.5, 9), (18, 11, 13), "red_paint", down=None)
    m.box((14.75, 11, 9.75), (17.25, 13, 12.25), "plastic_white", down=None)
    m.box((22, 7.5, 9), (27, 11.5, 14), "bear", down=None)
    m.box((23, 11.5, 10), (26, 13, 13), "bear", down=None)
    # the top shelf: a doll in a pink dress and a pile of picture books
    m.box((5, 14, 10), (9, 17, 13), "laundry_pink", down=None)
    m.box((5.75, 17, 10.75), (8.25, 19, 12.25), "plastic_beige", down=None)
    m.box((14, 14, 8), (24, 16.5, 14), "books_stack", down=None)
    return m


# ---------------------------------------------------------------- the polyclinic

def big_text(cv, x, y, s, c, k=2, gap=1):
    """The 5-row font at k texels a pixel (an eye chart's top rows)."""
    for ch in s.upper():
        g = P.glyph(ch)
        for j, row in enumerate(g):
            for i, v in enumerate(row):
                if v == "X":
                    cv.rect(x + i * k, y + j * k, x + i * k + k, y + j * k + k, c)
        x += (len(g[0]) + gap) * k


def waiting_seats():
    """A row of three linked seats in the corridor: bent plywood in brown lacquer on a grey tube frame."""
    m = Model()
    m.box((1.5, 2.25, 9.25), (29.5, 3, 10.75), "tube_steel")             # the rail under the seats
    for x in (1, 29.5):
        m.box((x, 0, 5), (x + 1.5, 2, 13), "tube_steel")                     # the feet
        m.box((x, 2, 9), (x + 1.5, 6, 11), "tube_steel", up=None, down=None)
    for k in range(3):
        x0 = 0.75 + k * 10.25
        m.box((x0, 6, 3), (x0 + 9.75, 7, 12), "plywood")                     # the seat
        m.box((x0, 7, 12), (x0 + 9.75, 15, 13), "plywood")                   # the back
    m.box((0, 7, 13), (32, 8, 14), "tube_steel")
    return m


def registry_counter():
    """The registry's counter (регистратура): a panelled front, a top, a glass screen with the little windows the cards
    are handed through, the sign over it."""
    def front(cv, W, H):
        panel(cv, 0, 0, cv.w, cv.h, WALNUT, "regf", vertical=True)
        for k in range(4):
            x = 2 + k * (cv.w - 4) // 4
            cv.outline(x, 3, x + (cv.w - 4) // 4 - 2, cv.h - 3, shade(WALNUT, 0.6))

    def sign(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("2a4a8a"))
        P.text(cv, cv.w // 2 - P.text_width("РЕГИСТРАТУРА") // 2, cv.h // 2 - 2, "РЕГИСТРАТУРА", rgb("f0f0e8"))

    m = Model()
    m.box((0, 0, 4), (32, 16, 10), "walnut", north=Pic("reg_front", front), down=None)
    m.box((-0.5, 16, 2), (32.5, 17, 11), "formica")
    m.box((0.5, 17, 9), (31.5, 29, 9.5), "glass", up=None, down=None)
    for x in (0, 10.5, 21, 31.5):
        m.box((x, 17, 8.75), (x + 0.5, 29, 9.75), "alu")
    for x in (3, 13.5, 24):                                                # the windows through the glass, an arch each
        m.box((x, 17, 8.5), (x + 5, 17.5, 10), "alu")
    m.box((0, 29, 8.5), (32, 33, 10), "walnut", north=Pic("reg_sign", sign), up=None)
    m.box((4, 17, 4), (9, 17.5, 8), "paper")                               # the cards on the counter
    m.box((22, 17, 3), (27, 18, 7), "books_stack")
    return m


def card_files():
    """The card index (картотека): a tall cabinet of small drawers with brass card-holders, the patients' cards."""
    def drawers(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, shade(rgb("8a6a44"), 0.9))
        rows, cols = 9, 3
        for r in range(rows):
            for c in range(cols):
                x0, y0 = 1 + c * (cv.w - 2) // cols, 1 + r * (cv.h - 2) // rows
                x1, y1 = x0 + (cv.w - 2) // cols - 1, y0 + (cv.h - 2) // rows - 1
                cv.rect(x0, y0, x1, y1, rgb("a8845a"))
                cv.outline(x0, y0, x1, y1, rgb("5a4028"))
                cv.rect((x0 + x1) // 2 - 2, y0 + 1, (x0 + x1) // 2 + 2, y0 + 3, rgb("c8a860"))
                cv.rect((x0 + x1) // 2 - 1, y0 + 2, (x0 + x1) // 2 + 1, y0 + 3, rgb("f0ece0"))

    m = Model()
    m.box((0.5, 0, 6), (15.5, 30, 16), "walnut", north=Pic("cards", drawers))
    return m


def couch():
    """The examination couch (кушетка): a white enamelled frame, a top in brown oilcloth, the head end raised, a sheet."""
    m = Model()
    for x in (1, 29.5):
        for z in (3, 12):
            m.box((x, 0, z), (x + 1.5, 7, z + 1.5), "enamel", up=None)
    m.box((0.5, 7, 2.5), (32, 10, 14), "oilcloth_brown")
    m.box((0.5, 10, 2.5), (8, 13, 14), "oilcloth_brown", rot=None)
    m.box((9, 10, 3), (30, 10.5, 13.5), "linen", down=None)
    return m


def med_screen():
    """The screen (ширма): three panels of white cloth on a white tube frame on castors."""
    m = Model()
    for x in (0.5, 10.5, 21, 31):
        m.box((x, 1, 9), (x + 0.75, 28, 10), "enamel")
    for x0, x1 in ((1.25, 10.5), (11.25, 21), (21.75, 31)):
        m.box((x0, 3, 9.25), (x1, 27, 9.75), "linen", up=None, down=None)
    for x in (0.5, 31):
        m.box((x - 0.5, 0, 8.5), (x + 1.25, 1, 10.5), "rubber_black")
    return m


def eye_chart():
    """Sivtsev's eye chart on the wall: the rows of letters getting smaller, Ш Б at the top, the row numbers at the side."""
    def chart(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("f6f4ee"))
        black = rgb("1a1a1a")
        big_text(cv, cv.w // 2 - 11, 3, "ШБ", black, k=2, gap=1)
        y = 16
        for row in ("МНК", "ЫМБШ", "БЫНКМ", "ИНШМК"):
            while P.text_width(row) > cv.w - 4:      # as many of the row's letters as the chart is wide
                row = row[:-1]
            P.text(cv, cv.w // 2 - P.text_width(row) // 2, y, row, black)
            y += 7
        for k in range(3):                         # the rows too small for any letter: specks
            for x in range(4 + k, cv.w - 4, 3 + (k == 0)):
                cv.set(x, y + 1, black if (x + k) % 2 else rgb("6a6a6a"))
            y += 4
        cv.outline(0, 0, cv.w, cv.h, rgb("8a8a86"))

    m = Model()
    m.box((1, 2, 15.25), (15, 30, 16), "paper", north=Pic("eye_chart", chart), faces=("north",), shade=False)
    m.box((1, 2, 15.5), (15, 30, 16), "board_frame", faces=("up", "down", "east", "west"))
    return m


def medicine_cabinet():
    """A white medicine cabinet: glass doors, glass shelves of brown bottles, bandages and a kidney dish."""
    m = Model()
    m.box((0.5, 0, 6), (15.5, 1, 16), "enamel")
    m.box((0.5, 1, 6), (1.5, 29, 16), "enamel")
    m.box((14.5, 1, 6), (15.5, 29, 16), "enamel")
    m.box((0.5, 29, 6), (15.5, 30, 16), "enamel")
    m.box((1.5, 1, 15), (14.5, 29, 16), "enamel", faces=("north",))
    for y in (8, 15, 22):
        m.box((1.5, y, 7), (14.5, y + 0.5, 15), "glass_shelf")
    for i, (x, y) in enumerate(((3, 1), (6, 1), (10, 1), (3, 8.5), (8, 8.5), (4, 15.5), (11, 15.5), (6, 22.5))):
        m.box((x, y, 10), (x + 2, y + 4, 12), "glass_amber" if i % 3 else "plastic_white")
    m.box((9, 22.5, 9), (13, 24, 13), "linen")                              # the bandages
    m.box((1.5, 1, 6), (14.5, 29, 6.5), "glass", up=None, down=None)       # the glass doors
    m.box((7.75, 1, 5.75), (8.25, 29, 6.75), "enamel")
    return m


def med_scales():
    """The medical scales: a platform, the column with the beam and its weights, the height rod with its sliding arm."""
    m = Model()
    m.box((2, 0, 2), (14, 2, 13), "enamel", up="rubber_black")
    m.box((6.5, 2, 12), (9.5, 22, 14), "enamel")
    m.box((3, 20, 11.5), (13, 21.5, 14.5), "chrome")                       # the beam
    m.box((10, 19, 11), (11, 22, 15), "black")                             # its weight
    m.box((7.25, 22, 12.5), (8.75, 32, 13.5), "chrome")                    # the height rod
    m.box((5, 30, 10), (11, 30.5, 13), "chrome")
    return m


def make():
    textures()
    Furn("pub_school_desk", "Школьная парта", "School desk", lambda: school_desk(), seat=7, color="GREEN")
    Furn("pub_teacher_desk", "Учительский стол", "Teacher's desk", lambda: teacher_desk(), color="BROWN")
    Furn("pub_blackboard", "Классная доска", "Blackboard", lambda: blackboard(), mount="WALL", color="GREEN", solid=False)
    Furn("pub_poster_table", "Таблица Менделеева", "Periodic table poster", poster("table"), mount="WALL", color="WHITE", solid=False)
    Furn("pub_poster_abc", "Плакат «Азбука»", "Alphabet poster", poster("abc"), mount="WALL", color="WHITE", solid=False)
    Furn("pub_honours_board", "Доска почёта", "Honours board", poster("honours"), mount="WALL", color="RED", solid=False)
    Furn("pub_tube_lamp", "Люминесцентный светильник", "Fluorescent lamp", tube_lamp, mount="CEILING", toggle=True, light=15,
         color="WHITE", sound="METAL", solid=False)
    Furn("pub_canteen_table", "Стол в столовой", "Canteen table", lambda: canteen_table(), color="WHITE_GRAY", sound="METAL")
    Furn("pub_coat_rack_long", "Вешалка в гардеробе", "Cloakroom coat rack", lambda: coat_rack_long(), color="GRAY", sound="METAL")
    Furn("pub_wall_bars", "Шведская стенка", "Wall bars", lambda: wall_bars(), color="OAK_TAN")
    Furn("pub_basket_hoop", "Баскетбольный щит", "Basketball hoop", lambda: basket_hoop(), mount="WALL", color="WHITE", sound="METAL",
         solid=False, cutout=True)
    Furn("pub_gym_mat", "Гимнастический мат", "Gym mat", lambda: gym_mat(), color="BLUE", sound="WOOL")
    Furn("pub_gym_bench", "Гимнастическая скамейка", "Gym bench", lambda: gym_bench(), seat=7.5, color="OAK_TAN")
    for c, en in (("blue", "blue"), ("red", "red"), ("green", "green"), ("yellow", "yellow")):
        Furn(f"pub_kid_bed_{c}", "Кроватка в детском саду", "Kindergarten bed", (lambda c=c: kid_bed(c)), color=c.upper(), sound="WOOL")
    Furn("pub_kid_table", "Детский столик", "Child's table", lambda: kid_table(), color="YELLOW")
    for c in ("red", "yellow", "green", "blue"):
        Furn(f"pub_kid_chair_{c}", "Детский стульчик", "Child's chair", kid_chair(c), seat=6, color=c.upper())
    Furn("pub_kid_lockers", "Шкафчики в раздевалке", "Changing room lockers", lambda: kid_lockers(), seat=4.5, color="WHITE_GRAY")
    Furn("pub_toy_shelf", "Стеллаж с игрушками", "Toy shelf", lambda: toy_shelf(), color="OAK_TAN")
    Furn("pub_waiting_seats", "Кресла в коридоре поликлиники", "Waiting room seats", lambda: waiting_seats(), seat=7, color="BROWN",
         sound="METAL")
    Furn("pub_registry_counter", "Стойка регистратуры", "Registry counter", lambda: registry_counter(), color="BROWN")
    Furn("pub_card_files", "Картотека", "Card index", lambda: card_files(), color="BROWN")
    Furn("pub_couch", "Медицинская кушетка", "Examination couch", lambda: couch(), seat=10, color="BROWN", sound="WOOL")
    Furn("pub_med_screen", "Медицинская ширма", "Medical screen", lambda: med_screen(), color="WHITE", sound="WOOL")
    Furn("pub_eye_chart", "Таблица Сивцева", "Eye chart", lambda: eye_chart(), mount="WALL", color="WHITE", solid=False)
    Furn("pub_medicine_cabinet", "Медицинский шкаф", "Medicine cabinet", lambda: medicine_cabinet(), color="WHITE", sound="GLASS")
    Furn("pub_med_scales", "Медицинские весы", "Medical scales", lambda: med_scales(), color="WHITE", sound="METAL")
