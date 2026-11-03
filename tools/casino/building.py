"""The village casino as structure templates, one per village kind (plains, desert, savanna, snowy, taiga).

    python3 -I tools/casino/building.py [plan OUT.png]

Writes src/main/resources/data/mobtalk/structure/casino/<kind>.nbt. The house joins the village's house pool (CasinoWorldgen) and is
built like a vanilla house: its jigsaw (name and target minecraft:building_entrance) at x=0 in front of the door meets the street.
The plan is hand-laid here, block by block; the furniture is ours (tools/casino/decor.py), the walls take the village's own materials.

Plan (x away from the street, z across; the door at x=1, z=5..6):
  x=2      the velvet ropes either side of the way in, a bouncer by each
  z=1      five slot machines along the left wall, stools in front
  x=9..10  roulette in the left corner, the table for 21 in the middle (dealer at x=11), stools and chairs on the players' side
  z=8..9   the card table on the right, chairs on both long sides
  x=10..12 the bar in the right corner: counter, the barman's aisle, the back bar against the wall
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nbt as N  # noqa: E402 (tools/casino/nbt.py, a copy of tools/city/nbt.py)

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "src", "main", "resources", "data", "mobtalk", "structure", "casino")
DATA_VERSION = 3955  # 1.21.1
SX, SY, SZ = 14, 9, 12

KINDS = {
    "plains": dict(wall="oak_planks", pillar="stripped_dark_oak_log", base="stone_bricks", roof="dark_oak_planks", trim="dark_oak_planks",
                   border="dark_oak_planks", door="dark_oak_door", villager="plains", step="stone_brick_stairs"),
    "desert": dict(wall="smooth_sandstone", pillar="cut_sandstone", base="sandstone", roof="smooth_sandstone", trim="cut_sandstone",
                   border="orange_terracotta", door="jungle_door", villager="desert", step="sandstone_stairs"),
    "savanna": dict(wall="acacia_planks", pillar="stripped_acacia_log", base="cobblestone", roof="acacia_planks", trim="red_terracotta",
                    border="dark_oak_planks", door="acacia_door", villager="savanna", step="cobblestone_stairs"),
    "snowy": dict(wall="white_terracotta", pillar="stripped_spruce_log", base="stone_bricks", roof="spruce_planks", trim="spruce_planks",
                  border="spruce_planks", door="spruce_door", villager="snow", step="stone_brick_stairs"),
    "taiga": dict(wall="spruce_planks", pillar="spruce_log", base="cobblestone", roof="dark_oak_planks", trim="spruce_planks",
                  border="dark_oak_planks", door="spruce_door", villager="taiga", step="cobblestone_stairs"),
}


def build(kind, p):
    g = {}  # (x, y, z) -> (name, props)

    def put(x, y, z, name, **props):
        g[(x, y, z)] = ("minecraft:" + name if ":" not in name else name, {k: str(v).lower() for k, v in props.items()})

    def casino(x, y, z, name, **props):
        put(x, y, z, "mobtalk:" + name, **props)

    # the street side: air above, a path in front of the door, the jigsaw
    for y in range(1, SY):
        for z in range(SZ):
            put(0, y, z, "air")
    put(0, 0, 5, "dirt_path")
    put(0, 0, 6, "dirt_path")
    put(0, 1, 6, "red_carpet")  # the red carpet to the door (the jigsaw beside it turns into the other half)
    # shell: floor, walls with pillars, roof
    for x in range(1, SX):
        for z in range(SZ):
            edge = x in (1, SX - 1) or z in (0, SZ - 1)
            inner_edge = x in (2, SX - 2) or z in (1, SZ - 2)
            put(x, 0, z, p["base"] if edge else (p["border"] if inner_edge else "red_wool"))
            for y in range(1, 6):
                if edge:
                    pillar = (x in (1, SX - 1, 7)) and z in (0, SZ - 1) or (x in (1, SX - 1) and z in (0, SZ - 1))
                    put(x, y, z, p["pillar"], axis="y") if pillar and "log" in p["pillar"] else put(x, y, z, p["pillar"] if pillar else p["wall"])
                else:
                    put(x, y, z, "air")
            put(x, 6, z, p["roof"])
            if edge:
                put(x, 7, z, p["trim"])
            else:
                put(x, 7, z, "air")
            put(x, 8, z, "air")
    # false front over the door: the wall climbs two blocks, stepped
    for z in range(SZ):
        put(1, 7, z, p["trim"])
        if 2 <= z <= 9:
            put(1, 8, z, p["trim"])
    put(1, 8, 5, "glowstone")
    put(1, 8, 6, "glowstone")
    # the front: marquee lights either side of the sign, pillars by the door, a red awning over it
    for z in (3, 8):
        put(1, 4, z, "glowstone")
    for z in (4, 7):
        for y in (1, 2, 3):
            put(1, y, z, p["pillar"], axis="y") if "log" in p["pillar"] else put(1, y, z, p["pillar"])
    for z in range(4, 8):
        put(0, 3, z, "crimson_trapdoor", facing="west", half="top", open=False, powered=False, waterlogged=False)
    # windows
    for x in (3, 4, 9, 10):
        for y in (2, 3):
            put(x, y, 0, "glass_pane", east=True, west=True)
            put(x, y, SZ - 1, "glass_pane", east=True, west=True)
    for z in (2, 3, 8, 9):
        for y in (2, 3):
            put(1, y, z, "glass_pane", north=True, south=True)
    # the door: a double door, the sign above it on the street side
    for z, hinge in ((5, "left"), (6, "right")):
        put(1, 1, z, p["door"], facing="east", half="lower", hinge=hinge, open=False, powered=False)
        put(1, 2, z, p["door"], facing="east", half="upper", hinge=hinge, open=False, powered=False)
    casino(0, 4, 6, "casino_sign", facing="west")
    # inside: the ropes by the way in (the bouncers' posts)
    casino(2, 1, 4, "casino_rope", facing="north")
    casino(2, 1, 7, "casino_rope", facing="north")
    # slot machines along the left wall, stools in front of every other one
    for x in range(4, 9):
        casino(x, 1, 1, "casino_slot_machine", facing="south", half="lower")
        casino(x, 2, 1, "casino_slot_machine", facing="south", half="upper")
    for x in (4, 6, 8):
        casino(x, 1, 2, "casino_bar_stool", facing="north")
    # roulette in the left corner, its long side to the room
    casino(10, 1, 2, "casino_roulette_table", facing="west")
    casino(10, 1, 1, "casino_part")
    casino(10, 1, 3, "casino_part")
    casino(9, 1, 1, "casino_bar_stool", facing="east")
    casino(9, 1, 3, "casino_bar_stool", facing="east")
    # the table for 21 in the middle, facing the door; the dealer's place behind it at x=11
    casino(10, 1, 5, "casino_blackjack_table", facing="west")
    for (x, z) in ((10, 4), (10, 6), (9, 4), (9, 5), (9, 6)):
        casino(x, 1, z, "casino_part")
    for z in (4, 5, 6):
        casino(8, 1, z, "casino_chair", facing="east")
    # the card table on the right
    casino(5, 1, 9, "casino_poker_table", facing="north")
    for (x, z) in ((4, 9), (6, 9), (4, 8), (5, 8), (6, 8)):
        casino(x, 1, z, "casino_part")
    for x in (4, 5, 6):
        casino(x, 1, 10, "casino_chair", facing="north")
        casino(x, 1, 7, "casino_chair", facing="south")
    # the bar in the right corner
    for z in (8, 9, 10):
        casino(10, 1, z, "casino_bar_counter", facing="west")
        casino(12, 1, z, "casino_bar_shelf", facing="west")
        casino(9, 1, z, "casino_bar_stool", facing="west")
    casino(10, 2, 9, "casino_chips", facing="west", chips=3)
    casino(10, 2, 10, "casino_chips", facing="north", chips=1)
    # chandeliers under the ceiling
    for (x, z) in ((4, 4), (4, 8), (9, 2), (7, 6), (10, 9)):
        casino(x, 5, z, "casino_chandelier", facing="north")
    # the jigsaw that meets the street
    put(0, 1, 5, "jigsaw", orientation="west_up")
    jigsaw = N.c(final_state=N.Tag(N.STRING, "minecraft:red_carpet"), name=N.Tag(N.STRING, "minecraft:building_entrance"),
                 pool=N.Tag(N.STRING, "minecraft:empty"), joint=N.Tag(N.STRING, "aligned"), id=N.Tag(N.STRING, "minecraft:jigsaw"),
                 target=N.Tag(N.STRING, "minecraft:building_entrance"), selection_priority=N.Tag(N.INT, 0),
                 placement_priority=N.Tag(N.INT, 0))
    # two bouncers, one by each rope
    entities = []
    for (x, z, yaw) in ((2, 2, 270.0), (2, 9, 270.0)):
        villager = N.c(id=N.Tag(N.STRING, "minecraft:villager"), PersistenceRequired=N.Tag(N.BYTE, 1), Xp=N.Tag(N.INT, 10),
                       VillagerData=N.c(profession=N.Tag(N.STRING, "mobtalk:casino_bouncer"), level=N.Tag(N.INT, 2),
                                        type=N.Tag(N.STRING, "minecraft:" + p["villager"])),
                       Rotation=N.Tag(N.LIST, [N.Tag(N.FLOAT, yaw), N.Tag(N.FLOAT, 0.0)], N.FLOAT),
                       Pos=N.Tag(N.LIST, [N.Tag(N.DOUBLE, x + 0.5), N.Tag(N.DOUBLE, 1.0), N.Tag(N.DOUBLE, z + 0.5)], N.DOUBLE))
        entities.append(N.c(blockPos=N.Tag(N.LIST, [N.Tag(N.INT, x), N.Tag(N.INT, 1), N.Tag(N.INT, z)], N.INT),
                            pos=N.Tag(N.LIST, [N.Tag(N.DOUBLE, x + 0.5), N.Tag(N.DOUBLE, 1.0), N.Tag(N.DOUBLE, z + 0.5)], N.DOUBLE),
                            nbt=villager))
    return g, jigsaw, entities


def write(kind, g, jigsaw, entities):
    palette, index, blocks = [], {}, []
    for pos in sorted(g, key=lambda q: (q[1], q[2], q[0])):
        name, props = g[pos]
        key = (name, tuple(sorted(props.items())))
        if key not in index:
            index[key] = len(palette)
            entry = {"Name": N.Tag(N.STRING, name)}
            if props:
                entry["Properties"] = N.Tag(N.COMPOUND, {k: N.Tag(N.STRING, v) for k, v in sorted(props.items())})
            palette.append(N.Tag(N.COMPOUND, entry))
        b = {"pos": N.Tag(N.LIST, [N.Tag(N.INT, v) for v in pos], N.INT), "state": N.Tag(N.INT, index[key])}
        if name == "minecraft:jigsaw":
            b["nbt"] = jigsaw
        blocks.append(N.Tag(N.COMPOUND, b))
    root = N.c(DataVersion=N.Tag(N.INT, DATA_VERSION), size=N.Tag(N.LIST, [N.Tag(N.INT, v) for v in (SX, SY, SZ)], N.INT),
               palette=N.Tag(N.LIST, palette, N.COMPOUND), blocks=N.Tag(N.LIST, blocks, N.COMPOUND),
               entities=N.Tag(N.LIST, entities, N.COMPOUND))
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, kind + ".nbt"), "wb") as f:
        f.write(N.dump("", root))


def plan(out):
    """Top-down plan of the plains casino at the furniture layer, one letter per block, for a quick check."""
    from PIL import Image, ImageDraw
    g, _, _ = build("plains", KINDS["plains"])
    cell = 28
    img = Image.new("RGB", (SZ * cell, SX * cell), (30, 30, 34))
    d = ImageDraw.Draw(img)
    for x in range(SX):
        for z in range(SZ):
            name = g.get((x, 1, z), ("", {}))[0].split(":")[-1]
            colour = (60, 60, 66) if name in ("air", "") else (150, 120, 90)
            if name.startswith("casino_"):
                colour = (180, 40, 50)
            d.rectangle([z * cell, x * cell, z * cell + cell - 2, x * cell + cell - 2], fill=colour)
            d.text((z * cell + 3, x * cell + 8), name.replace("casino_", "")[:4], fill=(255, 255, 255))
    img.save(out)


def main():
    for kind, p in KINDS.items():
        write(kind, *build(kind, p))
    if len(sys.argv) > 2 and sys.argv[1] == "plan":
        plan(sys.argv[2])
    print("casino buildings:", ", ".join(KINDS))


if __name__ == "__main__":
    main()
