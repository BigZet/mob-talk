"""The weapons' models and textures, drawn by code on the bosses' library (`scripts/bosses/boxtex.py`, GPL-3.0, no outside art).

A weapon is built upright in model space: its axis runs along Y with the point toward -Y (up), the grip near the origin;
`weapon_build` centres it and writes `<Name>ModelData.java` into the client's `weapon` package and the texture (and its glow)
into `textures/entity/weapon/`. The renderer (`WeaponRenderer`) lays the model on the sword's diagonal, so the vanilla
hand-held transforms fit. Run `python scripts/weapons/all.py` or one weapon's script."""
import math
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "bosses"))
from boxtex import *  # noqa: E402,F401,F403

JAVA_DIR = os.path.join(ROOT, "src", "client", "java", "io", "github", "divineblock", "client", "weapon")
TEX_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "divineblock", "textures", "entity", "weapon")
PACKAGE = "io.github.divineblock.client.weapon"

# Palette, beside the bosses'.
STEEL = (150, 154, 162)
STEEL_DARK = (62, 64, 72)
STEEL_LIGHT = (222, 226, 232)
BLACK_STEEL = (44, 40, 46)
RED_SHEEN = (150, 30, 30)
LEATHER = (104, 66, 38)
LEATHER_DARK = (60, 38, 22)
RED_WRAP = (140, 28, 30)
BLACK_WRAP = (28, 24, 26)
BIRCH = (222, 208, 176)
BIRCH_DARK = (92, 74, 52)
BRICK_WOOD = (150, 72, 46)
BRICK_DARK = (92, 40, 26)
BONE_LIGHT = (238, 230, 204)
BONE_DARK = (176, 160, 128)
CLAY = (160, 108, 72)
CLAY_DARK = (104, 66, 44)
HORN = (196, 166, 118)
HORN_DARK = (110, 84, 52)
COPPER = (200, 118, 70)
COPPER_LIGHT = (240, 170, 120)
PEBBLE = (142, 140, 136)
HOLY_BLUE = (170, 210, 255)


def steel(dark=STEEL_DARK, base=STEEL, light=STEEL_LIGHT, edge=True):
    """A ground steel blade: a bright edge, a darker midrib, fine grinding lines along the length."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                across = abs(u + 0.5 - f.w / 2) / max(1, f.w / 2) if f.w > 1 else 0.5
                c = mix(base, light, across ** 2 * 0.9) if edge else base
                if across < 0.14:
                    c = dark
                c = shade(c, 0.95 + 0.1 * ((v // 2) % 2))
                p.put(f, u, v, shade(c, p.noise(0.04)))
    return paint


def pointed(style, point_ratio=1.4, both=False):
    """Cuts the top (and with `both` the bottom) of a face to a sharp point: a blade's tip, a stake's end. For thin planes:
    the narrow sides are cleared by the library when the broad face has holes."""
    def paint(p, f, opts):
        style(p, f, opts)
        W, H = f.w, f.h
        if f.side not in ("front", "back"):
            return
        point = min(H, int(W * point_ratio))
        half = W / 2
        for v in range(point):
            for u in range(W):
                if abs(u + 0.5 - half) > half * (v / max(1, point)):
                    p.put(f, u, v, None)
        if both:
            for v in range(point):
                for u in range(W):
                    if abs(u + 0.5 - half) > half * (v / max(1, point)):
                        p.put(f, u, H - 1 - v, None)
    return paint


def holy_blade():
    """Steel with a light running down the fuller: pale blue, glowing."""
    base = steel()

    def paint(p, f, opts):
        base(p, f, opts)
        if f.side not in ("front", "back"):
            return
        d = opts.get("density", 1)
        for v in range(int(f.h * 0.08), int(f.h * 0.92)):
            for u in range(f.w):
                if abs(u + 0.5 - f.w / 2) < max(0.6, d * 0.35):
                    c = mix(HOLY_BLUE, (255, 255, 255), 0.4 + 0.3 * math.sin(v * 0.5))
                    p.put(f, u, v, c, c)
    return paint


def war_blade():
    """Blackened steel with a red sheen and notches hacked out of the edges."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                across = abs(u + 0.5 - f.w / 2) / max(1, f.w / 2) if f.w > 1 else 0.5
                c = mix(BLACK_STEEL, STEEL, across ** 2 * 0.7)
                sheen = 0.5 + 0.5 * math.sin(v * 0.35 + u * 0.6)
                c = mix(c, RED_SHEEN, 0.25 * sheen * (1 - across))
                p.put(f, u, v, shade(c, p.noise(0.05)))
        if f.side in ("front", "back") and f.w > 4 * d:
            for v in range(int(f.h * 0.15), int(f.h * 0.85), max(5, 6 * d)):
                side = 0 if (v // max(1, 6 * d)) % 2 else f.w - 1
                for dv in range(max(1, d)):
                    for du in range(max(1, d)):
                        p.put(f, side + (du if side == 0 else -du), v + dv, None)
    return paint


def wrap(base, dark=None, pitch=3):
    """A leather or cloth wrapping round a grip: diagonal windings with a dark seam."""
    dark = dark or shade(base, 0.6)

    def paint(p, f, opts):
        d = opts.get("density", 1)
        period = max(2, pitch * d)
        for v in range(f.h):
            for u in range(f.w):
                ph = ((v + u * 0.5) % period) / period
                c = dark if ph < 0.22 else shade(base, 0.9 + 0.25 * ph)
                p.put(f, u, v, shade(c, p.noise(0.05)))
    return paint


def wood(base, dark, rings=0):
    """Wood with a running grain; `rings` binds it every so many units with dark bark bands."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                k = 0.86 + 0.14 * math.sin(u * 1.9 + math.sin(v * 0.11) * 2.6)
                c = shade(base, k)
                if rings and f.side in ("front", "back", "left", "right") and (v // d) % rings < 2:
                    c = shade(dark, p.noise(0.08))
                p.put(f, u, v, shade(c, p.noise(0.05)))
    return paint


def bone():
    """Old bone: pale with darker pits and cracks."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                c = mix(BONE_LIGHT, BONE_DARK, 0.25 + 0.25 * math.sin(u * 0.7 + v * 0.3))
                if p.rand.random() < 0.05:
                    c = shade(BONE_DARK, 0.75)
                p.put(f, u, v, shade(c, p.noise(0.05)))
    return paint


def clay():
    """Fired clay: warm, with horizontal potter's lines."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                c = mix(CLAY, CLAY_DARK, 0.5 + 0.5 * math.sin(v * 0.9 / max(1, d)) * 0.4)
                p.put(f, u, v, shade(c, p.noise(0.06)))
    return paint


def horn():
    """A ram's horn: ridged rings along the length, darker toward the tip of each face."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                ridge = 0.8 + 0.2 * math.sin(v * math.pi / max(1, 1.5 * d))
                c = shade(mix(HORN, HORN_DARK, 0.3 * (v / max(1, f.h - 1))), ridge)
                p.put(f, u, v, shade(c, p.noise(0.05)))
    return paint


def copper():
    """Polished copper bands."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                c = mix(COPPER_LIGHT, COPPER, 0.3 + 0.7 * (v / max(1, f.h - 1)))
                p.put(f, u, v, shade(c, p.noise(0.05)))
    return paint


def spiral(a, b, pitch=4):
    """Two colours wound round a shaft (the lance of the icons): a diagonal stripe."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        period = max(2, pitch * d)
        for v in range(f.h):
            for u in range(f.w):
                c = a if ((v + u) % period) < period / 2 else b
                p.put(f, u, v, shade(c, p.noise(0.05)))
    return paint


def pebble():
    """A smooth grey stone from the brook."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                c = shade(PEBBLE, 0.9 + 0.2 * math.sin(u * 0.8) * math.sin(v * 0.6))
                p.put(f, u, v, shade(c, p.noise(0.04)))
    return paint


def flame_tongue(glow=True):
    """A small lick of fire on a thin plane: white core, orange edge, torn transparent edges."""
    def paint(p, f, opts):
        W, H = f.w, f.h
        for v in range(H):
            for u in range(W):
                across = abs(u + 0.5 - W / 2) / max(1, W / 2) if W > 1 else 0
                t = v / max(1, H - 1)
                if f.side in ("front", "back") and across > 0.3 + 0.7 * t + 0.15 * math.sin(v * 1.7 + u):
                    p.put(f, u, v, None)
                    continue
                c = mix((255, 250, 220), FLAME, min(1.0, across + 0.5 * (1 - t)))
                p.put(f, u, v, c, c if glow else None)
    return paint


def _bounds(parts):
    lo = [1e9, 1e9, 1e9]
    hi = [-1e9, -1e9, -1e9]

    def walk(part, ox, oy, oz):
        ox, oy, oz = ox + part.pivot[0], oy + part.pivot[1], oz + part.pivot[2]
        for b in part.boxes:
            for i, (o, s) in enumerate(zip((ox + b.origin[0], oy + b.origin[1], oz + b.origin[2]), b.size)):
                lo[i] = min(lo[i], o)
                hi[i] = max(hi[i], o + s)
        for child in part.children:
            walk(child, ox, oy, oz)

    for part in parts:
        walk(part, 0, 0, 0)
    return lo, hi


def weapon_build(name, texture, parts, size=64, seed=1, density=4):
    """Centres the weapon on the origin (rotations ignored: the long parts stand straight) and writes model and textures.
    Prints the weapon's length in model units, for the renderer's scale in `WeaponKind`."""
    lo, hi = _bounds(parts)
    centre = [(a + b) / 2 for a, b in zip(lo, hi)]
    for part in parts:
        part.pivot = tuple(part.pivot[i] - centre[i] for i in range(3))
    build(name, texture, parts, size=size, seed=seed, density=density, glow_needed=False, java_dir=JAVA_DIR, package=PACKAGE, tex_dir=TEX_DIR)
    print("  length %.1f units, width %.1f" % (hi[1] - lo[1], max(hi[0] - lo[0], hi[2] - lo[2])))
