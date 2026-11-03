"""Bandit skins for Mob Talk: player-format 64x64 skins put together from the MCA Reborn pieces the mod already ships
(third_party/mca-reborn, GPL-3.0): a tinted skin, a face with coloured eyes, clothes and hair, then a knitted balaclava or a flat
cap on top. No hand-drawn bodies: only the headwear is painted here. Writes src/client/resources/assets/mobtalk/textures/entity/bandit/
and, with --preview, a front/back sheet to check by eye.

    python3 tools/bandits/skins.py [--preview out.png]
"""
import os
import random
import sys
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HUMAN = os.path.join(ROOT, "third_party/mca-reborn/src/client/resources/assets/mobtalk/human")
OUT = os.path.join(ROOT, "src/client/resources/assets/mobtalk/textures/entity/bandit")

# skin texture, skin tone (melanin, hemoglobin), face, eye colour, clothes, hair, hair colour (eumelanin, pheomelanin), headwear, knit colour
VARIANTS = [
    (0, (0.30, 0.45), 6, (124, 72, 37), "none/1", None, None, "balaclava", (34, 34, 36)),
    (1, (0.22, 0.55), 2, (76, 120, 70), "none/5", 3, (0.85, 0.3), "cap", (40, 40, 44)),
    (2, (0.40, 0.40), 4, (90, 110, 140), "none/6", None, None, "balaclava", (52, 46, 40)),
    (3, (0.18, 0.60), 8, (124, 72, 37), "leatherworker/2", 5, (0.6, 0.5), "cap", (70, 52, 36)),
    (4, (0.35, 0.50), 1, (58, 90, 140), "none/5", None, None, "balaclava", (24, 26, 30)),
    (0, (0.55, 0.35), 3, (90, 60, 30), "none/1", 7, (0.95, 0.2), "hood", (30, 32, 38)),
]


def load(path):
    return Image.open(os.path.join(HUMAN, path)).convert("RGBA")


def palette(name, u, v):
    image = load("colormap/" + name)
    w, h = image.size
    x = int(min(max(v * (w - 1), 0), w - 1)); y = int(min(max(u * (h - 1), 0), h - 1))
    return image.getpixel((x, y))[:3]


def tint(image, color):
    out = image.copy(); px = out.load()
    for x in range(out.width):
        for y in range(out.height):
            r, g, b, a = px[x, y]
            if a: px[x, y] = (r * color[0] // 255, g * color[1] // 255, b * color[2] // 255, a)
    return out


def eyes(face, iris):
    """MCA's face texture split as HumanTextures does: white sclera as is, the iris tinted, dark details at half grey."""
    out = Image.new("RGBA", face.size); src = face.load(); dst = out.load()
    for x in range(face.width):
        for y in range(face.height):
            r, g, b, a = src[x, y]
            if not a: continue
            lo, hi = min(r, g, b), max(r, g, b)
            if a == 1 or (a == 255 and lo >= 160 and hi - lo <= 32): dst[x, y] = (r, g, b, 255)
            elif hi >= 32: dst[x, y] = (r * iris[0] // 255, g * iris[1] // 255, b * iris[2] // 255, a)
            else: dst[x, y] = (r // 2, g // 2, b // 2, a)
    return out


def knit(color, x, y, rng):
    """Wool: rows of stitches a shade apart and a little noise."""
    shade = 6 if (x + (y // 2)) % 2 == 0 else -4
    shade += rng.randint(-3, 3)
    return tuple(max(0, min(255, c + shade)) for c in color) + (255,)


# Head boxes of a player skin: (u, v) of the 8x8x8 head's net, base layer and hat layer.
HEAD, HAT = (0, 0), (32, 0)


def head_faces(origin):
    u, v = origin
    return {"top": (u + 8, v, 8, 8), "bottom": (u + 16, v, 8, 8), "right": (u, v + 8, 8, 8), "front": (u + 8, v + 8, 8, 8),
            "left": (u + 16, v + 8, 8, 8), "back": (u + 24, v + 8, 8, 8)}


def paint(skin, rect, fn):
    x0, y0, w, h = rect
    px = skin.load()
    for x in range(x0, x0 + w):
        for y in range(y0, y0 + h):
            c = fn(x - x0, y - y0, px[x, y])
            if c is not None: px[x, y] = c


def balaclava(skin, face_layer, color, rng):
    """A knitted mask over the whole head: the hair goes, the eyes stay in a slot two pixels high."""
    # The eyes themselves (white or coloured pixels), not the brows above them.
    eye_rows = sorted({y for x in range(8, 16) for y in range(8, 16)
                       if face_layer.getpixel((x, y))[3] and max(face_layer.getpixel((x, y))[:3]) >= 32})
    slot = set(eye_rows[:2]) if eye_rows else {13, 14}
    for name, rect in head_faces(HEAD).items():
        def fn(x, y, old, name=name):
            if name == "front" and (rect[1] + y) in slot and 1 <= x <= 6: return None
            return knit(color, x, y, rng)
        paint(skin, rect, fn)
    for rect in head_faces(HAT).values(): paint(skin, rect, lambda x, y, old: (0, 0, 0, 0))
    # A rolled rim at the slot's edges, one shade darker, so the opening reads from afar.
    top, bottom = min(slot) - 1, max(slot) + 1
    paint(skin, (9, top, 6, 1), lambda x, y, old: tuple(max(0, c - 14) for c in color) + (255,))
    paint(skin, (9, bottom, 6, 1), lambda x, y, old: tuple(max(0, c - 14) for c in color) + (255,))


def cap(skin, color, rng):
    """A flat cap in the hat layer: the crown and a band all round, the peak a darker band over the brow."""
    faces = head_faces(HAT)
    paint(skin, faces["top"], lambda x, y, old: knit(color, x, y, rng))
    for name in ("front", "left", "right", "back"):
        rows = 3 if name == "front" else 2
        paint(skin, faces[name], lambda x, y, old, rows=rows: knit(color, x, y, rng) if y < rows else old)
    dark = tuple(max(0, c - 18) for c in color) + (255,)
    paint(skin, faces["front"], lambda x, y, old: dark if y == 2 else old)


def hood(skin, color, rng):
    """A tracksuit hood up: over the top, sides and back of the head (hat layer), the face open."""
    faces = head_faces(HAT)
    for name in ("top", "left", "right", "back"): paint(skin, faces[name], lambda x, y, old: knit(color, x, y, rng))
    paint(skin, faces["front"], lambda x, y, old: knit(color, x, y, rng) if y < 2 or x == 0 or x == 7 else (0, 0, 0, 0))


def build(variant, index):
    skin_no, tone, face_no, iris, clothes, hair_no, hair_tone, headwear, wear = variant
    rng = random.Random(index * 7919)
    skin = tint(load(f"skin/male/{skin_no}.png"), palette("skin.png", *tone))
    face = eyes(load(f"face/normal/{face_no}.png"), iris)
    skin.alpha_composite(face)
    skin.alpha_composite(load(f"clothing/male/{clothes}.png"))
    if hair_no is not None and headwear != "balaclava":
        skin.alpha_composite(tint(load(f"hair/male/{hair_no}.png"), palette("hair.png", *hair_tone)))
    if headwear == "balaclava": balaclava(skin, face, wear, rng)
    elif headwear == "cap": cap(skin, wear, rng)
    elif headwear == "hood": hood(skin, wear, rng)
    return skin


def front(skin, back=False):
    """A flat front (or back) view of the skin, 16x32, base and overlay, for the preview."""
    view = Image.new("RGBA", (16, 32))
    parts = [((8, 8), (4, 0), 8, 8, (40, 8)), ((20, 20), (4, 8), 8, 12, (20, 36)), ((44, 20), (0, 8), 4, 12, (44, 36)),
             ((36, 52), (12, 8), 4, 12, (52, 52)), ((4, 20), (4, 20), 4, 12, (4, 36)), ((20, 52), (8, 20), 4, 12, (4, 52))]
    for (u, v), (x, y), w, h, (ou, ov) in parts:
        du = {8: 16, 4: 8}[w] if not back else 0
        if back:
            u2 = u + w + (w if w == 4 else 8); ou2 = ou + w + (w if w == 4 else 8)
            u, ou = u + 2 * w if w == 4 else u + 16, ou + 2 * w if w == 4 else ou + 16
        view.alpha_composite(skin.crop((u, v, u + w, v + h)), (x, y))
        view.alpha_composite(skin.crop((ou, ov, ou + w, ov + h)), (x, y))
    return view


def main():
    os.makedirs(OUT, exist_ok=True)
    skins = []
    for i, variant in enumerate(VARIANTS):
        skin = build(variant, i)
        skin.save(os.path.join(OUT, f"{i}.png"))
        skins.append(skin)
    if "--preview" in sys.argv:
        path = sys.argv[sys.argv.index("--preview") + 1]
        sheet = Image.new("RGBA", (len(skins) * 40, 72), (110, 140, 110, 255))
        for i, skin in enumerate(skins):
            sheet.alpha_composite(front(skin), (i * 40 + 2, 2))
            sheet.alpha_composite(front(skin, back=True), (i * 40 + 20, 2))
            sheet.alpha_composite(skin.crop((0, 0, 64, 16)).resize((32, 8)), (i * 40 + 2, 60))
        sheet.resize((sheet.width * 6, sheet.height * 6), Image.NEAREST).save(path)
    print(f"{len(skins)} skins -> {OUT}")


if __name__ == "__main__":
    main()
