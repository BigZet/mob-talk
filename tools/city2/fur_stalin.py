"""The stalinka's furniture (Dmitriy, 2026-10-26: under its high ceilings the rooms looked empty): pieces that rise to the
fourth row of a 4-block room - a carved oak buffet, a bookcase to the ceiling, a floor clock, a palm in a tub, a big
painting hung high, a bronze chandelier on a long rod under a plaster rosette, and the plaster cornice along the walls.
Same conventions as fur_models.py (north space, front at low z, the back against the wall at z 16, x to the viewer's left)."""
import math
import random

import fur_paint as P
from fur_paint import rgb, shade, ramp  # noqa: F401
from furniture import Furn, Model, Pic, mat
from fur_models import WALNUT, OAK, BRASS, panel, knob, books, crystal, wood_canvas

STUCCO = rgb("efebe0")


def textures():
    mat("stucco", P.enamel(32, 32, STUCCO, "stucco", chips=0))
    mat("stucco_shadow", P.enamel(32, 32, shade(STUCCO, 0.82), "stuccos", chips=0))
    mat("bronze", P.metal(32, 32, rgb("9a7432"), vertical=True))
    mat("tub_wood", P.wood(32, 32, rgb("7a5032"), "tub", vertical=True, knots=False, spacing=4))
    mat("palm", palm_tex())


def palm_tex():
    r = random.Random("palm")
    t = ramp(rgb("3e6a2e"), 5, 0.6, 1.3)
    cv = P.Canvas(32, 32, (0, 0, 0, 0))
    for y in range(32):            # a frond seen from above: the midrib down the middle, leaflets slanting off it
        cv.set(15, y, t[4]); cv.set(16, y, t[3])
        for side in (-1, 1):
            for k in range(1, 15 - abs(16 - y) // 3):
                x = 16 + side * k
                if (y + k // 2) % 3 != 0 and 0 <= x < 32:
                    cv.set(x, min(31, y + k // 3), t[r.choice((1, 2, 2, 3))])
    return cv


# ---------------------------------------------------------------- the buffet

def buffet():
    """A Stalin-era oak buffet, 2 m wide and 2.75 m tall: a cupboard with carved doors and drawers, an open niche with a
    mirror back, the glass cabinet of crystal and china over it, a cornice with a carved crest."""
    def lower(cv, W, H):
        wood_canvas(cv, OAK, "buf_lo", vertical=True, polish=1.2)
        t = ramp(OAK, 6, 0.6, 1.2)
        w, h = cv.w, cv.h
        for k in range(3):                       # three drawers along the top
            panel(cv, 2 + k * (w - 4) // 3, 2, 2 + (k + 1) * (w - 4) // 3 - 1, 9, OAK, f"bufd{k}", vertical=False, polish=1.3)
            knob(cv, 2 + k * (w - 4) // 3 + (w - 4) // 6 - 1, 5, BRASS)
        for k in range(2):                       # two doors, each with a carved oval
            x0, x1 = 2 + k * (w - 4) // 2, 2 + (k + 1) * (w - 4) // 2 - 1
            panel(cv, x0, 11, x1, h - 4, OAK, f"bufdoor{k}", polish=1.3)
            cx, cy = (x0 + x1) / 2, (11 + h - 4) / 2
            for a in range(0, 360, 12):
                x = int(cx + (x1 - x0) * 0.28 * math.cos(math.radians(a)))
                y = int(cy + (h - 15) * 0.32 * math.sin(math.radians(a)))
                cv.set(x, y, t[1]); cv.set(x + 1, y, t[4])
            knob(cv, x1 - 3 if k == 0 else x0 + 2, int(cy), BRASS)
        cv.rect(0, h - 3, w, h, t[0])

    def upper(cv, W, H):
        wood_canvas(cv, OAK, "buf_up", vertical=True, polish=1.2)
        t = ramp(OAK, 6, 0.6, 1.2)
        w, h = cv.w, cv.h
        cv.vgrad(3, 3, w - 3, h - 3, [rgb("9aaab0"), rgb("7a8a92"), rgb("6a7880")])
        third = (h - 6) // 3
        for k in range(3):
            y0 = 3 + k * third
            crystal(cv, 4, y0 + 1, w - 4, y0 + third - 1, f"buf{k}")
            cv.hline(3, w - 3, y0 + third - 1, rgb("c8dce2"))
        for x in (w // 3, 2 * w // 3):           # the glazing bars of three glass doors
            cv.vline(x, 3, h - 3, t[2])
        for y in range(3, h - 3):
            x = 5 + (y % 29)
            if y % 29 < 5 and x < w - 4:
                cv.set(x, y, rgb("e4f2f6"))
        cv.outline(2, 2, w - 2, h - 2, t[1])

    def crest(cv, W, H):
        wood_canvas(cv, OAK, "buf_cr", vertical=False, polish=1.3)
        t = ramp(OAK, 6, 0.6, 1.2)
        w, h = cv.w, cv.h
        for x in range(w):                       # a carved crest: an arch rising to the middle, a rosette on it
            top = int(h - 2 - (h - 4) * math.sin(math.pi * x / (w - 1)) ** 2)
            for y in range(top):
                cv.set(x, y, (0, 0, 0, 0))
            cv.set(x, top, t[5])
        cv.disc(w / 2, h * 0.45, 2.5, t[4], ring=t[1])

    m = Model()
    m.box((0, 0, 7), (32, 18, 16), "oak", north=Pic("buf_lower", lower), down=None)
    m.box((-0.5, 18, 6.5), (32.5, 19, 16), "oak", down=None)                              # the top of the cupboard
    m.box((1, 19, 15), (31, 24, 15.5), "glass", faces=("north",))                         # the niche's mirror back
    m.box((0, 19, 11), (1.5, 24, 16), "oak", up=None, down=None)
    m.box((30.5, 19, 11), (32, 24, 16), "oak", up=None, down=None)
    m.box((0, 24, 10), (32, 41, 16), "oak", north=Pic("buf_upper", upper), down=None)
    m.box((-1, 41, 9), (33, 43, 16), "oak", down=None)                                    # the cornice
    m.box((4, 43, 12), (28, 47, 12.5), "oak", north=Pic("buf_crest", crest), south=Pic("buf_crest_b", crest), faces=("north", "south"))
    return m


# ---------------------------------------------------------------- the bookcase to the ceiling

def bookcase_tall():
    def front(cv, W, H):
        wood_canvas(cv, WALNUT, "bct", vertical=True, polish=1.0)
        t = ramp(WALNUT, 6, 0.6, 1.2)
        w, h = cv.w, cv.h
        base = 16
        cv.rect(2, 4, w - 2, h - base - 2, rgb("2a1c14"))
        rows = 7
        hh = (h - base - 6) // rows
        for k in range(rows):
            y0 = 4 + k * hh
            books(cv, 3, y0 + 1, w - 3, y0 + hh - 1, f"bct{k}")
            cv.hline(2, w - 2, y0 + hh - 1, t[3])
        cv.vline(w // 2, 4, h - base - 2, t[1])
        for y in range(4, h - base - 2):
            x = 3 + (y % 27)
            if y % 27 < 4 and x < w - 3:
                cv.set(x, y, rgb("d8e4e8"))
        cv.outline(1, 3, w - 1, h - base - 1, t[0])
        for k in range(2):
            panel(cv, 1 + k * (w - 2) // 2, h - base, 1 + (k + 1) * (w - 2) // 2, h - 2, WALNUT, f"bctd{k}", frame=1)
        knob(cv, w // 2 - 3, h - base // 2, BRASS); knob(cv, w // 2 + 1, h - base // 2, BRASS)
        cv.rect(0, 0, w, 3, t[4]); cv.rect(0, h - 2, w, h, t[0])

    m = Model()
    # 3.8 m of books: a whole wall of the professor's study, the top shelf under the cornice reached from a stepladder
    m.box((0.5, 0, 8), (15.5, 60, 16), "walnut", north=Pic("bct_front", front), down=None)
    m.box((0, 60, 7), (16, 62, 16), "walnut", down=None)
    return m


# ---------------------------------------------------------------- the floor clock

def floor_clock():
    def case(cv, W, H):
        wood_canvas(cv, WALNUT, "fclk", vertical=True, polish=1.4)
        t = ramp(WALNUT, 6, 0.6, 1.2)
        w, h = cv.w, cv.h
        dial = w // 2
        cv.disc(w / 2, dial + 2, w / 2 - 3, rgb("f0e8d0"), ring=rgb("c8a040"))
        for k in range(12):
            a = k * math.pi / 6
            cv.set(int(w / 2 + (w / 2 - 5) * math.cos(a)), int(dial + 2 + (w / 2 - 5) * math.sin(a)), rgb("2a2a2a"))
        cv.vline(w // 2, dial - 3, dial + 2, rgb("1a1a1a"))
        cv.hline(w // 2, w // 2 + 4, dial + 2, rgb("1a1a1a"))
        y0 = w + 4                               # the long door: glass over the pendulum and the two weights
        cv.rect(4, y0, w - 4, h - 14, rgb("2a1c12"))
        cv.vline(w // 2, y0, h - 22, rgb("c8a040"))
        cv.disc(w / 2, h - 21, 3, rgb("e0b850"), ring=rgb("a08030"))
        for x in (w // 2 - 5, w // 2 + 5):
            cv.vline(x, y0, y0 + 10, rgb("c8a040"))
            cv.rect(x - 1, y0 + 10, x + 2, y0 + 16, rgb("b89038"))
        for y in range(y0, h - 14):
            if y % 19 < 3:
                cv.set(5 + (y % 19), y, rgb("d8e4e8"))
        cv.outline(3, y0 - 1, w - 3, h - 13, t[4])
        panel(cv, 3, h - 11, w - 3, h - 2, WALNUT, "fclkb", vertical=False, frame=1)

    m = Model()
    # 2.3 m: a plinth, the long case, the hood round the dial with a little gilt finial
    m.box((2.5, 0, 9), (13.5, 33, 16), "walnut", north=Pic("fclk_case", case), down=None)
    m.box((2, 33, 8.5), (14, 35, 16), "walnut", down=None)
    m.box((6, 35, 11.5), (10, 36.5, 14), "walnut", down=None)
    m.box((7.5, 36.5, 12.25), (8.5, 38, 13.25), "brass", down=None)
    return m


# ---------------------------------------------------------------- the palm in a tub

def palm():
    m = Model()
    # a fan of fronds on a hairy trunk out of an oak tub with iron hoops: 2.4 m, like in a sanatorium's foyer
    m.box((3, 0, 3), (13, 9, 13), "tub_wood", down=None)
    for y in (1.5, 7):
        m.box((2.75, y, 2.75), (13.25, y + 1, 13.25), "iron_black", up=None, down=None)
    m.box((3.5, 8.75, 3.5), (12.5, 8.75, 12.5), "soil", faces=("up",))
    m.box((7, 8.75, 7), (9, 30, 9), "trunk", down=None, up=None)
    for i, (d, sgn) in enumerate((("x", 1), ("x", -1), ("z", 1), ("z", -1))):     # four fronds drooping to the sides
        frond = Pic(f"frond{i}", lambda cv, W, H: cv.paste(palm_tex(), 0, 0))
        if d == "x":
            x0, x1 = (8, 16) if sgn > 0 else (0, 8)
            rot = {"origin": [8, 30, 8], "axis": "z", "angle": -22.5 * sgn}
            m.box((x0, 30, 5.5), (x1, 30, 10.5), "palm", faces=(), rot=rot, up=frond, down=frond)
        else:
            z0, z1 = (8, 16) if sgn > 0 else (0, 8)
            rot = {"origin": [8, 30, 8], "axis": "x", "angle": 22.5 * sgn}
            m.box((5.5, 30, z0), (10.5, 30, z1), "palm", faces=(), rot=rot, up=frond, down=frond)
    for i, y in enumerate((31.5, 32.5)):        # and two pairs across the diagonals, spread flat over the crown
        frond = Pic(f"frond_d{i}", lambda cv, W, H: cv.paste(palm_tex(), 0, 0))
        rot = {"origin": [8, y, 8], "axis": "y", "angle": 45}
        if i == 0:
            m.box((0.5, y, 5.5), (15.5, y, 10.5), "palm", faces=(), rot=rot, up=frond, down=frond)
        else:
            m.box((5.5, y, 0.5), (10.5, y, 15.5), "palm", faces=(), rot=rot, up=frond, down=frond)
    return m


# ---------------------------------------------------------------- the big painting

def painting():
    def pic(cv, W, H):
        t = ramp(rgb("b08a40"), 5, 0.6, 1.3)
        cv.rect(0, 0, cv.w, cv.h, t[2])
        for k, c in enumerate((t[0], t[4], t[1], t[3])):     # a deep gilt frame, moulded
            cv.outline(k, k, cv.w - k, cv.h - k, c)
        x0, y0, x1, y1 = 5, 5, cv.w - 5, cv.h - 5
        # Shishkin's «Rye»: a summer sky, the golden field to the horizon, the road, tall pines along it
        cv.vgrad(x0, y0, x1, y0 + (y1 - y0) // 2, [rgb("8ab0d8"), rgb("b8d0e8"), rgb("e8eef0")])
        cv.vgrad(x0, y0 + (y1 - y0) // 2, x1, y1, [rgb("d8b860"), rgb("c8a048"), rgb("a88030")])
        r = random.Random("rye")
        for _ in range(160):
            x, y = r.randrange(x0, x1), r.randrange(y0 + (y1 - y0) // 2, y1)
            cv.set(x, y, rgb("e8cc70") if r.random() < 0.6 else rgb("8a6a28"))
        for x in range(x0 + (x1 - x0) // 3, x0 + (x1 - x0) // 3 + 3):
            cv.vline(x, y0 + (y1 - y0) // 2, y1, rgb("b09060"))
        for k, px in enumerate((0.55, 0.68, 0.8, 0.9)):
            x = int(x0 + (x1 - x0) * px)
            top = y0 + 3 + k % 2 * 4
            cv.vline(x, top + 6, y0 + (y1 - y0) // 2 + 2, rgb("4a3020"))
            cv.disc(x, top + 4, 4, rgb("2e4a26"))
            cv.disc(x - 2, top + 7, 3, rgb("3a5a2e"))
        cv.rect(cv.w // 2 - 6, cv.h - 4, cv.w // 2 + 6, cv.h - 2, rgb("e8d080"))   # the little brass plate

    m = Model()
    # 1.9 x 1.2 m in a deep gilt frame, hung high over the sofa as in a museum
    m.box((0.5, 2, 14.5), (31.5, 22, 16), "frame_gold", north=Pic("rye", pic), south=None)
    return m


# ---------------------------------------------------------------- the chandelier on a long rod

def chandelier_long(on):
    lit = "crystal_lit" if on else "crystal"

    def build():
        m = Model()
        # a plaster rosette on the ceiling, a bronze rod a metre long, a ring of six arms with candle bulbs and hanging
        # cut-glass drops; the lowest point stays 2.4 m over the floor of a 4-block room
        m.box((2, 15.5, 2), (14, 16, 14), "stucco", up=None)
        m.box((4, 15, 4), (12, 15.5, 12), "stucco_shadow", up=None)
        m.box((6.5, 14.25, 6.5), (9.5, 15, 9.5), "bronze", up=None)
        m.box((7.5, -4, 7.5), (8.5, 14.25, 8.5), "bronze", up=None, down=None)
        m.box((5.5, -6, 5.5), (10.5, -4, 10.5), "bronze")
        m.box((7, -9, 7), (9, -6, 9), lit, shade=not on, up=None)
        for k in range(6):
            a = math.radians(k * 60)
            cx, cz = 8 + 5.5 * math.cos(a), 8 + 5.5 * math.sin(a)
            ax, az = 8 + 2.75 * math.cos(a), 8 + 2.75 * math.sin(a)
            m.box((min(ax, cx) - 0.4, -5.5, min(az, cz) - 0.4), (max(ax, cx) + 0.4, -5, max(az, cz) + 0.4), "bronze")
            m.box((cx - 1, -5, cz - 1), (cx + 1, -4.5, cz + 1), "bronze", down=None)
            m.box((cx - 0.5, -4.5, cz - 0.5), (cx + 0.5, -2.5, cz + 0.5), "candle", down=None)
            m.box((cx - 0.75, -2.5, cz - 0.75), (cx + 0.75, -1, cz + 0.75), "bulb" if on else "bulb_off", shade=not on, down=None)
            m.box((cx - 0.5, -7.5, cz - 0.5), (cx + 0.5, -5.5, cz + 0.5), lit, shade=not on, up=None)
        return m
    return build


# ---------------------------------------------------------------- the plaster cornice

def molding():
    m = Model()
    # a stepped plaster cornice where the wall meets the ceiling, with a row of small dentils under it
    m.box((0, 14, 13), (16, 16, 16), "stucco", south=None)
    m.box((0, 12.5, 14.5), (16, 14, 16), "stucco", south=None, up=None)
    for x in range(1, 16, 3):
        m.box((x, 11.5, 15), (x + 1.5, 12.5, 16), "stucco_shadow", south=None, up=None)
    return m


def make():
    textures()
    Furn("apt_buffet_stalin", "Дубовый буфет с резьбой", "Carved oak buffet", lambda: buffet(), color="OAK_TAN")
    Furn("apt_bookcase_tall", "Книжный шкаф до потолка", "Bookcase to the ceiling", lambda: bookcase_tall(), color="BROWN")
    Furn("apt_floor_clock", "Напольные часы", "Floor clock", lambda: floor_clock(), color="BROWN")
    Furn("apt_palm_tub", "Пальма в кадке", "Palm in a tub", lambda: palm(), color="GREEN", sound="GRASS", cutout=True)
    Furn("apt_painting_rye", "Картина «Рожь» в золочёной раме", "Painting: Rye, in a gilt frame", lambda: painting(), mount="WALL")
    Furn("apt_chandelier_long", "Бронзовая люстра с розеткой", "Bronze chandelier under a rosette", chandelier_long, mount="CEILING",
         toggle=True, light=15, color="GOLD", sound="GLASS", solid=False)
    Furn("apt_molding", "Лепной карниз", "Plaster cornice", lambda: molding(), mount="WALL", color="WHITE", sound="STONE", solid=False)
