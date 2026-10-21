"""Бегемот: the beast of the earth (Job 40:15-24). A colossal ox-like, river-horse body with a mossy, muddy hide; bones as
strong pieces of brass showing as bronze bars along his flanks and spine; a broad muzzle with tusks, small burning eyes,
brass horns; and a tail that moves like a cedar — a bark trunk hung with tiers of dark needles."""
from boxtex import *

MUD = (82, 72, 54)
MUD_DARK = (56, 46, 32)
MOSS = (64, 88, 42)
BARK = (74, 50, 34)
NEEDLE = (32, 60, 38)
EMBER_EYE = (255, 110, 36)


def hide(p, f, opts):
    """Thick wrinkled hide: moss on the back and the top of the flanks, swamp mud caked on the lower half."""
    for v in range(f.h):
        for u in range(f.w):
            t = v / max(1, f.h - 1)
            c = MUD
            if f.side == "top" or (f.side != "bottom" and t < 0.3 and p.rand.random() < 0.55 - t):
                c = mix(MUD, MOSS, 0.5 + 0.4 * p.rand.random())
            if f.side == "bottom" or (f.side != "top" and t > 0.65 and p.rand.random() < t - 0.3):
                c = mix(c, MUD_DARK, 0.6)
            if f.side != "top" and v % 5 == 2 and p.rand.random() < 0.7:
                c = shade(c, 0.8)
            p.put(f, u, v, shade(c, p.noise(0.07)))


brass = metal(BRONZE, GOLD_DARK, GOLD_LIGHT, PATINA)


def bark(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            groove = (u % 3 == 0) if f.side in ("left", "right", "top", "bottom") else (v % 3 == 0)
            p.put(f, u, v, shade(BARK, p.noise(0.1) * (0.7 if groove else 1)))


def needles(p, f, opts):
    """Cedar needles: dark green with lighter tips, a few gaps at the edges."""
    for v in range(f.h):
        for u in range(f.w):
            if f.side in ("front", "back", "left", "right") and (u + v) % 3 == 0 and v == 0:
                continue
            c = mix(NEEDLE, (70, 104, 60), 0.4) if p.rand.random() < 0.25 else NEEDLE
            p.put(f, u, v, shade(c, p.noise(0.1)))


def eye(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            c = shade(EMBER_EYE, p.noise(0.1))
            p.put(f, u, v, c, c)


def mouth(p, f, opts):
    """The lower jaw: hide outside, a dark red mouth on top."""
    if f.side == "top":
        solid((70, 20, 18))(p, f, opts)
    else:
        hide(p, f, opts)


hoof = solid((36, 30, 26))


def leg(name, pivot):
    return Part(name, pivot=pivot, boxes=[
        Box((-6, 0, -6), (12, 12, 12), hide),
        Box((-6.5, 10, -6.5), (13, 2, 13), hoof),
        Box((-6.6, 2, -2), (1, 6, 4), brass),
    ])


ribs = []
for k in range(4):
    z = -38 + k * 8
    ribs.append(Box((-19.6, -10, z), (1, 16, 3), brass))
    ribs.append(Box((18.6, -10, z), (1, 16, 3), brass))
spine = [Box((-2, -17 - (k % 2), -40 + k * 8), (4, 4, 5), brass) for k in range(6)]

tail_tip = Part("tail_tip", pivot=(0, 0, 18), boxes=[
    Box((-2, -2, 0), (4, 4, 18), bark),
    Box((-8, -3.5, 2), (16, 2, 6), needles),
    Box((-6, -3.5, 8), (12, 2, 5), needles),
    Box((-4, -3.5, 13), (8, 2, 4), needles),
    Box((-2, -3.5, 17), (4, 2, 3), needles),
    Box((-6, 1.5, 4), (12, 2, 6), needles),
])
tail = Part("tail", pivot=(0, -10, 6), rotation=(-0.35, 0, 0), boxes=[
    Box((-3, -3, 0), (6, 6, 18), bark),
    Box((-9, -4.5, 6), (18, 2, 6), needles),
    Box((-7, -4.5, 12), (14, 2, 5), needles),
    Box((-8, 2.5, 9), (16, 2, 6), needles),
], children=[tail_tip])

head = Part("head", pivot=(0, -2, -46), boxes=[
    Box((-13, -12, -20), (26, 20, 20), hide),
    Box((-12, -4, -30), (24, 10, 10), hide),
    Box((-9, -5, -28), (4, 1, 3), solid((24, 18, 14))),
    Box((5, -5, -28), (4, 1, 3), solid((24, 18, 14))),
    Box((-10, -9, -20.6), (3, 2, 1), eye),
    Box((7, -9, -20.6), (3, 2, 1), eye),
    Box((-15, -13, -8), (4, 3, 3), hide),
    Box((11, -13, -8), (4, 3, 3), hide),
], children=[
    Part("horn_right", pivot=(-10, -11, -12), rotation=(-0.3, 0, -0.35), boxes=[
        Box((-1.5, -8, -1.5), (3, 8, 3), brass), Box((-1, -12, -1), (2, 4, 2), solid(BONE))]),
    Part("horn_left", pivot=(10, -11, -12), rotation=(-0.3, 0, 0.35), boxes=[
        Box((-1.5, -8, -1.5), (3, 8, 3), brass), Box((-1, -12, -1), (2, 4, 2), solid(BONE))]),
    Part("jaw", pivot=(0, 6, -20), boxes=[
        Box((-11.5, 0, -10), (23, 5, 10), mouth),
        Box((-10, -4, -9), (2, 4, 2), solid(BONE)),
        Box((8, -4, -9), (2, 4, 2), solid(BONE)),
    ]),
])

body = Part("body", pivot=(0, 6, 20), boxes=[
    Box((-19, -14, -46), (38, 26, 52), hide),
    Box((-15, -19, -42), (30, 6, 20), hide),
    Box((-17, 12, -40), (34, 3, 40), hide),
] + ribs + spine, children=[
    head,
    tail,
    leg("leg_front_right", (-12, 6, -36)),
    leg("leg_front_left", (12, 6, -36)),
])

parts = [body, leg("leg_back_right", (-12, 12, 16)), leg("leg_back_left", (12, 12, 16))]

if __name__ == "__main__":
    build("Behemoth", "behemoth", parts, size=256, seed=40)
