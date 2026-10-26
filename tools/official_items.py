"""Draws the officials' item icons (track «чиновники»): the envelope, the certificate, the passport, the black ledger, the civil-status
certificates (one tint per act) and the справки, 16x16; writes the papers' item models with their custom-model-data looks.

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

def passport():
    """A small booklet in a burgundy cover, its spine darker, a gold ornament (a wreath of dots round a star, no real emblem) and
    a gold line of lettering under it; the pages peek out at the bottom right."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    edge, cover, spine, light = (60, 16, 22), (128, 30, 40), (96, 22, 30), (150, 44, 54)
    gold, dim = (232, 194, 80), (180, 140, 50)
    x0, y0, x1, y1 = 3, 1, 12, 14
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            border = x in (x0, x1) or y in (y0, y1)
            c = edge if border else spine if x == x0 + 1 else light if (y == y0 + 1 and x > x0 + 1) else cover
            img.putpixel((x, y), c + (255,))
    for y in range(y0 + 2, y1): img.putpixel((x1 + 1, y), (236, 230, 214, 255))  # page edges
    img.putpixel((x1 + 1, y1), (200, 194, 178, 255))
    cx, cy = 8, 5
    for dx, dy in ((0, -2), (1, -2), (2, -1), (2, 0), (2, 1), (1, 2), (0, 2), (-1, 2), (-2, 1), (-2, 0), (-2, -1), (-1, -2)):
        img.putpixel((cx + dx, cy + dy), dim + (255,))
    for dx, dy in ((0, -1), (-1, 0), (0, 0), (1, 0), (0, 1)): img.putpixel((cx + dx, cy + dy), gold + (255,))
    for x in range(x0 + 3, x1 - 1): img.putpixel((x, 10), gold + (255,))  # «ПАСПОРТ»
    for x in range(x0 + 4, x1 - 2): img.putpixel((x, 12), dim + (255,))
    return img

def black_ledger():
    """A black ledger book with a red ribbon bookmark and the edges of greenish pages (emerald accounts)."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    edge, cover, light = (14, 14, 18), (34, 32, 40), (56, 52, 64)
    x0, y0, x1, y1 = 2, 2, 12, 13
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            border = x in (x0, x1) or y in (y0, y1)
            img.putpixel((x, y), (edge if border else light if y == y0 + 1 else cover) + (255,))
    for y in range(y0 + 1, y1): img.putpixel((x1 + 1, y), (200, 220, 196, 255))  # pages
    for y in range(y0 + 2, y1, 2): img.putpixel((x1 + 1, y), (90, 190, 120, 255))  # emerald ticks on the edge
    for y in range(y1 - 1, y1 + 3): img.putpixel((9, min(15, y)), (180, 30, 40, 255))  # ribbon
    for x in range(x0 + 3, x1 - 2): img.putpixel((x, 6), (150, 140, 110, 255))  # label
    img.putpixel((x0 + 3, 8), (150, 140, 110, 255)); img.putpixel((x0 + 4, 8), (150, 140, 110, 255))
    return img

def civil_certificate(tint):
    """A certificate on a strict-account blank: a tinted guilloche sheet (wavy lines a shade darker), an ornamental double border,
    a small gold emblem (a star in a wreath, no real arms), the heading, the printed lines and the red series number at the bottom."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    x0, y0, x1, y1 = 2, 1, 13, 14
    edge, wave, frame = shade(tint, 0.62), shade(tint, 0.9), shade(tint, 0.74)
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if x in (x0, x1) or y in (y0, y1): c = edge
            elif x in (x0 + 1, x1 - 1) or y in (y0 + 1, y1 - 1): c = frame if (x + y) % 2 == 0 else tint
            else: c = wave if (x * 2 + y + (x // 3)) % 5 == 0 else tint
            img.putpixel((x, y), c + (255,))
    for x in (x0 + 1, x1 - 1):
        for y in (y0 + 1, y1 - 1): img.putpixel((x, y), (200, 160, 60, 255))  # gold corners
    gold, dim = (222, 182, 70), (170, 130, 50)
    cx, cy = 7, 4
    for dx, dy in ((-1, -1), (1, -1), (-2, 0), (2, 0), (-1, 1), (1, 1)): img.putpixel((cx + dx + (1 if dx > 0 else 0), cy + dy), dim + (255,))
    for dx, dy in ((0, -1), (1, -1), (0, 0), (1, 0), (-1, 0), (2, 0), (0, 1), (1, 1)): img.putpixel((cx + dx, cy + dy), gold + (255,))
    ink, faint = (50, 50, 64), shade(tint, 0.55)
    for x in range(x0 + 3, x1 - 2): img.putpixel((x, 7), ink + (255,))  # «СВИДЕТЕЛЬСТВО»
    for row, (a, b) in ((9, (x0 + 3, x1 - 2)), (10, (x0 + 3, x1 - 3)), (11, (x0 + 3, x1 - 2))):
        for x in range(a, b, 1): img.putpixel((x, row), faint + (255,))
    for x in range(x0 + 6, x1 - 2): img.putpixel((x, y1 - 2), (190, 40, 40, 255))  # series «II-ЛЖ № …»
    return img

def spravka(kind):
    """A справка: a white sheet with the corner stamp of the office (blue lines at the top left), the heading, the printed lines, a
    signature and a round blue seal. The permit has a red heading and a green seal; the notice of no record is greyish with a red
    rectangular stamp «не обнаружено» across it."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    x0, y0, x1, y1 = 3, 1, 12, 14
    sheet = (236, 236, 232) if kind == 'notice' else (248, 247, 242)
    edge, side = (150, 148, 140), shade(sheet, 0.9)
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            border = x in (x0, x1) or y in (y0, y1)
            img.putpixel((x, y), (edge if border else side if x == x1 - 1 else sheet) + (255,))
    blue, light, ink, faint = (50, 80, 180), (120, 150, 225), (60, 60, 72), (160, 160, 170)
    for y in (y0 + 2, y0 + 3, y0 + 4):  # the corner stamp of the office
        for x in range(x0 + 2, x0 + 5 if y != y0 + 3 else x0 + 4): img.putpixel((x, y), (blue if y == y0 + 2 else light) + (255,))
    head = (190, 36, 40) if kind == 'permit' else ink
    for x in range(x0 + 4, x1 - 1): img.putpixel((x, y0 + 6), head + (255,))
    for row, (a, b) in ((y0 + 8, (x0 + 2, x1 - 1)), (y0 + 9, (x0 + 2, x1 - 2)), (y0 + 10, (x0 + 2, x1 - 3))):
        for x in range(a, b): img.putpixel((x, row), faint + (255,))
    for x, y in ((x0 + 2, y1 - 1), (x0 + 3, y1 - 2), (x0 + 4, y1 - 1), (x0 + 5, y1 - 2)): img.putpixel((x, y), ink + (255,))
    seal, glow = ((40, 140, 70), (120, 200, 140)) if kind == 'permit' else (blue, light)
    cx, cy = x1 - 3, y1 - 2
    for dx, dy in ((-1, -1), (0, -1), (1, -1), (-1, 0), (1, 0), (-1, 1), (0, 1), (1, 1)): img.putpixel((cx + dx, cy + dy), seal + (255,))
    img.putpixel((cx, cy), glow + (255,))
    if kind == 'notice':
        red = (200, 40, 40, 255)
        for x in range(x0 + 1, x1): img.putpixel((x, y0 + 7), red); img.putpixel((x, y0 + 11), red)
        for y in range(y0 + 7, y0 + 12): img.putpixel((x0 + 1, y), red); img.putpixel((x1 - 1, y), red)
    return img

envelope().save(os.path.join(OUT, 'envelope.png'))
certificate().save(os.path.join(OUT, 'certificate.png'))
passport().save(os.path.join(OUT, 'passport.png'))
black_ledger().save(os.path.join(OUT, 'black_ledger.png'))
TINTS = {'': (234, 224, 196), '_birth': (200, 228, 190), '_marriage': (242, 208, 216), '_divorce': (202, 216, 238), '_death': (216, 208, 224)}
for suffix, tint in TINTS.items(): civil_certificate(tint).save(os.path.join(OUT, 'civil_certificate' + suffix + '.png'))
for suffix, kind in (('', 'plain'), ('_permit', 'permit'), ('_notice', 'notice')): spravka(kind).save(os.path.join(OUT, 'spravka' + suffix + '.png'))

import json
MODELS = os.path.join(ROOT, 'src/main/resources/assets/mobtalk/models/item')
def model(name, looks):
    """The item model with its custom-model-data looks, and one model per look."""
    body = {"parent": "minecraft:item/generated", "textures": {"layer0": "mobtalk:item/" + name}}
    if looks: body["overrides"] = [{"predicate": {"custom_model_data": n}, "model": "mobtalk:item/" + name + s} for n, s in looks]
    with open(os.path.join(MODELS, name + '.json'), 'w') as f: json.dump(body, f, ensure_ascii=False); f.write('\n')
    for n, s in looks:
        with open(os.path.join(MODELS, name + s + '.json'), 'w') as f:
            json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "mobtalk:item/" + name + s}}, f); f.write('\n')
model('civil_certificate', [(1, '_birth'), (2, '_marriage'), (3, '_divorce'), (4, '_death')])
model('spravka', [(1, '_permit'), (2, '_notice')])
print('ok')
