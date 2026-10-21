"""Шестикрылый серафим (Isaiah 6:2): a small face of light that is one great eye, in a core of fire, a nimbus right behind it, and six great
wings of individual feathers in red, orange and gold, every feather painted with an eye that shines in the dark. Two wings
cross over the face, two cross over the feet and two are spread for flight; tongues of flame lick between them.
Built about 44 units tall and drawn at scale 2.4 (wings about ten blocks across), at 4 pixels per unit."""
from boxtex import *

SCLERA = (255, 246, 222)
IRIS = (255, 196, 60)
PUPIL = (20, 6, 4)
LID = (120, 16, 14)
CRIMSON = (168, 26, 20)
SCARLET = (214, 52, 24)
ORANGE = (240, 118, 30)
AMBER = (250, 170, 52)
FACE_LIGHT = (255, 232, 176)


def eye(p, f, u, v, w=3, h=1, iris=IRIS):
    """A small almond eye at (u, v): a glowing white, a gold iris round a dark pupil, red lids. `w` and `h` are half sizes
    in pixels. Also used by the cherub."""
    for du in range(-w - 1, w + 2):
        for dv in range(-h - 1, h + 2):
            outer = (du / (w + 0.9)) ** 2 + (dv / (h + 0.9)) ** 2 <= 1.0
            inner = (du / (w + 0.01)) ** 2 + (dv / (h + 0.01)) ** 2 <= 1.0
            if outer and not inner:
                p.put(f, u + du, v + dv, LID)
    for du in range(-w, w + 1):
        for dv in range(-h, h + 1):
            if (du / (w + 0.01)) ** 2 + (dv / (h + 0.01)) ** 2 <= 1.0:
                r = math.hypot(du, dv) if w >= h else math.hypot(du, dv)
                core = min(w, h)
                if r <= max(0.5, core * 0.45):
                    p.put(f, u + du, v + dv, PUPIL)
                elif r <= core + 0.4:
                    p.put(f, u + du, v + dv, iris, iris)
                else:
                    p.put(f, u + du, v + dv, SCLERA, SCLERA)


def eye_feather(base, tip, glow_tip=False, eye_chance=1.0, at=0.58, shaft=GOLD_LIGHT):
    """A feather (boxtex `feather`) with an eye painted on both broad sides, a little toward its tip."""
    plain = feather(base, tip, shaft=shaft, glow_tip=glow_tip)

    def paint(p, f, opts):
        plain(p, f, opts)
        if f.side not in ("front", "back") or p.rand.random() > eye_chance:
            return
        vertical = f.h >= f.w
        W, L = (f.w, f.h) if vertical else (f.h, f.w)
        if W < 6 or L < 8:
            return
        w = max(2, int(W * 0.42))
        h = max(1, int(w * 0.6))
        if vertical:
            eye(p, f, W // 2, int(L * at), w, h)
        else:
            eye(p, f, int(L * at), W // 2, h, w)
    return paint


def fiery_wing(name, pivot, rotation, span, rows=3, feathers=10, side=1, glow_tip=True, eyes=1.0, widen=1.6):
    """The archangel's layered wing (boxtex `wing`), repainted for a seraph: gold coverts over the bone, orange secondaries,
    crimson primaries burning at the tips, and an eye on every feather."""
    w = wing(name, pivot, rotation, span, rows=rows, feathers=feathers, base=ORANGE, tip=CRIMSON, side=side)
    styles = {
        0: eye_feather(CRIMSON, (255, 150, 40), glow_tip=glow_tip, eye_chance=eyes),
        1: eye_feather(SCARLET, CRIMSON, eye_chance=eyes),
        2: eye_feather(ORANGE, SCARLET, eye_chance=eyes * 0.8, at=0.5),
    }
    bone = w.boxes[0]
    bone.origin, bone.size = (bone.origin[0], -0.8, -0.2), (span, 1.6, 1.4)
    bone.style = scale_mail(GOLD, GOLD_DARK)
    for child in w.children:
        if "_f" in child.name:
            row = int(child.name.rsplit("_f", 1)[1].split("_")[0])
            for b in child.boxes:
                b.style = styles.get(row, styles[2])
                width = b.size[0] * widen
                b.origin, b.size = (-width / 2, b.origin[1], b.origin[2]), (width, b.size[1], b.size[2])
    return w


def great_eye(p, f, opts):
    """The face of light: one great open eye in a pale gold glare, with a burning iris and a dark pupil, all of it shining
    (the seraph is no cartoon face, only light and sight). The other sides are plain light."""
    W, H = f.w, f.h
    for v in range(H):
        for u in range(W):
            dx, dy = (u + 0.5 - W / 2) / (W / 2), (v + 0.5 - H / 2) / (H / 2)
            if f.side in ("front", "back") and dx * dx + dy * dy > 1.0:
                p.put(f, u, v, None)
                continue
            c = mix((255, 250, 226), FACE_LIGHT, min(1.0, dx * dx + dy * dy))
            p.put(f, u, v, c, shade(c, 0.85))
    if f.side != "front":
        return
    cx, cy = W / 2, H / 2
    ew, eh = W * 0.46, H * 0.27
    for v in range(H):
        for u in range(W):
            x, y = (u + 0.5 - cx) / ew, (v + 0.5 - cy) / eh
            # Almond: two arcs meeting at sharp corners.
            if abs(x) > 1 or abs(y) > (1 - x * x) ** 0.8:
                if abs(x) <= 1.08 and abs(y) <= (1 - min(1, x * x)) ** 0.8 + 0.28:
                    p.put(f, u, v, LID, (200, 60, 30))
                continue
            r = math.hypot(u + 0.5 - cx, (v + 0.5 - cy) * 1.0)
            if r < eh * 0.42:
                p.put(f, u, v, PUPIL)
                p.glow.putpixel((f.x + u, f.y + v), (0, 0, 0, 0))
            elif r < eh * 0.95:
                c = mix((255, 236, 120), (255, 120, 20), r / eh)
                p.put(f, u, v, c, c)
            else:
                p.put(f, u, v, SCLERA, SCLERA)
    # A glint in the pupil.
    p.put(f, int(cx - eh * 0.2), int(cy - eh * 0.2), (255, 255, 255), (255, 255, 255))


def core_fire(p, f, opts):
    """The burning body behind the face: crimson fire with hot flickers, all of it glowing faintly."""
    for v in range(f.h):
        for u in range(f.w):
            t = v / max(1, f.h - 1)
            c = shade(mix(AMBER, CRIMSON, t), p.noise(0.2))
            hot = p.rand.random() < 0.3
            c = mix(c, CANDLE, 0.55) if hot else c
            p.put(f, u, v, c, shade(c, 0.8 if hot else 0.45))


def sun_ray(p, f, opts):
    """A ray of the nimbus: a gold spike fading to a point."""
    long_side = f.h >= f.w
    L, W = (f.h, f.w) if long_side else (f.w, f.h)
    for a in range(L):
        for b in range(W):
            u, v = (b, a) if long_side else (a, b)
            t = 1 - a / max(1, L - 1)
            half = (W - 1) / 2
            if abs(b - half) > half * (1 - t) + 0.4:
                p.put(f, u, v, None)
                continue
            c = mix((255, 240, 180), ORANGE, t)
            p.put(f, u, v, c, c)


def flame(name, pivot, rotation, length, width):
    """A tongue of fire on two crossed planes, so it reads from the side too."""
    return Part(name, pivot=pivot, rotation=rotation, boxes=[
        Box((-width / 2, -length, 0), (width, length, 0.1), flame_blade(), flat=True),
        Box((0, -length * 0.85, -width * 0.4), (0.1, length * 0.85, width * 0.8), flame_blade(), flat=True),
    ])


# ---- The six wings. ----
wings = []
for side, label in ((-1, "right"), (1, "left")):
    # The flight pair: spread out and up behind the face, a tip joint for the stroke.
    flight = fiery_wing(label + "_wing", pivot=(side * 3.2, -2.5, 3.0), rotation=(0.1, -side * 0.15, -side * 0.42), span=15,
                        rows=3, feathers=12, side=side)
    flight.children.append(fiery_wing(label + "_wing_tip", pivot=(side * 15, 0, 0), rotation=(0, 0, -side * 0.25), span=14,
                                      rows=3, feathers=12, side=side))
    wings.append(flight)
    # Over the face: from above its corner the bone slopes across the brow to the other side; the two cross in front.
    wings.append(fiery_wing("upper_%s_wing" % label, pivot=(side * 10, -14.5, -3.6 - 0.5 * (side > 0)),
                            rotation=(0.18, side * 0.15, -side * 0.5), span=15, rows=3, feathers=9, side=-side, glow_tip=False))
    # Over the feet: from below the face the bone slopes down across, the feathers hanging to the ground.
    wings.append(fiery_wing("lower_%s_wing" % label, pivot=(side * 4.5, 3.0, -2.6 - 0.5 * (side > 0)),
                            rotation=(-0.12, side * 0.1, -side * 1.05), span=15, rows=3, feathers=9, side=-side, glow_tip=False))

# ---- Flames between the wings. ----
flames = []
for i, (x, y, z, roll, length) in enumerate([
        (-4.5, -5, 1.5, 0.9, 9), (4.5, -5, 1.5, -0.9, 9), (-5.5, 2, 1.0, 1.7, 8), (5.5, 2, 1.0, -1.7, 8),
        (-3.5, 6, 0.8, 2.5, 8), (3.5, 6, 0.8, -2.5, 8), (-5, -2, 1.2, 1.25, 10), (5, -2, 1.2, -1.25, 10)]):
    flames.append(flame("flame_%d" % i, pivot=(x, y, z), rotation=(0, 0, roll), length=length, width=2.6))

# ---- The nimbus: a disc of light right behind the face, and a crown of rays round it. ----
rays = []
for i in range(16):
    a = 2 * math.pi * i / 16
    long = 6 if i % 2 == 0 else 3.8
    rays.append(Part("ray_%d" % i, pivot=(math.sin(a) * 7.2, -math.cos(a) * 7.2, 0.05), rotation=(0, 0, a),
                     boxes=[Box((-1.2, -long, 0), (2.4, long, 0.1), sun_ray, flat=True)]))
corona = []
for i in range(12):
    a = 2 * math.pi * (i + 0.5) / 12
    corona.append(flame("corona_%d" % i, pivot=(math.sin(a) * 3.0, -math.cos(a) * 3.5, 0.2), rotation=(0, 0, a), length=3.2, width=1.6))

parts = [
    Part("seraph", pivot=(0, -6, 0), children=[
        Part("core", pivot=(0, 0, 0), boxes=[
            Box((-4, -5, 1.2), (8, 10, 4), core_fire),
        ], children=[
            Part("face", pivot=(0, 0, -1.2), boxes=[Box((-3.6, -4.0, -1.6), (7.2, 8.0, 2.0), great_eye, flat=True)], children=[
                Part("nimbus", pivot=(0, 0, 1.2), children=[
                    Part("nimbus_disc", boxes=[Box((-7, -7, 0), (14, 14, 0.1), halo_disc(), flat=True)]),
                    Part("nimbus_rays", children=rays),
                ]),
            ] + corona),
        ] + flames),
    ] + wings),
]

if __name__ == "__main__":
    build("Seraph", "seraph", parts, size=256, seed=59, density=4)
