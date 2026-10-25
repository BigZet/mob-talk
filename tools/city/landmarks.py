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

    def put(self, x, y, z, name, facing="north"):
        self.set(x, y, z, "mobtalk:" + name, {"facing": facing})

    def stairs(self, x1, z1, floors, stone="minecraft:polished_andesite_stairs"):
        """A flight up each storey along the back wall, west from the corner (x1, z1) inside, rows taking turns."""
        for f in range(floors - 1):
            y0 = G + 1 + 4 * f
            z = z1 - f % 2
            for k in range(4):
                self.set(x1 - k, y0 + k, z, stone, {"facing": "west", "half": "bottom", "shape": "straight", "waterlogged": "false"})
                if k < 3:
                    self.set(x1 - k, y0 + 3, z, "minecraft:air")

    def ceiling_lights(self, x0, z0, x1, z1, floors, step=4, name="office_lamp"):
        for f in range(floors):
            y = G + 3 + 4 * f
            for x in range(x0 + 1, x1, step):
                for z in range(z0 + 1, z1, step):
                    if self.cells.get((x, y, z), ("?",))[0] == "minecraft:air":
                        self.put(x, y, z, name)

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
    dom_byta_inside(y, FRONT + 3, 2, sx - 3, sz - 3)
    y.pave_front(0, sz - 1)
    y.thing(1, 2, "bin_metal", "east")
    y.thing(1, sz - 3, "bin_metal", "east")
    y.post_lamp(1, 5)
    y.post_lamp(1, sz - 6)
    y.thing(0, 8, "bike_rack", "east")


def dom_byta_inside(y, x0, z0, x1, z1):
    """Downstairs the counters where things are handed in (shoes, watches, clothes to clean), chairs to wait on; the atelier
    upstairs with its looms and a mirror wardrobe; the office on top."""
    y.stairs(x1 - 1, z1 - 1, 3)
    g = G + 1
    for z in range(z0 + 2, z1 - 2):
        if z % 3:
            y.put(x0 + 6, g, z, "shop_counter", "west")
    y.put(x0 + 6, g, z0 + 6, "cash_register", "west")
    for z in (z0 + 2, z0 + 3, z1 - 3, z1 - 4):
        y.put(x0 + 2, g, z, "chair", "east")
    for z in range(z0 + 1, z1 - 2, 2):
        y.put(x1 - 1, g, z, "filing_cabinet", "west")
    y.set(x0 + 9, g, z0 + 4, "minecraft:grindstone", {"face": "floor", "facing": "west"})  # the shoe and watch repairers
    y.put(x0 + 9, g, z0 + 8, "office_desk_lamp", "west")
    y0 = g + 4
    for z in range(z0 + 2, z1 - 3, 3):
        y.set(x0 + 4, y0, z, "minecraft:loom", {"facing": "west"})
        y.put(x0 + 3, y0, z, "chair", "east")
    y.put(x1 - 1, y0, z0 + 2, "wardrobe_bottom", "west")
    y.put(x1 - 1, y0 + 1, z0 + 2, "wardrobe_top", "west")
    y.put(x1 - 1, y0, z0 + 4, "dresser", "west")
    y.put(x0 + 8, y0, z0 + 6, "dining_table")
    y0 = g + 8
    for z in range(z0 + 2, z1 - 3, 4):
        y.put(x0 + 3, y0, z, "office_desk_typewriter" if z % 2 else "office_desk_lamp", "west")
        y.put(x0 + 2, y0, z, "chair", "east")
    for z in range(z0 + 1, z1 - 2):
        y.put(x1 - 1, y0, z, "filing_cabinet", "west")
    y.put(x0 + 1, y0, z0 + 1, "safe", "east")
    y.ceiling_lights(x0, z0, x1, z1, 3)


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
    for z in range(5, sz - 5, 3):  # tables with white cloths, chairs, a chandelier over each
        y.put(FRONT + 2, G + 1, z, "table_samovar" if z == sz // 2 else "dining_table")
        y.put(FRONT + 3, G + 1, z, "chair", "west")
        y.put(FRONT + 1, G + 1, z, "chair", "east")
        y.put(FRONT + 2, G + 4, z, "chandelier")
    y.put(FRONT + 5, G + 1, 4, "piano", "west")
    hotel_inside(y, FRONT + 6, 1, sx - 2, sz - 2, 5)
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


def hotel_inside(y, x0, z0, x1, z1, floors):
    """The hall downstairs: the reception desk, a sofa, a chandelier; rooms upstairs along the far wall, each with a bed, a
    nightstand with a lamp, a wardrobe and an armchair, walled off from the next one; a carpet down the corridor."""
    y.stairs(x1 - 1, z1 - 1, floors)
    g = G + 1
    for z in range(z0 + 7, z0 + 11):
        y.put(x1 - 5, g, z, "shop_counter", "west")
    y.put(x1 - 3, g, z0 + 8, "filing_cabinet", "west")
    y.put(x1 - 1, g + 1, z0 + 9, "wall_clock", "west")
    y.put(x0 + 3, g, z0 + 2, "sofa_left_brown", "south")
    y.put(x0 + 4, g, z0 + 2, "sofa_right_brown", "south")
    y.put(x0 + 5, g, z0 + 2, "floor_lamp", "south")
    y.put(x0 + 3, g, z0 + 4, "coffee_table")
    y.put(x0 + 6, g + 2, z0 + 8, "chandelier")
    for f in range(1, floors):
        y0 = G + 1 + 4 * f
        for r0 in range(z0 + 1, z1 - 6, 4):
            for x in range(x1 - 5, x1):  # the wall to the next room
                for k in range(3):
                    y.set(x, y0 + k, r0 + 3, "minecraft:white_concrete")
            y.set(x1 - 1, y0, r0, "minecraft:red_bed", {"facing": "east", "part": "head", "occupied": "false"})
            y.set(x1 - 2, y0, r0, "minecraft:red_bed", {"facing": "east", "part": "foot", "occupied": "false"})
            y.put(x1 - 1, y0 + 1, r0, "picture_sea" if r0 % 2 else "picture_forest", "west")
            y.put(x1 - 1, y0, r0 + 1, "nightstand_lamp", "west")
            y.put(x1 - 1, y0, r0 + 2, "wardrobe_bottom", "west")
            y.put(x1 - 1, y0 + 1, r0 + 2, "wardrobe_top", "west")
            y.put(x1 - 4, y0, r0 + 1, "armchair_" + ("green" if r0 % 2 else "red"), "east")
        for z in range(z0 + 1, z1):
            for x in (x0 + 3, x0 + 4):
                y.set(x, y0, z, "minecraft:red_carpet")
    y.ceiling_lights(x0, z0, x1, z1, floors, 4, "chandelier")


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
    school_inside(y, x0, z0, x1, z1)


def school_inside(y, x0, z0, x1, z1):
    """A hall with the canteen downstairs; two classrooms a storey above it, boards at the far walls, desks in rows."""
    y.stairs(x1 - 1, z1 - 1, 3)
    for f in (1, 2):
        y0 = G + 1 + 4 * f
        mid = (z0 + z1) // 2
        for x in range(x0 + 1, x1):  # the wall between the two classes, a doorway by the windows
            for k in range(3):
                y.set(x, y0 + k, mid, "minecraft:white_concrete" if x > x0 + 1 or k == 2 else "minecraft:air")
        a, b = (x0 + x1) // 2, (x0 + x1) // 2 + 1
        # class A, its board on the north wall
        y.put(a, y0 + 1, z0 + 1, "blackboard_left", "south")
        y.put(b, y0 + 1, z0 + 1, "blackboard_right", "south")
        y.put(a, y0, z0 + 2, "chair", "south")
        y.put(a, y0, z0 + 3, "office_desk", "north")
        for z in (z0 + 5, z0 + 7):
            for x in (x0 + 2, x0 + 3, x1 - 3, x1 - 2):
                y.put(x, y0, z, "school_desk", "north")
        # class B, its board on the dividing wall
        y.put(b, y0 + 1, mid + 1, "blackboard_left", "north")
        y.put(a, y0 + 1, mid + 1, "blackboard_right", "north")
        y.put(b, y0, mid + 2, "chair", "north")
        y.put(b, y0, mid + 3, "office_desk", "south")
        for z in (mid + 5, mid + 6):
            for x in (x0 + 2, x0 + 3, x0 + 4):
                y.put(x, y0, z, "school_desk", "south")
        y.put(x1 - 1, y0 + 1, z0 + 3, "picture_forest", "west")
        y.put(x0 + 1, y0, z0 + 1, "bookcase", "east")
        y.put(x0 + 1, y0, z0 + 2, "bookcase", "east")
    y0 = G + 1  # the canteen
    for z in range(z0 + 2, z0 + 8, 2):
        for x in (x0 + 4, x0 + 7):
            y.put(x, y0, z, "dining_table")
            y.put(x - 1, y0, z, "chair", "east")
            y.put(x + 1, y0, z, "chair", "west")
    y.put(x1 - 1, y0, z1 - 4, "freezer", "west")
    y.put(x0 + 1, y0 + 1, z0 + 1, "wall_clock", "east")
    y.set(x1 - 1, y0, z0 + 1, "minecraft:lectern", {"facing": "west", "has_book": "false", "powered": "false"})  # the library corner
    y.put(x1 - 1, y0, z0 + 2, "bookcase", "west")
    y.put(x1 - 1, y0, z0 + 3, "bookcase", "west")
    y.ceiling_lights(x0, z0, x1, z1, 3)


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
    univermag_inside(y, x0, z0, x1, z1)
    y.pave_front(0, sz - 1)
    for z in (2, sz // 2, sz - 3):
        y.thing(1, z, "bin_metal", "east")
    y.thing(0, 8, "bike_rack", "east")
    for z in range(z0, z1 + 1):  # the loading yard behind
        for x in range(x1 + 1, sx):
            y.ground(x, z, "minecraft:gray_concrete")
    y.thing(x1 + 3, 3, "dumpster", "west")
    y.thing(x1 + 3, 5, "dumpster", "west")


def univermag_inside(y, x0, z0, x1, z1):
    """Shelves of tins, bread, bottles and jars along the back wall, glass counters with scales, tills by the doors, an ice
    cream freezer; the office upstairs."""
    y.stairs(x1 - 1, z1 - 1, 2)
    goods = ("shop_shelf_tins", "shop_shelf_bread", "shop_shelf_bottles", "shop_shelf_jars")
    for z in range(z0 + 1, z1 - 2):
        y.put(x1 - 1, G + 1, z, goods[(z // 3) % 4], "west")
        if z % 4 != 0:
            y.put(x1 - 4, G + 1, z, "shop_counter_scales" if z % 4 == 2 else "shop_counter", "west")
    for z in (z0 + 2, z1 - 3):
        y.put(x0 + 2, G + 1, z, "cash_register", "west")
    y.put(x0 + 1, G + 1, z1 - 1, "freezer", "east")
    y.put(x0 + 1, G + 1, z0 + 1, "freezer", "east")
    y.set(x1 - 2, G + 1, z1 - 4, "minecraft:barrel", {"facing": "up", "open": "false"})
    y0 = G + 5
    for z in range(z0 + 2, z1 - 3, 3):
        y.put(x0 + 3, y0, z, "office_desk_lamp" if z % 2 else "office_desk_typewriter", "west")
        y.put(x0 + 2, y0, z, "chair", "east")
        y.put(x1 - 1, y0, z, "filing_cabinet", "west")
        y.put(x1 - 1, y0, z + 1, "filing_cabinet", "west")
    y.put(x0 + 1, y0, z1 - 2, "safe", "east")
    y.ceiling_lights(x0, z0, x1, z1, 2, 3)


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
