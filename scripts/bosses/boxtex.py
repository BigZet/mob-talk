"""Original box models and textures for the sacred bosses, drawn by code (GPL-3.0, no outside art).

A boss is a tree of parts; each part has boxes. This module packs every box's net onto one texture, paints each face with a
named style, and writes three things: the Java model layer (`<Name>ModelData.java`), the texture and the glow texture (only
the pixels that shine in the dark: eyes, flames, candles). Run a boss script, e.g. `python scripts/bosses/schemnik.py`.

Model space is Minecraft's: y grows downward, the entity faces -Z (the "front" face of a box net), 16 units = 1 block, and
y = 24 is the ground under a model rendered at scale 1.
"""
import math
import os
import random
import sys
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAVA_DIR = os.path.join(ROOT, "src", "client", "java", "io", "github", "divineblock", "client", "boss")
TEX_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "divineblock", "textures", "entity", "boss")

# The palette: dark cloth, old gold, candlelight, bronze, bone, fire.
BLACK = (18, 16, 20)
NIGHT = (34, 30, 38)
GOLD = (196, 152, 58)
GOLD_LIGHT = (240, 206, 110)
GOLD_DARK = (122, 86, 30)
CANDLE = (255, 214, 120)
FLAME = (255, 140, 30)
EMBER = (200, 50, 20)
BONE = (222, 210, 180)
BRONZE = (146, 98, 52)
PATINA = (74, 132, 112)
WHITE_CLOTH = (206, 200, 186)
BLOOD = (110, 18, 22)
CINNABAR = (170, 40, 30)
SKY_BLUE = (52, 84, 140)


def clamp(v):
    return max(0, min(255, int(v)))


def shade(color, k):
    return tuple(clamp(c * k) for c in color[:3])


def mix(a, b, t):
    return tuple(clamp(a[i] * (1 - t) + b[i] * t) for i in range(3))


class Face:
    """One face of a box net: where it lies on the texture and which side of the box it is."""

    def __init__(self, side, x, y, w, h, box):
        self.side, self.x, self.y, self.w, self.h, self.box = side, x, y, w, h, box


class Painter:
    """Paints faces: `put(face, u, v, color, glow=None)` with u, v inside the face."""

    def __init__(self, size, seed):
        self.size = size
        self.img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        self.glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        self.rand = random.Random(seed)

    def put(self, face, u, v, color, glow=None):
        """Paints a pixel; a colour of None leaves it transparent (feather edges, halos, lace)."""
        if 0 <= u < face.w and 0 <= v < face.h:
            if color is None:
                self.img.putpixel((face.x + u, face.y + v), (0, 0, 0, 0))
                return
            self.img.putpixel((face.x + u, face.y + v), tuple(int(c) for c in color[:3]) + (255,))
            if glow:
                self.glow.putpixel((face.x + u, face.y + v), tuple(int(c) for c in glow[:3]) + (255,))

    def noise(self, k=0.12):
        return 1 + (self.rand.random() - 0.5) * 2 * k


# ---- Styles: each paints a whole face. They get the painter, the face and the box's options. ----

def cloth(base, folds=True, trim=None, trim_width=1):
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                k = p.noise(0.08)
                if folds and f.side in ("front", "back", "left", "right"):
                    k *= 0.86 + 0.14 * math.sin(u * 1.3 + (f.x % 7))
                c = shade(base, k)
                if trim and f.side in ("front", "back", "left", "right") and v >= f.h - trim_width:
                    c = shade(trim, p.noise(0.1))
                p.put(f, u, v, c)
    return paint


def metal(base, dark, light, streaks=None):
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                t = (v / max(1, f.h - 1))
                c = mix(light, dark, t * 0.8)
                c = shade(mix(c, base, 0.5), p.noise(0.1))
                if streaks and p.rand.random() < 0.06 + 0.1 * t:
                    c = mix(c, streaks, 0.6)
                p.put(f, u, v, c)
    return paint


def solid(color, glow=False, k=0.1):
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                c = shade(color, p.noise(k))
                p.put(f, u, v, c, c if glow else None)
    return paint


def fire(glow=True):
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                t = v / max(1, f.h - 1)
                c = mix(CANDLE, EMBER, t) if f.side not in ("top",) else CANDLE
                c = shade(c, p.noise(0.15))
                p.put(f, u, v, c, c if glow else None)
    return paint


def candle():
    """A wax candle; the top face burns."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                if f.side == "top":
                    p.put(f, u, v, CANDLE, CANDLE)
                else:
                    p.put(f, u, v, shade((236, 222, 190), p.noise(0.05)))
    return paint


def gold(trim=True):
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                edge = trim and (u in (0, f.w - 1) or v in (0, f.h - 1))
                c = GOLD_DARK if edge else mix(GOLD_LIGHT, GOLD, (u + v) % 3 / 2)
                p.put(f, u, v, shade(c, p.noise(0.08)))
    return paint


def eyes_face(skin, eye, socket=(10, 8, 8), glow=True):
    """A face on the front side: two burning eyes in dark sockets; the other sides are plain skin."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                p.put(f, u, v, shade(skin, p.noise(0.07)))
        if f.side != "front":
            return
        ey = max(1, f.h // 3)
        for ex in (f.w // 4, f.w - 1 - f.w // 4):
            for du in (-1, 0, 1):
                for dv in (0, 1):
                    p.put(f, ex + du, ey + dv, socket)
            p.put(f, ex, ey, eye, eye if glow else None)
            p.put(f, ex, ey + 1, shade(eye, 0.7), shade(eye, 0.7) if glow else None)
    return paint


def draw_cross(p, f, cx, top, height, color, glow=None):
    """An eight-pointed Orthodox cross centred at cx, from `top` down `height` pixels."""
    for v in range(height):
        p.put(f, cx, top + v, color, glow)
    arm = max(2, height // 3)
    for du in range(-arm, arm + 1):
        p.put(f, cx + du, top + height // 3, color, glow)
    for du in range(-1, 2):
        p.put(f, cx + du, top + 1, color, glow)
    # The slanted footrest.
    for du in range(-arm + 1, arm):
        p.put(f, cx + du, top + (2 * height) // 3 + (1 if du < 0 else -1 if du > 0 else 0), color, glow)


# ---- Model tree. ----

class Box:
    def __init__(self, origin, size, style, mirror=False, dilation=0.0, uv=None, flat=False):
        """`flat` skips the volume shading (halos, painted icons, fire)."""
        self.origin, self.size, self.style, self.mirror, self.dilation, self.uv, self.flat = origin, size, style, mirror, dilation, uv, flat

    def net(self):
        w, h, d = (max(0, int(math.ceil(s))) for s in self.size)
        return 2 * d + 2 * w, d + h


class Part:
    def __init__(self, name, pivot=(0, 0, 0), rotation=(0, 0, 0), boxes=(), children=()):
        self.name, self.pivot, self.rotation = name, pivot, rotation
        self.boxes, self.children = list(boxes), list(children)

    def walk(self):
        yield self
        for child in self.children:
            yield from child.walk()


def pack(root, size):
    """Shelf-packs every box net onto the texture, tallest first."""
    boxes = [b for part in root.walk() for b in part.boxes]
    order = sorted(boxes, key=lambda b: -b.net()[1])
    x = y = shelf = 0
    for b in order:
        w, h = b.net()
        if x + w > size:
            x, y, shelf = 0, y + shelf, 0
        if y + h > size:
            raise SystemExit("texture %d is too small for the model" % size)
        b.uv = (x, y)
        x += w
        shelf = max(shelf, h)


def faces(b):
    u, v = b.uv
    w, h, d = (max(0, int(math.ceil(s))) for s in b.size)
    return [
        Face("top", u + d, v, w, d, b), Face("bottom", u + d + w, v, w, d, b),
        Face("right", u, v + d, d, h, b), Face("front", u + d, v + d, w, h, b),
        Face("left", u + d + w, v + d, d, h, b), Face("back", u + 2 * d + w, v + d, w, h, b),
    ]


def java_float(x):
    s = ("%.4f" % x).rstrip("0").rstrip(".")
    return (s if s not in ("-0", "") else "0") + "F"


def emit_part(lines, part, parent_var, counter):
    var = "p%d" % next(counter)
    builder = "ModelPartBuilder.create()"
    for b in part.boxes:
        builder += ".uv(%d, %d)" % b.uv
        if b.mirror:
            builder += ".mirrored()"
        args = ", ".join(java_float(c) for c in b.origin) + ", " + ", ".join(java_float(c) for c in b.size)
        if b.dilation:
            builder += ".cuboid(%s, new Dilation(%s))" % (args, java_float(b.dilation))
        else:
            builder += ".cuboid(%s)" % args
        if b.mirror:
            builder += ".mirrored(false)"
    px, py, pz = part.pivot
    rx, ry, rz = part.rotation
    transform = "ModelTransform.of(%s, %s, %s, %s, %s, %s)" % tuple(java_float(c) for c in (px, py, pz, rx, ry, rz))
    lines.append("        ModelPartData %s = %s.addChild(\"%s\", %s, %s);" % (var, parent_var, part.name, builder, transform))
    for child in part.children:
        emit_part(lines, child, var, counter)


def build(name, texture, root_parts, size=128, seed=1, glow_needed=True, density=1, light=True):
    """Writes `<Name>ModelData.java`, `<texture>.png` and, when anything glows, `<texture>_glow.png`.

    `size` is the texture size in model units (what the Java model declares); `density` is pixels per unit, so the PNG is
    `size * density` wide and every face gets `density` times more painted detail (Minecraft scales UVs by the declared
    size). `light` shades every face for volume: lit tops, darker undersides, soft occlusion at the edges."""
    root = Part("__root__", children=root_parts)
    pack(root, size)
    painter = Painter(size * density, seed)
    for part in root.walk():
        for b in part.boxes:
            for f in faces(b):
                f = Face(f.side, f.x * density, f.y * density, f.w * density, f.h * density, f.box)
                if f.w > 0 and f.h > 0:
                    b.style(painter, f, {"density": density})
                    if light and not b.flat:
                        relight(painter, f, density)
    os.makedirs(TEX_DIR, exist_ok=True)
    painter.img.save(os.path.join(TEX_DIR, texture + ".png"))
    glows = painter.glow.getbbox() is not None
    if glows or glow_needed:
        painter.glow.save(os.path.join(TEX_DIR, texture + "_glow.png"))
    import itertools
    counter = itertools.count()
    lines = []
    for child in root.children:
        emit_part(lines, child, "root", counter)
    java = (
        "package io.github.divineblock.client.boss;\n\n"
        "import net.minecraft.client.model.*;\n\n"
        "/** Generated by scripts/bosses/%s from its box spec (original model, GPL-3.0). Do not edit by hand. */\n"
        "public final class %sModelData {\n"
        "    private %sModelData() { }\n\n"
        "    @SuppressWarnings(\"unused\")\n"
        "    public static TexturedModelData create() {\n"
        "        ModelData data = new ModelData();\n"
        "        ModelPartData root = data.getRoot();\n"
        "%s\n"
        "        return TexturedModelData.of(data, %d, %d);\n"
        "    }\n"
        "}\n"
    ) % (os.path.basename(sys.argv[0]), name, name, "\n".join(lines), size, size)
    os.makedirs(JAVA_DIR, exist_ok=True)
    with open(os.path.join(JAVA_DIR, name + "ModelData.java"), "w", encoding="utf-8", newline="\n") as out:
        out.write(java)
    print("wrote", name + "ModelData.java,", texture + ".png", "(glow)" if glows else "")


# ---- Volume shading, applied to every face after its style. ----

SIDE_LIGHT = {"top": 1.12, "front": 1.0, "back": 0.9, "left": 0.86, "right": 0.86, "bottom": 0.62}


def relight(p, f, density):
    """Light from above: a vertical gradient on the sides and a darker band of occlusion along the edges."""
    base = SIDE_LIGHT.get(f.side, 1.0)
    edge = max(1, density // 2)
    img = p.img
    for v in range(f.h):
        for u in range(f.w):
            x, y = f.x + u, f.y + v
            r, g, b, a = img.getpixel((x, y))
            if a == 0:
                continue
            k = base
            if f.side in ("front", "back", "left", "right"):
                k *= 1.08 - 0.22 * (v / max(1, f.h - 1))
            d = min(u, v, f.w - 1 - u, f.h - 1 - v)
            if d < edge:
                k *= 0.82 + 0.18 * d / edge
            img.putpixel((x, y), (clamp(r * k), clamp(g * k), clamp(b * k), a))


# ---- Richer styles for detailed models (use with density 4). ----

def gold_leaf(ornament=True):
    """Burnished gold: a warm gradient with leaf seams and, on larger faces, a punched dot border."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                t = (u * 0.6 + v) / max(1, f.w * 0.6 + f.h)
                c = mix(GOLD_LIGHT, GOLD_DARK, t * 0.9)
                if (u // max(1, 3 * d) + v // max(1, 3 * d)) % 5 == 0 and p.rand.random() < 0.3:
                    c = shade(c, 0.9)
                p.put(f, u, v, shade(c, p.noise(0.05)))
        if ornament and f.w >= 6 * d and f.h >= 6 * d:
            m = max(1, d)
            for u in range(m, f.w - m, 2 * m):
                p.put(f, u, m, GOLD_DARK)
                p.put(f, u, f.h - 1 - m, GOLD_DARK)
            for v in range(m, f.h - m, 2 * m):
                p.put(f, m, v, GOLD_DARK)
                p.put(f, f.w - 1 - m, v, GOLD_DARK)
    return paint


def drapery(base, highlight=None, hem=None, hem_width=2):
    """Painted cloth in the icon manner: deep folds with light ridges (пробела), an optional gold-dotted hem."""
    hl = highlight or shade(base, 1.6)

    def paint(p, f, opts):
        d = opts.get("density", 1)
        period = max(3, 3 * d)
        for v in range(f.h):
            for u in range(f.w):
                ph = ((u + 0.3 * v / max(1, d)) % period) / period
                k = 0.72 + 0.4 * math.sin(ph * math.pi)
                c = shade(base, k * p.noise(0.04))
                if f.side in ("front", "back", "left", "right") and 0.46 < ph < 0.54 and p.rand.random() < 0.8:
                    c = mix(c, hl, 0.55)
                if hem and f.side in ("front", "back", "left", "right") and v >= f.h - hem_width * d:
                    c = shade(hem, p.noise(0.08))
                    if (u // max(1, d)) % 3 == 0 and v == f.h - hem_width * d + d // 2:
                        c = GOLD_LIGHT
                p.put(f, u, v, c)
    return paint


def scale_mail(base, rim=None):
    """Overlapping scales, each lit at the top and rimmed at the bottom."""
    rim = rim or GOLD

    def paint(p, f, opts):
        d = opts.get("density", 1)
        sw, sh = max(2, 2 * d), max(2, 2 * d)
        for v in range(f.h):
            for u in range(f.w):
                row = v // sh
                uu = (u + (sw // 2 if row % 2 else 0)) % sw
                vv = v % sh
                arc = abs(uu - sw / 2) / (sw / 2)
                if vv >= sh - 1 - int(arc * (sh - 1) * 0.6):
                    c = shade(rim, 0.9 + 0.2 * (1 - arc))
                else:
                    c = shade(base, 1.2 - 0.5 * vv / sh)
                p.put(f, u, v, shade(c, p.noise(0.05)))
    return paint


def feather(base, tip=None, shaft=None, glow_tip=False):
    """One feather on a thin plane: a pale shaft, barbs, a darker (or burning) tip and ragged transparent edges, so a wing of
    them does not read as boxes. The feather runs along the face's longer side."""
    tip = tip or shade(base, 0.6)
    shaft = shaft or mix(base, (255, 255, 255), 0.6)

    def paint(p, f, opts):
        d = opts.get("density", 1)
        long_side = f.h >= f.w
        L, W = (f.h, f.w) if long_side else (f.w, f.h)
        for a in range(L):
            for b in range(W):
                u, v = (b, a) if long_side else (a, b)
                t = a / max(1, L - 1)
                half = (W - 1) / 2
                taper = 1.0 if t < 0.72 else max(0.12, 1 - (t - 0.72) / 0.28)
                if W > 2 and (abs(b - half) > half * taper + 0.5 or (b in (0, W - 1) and p.rand.random() < 0.35)):
                    p.put(f, u, v, None)
                    continue
                c = mix(base, tip, max(0, t - 0.55) / 0.45)
                if abs(b - half) < max(0.6, d * 0.3):
                    c = shaft
                elif int((a + abs(b - half) * 1.5) // max(1, d)) % 2 == 0:
                    c = shade(c, 0.9)
                g = c if glow_tip and t > 0.8 else None
                p.put(f, u, v, shade(c, p.noise(0.04)), g)
    return paint


def hair(base, light=None):
    """Curls: wavy strands with light catching their crests."""
    light = light or shade(base, 1.7)

    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                w = math.sin((u / max(1, d)) * 2.1 + math.sin(v / max(1, d) * 1.3) * 1.6)
                c = mix(shade(base, 0.7), light, max(0.0, w) ** 3)
                p.put(f, u, v, shade(c, p.noise(0.05)))
    return paint


def icon_face(skin, hair_color=(70, 44, 26), eye=(40, 30, 24), stern=True, glow_eyes=None):
    """A face in the manner of an icon: ochre flesh with light modelling (движки), almond eyes with whites, a long straight
    nose, a small closed mouth and brows; the other sides are hair or skin. Paint at density 4 or more."""
    def paint(p, f, opts):
        if f.side != "front":
            col = hair_color if f.side in ("back", "top") else skin
            for v in range(f.h):
                for u in range(f.w):
                    p.put(f, u, v, shade(col, p.noise(0.05)))
            return
        W, H = f.w, f.h
        for v in range(H):
            for u in range(W):
                dx, dy = (u - W / 2) / (W / 2), (v - H * 0.55) / (H / 2)
                c = shade(skin, 1.08 - 0.35 * dx * dx - 0.1 * max(0.0, dy))
                if (abs(dx) < 0.18 and 0.2 * H < v < 0.62 * H) or (0.38 < abs(dx) < 0.6 and 0.52 * H < v < 0.6 * H):
                    c = mix(c, (255, 236, 200), 0.25)
                p.put(f, u, v, shade(c, p.noise(0.03)))
        for v in range(int(H * 0.18)):
            for u in range(W):
                if v < H * 0.18 - 0.6 * abs(math.sin(u * 0.9)):
                    p.put(f, u, v, shade(hair_color, p.noise(0.08)))
        ey = int(H * 0.42)
        for side in (-1, 1):
            cx = W / 2 + side * W * 0.22
            ew, eh = max(2, int(W * 0.16)), max(1, int(H * 0.06))
            for u in range(int(cx - ew / 2), int(cx + ew / 2) + 1):
                for v in range(ey - eh, ey + eh + 1):
                    if ((u - cx) / (ew / 2)) ** 2 + ((v - ey) / max(1, eh)) ** 2 <= 1.0:
                        p.put(f, u, v, (236, 228, 210))
            iris = glow_eyes or eye
            for u in range(int(cx - eh), int(cx + eh) + 1):
                for v in range(ey - eh, ey + eh + 1):
                    if (u - cx) ** 2 + (v - ey) ** 2 <= eh * eh:
                        p.put(f, u, v, iris, glow_eyes)
            by = ey - eh - max(1, H // 14)
            for u in range(int(cx - ew / 2 - 1), int(cx + ew / 2 + 2)):
                tilt = (u - cx) * side * (0.25 if stern else -0.1)
                p.put(f, u, int(by + tilt), shade(hair_color, 0.8))
        nx = W // 2
        for v in range(int(H * 0.4), int(H * 0.66)):
            p.put(f, nx - 1, v, shade(skin, 0.72))
            p.put(f, nx, v, mix(skin, (255, 236, 200), 0.3))
        for u in (nx - 2, nx + 1):
            p.put(f, u, int(H * 0.66), shade(skin, 0.6))
        my = int(H * 0.77)
        for u in range(nx - max(1, W // 10), nx + max(2, W // 10)):
            p.put(f, u, my, (120, 50, 40))
            p.put(f, u, my + 1, shade(skin, 0.85))
    return paint


def halo_disc(rim=GOLD_DARK, fill=GOLD_LIGHT, glow=True, rays=True):
    """A nimbus on a thin plane: a gold disc with a dark rim, transparent outside the circle, faint radial rays."""
    def paint(p, f, opts):
        W, H = f.w, f.h
        R = min(W, H) / 2
        if R < 2:
            for v in range(H):
                for u in range(W):
                    p.put(f, u, v, rim)
            return
        for v in range(H):
            for u in range(W):
                dx, dy = u + 0.5 - W / 2, v + 0.5 - H / 2
                r = math.hypot(dx, dy)
                if r > R:
                    p.put(f, u, v, None)
                    continue
                c = mix(fill, GOLD, r / R * 0.6)
                if rays and int((math.atan2(dy, dx) + math.pi) / (2 * math.pi) * 24) % 2 == 0:
                    c = shade(c, 0.94)
                inner = r <= R - max(1.0, R * 0.08)
                if not inner:
                    c = rim
                p.put(f, u, v, c, c if glow and inner else None)
    return paint


def flame_blade(glow=True):
    """A blade of fire: a white-hot core, then gold, orange and red licks toward the edges, with a transparent flicker."""
    def paint(p, f, opts):
        W, H = f.w, f.h
        for v in range(H):
            for u in range(W):
                across = abs(u + 0.5 - W / 2) / (W / 2) if W > 1 else 0
                lick = 0.25 * math.sin(v * 0.7 + u) + 0.15 * math.sin(v * 1.9)
                t = min(1.0, across + lick * across)
                if t > 0.95 and p.rand.random() < 0.5:
                    p.put(f, u, v, None)
                    continue
                c = mix((255, 250, 220), CANDLE, min(1.0, t * 2)) if t < 0.5 else mix(FLAME, EMBER, (t - 0.5) * 2)
                p.put(f, u, v, c, c if glow else None)
    return paint


# ---- Builders: organic shapes out of many small rotated boxes. ----

def wing(name, pivot, rotation, span, rows=3, feathers=9, base=WHITE_CLOTH, tip=GOLD, glow_tip=False, side=1):
    """A wing of individual feathers in layers: long primaries fanned toward the tip, shorter secondaries, small coverts
    over the bone. The bone runs along +X from the pivot (`side=-1` builds it along -X for the other wing); feathers hang
    down (+Y) on thin planes. `span` is the bone length in units."""
    prim = feather(base, tip, glow_tip=glow_tip)
    cov = feather(shade(base, 0.96), shade(base, 0.78))
    x0 = 0 if side > 0 else -span
    arm = Part(name, pivot=pivot, rotation=rotation, boxes=[Box((x0, -1.5, -1.5), (span, 3, 3), feather(shade(base, 0.9), shade(base, 0.8)))])
    for row in range(rows):
        length = span * (0.8 - 0.22 * row)
        width = 3.0 - 0.5 * row
        for i in range(feathers):
            t = i / max(1, feathers - 1)
            x = side * span * (0.06 + 0.92 * t)
            fan = side * (t ** 1.6) * 1.1
            l = length * (0.5 + 0.7 * t) if row == 0 else length * (0.6 + 0.35 * t)
            arm.children.append(Part("%s_f%d_%d" % (name, row, i), pivot=(x, 0.5 + row * 0.7, 0.3 - row * 0.35),
                                     rotation=(0.04 * row, 0, -fan),
                                     boxes=[Box((-width / 2, 0, 0), (width, l, 0.2), prim if row == 0 else cov)]))
    return arm


def skirt(name, pivot, top_radius, bottom_radius, height, panels=12, style=None, sway=0.08):
    """A flowing robe: `panels` thin cloth panels around a ring, each tilted outward so the hem widens like a bell."""
    part = Part(name, pivot=pivot)
    style = style or drapery(NIGHT)
    tilt = math.atan2(bottom_radius - top_radius, height)
    width = 2 * math.pi * max(top_radius, bottom_radius) / panels * 1.2
    for i in range(panels):
        a = 2 * math.pi * i / panels
        part.children.append(Part("%s_%d" % (name, i), pivot=(math.sin(a) * top_radius, 0, -math.cos(a) * top_radius),
                                  rotation=(-tilt + sway * math.sin(i * 1.7), -a, 0),
                                  boxes=[Box((-width / 2, 0, -0.4), (width, height + 0.6 * math.sin(i * 2.3), 0.8), style)]))
    return part


def halo(name, pivot, radius, rotation=(0, 0, 0)):
    """A nimbus behind the head: one thin plane painted round and glowing."""
    return Part(name, pivot=pivot, rotation=rotation, boxes=[Box((-radius, -radius, 0), (2 * radius, 2 * radius, 0.1), halo_disc(), flat=True)])


def curls(name, pivot, count, radius, length, color, seed=3):
    """A head of curls: short twisted locks around the back and sides of the head."""
    rnd = random.Random(seed)
    part = Part(name, pivot=pivot)
    for i in range(count):
        a = math.pi * (0.1 + 0.8 * i / max(1, count - 1))
        x, z = math.cos(a) * radius, math.sin(a) * radius
        part.children.append(Part("%s_%d" % (name, i), pivot=(x, -1 + rnd.random() * 2, z),
                                  rotation=(rnd.uniform(-0.3, 0.3), -a + math.pi / 2, rnd.uniform(-0.4, 0.4)),
                                  boxes=[Box((-1.2, 0, -1.2), (2.4, length * rnd.uniform(0.7, 1.1), 2.4), hair(color))]))
    return part
