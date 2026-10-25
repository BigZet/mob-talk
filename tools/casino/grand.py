"""The grand casino of a city, as two structure templates (the same building with either of the city's street connectors).

    python3 -I tools/casino/grand.py [plan OUT.png]

Writes src/main/resources/data/mobtalk/structure/casino/grand.nbt and grand_5th.nbt. The lot follows the city's own landmark
lots (tools/city/landmarks.py on the cities branch): 24 deep and 21 wide, the street to the west (x < 0), the ground surface at
y = 4, the entrance connector (minecraft:building_entrance, or building_entrance_5th) at (0, 5, 10). A city has at most one
(CasinoWorldgen registers both templates with UniqueVillagePieces).

The building: a white-and-gold palace three storeys tall, raised one step over the pavement. Outside, a red carpet between velvet
ropes under a canopy edged with running lights, «КАЗ★ИНО» in neon letters two blocks tall framed by bulbs, a glass curtain over it and a gold
crown on the roof. Inside, a cashier's hall at the door, a hall open to a glass roof with a fountain and two crystal chandeliers,
a grand stair to the gallery, slot machines along the walls, roulette and twenty-one tables in the wings, card tables in the
corners, a bar at the back, and up on the gallery the private tables, more machines and a second bar.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nbt as N  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "src", "main", "resources", "data", "mobtalk", "structure", "casino")
DATA_VERSION = 3955
SX, SY, SZ = 24, 27, 21
G = 4  # the ground surface, as on the city's lots
F = G + 1  # the casino's floor: one step above the pavement
MID = 10
GALLERY = F + 6  # the gallery's floor block
ROOF = F + 17

WALL, PILLAR, TRIM, PLINTH = "smooth_quartz", "quartz_pillar", "gold_block", "polished_blackstone_bricks"


class Lot:
    def __init__(self):
        self.g = {}

    def put(self, x, y, z, name, **props):
        if not (0 <= x < SX and 0 <= y < SY and 0 <= z < SZ):
            raise ValueError(f"outside the lot: {x} {y} {z} {name}")
        self.g[(x, y, z)] = ("minecraft:" + name if ":" not in name else name, {k: str(v).lower() for k, v in props.items()})

    def casino(self, x, y, z, name, **props):
        self.put(x, y, z, "mobtalk:" + name, **props)

    def fill(self, x0, y0, z0, x1, y1, z1, name, **props):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    self.put(x, y, z, name, **props)

    def name(self, x, y, z):
        return self.g.get((x, y, z), ("minecraft:air", {}))[0]

    def free(self, x, y, z):
        return self.name(x, y, z) == "minecraft:air"


def pillar(lot, x, z, y0, y1):
    for y in range(y0, y1 + 1):
        lot.put(x, y, z, PILLAR, axis="y")


def shell(lot):
    # the ground under the lot, the pavement, the air above
    lot.fill(0, 0, 0, SX - 1, G - 1, SZ - 1, "dirt")
    lot.fill(0, G, 0, 2, G, SZ - 1, "polished_andesite")
    lot.fill(0, G + 1, 0, SX - 1, SY - 1, SZ - 1, "air")
    # the floor slab and the walls
    lot.fill(3, G, 0, SX - 1, F, SZ - 1, PLINTH)
    for x in range(3, SX):
        for z in range(SZ):
            if x in (3, SX - 1) or z in (0, SZ - 1):
                for y in range(F + 1, ROOF):
                    lot.put(x, y, z, WALL)
                lot.put(x, F + 1, z, PLINTH)
                lot.put(x, GALLERY, z, TRIM)  # the floor line
                lot.put(x, ROOF - 1, z, TRIM)  # the cornice
    # pilasters: quartz pillars with gold capitals
    posts = [(3, z) for z in (0, 4, 16, 20)] + [(x, z) for x in (8, 13, 18, 23) for z in (0, 20)] + [(23, z) for z in (5, 10, 15)]
    for (x, z) in posts:
        pillar(lot, x, z, F + 2, ROOF - 2)
        lot.put(x, ROOF - 1, z, "chiseled_quartz_block")
    # windows: low ones in the wings, tall ones above the floor line
    def window(x, z):
        for y in list(range(F + 2, F + 5)) + list(range(GALLERY + 2, ROOF - 2)):
            lot.put(x, y, z, "glass")
    for x in (5, 6, 10, 11, 15, 16, 20, 21):
        window(x, 0)
        window(x, SZ - 1)
    for z in (2, 3, 7, 8, 12, 13, 17, 18):
        window(SX - 1, z)
    for z in (1, 2, 18, 19):
        window(3, z)
    # the roof, a glass skylight over the hall, the parapet
    lot.fill(3, ROOF, 0, SX - 1, ROOF, SZ - 1, WALL)
    lot.fill(6, ROOF, 7, 14, ROOF, 13, "glass")
    for x in range(3, SX):
        for z in range(SZ):
            if x in (3, SX - 1) or z in (0, SZ - 1):
                lot.put(x, ROOF + 1, z, "quartz_bricks")
    for (x, z) in ((3, 0), (3, SZ - 1), (SX - 1, 0), (SX - 1, SZ - 1)):
        lot.put(x, ROOF + 1, z, TRIM)
        lot.put(x, ROOF + 2, z, "end_rod", facing="up")


def facade(lot):
    # the way in: one step up, a five-wide opening between gold columns, a gold lintel
    for z in range(7, 14):
        lot.put(2, G + 1, z, "quartz_stairs", facing="east", half="bottom", shape="straight", waterlogged=False)
    lot.fill(3, F + 1, 8, 3, F + 4, 12, "air")
    for z in (7, 13):
        for y in range(F + 1, F + 6):
            lot.put(3, y, z, TRIM)
    lot.fill(3, F + 5, 7, 3, F + 5, 13, TRIM)
    # the sign: a black stone board hung on the facade, «КАЗ★ИНО» in neon letters two blocks tall on it, running lights round them
    lot.fill(2, F + 6, 2, 2, F + 11, SZ - 3, "polished_blackstone")
    for z in range(3, 18):
        lot.casino(1, F + 7, z, "casino_marquee", facing="west")
        lot.casino(1, F + 10, z, "casino_marquee", facing="west")
    for y in (F + 8, F + 9):
        lot.casino(1, y, 3, "casino_marquee", facing="west")
        lot.casino(1, y, 17, "casino_marquee", facing="west")
    z = 4
    for letter in (0, 1, 2, 6, 3, 4, 5):  # К А З ★ И Н О
        for dz in range(1 if letter == 6 else 2):
            lot.casino(1, F + 9, z + dz, "casino_letter", facing="west", letter=letter, part=dz)
            lot.casino(1, F + 8, z + dz, "casino_letter", facing="west", letter=letter, part=2 + dz)
        z += 1 if letter == 6 else 2
    # a glass curtain over the sign with gold mullions, a gold crown on the roof above it
    for z in range(7, 14):
        for y in range(F + 11, ROOF - 1):
            lot.put(3, y, z, TRIM if z in (7, 10, 13) else "glass")
    lot.fill(3, ROOF + 1, 6, 4, ROOF + 1, 14, "quartz_bricks")
    lot.fill(3, ROOF + 2, 7, 4, ROOF + 2, 13, TRIM)
    lot.fill(3, ROOF + 3, 9, 4, ROOF + 3, 11, TRIM)
    for z in (7, 13):
        lot.casino(2, ROOF + 2, z, "casino_marquee", facing="west")
    for z in range(9, 12):
        lot.casino(2, ROOF + 3, z, "casino_marquee", facing="west")
    lot.put(3, ROOF + 4, MID, "end_rod", facing="up")
    # the canopy over the carpet, its edges lit
    lot.fill(1, F + 5, 6, 2, F + 5, 14, "polished_blackstone_slab", type="top", waterlogged=False)
    for z in range(6, 15):
        lot.casino(0, F + 5, z, "casino_marquee", facing="west")
    for x in (1, 2):
        lot.casino(x, F + 5, 5, "casino_marquee", facing="north")
        lot.casino(x, F + 5, 15, "casino_marquee", facing="south")
    for z in (7, 13):
        lot.put(1, F + 4, z, "end_rod", facing="down")
    # the red carpet between velvet ropes; lamps at the lot's corners
    for x in (0, 1):
        for z in (9, 10, 11):
            lot.put(x, G + 1, z, "red_carpet")
        lot.casino(x, G + 1, 8, "casino_rope", facing="north")
        lot.casino(x, G + 1, 12, "casino_rope", facing="north")
    for z in (2, SZ - 3):
        for y in (G + 1, G + 2, G + 3):
            lot.put(1, y, z, "polished_blackstone_wall", up=True, north="none", south="none", east="none", west="none", waterlogged=False)
        lot.put(1, G + 4, z, "lantern", hanging=False, waterlogged=False)


def floors(lot):
    # the hall's carpet: red with a gold lattice; the cashiers' hall by the door in black and white marble
    for x in range(4, SX - 1):
        for z in range(1, SZ - 1):
            if x <= 6:
                lot.put(x, F, z, "polished_blackstone" if (x + z) % 2 else "polished_diorite")
            else:
                lot.put(x, F, z, "yellow_wool" if (x + z) % 6 == 0 or (x - z) % 6 == 0 else "red_wool")
    # the gallery: over the two wings and the back, open over the hall in the middle
    for x in range(4, SX - 1):
        for z in range(1, SZ - 1):
            if z <= 5 or z >= 15 or x >= 17:
                lot.put(x, GALLERY, z, WALL)
                lot.put(x, GALLERY + 1, z, "red_carpet")
    # velvet ropes along the gallery's edge (and they are where the bouncers stand)
    for x in range(4, 17):
        lot.casino(x, GALLERY + 1, 5, "casino_rope", facing="north")
        lot.casino(x, GALLERY + 1, 15, "casino_rope", facing="north")
    for z in list(range(6, 9)) + list(range(12, 15)):
        lot.casino(17, GALLERY + 1, z, "casino_rope", facing="east")
    # the grand stair up to the back of the gallery
    for i in range(6):
        x = 11 + i
        for z in (9, 10, 11):
            for y in range(F + 1, F + 1 + i):
                lot.put(x, y, z, WALL)
            lot.put(x, F + 1 + i, z, "quartz_stairs", facing="east", half="bottom", shape="straight", waterlogged=False)
    for z in (9, 10, 11):
        lot.put(17, GALLERY + 1, z, "red_carpet")
    # the fountain in the middle of the hall: a pool sunk into the floor round a gold column with a lantern and a spark on top
    for x in range(7, 10):
        for z in range(9, 12):
            lot.put(x, F, z, "water", level=0)
    lot.put(8, F, MID, TRIM)
    lot.put(8, F + 1, MID, TRIM)
    lot.put(8, F + 2, MID, "sea_lantern")
    lot.put(8, F + 3, MID, TRIM)
    lot.put(8, F + 4, MID, "end_rod", facing="up")


def furniture(lot):
    stand, up = F + 1, GALLERY + 1

    def slot(x, y, z, facing):
        lot.casino(x, y, z, "casino_slot_machine", facing=facing, half="lower")
        lot.casino(x, y + 1, z, "casino_slot_machine", facing=facing, half="upper")

    def roulette(x, y, z, facing, stools_z):
        lot.casino(x, y, z, "casino_roulette_table", facing=facing)
        lot.casino(x - 1, y, z, "casino_part")
        lot.casino(x + 1, y, z, "casino_part")
        for sx in (x - 1, x + 1):
            lot.casino(sx, y, stools_z, "casino_bar_stool", facing=facing)

    def twenty_one(x, y, z, facing):
        """facing north or south: the players' side is that way, their chairs one further."""
        d = -1 if facing == "north" else 1
        lot.casino(x, y, z, "casino_blackjack_table", facing=facing)
        for (dx, dz) in ((-1, 0), (1, 0), (-1, d), (0, d), (1, d)):
            lot.casino(x + dx, y, z + dz, "casino_part")
        for dx in (-1, 0, 1):
            lot.casino(x + dx, y, z + 2 * d, "casino_chair", facing="south" if d < 0 else "north")

    def cards(x, y, z):
        """A card table facing west, chairs on both long sides."""
        lot.casino(x, y, z, "casino_poker_table", facing="west")
        for (dx, dz) in ((0, -1), (0, 1), (-1, -1), (-1, 0), (-1, 1)):
            lot.casino(x + dx, y, z + dz, "casino_part")
        for dz in (-1, 0, 1):
            lot.casino(x - 2, y, z + dz, "casino_chair", facing="east")
            lot.casino(x + 1, y, z + dz, "casino_chair", facing="west")

    def bar(counter_x, z0, z1, y, skip=()):
        for z in range(z0, z1 + 1):
            if z in skip:
                continue
            lot.casino(counter_x, y, z, "casino_bar_counter", facing="west")
            lot.casino(SX - 2, y, z, "casino_bar_shelf", facing="west")
            if (z - z0) % 2 == 0:
                lot.casino(counter_x - 1, y, z, "casino_bar_stool", facing="west")

    # the cashiers' hall: three windows either side of the way in
    for x in (4, 5, 6):
        lot.casino(x, stand, 7, "casino_cashier", facing="south")
        lot.casino(x, stand, 13, "casino_cashier", facing="north")
    # the wings: machines along the walls, roulette in the north wing, twenty-one in the south
    for x in range(7, 17):
        slot(x, stand, 1, "south")
        slot(x, stand, SZ - 2, "north")
    for x in (7, 9, 11, 13, 15):
        lot.casino(x, stand, 2, "casino_bar_stool", facing="north")
        lot.casino(x, stand, SZ - 3, "casino_bar_stool", facing="south")
    roulette(9, stand, 4, "south", 5)
    roulette(14, stand, 4, "south", 5)
    twenty_one(9, stand, 16, "north")
    twenty_one(14, stand, 16, "north")
    # the back: card tables in the corners, the bar between them
    cards(19, stand, 3)
    cards(19, stand, 17)
    bar(20, 6, 14, stand)
    lot.casino(20, stand + 1, 8, "casino_chips", facing="west", chips=4)
    lot.casino(20, stand + 1, 12, "casino_chips", facing="north", chips=2)
    # the gallery: private card tables in the north, roulette and twenty-one in the south, machines in the corners, the second bar
    cards(8, up, 3)
    cards(14, up, 3)
    roulette(8, up, 18, "north", 17)
    twenty_one(14, up, 18, "north")
    for x in range(18, SX - 1):
        slot(x, up, 1, "south")
        slot(x, up, SZ - 2, "north")
    bar(21, 6, 14, up, skip=(9, 10, 11))
    lot.casino(21, up + 1, 7, "casino_chips", facing="west", chips=3)
    # light: two crystal chandeliers over the hall, small ones under the gallery and the roof
    for x in (7, 13):
        lot.casino(x, ROOF - 1, MID, "casino_grand_chandelier", facing="north")
    for z in (3, SZ - 4):
        for x in (8, 12, 16, 20):
            lot.casino(x, GALLERY - 1, z, "casino_chandelier", facing="north")
            lot.casino(x, ROOF - 1, z, "casino_chandelier", facing="north")
    for z in (7, 13):
        lot.casino(19, GALLERY - 1, z, "casino_chandelier", facing="north")
        lot.casino(20, ROOF - 1, z, "casino_chandelier", facing="north")

def villagers():
    """Four bouncers: two at the ropes on the pavement, two inside by the cashiers."""
    out = []
    for (x, z) in ((1, 7), (1, 13), (4, 8), (4, 12)):
        y = G + 1 if x < 3 else F + 1
        v = N.c(id=N.Tag(N.STRING, "minecraft:villager"), PersistenceRequired=N.Tag(N.BYTE, 1), Xp=N.Tag(N.INT, 10),
                VillagerData=N.c(profession=N.Tag(N.STRING, "mobtalk:casino_bouncer"), level=N.Tag(N.INT, 2),
                                 type=N.Tag(N.STRING, "minecraft:plains")),
                Rotation=N.Tag(N.LIST, [N.Tag(N.FLOAT, 270.0), N.Tag(N.FLOAT, 0.0)], N.FLOAT),
                Pos=N.Tag(N.LIST, [N.Tag(N.DOUBLE, x + 0.5), N.Tag(N.DOUBLE, float(y)), N.Tag(N.DOUBLE, z + 0.5)], N.DOUBLE))
        out.append(N.c(blockPos=N.Tag(N.LIST, [N.Tag(N.INT, x), N.Tag(N.INT, y), N.Tag(N.INT, z)], N.INT),
                       pos=N.Tag(N.LIST, [N.Tag(N.DOUBLE, x + 0.5), N.Tag(N.DOUBLE, float(y)), N.Tag(N.DOUBLE, z + 0.5)], N.DOUBLE), nbt=v))
    return out


def build():
    lot = Lot()
    shell(lot)
    facade(lot)
    floors(lot)
    furniture(lot)
    lot.put(0, G + 1, MID, "jigsaw", orientation="west_up")
    return lot


def write(name, lot, connector):
    jigsaw = N.c(final_state=N.Tag(N.STRING, "minecraft:red_carpet"), name=N.Tag(N.STRING, connector),
                 pool=N.Tag(N.STRING, "minecraft:empty"), joint=N.Tag(N.STRING, "aligned"), id=N.Tag(N.STRING, "minecraft:jigsaw"),
                 target=N.Tag(N.STRING, "minecraft:empty"), selection_priority=N.Tag(N.INT, 0), placement_priority=N.Tag(N.INT, 0))
    palette, index, blocks = [], {}, []
    for pos in sorted(lot.g, key=lambda q: (q[1], q[2], q[0])):
        bname, props = lot.g[pos]
        key = (bname, tuple(sorted(props.items())))
        if key not in index:
            index[key] = len(palette)
            entry = {"Name": N.Tag(N.STRING, bname)}
            if props:
                entry["Properties"] = N.Tag(N.COMPOUND, {k: N.Tag(N.STRING, v) for k, v in sorted(props.items())})
            palette.append(N.Tag(N.COMPOUND, entry))
        b = {"pos": N.Tag(N.LIST, [N.Tag(N.INT, v) for v in pos], N.INT), "state": N.Tag(N.INT, index[key])}
        if bname == "minecraft:jigsaw":
            b["nbt"] = jigsaw
        blocks.append(N.Tag(N.COMPOUND, b))
    root = N.c(DataVersion=N.Tag(N.INT, DATA_VERSION), size=N.Tag(N.LIST, [N.Tag(N.INT, v) for v in (SX, SY, SZ)], N.INT),
               palette=N.Tag(N.LIST, palette, N.COMPOUND), blocks=N.Tag(N.LIST, blocks, N.COMPOUND),
               entities=N.Tag(N.LIST, villagers(), N.COMPOUND))
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, name + ".nbt"), "wb") as f:
        f.write(N.dump("", root))


def plan(out, lot):
    """Top-down plans of the ground floor and the gallery, one letter per block."""
    from PIL import Image, ImageDraw
    cell = 26
    img = Image.new("RGB", (SZ * cell * 2 + cell, SX * cell), (30, 30, 34))
    d = ImageDraw.Draw(img)
    for k, y in enumerate((F + 1, GALLERY + 1)):
        ox = k * (SZ * cell + cell)
        for x in range(SX):
            for z in range(SZ):
                name = lot.name(x, y, z).split(":")[-1]
                colour = (60, 60, 66) if name == "air" else (150, 140, 120)
                if name.startswith("casino_"):
                    colour = (180, 40, 50)
                d.rectangle([ox + z * cell, x * cell, ox + z * cell + cell - 2, x * cell + cell - 2], fill=colour)
                d.text((ox + z * cell + 2, x * cell + 7), name.replace("casino_", "")[:4], fill=(255, 255, 255))
    img.save(out)


def main():
    lot = build()
    write("grand", lot, "minecraft:building_entrance")
    write("grand_5th", lot, "minecraft:building_entrance_5th")
    if len(sys.argv) > 2 and sys.argv[1] == "plan":
        plan(sys.argv[2], lot)
    print("grand casino:", len(lot.g), "blocks")


if __name__ == "__main__":
    main()
