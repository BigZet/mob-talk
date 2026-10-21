"""Бегемот (Job 40:15-24): the beast of the earth. A colossal river-horse and ox in one, about ten and a half blocks long and
over six tall: a barrel body made of rings of hide plates (round, not a crate), mossy on the back, caked with swamp mud
below, with "bones as strong pieces of brass" (40:18) showing as brass rib hoops along the flanks and brass vertebrae along
the spine; four pillar legs "like bars of iron" with plated knees, iron bands and toenails; a huge head with a broad muzzle,
heavy brows, small ember eyes, curved tusks and great aurochs horns; and a tail "like a cedar" (40:17): a bark trunk of
five pieces with branch-like spines hung with needles. Built about 72 units long and drawn at scale 2.4, 4 pixels per unit."""
import random as _random
from boxtex import *

MUD = (86, 70, 50)
MUD_DARK = (52, 40, 28)
MUD_WET = (64, 50, 36)
MOSS = (70, 96, 40)
MOSS_LIGHT = (112, 136, 58)
BARK = (78, 52, 34)
BARK_DARK = (42, 28, 20)
NEEDLE = (30, 64, 40)
NEEDLE_LIGHT = (72, 112, 62)
EMBER_EYE = (255, 120, 40)
HORN = (58, 46, 36)
IRON = (62, 60, 64)


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


def _hash(ix, iy, seed):
    n = (ix * 374761393 + iy * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return (n & 0xFFFF) / 65535.0


def vnoise(x, y, seed=0):
    """Smooth value noise, so moss and mud lie in patches that run on from one plate to the next."""
    ix, iy = math.floor(x), math.floor(y)
    fx, fy = x - ix, y - iy
    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    a, b = _hash(ix, iy, seed), _hash(ix + 1, iy, seed)
    c, d = _hash(ix, iy + 1, seed), _hash(ix + 1, iy + 1, seed)
    return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy


def fbm(x, y, seed=0):
    return 0.6 * vnoise(x, y, seed) + 0.3 * vnoise(x * 2.1, y * 2.1, seed + 7) + 0.1 * vnoise(x * 4.3, y * 4.3, seed + 13)


# ---- Styles. ----

def hide(angle, arc0, arc1, z0, z1):
    """A plate of hide on the barrel. `angle` is where it sits on the ring (0 = the spine), arc0..arc1 and z0..z1 are its
    place on the body in units, so the patches of moss and mud match across neighbouring plates. Moss on the back, folds
    and wrinkles on the flanks, wet mud caked and dripping below; lit from above."""
    up = math.cos(angle)
    light = 0.66 + 0.4 * max(-0.4, up)

    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                x, y, z = canon(f, u, v)
                if f.side != "top":
                    p.put(f, u, v, shade(MUD_DARK, p.noise(0.08) * light))
                    continue
                s, t = arc0 + (arc1 - arc0) * x, z0 + (z1 - z0) * z
                n = fbm(s * 0.22, t * 0.22, 3)
                c = mix(MUD, MUD_WET, n)
                wr = math.sin(s * 1.7 + 2.2 * fbm(s * 0.3, t * 0.3, 9)) * 0.5 + 0.5
                if wr > 0.86:
                    c = shade(c, 0.74)
                moss = up * 1.2 + (fbm(s * 0.35, t * 0.35, 21) - 0.5) * 1.4
                if moss > 0.55:
                    c = mix(MOSS, MOSS_LIGHT, fbm(s * 1.3, t * 1.3, 5)) if moss > 0.7 else mix(c, MOSS, 0.5)
                mud = -up * 1.3 + (fbm(s * 0.5, t * 0.25, 33) - 0.5) * 1.2
                if mud > 0.45:
                    c = mix(c, MUD_DARK, min(1, (mud - 0.45) * 2))
                if p.rand.random() < 0.02:
                    c = shade(c, 1.25)
                p.put(f, u, v, shade(c, light * p.noise(0.04)))
    return paint


def brass(p, f, opts):
    """Brass bone: a warm gradient with a lit ridge, verdigris in the hollows and hammer marks."""
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            t = y if f.side not in ("top", "bottom") else z
            c = mix(GOLD_LIGHT, BRONZE, 0.25 + 0.6 * t)
            if f.side == "top":
                c = mix(c, GOLD_LIGHT, 0.4)
            if p.rand.random() < 0.08:
                c = mix(c, PATINA, 0.5)
            if p.rand.random() < 0.05:
                c = shade(c, 0.8)
            p.put(f, u, v, shade(c, p.noise(0.05)))


def rib_brass(p, f, opts):
    """Old brass bone showing through the hide: darker than polished brass, green in the grooves, lit along the crest."""
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            c = mix(GOLD, BRONZE, 0.55 + 0.4 * abs(z - 0.5) * 2) if f.side == "top" else shade(BRONZE, 0.8)
            if p.rand.random() < 0.12:
                c = mix(c, PATINA, 0.55)
            p.put(f, u, v, shade(c, p.noise(0.06)))


def iron(p, f, opts):
    """Iron bands: dark, with a cold highlight on the upper edge and rivets."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            c = mix((120, 118, 124), IRON, min(1, y * 2.5)) if f.side not in ("top", "bottom") else IRON
            if f.side not in ("top", "bottom") and u % (3 * d) == d and abs(y - 0.5) < 0.2:
                c = (150, 146, 140)
            p.put(f, u, v, shade(c, p.noise(0.06)))


def leg_hide(p, f, opts):
    """Legs: grey-brown hide in deep horizontal folds, mud splashed up from the feet, moss gone."""
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            c = mix(MUD, MUD_DARK, y * 0.6)
            if math.sin(y * 40 + math.sin(x * 9 + z * 7) * 1.5) > 0.75:
                c = shade(c, 0.72)
            if y > 0.55 and fbm(u * 0.2, v * 0.1, 4) < (y - 0.4):
                c = mix(c, MUD_DARK, 0.7)
            p.put(f, u, v, shade(c, p.noise(0.05)))


def hoof(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            p.put(f, u, v, shade(mix((44, 36, 30), (24, 20, 18), y), p.noise(0.06)))


def nail(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            p.put(f, u, v, shade(mix(BONE, (120, 100, 80), y), p.noise(0.05)))


def bark(p, f, opts):
    """Cedar bark: deep furrows running along the trunk, reddish ridges between them, lichen here and there."""
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            across = y if f.side in ("left", "right") else x
            ridge = math.sin(across * 18 + math.sin(z * 9) * 1.2)
            c = mix(BARK, (110, 70, 44), 0.5 + 0.5 * ridge) if ridge > -0.4 else BARK_DARK
            if fbm(across * 4, z * 6, 2) > 0.72:
                c = mix(c, MOSS_LIGHT, 0.5)
            p.put(f, u, v, shade(c, p.noise(0.06)))


def needles(p, f, opts):
    """A cedar spray on a thin plane: a twig down the middle and needles fanning off it, the gaps between them open."""
    d = opts.get("density", 1)
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            if f.side not in ("top", "bottom"):
                p.put(f, u, v, None)
                continue
            across = abs(x - 0.5) * 2
            width = 1 - z ** 1.6
            if across > width:
                p.put(f, u, v, None)
                continue
            if across < 0.08:
                p.put(f, u, v, shade(BARK, p.noise(0.08)))
                continue
            k = (z * 18 + across * 4) % 1.0
            if k > 0.62 or p.rand.random() < 0.08:
                p.put(f, u, v, None)
                continue
            c = mix(NEEDLE_LIGHT, NEEDLE, across)
            p.put(f, u, v, shade(c, p.noise(0.08)))


def head_hide(p, f, opts):
    """The head: rough hide, moss on the crown, warts; the underside paler mud."""
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            n = fbm(u * 0.08, v * 0.08, 11)
            c = mix(MUD, MUD_WET, n)
            if f.side == "top" and fbm(u * 0.12, v * 0.12, 17) > 0.45:
                c = mix(MOSS, MOSS_LIGHT, fbm(u * 0.4, v * 0.4, 3))
            if f.side == "bottom":
                c = mix(c, MUD_DARK, 0.4)
            if math.sin(v * 0.7 + math.sin(u * 0.3) * 2) > 0.9:
                c = shade(c, 0.78)
            if p.rand.random() < 0.015:
                c = shade(c, 1.3)
            p.put(f, u, v, shade(c, p.noise(0.05)))


def muzzle(p, f, opts):
    """The muzzle: hide, with two great nostrils on top and lips darkened along the bottom edge."""
    head_hide(p, f, opts)
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            if f.side == "top":
                for nx in (0.27, 0.73):
                    r = math.hypot((x - nx) * 1.6, (z - 0.25) * 1.2)
                    if r < 0.16:
                        p.put(f, u, v, (18, 12, 10) if r < 0.12 else MUD_DARK)
            if f.side == "front" and y > 0.8:
                p.put(f, u, v, shade((50, 30, 26), p.noise(0.06)))


def mouth(p, f, opts):
    """The lower jaw: hide outside, a dark red mouth with a row of blunt teeth on top."""
    if f.side != "top":
        head_hide(p, f, opts)
        return
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            across = abs(x - 0.5) * 2
            c = mix((110, 30, 26), (60, 14, 12), across)
            if across > 0.8 and int(z * 14) % 2 == 0:
                c = BONE
            p.put(f, u, v, shade(c, p.noise(0.06)))


def ember_eye(p, f, opts):
    """A small ember eye: a burning core, a dark rim."""
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            cx = z if f.side in ("left", "right", "top", "bottom") else x
            r = math.hypot(cx - 0.5, y - 0.5) if f.side not in ("top", "bottom") else 1
            if r < 0.42:
                c = mix((255, 236, 160), EMBER_EYE, min(1, r * 2.4))
                p.put(f, u, v, c, c)
            else:
                p.put(f, u, v, (30, 14, 8))


def horn_style(p, f, opts):
    """Horn: dark keratin in growth rings, brass bands on the base pieces."""
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            c = HORN if int(z * 6) % 2 else shade(HORN, 0.8)
            p.put(f, u, v, shade(c, p.noise(0.06)))


def horn_tip(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            p.put(f, u, v, shade(mix(HORN, BONE, 0.3 + 0.6 * z), p.noise(0.05)))


def tusk(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            p.put(f, u, v, shade(mix((170, 150, 112), BONE, z), p.noise(0.05)))


# ---- Builders. ----

def ring(name, z0, z1, sec0, sec1, n, style_for, thickness=1.2, overlap=1.12, start=0.0, keep=None):
    """A ring of hide plates around the Z axis between z0 and z1; `sec` = (rx, ry, cy): the ellipse at each end and its
    centre height. Every plate is a thin box whose outer face is its top, rolled onto the ellipse and tilted to follow the
    taper. `style_for(i, angle, arc0, arc1, z0, z1)` paints plate i; `keep(angle)` may leave plates out (a dome, a jaw)."""
    parts = []
    da = math.pi / n

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
        pitch = math.atan2(out, length)
        plen = math.hypot(length, out) + 0.4
        arc = (rx + ry) / 2 * a
        parts.append(Part("%s_%d" % (name, i), pivot=(p0[0], p0[1], z0 - 0.2), rotation=(pitch, 0, roll),
                          boxes=[Box((-w / 2, 0, 0), (w, thickness, plen), style_for(i, a, arc - w / 2, arc + w / 2, z0, z1), flat=True)]))
    return parts


def pillar(name, x, y, z, w, h, style, rotation=(0, 0, 0), children=()):
    """A thick round-ish column: a square box and a smaller one turned 45 degrees inside it, so the corners are bevelled."""
    inner = w * 0.78
    return Part(name, pivot=(x, y, z), rotation=rotation, boxes=[Box((-w / 2, 0, -w / 2), (w, h, w), style)], children=[
        Part(name + "_bevel", pivot=(0, 0, 0), rotation=(0, math.pi / 4, 0), boxes=[Box((-inner / 2, 0.15, -inner / 2), (inner, h - 0.3, inner), style)]),
    ] + list(children))


def chain(name, pivot, rotation, pieces, styles):
    """A tapering curved piece (horn, tusk): `pieces` = [(length, thickness, pitch, yaw, roll)], each a child of the one
    before, so the curve bends further at every joint; the last pieces are thin enough to end in a point."""
    first = parent = None
    for k, (length, t, pitch, yaw, roll) in enumerate(pieces):
        style = styles[min(k, len(styles) - 1)]
        part = Part("%s_%d" % (name, k), pivot=pivot if k == 0 else (0, 0, pieces[k - 1][0] * 0.9),
                    rotation=(rotation[0] + pitch, rotation[1] + yaw, rotation[2] + roll) if k == 0 else (pitch, yaw, roll),
                    boxes=[Box((-t / 2, -t / 2, 0), (t, t, length), style)])
        if parent is None:
            first = part
        else:
            parent.children.append(part)
        parent = part
    return first, parent


# ---- The body: a barrel of hide rings, pivoting at the hips so he can rear. ----

HIPS = (0, -25, 13)
STATIONS = [  # z, rx, ry, cy (in figure space: the ground is y = 0)
    (-21, 8.5, 9.0, -27),
    (-17, 13.0, 13.0, -29.5),
    (-8, 15.0, 13.0, -28.0),
    (3, 15.5, 12.5, -25.5),
    (13, 14.5, 11.5, -26),
    (19.5, 11.5, 10.0, -26),
    (23, 6.0, 5.5, -26),
    (24.2, 1.2, 1.2, -26),
]


def at(z):
    """The body's ellipse at z, interpolated between stations (figure space)."""
    for (za, *a), (zb, *b) in zip(STATIONS, STATIONS[1:]):
        if za <= z <= zb:
            t = (z - za) / (zb - za)
            return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))
    return tuple(STATIONS[-1][1:])


def rel(x, y, z):
    return x - HIPS[0], y - HIPS[1], z - HIPS[2]


def hide_for(i, a, arc0, arc1, z0, z1):
    return hide(a, arc0, arc1, z0, z1)


body_parts = []
for k, ((za, rxa, rya, cya), (zb, rxb, ryb, cyb)) in enumerate(zip(STATIONS, STATIONS[1:])):
    n = 14 if max(rxa, rxb) > 9 else 10
    for part in ring("hide%d" % k, za, zb, (rxa, rya, cya), (rxb, ryb, cyb), n, hide_for, start=(math.pi / n) * (k % 2)):
        px, py, pz = part.pivot
        part.pivot = rel(px, py, pz)
        body_parts.append(part)

# Bones as tubes of brass: rib hoops on each flank, each made of five short bars that follow the curve of the barrel and
# stand half out of the hide.
for k, z in enumerate((-11, -5.5, 0, 5.5)):
    rx, ry, cy = at(z)
    for side in (-1, 1):
        edges = [0.8 + 0.32 * j for j in range(6)]
        for j in range(5):
            a0, a1 = side * edges[j], side * edges[j + 1]
            x0, y0 = rx * math.sin(a0), cy - ry * math.cos(a0)
            x1, y1 = rx * math.sin(a1), cy - ry * math.cos(a1)
            mx, my = (x0 + x1) / 2, (y0 + y1) / 2
            ang = (a0 + a1) / 2
            nx, ny = ry * math.sin(ang), -rx * math.cos(ang)
            ln = math.hypot(nx, ny)
            roll = math.atan2(nx / ln, -ny / ln)
            w = math.hypot(x1 - x0, y1 - y0) * 1.08
            body_parts.append(Part("rib%d_%s%d" % (k, "l" if side > 0 else "r", j), pivot=rel(mx + nx / ln * 0.5, my + ny / ln * 0.5, z),
                                   rotation=(0, 0, roll), boxes=[Box((-w / 2, -0.35, -0.8), (w, 0.9, 1.6), rib_brass)]))

# The spine: brass vertebrae standing out of the moss as backward-leaning spines that narrow to a point.
for k, z in enumerate(range(-16, 22, 4)):
    rx, ry, cy = at(z)
    top = cy - ry
    tall = 1.0 + 0.5 * (k % 2) + (0.4 if -10 < z < 6 else 0)
    boxes, y = [], 0.6
    for wk, hk in ((3.0, 1.4), (2.1, 1.2), (1.3, 1.0), (0.6, 0.9)):
        h = hk * tall
        boxes.append(Box((-wk / 2, -y - h, -wk * 0.45), (wk, h, wk * 0.9), brass))
        y += h
    body_parts.append(Part("vertebra_%d" % k, pivot=rel(0, top + 0.9, z), rotation=(-0.4, 0, 0), boxes=boxes))

# The neck: a short thick ring from the chest to the back of the head.
for part in ring("neck", -29, -20.5, (9.0, 9.5, -26), (9.8, 10.5, -27.5), 12, hide_for):
    px, py, pz = part.pivot
    part.pivot = rel(px, py, pz)
    body_parts.append(part)

# ---- The head: a skull, a great domed muzzle and a heavy jaw, built of rings like the body. ----


def horn(label, side):
    first, last = chain("horn_" + label, (side * 5.6, -6.2, -4.0), (0.35, side * 1.85, 0),
                        [(6.0, 3.8, 0, 0, 0), (5.2, 3.2, 0.26, side * 0.3, 0), (4.6, 2.5, 0.28, side * 0.3, 0),
                         (4.0, 1.8, 0.3, side * 0.2, 0), (3.2, 1.2, 0.32, 0, 0), (2.2, 0.6, 0.3, 0, 0), (1.4, 0.28, 0.25, 0, 0)],
                        [brass, horn_style, horn_style, horn_style, horn_tip])
    return first


def tusk_piece(label, side):
    first, last = chain("tusk_" + label, (side * 8.3, -0.2, -15.2), (1.25, 0, side * 0.35),
                        [(3.4, 2.0, 0, 0, 0), (3.0, 1.6, -0.35, 0, side * 0.08), (2.6, 1.2, -0.38, 0, 0), (2.0, 0.8, -0.4, 0, 0),
                         (1.4, 0.45, -0.35, 0, 0), (0.9, 0.2, -0.3, 0, 0)],
                        [tusk])
    return first


def nostrils(p, f, opts):
    if f.side != "top":
        head_hide(p, f, opts)
        return
    for v in range(f.h):
        for u in range(f.w):
            x, y, z = canon(f, u, v)
            r = math.hypot((x - 0.5) * 1.4, z - 0.5)
            p.put(f, u, v, (16, 10, 8) if r < 0.3 else shade(MUD_DARK, p.noise(0.06)))


def lid(p, f, opts):
    """The mouth seen from above: a dark red tongue between rows of blunt grinding teeth."""
    if f.side != "top":
        head_hide(p, f, opts)
        return
    mouth(p, f, opts)


jaw = Part("jaw", pivot=(0, 4.5, -4), rotation=(0.06, 0, 0), children=ring(
    "jaw_p", -17.6, 1.0, (7.4, 3.8, 0), (7.8, 5.0, 0), 12, hide_for, keep=lambda a: math.cos(a) < 0.01) + [
    Part("jaw_tip", pivot=(0, 0, -17.6), boxes=[Box((-7.2, -0.3, -0.9), (14.4, 3.8, 1.2), head_hide)]),
    Part("tongue", pivot=(0, -0.3, 0), boxes=[Box((-7.0, 0, -17.2), (14.0, 0.6, 17.6), lid)]),
    tusk_piece("r", -1), tusk_piece("l", 1),
])

head = Part("head", pivot=rel(0, -25.5, -28), children=ring("skull", -10.8, 2.0, (7.4, 7.0, -1.5), (7.0, 7.4, -1.0), 12, hide_for) +
    ring("muzzle", -21.0, -9.6, (7.6, 7.2, 4.5), (8.2, 9.6, 4.5), 12, hide_for, keep=lambda a: math.cos(a) > -0.01) + [
    jaw,
    horn("r", -1), horn("l", 1),
    Part("ear_r", pivot=(-6.6, -5.8, 0.5), rotation=(0.2, -0.5, -0.7), boxes=[Box((-3.6, -1, -0.5), (3.6, 2.4, 1), head_hide)]),
    Part("ear_l", pivot=(6.6, -5.8, 0.5), rotation=(0.2, 0.5, 0.7), boxes=[Box((0, -1, -0.5), (3.6, 2.4, 1), head_hide)]),
], boxes=[
    Box((-7.6, -2.8, -21.7), (15.2, 7.4, 1.0), muzzle),
    Box((-5.2, -3.6, -20.6), (3.4, 1.4, 3.0), nostrils),
    Box((1.8, -3.6, -20.6), (3.4, 1.4, 3.0), nostrils),
    Box((-7.8, -8.0, -12.2), (15.6, 2.6, 3.4), head_hide),
    Box((-7.0, -6.0, -11.0), (1.5, 1.2, 1.6), ember_eye, flat=True),
    Box((5.5, -6.0, -11.0), (1.5, 1.2, 1.6), ember_eye, flat=True),
])

# ---- The cedar tail: five pieces of trunk, each with branch-like spines carrying sprays of needles. ----


def branch(name, x, y, z, rot, length):
    return Part(name, pivot=(x, y, z), rotation=rot, boxes=[
        Box((-0.55, -0.55, 0), (1.1, 1.1, length * 0.6), bark), Box((-0.3, -0.3, length * 0.6), (0.6, 0.6, length * 0.4), bark)], children=[
        Part(name + "_spray", pivot=(0, -0.2, length * 0.2), rotation=(0.08, 0, 0),
             boxes=[Box((-length * 0.45, -0.15, 0), (length * 0.9, 0.3, length * 1.0), needles)])])


TAIL = [(7.0, 8.0, -0.55), (6.5, 6.6, 0.12), (6.0, 5.4, 0.14), (5.5, 4.2, 0.16), (5.0, 3.0, 0.2)]
rnd = _random.Random(40)
tail_parent = None
tail_first = None
for k, (length, w, pitch) in enumerate(TAIL):
    kids = []
    for j, side in enumerate((-1, 1)):
        a = side * (0.9 + 0.2 * rnd.random())
        kids.append(branch("tail%d_branch_%d" % (k, j), side * w * 0.4, -w * 0.3, length * (0.35 + 0.25 * j),
                           (0.45 + 0.2 * rnd.random(), a, 0), 5.0 - k * 0.5))
    kids.append(branch("tail%d_branch_up" % k, 0, -w * 0.45, length * 0.6, (0.9, 0, 0), 4.4 - k * 0.4))
    piece = Part("tail_%d" % k, pivot=rel(0, -30, 21.5) if k == 0 else (0, 0, TAIL[k - 1][0] * 0.92), rotation=(pitch, 0, 0),
                 boxes=[Box((-w / 2, -w / 2, 0), (w, w, length), bark)],
                 children=[Part("tail_%d_bevel" % k, rotation=(0, 0, math.pi / 4),
                                boxes=[Box((-w * 0.39, -w * 0.39, 0.1), (w * 0.78, w * 0.78, length - 0.2), bark)])] + kids)
    if tail_parent is None:
        tail_first = piece
    else:
        tail_parent.children.append(piece)
    tail_parent = piece
tail_parent.children.append(Part("tail_tuft", pivot=(0, 0, TAIL[-1][0] * 0.9), children=[
    Part("tail_tuft_%d" % i, rotation=(0.3 * math.cos(i * 2.1), 0, i * math.pi / 3),
         boxes=[Box((-2.2, -0.15, 0), (4.4, 0.3, 6), needles)]) for i in range(3)]))

# ---- The legs: pillars like bars of iron, plated at the knee, with pointed nails. ----


def leg(label, x, z, body_relative):
    knee = Part("shin_" + label, pivot=(0, 9.5, 0), children=[
        pillar("shin_" + label + "_col", 0, 0, 0, 9.4, 7.2, leg_hide),
        Part("knee_" + label, pivot=(0, 0.2, -4.4), rotation=(-0.18, 0, 0), boxes=[
            Box((-4.4, -2.6, -1.2), (8.8, 5.0, 1.6), brass),
            Box((-3.0, -3.6, -1.5), (6.0, 1.4, 1.4), brass)]),
        Part("band_" + label, pivot=(0, 4.0, 0), boxes=[Box((-5.0, 0, -5.0), (10, 1.3, 10), iron)]),
        Part("foot_" + label, pivot=(0, 6.0, 0), boxes=[Box((-5.6, 0, -5.8), (11.2, 2.0, 11.4), hoof)], children=[
            Part("nail_%s_%d" % (label, i), pivot=(i * 3.2, 0.4, -5.6), boxes=[
                Box((-1.2, 0, -1.2), (2.4, 1.6, 1.4), nail), Box((-0.8, 0.5, -1.9), (1.6, 1.1, 0.8), nail),
                Box((-0.4, 1.0, -2.5), (0.8, 0.6, 0.7), nail)]) for i in (-1, 0, 1)]),
    ])
    thigh = pillar("leg_" + label, *(rel(x, -18.5, z) if body_relative else (x, -18.5, z)), 10.6, 11, leg_hide, children=[
        Part("thigh_band_" + label, pivot=(0, 7.5, 0), boxes=[Box((-6.0, 0, -6.0), (12, 1.4, 12), iron)]),
        knee,
    ])
    knee.pivot = (0, 10.5, 0)
    return thigh


body = Part("body", pivot=HIPS, children=body_parts + [head, tail_first, leg("front_right", -9.0, -13, True), leg("front_left", 9.0, -13, True)])

figure = Part("figure", pivot=(0, 24, 12), children=[body, leg("back_right", -9.0, 13, False), leg("back_left", 9.0, 13, False)])

if __name__ == "__main__":
    build("Behemoth", "behemoth", [figure], size=384, seed=40, density=4)
