"""Нимрод: the mighty hunter-king who began the tower of Babel (Genesis 10-11). A tall bronze-skinned man in a long fringed
Mesopotamian robe with a mason's leather apron, a curled black beard and hair, a crenellated gold crown like a tower's top,
a great bow in his left hand, a fired clay brick in his right, and a quiver on his back. His eyes smoulder."""
from boxtex import *

SKIN = (164, 108, 70)
ROBE = (112, 30, 28)
LEATHER = (92, 60, 34)
CLAY = (156, 72, 44)
HAIR = (16, 12, 12)


def robe(p, f, opts):
    """Dark red wool with a gold fringed hem and a diagonal gold band across the front."""
    cloth(ROBE, trim=GOLD, trim_width=2)(p, f, opts)
    if f.side in ("front", "back", "left", "right"):
        for u in range(f.w):
            if u % 2 == 0:
                p.put(f, u, f.h - 1, GOLD_DARK)
    if f.side == "front":
        for v in range(f.h):
            for u in range(f.w):
                if abs(u - v * f.w / max(1, f.h)) < 1.2:
                    p.put(f, u, v, GOLD if (u + v) % 3 else GOLD_DARK)


def curls(base):
    """Tight rows of curls, Assyrian style: dark with small light spirals."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                c = shade(base, p.noise(0.1))
                if (u + (v // 2) % 2) % 2 == 0 and v % 2 == 0:
                    c = shade((60, 50, 46), p.noise(0.1))
                p.put(f, u, v, c)
    return paint


def arm(p, f, opts):
    """A short robe sleeve, a bare bronze forearm with a gold armlet."""
    for v in range(f.h):
        for u in range(f.w):
            if v < 5:
                c = shade(ROBE, p.noise(0.08))
            elif v in (8, 9):
                c = shade(GOLD, p.noise(0.08))
            else:
                c = shade(SKIN, p.noise(0.06))
            p.put(f, u, v, c)


def apron(p, f, opts):
    solid(LEATHER, k=0.12)(p, f, opts)
    if f.side == "front":
        for v in range(f.h):
            p.put(f, 0, v, shade(LEATHER, 0.7))
            p.put(f, f.w - 1, v, shade(LEATHER, 0.7))
        for u in range(1, f.w - 1):
            p.put(f, u, 1, GOLD_DARK)


def crown(p, f, opts):
    """A gold band with glowing stones."""
    gold(trim=True)(p, f, opts)
    if f.side in ("front", "back", "left", "right"):
        for u in range(1, f.w - 1, 3):
            p.put(f, u, f.h // 2, CANDLE, CANDLE)


def brick(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            c = shade(CLAY, p.noise(0.12))
            if v == f.h - 1 or u % 4 == 3:
                c = (190, 176, 150)
            p.put(f, u, v, c)


wood = solid((88, 56, 30), k=0.12)
string = solid(BONE, k=0.05)

parts = [
    Part("robe", pivot=(0, 24, 0), boxes=[
        Box((-5.5, -16, -3.5), (11, 16, 7), robe),
        Box((-3.5, -15, -4.3), (7, 12, 0.8), apron),
    ], children=[
        Part("body", pivot=(0, -16, 0), boxes=[
            Box((-5, -14, -3), (10, 14, 6), robe),
            Box((-5.2, -3, -3.2), (10.4, 2, 6.4), gold()),
        ], children=[
            Part("quiver", pivot=(0, -8, 3.5), rotation=(0, 0, 0.35), boxes=[
                Box((-1.5, -6, 0), (3, 10, 3), solid(LEATHER)),
                Box((-1, -8, 0.5), (0.5, 2, 0.5), wood),
                Box((0.4, -8.5, 1.4), (0.5, 2.5, 0.5), wood),
                Box((-0.3, -7.5, 2), (0.5, 1.5, 0.5), wood),
            ]),
            Part("head", pivot=(0, -14, 0), boxes=[
                Box((-4, -8, -4), (8, 8, 8), eyes_face(SKIN, EMBER, socket=(40, 20, 16))),
                Box((-4.4, -7, 1), (8.8, 8, 3.6), curls(HAIR)),
                Box((-3.6, -3.2, -4.8), (7.2, 7, 2), curls(HAIR)),
                Box((-4.6, -11, -4.6), (9.2, 3.5, 9.2), crown),
                Box((-4.6, -13, -4.6), (2.2, 2, 2.2), gold()),
                Box((2.4, -13, -4.6), (2.2, 2, 2.2), gold()),
                Box((-4.6, -13, 2.4), (2.2, 2, 2.2), gold()),
                Box((2.4, -13, 2.4), (2.2, 2, 2.2), gold()),
                Box((-1.1, -13, -4.6), (2.2, 2, 2.2), gold()),
            ]),
            Part("right_arm", pivot=(-6.5, -12, 0), boxes=[
                Box((-2, -1, -2), (4, 14, 4), arm),
            ], children=[
                Part("brick", pivot=(0, 13.5, -1), boxes=[Box((-1.5, -1, -2), (3, 2, 4), brick)]),
            ]),
            Part("left_arm", pivot=(6.5, -12, 0), boxes=[
                Box((-2, -1, -2), (4, 14, 4), arm),
            ], children=[
                Part("bow", pivot=(0, 12, -1), boxes=[
                    Box((-0.5, -2, -1.5), (1, 4, 1), solid(LEATHER)),
                    Box((-0.5, -8, -1), (1, 6, 1), wood),
                    Box((-0.5, -12, 0), (1, 4, 1), wood),
                    Box((-0.5, 2, -1), (1, 6, 1), wood),
                    Box((-0.5, 8, 0), (1, 4, 1), wood),
                    Box((-0.25, -12, 1), (0.5, 24, 0.5), string),
                ]),
            ]),
        ]),
    ]),
]

if __name__ == "__main__":
    build("Nimrod", "nimrod", parts, size=128, seed=33)
