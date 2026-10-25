"""More of the flat: the hall, the living room's showpieces, the nursery, the kitchen's small things and the stairwell.
Same conventions as fur_models.py (north space, front at low z, x to the viewer's left, a room 2 blocks high)."""
import math
import random

import fur_paint as P
from fur_paint import rgb, shade, mix, ramp
from furniture import Furn, Model, Pic, mat
from fur_models import (WALNUT, LIGHTWOOD, OAK, ENAMEL, CREAM, CHROME, BRASS, FABRICS, panel, knob, handle_bar, books, crystal,
                        wood_canvas, legs, lace_doily, test_card)


# ---------------------------------------------------------------- textures

def textures():
    mat("coat_brown", P.fabric(32, 32, rgb("5e4430"), "coatb", weave=1))
    mat("coat_grey", P.fabric(32, 32, rgb("6a6c70"), "coatg", weave=1))
    mat("coat_red", P.fabric(32, 32, rgb("8a3030"), "coatr", weave=1))
    mat("fur", fur_tex())
    mat("scarf", scarf_tex())
    mat("lacquer", lacquer())
    mat("keys", piano_keys())
    mat("felt_green", P.fabric(32, 32, rgb("3e6a40"), "felt", weave=1, nap=False))
    mat("leatherette", P.fabric(32, 32, rgb("6a2a22"), "lth", weave=1, nap=False))
    mat("pot_clay", pot_clay())
    mat("trunk", P.wood(32, 32, rgb("6a5a40"), "trunk", vertical=True, knots=False, spacing=3))
    mat("aloe", aloe_tex())
    mat("ficus_stalk", P.Canvas(32, 32, rgb("3a5a2a")))
    mat("mailbox_blue", P.enamel(32, 32, rgb("3a5a8a"), "mbb", chips=4))
    mat("mailbox_green", P.enamel(32, 32, rgb("4a6a48"), "mbg", chips=4))
    mat("panel_grey", P.enamel(32, 32, rgb("8a8e90"), "pg", chips=3))
    mat("chute", P.enamel(32, 32, rgb("6a7a70"), "chute", chips=6))
    mat("cork", cork())
    mat("doormat", doormat())
    mat("rubber_black", P.Canvas(32, 32, rgb("1e1e20")))
    mat("tyre", tyre())
    mat("red_paint", P.enamel(32, 32, rgb("b02a24"), "rp"))
    mat("blue_paint", P.enamel(32, 32, rgb("2a50a0"), "bp"))
    mat("yellow_paint", P.enamel(32, 32, rgb("e0b030"), "yp"))
    mat("green_paint", P.enamel(32, 32, rgb("3a8a3a"), "gp"))
    mat("bear", P.fabric(32, 32, rgb("a87a48"), "bear", weave=1))
    mat("sled_wood", P.wood(32, 32, rgb("c89a5a"), "sled", vertical=False, knots=False))
    mat("canvas_blue", P.fabric(32, 32, rgb("4a6a9a"), "pram", weave=1))
    mat("dial_glow", P.Canvas(32, 32, rgb("f0d890")))
    mat("dial_dark", P.Canvas(32, 32, rgb("8a7a50")))
    mat("felt_grey", P.fabric(32, 32, rgb("8a8a86"), "fg", weave=1))


def fur_tex():
    r = random.Random("fur")
    t = ramp(rgb("4a3a2a"), 5, 0.6, 1.3)
    cv = P.Canvas(32, 32, t[2])
    for _ in range(140):
        x, y = r.randrange(32), r.randrange(32)
        cv.vline(x, y, min(32, y + r.randint(1, 3)), t[r.choice((1, 3, 3, 4))])
    return cv


def scarf_tex():
    cv = P.Canvas(32, 32, rgb("c8b8a0"))
    for y in range(32):
        for x in range(32):
            if (x // 4 + y // 4) % 2 == 0:
                cv.set(x, y, rgb("a83030"))
    return cv


def lacquer():
    t = ramp(rgb("1e1a1a"), 5, 0.8, 2.4)
    cv = P.Canvas(32, 32, t[0])
    for y in range(32):
        for x in range(32):
            d = (x + y) % 32
            if d in (5, 6):
                cv.set(x, y, t[3])
            elif d in (4, 7, 18):
                cv.set(x, y, t[1])
    return cv


def piano_keys():
    cv = P.Canvas(32, 32, rgb("f2eedc"))
    for x in range(0, 32, 3):
        cv.vline(x, 0, 32, rgb("b8b4a4"))
    for x in (2, 5, 11, 14, 17, 23, 26, 29):
        cv.rect(x, 0, x + 2, 18, rgb("1a1a1c"))
    return cv


def pot_clay():
    cv = P.enamel(32, 32, rgb("a85a3a"), "clay")
    cv.hline(0, 32, 2, rgb("c87a5a")); cv.hline(0, 32, 3, rgb("8a4a2a"))
    return cv


def ficus_leaf(cv, W, H):
    """One glossy rubber-fig leaf along the face's long side: a dark oval, a pale midrib, a light rim where it catches the window."""
    t = ramp(rgb("2c5e2a"), 5, 0.6, 1.35)
    long_u = cv.w >= cv.h
    L, S = (cv.w, cv.h) if long_u else (cv.h, cv.w)
    for i in range(L):
        for j in range(S):
            a = (i + 0.5) / L * 2 - 1           # -1 stalk .. 1 tip
            b = (j + 0.5) / S * 2 - 1
            half = math.sqrt(max(0.0, 1 - a * a)) * (1 - 0.25 * max(0.0, a))  # a bit pointed at the tip
            if abs(b) > half:
                continue
            c = t[2] if b < 0 else t[1]
            if abs(b) > half - 0.28:
                c = t[3] if b < 0 else t[2]
            if abs(b) < 0.12 and a < 0.85:
                c = t[4]
            elif (i + 2 * j) % 7 == 0 and abs(b) > 0.3:
                c = t[3] if b < 0 else t[2]
            x, y = (i, j) if long_u else (j, i)
            cv.set(x, y, c)


def aloe_tex():
    """Fleshy grey-green aloe: a smooth gradient along the leaf, a few pale flecks, tiny white teeth at the edges."""
    t = ramp(rgb("5a8a4a"), 5, 0.7, 1.25)
    cv = P.Canvas(32, 32, t[2])
    cv.vgrad(0, 0, 32, 32, [t[3], t[2], t[1]])
    for (x, y) in ((5, 4), (20, 9), (11, 15), (26, 21), (7, 26), (17, 29)):
        cv.set(x, y, t[4]); cv.set(x + 1, y, t[4])
    for y in range(2, 32, 6):
        cv.set(0, y, rgb("e8eedc")); cv.set(31, y + 3 if y + 3 < 32 else y, rgb("e8eedc"))
    return cv


def cork():
    r = random.Random("cork")
    t = ramp(rgb("b48a5a"), 4, 0.8, 1.15)
    cv = P.Canvas(32, 32, t[2])
    for _ in range(120):
        cv.set(r.randrange(32), r.randrange(32), t[r.choice((0, 1, 3))])
    return cv


def doormat():
    cv = P.Canvas(32, 32, rgb("6a4a2e"))
    for y in range(32):
        for x in range(32):
            if (x + y) % 4 < 2:
                cv.set(x, y, rgb("7a5a38"))
    cv.outline(0, 0, 32, 32, rgb("3a2a1a"))
    cv.outline(1, 1, 31, 31, rgb("3a2a1a"))
    return cv


def tyre():
    cv = P.Canvas(32, 32, rgb("26262a"))
    for x in range(0, 32, 3):
        cv.vline(x, 0, 32, rgb("3a3a3e"))
    return cv


def carpet_pic(cv, W, H, field=rgb("9a2228"), border=rgb("1e2a5a"), ivory=rgb("e2cc98"), gold=rgb("c8903a")):
    """The wall carpet: a red field, three borders (dark blue, ivory chain, dark blue), a central medallion and corner pieces,
    the classic "Persian" pattern every Soviet family hung over the sofa."""
    w, h = cv.w, cv.h
    cx, cy = w / 2, h / 2
    t = ramp(field, 5, 0.75, 1.2)
    for y in range(h):
        for x in range(w):
            e = min(x, w - 1 - x, y, h - 1 - y)
            c = t[2]
            if e < 3:
                c = border if e != 1 else mix(border, ivory, 0.2)
            elif e < 6:
                c = ivory if ((x + y) // 2) % 3 else gold
                if e == 4 and (x + y) % 4 == 0:
                    c = field
            elif e < 8:
                c = border
            else:
                dx, dy = abs(x + 0.5 - cx) / (w * 0.22), abs(y + 0.5 - cy) / (h * 0.30)
                d = dx + dy
                if d < 0.35:
                    c = ivory if d > 0.18 else field
                    if d < 0.08:
                        c = border
                elif d < 0.45:
                    c = border
                elif d < 0.55:
                    c = gold if (x + y) % 2 else ivory
                elif d < 1.0:
                    c = t[1] if (int(d * 20) + x // 3) % 3 == 0 else t[2]
                    if (x % 8 == 4 and y % 6 == 3):
                        c = ivory
                # corner quarter-medallions
                for qx, qy in ((8, 8), (w - 9, 8), (8, h - 9), (w - 9, h - 9)):
                    q = abs(x - qx) + abs(y - qy)
                    if q < 7:
                        c = border if q > 4 else (gold if q > 2 else ivory)
            cv.set(x, y, c)
    # tiny tassels along the short edges are drawn by the model; here a slight wear in the middle


# ---------------------------------------------------------------- hall

def coat_rack():
    m = Model()
    # a board with hooks at 1.65 m, a hat shelf above with a fur hat, two coats and a scarf hanging down to 0.8 m
    m.box((0.5, 25, 15), (15.5, 28, 16), "walnut", north=Pic("board", lambda cv, W, H: panel(cv, 0, 0, cv.w, cv.h, WALNUT, "crb", vertical=False)))
    m.box((0.5, 30, 9), (15.5, 30.5, 16), "walnut")
    for x in (1.5, 14):
        m.box((x, 28, 15), (x + 0.5, 30, 16), "walnut", up=None, down=None)
    for x in (3, 7.5, 12):
        m.box((x, 26, 13.5), (x + 1, 27, 15), "brass", south=None)
    m.box((3, 30.5, 10), (9, 31.5, 15), "fur")
    m.box((3.5, 31.5, 10.5), (8.5, 32, 14.5), "fur")
    # the coats: shoulders, body and sleeves
    for x0, mt, low in ((1.5, "coat_brown", 12), (8.5, "coat_grey", 14)):
        m.box((x0, low, 11), (x0 + 6, 26, 14.5), mt, north=Pic("coat" + mt, coat_front(mt)))
        m.box((x0 + 0.5, 26, 11.5), (x0 + 5.5, 26.5, 14), mt, down=None)
    m.box((6.5, 18, 10.5), (8.5, 26.5, 11), "scarf", south=None)
    return m


def coat_front(material):
    base = {"coat_brown": rgb("5e4430"), "coat_grey": rgb("6a6c70")}[material]

    def p(cv, W, H):
        cv.paste(P.fabric(cv.w, cv.h, base, material + "f", weave=1), 0, 0)
        t = ramp(base, 5, 0.6, 1.2)
        mid = cv.w // 2
        cv.vline(mid, 0, cv.h, t[0])
        for y in range(4, cv.h - 4, 6):
            cv.set(mid + 2, y, rgb("2a2420")); cv.set(mid + 2, y + 1, rgb("4a4038"))
        cv.vline(1, 0, cv.h, t[1]); cv.vline(cv.w - 2, 0, cv.h, t[1])
        for x in range(2, cv.w - 2):  # the collar
            cv.set(x, 1 + abs(x - mid) // 3, t[3])
        cv.hline(1, mid - 1, cv.h // 2 + 3, t[1]); cv.hline(mid + 2, cv.w - 1, cv.h // 2 + 3, t[1])  # pockets
    return p


def shoe_cabinet():
    m = Model()
    m.box((0.5, 0, 9), (15.5, 7, 16), "lightwood", down=None,
          north=Pic("doors", lambda cv, W, H: (panel(cv, 0, 0, cv.w // 2, cv.h, LIGHTWOOD, "sc1", frame=1),
                                               panel(cv, cv.w // 2, 0, cv.w, cv.h, LIGHTWOOD, "sc2", frame=1),
                                               knob(cv, cv.w // 2 - 3, cv.h // 2 - 1, rgb("e0e0e0")), knob(cv, cv.w // 2 + 1, cv.h // 2 - 1, rgb("e0e0e0")))))
    # felt slippers with a pompon, and boots
    for x, c in ((2, "felt_grey"), (4.5, "felt_grey")):
        m.box((x, 0, 3), (x + 2, 1.5, 7.5), c, down=None)
        m.box((x + 0.5, 1.5, 3.5), (x + 1.5, 2, 4.5), "red_paint", down=None)
    for x in (9, 12):
        m.box((x, 0, 4), (x + 2.5, 1.5, 8.5), "rubber_black", down=None)
        m.box((x, 1.5, 6), (x + 2.5, 6, 8.5), "rubber_black", down=None)
    m.box((10, 7, 10), (14, 8.5, 14), "plastic_beige", down=None)  # a shoe-brush box
    return m


def hall_mirror():
    def mirror(cv, W, H):
        t = ramp(WALNUT, 6, 0.6, 1.2)
        wood_canvas(cv, WALNUT, "hm", vertical=True)
        cv.rect(3, 3, cv.w - 3, cv.h - 3, rgb("a8bac2"))
        for y in range(3, cv.h - 3):
            for x in range(3, cv.w - 3):
                if (x - y) % 14 in (0, 1, 2):
                    cv.set(x, y, rgb("dae6ec"))
                elif (x - y) % 14 in (6,):
                    cv.set(x, y, rgb("c2d2da"))
        cv.outline(2, 2, cv.w - 2, cv.h - 2, t[5])
        cv.outline(1, 1, cv.w - 1, cv.h - 1, t[1])

    m = Model()
    m.box((1, 0, 8), (15, 10, 16), "walnut", down=None,
          north=Pic("drawers", lambda cv, W, H: [panel(cv, 0, k * cv.h // 2, cv.w, (k + 1) * cv.h // 2, WALNUT, f"hmd{k}", vertical=False, frame=1)
                                                 for k in range(2)] and [handle_bar(cv, cv.w // 2 - 4, k * cv.h // 2 + cv.h // 4, cv.w // 2 + 4, BRASS)
                                                                         for k in range(2)]))
    m.box((0.5, 10, 7.5), (15.5, 11, 16), "walnut", down=None)
    m.box((2, 11, 14.5), (14, 29, 15.5), "walnut", north=Pic("mirror", mirror))
    m.box((7, 11, 15.5), (9, 13, 16), "walnut", up=None, down=None)
    # a perfume bottle ("Красная Москва"), a brush and a doily
    m.box((3, 11, 9), (5, 14, 11), "glass_red", down=None)
    m.box((3.5, 14, 9.5), (4.5, 15, 10.5), "brass", down=None)
    m.box((9, 11, 9.5), (13, 12, 11), "walnut", down=None)
    m.box((9.5, 12, 9.75), (12.5, 12.5, 10.75), "black", down=None)
    return m


def wall_phone():
    m = Model()
    # a small shelf at 0.85 m with the rotary phone, the notebook of numbers, the wire going up the wall
    m.box((3, 13, 11), (13, 14, 16), "walnut")
    m.box((4, 14, 11.5), (10, 15.5, 15), "phone_body", up=Pic("dial", phone_dial))
    m.box((4.5, 15.5, 12), (9.5, 16, 14.5), "phone_body", down=None)
    m.box((4, 16, 12.5), (10, 17, 13.5), "phone_body")
    m.box((4, 15.5, 12.5), (5, 16, 13.5), "phone_body", up=None, down=None)
    m.box((9, 15.5, 12.5), (10, 16, 13.5), "phone_body", up=None, down=None)
    m.box((10.5, 14, 12), (12.5, 14.5, 15), "paper", down=None)
    m.box((11.5, 14.5, 15.5), (12, 32, 16), "black", up=None)
    return m


def phone_dial(cv, W, H):
    cv.rect(0, 0, cv.w, cv.h, rgb("b8302a"))
    cx, cy = cv.w / 2, cv.h / 2 + 1
    cv.disc(cx, cy, 4.5, rgb("e8e4d8"), ring=rgb("8a8a88"))
    for k in range(10):
        a = math.pi * (0.25 + k * 0.15)
        cv.set(int(cx + 3.2 * math.cos(a)), int(cy + 3.2 * math.sin(a)), rgb("2a2a2a"))
    cv.disc(cx, cy, 1.4, rgb("c8c4b8"))


def electric_meter():
    def face(cv, W, H):
        t = ramp(rgb("c8c8c0"), 5, 0.7, 1.08)
        cv.rect(0, 0, cv.w, cv.h, t[3])
        cv.outline(0, 0, cv.w, cv.h, t[1])
        # the meter: black body, a window with the counter and the spinning disc; two white fuse plugs below
        cv.rect(4, 2, cv.w - 4, cv.h // 2 + 2, rgb("2a2a2c"))
        cv.rect(6, 4, cv.w - 6, 9, rgb("e8e8e0"))
        for i in range(5):
            cv.rect(7 + i * 3, 5, 9 + i * 3, 8, rgb("1a1a1a") if i < 4 else rgb("c02020"))
            cv.set(7 + i * 3, 6, rgb("f0f0f0"))
        cv.hline(8, cv.w - 8, 11, rgb("b0b0b0"))
        cv.hline(8, cv.w - 8, 12, rgb("e04040"))
        for x in (8, cv.w - 12):
            cv.disc(x + 2, cv.h - 6, 2.4, rgb("eeeeea"), ring=rgb("8a8a8a"))
            cv.set(x + 2, cv.h - 6, rgb("5a5a5a"))

    m = Model()
    m.box((2, 4, 13), (14, 14, 16), "panel_grey", north=Pic("meter", face))
    m.box((4, 14, 15), (5, 16, 16), "black", up=None)
    return m


# ---------------------------------------------------------------- living room showpieces

def sideboard():
    def front(cv, W, H):
        wood_canvas(cv, LIGHTWOOD, "sideboard", vertical=True, polish=1.4)
        t = ramp(LIGHTWOOD, 6, 0.6, 1.2)
        w, h = cv.w, cv.h
        lower = 30
        for k in range(2):
            panel(cv, 1 + k * (w - 2) // 2, h - lower, 1 + (k + 1) * (w - 2) // 2, h - 3, LIGHTWOOD, f"sbl{k}", polish=1.4)
        knob(cv, w // 2 - 3, h - lower // 2, BRASS); knob(cv, w // 2 + 1, h - lower // 2, BRASS)
        # the glass part: a mirror back, two glass shelves, a tea set and crystal, sliding glass doors
        gy0, gy1 = 6, h - lower - 4
        cv.vgrad(2, gy0, w - 2, gy1, [rgb("9aaab0"), rgb("7a8a92"), rgb("6a7880")])
        thirds = [gy0 + (gy1 - gy0) * k // 3 for k in range(4)]
        for k in range(1, 3):
            cv.hline(2, w - 2, thirds[k], rgb("c8dce2"))
            cv.hline(2, w - 2, thirds[k] + 1, rgb("5a666c"))
        crystal(cv, 3, thirds[0] + 1, w - 3, thirds[1], "sb1")
        tea_set(cv, 3, thirds[1] + 2, w - 3, thirds[2])
        crystal(cv, 3, thirds[2] + 2, w - 3, gy1, "sb3")
        cv.outline(1, gy0 - 1, w - 1, gy1 + 1, t[1])
        cv.vline(w // 2, gy0, gy1, t[2])
        for y in range(gy0, gy1):
            x = 4 + (y - gy0) // 2
            if y % 9 < 3 and x < w - 3:
                cv.set(x, y, rgb("e4f2f6"))
        cv.rect(0, 0, w, 3, t[4]); cv.hline(0, w, 2, t[1])
        cv.rect(0, h - 2, w, h, t[0])
        cv.rect(1, h - lower - 3, w - 1, h - lower, t[3]); cv.hline(1, w - 1, h - lower - 1, t[1])

    m = Model()
    fr = Pic("front", front, area=(-16, -10, 16, 16))
    m.box((0, 0, 9), (32, 26, 16), "lightwood", north=None, down=None)
    m.box((0, 0, 8.5), (32, 26, 9), "lightwood", north=fr, south=None, down=None)
    m.box((-0.5 + 0.5, 26, 8), (32, 26.5, 16), "lightwood", down=None)
    return m


def tea_set(cv, x0, y0, x1, y1):
    """A gilded tea set with cobalt net (like the Leningrad factory's): a teapot, cups on saucers, a sugar bowl."""
    x = x0 + 1
    white, gold, cobalt = rgb("f4f2ea"), rgb("d8b048"), rgb("2a4aa0")
    cv.rect(x, y1 - 6, x + 6, y1 - 1, white); cv.hline(x, x + 6, y1 - 6, gold); cv.rect(x + 1, y1 - 4, x + 5, y1 - 2, cobalt)
    cv.rect(x + 6, y1 - 4, x + 8, y1 - 3, white); cv.set(x + 2, y1 - 7, gold); cv.set(x + 3, y1 - 7, gold)
    x += 10
    while x < x1 - 5:
        cv.hline(x - 1, x + 5, y1 - 1, white); cv.hline(x - 1, x + 5, y1 - 2, gold)
        cv.rect(x, y1 - 5, x + 4, y1 - 2, white); cv.hline(x, x + 4, y1 - 5, gold); cv.set(x + 1, y1 - 4, cobalt); cv.set(x + 2, y1 - 3, cobalt)
        cv.set(x + 4, y1 - 4, white)
        x += 7


def bookcase():
    def front(cv, W, H):
        wood_canvas(cv, WALNUT, "bookcase", vertical=True, polish=1.0)
        t = ramp(WALNUT, 6, 0.6, 1.2)
        w, h = cv.w, cv.h
        cv.rect(2, 3, w - 2, h - 10, rgb("2a1c14"))
        rows = 4
        hh = (h - 13) // rows
        for k in range(rows):
            y0 = 3 + k * hh
            books(cv, 3, y0 + 1, w - 3, y0 + hh - 1, f"bc{k}")
            cv.hline(2, w - 2, y0 + hh - 1, t[3])
        cv.vline(w // 2, 3, h - 10, t[1])
        for y in range(3, h - 10):  # the glass doors
            x = 3 + (y % 23)
            if y % 23 < 4 and x < w - 3:
                cv.set(x, y, rgb("d8e4e8"))
        cv.outline(1, 2, w - 1, h - 9, t[0])
        panel(cv, 1, h - 9, w - 1, h - 2, WALNUT, "bcd", vertical=False, frame=1)
        handle_bar(cv, w // 2 - 4, h - 6, w // 2 + 4, BRASS)
        cv.rect(0, 0, w, 2, t[4]); cv.rect(0, h - 2, w, h, t[0])

    m = Model()
    m.box((0.5, 0, 9), (15.5, 30, 16), "walnut", down=None, north=Pic("front", front))
    return m


def piano():
    def front(cv, W, H):
        t = ramp(rgb("1e1a1a"), 5, 0.8, 2.6)
        cv.rect(0, 0, cv.w, cv.h, t[0])
        for y in range(cv.h):
            for x in range(cv.w):
                if (x + y * 2) % 40 in (6, 7):
                    cv.set(x, y, t[2])
        # gilt name «Красный Октябрь» as a thin line and the two candle brackets' plates
        cv.hline(cv.w // 2 - 10, cv.w // 2 + 10, 6, rgb("c8a040"))
        cv.hline(cv.w // 2 - 6, cv.w // 2 + 6, 8, rgb("a08030"))
        cv.outline(3, 12, cv.w - 3, cv.h - 4, t[1])

    m = Model()
    # 1.5 m wide upright, 1.3 m tall: body, the keyboard shelf, keys, the lid, legs, two brass candlesticks, a metronome
    fr = Pic("front", front, area=(-8, -5, 24, 8))
    m.box((-8, 0, 10), (24, 21, 16), "lacquer", north=fr, down=None)
    m.box((-8, 10, 5), (24, 11.5, 10), "lacquer", down=None, up=None)
    m.box((-7, 11, 5.5), (23, 11.5, 10), "keys", faces=("up",))
    m.els[-1].f[1] = 11.5
    m.box((-8, 11.5, 5), (-7, 13, 10), "lacquer", down=None)
    m.box((23, 11.5, 5), (24, 13, 10), "lacquer", down=None)
    m.box((-7, 12, 9), (23, 13, 10), "lacquer", down=None)
    m.box((-7, 0, 6), (-5.5, 10, 7.5), "lacquer", up=None, down=None)
    m.box((21.5, 0, 6), (23, 10, 7.5), "lacquer", up=None, down=None)
    m.box((-6, 0.5, 9), (-5, 1.5, 10), "brass", up=None, down=None)
    for x in (-5, 19):
        m.box((x, 17, 9.5), (x + 2, 17.5, 10), "brass", faces=("north", "up", "down", "east", "west"))
        m.box((x + 0.5, 17.5, 8.5), (x + 1.5, 18, 9.5), "brass", south=None)
        m.box((x + 0.75, 18, 8.75), (x + 1.25, 20, 9.25), "candle", down=None)
    m.box((10, 21, 11), (13, 25, 14), "walnut", down=None, north=Pic("metronome", metronome))
    return m


def metronome(cv, W, H):
    wood_canvas(cv, WALNUT, "metro", vertical=True)
    cv.rect(2, 2, cv.w - 2, cv.h - 2, rgb("d8d0b8"))
    cv.vline(cv.w // 2, 2, cv.h - 3, rgb("2a2a2a"))
    cv.set(cv.w // 2, 4, rgb("c8a040")); cv.set(cv.w // 2 + 1, 4, rgb("c8a040"))


def piano_stool():
    m = Model()
    m.box((6.5, 0, 6.5), (9.5, 1, 9.5), "lacquer", down=None)
    for x, z in ((4, 4), (11, 4), (4, 11), (11, 11)):
        m.box((x, 0, z), (x + 1, 7, z + 1), "lacquer", up=None, down=None)
    m.box((7.25, 1, 7.25), (8.75, 7, 8.75), "brass", up=None, down=None)
    m.box((3.5, 7, 3.5), (12.5, 7.5, 12.5), "lacquer")
    m.box((4, 7.5, 4), (12, 8.5, 12), "leatherette", down=None)
    return m


def radiola(on):
    def front(cv, W, H):
        wood_canvas(cv, LIGHTWOOD, "radiola", vertical=False, polish=1.4)
        t = ramp(LIGHTWOOD, 6, 0.6, 1.2)
        w, h = cv.w, cv.h
        cv.rect(2, 3, w - 2, h - 3, rgb("c8b890"))
        for y in range(3, h - 3):
            for x in range(2, w - 2):
                if (x + y) % 3 == 0:
                    cv.set(x, y, rgb("a89870"))
        # the station dial: cities of the world on a lit scale, the needle
        dial = rgb("f2dc90") if on else rgb("b8a878")
        cv.rect(4, 4, w - 4, 10, dial)
        for x in range(5, w - 5, 3):
            cv.set(x, 6, rgb("6a4a20")); cv.set(x + 1, 8, rgb("6a4a20"))
        cv.vline(w // 2 + 3, 4, 10, rgb("c02020"))
        if on:
            cv.set(w - 6, 12, rgb("50e050")); cv.set(w - 7, 12, rgb("30a030"))
        for x in (6, w // 2, w - 8):
            knob(cv, x, h - 7, rgb("e8e0c8"))
        cv.bevel(0, 0, w, h, t[5], t[1])

    def build():
        m = Model()
        # a radio-gramophone "Ригонда" on splayed legs: the record player under the lid, a record on it
        for x, z in ((2, 4), (13, 4), (2, 13), (13, 13)):
            m.box((x, 0, z), (x + 1, 4, z + 1), "walnut", up=None, down=None)
        m.box((1, 4, 3), (15, 11.5, 15), "lightwood", north=Pic("front_" + ("on" if on else "off"), front), down=None)
        m.box((1, 11.5, 3), (15, 12, 15), "lightwood", down=None)
        m.box((4, 12, 6), (11, 12.5, 13), "record", down=None)
        m.box((12, 12, 11), (12.5, 13, 12), "chrome", down=None)
        return m
    return build


def wall_carpet():
    m = Model()
    # 2.5 x 1.6 m, from 0.3 to 1.9 m above the floor, hung on the wall behind the sofa; fringe at the short ends
    m.box((-12, 4.5, 15.5), (28, 30, 16), "carpet_side", north=Pic("carpet", carpet_pic), south=None)
    for y in range(5, 30, 2):
        m.box((27.5 if False else 28, y, 15.5), (28.5, y + 1, 16), "carpet_fringe", faces=("north", "up", "down", "east"))
        m.box((-12.5, y, 15.5), (-12, y + 1, 16), "carpet_fringe", faces=("north", "up", "down", "west"))
    return m


def floor_rug():
    def pic(cv, W, H):
        carpet_pic(cv, W, H, field=rgb("7a2a2a"), border=rgb("2a3048"), ivory=rgb("d8c090"), gold=rgb("b07a30"))

    m = Model()
    m.box((-6, 0, -14), (22, 0.5, 30), "carpet_side", up=Pic("rug", pic), down=None)
    return m


def ficus():
    m = Model()
    # a rubber fig in a clay pot: 1.6 m in all; big leaves on short stalks spiral up the trunk, tilted up towards the light
    m.box((4.5, 0, 4.5), (11.5, 6, 11.5), "pot_clay", down=None)
    m.box((5, 5.75, 5), (11, 5.75, 11), "soil", faces=("up",))
    m.box((7.5, 5.75, 7.5), (8.5, 25, 8.5), "trunk", up=None, down=None)
    leaves = ((10, "e"), (12, "w"), (14, "s"), (16, "n"), (18, "e"), (19.5, "s"), (21, "w"), (22.5, "n"), (24, "e"))
    for i, (y, d) in enumerate(leaves):
        ln = 7 if y < 20 else 5.5
        wd = 3.5 if y < 20 else 3
        if d in "ew":
            x0, x1 = (8.5, 8.5 + ln) if d == "e" else (7.5 - ln, 7.5)
            f, t = (x0, y, 8 - wd / 2), (x1, y, 8 + wd / 2)
            rot = {"origin": [8.5 if d == "e" else 7.5, y, 8], "axis": "z", "angle": 22.5 if d == "e" else -22.5}
        else:
            z0, z1 = (8.5, 8.5 + ln) if d == "s" else (7.5 - ln, 7.5)
            f, t = (8 - wd / 2, y, z0), (8 + wd / 2, y, z1)
            rot = {"origin": [8, y, 8.5 if d == "s" else 7.5], "axis": "x", "angle": -22.5 if d == "s" else 22.5}
        leaf = Pic(f"leaf{i}", ficus_leaf)
        leaf_d = Pic(f"leafd{i}", ficus_leaf)
        m.box(f, t, "soil", faces=(), rot=rot, up=leaf, down=leaf_d)
    # the rolled new leaf at the crown, in its pink sheath
    m.box((7.5, 25, 7.5), (8.5, 28, 8.5), "ficus_stalk", down=None)
    m.box((7.75, 28, 7.75), (8.25, 29, 8.25), "red_paint", down=None)
    return m


def aloe():
    m = Model()
    # an aloe on the windowsill: fleshy leaves fanning out of a small pot, each tapering to a point
    m.box((5, 0, 5), (11, 4, 11), "pot_clay", down=None)
    m.box((5.5, 3.75, 5.5), (10.5, 3.75, 10.5), "soil", faces=("up",))
    for (x, z, h, ax, r) in ((7, 7, 9, "z", 0), (5.5, 7, 7, "z", 22.5), (8.5, 7, 7, "z", -22.5), (7, 5.5, 7, "x", -22.5),
                             (7, 8.5, 6, "x", 22.5), (5.5, 5.5, 5, "x", -45), (8.5, 8.5, 5, "z", -45)):
        o = [x + 1, 3.75, z + 1]
        rot = {"origin": o, "axis": ax, "angle": r} if r else None
        m.box((x, 3.75, z), (x + 2, 3.75 + h * 0.6, z + 2), "aloe", down=None, rot=rot)
        m.box((x + 0.5, 3.75 + h * 0.6, z + 0.5), (x + 1.5, 3.75 + h, z + 1.5), "aloe", down=None, rot=rot)
    return m


def picture(kind):
    def pic(cv, W, H):
        t = ramp(rgb("b08a40"), 5, 0.6, 1.3)
        cv.rect(0, 0, cv.w, cv.h, t[2])
        cv.outline(0, 0, cv.w, cv.h, t[0])
        cv.outline(1, 1, cv.w - 1, cv.h - 1, t[4])
        x0, y0, x1, y1 = 3, 3, cv.w - 3, cv.h - 3
        if kind == "bears":  # morning in a pine forest: misty light, a fallen pine, three cubs and the mother bear
            cv.vgrad(x0, y0, x1, y1, [rgb("c8d0b0"), rgb("8a9a70"), rgb("4a5a3a"), rgb("3a4a2e")])
            for k, x in enumerate(range(x0 + 2, x1 - 2, 6)):
                cv.rect(x, y0, x + 2, y1, rgb("4a3a2a") if k % 2 else rgb("5a4a32"))
            cv.rect(x0, y1 - 8, x1, y1 - 5, rgb("6a5034"))
            for i, x in enumerate(x0 + (x1 - x0) * k // 10 for k in (2, 4, 6, 8)):
                s = 3 if i == 3 else 2
                cv.rect(x, y1 - 8 - s, x + s + 1, y1 - 8, rgb("3a2a1e"))
                cv.set(x + s, y1 - 9 - s, rgb("3a2a1e"))
            cv.dither(x0, y0, x1, y0 + (y1 - y0) // 3, rgb("c8d0b0"), rgb("e8ecd8"), 0.4)
        else:  # the ninth wave
            cv.vgrad(x0, y0, x1, y1, [rgb("e8b060"), rgb("d08040"), rgb("4a6a7a"), rgb("2a4a5a")])
            cv.disc((x0 + x1) / 2, y0 + 6, 4, rgb("fff0b0"))
            for x in range(x0, x1):
                y = int(y1 - 6 + 3 * math.sin(x / 4))
                cv.vline(x, y, y1, rgb("2a5a6a"))
                cv.set(x, y, rgb("e0f0e8"))
            cv.rect(x0 + (x1 - x0) // 3, y1 - 8, x0 + (x1 - x0) // 3 + 5, y1 - 6, rgb("5a3a20"))
    m = Model()
    # a framed reproduction 0.95 x 0.6 m, hung in the upper cell at 1.2..1.8 m
    m.box((0.5, 3, 15), (15.5, 13, 16), "frame_gold", north=Pic("pic_" + kind, pic), south=None)
    return m


def wall_clock():
    def face(cv, W, H):
        wood_canvas(cv, WALNUT, "clock", vertical=True, polish=1.2)
        t = ramp(WALNUT, 6, 0.6, 1.2)
        w = cv.w
        cv.disc(w / 2, w / 2, w / 2 - 2, rgb("f0e8d0"), ring=rgb("c8a040"))
        for k in range(12):
            a = k * math.pi / 6
            cv.set(int(w / 2 + (w / 2 - 4) * math.cos(a)), int(w / 2 + (w / 2 - 4) * math.sin(a)), rgb("2a2a2a"))
        cv.vline(w // 2, w // 2 - 5, w // 2 + 1, rgb("1a1a1a"))
        cv.hline(w // 2, w // 2 + 4, w // 2, rgb("1a1a1a"))
        # the glass door over the pendulum
        cv.rect(4, w + 1, w - 4, cv.h - 3, rgb("3a2a1c"))
        cv.vline(w // 2, w + 1, cv.h - 10, rgb("c8a040"))
        cv.disc(w / 2, cv.h - 9, 2.5, rgb("e0b850"), ring=rgb("a08030"))
        cv.bevel(0, 0, w, cv.h, t[5], t[1])

    m = Model()
    m.box((4, 0, 13.5), (12, 15, 16), "walnut", north=Pic("face", face))
    m.box((3.5, 15, 13), (12.5, 16, 16), "walnut")
    return m


def dining_table(festive):
    def cloth(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("f4f1e6"))
        for y in range(cv.h):
            for x in range(cv.w):
                if (x % 8 == 0 or y % 8 == 0) and (x + y) % 2:
                    cv.set(x, y, rgb("e2ded0"))
        cv.outline(0, 0, cv.w, cv.h, rgb("d8d2c0"))

    m = Model()
    # 1.6 x 0.9 m with a white tablecloth hanging 0.15 m over the edge
    for x in (1, 29.5):
        for z in (2.5, 12):
            m.box((x, 0, z), (x + 1.5, 12, z + 1.5), "walnut_v", up=None, down=None)
    m.box((0, 12, 1.5), (32, 12.5, 14.5), "tablecloth_side", up=Pic("cloth", cloth), down=None)
    m.box((0, 9.5, 1.5), (32, 12, 2), "tablecloth_side", up=None, south=None)
    m.box((0, 9.5, 14), (32, 12, 14.5), "tablecloth_side", up=None, north=None)
    m.box((0, 9.5, 2), (0.5, 12, 14), "tablecloth_side", up=None, east=None)
    m.box((31.5, 9.5, 2), (32, 12, 14), "tablecloth_side", up=None, west=None)
    if festive:  # New Year: Olivier salad, herring under a fur coat, tangerines, champagne, a cake, plates
        for x in (3, 13, 21):
            m.box((x, 12.5, 3), (x + 4, 13, 7), "plate", down=None)
            m.els[-1].faces["up"] = "plate"
        m.box((3.5, 12.5, 9), (7.5, 14, 13), "salad_bowl", down=None, up="olivier")
        m.box((9, 12.5, 9.5), (14, 13.5, 12.5), "plate_rim", down=None, up="herring")
        m.box((16, 12.5, 9), (20, 13, 13), "plate_rim", down=None, up="tangerines")
        for (x, z) in ((16.5, 9.5), (18, 10.5), (17, 11.5), (18.5, 12)):
            m.box((x, 13, z), (x + 1, 14, z + 1), "tangerine", down=None)
        m.box((23, 12.5, 10), (25, 19, 12), "bottle_green", down=None)
        m.box((23.5, 19, 10.5), (24.5, 21, 11.5), "foil", down=None)
        m.box((26, 12.5, 4), (30, 15, 8), "cake", down=None, up="cake_top")
        for x in (8, 18, 24.5):
            m.box((x, 12.5, 4.5), (x + 1, 15, 5.5), "glass", down=None)
    return m


def desk(on):
    def drawers(cv, W, H):
        wood_canvas(cv, OAK, "deskd", vertical=False, polish=0.6)
        for k in range(3):
            y0 = k * cv.h // 3
            panel(cv, 0, y0, cv.w, y0 + cv.h // 3, OAK, f"dd{k}", vertical=False, frame=1)
            handle_bar(cv, cv.w // 2 - 3, y0 + cv.h // 6, cv.w // 2 + 3, BRASS)

    def build():
        m = Model()
        # a writing desk 1.2 m: drawer pedestal, a green baize top, a green-shaded lamp (switches), books, an ink pot, a globe
        m.box((0, 0, 3), (12, 12, 15), "oak", north=Pic("drawers", drawers), down=None)
        m.box((28, 0, 3), (31, 12, 15), "oak", down=None)
        m.box((12, 9, 14), (28, 12, 15), "oak", up=None, down=None)
        m.box((0, 12, 2), (32, 13, 16), "oak", down=None, up=Pic("baize", lambda cv, W, H: baize(cv)))
        m.box((24, 13, 11), (28, 13.5, 14), "brass", down=None)
        m.box((25.75, 13.5, 12.25), (26.25, 18, 12.75), "brass", up=None, down=None)
        m.box((23.5, 18, 9.5), (28.5, 20, 13), "lampshade_green_lit" if on else "lampshade_green", shade=not on, down=None)
        m.box((24.5, 18.5, 10.5), (27.5, 18.5, 12), "bulb" if on else "bulb_off", faces=("down",), shade=not on)
        m.box((14, 13, 10), (21, 15, 14), "books_stack", down=None)
        m.box((3, 13, 4), (8, 13.5, 9), "paper", down=None)
        m.box((9, 13, 10), (10.5, 14.5, 11.5), "ink", down=None)
        m.box((2, 13, 11), (5, 14, 14), "walnut", down=None)
        m.box((3.25, 14, 12.25), (3.75, 16, 12.75), "brass", up=None, down=None)
        m.box((1.5, 16, 10.5), (5.5, 20, 14.5), "globe")
        return m
    return build


def baize(cv):
    cv.paste(P.fabric(cv.w, cv.h, rgb("3e6a40"), "baize", weave=1, nap=False), 0, 0)
    cv.outline(0, 0, cv.w, cv.h, rgb("6a4a2a"))
    cv.outline(1, 1, cv.w - 1, cv.h - 1, rgb("8a6a3a"))


def sewing_machine():
    m = Model()
    # a Podolsk treadle machine: cast-iron frame with the wheel, a wooden top, the black and gilt head
    for x in (1.5, 13.5):
        m.box((x, 0, 4), (x + 1, 11, 12), "iron_black", up=None, down=None, north="lattice", south="lattice")
    m.box((2.5, 1, 6), (13.5, 1.5, 11), "iron_black", down=None)
    m.box((10, 2, 7.5), (13, 9, 8.5), "iron_black")
    m.box((0.5, 11, 3), (15.5, 12, 13), "oak", down=None)
    m.box((3, 12, 7), (12, 13, 10), "lacquer", down=None)
    m.box((3, 13, 7.5), (4.5, 18, 9.5), "lacquer", down=None)
    m.box((3, 18, 7.5), (12, 20, 9.5), "lacquer", north=Pic("head", sewing_head))
    m.box((10.5, 13, 7.75), (11.5, 18, 8.75), "chrome", up=None, down=None)
    m.box((12, 17, 7), (13.5, 20, 10), "chrome")
    m.box((6, 12, 3.5), (9, 12.5, 6), "fabric_blue", down=None)
    return m


def sewing_head(cv, W, H):
    cv.rect(0, 0, cv.w, cv.h, rgb("1c1a1a"))
    for x in range(2, cv.w - 2, 2):
        cv.set(x, 1, rgb("c8a040"))
        cv.set(x + 1, cv.h - 2, rgb("c8a040"))
    P.text(cv, (cv.w - P.text_width("ПМЗ")) // 2, 0, "ПМЗ", rgb("d8b050"))


def crib():
    m = Model()
    # a cot with bars on all sides, a mattress and a blanket, a teddy bear in the corner
    for x in (0.5, 14.5):
        for z in (2.5, 13.5):
            m.box((x, 0, z), (x + 1, 13, z + 1), "lightwood_v", down=None)
    for z in (2.5, 13.5):
        m.box((1.5, 12, z + 0.25), (14.5, 13, z + 0.75), "lightwood", up=None, down=None)
        m.box((1.5, 4, z + 0.25), (14.5, 5, z + 0.75), "lightwood", up=None, down=None)
        for x in range(3, 14, 2):
            m.box((x, 5, z + 0.25), (x + 0.5, 12, z + 0.75), "lightwood_v", up=None, down=None)
    for x in (0.5, 14.5):
        m.box((x + 0.25, 12, 3.5), (x + 0.75, 13, 13.5), "lightwood", up=None, down=None)
        m.box((x + 0.25, 4, 3.5), (x + 0.75, 5, 13.5), "lightwood", up=None, down=None)
    m.box((1.5, 5, 3.5), (14.5, 6.5, 13.5), "linen", down=None)
    m.box((2, 6.5, 4), (14, 7, 10), "blanket_kids", down=None)
    m.box((10.5, 6.5, 10.5), (13, 9, 12.5), "bear", down=None)
    m.box((11, 9, 11), (12.5, 10.5, 12.5), "bear", down=None)
    return m


def toy_box():
    m = Model()
    m.box((1, 0, 4), (15, 7, 14), "red_paint", up=None, down=None, north=Pic("box", toy_box_front))
    m.box((1.5, 0.5, 4.5), (14.5, 5, 13.5), "red_paint", faces=("up",))
    m.box((1, 7, 4), (15, 7.5, 14), "red_paint", faces=())
    m.els.pop()
    # toys sticking out: a pyramid of rings, a ball, a toy truck, a bear's head
    for i, (c, w) in enumerate((("red_paint", 4), ("yellow_paint", 3.5), ("green_paint", 3), ("blue_paint", 2.5))):
        y = 5 + i * 1.25
        m.box((3 + (4 - w) / 2, y, 6 + (4 - w) / 2), (3 + (4 - w) / 2 + w, y + 1.25, 6 + (4 - w) / 2 + w), c, down=None)
    m.box((4.5, 10, 7.5), (5.5, 11, 8.5), "red_paint", down=None)
    m.box((8.5, 5, 6.5), (12.5, 9, 10.5), "ball", down=None)
    m.box((9, 5, 10.5), (13, 9.5, 13), "bear", down=None)
    m.box((10, 9.5, 11), (12, 11, 12.5), "bear", down=None)
    return m


def toy_box_front(cv, W, H):
    t = ramp(rgb("b02a24"), 5, 0.7, 1.15)
    cv.rect(0, 0, cv.w, cv.h, t[3])
    cv.bevel(0, 0, cv.w, cv.h, t[4], t[1])
    # a painted folk border: a gold vine with berries and three daisies
    gold, leaf, white, core = rgb("e0b040"), rgb("3a7a30"), rgb("f4f0e0"), rgb("e0b040")
    for x in range(2, cv.w - 2):
        y = cv.h // 2 + round(2 * math.sin(x / 2.2))
        cv.set(x, y, gold)
        if x % 5 == 0:
            cv.set(x, y - 2, leaf); cv.set(x + 1, y - 2, leaf)
        if x % 5 == 2:
            cv.set(x, y + 2, leaf); cv.set(x + 1, y + 2, leaf)
    for k in range(3):
        cx = 5 + k * (cv.w - 10) // 2
        cy = cv.h // 2
        for dx, dy in ((0, -2), (0, 2), (-2, 0), (2, 0), (-1, -1), (1, -1), (-1, 1), (1, 1)):
            cv.set(cx + dx, cy + dy, white)
        cv.set(cx, cy, core)
    cv.hline(2, cv.w - 2, 2, gold); cv.hline(2, cv.w - 2, cv.h - 3, gold)


def rocking_horse():
    m = Model()
    # a wooden horse on rockers: body, head and neck, mane, a red saddle
    m.box((2, 0, 7), (14, 1, 8), "lightwood", up=None)
    m.box((2, 0, 8), (14, 1, 9), "lightwood", up=None)
    m.els.pop(); m.els.pop()
    for z in (5.5, 10):
        m.box((1, 0, z), (15, 1, z + 1), "lightwood", down=None)
        m.box((0, 1, z), (1, 2, z + 1), "lightwood", down=None)
        m.box((15, 1, z), (16, 2, z + 1), "lightwood", down=None)
    for x in (4, 11):
        m.box((x, 1, 6), (x + 1, 5, 11), "lightwood", up=None, down=None)
    m.box((3, 5, 6.5), (13, 9, 10.5), "horse", down=None)
    m.box((1, 8, 7), (4, 13, 10), "horse")
    m.box((0, 10.5, 7.25), (1, 12.5, 9.75), "horse", east=None)
    m.box((3.5, 12, 7.75), (4.5, 14, 9.25), "mane")
    m.box((6, 9, 6.25), (10, 9.5, 10.75), "red_paint", down=None)
    m.box((13, 6, 8), (15, 8.5, 9), "mane")
    return m


# ---------------------------------------------------------------- kitchen small things

def gas_heater(on):
    def front(cv, W, H):
        t = ramp(ENAMEL, 6, 0.7, 1.06)
        cv.rect(0, 0, cv.w, cv.h, t[4])
        cv.outline(0, 0, cv.w, cv.h, t[2])
        cv.rect(1, 2, cv.w - 1, 9, rgb("2a2a2c"))
        P.text(cv, (cv.w - P.text_width("НЕВА")) // 2, 3, "НЕВА", rgb("e0e0e0"))
        # the flame window
        cv.rect(cv.w // 2 - 3, cv.h - 14, cv.w // 2 + 3, cv.h - 10, rgb("2a3a4a"))
        if on:
            for x in range(cv.w // 2 - 2, cv.w // 2 + 2):
                cv.set(x, cv.h - 11, rgb("60a0ff")); cv.set(x, cv.h - 12, rgb("f0a030") if x % 2 else rgb("60a0ff"))
        for x in (5, cv.w - 8):
            cv.disc(x + 1.5, cv.h - 5, 2, rgb("d8d8d4"), ring=rgb("6a6a6c"))

    def build():
        m = Model()
        # the "Нева" gas water heater: a white box on the wall at 1.1..1.9 m with the flue going up
        m.box((3, 2, 11), (13, 13, 16), "enamel", north=Pic("front_" + ("on" if on else "off"), front))
        m.box((6.5, 13, 12.5), (9.5, 16, 15.5), "alu", up=None)
        m.box((5, 0, 13), (6, 2, 14), "chrome", down=None)
        m.box((10, 0, 13), (11, 2, 14), "chrome", down=None)
        return m
    return build


def kitchen_shelf():
    m = Model()
    # a wall shelf with the enamel canisters (sugar, salt, flour, grain), a grinder and a jar of tea
    m.box((0.5, 0, 12), (15.5, 1, 16), "lightwood")
    for x in (1, 14.5):
        m.box((x, 1, 12), (x + 0.5, 4, 16), "lightwood", down=None)
    for i, word in enumerate(("САХАР", "СОЛЬ", "КРУПА")):
        x = 1.75 + i * 4.25
        m.box((x, 1, 12.5), (x + 3.5, 6, 15.5), "canister", down=None, north=Pic("can" + str(i), canister(word)), up="canister_lid")
    m.box((14.5, 4, 12), (15, 5, 16), "lightwood")
    return m


def canister(word):
    def p(cv, W, H):
        t = ramp(rgb("e8e4d8"), 5, 0.75, 1.08)
        cv.rect(0, 0, cv.w, cv.h, t[3])
        cv.vline(0, 0, cv.h, t[1]); cv.vline(cv.w - 1, 0, cv.h, t[1])
        cv.hline(0, cv.w, 1, rgb("c03028"))
        cv.disc(cv.w / 2, cv.h / 2 + 1, 2.2, rgb("e05040"))
        cv.set(cv.w // 2 - 2, cv.h // 2 + 3, rgb("3a7a2a")); cv.set(cv.w // 2 + 2, cv.h // 2 + 3, rgb("3a7a2a"))
        cv.hline(1, cv.w - 1, cv.h - 2, rgb("c03028"))
        for i in range(min(len(word), cv.w // 2)):
            cv.set(1 + i * 1, 3, rgb("2a2a2a"))
    return p


def wall_radio(on):
    m = Model()
    # the kitchen radio point ("Маяк"): a plastic box with a grille and one knob, the wire to the socket
    m.box((4, 2, 12.5), (12, 8, 16), "plastic_beige", north=Pic("grille", radio_grille))
    m.box((10, 3, 12), (11, 4, 12.5), "black", south=None)
    m.box((7.5, 0, 15), (8, 2, 15.5), "black", up=None, down=None)
    return m


def radio_grille(cv, W, H):
    t = ramp(rgb("d8ccb0"), 5, 0.7, 1.1)
    cv.rect(0, 0, cv.w, cv.h, t[3])
    for y in range(2, cv.h - 2):
        for x in range(2, cv.w - 6):
            if y % 2 == 0:
                cv.set(x, y, t[1])
    P.text(cv, cv.w - 7, 2, "М", rgb("b02020"))


# ---------------------------------------------------------------- stairwell

def post_boxes():
    def front(cv, W, H):
        t = ramp(rgb("3a5a8a"), 5, 0.6, 1.2)
        cv.rect(0, 0, cv.w, cv.h, t[2])
        cols, rows = 4, 2
        bw, bh = cv.w // cols, cv.h // rows
        n = 1
        for j in range(rows):
            for i in range(cols):
                x0, y0 = i * bw, j * bh
                cv.bevel(x0, y0, x0 + bw, y0 + bh, t[4], t[0])
                cv.rect(x0 + 2, y0 + 2, x0 + bw - 2, y0 + 3, rgb("1a1a1c"))  # the slot
                for k in range(3):  # the round holes to see the post inside
                    cv.set(x0 + 2 + k * 2, y0 + 6, rgb("1a1a1c"))
                cv.rect(x0 + bw - 4, y0 + bh - 4, x0 + bw - 2, y0 + bh - 2, rgb("c8c8c0"))
                P.text(cv, x0 + 2, y0 + bh - 6, str(n), rgb("f0f0e8"))
                n += 1

    m = Model()
    # eight flat boxes in two rows on the ground-floor wall at 1.2..1.7 m
    m.box((0.5, 3, 12.5), (15.5, 11, 16), "mailbox_blue", north=Pic("boxes", front))
    return m


def bare_bulb(on):
    def build():
        m = Model()
        # the stairwell's bulb on a wire with a tin shade: hangs from the landing's ceiling to 1.85 m
        m.box((7.5, 15.5, 7.5), (8.5, 16, 8.5), "black", down=None)
        m.box((6.5, 14.5, 6.5), (9.5, 15.5, 9.5), "enamel", faces=("north", "south", "east", "west", "up"))
        m.box((5, 14, 5), (11, 14.5, 11), "enamel", faces=("north", "south", "east", "west", "up", "down"))
        m.box((7, 13.6, 7), (9, 14.1, 9), "bulb" if on else "bulb_off", shade=not on, up=None)
        return m
    return build


def chute():
    def hatch(cv, W, H):
        t = ramp(rgb("6a7a70"), 5, 0.6, 1.2)
        cv.rect(0, 0, cv.w, cv.h, t[2])
        cv.bevel(0, 0, cv.w, cv.h, t[4], t[0])
        cv.rect(cv.w // 2 - 4, 3, cv.w // 2 + 4, 5, rgb("2a2a2a"))  # the handle
        P.text(cv, (cv.w - P.text_width("МУСОР")) // 2, cv.h - 7, "МУСОР", rgb("e8e4d8"))
    m = Model()
    # the rubbish chute: a square pipe in the corner from floor to ceiling, a tipping hatch at 0.8 m
    m.box((4, 0, 8), (16, 32, 16), "chute", north=None, up=None, down=None)
    m.box((4, 0, 7.5), (16, 9, 8), "chute", north="chute", south=None, down=None)
    m.box((4, 15, 7.5), (16, 32, 8), "chute", south=None, up=None)
    m.box((4, 9, 6.5), (16, 15, 7.5), "chute", north=Pic("hatch", hatch))
    return m


def notice_board():
    def board(cv, W, H):
        t = ramp(rgb("b48a5a"), 4, 0.8, 1.15)
        cv.rect(0, 0, cv.w, cv.h, t[2])
        cv.outline(0, 0, cv.w, cv.h, rgb("5a3a20"))
        cv.outline(1, 1, cv.w - 1, cv.h - 1, rgb("8a6a40"))
        # the painted header, then notes pinned on the cork
        word = "ОБЪЯВЛЕНИЯ"
        cv.rect(2, 2, cv.w - 2, 9, rgb("f0ece0"))
        P.text(cv, (cv.w - P.text_width(word)) // 2, 3, word, rgb("2a3a8a"))
        notes = [(3, 11, 12, 20, rgb("f4f0e0")), (14, 11, 23, 18, rgb("f0e8a0")), (25, 12, 34, 21, rgb("f4f0e0")),
                 (36, 11, 45, 19, rgb("d8e8f0"))]
        for x0, y0, x1, y1, c in notes:
            if x1 < cv.w - 1 and y1 < cv.h - 1:
                cv.rect(x0, y0, x1, y1, c)
                for y in range(y0 + 2, y1 - 1, 2):
                    cv.hline(x0 + 1, x1 - 1 - (y % 3), y, rgb("6a6a70"))
                cv.set((x0 + x1) // 2, y0, rgb("c02020"))

    m = Model()
    # 1.5 x 0.75 m by the mailboxes, 1.1..1.85 m above the floor
    m.box((-8, 2, 15), (16, 14, 16), "cork", north=Pic("board", board), south=None)
    return m


def door_mat():
    m = Model()
    m.box((2, 0, 4), (14, 0.5, 12), "doormat", down=None)
    return m


def pram():
    m = Model()
    # a Soviet pram: a deep blue cradle with a folding hood on a chrome frame, four big wheels
    for x in (2, 12.5):
        for z in (2, 12):
            m.box((x, 0, z), (x + 1.5, 4, z + 2), "tyre", up=None, down=None, north="tyre", south="tyre",
                  east="wheel_side", west="wheel_side")
    m.box((3.5, 2, 2.75), (12.5, 2.5, 3.25), "chrome", faces=("north", "south", "up", "down"))
    m.box((3.5, 2, 12.75), (12.5, 2.5, 13.25), "chrome", faces=("north", "south", "up", "down"))
    m.box((4, 2.5, 4), (12, 3, 12), "chrome", faces=("up", "down"))
    m.box((3.5, 3, 3), (12.5, 9, 13), "canvas_blue", down=None)
    m.box((3.5, 9, 9), (12.5, 13, 13), "canvas_blue", down=None)
    m.box((4, 9, 3.5), (12, 9.5, 9), "linen", down=None)
    m.box((7.5, 9, 0), (8.5, 12, 1), "chrome", up=None, down=None)
    m.box((4, 12, 0), (12, 12.75, 1), "chrome")
    m.box((7.5, 8, 1), (8.5, 9, 3), "chrome", up=None)
    return m


def sled():
    m = Model()
    # children's sledge leaning against the wall: two curved steel runners, slats, a pull cord
    m.box((3, 0, 13), (13, 1, 16), "sled_wood", rot={"origin": [8, 0, 16], "axis": "x", "angle": 0})
    m.els.pop()
    for x in (3, 12):
        m.box((x, 0, 13), (x + 1, 14, 14), "chrome")
        m.box((x, 14, 13.5), (x + 1, 15, 15), "chrome")
    for y in (2, 5, 8, 11):
        m.box((3, y, 14), (13, y + 2, 15), "sled_wood")
    m.box((4, 13, 12), (12, 13.5, 13), "red_paint", south=None)
    return m


def bicycle():
    m = Model()
    # a road bicycle ("Кама" folding) standing along the wall: two wheels, the frame, the saddle, the handlebar
    for k, z in enumerate((-12, 4)):  # each wheel a see-through disc, painted whole on both sides
        m.box((8, 0, z), (8, 10, z + 10), "tyre", faces=(), east=Pic(f"wheel_e{k}", wheel_pic), west=Pic(f"wheel_w{k}", wheel_pic))
        m.box((7.75, 4.75, z + 4.75), (8.25, 5.25, z + 5.25), "chrome", faces=("north", "south", "up", "down"))
    m.box((7.75, 4.5, -7.5), (8.25, 5.5, 9), "red_paint", up=None, down=None)
    m.box((7.65, 5, -2.25), (8.35, 10, -1.25), "red_paint", up=None)
    m.box((7.7, 9.5, -2), (8.3, 10.5, 8), "red_paint")
    m.box((7, 10, -3), (9, 11, 0), "rubber_black")
    m.box((7.75, 10.5, 8), (8.25, 13, 9), "chrome", down=None)
    m.box((5, 13, 8), (11, 13.75, 9), "chrome")
    return m


def wheel_pic(cv, W, H):
    """A bicycle wheel filling the face: a black tyre, a chrome rim, a few clear spokes and the hub; the rest see-through."""
    n = cv.w
    c = n / 2
    for y in range(n):
        for x in range(n):
            d = math.hypot(x + 0.5 - c, y + 0.5 - c)
            col = None
            if d <= c and d > c - 2:
                col = rgb("26262a") if d > c - 1.2 or (x + y) % 3 else rgb("3a3a3e")
            elif c - 3 < d <= c - 2:
                col = rgb("b8bcc0")
            elif d <= 1.6:
                col = rgb("d0d4d8")
            elif d < c - 3:
                a = math.atan2(y + 0.5 - c, x + 0.5 - c)
                if abs(math.sin(a * 4)) * d < 0.55:
                    col = rgb("a8acb0")
            if col:
                cv.px[x, y] = col


def wheel_tex(spokes=16):
    cv = P.Canvas(32, 32, (0, 0, 0, 0))
    cv.disc(16, 16, 15.5, rgb("26262a"))
    cv.disc(16, 16, 13.5, (0, 0, 0, 0))
    for y in range(32):
        for x in range(32):
            d = math.hypot(x + 0.5 - 16, y + 0.5 - 16)
            if 12.5 < d <= 13.6:
                cv.px[x, y] = rgb("b8bcc0")
            elif d <= 12.5:
                a = math.atan2(y - 16, x - 16)
                if abs(math.sin(a * spokes / 2)) < 0.12 or d < 1.8:
                    cv.px[x, y] = rgb("c8ccd0")
    return cv


# ---------------------------------------------------------------- the list

def make():
    textures()
    mat("glass_red", P.glass(32, 32, rgb("b03030")))
    mat("phone_body", P.enamel(32, 32, rgb("b8302a"), "phone"))
    mat("candle", P.enamel(32, 32, rgb("f0ead8")))
    mat("record", record_tex())
    mat("carpet_side", P.fabric(32, 32, rgb("8a2a2a"), "cside", weave=1))
    mat("carpet_fringe", P.Canvas(32, 32, rgb("e2d4b0")))
    mat("frame_gold", P.metal(32, 32, rgb("b08a40"), vertical=False))
    mat("tablecloth_side", lace_edge())
    mat("salad_bowl", P.glass(32, 32, rgb("c8d8dc")))
    mat("olivier", olivier())
    mat("herring", herring())
    mat("tangerines", P.enamel(32, 32, rgb("f2f0ea")))
    mat("tangerine", P.enamel(32, 32, rgb("f08a20"), "tang"))
    mat("bottle_green", P.glass(32, 32, rgb("2a5a2a")))
    mat("foil", P.metal(32, 32, rgb("d8c050")))
    mat("cake", cake_side())
    mat("cake_top", cake_top())
    mat("lampshade_green", P.glass(32, 32, rgb("2a7a3a")))
    mat("lampshade_green_lit", P.glass(32, 32, rgb("5ad06a")))
    mat("books_stack", books_stack())
    mat("ink", P.glass(32, 32, rgb("2a3a7a")))
    mat("globe", globe())
    mat("lattice", lattice())
    mat("blanket_kids", P.fabric(32, 32, rgb("e8a8b8"), "bk", weave=2))
    mat("ball", ball())
    mat("horse", P.enamel(32, 32, rgb("e8e0d0"), "horse"))
    mat("mane", P.fabric(32, 32, rgb("4a2a1a"), "mane", weave=1))
    mat("canister", P.enamel(32, 32, rgb("e8e4d8")))
    mat("canister_lid", P.enamel(32, 32, rgb("c03028")))
    mat("wheel_side", wheel_tex(8))

    # hall
    Furn("apt_coat_rack", "Вешалка с пальто", "Coat rack with coats", lambda: coat_rack(), mount="WALL", color="BROWN", sound="WOOL")
    Furn("apt_shoe_cabinet", "Тумба для обуви с тапочками", "Shoe cabinet and slippers", lambda: shoe_cabinet(), color="OAK_TAN")
    Furn("apt_hall_mirror", "Трюмо", "Dressing mirror", lambda: hall_mirror(), color="BROWN", cutout=False)
    Furn("apt_wall_phone", "Телефон на полочке", "Telephone on a shelf", lambda: wall_phone(), mount="WALL", color="RED")
    Furn("apt_electric_meter", "Электросчётчик с пробками", "Electricity meter", lambda: electric_meter(), mount="WALL", color="GRAY", sound="METAL")
    # living room
    Furn("apt_sideboard", "Сервант с сервизом", "Sideboard with china", lambda: sideboard(), color="OAK_TAN")
    Furn("apt_bookcase", "Книжный шкаф", "Bookcase", lambda: bookcase(), color="BROWN")
    Furn("apt_piano", "Пианино «Красный Октябрь»", "Upright piano", lambda: piano(), color="BLACK")
    Furn("apt_piano_stool", "Банкетка для пианино", "Piano stool", lambda: piano_stool(), seat=8.5, color="BLACK")
    Furn("apt_radiola", "Радиола «Ригонда»", "Radiogram", radiola, toggle=True, light=3, color="OAK_TAN")
    Furn("apt_wall_carpet", "Ковёр на стену", "Wall carpet", lambda: wall_carpet(), mount="WALL", color="RED", sound="WOOL", solid=False)
    Furn("apt_rug", "Палас", "Floor rug", lambda: floor_rug(), color="RED", sound="WOOL", solid=False)
    Furn("apt_ficus", "Фикус в горшке", "Rubber fig in a pot", lambda: ficus(), color="GREEN", sound="GRASS", cutout=True)
    Furn("apt_aloe", "Алоэ в горшке", "Aloe in a pot", lambda: aloe(), color="GREEN", sound="GRASS", cutout=True)
    Furn("apt_picture_bears", "Картина «Утро в сосновом лесу»", "Painting: morning in a pine forest", lambda: picture("bears"), mount="WALL")
    Furn("apt_picture_wave", "Картина «Девятый вал»", "Painting: the ninth wave", lambda: picture("wave"), mount="WALL")
    Furn("apt_wall_clock", "Часы с маятником", "Pendulum clock", lambda: wall_clock(), mount="WALL", color="BROWN")
    Furn("apt_dining_table", "Обеденный стол со скатертью", "Dining table with a cloth", lambda: dining_table(False), color="WHITE")
    Furn("apt_feast_table", "Праздничный стол", "New Year's table", lambda: dining_table(True), color="WHITE")
    Furn("apt_desk", "Письменный стол с лампой", "Writing desk with a lamp", desk, toggle=True, light=11, color="BROWN")
    Furn("apt_sewing_machine", "Швейная машинка «Подольск»", "Treadle sewing machine", lambda: sewing_machine(), color="BLACK", sound="METAL", cutout=True)
    # nursery
    Furn("apt_crib", "Детская кроватка", "Baby cot", lambda: crib(), color="OAK_TAN")
    Furn("apt_toy_box", "Ящик с игрушками", "Toy box", lambda: toy_box(), color="RED")
    Furn("apt_rocking_horse", "Лошадка-качалка", "Rocking horse", lambda: rocking_horse(), seat=8, color="WHITE")
    # kitchen small things
    Furn("apt_gas_heater", "Газовая колонка «Нева»", "Gas water heater", gas_heater, mount="WALL", toggle=True, light=4, color="WHITE", sound="METAL")
    Furn("apt_kitchen_shelf", "Полка с банками для круп", "Shelf with canisters", lambda: kitchen_shelf(), mount="WALL", color="WHITE")
    Furn("apt_wall_radio", "Радиоточка", "Wall radio", lambda: wall_radio(True), mount="WALL", color="PALE_YELLOW")
    # stairwell
    Furn("apt_post_boxes", "Почтовые ящики", "Post boxes", lambda: post_boxes(), mount="WALL", color="BLUE", sound="METAL")
    Furn("apt_bare_bulb", "Лампочка в подъезде", "Stairwell bulb", bare_bulb, mount="CEILING", toggle=True, light=13, color="WHITE",
         sound="GLASS", solid=False)
    Furn("apt_chute", "Мусоропровод", "Rubbish chute", lambda: chute(), mount="WALL", color="GREEN", sound="METAL")
    Furn("apt_notice_board", "Доска объявлений", "Notice board", lambda: notice_board(), mount="WALL", color="BROWN")
    Furn("apt_door_mat", "Коврик у двери", "Door mat", lambda: door_mat(), color="BROWN", sound="WOOL", solid=False)
    Furn("apt_pram", "Детская коляска", "Pram", lambda: pram(), color="BLUE", sound="WOOL", cutout=True)
    Furn("apt_sled", "Санки", "Sledge", lambda: sled(), mount="WALL", color="RED", sound="METAL")
    Furn("apt_bicycle", "Велосипед «Кама»", "Bicycle", lambda: bicycle(), color="RED", sound="METAL", cutout=True)


def record_tex():
    cv = P.Canvas(32, 32, (0, 0, 0, 0))
    cv.disc(16, 16, 15.5, rgb("1a1a1c"))
    for r in range(4, 15, 2):
        for a in range(0, 360, 6):
            cv.set(int(16 + r * math.cos(math.radians(a))), int(16 + r * math.sin(math.radians(a))), rgb("2c2c30"))
    cv.disc(16, 16, 4, rgb("c83a2a"))
    cv.disc(16, 16, 0.8, rgb("e0e0e0"))
    return cv


def lace_edge():
    cv = P.Canvas(32, 32, rgb("f4f1e6"))
    for y in range(32):
        for x in range(32):
            if (x + y) % 6 == 0 or (x - y) % 6 == 0:
                cv.set(x, y, rgb("e2ddcc"))
    return cv


def olivier():
    r = random.Random("olivier")
    cv = P.Canvas(32, 32, rgb("e8dca0"))
    for _ in range(90):
        cv.set(r.randrange(32), r.randrange(32), r.choice([rgb("f4ecc0"), rgb("6a9a3a"), rgb("e89a50"), rgb("c8a070"), rgb("f8f4e0")]))
    return cv


def herring():
    cv = P.Canvas(32, 32, rgb("a03a5a"))
    for y in range(32):
        for x in range(32):
            if (x + y) % 9 == 0:
                cv.set(x, y, rgb("f0e8d8"))
            elif (x * 3 + y) % 13 == 0:
                cv.set(x, y, rgb("e0b030"))
    return cv


def cake_side():
    cv = P.Canvas(32, 32, rgb("8a5a3a"))
    for y in range(32):
        if y % 8 in (3, 4):
            cv.hline(0, 32, y, rgb("f2e8d0"))
    cv.hline(0, 32, 0, rgb("f4ecd8")); cv.hline(0, 32, 1, rgb("e8a8b8"))
    return cv


def cake_top():
    cv = P.Canvas(32, 32, rgb("f4ecd8"))
    for (x, y) in ((8, 8), (24, 8), (8, 24), (24, 24), (16, 16)):
        cv.disc(x, y, 2.5, rgb("e86a8a"))
        cv.set(x + 2, y - 2, rgb("5a9a3a"))
    return cv


def books_stack():
    cv = P.Canvas(32, 32, rgb("eeeadc"))
    cols = [rgb("7a2420"), rgb("2c4a6e"), rgb("2f5a32"), rgb("8a6a2a")]
    for i, y in enumerate(range(0, 32, 8)):
        cv.rect(0, y, 32, y + 7, cols[i])
        cv.hline(0, 32, y + 7, rgb("eeeadc"))
        cv.hline(4, 28, y + 3, rgb("d8b860"))
    return cv


def globe():
    cv = P.Canvas(32, 32, rgb("3a6aa8"))
    r = random.Random("globe")
    for _ in range(8):
        cv.ellipse(r.randrange(32), r.randrange(32), r.uniform(3, 7), r.uniform(2, 5), rgb("6a9a4a"))
    for x in range(0, 32, 8):
        cv.vline(x, 0, 32, rgb("8aaad0"))
    return cv


def lattice():
    cv = P.Canvas(32, 32, (0, 0, 0, 0))
    for y in range(32):
        for x in range(32):
            if x < 3 or x > 28 or y < 3 or y > 28 or abs(x - y) < 2 or abs(x + y - 31) < 2:
                cv.px[x, y] = rgb("2e2e32")
    return cv


def ball():
    cv = P.Canvas(32, 32, rgb("d83a2a"))
    for y in range(32):
        if 12 <= y < 20:
            cv.hline(0, 32, y, rgb("2a5ac0") if y < 16 else rgb("f0d040"))
    return cv
