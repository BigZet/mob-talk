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
FINISH = {"kitchen": "tile_kitchen", "bath": "tile_bath", "wc": "tile_bath", "hall": "paper_wood", "stair": "paint_stair",
          "pantry": "plaster"}
FLOOR = {"kitchen": "floor_lino_beige", "bath": "floor_tile", "wc": "floor_tile", "hall": "floor_lino_brown", "stair": "floor_terrazzo",
         "pantry": "floor_lino_brown"}
ROWS = ("lo", "mid", "hi")   # a room's three rows over its floor: skirting, plain, cornice
PAINTS = ("paint_stair", "paint_stair_blue", "paint_stair_ochre", "paint_stair_brown")   # the staircases' oil paints
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
         "hall": ["apt_lamp_ball", "apt_ceiling_plate"], "bath": ["apt_lamp_ball", "apt_ceiling_plate"], "wc": ["apt_lamp_ball"],
         "pantry": ["apt_bare_bulb"]}
LAMP_STAIR = "apt_wall_puck"
# the windows' frames (blocks.py WIN_FRAMES) and how often a flat has them: in the old houses many keep the Soviet wooden
# frames, in the newer towers nearly all are plastic; a staircase keeps its wooden frames more often than not
WIN_WEIGHTS = {"old": {"pvc": 50, "wood": 30, "brown": 13, "grey": 7}, "new": {"pvc": 68, "wood": 7, "brown": 15, "grey": 10}}
STAIR_WOOD = {"old": 0.7, "new": 0.4}
NEW_HOUSES = ("i-209a", "p-44")
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
    def __init__(self, series, sections=4, floors=5, colour="grey", seed=0, opts=None):
        """opts: overrides of the series' options for this block (compose.py closes a wing's end where it abuts a corner)."""
        self.s = dict(S.SERIES[series], **(opts or {}))
        self.series, self.n, self.floors, self.colour, self.seed = series, sections, floors, colour, seed
        self.salt = f":{seed}" if seed else ""         # the blocks of one composed house differ in their flats' choices
        self.r = random.Random(f"{series}:{sections}:{floors}:{colour}:{seed}")
        plan = self.s["plan"]
        self.whole = self.s.get("whole", False)   # a plan of the whole block (a corner block): no sections, no closing wall
        if self.whole:
            sections = self.n = 1
        self.sw, self.depth = len(plan[0]), len(plan)
        self.length = self.sw * sections + (0 if self.whole else 1)
        m = scale.MARGIN
        self.ox, self.oz = m, m
        # a storey's height: 4 (slab + three rows), 5 in the stalinkas (a fourth row of room: their high ceilings)
        self.storey = self.s.get("storey", scale.STOREY)
        self.rows = ("lo",) + ("mid",) * (self.storey - 3) + ("hi",)
        self.top = self.storey - 1                           # the top room row over the slab
        self.pitched = self.s.get("roof")                    # a pitched roof of this block (an attic under it), or a flat one
        self.roof = scale.PLINTH + self.storey * floors      # the roof slab's row
        self.tpl = Tpl((self.length + 2 * m, self.roof + (self.depth // 4 + 5 if self.pitched else 6), self.depth + 2 * m))
        self.flats = []
        self.grid = {}   # (bx, bz) -> plan char, bx over the whole house; a " " of the plan is outside (a notch, an arch)
        for i in range(sections):
            for z, row in enumerate(plan):
                for x, ch in enumerate(row):
                    if ch != " ":
                        self.grid[(i * self.sw + x, z)] = ch
        if not self.whole:
            end = self.s.get("end", "#" * self.depth)     # the closing wall: blank, or with windows (i-209a)
            for z in range(self.depth):
                self.grid[(self.length - 1, z)] = end[z]
        if self.s.get("closed_end"):                     # the end abuts another block (a corner block): no windows in it
            for z in range(self.depth):
                self.grid[(self.length - 1, z)] = "#"
        if self.s.get("closed_start"):
            for z in range(self.depth):
                if self.grid.get((0, z)) in ("W", "B"):
                    self.grid[(0, z)] = "#"
        # the walls where another block abuts this one (compose.py): under one roof with it, no gable, no parapet there
        self.joints = set()
        if self.s.get("closed_end"):
            self.joints |= {(self.length - 1, z) for z in range(self.depth)}
        if self.s.get("closed_start"):
            self.joints |= {(0, z) for z in range(self.depth)}
        for x0, z0, x1, z1 in self.s.get("joints", []):
            self.joints |= {(x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1)}
        self.base_grid = self.grid
        self.grids = {}                                  # floor -> its own plan, where one differs (the ground floor's arch)
        plans = dict(self.s.get("floor_plans", {}))
        if "plan_ground" in self.s:
            plans[1] = self.s["plan_ground"]
        for k, plan_k in plans.items():
            g = {key: v for key, v in self.grid.items()}
            for z, row in enumerate(plan_k):
                for x, ch in enumerate(row):
                    if ch == " ":
                        g.pop((x, z), None)
                    else:
                        g[(x, z)] = ch
            self.grids[k] = g
        self.palettes = {}

    # ------------------------------------------------------------ plan queries

    def ch(self, bx, bz):
        return self.grid.get((bx, bz))

    def section_of(self, bx):
        return min(bx // self.sw, self.n - 1)

    def slab(self, k):
        """The slab row of floor k (1 = the first floor)."""
        return scale.PLINTH + self.storey * (k - 1)

    def balcony_door(self, k):
        """Whether a "B" of the plan is a balcony door on floor k (from the second floor up, the third in the stalinkas)."""
        return k >= self.s.get("balcony_from", 2)

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
            glazed = r.random() < self.s.get("glazed", 0.65)    # the stalinkas' balconies stay open (their frames are for a 4-row storey)
            self.palettes[key] = {"papers": papers, "floor": r.choice(["floor_parquet", "floor_parquet", "floor_lino_brown"]),
                                  "flat_door": r.choice(FLAT_DOORS), "room_door": r.choice(ROOM_DOORS),
                                  "curtain": r.choice(CURTAINS),
                                  "balcony": ("glazed", r.choice(PARAPETS), r.choice(FRAMES)) if glazed else ("open", r.choice(RAILS), None)}
            w = WIN_WEIGHTS[self.age]
            self.palettes[key]["frame"] = r.choices(list(w), weights=list(w.values()))[0]
        return self.palettes[key]

    def flat_side(self, bx, bz):
        """L or R: the flat a cell's neighbourhood belongs to (by the nearest room letter along the row). A flat door in a
        corridor's wall (i-209a) belongs to the hall behind it."""
        if self.ch(bx, bz) == "E":
            for dx, dz in DIRS.values():
                c = self.ch(bx + dx, bz + dz)
                if c in self.s["rooms"]:
                    return self.s["rooms"][c][0], self.section_of(bx + dx)
        for d in range(0, self.sw):
            for x in (bx - d, bx + d):
                c = self.ch(x, bz) or self.ch(x, bz + 1) or ""
                if c in self.s["rooms"]:
                    return self.s["rooms"][c][0], self.section_of(x)
        return "L", self.section_of(bx)

    def on_floor(self, k):
        """Switches the plan to floor k's (the ground floor may have an arch through it)."""
        self.grid = self.grids.get(k, self.base_grid)

    def outside(self, bx, bz):
        return (bx, bz) not in self.grid

    def is_facade(self, bx, bz):
        return (bx, bz) in self.grid and any(self.outside(bx + dx, bz + dz) for dx, dz in DIRS.values())

    def convex(self, bx, bz):
        """A facade cell at an outer corner of the block: outside on two sides."""
        return sum(self.outside(bx + dx, bz + dz) for dx, dz in DIRS.values()) >= 2

    def outward(self, bx, bz):
        for d in (N, SO, W, E):
            if self.outside(bx + DIRS[d][0], bz + DIRS[d][1]):
                return d
        return N

    def wallish(self, c):
        return c in ("#", "-", "D", "E", "W", "B", "S", "X")

    # ------------------------------------------------------------ writing

    def paint(self, sec):
        """The oil paint of section sec's staircase: green mostly, blue, ochre or red-brown in other entrances."""
        r = random.Random(f"paint:{self.series}:{self.n}:{self.floors}:{self.colour}:{sec}{self.salt}")
        return r.choices(PAINTS, weights=(35, 30, 20, 15))[0]

    def at(self, bx, y, bz, name, props=None):
        if props and "paint_stair" in props.values():
            paint = self.paint(max(0, self.section_of(bx)))
            if paint != "paint_stair" and name in ("wall_lo", "wall_mid", "wall_hi"):
                # a stair wall in another paint: painted sides, the one room finish on the others (blocks.py walls())
                room = next((v for v in props.values() if v not in ("paint_stair", "plaster")), "plaster")
                props = {d: str(v == "paint_stair").lower() for d, v in props.items()} | {"room": room}
                name = f"wall_stair_{paint[len('paint_stair_'):]}_{name[len('wall_'):]}"
            elif paint != "paint_stair":
                props = {k: (paint if v == "paint_stair" else v) for k, v in props.items()}
        self.tpl.set(self.ox + bx, y, self.oz + bz, name, props)

    def build(self):
        t = self.tpl
        for x in range(t.size[0]):            # the apron and the foundation
            for z in range(t.size[2]):
                bx, bz = x - self.ox, z - self.oz
                inside = (bx, bz) in self.grids.get(1, self.base_grid)
                t.set(x, 0, z, "plinth" if inside else "asphalt")
        for k in range(1, self.floors + 1):
            self.on_floor(k)
            self.floor(k)
        self.on_floor(None)
        self.roof_level()
        for i in range(self.n):
            if "stair" in self.s:       # a gym block of a school has none
                self.stairs(i)
            if "lift" in self.s or "lifts" in self.s:
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
            for n, row in enumerate(self.rows):
                y = s + 1 + n
                if c in ("D", "E") and n < 2:
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

    @property
    def age(self):
        return "new" if self.series in NEW_HOUSES else "old"

    def frame(self, bx, bz, k):
        """The frames of the flat whose room a facade cell's window lights; the staircase's (k None) per entrance."""
        if "frame" in self.s:                   # a public building: one kind of frames throughout
            return self.s["frame"]
        if k is not None:
            d = OPP[self.outward(bx, bz)]
            ix, iz = bx + DIRS[d][0], bz + DIRS[d][1]
            if self.ch(ix, iz) in self.s["rooms"]:
                side, sec = self.flat_side(ix, iz)
                return self.palette(sec, k, side)["frame"]
        r = random.Random(f"stairframe:{self.series}:{self.n}:{self.floors}:{self.colour}:{self.section_of(bx)}{self.salt}")
        return "wood" if r.random() < STAIR_WOOD[self.age] else "pvc"

    def window(self, bx, bz, s, out, inner="plaster", k=None):
        fr = self.frame(bx, bz, k)
        for n, row in enumerate(self.rows):
            self.at(bx, s + 1 + n, bz, f"window_{self.colour}_{row}",
                    {"facing": out, "frame": fr} if row == "mid" else {"facing": out, "inner": inner, "frame": fr})

    def facade_cell(self, bx, bz, s, k, c):
        out = self.outward(bx, bz)
        corner = self.convex(bx, bz)
        d = OPP[out]
        dx, dz = DIRS[d]
        joint = corner or self.wallish(self.ch(bx + dx, bz + dz) or "")
        col = self.colour
        if corner:
            self.at(bx, s, bz, "plinth" if k == 1 else f"facade_{col}_edge")
            for n in range(self.top):
                self.at(bx, s + 1 + n, bz, f"facade_{col}_edge")
            return
        self.at(bx, s, bz, "plinth" if k == 1 else f"facade_{col}_{'cross' if joint else 'band'}")
        if c == "W" or (c == "B" and not self.balcony_door(k)):
            self.window(bx, bz, s, out, self.inner_finish(bx, bz, k), k)
        elif c == "B":
            hinge = "left" if self.ch(bx + DIRS[LEFT[out]][0], bz + DIRS[LEFT[out]][1]) == "W" else "right"
            for y, half in ((s + 1, "lower"), (s + 2, "upper")):
                self.at(bx, y, bz, "door_balcony", {"facing": out, "half": half, "hinge": hinge, "open": "false", "powered": "false"})
            for n, row in enumerate(self.rows[2:]):       # the transom over the door
                fr = self.frame(bx, bz, k)
                props = {"facing": out, "frame": fr} if row == "mid" else {"facing": out, "inner": self.inner_finish(bx, bz, k), "frame": fr}
                self.at(bx, s + 3 + n, bz, f"window_{col}_{row}", props)
        elif c == "X" and k == 1:     # a public building's front door: a pair of doors at the floor, a porch and a canopy
            look = OPP[out]
            mate = (bx + DIRS[LEFT[look]][0], bz + DIRS[LEFT[look]][1])
            hinge = "right" if self.ch(*mate) == "X" else "left"
            inner = self.inner_finish(bx, bz, k)
            for n, row in enumerate(self.rows):
                if n < 2:
                    self.at(bx, s + 1 + n, bz, self.s.get("front_door", "door_entrance"),
                            {"facing": look, "half": "lower" if n == 0 else "upper", "hinge": hinge, "open": "false", "powered": "false"})
                else:
                    self.at(bx, s + 1 + n, bz, f"facade_{col}_{row}", {"facing": out, "inner": inner})
            dx, dz = DIRS[out]
            side = DIRS[LEFT[out]]
            for d in (1, 2):
                for w in (-1, 0, 1):
                    px, pz = bx + dx * d + side[0] * w, bz + dz * d + side[1] * w
                    self.at(px, s, pz, "floor_concrete")
                    self.at(px, self.slab(2), pz, "canopy", {"facing": out})
        else:
            inner = "paint_stair" if c == "S" else self.inner_finish(bx, bz, k)
            for n, row in enumerate(self.rows):
                self.at(bx, s + 1 + n, bz, f"facade_{col}_{'j' if joint else ''}{row}", {"facing": out, "inner": inner})

    def door(self, bx, y, bz, row, k, c):
        along_x = self.wallish(self.ch(bx - 1, bz) or "") and self.wallish(self.ch(bx + 1, bz) or "")
        facing = SO if along_x else E
        side, sec = self.flat_side(bx, bz)
        pal = self.palette(sec, k, side)
        name = pal["flat_door"] if c == "E" else pal["room_door"]
        if c == "E" and along_x:   # in a corridor's wall: into the hall on the other side
            facing = N if self.ch(bx, bz - 1) in self.s["rooms"] else SO
        elif c == "E":  # the flat door opens into the hall, away from the landing
            facing = W if self.ch(bx - 1, bz) in self.s["rooms"] else E
        self.at(bx, y, bz, name, {"facing": facing, "half": "lower" if row == "lo" else "upper", "hinge": "left", "open": "false",
                                  "powered": "false"})

    def rise(self, z, x=None):
        """A pitched roof's height over cell (x, z), in half blocks: it climbs half a block a row from either long facade.
        A corner block has two ridges crossing (series "ridges": along x over rows lo..hi-1, along z over columns lo..hi-1);
        a cell under both takes the higher slope, so the slopes meet in valleys and hips."""
        best = None
        for axis, lo, hi in self.s.get("ridges", [("x", 0, self.depth)]):
            c = z if axis == "x" else x
            if c is None or not lo - 1 <= c <= hi:
                continue
            d = max(0, min(c - lo + 1, hi - c))
            best = d if best is None else max(best, d)
        return best or 0

    def roof_level(self):
        if self.pitched:
            return self.pitched_roof()
        R = self.roof
        col = self.colour
        for (bx, bz), c in self.grid.items():
            if self.is_facade(bx, bz) and (bx, bz) not in self.joints:
                corner = self.convex(bx, bz)
                self.at(bx, R, bz, f"facade_{col}_{'edge' if corner else 'band'}")
                self.at(bx, R + 1, bz, f"facade_{col}_{'edge' if corner else 'band'}")   # parapet
            else:
                self.at(bx, R, bz, "roof_bitumen")
        for i in range(self.n):  # vent stacks over the bathrooms' risers, an aerial or two
            x0 = i * self.sw
            for vx, vz in self.s.get("vents", []):
                self.at(x0 + vx, R + 1, vz, "vent_shaft", {"facing": N})
                self.at(x0 + vx, R + 2, vz, "vent_cap", {"facing": N})
            if self.r.random() < 0.7:
                ax, az = x0 + self.r.choice((2, self.sw - 3)), self.r.choice((4, self.depth - 5))
                for y in (R + 1, R + 2):
                    self.at(ax, y, az, "tv_antenna", {"facing": self.r.choice((N, E))})

    def pitched_roof(self):
        """A pitched roof (the brick houses): over the roof slab (the attic's floor) two slopes of slabs climb from eaves a block
        out over the long facades to the ridge, the brick gables close the ends; vent stacks and chimneys go through the
        slopes, the aerials stand on the ridge. The staircase stops at the top floor (no roof exit box)."""
        R, col = self.roof, self.colour
        full = self.pitched
        slab = full + "_slab"
        for (bx, bz), c in self.grid.items():
            if self.is_facade(bx, bz):
                self.at(bx, R, bz, f"facade_{col}_{'edge' if self.convex(bx, bz) else 'band'}")
            else:
                self.at(bx, R, bz, "roof_bitumen")
        cover = sorted({(x + dx, z + dz) for (x, z) in self.grid for dx in (-1, 0, 1) for dz in (-1, 0, 1)}, key=lambda p: (p[1], p[0]))
        for x, z in cover:
            d = self.rise(z, x)
            y0, half = R + 1 + d // 2, "bottom" if d % 2 == 0 else "top"
            if self.is_facade(x, z) and (x, z) not in self.joints:  # the gables and the eaves' course of brick under the slopes; a full brick where a top slab would leave a slit
                corner = self.convex(x, z)
                for y in range(R + 1, y0 + (half == "top")):
                    self.at(x, y, z, f"facade_{col}_{'edge' if corner else 'band'}")
                if half == "top":
                    continue
            self.at(x, y0, z, slab, {"type": half, "waterlogged": "false"})
        if self.s.get("cornice"):       # the stalinkas: a white cornice under the eaves, a belt over the ground floor
            for x, z in cover:
                if (x, z) in self.grid or self.rise(z, x):
                    continue
                for d in (SO, N, E, W):  # facing the wall it hangs on
                    if (x + DIRS[d][0], z + DIRS[d][1]) in self.grid:
                        self.at(x, R, z, "minecraft:smooth_quartz_stairs", {"facing": d, "half": "top", "shape": "straight",
                                                                            "waterlogged": "false"})
                        self.at(x, self.slab(2), z, "minecraft:smooth_quartz_slab", {"type": "top", "waterlogged": "false"})
                        break
        for i in range(self.n):
            x0 = i * self.sw
            for vx, vz in self.s.get("vents", []):
                top = R + 1 + self.rise(vz, x0 + vx) // 2
                for y in range(R + 1, top + 1):
                    self.at(x0 + vx, y, vz, "vent_shaft", {"facing": N})
                self.at(x0 + vx, top + 1, vz, "vent_cap", {"facing": N})
            if self.r.random() < 0.7:
                ridge = self.s.get("ridges", [("x", 0, self.depth)])[0]
                ax, az = x0 + self.r.choice((2, self.sw - 3)), (ridge[1] + ridge[2]) // 2
                y = R + 2 + self.rise(az, ax) // 2
                for dy in (0, 1):
                    self.at(ax, y + dy, az, "tv_antenna", {"facing": self.r.choice((N, E))})

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
        top_flights = not self.pitched  # under a pitched roof the staircase stops at the top floor
        n1 = self.storey - 1            # steps in a flight (3; 4 under a stalinka's high ceiling)
        rows1 = [sz + 5 - j for j in range(n1)]                 # the first flight, from the floor landing towards facade A
        mid = range(sz, sz + 6 - n1)    # the half-landing's rows, by facade A
        fl = (sz + 6, sz + 7)           # the floor landing's rows, by the flats' doors

        def at_half(s, h):
            """The block row and slab type of a walking surface h half blocks over floor s's landing."""
            v = 2 * s + 2 + h
            return (v + 1) // 2 - 1, "bottom" if v % 2 else "top"

        hl_y, hl_type = at_half(0, n1 + 1)   # the half-landing over the slab row (0-based)

        def step(x, y, z, half, facing=N, rail="none"):
            self.at(x, y, z, "step_terrazzo", {"type": half, "facing": facing, "rail": rail, "waterlogged": "false"})

        for k in range(1, F + 1):       # clear the shaft of the staircase first: the second flight reaches into the next row
            s = self.slab(k)
            for x in cols:
                for z in range(sz, sz + 8):
                    for y in range(s, s + self.storey + (k == F and top_flights)):   # the top flight climbs through the roof's row
                        self.at(x, y, z, "minecraft:air")
        for k in range(1, F + 2):
            if k > F and not top_flights:
                break
            s = self.slab(k)
            for x in cols:
                for z in fl:
                    step(x, s, z, "top", rail="front" if (x == M and z == fl[0]) else "none")
            if k > F:
                break
            if self.wallish(self.ch(M, sz + 8) or ""):          # the landing's lamp on the wall behind it (1-464)
                self.at(M, s + self.top, sz + 7, LAMP_STAIR, {"facing": N, "lit": "true"})
            else:                                               # the landing opens into a lift hall: on the side wall
                self.at(A0, s + self.top, sz + 7, LAMP_STAIR, {"facing": E, "lit": "true"})
            if k == F and not top_flights:                      # the top floor: a plain staircase window, no flights up
                for x in (A1, M, B0):
                    self.window(x, 0, s, N, "paint_stair")
                for x in (A0, B1):                              # the floor line beside the window below: plastered inside
                    self.at(x, s, 0, f"facade_{col}_jmid", {"facing": N, "inner": "paint_stair"})
                break
            for x, rail in ((A0, "none"), (A1, "right")):       # the first flight, towards facade A
                for j, z in enumerate(rows1):
                    y, half = at_half(s, j + 1)
                    step(x, y, z, half, N, rail)
            for x in cols:                                      # the half-landing
                for z in mid:
                    step(x, s + hl_y, z, hl_type, SO, "front" if (x == M and z == mid[-1]) else "none")
            for x, rail in ((B0, "right"), (B1, "none")):       # and the second flight back up
                for j, z in enumerate(reversed(rows1)):
                    y, half = at_half(s, n1 + 2 + j)
                    step(x, y, z, half, SO, rail)
            for x in (A0, B1):                                  # the floor line at the half-landing's corners: plastered inside
                if k > 1:
                    self.at(x, s, 0, f"facade_{col}_jmid", {"facing": N, "inner": "paint_stair"})
                if k == F:
                    self.at(x, R, 0, f"facade_{col}_jmid", {"facing": N, "inner": "paint_stair"})
            for x in (A1, M, B0):                               # the staircase window over the half-landing
                for n, row in enumerate(self.rows):
                    if self.s.get("stair_glass"):                   # or glass blocks the height of the house (1-335)
                        for y in range(max(4, s + 2), s + 7 if k < F else R):
                            self.at(x, y, 0, "glass_blocks", {"facing": N})
                        continue
                    fr = self.frame(x, 0, None)
                    self.at(x, s + hl_y + 1 + n, 0, f"window_{col}_{row}",
                            {"facing": N, "frame": fr} if row == "mid" else {"facing": N, "inner": "paint_stair", "frame": fr})
        # The vestibule: under the first half-landing at street level, the floor of the ground (y=0 is the ground layer).
        for x in cols:
            for z in range(sz, sz + 6):
                self.at(x, 0, z, "floor_terrazzo")
                if x in (M, B0, B1) or z < mid.stop:
                    self.at(x, 1, z, "minecraft:air")
        for x in (M, B0, B1):           # two half-steps up to the first floor landing
            step(x, 1, sz + 4, "bottom", SO)
            step(x, 1, sz + 5, "top", SO)
        for x in (A0, A1):              # under the first flight: its painted base
            for j, z in enumerate(rows1):
                self.at(x, 1, z, "wall_lo", {d: "paint_stair" for d in DIRS})
                if at_half(1, j + 1)[0] >= 3:
                    self.at(x, 2, z, "wall_mid", {d: "paint_stair" for d in DIRS})
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
        self.hall_lamps(i)
        if not top_flights:
            self.chute(i)
            return
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
        if self.s.get("chute", True):
            self.at(A0, R + 5, sz, "vent_cap", {"facing": N})     # over the chute
        self.chute(i)

    def hall_lamps(self, i):
        x0, F = i * self.sw, self.floors
        if "hall_lamp" in self.s:       # the hall behind the landing or the corridor: lamps under its ceiling on every floor
            lamps = self.s["hall_lamp"]
            for hx, hz in lamps if isinstance(lamps, list) else [lamps]:
                for k in range(1, F + 1):
                    self.at(x0 + hx, self.slab(k) + self.top, hz, "apt_cage_lamp", {"facing": N, "lit": "true"})

    def chute(self, i):
        """The garbage chute: one pipe in the corner of the half-landings by the side wall, from the ground to the roof box's
        ceiling, through each half-landing's slab, a hopper over each half-landing. None in the low brick houses."""
        if not self.s.get("chute", True):
            return
        sx, sz = self.s["stair"]
        A0, R, F = i * self.sw + sx, self.roof, self.floors
        for y in range(1, R + 4):
            k = (y - 1) // self.storey + 1           # the storey whose half-landing this row may belong to
            s = self.slab(k)
            name = "chute_slab" if y == s + 2 and k <= F else "chute_hopper" if y == s + 3 and k <= F else "chute_pipe"
            self.at(A0, y, sz, name, {"facing": E})

    def lift(self, i):
        """The lift(s) of section i (spec §3.2 p.4, lift.py): the shaft 2 x 2 behind the lift hall, its doors in the hall's
        back wall on every floor, the button beside them; on the roof the machine room, a box over the shafts with the
        winches."""
        import lift
        x0 = i * self.sw
        shafts = self.s.get("lifts") or [self.s["lift"]]
        # each entrance its own lift: overhauled ones and old Soviet ones side by side in one house (both of a pair alike)
        style = lift.pick_style(f"{self.series}:{self.n}:{self.floors}:{self.colour}:{i}")
        for lx, lz, facing in shafts:
            lift.place(self.tpl, self.ox + x0 + lx, self.slab(1), self.oz + lz, self.floors, self.storey, facing, style)
        R, col = self.roof, self.colour
        lx, lz = min(s[0] for s in shafts), min(s[1] for s in shafts)
        xs = range(x0 + lx - 1, x0 + max(s[0] for s in shafts) + 3)
        zs = range(lz - 1, max(s[1] for s in shafts) + 3)
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
        if "hall_lamp" in self.s:             # a corridor with its own lamps
            return
        for k in range(1, self.floors + 1):   # the lift hall's lamp under its ceiling, in the middle of the hall
            self.at(x0 + lx + 1, self.slab(k) + self.top, lz - 4, "apt_cage_lamp", {"facing": N, "lit": "true"})

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
            inward = DIRS[OPP[out]]
            side, sec = self.flat_side(bx + inward[0], bz + inward[1])
            first = self.s.get("balcony_from", 2)
            for k in range(first, self.floors + 1):
                s = self.slab(k)
                kind, style, frame = self.palette(sec, k, side)["balcony"]
                below = self.palette(sec, k - 1, side)["balcony"] if k > first else ("open", None, None)
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
            self.on_floor(k)
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
                self.at(lamp[0], s + self.top, lamp[1], pick, {"facing": N, "lit": "true"})
                flat = next((f for f in self.flats if f.section == sec and f.floor == k and f.side == side), None)
                if flat is None:
                    flat = Flat(sec, k, side)
                    self.flats.append(flat)
                flat.rooms.setdefault(kind, []).extend((self.ox + x, self.oz + z) for x, z in cells)
            hung = {}
            if not self.s.get("curtains", True):    # the public buildings: no curtains at their windows
                continue
            for (bx, bz), c in self.grid.items():
                if not (c == "W" or (c == "B" and not self.balcony_door(k))) or not self.is_facade(bx, bz):
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
                tall = {"tall": "true"} if self.top == 4 else {}     # under a stalinka's ceiling: the four-tier curtains
                for t in range(self.top):
                    self.at(ix, s + 1 + t, iz, name, tall | {"facing": d, "tier": str(t), "open": "true", "left": str(left).lower(),
                                                      "right": str(right).lower(), "radiator": "true"})
        self.on_floor(None)


def main():
    h = House("1-464", 4, 5, "grey")
    t = h.build()
    print(t.size, len(h.flats), "flats")
    h = House("1-515-9", 3, 9, "grey")
    t = h.build()
    print(t.size, len(h.flats), "flats")
    h = House("1-335", 3, 5, "grey")
    t = h.build()
    print(t.size, len(h.flats), "flats")
    h = House("i-209a", 1, 14, "grey")
    t = h.build()
    print(t.size, len(h.flats), "flats")


if __name__ == "__main__":
    main()
