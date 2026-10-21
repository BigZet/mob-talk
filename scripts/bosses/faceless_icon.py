"""Безликий лик: a living icon. A flat board in a heavy gold oklad, the painted dark figure of a saint whose face is a blank,
burning golden void under a glowing halo; a crest with a cross on top and a small red lampada hanging below."""
from boxtex import *

FACE_LIGHT = (255, 238, 176)
OLIFA = (92, 64, 30)
PIVOT = (0, 2, 0)


def at(origin):
    """Absolute model coordinates to the icon part's own (its pivot is the middle of the board)."""
    return tuple(origin[i] - PIVOT[i] for i in range(3))


def board(p, f, opts):
    """The painted board: gold ground, the dark figure, the halo, the blank face; dark wood with battens behind."""
    if f.side != "front":
        wood = (54, 36, 22)
        for v in range(f.h):
            for u in range(f.w):
                k = p.noise(0.1) * (0.9 + 0.1 * math.sin(u * 0.9 + v * 0.05))
                p.put(f, u, v, shade(wood, k))
        if f.side == "back" and f.h > 20:
            for row in (f.h // 5, f.h - f.h // 5):
                for u in range(f.w):
                    p.put(f, u, row, shade((36, 24, 14), p.noise(0.05)))
            draw_cross(p, f, f.w // 2, f.h // 3, f.h // 3, (30, 20, 12))
        return
    cx, hy = f.w / 2 - 0.5, 13
    for v in range(f.h):
        for u in range(f.w):
            # Gold leaf, darker toward the edges where the olifa has aged.
            edge = min(u, v, f.w - 1 - u, f.h - 1 - v)
            c = mix(GOLD_LIGHT, GOLD, ((u * 7 + v * 3) % 5) / 5)
            c = mix(OLIFA, c, min(1, 0.45 + edge * 0.18))
            p.put(f, u, v, shade(c, p.noise(0.06)))
    for u in range(1, f.w - 1):
        p.put(f, u, 1, CINNABAR)
        p.put(f, u, f.h - 2, CINNABAR)
    for v in range(1, f.h - 1):
        p.put(f, 1, v, CINNABAR)
        p.put(f, f.w - 2, v, CINNABAR)
    # The robe: a dark trapezoid from the shoulders down, with gold assist hatching and cinnabar edges.
    for v in range(19, f.h - 2):
        half = 8 + (v - 19) * 0.22
        for u in range(2, f.w - 2):
            d = abs(u - cx)
            if d <= half:
                c = shade(NIGHT, p.noise(0.08))
                if d > half - 1:
                    c = CINNABAR
                elif (u + v // 2) % 5 == 0 and v > 22:
                    c = mix(c, GOLD_DARK, 0.6)
                p.put(f, u, v, c)
    # The blessing hand and the closed book.
    for du in range(-1, 2):
        for dv in range(0, 3):
            p.put(f, int(cx) - 4 + du, 25 + dv, BONE)
    for du in range(0, 5):
        for dv in range(0, 6):
            p.put(f, int(cx) + 2 + du, 27 + dv, GOLD_DARK if du in (0, 4) or dv in (0, 5) else CINNABAR)
    # The halo: a glowing ring with a lighter disc inside.
    for v in range(0, 26):
        for u in range(f.w):
            r = math.hypot(u - cx, v - hy)
            if 7.6 <= r <= 9.4:
                c = shade(CANDLE, p.noise(0.05))
                p.put(f, u, v, c, c)
            elif r < 7.6:
                p.put(f, u, v, mix(GOLD_LIGHT, (255, 230, 150), 0.5))
    # The veil around the head, then the face: no features, only burning light.
    for v in range(4, 22):
        for u in range(f.w):
            if math.hypot((u - cx) / 1.05, v - hy - 1) <= 6.2 or (v >= hy and abs(u - cx) <= 6.6 + (v - hy) * 0.15):
                p.put(f, u, v, shade(BLACK, p.noise(0.08)))
    for v in range(4, 22):
        for u in range(f.w):
            r = math.hypot((u - cx) / 3.6, (v - hy) / 4.6)
            if r <= 1:
                c = mix(FACE_LIGHT, GOLD_LIGHT, r * 0.8)
                p.put(f, u, v, c, c)
    # Faded cinnabar lettering in the upper corners.
    for u0 in (3, f.w - 8):
        for u in range(u0, u0 + 5):
            for v in (4, 6):
                if (u * 3 + v) % 4:
                    p.put(f, u, v, CINNABAR)


def oklad(p, f, opts):
    """The heavy chased gold frame, set with red and blue stones on the front."""
    gold(trim=True)(p, f, opts)
    for v in range(f.h):
        for u in range(f.w):
            if (u + 2 * v) % 4 == 0:
                p.put(f, u, v, shade(GOLD_LIGHT, p.noise(0.05)))
    if f.side == "front":
        long_side = f.w >= f.h
        n = f.w if long_side else f.h
        for i in range(3, n - 2, 6):
            stone = CINNABAR if (i // 6) % 2 == 0 else SKY_BLUE
            u, v = (i, f.h // 2) if long_side else (f.w // 2, i)
            p.put(f, u, v, stone, shade(stone, 0.6))
            p.put(f, u + (0 if long_side else 1), v + (1 if long_side else 0), shade(stone, 0.7))


def crest(p, f, opts):
    gold(trim=True)(p, f, opts)
    if f.side == "front":
        draw_cross(p, f, f.w // 2, 0, f.h, CANDLE, CANDLE)


def lamp_glass(p, f, opts):
    """Red glass of the lampada with a faint glow; the top burns."""
    for v in range(f.h):
        for u in range(f.w):
            if f.side == "top":
                p.put(f, u, v, CANDLE, CANDLE)
            else:
                c = shade(CINNABAR, p.noise(0.1) * (1.2 if v == 0 else 1))
                p.put(f, u, v, c, shade(c, 0.55))


chain = solid(GOLD_DARK, k=0.15)

parts = [
    Part("icon", pivot=PIVOT, boxes=[
        Box(at((-16, -22, -1)), (32, 48, 2), board),
        Box(at((-19, -25, -2)), (38, 3, 4), oklad),
        Box(at((-19, 26, -2)), (38, 3, 4), oklad),
        Box(at((-19, -22, -2)), (3, 48, 4), oklad),
        Box(at((16, -22, -2)), (3, 48, 4), oklad),
        Box(at((-7, -30, -1.5)), (14, 5, 3), crest),
        Box(at((-0.5, -35, -0.5)), (1, 5, 1), gold(trim=False)),
        Box(at((-2, -34, -0.5)), (4, 1, 1), gold(trim=False)),
    ], children=[
        Part("lampada", pivot=at((0, 29, -3)), boxes=[
            Box((-0.5, 0, -0.5), (1, 4, 1), chain),
            Box((-2, 4, -2), (4, 3, 4), lamp_glass),
            Box((-1, 7, -1), (2, 1, 2), chain),
            Box((-0.5, 2.5, -0.5), (1, 1.5, 1), fire()),
        ]),
    ]),
]

if __name__ == "__main__":
    build("FacelessIcon", "faceless_icon", parts, size=128, seed=21)
