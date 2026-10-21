"""Звонарь погоста: a giant living church bell of old bronze with green patina, a cast band of Church Slavonic-like lettering,
a cast face with two candle-lit eyes, a heavy clapper swinging under the lip, two long gaunt arms clutching bell ropes and a
small hunched ringer's hood on the crown."""
from boxtex import *

BRONZE_DARK = (92, 60, 30)
BRONZE_LIGHT = (196, 142, 78)
HOLLOW = (16, 12, 10)
ROPE = (150, 120, 80)
FLESH = (120, 110, 96)

# Pseudo-Slavonic glyphs, 3x5, cast in relief on the band.
GLYPHS = [
    ["111", "101", "111", "101", "101"],  # А-like
    ["101", "101", "111", "010", "010"],  # Ч-like
    ["111", "010", "111", "010", "111"],  # Ѣ-like
    ["101", "111", "010", "111", "101"],  # Ж-like
    ["010", "101", "111", "101", "101"],  # Ѧ-like
    ["111", "100", "110", "100", "111"],  # Е-like
    ["101", "101", "111", "001", "001"],  # Ч, cast thin
    ["111", "101", "101", "101", "101"],  # П-like
]


def bronze(p, f, opts, patina=0.32):
    """Old bronze: lighter at the top of each face, darker below, with green patina running down in streaks."""
    for v in range(f.h):
        for u in range(f.w):
            t = v / max(1, f.h - 1)
            c = mix(BRONZE_LIGHT, BRONZE_DARK, 0.25 + t * 0.6)
            c = shade(c, p.noise(0.1))
            # Patina gathers in vertical runs.
            run = math.sin(u * 0.9 + (f.x % 11)) * 0.5 + 0.5
            if p.rand.random() < patina * run * (0.4 + t):
                c = mix(c, PATINA, 0.55 + p.rand.random() * 0.3)
            p.put(f, u, v, c)


def bell_plain(p, f, opts):
    bronze(p, f, opts)
    if f.side == "bottom":
        for v in range(f.h):
            for u in range(f.w):
                p.put(f, u, v, shade(HOLLOW, p.noise(0.1)))


def bell_lip(p, f, opts):
    """The lip: a bright worn rim, and from below the dark hollow of the bell."""
    bronze(p, f, opts, patina=0.08)
    if f.side in ("front", "back", "left", "right"):
        for u in range(f.w):
            p.put(f, u, 0, shade(BRONZE_LIGHT, p.noise(0.05)))
            p.put(f, u, f.h - 1, shade(BRONZE_DARK, p.noise(0.05)))
    if f.side == "bottom":
        for v in range(f.h):
            for u in range(f.w):
                edge = min(u, v, f.w - 1 - u, f.h - 1 - v)
                p.put(f, u, v, shade(BRONZE_DARK, p.noise(0.05)) if edge < 2 else shade(HOLLOW, p.noise(0.1)))


def bell_band(p, f, opts):
    """The waist of the bell with a cast band of lettering between two raised rims."""
    bronze(p, f, opts)
    if f.side not in ("front", "back", "left", "right"):
        if f.side == "bottom":
            for v in range(f.h):
                for u in range(f.w):
                    p.put(f, u, v, shade(HOLLOW, p.noise(0.1)))
        return
    top = 2
    for u in range(f.w):
        p.put(f, u, top, shade(BRONZE_LIGHT, p.noise(0.05)))
        p.put(f, u, top + 7, shade(BRONZE_LIGHT, p.noise(0.05)))
        for v in range(top + 1, top + 7):
            p.put(f, u, v, shade(BRONZE_DARK, p.noise(0.08)))
    u = 1 + (f.x % 3)
    while u + 3 < f.w:
        glyph = GLYPHS[p.rand.randrange(len(GLYPHS))]
        for gv, row in enumerate(glyph):
            for gu, bit in enumerate(row):
                if bit == "1":
                    p.put(f, u + gu, top + 1 + gv, shade(mix(BRONZE_LIGHT, GOLD, 0.4), p.noise(0.06)))
        u += 4
    # A little cross between words, as on real bells.
    if f.side == "back":
        draw_cross(p, f, f.w // 2, top + 9, 7, shade(BRONZE_LIGHT, 1.0))


def bell_face(p, f, opts):
    """The bell's shoulder with the cast face on the front: heavy brows, a long nose, a sorrowful mouth and candle eyes."""
    bronze(p, f, opts)
    if f.side == "bottom":
        for v in range(f.h):
            for u in range(f.w):
                p.put(f, u, v, shade(HOLLOW, p.noise(0.1)))
    if f.side != "front":
        return
    cx = f.w // 2
    brow = shade(BRONZE_LIGHT, 1.05)
    deep = (30, 20, 12)
    for side in (-1, 1):
        ex = cx + side * 6
        # Brow ridge, sloping down to the nose: a mournful cast.
        for du in range(-3, 4):
            p.put(f, ex + du, 2 + (1 if du * side > 1 else 0), brow)
        # Socket and the candle burning in it.
        for du in range(-3, 3):
            for dv in range(3, 9):
                p.put(f, ex + du, dv, deep)
        for du in (-1, 0):
            for dv in range(4, 8):
                flame = CANDLE if dv < 6 else FLAME
                p.put(f, ex + du, dv, flame, flame)
        p.put(f, ex - 2, 5, shade(CANDLE, 0.6), shade(CANDLE, 0.6))
        p.put(f, ex + 1, 5, shade(CANDLE, 0.6), shade(CANDLE, 0.6))
    for dv in range(3, 9):
        p.put(f, cx, dv, brow)
    p.put(f, cx - 1, 9, brow)
    p.put(f, cx + 1, 9, brow)
    for du in range(-3, 4):
        p.put(f, cx + du, 11 - (1 if abs(du) == 3 else 0), deep)


def crown(p, f, opts):
    bronze(p, f, opts, patina=0.3)
    if f.side in ("front", "back", "left", "right"):
        for u in range(0, f.w, 3):
            for v in range(f.h):
                p.put(f, u, v, shade(BRONZE_DARK, p.noise(0.06)))


def hood(p, f, opts):
    """The ringer's hood: rotten sackcloth with a dark opening and two dim embers."""
    cloth((52, 44, 38), folds=True)(p, f, opts)
    if f.side == "front":
        for v in range(2, f.h - 1):
            for u in range(2, f.w - 2):
                p.put(f, u, v, (10, 8, 8))
        ey = f.h // 2
        for ex in (f.w // 2 - 2, f.w // 2 + 1):
            p.put(f, ex, ey, (255, 170, 70), (255, 170, 70))


def gaunt_arm(p, f, opts):
    """Grey dead skin over bones, with a ragged sleeve at the shoulder."""
    for v in range(f.h):
        for u in range(f.w):
            c = shade(FLESH, p.noise(0.1))
            if v < 6:
                c = shade((52, 44, 38), p.noise(0.1))
            elif v % 7 == 0:
                c = shade(FLESH, 0.7)
            p.put(f, u, v, c)


def rope(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            p.put(f, u, v, shade(ROPE, 0.75 if (u + v) % 3 == 0 else p.noise(0.08)))


hand = solid(BONE, k=0.08)
iron = metal((60, 56, 54), (30, 28, 28), (96, 90, 84))

# Absolute model space (y down, ground at 24): crown -21..-17, shoulder -17..-10, waist -10..2, lower 2..14, lip 14..19.
# The bell hangs from a pivot inside its crown at y = -19 so it can sway like a rung bell.
parts = [
    Part("bell", pivot=(0, -19, 0), boxes=[
        Box((-8, -2, -8), (16, 4, 16), crown),
        Box((-11, 2, -11), (22, 7, 22), bell_plain),
        Box((-13, 9, -13), (26, 12, 26), bell_face),
        Box((-15, 21, -15), (30, 12, 30), bell_band),
        Box((-17, 33, -17), (34, 5, 34), bell_lip),
    ], children=[
        Part("clapper", pivot=(0, 10, 0), boxes=[
            Box((-1, 0, -1), (2, 26, 2), iron),
            Box((-3, 26, -3), (6, 6, 6), iron),
        ]),
        Part("head", pivot=(0, -2, 2), rotation=(0.35, 0, 0), boxes=[
            Box((-5, -9, -5), (10, 9, 10), hood),
            Box((-3, -11, -2), (6, 2, 6), hood),
        ]),
        Part("right_arm", pivot=(-11, 4, 0), rotation=(0, 0, 0.3), boxes=[
            Box((-1.5, 0, -1.5), (3, 28, 3), gaunt_arm),
            Box((-2, 28, -2), (4, 4, 4), hand),
        ], children=[
            Part("right_rope", pivot=(0, 30, 0), rotation=(0, 0, 0.07), boxes=[
                Box((-0.5, -38, -0.5), (1, 38, 1), rope),
                Box((-1, 0, -1), (2, 6, 2), rope),
            ]),
        ]),
        Part("left_arm", pivot=(11, 4, 0), rotation=(0, 0, -0.3), boxes=[
            Box((-1.5, 0, -1.5), (3, 28, 3), gaunt_arm, mirror=True),
            Box((-2, 28, -2), (4, 4, 4), hand),
        ], children=[
            Part("left_rope", pivot=(0, 30, 0), rotation=(0, 0, -0.07), boxes=[
                Box((-0.5, -38, -0.5), (1, 38, 1), rope),
                Box((-1, 0, -1), (2, 6, 2), rope),
            ]),
        ]),
    ]),
]

if __name__ == "__main__":
    build("BellRinger", "bell_ringer", parts, size=256, seed=57)
