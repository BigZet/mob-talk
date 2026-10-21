"""ÐŸÐ»Ð°ÐºÐ°Ð»ÑŒÑ‰Ð¸Ñ†Ð° Ð¿Ð¾Ð³Ð¾ÑÑ‚Ð°: a tall, terrible mourner gliding over the graves. A black funeral shroud and a long veil fall to the
ground in layers of torn strips with ragged see-through edges; under a mourning ÐºÐ¾ÐºÐ¾ÑˆÐ½Ð¸Ðº and a half-veil a pale face with
hollow cheeks, an open wailing mouth and glowing tear-streaked eyes; very long thin arms with bony fingers, one holding a
burning funeral candle up before her face. No feet: she floats.
Built about 44 units tall and drawn at scale 2 (about five and a half blocks), at 4 pixels per unit."""
from boxtex import *
from schemnik import flame, finger, bone, ragged, pointed

SHROUD = (22, 20, 26)
SHROUD_HL = (70, 66, 82)
GAUZE = (34, 32, 40)
ASH = (204, 204, 198)
ASH_SHADOW = (120, 124, 134)
GHOST = (206, 236, 246)
TEAR = (150, 206, 232)
SILVER = (156, 156, 166)
SILVER_DARK = (88, 88, 98)
VELVET = (16, 14, 18)
WAX = (232, 220, 186)


def tatter(base, highlight, end=False, holes=0.03, lace=False):
    """Torn funeral cloth: vertical threads and soft folds, frayed see-through sides, moth holes (more toward the bottom) and,
    on the last piece of a strip, a torn forked end. `lace` makes it gauze: a fine net of holes all over."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        W, H = f.w, f.h
        side = f.side in ("front", "back", "left", "right")
        for v in range(H):
            t = v / max(1, H - 1)
            for u in range(W):
                k = 0.8 + 0.25 * math.sin(u * 1.1 / d + v * 0.05) + (0.12 if u % 3 == 0 else 0.0)
                c = shade(base, k * p.noise(0.06))
                if (u * 7 + v // d) % 23 == 0:
                    c = mix(c, highlight, 0.5)
                if side and f.side in ("front", "back"):
                    if lace and (u % 3 == 1 and v % 3 == 1) and p.rand.random() < 0.85:
                        p.put(f, u, v, None)
                        continue
                    if p.rand.random() < holes * (0.4 + 1.6 * t):
                        p.put(f, u, v, None)
                        continue
                    edge = min(u, W - 1 - u)
                    if edge < d and p.rand.random() < 0.45 - 0.2 * edge / d:
                        p.put(f, u, v, None)
                        continue
                    if edge < d:
                        c = mix(c, highlight, 0.35)
                p.put(f, u, v, c)
        if end and f.side in ("front", "back", "left", "right"):
            # A torn end: two or three ragged tongues.
            tongues = 2 + (f.x // 7) % 2
            for u in range(W):
                ph = (u + 0.5) / W * tongues
                depth = 0.55 * (1 - abs(math.sin(ph * math.pi))) + 0.12 * p.rand.random()
                for v in range(int(H * (1 - depth)), H):
                    p.put(f, u, v, None)
                cut = int(H * (1 - depth)) - 1
                if 0 <= cut < H:
                    p.put(f, u, cut, mix(base, highlight, 0.6))
    return paint


def strips(name, pivot, top, bottom, length, count, base, highlight, arc=(0.0, 2 * math.pi), segs=3, seed=1,
           offset=0.0, width_k=1.0, thickness=0.3, holes=0.03, lace=False):
    """Long torn strips hanging round an ellipse, each a chain of `segs` hinged pieces so it can ripple; every strip ends in a
    torn tongue. Widths and lengths vary, so the hem never reads as one bell."""
    rnd = random.Random(seed)
    part = Part(name, pivot=pivot)
    a0, a1 = arc
    full = abs((a1 - a0) - 2 * math.pi) < 1e-6
    rxb, rzb = bottom
    perimeter = math.pi * (3 * (rxb + rzb) - math.sqrt((3 * rxb + rzb) * (rxb + 3 * rzb)))
    width = perimeter * (a1 - a0) / (2 * math.pi) / count * width_k
    body_style = tatter(base, highlight, holes=holes, lace=lace)
    end_style = tatter(base, highlight, end=True, holes=holes * 1.5, lace=lace)
    for i in range(count):
        t = (i + offset) / count if full else (i + offset) / max(1, count - 1)
        a = a0 + (a1 - a0) * t
        xt, zt = math.sin(a) * top[0], -math.cos(a) * top[1]
        xb, zb = math.sin(a) * bottom[0], -math.cos(a) * bottom[1]
        tilt = math.atan2(math.hypot(xb, zb) - math.hypot(xt, zt), length)
        L = length * rnd.uniform(0.86, 1.06)
        seg = L / segs
        w = width * rnd.uniform(0.8, 1.15)
        parent = None
        for k in range(segs):
            wk = w * (0.94 ** k)
            box = Box((-wk / 2, 0, -thickness / 2), (wk, seg + 0.3, thickness), end_style if k == segs - 1 else body_style)
            if k == 0:
                piece = Part("%s_%d" % (name, i), pivot=(xt, 0, zt),
                             rotation=(-tilt + rnd.uniform(-0.04, 0.04), -a + rnd.uniform(-0.08, 0.08), rnd.uniform(-0.05, 0.05)), boxes=[box])
                part.children.append(piece)
            else:
                piece = Part("%s_%d_%d" % (name, i, k), pivot=(0, seg, 0), rotation=(rnd.uniform(-0.05, 0.03), 0, rnd.uniform(-0.03, 0.03)), boxes=[box])
                parent.children.append(piece)
            parent = piece
    return part


def wailer_face(p, f, opts):
    """Ashen skin over the skull: sunken temples and cheeks, deep sockets with glowing white eyes and pin pupils, glowing
    tear tracks to the jaw, a thin nose, grey lips round a gaping mouth; the underside is the dark of the open mouth."""
    W, H = f.w, f.h
    if f.side == "bottom":
        for v in range(H):
            for u in range(W):
                p.put(f, u, v, shade((10, 6, 8), p.noise(0.1)))
        return
    for v in range(H):
        for u in range(W):
            dx = (u + 0.5 - W / 2) / (W / 2)
            c = shade(ASH, (1.05 - 0.3 * dx * dx) * p.noise(0.04))
            if f.side == "front" and 0.45 < abs(dx) < 0.95 and 0.55 * H < v < 0.85 * H:
                c = mix(c, ASH_SHADOW, 0.45)
            p.put(f, u, v, c)
    if f.side != "front":
        return
    ey = int(H * 0.38)
    for sgn in (-1, 1):
        cx = W / 2 + sgn * W * 0.24
        for u in range(W):
            for v in range(H):
                r = ((u + 0.5 - cx) / (W * 0.2)) ** 2 + ((v + 0.5 - ey) / (H * 0.1)) ** 2
                if r <= 1.0:
                    p.put(f, u, v, (26, 20, 28))
                elif r <= 1.8:
                    p.put(f, u, v, mix(ASH_SHADOW, (40, 34, 44), 0.5))
        ex = int(cx)
        for du in (-1, 0, 1):
            p.put(f, ex + du, ey, GHOST, GHOST)
        p.put(f, ex, ey - 1, shade(GHOST, 0.8), shade(GHOST, 0.8))
        p.put(f, ex + sgn, ey, (30, 40, 50), (30, 40, 50))
        # Tear tracks, glowing faintly, down to the jaw.
        for v in range(ey + 1, H):
            wob = int(round(math.sin(v * 0.5 + sgn) * 0.6))
            g = shade(TEAR, 1.0 - 0.5 * (v - ey) / (H - ey))
            p.put(f, ex + wob, v, g, g)
    nx = W // 2
    for v in range(int(H * 0.42), int(H * 0.7)):
        p.put(f, nx - 1, v, ASH_SHADOW)
    p.put(f, nx - 2, int(H * 0.7), (60, 56, 64))
    p.put(f, nx + 1, int(H * 0.7), (60, 56, 64))
    for v in range(int(H * 0.8), H):
        for u in range(nx - 3, nx + 3):
            p.put(f, u, v, (12, 8, 10) if v > int(H * 0.82) else (130, 120, 126))


def jaw(p, f, opts):
    """The lower jaw: a grey lip over the dark of the mouth, a sharp pale chin."""
    W, H = f.w, f.h
    for v in range(H):
        for u in range(W):
            dx = (u + 0.5 - W / 2) / (W / 2)
            p.put(f, u, v, shade(ASH, (1.0 - 0.35 * dx * dx) * p.noise(0.04)))
    if f.side == "front":
        for u in range(W // 2 - 3, W // 2 + 3):
            p.put(f, u, 0, (12, 8, 10))
            p.put(f, u, 1, (130, 120, 126))
    if f.side == "top":
        for v in range(H):
            for u in range(W):
                p.put(f, u, v, (40, 10, 14))


def kokoshnik(p, f, opts):
    """A mourning ÐºÐ¾ÐºÐ¾ÑˆÐ½Ð¸Ðº: a pointed arch of black velvet with jagged lace on its edge, a border of dull pearls and a silver
    cross; transparent outside the arch."""
    W, H = f.w, f.h
    d = opts.get("density", 1)
    for v in range(H):
        for u in range(W):
            x = abs(u + 0.5 - W / 2) / (W / 2)
            height = H * (1 - x ** 1.7) * 0.92 + (2 if (u // 2) % 2 == 0 else 0)
            rise = H - v
            if f.side in ("front", "back") and rise > height:
                p.put(f, u, v, None)
                continue
            c = shade(VELVET, p.noise(0.1))
            if f.side in ("front", "back") and height - d * 1.5 < rise <= height:
                c = SILVER if (u + v) % 2 == 0 else SILVER_DARK
            elif f.side in ("front", "back") and height - d * 2.5 < rise <= height - d * 1.5 and u % 3 == 0:
                c = (200, 198, 204)
            p.put(f, u, v, c)
    if f.side == "front":
        cx = W // 2
        for v in range(int(H * 0.3), int(H * 0.75)):
            p.put(f, cx, v, SILVER)
            p.put(f, cx - 1, v, SILVER)
        for u in range(cx - 4, cx + 4):
            p.put(f, u, int(H * 0.42), SILVER)
        for u in range(cx - 3, cx + 3):
            p.put(f, u, int(H * 0.62) + (1 if u < cx else 0), SILVER)
        for u in range(2, W - 2, 3):
            p.put(f, u, H - 2, (190, 188, 196))


def band(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            c = shade(VELVET, p.noise(0.1))
            if v in (1, f.h - 2) and u % 2 == 0:
                c = SILVER_DARK
            p.put(f, u, v, c)


def wax(p, f, opts):
    """A thin yellowed funeral candle, wax run down its side; the top burns."""
    for v in range(f.h):
        for u in range(f.w):
            if f.side == "top":
                p.put(f, u, v, CANDLE, CANDLE)
                continue
            k = 1.08 if u % 4 == 1 else 1.0
            p.put(f, u, v, shade(WAX, k * (1.0 - 0.15 * v / max(1, f.h)) * p.noise(0.04)))


skin = bone(ASH)
knuckle = bone(shade(ASH, 0.92))
shroud = tatter(SHROUD, SHROUD_HL, holes=0.0)
gauze = tatter(GAUZE, SHROUD_HL, holes=0.01, lace=True)


def claw(name, side, curl, splay):
    """A long bony hand: a narrow palm and four very long fingers of three joints with a thumb."""
    hand = Part(name, boxes=[
        Box((-0.85, 0, -0.32), (1.7, 2.2, 0.64), skin),
        Box((-0.95, 1.9, -0.4), (1.9, 0.6, 0.8), knuckle),
        Box((-0.5, -0.6, -0.5), (1.0, 0.9, 1.0), knuckle),
    ])
    for i, x in enumerate((-0.66, -0.22, 0.22, 0.66)):
        length = 2.1 - 0.2 * abs(i - 1.5)
        hand.children.append(finger("%s_finger%d" % (name, i), (x, 2.3, 0), (-curl * 0.5, 0, (x / 0.66) * splay),
                                    (length, length * 0.82, length * 0.62), 0.36, curl, knuckle))
    hand.children.append(finger("%s_thumb" % name, (side * 0.9, 0.8, -0.2), (-0.6, 0, -side * 0.6), (1.5, 1.1), 0.38, 0.35, knuckle))
    return hand


def sleeve_rags(prefix):
    """Torn sleeve ends hanging from the elbow."""
    return strips(prefix + "_rags", pivot=(0, 8.5, 0), top=(1.0, 1.0), bottom=(1.9, 1.9), length=7.5, count=5,
                  base=SHROUD, highlight=SHROUD_HL, segs=2, seed=len(prefix) * 7, width_k=1.0, thickness=0.25)


def arm(label, side, rotation, fore_rotation, curl, splay, held):
    hand = claw(label + "_hand", side, curl, splay)
    hand.pivot = (0, 11.8, 0)
    fore = Part(label + "_forearm", pivot=(0, 11.4, 0), rotation=fore_rotation, boxes=[
        Box((-0.5, 0, -0.5), (1.0, 12, 1.0), skin),
        Box((-0.68, -0.7, -0.68), (1.36, 1.4, 1.36), knuckle),
        Box((-0.58, 11.2, -0.58), (1.16, 0.9, 1.16), knuckle),
    ], children=[hand] + held)
    return Part(label, pivot=(side * 4.2, -12.0, 0), rotation=rotation, boxes=[
        Box((-1.3, -1.1, -1.3), (2.6, 2.4, 2.6), shroud),
        Box((-0.95, 0, -0.95), (1.9, 11.6, 1.9), shroud),
    ], children=[fore, sleeve_rags(label)])


candle = Part("candle", pivot=(0, 12.9, -0.4), rotation=(2.55, 0, -0.1), boxes=[
    Box((-0.5, -7.5, -0.5), (1.0, 8.6, 1.0), wax),
    Box((-0.68, -6.6, -0.2), (0.28, 2.6, 0.5), wax),
    Box((0.4, -5.2, -0.25), (0.28, 1.8, 0.5), wax),
    Box((-0.08, -8.2, -0.08), (0.16, 0.8, 0.16), solid((24, 18, 14))),
], children=[flame("candle_flame", (0, -8.0, 0), height=3.2, width=1.9)])

veil_front = Part("half_veil", pivot=(0.4, -6.9, -2.75), rotation=(-0.06, 0, 0.03),
                  boxes=[Box((-0.3, 0, -0.12), (3.0, 7.6, 0.24), pointed(tatter(GAUZE, SHROUD_HL, end=True, lace=True), 0.6))])

headdress = Part("headdress", pivot=(0, -6.6, 0.8), rotation=(-0.24, 0, 0), boxes=[
    Box((-4.2, -6.4, -0.3), (8.4, 6.4, 0.6), kokoshnik),
    Box((-2.9, -0.6, -3.2), (5.8, 1.2, 5.6), band),
], children=[
    Part("headdress_cross", pivot=(0, -6.0, 0), boxes=[
        Box((-0.22, -2.4, -0.22), (0.44, 2.4, 0.44), solid(SILVER, k=0.08)),
        Box((-0.8, -1.8, -0.22), (1.6, 0.4, 0.44), solid(SILVER, k=0.08)),
    ]),
])

head = Part("head", pivot=(0, -14.6, -0.6), rotation=(0.28, 0, 0), boxes=[
    Box((-2, -6, -2.3), (4, 6, 4.4), wailer_face),
    Box((-2.8, -6.8, -2.5), (0.75, 8.2, 5.4), gauze),
    Box((2.05, -6.8, -2.5), (0.75, 8.2, 5.4), gauze),
    Box((-2.8, -6.8, 2.1), (5.6, 8.6, 0.7), gauze),
    Box((-2.8, -7.4, -2.6), (5.6, 0.7, 5.4), gauze),
    Box((-2.9, -7.2, -3.0), (5.8, 1.9, 0.45), ragged(gauze, 0.35, 2)),
], children=[
    Part("jaw", pivot=(0, -0.2, -0.3), boxes=[Box((-1.6, 0, -2.0), (3.2, 1.6, 3.4), jaw)]),
    veil_front, headdress,
])

parts = [
    Part("figure", pivot=(0, 24, 0), children=[
        strips("hem", pivot=(0, -20.6, 0), top=(3.0, 2.4), bottom=(6.4, 6.0), length=19.2, count=15, base=SHROUD,
               highlight=SHROUD_HL, segs=3, seed=4, width_k=1.05, holes=0.02),
        Part("body", pivot=(0, -20, 0), boxes=[
            Box((-2.6, -6, -1.8), (5.2, 6.6, 3.6), shroud),
            Box((-3.2, -11.6, -2.1), (6.4, 5.8, 4.2), shroud),
            Box((-4.3, -12.7, -1.7), (8.6, 1.9, 3.4), shroud),
            Box((-0.8, -15.2, -1.0), (1.6, 3.4, 1.8), skin),
        ], children=[
            strips("drape", pivot=(0, -9.0, 0), top=(3.6, 2.8), bottom=(7.8, 7.2), length=18.5, count=12, base=SHROUD,
                   highlight=SHROUD_HL, segs=3, seed=9, offset=0.5, width_k=0.95, holes=0.035),
            strips("shawl", pivot=(0, -13.4, 0.1), top=(3.0, 2.3), bottom=(5.8, 4.8), length=8.0, count=12, base=GAUZE,
                   highlight=SHROUD_HL, segs=2, seed=13, width_k=1.0, holes=0.02),
            strips("veil", pivot=(0, -21.2, 2.4), top=(2.6, 1.4), bottom=(8.5, 9.0), length=39, count=8, base=GAUZE,
                   highlight=SHROUD_HL, arc=(2.0, 2 * math.pi - 2.0), segs=4, seed=21, width_k=1.0, holes=0.01, lace=True),
            head,
            arm("right_arm", -1, (-0.18, 0, 0.12), (-0.3, 0, 0), 0.35, 0.22, []),
            arm("left_arm", 1, (-0.35, 0, -0.1), (-2.2, 0, 0.1), 0.9, 0.08, [candle]),
        ]),
    ]),
]

if __name__ == "__main__":
    build("Wailer", "wailer", parts, size=256, seed=56, density=4)
