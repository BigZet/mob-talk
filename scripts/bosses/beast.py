"""Зверь из бездны (Revelation 13:1-3): a leopard's spotted body on a bear's feet, seven heads on long necks, ten horns with
ten crowns, lions' mouths burning inside, names of blasphemy in red on the heads, and one head wounded to death (a black
gash that smoulders)."""
from boxtex import *

TAWNY = (176, 128, 60)
TAWNY_DARK = (120, 80, 34)
SPOT = (40, 26, 16)
BEAR = (64, 44, 30)
BEAR_DARK = (40, 28, 20)
MANE = (110, 62, 26)
SIGIL = (220, 30, 24)
MAW = (255, 110, 30)

# Which heads carry two horns (3 x 2 + 4 x 1 = 10) and which one is wounded to death.
TWO_HORNS = {2, 3, 4}
WOUNDED = 5


def leopard(p, f, opts):
    """Tawny hide with dark rosettes, paler underneath."""
    for v in range(f.h):
        for u in range(f.w):
            base = mix(TAWNY, (214, 186, 130), 0.5) if f.side == "bottom" else TAWNY
            p.put(f, u, v, shade(base, p.noise(0.08)))
    if f.side == "bottom":
        return
    rand = p.rand
    for _ in range(max(1, f.w * f.h // 18)):
        cu, cv = rand.randrange(f.w), rand.randrange(f.h)
        for du, dv in ((0, 0), (1, 0), (0, 1), (-1, 0), (0, -1)):
            p.put(f, cu + du, cv + dv, SPOT if (du, dv) != (0, 0) else TAWNY_DARK)


def bear_fur(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            c = shade(BEAR, p.noise(0.14))
            if (u + v * 3) % 7 == 0:
                c = BEAR_DARK
            p.put(f, u, v, c)


def paw(p, f, opts):
    bear_fur(p, f, opts)
    if f.side == "front":
        # Three bone claws along the bottom.
        for u in range(1, f.w - 1, 3):
            p.put(f, u, f.h - 1, BONE)
            p.put(f, u, f.h - 2, BONE)


def mane(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            c = shade(MANE, p.noise(0.16))
            if (u * 2 + v) % 5 == 0:
                c = shade(MANE, 0.7)
            p.put(f, u, v, c)


def sigils(p, f, rows, glow=True):
    """Lines of red letters of blasphemy: little strokes that glow in the dark."""
    for row in rows:
        u = 1
        while u < f.w - 1:
            shape = p.rand.randrange(4)
            pts = [(0, 0), (0, 1)] if shape == 0 else [(0, 0), (1, 0), (0, 1)] if shape == 1 else [(0, 1), (1, 0), (1, 1)] if shape == 2 else [(0, 0), (1, 1)]
            for du, dv in pts:
                p.put(f, u + du, row + dv, SIGIL, SIGIL if glow else None)
            u += 3


def lion_head(wounded=False):
    """A lion's head: tawny, two amber eyes that glow, names of blasphemy across the brow; the wounded one has a black gash."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                p.put(f, u, v, shade(mix(TAWNY, MANE, 0.25), p.noise(0.08)))
        if f.side == "top":
            sigils(p, f, range(1, f.h - 2, 3))
        if f.side == "bottom":
            # The inside of the upper jaw: the maw burns.
            for v in range(f.h):
                for u in range(1, f.w - 1):
                    p.put(f, u, v, MAW, MAW)
        if f.side == "front":
            ey = 2
            for ex in (1, f.w - 2):
                p.put(f, ex, ey, CANDLE, CANDLE)
                p.put(f, ex, ey + 1, FLAME, FLAME)
            sigils(p, f, [0])
            for u in range(f.w // 2 - 1, f.w // 2 + 1):
                p.put(f, u, f.h - 2, SPOT)
        if wounded and f.side in ("front", "left", "top"):
            # The deadly wound: a black gash with embers in it.
            for i in range(min(f.w, f.h) + 2):
                u, v = i, i * f.h // max(1, f.w)
                p.put(f, u, v, (20, 6, 6))
                if i % 2 == 0:
                    p.put(f, u, v + 1, EMBER, EMBER)
    return paint


def jaw(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            p.put(f, u, v, shade(TAWNY_DARK, p.noise(0.08)))
    if f.side == "top":
        for v in range(f.h):
            for u in range(f.w):
                c = MAW if 0 < u < f.w - 1 else BONE
                p.put(f, u, v, c, c if c == MAW else None)
    if f.side == "front":
        for u in range(0, f.w, 2):
            p.put(f, u, 0, BONE)


def neck_hide(p, f, opts):
    leopard(p, f, opts)
    if f.side == "front":
        sigils(p, f, range(2, f.h - 2, 5))


horn = solid(BONE, k=0.08)
crown = gold(trim=False)


def head(i):
    big = i == 3
    w = 9 if big else 7
    length = 18 if big else 16 if abs(i - 3) == 1 else 15 if abs(i - 3) == 2 else 14
    horns = [(-2.5,), (2.5,)] if i in TWO_HORNS else [(0,)]
    horn_boxes = []
    for (hx,) in horns:
        horn_boxes.append(Box((hx - 0.5, -12, -2.5), (1, 5, 1), horn))
        horn_boxes.append(Box((hx - 1, -10.5, -3), (2, 1.5, 2), crown))
    half = w / 2
    return Part("neck_%d" % i, pivot=((i - 3) * 4, -16, -18), rotation=(0.45, 0, (i - 3) * 0.24), boxes=[
        Box((-2.5, -length, -2.5), (5, length, 5), neck_hide),
    ], children=[
        Part("head_%d" % i, pivot=(0, -length, 0), boxes=[
            Box((-half, -7, -7), (w, 7, 8), lion_head(i == WOUNDED)),
            Box((-half - 1.5, -8.5, -1), (w + 3, 10, 3), mane),
        ] + horn_boxes, children=[
            Part("jaw_%d" % i, pivot=(0, 0, -1), boxes=[
                Box((-half + 1, 0, -6), (w - 2, 2, 6), jaw),
            ]),
        ]),
    ])


def leg(name, x, z):
    return Part(name, pivot=(x, 8, z), boxes=[
        Box((-4, 0, -4), (8, 12, 8), bear_fur),
        Box((-4.5, 12, -6), (9, 4, 10), paw),
    ])


parts = [
    Part("body", pivot=(0, 10, 0), boxes=[
        Box((-13, -18, -22), (26, 18, 44), leopard),
        Box((-11, -21, -16), (22, 4, 20), leopard),
    ], children=[head(i) for i in range(7)] + [
        Part("tail", pivot=(0, -14, 22), rotation=(-0.6, 0, 0), boxes=[
            Box((-1.5, -1.5, 0), (3, 3, 18), leopard),
            Box((-2, -2, 18), (4, 4, 4), solid(SPOT)),
        ]),
    ]),
    leg("front_right_leg", -9, -15),
    leg("front_left_leg", 9, -15),
    leg("back_right_leg", -9, 15),
    leg("back_left_leg", 9, 15),
]

if __name__ == "__main__":
    build("Beast", "beast", parts, size=256, seed=13)
