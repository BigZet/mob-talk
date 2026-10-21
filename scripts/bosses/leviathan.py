"""Левиафан: the crooked serpent of the sea (Job 41). A long body of six segments that undulate, armoured in close-sealed
scales with a bronze sheen on their rims; a dragon's head with a fanged maw that glows with fire inside, smoking nostrils,
eyes like the eyelids of the morning, back-swept horns, spines along the back, side fins and a forked tail fin."""
from boxtex import *

SEA_DARK = (20, 34, 44)
SEA = (34, 60, 72)
SEA_EDGE = (78, 112, 112)
BELLY = (142, 128, 96)
DAWN = (255, 196, 110)


def scales(base=SEA, edge=SEA_EDGE, belly=BELLY):
    """Close-sealed scales (Job 41:15): offset rows, a lighter rim at the bottom of each scale, some rims bronze; plated belly."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                if f.side == "bottom":
                    c = shade(belly, p.noise(0.06) * (0.8 if v % 3 == 0 else 1))
                    p.put(f, u, v, c)
                    continue
                row = v // 3
                su, sv = (u + (row % 2) * 2) % 4, v % 3
                k = p.noise(0.08) * (0.85 if f.side == "top" else 1)
                if sv == 2 and su in (1, 2):
                    c = mix(edge, GOLD_DARK, 0.5) if (row * 7 + u // 4) % 5 == 0 else edge
                elif sv == 0 and su == 0:
                    c = SEA_DARK
                else:
                    c = mix(base, SEA_DARK, 0.25 * sv)
                p.put(f, u, v, shade(c, k))
    return paint


def snout(p, f, opts):
    """Scales outside; the palate underneath burns (the burning lamps of Job 41:19)."""
    if f.side == "bottom":
        fire()(p, f, opts)
    else:
        scales()(p, f, opts)


def fangs(p, f, opts):
    """A row of bone fangs: every other column is a tooth, the gaps stay see-through."""
    for v in range(f.h):
        for u in range(f.w):
            if f.side in ("top", "bottom") or u % 2 == 0 or v == 0:
                p.put(f, u, v, shade(BONE, p.noise(0.08)))


def fin(p, f, opts):
    """A dark membrane with bronze ribs."""
    for v in range(f.h):
        for u in range(f.w):
            rib = (u % 4 == 0) if f.side in ("top", "bottom", "front", "back") else (v % 4 == 0)
            p.put(f, u, v, shade(mix(BRONZE, GOLD_DARK, 0.4) if rib else (28, 50, 58), p.noise(0.08)))


def spine(p, f, opts):
    metal(BONE, BRONZE, BONE)(p, f, opts)


def eye(p, f, opts):
    """Eyes like the eyelids of the morning (Job 41:18): dawn light with a dark slit."""
    for v in range(f.h):
        for u in range(f.w):
            c = (40, 16, 8) if f.side == "front" and u == f.w // 2 else shade(DAWN, p.noise(0.08))
            p.put(f, u, v, c, c)


def segment(name, half, length, pivot, child=None):
    boxes = [Box((-half, -half, 0), (2 * half, 2 * half, length), scales()),
             Box((-1, -half - 4, 3), (2, 4, 4), spine),
             Box((-1, -half - 3, length - 8), (2, 3, 4), spine)]
    return Part(name, pivot=pivot, boxes=boxes, children=[child] if child else [])


tail = Part("tail", pivot=(0, 0, 14), boxes=[
    Box((-0.5, -9, 0), (1, 18, 16), fin),
    Box((-9, -0.5, 2), (18, 1, 12), fin),
])
seg5 = segment("seg5", 3.5, 14, (0, 0.5, 16), tail)
seg4 = segment("seg4", 5, 16, (0, 0.5, 16), seg5)
seg3 = segment("seg3", 6.5, 16, (0, 0.5, 18), seg4)
seg2 = segment("seg2", 8, 18, (0, 0.5, 18), seg3)
seg1 = segment("seg1", 9, 18, (0, 0, 20), seg2)

head = Part("head", pivot=(0, -2, 0), boxes=[
    Box((-9, -9, -16), (18, 11, 16), scales()),
    Box((-7, -6, -30), (14, 7, 14), snout),
    Box((-6, 1, -29), (12, 2, 13), fangs),
    Box((-9.5, -12, -13), (4, 3, 11), scales(SEA_DARK)),
    Box((5.5, -12, -13), (4, 3, 11), scales(SEA_DARK)),
    Box((-8.5, -9, -16.6), (4, 2, 1), eye),
    Box((4.5, -9, -16.6), (4, 2, 1), eye),
    Box((-5, -5, -30.5), (2, 1.5, 1), solid((8, 6, 6))),
    Box((3, -5, -30.5), (2, 1.5, 1), solid((8, 6, 6))),
], children=[
    Part("horn_right", pivot=(-6.5, -10, -2), rotation=(0.6, 0, -0.2), boxes=[Box((-1.5, -9, -1.5), (3, 9, 3), spine)]),
    Part("horn_left", pivot=(6.5, -10, -2), rotation=(0.6, 0, 0.2), boxes=[Box((-1.5, -9, -1.5), (3, 9, 3), spine)]),
    Part("jaw", pivot=(0, 2, -14), boxes=[
        Box((-6.5, 0, -16), (13, 4, 16), scales(SEA, SEA_EDGE)),
        Box((-5.5, -2, -15.5), (11, 2, 14), fangs),
        Box((-5, -0.5, -14), (10, 1, 13), fire()),
    ]),
])

neck = Part("neck", pivot=(0, 12, -18), boxes=[
    Box((-10, -10, 0), (20, 20, 20), scales()),
    Box((-1, -14, 4), (2, 4, 5), spine),
    Box((-1, -13, 12), (2, 3, 5), spine),
], children=[
    head,
    seg1,
    Part("fin_left", pivot=(10, 6, 8), boxes=[Box((0, -0.5, -2), (14, 1, 12), fin)]),
    Part("fin_right", pivot=(-10, 6, 8), boxes=[Box((-14, -0.5, -2), (14, 1, 12), fin)]),
])

if __name__ == "__main__":
    build("Leviathan", "leviathan", [neck], size=256, seed=41)
