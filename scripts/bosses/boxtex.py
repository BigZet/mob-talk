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
        if 0 <= u < face.w and 0 <= v < face.h:
            self.img.putpixel((face.x + u, face.y + v), tuple(color[:3]) + (255,))
            if glow:
                self.glow.putpixel((face.x + u, face.y + v), tuple(glow[:3]) + (255,))

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
    def __init__(self, origin, size, style, mirror=False, dilation=0.0, uv=None):
        self.origin, self.size, self.style, self.mirror, self.dilation, self.uv = origin, size, style, mirror, dilation, uv

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


def build(name, texture, root_parts, size=128, seed=1, glow_needed=True):
    """Writes `<Name>ModelData.java`, `<texture>.png` and, when anything glows, `<texture>_glow.png`."""
    root = Part("__root__", children=root_parts)
    pack(root, size)
    painter = Painter(size, seed)
    for part in root.walk():
        for b in part.boxes:
            for f in faces(b):
                if f.w > 0 and f.h > 0:
                    b.style(painter, f, {})
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
