"""The casino bouncer's look: a black suit, a white shirt with a black tie, dark glasses and an earpiece.

    python3 -I tools/casino/bouncer.py [preview OUT.png]

Writes the profession layer for the vanilla villager model (and the zombie villager), and the clothes for the human villagers
(MCA layout, a player skin 64x64) under human/clothing/neutral/casino_bouncer. Drawn texel by texel in code, own work.
"""
import os
import random
import sys

from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MAIN = os.path.join(ROOT, "src", "main", "resources", "assets", "mobtalk", "textures", "entity")
HUMAN = os.path.join(ROOT, "src", "client", "resources", "assets", "mobtalk", "human", "clothing", "neutral", "casino_bouncer")

SUIT = (28, 28, 33)
SUIT_HI = (52, 52, 60)
SUIT_LO = (16, 16, 20)
SHIRT = (236, 236, 232)
TIE = (12, 12, 14)
LENS = (14, 16, 22)
GLINT = (150, 176, 210)
FRAME = (40, 40, 46)
SHOE = (10, 10, 12)
SHOE_HI = (70, 70, 78)
EAR = (210, 210, 214)


def put(img, x, y, c):
    img.putpixel((x, y), tuple(c[:3]) + (255,))


def fill(img, x0, y0, x1, y1, r, base=SUIT, amount=0.07):
    for y in range(y0, y1):
        for x in range(x0, x1):
            k = 1 + r.uniform(-amount, amount)
            put(img, x, y, tuple(max(0, min(255, int(v * k))) for v in base))


def suit_front(img, x0, y0, w, h, rows_shirt=4, buttons=True):
    """A jacket front w texels wide from (x0, y0): lapels, a shirt V, a tie, two buttons."""
    mid = x0 + w // 2
    for dy in range(rows_shirt):
        half = max(1, rows_shirt - dy) if w >= 8 else 1
        for x in range(mid - half, mid + half):
            put(img, x, y0 + dy, SHIRT)
        put(img, mid - half - 1, y0 + dy, SUIT_HI)  # lapels
        put(img, mid + half, y0 + dy, SUIT_HI)
    for dy in range(0, rows_shirt + 3):  # the tie: knot, then a long blade
        put(img, mid - 1, y0 + dy, TIE)
        put(img, mid, y0 + dy, TIE)
    put(img, mid - 1, y0, (40, 40, 44))
    if buttons:
        for dy in (rows_shirt + 4, rows_shirt + 7):
            if dy < h:
                put(img, mid - 1, y0 + dy, (60, 60, 66))
    for y in range(y0, y0 + h):  # the jacket's opening below the tie
        if y >= y0 + rows_shirt + 3:
            put(img, mid, y, SUIT_LO)


def villager_layer():
    """The vanilla villager model (VillagerResemblingModel): body 16,20 8x12x6; robe 0,38 8x20x6; arms 44,22 4x8x4 and 40,38 8x4x4;
    legs 0,22 4x12x4; head 0,0 8x10x8 (front face 8..16 x 8..18, the eyes on rows 13-14)."""
    r = random.Random("bouncer")
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    fill(img, 16, 20, 44, 38, r)  # body
    for x in range(22, 44):  # top and bottom of the body only in their real cells
        for y in range(20, 26):
            if not (22 <= x < 38):
                img.putpixel((x, y), (0, 0, 0, 0))
    for x in range(16, 22):
        for y in range(20, 26):
            img.putpixel((x, y), (0, 0, 0, 0))
    suit_front(img, 22, 26, 8, 12)
    fill(img, 0, 38, 28, 64, r)  # robe = the long jacket
    for y in range(38, 44):
        for x in list(range(0, 6)) + list(range(22, 28)):
            img.putpixel((x, y), (0, 0, 0, 0))
    suit_front(img, 6, 44, 8, 20)
    for x in range(6, 14):  # the hem: a darker line and polished shoes peeping out
        put(img, x, 62, SUIT_LO)
        put(img, x, 63, SHOE if x % 3 else SHOE_HI)
    for x in list(range(0, 6)) + list(range(14, 28)):
        put(img, x, 63, SHOE)
    fill(img, 0, 22, 16, 38, r)  # legs: trousers
    for y in range(22, 26):
        for x in list(range(0, 4)) + list(range(12, 16)):
            img.putpixel((x, y), (0, 0, 0, 0))
    for x in range(0, 16):
        put(img, x, 36, SHOE_HI if x % 4 == 1 else SHOE)
        put(img, x, 37, SHOE)
    fill(img, 44, 22, 60, 34, r)  # upper arms: sleeves
    for y in range(22, 26):
        for x in list(range(44, 48)) + list(range(56, 60)):
            img.putpixel((x, y), (0, 0, 0, 0))
    fill(img, 40, 38, 64, 46, r)  # the crossed forearms: sleeves, white cuffs, bare hands in the middle of the front
    for y in range(38, 42):
        for x in list(range(40, 44)) + list(range(60, 64)):
            img.putpixel((x, y), (0, 0, 0, 0))
    for y in range(42, 46):
        for x in range(46, 50):
            img.putpixel((x, y), (0, 0, 0, 0))
        put(img, 45, y, SHIRT)
        put(img, 50, y, SHIRT)
    # dark glasses over the eyes, temples along the sides, an earpiece with its curly wire on the right
    for x in range(9, 15):
        put(img, x, 13, FRAME)
    for x in (9, 10, 12, 13):
        put(img, x, 14, LENS)
    for x in (9, 10, 12, 13):
        put(img, x, 13, LENS)
    put(img, 11, 13, FRAME)
    put(img, 8, 13, FRAME)
    put(img, 15, 13, FRAME)
    put(img, 14, 14, LENS)
    put(img, 9, 13, GLINT)
    put(img, 12, 13, GLINT)
    for x in range(5, 8):
        put(img, x, 13, FRAME)
    for x in range(16, 19):
        put(img, x, 13, FRAME)
    put(img, 3, 14, EAR)
    put(img, 3, 15, EAR)
    put(img, 2, 16, EAR)
    put(img, 3, 17, EAR)
    return img


def human_clothes(variant):
    """MCA's clothes on a player skin layout: body 16,20 (front 20..28 x 20..32), arms 40,16 / 32,48, legs 0,16 / 16,48, the head
    left bare except the glasses on the hat layer (front 40..48 x 8..16, the eyes on rows 12-14)."""
    r = random.Random("bouncer-human-%d" % variant)
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    # body base 16..40 x 16..32 (top/bottom row 16..20 only in their cells)
    fill(img, 16, 20, 40, 32, r)
    fill(img, 20, 16, 36, 20, r)
    suit_front(img, 20, 20, 8, 12, rows_shirt=4)
    # arms: right 40..56 x 16..32, left 32..48 x 48..64; the last two rows stay bare (hands), with a white cuff above them
    for (ax, ay) in ((40, 16), (32, 48)):
        fill(img, ax, ay + 4, ax + 16, ay + 14, r)
        fill(img, ax + 4, ay, ax + 12, ay + 4, r)
        for x in range(ax, ax + 16):
            put(img, x, ay + 13, SHIRT)
        for y in range(ay, ay + 4):  # the hand's underside cell stays bare
            for x in range(ax + 8, ax + 12):
                img.putpixel((x, y), (0, 0, 0, 0))
    # legs: right 0..16 x 16..32, left 16..32 x 48..64; trousers with a crease, black shoes
    for (lx, ly) in ((0, 16), (16, 48)):
        fill(img, lx, ly + 4, lx + 16, ly + 16, r)
        fill(img, lx + 4, ly, lx + 12, ly + 4, r)
        for y in range(ly + 4, ly + 13):
            put(img, lx + 6, y, SUIT_HI)
        for x in range(lx, lx + 16):
            put(img, x, ly + 14, SHOE_HI if x % 4 == 1 else SHOE)
            put(img, x, ly + 15, SHOE)
        for x in range(lx + 8, lx + 12):
            for y in range(ly, ly + 4):
                put(img, x, y, SHOE)
    # jacket layer (overlay) on the body: 16..40 x 32..48, a little wider than the body: the jacket's skirt and shoulders
    fill(img, 16, 36, 40, 48, r)
    fill(img, 20, 32, 36, 36, r)
    for dy in range(12):  # open front: the shirt's V and the tie show through the jacket
        half = max(1, 4 - dy)
        for x in range(24 - half, 24 + half):
            img.putpixel((x, 36 + dy), (0, 0, 0, 0))
        if dy < 7:
            put(img, 24 - half - 1, 36 + dy, SUIT_HI)
            put(img, 24 + half, 36 + dy, SUIT_HI)
    # glasses on the hat layer
    hx, hy = 40, 8
    for x in range(hx, hx + 8):
        put(img, x, hy + 4, FRAME)
    for x in (1, 2, 5, 6):
        put(img, hx + x, hy + 4, LENS)
        put(img, hx + x, hy + 5, LENS)
    put(img, hx + 1, hy + 4, GLINT)
    put(img, hx + 5, hy + 4, GLINT)
    for x in range(36, 40):  # temples on the right side of the hat layer (32..40), front edge at 40
        put(img, x, hy + 4, FRAME)
    for x in range(48, 52):  # and on the left side (48..56), front edge at 48
        put(img, x, hy + 4, FRAME)
    put(img, 35, hy + 5, EAR)  # earpiece
    put(img, 35, hy + 6, EAR)
    put(img, 34, hy + 7, EAR)
    if variant == 1:  # a second suit: charcoal with a dark red tie
        for y in range(64):
            for x in range(64):
                p = img.getpixel((x, y))
                if p[3] and p[:3] == TIE:
                    img.putpixel((x, y), (110, 14, 20, 255))
    return img


def main():
    v = villager_layer()
    for kind in ("villager", "zombie_villager"):
        d = os.path.join(MAIN, kind, "profession")
        os.makedirs(d, exist_ok=True)
        v.save(os.path.join(d, "casino_bouncer.png"))
    os.makedirs(HUMAN, exist_ok=True)
    for i in range(2):
        human_clothes(i).save(os.path.join(HUMAN, f"{i}.png"))
    if len(sys.argv) > 2 and sys.argv[1] == "preview":
        sheet = Image.new("RGBA", (64 * 8 * 2 + 16, 64 * 8), (90, 90, 96, 255))
        sheet.alpha_composite(v.resize((512, 512), Image.NEAREST), (0, 0))
        sheet.alpha_composite(human_clothes(0).resize((512, 512), Image.NEAREST), (528, 0))
        sheet.save(sys.argv[2])
    print("bouncer textures written")


if __name__ == "__main__":
    main()
