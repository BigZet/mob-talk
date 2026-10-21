"""Плакальщица погоста: a tall gaunt mourner in a black shroud, a long veil over the face with two pale eyes showing through,
very long thin arms with bony fingers, a burning funeral candle in one hand and a ragged hem that never touches the ground."""
from boxtex import *

SHROUD = (22, 20, 26)
VEIL = (40, 38, 48)
PALE = (200, 226, 236)
SKIN = (176, 170, 160)


def shroud(p, f, opts):
    """Black shroud with soft folds and a faint silver hem line."""
    cloth(SHROUD, folds=True)(p, f, opts)
    if f.side in ("front", "back", "left", "right"):
        for u in range(f.w):
            if (u + f.x) % 5 == 0:
                for v in range(f.h):
                    p.put(f, u, v, shade(SHROUD, 0.75 * p.noise(0.05)))


def skirt(p, f, opts):
    """The long shroud skirt; its bottom edge is torn into rags (transparent gaps)."""
    shroud(p, f, opts)
    if f.side in ("front", "back", "left", "right"):
        for u in range(f.w):
            torn = int(abs(math.sin(u * 1.7 + f.x)) * 4) + (1 if p.rand.random() < 0.3 else 0)
            for v in range(f.h - torn, f.h):
                p.img.putpixel((f.x + u, f.y + v), (0, 0, 0, 0))
            if f.h - torn - 1 >= 0:
                p.put(f, u, f.h - torn - 1, shade((70, 66, 76), p.noise(0.1)))
    if f.side == "bottom":
        for v in range(f.h):
            for u in range(f.w):
                p.put(f, u, v, (8, 7, 10))


def veil(p, f, opts):
    """The veil over her head: thin dark gauze; on the front two pale eyes glow through it."""
    for v in range(f.h):
        for u in range(f.w):
            k = p.noise(0.1) * (0.85 if (u + v) % 2 else 1.0)
            p.put(f, u, v, shade(VEIL, k))
    if f.side != "front":
        return
    ey = f.h // 2 - 1
    for ex in (f.w // 2 - 2, f.w // 2 + 1):
        p.put(f, ex, ey, PALE, PALE)
        p.put(f, ex, ey + 1, shade(PALE, 0.6), shade(PALE, 0.6))
    # Tear tracks under the eyes, faintly lit.
    for ex in (f.w // 2 - 2, f.w // 2 + 1):
        for dv in range(2, 5):
            p.put(f, ex, ey + dv, shade(PALE, 0.35), shade(PALE, 0.25))


def long_veil(p, f, opts):
    """The veil falling down her back, ragged at the end."""
    for v in range(f.h):
        for u in range(f.w):
            p.put(f, u, v, shade(VEIL, p.noise(0.1) * (0.8 + 0.2 * math.sin(u * 1.4))))
    if f.side in ("front", "back"):
        for u in range(f.w):
            torn = int(abs(math.sin(u * 2.3)) * 3)
            for v in range(f.h - torn, f.h):
                p.img.putpixel((f.x + u, f.y + v), (0, 0, 0, 0))


def thin_arm(p, f, opts):
    """Black sleeve to the elbow, then grey skin over bone."""
    for v in range(f.h):
        for u in range(f.w):
            if v < f.h * 0.55:
                c = shade(SHROUD, p.noise(0.08))
            else:
                c = shade(SKIN, p.noise(0.08) * (0.8 if v % 6 == 0 else 1))
            p.put(f, u, v, c)


bone = solid(BONE, k=0.08)
wax = candle()

# Absolute model space (y down, ground at 24): hem 22, skirt 2..22, torso -14..2, head -22..-14. 46 units tall in all.
parts = [
    Part("skirt", pivot=(0, 2, 0), boxes=[Box((-6, 0, -4), (12, 20, 8), skirt)], children=[
        Part("body", pivot=(0, 0, 0), boxes=[
            Box((-4.5, -16, -3), (9, 16, 6), shroud),
            Box((-5, -16, -3.5), (10, 3, 7), cloth((48, 44, 54), folds=False)),
        ], children=[
            Part("head", pivot=(0, -16, -0.5), boxes=[
                Box((-3.5, -8, -3.5), (7, 8, 7), veil),
                Box((-3.5, -8, 3.5), (7, 18, 1), long_veil),
            ]),
            Part("right_arm", pivot=(-5.5, -14, 0), rotation=(-0.1, 0, 0.06), boxes=[
                Box((-1, 0, -1), (2, 30, 2), thin_arm),
                Box((-1, 30, -1.5), (2, 5, 1), bone),
                Box((-1, 30, 0.5), (2, 4, 1), bone),
            ]),
            Part("left_arm", pivot=(5.5, -14, 0), rotation=(-0.35, 0, -0.06), boxes=[
                Box((-1, 0, -1), (2, 30, 2), thin_arm, mirror=True),
                Box((-1, 30, -1), (2, 3, 2), bone),
            ], children=[
                Part("candle", pivot=(0, 31, -1.5), rotation=(0.35, 0, 0), boxes=[
                    Box((-1, -7, -1), (2, 7, 2), wax),
                    Box((-0.5, -10, -0.5), (1, 3, 1), fire()),
                ]),
            ]),
        ]),
    ]),
]

if __name__ == "__main__":
    build("Wailer", "wailer", parts, size=128, seed=56)
