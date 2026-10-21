"""Херувим с пламенным мечом (Genesis 3:24; Ezekiel 1 and 10): a tall guardian in golden armour with four faces on one head —
a man before, a lion on the left, an ox with horns on the right, an eagle behind — four wings full of eyes, two raised and
two folded, and in his right hand the flaming sword that turns every way."""
from boxtex import *
from seraph import eye, eye_wing

SKIN = (214, 170, 128)
LION = (196, 132, 52)
MANE = (140, 72, 24)
OX = (98, 62, 40)
EAGLE = (110, 78, 46)
EAGLE_WHITE = (230, 222, 200)
EYE_GOLD = (255, 220, 120)


def eyes_at(p, f, xs, y, color=EYE_GOLD):
    for x in xs:
        p.put(f, x, y, color, color)


def tetra_head(p, f, opts):
    """The four faces, one per side: man (front), lion (left), ox (right), eagle (back); golden hair on top."""
    side = f.side
    for v in range(f.h):
        for u in range(f.w):
            if side == "front":
                c = GOLD if v < 2 else SKIN
            elif side == "left":
                c = MANE if u in (0, f.w - 1) or v in (0, f.h - 1) else LION
            elif side == "right":
                c = (236, 226, 210) if v >= f.h - 3 and 1 < u < f.w - 2 else OX
            elif side == "back":
                c = EAGLE_WHITE if v < f.h // 2 else EAGLE
            else:
                c = GOLD
            p.put(f, u, v, shade(c, p.noise(0.07)))
    w, h = f.w, f.h
    if side == "front":
        eyes_at(p, f, (2, w - 3), 3)
        p.put(f, w // 2, 5, shade(SKIN, 0.75))
        p.put(f, w // 2 - 1, 6, (120, 50, 40))
        p.put(f, w // 2, 6, (120, 50, 40))
    elif side == "left":
        eyes_at(p, f, (2, w - 3), 3, (255, 170, 40))
        for u in (w // 2 - 1, w // 2):
            p.put(f, u, 5, (40, 24, 16))
    elif side == "right":
        eyes_at(p, f, (2, w - 3), 2, (255, 150, 60))
        p.put(f, 3, h - 2, (40, 30, 26))
        p.put(f, w - 4, h - 2, (40, 30, 26))
    elif side == "back":
        eyes_at(p, f, (2, w - 3), 3, (255, 230, 60))
        for v in range(4, 7):
            for u in range(w // 2 - 1, w // 2 + 1):
                p.put(f, u, v, (232, 180, 50))


def armour(p, f, opts):
    """Golden scale armour; on the breast an embossed cross, a cinnabar sash below it."""
    metal(GOLD, GOLD_DARK, GOLD_LIGHT, streaks=BRONZE)(p, f, opts)
    for v in range(f.h):
        for u in range(f.w):
            if (u + (v // 2) % 2) % 3 == 0 and v % 2 == 0:
                p.put(f, u, v, shade(GOLD_DARK, p.noise(0.1)))
    if f.side == "front" and f.w >= 8:
        draw_cross(p, f, f.w // 2, 1, min(8, f.h - 4), GOLD_LIGHT)
        for u in range(f.w):
            p.put(f, u, f.h - 3, CINNABAR)


def flaming_blade(p, f, opts):
    """The blade: white-hot at its edge, flame running along it."""
    for v in range(f.h):
        for u in range(f.w):
            t = (v % 5) / 4
            c = mix(CANDLE, FLAME, t * 0.8) if u in (0, f.w - 1) else mix((255, 240, 200), CANDLE, t)
            c = shade(c, p.noise(0.12))
            p.put(f, u, v, c, c)


def flame_tongues(p, f, opts):
    """Tongues of flame round the blade: scattered burning pixels, the rest open."""
    for v in range(f.h):
        for u in range(f.w):
            if p.rand.random() < 0.45 + 0.2 * ((v + u) % 3 == 0):
                c = shade(mix(FLAME, EMBER, p.rand.random()), p.noise(0.1))
                p.put(f, u, v, c, c)


cherub_wing = eye_wing((250, 236, 190), GOLD, eye_every=(4, 4), tips=True)
greave = metal(GOLD, GOLD_DARK, GOLD_LIGHT)
horn = solid(BONE, k=0.08)
hilt = solid((60, 40, 24), k=0.12)

parts = [
    Part("right_leg", pivot=(-2.5, 10, 0), boxes=[Box((-2, 0, -2), (4, 14, 4), greave)]),
    Part("left_leg", pivot=(2.5, 10, 0), boxes=[Box((-2, 0, -2), (4, 14, 4), greave)]),
    Part("body", pivot=(0, 10, 0), boxes=[
        Box((-5, -14, -3), (10, 14, 6), armour),
        Box((-5.5, -2, -3.5), (11, 6, 7), armour),
    ], children=[
        Part("head", pivot=(0, -14, 0), boxes=[
            Box((-4, -8, -4), (8, 8, 8), tetra_head),
            Box((-4.5, -8.5, -4.5), (9, 1, 9), gold()),
            Box((-6, -6, -1), (2, 1, 2), horn),
            Box((-7, -8, -1), (1, 2, 2), horn),
            Box((4, -7.5, -3), (1.5, 8, 6), solid(MANE, k=0.15)),
            Box((-1, -4, 4), (2, 2, 2), solid((232, 180, 50))),
        ]),
        Part("right_arm", pivot=(-6.5, -12, 0), boxes=[Box((-2, -1, -2), (4, 13, 4), armour)], children=[
            Part("sword", pivot=(0, 11.5, -0.5), rotation=(1.5708, 0, 0), boxes=[
                Box((-0.5, -1, -0.5), (1, 4, 1), hilt),
                Box((-2.5, -2, -0.75), (5, 1, 1.5), gold(trim=False)),
                Box((-0.75, -22, -0.5), (1.5, 20, 1), flaming_blade),
                Box((-1.75, -23, -0.5), (3.5, 20, 1), flame_tongues),
            ]),
        ]),
        Part("left_arm", pivot=(6.5, -12, 0), boxes=[Box((-2, -1, -2), (4, 13, 4), armour)]),
        Part("upper_left_wing", pivot=(2.5, -12, 3), rotation=(-0.25, 0, 0.35), boxes=[Box((-1, -18, -0.5), (6, 18, 1), cherub_wing)]),
        Part("upper_right_wing", pivot=(-2.5, -12, 3), rotation=(-0.25, 0, -0.35), boxes=[Box((-5, -18, -0.5), (6, 18, 1), cherub_wing)]),
        Part("lower_left_wing", pivot=(2.5, -9, 3), rotation=(0.2, 0, -0.2), boxes=[Box((-1, 0, -0.5), (5, 17, 1), cherub_wing)]),
        Part("lower_right_wing", pivot=(-2.5, -9, 3), rotation=(0.2, 0, 0.2), boxes=[Box((-4, 0, -0.5), (5, 17, 1), cherub_wing)]),
    ]),
]

if __name__ == "__main__":
    build("Cherub", "cherub", parts, size=128, seed=324)
