"""Голиаф (1 Samuel 17): a giant Philistine warrior — a brass helmet with a red crest and cheek guards, a coat of scale mail,
brass greaves, a leather kilt, a round shield, a javelin of brass between his shoulders and a spear whose staff is like a
weaver's beam. Under the helmet his forehead is bare: that is where David's stone went in."""
from boxtex import *

SKIN = (172, 116, 80)
SKIN_LIGHT = (204, 150, 108)
BEARD = (46, 30, 20)
BRASS = (176, 128, 60)
BRASS_DARK = (96, 62, 28)
BRASS_LIGHT = (232, 186, 104)
IRON = (120, 120, 124)
WOOD = (74, 52, 32)
LEATHER = (92, 60, 36)


def scale_mail(p, f, opts):
    """Brass scales in overlapping rows, each with a dark lower edge."""
    for v in range(f.h):
        for u in range(f.w):
            offset = (v // 2) % 2
            col = (u + offset) % 3
            if v % 2 == 1:
                c = BRASS_DARK if col != 1 else BRASS
            else:
                c = BRASS_LIGHT if col == 1 else BRASS
            if col == 0:
                c = shade(c, 0.7)
            p.put(f, u, v, shade(c, p.noise(0.08)))


def bronze(p, f, opts):
    metal(BRASS, BRASS_DARK, BRASS_LIGHT, streaks=PATINA)(p, f, opts)


def helmet(p, f, opts):
    bronze(p, f, opts)
    if f.side in ("front", "back", "left", "right"):
        for u in range(f.w):
            p.put(f, u, f.h - 1, GOLD_DARK)
            if u % 2 == 0:
                p.put(f, u, f.h - 2, BRASS_LIGHT)


def face(p, f, opts):
    """Sunburnt skin and a black beard; on the front, the bare forehead (rows 3-4, lit a little paler, the target), two
    ember eyes under heavy brows, the nose, the beard."""
    for v in range(f.h):
        for u in range(f.w):
            c = SKIN
            if f.side in ("left", "right", "back") and v >= f.h - 4:
                c = BEARD
            if f.side == "back" and v >= 2:
                c = BEARD
            p.put(f, u, v, shade(c, p.noise(0.06)))
    if f.side != "front":
        return
    for u in range(f.w):
        p.put(f, u, 3, shade(SKIN_LIGHT, p.noise(0.04)))
        p.put(f, u, 4, shade(SKIN_LIGHT, p.noise(0.04)))
    # A pale scar in the middle of the brow: the mark where the stone will strike.
    p.put(f, f.w // 2, 3, (230, 196, 160))
    p.put(f, f.w // 2, 4, (226, 186, 150))
    for u in range(1, f.w - 1):
        p.put(f, u, 5, (60, 36, 24))
    for ex in (2, f.w - 3):
        p.put(f, ex, 5, FLAME, FLAME)
    p.put(f, f.w // 2, 6, shade(SKIN, 0.75))
    for u in range(f.w):
        p.put(f, u, 7, BEARD)
        if u in (0, f.w - 1):
            p.put(f, u, 6, BEARD)


def beard(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            c = BEARD if (u + v) % 3 else (30, 20, 14)
            p.put(f, u, v, shade(c, p.noise(0.1)))


def crest(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            c = CINNABAR if (u + v) % 2 else (130, 26, 22)
            p.put(f, u, v, shade(c, p.noise(0.12)))


def kilt(p, f, opts):
    """Hanging leather strips with brass studs."""
    for v in range(f.h):
        for u in range(f.w):
            c = LEATHER if u % 3 else shade(LEATHER, 0.55)
            if v == 0:
                c = BRASS_DARK
            if v == 1 and u % 3 == 1:
                c = BRASS_LIGHT
            p.put(f, u, v, shade(c, p.noise(0.1)))


def arm(p, f, opts):
    """A bare muscled arm with a brass armlet near the shoulder and a bracer at the wrist."""
    for v in range(f.h):
        for u in range(f.w):
            c = SKIN
            if v in (2, 3) or v >= f.h - 4:
                c = BRASS if v not in (3, f.h - 4) else BRASS_DARK
            p.put(f, u, v, shade(c, p.noise(0.07)))


def leg(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            p.put(f, u, v, shade(SKIN if v > 1 else LEATHER, p.noise(0.07)))


def shield(p, f, opts):
    """A round shield on a square box: the corners stay transparent. Brass rim, rings, a gold boss in the middle."""
    if f.side in ("front", "back"):
        cx, cy = (f.w - 1) / 2, (f.h - 1) / 2
        r = f.w / 2
        for v in range(f.h):
            for u in range(f.w):
                d = math.hypot(u - cx, v - cy)
                if d > r:
                    continue
                if d > r - 1.2:
                    c = BRASS_DARK
                elif d < 1.6:
                    c = GOLD_LIGHT
                elif abs(d - r * 0.55) < 0.6:
                    c = BRASS_LIGHT
                else:
                    c = mix(BRASS, LEATHER, 0.35) if f.side == "back" else BRASS
                p.put(f, u, v, shade(c, p.noise(0.08)))
        return
    long = f.h if f.side in ("left", "right") else f.w
    for v in range(f.h):
        for u in range(f.w):
            along = v if f.side in ("left", "right") else u
            if abs(along - (long - 1) / 2) < 3:
                p.put(f, u, v, shade(BRASS_DARK, p.noise(0.08)))


wood = solid(WOOD, k=0.14)
iron = metal(IRON, (70, 70, 74), (190, 190, 196))

parts = [
    Part("hips", pivot=(0, 11, 0), children=[
        Part("right_leg", pivot=(-3, 0, 0), boxes=[
            Box((-2.5, 0, -2.5), (5, 6, 5), leg),
            Box((-3, 6, -3), (6, 7, 6), bronze),
        ]),
        Part("left_leg", pivot=(3, 0, 0), boxes=[
            Box((-2.5, 0, -2.5), (5, 6, 5), leg),
            Box((-3, 6, -3), (6, 7, 6), bronze),
        ]),
        Part("body", pivot=(0, 0, 0), boxes=[
            Box((-5, -16, -3), (10, 14, 6), scale_mail),
            Box((-5.5, -2, -3.5), (11, 5, 7), kilt),
        ], children=[
            Part("head", pivot=(0, -16, 0), boxes=[
                Box((-3.5, -8, -3.5), (7, 8, 7), face),
                Box((-4, -10, -4), (8, 5, 8), helmet),
                Box((-0.5, -13, -4.5), (1, 3, 9), crest),
                Box((-4.5, -5, -3.5), (1, 4, 7), bronze),
                Box((3.5, -5, -3.5), (1, 4, 7), bronze),
                Box((-3, -1, -4), (6, 3, 1), beard),
            ]),
            Part("javelin", pivot=(0, -9, 3.5), rotation=(0, 0, 0.6), boxes=[
                Box((-0.5, -10, -0.5), (1, 20, 1), bronze),
            ]),
            Part("right_arm", pivot=(-7, -14, 0), boxes=[
                Box((-2, -1, -2), (4, 15, 4), arm),
            ], children=[
                Part("spear", pivot=(0, 13, -1), boxes=[
                    Box((-1, -32, -1), (2, 45, 2), wood),
                    Box((-1, -38, -0.5), (2, 6, 1), iron),
                ]),
            ]),
            Part("left_arm", pivot=(7, -14, 0), boxes=[
                Box((-2, -1, -2), (4, 15, 4), arm),
            ], children=[
                Part("shield", pivot=(1.5, 8, -2.5), boxes=[
                    Box((-6, -6, -1.5), (12, 12, 2), shield),
                ]),
            ]),
        ]),
    ]),
]

if __name__ == "__main__":
    build("Goliath", "goliath", parts, size=128, seed=17)
