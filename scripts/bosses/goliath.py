"""Голиаф (1 Samuel 17): the giant of Gath. A massive Philistine with a broad chest, a bull neck and great bare arms; a bronze
helmet with a horsehair crest and cheek guards that leaves his forehead bare (the place where David's stone went in), a
black curled beard, a coat of bronze scale mail in layered rows, a bronze gorget, leather pteruges over a red kilt, bronze
greaves, a huge round shield with a relief of bands and rosettes, a spear whose staff is like a weaver's beam with a heavy
iron head, and a bronze javelin strapped across his back.
Built about 58 units tall and drawn at scale 1.75 (about six and a half blocks to the crest), 4 pixels per unit."""
from boxtex import *

SKIN = (178, 118, 78)
SKIN_LIGHT = (214, 160, 116)
SKIN_DARK = (120, 72, 46)
BEARD = (40, 26, 18)
BRONZE = (170, 116, 54)
BRONZE_DARK = (92, 56, 24)
BRONZE_LIGHT = (236, 188, 108)
IRON = (118, 118, 124)
IRON_DARK = (62, 62, 68)
IRON_LIGHT = (196, 196, 204)
WOOD = (82, 56, 34)
LEATHER = (98, 62, 36)
LEATHER_DARK = (58, 36, 22)
KILT = (138, 30, 26)
CREST = (168, 34, 28)


# ---- Styles. ----

def flesh(base=SKIN, armband=None):
    """Sunburnt skin with muscle modelling: soft masses with lit crests and dark grooves between them."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                x = u / max(1, f.w - 1)
                y = v / max(1, f.h - 1)
                mass = math.sin(x * math.pi * 1.6 + 0.4) * 0.5 + math.sin(y * math.pi * 1.2) * 0.35
                c = mix(shade(base, 0.82), SKIN_LIGHT, max(0.0, mass) * 0.55)
                if abs(math.sin(x * math.pi * 1.6 + 0.4)) < 0.12 and 0.15 < y < 0.85:
                    c = mix(c, SKIN_DARK, 0.35)
                p.put(f, u, v, shade(c, p.noise(0.035)))
        if armband and f.side in ("front", "back", "left", "right"):
            top, height = armband
            for v in range(int(top * d), int((top + height) * d)):
                for u in range(f.w):
                    edge = v in (int(top * d), int((top + height) * d) - 1)
                    c = BRONZE_DARK if edge else mix(BRONZE_LIGHT, BRONZE, (u % (2 * d)) / (2 * d))
                    p.put(f, u, v, shade(c, p.noise(0.05)))
    return paint


def bronze(patina=0.08, hammered=True, rim=True):
    """Hammered bronze: a warm gradient, small dents catching light, green patina in the hollows and a dark rim."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                t = (v + 0.4 * u) / max(1, f.h + 0.4 * f.w)
                c = mix(BRONZE_LIGHT, BRONZE_DARK, t * 0.85)
                if hammered and p.rand.random() < 0.05:
                    c = shade(c, 1.18)
                if p.rand.random() < patina * t:
                    c = mix(c, PATINA, 0.55)
                if rim and (u < d // 2 + 1 or v < d // 2 + 1 or u >= f.w - d // 2 - 1 or v >= f.h - d // 2 - 1):
                    c = shade(c, 0.62)
                p.put(f, u, v, shade(c, p.noise(0.05)))
    return paint


def iron_blade(p, f, opts):
    """Dark forged iron with a bright ground edge and a midrib."""
    for v in range(f.h):
        for u in range(f.w):
            across = abs(u + 0.5 - f.w / 2) / max(1, f.w / 2)
            c = mix(IRON_DARK, IRON_LIGHT, across ** 2)
            if across < 0.15:
                c = IRON_LIGHT
            p.put(f, u, v, shade(c, p.noise(0.06)))


def wood_grain(base=WOOD, rings=None):
    """Wood (or leather) with a running grain; `rings` binds it every so many units with leather and bronze."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                k = 0.85 + 0.15 * math.sin(u * 2.3 + math.sin(v * 0.09) * 3)
                c = shade(base, k * p.noise(0.05))
                if rings and f.side in ("front", "back", "left", "right") and (v // d) % rings < 2:
                    c = shade(LEATHER_DARK, p.noise(0.08)) if (v // d) % rings == 0 else BRONZE
                p.put(f, u, v, c)
    return paint


def beard_strands(p, f, opts):
    """A black beard in tight ringlets: rows of curls with light on each crest."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            w = math.sin(u / max(1, d) * 2.4 + (v // (2 * d)) * 1.7) * math.cos(v / max(1, d) * 1.6)
            c = mix(shade(BEARD, 0.6), (110, 84, 64), max(0.0, w) ** 3)
            p.put(f, u, v, shade(c, p.noise(0.06)))


def crest_hair(p, f, opts):
    """Red horsehair: long strands with dark partings and a ragged tip."""
    for v in range(f.h):
        for u in range(f.w):
            t = v / max(1, f.h - 1)
            if t > 0.88 and p.rand.random() < 0.5:
                p.put(f, u, v, None)
                continue
            k = 0.75 + 0.35 * abs(math.sin(u * 1.7 + v * 0.05))
            c = shade(CREST, k * (1.05 - 0.3 * t))
            p.put(f, u, v, shade(c, p.noise(0.05)))


def mail(p, f, opts):
    """His coat of mail: small bronze scales with rounded lower edges in offset rows, each lit from the upper left and
    outlined dark, with a dark rim along the bottom of every row of plates."""
    d = opts.get("density", 1)
    sw = sh = max(3, int(1.3 * d))
    for v in range(f.h):
        for u in range(f.w):
            row = v // sh
            uu = (u + (sw // 2 if row % 2 else 0)) % sw
            x = (uu + 0.5) / sw * 2 - 1
            y = (v % sh + 0.5) / sh
            edge = 1 - 0.55 * x * x
            if y > edge or abs(x) > 0.92:
                c = shade(BRONZE_DARK, 0.7)
            else:
                c = mix(BRONZE_LIGHT, BRONZE, min(1.0, y * 0.9 + 0.3 * (x + 1) / 2))
                if y > edge - 0.18:
                    c = shade(c, 0.78)
                if p.rand.random() < 0.03:
                    c = mix(c, PATINA, 0.5)
            p.put(f, u, v, shade(c, p.noise(0.04)))
    if f.side in ("front", "back", "left", "right"):
        for u in range(f.w):
            for v in range(max(0, f.h - d // 2), f.h):
                p.put(f, u, v, shade(BRONZE_DARK, 0.75))


def pteryx(p, f, opts):
    """A leather strip of the war kilt with two bronze studs and a red fringe at the end."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            c = shade(LEATHER, p.noise(0.07) * (0.9 + 0.1 * math.sin(v * 0.5)))
            if u < d // 2 + 1 or u >= f.w - d // 2 - 1:
                c = shade(LEATHER_DARK, 1.0)
            if v >= f.h - 2 * d:
                if (u // max(1, d // 2)) % 2 == 0:
                    c = shade(KILT, 1.1)
                elif v > f.h - d:
                    p.put(f, u, v, None)
                    continue
            p.put(f, u, v, c)
    if f.side in ("front", "back") and f.h > 6 * d:
        cx = f.w // 2
        for sy in (int(f.h * 0.25), int(f.h * 0.55)):
            for du in range(-d // 2, d // 2 + 1):
                for dv in range(-d // 2, d // 2 + 1):
                    if du * du + dv * dv <= (d // 2) ** 2 + 1:
                        p.put(f, cx + du, sy + dv, BRONZE_LIGHT if du + dv < 0 else BRONZE)


def goliath_face(p, f, opts):
    """His face: broad and sunburnt. The forehead stays bare and pale with a frown line and the faint red mark of the target;
    heavy lowered brows over deep-set eyes; a broad nose, a drooping moustache and the beard climbing the cheeks."""
    if f.side != "front":
        col = BEARD if f.side in ("back", "top") else SKIN
        for v in range(f.h):
            for u in range(f.w):
                c = col
                if f.side in ("left", "right") and v > f.h * 0.55:
                    c = BEARD
                p.put(f, u, v, shade(c, p.noise(0.05)))
        return
    W, H = f.w, f.h
    for v in range(H):
        for u in range(W):
            dx = (u + 0.5 - W / 2) / (W / 2)
            dy = (v - H * 0.5) / (H / 2)
            c = shade(SKIN, 1.06 - 0.32 * dx * dx - 0.08 * max(0.0, dy))
            if 0.3 < abs(dx) < 0.62 and 0.5 * H < v < 0.6 * H:
                c = mix(c, SKIN_LIGHT, 0.35)
            p.put(f, u, v, shade(c, p.noise(0.03)))
    cx = W / 2
    # The bare forehead: paler, a frown line, and the faint red mark where the stone will go.
    for v in range(0, int(H * 0.3)):
        for u in range(W):
            dx = (u + 0.5 - cx) / (W / 2)
            p.put(f, u, v, shade(mix(SKIN_LIGHT, SKIN, min(1.0, abs(dx) * 0.7 + v / H)), p.noise(0.03)))
    for u in range(int(cx - W * 0.25), int(cx + W * 0.25)):
        p.put(f, u, int(H * 0.08), shade(SKIN, 0.82))
    for a in range(32):
        ang = a / 32 * 2 * math.pi
        p.put(f, int(cx + math.cos(ang) * 2.6), int(H * 0.17 + math.sin(ang) * 2.0), mix(SKIN_LIGHT, (178, 60, 44), 0.45))
    for u in range(int(cx - 1), int(cx + 1)):
        for v in range(int(H * 0.15), int(H * 0.2)):
            p.put(f, u, v, (172, 70, 52))
    ey = int(H * 0.42)
    for side in (-1, 1):
        ex = cx + side * W * 0.22
        # Heavy brows, lowered toward the nose (a scowl).
        # Deep sockets under a jutting brow ridge.
        for u in range(int(ex - W * 0.16), int(ex + W * 0.16) + 1):
            for v in range(ey - 3, ey + 3):
                p.put(f, u, v, shade(SKIN, 0.5 + 0.06 * abs(v - ey)))
        for u in range(int(ex - W * 0.17), int(ex + W * 0.17) + 1):
            t = (u - ex) * side
            by = int(H * 0.34 - t * 0.3)
            for k in range(3):
                p.put(f, u, by + k, shade(BEARD, 0.9 + 0.25 * k))
        for u in range(int(ex - W * 0.09), int(ex + W * 0.09) + 1):
            p.put(f, u, ey, (226, 214, 190))
            p.put(f, u, ey + 1, (196, 182, 160))
        for u in range(int(ex - 1), int(ex + 1)):
            p.put(f, u, ey, (36, 24, 18))
            p.put(f, u, ey + 1, (60, 36, 24))
        p.put(f, int(ex - 1), ey - 1, (30, 20, 14))
        p.put(f, int(ex), ey - 1, (30, 20, 14))
    # Frown furrows between the brows, and an old scar across the right cheek.
    for v in range(int(H * 0.27), int(H * 0.36)):
        p.put(f, int(cx - 2), v, shade(SKIN, 0.68))
        p.put(f, int(cx + 1), v, shade(SKIN, 0.68))
    for k in range(7):
        p.put(f, int(cx - W * 0.38 + k), int(H * 0.5 + k * 0.9), (150, 86, 66))
    # Shadow under the cheekbones.
    for side in (-1, 1):
        for v in range(int(H * 0.6), int(H * 0.68)):
            p.put(f, int(cx + side * W * 0.33), v, shade(SKIN, 0.7))
    # A broad nose with a lit ridge, a shadowed side and flared nostrils.
    for v in range(int(H * 0.42), int(H * 0.64)):
        p.put(f, int(cx + 1), v, shade(SKIN, 0.66))
    for v in range(int(H * 0.38), int(H * 0.64)):
        p.put(f, int(cx - 1), v, shade(SKIN, 0.78))
        p.put(f, int(cx), v, SKIN_LIGHT)
    for u in range(int(cx - 3), int(cx + 3)):
        p.put(f, u, int(H * 0.64), shade(SKIN, 0.55 if u in (int(cx - 3), int(cx + 2)) else 0.9))
    # The moustache, the mouth set hard, and the beard rising up the cheeks.
    my = int(H * 0.74)
    for u in range(int(cx - W * 0.28), int(cx + W * 0.28)):
        droop = int(abs(u + 0.5 - cx) / (W * 0.28) * 2)
        p.put(f, u, my - 2 + droop, BEARD)
        p.put(f, u, my - 1 + droop, shade(BEARD, 1.3))
    for u in range(int(cx - 2), int(cx + 2)):
        p.put(f, u, my + 1, (96, 40, 32))
    for v in range(int(H * 0.62), H):
        for u in range(W):
            dx = abs(u + 0.5 - cx) / (W / 2)
            if dx > 0.78 - (v - H * 0.62) / H * 0.4 or v > H * 0.84:
                if not (abs(u + 0.5 - cx) < 2.5 and my <= v <= my + 2):
                    p.put(f, u, v, shade(BEARD, p.noise(0.1) * (1.25 if (u + v) % 3 == 0 else 1.0)))


def shield_face(p, f, opts):
    """The round shield in relief: a dark rim, a band of running spirals, a groove, a field of eight rosettes between bronze
    rays, and an inner ring round the boss. Outside the circle (and on the thin edges) it is transparent."""
    if f.side not in ("front", "back"):
        for v in range(f.h):
            for u in range(f.w):
                p.put(f, u, v, None)
        return
    W, H = f.w, f.h
    R = min(W, H) / 2
    for v in range(H):
        for u in range(W):
            dx, dy = u + 0.5 - W / 2, v + 0.5 - H / 2
            r = math.hypot(dx, dy) / R
            if r > 1:
                p.put(f, u, v, None)
                continue
            if f.side == "back":
                c = shade(LEATHER, (0.8 + 0.2 * math.sin(dx * 0.6)) * p.noise(0.05))
                if r > 0.94:
                    c = BRONZE_DARK
                p.put(f, u, v, c)
                continue
            a = math.atan2(dy, dx)
            light = 0.5 - 0.5 * (dx + dy) / max(1e-6, math.hypot(dx, dy))
            c = mix(BRONZE, BRONZE_LIGHT, 0.3)
            if r > 0.94:
                c = shade(BRONZE_DARK, 0.9)
            elif r > 0.78:
                spiral = math.sin(a * 22 + (r - 0.86) * 60)
                c = mix(BRONZE_DARK, BRONZE_LIGHT, 0.5 + 0.5 * spiral)
            elif r > 0.73:
                c = shade(BRONZE_DARK, 0.75 + 0.35 * light)
            elif r > 0.4:
                ray = math.cos(a * 16)
                c = mix(BRONZE, BRONZE_LIGHT, 0.25 + 0.3 * ray * (r - 0.4) / 0.33)
                if p.rand.random() < 0.06:
                    c = mix(c, PATINA, 0.5)
                for k in range(8):
                    ra = k * math.pi / 4 + math.pi / 8
                    rx, ry = math.cos(ra) * 0.57 * R, math.sin(ra) * 0.57 * R
                    dd = math.hypot(dx - rx, dy - ry) / R
                    if dd < 0.09:
                        petal = math.cos(math.atan2(dy - ry, dx - rx) * 6)
                        c = mix(GOLD_LIGHT, BRONZE_DARK, 0.2 + 0.5 * (dd / 0.09) - 0.2 * petal)
                    elif dd < 0.11:
                        c = BRONZE_DARK
            elif r > 0.36:
                c = shade(BRONZE_DARK, 0.75 + 0.35 * light)
            else:
                c = mix(BRONZE_LIGHT, BRONZE, r / 0.36)
                if 0.24 < r < 0.32 and int((a + math.pi) / (2 * math.pi) * 16) % 2 == 0:
                    c = GOLD_LIGHT
            p.put(f, u, v, shade(c, p.noise(0.04)))


def sandal(p, f, opts):
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            c = shade(SKIN, p.noise(0.05))
            if f.side == "bottom" or (f.side != "top" and v >= f.h - d):
                c = LEATHER_DARK
            elif (u + v) // d % 4 == 0:
                c = LEATHER
            p.put(f, u, v, c)


# ---- Builders. ----

def hand(label, side, pivot, curl=1.3, children=()):
    """A big hand: the palm (facing in), four two-jointed fingers along Z curled toward the palm and a thumb in front.
    `side` is -1 for his right hand (at -X), +1 for the left."""
    skin = flesh(SKIN)
    part = Part(label, pivot=pivot, boxes=[Box((-1.0, 0, -2.1), (2.0, 3.6, 4.2), skin)])
    for i, z in enumerate((-1.55, -0.5, 0.55, 1.6)):
        length = 1.8 if i in (1, 2) else 1.5
        tipp = Part("%s_tip%d" % (label, i), pivot=(0, length, 0), rotation=(0, 0, side * curl * 0.9),
                    boxes=[Box((-0.45, -0.1, -0.45), (0.9, 1.4, 0.9), skin)])
        part.children.append(Part("%s_finger%d" % (label, i), pivot=(-side * 0.3, 3.4, z), rotation=(0, 0, side * curl),
                                  boxes=[Box((-0.5, 0, -0.5), (1.0, length, 1.0), skin)], children=[tipp]))
    part.children.append(Part(label + "_thumb", pivot=(-side * 0.6, 1.0, -2.0), rotation=(-0.5, 0, side * 0.6),
                              boxes=[Box((-0.55, 0, -0.55), (1.1, 2.6, 1.1), skin)]))
    part.children.extend(children)
    return part


def ring(name, pivot, rotation, radius, count, size, style):
    """A ring of small boxes standing on the XY plane (a raised rim): each lies tangent to the circle."""
    part = Part(name, pivot=pivot, rotation=rotation)
    seg = 2 * math.pi * radius / count * 1.08
    for i in range(count):
        a = 2 * math.pi * i / count
        part.children.append(Part("%s_%d" % (name, i), pivot=(math.cos(a) * radius, math.sin(a) * radius, 0), rotation=(0, 0, a + math.pi / 2),
                                  boxes=[Box((-seg / 2, -size[0] / 2, -size[1] / 2), (seg, size[0], size[1]), style)]))
    return part


# ---- The model. ----

skin = flesh(SKIN)
plate = bronze()

# The spear like a weaver's beam: a thick staff bound with leather and bronze, a long iron head on a bronze socket, a bronze butt.
spear = Part("spear", pivot=(1.4, 4.4, 0), rotation=(1.15, 0, 0), boxes=[
    Box((-1, -40, -1), (2, 62, 2), wood_grain(rings=9)),
    Box((-1.3, -42.5, -1.3), (2.6, 3.4, 2.6), plate),
    Box((-1.1, 21.5, -1.1), (2.2, 2.6, 2.2), plate),
    Box((-0.6, 24, -0.6), (1.2, 1.6, 1.2), bronze(rim=False)),
], children=[
    Part("spear_head", pivot=(0, -42.5, 0), boxes=[
        Box((-2.0, -3, -0.4), (4.0, 3, 0.8), iron_blade),
        Box((-2.8, -9, -0.4), (5.6, 6, 0.8), iron_blade),
        Box((-2.0, -12, -0.35), (4.0, 3, 0.7), iron_blade),
        Box((-1.1, -14, -0.3), (2.2, 2, 0.6), iron_blade),
        Box((-0.55, -13.5, -0.7), (1.1, 13.5, 1.4), metal(IRON, IRON_DARK, IRON_LIGHT)),
    ], children=[
        # The point: a diamond turned on its corner, so the blade ends sharp.
        Part("spear_point", pivot=(0, -14, 0), rotation=(0, 0, 0.785), boxes=[Box((-0.8, -0.8, -0.3), (1.6, 1.6, 0.6), iron_blade)]),
    ]),
])

shield = Part("shield", pivot=(-1.2, 4.0, -1.6), rotation=(1.35, -0.7, 0.4), children=[
    Part("shield_disc", boxes=[Box((-11, -11, -0.6), (22, 22, 1.2), shield_face, flat=True)]),
    ring("shield_rim", (0, 0, -0.5), (0, 0, 0), 10.6, 22, (1.4, 1.8), bronze(patina=0.15)),
    ring("shield_studs", (0, 0, -0.7), (0, 0, 0), 4.6, 10, (0.9, 1.0), bronze(rim=False)),
    Part("shield_boss", pivot=(0, 0, -0.6), boxes=[
        Box((-2.6, -2.6, -1.0), (5.2, 5.2, 1.0), bronze()),
        Box((-1.8, -1.8, -2.0), (3.6, 3.6, 1.0), bronze()),
        Box((-0.9, -0.9, -2.8), (1.8, 1.8, 0.8), gold_leaf(ornament=False)),
    ]),
])


def arm(label, side, rest, fore_rest, held):
    """A huge bare arm: a layered pauldron of mail, the deltoid, biceps and triceps, a bronze armlet, the forearm in a
    leather bracer, the hand. `side` -1 is his right."""
    pauldrons = [Part("%s_pauldron_%d" % (label, k), pivot=(side * 0.4, -2.4 + k * 1.9, 0), rotation=(0, 0, side * (0.3 + 0.16 * k)),
                      boxes=[Box((-3.2 + side * 0.6, 0, -3.4), (6.4, 2.4, 6.8), mail)]) for k in range(3)]
    forearm = Part(label + "_forearm", pivot=(0, 10.6, 0), rotation=(fore_rest, 0, 0), boxes=[
        Box((-2.4, -0.6, -2.4), (4.8, 4.6, 4.8), skin),
        Box((-2.25, 3.6, -2.25), (4.5, 6.2, 4.5), wood_grain(LEATHER, rings=3)),
        Box((-1.6, -0.2, -2.9), (3.2, 4.2, 1.0), skin),
    ], children=[hand(label + "_hand", side, (0, 9.4, 0), curl=1.35, children=held)])
    return Part(label, pivot=(side * 9.4, -15.0, 0.2), rotation=rest, boxes=[
        Box((-2.9, -1.4, -2.9), (5.8, 6.2, 5.8), skin),
        Box((-2.6, 4.6, -2.6), (5.2, 6.4, 5.2), flesh(SKIN, armband=(0.4, 1.4))),
        Box((-1.9, 3.4, -3.4), (3.8, 5.6, 1.4), skin),
        Box((-1.8, 3.0, 2.2), (3.6, 6.0, 1.3), skin),
    ], children=pauldrons + [forearm])


def leg(label, x):
    """A pillar of a leg: the thigh under the kilt, the knee, a greaved shin and a sandalled foot with toes."""
    shin = Part(label + "_shin", pivot=(0, 12, 0), boxes=[
        Box((-2.6, -0.2, -2.2), (5.2, 10.8, 5.0), skin),
        Box((-2.9, 0.6, -3.0), (5.8, 5.6, 4.4), plate),
        Box((-2.5, 6.0, -2.7), (5.0, 4.6, 4.0), plate),
        Box((-2.2, -1.6, -3.4), (4.4, 2.6, 1.4), bronze(rim=False)),
        Box((-2.4, 10.4, -4.8), (4.8, 2.6, 7.0), sandal),
    ], children=[
        Part(label + "_toes", pivot=(0, 11.8, -4.8), boxes=[Box((-2.2 + i * 1.12, 0, -1.2), (1.0, 1.2, 1.4), skin) for i in range(4)]),
    ])
    return Part(label, pivot=(x, 0, 0), boxes=[
        Box((-3.0, -1.0, -3.1), (6.0, 13.6, 6.2), skin),
        Box((-2.4, 1.0, -3.6), (4.8, 8.4, 1.0), skin),
    ], children=[shin])


torso_rows = []
profile = [(12.5, 8.4, 0.0), (13.0, 8.6, -0.1), (13.8, 9.0, -0.2), (15.0, 9.6, -0.4), (16.4, 10.2, -0.6), (17.2, 10.6, -0.7),
           (17.0, 10.2, -0.6), (15.2, 9.2, -0.3)]
for i, (w, dd, z) in enumerate(profile):
    y = 1.0 - (i + 1) * 2.25
    torso_rows.append(Box((-w / 2, y, -dd / 2 + z), (w, 2.7, dd), mail))

crest_strands = []
for i in range(13):
    t = i / 12
    h = 9.0 - 4.0 * abs(t - 0.4)
    crest_strands.append(Part("crest_%d" % i, pivot=(0, -12.6, -4.0 + t * 8.2), rotation=(0.95 - t * 2.1, 0, 0),
                              boxes=[Box((-0.9, -h, -0.8), (1.8, h, 1.6), crest_hair)]))
crest_strands.append(Part("crest_tail", pivot=(0, -12, 4.4), rotation=(0.35, 0, 0), boxes=[Box((-1.0, 0, -0.4), (2.0, 11, 0.8), crest_hair)]))

beard = Part("beard", pivot=(0, -1.6, -3.6), boxes=[Box((-3.6, -0.6, -0.4), (7.2, 2.6, 3.2), beard_strands)], children=[
    Part("beard_%d" % i, pivot=(-3.0 + i * 1.0, 0, 0.2 * abs(i - 3)), rotation=(0.18, 0, 0.05 * (3 - i)),
         boxes=[Box((-0.6, 0, -0.7), (1.2, 4.6 + 1.6 * math.cos((i - 3) / 3 * 1.2), 1.4), beard_strands)]) for i in range(7)
])

head = Part("head", pivot=(0, -20.4, -0.6), boxes=[
    Box((-4, -8, -4), (8, 8, 8), goliath_face),
    # A jutting brow ridge and a broad nose in relief, so the face is not painted flat on a box.
    Box((-3.4, -5.7, -4.5), (6.8, 0.8, 0.6), solid(BEARD, k=0.12)),
], children=[
    Part("nose", pivot=(0, -4.7, -4.0), rotation=(-0.18, 0, 0), boxes=[Box((-0.65, 0, -0.9), (1.3, 2.0, 0.9), flesh(SKIN)),
                                                                        Box((-0.95, 1.5, -0.7), (1.9, 0.7, 0.7), flesh(SKIN))]),
    beard,
    Part("helmet", boxes=[
        Box((-4.5, -10.6, -4.5), (9, 2.8, 9.2), plate),
        Box((-3.8, -11.8, -3.8), (7.6, 1.3, 7.8), plate),
        Box((-2.6, -12.4, -2.8), (5.2, 0.7, 5.6), plate),
        # The brim sits above the brow: the forehead stays bare.
        Box((-4.7, -8.4, -4.7), (9.4, 0.9, 9.6), gold_leaf(ornament=False)),
        Box((-0.7, -13.0, -4.4), (1.4, 1.1, 9.0), bronze(rim=False)),
    ], children=[
        Part("neck_guard", pivot=(0, -8, 4.5), rotation=(-0.35, 0, 0), boxes=[Box((-4.4, 0, -0.5), (8.8, 5.4, 0.9), plate)]),
        Part("cheek_r", pivot=(-4.3, -8.2, -3.8), rotation=(0, -0.22, -0.06), boxes=[Box((-0.5, 0, -0.6), (1.0, 7.0, 5.0), plate),
                                                                                    Box((-0.4, 4.6, -0.8), (0.9, 2.2, 1.4), gold_leaf(ornament=False))]),
        Part("cheek_l", pivot=(4.3, -8.2, -3.8), rotation=(0, 0.22, 0.06), boxes=[Box((-0.5, 0, -0.6), (1.0, 7.0, 5.0), plate),
                                                                                 Box((-0.5, 4.6, -0.8), (0.9, 2.2, 1.4), gold_leaf(ornament=False))]),
        Part("crest", children=crest_strands),
    ]),
])

javelin = Part("javelin", pivot=(0, -8.5, 5.6), rotation=(0, 0, 0.62), boxes=[
    Box((-0.55, -16, -0.55), (1.1, 31, 1.1), bronze(rim=False)),
    Box((-1.1, -21, -0.3), (2.2, 5, 0.6), iron_blade),
    Box((-0.6, -22.4, -0.25), (1.2, 1.4, 0.5), iron_blade),
    Box((-0.8, 14, -0.8), (1.6, 2, 1.6), plate),
], children=[Part("javelin_point", pivot=(0, -22.4, 0), rotation=(0, 0, 0.785), boxes=[Box((-0.5, -0.5, -0.2), (1.0, 1.0, 0.4), iron_blade)])])

body = Part("body", boxes=torso_rows + [
    # The belt with a bronze plaque.
    Box((-6.9, -1.8, -4.8), (13.8, 3.4, 9.6), wood_grain(LEATHER)),
    Box((-2.2, -2.2, -5.3), (4.4, 4.2, 0.8), gold_leaf()),
    # The gorget over the collarbones, and the bull neck.
    Box((-6.2, -18.0, -5.2), (12.4, 2.0, 10.0), bronze(patina=0.12)),
    Box((-3.2, -21.4, -3.2), (6.4, 4.4, 6.4), skin),
], children=[
    Part("pec_r", pivot=(-4.2, -12.6, -5.3), rotation=(0.08, 0.22, 0), boxes=[Box((-4.0, -3.0, -0.8), (8.0, 6.0, 1.6), mail)]),
    Part("pec_l", pivot=(4.2, -12.6, -5.3), rotation=(0.08, -0.22, 0), boxes=[Box((-4.0, -3.0, -0.8), (8.0, 6.0, 1.6), mail)]),
    Part("trap_r", pivot=(-3.4, -17.4, 0.6), rotation=(0, 0, -0.45), boxes=[Box((-4.0, -1.4, -2.6), (5.0, 2.6, 5.2), skin)]),
    Part("trap_l", pivot=(3.4, -17.4, 0.6), rotation=(0, 0, 0.45), boxes=[Box((-1.0, -1.4, -2.6), (5.0, 2.6, 5.2), skin)]),
    Part("neck_tendon_r", pivot=(-1.6, -17.6, -2.6), rotation=(0.2, 0, -0.25), boxes=[Box((-0.6, -3.6, -0.6), (1.2, 3.8, 1.2), skin)]),
    Part("neck_tendon_l", pivot=(1.6, -17.6, -2.6), rotation=(0.2, 0, 0.25), boxes=[Box((-0.6, -3.6, -0.6), (1.2, 3.8, 1.2), skin)]),
    Part("baldric", pivot=(0, -8.5, -5.6), rotation=(0, 0, 0.64), boxes=[Box((-0.9, -11.5, -0.3), (1.8, 23, 0.6), pteryx)]),
    head,
    javelin,
    arm("right_arm", -1, (-0.22, 0, 0.12), -0.85, [spear]),
    arm("left_arm", 1, (-0.05, 0, -0.6), -1.25, [shield]),
])

parts = [
    Part("figure", pivot=(0, 24, 0), children=[
        Part("hips", pivot=(0, -25, 0), children=[
            leg("right_leg", -3.7),
            leg("left_leg", 3.7),
            skirt("kilt", pivot=(0, 0.6, 0), top_radius=6.0, bottom_radius=7.6, height=10.5, panels=16,
                  style=drapery(KILT, highlight=(214, 84, 64), hem=GOLD_DARK, hem_width=1)),
            skirt("pteruges", pivot=(0, 0.2, 0), top_radius=6.6, bottom_radius=8.0, height=9.0, panels=14, style=pteryx, sway=0.05),
            body,
        ]),
    ]),
]

if __name__ == "__main__":
    build("Goliath", "goliath", parts, size=256, seed=17, density=4)
