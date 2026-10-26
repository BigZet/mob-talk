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
    """A basketball backboard on the end wall: white board with the red square, the red ring and its net."""
    def bb(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("f4f4f0"))
        cv.outline(0, 0, cv.w, cv.h, rgb("c84a2a"))
        cv.outline(1, 1, cv.w - 1, cv.h - 1, rgb("c84a2a"))
        x0, x1 = cv.w // 2 - 7, cv.w // 2 + 7
        cv.outline(x0, cv.h - 14, x1, cv.h - 3, rgb("c84a2a"))

    m = Model()
    m.box((-8, 8, 14), (24, 30, 15), "lamp_body", north=Pic("backboard", bb))
    m.box((6, 6, 15), (10, 12, 16), "tube_steel")
    m.box((4, 6, 7), (12, 7, 14), "hoop_red", up=None, down=None)
    m.box((5, 0, 8), (11, 6, 13), "net", faces=("north", "south", "east", "west"), shade=False)
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
