"""Шестикрылый серафим (Isaiah 6:2): a small burning face in a core of fire, six wings around it — two over the face, two
over the feet, two spread for flight — red, orange and gold, covered with eyes that shine in the dark. A halo ring behind,
tongues of flame below instead of feet."""
from boxtex import *

SCLERA = (255, 244, 210)
IRIS = (255, 190, 60)
PUPIL = (24, 8, 6)
CRIMSON = (150, 24, 20)
ORANGE = (236, 110, 30)


def eye(p, f, u, v):
    """A small open eye, three wide: glowing white with a dark pupil, red lids above and below."""
    for du in (-1, 1):
        p.put(f, u + du, v, SCLERA, SCLERA)
    p.put(f, u, v, PUPIL)
    p.put(f, u, v - 1, IRIS, IRIS)
    p.put(f, u, v + 1, IRIS, shade(IRIS, 0.6))
    for du in (-1, 1):
        p.put(f, u + du, v - 1, CRIMSON)
        p.put(f, u + du, v + 1, CRIMSON)


def eye_wing(root, tip, eye_every=(5, 4), tips=True):
    """Wing feathers: vertical quills shading from `root` at the top to `tip` at the bottom, the bottom edge cut into
    feather points (left transparent), and eyes scattered over both broad sides."""
    def paint(p, f, opts):
        broad = f.side in ("front", "back")
        for v in range(f.h):
            t = v / max(1, f.h - 1)
            for u in range(f.w):
                if broad and tips and v >= f.h - 2 and (u % 3 == 1 or v == f.h - 1 and u % 3 != 0):
                    continue  # the ragged ends of the feathers
                c = mix(root, tip, t)
                if broad and u % 3 == 0:
                    c = shade(c, 0.7)  # the quill line between feathers
                c = shade(c, p.noise(0.08))
                glow = shade(c, 0.35) if broad and t > 0.7 and p.rand.random() < 0.25 else None
                p.put(f, u, v, c, glow)
        if not broad or f.w < 4 or f.h < 4:
            return
        ex, ey = eye_every
        row = 0
        for v in range(2, f.h - 2, ey):
            shift = (row % 2) * (ex // 2)
            for u in range(2 + shift, f.w - 1, ex):
                if p.rand.random() < 0.85:
                    eye(p, f, u, v)
            row += 1
    return paint


def core(p, f, opts):
    """The burning body behind the face: crimson fire with brighter flickers."""
    for v in range(f.h):
        for u in range(f.w):
            t = v / max(1, f.h - 1)
            c = shade(mix(ORANGE, CRIMSON, t), p.noise(0.18))
            hot = p.rand.random() < 0.3
            p.put(f, u, v, mix(c, CANDLE, 0.5) if hot else c, mix(c, CANDLE, 0.5) if hot else None)


def seraph_face(p, f, opts):
    """A small shining face: pale gold, two white-hot eyes, a thin mouth."""
    for v in range(f.h):
        for u in range(f.w):
            c = shade(mix(CANDLE, GOLD_LIGHT, 0.4), p.noise(0.05))
            p.put(f, u, v, c, shade(c, 0.85))
    if f.side != "front":
        return
    for ex in (1, f.w - 2):
        p.put(f, ex, 1, (255, 255, 255), (255, 255, 255))
        p.put(f, ex, 2, IRIS, IRIS)
    p.put(f, f.w // 2, 3, GOLD_DARK, GOLD)


def halo(p, f, opts):
    """A thin ring of light: only the border of the broad sides is painted, the middle stays open."""
    for v in range(f.h):
        for u in range(f.w):
            edge = u in (0, f.w - 1) or v in (0, f.h - 1)
            if f.side in ("front", "back") and not edge:
                continue
            c = shade(GOLD_LIGHT, p.noise(0.06))
            p.put(f, u, v, c, c)


flight = eye_wing(GOLD_LIGHT, CRIMSON, eye_every=(5, 4))
cover = eye_wing((250, 170, 60), (190, 40, 24), eye_every=(4, 4))

parts = [
    Part("body", pivot=(0, 4, 0), boxes=[
        Box((-3.5, -4.5, -2.5), (7, 9, 5), core),
        Box((-2.5, -3, -3.2), (5, 5, 1), seraph_face),
    ], children=[
        Part("halo", pivot=(0, -1, 3.5), boxes=[Box((-6, -6, 0), (12, 12, 1), halo)]),
        # Two wings over the face: from above its corners, sloping in across the brow.
        Part("upper_left_wing", pivot=(4, -8, -4), rotation=(0, 0, 0.9), boxes=[Box((-2.5, 0, -0.5), (5, 7, 1), cover)]),
        Part("upper_right_wing", pivot=(-4, -8, -4), rotation=(0, 0, -0.9), boxes=[Box((-2.5, 0, -0.5), (5, 7, 1), cover)]),
        # Two wings spread for flight, behind.
        Part("left_wing", pivot=(3.5, -2, 2), rotation=(0, 0, -0.3), boxes=[Box((0, -7, -0.5), (22, 13, 1), flight)]),
        Part("right_wing", pivot=(-3.5, -2, 2), rotation=(0, 0, 0.3), boxes=[Box((-22, -7, -0.5), (22, 13, 1), flight)]),
        # Two wings over the feet: hanging from below the face, crossing beneath.
        Part("lower_left_wing", pivot=(3, 4, -3), rotation=(0, 0, 0.4), boxes=[Box((-3, 0, -0.5), (6, 12, 1), cover)]),
        Part("lower_right_wing", pivot=(-3, 4, -3), rotation=(0, 0, -0.4), boxes=[Box((-3, 0, -0.5), (6, 12, 1), cover)]),
        Part("flames", pivot=(0, 5, 0), boxes=[
            Box((-1.5, 0, -1.5), (3, 6, 3), fire()),
            Box((-0.75, 6, -0.75), (1.5, 4, 1.5), fire()),
        ]),
    ]),
]

if __name__ == "__main__":
    build("Seraph", "seraph", parts, size=128, seed=59)
