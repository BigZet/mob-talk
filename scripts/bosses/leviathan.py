"""Левиафан (Job 41): the crooked serpent, the dragon that is in the sea. A colossal sea dragon about twelve blocks long: a
crocodile's head with a long jaw that opens on rows of fangs and on burning lamps in the throat (41:19), smoke from the
nostrils (41:20), eyes like the eyelids of the morning (41:18); a body of fourteen tapering rings of armour, every ring a
crown of plates "shut up together as with a close seal" (41:15) with bronze rims, staggered like real scales and lifted at
the trailing edge so they overlap; a dorsal crest of red membrane on bronze spines, fan fins and a forked tail fin.
Built about 88 units long and drawn at scale 2.25, at 4 pixels per unit."""
from boxtex import *

SEA_DARK = (14, 26, 34)
SEA = (30, 58, 70)
SEA_LIGHT = (70, 112, 116)
BELLY = (168, 150, 110)
BELLY_DARK = (110, 92, 62)
RIM = (176, 120, 58)
MEMBRANE = (96, 18, 22)
MEMBRANE_LIGHT = (150, 44, 36)
DAWN = (255, 206, 120)
DAWN_ROSE = (255, 120, 80)


def canon(f, u, v):
    """Where a pixel lies on its box, as fractions: (x, y, z) with z = 0 at the front (-Z) and 1 at the back."""
    fu, fv = (u + 0.5) / max(1, f.w), (v + 0.5) / max(1, f.h)
    if f.side in ("top", "bottom"):
        return fu, 0.0 if f.side == "top" else 1.0, 1 - fv
    if f.side == "front":
        return fu, fv, 0.0
    if f.side == "back":
        return 1 - fu, fv, 1.0
    if f.side == "right":
        return 0.0, fv, 1 - fu
    return 1.0, fv, fu


# ---- Styles. ----

def plate(angle, belly=False):
    """One armour plate of a ring: a keeled scale with smaller scales in rows, lit by where it sits on the ring, a bronze
    rim along its trailing edge curved like the edge of a shield, and a dark seam where the next plate starts."""
    light = 0.62 + 0.42 * max(-0.4, math.cos(angle))

    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                x, y, z = canon(f, u, v)
                if f.side != "top":
                    c = shade(RIM if f.side == "back" else SEA_DARK, p.noise(0.06) * light)
                    p.put(f, u, v, c)
                    continue
                across = abs(x - 0.5) * 2
                edge = 0.84 - 0.16 * across * across
                if belly:
                    band = (z * 3) % 1.0
                    c = mix(BELLY, BELLY_DARK, 0.15 + 0.6 * band ** 3)
                    if band > 0.9:
                        c = shade(BELLY_DARK, 0.7)
                    if across > 0.9:
                        c = shade(c, 0.7)
                else:
                    c = mix(SEA_LIGHT, SEA, 0.35 + 0.65 * z)
                    # Little round scales in rows over the plate, each lit at its root and rimmed along its free edge.
                    cell = 1.3
                    xu, zu = x * f.w / d / cell, z * f.h / d / cell
                    row = math.floor(zu)
                    fu = (xu + 0.5 * (row % 2)) % 1.0
                    fz = zu - row
                    bound = 0.62 + 0.36 * (1 - (2 * fu - 1) ** 2)
                    if fz > bound:
                        c = shade(c, 0.62)
                    elif fz > bound - 0.16:
                        c = mix(c, RIM, 0.35)
                    else:
                        c = shade(c, 1.12 - 0.3 * fz)
                    if across < 0.12:
                        c = mix(c, SEA_LIGHT, 0.45)
                    if z > edge:
                        c = mix(RIM, GOLD, 0.35 * (1 - across)) if z < edge + 0.1 else shade(SEA_DARK, 0.6)
                    elif z > edge - 0.05:
                        c = shade(c, 0.6)
                    if 0.12 < z < 0.2 and abs(across - 0.5) < 0.08:
                        c = GOLD_DARK
                p.put(f, u, v, shade(c, light * p.noise(0.05)))
    return paint


def membrane(ribs=4, scallop=0.45, rake=0.18, base=MEMBRANE, light=MEMBRANE_LIGHT):
    """A fin on a thin vertical plane: red membrane between bronze spines that rake back, its top edge scalloped between
    the spines and ragged; the spines stand out above it. Painted the same from either side."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                x, y, z = canon(f, u, v)
                if f.side not in ("left", "right"):
                    p.put(f, u, v, None)
                    continue
                up = 1 - y
                zz = z - rake * up
                k = zz * ribs - 0.5
                near = abs(k - round(k)) / ribs
                top = 1 - scallop * min(1.0, (near * ribs * 2) ** 1.5)
                spine = near * ribs < 0.07 and zz > -0.05
                if spine:
                    c = mix(BONE, RIM, 0.3 + 0.5 * y)
                elif up > top or (up > top - 0.04 and p.rand.random() < 0.4):
                    p.put(f, u, v, None)
                    continue
                else:
                    c = mix(light, base, 0.3 + 0.7 * y)
                    if int(up * 14 + near * 30) % 3 == 0:
                        c = shade(c, 0.85)
                p.put(f, u, v, shade(c, p.noise(0.05)))
    return paint


def fan(side=1, ribs=7):
    """A fan fin on a thin horizontal plane: rays from the root corner, membrane between them, a ragged outer edge."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                x, y, z = canon(f, u, v)
                if f.side not in ("top", "bottom"):
                    p.put(f, u, v, None)
                    continue
                s = x if side > 0 else 1 - x
                r = math.hypot(s, z * 0.8)
                a = math.atan2(z * 0.8, s + 1e-6) / (math.pi / 2)
                outer = 1.0 - 0.12 * abs(math.sin(a * ribs * math.pi))
                if r > outer or (r > outer - 0.05 and p.rand.random() < 0.35) or z > 0.98:
                    p.put(f, u, v, None)
                    continue
                k = a * ribs
                if abs(k - round(k)) < 0.09:
                    c = mix(BONE, RIM, 0.4 + 0.4 * r)
                else:
                    c = mix(MEMBRANE_LIGHT, MEMBRANE, r)
                p.put(f, u, v, shade(c, p.noise(0.05)))
    return paint


def tail_fan(p, f, opts):
    """The tail fin seen from the side: a forked crescent of membrane on rays spreading from the root."""
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            if f.side not in ("left", "right"):
                p.put(f, u, v, None)
                continue
            dy = abs(y - 0.5) * 2
            width = 0.18 + 0.82 * min(1.0, z / 0.8) ** 0.8
            notch = 1 - 0.45 * (1 - dy) ** 1.5
            if dy > width or z > notch or (z > notch - 0.04 and p.rand.random() < 0.4):
                p.put(f, u, v, None)
                continue
            a = math.atan2((y - 0.5), z + 0.05)
            k = a / 0.16
            if abs(k - round(k)) < 0.12:
                c = mix(BONE, RIM, 0.3 + 0.5 * z)
            else:
                c = mix(MEMBRANE_LIGHT, MEMBRANE, z)
            p.put(f, u, v, shade(c, p.noise(0.05)))


def fluke(p, f, opts):
    """The flat tail flukes seen from above: two swept lobes."""
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            if f.side not in ("top", "bottom"):
                p.put(f, u, v, None)
                continue
            dx = abs(x - 0.5) * 2
            if z < dx * 0.75 - 0.05 or z > 0.35 + dx * 0.65 or (z > 0.3 + dx * 0.65 and p.rand.random() < 0.4):
                p.put(f, u, v, None)
                continue
            k = dx * 6
            c = mix(BONE, RIM, 0.5) if abs(k - round(k)) < 0.1 else mix(MEMBRANE_LIGHT, MEMBRANE, dx)
            p.put(f, u, v, shade(c, p.noise(0.05)))


def scutes(base=SEA, rim=RIM, under=BELLY):
    """A crocodile's hide over the skull: square scutes with dark grooves and bronze-flecked edges; a pale underside."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        cell = max(3, int(2.4 * d))
        for v in range(f.h):
            for u in range(f.w):
                if f.side == "bottom":
                    c = mix(under, BELLY_DARK, 0.3 if (v // max(1, d)) % 3 == 0 else 0.0)
                    p.put(f, u, v, shade(c, p.noise(0.05)))
                    continue
                row = v // cell
                uu = (u + (cell // 2 if row % 2 else 0)) % cell
                vv = v % cell
                c = mix(SEA_LIGHT, base, 0.4 + 0.6 * vv / cell)
                if uu == 0 or vv == 0:
                    c = shade(SEA_DARK, 0.8)
                elif vv == cell - 1 and p.rand.random() < 0.5:
                    c = mix(c, rim, 0.6)
                p.put(f, u, v, shade(c, p.noise(0.06)))
    return paint


def upper_jaw(p, f, opts):
    """The snout: scutes outside, the palate underneath burning (the burning lamps of Job 41:19)."""
    if f.side == "bottom":
        for v in range(f.h):
            for u in range(f.w):
                c = mix(CANDLE, FLAME, 0.3 + 0.5 * abs(u / max(1, f.w - 1) - 0.5) * 2)
                if (v // 3) % 4 == 0:
                    c = mix(c, EMBER, 0.5)
                c = shade(c, p.noise(0.12))
                p.put(f, u, v, c, c)
    else:
        scutes()(p, f, opts)


def lower_jaw(p, f, opts):
    """The lower jaw: scutes outside, a pale scaled throat beneath, a mouth full of fire on top."""
    if f.side == "top":
        for v in range(f.h):
            for u in range(f.w):
                x, y, z = canon(f, u, v)
                across = abs(x - 0.5) * 2
                c = mix((255, 236, 170), FLAME, across) if across < 0.75 else mix(EMBER, BLOOD, (across - 0.75) * 4)
                c = shade(c, p.noise(0.1))
                p.put(f, u, v, c, c if across < 0.9 else None)
    else:
        scutes()(p, f, opts)


def fang(p, f, opts):
    """Ivory going dark at the root."""
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            p.put(f, u, v, shade(mix(BONE, (150, 128, 96), y if f.side != "top" else 0.6), p.noise(0.05)))


def morning_eye(p, f, opts):
    """Eyes like the eyelids of the morning (Job 41:18): rose dawn at the rim, gold within, a black slit; it all shines."""
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            cx = z if f.side in ("left", "right", "top", "bottom") else x
            cy = y if f.side not in ("top", "bottom") else x
            r = math.hypot((cx - 0.5) * 1.3, (cy - 0.5) * 2)
            c = mix((255, 246, 200), DAWN, min(1, r * 1.4))
            if r > 0.7:
                c = mix(DAWN, DAWN_ROSE, min(1, (r - 0.7) * 3))
            if abs(cx - 0.5) < 0.09 and abs(cy - 0.5) < 0.42:
                p.put(f, u, v, (30, 10, 6))
                continue
            p.put(f, u, v, c, c)


def nostril(p, f, opts):
    """A nostril bulb: scutes, with a dark hole on top lit by embers deep inside (smoke comes from here)."""
    if f.side != "top":
        scutes()(p, f, opts)
        return
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            r = math.hypot(x - 0.5, z - 0.5)
            if r < 0.3:
                p.put(f, u, v, EMBER if r <= 0.14 else (20, 8, 6), EMBER if r <= 0.14 else None)
            else:
                p.put(f, u, v, shade(SEA, p.noise(0.06)))


def lamp(p, f, opts):
    """A burning lamp in the throat."""
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            c = mix((255, 250, 220), FLAME, min(1, math.hypot(x - 0.5, y - 0.5) * 2.2))
            p.put(f, u, v, c, c)


def horn_style(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            c = mix(BONE, (60, 46, 34), z * 0.7)
            if int(z * 9) % 3 == 0 and z < 0.7:
                c = mix(c, RIM, 0.5)
            p.put(f, u, v, shade(c, p.noise(0.05)))


# ---- Builders. ----

def ring(name, z0, z1, sec0, sec1, n, style_for, thickness=0.9, overlap=1.14, flare=0.0, start=0.0, keep=None):
    """A ring of plates around the Z axis between z0 and z1: `sec` = (rx, ry[, cy]) of the ellipse at each end (cy is its
    centre height). Every plate is a thin box whose outer face is its top, rolled onto the ellipse and tilted to follow the
    taper (plus `flare`, which lifts the trailing edge so plates overlap the next ring like shingles). `style_for(i, angle)`
    paints plate i; `keep(angle)` may leave plates out (an open dome over a palate, a jaw open on top)."""
    parts = []
    da = math.pi / n
    sec0, sec1 = tuple(sec0) + (0,) * (3 - len(sec0)), tuple(sec1) + (0,) * (3 - len(sec1))

    def pt(sec, ang):
        return sec[0] * math.sin(ang), sec[2] - sec[1] * math.cos(ang)

    for i in range(n):
        a = start + 2 * math.pi * i / n
        if keep and not keep(a):
            continue
        p0, p1 = pt(sec0, a), pt(sec1, a)
        rx, ry = (sec0[0] + sec1[0]) / 2, (sec0[1] + sec1[1]) / 2
        nx, ny = ry * math.sin(a), -rx * math.cos(a)
        ln = math.hypot(nx, ny)
        nx, ny = nx / ln, ny / ln
        roll = math.atan2(nx, -ny)

        def chord(sec):
            q0, q1 = pt(sec, a - da), pt(sec, a + da)
            return math.hypot(q1[0] - q0[0], q1[1] - q0[1])
        w = max(chord(sec0), chord(sec1)) * overlap
        length = z1 - z0
        out = (p1[0] - p0[0]) * nx + (p1[1] - p0[1]) * ny
        pitch = math.atan2(out, length) + flare
        plen = math.hypot(length, out)
        parts.append(Part("%s_%d" % (name, i), pivot=(p0[0], p0[1], z0), rotation=(pitch, 0, roll),
                          boxes=[Box((-w / 2, 0, 0), (w, thickness, plen), style_for(i, a), flat=True)]))
    return parts


def plates_for(i, a):
    return plate(a, belly=math.cos(a) < -0.62)


def body_plates(name, length, r0, r1, n, stagger):
    start = (math.pi / n) if stagger else 0.0
    return ring(name, -0.4, length * 1.24, (r0, r0 * 0.94), (r1 * 1.02, r1 * 0.96), n, plates_for, flare=0.09, start=start)


def chain(name, pivot, rotation, pieces, style):
    """A tapering curved spike (a horn): `pieces` = [(length, thickness, pitch, yaw)], each piece a child of the one
    before, so the curve bends a little more at every joint; two thinner pieces end it in a sharp point."""
    last_t = pieces[-1][1]
    pieces = list(pieces) + [(1.4, last_t * 0.55, 0.1, 0), (1.1, last_t * 0.25, 0.1, 0)]
    first = parent = None
    for k, (length, t, pitch, yaw) in enumerate(pieces):
        part = Part("%s_%d" % (name, k), pivot=pivot if k == 0 else (0, 0, pieces[k - 1][0] * 0.92),
                    rotation=(rotation[0] + pitch, rotation[1] + yaw, rotation[2]) if k == 0 else (pitch, yaw, 0),
                    boxes=[Box((-t / 2, -t / 2, 0), (t, t, length), style)])
        if parent is None:
            first = part
        else:
            parent.children.append(part)
        parent = part
    return first


def pointed(w, L, down):
    """A fang in three narrowing steps, so it ends in a point and not a blunt cube."""
    steps = [(1.0, 0.5), (0.62, 0.3), (0.3, 0.25)]
    boxes, y = [], 0.0
    for wk, lk in steps:
        ww, ll = w * wk, L * lk
        boxes.append(Box((-ww / 2, y if down else -y - ll, -ww / 2), (ww, ll, ww), fang))
        y += ll
    return boxes


def fang_row(prefix, points, y, length, down=True):
    """Fangs along a jaw edge: (x, z, scale) tuples; they hang down from the upper jaw or stand up from the lower."""
    parts = []
    for k, (x, z, s) in enumerate(points):
        lean = 0.15 if x > 0 else -0.15 if x < 0 else 0
        parts.append(Part("%s_%d" % (prefix, k), pivot=(x, y, z), rotation=(0.12 if down else -0.12, 0, lean if down else -lean),
                          boxes=pointed(0.8 if down else 0.7, length * s, down)))
    return parts


# ---- The head: a skull and a long snout built of rings of scales like the body, a long jaw below. ----

def lerp_r(z, z_back, r_back, z_front, r_front):
    t = (z - z_back) / (z_front - z_back)
    return r_back + (r_front - r_back) * t


def edge_fangs(z_back, r_back, z_front, r_front, count, k_big):
    pts = []
    for k in range(count):
        z = z_back + (z_front - z_back) * k / (count - 1)
        s = 1.7 if k == k_big else 1.0 + 0.25 * math.sin(k * 2.1)
        pts.append((lerp_r(z, z_back, r_back, z_front, r_front) * 0.86, z, s))
    return pts


def palate(p, f, opts):
    """The roof of the mouth: burning (the burning lamps of Job 41:19)."""
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            if f.side != "bottom":
                p.put(f, u, v, shade(SEA_DARK, p.noise(0.06)))
                continue
            c = mix(CANDLE, FLAME, 0.3 + 0.6 * abs(x - 0.5) * 2)
            if int(z * 14) % 3 == 0:
                c = mix(c, EMBER, 0.5)
            c = shade(c, p.noise(0.1))
            p.put(f, u, v, c, c)


upper = edge_fangs(-10.0, 4.9, -21.6, 3.0, 9, 6)
upper_fangs = fang_row("fang_ur", [(-x, z, s) for x, z, s in upper], 1.4, 2.5) + fang_row("fang_ul", upper, 1.4, 2.5) + \
    fang_row("fang_uf", [(-1.3, -22.3, 1.3), (1.3, -22.3, 1.3)], 1.3, 1.9)
lower = edge_fangs(-2.0, 4.6, -19.6, 3.0, 9, 6)
lower_fangs = fang_row("fang_lr", [(-x, z, s) for x, z, s in lower], -0.1, 2.2, down=False) + \
    fang_row("fang_ll", lower, -0.1, 2.2, down=False)

jaw = Part("jaw", pivot=(0, 1.6, -0.5), rotation=(0.16, 0, 0), children=ring(
    "jaw_p", -20.6, 0.6, (3.0, 2.0, 0), (4.8, 3.4, 0), 10, plates_for, keep=lambda a: math.cos(a) < 0.0) + lower_fangs + [
    Part("jaw_tip", pivot=(0, 0, -20.6), boxes=[Box((-3.0, -0.3, -0.8), (6.0, 2.3, 1.0), scutes())]),
    Part("tongue", pivot=(0, -0.2, 0), boxes=[Box((-3.6, 0, -19.8), (7.2, 0.4, 19.6), lower_jaw)]),
    Part("lamp_0", pivot=(-2.0, -0.9, -6.5), rotation=(0.3, 0.4, 0.2), boxes=[Box((-0.8, -0.8, -0.8), (1.6, 1.6, 1.6), lamp, flat=True)]),
    Part("lamp_1", pivot=(1.9, -0.8, -8.8), rotation=(0.6, 0.2, 0.5), boxes=[Box((-0.7, -0.7, -0.7), (1.4, 1.4, 1.4), lamp, flat=True)]),
    Part("lamp_2", pivot=(0.0, -1.0, -4.2), rotation=(0.2, 0.7, 0.1), boxes=[Box((-0.9, -0.9, -0.9), (1.8, 1.8, 1.8), lamp, flat=True)]),
])


def horn(label, side):
    return chain("horn_" + label, (side * 3.4, -4.4, -0.5), (0.5, side * 0.3, 0),
                 [(4.0, 2.0, 0, 0), (3.4, 1.6, 0.18, side * 0.05), (2.8, 1.2, 0.22, side * 0.05)], horn_style)


def frill(label, side):
    """A fan of membrane behind the cheek, like the ear of a sea dragon."""
    return Part("frill_" + label, pivot=(side * 5.2, 0.0, -1.0), rotation=(0.1, side * 0.55, 0), boxes=[
        Box((-0.2, -4.5, 0), (0.4, 9, 8), membrane(ribs=4, scallop=0.5, rake=0.35))])


def brow(label, side):
    """A heavy scaled brow over the eye, ending in a backward point."""
    return Part("brow_" + label, pivot=(side * 4.9, -4.2, -10.4), rotation=(0.12, side * 0.18, side * 0.45), boxes=[
        Box((-1.2, -0.4, 0), (2.4, 0.9, 4.6), scutes()), Box((-0.8, -0.3, 4.6), (1.6, 0.6, 1.6), scutes()),
        Box((-0.35, -0.15, 6.2), (0.7, 0.3, 1.2), horn_style)])


head = Part("head", pivot=(0, -1.8, -6.2), boxes=[
    Box((-6.2, -3.6, -9.8), (1.8, 1.6, 3.4), morning_eye, flat=True),
    Box((4.4, -3.6, -9.8), (1.8, 1.6, 3.4), morning_eye, flat=True),
    Box((-3.0, -1.9, -23.0), (6.0, 3.5, 1.0), scutes()),
    Box((-2.4, -2.5, -21.9), (1.7, 0.9, 1.9), nostril),
    Box((0.7, -2.5, -21.9), (1.7, 0.9, 1.9), nostril),
    Box((-4.4, 1.3, -15.6), (8.8, 0.4, 6.2), palate),
    Box((-3.0, 1.2, -22.4), (6.0, 0.4, 7.0), palate),
], children=ring("skull", -9.8, 1.6, (5.8, 4.4, -0.8), (5.4, 4.8, -0.4), 10, plates_for) +
    ring("snout", -22.5, -9.4, (3.0, 3.4, 1.5), (4.9, 5.2, 1.6), 10, plates_for, keep=lambda a: math.cos(a) > 0.0) +
    upper_fangs + [
    jaw,
    horn("r", -1), horn("l", 1), frill("r", -1), frill("l", 1), brow("r", -1), brow("l", 1),
    Part("head_crest", pivot=(0, -5.0, 1), rotation=(-0.2, 0, 0), boxes=[Box((-0.2, -4.5, -8), (0.4, 4.5, 9), membrane(ribs=3, scallop=0.4, rake=0.3))]),
])

# ---- The body: a neck, a trunk at the shoulders and fourteen rings to the tail. ----

LENGTHS = [4.6, 4.6, 4.6, 4.4, 4.2, 4.0, 3.9, 3.8, 3.6, 3.5, 3.4, 3.2, 3.0, 3.0]
RADII = [7.8, 8.0, 7.9, 7.6, 7.1, 6.5, 5.9, 5.3, 4.7, 4.1, 3.5, 2.9, 2.4, 1.9, 1.4]


def crest(name, pivot, length, height):
    return Part(name, pivot=pivot, rotation=(-0.12, 0, 0), boxes=[
        Box((-0.2, -height, 0), (0.4, height, length * 1.15), membrane(ribs=2, scallop=0.42, rake=0.25))])


def fins(prefix, z, r, span, chord, down=0.35):
    return [Part(prefix + "_l", pivot=(r * 0.92, r * 0.25, z), rotation=(0, -0.55, down), boxes=[Box((0, -0.2, -2), (span, 0.4, chord), fan(1))]),
            Part(prefix + "_r", pivot=(-r * 0.92, r * 0.25, z), rotation=(0, 0.55, -down), boxes=[Box((-span, -0.2, -2), (span, 0.4, chord), fan(-1))])]


tail_fin = Part("tail_fin", pivot=(0, 0, LENGTHS[-1]), boxes=[
    Box((-0.2, -9, -1), (0.4, 18, 12), tail_fan),
    Box((-7, -0.2, -1), (14, 0.4, 9), fluke),
])

child = tail_fin
for k in range(len(LENGTHS) - 1, -1, -1):
    r0, r1, L = RADII[k], RADII[k + 1], LENGTHS[k]
    n = 10 if r0 > 5 else 8 if r0 > 3 else 6
    kids = body_plates("seg%d_p" % (k + 1), L, r0, r1, n, stagger=k % 2 == 0)
    kids.append(crest("seg%d_crest" % (k + 1), (0, -r0 * 0.94 + 0.6, 0.2), L, 1.6 + 3.6 * (r0 / 8.0) ** 1.4))
    if k == 7:
        kids += fins("fin_hind", 1.0, r0, 7, 5, 0.5)
    kids.append(child)
    child = Part("seg%d" % (k + 1), pivot=(0, 0, 5.0) if k == 0 else (0, 0, LENGTHS[k - 1]), children=kids)
seg1 = child

neck = Part("neck", pivot=(0, 0, 0), children=ring("neck_p", -6.4, 0.4, (6.6, 6.3), (7.8, 7.3), 10, plates_for, flare=-0.05) + [
    crest("neck_crest", (0, -5.6, -6.2), 6, 4.2),
    head,
])

trunk = Part("trunk", pivot=(0, 0, -6), children=ring("trunk_p", -0.4, 5.6, (7.8, 7.3), (8.0, 7.5), 10, plates_for, flare=0.07) + [
    crest("trunk_crest", (0, -6.6, 0.2), 5, 5.6),
    neck,
    seg1,
] + fins("fin_fore", 2.0, 7.8, 12, 8, 0.4))

serpent = Part("serpent", pivot=(0, 15, 0), children=[trunk])

if __name__ == "__main__":
    build("Leviathan", "leviathan", [serpent], size=320, seed=41, density=4)
