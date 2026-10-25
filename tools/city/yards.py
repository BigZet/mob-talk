"""Courtyards between the city's blocks of flats, built from scratch with our decorations: a playground, a car park with
Zhigulis and dumpsters, a small square with flower beds. Each is a template 16 deep by 15 wide that hangs on a street like a house.
pieces.py calls build_all() after the import and adds the yards to the house pools.

Frame: the street is to the west (x < 0); the entrance connector is at (0, G + 1, 7) and faces it. Ground surface at y = G.
"""
import os
import random

import nbt

G = 4  # ground surface (dirt below it, so a yard on a slope still has earth under it)
SX, SY, SZ = 16, 12, 15
MID = SZ // 2
YARDS = ("playground", "parking", "square")
VARIANTS = 2
DATA_VERSION = 3955
CAR_COLORS = ("white", "red", "green", "blue")
FLOWERS = ("minecraft:poppy", "minecraft:dandelion", "minecraft:cornflower", "minecraft:oxeye_daisy", "minecraft:azure_bluet",
           "minecraft:red_tulip", "minecraft:orange_tulip")


class Yard:
    def __init__(self, r):
        self.r = r
        self.cells = {}
        for x in range(SX):
            for z in range(SZ):
                for y in range(G):
                    self.cells[(x, y, z)] = ("minecraft:dirt", None, None)
                self.cells[(x, G, z)] = ("minecraft:grass_block", {"snowy": "false"}, None)
                for y in range(G + 1, SY):
                    self.cells[(x, y, z)] = ("minecraft:air", None, None)

    def set(self, x, y, z, name, props=None, block_nbt=None):
        if 0 <= x < SX and 0 <= y < SY and 0 <= z < SZ:
            self.cells[(x, y, z)] = (name, props, block_nbt)

    def ground(self, x, z, name, props=None):
        self.set(x, G, z, name, props)

    def free(self, x, z, h=1):
        return all(self.cells.get((x, G + 1 + k, z), ("?",))[0] == "minecraft:air" for k in range(h))

    def thing(self, x, z, name, facing="north"):
        self.set(x, G + 1, z, "mobtalk:" + name, {"facing": facing})

    def lamp(self, x, z, facing):
        for k in (1, 2, 3):
            self.set(x, G + k, z, "mobtalk:lamp_pole", {"facing": facing})
        self.set(x, G + 4, z, "mobtalk:street_lamp", {"facing": facing})

    def tree(self, x, z, height=None):
        """A birch (or now and then an oak) with a round crown; leaves never decay."""
        log = self.r.choice(("birch", "birch", "oak"))
        h = height or self.r.randint(4, 5)
        leaf = {"distance": "1", "persistent": "true", "waterlogged": "false"}
        for dy in range(h - 2, h + 2):
            rad = 2 if dy < h else 1
            for dx in range(-rad, rad + 1):
                for dz in range(-rad, rad + 1):
                    if abs(dx) == rad and abs(dz) == rad and (rad == 1 or self.r.random() < 0.6):
                        continue
                    if self.cells.get((x + dx, G + 1 + dy, z + dz), ("?",))[0] == "minecraft:air":
                        self.set(x + dx, G + 1 + dy, z + dz, f"minecraft:{log}_leaves", leaf)
        for dy in range(h):
            self.set(x, G + 1 + dy, z, f"minecraft:{log}_log", {"axis": "y"})
        self.ground(x, z, "minecraft:dirt")

    def car(self, x, z, facing):
        """A parked Zhiguli three cells long along its facing, the two end cells filled with its invisible parts."""
        dx, dz = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}[facing]
        self.thing(x, z, "car_" + self.r.choice(CAR_COLORS), facing)
        for k in (-1, 1):
            self.set(x + dx * k, G + 1, z + dz * k, "mobtalk:car_part")

    def flowers(self, x0, z0, x1, z1, density=0.35):
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if self.free(x, z) and self.cells[(x, G, z)][0] == "minecraft:grass_block" and self.r.random() < density:
                    self.set(x, G + 1, z, self.r.choice(FLOWERS))

    def connector(self, name):
        jig = nbt.c(id=nbt.Tag(nbt.STRING, "minecraft:jigsaw"), name=nbt.Tag(nbt.STRING, name),
                    target=nbt.Tag(nbt.STRING, "minecraft:empty"), pool=nbt.Tag(nbt.STRING, "minecraft:empty"),
                    final_state=nbt.Tag(nbt.STRING, "minecraft:air"), joint=nbt.Tag(nbt.STRING, "aligned"),
                    placement_priority=nbt.Tag(nbt.INT, 0), selection_priority=nbt.Tag(nbt.INT, 0))
        self.set(0, G + 1, MID, "minecraft:jigsaw", {"orientation": "west_up"}, jig)

    def tag(self):
        palette, index, blocks = [], {}, []
        for (x, y, z), (name, props, block_nbt) in sorted(self.cells.items()):
            key = (name, tuple(sorted((props or {}).items())))
            if key not in index:
                entry = nbt.c(Name=nbt.Tag(nbt.STRING, name))
                if props:
                    entry.value["Properties"] = nbt.c(**{k: nbt.Tag(nbt.STRING, v) for k, v in props.items()})
                index[key] = len(palette)
                palette.append(entry)
            b = nbt.c(pos=nbt.Tag(nbt.LIST, [nbt.Tag(nbt.INT, v) for v in (x, y, z)], nbt.INT),
                      state=nbt.Tag(nbt.INT, index[key]))
            if block_nbt is not None:
                b.value["nbt"] = block_nbt
            blocks.append(b)
        return nbt.c(size=nbt.Tag(nbt.LIST, [nbt.Tag(nbt.INT, v) for v in (SX, SY, SZ)], nbt.INT),
                     blocks=nbt.Tag(nbt.LIST, blocks, nbt.COMPOUND), palette=nbt.Tag(nbt.LIST, palette, nbt.COMPOUND),
                     entities=nbt.Tag(nbt.LIST, [], nbt.COMPOUND), DataVersion=nbt.Tag(nbt.INT, DATA_VERSION))


def path(y, x0, z0, x1, z1, name="minecraft:polished_andesite"):
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            y.ground(x, z, name)


def playground(y):
    """Детская площадка: sand under the swings and the slide, a sandbox, a chin-up bar, tyre swans, benches for the grannies."""
    r = y.r
    path(y, 0, MID - 1, 4, MID + 1)
    for x in range(5, 15):
        for z in range(1, 14):
            if r.random() < 0.85:
                y.ground(x, z, "minecraft:sand" if r.random() < 0.7 else "minecraft:coarse_dirt")
    # sandbox: a wooden rim of slabs, sand inside
    for x in range(10, 14):
        for z in range(9, 13):
            y.ground(x, z, "minecraft:sand")
            if x in (10, 13) or z in (9, 12):
                y.set(x, G + 1, z, "minecraft:spruce_slab", {"type": "bottom", "waterlogged": "false"})
    y.thing(8, 3, "swing", "north")
    y.thing(12, 4, "slide", "north")
    y.thing(6, 11, "chin_up_bar", "east")
    for x, z in ((3, 3), (3, 11), (6, 6) if r.random() < 0.5 else (6, 8)):
        y.thing(x, z, "tyre_swan", r.choice(("north", "south", "east", "west")))
    y.thing(2, MID - 2, "bench", "south")
    y.thing(2, MID + 2, "bench", "north")
    y.thing(1, MID - 2, "urn", "south")
    y.lamp(5, MID + 2, "north")
    y.tree(1, 1)
    y.tree(14, 13 if r.random() < 0.5 else 1)
    y.flowers(0, 12, 4, 14, 0.25)


def parking(y):
    """Стоянка во дворе: asphalt, two rows of Zhigulis across the drive, a row of dumpsters behind a concrete screen."""
    r = y.r
    for x in range(SX):
        for z in range(1, SZ - 1):
            y.ground(x, z, "minecraft:gray_concrete")
    for x in range(2, 14, 2):  # bays: a white line between every two cars
        for z in (2, 3, 4, 10, 11, 12):
            y.ground(x, z, "minecraft:white_concrete")
    for x in range(1, 14, 2):
        if r.random() < 0.75:
            y.car(x, 3, r.choice(("north", "south")))
        if r.random() < 0.75:
            y.car(x, 11, r.choice(("north", "south")))
    for z in range(MID - 1, MID + 2):
        y.thing(14, z, "dumpster", "west")
    for z in range(MID - 2, MID + 3):
        for k in (1, 2):
            y.set(15, G + k, z, "minecraft:light_gray_concrete")
    y.lamp(15, 2, "west")
    y.lamp(0, 12, "east")
    y.thing(0, 2, "bench", "east")
    for x in range(3, SX - 2, 4):
        for z in (0, SZ - 1):
            if y.free(x, z) and r.random() < 0.5:
                y.tree(x, z, 4)


def square(y):
    """Сквер: crossing paths round a flower bed, benches with bins, lamps, tyre swans in the grass, trees and a bus stop."""
    r = y.r
    path(y, 0, MID - 1, 14, MID + 1)
    path(y, 7, 1, 9, 13)
    path(y, 6, MID - 2, 10, MID + 2)
    for x in range(7, 10):  # the round flower bed in the middle, with a stone rim
        for z in range(MID - 1, MID + 2):
            y.ground(x, z, "minecraft:grass_block", {"snowy": "false"})
            y.set(x, G + 1, z, r.choice(FLOWERS))
    y.set(8, G + 1, MID, "minecraft:rose_bush", {"half": "lower"})
    y.set(8, G + 2, MID, "minecraft:rose_bush", {"half": "upper"})
    for x, z, facing in ((4, MID - 2, "south"), (4, MID + 2, "north"), (12, MID - 2, "south"), (12, MID + 2, "north"),
                         (6, 3, "east"), (10, 3, "west"), (6, 11, "east"), (10, 11, "west")):
        y.thing(x, z, "bench", facing)
    for x, z in ((5, MID - 2), (11, MID + 2), (6, 2), (10, 12)):
        y.thing(x, z, "urn", "north")
    y.lamp(3, MID - 2, "south")
    y.lamp(13, MID + 2, "north")
    y.lamp(6, 12, "east")
    y.thing(2, 11, "tyre_swan", "east")
    y.thing(13, 3, "tyre_swan", "west")
    for x, z in ((2, 2), (13, 12), (3, 13), (12, 1)):
        y.tree(x, z)
    y.flowers(0, 0, 5, 4)
    y.flowers(11, 10, 15, 14)
    y.thing(0, 1, "bus_stop_sign", "west")
    y.thing(1, 2, "bench", "west")


BUILD = {"playground": playground, "parking": parking, "square": square}


def build_all(save):
    """Writes ours/yard/<kind>_<n> (and the same with the _5th connector) through save(rel, name, tag); returns the template ids."""
    out = []
    for kind in YARDS:
        for n in range(1, VARIANTS + 1):
            for suffix, connector in (("", "minecraft:building_entrance"), ("_5th", "minecraft:building_entrance_5th")):
                y = Yard(random.Random(f"{kind}{n}"))
                BUILD[kind](y)
                y.connector(connector)
                rel = f"ours/yard/{kind}_{n}{suffix}"
                save(rel, "", y.tag())
                out.append(rel)
    return out
