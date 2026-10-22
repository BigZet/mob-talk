"""Generates the resources of Sosnowsky's hogweed: pixel-art textures drawn after the reference photos (rosette of deeply cut
leaves, ribbed stalk with purple blotches, flat white compound umbels, brown seed heads, the bleached dry skeleton), the
multi-part block models, blockstates, loot tables, damage type, world generation JSON and the names in tools/lang.
Original work, GPL-3.0 like the mod.

Run from the repository root: python tools/generate_hogweed.py  (needs Pillow), then python tools/generate_assets.py to merge
the names into the language files. The output is committed; rerun after edits.
"""
import json
import math
import random
from pathlib import Path

from PIL import Image

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent / 'src' / 'main' / 'resources'
ASSETS = ROOT / 'assets' / 'rusflora'
DATA = ROOT / 'data' / 'rusflora'
MC = ROOT / 'data' / 'minecraft'
TEX = ASSETS / 'textures'

CLEAR = (0, 0, 0, 0)
# From the photos: dark glossy leaf, mid, lit, yellow-green young leaf.
GREEN = [(38, 70, 26), (58, 98, 34), (84, 128, 46), (118, 158, 62), (150, 182, 80)]
LATE = [(70, 82, 30), (102, 112, 40), (136, 140, 56), (170, 164, 72), (196, 186, 96)]  # leaves yellowing under ripe seeds
STEM = [(70, 104, 46), (96, 132, 58), (122, 156, 74)]
SPOT = [(104, 52, 86), (132, 70, 104)]  # purple-red blotches on the stalk
FLOWER = [(206, 210, 188), (232, 234, 218), (248, 248, 238)]
RAY = [(96, 128, 62), (126, 154, 80)]
SEED = [(96, 92, 46), (128, 120, 62), (160, 150, 86), (186, 176, 112)]
DRY = [(84, 62, 42), (112, 84, 56), (142, 110, 74), (172, 142, 100), (196, 170, 128)]


def img(size=16):
    return Image.new('RGBA', (size, size), CLEAR)


def put(im, x, y, c):
    w, h = im.size
    if 0 <= x < w and 0 <= y < h:
        im.putpixel((int(x), int(y)), tuple(c) + (255,) if len(c) == 3 else tuple(c))


def line(im, x0, y0, x1, y1, c):
    n = max(abs(x1 - x0), abs(y1 - y0), 1)
    for i in range(int(n) + 1):
        put(im, round(x0 + (x1 - x0) * i / n), round(y0 + (y1 - y0) * i / n), c)


def lobed_leaf(im, cx, cy, ang, length, width, pal, rnd, base_x=None, base_y=None):
    """A deeply cut hogweed leaf blade: a fan of three to five lobes, each a jagged wedge, with a pale midrib.
    ang points from the leaf base to its tip; cx, cy is where the blade starts."""
    if base_x is not None:  # petiole from the plant centre
        line(im, base_x, base_y, cx, cy, STEM[1])
    lobes = rnd.choice([3, 5])
    for k in range(lobes):
        a = ang + (k - (lobes - 1) / 2) * 0.55
        ln = length * (1.0 if k == lobes // 2 else 0.75)
        for t in range(int(ln * 4)):
            d = t / 4
            half = width * math.sin(math.pi * min(1, d / ln + 0.15)) * (0.55 if k != lobes // 2 else 0.7)
            half *= 0.75 + 0.25 * math.sin(d * 2.2 + k)  # toothed outline
            for s in range(-int(half * 2), int(half * 2) + 1):
                o = s / 2
                x = cx + math.cos(a) * d - math.sin(a) * o
                y = cy + math.sin(a) * d + math.cos(a) * o
                shade = 1 + (1 if o < 0 else 0) + (1 if d > ln * 0.6 else 0)
                if abs(o) > half * 0.8:
                    shade = max(0, shade - 1)
                put(im, round(x), round(y), pal[min(shade + rnd.randrange(0, 2), len(pal) - 2)])
        for t in range(int(ln * 0.8)):  # midrib
            put(im, round(cx + math.cos(a) * t), round(cy + math.sin(a) * t), pal[-1])


def seedling():
    im = img()
    rnd = random.Random(1)
    line(im, 8, 15, 8, 11, STEM[1])
    for x0, y0, x1, y1 in [(8, 11, 3, 8), (8, 11, 13, 8)]:  # two narrow cotyledons
        line(im, x0, y0, x1, y1, GREEN[2]); line(im, x0, y0 - 1, x1, y1 - 1, GREEN[3])
    lobed_leaf(im, 8, 9, -math.pi / 2, 4, 2.2, GREEN, rnd, 8, 12)
    return im


def big_leaf(pal, seed, size=32):
    """One whole basal leaf seen face-on, as on the photos of rosettes: a petiole from the bottom edge and a broad blade cut
    into five jagged lobes, a pale midrib and veins running to each lobe tip, the lit side brighter."""
    im = img(size)
    rnd = random.Random(seed)
    cx, cy = size / 2, size * 0.5
    line(im, cx, size - 1, cx, cy, STEM[1]); line(im, cx - 1, size - 1, cx - 1, cy, STEM[0])
    lobes = [(-math.pi / 2, 1.0)] + [(-math.pi / 2 + s * a, r) for a, r in [(0.9, 0.95), (1.75, 0.8)] for s in (-1, 1)]
    radius = size * 0.5
    for x in range(size):
        for y in range(size):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d, ang = math.hypot(dx, dy), math.atan2(dy, dx)
            reach = 0
            for la, lr in lobes:
                diff = abs((ang - la + math.pi) % (2 * math.pi) - math.pi)
                reach = max(reach, lr * radius * max(0, 1 - (diff / 0.95) ** 2))
            reach *= 0.9 + 0.1 * math.sin(ang * 13 + seed)  # serrated edge
            if dy > 0:  # nothing below the leaf base but the petiole
                reach = min(reach, size * 0.05)
            if d < max(reach, size * 0.14):
                lit = (dx < 0) + (d < radius * 0.55) + (rnd.random() < 0.08)
                put(im, x, y, pal[min(1 + lit, len(pal) - 2)])
                if d > reach - 1.2 and d > size * 0.14:
                    put(im, x, y, pal[0])
    for la, lr in lobes:  # veins
        for t in range(int(lr * radius * 0.9)):
            put(im, round(cx + math.cos(la) * t), round(cy + math.sin(la) * t), pal[-1] if t > 2 else pal[-2])
    return im


def hanging_leaf(seed, size=16):
    """A dead leaf shrivelled and hanging down from the stalk, brown and curled."""
    im = img(size)
    rnd = random.Random(seed)
    line(im, 8, 0, 8, 5, DRY[1])
    for y in range(4, 15):
        w = 3.5 * math.sin(math.pi * (y - 4) / 11)
        for x in range(int(8 - w), int(8 + w) + 1):
            if rnd.random() < 0.8:
                put(im, x + (1 if y > 10 else 0), y, DRY[rnd.randrange(0, 4)])
    return im


def dome(petals, rays, seed, size=32):
    """The umbel from the side, for upright planes 26 units wide and 9.75 high (the upper 12 rows of the texture): a flat
    row of small umbellets along the top, the rays fanning down from them to the top of the stalk at the bottom centre."""
    im = img(size)
    rnd = random.Random(seed)
    rows = 12
    heads = [(1.5 + k * (size - 3) / 10, 2.2 + abs(k - 5) * 0.18) for k in range(11)]
    for i, (hx, hy) in enumerate(heads):
        line(im, size / 2, rows - 1, hx, hy + 1, rays[i % 2])
    for hx, hy in heads:
        for x in range(int(hx - 2), int(hx + 3)):
            for y in range(int(hy - 2), int(hy + 3)):
                if math.hypot((x + 0.5 - hx) / 1.3, y + 0.5 - hy) < 1.7 and rnd.random() < 0.9:
                    put(im, x, y, petals[2 if y < hy else 1 if rnd.random() < 0.6 else 0])
    return im


def stem(colours, spots):
    """Thick ribbed stalk: vertical ribs, purple-red blotches on the living one, bleached streaks on the dead one."""
    im = img()
    rnd = random.Random(5)
    for x in range(16):
        for y in range(16):
            put(im, x, y, colours[1] if x % 3 else colours[0])
            if rnd.random() < 0.08:
                put(im, x, y, colours[2])
    if spots:
        for _ in range(9):
            x, y = rnd.randrange(16), rnd.randrange(16)
            for a, b in [(0, 0), (1, 0), (0, 1)]:
                put(im, (x + a) % 16, (y + b) % 16, spots[rnd.randrange(2)])
    return im


def umbel_top(petals, rays, seed, size=32, fill=0.92, head=1.0):
    """A compound umbel from above: a ring of umbellets (small round heads) around a few inner ones, gaps between them.
    A dead umbel has smaller, sparser heads (fill, head) so its spokes show."""
    im = img(size)
    rnd = random.Random(seed)
    c = size / 2
    heads = [(c, c, 2.4)]
    for ring, count, r in [(size * 0.2, 6, 2.6), (size * 0.38, 12, 2.9)]:
        for i in range(count):
            a = i * 2 * math.pi / count + ring
            heads.append((c + math.cos(a) * ring, c + math.sin(a) * ring, r * head))
    for hx, hy, r in heads:
        line(im, c, c, hx, hy, rays[0])
    for hx, hy, r in heads:
        for x in range(int(hx - r - 1), int(hx + r + 2)):
            for y in range(int(hy - r - 1), int(hy + r + 2)):
                d = math.hypot(x + 0.5 - hx, y + 0.5 - hy)
                if d < r and rnd.random() < fill:
                    put(im, x, y, petals[2 if d < r * 0.5 else 1 if rnd.random() < 0.6 else 0])
    return im


def seeds_item():
    im = img()
    for cx, cy in [(5, 9), (10, 6), (10, 12)]:
        for x in range(cx - 2, cx + 3):
            for y in range(cy - 3, cy + 4):
                if ((x - cx) / 2.5) ** 2 + ((y - cy) / 3.5) ** 2 < 1:
                    put(im, x, y, SEED[2] if x != cx else SEED[0])
        put(im, cx - 1, cy - 1, SEED[3])
    return im


def stalk_item():
    im = img()
    for i in range(12):
        x, y = 2 + i, 13 - i
        for a, b in [(0, 0), (1, 0), (0, 1), (1, 1)]:
            put(im, x + a, y + b, STEM[1] if (a + b) % 2 else STEM[2])
        if i % 4 == 1:
            put(im, x, y + 1, SPOT[0])
    for a, b in [(13, 1), (14, 1), (14, 2), (13, 2)]:  # hollow end
        put(im, a, b, (40, 56, 30))
    put(im, 12, 1, STEM[2]); put(im, 15, 2, STEM[0])
    return im


def effect_icon(burn):
    im = img(18)
    if burn:  # a sun over a blistered patch of skin
        for a in range(8):
            ang = a * math.pi / 4
            line(im, 9 + math.cos(ang) * 4, 6 + math.sin(ang) * 4, 9 + math.cos(ang) * 6, 6 + math.sin(ang) * 6, (250, 200, 60))
        for x in range(7, 12):
            for y in range(4, 9):
                if math.hypot(x - 9, y - 6) < 2.6:
                    put(im, x, y, (255, 222, 90))
        for x in range(2, 16):
            for y in range(12, 17):
                put(im, x, y, (222, 120, 96) if (x + y) % 3 else (196, 86, 70))
        for x, y in [(5, 13), (10, 14), (13, 12)]:
            put(im, x, y, (250, 236, 190)); put(im, x + 1, y, (240, 214, 170))
    else:  # a drop of greenish sap
        for x in range(4, 14):
            for y in range(2, 16):
                if (y > 8 and math.hypot(x - 8.5, y - 11) < 4.2) or (y <= 8 and abs(x - 8.5) < (y - 2) * 0.6):
                    put(im, x, y, (176, 196, 80) if x < 9 else (140, 162, 58))
        put(im, 7, 10, (226, 238, 150))
    return im


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def save(im, name):
    path = TEX / f'{name}.png'
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path)


# ---- Model elements ----

def cross(texture, y0=0, y1=16):
    """The two diagonal planes of a vanilla cross model, optionally only between y0 and y1."""
    uv = [0, 16 - y1, 16, 16 - y0]
    faces = {'north': {'uv': uv, 'texture': texture}, 'south': {'uv': uv, 'texture': texture}}
    faces_x = {'east': {'uv': uv, 'texture': texture}, 'west': {'uv': uv, 'texture': texture}}
    return [{'from': [0.8, y0, 8], 'to': [15.2, y1, 8], 'rotation': {'origin': [8, 8, 8], 'axis': 'y', 'angle': 45, 'rescale': True},
             'shade': False, 'faces': faces},
            {'from': [8, y0, 0.8], 'to': [8, y1, 15.2], 'rotation': {'origin': [8, 8, 8], 'axis': 'y', 'angle': 45, 'rescale': True},
             'shade': False, 'faces': faces_x}]


def plane(texture, y, lo, hi, angle=0, at=(8, 8), under=None):
    """A flat horizontal plane at height y, lo to hi around the point at, seen from above and (with under, as a different
    texture) from below."""
    x, z = at[0] - 8, at[1] - 8
    el = {'from': [lo + x, y, lo + z], 'to': [hi + x, y, hi + z], 'shade': False,
          'faces': {'up': {'uv': [0, 0, 16, 16], 'texture': texture}, 'down': {'uv': [0, 16, 16, 0], 'texture': under or texture}}}
    if angle:
        el['rotation'] = {'origin': [8 + x, y, 8 + z], 'axis': 'y', 'angle': angle}
    return el


def leaf(texture, side, tilt, width, height, y0=0, out=1.0, uv=(0, 0, 16, 16)):
    """One leaf: an upright plane whose foot stands out blocks from the stalk on the given side (n, s, e or w) and whose top
    leans tilt degrees away from it; a negative height hangs it down from y0. Model elements turn by 22.5 or 45 degrees
    around one axis only."""
    uvl = list(uv)
    lo, hi = (y0, y0 + height) if height > 0 else (y0 + height, y0)
    if height < 0:
        tilt = -tilt
    if side in 'ns':
        z = 8 + (out if side == 's' else -out)
        el = {'from': [8 - width / 2, lo, z], 'to': [8 + width / 2, hi, z],
              'rotation': {'origin': [8, y0, z], 'axis': 'x', 'angle': tilt if side == 's' else -tilt},
              'faces': {'north': {'uv': uvl, 'texture': texture}, 'south': {'uv': uvl, 'texture': texture}}}
    else:
        x = 8 + (out if side == 'e' else -out)
        el = {'from': [x, lo, 8 - width / 2], 'to': [x, hi, 8 + width / 2],
              'rotation': {'origin': [x, y0, 8], 'axis': 'z', 'angle': -tilt if side == 'e' else tilt},
              'faces': {'east': {'uv': uvl, 'texture': texture}, 'west': {'uv': uvl, 'texture': texture}}}
    el['shade'] = False
    return el


def upright(texture, axis, width, y0, y1, uv):
    """A plane standing straight up through the stalk, along x or z."""
    if axis == 'x':
        return {'from': [8 - width / 2, y0, 8], 'to': [8 + width / 2, y1, 8], 'shade': False,
                'faces': {'north': {'uv': uv, 'texture': texture}, 'south': {'uv': uv, 'texture': texture}}}
    return {'from': [8, y0, 8 - width / 2], 'to': [8, y1, 8 + width / 2], 'shade': False,
            'faces': {'east': {'uv': uv, 'texture': texture}, 'west': {'uv': uv, 'texture': texture}}}


def stalk(texture, y0, y1, w=1.25):
    lo, hi = 8 - w, 8 + w
    uv = [6, 16 - y1, 9, 16 - y0]
    return {'from': [lo, y0, lo], 'to': [hi, y1, hi],
            'faces': {d: {'uv': uv, 'texture': texture} for d in ['north', 'south', 'east', 'west']} |
                     {'up': {'uv': [6, 6, 9, 9], 'texture': texture}}}


def model(name, textures, elements):
    textures = {k: f'rusflora:block/{v}' for k, v in textures.items()}
    textures['particle'] = next(iter(textures.values()))
    write_json(ASSETS / 'models' / 'block' / f'{name}.json', {'ambientocclusion': False, 'textures': textures, 'elements': elements})


def flat_leaf(texture, side, length, width, y, rise=22.5, diagonal=False):
    """A basal leaf lying nearly flat: a horizontal plane from the stalk outwards on side n, s, e or w, its petiole at the
    centre, the blade lifted by rise degrees; or, with diagonal, turned 45 degrees around the stalk instead."""
    turn = {'n': 0, 'e': 90, 's': 180, 'w': 270}[side]
    if side in 'ew':
        x0, x1 = (8, 8 + length) if side == 'e' else (8 - length, 8)
        frm, to = [x0, y, 8 - width / 2], [x1, y, 8 + width / 2]
    else:
        z0, z1 = (8, 8 + length) if side == 's' else (8 - length, 8)
        frm, to = [8 - width / 2, y, z0], [8 + width / 2, y, z1]
    el = {'from': frm, 'to': to, 'shade': False,
          'faces': {'up': {'uv': [0, 0, 16, 16], 'texture': texture, 'rotation': turn},
                    'down': {'uv': [0, 16, 16, 0], 'texture': texture, 'rotation': turn}}}
    if diagonal:
        el['rotation'] = {'origin': [8, y, 8], 'axis': 'y', 'angle': 45}
    elif rise:
        axis = 'z' if side in 'ew' else 'x'
        sign = {'e': 1, 'w': -1, 'n': 1, 's': -1}[side]
        el['rotation'] = {'origin': [8, y, 8], 'axis': axis, 'angle': sign * rise}
    return el


def rosette(scale, tilt=45):
    """Basal leaves around the root, broad and spreading as on the photos: four lying out on the ground and lifted a little,
    four between them turned diagonally, four standing up and leaning out, so the clump reads as a round rosette of big
    leaves from the side and from above. scale 1 is a first-year rosette, 1.6 a big one, whose upright leaves stand steeper."""
    return [*(flat_leaf('#leaf', s, 15 * scale, 13 * scale, 2.5 * scale) for s in 'nsew'),
            *(flat_leaf('#leaf', s, 13 * scale, 12 * scale, 4.5 * scale, diagonal=True) for s in 'nsew'),
            *(leaf('#leaf', s, tilt, 13 * scale, 15 * scale, 0, 0.8) for s in 'nsew')]


def ray(texture, side, y0, length, w=0.35):
    """A thin side ray growing out of the stalk at 45 degrees towards side e or n; its tip is where a small umbel sits."""
    el = {'from': [8 - w, y0, 8 - w], 'to': [8 + w, y0 + length, 8 + w],
          'faces': {d: {'uv': [7, 0, 8, 16], 'texture': texture} for d in ['north', 'south', 'east', 'west']}}
    el['rotation'] = {'origin': [8, y0, 8], 'axis': 'z' if side == 'e' else 'x', 'angle': -45}
    return el


def ray_tip(side, y0, length):
    """Where a ray from ray() ends: (x, y, z)."""
    d = length * math.sqrt(0.5)
    return (8 + d, y0 + d, 8) if side == 'e' else (8, y0 + d, 8 - d)


def small_umbel(side, y0, length, width=8):
    """A small umbel on the tip of a side ray: a disc of umbellets and a low dome under it, both touching the ray tip."""
    x, y, z = ray_tip(side, y0, length)
    h = width * 12 / 32  # the dome texture rows over the plane width keep the pixels square
    uv = [0, 0, 16, 6]
    if side == 'e':
        dome_el = {'from': [x - width / 2, y - h + 0.3, z], 'to': [x + width / 2, y + 0.3, z], 'shade': False,
                   'faces': {'north': {'uv': uv, 'texture': '#dome'}, 'south': {'uv': uv, 'texture': '#dome'}}}
    else:
        dome_el = {'from': [x, y - h + 0.3, z - width / 2], 'to': [x, y + 0.3, z + width / 2], 'shade': False,
                   'faces': {'east': {'uv': uv, 'texture': '#dome'}, 'west': {'uv': uv, 'texture': '#dome'}}}
    return [ray('#stem', side, y0, length), dome_el, plane('#top', y + 0.3, -width / 2, width / 2, 0, (x, z), '#under')]


def umbel_top_parts(w=0.9, lift=0.0, width=22):
    """Top block of a flowering, seeding or dry plant: the stalk ends in a wide flat-topped central umbel (rays fanning out
    under a flat disc of umbellets; from below one sees the spokes, not the flowers) with two smaller umbels on side rays
    that grow out of the stalk. lift and width vary per position (several blockstate variants), so the umbels of plants
    standing side by side sit at different heights instead of cutting through each other."""
    uv = [0, 0, 16, 6]
    half = width / 2
    top = 15.2 + lift
    return [stalk('#stem', 0, top - 8.2, w),
            upright('#dome', 'x', width, top - 9, top + 0.8, uv), upright('#dome', 'z', width, top - 9, top + 0.8, uv),
            plane('#top', top, 8 - half, 8 + half, under='#under'),
            *small_umbel('e', 1 + lift, 8.5), *small_umbel('n', 0 + lift, 7.8)]


UMBEL_VARIANTS = [('', 0.0, 22), ('_b', -1.5, 20), ('_c', -3.0, 21)]


def main():
    # Textures.
    save(seedling(), 'block/hogweed_seedling')
    save(big_leaf(GREEN, 3), 'block/hogweed_leaf')
    save(big_leaf(LATE, 3), 'block/hogweed_leaf_late')
    save(hanging_leaf(4), 'block/hogweed_dry_leaf')
    save(stem(STEM, SPOT), 'block/hogweed_stem')
    save(stem(DRY[1:4], None), 'block/hogweed_stem_dry')
    save(umbel_top(FLOWER, RAY, 16), 'block/hogweed_umbel_top')
    save(umbel_top([FLOWER[0], FLOWER[1], FLOWER[1]], RAY, 16, fill=0.3, head=0.5), 'block/hogweed_umbel_under')
    save(dome(FLOWER, RAY, 17), 'block/hogweed_umbel_side')
    save(umbel_top(SEED, [SEED[0], SEED[1]], 16), 'block/hogweed_seeds_top')
    save(umbel_top(SEED, [SEED[0], SEED[1]], 16, fill=0.35, head=0.55), 'block/hogweed_seeds_under')
    save(dome(SEED, [SEED[0], SEED[1]], 17), 'block/hogweed_seeds_side')
    save(umbel_top(DRY[2:], [DRY[0], DRY[1]], 18, fill=0.55, head=0.65), 'block/hogweed_dry_top')
    save(umbel_top(DRY[2:], [DRY[0], DRY[1]], 18, fill=0.3, head=0.5), 'block/hogweed_dry_under')
    save(dome(DRY[2:], [DRY[0], DRY[1]], 19), 'block/hogweed_dry_side')
    save(seeds_item(), 'item/hogweed_seeds')
    save(stalk_item(), 'item/hogweed_stalk')
    save(effect_icon(False), 'mob_effect/hogweed_sap')
    save(effect_icon(True), 'mob_effect/hogweed_burn')

    # Block models, one per stage and part. Leaves of the root block reach up into the block above, as on a real plant.
    model('hogweed_seedling', {'cross': 'hogweed_seedling'}, cross('#cross'))
    model('hogweed_rosette', {'leaf': 'hogweed_leaf'}, rosette(1.0))
    model('hogweed_big_bottom', {'leaf': 'hogweed_leaf'}, rosette(1.6, 22.5))
    for stage, late in [('flowering', ''), ('seeding', '_late')]:
        top = 'umbel' if stage == 'flowering' else 'seeds'
        model(f'hogweed_{stage}_bottom', {'stem': 'hogweed_stem', 'leaf': f'hogweed_leaf{late}'}, [stalk('#stem', 0, 16, 0.9), *rosette(1.4)])
        model(f'hogweed_{stage}_middle', {'stem': 'hogweed_stem', 'leaf': f'hogweed_leaf{late}'},
              [stalk('#stem', 0, 16, 0.9), flat_leaf('#leaf', 'e', 9, 8, 4, 45), flat_leaf('#leaf', 'w', 7, 7, 11, 45)])
        for suffix, lift, width in UMBEL_VARIANTS:
            model(f'hogweed_{stage}_top{suffix}', {'stem': 'hogweed_stem', 'dome': f'hogweed_{top}_side', 'top': f'hogweed_{top}_top',
                                                   'under': f'hogweed_{top}_under'}, umbel_top_parts(0.9, lift, width))
    model('hogweed_dry_bottom', {'stem': 'hogweed_stem_dry', 'leaf': 'hogweed_dry_leaf'},
          [stalk('#stem', 0, 16, 0.8), *(leaf('#leaf', s, 22.5, 7, -9, 11, 0.7) for s in 'nsew')])
    model('hogweed_dry_middle', {'stem': 'hogweed_stem_dry', 'leaf': 'hogweed_dry_leaf'},
          [stalk('#stem', 0, 16, 0.75), leaf('#leaf', 'e', 22.5, 6, -8, 12, 0.6)])
    for suffix, lift, width in UMBEL_VARIANTS:
        model(f'hogweed_dry_top{suffix}', {'stem': 'hogweed_stem_dry', 'dome': 'hogweed_dry_side', 'top': 'hogweed_dry_top',
                                           'under': 'hogweed_dry_under'}, umbel_top_parts(0.7, lift, width))
    write_json(ASSETS / 'models' / 'block' / 'hogweed_none.json', {'textures': {'particle': 'rusflora:block/hogweed_stem'}, 'elements': []})
    uv = [0, 0, 16, 6]
    # A cut dry umbel on its stalk, slender, with one small side umbel, so it reads as a dried flower and not a mushroom.
    model('dry_hogweed_umbel', {'stem': 'hogweed_stem_dry', 'dome': 'hogweed_dry_side', 'top': 'hogweed_dry_top', 'under': 'hogweed_dry_under'},
          [stalk('#stem', 0, 11, 0.5), upright('#dome', 'x', 12, 9.5, 14, uv), upright('#dome', 'z', 12, 9.5, 14, uv),
           plane('#top', 13.8, 3, 13, under='#under'), *small_umbel('e', 3, 6, 5)])

    names = {(0, 0): 'seedling', (1, 0): 'rosette', (2, 0): 'big_bottom'}
    for s, stage in [(3, 'flowering'), (4, 'seeding'), (5, 'dry')]:
        names |= {(s, 0): f'{stage}_bottom', (s, 1): f'{stage}_middle', (s, 2): f'{stage}_top'}
    variants = {}
    for stage in range(6):
        for part in range(3):
            name = names.get((stage, part), 'none')
            if name.endswith('_top'):  # one of the umbel variants, picked per position
                variants[f'part={part},stage={stage}'] = [{'model': f'rusflora:block/hogweed_{name}{suffix}'} for suffix, _, _ in UMBEL_VARIANTS]
            else:
                variants[f'part={part},stage={stage}'] = {'model': f'rusflora:block/hogweed_{name}'}
    write_json(ASSETS / 'blockstates' / 'hogweed.json', {'variants': variants})
    write_json(ASSETS / 'blockstates' / 'dry_hogweed_umbel.json', {'variants': {'': {'model': 'rusflora:block/dry_hogweed_umbel'}}})
    for item in ['hogweed_seeds', 'hogweed_stalk']:
        write_json(ASSETS / 'models' / 'item' / f'{item}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'rusflora:item/{item}'}})
    write_json(ASSETS / 'models' / 'item' / 'dry_hogweed_umbel.json',
               {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'rusflora:block/hogweed_dry_side'}})

    # Loot: the stalk from the middle block, seeds from ripe heads, the dry umbel from a dead top, a seedling dug out whole.
    def when(stage_min, stage_max, part):
        return {'condition': 'minecraft:block_state_property', 'block': 'rusflora:hogweed',
                'properties': {'part': str(part), 'stage': {'min': str(stage_min), 'max': str(stage_max)}}}
    def entry(item, lo=1, hi=1):
        e = {'type': 'minecraft:item', 'name': item}
        if (lo, hi) != (1, 1):
            e['functions'] = [{'function': 'minecraft:set_count', 'count': {'type': 'minecraft:uniform', 'min': lo, 'max': hi}}]
        return e
    shovel = {'condition': 'minecraft:match_tool', 'predicate': {'items': '#minecraft:shovels'}}
    write_json(DATA / 'loot_table' / 'blocks' / 'hogweed.json', {'type': 'minecraft:block', 'pools': [
        {'rolls': 1, 'conditions': [when(3, 5, 1)], 'entries': [entry('rusflora:hogweed_stalk')]},
        {'rolls': 1, 'conditions': [when(4, 4, 2)], 'entries': [entry('rusflora:hogweed_seeds', 2, 4)]},
        {'rolls': 1, 'conditions': [when(5, 5, 2)], 'entries': [entry('rusflora:dry_hogweed_umbel')]},
        {'rolls': 1, 'conditions': [when(0, 1, 0), shovel], 'entries': [entry('rusflora:hogweed_seeds')]}]})
    write_json(DATA / 'loot_table' / 'blocks' / 'dry_hogweed_umbel.json', {'type': 'minecraft:block', 'pools': [
        {'rolls': 1, 'conditions': [{'condition': 'minecraft:survives_explosion'}], 'entries': [entry('rusflora:dry_hogweed_umbel')]}]})

    # The burn: ignores armour (the sap is under it), no knockback.
    write_json(DATA / 'damage_type' / 'hogweed_burn.json', {'message_id': 'rusflora.hogweed_burn', 'exhaustion': 0.0, 'scaling': 'never'})
    write_json(MC / 'tags' / 'damage_type' / 'bypasses_armor.json', {'replace': False, 'values': ['rusflora:hogweed_burn']})
    write_json(MC / 'tags' / 'damage_type' / 'no_knockback.json', {'replace': False, 'values': ['rusflora:hogweed_burn']})

    # World generation: rare patches in oak and birch forests and on plains.
    write_json(DATA / 'worldgen' / 'configured_feature' / 'hogweed_patch.json', {'type': 'rusflora:hogweed_patch', 'config': {}})
    def placed(name, rarity, extra=()):
        mods = [{'type': 'minecraft:rarity_filter', 'chance': rarity}, {'type': 'minecraft:in_square'},
                {'type': 'minecraft:surface_water_depth_filter', 'max_water_depth': 0},
                {'type': 'minecraft:heightmap', 'heightmap': 'MOTION_BLOCKING_NO_LEAVES'}, *extra, {'type': 'minecraft:biome'}]
        write_json(DATA / 'worldgen' / 'placed_feature' / f'{name}.json', {'feature': 'rusflora:hogweed_patch', 'placement': mods})
    # Seven times rarer than at first, but each patch is a group of 10 to 25 plants; on plains four times rarer than in forests.
    placed('hogweed_forest', 320)
    placed('hogweed_plains', 1280)

    ru = {'block.rusflora.hogweed': 'Борщевик Сосновского', 'item.rusflora.hogweed_seeds': 'Семена борщевика',
          'item.rusflora.hogweed_stalk': 'Полый стебель борщевика', 'block.rusflora.dry_hogweed_umbel': 'Сухой зонтик борщевика',
          'effect.rusflora.hogweed_sap': 'Сок борщевика', 'effect.rusflora.hogweed_burn': 'Ожог борщевиком',
          'death.attack.rusflora.hogweed_burn': '%1$s сгорел от сока борщевика на солнце',
          'death.attack.rusflora.hogweed_burn.player': '%1$s сгорел от сока борщевика, убегая от %2$s',
          'key.rusflora.hogweed_settings': 'Настройки борщевика', 'key.categories.rusflora': 'Русская флора'}
    en = {'block.rusflora.hogweed': "Sosnowsky's Hogweed", 'item.rusflora.hogweed_seeds': 'Hogweed Seeds',
          'item.rusflora.hogweed_stalk': 'Hollow Hogweed Stalk', 'block.rusflora.dry_hogweed_umbel': 'Dry Hogweed Umbel',
          'effect.rusflora.hogweed_sap': 'Hogweed Sap', 'effect.rusflora.hogweed_burn': 'Hogweed Burn',
          'death.attack.rusflora.hogweed_burn': '%1$s was burned by hogweed sap in the sun',
          'death.attack.rusflora.hogweed_burn.player': '%1$s was burned by hogweed sap while fleeing %2$s',
          'key.rusflora.hogweed_settings': 'Hogweed settings', 'key.categories.rusflora': 'Russian Flora'}
    write_json(TOOLS / 'lang' / 'hogweed.ru_ru.json', ru)
    write_json(TOOLS / 'lang' / 'hogweed.en_us.json', en)
    print('ok')


if __name__ == '__main__':
    main()
