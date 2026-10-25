"""Village bar: textures, block/item models and blockstates of the bar furniture, and the bar building templates
(one per village type) for the village houses pools. Run from the repository root: python3 tools/bar/bar.py

The chair and table shapes follow skniro's Furniture (MIT, third_party/skniro-furniture); the stool, counter, keg, shelf,
bottles and mugs are drawn here. Wood, barrel and wool textures are vanilla's, referenced by name, not copied."""
import json
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
import nbt  # noqa: E402

ROOT = os.path.join("src", "main", "resources")
ASSETS = os.path.join(ROOT, "assets", "mobtalk")
DATA = os.path.join(ROOT, "data", "mobtalk")
DATA_VERSION = 3955  # 1.21.1


def write_json(path, value):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False, indent=2)
        f.write("\n")


# ---------------------------------------------------------------- textures

BOTTLES = {  # dark, mid, light, label
    "green": ((28, 78, 34), (46, 116, 52), (150, 214, 150), (226, 210, 160)),
    "brown": ((78, 42, 14), (122, 70, 26), (214, 160, 96), (232, 220, 180)),
    "wine": ((70, 10, 24), (122, 22, 42), (222, 118, 130), (236, 226, 196)),
    "clear": ((128, 150, 160), (184, 206, 212), (246, 252, 252), (240, 240, 240)),
}


def bottles_texture():
    """bar_bottles.png: four bottle colours side by side, x = 4k..4k+3. Rows 0-7 the body (rows 3-5 the label), rows 8-10 the
    neck, row 11 the lip, rows 12-13 the cork, rows 14-15 the body's top."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    cork_dark, cork_mid, cork_light = (112, 74, 38), (156, 108, 58), (190, 140, 84)
    for k, (dark, mid, light, label) in enumerate(BOTTLES.values()):
        x0 = 4 * k
        for y in range(8):
            row = [dark, light, mid, dark]
            if y == 0:
                row = [mid, light, light, mid]
            for i, c in enumerate(row):
                px[x0 + i, y] = c + (255,)
        for y in (3, 4, 5):
            for i in range(4):
                px[x0 + i, y] = label + (255,)
        # The label's print: a red band on the clear one (vodka), a dark band on the others.
        ink = (176, 28, 28) if k == 3 else tuple(max(0, v - 120) for v in label)
        for i in range(4):
            px[x0 + i, 4] = ink + (255,)
        for y in (8, 9, 10):
            for i, c in enumerate([mid, light, dark, dark]):
                px[x0 + i, y] = c + (255,)
        for i, c in enumerate([light, light, mid, mid]):
            px[x0 + i, 11] = c + (255,)
        for i, c in enumerate([cork_light, cork_mid, cork_mid, cork_dark]):
            px[x0 + i, 12] = c + (255,)
            px[x0 + i, 13] = (cork_mid if i < 2 else cork_dark) + (255,)
        for y in (14, 15):
            for i in range(4):
                px[x0 + i, y] = (light if (i + y) % 3 == 0 else mid) + (255,)
    return img


def mug_texture():
    """bar_mug.png: rows 0-5 the staves (x 0-7) with two iron hoops, x 8-15 rows 0-7 the foam, x 0-1 rows 8-11 the handle."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    staves = [(176, 128, 72), (150, 104, 56), (196, 150, 92), (138, 94, 50)]
    for y in range(8):
        for x in range(8):
            c = staves[x % 4]
            if y in (1, 5):
                c = (96, 96, 104) if x % 2 else (132, 132, 140)
            px[x, y] = c + (255,)
    foam = [(250, 246, 232), (236, 228, 204), (255, 255, 248)]
    for y in range(8):
        for x in range(8, 16):
            px[x, y] = foam[(x * 7 + y * 3) % 3] + (255,)
    for (x, y) in ((9, 2), (13, 5), (11, 6)):
        px[x, y] = (212, 200, 170, 255)
    for y in range(8, 12):
        px[0, y] = (120, 80, 40, 255)
        px[1, y] = (96, 62, 30, 255)
    # Beer seen over the rim on the side of the foam (x 0-7 row 12).
    for x in range(8):
        px[x, 12] = (214, 150, 40, 255) if x % 3 else (236, 184, 70, 255)
    return img


# ---------------------------------------------------------------- models

def face(tex, uv=None, cull=None):
    f = {"texture": tex}
    if uv is not None:
        f["uv"] = uv
    if cull:
        f["cullface"] = cull
    return f


def box(fr, to, tex, faces="nsewud", uvs=None):
    names = {"n": "north", "s": "south", "e": "east", "w": "west", "u": "up", "d": "down"}
    out = {}
    for ch in faces:
        t = tex[ch] if isinstance(tex, dict) else tex
        out[names[ch]] = face(t, (uvs or {}).get(ch))
    return {"from": fr, "to": to, "faces": out}


def model(textures, elements, parent="block/block", ambient=True):
    m = {"parent": parent, "textures": textures, "elements": elements}
    if not ambient:
        m["ambientocclusion"] = False
    return m


def bottle(x, y, z, kind, body=6, neck=3, w=3):
    """One slim bottle standing at (x, y, z) (its body's lower north-west corner): a w×w body, a one-pixel neck and its cork."""
    k = list(BOTTLES).index(kind)
    u = 4 * k
    side = [u, 8 - body, u + w, 8]
    els = [box([x, y, z], [x + w, y + body, z + w], "#bottles", "nsewud",
               {c: side for c in "nsew"} | {"u": [u, 14, u + w, 14 + w / 2], "d": [u, 14, u + w, 14 + w / 2]})]
    nx, nz = x + (w - 1) / 2, z + (w - 1) / 2
    els.append(box([nx, y + body, nz], [nx + 1, y + body + neck, nz + 1], "#bottles", "nsew",
                   {c: [u + 1, 8, u + 2, 8 + min(3, neck)] for c in "nsew"}))
    els.append(box([nx - 0.25, y + body + neck, nz - 0.25], [nx + 1.25, y + body + neck + 1, nz + 1.25], "#bottles", "nsewu",
                   {c: [u, 12, u + 1.5, 13] for c in "nsew"} | {"u": [u, 12, u + 1.5, 13.5]}))
    return els


def mug(x, y, z):
    """A beer mug: staves, foam over the rim and a handle on the east side."""
    els = [box([x, y, z], [x + 4, y + 5, z + 4], "#mug", "nsewd", {c: [0, 0, 4, 5] for c in "nsewd"})]
    els.append(box([x - 0.5, y + 5, z - 0.5], [x + 4.5, y + 6.5, z + 4.5], "#mug", "nsewu",
                   {c: [8, 0, 13, 1.5] for c in "nsew"} | {"u": [9, 1, 14, 6]}))
    els.append(box([x + 4, y + 1, z + 1.5], [x + 5.5, y + 4, z + 2.5], "#mug", "nsewud",
                   {c: [0, 8, 1, 11] for c in "nsewud"}))
    return els


def furniture_models():
    m = {}
    dark, stripped = "minecraft:block/dark_oak_planks", "minecraft:block/stripped_dark_oak_log"
    spruce, stripped_spruce = "minecraft:block/spruce_planks", "minecraft:block/stripped_spruce_log"
    # Counter: customers on the north side. Panelled front, a top that overhangs the customers' side.
    m["bar_counter"] = model({"particle": dark, "wood": dark, "top": stripped, "trim": stripped}, [
        box([0, 0, 1], [16, 14, 16], "#wood"),
        box([0, 0, 0.5], [16, 1.5, 1], "#trim"),  # kick plate
        box([1, 2.5, 0.5], [2.5, 12.5, 1], "#trim"), box([7.25, 2.5, 0.5], [8.75, 12.5, 1], "#trim"),
        box([13.5, 2.5, 0.5], [15, 12.5, 1], "#trim"),
        box([0, 12.5, 0.5], [16, 14, 1], "#trim"),
        box([0, 14, -1], [16, 16, 16], {"n": "#top", "s": "#top", "e": "#top", "w": "#top", "u": "#top", "d": "#top"}),
    ])
    # Bar stool: base, post, foot ring, seat and a red cushion.
    m["bar_stool"] = model({"particle": dark, "wood": dark, "metal": "minecraft:block/stripped_dark_oak_log", "cushion": "minecraft:block/red_wool"}, [
        box([5, 0, 5], [11, 1, 11], "#wood"),
        box([7, 1, 7], [9, 11, 9], "#metal"),
        box([5, 4, 5], [11, 5, 6], "#metal"), box([5, 4, 10], [11, 5, 11], "#metal"),
        box([5, 4, 6], [6, 5, 10], "#metal"), box([10, 4, 6], [11, 5, 10], "#metal"),
        box([6, 4, 7.5], [10, 5, 8.5], "#metal"), box([7.5, 4, 6], [8.5, 5, 10], "#metal"),
        box([4, 11, 4], [12, 12.5, 12], "#wood"),
        box([4.5, 12.5, 4.5], [11.5, 13.5, 11.5], "#cushion"),
    ])
    # Round table: cross foot, post, an octagon of two crossed boards.
    m["bar_table"] = model({"particle": spruce, "wood": spruce, "top": stripped_spruce}, [
        box([3, 0, 7], [13, 1, 9], "#wood"), box([7, 0, 3], [9, 1, 13], "#wood"),
        box([7, 1, 7], [9, 13, 9], "#wood"),
        box([1, 13, 3], [15, 15, 13], "#top"), box([3, 13, 1], [13, 15, 15], "#top"),
    ])
    # Chair (shape after skniro's Furniture normal_chair, MIT): the sitter faces north, the back is on the south side.
    m["bar_chair"] = model({"particle": spruce, "wood": spruce}, [
        box([12.5, 0, 12.5], [14, 8, 14], "#wood"), box([2, 0, 12.5], [3.5, 8, 14], "#wood"),
        box([12.5, 0, 2], [14, 8, 3.5], "#wood"), box([2, 0, 2], [3.5, 8, 3.5], "#wood"),
        box([2, 8, 2], [14, 9.5, 14], "#wood"),
        box([2, 9.5, 12.5], [3.5, 21, 14], "#wood", uvs={c: [2, 0, 3.5, 11.5] for c in "nsewud"}),
        box([12.5, 9.5, 12.5], [14, 21, 14], "#wood", uvs={c: [12.5, 0, 14, 11.5] for c in "nsewud"}),
        box([3.5, 17, 13], [12.5, 20.5, 14], "#wood", uvs={c: [3.5, 2, 12.5, 5.5] for c in "nsewud"}),
        box([3.5, 12, 13], [12.5, 14, 14], "#wood"),
    ])
    # Keg: a barrel lying on two cradles, its lid to the north with a brass tap.
    m["bar_keg"] = model({"particle": "minecraft:block/barrel_side", "side": "minecraft:block/barrel_side",
                          "lid": "minecraft:block/barrel_top", "wood": dark, "brass": "minecraft:block/gold_block"}, [
        box([2, 0, 2], [14, 3, 4], "#wood"), box([2, 0, 12], [14, 3, 14], "#wood"),
        {"from": [2, 2, 1], "to": [14, 14, 15], "faces": {
            "north": face("#lid", [2, 2, 14, 14]), "south": face("#lid", [2, 2, 14, 14]),
            "east": face("#side", [1, 2, 15, 14], None) | {"rotation": 90}, "west": face("#side", [1, 2, 15, 14]) | {"rotation": 90},
            "up": face("#side", [2, 1, 14, 15]), "down": face("#side", [2, 1, 14, 15])}},
        box([7, 5, -1], [9, 7, 1], "#brass"), box([7, 3.5, -2], [9, 5.5, -1], "#brass"),
        box([7.5, 7, -0.5], [8.5, 10, 0.5], "#wood"),
    ])
    # Wall shelf with bottles: the back against the south wall, two boards, three bottles on each.
    els = [box([0, 0, 15], [16, 16, 16], "#wood"),
           box([0, 0, 10], [16, 1.5, 15], "#wood"),
           box([0, 1.5, 10], [1, 13, 15], "#wood"), box([15, 1.5, 10], [16, 13, 15], "#wood")]
    for i, (kind, body) in enumerate([("green", 6), ("wine", 7), ("clear", 6), ("brown", 7)]):
        els += bottle(1.75 + i * 3.25, 1.5, 11, kind, body=body, neck=3)
    m["bar_shelf"] = model({"particle": spruce, "wood": spruce, "bottles": "mobtalk:block/bar_bottles"}, els)
    # Bottles to stand on the counter (or a table): three of different heights and a glass.
    els = bottle(3, 0, 6, "clear", body=7, neck=3) + bottle(7, 0, 4, "green", body=6, neck=3) + bottle(10, 0, 8, "wine", body=7, neck=3)
    m["bar_bottles"] = model({"particle": "mobtalk:block/bar_bottles", "bottles": "mobtalk:block/bar_bottles"}, els)
    # Mugs on a table: the table top is a pixel below the block's floor.
    els = mug(3, -1, 4) + mug(9, -1, 8)
    m["bar_mugs"] = model({"particle": "mobtalk:block/bar_mug", "mug": "mobtalk:block/bar_mug"}, els)
    return m


FACING = ["bar_counter", "bar_stool", "bar_chair", "bar_keg", "bar_shelf", "bar_bottles", "bar_mugs", "bar_table"]


def write_assets():
    tex = os.path.join(ASSETS, "textures", "block")
    os.makedirs(tex, exist_ok=True)
    bottles_texture().save(os.path.join(tex, "bar_bottles.png"))
    mug_texture().save(os.path.join(tex, "bar_mug.png"))
    for name, m in furniture_models().items():
        write_json(os.path.join(ASSETS, "models", "block", name + ".json"), m)
        write_json(os.path.join(ASSETS, "models", "item", name + ".json"), {"parent": "mobtalk:block/" + name})
        if name in FACING:
            variants = {f"facing={d}": ({"model": "mobtalk:block/" + name} | ({"y": r} if r else {}))
                        for d, r in (("north", 0), ("east", 90), ("south", 180), ("west", 270))}
        else:
            variants = {"": {"model": "mobtalk:block/" + name}}
        write_json(os.path.join(ASSETS, "blockstates", name + ".json"), {"variants": variants})


# ---------------------------------------------------------------- building

PALETTES = {
    "plains": dict(found="minecraft:cobblestone", floor="minecraft:oak_planks", wall="minecraft:oak_planks",
                   post="minecraft:oak_log", roof="minecraft:dark_oak", ceiling="minecraft:spruce_planks",
                   window="minecraft:glass_pane", door="minecraft:oak_door", sign="minecraft:oak_wall_sign", gable=True),
    "taiga": dict(found="minecraft:cobblestone", floor="minecraft:spruce_planks", wall="minecraft:spruce_planks",
                  post="minecraft:spruce_log", roof="minecraft:spruce", ceiling="minecraft:spruce_planks",
                  window="minecraft:glass_pane", door="minecraft:spruce_door", sign="minecraft:spruce_wall_sign", gable=True),
    "snowy": dict(found="minecraft:stone_bricks", floor="minecraft:spruce_planks", wall="minecraft:spruce_planks",
                  post="minecraft:stripped_spruce_log", roof="minecraft:spruce", ceiling="minecraft:spruce_planks",
                  window="minecraft:glass_pane", door="minecraft:spruce_door", sign="minecraft:spruce_wall_sign", gable=True),
    "savanna": dict(found="minecraft:terracotta", floor="minecraft:acacia_planks", wall="minecraft:acacia_planks",
                    post="minecraft:acacia_log", roof="minecraft:acacia", ceiling="minecraft:acacia_planks",
                    window="minecraft:glass_pane", door="minecraft:acacia_door", sign="minecraft:acacia_wall_sign", gable=True),
    "desert": dict(found="minecraft:sandstone", floor="minecraft:smooth_sandstone", wall="minecraft:smooth_sandstone",
                   post="minecraft:cut_sandstone", roof=None, ceiling="minecraft:smooth_sandstone",
                   window="minecraft:glass_pane", door="minecraft:jungle_door", sign="minecraft:birch_wall_sign", gable=False),
}
NAMES = {"plains": "Хмельной гусь", "taiga": "Сосновая шишка", "snowy": "Тёплый угол", "savanna": "Жаркий вечер",
         "desert": "Колодец"}

D = 11  # z 0..9 the house, z 10 the street side (the entrance jigsaw)
# The width: 13 (x 0..12); the desert's narrow plots take an 11 wide bar with a counter of seven.
WIDTH = {"desert": 11}
FRONT = 9


class Template:
    def __init__(self, width, height):
        self.size = (width, height, D)
        self.blocks = {}  # (x, y, z) -> (name, props, nbt)

    def set(self, x, y, z, name, nbt_tag=None, **props):
        self.blocks[(x, y, z)] = (name, {k: str(v).lower() for k, v in props.items()}, nbt_tag)

    def to_nbt(self):
        palette, index = [], {}
        blocks = []
        sx, sy, sz = self.size
        for y in range(sy):
            for z in range(sz):
                for x in range(sx):
                    name, props, tag = self.blocks.get((x, y, z), ("minecraft:air", {}, None))
                    key = (name, tuple(sorted(props.items())))
                    if key not in index:
                        index[key] = len(palette)
                        entry = {"Name": nbt.Tag(nbt.STRING, name)}
                        if props:
                            entry["Properties"] = nbt.c(**{k: nbt.Tag(nbt.STRING, v) for k, v in props.items()})
                        palette.append(nbt.c(**entry))
                    b = {"pos": nbt.Tag(nbt.LIST, [nbt.Tag(nbt.INT, x), nbt.Tag(nbt.INT, y), nbt.Tag(nbt.INT, z)], nbt.INT),
                         "state": nbt.Tag(nbt.INT, index[key])}
                    if tag is not None:
                        b["nbt"] = tag
                    blocks.append(nbt.c(**b))
        root = nbt.c(
            size=nbt.Tag(nbt.LIST, [nbt.Tag(nbt.INT, v) for v in self.size], nbt.INT),
            palette=nbt.Tag(nbt.LIST, palette, nbt.COMPOUND),
            blocks=nbt.Tag(nbt.LIST, blocks, nbt.COMPOUND),
            entities=nbt.Tag(nbt.LIST, [], nbt.COMPOUND),
            DataVersion=nbt.Tag(nbt.INT, DATA_VERSION))
        return nbt.dump("", root)


def s(v):
    return nbt.Tag(nbt.STRING, v)


def jigsaw_nbt(name, pool, target, final_state, joint="aligned"):
    return nbt.c(id=s("minecraft:jigsaw"), name=s(name), pool=s(pool), target=s(target), final_state=s(final_state),
                 joint=s(joint), selection_priority=nbt.Tag(nbt.INT, 0), placement_priority=nbt.Tag(nbt.INT, 0))


def sign_nbt(lines):
    def side(msgs):
        return nbt.c(messages=nbt.Tag(nbt.LIST, [s(json.dumps({"text": t}, ensure_ascii=False)) for t in msgs], nbt.STRING),
                     color=s("black"), has_glowing_text=nbt.Tag(nbt.BYTE, 0))
    return nbt.c(id=s("minecraft:sign"), front_text=side(lines), back_text=side(["", "", "", ""]),
                 is_waxed=nbt.Tag(nbt.BYTE, 1))


def building(kind):
    p = PALETTES[kind]
    W = WIDTH.get(kind, 13)
    M = W // 2  # the door's column
    t = Template(W, 11 if p["gable"] else 8)
    top = 5  # walls y 1..5, ceiling y 6
    for x in range(W):
        for z in range(D):
            if z == D - 1:
                t.set(x, 0, z, "minecraft:structure_void")
                for y in range(1, t.size[1]):
                    t.set(x, y, z, "minecraft:structure_void")
                continue
            t.set(x, 0, z, p["found"])
            edge_x, edge_z = x in (0, W - 1), z in (0, FRONT)
            for y in range(1, top + 1):
                if edge_x or edge_z:
                    corner = edge_x and edge_z or (edge_z and x in (M - 2, M + 2)) or (edge_x and z in (4, 5) and False)
                    if y == 1:
                        t.set(x, y, z, p["found"])
                    elif corner:
                        t.set(x, y, z, p["post"], axis="y") if "log" in p["post"] else t.set(x, y, z, p["post"])
                    else:
                        t.set(x, y, z, p["wall"])
                elif y == 1:
                    t.set(x, y, z, p["floor"])
            t.set(x, top + 1, z, p["ceiling"])
    # Windows: two on each side wall, two on the front, two at the back above the shelves' ends.
    for z in (3, 6):
        for y in (3, 4):
            t.set(0, y, z, p["window"], north=True, south=True, east=False, west=False, waterlogged=False)
            t.set(W - 1, y, z, p["window"], north=True, south=True, east=False, west=False, waterlogged=False)
    for x in (2, 3, W - 4, W - 3):
        for y in (3, 4):
            t.set(x, y, FRONT, p["window"], north=False, south=False, east=True, west=True, waterlogged=False)
    # Door in the middle of the front, entrance jigsaw on the street side.
    t.set(M, 2, FRONT, p["door"], facing="south", half="lower", hinge="left", open=False, powered=False)
    t.set(M, 3, FRONT, p["door"], facing="south", half="upper", hinge="left", open=False, powered=False)
    t.set(M, 1, D - 1, "minecraft:jigsaw", jigsaw_nbt("minecraft:building_entrance", "minecraft:empty",
                                                    "minecraft:building_entrance", "minecraft:structure_void"), orientation="south_up")
    # One villager lives here (the bartender's bed is not ours: any villager may come).
    t.set(M, 1, 6, "minecraft:jigsaw", jigsaw_nbt("minecraft:bottom", f"minecraft:village/{kind}/villagers", "minecraft:bottom",
                                                p["floor"], joint="rollable"), orientation="up_north")
    # Sign and torches over the door.
    t.set(M, 4, D - 1, p["sign"], sign_nbt(["", "БАР", "«" + NAMES[kind] + "»", ""]), facing="south", waterlogged=False)
    t.set(M - 1, 3, D - 1, "minecraft:wall_torch", facing="south")
    t.set(M + 1, 3, D - 1, "minecraft:wall_torch", facing="south")
    # Roof.
    if p["gable"]:
        stairs = p["roof"] + "_stairs"
        slab = p["roof"] + "_slab"
        for x in range(W):
            for i in range(5):
                y = top + 1 + i
                t.set(x, y, i, stairs, facing="south", half="bottom", shape="straight", waterlogged=False)
                t.set(x, y, FRONT - i, stairs, facing="north", half="bottom", shape="straight", waterlogged=False)
                if x in (0, W - 1) and i > 0:
                    for z in range(i + 1, FRONT - i):
                        t.set(x, y, z, p["wall"])
    else:
        for x in range(W):
            for z in range(D - 1):
                if x in (0, W - 1) or z in (0, FRONT):
                    t.set(x, top + 2, z, "minecraft:cut_sandstone_slab", type="bottom", waterlogged=False)
    # ---- Inside: back row z=1, bartender's aisle z=2, counter z=3, stools z=4, tables z=6..8.
    for x in range(M - 2, M + 3):
        t.set(x, 2, 1, "mobtalk:bar_keg", facing="south")
        t.set(x, 3, 1, "mobtalk:bar_shelf", facing="south")
        t.set(x, 4, 1, "mobtalk:bar_shelf", facing="south")
    t.set(1, 2, 1, "minecraft:barrel", facing="up", open=False)
    t.set(2, 2, 1, "minecraft:barrel", facing="up", open=False)
    t.set(1, 3, 1, "minecraft:barrel", facing="up", open=False)
    t.set(W - 3, 2, 1, "minecraft:jukebox", has_record=False)
    t.set(W - 2, 2, 1, "minecraft:barrel", facing="up", open=False)
    for x in range(1, W - 3):
        t.set(x, 2, 3, "mobtalk:bar_counter", facing="south")
    t.set(2, 3, 3, "mobtalk:bar_bottles", facing="south")
    t.set(W - 5, 3, 3, "mobtalk:bar_bottles", facing="west")
    for x in range(2, W - 4, 2):
        t.set(x, 2, 4, "mobtalk:bar_stool", facing="north")
    for tx in (2, W - 3):
        t.set(tx, 2, 7, "mobtalk:bar_table", facing="north")
        t.set(tx, 3, 7, "mobtalk:bar_mugs", facing="north" if tx == 2 else "east")
        t.set(tx, 2, 6, "mobtalk:bar_chair", facing="south")
        t.set(tx, 2, 8, "mobtalk:bar_chair", facing="north")
        t.set(tx - 1, 2, 7, "mobtalk:bar_chair", facing="east")
        t.set(tx + 1, 2, 7, "mobtalk:bar_chair", facing="west")
    for (x, z) in ((3, 6), (W - 4, 6), (M, 4)):
        t.set(x, top, z, "minecraft:lantern", hanging=True, waterlogged=False)
    return t


def write_buildings():
    out = os.path.join(DATA, "structure", "bar")
    os.makedirs(out, exist_ok=True)
    for kind in PALETTES:
        with open(os.path.join(out, kind + ".nbt"), "wb") as f:
            f.write(building(kind).to_nbt())


if __name__ == "__main__":
    write_assets()
    write_buildings()
    print("bar: assets and", len(PALETTES), "buildings written")
