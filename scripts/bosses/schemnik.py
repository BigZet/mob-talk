"""Чёрный Схимник: a tall monk in the great schema — black hood, robe embroidered with the Golgotha cross and the skull, a
staff with a cross in one hand and a black candle in the other; two candle-lit eyes under the hood."""
from boxtex import *


def schema_cloth(p, f, opts):
    """Black cloth; on the front, the bone-white Golgotha cross over a skull and lines of red lettering."""
    cloth(BLACK, trim=GOLD_DARK)(p, f, opts)
    if f.side != "front" or f.w < 6:
        return
    cx = f.w // 2
    draw_cross(p, f, cx, 2, max(6, f.h // 2), BONE)
    sy = 2 + max(6, f.h // 2) + 1
    for du in range(-2, 3):
        for dv in range(0, 3):
            if abs(du) == 2 and dv == 2:
                continue
            p.put(f, cx + du, sy + dv, BONE)
    p.put(f, cx - 1, sy + 1, BLACK)
    p.put(f, cx + 1, sy + 1, BLACK)
    for row in (1, f.h - 3):
        for u in range(1, f.w - 1):
            if (u + row) % 3 != 0:
                p.put(f, u, row, CINNABAR)


def hood(p, f, opts):
    cloth(NIGHT, folds=True)(p, f, opts)
    if f.side == "front":
        # The opening of the hood: a pit of darkness the face sits in.
        for v in range(2, f.h - 1):
            for u in range(1, f.w - 1):
                p.put(f, u, v, (6, 5, 8))
    if f.side in ("left", "right", "back"):
        for v in range(f.h):
            p.put(f, f.w // 2, v, CINNABAR if v % 4 == 0 else NIGHT)


wood = solid((58, 40, 26), k=0.15)

parts = [
    Part("robe", pivot=(0, 24, 0), boxes=[Box((-6, -16, -4), (12, 16, 8), schema_cloth)], children=[
        Part("body", pivot=(0, -16, 0), boxes=[
            Box((-5, -14, -3), (10, 14, 6), schema_cloth),
            Box((-1.5, -12, -3.4), (3, 5, 0.4), gold()),
        ], children=[
            Part("head", pivot=(0, -14, 0), boxes=[
                Box((-4.5, -10, -4.5), (9, 10, 9), hood),
                Box((-2, -12, -2), (4, 2, 4), hood),
                Box((-3, -7.5, -4.3), (6, 5, 1), eyes_face((14, 12, 16), CANDLE)),
            ]),
            Part("right_arm", pivot=(-6.5, -12, 0), boxes=[
                Box((-2.5, -1, -2.5), (5, 15, 5), cloth(BLACK, trim=GOLD_DARK)),
                Box((-1.5, 14, -1.5), (3, 3, 3), solid(BONE)),
            ], children=[
                Part("staff", pivot=(0, 15.5, -2), boxes=[
                    Box((-0.5, -22, -0.5), (1, 34, 1), wood),
                    Box((-0.5, -28, -0.5), (1, 6, 1), gold(trim=False)),
                    Box((-2.5, -26.5, -0.5), (5, 1, 1), gold(trim=False)),
                    Box((-1.5, -24.5, -0.5), (3, 1, 1), gold(trim=False)),
                ]),
            ]),
            Part("left_arm", pivot=(6.5, -12, 0), boxes=[
                Box((-2.5, -1, -2.5), (5, 15, 5), cloth(BLACK, trim=GOLD_DARK)),
                Box((-1.5, 14, -1.5), (3, 3, 3), solid(BONE)),
            ], children=[
                Part("candle", pivot=(0, 15, -1), boxes=[
                    Box((-1, -6, -1), (2, 6, 2), solid((30, 26, 28))),
                    Box((-0.5, -8, -0.5), (1, 2, 1), fire()),
                ]),
            ]),
        ]),
    ]),
]

if __name__ == "__main__":
    build("Schemnik", "black_schemnik", parts, size=128, seed=11)
