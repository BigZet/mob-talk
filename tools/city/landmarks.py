"""Local landmark buildings after the Pushchino panoramas, built from scratch like the yards: a house of everyday services
(Дом быта, prospekt Nauki 32V), a hotel with a restaurant, a red-brick school with a pitched roof and a railed yard,
a two-storey shop pavilion with a yellow band (Универсам), and a paved square with a fountain.
Each hangs on a street like a house: the street is to the west (x < 0), the entrance connector at (0, G + 1, z).
pieces.py calls build_all() after the yards and adds a few of them to the house pools.
"""
import random

from yards import G, Yard

SIZE = (24, 30, 21)  # depth (away from the street), height, width
FRONT = 3  # the facade line; x < FRONT is pavement
LIGHT = {"level": "13", "waterlogged": "false"}
ALL_SIDES = {k: "true" for k in ("up", "down", "north", "south", "east", "west")}


class Lot(Yard):
    def __init__(self, r, sign_nbt):
        super().__init__(r, SIZE)
        self.sign_nbt = sign_nbt
        sx, _, sz = SIZE
        for x in range(FRONT):
            for z in range(sz):
                self.ground(x, z, "mobtalk:paving_grey")

    def block(self, x0, y0, z0, x1, y1, z1, name, props=None):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    self.set(x, y, z, name, props)

    def sign(self, x, y, z, lines, facing="west"):
        """A wall sign hanging on the block behind it (east of it when it faces west)."""
        self.set(x, y, z, "minecraft:dark_oak_wall_sign", {"facing": facing, "waterlogged": "false"}, self.sign_nbt(lines))

    def door(self, x, z, wood="dark_oak", facing="west"):
        for half, dy in (("lower", 1), ("upper", 2)):
            self.set(x, G + dy, z, f"minecraft:{wood}_door", {"facing": facing, "half": half, "hinge": "left", "open": "false",
                                                              "powered": "false"})

    def building(self, x0, z0, x1, z1, floors, wall, trim, glass="minecraft:glass", ground_glass=None, band=None):
        """A box of flats or offices: storeys four blocks tall, windows in pairs, trimmed corners and floor lines, a flat
        roof with a parapet, invisible lights inside so nothing spawns there. Returns the roof level."""
        for f in range(floors):
            y0 = G + 1 + 4 * f
            self.block(x0, y0 - 1, z0, x1, y0 - 1, z1, trim if f else "minecraft:polished_andesite")  # the floor
            for y in range(y0, y0 + 3):
                for x in range(x0, x1 + 1):
                    for z in range(z0, z1 + 1):
                        edge_x, edge_z = x in (x0, x1), z in (z0, z1)
                        if not (edge_x or edge_z):
                            self.set(x, y, z, "minecraft:air")
                            continue
                        corner = edge_x and edge_z
                        along = z if edge_x else x
                        lo, hi = (z0, z1) if edge_x else (x0, x1)
                        if corner:
                            self.set(x, y, z, trim)
                        elif f == 0 and ground_glass and y < y0 + 3:
                            self.set(x, y, z, ground_glass if (along - lo) % 4 else trim)
                        elif y > y0 and (along - lo) % 3 != 0 and lo < along < hi:
                            self.set(x, y, z, glass)
                        elif band and y == y0 and f > 0:
                            self.set(x, y, z, band)
                        else:
                            self.set(x, y, z, wall)
            self.set((x0 + x1) // 2, y0 + 2, (z0 + z1) // 2, "minecraft:light", LIGHT)
        top = G + 1 + 4 * floors - 1
        self.block(x0, top, z0, x1, top, z1, trim)
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if x in (x0, x1) or z in (z0, z1):
                    self.set(x, top + 1, z, trim)
        return top

    def pave_front(self, z0, z1, x1=FRONT - 1):
        for x in range(0, x1 + 1):
            for z in range(z0, z1 + 1):
                self.ground(x, z, "mobtalk:paving_red" if x == 0 else "mobtalk:paving_grey")


def dom_byta(y):
    """Дом быта: three storeys of yellow brick, a glazed ground floor of little shops, a glass porch in front."""
    sx, sy, sz = SIZE
    y.building(FRONT + 3, 2, sx - 3, sz - 3, 3, "minecraft:smooth_sandstone", "minecraft:cut_sandstone",
               ground_glass="minecraft:light_blue_stained_glass")
    # the glass porch with the entrance
    y.block(FRONT, G + 1, 6, FRONT + 3, G + 3, sz - 7, "minecraft:light_blue_stained_glass")
    y.block(FRONT + 1, G + 1, 7, FRONT + 3, G + 3, sz - 8, "minecraft:air")
    y.block(FRONT, G + 4, 5, FRONT + 3, G + 4, sz - 6, "minecraft:light_gray_concrete")
    y.block(FRONT + 3, G + 1, 8, FRONT + 3, G + 2, sz - 9, "minecraft:air")  # through to the hall
    y.door(FRONT, sz // 2)
    y.door(FRONT, sz // 2 - 1)
    y.sign(FRONT - 1, G + 4, sz // 2, ["ДОМ БЫТА"])
    y.sign(FRONT - 1, G + 4, sz // 2 - 1, ["Ремонт обуви", "Ателье", "Химчистка"])
    for z, text in ((3, ["Продукты"]), (sz - 4, ["Ремонт", "часов"])):
        y.door(FRONT + 3, z, "birch")
        y.sign(FRONT + 2, G + 3, z, text)
        y.ground(FRONT, z, "mobtalk:paving_grey"); y.ground(FRONT + 1, z, "mobtalk:paving_grey"); y.ground(FRONT + 2, z, "mobtalk:paving_grey")
    y.pave_front(0, sz - 1)
    y.thing(1, 2, "bin_metal", "east")
    y.thing(1, sz - 3, "bin_metal", "east")
    y.post_lamp(1, 5)
    y.post_lamp(1, sz - 6)
    y.thing(0, 8, "bike_rack", "east")


def hotel(y):
    """Гостиница: five white storeys and a stair tower two storeys higher, a glazed restaurant wing along the street."""
    sx, sy, sz = SIZE
    top = y.building(FRONT + 6, 1, sx - 2, sz - 2, 5, "minecraft:white_concrete", "minecraft:light_gray_concrete",
                     glass="minecraft:gray_stained_glass")
    # the tower over the north end
    y.building(FRONT + 6, 1, FRONT + 10, 5, 6, "minecraft:white_concrete", "minecraft:light_gray_concrete",
               glass="minecraft:gray_stained_glass")
    y.block(FRONT + 6, top + 1, 1, FRONT + 10, top + 1, 5, "minecraft:light_gray_concrete")
    # the restaurant wing: glass on a low base, a long flat roof
    y.block(FRONT, G + 1, 3, FRONT + 5, G + 1, sz - 4, "minecraft:polished_andesite")
    y.block(FRONT, G + 2, 3, FRONT + 5, G + 4, sz - 4, "minecraft:light_blue_stained_glass")
    y.block(FRONT + 1, G + 1, 4, FRONT + 5, G + 4, sz - 5, "minecraft:air")
    y.block(FRONT, G + 5, 2, FRONT + 6, G + 5, sz - 3, "minecraft:white_concrete")
    y.block(FRONT + 1, G, 4, FRONT + 5, G, sz - 5, "minecraft:spruce_planks")
    y.block(FRONT + 6, G + 1, 6, FRONT + 6, G + 3, sz - 7, "minecraft:air")  # the wing opens into the hotel hall
    for z in range(5, sz - 5, 3):  # tables with chairs
        y.set(FRONT + 2, G + 1, z, "minecraft:spruce_fence")
        y.set(FRONT + 2, G + 2, z, "minecraft:white_carpet")
        y.set(FRONT + 3, G + 1, z, "minecraft:spruce_stairs", {"facing": "west", "half": "bottom", "shape": "straight", "waterlogged": "false"})
        y.set(FRONT + 1, G + 1, z, "minecraft:spruce_stairs", {"facing": "east", "half": "bottom", "shape": "straight", "waterlogged": "false"})
    y.set(FRONT + 3, G + 4, sz // 2, "minecraft:light", LIGHT)
    y.block(FRONT, G + 1, sz // 2 - 1, FRONT, G + 1, sz // 2, "minecraft:air")
    y.door(FRONT, sz // 2)
    y.door(FRONT, sz // 2 - 1)
    y.sign(FRONT - 1, G + 5, sz // 2, ["ГОСТИНИЦА"])
    y.sign(FRONT - 1, G + 5, sz // 2 - 1, ["РЕСТОРАН"])
    y.sign(FRONT + 5, top + 2, 3, ["ГОСТИНИЦА", "«НАУКА»"])
    y.pave_front(0, sz - 1)
    for z in (1, sz - 2):
        y.thing(1, z, "flagpole", "east")
    y.lamp(1, 5, "south")
    y.lamp(1, sz - 6, "north")


def school(y):
    """Школа: three storeys of red brick with white lintels under a pitched red roof, a railed schoolyard behind."""
    sx, sy, sz = SIZE
    x0, x1, z0, z1 = FRONT + 1, FRONT + 10, 1, sz - 2
    top = y.building(x0, z0, x1, z1, 3, "minecraft:bricks", "minecraft:white_concrete", glass="minecraft:glass")
    for k in range(0, (x1 - x0) // 2 + 1):  # the roof, along z
        for z in range(z0 - 1, z1 + 2):
            ya = top + 1 + k
            if x0 - 1 + k <= x1 + 1 - k:
                y.set(x0 - 1 + k, ya, z, "minecraft:red_nether_brick_stairs", {"facing": "east", "half": "bottom", "shape": "straight", "waterlogged": "false"})
                y.set(x1 + 1 - k, ya, z, "minecraft:red_nether_brick_stairs", {"facing": "west", "half": "bottom", "shape": "straight", "waterlogged": "false"})
            for x in range(x0 + k, x1 - k + 1):
                if z in (z0, z1):
                    y.set(x, ya, z, "minecraft:bricks")
    y.block(x0, G + 1, sz // 2 - 1, x0, G + 2, sz // 2, "minecraft:air")
    y.door(x0, sz // 2)
    y.door(x0, sz // 2 - 1)
    y.block(x0 - 1, G + 4, sz // 2 - 2, x0 - 1, G + 4, sz // 2 + 1, "minecraft:white_concrete")  # the porch canopy
    y.sign(x0 - 1, G + 3, sz // 2 + 1, ["ШКОЛА № 1"])
    # the schoolyard: railings, a gate, a chin-up bar, a flag
    for x in range(x1 + 1, sx):
        for z in range(sz):
            y.ground(x, z, "minecraft:grass_block", {"snowy": "false"})
    for x in range(x1 + 2, sx):
        for z in (0, sz - 1):
            y.set(x, G + 1, z, "mobtalk:fence_black", {"north": "false", "south": "false", "east": str(x < sx - 1).lower(),
                                                      "west": str(x > x1 + 2).lower(), "waterlogged": "false"})
    for z in range(1, sz - 1):
        y.set(sx - 1, G + 1, z, "mobtalk:fence_black", {"north": "true", "south": "true", "east": "false", "west": "false", "waterlogged": "false"})
    for x in range(x1 + 3, sx - 2):
        for z in range(3, 8):
            y.ground(x, z, "minecraft:red_terracotta")  # the running track
    y.thing(x1 + 4, sz - 4, "chin_up_bar", "west")
    y.thing(sx - 3, sz // 2, "flagpole", "west")
    y.pave_front(0, sz - 1)
    for z in range(0, sz, 4):
        if z not in (sz // 2 - 1, sz // 2):
            y.set(FRONT - 1, G + 1, z, "mobtalk:fence_black", {"north": "false", "south": "false", "east": "false", "west": "false", "waterlogged": "false"})
    y.linden(FRONT - 1, 2)
    y.linden(FRONT - 1, sz - 3)


def univermag(y):
    """Универсам: a long two-storey pavilion, glass below, a white upper floor with a yellow band and a strip of windows."""
    sx, sy, sz = SIZE
    x0, x1, z0, z1 = FRONT, FRONT + 9, 1, sz - 2
    y.building(x0, z0, x1, z1, 2, "minecraft:white_concrete", "minecraft:light_gray_concrete",
               ground_glass="minecraft:light_blue_stained_glass", band="minecraft:yellow_concrete")
    for z in range(z0 + 1, z1):  # the yellow band runs all along the front
        y.set(x0, G + 5, z, "minecraft:yellow_concrete")
    y.block(x0 - 1, G + 4, z0, x0 - 1, G + 4, z1, "minecraft:light_gray_concrete")  # a canopy over the shop windows
    for z in (5, sz - 6):
        y.door(x0, z, "birch")
    y.sign(x0 - 1, G + 5, sz // 2, ["УНИВЕРСАМ"])
    y.sign(x0 - 1, G + 5, sz // 2 + 2, ["Кулинария"])
    y.sign(x0 - 1, G + 5, sz // 2 - 2, ["Овощи-фрукты"])
    for z in range(z0 + 2, z1 - 1, 3):  # counters and shelves inside
        y.set(x1 - 1, G + 1, z, "minecraft:barrel", {"facing": "west", "open": "false"})
        y.set(x0 + 3, G + 1, z, "minecraft:smooth_stone_slab", {"type": "double", "waterlogged": "false"})
    y.pave_front(0, sz - 1)
    for z in (2, sz // 2, sz - 3):
        y.thing(1, z, "bin_metal", "east")
    y.thing(0, 8, "bike_rack", "east")
    for z in range(z0, z1 + 1):  # the loading yard behind
        for x in range(x1 + 1, sx):
            y.ground(x, z, "minecraft:gray_concrete")
    y.thing(x1 + 3, 3, "dumpster", "west")
    y.thing(x1 + 3, 5, "dumpster", "west")


def fountain(y):
    """Площадь с фонтаном: grey and red pavers, a round basin with a jet, benches and lamps all round, lindens at the corners."""
    sx, sy, sz = SIZE
    cx, cz = sx // 2, sz // 2
    for x in range(sx):
        for z in range(sz):
            d = ((x - cx) ** 2 + (z - cz) ** 2) ** 0.5
            y.ground(x, z, "mobtalk:paving_red" if int(d) % 4 == 3 else "mobtalk:paving_grey")
    for x in range(sx):
        for z in range(sz):
            d = ((x - cx) ** 2 + (z - cz) ** 2) ** 0.5
            if d <= 4.5:
                y.set(x, G - 1, z, "minecraft:polished_andesite")
                y.ground(x, z, "minecraft:water", {"level": "0"})
            if 4.5 < d <= 5.5:
                y.set(x, G + 1, z, "minecraft:polished_andesite_slab", {"type": "bottom", "waterlogged": "false"})
                y.ground(x, z, "minecraft:polished_andesite")
    for k in range(1, 4):
        y.set(cx, G + k, cz, "minecraft:polished_diorite")
    y.set(cx, G + 4, cz, "minecraft:water", {"level": "0"})
    for dx, dz, facing in ((-8, 0, "east"), (8, 0, "west"), (0, -8, "south"), (0, 8, "north")):
        for k in (-1, 0, 1):
            ax, az = (cx + dx, cz + dz + k) if dx else (cx + dx + k, cz + dz)
            y.thing(ax, az, "long_bench", facing if dz else ("north" if facing == "east" else "south"))
    for dx, dz in ((-7, -7), (7, -7), (-7, 7), (7, 7)):
        y.post_lamp(cx + dx, cz + dz)
    for x, z in ((2, 2), (2, sz - 3), (sx - 3, 2), (sx - 3, sz - 3)):
        y.linden(x, z)
    y.thing(cx - 8, cz - 3, "bin_metal", "east")
    y.thing(cx + 8, cz + 3, "bin_metal", "west")


LANDMARKS = {"dom_byta": dom_byta, "hotel": hotel, "school": school, "univermag": univermag, "fountain": fountain}


def build_all(save, sign_nbt):
    """Writes ours/landmark/<name> (and the same with the _5th connector); returns the template ids."""
    out = []
    for name, build in LANDMARKS.items():
        for suffix, connector in (("", "minecraft:building_entrance"), ("_5th", "minecraft:building_entrance_5th")):
            lot = Lot(random.Random(name), sign_nbt)
            build(lot)
            lot.connector(connector, SIZE[2] // 2)
            rel = f"ours/landmark/{name}{suffix}"
            save(rel, "", lot.tag())
            out.append(rel)
    return out
