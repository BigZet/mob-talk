"""The bedroom, filled out: a double bed (two real beds that join), bedside tables with an alarm clock and a lamp, a chest of
drawers, the three-leaf dressing mirror, a bedside rug, a chair with clothes over it, an ironing board, suitcases, a carpet hung
above the bed. Same conventions as fur_models.py (north space, front at low z, x to the viewer's left, a room 2 blocks high)."""
import math

import fur_paint as P
from fur_paint import rgb, ramp
from furniture import Furn, Model, Pic, mat
from fur_models import WALNUT, LIGHTWOOD, BRASS, FABRICS, panel, knob, handle_bar, wood_canvas, lace_doily, chair
from fur_more import carpet_pic

QUILT = rgb("7a2a3e")


# ---------------------------------------------------------------- the double bed

def quilt(cv, W, H, inner):
    """A satin quilt, stitched in diamonds, with a turned-down white sheet at the head; `inner` is the side that meets
    the other half (0: u=0 edge, 1: the far edge), where the pattern carries straight on."""
    t = ramp(QUILT, 5, 0.7, 1.3)
    for y in range(cv.h):
        for x in range(cv.w):
            gx = x if inner == 0 else cv.w - 1 - x  # the diamonds line up across the two halves
            d = (gx + y) % 10 == 0 or (gx - y) % 10 == 0
            c = t[1] if d else t[2 + ((gx // 5 + y // 5) % 2)]
            cv.set(x, y, c)
    cv.rect(0, 0, cv.w, 6, rgb("f2f0ea"))
    cv.hline(0, cv.w, 6, rgb("d8d4c8"))
    for x in range(0, cv.w, 3):
        cv.set(x, 5, rgb("e2ded2"))


def double_bed(side):
    """One half of a double bed: side "l" meets its partner at x=0 (the partner stands one cell to x-1), "r" at x=16."""
    inner_lo = side == "l"

    def build():
        m = Model()
        x0, x1 = (0, 15.5) if inner_lo else (0.5, 16)
        mx0, mx1 = (0, 14.5) if inner_lo else (1.5, 16)
        bx0, bx1 = (0, 15) if inner_lo else (1, 16)
        # a tall polished headboard with an arched panel, a low footboard, one rail on the outer side
        m.box((x0, 0, -16), (x1, 15, -14.5), "walnut", down=None,
              north=Pic("head_back", lambda cv, W, H: panel(cv, 0, 0, cv.w, cv.h, WALNUT, "dbh1" + side, vertical=False)),
              south=Pic("head", lambda cv, W, H: headboard(cv, side)))
        m.box((x0, 0, 14.5), (x1, 8, 16), "walnut", down=None,
              north=Pic("foot", lambda cv, W, H: panel(cv, 0, 0, cv.w, cv.h, WALNUT, "dbf" + side, vertical=False)))
        rail = 14.5 if inner_lo else 0.5
        m.box((rail, 2.5, -14.5), (rail + 1, 4.5, 14.5), "walnut", down=None)
        m.box((mx0, 3, -14.5), (mx1, 6.5, 14.5), "linen", down=None)
        m.box((bx0, 6.5, -9), (bx1, 7.5, 14.25), "quilt_edge", down=None, up=Pic("quilt", lambda cv, W, H: quilt(cv, W, H, 0 if inner_lo else 1)))
        drop = (14.5, 15) if inner_lo else (1, 1.5)
        m.box((drop[0], 4, -9), (drop[1], 6.5, 14.25), "quilt_edge", up=None, down=None,
              **({"west": None} if inner_lo else {"east": None}))
        m.box((mx0, 6.5, -14), (mx1, 7, -9), "linen", down=None)
        # two pillows a half: a big square one standing up behind a flat one
        px = (2, 13) if inner_lo else (3, 14)
        m.box((px[0], 7, -14), (px[1], 9, -10.5), "pillow", down=None)
        m.box((px[0] + 1, 9, -14.25), (px[1] - 1, 15.5, -13), "pillow", down=None)
        return m
    return build


def headboard(cv, side):
    t = ramp(WALNUT, 6, 0.6, 1.25)
    wood_canvas(cv, WALNUT, "dbhead" + side, vertical=False, polish=1.4)
    # an arched raised panel; the arch is centred on the joint between the halves so the two read as one bed
    w, h = cv.w, cv.h
    cx = 0 if side == "l" else w
    for y in range(3, h - 3):
        for x in range(w):
            r = math.hypot((x - cx) / 1.6, (y - h) * 1.0)
            if 18 < r < 20:
                cv.set(x, y, t[5] if r < 19 else t[0])
    cv.rect(0, 0, w, 2, t[4])
    cv.hline(0, w, h - 1, t[0])


# ---------------------------------------------------------------- bedside, chest of drawers, dressing mirror

def nightstand(on):
    def front(cv, W, H):
        wood_canvas(cv, WALNUT, "ns", vertical=True, polish=1.2)
        panel(cv, 1, 2, cv.w - 1, 7, WALNUT, "nsd", vertical=False, frame=1)
        knob(cv, cv.w // 2 - 1, 4, BRASS)
        panel(cv, 1, 8, cv.w - 1, cv.h - 2, WALNUT, "nsdoor", polish=1.3)
        knob(cv, cv.w - 5, cv.h // 2 + 3, BRASS)

    def build():
        m = Model()
        # a bedside table 0.55 m high with a drawer and a door; on it an alarm clock «Слава» and a little lamp
        m.box((3, 0, 6), (13, 9, 15.5), "walnut", down=None, north=Pic("front", front))
        m.box((2.5, 9, 5.5), (13.5, 9.5, 16), "walnut", down=None,
              up=Pic("top", lambda cv, W, H: (wood_canvas(cv, WALNUT, "nstop", vertical=False, polish=1.4),
                                              lace_doily(cv, cv.w // 2, cv.h // 2, 6))))
        # the alarm clock: round face on two feet, two bells on top
        m.box((4, 9.5, 8), (4.5, 10, 8.5), "chrome")
        m.box((6, 9.5, 8), (6.5, 10, 8.5), "chrome")
        m.box((3.75, 10, 7.5), (6.75, 13, 9), "clock_red", north=Pic("clock", alarm_face))
        m.box((3.75, 13, 7.75), (4.75, 13.75, 8.75), "chrome")
        m.box((5.75, 13, 7.75), (6.75, 13.75, 8.75), "chrome")
        # the lamp: a turned wooden stem and a pleated cream shade
        m.box((9, 9.5, 10), (12, 10, 13), "walnut")
        m.box((10.25, 10, 11.25), (10.75, 13, 11.75), "brass", up=None, down=None)
        sh = "lampshade_lit" if on else "lampshade"
        m.box((8.5, 13, 9.5), (12.5, 16.5, 13.5), sh, shade=not on, up=None, down=None)
        m.box((9, 16.5, 10), (12, 17, 13), sh, shade=not on, faces=("up",))
        m.box((9.75, 13, 10.75), (11.25, 14.5, 12.25), "bulb" if on else "bulb_off", shade=not on, down=None)
        return m
    return build


def alarm_face(cv, W, H):
    t = ramp(rgb("b8302a"), 4, 0.7, 1.2)
    cv.rect(0, 0, cv.w, cv.h, t[2])
    cv.disc(cv.w / 2, cv.h / 2, min(cv.w, cv.h) / 2 - 0.6, rgb("f2eee0"), ring=t[0])
    c = (cv.w // 2, cv.h // 2)
    cv.set(c[0], c[1] - 1, rgb("202020")); cv.set(c[0], c[1] - 2, rgb("202020"))
    cv.set(c[0] + 1, c[1], rgb("202020"))
    cv.set(c[0], c[1], rgb("202020"))


def chest_of_drawers():
    def front(cv, W, H):
        wood_canvas(cv, WALNUT, "chest", vertical=True, polish=1.2)
        n = 4
        top, bot = 2, cv.h - 4
        step = (bot - top) // n
        for k in range(n):
            y0 = top + k * step
            panel(cv, 1, y0, cv.w - 1, y0 + step - 1, WALNUT, f"chd{k}", vertical=False, frame=1)
            handle_bar(cv, cv.w // 2 - 4, y0 + step // 2 - 1, cv.w // 2 + 4, BRASS)
            cv.set(cv.w // 2 - 1, y0 + 2, rgb("2a1a10"))  # the keyhole
        cv.rect(0, cv.h - 3, cv.w, cv.h, ramp(WALNUT)[0])

    m = Model()
    # a chest of four drawers 0.85 m high with a lace runner; a cut-glass vase, a jewel box, a bottle of «Красная Москва»
    m.box((0.5, 0, 6), (15.5, 13.5, 16), "walnut", down=None, north=Pic("front", front))
    m.box((0, 13.5, 5.5), (16, 14, 16), "walnut", down=None,
          up=Pic("top", lambda cv, W, H: (wood_canvas(cv, WALNUT, "chtop", vertical=False, polish=1.4), runner(cv))))
    m.box((2, 14, 9), (4.5, 18, 11.5), "glass")
    m.box((2.5, 18, 9.5), (4, 19.5, 11), "plastic_red", down=None)
    m.box((6.5, 14, 8.5), (10.5, 16, 11.5), "lacquer_red", up=Pic("jewel", jewel_lid))
    m.box((12, 14, 9.5), (13.5, 17, 11), "glass_red")
    m.box((12.25, 17, 9.75), (13.25, 18, 10.75), "brass", down=None)
    return m


def runner(cv):
    """A crocheted runner along the top."""
    y0, y1 = cv.h // 2 - 5, cv.h // 2 + 5
    for y in range(y0, y1):
        for x in range(1, cv.w - 1):
            if (x + y) % 3 and not (x % 4 == 0 and y % 4 == 0):
                cv.set(x, y, rgb("f2eee4") if (x // 3 + y // 3) % 2 else rgb("e6e0d0"))


def jewel_lid(cv, W, H):
    t = ramp(rgb("a02a24"), 5, 0.6, 1.3)
    cv.rect(0, 0, cv.w, cv.h, t[2])
    cv.outline(0, 0, cv.w, cv.h, rgb("c8a040"))
    cv.disc(cv.w / 2, cv.h / 2, 2, rgb("e0b040"))  # a Palekh-style gold flower
    cv.set(cv.w // 2, cv.h // 2, t[4])


def dressing_table():
    def front(cv, W, H):
        wood_canvas(cv, WALNUT, "trel", vertical=True, polish=1.2)
        for k in range(2):
            x0 = 1 + k * (cv.w - 2) // 2
            panel(cv, x0, 2, x0 + (cv.w - 2) // 2, cv.h - 2, WALNUT, f"trd{k}", vertical=False, frame=1)
            knob(cv, x0 + (cv.w - 2) // 4 - 1, cv.h // 2 - 1, BRASS)

    def mirror(cv, W, H):
        t = ramp(WALNUT, 6, 0.6, 1.2)
        cv.rect(0, 0, cv.w, cv.h, t[3])
        cv.rect(1, 1, cv.w - 1, cv.h - 1, rgb("a8bcc4"))
        for y in range(1, cv.h - 1):
            for x in range(1, cv.w - 1):
                if (x - y) % 11 in (0, 1):
                    cv.set(x, y, rgb("dceaee"))
        cv.outline(0, 0, cv.w, cv.h, t[1])

    m = Model()
    # the трельяж: a low table with two drawers and the three-leaf mirror; the side leaves turned in towards the sitter
    m.box((1, 0, 8), (15, 10, 16), "walnut", down=None, north=Pic("front", front))
    m.box((0.5, 10, 7.5), (15.5, 10.5, 16), "walnut", down=None,
          up=Pic("top", lambda cv, W, H: (wood_canvas(cv, WALNUT, "trtop", vertical=False, polish=1.4), lace_doily(cv, cv.w // 2, cv.h // 2 + 2, 5))))
    m.box((5, 10.5, 14.5), (11, 26, 15.5), "walnut", north=Pic("mirror_c", mirror), down=None)
    m.box((1, 11, 14.5), (5, 24, 15.25), "walnut", north=Pic("mirror_l", mirror), down=None,
          rot={"origin": [5, 11, 15], "axis": "y", "angle": -22.5})
    m.box((11, 11, 14.5), (15, 24, 15.25), "walnut", north=Pic("mirror_r", mirror), down=None,
          rot={"origin": [11, 11, 15], "axis": "y", "angle": 22.5})
    # powder box, scent bottles
    m.box((3, 10.5, 9.5), (5, 11.5, 11.5), "enamel", up=Pic("powder", lambda cv, W, H: cv.disc(cv.w / 2, cv.h / 2, 1.5, rgb("e8b0b0"))))
    m.box((11.5, 10.5, 10), (12.5, 12.5, 11), "glass_red")
    m.box((13, 10.5, 10.5), (14, 12, 11.5), "glass")
    return m


# ---------------------------------------------------------------- the rest of the room

def bedside_rug():
    import fur_rugs
    # a rug by the bed, 0.9 x 1.9 m, the same knotted pattern as the big rugs at a smaller scale
    return fur_rugs.rug(1, -14.5, 15, 14.5, lambda cv, W, H: fur_rugs.persian(cv, W, H, fur_rugs.PERSIAN_BLUE), "bedside")


def chair_with_clothes():
    m = chair()
    # a jacket hung over the back and a dress folded on the seat
    m.box((3.5, 11, 12.75), (12.5, 16.5, 13.5), "coat_brown", down=None)
    m.box((3.5, 8, 13.5), (12.5, 16.5, 14), "coat_brown", up=None)
    m.box((4.5, 9.5, 4), (11, 10.5, 10), "dress_blue", down=None)
    return m


def ironing_board():
    m = Model()
    # an ironing board on crossed legs, the iron standing on its heel at the narrow end
    m.box((1, 12.5, 6), (15, 13.5, 10), "ironing_cover", down=None)
    m.box((1.5, 12, 6.5), (14.5, 12.5, 9.5), "chrome", up=None)
    for z in (6.25, 8.25):
        m.box((7.5, 0, z), (8.5, 13, z + 0.5), "chrome", up=None, down=None,
              rot={"origin": [8, 6.5, z + 0.25], "axis": "z", "angle": 22.5})
        m.box((7.5, 0, z + 0.75), (8.5, 13, z + 1.25), "chrome", up=None, down=None,
              rot={"origin": [8, 6.5, z + 1], "axis": "z", "angle": -22.5})
    m.box((12, 13.5, 7), (14, 15.5, 9), "iron_steel", north=None)
    m.box((11.5, 13.5, 7), (12, 14, 9), "chrome", up=None)
    m.box((12.5, 15.5, 7.75), (13.5, 16.5, 8.25), "black", down=None)
    return m


def suitcases():
    m = Model()
    # two suitcases stood on end against the wall: brown fibre with leather corners and a strap
    for x0, x1, h, c in ((1, 8, 11, "suitcase_brown"), (8.5, 15, 9, "suitcase_green")):
        m.box((x0, 0, 11), (x1, h, 15.5), c, down=None)
        m.box((x0 + 2, h, 12.75), (x1 - 2, h + 1, 13.75), "black", down=None)
        for xc in (x0, x1 - 1):
            m.box((xc, 0, 10.5), (xc + 1, 1.5, 11), "leather", up=None, down=None, south=None)
            m.box((xc, h - 1.5, 10.5), (xc + 1, h, 11), "leather", down=None, south=None)
    return m


def wall_carpet_high():
    def pic(cv, W, H):
        carpet_pic(cv, W, H, field=rgb("6a2034"), border=rgb("1e2a4a"), ivory=rgb("e2cc98"), gold=rgb("c8903a"))

    m = Model()
    # the carpet over the bed: 2.5 x 0.85 m in the upper cells only, so the bed's head stands under it
    m.box((-12, 1, 15.5), (28, 15, 16), "carpet_side", north=Pic("carpet_hi", pic), south=None)
    for y in range(2, 15, 2):
        m.box((28, y, 15.5), (28.5, y + 1, 16), "carpet_fringe", faces=("north", "up", "down", "east"))
        m.box((-12.5, y, 15.5), (-12, y + 1, 16), "carpet_fringe", faces=("north", "up", "down", "west"))
    return m


# ---------------------------------------------------------------- the list

def make():
    mat("quilt_edge", P.fabric(32, 32, QUILT, "qedge", weave=1))
    mat("clock_red", P.enamel(32, 32, rgb("b8302a"), "clockr"))
    mat("lacquer_red", P.enamel(32, 32, rgb("8a1e1a"), "lacr"))
    mat("dress_blue", P.fabric(32, 32, rgb("3a5a9a"), "dressb"))
    mat("ironing_cover", ironing_cover())
    mat("iron_steel", P.metal(32, 32, rgb("b8bcc0"), vertical=False))
    mat("suitcase_brown", P.fabric(32, 32, rgb("7a5232"), "suitb", weave=1, nap=False))
    mat("suitcase_green", P.fabric(32, 32, rgb("4a5a3a"), "suitg", weave=1, nap=False))
    mat("leather", P.enamel(32, 32, rgb("4a2e1a"), "leath"))
    Furn("apt_bed_double_l", "Двуспальная кровать (левая половина)", "Double bed (left half)", double_bed("l"), kind="bed",
         color="BROWN", sound="WOOL")
    Furn("apt_bed_double_r", "Двуспальная кровать (правая половина)", "Double bed (right half)", double_bed("r"), kind="bed",
         color="BROWN", sound="WOOL")
    Furn("apt_nightstand", "Тумбочка с будильником и лампой", "Bedside table with a lamp", nightstand, toggle=True, light=10,
         color="BROWN")
    Furn("apt_chest_drawers", "Комод", "Chest of drawers", lambda: chest_of_drawers(), color="BROWN")
    Furn("apt_dressing_table", "Трельяж", "Dressing table with three mirrors", lambda: dressing_table(), color="BROWN")
    Furn("apt_bedside_rug", "Прикроватный коврик", "Bedside rug", lambda: bedside_rug(), color="BLUE", sound="WOOL", solid=False)
    Furn("apt_chair_clothes", "Стул с одеждой", "Chair with clothes", lambda: chair_with_clothes(), seat=8, color="BROWN")
    Furn("apt_ironing_board", "Гладильная доска с утюгом", "Ironing board", lambda: ironing_board(), color="WHITE", sound="METAL")
    Furn("apt_suitcases", "Чемоданы", "Suitcases", lambda: suitcases(), color="BROWN", sound="WOOL")
    Furn("apt_wall_carpet_high", "Ковёр над кроватью", "Carpet over the bed", lambda: wall_carpet_high(), mount="WALL",
         color="RED", sound="WOOL", solid=False)


def ironing_cover():
    cv = P.fabric(32, 32, rgb("d8d0b8"), "iron_cov", weave=1, nap=False)
    for y in range(0, 32, 8):
        for x in range(32):
            if (x + y // 8 * 3) % 8 < 2:
                cv.set(x, y + 3, rgb("b8a888"))
    return cv
