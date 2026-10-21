"""Херувим с пламенным мечом (Genesis 3:24; Ezekiel 1 and 10): a guardian in gold scale armour over a deep red tunic, with four
faces on one head (a noble man before, a lion on the right, an ox with horns on the left, an eagle with a hooked beak behind),
straight legs ending in calves' hooves of burnished bronze, four great wings full of eyes (two stretched upward, two folded
over the body), the wheels within wheels (ophanim) turning behind him with their rims full of eyes, and in his right hand
the huge flaming sword that turns every way. Built about 38 units tall and drawn at scale 2.5 (about six blocks)."""
from boxtex import *
from seraph import eye, eye_feather

SKIN = (214, 170, 128)
HAIR = (96, 58, 28)
LION = (204, 140, 60)
MANE = (150, 80, 26)
OX = (104, 66, 42)
OX_MUZZLE = (196, 170, 140)
EAGLE = (232, 224, 204)
EAGLE_DARK = (110, 74, 40)
BEAK = (232, 176, 52)
WING = (246, 238, 214)
OCHRE = (196, 128, 52)
CRIMSON = (140, 22, 26)
ARMOUR = (198, 150, 60)
EYE_AMBER = (255, 176, 40)


def glowing_eyes(p, f, at, half_w, half_h, iris):
    for u, v in at:
        eye(p, f, u, v, half_w, half_h, iris=iris)


def lion_face(p, f, opts):
    """A lion's face: tawny, a darker bridge down to the nose, amber eyes under a heavy brow."""
    for v in range(f.h):
        for u in range(f.w):
            dx = (u - f.w / 2) / (f.w / 2)
            c = shade(LION, (1.05 - 0.3 * dx * dx) * p.noise(0.06))
            if abs(dx) < 0.16 and v > f.h * 0.35:
                c = shade(c, 0.82)
            p.put(f, u, v, c)
    if f.side == "front":
        W, H = f.w, f.h
        for u in range(W):
            p.put(f, u, int(H * 0.3), shade(MANE, 0.8))
        glowing_eyes(p, f, [(int(W * 0.27), int(H * 0.42)), (int(W * 0.73), int(H * 0.42))], 2, 1, EYE_AMBER)


def ox_face(p, f, opts):
    """An ox's long face: brown hide with a pale blaze, wide-set dark eyes with a gold glint."""
    for v in range(f.h):
        for u in range(f.w):
            dx = (u - f.w / 2) / (f.w / 2)
            c = shade(OX, (1.05 - 0.25 * dx * dx) * p.noise(0.07))
            if f.side == "front" and abs(dx) < 0.25 + 0.1 * math.sin(v * 0.7) and v < f.h * 0.5:
                c = mix(c, OX_MUZZLE, 0.7)
            p.put(f, u, v, c)
    if f.side == "front":
        W, H = f.w, f.h
        glowing_eyes(p, f, [(int(W * 0.2), int(H * 0.45)), (int(W * 0.8), int(H * 0.45))], 2, 1, (230, 140, 50))


def muzzle(color, nostrils=True):
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                p.put(f, u, v, shade(color, p.noise(0.06)))
        if nostrils and f.side == "front":
            for cx in (int(f.w * 0.28), int(f.w * 0.72)):
                for du in range(-1, 2):
                    for dv in range(-1, 1):
                        p.put(f, cx + du, int(f.h * 0.35) + dv, (40, 24, 20))
            for u in range(int(f.w * 0.3), int(f.w * 0.7)):
                p.put(f, u, int(f.h * 0.8), shade(color, 0.55))
    return paint


def eagle_face(p, f, opts):
    """An eagle's head: white feathers in scaled rows with a dark streak, fierce gold eyes set wide."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            c = EAGLE if (u // max(1, d) + v // max(1, 2 * d)) % 3 else shade(EAGLE, 0.86)
            if v > f.h * 0.75:
                c = mix(c, EAGLE_DARK, 0.6)
            p.put(f, u, v, shade(c, p.noise(0.05)))
    if f.side == "front":
        W, H = f.w, f.h
        for side in (0.2, 0.8):
            for u in range(int(W * side) - 3, int(W * side) + 4):
                p.put(f, u, int(H * 0.36) - 2, (60, 40, 24))
        glowing_eyes(p, f, [(int(W * 0.2), int(H * 0.42)), (int(W * 0.8), int(H * 0.42))], 2, 2, (255, 210, 40))


def breastplate(p, f, opts):
    """A gold plate on the breast with one great open eye in a rayed ring: their whole body was full of eyes."""
    gold_leaf()(p, f, opts)
    if f.side != "front":
        return
    d = opts.get("density", 1)
    cx, cy = f.w // 2, f.h // 2
    r = min(f.w, f.h) * 0.4
    for v in range(f.h):
        for u in range(f.w):
            dist = math.hypot(u - cx, v - cy)
            if r - d * 0.6 < dist < r + d * 0.4:
                p.put(f, u, v, GOLD_DARK)
            elif r + d < dist < r + 3 * d and int((math.atan2(v - cy, u - cx) + math.pi) / (2 * math.pi) * 16) % 2 == 0:
                p.put(f, u, v, GOLD_LIGHT)
    eye(p, f, cx, cy, int(r * 0.75), int(r * 0.42))


def scaled_eyes(base, rim, chance=0.22):
    """Gold scale mail with here and there an open eye among the scales."""
    mail = scale_mail(base, rim)

    def paint(p, f, opts):
        mail(p, f, opts)
        d = opts.get("density", 1)
        if f.side not in ("front", "back", "left", "right") or f.w < 6 * d or f.h < 5 * d:
            return
        for v in range(3 * d, f.h - 2 * d, 4 * d):
            for u in range(3 * d, f.w - 2 * d, 5 * d):
                if p.rand.random() < chance:
                    eye(p, f, u + (v // (4 * d)) % 2 * 2 * d, v, 4, 2)
    return paint


def eyed_rim(p, f, opts):
    """A segment of a wheel's rim: burnished bronze with gold edges, an eye on every broad side (the rims were full of eyes)."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            edge = v < d or v >= f.h - d
            c = GOLD_LIGHT if edge else mix(BRONZE, GOLD, 0.5 + 0.5 * math.sin(u * 0.6))
            p.put(f, u, v, shade(c, p.noise(0.08)))
    if f.w >= 8 and f.h >= 5:
        long_u = f.w >= f.h
        if long_u:
            eye(p, f, f.w // 2, f.h // 2, min(f.w // 3, 6), max(1, f.h // 2 - 2))
        else:
            eye(p, f, f.w // 2, f.h // 2, max(1, f.w // 2 - 2), min(f.h // 3, 6))


def hoof(p, f, opts):
    """A calf's hoof of burnished bronze: it sparkled like the colour of burnished brass."""
    for v in range(f.h):
        for u in range(f.w):
            c = mix(GOLD_LIGHT, BRONZE, v / max(1, f.h - 1))
            if f.side == "front" and abs(u - f.w / 2) < 1:
                c = (40, 26, 16)
            p.put(f, u, v, shade(c, p.noise(0.06)))


def eyed_wing(name, pivot, rotation, span, rows=3, feathers=10, side=1, widen=1.5):
    """The archangel's layered wing (boxtex `wing`) in white and gold, an eye on nearly every feather."""
    w = wing(name, pivot, rotation, span, rows=rows, feathers=feathers, base=WING, tip=GOLD, side=side)
    styles = {
        0: eye_feather(WING, GOLD, eye_chance=0.9, at=0.62, shaft=GOLD_LIGHT),
        1: eye_feather(shade(WING, 0.95), OCHRE, eye_chance=0.9, shaft=GOLD_LIGHT),
        2: eye_feather(mix(WING, OCHRE, 0.35), GOLD_DARK, eye_chance=0.6, at=0.5, shaft=GOLD_LIGHT),
    }
    bone = w.boxes[0]
    bone.origin, bone.size = (bone.origin[0], -0.9, -0.3), (span, 1.8, 1.6)
    bone.style = gold_leaf(ornament=False)
    for child in w.children:
        if "_f" in child.name:
            row = int(child.name.rsplit("_f", 1)[1].split("_")[0])
            for b in child.boxes:
                b.style = styles.get(row, styles[2])
                width = b.size[0] * widen
                b.origin, b.size = (-width / 2, b.origin[1], b.origin[2]), (width, b.size[1], b.size[2])
    return w


def wheel(name, radius, segments, thick=1.6, rotation=(0, 0, 0), pivot=(0, 0, 0), children=()):
    """A wheel's rim of short eyed segments round a circle in the part's XY plane, with four spokes."""
    part = Part(name, pivot=pivot, rotation=rotation, children=list(children))
    length = 2 * math.pi * radius / segments * 1.04
    for i in range(segments):
        a = 2 * math.pi * i / segments
        part.children.append(Part("%s_rim_%d" % (name, i), pivot=(math.sin(a) * radius, -math.cos(a) * radius, 0), rotation=(0, 0, a),
                                  boxes=[Box((-length / 2, -thick / 2, -thick / 2), (length, thick, thick), eyed_rim)]))
    for i in range(4):
        a = math.pi / 4 + i * math.pi / 2
        part.children.append(Part("%s_spoke_%d" % (name, i), rotation=(0, 0, a),
                                  boxes=[Box((-0.35, -radius, -0.35), (0.7, radius - 1.2, 0.7), gold_leaf(ornament=False))]))
    part.boxes.append(Box((-1.4, -1.4, -1.0), (2.8, 2.8, 2.0), gold_leaf(ornament=False)))
    return part


def flame(name, pivot, rotation, length, width):
    """A tongue of fire on two crossed planes."""
    return Part(name, pivot=pivot, rotation=rotation, boxes=[
        Box((-width / 2, -length, 0), (width, length, 0.1), flame_blade(), flat=True),
        Box((0, -length * 0.85, -width * 0.4), (0.1, length * 0.85, width * 0.8), flame_blade(), flat=True),
    ])


# ---- The four faces on one head; each is built facing -Z and turned to its side. ----

def man():
    return Part("face_man", children=[], boxes=[
        Box((-2.6, -7.0, -3.5), (5.2, 6.4, 1.0), icon_face(SKIN, HAIR, eye=(48, 34, 24), stern=True)),
        Box((-0.45, -4.6, -3.95), (0.9, 2.2, 0.5), solid(SKIN, k=0.04)),
        Box((-2.4, -1.3, -3.6), (4.8, 2.6, 1.4), hair(HAIR)),
        Box((-1.4, 1.2, -3.3), (2.8, 1.4, 1.1), hair(HAIR)),
        Box((-3.0, -7.8, -3.7), (6.0, 1.0, 0.6), gold_leaf(ornament=False)),
    ])


def lion():
    locks = []
    for i in range(16):
        a = 2 * math.pi * i / 16
        r = 3.9 + 0.4 * (i % 2)
        locks.append(Part("mane_%d" % i, pivot=(math.sin(a) * 2.6, -3.6 - math.cos(a) * 2.9, -2.4), rotation=(0.35, 0, a),
                          boxes=[Box((-0.9, -r, -0.6), (1.8, r, 1.2), hair(MANE))]))
    return Part("face_lion", rotation=(0, math.pi / 2, 0), children=locks, boxes=[
        Box((-2.6, -6.6, -3.5), (5.2, 5.6, 1.0), lion_face),
        Box((-2.8, -5.4, -3.9), (5.6, 0.9, 0.6), solid(shade(LION, 0.8), k=0.06)),
        Box((-1.5, -3.6, -5.3), (3.0, 2.4, 1.9), muzzle(LION, nostrils=False)),
        Box((-0.8, -3.9, -5.6), (1.6, 0.9, 0.5), solid((48, 30, 22), k=0.05)),
        Box((-1.1, -1.4, -4.7), (2.2, 1.0, 1.3), solid(shade(LION, 0.9), k=0.06)),
    ])


def horn(name, x, sign):
    """A horn of three pieces turning outward, then up and forward, paling to the tip."""
    seg3 = Part(name + "_3", pivot=(sign * 2.0, 0, 0), rotation=(0.5, 0, -sign * 0.6),
                boxes=[Box((0 if sign > 0 else -2.0, -0.45, -0.45), (2.0, 0.9, 0.9), solid(shade(BONE, 1.05), k=0.04))])
    seg2 = Part(name + "_2", pivot=(sign * 2.4, 0, 0), rotation=(0.2, 0, -sign * 0.6), children=[seg3],
                boxes=[Box((0 if sign > 0 else -2.4, -0.65, -0.65), (2.4, 1.3, 1.3), solid(BONE, k=0.05))])
    return Part(name, pivot=(x, -6.2, -2.8), rotation=(0, sign * 0.3, -sign * 0.25), children=[seg2],
                boxes=[Box((0 if sign > 0 else -2.4, -0.85, -0.85), (2.4, 1.7, 1.7), solid(shade(BONE, 0.85), k=0.06))])


def ox():
    return Part("face_ox", rotation=(0, -math.pi / 2, 0), children=[horn("horn_r", -2.2, -1), horn("horn_l", 2.2, 1)], boxes=[
        Box((-2.3, -6.8, -3.5), (4.6, 6.6, 1.0), ox_face),
        Box((-2.0, -2.6, -5.4), (4.0, 2.6, 2.2), muzzle(OX_MUZZLE)),
        Box((-3.8, -5.6, -3.2), (1.5, 0.9, 1.0), solid(OX, k=0.08)),
        Box((2.3, -5.6, -3.2), (1.5, 0.9, 1.0), solid(OX, k=0.08)),
        Box((-1.4, -7.6, -3.6), (2.8, 1.2, 1.0), hair(shade(OX, 0.8))),
    ])


def eagle():
    hook = Part("beak_hook", pivot=(0, -3.9, -6.6), rotation=(0.9, 0, 0),
                boxes=[Box((-0.6, 0, -0.6), (1.2, 1.7, 1.0), solid(shade(BEAK, 0.9), k=0.05))])
    crest = [Part("crest_%d" % i, pivot=(-1.5 + i * 1.0, -6.8, -2.6), rotation=(-0.5, 0, -0.3 + i * 0.15),
                  boxes=[Box((-0.6, -3.2, 0), (1.2, 3.2, 0.2), feather(EAGLE, EAGLE_DARK))]) for i in range(4)]
    return Part("face_eagle", rotation=(0, math.pi, 0), children=[hook] + crest, boxes=[
        Box((-2.5, -7.0, -3.5), (5.0, 5.6, 1.0), eagle_face),
        Box((-2.8, -5.9, -3.8), (5.6, 0.8, 0.5), solid(EAGLE_DARK, k=0.06)),
        Box((-0.9, -4.6, -6.6), (1.8, 1.4, 3.2), gold_leaf(ornament=False)),
        Box((-0.7, -3.1, -5.6), (1.4, 0.6, 2.1), solid(shade(BEAK, 0.75), k=0.05)),
        Box((-2.6, -1.6, -3.6), (5.2, 1.8, 1.2), feather(EAGLE, EAGLE_DARK)),
    ])


def scaled(part, k):
    """Scales a built part tree about its own pivot (pivots of children, box origins and sizes)."""
    for b in part.boxes:
        b.origin, b.size = tuple(c * k for c in b.origin), tuple(c * k for c in b.size)
    for child in part.children:
        child.pivot = tuple(c * k for c in child.pivot)
        scaled(child, k)
    return part


head = Part("head", pivot=(0, -14.6, 0), boxes=[
    Box((-3.0, -7.6, -3.0), (6.0, 7.6, 6.0), hair(HAIR)),
    Box((-1.4, 0.0, -1.4), (2.8, 1.2, 2.8), solid(SKIN, k=0.05)),
], children=[man(), lion(), ox(), eagle()])
scaled(head, 1.25)


# ---- The flaming sword. ----
blade = Part("blade", children=[
    flame("blade_flame_%d" % i, pivot=(side * 1.4, -6 - i * 3.6, 0), rotation=(0, 0, side * 0.5), length=3.2 + (i % 3) * 0.6, width=1.4)
    for i, side in ((i, 1 if i % 2 else -1) for i in range(8))
], boxes=[
    Box((-1.9, -34, -0.05), (3.8, 31.5, 0.1), flame_blade(), flat=True),
    Box((-0.05, -33, -1.9), (0.1, 30.5, 3.8), flame_blade(), flat=True),
    Box((-0.6, -32, -0.6), (1.2, 29.5, 1.2), solid((255, 246, 220), glow=True, k=0.03), flat=True),
])
sword = Part("sword", pivot=(0, 13.6, -0.6), rotation=(0.9, 0, -0.15), children=[blade], boxes=[
    Box((-0.6, -2.2, -0.6), (1.2, 5.2, 1.2), solid((70, 40, 24), k=0.1)),
    Box((-4.2, -3.3, -0.8), (8.4, 1.2, 1.6), gold_leaf(ornament=False)),
    Box((-1.0, -3.6, -1.0), (2.0, 1.8, 2.0), solid(CINNABAR, k=0.06)),
    Box((-1.0, 2.9, -1.0), (2.0, 1.6, 2.0), gold_leaf(ornament=False)),
])


def arm(label, side, hand_children):
    pauldrons = [Part("%s_pauldron_%d" % (label, k), pivot=(side * 0.6, -1.2 + k * 1.6, 0), rotation=(0, 0, side * (0.35 + 0.12 * k)),
                      boxes=[Box((-2.3 + side * 0.4, 0, -2.4), (4.6, 2, 4.8), scale_mail(ARMOUR, GOLD_DARK))]) for k in range(3)]
    return Part(label, pivot=(side * 6.0, -13.8, 0), boxes=[
        Box((-1.6, -0.5, -1.6), (3.2, 7, 3.2), drapery(CRIMSON, highlight=(214, 80, 70))),
        Box((-1.8, 6, -1.8), (3.6, 6, 3.6), scaled_eyes(ARMOUR, GOLD_DARK, chance=0.5)),
        Box((-1.4, 12, -1.4), (2.8, 2.8, 2.8), solid(SKIN, k=0.05)),
    ], children=pauldrons + hand_children)


def leg(label, x):
    return Part(label, pivot=(x, -15, 0), boxes=[
        Box((-1.8, 0, -1.8), (3.6, 7, 3.6), drapery(CRIMSON)),
        Box((-2.0, 6.5, -2.0), (4.0, 7.5, 4.0), gold_leaf()),
        Box((-2.2, 6.2, -2.2), (4.4, 1.2, 4.4), gold_leaf(ornament=False)),
        Box((-2.1, 14, -2.6), (1.9, 1.0, 4.2), hoof),
        Box((0.2, 14, -2.6), (1.9, 1.0, 4.2), hoof),
    ])


# ---- The wings: two stretched upward, their tips leaning toward each other; two folded down over the body. ----
wings = []
for side, label in ((-1, "right"), (1, "left")):
    upper = eyed_wing("upper_%s_wing" % label, pivot=(side * 2.6, -12.5, 3.2), rotation=(0.15, -side * 0.3, -side * 0.68), span=13,
                      rows=3, feathers=10, side=side)
    upper.children.append(eyed_wing("upper_%s_wing_tip" % label, pivot=(side * 14, 0, 0), rotation=(0, 0, -side * 0.4), span=11,
                                    rows=3, feathers=10, side=side))
    wings.append(upper)
    wings.append(eyed_wing("lower_%s_wing" % label, pivot=(side * 3.6, -11, 3.4), rotation=(0.1, side * 0.7, side * 1.35), span=15,
                           rows=3, feathers=10, side=side))

wheels = Part("wheels", pivot=(0, -6, 12), children=[
    wheel("wheel_outer", radius=13, segments=20, children=[
        Part("wheel_axis", rotation=(0, math.pi / 2, 0), children=[wheel("wheel_inner", radius=9.5, segments=16, thick=1.4)]),
    ]),
])

parts = [
    Part("figure", pivot=(0, 24, 0), children=[
        leg("right_leg", -2.3),
        leg("left_leg", 2.3),
        skirt("tunic", pivot=(0, -16.5, 0), top_radius=4.4, bottom_radius=6.6, height=11, panels=14,
              style=drapery(CRIMSON, highlight=(226, 96, 80), hem=GOLD, hem_width=1)),
        skirt("scales", pivot=(0, -17, 0), top_radius=4.8, bottom_radius=6.2, height=7.5, panels=12, style=scale_mail(ARMOUR, GOLD_DARK)),
        Part("body", pivot=(0, -15, 0), boxes=[
            Box((-3.8, -6.5, -2.7), (7.6, 6.5, 5.4), scale_mail(ARMOUR, GOLD_DARK)),
            Box((-4.9, -14.5, -3.2), (9.8, 8.4, 6.4), scaled_eyes(ARMOUR, GOLD_DARK)),
            Box((-3.6, -14.0, -3.7), (7.2, 6.2, 0.7), breastplate),
            Box((-4.3, -7.5, -3.1), (8.6, 1.4, 6.2), gold_leaf(ornament=False)),
            Box((-4.0, -1.2, -3.0), (8.0, 1.6, 6.0), gold_leaf()),
        ], children=[head, arm("right_arm", -1, [sword]), arm("left_arm", 1, []), wheels] + wings),
    ]),
]

if __name__ == "__main__":
    build("Cherub", "cherub", parts, size=256, seed=324, density=4)
