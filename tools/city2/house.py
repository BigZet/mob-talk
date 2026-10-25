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

PAPERS = ["paper_beige", "paper_green", "paper_blue", "paper_pink"]
FINISH = {"kitchen": "tile_kitchen", "bath": "tile_bath", "hall": "paper_wood", "stair": "paint_stair"}
FLOOR = {"kitchen": "floor_lino_beige", "bath": "floor_tile", "hall": "floor_lino_brown", "stair": "floor_terrazzo"}


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

    def room(self, bx, bz, floor):
        """(kind, finish, floor block) of the room a plan cell belongs to, or None for walls and outside."""
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
        key = (sec, floor, side)
        if key not in self.palettes:
            r = random.Random(f"{self.r.random()}")
            letters = [c for c, (s, k) in self.s["rooms"].items() if s == side]
            papers = {c: r.choice(PAPERS) for c in letters}
            self.palettes[key] = {"papers": papers, "floor": r.choice(["floor_parquet", "floor_parquet", "floor_lino_brown"])}
        return self.palettes[key]

    def is_facade(self, bx, bz):
        return bz in (0, self.depth - 1) or bx in (0, self.length - 1)

    def outward(self, bx, bz):
        if bz == 0:
            return N
        if bz == self.depth - 1:
            return SO
        return W if bx == 0 else E

    def wallish(self, c):
        return c in ("#", "-", "D", "E", "W", "B")

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
        self.balconies()
        self.fixtures()
        return t

    def floor(self, k):
        s = scale.PLINTH + scale.STOREY * (k - 1)   # the slab row of floor k
        for (bx, bz), c in self.grid.items():
            facade = self.is_facade(bx, bz)
            if facade:
                self.facade_cell(bx, bz, s, k, c)
                continue
            rm = self.room(bx, bz, k)
            if rm:
                self.at(bx, s, bz, rm[2])
                continue
            self.at(bx, s, bz, "floor_concrete")
            for row, y in (("lo", s + 1), ("hi", s + 2)):
                if c in ("D", "E"):
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

    def facade_cell(self, bx, bz, s, k, c):
        out = self.outward(bx, bz)
        corner = bx in (0, self.length - 1) and bz in (0, self.depth - 1)
        d = OPP[out]
        dx, dz = DIRS[d]
        joint = corner or self.wallish(self.ch(bx + dx, bz + dz) or "")
        col = self.colour
        if corner:
            self.at(bx, s, bz, "plinth" if k == 1 else f"facade_{col}_edge")
            for y in (s + 1, s + 2):
                self.at(bx, y, bz, f"facade_{col}_edge")
            return
        self.at(bx, s, bz, "plinth" if k == 1 else f"facade_{col}_{'cross' if joint else 'band'}")
        if c == "W" or (c == "B" and k == 1):
            self.at(bx, s + 1, bz, f"window_{col}_lo", {"facing": out})
            self.at(bx, s + 2, bz, f"window_{col}_hi", {"facing": out})
        elif c == "B":
            hinge = "left" if self.ch(bx + DIRS[LEFT[out]][0], bz + DIRS[LEFT[out]][1]) == "W" else "right"
            for y, half in ((s + 1, "lower"), (s + 2, "upper")):
                self.at(bx, y, bz, "door_balcony", {"facing": out, "half": half, "hinge": hinge, "open": "false", "powered": "false"})
        else:
            inner = self.inner_finish(bx, bz, k)
            for row, y in (("lo", s + 1), ("hi", s + 2)):
                self.at(bx, y, bz, f"facade_{col}_{'j' if joint else ''}{row}", {"facing": out, "inner": inner})

    def door(self, bx, y, bz, row, k, c):
        along_x = self.wallish(self.ch(bx - 1, bz) or "") and self.wallish(self.ch(bx + 1, bz) or "")
        facing = SO if along_x else E
        name = "door_flat" if c == "E" else "door_room"
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
            for vx, vz in ((6, 9), (14, 9)):
                self.at(x0 + vx, R + 1, vz, "vent_shaft", {"facing": N})
                self.at(x0 + vx, R + 2, vz, "vent_cap", {"facing": N})
            if self.r.random() < 0.6:
                ax, az = x0 + self.r.choice((2, 17)), self.r.choice((4, 12))
                for y in (R + 1, R + 2):
                    self.at(ax, y, az, "tv_antenna", {"facing": self.r.choice((N, E))})

    def stairs(self, i):
        """Two flights of three half-steps per storey along z, the well between them, landings at the flats' doors;
        the ground floor is entered from facade A at street level and climbs two half-steps to the first landing."""
        x0 = i * self.sw
        sx, sz = self.s["stair"]
        A, M, B = x0 + sx, x0 + sx + 1, x0 + sx + 2   # flight up, the well, flight down... up again
        z1 = sz
        col = self.colour
        R = self.roof
        rows = []
        for k in range(1, self.floors + 2):
            s = scale.PLINTH + scale.STOREY * (k - 1)
            rows.append(s)
            for x in (A, M, B):
                for z in range(z1, z1 + 5):
                    for y in range(s, s + 3):
                        self.at(x, y, z, "minecraft:air")
                for z in (z1 + 5, z1 + 6):   # landings are top slabs: their underside is half a block higher, so the
                    # landing above leaves 1.8 m over the first step (a full slab would leave 1.5 and the player bumps)
                    self.at(x, s, z, "step_terrazzo", {"type": "top", "waterlogged": "false"})
                    for y in (s + 1, s + 2):
                        self.at(x, y, z, "minecraft:air")
        step = lambda x, y, z, half: self.at(x, y, z, "step_terrazzo", {"type": half, "waterlogged": "false"})  # noqa: E731
        for k in range(1, self.floors + 1):
            s = rows[k - 1]
            step(A, s + 1, z1 + 4, "bottom")      # flight up from the landing, towards facade A
            step(A, s + 1, z1 + 3, "top")
            step(A, s + 2, z1 + 2, "bottom")
            for x in (A, M, B):                   # the half-landing under the staircase window
                for z in (z1, z1 + 1):
                    step(x, s + 2, z, "bottom")
            step(B, s + 2, z1 + 2, "top")         # and back up to the next landing
            step(B, s + 3, z1 + 3, "bottom")
            step(B, s + 3, z1 + 4, "top")
            self.at(M, s + 2, z1 + 6, "ceiling_lamp", {"facing": N})
        # the entrance: a door in facade A at street level, two half-steps up to the first landing
        step(M, 1, z1 + 3, "bottom")
        step(M, 1, z1 + 4, "top")
        for z in (z1 + 3, z1 + 4):
            step(B, 1, z, "double")
        for y, half in ((1, "lower"), (2, "upper")):   # a pair of doors: the well's column and the one under the first flight
            self.at(A, y, 0, "door_entrance", {"facing": N, "half": half, "hinge": "right", "open": "false", "powered": "false"})
            self.at(M, y, 0, "door_entrance", {"facing": N, "half": half, "hinge": "left", "open": "false", "powered": "false"})
        for (x, z), c in list(self.grid.items()):   # the vestibule is a storey row lower than the flats: paint its walls down to it
            if self.wallish(c) and not self.is_facade(x, z) and any(self.ch(x + dx, z + dz) == "s" and z + dz <= z1 + 4
                                                                      for dx, dz in DIRS.values()):
                if x0 <= x <= x0 + self.sw:
                    self.at(x, 1, z, "wall_lo", self.wall_sides(x, z, 1))
        for x in (A, M, B):
            self.at(x, 3, -1, "canopy", {"facing": N})
        self.at(B, 2, -1, "wall_lamp_out", {"facing": N})
        self.at(A, 2, z1 + 1, "mailboxes", {"facing": E})
        self.at(B, 2, z1, "ceiling_lamp", {"facing": N})
        self.at(B, 1, 0, "plinth")
        # the well's railing, from above the entrance path to the roof
        for y in range(3, R + 2):   # (on the ground floor the well is the way in: no end bars at head height there)
            self.at(M, y, z1 + 2, "well_rail_end", {"facing": N})
            self.at(M, y, z1 + 3, "well_rail", {"facing": N})
            if y >= 4:
                self.at(M, y, z1 + 4, "well_rail_end", {"facing": SO})
        # staircase windows, half a storey up from each floor
        for k in range(1, self.floors + 1):
            s = rows[k - 1]
            for x in (A, M, B):
                self.at(x, s + 3, 0, f"window_{col}_lo", {"facing": N})
                self.at(x, s + 4, 0, f"window_{col}_hi", {"facing": N})
        # the roof exit: a box over the staircase with a door onto the roof
        xs, zs = range(A - 1, B + 2), range(0, z1 + 8)
        for x in xs:
            for z in zs:
                edge = x in (A - 1, B + 1) or z in (0, z1 + 7)
                corner = x in (A - 1, B + 1) and z in (0, z1 + 7)
                if not edge:
                    for y in (R + 1, R + 2):
                        self.at(x, y, z, "minecraft:air")
                    self.at(x, R + 3, z, "roof_bitumen")
                    continue
                out = N if z == 0 else SO if z == z1 + 7 else (W if x == A - 1 else E)
                for row, y in (("lo", R + 1), ("hi", R + 2)):
                    if corner:
                        self.at(x, y, z, f"facade_{col}_edge")
                    elif not (z == 0 and x in (A, M, B) and y == R + 1):
                        self.at(x, y, z, f"facade_{col}_{row}", {"facing": out, "inner": "paint_stair"})
                self.at(x, R + 3, z, f"facade_{col}_{'edge' if corner else 'band'}")
        for y, half in ((R + 1, "lower"), (R + 2, "upper")):
            self.at(M, y, z1 + 7, "door_entrance", {"facing": SO, "half": half, "hinge": "left", "open": "false", "powered": "false"})
        self.at(M, R + 2, z1 + 6, "ceiling_lamp", {"facing": N})
        for x in (A, M, B):  # the top staircase window belongs to the box
            self.at(x, R + 1, 0, f"window_{col}_hi", {"facing": N})

    def balconies(self):
        """A balcony in front of every balcony door from the second floor up: the slab in the floor's slab row, the
        railing a storey row above, side railings at the ends."""
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
            for k in range(2, self.floors + 1):
                s = scale.PLINTH + scale.STOREY * (k - 1)
                for j, (x, z) in enumerate(run):
                    ox, oz = x + dx, z + dz
                    self.at(ox, s, oz, "balcony_slab", {"facing": out})
                    if j == len(run) - 1:
                        self.at(ox, s + 1, oz, "balcony_rail_corner", {"facing": out})          # left end: outward + left
                    elif j == 0:
                        self.at(ox, s + 1, oz, "balcony_rail_corner", {"facing": RIGHT[out]})   # right end: outward + right
                    else:
                        self.at(ox, s + 1, oz, "balcony_rail", {"facing": out})

    def fixtures(self):
        """Radiators under the windows, a ceiling lamp in each room; the flats' records."""
        for k in range(1, self.floors + 1):
            s = scale.PLINTH + scale.STOREY * (k - 1)
            rooms = {}
            for (bx, bz), c in self.grid.items():
                if c in self.s["rooms"]:
                    rooms.setdefault((self.section_of(bx), c), []).append((bx, bz))
            for (sec, c), cells in rooms.items():
                side, kind = self.s["rooms"][c]
                cx = sum(p[0] for p in cells) / len(cells)
                cz = sum(p[1] for p in cells) / len(cells)
                lamp = min(cells, key=lambda p: (p[0] - cx) ** 2 + (p[1] - cz) ** 2)
                self.at(lamp[0], s + 2, lamp[1], "ceiling_lamp", {"facing": N})
                flat = next((f for f in self.flats if f.section == sec and f.floor == k and f.side == side), None)
                if flat is None:
                    flat = Flat(sec, k, side)
                    self.flats.append(flat)
                flat.rooms.setdefault(kind, []).extend((self.ox + x, self.oz + z) for x, z in cells)
            for (bx, bz), c in self.grid.items():
                if c != "W" or not self.is_facade(bx, bz):
                    continue
                d = OPP[self.outward(bx, bz)]
                ix, iz = bx + DIRS[d][0], bz + DIRS[d][1]
                if self.ch(ix, iz) in self.s["rooms"]:
                    self.at(ix, s + 1, iz, "radiator", {"facing": d})


def main():
    h = House("1-464", 4, 5, "grey")
    t = h.build()
    print(t.size, len(h.flats), "flats")


if __name__ == "__main__":
    main()
