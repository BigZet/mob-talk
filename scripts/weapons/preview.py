"""A quick look at a weapon without the game: draws the box model in three views (front, side, three-quarter) with each face
in the average colour of its texture, painter's order. Run: python scripts/weapons/preview.py cherub_sword [out.png]"""
import math
import os
import runpy
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import weaponlib  # noqa: E402
from boxtex import faces, pack, Part  # noqa: E402


def rot(v, r):
    x, y, z = v
    rx, ry, rz = r
    # Minecraft's order: Z, then Y, then X about the pivot.
    cz, sz = math.cos(rz), math.sin(rz)
    x, y = x * cz - y * sz, x * sz + y * cz
    cy, sy = math.cos(ry), math.sin(ry)
    x, z = x * cy + z * sy, -x * sy + z * cy
    cx, sx = math.cos(rx), math.sin(rx)
    y, z = y * cx - z * sx, y * sx + z * cx
    return (x, y, z)


def world_boxes(parts):
    """Every box's eight corners in model space, with the face colours."""
    out = []

    def walk(part, chain):
        chain = chain + [(part.pivot, part.rotation)]
        for b in part.boxes:
            x0, y0, z0 = b.origin
            w, h, d = b.size
            corners = [(x0 + i * w, y0 + j * h, z0 + k * d) for k in (0, 1) for j in (0, 1) for i in (0, 1)]
            pts = []
            for c in corners:
                p = c
                for pivot, rotation in reversed(chain):
                    p = rot(p, rotation)
                    p = (p[0] + pivot[0], p[1] + pivot[1], p[2] + pivot[2])
                pts.append(p)
            out.append((b, pts))
        for child in part.children:
            walk(child, chain)

    for part in parts:
        walk(part, [])
    return out


# Corner indices: i (x) + 2 j (y) + 4 k (z).
FACES = {
    "front": (0, 1, 3, 2), "back": (4, 5, 7, 6), "right": (0, 2, 6, 4), "left": (1, 3, 7, 5), "top": (0, 1, 5, 4), "bottom": (2, 3, 7, 6),
}


def face_color(img, b, density):
    colors = {}
    for f in faces(b, density):
        if f.w <= 0 or f.h <= 0:
            continue
        total = [0, 0, 0]
        n = 0
        for v in range(f.h):
            for u in range(f.w):
                r, g, bl, a = img.getpixel((f.x + u, f.y + v))
                if a:
                    total[0] += r; total[1] += g; total[2] += bl; n += 1
        colors[f.side] = tuple(c // n for c in total) if n else None
    return colors


def render(parts, img, density, size=320):
    views = [("front", 0.0, 0.0), ("side", math.pi / 2, 0.0), ("three-quarter", 0.7, 0.35)]
    sheet = Image.new("RGBA", (size * len(views), size), (24, 24, 28, 255))
    boxes = world_boxes(parts)
    extent = max(max(abs(c) for c in p) for _, pts in boxes for p in pts) * 1.1 + 1
    scale = size / 2 / extent
    for n, (label, yaw, pitch) in enumerate(views):
        draw = ImageDraw.Draw(sheet)
        quads = []
        for b, pts in boxes:
            colors = face_color(img, b, density)
            view = [rot(rot(p, (0, yaw, 0)), (pitch, 0, 0)) for p in pts]
            for side, idx in FACES.items():
                c = colors.get(side)
                if c is None:
                    continue
                poly = [view[i] for i in idx]
                depth = sum(p[2] for p in poly) / 4
                quads.append((depth, [(n * size + size / 2 + p[0] * scale, size / 2 + p[1] * scale) for p in poly], c))
        for depth, poly, c in sorted(quads, key=lambda q: -q[0]):
            draw.polygon(poly, fill=c + (255,), outline=tuple(int(v * 0.7) for v in c) + (255,))
        draw.text((n * size + 6, 6), label, fill=(200, 200, 200, 255))
    return sheet


if __name__ == "__main__":
    name = sys.argv[1]
    module = runpy.run_path(os.path.join(HERE, name + ".py"), run_name="preview")
    parts = module["parts"]
    density = 4
    root = Part("__root__", children=parts)
    for part in root.walk():
        for b in part.boxes:
            b.snap(density)
    pack(root, 96 if name == "george_spear" else 64)
    tex = Image.open(os.path.join(weaponlib.TEX_DIR, name + ".png")).convert("RGBA")
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, "preview-" + name + ".png")
    render(parts, tex, density).save(out)
    print("wrote", out)
