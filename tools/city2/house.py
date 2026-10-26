"""A panel house from a series plan (series.py): real flats with papered rooms, kitchens, bathrooms, halls, balconies you
can step onto, a staircase to every floor and onto the roof, windows, radiators and ceiling lamps (spec §3.2).

    house = House("1-464", sections=4, floors=5, colour="grey", seed=1)
    tpl = house.build()          # a Tpl (template grid) with the house, its apron and the air around it
    house.flats                  # [Flat(section, floor, side, rooms, door, bed)] for POI and villagers' talk

Template axes: x along the facade, z across (facade A with the entrances at small z), y up. The house stands MARGIN blocks
in from the template's edges; y=0 is the ground layer (asphalt apron, the foundation under the house), the street is
walked at y=1, the first floor's slab is the plinth row y=1 (so the first floor is one metre above the ground).
"""
import os
import random
import sys
from dataclasses import dataclass, field

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "city"))
import nbt  # noqa: E402
import scale  # noqa: E402
import series as S  # noqa: E402

DATA_VERSION = 3955
N, E, SO, W = "north", "east", "south", "west"
DIRS = {N: (0, -1), E: (1, 0), SO: (0, 1), W: (-1, 0)}
LEFT = {N: W, W: SO, SO: E, E: N}   # looking out in a direction, the side on the left
RIGHT = {v: k for k, v in LEFT.items()}
OPP = {N: SO, SO: N, E: W, W: E}

PAPERS = ["paper_beige", "paper_green", "paper_blue", "paper_pink", "paper_stripe", "paper_roses", "paper_damask", "paper_geo"]
FINISH = {"kitchen": "tile_kitchen", "bath": "tile_bath", "hall": "paper_wood", "stair": "paint_stair", "pantry": "plaster"}
FLOOR = {"kitchen": "floor_lino_beige", "bath": "floor_tile", "hall": "floor_lino_brown", "stair": "floor_terrazzo",
         "pantry": "floor_lino_brown"}
ROWS = ("lo", "mid", "hi")   # a room's three rows over its floor: skirting, plain, cornice
FLAT_DOORS = ["door_flat", "door_flat_wine", "door_flat_black", "door_flat_wood", "door_flat_steel", "door_flat_steel_brown"]
ROOM_DOORS = ["door_room", "door_room", "door_room_wood"]
ENTRANCE_DOORS = ["door_entrance", "door_entrance_green"]
# Balconies (spec §1: real ones you step out on). Most are glazed by their owners, each in their own way: a parapet of
# sheet or brick and frames of white PVC, brown wood or aluminium; the rest are open with a sheet railing.
RAILS = ["blue", "white", "green"]
PARAPETS = ["siding", "brown", "green", "brick"]
FRAMES = ["pvc", "wood", "alu"]
# Lamps (the furniture's): a chandelier in the rooms, a flat plafond in the kitchen, bathroom and hall; in the staircase
# round wall plafonds (the landings' ceilings are slabs' undersides half a block up, where a hanging lamp would float).
LAMPS = {"living": ["apt_chandelier", "apt_lamp_cascade", "apt_lamp_horns", "apt_lamp_silk"],
         "room": ["apt_chandelier", "apt_lamp_horns", "apt_lamp_orange", "apt_ceiling_plate"],
         "bedroom": ["apt_lamp_orange", "apt_lamp_horns", "apt_ceiling_plate", "apt_lamp_silk"],
         "kitchen": ["apt_lamp_cone", "apt_ceiling_plate", "apt_lamp_orange"],
         "hall": ["apt_lamp_ball", "apt_ceiling_plate"], "bath": ["apt_lamp_ball", "apt_ceiling_plate"],
         "pantry": ["apt_bare_bulb"]}
LAMP_STAIR = "apt_wall_puck"
CURTAINS = ["apt_curtain_wine", "apt_curtain_gold", "apt_curtain_blue", "apt_curtain_olive"]
CW = {N: E, E: SO, SO: W, W: N}


class Tpl:
    """A structure template as a dict of cells; air is written explicitly so placing the template clears the ground."""

    def __init__(self, size):
        self.size = list(size)
        self.cells = {}

    def set(self, x, y, z, name, props=None):
        if 0 <= x < self.size[0] and 0 <= y < self.size[1] and 0 <= z < self.size[2]:
            self.cells[(x, y, z)] = ("mobtalk:" + name if ":" not in name else name, dict(props or {}))

    def get(self, x, y, z):
        c = self.cells.get((x, y, z))
        return c[0].split(":")[1] if c else None

    def tag(self):
        palette, index, blocks = [], {}, []
        for x in range(self.size[0]):
            for y in range(self.size[1]):
                for z in range(self.size[2]):
                    name, props = self.cells.get((x, y, z), ("minecraft:air", {}))
                    key = (name, tuple(sorted(props.items())))
                    if key not in index:
                        index[key] = len(palette)
                        entry = nbt.c(Name=nbt.Tag(nbt.STRING, name))
                        if props:
                            entry.value["Properties"] = nbt.c(**{k: nbt.Tag(nbt.STRING, v) for k, v in sorted(props.items())})
                        palette.append(entry)
                    blocks.append(nbt.c(pos=nbt.Tag(nbt.LIST, [nbt.Tag(nbt.INT, v) for v in (x, y, z)], nbt.INT),
                                        state=nbt.Tag(nbt.INT, index[key])))
        return nbt.c(size=nbt.Tag(nbt.LIST, [nbt.Tag(nbt.INT, v) for v in self.size], nbt.INT),
                     blocks=nbt.Tag(nbt.LIST, blocks, nbt.COMPOUND), palette=nbt.Tag(nbt.LIST, palette, nbt.COMPOUND),
                     entities=nbt.Tag(nbt.LIST, [], nbt.COMPOUND), DataVersion=nbt.Tag(nbt.INT, DATA_VERSION))

    def save(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(nbt.dump("", self.tag()))


@dataclass
class Flat:
    section: int
    floor: int
    side: str
    rooms: dict = field(default_factory=dict)   # kind -> list of cells (x, z) in template coordinates
    door: tuple = None                          # entrance door (x, y, z)
    bed: tuple = None                           # where the bed goes (x, y, z), for the villager's home POI


class House:
    def __init__(self, series, sections=4, floors=5, colour="grey", seed=0):
        self.s = S.SERIES[series]
        self.series, self.n, self.floors, self.colour = series, sections, floors, colour
        self.r = random.Random(f"{series}:{sections}:{floors}:{colour}:{seed}")
        plan = self.s["plan"]
        self.sw, self.depth = len(plan[0]), len(plan)
        self.length = self.sw * sections + 1
        m = scale.MARGIN
        self.ox, self.oz = m, m
        self.roof = scale.PLINTH + scale.STOREY * floors      # the roof slab's row
        self.tpl = Tpl((self.length + 2 * m, self.roof + 6, self.depth + 2 * m))
        self.flats = []
        self.grid = {}   # (bx, bz) -> plan char, bx over the whole house
        for i in range(sections):
            for z, row in enumerate(plan):
                for x, ch in enumerate(row):
                    self.grid[(i * self.sw + x, z)] = ch
        for z, row in enumerate(plan):
            self.grid[(self.length - 1, z)] = "#"
        self.palettes = {}

    # ------------------------------------------------------------ plan queries

    def ch(self, bx, bz):
        return self.grid.get((bx, bz))

    def section_of(self, bx):
        return min(bx // self.sw, self.n - 1)

    def slab(self, k):
        """The slab row of floor k (1 = the first floor)."""
        return scale.PLINTH + scale.STOREY * (k - 1)

    def room(self, bx, bz, floor):
        """(kind, finish, floor block, flat key) of the room a plan cell belongs to, or None for walls and outside."""
        c = self.ch(bx, bz)
        if c is None:
            return None
        if c == "s":
            return ("stair", FINISH["stair"], FLOOR["stair"], None)
        if c not in self.s["rooms"]:
            return None
        side, kind = self.s["rooms"][c]
        sec = self.section_of(bx)
        pal = self.palette(sec, floor, side)
        if kind in FINISH:
            return (kind, FINISH[kind], FLOOR[kind], (sec, side))
        return (kind, pal["papers"][c], pal["floor"], (sec, side))

    def palette(self, sec, floor, side):
        """A flat's own choices: wallpaper per room, the floor, the doors, the balconies' glazing."""
        key = (sec, floor, side)
        if key not in self.palettes:
            r = random.Random(f"{self.r.random()}")
            letters = [c for c, (s, k) in self.s["rooms"].items() if s == side]
            papers = {}
            for c in letters:   # no two rooms of a flat alike when it can be helped
                left = [p for p in PAPERS if p not in papers.values()] or PAPERS
                papers[c] = r.choice(left)
            glazed = r.random() < 0.65
            self.palettes[key] = {"papers": papers, "floor": r.choice(["floor_parquet", "floor_parquet", "floor_lino_brown"]),
                                  "flat_door": r.choice(FLAT_DOORS), "room_door": r.choice(ROOM_DOORS),
                                  "curtain": r.choice(CURTAINS),
                                  "balcony": ("glazed", r.choice(PARAPETS), r.choice(FRAMES)) if glazed else ("open", r.choice(RAILS), None)}
        return self.palettes[key]

    def flat_side(self, bx, bz):
        """L or R: the flat a cell's neighbourhood belongs to (by the nearest room letter along the row)."""
        for d in range(0, self.sw):
            for x in (bx - d, bx + d):
                c = self.ch(x, bz) or self.ch(x, bz + 1) or ""
                if c in self.s["rooms"]:
                    return self.s["rooms"][c][0], self.section_of(x)
        return "L", self.section_of(bx)

    def is_facade(self, bx, bz):
        return bz in (0, self.depth - 1) or bx in (0, self.length - 1)

    def outward(self, bx, bz):
        if bz == 0:
            return N
        if bz == self.depth - 1:
            return SO
        return W if bx == 0 else E

    def wallish(self, c):
        return c in ("#", "-", "D", "E", "W", "B", "S")

    # ------------------------------------------------------------ writing

    def at(self, bx, y, bz, name, props=None):
        self.tpl.set(self.ox + bx, y, self.oz + bz, name, props)

    def build(self):
        t = self.tpl
        for x in range(t.size[0]):            # the apron and the foundation
            for z in range(t.size[2]):
                bx, bz = x - self.ox, z - self.oz
                inside = 0 <= bx < self.length and 0 <= bz < self.depth
                t.set(x, 0, z, "plinth" if inside else "asphalt")
        for k in range(1, self.floors + 1):
            self.floor(k)
        self.roof_level()
        for i in range(self.n):
            self.stairs(i)
            if "lift" in self.s:
                self.lift(i)
        self.balconies()
        self.fixtures()
        import rooms   # the flats' furniture (rooms.py reads the furniture catalogue, so it is imported only here)
        rooms.furnish(self)
        return t

    def floor(self, k):
        s = self.slab(k)
        for (bx, bz), c in self.grid.items():
            if self.is_facade(bx, bz):
                self.facade_cell(bx, bz, s, k, c)
                continue
            rm = self.room(bx, bz, k)
            if rm:
                self.at(bx, s, bz, rm[2])
                continue
            self.at(bx, s, bz, "floor_concrete")
            for n, row in enumerate(ROWS):
                y = s + 1 + n
                if c in ("D", "E") and row != "hi":
                    self.door(bx, y, bz, row, k, c)
                else:
                    self.at(bx, y, bz, f"wall_{row}", self.wall_sides(bx, bz, k))

    def wall_sides(self, bx, bz, k):
        props = {}
        for d, (dx, dz) in DIRS.items():
            rm = self.room(bx + dx, bz + dz, k)
            props[d] = rm[1] if rm else "plaster"
        return props

    def inner_finish(self, bx, bz, k):
        d = OPP[self.outward(bx, bz)]
        dx, dz = DIRS[d]
        rm = self.room(bx + dx, bz + dz, k)
        return rm[1] if rm else "plaster"

    def window(self, bx, bz, s, out, inner="plaster"):
        for n, row in enumerate(ROWS):
            self.at(bx, s + 1 + n, bz, f"window_{self.colour}_{row}", {"facing": out} if row == "mid" else {"facing": out, "inner": inner})

    def facade_cell(self, bx, bz, s, k, c):
        out = self.outward(bx, bz)
        corner = bx in (0, self.length - 1) and bz in (0, self.depth - 1)
        d = OPP[out]
        dx, dz = DIRS[d]
        joint = corner or self.wallish(self.ch(bx + dx, bz + dz) or "")
        col = self.colour
        if corner:
            self.at(bx, s, bz, "plinth" if k == 1 else f"facade_{col}_edge")
            for n in range(3):
                self.at(bx, s + 1 + n, bz, f"facade_{col}_edge")
            return
        self.at(bx, s, bz, "plinth" if k == 1 else f"facade_{col}_{'cross' if joint else 'band'}")
        if c == "W" or (c == "B" and k == 1):
            self.window(bx, bz, s, out, self.inner_finish(bx, bz, k))
        elif c == "B":
            hinge = "left" if self.ch(bx + DIRS[LEFT[out]][0], bz + DIRS[LEFT[out]][1]) == "W" else "right"
            for y, half in ((s + 1, "lower"), (s + 2, "upper")):
                self.at(bx, y, bz, "door_balcony", {"facing": out, "half": half, "hinge": hinge, "open": "false", "powered": "false"})
            self.at(bx, s + 3, bz, f"window_{col}_hi", {"facing": out, "inner": self.inner_finish(bx, bz, k)})   # the transom over the door
        else:
            inner = "paint_stair" if c == "S" else self.inner_finish(bx, bz, k)
            for n, row in enumerate(ROWS):
                self.at(bx, s + 1 + n, bz, f"facade_{col}_{'j' if joint else ''}{row}", {"facing": out, "inner": inner})

    def door(self, bx, y, bz, row, k, c):
        along_x = self.wallish(self.ch(bx - 1, bz) or "") and self.wallish(self.ch(bx + 1, bz) or "")
        facing = SO if along_x else E
        side, sec = self.flat_side(bx, bz)
        pal = self.palette(sec, k, side)
        name = pal["flat_door"] if c == "E" else pal["room_door"]
        if c == "E":  # the flat door opens into the hall, away from the landing
            facing = W if self.ch(bx - 1, bz) in self.s["rooms"] else E
        self.at(bx, y, bz, name, {"facing": facing, "half": "lower" if row == "lo" else "upper", "hinge": "left", "open": "false",
                                  "powered": "false"})

    def roof_level(self):
        R = self.roof
        col = self.colour
        for (bx, bz), c in self.grid.items():
            if self.is_facade(bx, bz):
                corner = bx in (0, self.length - 1) and bz in (0, self.depth - 1)
                self.at(bx, R, bz, f"facade_{col}_{'edge' if corner else 'band'}")
                self.at(bx, R + 1, bz, f"facade_{col}_{'edge' if corner else 'band'}")   # parapet
            else:
                self.at(bx, R, bz, "roof_bitumen")
        for i in range(self.n):  # vent stacks over the bathrooms' risers, an aerial or two
            x0 = i * self.sw
            for vx, vz in self.s["vents"]:
                self.at(x0 + vx, R + 1, vz, "vent_shaft", {"facing": N})
                self.at(x0 + vx, R + 2, vz, "vent_cap", {"facing": N})
            if self.r.random() < 0.7:
                ax, az = x0 + self.r.choice((2, self.sw - 3)), self.r.choice((4, self.depth - 5))
                for y in (R + 1, R + 2):
                    self.at(ax, y, az, "tv_antenna", {"facing": self.r.choice((N, E))})

    def stairs(self, i):
        """The staircase of section i: 5 wide (flight, flight, the well, flight, flight) and 8 deep. Per storey a flight of
        three half-steps climbs from the floor landing (2 rows, the flats' doors) towards facade A to the half-landing
        (3 rows under the staircase window), and the second flight climbs back to the next floor landing. Landings are
        top slabs, so their underside is half a block up. The entrance is in facade A at street level, under the first
        half-landing, and two half-steps lead up to the first floor landing. Thin railings stand on the steps' edges by
        the well, which stays open from the ground to the roof."""
        x0 = i * self.sw
        sx, sz = self.s["stair"]
        A0, A1, M, B0, B1 = (x0 + sx + n for n in range(5))
        cols = (A0, A1, M, B0, B1)
        col = self.colour
        R = self.roof
        F = self.floors
        mid = range(sz, sz + 3)         # the half-landing's rows, by facade A
        fl = (sz + 6, sz + 7)           # the floor landing's rows, by the flats' doors

        def step(x, y, z, half, facing=N, rail="none"):
            self.at(x, y, z, "step_terrazzo", {"type": half, "facing": facing, "rail": rail, "waterlogged": "false"})

        for k in range(1, F + 1):       # clear the shaft of the staircase first: the second flight reaches into the next row
            s = self.slab(k)
            for x in cols:
                for z in range(sz, sz + 8):
                    for y in range(s, s + (5 if k == F else 4)):   # the top storey's flight climbs through the roof's row
                        self.at(x, y, z, "minecraft:air")
        for k in range(1, F + 2):
            s = self.slab(k)
            for x in cols:
                for z in fl:
                    step(x, s, z, "top", rail="front" if (x == M and z == fl[0]) else "none")
            if k > F:
                break
            self.at(M, s + 3, sz + 7, LAMP_STAIR, {"facing": N, "lit": "true"})
            for x, rail in ((A0, "none"), (A1, "right")):       # the first flight, towards facade A
                step(x, s + 1, sz + 5, "bottom", N, rail)
                step(x, s + 1, sz + 4, "top", N, rail)
                step(x, s + 2, sz + 3, "bottom", N, rail)
            for x in cols:                                      # the half-landing
                for z in mid:
                    step(x, s + 2, z, "top", SO, "front" if (x == M and z == mid[-1]) else "none")
            for x, rail in ((B0, "right"), (B1, "none")):       # and the second flight back up
                step(x, s + 3, sz + 3, "bottom", SO, rail)
                step(x, s + 3, sz + 4, "top", SO, rail)
                step(x, s + 4, sz + 5, "bottom", SO, rail)
            for x in (A0, B1):                                  # the floor line at the half-landing's corners: plastered inside
                if k > 1:
                    self.at(x, s, 0, f"facade_{col}_jmid", {"facing": N, "inner": "paint_stair"})
                if k == F:
                    self.at(x, R, 0, f"facade_{col}_jmid", {"facing": N, "inner": "paint_stair"})
            for x in (A1, M, B0):                               # the staircase window over the half-landing
                for n, row in enumerate(ROWS):
                    self.at(x, s + 3 + n, 0, f"window_{col}_{row}", {"facing": N} if row == "mid" else {"facing": N, "inner": "paint_stair"})
        # The vestibule: under the first half-landing at street level, the floor of the ground (y=0 is the ground layer).
        for x in cols:
            for z in range(sz, sz + 6):
                self.at(x, 0, z, "floor_terrazzo")
                if x in (M, B0, B1) or z < sz + 3:
                    self.at(x, 1, z, "minecraft:air")
        for x in (M, B0, B1):           # two half-steps up to the first floor landing
            step(x, 1, sz + 4, "bottom", SO)
            step(x, 1, sz + 5, "top", SO)
        for x in (A0, A1):              # under the first flight: its painted base
            for z in range(sz + 3, sz + 6):
                self.at(x, 1, z, "wall_lo", {d: "paint_stair" for d in DIRS})
            self.at(x, 2, sz + 3, "wall_mid", {d: "paint_stair" for d in DIRS})
        for (x, z), c in list(self.grid.items()):   # the vestibule's walls are a row lower than the flats': paint them down
            if x0 <= x <= x0 + self.sw and self.wallish(c) and not self.is_facade(x, z) and z < sz + 6 and \
                    any(self.ch(x + dx, z + dz) == "s" for dx, dz in DIRS.values()):
                for y, row in ((1, "lo"), (2, "mid"), (3, "mid")):
                    self.at(x, y, z, f"wall_{row}", self.wall_sides(x, z, 1))
        for x in cols:                  # facade A at the vestibule: painted inside down to the ground
            if x not in (M, B0):
                self.at(x, 1, 0, f"facade_{col}_lo", {"facing": N, "inner": "paint_stair"})
        door = self.r.choice(ENTRANCE_DOORS)
        for y, half in ((1, "lower"), (2, "upper")):   # a pair of steel doors, opening together
            self.at(M, y, 0, door, {"facing": SO, "half": half, "hinge": "right", "open": "false", "powered": "false"})
            self.at(B0, y, 0, door, {"facing": SO, "half": half, "hinge": "left", "open": "false", "powered": "false"})
        self.at(M, 3, 0, f"facade_{col}_mid", {"facing": N, "inner": "paint_stair"})
        self.at(B0, 3, 0, f"facade_{col}_mid", {"facing": N, "inner": "paint_stair"})
        self.at(A0, 2, sz + 1, "apt_post_boxes", {"facing": E, "lit": "true"})
        self.at(B1, 2, sz + 1, LAMP_STAIR, {"facing": W, "lit": "true"})   # under the half-landing, not in it
        # The porch: a concrete pad between two cheek walls, a canopy on them, the lamp and the entrance's number plate.
        for x in (A1, M, B0, B1):
            for z in (-1, -2):
                self.at(x, 0, z, "floor_concrete")
        for x in (A1, B1):
            for z in (-1, -2):
                for y in (1, 2, 3):
                    self.at(x, y, z, f"facade_{col}_edge")
        for x in (A1, M, B0, B1):
            for z in (-1, -2):
                self.at(x, 4, z, "canopy", {"facing": N})
        self.at(M, 3, -1, "wall_lamp_out", {"facing": N})
        self.at(B0, 3, -1, "entrance_plate", {"facing": N})
        # the roof exit: a box over the staircase with a door onto the roof
        xs, zs = range(A0 - 1, B1 + 2), range(0, sz + 9)
        for x in xs:
            for z in zs:
                edge = x in (A0 - 1, B1 + 1) or z in (0, sz + 8)
                corner = x in (A0 - 1, B1 + 1) and z in (0, sz + 8)
                if not edge:
                    for y in (R + 1, R + 2, R + 3):
                        self.at(x, y, z, "minecraft:air")
                    self.at(x, R + 4, z, "roof_bitumen")
                    continue
                out = N if z == 0 else SO if z == sz + 8 else (W if x == A0 - 1 else E)
                for n, row in enumerate(ROWS):
                    y = R + 1 + n
                    if corner:
                        self.at(x, y, z, f"facade_{col}_edge")
                    elif not (z == 0 and x in (A1, M, B0) and y == R + 1):
                        self.at(x, y, z, f"facade_{col}_{row}", {"facing": out, "inner": "paint_stair"})
                self.at(x, R + 4, z, f"facade_{col}_{'edge' if corner else 'band'}")
        for y, half in ((R + 1, "lower"), (R + 2, "upper")):
            self.at(M, y, sz + 8, door, {"facing": SO, "half": half, "hinge": "left", "open": "false", "powered": "false"})
        self.at(A0, R + 3, sz + 7, LAMP_STAIR, {"facing": E, "lit": "true"})
        # The garbage chute: one pipe in the corner of the half-landings by the side wall, from the ground to the roof box's
        # ceiling, through each half-landing's slab, a hopper over each half-landing.
        for y in range(1, R + 4):
            k = (y - 1) // scale.STOREY + 1          # the storey whose half-landing this row may belong to
            s = self.slab(k)
            name = "chute_slab" if y == s + 2 and k <= F else "chute_hopper" if y == s + 3 and k <= F else "chute_pipe"
            self.at(A0, y, sz, name, {"facing": E})
        self.at(A0, R + 5, sz, "vent_cap", {"facing": N})

    def lift(self, i):
        """The lift of section i (spec §3.2 p.4, lift.py): the shaft 2 x 2 behind the lift hall, its doors in the hall's back
        wall on every floor, the button beside them; on the roof the machine room, a box over the shaft with the winch."""
        import lift
        x0 = i * self.sw
        lx, lz, facing = self.s["lift"]
        lift.place(self.tpl, self.ox + x0 + lx, self.slab(1), self.oz + lz, self.floors, scale.STOREY, facing)
        R, col = self.roof, self.colour
        xs, zs = range(x0 + lx - 1, x0 + lx + 3), range(lz - 1, lz + 3)
        for x in xs:
            for z in zs:
                edge = x in (xs[0], xs[-1]) or z in (zs[0], zs[-1])
                corner = x in (xs[0], xs[-1]) and z in (zs[0], zs[-1])
                for n, row in enumerate(ROWS):
                    if not edge:
                        self.at(x, R + 1 + n, z, "minecraft:air")
                    elif corner:
                        self.at(x, R + 1 + n, z, f"facade_{col}_edge")
                    else:
                        out = N if z == zs[0] else SO if z == zs[-1] else (W if x == xs[0] else E)
                        self.at(x, R + 1 + n, z, f"facade_{col}_{row}", {"facing": out, "inner": "plaster"})
                self.at(x, R + 4, z, f"facade_{col}_{'edge' if corner else 'band'}" if edge else "roof_bitumen")
        self.at(x0 + lx + 1, R + 3, lz + 1, "apt_bare_bulb", {"facing": N, "lit": "true"})
        for k in range(1, self.floors + 1):   # the lift hall's lamp under its ceiling, in the middle of the hall
            self.at(x0 + lx + 1, self.slab(k) + 3, lz - 4, "apt_cage_lamp", {"facing": N, "lit": "true"})

    def balconies(self):
        """A balcony in front of every balcony door from the second floor up: the slab in the floor's slab row; open ones
        have a sheet railing, glazed ones a parapet and two rows of frames, each flat's in its own style; a glazed balcony
        on the top floor gets a roof."""
        for (bx, bz), c in self.grid.items():
            if c != "B":
                continue
            out = self.outward(bx, bz)
            dx, dz = DIRS[out]
            along = DIRS[LEFT[out]]
            run = [(bx, bz)]
            for sgn in (1, -1):
                x, z = bx + sgn * along[0], bz + sgn * along[1]
                while self.ch(x, z) == "W":
                    run.append((x, z))
                    x, z = x + sgn * along[0], z + sgn * along[1]
            run.sort(key=lambda p: (p[0] * along[0] + p[1] * along[1]))   # from the right end to the left end, looking out
            side, sec = self.flat_side(bx, bz + (1 if bz == 0 else -1))
            for k in range(2, self.floors + 1):
                s = self.slab(k)
                kind, style, frame = self.palette(sec, k, side)["balcony"]
                below = self.palette(sec, k - 1, side)["balcony"] if k > 2 else ("open", None, None)
                for j, (x, z) in enumerate(run):
                    ox, oz = x + dx, z + dz
                    pos = "l" if j == len(run) - 1 else "r" if j == 0 else "m"
                    # the slab; under it the head of the glazing of the balcony below, if that one is glazed
                    self.at(ox, s, oz, f"balcap_{below[2]}_{pos}" if below[0] == "glazed" else "balcony_slab", {"facing": out})
                    if kind == "open":
                        self.at(ox, s + 1, oz, f"balrail_{style}_{pos}", {"facing": out})
                        continue
                    self.at(ox, s + 1, oz, f"balpar_{style}_{pos}", {"facing": out})
                    self.at(ox, s + 2, oz, f"balglass_{frame}_{pos}", {"facing": out})
                    self.at(ox, s + 3, oz, f"baltop_{frame}_{pos}", {"facing": out})
                    if k == self.floors:
                        self.at(ox, s + 4, oz, f"balroof_{frame}_{pos}", {"facing": out})

    def fixtures(self):
        """A lamp in each room; at each window the curtains, with the radiator under the sill; the flats' records."""
        for k in range(1, self.floors + 1):
            s = self.slab(k)
            rooms = {}
            for (bx, bz), c in self.grid.items():
                if c in self.s["rooms"]:
                    rooms.setdefault((self.section_of(bx), c), []).append((bx, bz))
            for (sec, c), cells in rooms.items():
                side, kind = self.s["rooms"][c]
                cx = sum(p[0] for p in cells) / len(cells)
                cz = sum(p[1] for p in cells) / len(cells)
                lamp = min(cells, key=lambda p: (p[0] - cx) ** 2 + (p[1] - cz) ** 2)
                pick = random.Random(f"{self.r.random()}").choice(LAMPS.get(kind, LAMPS["room"]))
                self.at(lamp[0], s + 3, lamp[1], pick, {"facing": N, "lit": "true"})
                flat = next((f for f in self.flats if f.section == sec and f.floor == k and f.side == side), None)
                if flat is None:
                    flat = Flat(sec, k, side)
                    self.flats.append(flat)
                flat.rooms.setdefault(kind, []).extend((self.ox + x, self.oz + z) for x, z in cells)
            hung = {}
            for (bx, bz), c in self.grid.items():
                if not (c == "W" or (c == "B" and k == 1)) or not self.is_facade(bx, bz):
                    continue
                d = OPP[self.outward(bx, bz)]
                ix, iz = bx + DIRS[d][0], bz + DIRS[d][1]
                if self.ch(ix, iz) in self.s["rooms"]:
                    side, sec = self.flat_side(ix, iz)
                    hung[(ix, iz)] = (self.palette(sec, k, side)["curtain"], d)
            for (ix, iz), (name, d) in hung.items():
                def same(x, z):
                    return hung.get((x, z)) == (name, d)
                left = not same(ix + DIRS[CW[d]][0], iz + DIRS[CW[d]][1])
                right = not same(ix + DIRS[LEFT[d]][0], iz + DIRS[LEFT[d]][1])
                for t in range(3):
                    self.at(ix, s + 1 + t, iz, name, {"facing": d, "tier": str(t), "open": "true", "left": str(left).lower(),
                                                      "right": str(right).lower(), "radiator": "true"})


def main():
    h = House("1-464", 4, 5, "grey")
    t = h.build()
    print(t.size, len(h.flats), "flats")
    h = House("1-515-9", 3, 9, "grey")
    t = h.build()
    print(t.size, len(h.flats), "flats")


if __name__ == "__main__":
    main()
