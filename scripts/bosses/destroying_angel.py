"""Ангел-губитель (Exodus 12; 1 Chronicles 21): a tall dark angel of the night in a hooded shadow robe with a tattered hem,
wide shadowy wings, a crimson sash, and a drawn sword in his hand. He has no face: under the hood only one cold light."""
from boxtex import *

SHADOW = (14, 12, 18)
SHADOW_LIGHT = (40, 36, 50)
COLD = (196, 226, 255)
COLD_DIM = (90, 120, 160)
STEEL = (176, 196, 216)


def robe(p, f, opts):
    """Shadow cloth; the hem is torn, so the last rows of the sides are full of holes."""
    for v in range(f.h):
        for u in range(f.w):
            if f.side in ("front", "back", "left", "right") and v >= f.h - 3 and p.rand.random() < 0.25 + 0.2 * (v - f.h + 3):
                continue
            k = p.noise(0.1) * (0.85 + 0.15 * math.sin(u * 1.1 + v * 0.2))
            c = shade(SHADOW_LIGHT if (u + f.x) % 5 == 0 else SHADOW, k)
            p.put(f, u, v, c)


def body(p, f, opts):
    robe(p, f, opts)
    if f.side in ("front", "back", "left", "right"):
        # The crimson sash, the blood of the lamb he passes over.
        for u in range(f.w):
            p.put(f, u, f.h - 4, shade(BLOOD, p.noise(0.1)))
            p.put(f, u, f.h - 3, shade(CINNABAR, p.noise(0.1)))


def hood(p, f, opts):
    """A deep hood; in front a void with a single cold light and its dim halo."""
    cloth(SHADOW, folds=True)(p, f, opts)
    if f.side != "front":
        return
    for v in range(1, f.h):
        for u in range(1, f.w - 1):
            p.put(f, u, v, (4, 3, 7))
    cx, cy = f.w // 2, f.h // 2
    for du, dv in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        p.put(f, cx + du, cy + dv, COLD_DIM, COLD_DIM)
    p.put(f, cx, cy, COLD, COLD)
    p.put(f, cx - 1, cy, COLD, COLD)


def wing(p, f, opts):
    """Rows of dark feathers with paler tips; the far edge is ragged like smoke."""
    for v in range(f.h):
        for u in range(f.w):
            if f.side in ("front", "back"):
                far = u if f.side == "back" else f.w - 1 - u
                # The right wing grows toward -X; on the net its far end is the front face's left side.
                if far > f.w - 4 and p.rand.random() < 0.35:
                    continue
                if v > f.h - 4 and p.rand.random() < 0.3:
                    continue
                row = (v + (u // 3)) % 4
                c = SHADOW_LIGHT if row == 0 else SHADOW
                if row == 0 and (u + v) % 7 == 0:
                    c = (70, 64, 88)
            else:
                c = SHADOW
            p.put(f, u, v, shade(c, p.noise(0.1)))


def blade(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            c = mix(COLD, STEEL, v / max(1, f.h - 1))
            edge = u in (0, f.w - 1)
            c = shade(c, 1.1 if edge else 0.85)
            p.put(f, u, v, c, shade(c, 0.55))


hand = solid((150, 144, 132), k=0.08)
grip = solid((30, 22, 18))
sleeve = cloth(SHADOW, trim=BLOOD)

parts = [
    Part("robe", pivot=(0, 24, 0), boxes=[Box((-5, -18, -4), (10, 18, 8), robe)], children=[
        Part("body", pivot=(0, -18, 0), boxes=[
            Box((-4.5, -13, -3), (9, 13, 6), body),
        ], children=[
            Part("head", pivot=(0, -13, 0), boxes=[
                Box((-4, -9, -4), (8, 9, 8), hood),
                Box((-2, -11, -1), (4, 2, 4), cloth(SHADOW)),
            ]),
            Part("right_wing", pivot=(-2, -11, 3), rotation=(0.15, 0.45, 0), boxes=[
                Box((-18, -6, 0), (18, 26, 1), wing),
            ]),
            Part("left_wing", pivot=(2, -11, 3), rotation=(0.15, -0.45, 0), boxes=[
                Box((0, -6, 0), (18, 26, 1), wing),
            ]),
            Part("right_arm", pivot=(-6, -11, 0), boxes=[
                Box((-2, -1, -2), (4, 14, 4), sleeve),
                Box((-1.5, 13, -1.5), (3, 2, 3), hand),
            ], children=[
                Part("sword", pivot=(0, 14, -0.5), boxes=[
                    Box((-0.5, -3, -0.5), (1, 4, 1), grip),
                    Box((-2.5, 1, -0.5), (5, 1, 1), gold(trim=False)),
                    Box((-1, 2, -0.5), (2, 20, 1), blade),
                ]),
            ]),
            Part("left_arm", pivot=(6, -11, 0), boxes=[
                Box((-2, -1, -2), (4, 14, 4), sleeve),
                Box((-1.5, 13, -1.5), (3, 2, 3), hand),
            ]),
        ]),
    ]),
]

if __name__ == "__main__":
    build("DestroyingAngel", "destroying_angel", parts, size=128, seed=23)
