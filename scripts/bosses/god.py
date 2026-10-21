"""Бог с булавой: the Ancient of Days (Ветхий денми) of the icons, coming out of a cloud bank by the waist. A majestic elder
with a long white beard and hair of many strands, a noble face with glowing eyes, white and gold robes with deep folds and
gold assist lines, a cross-inscribed nimbus with rays of glory, and an enormous gold flanged mace in his right hand.

Model space: y down, the waist (the cloud bank) is y = 0, the figure faces -Z. Built about 48 units from the waist to the top
of the nimbus and drawn at scale 11 (about 33 blocks of upper body). Density 4. The cloud bank round his waist is not part
of the model: the renderer draws it as soft billboards (GodClouds.java, textures from god_sky.py)."""
from boxtex import *

SKIN = (222, 186, 146)
WHITE = (240, 236, 226)
WHITE_SHADOW = (176, 178, 190)
HAIR_WHITE = (246, 244, 238)
HAIR_GREY = (168, 168, 178)
OCHRE = (214, 164, 72)
EYE = (255, 246, 196)


def robe(base, line=GOLD_LIGHT, hem=GOLD, hem_width=2, assist=True):
    """Icon cloth: deep folds with light ridges and, over them, the fine gold hatching of assist (ассист) that marks the
    divine light on the garments, with a dotted gold hem."""
    folds = drapery(base, highlight=mix(base, (255, 255, 255), 0.6), hem=hem, hem_width=hem_width)

    def paint(p, f, opts):
        folds(p, f, opts)
        if not assist or f.side not in ("front", "back", "left", "right"):
            return
        d = opts.get("density", 1)
        step = 5 * d
        for v in range(f.h - hem_width * d):
            for u in range(f.w):
                if (u + v * 2 + f.x) % step == 0 and math.sin(u * 0.13 + v * 0.07 + f.y) > 0.1:
                    p.put(f, u, v, mix(line, (255, 250, 220), 0.3 * p.rand.random()))
    return paint


def strand(base, shadow, tip_clear=True):
    """One lock of hair or beard: lengthwise strands, light on the ridges and dark between, thinning to a ragged tip."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        long_side = f.h >= f.w
        L, W = (f.h, f.w) if long_side else (f.w, f.h)
        phase = p.rand.random() * 6
        for a in range(L):
            t = a / max(1, L - 1)
            for b in range(W):
                u, v = (b, a) if long_side else (a, b)
                half = (W - 1) / 2
                taper = 1.0 if t < 0.65 else max(0.15, 1 - (t - 0.65) / 0.35)
                if tip_clear and W > 2 and abs(b - half) > half * taper + 0.5:
                    p.put(f, u, v, None)
                    continue
                w = math.sin(b * 2.2 / max(1, d) + phase + a * 0.04)
                c = mix(shadow, base, 0.45 + 0.55 * max(0.0, w))
                if w > 0.92:
                    c = mix(c, (255, 255, 255), 0.5)
                p.put(f, u, v, shade(c, p.noise(0.04) * (1.05 - 0.15 * t)))
    return paint


def elder_face(p, f, opts):
    """The face of the Ancient of Days: warm ochre flesh modelled with light, a high lined brow, the eyes deep in shadow
    under it with only their light showing (white-gold, glowing), a long straight nose, hollow cheeks, and the white
    moustache and beard over the mouth and chin. The other sides are white hair (back, top) or flesh."""
    d = opts.get("density", 1)
    if f.side != "front":
        col = HAIR_WHITE if f.side in ("back", "top") else SKIN
        for v in range(f.h):
            for u in range(f.w):
                if col == HAIR_WHITE:
                    w = math.sin(u * 1.9 / max(1, d) + v * 0.05)
                    c = mix(HAIR_GREY, HAIR_WHITE, 0.5 + 0.5 * max(0.0, w))
                else:
                    c = shade(SKIN, 0.9 - 0.25 * v / max(1, f.h))
                p.put(f, u, v, shade(c, p.noise(0.03)))
        return
    W, H = f.w, f.h
    ey = int(H * 0.46)
    for v in range(H):
        for u in range(W):
            dx, dy = (u + 0.5 - W / 2) / (W / 2), (v + 0.5) / H
            c = shade(SKIN, 1.08 - 0.4 * dx * dx)
            # Light on the brow, the bridge of the nose and the cheekbones (движки).
            if (dy < 0.3 and abs(dx) < 0.5) or (abs(dx) < 0.1 and 0.4 < dy < 0.66) or (0.35 < abs(dx) < 0.62 and 0.55 < dy < 0.62):
                c = mix(c, (255, 238, 206), 0.3)
            # Hollow cheeks.
            if 0.3 < abs(dx) < 0.75 and 0.62 < dy < 0.75:
                c = shade(c, 0.86)
            # The sockets: a band of deep shadow under the brow.
            if abs(dy - ey / H) < 0.075 and abs(dx) < 0.82 and not (abs(dx) < 0.1):
                c = mix(c, (40, 26, 20), 0.75 - 0.35 * abs(dx))
            p.put(f, u, v, shade(c, p.noise(0.025)))
    # The brow's lines.
    for k, frac in enumerate((0.19, 0.25)):
        v = int(H * frac)
        for u in range(int(W * (0.3 + 0.05 * k)), int(W * (0.7 - 0.05 * k))):
            p.put(f, u, v + int(0.8 * math.sin((u - W / 2) * 0.12)), shade(SKIN, 0.86))
    # Hair over the forehead's top.
    for v in range(int(H * 0.12)):
        for u in range(W):
            if v < H * 0.12 - 0.8 * abs(math.sin(u * 0.5)):
                p.put(f, u, v, mix(HAIR_GREY, HAIR_WHITE, 0.6))
    # The eyes: deep, glowing, almond-shaped, with a hot core.
    for side in (-1, 1):
        cx = W / 2 + side * W * 0.22
        ew, eh = W * 0.1, max(1.0, H * 0.028)
        for u in range(int(cx - ew - 1), int(cx + ew + 2)):
            for v in range(int(ey - eh - 1), int(ey + eh + 2)):
                q = ((u + 0.5 - cx) / ew) ** 2 + ((v + 0.5 - ey) / eh) ** 2
                if q <= 1.0:
                    c = (255, 255, 240) if q < 0.35 else EYE
                    p.put(f, u, v, c, c)
                elif q <= 1.9:
                    p.put(f, u, v, (120, 92, 40), (90, 70, 30))
    # The long straight nose with its shadow side.
    nx = W // 2
    for v in range(int(H * 0.42), int(H * 0.67)):
        for k in range(max(1, d // 2)):
            p.put(f, nx + k, v, mix(SKIN, (255, 238, 206), 0.35))
            p.put(f, nx - 1 - k, v, shade(SKIN, 0.72))
    for u in range(nx - d, nx + d):
        p.put(f, u, int(H * 0.67), shade(SKIN, 0.6))
    # The moustache and the beard's start over the lower face.
    for v in range(int(H * 0.66), H):
        for u in range(W):
            dx = (u - W / 2) / (W / 2)
            top = 0.7 + 0.06 * abs(dx) - 0.03 * abs(math.sin(u * 0.7))
            if v >= H * top:
                w = math.sin(u * 1.7 / max(1, d) + v * 0.05)
                p.put(f, u, v, mix(HAIR_GREY, HAIR_WHITE, 0.5 + 0.5 * max(0.0, w)))


def cross_nimbus(p, f, opts):
    """A gold nimbus with the cross inscribed (крестчатый нимб), its arms set with cinnabar and pearls; the disc glows."""
    W, H = f.w, f.h
    R = min(W, H) / 2
    if R < 3:
        for v in range(H):
            for u in range(W):
                p.put(f, u, v, None)
        return
    d = opts.get("density", 1)
    for v in range(H):
        for u in range(W):
            dx, dy = u + 0.5 - W / 2, v + 0.5 - H / 2
            r = math.hypot(dx, dy)
            if r > R:
                p.put(f, u, v, None)
                continue
            c = mix(GOLD_LIGHT, GOLD, (r / R) ** 2 * 0.7)
            ang = math.atan2(dy, dx)
            if int((ang + math.pi) / (2 * math.pi) * 48) % 2 == 0:
                c = shade(c, 0.95)
            arm = abs(dx) < R * 0.17 or abs(dy) < R * 0.17
            if arm and r > R * 0.55:
                c = CINNABAR if (abs(dx) < R * 0.12 or abs(dy) < R * 0.12) else GOLD_DARK
                if r % (3 * d) < d and (abs(dx) < d or abs(dy) < d):
                    c = (250, 244, 230)
            if R - 1.5 * d < r:
                c = GOLD_DARK
            elif R - 3 * d < r < R - 2 * d:
                c = (252, 240, 200) if int(ang * 30) % 2 else GOLD_DARK
            glow = c if r < R - 1.5 * d else None
            p.put(f, u, v, c, glow)


def glory_ray(p, f, opts):
    """A ray of glory: a narrow shaft of light, white-gold at the root and fading out to nothing at the tip."""
    if f.side not in ("front", "back"):
        for v in range(f.h):
            for u in range(f.w):
                p.put(f, u, v, None)
        return
    W, H = f.w, f.h
    for v in range(H):
        t = v / max(1, H - 1)
        for u in range(W):
            across = abs(u + 0.5 - W / 2) / (W / 2)
            if across > 1 - t * 0.85:
                p.put(f, u, v, None)
                continue
            c = mix((255, 252, 230), GOLD_LIGHT, t)
            p.put(f, u, v, c, shade(c, 1 - 0.5 * t))


def mace_flange(p, f, opts):
    """A flange blade of the mace: burnished gold, a bright honed edge line and a dark rim, so it reads crisp."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            t = (u + v) / max(1, f.w + f.h)
            c = mix(GOLD_LIGHT, GOLD, t)
            edge = min(u, v, f.w - 1 - u, f.h - 1 - v)
            if edge == 0:
                c = GOLD_DARK
            elif edge == 1 and f.side in ("left", "right"):
                c = (255, 240, 176)
            p.put(f, u, v, c)


def knuckles(p, f, opts):
    """A finger round the haft: flesh with a lit knuckle ridge and a dark crease below it."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            c = shade(SKIN, 1.05 - 0.3 * v / max(1, f.h - 1))
            if v == f.h - 1:
                c = shade(SKIN, 0.6)
            p.put(f, u, v, shade(c, p.noise(0.03)))


def ornament_band(p, f, opts):
    """A gold band of the shaft: a running pattern of dark punched dots between two light ridges."""
    gold_leaf(ornament=False)(p, f, opts)
    d = opts.get("density", 1)
    for u in range(f.w):
        p.put(f, u, 0, GOLD_LIGHT)
        p.put(f, u, f.h - 1, GOLD_LIGHT)
        if (u // d) % 2 == 0:
            p.put(f, u, f.h // 2, GOLD_DARK)


def shaft(p, f, opts):
    """The mace's haft: gold wound with a spiral of darker gold, like a crozier's."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            c = mix(GOLD_LIGHT, GOLD, u / max(1, f.w - 1))
            if (v + u) % (4 * d) < d:
                c = GOLD_DARK
            p.put(f, u, v, shade(c, p.noise(0.04)))


# ---- The figure. ----

def locks(name, pivot, count, arc, radius, length, width, tilt, color, shadow, seed, y_jitter=1.0, forward=0.0):
    """Many locks around an arc of the head (angles in radians, 0 at the back), hanging down and out."""
    rnd = random.Random(seed)
    part = Part(name, pivot=pivot)
    a0, a1 = arc
    for i in range(count):
        a = a0 + (a1 - a0) * (i + rnd.random() * 0.6) / count
        x, z = math.sin(a) * radius, math.cos(a) * radius
        l = length * rnd.uniform(0.75, 1.15)
        part.children.append(Part("%s_%d" % (name, i), pivot=(x, rnd.uniform(-y_jitter, y_jitter), z),
                                  rotation=(tilt * math.cos(a) + forward + rnd.uniform(-0.08, 0.08), -a, -tilt * math.sin(a) * 0.9 + rnd.uniform(-0.06, 0.06)),
                                  boxes=[Box((-width / 2, 0, -0.45), (width * rnd.uniform(0.85, 1.15), l, 0.9), strand(color, shadow))]))
    return part


def beard(name, pivot, seed=11):
    """The long beard: three layers of locks fanned from the jaw down over the chest, longest in the middle."""
    rnd = random.Random(seed)
    part = Part(name, pivot=pivot)
    k = 0
    for layer, (count, spread, length, z) in enumerate(((13, 4.2, 19, 0.0), (11, 3.6, 22, -0.8), (9, 2.8, 24, -1.6))):
        for i in range(count):
            t = (i + 0.5) / count * 2 - 1
            x = t * spread
            l = length * (1 - 0.35 * t * t) * rnd.uniform(0.88, 1.08)
            part.children.append(Part("%s_%d" % (name, k), pivot=(x, -0.6 * (1 - t * t) + rnd.uniform(-0.3, 0.3), z - 0.4 * (1 - t * t)),
                                      rotation=(-0.18 - 0.05 * layer + rnd.uniform(-0.04, 0.04), 0, -t * 0.18 + rnd.uniform(-0.05, 0.05)),
                                      boxes=[Box((-0.85, 0, -0.45), (1.7 * rnd.uniform(0.9, 1.2), l, 0.9),
                                                 strand(HAIR_WHITE if layer != 1 else (236, 236, 236), HAIR_GREY))]))
            k += 1
    return part


def glory(name, pivot, radius, rays=28):
    """The nimbus with its rays of glory: a cross-inscribed disc and long rays fanning out behind it, a faint outer ring."""
    part = Part(name, pivot=pivot, boxes=[Box((-radius, -radius, 0), (2 * radius, 2 * radius, 0.1), cross_nimbus, flat=True)])
    rays_part = Part(name + "_rays", pivot=(0, 0, 0.4))
    for i in range(rays):
        a = 2 * math.pi * i / rays
        l = radius * (1.1 if i % 2 == 0 else 0.75)
        w = 2.2 if i % 2 == 0 else 1.4
        rays_part.children.append(Part("%s_ray_%d" % (name, i), pivot=(0, 0, 0.05 * (i % 3)), rotation=(0, 0, a),
                                       boxes=[Box((-w / 2, radius * 0.75, 0), (w, l, 0.1), glory_ray, flat=True)]))
    part.children.append(rays_part)
    return part


def arm(label, side, children, sleeve=WHITE, forearm_children=()):
    """A great arm in a wide icon sleeve: the upper arm under the gold himation, a bell sleeve with a gold hem at the
    forearm, the hand. The arm hangs down (+Y) from the shoulder."""
    forearm = Part(label + "_fore", pivot=(0, 13, 0), rotation=(-0.9, 0, 0), boxes=[
        Box((-3.0, 0, -3.0), (6, 11, 6), robe(sleeve, hem=GOLD, hem_width=2)),
        Box((-3.8, 6.5, -3.8), (7.6, 5, 7.6), robe(sleeve, hem=GOLD, hem_width=3)),
        Box((-2.1, 11, -2.1), (4.2, 3, 4.2), solid(SKIN, k=0.05)),
    ], children=list(forearm_children) + children)
    return Part(label, pivot=(side * 13.5, -23.5, 0), rotation=(0.15, 0, -side * (0.42 if side < 0 else 0.18)), boxes=[
        Box((-3.6, -1.5, -3.6), (7.2, 15, 7.2), robe(sleeve, hem=None)),
        Box((-4.4, -2.5, -4.2), (8.8, 7, 8.4), robe(OCHRE, line=(255, 236, 170), hem=GOLD, hem_width=1)),
    ], children=[forearm])


def hand(label, fingers_up=False):
    """The blessing hand with two fingers raised (двуперстие), or just the wrist when the fist is the mace's grip."""
    boxes = [Box((-1.9, 0, -1.9), (3.8, 1.6, 3.8), solid(SKIN, k=0.05))]
    children = []
    if fingers_up:
        boxes = [Box((-1.9, 0, -0.9), (3.8, 4.2, 1.8), knuckles)]
        # Index and middle fingers raised, the ring and little fingers folded onto the palm, the thumb over them.
        for i, (x, l) in enumerate(((-1.25, 4.4), (-0.05, 4.8))):
            children.append(Part("%s_finger_%d" % (label, i), pivot=(x, 4.0, -0.2), rotation=(0.12, 0, 0),
                                 boxes=[Box((-0.55, 0, -0.5), (1.1, l, 1.0), knuckles)]))
        boxes.append(Box((0.7, 3.4, -1.5), (1.2, 1.6, 1.0), knuckles))
        boxes.append(Box((-0.2, 2.6, -1.6), (2.4, 0.9, 0.9), knuckles))
        children.append(Part(label + "_glow", pivot=(0, 1.5, -2.8), boxes=[Box((-2.5, -2.5, 0), (5, 5, 0.1), halo_disc(rim=(255, 236, 160), fill=(255, 252, 230)), flat=True)]))
    return Part(label, pivot=(0, 13.5, 0), boxes=boxes, children=children)


def mace(name):
    """The enormous mace: a solid gold haft with ornament bands and a pommel, a compact head with six crisp flanges —
    thin stepped blades narrowing to a point — and the eight-pointed Orthodox cross on top. The haft runs up (-Y) from the
    fist, and the fist itself is part of the mace: the palm behind the haft, four fingers wrapped round it, the thumb over them."""
    flanges = []
    for i in range(6):
        a = 2 * math.pi * i / 6 + math.pi / 6
        flanges.append(Part("%s_flange_%d" % (name, i), pivot=(0, -32.2, 0), rotation=(0, a, 0), boxes=[
            Box((-0.4, -4.4, -6.4), (0.8, 8.8, 3.2), mace_flange),
            Box((-0.4, -2.4, -7.8), (0.8, 4.8, 1.4), mace_flange),
        ]))
    bands = [Box((-1.5, y, -1.5), (3.0, 1.2, 3.0), ornament_band) for y in (-25, -17, -9, 5.6)]
    grip = [Box((-1.6, -0.4, 0.95), (3.2, 4.6, 1.5), solid(SKIN, k=0.05)),
            Box((-2.0, -0.4, -1.3), (0.8, 4.6, 2.6), solid(SKIN, k=0.05)),
            Box((1.2, -0.9, -1.4), (0.85, 2.6, 2.6), solid(shade(SKIN, 0.95), k=0.05))]
    grip += [Box((-1.6, 0.05 + k * 1.1, -1.95), (3.0, 0.95, 0.8), knuckles) for k in range(4)]
    return Part(name, pivot=(0, 2, -0.3), rotation=(1.2, 0, -0.3), boxes=[
        Box((-1.0, -26, -1.0), (2.0, 32, 2.0), shaft),
        Box((-1.8, 6, -1.8), (3.6, 2.6, 3.6), gold_leaf(ornament=False)),
        Box((-2.1, -28.6, -2.1), (4.2, 2.6, 4.2), ornament_band),
        Box((-3.4, -36.0, -3.4), (6.8, 7.6, 6.8), gold_leaf()),
        Box((-2.4, -37.6, -2.4), (4.8, 1.6, 4.8), gold_leaf(ornament=False)),
        # The finial: upright, titulus, crossbar (the slanted footrest is its own part below).
        Box((-0.5, -46.4, -0.5), (1.0, 8.8, 1.0), gold_leaf(ornament=False)),
        Box((-1.1, -45.0, -0.4), (2.2, 0.7, 0.8), gold_leaf(ornament=False)),
        Box((-2.4, -43.4, -0.4), (4.8, 0.9, 0.8), gold_leaf(ornament=False)),
    ] + bands + grip, children=flanges + [
        # The footrest: its left end, as one sees the cross from the front, raised.
        Part(name + "_footrest", pivot=(0, -40.4, 0), rotation=(0, 0, 0.45),
             boxes=[Box((-1.5, -0.35, -0.4), (3.0, 0.7, 0.8), gold_leaf(ornament=False))])])


def himation(name):
    """The gold-ochre himation thrown over the left shoulder and down across the chest: a few broad overlapping panels in
    heavy diagonal folds, reading as one cloth."""
    part = Part(name, pivot=(0, -24, 0))
    for i in range(5):
        part.children.append(Part("%s_%d" % (name, i), pivot=(8.5 - i * 3.6, -0.5 + i * 0.6, -7.0 - 0.15 * i),
                                  rotation=(-0.05, 0.05 * math.sin(i * 1.3), 0.6),
                                  boxes=[Box((-2.9, 0, -0.4), (5.8, 19 - abs(i - 2) * 2.0, 0.8),
                                             robe(OCHRE, line=(255, 238, 176), hem=GOLD, hem_width=1))]))
    return part


figure = Part("figure", pivot=(0, 0, 0), children=[
    skirt("robe", pivot=(0, -23, 0), top_radius=8.5, bottom_radius=12.5, height=27, panels=18,
          style=robe(WHITE, hem=None)),
    Part("torso", pivot=(0, 0, 0), boxes=[
        Box((-9, -14, -6.5), (18, 16, 13), robe(WHITE, hem=None)),
        Box((-11.5, -26, -7), (23, 13, 14), robe(WHITE, hem=None)),
        Box((-13, -27.5, -6.5), (26, 4, 13), robe(OCHRE, line=(255, 238, 176), hem=GOLD, hem_width=1)),
        Box((-5.2, -28, -7.6), (10.4, 2.4, 1.2), gold_leaf()),
        Box((3.2, -25.5, -7.5), (2.2, 25, 0.6), gold_leaf(ornament=False)),
    ], children=[
        himation("himation"),
        Part("neck", pivot=(0, -27.5, 0), boxes=[Box((-3, -3.5, -3), (6, 4, 6), solid(SKIN, k=0.05))], children=[
            Part("head", pivot=(0, -3, -0.5), boxes=[
                Box((-4.6, -11.5, -4.6), (9.2, 11.5, 9.2), elder_face),
                Box((-4.9, -12.3, -4.1), (9.8, 1.6, 9.0), strand(HAIR_WHITE, HAIR_GREY, tip_clear=False)),
                Box((-1, -6.6, -5.4), (2, 3.6, 1), solid(SKIN, k=0.04)),
            ], children=[
                locks("hair", pivot=(0, -9.5, 0.4), count=26, arc=(-2.0, 2.0), radius=4.8, length=19, width=2.0, tilt=0.16,
                      color=HAIR_WHITE, shadow=HAIR_GREY, seed=5),
                locks("hair_front", pivot=(0, -10.5, -0.5), count=10, arc=(-2.5, -1.7), radius=4.6, length=15, width=1.6, tilt=0.15,
                      color=HAIR_WHITE, shadow=HAIR_GREY, seed=8),
                locks("hair_front2", pivot=(0, -10.5, -0.5), count=10, arc=(1.7, 2.5), radius=4.6, length=15, width=1.6, tilt=0.15,
                      color=HAIR_WHITE, shadow=HAIR_GREY, seed=9),
                beard("beard", pivot=(0, -2.6, -4.8)),
                glory("halo", pivot=(0, -7, 7.5), radius=14),
            ]),
        ]),
        arm("right_arm", -1, [hand("right_hand")], forearm_children=[]),
        arm("left_arm", 1, [hand("left_hand", fingers_up=True)]),
    ]),
])

# The mace goes in the right fist.
right_hand = [c for c in figure.walk() if c.name == "right_hand"][0]
right_hand.children.append(mace("mace"))

parts = [Part("god", pivot=(0, 0, 0), children=[figure])]

if __name__ == "__main__":
    build("God", "god_avatar", parts, size=320, seed=77, density=4)
