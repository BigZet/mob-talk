"""Четыре всадника (Revelation 6:1-8): one horse with its rider, drawn four times. The box layout is shared, so a single model
serves all four; each rider gets its own texture (white, red, black, pale) and the renderer picks it by the boss's phase.
The rider's gear is in separate parts the model shows or hides by phase: a bow and a crown, a great sword and a helm,
a pair of scales and a hood, a scythe and a hood."""
from boxtex import *

RIDERS = {
    "white": dict(coat=(226, 220, 204), coat_dark=(176, 168, 150), mane=(240, 214, 140), mane_glow=False,
                  cloth=WHITE_CLOTH, trim=GOLD, skin=(214, 190, 160), eye=GOLD_LIGHT, horse_eye=GOLD_LIGHT,
                  hoof=(120, 96, 50), emblem="crown", steel=(220, 220, 210), blade_glow=False),
    "red": dict(coat=(150, 42, 24), coat_dark=(96, 22, 14), mane=FLAME, mane_glow=True,
                cloth=BLOOD, trim=GOLD_DARK, skin=(150, 96, 70), eye=FLAME, horse_eye=FLAME,
                hoof=(40, 24, 18), emblem="sword", steel=(200, 70, 40), blade_glow=True),
    "black": dict(coat=(26, 24, 28), coat_dark=(12, 10, 14), mane=(44, 40, 48), mane_glow=False,
                  cloth=BLACK, trim=GOLD_DARK, skin=(20, 18, 22), eye=(226, 196, 120), horse_eye=(200, 180, 120),
                  hoof=(60, 50, 40), emblem="scales", steel=GOLD, blade_glow=False),
    "pale": dict(coat=(164, 176, 146), coat_dark=(110, 122, 100), mane=(120, 255, 200), mane_glow=True,
                 cloth=(58, 66, 58), trim=(96, 104, 84), skin=BONE, eye=(110, 255, 190), horse_eye=(110, 255, 190),
                 hoof=(70, 74, 64), emblem="skull", steel=(196, 210, 190), blade_glow=True),
}


def coat(r):
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                k = p.noise(0.07) * (0.92 + 0.08 * math.sin(u * 0.7 + v * 0.4))
                c = shade(mix(r["coat"], r["coat_dark"], v / max(1, f.h - 1) * 0.5), k)
                p.put(f, u, v, c)
        if r is RIDERS["pale"] and f.side in ("left", "right") and f.w > 12:
            # The pale horse is gaunt: its ribs show.
            for u in range(f.w // 3, 2 * f.w // 3, 3):
                for v in range(2, f.h - 3):
                    p.put(f, u, v, shade(BONE, p.noise(0.08)))
        if r is RIDERS["white"] and f.side in ("left", "right", "top"):
            for _ in range(f.w * f.h // 18):
                p.put(f, p.rand.randrange(f.w), p.rand.randrange(f.h), shade(r["coat_dark"], 1.05))
    return paint


def mane(r):
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                t = v / max(1, f.h - 1)
                c = shade(mix(r["mane"], r["coat_dark"], t * 0.4), p.noise(0.18))
                p.put(f, u, v, c, c if r["mane_glow"] and p.rand.random() < 0.8 else None)
    return paint


def caparison(r):
    """The saddle cloth: the rider's colour with a trim, and on both flanks the rider's sign."""
    def paint(p, f, opts):
        cloth(r["cloth"], trim=r["trim"], trim_width=2)(p, f, opts)
        if f.side not in ("left", "right") or f.w < 8:
            return
        cx, gold_c = f.w // 2, r["trim"] if r is not RIDERS["white"] else GOLD
        sign = r["emblem"]
        if sign == "crown":
            for u in range(cx - 3, cx + 4):
                p.put(f, u, 4, gold_c)
            for u in (cx - 3, cx, cx + 3):
                p.put(f, u, 3, gold_c); p.put(f, u, 2, gold_c)
        elif sign == "sword":
            for v in range(1, 6):
                p.put(f, cx, v, (220, 200, 190))
            for u in range(cx - 2, cx + 3):
                p.put(f, u, 4, GOLD)
        elif sign == "scales":
            for u in range(cx - 3, cx + 4):
                p.put(f, u, 2, GOLD)
            for v in range(1, 5):
                p.put(f, cx, v, GOLD)
            for u in (cx - 4, cx - 3, cx - 2, cx + 2, cx + 3, cx + 4):
                p.put(f, u, 4, GOLD)
        else:
            for u in range(cx - 2, cx + 3):
                for v in range(1, 5):
                    p.put(f, u, v, BONE)
            p.put(f, cx - 1, 2, BLACK); p.put(f, cx + 1, 2, BLACK)
            p.put(f, cx, 4, BLACK)
    return paint


def rider_face(r):
    """The rider's face on the front: a crowned man, a warrior, a shadow, a skull."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                p.put(f, u, v, shade(r["skin"], p.noise(0.07)))
        if f.side != "front":
            return
        eye, ey = r["eye"], 3
        if r is RIDERS["pale"]:
            for ex in (1, 2, 5, 6):
                for dv in (0, 1, 2):
                    p.put(f, ex, ey - 1 + dv, (14, 16, 14))
            p.put(f, 2, ey, eye, eye); p.put(f, 5, ey, eye, eye)
            p.put(f, 3, 5, (30, 30, 26)); p.put(f, 4, 5, (30, 30, 26))
            for u in range(1, 7):
                p.put(f, u, 6, BONE if u % 2 else (30, 30, 26))
            return
        for ex in (2, 5):
            p.put(f, ex, ey, eye, eye)
            p.put(f, ex + (1 if ex == 2 else -1), ey, shade(r["skin"], 0.6))
        if r is RIDERS["white"]:
            for u in range(2, 6):
                p.put(f, u, 6, shade(GOLD_LIGHT, 0.9))
            for u in range(1, 7):
                p.put(f, u, 7, shade(GOLD_LIGHT, 0.8))
        elif r is RIDERS["red"]:
            for v in range(1, 8):
                p.put(f, 6, v, CINNABAR if v % 2 else BLOOD)
            for u in range(2, 6):
                p.put(f, u, 6, BLOOD)
    return paint


def blade(r):
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                edge = u in (0, f.w - 1)
                c = shade(mix(r["steel"], (255, 255, 255), 0.35 if edge else 0), p.noise(0.08))
                p.put(f, u, v, c, c if r["blade_glow"] and (edge or f.side in ("top", "bottom")) else None)
    return paint


def glow(color):
    return solid(color, glow=True, k=0.05)


def make(r):
    wood = solid((70, 48, 30), k=0.15)
    hoof = solid(r["hoof"])
    robe = cloth(r["cloth"], trim=r["trim"])
    gold_s = gold()
    leg = coat(r)

    def horse_leg(name, x, z):
        return Part(name, pivot=(x, 11, z), boxes=[
            Box((-1.75, 0, -1.75), (3.5, 11, 3.5), leg),
            Box((-2, 11, -2), (4, 2, 4), hoof),
        ])

    crown_parts = [Box((-4.5, -9.5, -4.5), (9, 2, 9), gold_s)]
    for x in (-4, -1.5, 1, 3.5):
        crown_parts.append(Box((x - 0.5, -11.5, -4.6), (1, 2, 0.5), glow(GOLD_LIGHT)))

    rider = Part("rider", pivot=(0, -11, -1), boxes=[
        Box((-4, -12, -2.5), (8, 12, 5), robe),
        Box((-4.5, -1.5, -3), (9, 2, 6), solid(r["trim"])),
        Box((-8, -1, -3), (2, 10, 4), robe),
        Box((6, -1, -3), (2, 10, 4), robe),
        Box((-8.5, 9, -4), (2.5, 2, 5), hoof),
        Box((6, 9, -4), (2.5, 2, 5), hoof),
    ], children=[
        Part("rider_head", pivot=(0, -12, 0), boxes=[
            Box((-4, -8, -4), (8, 8, 8), rider_face(r)),
        ], children=[
            Part("crown", boxes=crown_parts),
            Part("helm", boxes=[
                Box((-4.5, -8.6, -4.5), (9, 4, 9), metal(r["steel"], shade(r["steel"], 0.4), shade(r["steel"], 1.3))),
                Box((-0.5, -11.5, -3.5), (1, 3, 8), mane(r)),
            ]),
            Part("hood", boxes=[
                Box((-4.5, -8.6, -3.6), (9, 9, 8.2), cloth(shade(r["cloth"], 0.8), folds=True)),
                Box((-3, -10, -2.5), (6, 1.5, 6), cloth(shade(r["cloth"], 0.7))),
            ]),
        ]),
        Part("right_arm", pivot=(-5.5, -11, 0), boxes=[
            Box((-1.5, -1, -1.5), (3, 11, 3), robe),
            Box((-1.5, 10, -1.5), (3, 2, 3), solid(r["skin"])),
        ], children=[
            Part("sword", pivot=(0, 11, 0), rotation=(1.57, 0, 0), boxes=[
                Box((-0.5, -1, -0.5), (1, 4, 1), wood),
                Box((-1, 3, -1), (2, 1.5, 2), gold_s),
                Box((-3.5, -2, -1), (7, 1, 2), gold_s),
                Box((-1.25, -24, -0.5), (2.5, 22, 1), blade(r)),
            ]),
            Part("scythe", pivot=(0, 11, 0), boxes=[
                Box((-0.5, -18, -0.5), (1, 30, 1), solid(BONE if r is RIDERS["pale"] else (70, 48, 30), k=0.12)),
                Box((-0.5, -19.5, -8), (1, 2, 8), blade(r)),
                Box((-0.5, -18, -11), (1, 2, 3.5), blade(r)),
                Box((-0.5, -16, -12.5), (1, 2.5, 2), blade(r)),
            ]),
        ]),
        Part("left_arm", pivot=(5.5, -11, 0), boxes=[
            Box((-1.5, -1, -1.5), (3, 11, 3), robe),
            Box((-1.5, 10, -1.5), (3, 2, 3), solid(r["skin"])),
        ], children=[
            Part("bow", pivot=(0, 11, -0.5), boxes=[
                Box((-0.5, -2, -0.5), (1, 4, 1), gold_s),
                Box((-0.5, -9, 0.5), (1, 7, 1), solid((236, 226, 200))),
                Box((-0.5, 2, 0.5), (1, 7, 1), solid((236, 226, 200))),
                Box((-0.5, -10, 1.5), (1, 1.5, 1), gold_s),
                Box((-0.5, 8.5, 1.5), (1, 1.5, 1), gold_s),
                Box((-0.15, -9.5, 2.2), (0.3, 19, 0.3), glow(GOLD_LIGHT)),
            ]),
            Part("scales", pivot=(0, 11, -1), boxes=[
                Box((-0.5, 0, -0.5), (1, 2, 1), gold_s),
                Box((-5, 2, -0.5), (10, 1, 1), gold_s),
                Box((-4.6, 3, -0.1), (0.2, 4, 0.2), solid(GOLD_DARK)),
                Box((4.4, 3, -0.1), (0.2, 4, 0.2), solid(GOLD_DARK)),
                Box((-6, 7, -1.5), (3, 0.6, 3), gold_s),
                Box((3, 7, -1.5), (3, 0.6, 3), gold_s),
            ]),
        ]),
        Part("cape", pivot=(0, -12, 2.5), rotation=(0.15, 0, 0), boxes=[
            Box((-4, 0, 0), (8, 13, 1), cloth(r["cloth"], trim=r["trim"])),
        ]),
    ])

    horse = Part("horse", pivot=(0, 12, 0), boxes=[
        Box((-6, -11, -12), (12, 11, 24), coat(r)),
    ], children=[
        Part("caparison", boxes=[Box((-6.5, -11.5, -7), (13, 8, 14), caparison(r))]),
        Part("neck", pivot=(0, -7, -10), rotation=(0.55, 0, 0), boxes=[
            Box((-2.5, -13, -3.5), (5, 14, 6), coat(r)),
            Box((-1, -14, 1.5), (2, 15, 2), mane(r)),
        ], children=[
            Part("horse_head", pivot=(0, -12, 0), boxes=[
                Box((-3, -5, -8), (6, 6, 9), coat(r)),
                Box((-2.5, -3.5, -13), (5, 5, 5), coat(r)),
                Box((-2.5, -7, -0.5), (1, 2, 1), coat(r)),
                Box((1.5, -7, -0.5), (1, 2, 1), coat(r)),
                Box((-3.3, -3.5, -6.5), (0.4, 1.2, 1.2), glow(r["horse_eye"])),
                Box((2.9, -3.5, -6.5), (0.4, 1.2, 1.2), glow(r["horse_eye"])),
                Box((-0.5, -6, -6), (1, 1, 6), mane(r)),
            ]),
        ]),
        Part("tail", pivot=(0, -9, 12), rotation=(0.6, 0, 0), boxes=[Box((-1.5, -1, -1), (3, 14, 3), mane(r))]),
        rider,
    ])

    return [horse, horse_leg("leg_front_right", -3.5, -9), horse_leg("leg_front_left", 3.5, -9),
            horse_leg("leg_back_right", -3.5, 9), horse_leg("leg_back_left", 3.5, 9)]


if __name__ == "__main__":
    for i, name in enumerate(("white", "red", "black", "pale")):
        build("Horsemen", "horsemen_" + name, make(RIDERS[name]), size=128, seed=41 + i)
