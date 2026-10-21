"""Нимрод (Genesis 10-11): «a mighty hunter before the Lord», king of Babel and builder of the tower. A tall bronze-skinned
king in the Assyrian manner: a tall tiered crown of gold with crenellations like a ziggurat and fillets hanging behind, a
long beard of many curled strands in tiers, curled hair to the shoulders, a detailed face with kohl-lined eyes that
smoulder; a layered royal robe (a linen under-robe and a crimson over-robe with patterned borders and fringes, a fringed
shawl across the chest); a mason's leather apron with a trowel, a hammer, a plumb line and a square; gold cuffs on his
arms; a great composite bow with recurved tips, a quiver of fletched arrows on his back and a fired clay brick in his hand.
Built about 60 units tall and drawn at scale 1.35 (about five blocks to the crown), 4 pixels per unit."""
from boxtex import *

SKIN = (176, 112, 72)
SKIN_LIGHT = (212, 152, 104)
SKIN_DARK = (116, 68, 40)
HAIR = (42, 38, 56)
HAIR_LIGHT = (160, 150, 182)
CRIMSON = (126, 24, 32)
LAPIS = (38, 62, 138)
LAPIS_LIGHT = (86, 120, 196)
LINEN = (214, 200, 170)
LEATHER = (104, 66, 36)
LEATHER_DARK = (62, 38, 22)
CLAY = (164, 78, 46)
WOOD = (96, 62, 32)
HORN = (196, 170, 120)
IRON = (128, 128, 134)


# ---- Styles. ----

def flesh(base=SKIN):
    """Bronze skin with muscle modelling."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                x = u / max(1, f.w - 1)
                y = v / max(1, f.h - 1)
                mass = math.sin(x * math.pi * 1.6 + 0.4) * 0.5 + math.sin(y * math.pi * 1.2) * 0.35
                c = mix(shade(base, 0.82), SKIN_LIGHT, max(0.0, mass) * 0.5)
                if abs(math.sin(x * math.pi * 1.6 + 0.4)) < 0.12 and 0.15 < y < 0.85:
                    c = mix(c, SKIN_DARK, 0.3)
                p.put(f, u, v, shade(c, p.noise(0.035)))
    return paint


def rosette(p, f, cx, cy, r, petals=(240, 206, 110), heart=LAPIS):
    """A small Mesopotamian rosette: petals round a heart."""
    for v in range(int(cy - r - 1), int(cy + r + 2)):
        for u in range(int(cx - r - 1), int(cx + r + 2)):
            dx, dy = u + 0.5 - cx, v + 0.5 - cy
            d = math.hypot(dx, dy)
            if d <= r * 0.38:
                p.put(f, u, v, heart)
            elif d <= r and math.cos(math.atan2(dy, dx) * 8) > -0.3:
                p.put(f, u, v, petals)


def royal(base, border=LAPIS, fringe=True, border_w=2.0, diaper=True):
    """Royal wool: a diaper of small gold dots, a patterned border (zigzag between gold rules, rosettes) and a fringe of
    tassels with gaps at the very end."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        sides = f.side in ("front", "back", "left", "right")
        bw = int(border_w * d)
        fr = int(1.4 * d) if fringe and sides else 0
        for v in range(f.h):
            for u in range(f.w):
                k = 0.78 + 0.22 * math.sin(((u + 0.3 * v / max(1, d)) % (3 * d)) / (3 * d) * math.pi)
                c = shade(base, k * p.noise(0.04))
                if diaper and (u // d) % 3 == 1 and (v // d) % 3 == 1 and (u % d, v % d) == (d // 2, d // 2):
                    c = GOLD_LIGHT
                if sides and f.h - fr - bw <= v < f.h - fr:
                    b = v - (f.h - fr - bw)
                    if b < d // 2 + 1 or b >= bw - d // 2 - 1:
                        c = GOLD
                    else:
                        zig = abs(((u // max(1, d // 2)) % 4) - 2)
                        c = GOLD_LIGHT if abs((b - d // 2) - zig * (bw - d) / 4) < 1 else shade(border, p.noise(0.05))
                if sides and v >= f.h - fr:
                    t = (u // max(1, d // 2)) % 3
                    if t == 2 or (v >= f.h - d // 2 and t == 1):
                        p.put(f, u, v, None)
                        continue
                    c = GOLD if t == 0 else shade(base, 1.2)
                p.put(f, u, v, c)
        if sides and bw >= 2 * d and f.w >= 4 * d:
            for cx in range(2 * d, f.w - d, 4 * d):
                rosette(p, f, cx, f.h - fr - bw / 2, bw * 0.38)
    return paint


def assyrian_curls(base=HAIR, light=HAIR_LIGHT):
    """Tight Assyrian ringlets: rows of little spirals, each with a lit crest."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        cell = max(2, int(1.2 * d))
        for v in range(f.h):
            for u in range(f.w):
                row = v // cell
                cu = (u + (cell // 2 if row % 2 else 0)) % cell - cell / 2 + 0.5
                cv = v % cell - cell / 2 + 0.5
                r = math.hypot(cu, cv) / (cell / 2)
                spiral = math.sin(math.atan2(cv, cu) * 1 + r * 6)
                c = mix(shade(base, 0.7), light, max(0.0, spiral) * (1 - r) * 0.9) if r < 1 else shade(base, 0.5)
                p.put(f, u, v, shade(c, p.noise(0.05)))
    return paint


def gold_band(stones=True):
    """A band of gold with lapis and carnelian stones set in it; the carnelians glow faintly."""
    def paint(p, f, opts):
        gold_leaf(ornament=False)(p, f, opts)
        d = opts.get("density", 1)
        if not stones or f.side in ("top", "bottom") or f.h < 2 * d:
            return
        cy = f.h / 2
        for i, cx in enumerate(range(int(1.5 * d), f.w - d, 3 * d)):
            stone = LAPIS_LIGHT if i % 2 == 0 else (200, 60, 40)
            glow = None if i % 2 == 0 else (150, 40, 24)
            for v in range(f.h):
                for u in range(f.w):
                    if math.hypot(u + 0.5 - cx, v + 0.5 - cy) < d * 0.6:
                        p.put(f, u, v, stone, glow)
                    elif math.hypot(u + 0.5 - cx, v + 0.5 - cy) < d * 0.85:
                        p.put(f, u, v, GOLD_DARK)
    return paint


def cuff(p, f, opts):
    """A gold arm cuff with a rosette in the middle of each side."""
    gold_leaf(ornament=True)(p, f, opts)
    if f.side in ("front", "back", "left", "right") and f.h >= 2 * opts.get("density", 1):
        rosette(p, f, f.w / 2, f.h / 2, min(f.w, f.h) * 0.36, petals=(250, 224, 140), heart=(190, 50, 36))


def leather(base=LEATHER, stitch=True):
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                c = shade(base, p.noise(0.07) * (0.92 + 0.08 * math.sin(u * 0.7 + v * 0.3)))
                if stitch and f.side in ("front", "back") and (u in (d, f.w - 1 - d) or v == d) and (u + v) % 3 == 0:
                    c = BONE
                p.put(f, u, v, c)
    return paint


def wood(base=WOOD):
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                p.put(f, u, v, shade(base, (0.85 + 0.15 * math.sin(u * 2.1 + v * 0.13)) * p.noise(0.05)))
    return paint


def composite(p, f, opts):
    """A composite bow limb: horn on the belly, sinew-and-bark lacquer with gold bands on the back."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            c = shade((70, 40, 26), p.noise(0.06)) if f.side in ("front", "left", "right") else shade(HORN, p.noise(0.05))
            if (v // d) % 6 == 0:
                c = GOLD
            p.put(f, u, v, c)


def brick_style(p, f, opts):
    """A fired clay brick, stamped with a panel of cuneiform wedges on its broad faces."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            c = shade(CLAY, p.noise(0.1) * (0.9 + 0.1 * math.sin(u * 0.9 + v * 1.3)))
            if p.rand.random() < 0.04:
                c = (196, 150, 110)
            p.put(f, u, v, c)
    if f.side in ("top", "front", "back", "left", "right") and f.w >= 3 * d and f.h >= 3 * d:
        for v in range(d // 2 + 1, f.h - d // 2 - 1, max(2, d // 2 + 1)):
            u = d // 2 + 1
            while u < f.w - d // 2 - 2:
                if p.rand.random() < 0.7:
                    p.put(f, u, v, shade(CLAY, 0.45))
                    p.put(f, u + 1, v, shade(CLAY, 0.6))
                    p.put(f, u, v + 1, shade(CLAY, 0.6))
                u += p.rand.choice((2, 3))


def fletching(p, f, opts):
    feather((214, 200, 180), tip=CRIMSON)(p, f, opts)


def nimrod_face(p, f, opts):
    """The king's face: bronze skin; the hair low over the brow in curls under the crown; heavy joined brows; great almond eyes
    lined with kohl, whites and dark irises with a smouldering ember in them; a strong hooked nose; full lips under a
    moustache curled up at the ends; the beard begins on the cheeks."""
    if f.side != "front":
        col = HAIR if f.side in ("back", "top") else SKIN
        for v in range(f.h):
            for u in range(f.w):
                c = col
                if f.side in ("left", "right") and (v > f.h * 0.6 or u > f.w * 0.55 or v < f.h * 0.2):
                    c = HAIR
                p.put(f, u, v, shade(c, p.noise(0.05)))
        return
    W, H = f.w, f.h
    cx = W / 2
    for v in range(H):
        for u in range(W):
            dx = (u + 0.5 - cx) / (W / 2)
            dy = (v - H * 0.5) / (H / 2)
            c = shade(SKIN, 1.06 - 0.3 * dx * dx - 0.06 * max(0.0, dy))
            if 0.3 < abs(dx) < 0.62 and 0.5 * H < v < 0.6 * H:
                c = mix(c, SKIN_LIGHT, 0.3)
            p.put(f, u, v, shade(c, p.noise(0.03)))
    # Curls along the hairline.
    for v in range(int(H * 0.16)):
        for u in range(W):
            if v < H * 0.16 - 1.2 * abs(math.sin(u * 0.8)):
                p.put(f, u, v, mix(HAIR, HAIR_LIGHT, 0.3 * max(0.0, math.sin(u * 0.8 + v))))
    ey = int(H * 0.43)
    for side in (-1, 1):
        ex = cx + side * W * 0.21
        # Heavy brows drawn down toward the nose: an imperious, unforgiving look; shadow under them.
        for u in range(int(ex - W * 0.15), int(ex + W * 0.15) + 1):
            for v in range(int(ey - 3), int(ey - 1)):
                p.put(f, u, v, shade(SKIN, 0.7))
        for u in range(int(ex - W * 0.19), int(ex + W * 0.19) + 1):
            t = (u + 0.5 - ex) * side
            by = int(H * 0.33 - t * 0.22)
            for k in range(3):
                p.put(f, u, by + k, HAIR if k < 2 else shade(HAIR, 1.6))
        # The almond eye: a kohl outline, the white, the iris with an ember.
        ew, eh = W * 0.15, max(1.6, H * 0.065)
        for u in range(int(ex - ew - 1), int(ex + ew + 2)):
            for v in range(int(ey - eh - 1), int(ey + eh + 2)):
                q = ((u + 0.5 - ex) / ew) ** 2 + ((v + 0.5 - ey) / eh) ** 2
                if q <= 1.0:
                    p.put(f, u, v, (232, 222, 200))
                elif q <= 1.7:
                    p.put(f, u, v, (20, 14, 16))
        # The kohl line runs out toward the temple.
        for k in range(3):
            p.put(f, int(ex + side * (ew + 1 + k)), int(ey - k * 0.4), (20, 14, 16))
        for u in range(int(ex - 1), int(ex + 1)):
            for v in range(int(ey - eh + 0.5), int(ey + eh + 0.5)):
                p.put(f, u, v, (54, 28, 20))
        p.put(f, int(ex), int(ey), (255, 120, 40), (190, 70, 20))
        p.put(f, int(ex - 1), int(ey), (220, 90, 30), (150, 50, 16))
    for v in range(int(H * 0.28), int(H * 0.36)):
        p.put(f, int(cx - 1), v, shade(SKIN, 0.7))
    # A strong hooked nose with a shadowed side.
    for v in range(int(H * 0.42), int(H * 0.64)):
        p.put(f, int(cx + 1), v, shade(SKIN, 0.68))
    for v in range(int(H * 0.38), int(H * 0.64)):
        p.put(f, int(cx - 1), v, shade(SKIN, 0.75))
        p.put(f, int(cx), v, SKIN_LIGHT)
    for u in range(int(cx - 3), int(cx + 3)):
        p.put(f, u, int(H * 0.64), shade(SKIN, 0.5 if u in (int(cx - 3), int(cx + 2)) else 0.85))
    # The moustache, curled up at the ends, over full lips.
    my = int(H * 0.74)
    for u in range(int(cx - W * 0.3), int(cx + W * 0.3)):
        a = abs(u + 0.5 - cx) / (W * 0.3)
        y0 = my - 2 + int(a * 1.5) - (2 if a > 0.85 else 0)
        p.put(f, u, y0, HAIR)
        p.put(f, u, y0 + 1, mix(HAIR, HAIR_LIGHT, 0.4))
    for u in range(int(cx - 2), int(cx + 2)):
        p.put(f, u, my + 1, (140, 60, 50))
        p.put(f, u, my + 2, (110, 46, 40))
    for v in range(int(H * 0.6), H):
        for u in range(W):
            dx = abs(u + 0.5 - cx) / (W / 2)
            if dx > 0.8 - (v - H * 0.6) / H * 0.5 or v > H * 0.88:
                if not (abs(u + 0.5 - cx) < 2.5 and my <= v <= my + 2):
                    p.put(f, u, v, mix(HAIR, HAIR_LIGHT, 0.4 * max(0.0, math.sin(u * 1.3 + v * 0.9))))


# ---- Builders. ----

def hand(label, side, pivot, curl=1.3, children=()):
    """A hand: the palm (facing in), four two-jointed fingers curled toward the palm and a thumb in front; `side` -1 is his right."""
    skin = flesh()
    part = Part(label, pivot=pivot, boxes=[Box((-0.85, 0, -1.8), (1.7, 3.0, 3.6), skin)])
    for i, z in enumerate((-1.35, -0.45, 0.45, 1.35)):
        length = 1.5 if i in (1, 2) else 1.25
        tipp = Part("%s_tip%d" % (label, i), pivot=(0, length, 0), rotation=(0, 0, side * curl * 0.9),
                    boxes=[Box((-0.38, -0.1, -0.38), (0.76, 1.2, 0.76), skin)])
        part.children.append(Part("%s_finger%d" % (label, i), pivot=(-side * 0.25, 2.85, z), rotation=(0, 0, side * curl),
                                  boxes=[Box((-0.42, 0, -0.42), (0.84, length, 0.84), skin)], children=[tipp]))
    part.children.append(Part(label + "_thumb", pivot=(-side * 0.5, 0.9, -1.7), rotation=(-0.5, 0, side * 0.6),
                              boxes=[Box((-0.45, 0, -0.45), (0.9, 2.2, 0.9), skin)]))
    part.children.extend(children)
    return part


def robe(name, pivot, top_radius, bottom_radius, height, panels, style, front_short=0.0, back_long=0.0, sway=0.05):
    """A bell of tilted cloth panels like `skirt`, but the hem may rise in front (to show the feet) and trail behind."""
    part = Part(name, pivot=pivot)
    width = 2 * math.pi * max(top_radius, bottom_radius) / panels * 1.18
    for i in range(panels):
        a = 2 * math.pi * i / panels
        h = height - front_short * max(0.0, math.cos(a)) ** 2 + back_long * max(0.0, -math.cos(a)) ** 2
        tilt = math.atan2(bottom_radius - top_radius, h)
        part.children.append(Part("%s_%d" % (name, i), pivot=(math.sin(a) * top_radius, 0, -math.cos(a) * top_radius),
                                  rotation=(-tilt + sway * math.sin(i * 1.7), -a, 0),
                                  boxes=[Box((-width / 2, 0, -0.4), (width, h, 0.8), style)]))
    return part


def crenels(name, y, half, count, size, style):
    """Merlons standing round the top edge of a square tier of the crown, like the battlements of a tower."""
    part = Part(name, pivot=(0, y, 0))
    per = max(1, count // 4)
    for s in range(4):
        for k in range(per):
            t = -half + (k + 0.5) * 2 * half / per
            x, z = [(t, -half), (half, t), (-t, half), (-half, -t)][s]
            part.children.append(Part("%s_%d_%d" % (name, s, k), pivot=(x, 0, z), rotation=(0, -s * math.pi / 2, 0),
                                      boxes=[Box((-size / 2, -size * 1.2, -0.4), (size, size * 1.2, 0.8), style)]))
    return part


# ---- The model. ----

skin = flesh()
over = royal(CRIMSON, border=LAPIS)
under = royal(LINEN, border=LAPIS, diaper=False, border_w=1.5)
gold_plain = gold_leaf(ornament=False)

# The composite bow: a leather grip, limbs of three angled segments each with a recurved tip, the string in two halves from
# the tips to the nocking point (they swing back when he draws), and an arrow on the string that shows only while drawing.
SEGS = [(5.6, 0.2), (5.0, 0.3), (3.0, -1.05)]


def limb_tip(sign):
    """Where a limb ends, in (y, z) from the grip: sign -1 for the upper limb, +1 for the lower. A bend b tilts the limb
    toward the archer (+Z); the last one recurves away."""
    y, z, angle = sign * 2.0, 0.0, 0.0
    for length, bend in SEGS:
        angle += sign * bend
        y += sign * length * math.cos(angle)
        z += sign * length * math.sin(angle)
    return y, z


def limb(name, sign):
    """A limb as a chain of segments, each a child of the one before."""
    root = parent = None
    for k, (length, bend) in enumerate(SEGS):
        width = 1.7 - 0.25 * k
        box = Box((-width / 2, -length if sign < 0 else 0, -0.75), (width, length, 1.5), composite)
        pivot = (0, sign * 2.0, 0) if parent is None else (0, sign * SEGS[k - 1][0], 0)
        node = Part("%s_%d" % (name, k), pivot=pivot, rotation=(sign * bend, 0, 0), boxes=[box])
        if parent is None:
            root = node
        else:
            parent.children.append(node)
        parent = node
    return root


upper_tip = limb_tip(-1)
lower_tip = limb_tip(1)
NOCK = (0.0, (upper_tip[1] + lower_tip[1]) / 2)


def string_half(name, tip):
    ty, tz = tip
    dy, dz = NOCK[0] - ty, NOCK[1] - tz
    return Part(name, pivot=(0, ty, tz), rotation=(math.atan2(dz, dy), 0, 0),
                boxes=[Box((-0.2, 0, -0.2), (0.4, math.hypot(dy, dz), 0.4), solid(BONE, k=0.04))])


bow = Part("bow", pivot=(-1.3, 2.2, 0.2), rotation=(0.1, -1.2, 0), boxes=[
    Box((-0.9, -2.2, -0.9), (1.8, 4.4, 1.8), leather(LEATHER_DARK, stitch=False)),
    Box((-0.8, -2.6, -0.8), (1.6, 0.6, 1.6), gold_plain),
    Box((-0.8, 2.0, -0.8), (1.6, 0.6, 1.6), gold_plain),
], children=[
    limb("bow_upper", -1),
    limb("bow_lower", 1),
    string_half("string_upper", upper_tip),
    string_half("string_lower", lower_tip),
    Part("nocked_arrow", pivot=(0, 0, NOCK[1]), boxes=[
        Box((-0.2, -0.2, -17), (0.4, 0.4, 18), wood((150, 112, 62))),
        Box((-0.45, -0.45, -18.2), (0.9, 0.9, 1.2), metal(IRON, (70, 70, 76), (200, 200, 206))),
        Box((-0.05, -1.2, -1.5), (0.1, 2.4, 3.0), fletching),
        Box((-1.2, -0.05, -1.5), (2.4, 0.1, 3.0), fletching),
    ], children=[Part("arrow_point", pivot=(0, 0, -18.2), rotation=(0, 0.785, 0), boxes=[Box((-0.6, -0.15, -0.6), (1.2, 0.3, 1.2), metal(IRON, (70, 70, 76), (200, 200, 206)))])]),
])

brick = Part("brick", pivot=(1.6, 3.4, 0), rotation=(0.6, 0, 0.2), boxes=[Box((-1.6, -1.2, -2.6), (3.2, 2.4, 5.2), brick_style)])


def arm(label, side, rest, fore_rest, held):
    """An arm of a king: a fringed sleeve, a muscled upper arm with a gold armlet, a bare forearm with a wide gold cuff, the hand."""
    forearm = Part(label + "_forearm", pivot=(0, 9.0, 0), rotation=(fore_rest, 0, 0), boxes=[
        Box((-2.0, -0.4, -2.0), (4.0, 5.2, 4.0), skin),
        Box((-1.75, 4.6, -1.75), (3.5, 3.4, 3.5), skin),
        Box((-2.15, 4.6, -2.15), (4.3, 2.6, 4.3), cuff),
    ], children=[hand(label + "_hand", side, (0, 7.8, 0), curl=1.3, children=held)])
    return Part(label, pivot=(side * 7.4, -13.2, 0.2), rotation=rest, boxes=[
        Box((-2.2, -1.0, -2.2), (4.4, 10.2, 4.4), skin),
        Box((-1.6, 2.8, -2.7), (3.2, 4.6, 1.0), skin),
        Box((-2.5, 5.4, -2.5), (5.0, 1.5, 5.0), gold_band()),
        Box((-2.75, -1.6, -2.75), (5.5, 5.4, 5.5), royal(CRIMSON, border=LAPIS, border_w=1.5)),
    ], children=[forearm])


def leg(label, x):
    shin = Part(label + "_shin", pivot=(0, 11.5, 0), boxes=[
        Box((-1.9, 0, -1.9), (3.8, 11.1, 3.8), skin),
        Box((-1.8, 11.1, -4.4), (3.6, 1.4, 6.0), leather(LEATHER_DARK, stitch=False)),
        Box((-1.6, 10.3, -4.0), (3.2, 0.9, 5.0), skin),
        Box((-1.65, 8.7, -2.0), (3.3, 0.6, 4.0), gold_plain),
    ])
    return Part(label, pivot=(x, 0, 0), boxes=[Box((-2.3, -0.5, -2.3), (4.6, 12.1, 4.6), skin)], children=[shin])


# The long beard of the Assyrian kings: a jaw piece, then three tiers of strands in ringlets, each ending in a curl.
beard_parts = [Part("beard_jaw", boxes=[Box((-4.1, 0, -0.5), (8.2, 2.4, 3.4), assyrian_curls())])]
for tier in range(2):
    count = 8 - tier * 2
    for i in range(count):
        x = (i - (count - 1) / 2) * 1.02
        length = 3.6 - 0.4 * tier + 0.5 * math.cos(i * 1.7)
        beard_parts.append(Part("beard_%d_%d" % (tier, i), pivot=(x, 2.2 + tier * 3.6, -0.5 - tier * 0.5),
                                rotation=(0.12 + 0.06 * tier, 0, 0.04 * (i - (count - 1) / 2)),
                                boxes=[Box((-0.4, 0, -0.5), (0.8, length, 1.0), assyrian_curls()),
                                       Box((-0.5, length - 0.3, -0.7), (1.0, 1.0, 1.2), assyrian_curls(light=(170, 160, 186)))]))
beard = Part("beard", pivot=(0, -1.0, -3.6), children=beard_parts)

# Hair: a mass behind, ringlets at the sides, and a roll of curls resting on the shoulders.
hair = Part("hair", children=[
    Part("hair_back", boxes=[Box((-4.4, -7.6, 0.4), (8.8, 9.6, 4.2), assyrian_curls()),
                             Box((-4.5, -7.6, -1.6), (0.9, 8.2, 2.2), assyrian_curls()),
                             Box((3.6, -7.6, -1.6), (0.9, 8.2, 2.2), assyrian_curls())]),
] + [Part("hair_roll_%d" % i, pivot=(-3.8 + i * 1.27, 2.4, 3.0 + 0.2 * math.sin(i)), rotation=(0, 0, 0.1 * (i - 3)),
          boxes=[Box((-0.7, -0.7, -1.3), (1.4, 1.6, 2.6), assyrian_curls(light=(140, 120, 120)))]) for i in range(7)])

# The tiered crown: four stepped tiers of gold set with stones, battlements round each, a knob on top, fillets behind.
crown = Part("crown", pivot=(0, -7.8, 0), boxes=[
    Box((-4.6, -2.2, -4.6), (9.2, 2.2, 9.2), gold_band()),
    Box((-3.9, -4.8, -3.9), (7.8, 2.6, 7.8), gold_band()),
    Box((-3.1, -7.0, -3.1), (6.2, 2.2, 6.2), gold_band()),
    Box((-2.2, -8.8, -2.2), (4.4, 1.8, 4.4), gold_band(stones=False)),
    Box((-1.0, -10.0, -1.0), (2.0, 1.2, 2.0), gold_plain),
    Box((-0.6, -11.0, -0.6), (1.2, 1.0, 1.2), gold_plain),
], children=[
    # The spike on top, ending in a point: a carnelian diamond on its corner.
    Part("crown_point", pivot=(0, -11.6, 0), rotation=(0, 0.785, 0.785), boxes=[Box((-0.6, -0.6, -0.6), (1.2, 1.2, 1.2), solid((200, 60, 40)))]),
    crenels("crown_merlons_0", -2.2, 4.4, 16, 1.2, gold_plain),
    crenels("crown_merlons_1", -4.8, 3.7, 12, 1.0, gold_plain),
    crenels("crown_merlons_2", -7.0, 2.9, 8, 0.9, gold_plain),
    Part("ribbons", pivot=(0, -0.6, 4.6), rotation=(0.12, 0, 0), children=[
        Part("ribbon_%d" % k, pivot=(k * 1.3, 0, 0), rotation=(0, 0, k * 0.06),
             boxes=[Box((-0.6, 0, -0.15), (1.2, 15, 0.3), royal(CRIMSON, border=GOLD_DARK, border_w=1.0, diaper=False))]) for k in (-1, 1)
    ]),
])

head = Part("head", pivot=(0, -16.6, -0.4), boxes=[Box((-4, -8, -4), (8, 8, 8), nimrod_face)], children=[beard, hair, crown])

quiver = Part("quiver", pivot=(1.0, -7.0, 4.4), rotation=(0.12, 0, -0.38), boxes=[
    Box((-1.9, -8.0, -1.4), (3.8, 15.0, 3.2), leather()),
    Box((-2.1, -8.4, -1.6), (4.2, 1.2, 3.6), gold_plain),
    Box((-2.1, -1.0, -1.6), (4.2, 1.0, 3.6), gold_plain),
    Box((-2.1, 5.8, -1.6), (4.2, 1.2, 3.6), gold_plain),
], children=[
    Part("quiver_arrow_%d" % i, pivot=(-1.1 + (i % 3) * 1.1, -8.0, -0.6 + (i // 3) * 1.1), rotation=(0.08 * (i - 3), 0, 0.07 * (i - 3)), boxes=[
        Box((-0.18, -4.6, -0.18), (0.36, 5.0, 0.36), wood((150, 112, 62))),
        Box((-0.05, -4.4, -0.8), (0.1, 3.0, 1.6), fletching),
        Box((-0.8, -4.4, -0.05), (1.6, 3.0, 0.1), fletching),
    ]) for i in range(7)
])

# The mason's apron with his tools: a trowel, a hammer in the belt, a plumb line and a set square.
apron = Part("apron", pivot=(0, 0.6, -7.0), rotation=(-0.12, 0, 0), boxes=[
    Box((-4.2, 0, -0.4), (8.4, 11, 0.8), leather()),
    Box((-4.4, -0.4, -0.6), (8.8, 1.0, 1.2), leather(LEATHER_DARK, stitch=False)),
], children=[
    Part("trowel", pivot=(-3.0, 1.4, -0.7), rotation=(0, 0, 0.35), boxes=[
        Box((-0.3, -2.4, -0.3), (0.6, 2.6, 0.6), wood()),
        Box((-1.1, 0.2, -0.15), (2.2, 3.4, 0.3), metal(IRON, (70, 70, 76), (200, 200, 206))),
    ]),
    Part("hammer", pivot=(2.6, 0.2, -0.8), rotation=(0, 0, -0.25), boxes=[
        Box((-0.3, -1.0, -0.3), (0.6, 5.4, 0.6), wood()),
        Box((-1.3, -1.8, -0.6), (2.6, 1.0, 1.2), solid((120, 116, 108))),
    ]),
    Part("plumb_line", pivot=(-4.6, 0.6, -0.2), boxes=[
        Box((-0.1, 0, -0.1), (0.2, 5.6, 0.2), solid(BONE)),
        Box((-0.55, 5.4, -0.55), (1.1, 1.0, 1.1), metal(BRONZE, (90, 60, 30), (220, 170, 100))),
        Box((-0.3, 6.4, -0.3), (0.6, 0.7, 0.6), metal(BRONZE, (90, 60, 30), (220, 170, 100))),
    ]),
    Part("square", pivot=(1.0, 5.0, -0.5), rotation=(0, 0, 0.2), boxes=[
        Box((0, 0, -0.15), (3.6, 0.5, 0.3), wood((170, 130, 80))),
        Box((0, 0, -0.15), (0.5, 3.6, 0.3), wood((170, 130, 80))),
    ]),
])

torso = [
    Box((-5.6, -7.0, -3.6), (11.2, 7.4, 7.2), over),
    Box((-6.6, -14.0, -3.9), (13.2, 7.4, 7.8), royal(CRIMSON, border=LAPIS, fringe=False)),
    Box((-5.0, -15.2, -3.3), (10.0, 1.6, 6.6), royal(CRIMSON, fringe=False)),
    # The broad belt of the king.
    Box((-6.0, -1.8, -4.0), (12.0, 2.6, 8.0), gold_band()),
    # Neck and the gold collar.
    Box((-2.4, -17.6, -2.4), (4.8, 3.6, 4.8), skin),
    Box((-4.2, -15.6, -3.8), (8.4, 1.2, 7.6), gold_band()),
]

body = Part("body", boxes=torso, children=[
    Part("shawl", pivot=(0, -7.6, -4.1), rotation=(0, 0, -0.62), boxes=[
        Box((-1.9, -9.5, -0.35), (3.8, 19, 0.7), royal(LAPIS, border=GOLD_DARK, border_w=1.0, fringe=False)),
        Box((1.9, -9.5, -0.3), (0.7, 19, 0.6), royal(GOLD, border=GOLD_DARK, border_w=0, fringe=False, diaper=False)),
    ]),
    Part("shawl_back", pivot=(0, -7.6, 4.1), rotation=(0, 0, 0.62), boxes=[
        Box((-1.9, -9.5, -0.35), (3.8, 19, 0.7), royal(LAPIS, border=GOLD_DARK, border_w=1.0, fringe=False)),
    ]),
    Part("pectoral", pivot=(0, -11.8, -4.0), boxes=[Box((-1.6, -1.6, -0.4), (3.2, 3.2, 0.6), cuff)]),
    head,
    quiver,
    arm("right_arm", -1, (-0.12, 0, 0.08), -0.75, [brick]),
    arm("left_arm", 1, (0.0, 0, -0.28), -0.35, [bow]),
])

parts = [
    Part("figure", pivot=(0, 24, 0), children=[
        Part("hips", pivot=(0, -24, 0), children=[
            leg("right_leg", -2.6),
            leg("left_leg", 2.6),
            robe("robe_under", (0, -0.5, 0), 5.4, 7.6, 23.0, 18, under, front_short=1.6, back_long=1.4),
            robe("robe_over", (0, -1.0, 0), 5.8, 8.2, 19.0, 16, over, front_short=2.0, back_long=3.5, sway=0.04),
            robe("shawl_skirt", (0, -0.6, 0), 6.3, 7.1, 7.5, 14, royal(LAPIS, border=GOLD_DARK, border_w=1.2), sway=0.02),
            apron,
            body,
        ]),
    ]),
]

if __name__ == "__main__":
    build("Nimrod", "nimrod", parts, size=256, seed=33, density=4)
