"""Ceiling lights for the flats, each a thing you could still find in a Soviet or 90s flat: the "Cascade" crystal chandelier, a
horned chandelier with pressed-glass tulips, the orange plastic dome, the enamel kitchen cone, the opal glass ball and the silk
shade with fringe over the dining table. All hang in the room's top cell (two to three blocks above the floor), so they clear a
player's head. Same conventions as fur_models.py (north space, front at low z, x to the viewer's left)."""
import random

from fur_paint import rgb, mix, ramp
from furniture import Furn, Model, Pic, mat
import fur_paint as P


def materials():
    cr = P.glass(32, 32, (214, 228, 236, 255))
    crl = P.Canvas(32, 32, rgb("fffbea"))
    rnd = random.Random(7)
    for _ in range(26):  # the facets catch the light: a few hard sparkles
        x, y = rnd.randrange(32), rnd.randrange(32)
        cr.set(x, y, rgb("ffffff"))
        crl.set(x, y, rgb("ffe9a8"))
    mat("crystal", cr)
    mat("crystal_lit", crl)
    mat("plastic_orange", P.enamel(32, 32, rgb("dc6a24")))
    mat("plastic_orange_lit", P.Canvas(32, 32, rgb("ffa458")))
    mat("enamel_green", P.enamel(32, 32, rgb("4e7a5a")))
    mat("glass_amber", P.glass(32, 32, (206, 150, 70, 255)))
    mat("glass_amber_lit", P.glass(32, 32, (255, 214, 140, 255)))
    mat("silk_wine", P.fabric(32, 32, rgb("8a2432"), "silkw", weave=1))
    mat("silk_wine_lit", P.fabric(32, 32, rgb("e0604c"), "silkl", weave=1))


def stack(m, cx, cz, rings, material, shade=True, bottom=None, bottom_shade=True):
    """A round-ish body of square tiers, rings = [(y0, y1, half width)] from the top down; only the faces that show are kept."""
    for i, (y0, y1, hw) in enumerate(rings):
        above = rings[i - 1][2] if i else None
        below = rings[i + 1][2] if i + 1 < len(rings) else None
        over = {}
        if above is not None and above >= hw:
            over["up"] = None
        if below is not None and below >= hw:
            over["down"] = None
        elif below is None and bottom is not None:
            over["down"] = None
        m.box((cx - hw, y0, cz - hw), (cx + hw, y1, cz + hw), material, shade=shade, **over)
    if bottom is not None:  # the mouth on its own face, so a lit lamp's inside glows instead of taking the underside's shadow
        y0, _, hw = rings[-1]
        m.box((cx - hw, y0, cz - hw), (cx + hw, y0, cz + hw), material, faces=("down",), shade=bottom_shade, down=bottom)


def mouth(rim, on, inner_off=rgb("8a8478"), glow=rgb("fff6d8"), border=1):
    """The open underside of a shade: its rim, the inside lit up or in shadow, the bulb in the middle."""
    def paint(cv, W, H):
        t = ramp(rim, 5, 0.7, 1.15)
        cv.rect(0, 0, cv.w, cv.h, t[2])
        inner = glow if on else inner_off
        cv.rect(border, border, cv.w - border, cv.h - border, inner)
        if not on:
            cv.outline(border, border, cv.w - border, cv.h - border, mix(inner, rgb("000000"), 0.25))
        cv.disc(cv.w / 2, cv.h / 2, 2.2, rgb("fffef4") if on else rgb("c8c2a8"))
    return paint


def cascade(on):
    cry = "crystal_lit" if on else "crystal"

    def build():
        m = Model()
        # «Каскад»: a brass cup and rod, a square brass ring of eight candle bulbs, strings of cut-glass drops all round
        m.box((6, 15.25, 6), (10, 16, 10), "brass")
        m.box((7.75, 9, 7.75), (8.25, 15.25, 8.25), "brass", up=None, down=None)
        m.box((7, 6.5, 7), (9, 9, 9), cry, shade=not on)
        m.box((7.5, 5, 7.5), (8.5, 6.5, 8.5), cry, shade=not on, up=None)
        m.box((2, 8, 2), (14, 8.5, 2.5), "brass")
        m.box((2, 8, 13.5), (14, 8.5, 14), "brass")
        m.box((2, 8, 2.5), (2.5, 8.5, 13.5), "brass", north=None, south=None)
        m.box((13.5, 8, 2.5), (14, 8.5, 13.5), "brass", north=None, south=None)
        m.box((2.5, 8.25, 7.75), (7, 8.75, 8.25), "brass", faces=("north", "south", "up", "down"))
        m.box((9, 8.25, 7.75), (13.5, 8.75, 8.25), "brass", faces=("north", "south", "up", "down"))
        m.box((7.75, 8.25, 2.5), (8.25, 8.75, 7), "brass", faces=("east", "west", "up", "down"))
        m.box((7.75, 8.25, 9), (8.25, 8.75, 13.5), "brass", faces=("east", "west", "up", "down"))
        for x, z in ((2.25, 2.25), (13.75, 2.25), (2.25, 13.75), (13.75, 13.75), (8, 2.25), (8, 13.75), (2.25, 8), (13.75, 8)):
            m.box((x - 0.75, 8.5, z - 0.75), (x + 0.75, 9, z + 0.75), "brass", down=None)
            m.box((x - 0.5, 9, z - 0.5), (x + 0.5, 10.5, z + 0.5), "bulb" if on else "bulb_off", shade=not on, down=None)
        # the strings of drops hang from the ring, longer in the middle of each side, like a fringe
        spots = [(x, 2.25) for x in range(3, 14, 2)] + [(x, 13.75) for x in range(3, 14, 2)]
        spots += [(2.25, z) for z in range(4, 13, 2)] + [(13.75, z) for z in range(4, 13, 2)]
        for i, (x, z) in enumerate(spots):
            along = x if z in (2.25, 13.75) else z
            n = 5 - abs(along - 8) // 2.5
            y = 8 - n
            m.box((x - 0.25, y, z - 0.25), (x + 0.25, 8, z + 0.25), cry, shade=not on, up=None)
            m.box((x - 0.5, y - 1.5, z - 0.5), (x + 0.5, y, z + 0.5), cry, shade=not on)
        return m
    return build


def horns(on):
    gl = "glass_amber_lit" if on else "glass_amber"

    def build():
        m = Model()
        # the horned chandelier: a brass stem, four arms that bend down, a pressed amber glass tulip on each, mouth down
        m.box((6.5, 15.25, 6.5), (9.5, 16, 9.5), "brass")
        m.box((7.5, 6, 7.5), (8.5, 15.25, 8.5), "brass", up=None)
        m.box((7, 5, 7), (9, 6, 9), "brass")
        m.box((7.25, 4, 7.25), (8.75, 5, 8.75), "brass", up=None)
        m.box((2, 11, 7.5), (7.5, 12, 8.5), "brass", east=None)
        m.box((8.5, 11, 7.5), (14, 12, 8.5), "brass", west=None)
        m.box((7.5, 11, 2), (8.5, 12, 7.5), "brass", south=None)
        m.box((7.5, 11, 8.5), (8.5, 12, 14), "brass", north=None)
        for cx, cz in ((2.5, 8), (13.5, 8), (8, 2.5), (8, 13.5)):
            m.box((cx - 0.5, 9, cz - 0.5), (cx + 0.5, 11, cz + 0.5), "brass", up=None)
            m.box((cx - 0.75, 8.25, cz - 0.75), (cx + 0.75, 9, cz + 0.75), "brass", up=None)
            stack(m, cx, cz, [(7.25, 8.25, 1.25), (5.75, 7.25, 1.875), (4.25, 5.75, 2.25)], gl,
                  bottom=Pic(f"tulip{int(cx)}{int(cz)}_{int(on)}", mouth(rgb("c8903e"), on, inner_off=rgb("a07848"))), bottom_shade=not on)
        return m
    return build


def orange_dome(on):
    pl = "plastic_orange_lit" if on else "plastic_orange"

    def build():
        m = Model()
        # the 70s orange plastic dome on a black cord
        m.box((7, 15.5, 7), (9, 16, 9), "black")
        m.box((7.75, 10, 7.75), (8.25, 15.5, 8.25), "black", up=None, down=None)
        m.box((7.25, 9, 7.25), (8.75, 10, 8.75), "black", down=None)
        stack(m, 8, 8, [(8, 9, 2), (7, 8, 3.5), (6, 7, 4.75), (5, 6, 5.75), (3.5, 5, 6.5)], pl,
              bottom=Pic("mouth_" + str(int(on)), mouth(rgb("dc6a24"), on, inner_off=rgb("b05a28"), glow=rgb("ffd9a0"))), bottom_shade=not on)
        return m
    return build


def kitchen_cone(on):
    def build():
        m = Model()
        # the green enamel cone over the kitchen table, white inside
        m.box((7, 15.5, 7), (9, 16, 9), "enamel")
        m.box((7.75, 11, 7.75), (8.25, 15.5, 8.25), "black", up=None, down=None)
        m.box((7.25, 10.5, 7.25), (8.75, 11, 8.75), "black", down=None)
        stack(m, 8, 8, [(9.5, 10.5, 1.5), (8.5, 9.5, 2.5), (7.5, 8.5, 3.5), (6.5, 7.5, 4.5), (6, 6.5, 5)], "enamel_green",
              bottom=Pic("mouth_" + str(int(on)), mouth(rgb("4e7a5a"), on, inner_off=rgb("d8d4c8"))), bottom_shade=not on)
        return m
    return build


def opal_ball(on):
    gl = "frosted_lit" if on else "frosted"

    def build():
        m = Model()
        # an opal glass ball on a chrome stem, the hallway and bathroom light
        m.box((6.5, 15.5, 6.5), (9.5, 16, 9.5), "chrome")
        m.box((7.5, 13, 7.5), (8.5, 15.5, 8.5), "chrome", up=None, down=None)
        m.box((6.5, 12, 6.5), (9.5, 13, 9.5), "chrome", down=None)
        stack(m, 8, 8, [(11, 12, 2.5), (10, 11, 3.5), (8, 10, 4), (7, 8, 3.5), (6, 7, 2.5)], gl)
        return m
    return build


def fringe_pic(cv, W, H):
    t = ramp(rgb("c8a050"), 4, 0.7, 1.15)
    for x in range(cv.w):
        if x % 2:
            continue
        n = cv.h - (1 if x % 4 else 0)
        for y in range(n):
            cv.set(x, y, t[1 + (x // 2 + y) % 2])
    cv.hline(0, cv.w, 0, t[0])


def silk_shade(on):
    silk = "silk_wine_lit" if on else "silk_wine"

    def build():
        m = Model()
        # the silk lampshade with a gold fringe that hung low over the dining table
        m.box((7, 15.5, 7), (9, 16, 9), "brass")
        m.box((7.75, 12.5, 7.75), (8.25, 15.5, 8.25), "black", up=None, down=None)
        stack(m, 8, 8, [(11.5, 12.5, 2.5), (10.5, 11.5, 4), (9.5, 10.5, 5.25), (6, 9.5, 6.25)], silk,
              bottom=Pic("mouth_" + str(int(on)), mouth(rgb("8a2432"), on, inner_off=rgb("6a2a2a"), glow=rgb("ffd8a8"))), bottom_shade=not on)
        fr = Pic("fringe", fringe_pic)
        m.box((1.75, 4, 1.75), (14.25, 6, 1.75), "brass", faces=("north", "south"), north=fr, south=fr)
        m.box((1.75, 4, 14.25), (14.25, 6, 14.25), "brass", faces=("north", "south"), north=fr, south=fr)
        m.box((1.75, 4, 1.75), (1.75, 6, 14.25), "brass", faces=("east", "west"), east=fr, west=fr)
        m.box((14.25, 4, 1.75), (14.25, 6, 14.25), "brass", faces=("east", "west"), east=fr, west=fr)
        return m
    return build


def make():
    materials()
    Furn("apt_lamp_cascade", "Люстра «Каскад» (хрусталь)", "Crystal chandelier", cascade, mount="CEILING", toggle=True, light=15,
         color="GOLD", sound="GLASS", solid=False)
    Furn("apt_lamp_horns", "Люстра рожковая", "Horned chandelier", horns, mount="CEILING", toggle=True, light=15, color="GOLD",
         sound="GLASS", solid=False)
    Furn("apt_lamp_orange", "Плафон пластиковый, оранжевый", "Orange plastic pendant", orange_dome, mount="CEILING", toggle=True,
         light=14, color="ORANGE", sound="STONE", solid=False)
    Furn("apt_lamp_cone", "Кухонный плафон-конус", "Enamel kitchen pendant", kitchen_cone, mount="CEILING", toggle=True, light=14,
         color="GREEN", sound="METAL", solid=False)
    Furn("apt_lamp_ball", "Плафон-шар", "Opal glass ball", opal_ball, mount="CEILING", toggle=True, light=14, color="WHITE",
         sound="GLASS", solid=False)
    Furn("apt_lamp_silk", "Абажур с бахромой", "Silk lampshade with fringe", silk_shade, mount="CEILING", toggle=True, light=13,
         color="RED", sound="WOOL", solid=False, cutout=True)
