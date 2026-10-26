"""Draws the officials' item icons (track «чиновники»): the envelope and the certificate, 16x16.

usage: python3 tools/official_items.py <repo root>
"""
import sys, os
from PIL import Image

ROOT = sys.argv[1]
OUT = os.path.join(ROOT, 'src/main/resources/assets/mobtalk/textures/item')

def envelope():
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    edge, body, shadow, flap = (120, 104, 80), (236, 226, 200), (210, 196, 166), (222, 210, 180)
    x0, y0, x1, y1 = 1, 4, 14, 13
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            border = x in (x0, x1) or y in (y0, y1)
            img.putpixel((x, y), edge + (255,) if border else (body if y < y1 - 1 else shadow) + (255,))
    # The flap: a V from the top corners to the middle, slightly darker inside.
    for x in range(x0 + 1, x1):
        depth = min(x - x0, x1 - x)
        for y in range(y0 + 1, min(y1, y0 + 1 + depth)):
            img.putpixel((x, y), flap + (255,))
        if y0 + depth < y1: img.putpixel((x, y0 + depth), shade(edge, 1.15) + (255,))
    # Bottom folds: two faint diagonals from the bottom corners.
    for i in range(1, 4):
        img.putpixel((x0 + i, y1 - i), shade(shadow, 0.92) + (255,)); img.putpixel((x1 - i, y1 - i), shade(shadow, 0.92) + (255,))
    # The wax seal on the flap tip.
    for (x, y), c in {(7, 9): (176, 30, 36), (8, 9): (176, 30, 36), (7, 10): (150, 22, 30), (8, 10): (150, 22, 30), (7, 8): (205, 60, 60)}.items():
        img.putpixel((x, y), c + (255,))
    # A green corner of a banknote... of an emerald peeking out at the top right.
    img.putpixel((11, 3), (60, 200, 110, 255)); img.putpixel((12, 3), (30, 150, 80, 255))
    return img

def shade(c, k): return tuple(max(0, min(255, int(ch * k))) for ch in c)

def certificate():
    """A sheet with a heading, lines of text, a signature and a round blue stamp, its right edge in shade."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    edge, sheet, side = (150, 146, 136), (246, 244, 236), (222, 218, 206)
    x0, y0, x1, y1 = 3, 1, 12, 14
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            border = x in (x0, x1) or y in (y0, y1)
            img.putpixel((x, y), (edge if border else side if x == x1 - 1 else sheet) + (255,))
    ink, faint, stamp, light = (60, 60, 72), (150, 150, 160), (50, 80, 180), (110, 140, 220)
    for x in range(x0 + 3, x1 - 2): img.putpixel((x, y0 + 2), ink + (255,))  # heading «СПРАВКА»
    for row, (a, b) in ((y0 + 4, (x0 + 2, x1 - 1)), (y0 + 6, (x0 + 2, x1 - 2)), (y0 + 8, (x0 + 2, x1 - 3))):
        for x in range(a, b): img.putpixel((x, row), faint + (255,))
    for x, y in ((x0 + 2, y1 - 2), (x0 + 3, y1 - 3), (x0 + 4, y1 - 2), (x0 + 5, y1 - 3)): img.putpixel((x, y), ink + (255,))  # signature
    cx, cy = x1 - 3, y1 - 3
    for dx, dy in ((-1, -1), (0, -1), (1, -1), (-1, 0), (1, 0), (-1, 1), (0, 1), (1, 1)): img.putpixel((cx + dx, cy + dy), stamp + (255,))
    img.putpixel((cx, cy), light + (255,))
    return img

envelope().save(os.path.join(OUT, 'envelope.png'))
certificate().save(os.path.join(OUT, 'certificate.png'))
print('ok')
