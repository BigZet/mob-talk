"""Generates the mod's resources: 16x16 pixel-art textures drawn from the reference notes in docs/references.md, block and
item models, blockstates, loot tables, tags, language files and world generation JSON. Original work, GPL-3.0 like the mod.

Run from the repository root: python tools/generate_assets.py  (needs Pillow). The output is committed; rerun after edits.
"""
import json
import math
import random
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent / 'src' / 'main' / 'resources'
ASSETS = ROOT / 'assets' / 'rusflora'
DATA = ROOT / 'data' / 'rusflora'
MC = ROOT / 'data' / 'minecraft'

# kind: (russian name, english name, has flowers, fruit item or None)
KINDS = {
    'hazel': ('Лещина', 'Hazel', True, 'hazelnut'),
    'dog_rose': ('Шиповник', 'Dog Rose', True, 'rose_hip'),
    'bird_cherry': ('Черёмуха', 'Bird Cherry', True, 'bird_cherry'),
    'red_elder': ('Бузина красная', 'Red Elder', True, 'red_elderberry'),
    'juniper': ('Можжевельник', 'Juniper', False, 'juniper_berry'),
    'willow': ('Ивняк', 'Grey Willow', True, None),
    'lilac': ('Сирень', 'Lilac', True, None),
    'white_lilac': ('Сирень белая', 'White Lilac', True, None),
}
FRUIT_NAMES = {
    'hazelnut': ('Лесной орех', 'Hazelnut'),
    'rose_hip': ('Плоды шиповника', 'Rose Hip'),
    'bird_cherry': ('Ягоды черёмухи', 'Bird Cherries'),
    'red_elderberry': ('Ягоды красной бузины', 'Red Elderberries'),
    'juniper_berry': ('Шишкоягоды можжевельника', 'Juniper Berries'),
}

# Leaf palettes (dark to light) and leaf shape per kind, from the reference photos.
LEAVES = {
    'hazel': ([(58, 94, 34), (76, 118, 44), (98, 142, 58), (124, 166, 74)], 'round'),
    'dog_rose': ([(52, 88, 40), (70, 110, 50), (90, 134, 62), (112, 154, 76)], 'pinnate'),
    'bird_cherry': ([(40, 72, 32), (56, 94, 42), (74, 116, 52), (94, 136, 64)], 'long'),
    'red_elder': ([(56, 94, 36), (74, 118, 46), (94, 140, 58), (118, 160, 72)], 'pinnate'),
    'juniper': ([(30, 56, 46), (42, 72, 58), (56, 88, 70), (76, 106, 88)], 'needle'),
    'willow': ([(92, 112, 82), (116, 136, 102), (140, 158, 122), (170, 184, 152)], 'narrow'),
    'lilac': ([(48, 86, 36), (64, 108, 46), (84, 130, 58), (106, 150, 70)], 'round'),
    'white_lilac': ([(48, 86, 36), (64, 108, 46), (84, 130, 58), (106, 150, 70)], 'round'),
}
BARK = {
    'hazel': [(96, 80, 66), (120, 102, 86), (146, 128, 108)],
    'dog_rose': [(84, 70, 44), (108, 92, 58), (132, 112, 70)],
    'bird_cherry': [(52, 46, 46), (70, 64, 62), (92, 86, 82)],
    'red_elder': [(104, 90, 72), (130, 114, 94), (160, 146, 122)],
    'juniper': [(84, 52, 38), (110, 70, 50), (136, 92, 68)],
    'willow': [(92, 88, 74), (115, 110, 95), (140, 134, 116)],
    'lilac': [(88, 86, 80), (110, 108, 100), (134, 132, 122)],
    'white_lilac': [(88, 86, 80), (110, 108, 100), (134, 132, 122)],
}
CLEAR = (0, 0, 0, 0)


def img():
    return Image.new('RGBA', (16, 16), CLEAR)


def put(im, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16:
        im.putpixel((x, y), tuple(c) + (255,) if len(c) == 3 else tuple(c))


def leaf_base(kind, seed):
    """A tile of overlapping leaves with see-through gaps, wrapping at the edges so blocks join seamlessly."""
    pal, shape = LEAVES[kind]
    rnd = random.Random(seed)
    im = img()
    count = {'round': 26, 'pinnate': 46, 'long': 22, 'needle': 90, 'narrow': 40}[shape]
    for _ in range(count):
        cx, cy = rnd.randrange(16), rnd.randrange(16)
        ang = rnd.random() * math.pi
        dx, dy = math.cos(ang), math.sin(ang)
        tone = rnd.randrange(len(pal))
        if shape == 'round':
            pts = [(a, b) for a in range(-2, 2) for b in range(-1, 2) if abs(a + 0.5) + abs(b) * 0.9 < 2.4]
        elif shape == 'pinnate':
            pts = [(0, 0), (1, 0), (0, 1)] if rnd.random() < 0.5 else [(0, 0), (1, 1)]
        elif shape == 'long':
            pts = [(round(dx * t), round(dy * t)) for t in range(-2, 3)] + [(round(dx * t + dy * 0.8), round(dy * t - dx * 0.8)) for t in range(-1, 2)]
        elif shape == 'needle':
            pts = [(round(dx * t), round(dy * t)) for t in range(0, 3)]
        else:  # narrow willow leaves, mostly hanging
            dx, dy = math.cos(ang * 0.4 + 1.2), math.sin(ang * 0.4 + 1.2)
            pts = [(round(dx * t), round(dy * t)) for t in range(-2, 3)]
        for a, b in pts:
            put(im, (cx + a) % 16, (cy + b) % 16, pal[tone])
        # a lit edge on top-left of each leaf, darker underside
        put(im, (cx + pts[0][0]) % 16, (cy + pts[0][1] - 1) % 16, pal[min(tone + 1, len(pal) - 1)]) if rnd.random() < 0.3 else None
    # a few dark inner shadows show depth
    for _ in range(8):
        x, y = rnd.randrange(16), rnd.randrange(16)
        if im.getpixel((x, y))[3]:
            put(im, x, y, pal[0])
    return im


def dot(im, x, y, c):
    put(im, x % 16, y % 16, c)


def overlay(kind, stage, base, seed):
    """Flowers (stage 1) or fruit (stage 2) on top of the leaves."""
    rnd = random.Random(seed * 7 + stage)
    im = base.copy()
    if kind == 'hazel' and stage == 1:  # dangling yellow catkins
        for _ in range(5):
            x, y = rnd.randrange(16), rnd.randrange(12)
            for t in range(4):
                dot(im, x, y + t, (222, 204, 120) if t % 2 == 0 else (196, 176, 96))
    elif kind == 'hazel' and stage == 2:  # nuts in frilled green husks
        for _ in range(4):
            x, y = rnd.randrange(16), rnd.randrange(16)
            for a, b in [(0, 0), (1, 0), (0, 1), (1, 1)]:
                dot(im, x + a, y + b, (176, 120, 62) if b else (150, 98, 48))
            for a, b in [(-1, -1), (2, -1), (0, -1), (1, -1)]:
                dot(im, x + a, y + b, (156, 190, 84))
    elif kind == 'dog_rose' and stage == 1:  # five-petalled pink flowers with yellow hearts
        for _ in range(4):
            x, y = rnd.randrange(16), rnd.randrange(16)
            for a, b in [(0, -1), (-1, 0), (1, 0), (-1, 1), (1, 1)]:
                dot(im, x + a, y + b, (236, 138, 168))
            dot(im, x, y + 1, (226, 116, 150))
            dot(im, x, y, (250, 222, 96))
    elif kind == 'dog_rose' and stage == 2:  # oval red hips with a dark tip
        for _ in range(5):
            x, y = rnd.randrange(16), rnd.randrange(16)
            dot(im, x, y, (40, 30, 24))
            dot(im, x, y + 1, (226, 70, 34))
            dot(im, x + 1, y + 1, (190, 40, 26))
            dot(im, x, y + 2, (204, 48, 28))
            dot(im, x + 1, y + 2, (170, 34, 22))
            dot(im, x, y + 1, (246, 120, 70)) if rnd.random() < 0.5 else None
    elif kind == 'bird_cherry' and stage == 1:  # drooping white racemes
        for _ in range(4):
            x, y = rnd.randrange(16), rnd.randrange(10)
            for t in range(6):
                dot(im, x, y + t, (248, 248, 238))
                if t % 2 == 1:
                    dot(im, x + 1, y + t, (226, 228, 214))
                if t % 3 == 0:
                    dot(im, x - 1, y + t, (236, 238, 226))
    elif kind == 'bird_cherry' and stage == 2:  # hanging strings of shiny black berries
        for _ in range(4):
            x, y = rnd.randrange(16), rnd.randrange(10)
            for t in range(0, 6, 2):
                dot(im, x + (t // 2) % 2, y + t, (28, 22, 30))
                dot(im, x + (t // 2) % 2, y + t + 1, (54, 42, 60))
    elif kind == 'red_elder' and stage == 1:  # creamy egg-shaped panicles
        for _ in range(3):
            x, y = rnd.randrange(16), rnd.randrange(16)
            for a, b in [(0, 0), (1, 0), (-1, 1), (0, 1), (1, 1), (2, 1), (0, 2), (1, 2)]:
                dot(im, x + a, y + b, (234, 228, 180) if (a + b) % 2 else (214, 208, 156))
    elif kind == 'red_elder' and stage == 2:  # tight bunches of bright red berries
        for _ in range(3):
            x, y = rnd.randrange(16), rnd.randrange(16)
            for a, b in [(0, 0), (1, 0), (2, 0), (0, 1), (1, 1), (2, 1), (1, 2)]:
                dot(im, x + a, y + b, (220, 40, 34) if (a + b) % 2 else (186, 24, 24))
            dot(im, x + 1, y, (250, 110, 90))
    elif kind == 'juniper' and stage == 2:  # dusky blue berries with a pale bloom
        for _ in range(6):
            x, y = rnd.randrange(16), rnd.randrange(16)
            dot(im, x, y, (52, 60, 104))
            dot(im, x + 1, y, (38, 44, 82))
            dot(im, x, y + 1, (40, 46, 86))
            dot(im, x + 1, y + 1, (30, 34, 66))
            dot(im, x, y, (110, 120, 160))
    elif kind == 'willow' and stage == 1:  # silky silver-yellow catkins
        for _ in range(6):
            x, y = rnd.randrange(16), rnd.randrange(16)
            dot(im, x, y, (244, 240, 212))
            dot(im, x, y + 1, (226, 222, 160))
            dot(im, x + 1, y, (236, 232, 190))
    elif kind in ('lilac', 'white_lilac') and stage == 1:  # dense cone-shaped panicles
        pal = [(176, 136, 206), (150, 106, 188), (196, 166, 222)] if kind == 'lilac' else [(248, 246, 240), (226, 224, 214), (238, 236, 226)]
        for _ in range(3):
            x, y = rnd.randrange(16), rnd.randrange(16)
            for b in range(5):
                for a in range(-(2 - b // 2), 3 - b // 2):
                    dot(im, x + a, y + b, pal[(a * 3 + b) % 3])
    return im


def stem_texture(kind):
    rnd = random.Random(sum(map(ord, kind)))
    dark, mid, light = BARK[kind]
    im = img()
    for x in range(16):
        for y in range(16):
            c = mid if x % 2 == 0 else dark
            if rnd.random() < 0.12:
                c = light
            put(im, x, y, c)
    if kind == 'dog_rose':  # thorns
        for _ in range(10):
            put(im, rnd.randrange(16), rnd.randrange(16), (170, 120, 90))
    return im


def sapling_texture(kind):
    pal, _ = LEAVES[kind]
    dark, mid, _light = BARK[kind]
    im = img()
    for y in range(7, 16):
        put(im, 8 if y > 10 else 7, y, mid if y % 2 else dark)
    put(im, 9, 10, mid); put(im, 10, 9, mid); put(im, 6, 9, mid); put(im, 5, 8, mid)
    rnd = random.Random(len(kind) * 31)
    for cx, cy in [(7, 5), (11, 7), (4, 7), (9, 3), (6, 2), (12, 4), (3, 4)]:
        for a, b in [(0, 0), (1, 0), (0, 1), (-1, 0), (0, -1)]:
            if rnd.random() < 0.85:
                put(im, cx + a, cy + b, pal[rnd.randrange(1, len(pal))])
    if kind == 'juniper':
        im = img()
        for y in range(4, 16):
            w = max(0, (y - 4) // 3)
            for x in range(8 - w, 9 + w):
                put(im, x, y, pal[(x + y) % len(pal)])
        for y in range(13, 16):
            put(im, 8, y, mid)
    return im


def fruit_texture(fruit):
    im = img()
    if fruit == 'hazelnut':
        for x in range(4, 12):
            for y in range(6, 14):
                if (x - 7.5) ** 2 / 14 + (y - 10) ** 2 / 16 < 1:
                    put(im, x, y, (176, 116, 58) if x < 9 else (140, 90, 42))
        put(im, 6, 8, (214, 160, 96)); put(im, 6, 9, (214, 160, 96))
        for x, y in [(5, 5), (6, 4), (7, 5), (8, 4), (9, 5), (10, 4), (7, 3), (8, 2), (6, 6), (9, 6)]:
            put(im, x, y, (140, 176, 72))
    elif fruit == 'rose_hip':
        for x in range(5, 11):
            for y in range(4, 14):
                if (x - 7.5) ** 2 / 8 + (y - 9) ** 2 / 22 < 1:
                    put(im, x, y, (222, 62, 30) if x < 8 else (180, 38, 22))
        put(im, 6, 7, (250, 140, 90)); put(im, 6, 8, (246, 112, 64))
        for x, y in [(7, 3), (8, 3), (6, 2), (9, 2), (7, 2)]:
            put(im, x, y, (50, 36, 26))
    elif fruit == 'bird_cherry':
        put(im, 8, 1, (90, 110, 50))
        for i, (x, y) in enumerate([(6, 4), (9, 6), (6, 8), (9, 10), (7, 12)]):
            for a, b in [(0, 0), (1, 0), (0, 1), (1, 1)]:
                put(im, x + a, y + b, (30, 24, 34) if a or b else (90, 76, 100))
            put(im, 8, 2 + i * 2, (90, 110, 50))
    elif fruit == 'red_elderberry':
        for x, y in [(5, 6), (7, 5), (9, 6), (11, 7), (6, 8), (8, 7), (10, 9), (7, 10), (9, 11), (5, 10), (8, 12)]:
            put(im, x, y, (214, 36, 30)); put(im, x + 1, y, (176, 22, 22)); put(im, x, y + 1, (186, 26, 24))
            put(im, x, y, (248, 104, 86))
        for x, y in [(8, 2), (8, 3), (7, 4), (9, 4)]:
            put(im, x, y, (150, 60, 80))
    elif fruit == 'juniper_berry':
        for x, y in [(4, 7), (8, 5), (10, 9), (6, 11)]:
            for a, b in [(0, 0), (1, 0), (2, 0), (0, 1), (1, 1), (2, 1), (1, 2), (1, -1)]:
                put(im, x + a, y + b, (48, 56, 98))
            put(im, x + 1, y, (120, 128, 170))
            put(im, x + 2, y + 1, (32, 36, 68))
        for x, y in [(3, 14), (4, 13), (5, 12), (6, 11)]:
            put(im, x, y, (110, 70, 50))
    return im


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def save(im, path, scale=1):
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path)


def leaves_stages(kind):
    """Which texture each stage uses: flowerless kinds reuse the plain leaves for stage 1, fruitless ones for stage 2."""
    _ru, _en, flowers, fruit = KINDS[kind]
    return [f'{kind}_leaves', f'{kind}_leaves_flowering' if flowers else f'{kind}_leaves',
            f'{kind}_leaves_fruiting' if fruit else (f'{kind}_leaves_flowering' if flowers and kind != 'willow' else f'{kind}_leaves')]


def main():
    tex = ASSETS / 'textures'
    lang_ru, lang_en = {}, {}
    for i, (kind, (ru, en, flowers, fruit)) in enumerate(KINDS.items()):
        seed = 1000 + i * 17
        base = leaf_base(kind, seed)
        save(base, tex / 'block' / f'{kind}_leaves.png')
        if flowers:
            save(overlay(kind, 1, base, seed), tex / 'block' / f'{kind}_leaves_flowering.png')
        if fruit:
            save(overlay(kind, 2, base, seed), tex / 'block' / f'{kind}_leaves_fruiting.png')
        save(stem_texture(kind), tex / 'block' / f'{kind}_stems.png')
        save(sapling_texture(kind), tex / 'block' / f'{kind}_sapling.png')

        stages = leaves_stages(kind)
        for name in sorted(set(stages)):
            write_json(ASSETS / 'models' / 'block' / f'{name}.json', {'parent': 'rusflora:block/shrub_leaves', 'textures': {'all': f'rusflora:block/{name}'}})
        write_json(ASSETS / 'blockstates' / f'{kind}_leaves.json',
                   {'variants': {f'stage={s}': {'model': f'rusflora:block/{stages[s]}'} for s in range(3)}})
        write_json(ASSETS / 'models' / 'block' / f'{kind}_stems.json', {'parent': 'rusflora:block/shrub_stems', 'textures': {'bark': f'rusflora:block/{kind}_stems'}})
        write_json(ASSETS / 'blockstates' / f'{kind}_stems.json', {'variants': {'': {'model': f'rusflora:block/{kind}_stems'}}})
        write_json(ASSETS / 'models' / 'block' / f'{kind}_sapling.json', {'parent': 'minecraft:block/cross', 'textures': {'cross': f'rusflora:block/{kind}_sapling'}})
        write_json(ASSETS / 'blockstates' / f'{kind}_sapling.json', {'variants': {'': {'model': f'rusflora:block/{kind}_sapling'}}})
        write_json(ASSETS / 'models' / 'item' / f'{kind}_leaves.json', {'parent': f'rusflora:block/{kind}_leaves'})
        write_json(ASSETS / 'models' / 'item' / f'{kind}_stems.json', {'parent': f'rusflora:block/{kind}_stems'})
        write_json(ASSETS / 'models' / 'item' / f'{kind}_sapling.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'rusflora:block/{kind}_sapling'}})

        lang_ru[f'block.rusflora.{kind}_leaves'] = f'{ru} (листва)'
        lang_ru[f'block.rusflora.{kind}_stems'] = f'{ru} (стволики)'
        lang_ru[f'block.rusflora.{kind}_sapling'] = f'{ru} (росток)'
        lang_en[f'block.rusflora.{kind}_leaves'] = f'{en} Leaves'
        lang_en[f'block.rusflora.{kind}_stems'] = f'{en} Stems'
        lang_en[f'block.rusflora.{kind}_sapling'] = f'{en} Sapling'

        # Loot: shears or silk touch keep the leaves; otherwise now and then a sapling or a stick, and ripe fruit.
        leaves_pools = [{'rolls': 1, 'entries': [{'type': 'minecraft:alternatives', 'children': [
            {'type': 'minecraft:item', 'name': f'rusflora:{kind}_leaves', 'conditions': [{'condition': 'minecraft:any_of', 'terms': [
                {'condition': 'minecraft:match_tool', 'predicate': {'items': 'minecraft:shears'}},
                {'condition': 'minecraft:match_tool', 'predicate': {'predicates': {'minecraft:enchantments': [{'enchantments': 'minecraft:silk_touch', 'levels': {'min': 1}}]}}}]}]},
            {'type': 'minecraft:item', 'name': f'rusflora:{kind}_sapling', 'conditions': [{'condition': 'minecraft:survives_explosion'},
                {'condition': 'minecraft:table_bonus', 'enchantment': 'minecraft:fortune', 'chances': [0.06, 0.08, 0.1, 0.14]}]},
            {'type': 'minecraft:item', 'name': 'minecraft:stick', 'conditions': [{'condition': 'minecraft:random_chance', 'chance': 0.04}]}]}]}]
        if fruit:
            leaves_pools.append({'rolls': 1, 'conditions': [{'condition': 'minecraft:block_state_property', 'block': f'rusflora:{kind}_leaves', 'properties': {'stage': '2'}}],
                                 'entries': [{'type': 'minecraft:item', 'name': f'rusflora:{fruit}', 'functions': [
                                     {'function': 'minecraft:set_count', 'count': {'type': 'minecraft:uniform', 'min': 1, 'max': 2}}]}]})
        write_json(DATA / 'loot_table' / 'blocks' / f'{kind}_leaves.json', {'type': 'minecraft:block', 'pools': leaves_pools})
        for part in ('stems', 'sapling'):
            write_json(DATA / 'loot_table' / 'blocks' / f'{kind}_{part}.json', {'type': 'minecraft:block', 'pools': [
                {'rolls': 1, 'conditions': [{'condition': 'minecraft:survives_explosion'}], 'entries': [{'type': 'minecraft:item', 'name': f'rusflora:{kind}_{part}'}]}]})
        write_json(DATA / 'worldgen' / 'configured_feature' / f'{kind}.json', {'type': 'rusflora:shrub', 'config': {'kind': kind}})

    for fruit, (ru, en) in FRUIT_NAMES.items():
        save(fruit_texture(fruit), tex / 'item' / f'{fruit}.png')
        write_json(ASSETS / 'models' / 'item' / f'{fruit}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'rusflora:item/{fruit}'}})
        lang_ru[f'item.rusflora.{fruit}'] = ru
        lang_en[f'item.rusflora.{fruit}'] = en

    # Leaves: a see-through cube with two crossed planes inside, so a bush looks deep rather than like a hollow box.
    write_json(ASSETS / 'models' / 'block' / 'shrub_leaves.json', {
        'parent': 'minecraft:block/block', 'textures': {'particle': '#all'},
        'elements': [
            {'from': [0, 0, 0], 'to': [16, 16, 16], 'faces': {d: {'uv': [0, 0, 16, 16], 'texture': '#all', 'cullface': d}
                                                              for d in ('down', 'up', 'north', 'south', 'west', 'east')}},
            {'from': [0.8, 0, 8], 'to': [15.2, 16, 8], 'shade': False, 'rotation': {'origin': [8, 8, 8], 'axis': 'y', 'angle': 45, 'rescale': True},
             'faces': {'north': {'uv': [0, 0, 16, 16], 'texture': '#all'}, 'south': {'uv': [0, 0, 16, 16], 'texture': '#all'}}},
            {'from': [0.8, 0, 8], 'to': [15.2, 16, 8], 'shade': False, 'rotation': {'origin': [8, 8, 8], 'axis': 'y', 'angle': -45, 'rescale': True},
             'faces': {'north': {'uv': [0, 0, 16, 16], 'texture': '#all'}, 'south': {'uv': [0, 0, 16, 16], 'texture': '#all'}}},
        ]})

    # Stems: three thin trunks, two of them leaning.
    def stick(frm, to, rot=None):
        u = frm[0]
        faces = {d: {'uv': [u, 0, u + 2, 16], 'texture': '#bark'} for d in ('north', 'south', 'west', 'east')}
        faces['up'] = {'uv': [u, 0, u + 2, 2], 'texture': '#bark'}
        faces['down'] = {'uv': [u, 14, u + 2, 16], 'texture': '#bark'}
        e = {'from': frm, 'to': to, 'faces': faces}
        if rot:
            e['rotation'] = rot
        return e
    write_json(ASSETS / 'models' / 'block' / 'shrub_stems.json', {
        'parent': 'minecraft:block/block', 'ambientocclusion': False, 'textures': {'particle': '#bark'},
        'elements': [stick([3, 0, 4], [5, 16, 6]),
                     stick([10, 0, 9], [12, 16, 11], {'origin': [11, 0, 10], 'axis': 'x', 'angle': 22.5}),
                     stick([8, 0, 3], [10, 16, 5], {'origin': [9, 0, 4], 'axis': 'z', 'angle': -22.5})]})

    lang_ru['itemGroup.rusflora'] = 'Русская флора'
    lang_en['itemGroup.rusflora'] = 'Russian Flora'
    # Other parts of the mod (e.g. hogweed) add their names in tools/lang/<part>.ru_ru.json and <part>.en_us.json.
    for extra in sorted((Path(__file__).parent / 'lang').glob('*.json')):
        (lang_ru if extra.name.endswith('.ru_ru.json') else lang_en).update(json.loads(extra.read_text(encoding='utf-8')))
    write_json(ASSETS / 'lang' / 'ru_ru.json', lang_ru)
    write_json(ASSETS / 'lang' / 'en_us.json', lang_en)

    leaves = [f'rusflora:{k}_leaves' for k in KINDS]
    stems = [f'rusflora:{k}_stems' for k in KINDS]
    saplings = [f'rusflora:{k}_sapling' for k in KINDS]
    write_json(MC / 'tags' / 'block' / 'mineable' / 'hoe.json', {'replace': False, 'values': leaves})
    write_json(MC / 'tags' / 'block' / 'mineable' / 'axe.json', {'replace': False, 'values': stems})
    write_json(MC / 'tags' / 'block' / 'sword_efficient.json', {'replace': False, 'values': leaves})
    write_json(MC / 'tags' / 'block' / 'replaceable_by_trees.json', {'replace': False, 'values': leaves})
    write_json(MC / 'tags' / 'block' / 'saplings.json', {'replace': False, 'values': saplings})
    write_json(MC / 'tags' / 'item' / 'saplings.json', {'replace': False, 'values': saplings})

    # Placement: how often a chunk tries, then the usual square spread, dry land, the surface, the biome check.
    def placed(name, kind, count=1, rarity=None, extra=()):
        mods = []
        if count > 1:
            mods.append({'type': 'minecraft:count', 'count': count})
        if rarity:
            mods.append({'type': 'minecraft:rarity_filter', 'chance': rarity})
        mods += [{'type': 'minecraft:in_square'}, {'type': 'minecraft:surface_water_depth_filter', 'max_water_depth': 0},
                 {'type': 'minecraft:heightmap', 'heightmap': 'MOTION_BLOCKING_NO_LEAVES'}, *extra, {'type': 'minecraft:biome'}]
        write_json(DATA / 'worldgen' / 'placed_feature' / f'{name}.json', {'feature': f'rusflora:{kind}', 'placement': mods})
    placed('hazel_forest', 'hazel', count=1)
    placed('hazel_open', 'hazel', rarity=10)
    placed('dog_rose_open', 'dog_rose', rarity=3)
    placed('dog_rose_forest', 'dog_rose', rarity=5)
    placed('bird_cherry_water', 'bird_cherry', count=3, extra=[{'type': 'rusflora:near_water', 'radius': 6}])
    placed('red_elder', 'red_elder', rarity=2)
    placed('juniper_taiga', 'juniper', count=1)
    placed('juniper_hills', 'juniper', rarity=2)
    placed('willow_water', 'willow', count=4, extra=[{'type': 'rusflora:near_water', 'radius': 5}])
    placed('lilac_village', 'lilac', count=6, extra=[{'type': 'rusflora:near_village'}])
    placed('white_lilac_village', 'white_lilac', count=2, extra=[{'type': 'rusflora:near_village'}])
    placed('lilac_wild', 'lilac', rarity=24)

    # Mod icon: the lilac and rose tiles side by side, scaled up.
    icon = Image.new('RGBA', (32, 32), CLEAR)
    for (x, y), name in zip([(0, 0), (16, 0), (0, 16), (16, 16)], ['lilac_leaves_flowering', 'dog_rose_leaves_flowering', 'hazel_leaves_fruiting', 'red_elder_leaves_fruiting']):
        icon.paste(Image.open(tex / 'block' / f'{name}.png'), (x, y))
    icon.resize((128, 128), Image.NEAREST).save(ASSETS / 'icon.png')
    print('ok')


if __name__ == '__main__':
    main()
