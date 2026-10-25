"""Writes the weapons' data: names (ru/en), crafts, item models (flat sprites held like the vanilla sword and bow), the sling
stone's icon, the Rout affliction's icon and the block tag of walls the Trumpets of Jericho may bring down.
Run: python scripts/weapons/resources.py (or scripts/weapons/all.py for the models as well)."""
import os
import random
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "bosses"))
from PIL import Image  # noqa: E402
from boxtex import ROOT  # noqa: E402
from resources import effect_icon, write_json, ASSETS, DATA  # noqa: E402  (scripts/bosses/resources.py)

# id, ru, en
WEAPONS = [
    ("david_sling", "Праща Давида", "David's Sling"),
    ("cherub_sword", "Пламенный меч Херувима", "Cherub's Flaming Sword"),
    ("archistrategos_sword", "Меч архистратига", "Sword of the Archistrategos"),
    ("george_spear", "Копьё Георгия", "Spear of St George"),
    ("nimrod_bow", "Лук Нимрода", "Nimrod's Bow"),
    ("samson_jawbone", "Ослиная челюсть Самсона", "Samson's Jawbone"),
    ("gideon_jar", "Кувшин Гедеона", "Gideon's Pitcher"),
    ("war_sword", "Большой меч Войны", "Great Sword of War"),
    ("jericho_horn", "Трубы Иерихона", "Trumpets of Jericho"),
    ("aspen_stake", "Осиновый кол", "Aspen Stake"),
]

EXTRA_NAMES = {
    "item.divineblock.sling_stone": ("Гладкий камень", "Smooth Stone"),
    "item.divineblock.aspen_stake.tooltip": ("Народное поверье, не церковное учение", "A folk belief, not church teaching"),
    "effect.divineblock.rout": ("Смятение", "Rout"),
    "entity.divineblock.sling_stone": ("Камень пращи", "Sling Stone"),
    "entity.divineblock.thrown_spear": ("Копьё Георгия", "Spear of St George"),
    "tag.block.divineblock.jericho_breakable": ("Стены Иерихона", "Walls of Jericho"),
}

# Each weapon is made of the relic of the boss it recalls, with plain materials around it.
RECIPES = {
    "david_sling": (["L L", " S ", " S "], {"L": "minecraft:leather", "S": "minecraft:string"}),
    "cherub_sword": ([" F ", "GBG", " T "], {"F": "divineblock:cherub_flame", "G": "minecraft:gold_ingot", "B": "minecraft:blaze_rod", "T": "minecraft:stick"}),
    "archistrategos_sword": ([" W ", "IDI", " T "], {"W": "divineblock:wrath_sword_shard", "I": "minecraft:iron_ingot", "D": "minecraft:diamond", "T": "minecraft:stick"}),
    "george_spear": (["  S", " IR", "T  "], {"S": "divineblock:leviathan_scale", "I": "minecraft:iron_ingot", "R": "minecraft:red_wool", "T": "minecraft:stick"}),
    "nimrod_bow": ([" B ", "SWS"], {"B": "divineblock:babel_brick", "W": "minecraft:bow", "S": "minecraft:string"}),
    "samson_jawbone": (["BB ", "BK ", " L "], {"B": "minecraft:bone", "K": "minecraft:bone_block", "L": "minecraft:leather"}),
    "gideon_jar": (["T", "P"], {"T": "minecraft:torch", "P": "minecraft:flower_pot"}),
    "war_sword": ([" B ", "INI", " T "], {"B": "divineblock:broken_seal", "N": "minecraft:netherite_ingot", "I": "minecraft:iron_ingot", "T": "minecraft:stick"}),
    "jericho_horn": (["CC ", "CTC"], {"C": "minecraft:copper_ingot", "T": "divineblock:tocsin_tongue"}),
    "aspen_stake": (["T", "T", "P"], {"T": "minecraft:stick", "P": "minecraft:birch_planks"}),
}

# The walls Jericho's trumpets bring down: built stone, brick, wood and glass, never ore, bedrock, obsidian or the earth.
JERICHO_BREAKABLE = [
    "#minecraft:planks", "#minecraft:logs", "#minecraft:wooden_fences", "#minecraft:fence_gates", "#minecraft:walls", "#minecraft:wooden_doors",
    "#minecraft:wooden_trapdoors", "#minecraft:wooden_stairs", "#minecraft:wooden_slabs", "#minecraft:impermeable",
    "#minecraft:terracotta", "#minecraft:stone_bricks", "#minecraft:wool",
    "minecraft:stone", "minecraft:cobblestone", "minecraft:mossy_cobblestone", "minecraft:smooth_stone", "minecraft:stone_slab", "minecraft:stone_stairs",
    "minecraft:cobblestone_slab", "minecraft:cobblestone_stairs", "minecraft:cobblestone_wall", "minecraft:mossy_cobblestone_wall",
    "minecraft:granite", "minecraft:polished_granite", "minecraft:diorite", "minecraft:polished_diorite", "minecraft:andesite", "minecraft:polished_andesite",
    "minecraft:bricks", "minecraft:brick_slab", "minecraft:brick_stairs", "minecraft:brick_wall", "minecraft:nether_bricks", "minecraft:red_nether_bricks",
    "minecraft:sandstone", "minecraft:cut_sandstone", "minecraft:smooth_sandstone", "minecraft:chiseled_sandstone", "minecraft:red_sandstone", "minecraft:cut_red_sandstone",
    "minecraft:smooth_red_sandstone", "minecraft:clay", "minecraft:packed_mud", "minecraft:mud_bricks", "minecraft:deepslate_bricks", "minecraft:deepslate_tiles",
    "minecraft:polished_deepslate", "minecraft:cobbled_deepslate", "minecraft:blackstone", "minecraft:polished_blackstone", "minecraft:polished_blackstone_bricks",
    "minecraft:quartz_block", "minecraft:smooth_quartz", "minecraft:quartz_bricks", "minecraft:prismarine", "minecraft:prismarine_bricks", "minecraft:dark_prismarine",
    "minecraft:tuff", "minecraft:polished_tuff", "minecraft:tuff_bricks", "minecraft:calcite", "minecraft:dripstone_block",
    "minecraft:white_concrete", "minecraft:orange_concrete", "minecraft:magenta_concrete", "minecraft:light_blue_concrete", "minecraft:yellow_concrete",
    "minecraft:lime_concrete", "minecraft:pink_concrete", "minecraft:gray_concrete", "minecraft:light_gray_concrete", "minecraft:cyan_concrete",
    "minecraft:purple_concrete", "minecraft:blue_concrete", "minecraft:brown_concrete", "minecraft:green_concrete", "minecraft:red_concrete", "minecraft:black_concrete",
    "minecraft:iron_bars", "minecraft:glass_pane", "minecraft:bookshelf", "minecraft:hay_block",
]

# Weapons with a second sprite of light drawn at full brightness over the first (WeaponGlowRenderer on the client).
GLOWING = ["cherub_sword", "archistrategos_sword", "war_sword", "gideon_jar", "george_spear"]

# The vanilla hand-held transforms, enlarged: these are the greatest arms in the game and sit big in the hand.
BIG_DISPLAY = {
    "thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 4.5, 0.5], "scale": [1.15, 1.15, 1.15]},
    "thirdperson_lefthand": {"rotation": [0, 90, -55], "translation": [0, 4.5, 0.5], "scale": [1.15, 1.15, 1.15]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.0, 2.9, 1.0], "scale": [0.8, 0.8, 0.8]},
    "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.0, 2.9, 1.0], "scale": [0.8, 0.8, 0.8]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
}
IDENTITY_DISPLAY = {mode: {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]} for mode in BIG_DISPLAY}


def handheld(texture, parent="minecraft:item/handheld", overrides=None, display=None):
    model = {"parent": parent, "textures": {"layer0": "divineblock:item/" + texture}}
    if display: model["display"] = display
    if overrides: model["overrides"] = overrides
    return model


def stone_icon(seed):
    """A smooth grey river stone, 16 px, drawn by code."""
    import math
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    rand = random.Random(seed)
    for y in range(16):
        for x in range(16):
            dx, dy = (x - 7.5) / 6.2, (y - 8.0) / 5.0
            d = math.hypot(dx, dy)
            if d <= 1.0:
                light = 0.75 + 0.35 * (1 - math.hypot((x - 5.5) / 6.2, (y - 6) / 5.0)) + 0.08 * (rand.random() - 0.5)
                g = int(max(0, min(255, 150 * light)))
                img.putpixel((x, y), (g, g, int(g * 1.03) if g * 1.03 < 255 else 255, 255))
            elif d <= 1.16:
                img.putpixel((x, y), (70, 70, 76, 255))
    return img


def main():
    for lang, column in (("ru_ru", 1), ("en_us", 2)):
        path = os.path.join(ASSETS, "lang", lang + ".json")
        import json
        with open(path, encoding="utf-8") as f:
            names = json.load(f)
        for weapon in WEAPONS:
            names["item.divineblock." + weapon[0]] = weapon[column]
        for key, value in EXTRA_NAMES.items():
            names[key] = value[column - 1]
        with open(path, "rb") as f:
            crlf = b"\r\n" in f.read()
        write_json(path, names)
        if crlf:  # keep the file's own line endings, so the diff stays small
            with open(path, "rb") as f: data = f.read()
            with open(path, "wb") as f: f.write(data.replace(b"\n", b"\r\n"))

    # Item models: flat sprites held like a sword, big. The glowing ones are built-in-rendered from two plain models,
    # <id>_base and <id>_glow, whose own transforms are identity because the outer model's display has already been applied.
    models = os.path.join(ASSETS, "models", "item")
    for weapon in WEAPONS:
        if weapon[0] in ("nimrod_bow", "david_sling"): continue
        if weapon[0] in GLOWING:
            write_json(os.path.join(models, weapon[0] + ".json"), {"parent": "minecraft:builtin/entity", "display": BIG_DISPLAY})
            write_json(os.path.join(models, weapon[0] + "_base.json"), handheld(weapon[0], "minecraft:item/generated", display=IDENTITY_DISPLAY))
            write_json(os.path.join(models, weapon[0] + "_glow.json"), handheld(weapon[0] + "_glow", "minecraft:item/generated", display=IDENTITY_DISPLAY))
        else:
            write_json(os.path.join(models, weapon[0] + ".json"), handheld(weapon[0], display=BIG_DISPLAY))
    write_json(os.path.join(models, "nimrod_bow.json"), handheld("nimrod_bow", "minecraft:item/bow", [
        {"predicate": {"pulling": 1}, "model": "divineblock:item/nimrod_bow_pulling_0"},
        {"predicate": {"pulling": 1, "pull": 0.5}, "model": "divineblock:item/nimrod_bow_pulling_1"},
        {"predicate": {"pulling": 1, "pull": 0.9}, "model": "divineblock:item/nimrod_bow_pulling_2"}]))
    for i in range(3): write_json(os.path.join(models, "nimrod_bow_pulling_%d.json" % i), handheld("nimrod_bow_pulling_%d" % i, "minecraft:item/bow"))
    write_json(os.path.join(models, "david_sling.json"), handheld("david_sling", "minecraft:item/handheld", display=BIG_DISPLAY, overrides=[
        {"predicate": {"pulling": 1}, "model": "divineblock:item/david_sling_pulling_0"},
        {"predicate": {"pulling": 1, "pull": 0.5}, "model": "divineblock:item/david_sling_pulling_1"}]))
    for i in range(2): write_json(os.path.join(models, "david_sling_pulling_%d.json" % i), handheld("david_sling_pulling_%d" % i, display=BIG_DISPLAY))
    write_json(os.path.join(ASSETS, "models", "item", "sling_stone.json"), {"parent": "minecraft:item/generated", "textures": {"layer0": "divineblock:item/sling_stone"}})
    stone_icon(zlib.crc32(b"sling_stone")).save(os.path.join(ASSETS, "textures", "item", "sling_stone.png"))

    for weapon, (pattern, key) in RECIPES.items():
        write_json(os.path.join(DATA, "recipe", weapon + ".json"), {
            "type": "minecraft:crafting_shaped", "category": "equipment",
            "pattern": pattern, "key": {k: {"item": v} for k, v in key.items()},
            "result": {"id": "divineblock:" + weapon, "count": 1}})
    write_json(os.path.join(DATA, "recipe", "sling_stone.json"), {
        "type": "minecraft:crafting_shapeless", "category": "equipment",
        "ingredients": [{"item": "minecraft:cobblestone"}], "result": {"id": "divineblock:sling_stone", "count": 4}})

    os.makedirs(os.path.join(DATA, "tags", "block"), exist_ok=True)
    write_json(os.path.join(DATA, "tags", "block", "jericho_breakable.json"), {"replace": False, "values": JERICHO_BREAKABLE})

    effect_icon((0xF0, 0xD0, 0x80), (0x60, 0x30, 0x20), zlib.crc32(b"rout")).save(os.path.join(ASSETS, "textures", "mob_effect", "rout.png"))


if __name__ == "__main__":
    main()
