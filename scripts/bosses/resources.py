"""Writes the sacred bosses' data and icons: names (ru/en), loot tables, the holy relics tag, item and block models, and
small original pixel icons for relics, afflictions and the seal (GPL-3.0, drawn by code). Run: python scripts/bosses/resources.py"""
import json
import os
import random
import zlib
from PIL import Image

from boxtex import ROOT, GOLD, GOLD_LIGHT, GOLD_DARK, BLACK, NIGHT, BONE, CANDLE, FLAME, EMBER, BRONZE, PATINA, CINNABAR, BLOOD, WHITE_CLOTH, mix, shade

ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "divineblock")
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "divineblock")

BOSSES = [
    # id, ru, en, relic id, relic ru, relic en, affliction id, affliction ru, affliction en
    ("black_schemnik", "Чёрный Схимник", "Black Schema-Monk", "schema_rosary", "Чётки Схимника", "Schema-Monk's Prayer Rope", "vow_of_silence", "Обет молчания", "Vow of Silence"),
    ("bell_ringer", "Звонарь погоста", "Graveyard Bell-Ringer", "tocsin_tongue", "Язык набатного колокола", "Tocsin Bell Clapper", "ringing", "Звон в ушах", "Ringing Ears"),
    ("seraph", "Шестикрылый серафим", "Six-Winged Seraph", "seraph_feather", "Пламенное перо серафима", "Seraph's Flaming Feather", "scorch", "Опаление", "Scorch"),
    ("faceless_icon", "Безликий лик", "Faceless Icon", "icon_oklad", "Оклад безликого лика", "Faceless Icon's Cover", "gaze", "Взор лика", "Gaze of the Icon"),
    ("wailer", "Плакальщица погоста", "Graveyard Wailer", "mourning_veil", "Плат плакальщицы", "Wailer's Veil", "panikhida", "Панихида", "Requiem"),
    ("archangel", "Архангел гнева", "Archangel of Wrath", "wrath_sword_shard", "Осколок меча гнева", "Shard of the Sword of Wrath", None, None, None),
    ("leviathan", "Левиафан", "Leviathan", "leviathan_scale", "Чешуя Левиафана", "Leviathan Scale", "depths", "Тяжесть глубин", "Weight of the Deep"),
    ("behemoth", "Бегемот", "Behemoth", "behemoth_horn", "Рог Бегемота", "Behemoth Horn", "dust", "Прах земной", "Dust of the Earth"),
    ("goliath", "Голиаф", "Goliath", "goliath_spearhead", "Наконечник копья Голиафа", "Goliath's Spearhead", "stun", "Оглушение", "Stunned"),
    ("horsemen", "Четыре всадника", "Four Horsemen", "broken_seal", "Сломанная печать", "Broken Seal", "famine", "Голод", "Famine"),
    ("destroying_angel", "Ангел-губитель", "Destroying Angel", "hyssop_branch", "Ветвь иссопа", "Hyssop Branch", "firstborn_mark", "Печать первенца", "Mark of the Firstborn"),
    ("cherub", "Херувим с пламенным мечом", "Cherub with the Flaming Sword", "cherub_flame", "Пламя херувима", "Cherub's Flame", "exile", "Изгнание из рая", "Exile from Paradise"),
    ("beast", "Зверь из бездны", "Beast from the Abyss", "beast_horn", "Рог Зверя", "Horn of the Beast", "mark_of_beast", "Начертание", "Mark of the Beast"),
    ("nimrod", "Нимрод", "Nimrod", "babel_brick", "Кирпич Вавилонской башни", "Brick of Babel", "confusion_of_tongues", "Смешение языков", "Confusion of Tongues"),
]

# Each relic's two colours (base, accent) and a shape for its icon.
RELIC_LOOK = {
    "schema_rosary": (BLACK, BONE, "ring"), "tocsin_tongue": (BRONZE, PATINA, "bell"), "seraph_feather": (FLAME, CANDLE, "feather"),
    "icon_oklad": (GOLD, GOLD_DARK, "frame"), "mourning_veil": (NIGHT, WHITE_CLOTH, "veil"), "wrath_sword_shard": ((200, 200, 220), FLAME, "shard"),
    "leviathan_scale": ((30, 70, 90), (90, 170, 170), "scale"), "behemoth_horn": ((110, 90, 60), BONE, "horn"), "goliath_spearhead": ((120, 120, 130), BRONZE, "shard"),
    "broken_seal": (CINNABAR, GOLD, "seal"), "hyssop_branch": ((60, 110, 50), BLOOD, "branch"), "cherub_flame": (FLAME, GOLD_LIGHT, "flame"),
    "beast_horn": (BLOOD, BLACK, "horn"), "babel_brick": ((160, 90, 60), (110, 60, 40), "brick"),
}
EFFECT_LOOK = {
    "vow_of_silence": (BLACK, BONE), "ringing": (BRONZE, GOLD_LIGHT), "scorch": (EMBER, CANDLE), "gaze": (GOLD, BLACK), "panikhida": (NIGHT, CANDLE),
    "depths": ((20, 50, 80), (90, 170, 200)), "dust": ((110, 85, 55), (170, 140, 100)), "stun": ((140, 130, 110), (240, 230, 120)),
    "famine": ((50, 50, 30), (140, 120, 60)), "firstborn_mark": (BLOOD, (230, 40, 40)), "exile": (FLAME, GOLD_LIGHT),
    "mark_of_beast": ((60, 10, 10), (220, 40, 30)), "confusion_of_tongues": ((150, 110, 60), (240, 220, 160)),
}


def icon(shape, base, accent, seed, size=16):
    rand = random.Random(seed)
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    def put(x, y, c):
        if 0 <= x < size and 0 <= y < size:
            img.putpixel((x, y), tuple(int(v) for v in c[:3]) + (255,))
    c = size // 2
    if shape == "ring":
        for i in range(24):
            import math
            a = math.pi * 2 * i / 24
            put(int(c + math.cos(a) * 5.5), int(c + math.sin(a) * 5.5), base if i % 2 else shade(base, 1.8))
        for y in range(10, 15): put(c, y, accent)
        for x in range(c - 1, c + 2): put(x, 12, accent)
    elif shape == "bell":
        for y in range(3, 13):
            w = 2 + (y - 3) // 2
            for x in range(c - w, c + w): put(x, y, shade(base, 0.8 + 0.4 * rand.random()) if rand.random() > 0.15 else accent)
        for x in range(c - 6, c + 6): put(x, 13, shade(base, 0.6))
        put(c, 14, accent); put(c - 1, 14, accent)
    elif shape == "feather":
        for i in range(12):
            x, y = 3 + i, 13 - i
            put(x, y, accent)
            for k in range(1, 4 - abs(i - 6) // 3): put(x - k, y - k, mix(base, accent, k / 4)); put(x + k, y + k, base)
    elif shape == "frame":
        for y in range(2, 14):
            for x in range(4, 12):
                edge = x in (4, 11) or y in (2, 13)
                put(x, y, shade(base, 1.2) if edge else (40, 30, 26))
        for y in range(5, 9):
            for x in range(6, 10): put(x, y, GOLD_LIGHT)
        for x in range(5, 11): put(x, 11, (90, 30, 30))
    elif shape == "veil":
        for y in range(2, 14):
            for x in range(3 + y // 4, 13 - y // 4 + (y > 8)):
                put(x, y, shade(base, 0.8 + 0.3 * rand.random()))
        for x in range(4, 12): put(x, 13, accent)
    elif shape == "shard":
        for i in range(10):
            for k in range(-1 - (i < 5), 2 + (i < 5)):
                put(3 + i + k, 12 - i, base if k else shade(base, 1.3))
        for i in range(3): put(12 + i, 3 - i + 1, accent)
    elif shape == "scale":
        for y in range(3, 14):
            for x in range(3, 13):
                if (x - c) ** 2 + (y - 6) ** 2 * 0.7 < 30 and y > 2:
                    put(x, y, mix(base, accent, (y - 3) / 12))
        for x in range(4, 12): put(x, 6, shade(accent, 1.3))
    elif shape == "horn":
        for i in range(12):
            w = max(1, 3 - i // 4)
            for k in range(w): put(3 + i, 13 - int(i * 0.8) - k, mix(base, accent, i / 12))
    elif shape == "seal":
        import math
        for y in range(16):
            for x in range(16):
                d = math.hypot(x - 7.5, y - 7.5)
                if d < 6: put(x, y, shade(base, 1 - d / 15))
        for i in range(-3, 4): put(8 + i, 8 + (i % 2), (30, 10, 10))
        for y in range(4, 12): put(7, y, accent)
    elif shape == "branch":
        for i in range(13): put(2 + i, 14 - i, (90, 70, 40))
        for i in range(2, 13, 2):
            put(2 + i - 1, 14 - i - 1, base); put(2 + i + 1, 14 - i + 1, base); put(2 + i, 14 - i - 2, base)
        put(12, 3, accent); put(13, 2, accent); put(11, 2, accent)
    elif shape == "flame":
        for y in range(2, 15):
            w = int(4.5 * (y - 2) / 12 + 1)
            for x in range(c - w, c + w):
                put(x, y, mix(accent, base, abs(x - c + 0.5) / max(1, w)))
    elif shape == "brick":
        for y in range(4, 12):
            for x in range(2, 14):
                mortar = y in (4, 11) or x in (2, 13) or (y == 8) or (x == 8 and y < 8) or (x == 5 and y > 8) or (x == 11 and y > 8)
                put(x, y, shade(accent if mortar else base, 0.8 + 0.3 * rand.random()))
    return img


def effect_icon(base, accent, seed):
    import math
    img = Image.new("RGBA", (18, 18), (0, 0, 0, 0))
    rand = random.Random(seed)
    for y in range(18):
        for x in range(18):
            d = math.hypot(x - 8.5, y - 8.5)
            if d < 8:
                img.putpixel((x, y), tuple(shade(mix(base, accent, max(0, 0.6 - d / 12)), 0.85 + 0.3 * rand.random())) + (255,))
            elif d < 8.8:
                img.putpixel((x, y), tuple(GOLD_DARK) + (255,))
    # An eight-pointed cross in the accent colour.
    for y in range(4, 15): img.putpixel((9, y), tuple(accent) + (255,))
    for x in range(6, 13): img.putpixel((x, 7), tuple(accent) + (255,))
    for x in range(8, 11): img.putpixel((x, 5), tuple(accent) + (255,))
    img.putpixel((7, 12), tuple(accent) + (255,)); img.putpixel((8, 11), tuple(accent) + (255,))
    img.putpixel((10, 11), tuple(accent) + (255,)); img.putpixel((11, 10), tuple(accent) + (255,))
    return img


def seal_texture():
    img = Image.new("RGBA", (16, 16))
    rand = random.Random(7)
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), tuple(shade(BLACK, 0.8 + 0.5 * rand.random())) + (255,))
    for y in range(2, 14): img.putpixel((7, y), tuple(GOLD) + (255,)); img.putpixel((8, y), tuple(GOLD) + (255,))
    for x in range(4, 12): img.putpixel((x, 5), tuple(GOLD) + (255,))
    for x in range(6, 10): img.putpixel((x, 3), tuple(GOLD) + (255,))
    for i in range(-3, 4): img.putpixel((7 + i, 10 - (1 if i > 0 else -1 if i < 0 else 0)), tuple(GOLD_LIGHT) + (255,))
    for i in range(16):
        img.putpixel((i, 0), tuple(GOLD_DARK) + (255,)); img.putpixel((i, 15), tuple(GOLD_DARK) + (255,))
        img.putpixel((0, i), tuple(GOLD_DARK) + (255,)); img.putpixel((15, i), tuple(GOLD_DARK) + (255,))
    return img


def write_json(path, value):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        json.dump(value, out, ensure_ascii=False, indent=2)
        out.write("\n")


def main():
    for lang, column in (("ru_ru", 1), ("en_us", 2)):
        path = os.path.join(ASSETS, "lang", lang + ".json")
        with open(path, encoding="utf-8") as f:
            names = json.load(f)
        for boss in BOSSES:
            names["entity.divineblock." + boss[0]] = boss[column]
            names["item.divineblock." + boss[3]] = boss[3 + column]
            if boss[6]:
                names["effect.divineblock." + boss[6]] = boss[6 + column]
        names["block.divineblock.boss_seal"] = "Печать стража" if lang == "ru_ru" else "Guardian's Seal"
        names["tag.item.divineblock.holy_relics"] = "Святые реликвии" if lang == "ru_ru" else "Holy Relics"
        write_json(path, names)

    relics = []
    for boss in BOSSES:
        relic = boss[3]
        relics.append("divineblock:" + relic)
        base, accent, shape = RELIC_LOOK[relic]
        os.makedirs(os.path.join(ASSETS, "textures", "item"), exist_ok=True)
        icon(shape, base, accent, zlib.crc32(relic.encode())).save(os.path.join(ASSETS, "textures", "item", relic + ".png"))
        write_json(os.path.join(ASSETS, "models", "item", relic + ".json"), {"parent": "minecraft:item/generated", "textures": {"layer0": "divineblock:item/" + relic}})
        # The relic always drops; some gold, and experience from the boss itself.
        write_json(os.path.join(DATA, "loot_table", "entities", boss[0] + ".json"), {
            "type": "minecraft:entity",
            "pools": [
                {"rolls": 1, "entries": [{"type": "minecraft:item", "name": "divineblock:" + relic}]},
                {"rolls": 1, "entries": [{"type": "minecraft:item", "name": "minecraft:gold_ingot",
                                          "functions": [{"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 3, "max": 8}}]}]},
                {"rolls": 1, "entries": [{"type": "minecraft:item", "name": "minecraft:candle",
                                          "functions": [{"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 1, "max": 4}}]}]},
            ],
        })
    write_json(os.path.join(DATA, "tags", "item", "holy_relics.json"), {"values": relics})

    os.makedirs(os.path.join(ASSETS, "textures", "mob_effect"), exist_ok=True)
    for effect, (base, accent) in EFFECT_LOOK.items():
        effect_icon(base, accent, zlib.crc32(effect.encode())).save(os.path.join(ASSETS, "textures", "mob_effect", effect + ".png"))

    os.makedirs(os.path.join(ASSETS, "textures", "block"), exist_ok=True)
    seal_texture().save(os.path.join(ASSETS, "textures", "block", "boss_seal.png"))
    write_json(os.path.join(ASSETS, "blockstates", "boss_seal.json"), {"variants": {"": {"model": "divineblock:block/boss_seal"}}})
    write_json(os.path.join(ASSETS, "models", "block", "boss_seal.json"), {
        "parent": "minecraft:block/cube_bottom_top",
        "textures": {"top": "divineblock:block/boss_seal", "bottom": "minecraft:block/polished_blackstone", "side": "minecraft:block/polished_blackstone_bricks"}})
    write_json(os.path.join(ASSETS, "models", "item", "boss_seal.json"), {"parent": "divineblock:block/boss_seal"})
    write_json(os.path.join(DATA, "loot_table", "blocks", "boss_seal.json"), {"type": "minecraft:block", "pools": []})
    print("resources written")


if __name__ == "__main__":
    main()
