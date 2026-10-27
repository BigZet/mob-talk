"""Furnishes a house's flats (spec §4, stage 5) with the furniture thread's pieces (tools/city2/furniture.py, ids apt_*):
each room gets what its kind needs, pieces with their backs to the walls, a walkway kept clear between the room's doors,
nothing in front of the windows (the curtains and radiators are there). Choices are seeded per flat, so every flat differs:
the kitchen set's colour, the sofa's, the room's profile (a child's, an old woman's, a student's). A carpet was a luxury
(Dmitriy): a floor rug in about one flat in three, at most one per flat; a carpet on the wall about as rarely.

    rooms.furnish(house)     # after house.build(): writes the pieces into house.tpl, sets each Flat's bed

The pieces' cells, kinds and mounts are read from the generated src/main/java/io/github/mobtalk/game/CityFurniture.java.
A piece faces the way its front looks; its cells are given for the north-facing model and turned like FurnitureBlocks.turn.
"""
import json
import os
import random
import re
from collections import deque

import fur_sets

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
N, E, SO, W = "north", "east", "south", "west"
DIRS = {N: (0, -1), E: (1, 0), SO: (0, 1), W: (-1, 0)}
OPP = {N: SO, SO: N, E: W, W: E}
SOLID = "#-"   # plan characters a piece can stand with its back to


def catalogue():
    src = open(os.path.join(ROOT, "src", "main", "java", "io", "github", "mobtalk", "game", "CityFurniture.java"), encoding="utf-8").read()
    out = {}
    for m in re.finditer(r'new FurnitureBlocks\.F\("(\w+)", FurnitureBlocks\.Kind\.(\w+), FurnitureBlocks\.Mount\.(\w+), '
                         r'new int\[\]\[\] \{(.*?)\},\s*\n', src):
        cells = [tuple(int(v) for v in c.split(",")) for c in re.findall(r"\{(-?\d+, -?\d+, -?\d+)\}", m.group(4))]
        out[m.group(1)] = (m.group(2), m.group(3), cells)
    return out


CAT = catalogue()
with open(os.path.join(HERE, "variants.json"), encoding="utf-8") as _f:
    VARIANTS = json.load(_f)


def turn(c, facing):
    x, y, z = c
    for _ in range({N: 0, E: 1, SO: 2, W: 3}[facing]):
        x, z = -z, x
    return x, y, z


class Room:
    def __init__(self, house, k, letter, kind, cells, rnd):
        self.h, self.k, self.letter, self.kind, self.cells, self.r = house, k, letter, kind, set(cells), rnd
        self.fin = None   # the flat's seed of finishes, set by furnish()
        self.pal = False  # the room's finish set, drawn on the first piece
        self.s = house.slab(k)
        self.clear = self.walkway()
        self.placed = {}     # name -> (x, z, facing) of anchors (sofa, table, bed)

    # ---------------------------------------------------------------- the room's shape

    def doors(self):
        """Cells of the room in front of its doors (room doors, the flat's door, the balcony door)."""
        out = []
        for (x, z) in self.cells:
            for d, (dx, dz) in DIRS.items():
                c = self.h.ch(x + dx, z + dz)
                if c in ("D", "E", "X") or (c == "B" and self.h.balcony_door(self.k)):
                    out.append((x, z))
        return out

    def walkway(self):
        """The door cells and the shortest ways between them, kept free of furniture."""
        doors = self.doors()
        keep = set(doors)
        for a in doors[1:]:
            prev = {doors[0]: None}
            q = deque([doors[0]])
            while q:
                c = q.popleft()
                if c == a:
                    break
                for dx, dz in DIRS.values():
                    n = (c[0] + dx, c[1] + dz)
                    if n in self.cells and n not in prev:
                        prev[n] = c
                        q.append(n)
            c = a
            while c is not None and c in prev:
                keep.add(c)
                c = prev[c]
        return keep

    def free(self, x, y, z, flat=False):
        if (x, z) not in self.cells or self.h.tpl.get(self.h.ox + x, y, self.h.oz + z) is not None:
            return False
        return flat or y > self.s + 2 or (x, z) not in self.clear

    def backed(self, x, z, facing):
        """A wall (not a door or a window) right behind the cell, seen from a piece facing `facing`."""
        dx, dz = DIRS[OPP[facing]]
        return (self.h.ch(x + dx, z + dz) or "") in SOLID

    # ---------------------------------------------------------------- putting pieces

    def fits(self, pid, x, y, z, facing, flat=False, backed=True):
        kind, mount, cells = CAT[pid]
        for c in cells:
            tx, ty, tz = turn(c, facing)
            if not self.free(x + tx, y + ty, z + tz, flat):
                return False
            if backed and c[2] == 0 and not self.backed(x + tx, z + tz, facing):
                return False
        return True

    def vary(self, pid):
        """The finish of a piece that has variants (fur_variants.py): what the room's set (fur_sets.py) gives its family."""
        if self.fin is None:
            return pid
        if self.pal is False:
            self.pal = fur_sets.pick(self.fin, self.letter, self.kind)
        return fur_sets.resolve(pid, self.pal, VARIANTS)

    def write(self, pid, x, y, z, facing):
        pid = self.vary(pid)
        kind, mount, cells = CAT[pid]
        for i, c in enumerate(cells):
            tx, ty, tz = turn(c, facing)
            if kind == "BED":
                props = {"facing": facing, "part": "foot" if i == 0 else "head", "occupied": "false"}
                self.h.at(x + tx, y + ty, z + tz, pid, props)
            elif i == 0:
                self.h.at(x, y, z, pid, {"facing": facing, "lit": "true"})
            else:
                self.h.at(x + tx, y + ty, z + tz, pid + "_part", {"facing": facing, "lit": "true", "part": str(i)})

    def spots(self):
        """(x, z, facing) for every cell of the room and every way a piece there could face, in a random order."""
        out = [(x, z, f) for (x, z) in self.cells for f in DIRS]
        self.r.shuffle(out)
        return out

    def against(self, pid, height=0, name=None, near=None, flat=False, row=False):
        """Puts the piece with its back to a wall at floor row + height; near: prefer spots close to an anchor; row: prefer
        the longest free stretch of wall (the sink, which the kitchen units then line up with)."""
        y = self.s + 1 + height
        spots = self.spots()
        if row:
            spots.sort(key=lambda p: -self.wall_room(*p))
        if near and near in self.placed:
            ax, az, _ = self.placed[near]
            spots.sort(key=lambda p: abs(p[0] - ax) + abs(p[1] - az))
        for x, z, f in spots:
            if self.fits(pid, x, y, z, f, flat):
                self.write(pid, x, y, z, f)
                if name:
                    self.placed[name] = (x, z, f)
                return True
        return False

    def above(self, pid, under, height=1):
        """A wall piece over something already standing (a cupboard over the worktop, a carpet over the sofa)."""
        y = self.s + 1 + height
        for x, z, f in self.spots():
            below = self.h.tpl.get(self.h.ox + x, y - height, self.h.oz + z)
            if below and below.startswith(under) and self.fits(pid, x, y, z, f):
                self.write(pid, x, y, z, f)
                return True
        return False

    def over(self, pid, under, height=None):
        """A ceiling piece in the top row over something standing on the floor (drying lines over the bath)."""
        y = self.s + (self.h.top if height is None else 1 + height)
        for x, z, f in self.spots():
            below = self.h.tpl.get(self.h.ox + x, self.s + 1, self.h.oz + z)
            if below and below.startswith(under) and self.fits(pid, x, y, z, f, backed=False):
                if all((self.h.tpl.get(self.h.ox + x + tx, self.s + 1, self.h.oz + z + tz) or "").startswith(under)
                       for tx, _, tz in (turn(c, f) for c in CAT[pid][2])):
                    self.write(pid, x, y, z, f)
                    return True
        return False

    def bed(self, pid, double=False):
        """A bed with its head to a wall (a double one: two beds side by side), the foot towards the room."""
        y = self.s + 1
        for x, z, f in self.spots():   # (x, z) is the head's cell, f points from the foot to the head (towards the wall)
            if not self.backed(x, z, OPP[f]):
                continue
            fx, fz = x - DIRS[f][0], z - DIRS[f][1]
            cells = [(x, z), (fx, fz)]
            if double:
                rx, _, rz = turn((-1, 0, 0), f)
                cells += [(x + rx, z + rz), (fx + rx, fz + rz)]
                if not self.backed(x + rx, z + rz, OPP[f]):
                    continue
            if all(self.free(cx, y, cz) for cx, cz in cells):
                if double:
                    self.write("apt_bed_double_l", fx, y, fz, f)
                    self.write("apt_bed_double_r", fx + rx, y, fz + rz, f)
                else:
                    self.write(pid, fx, y, fz, f)
                self.placed["bed"] = (x, z, f)
                self.heads = [(x, z)] + ([(x + rx, z + rz)] if double else [])
                return (fx, fz)
        return None

    def beside(self, pid):
        """A nightstand on each side of the bed's head, by the wall, facing into the room (one per side at most)."""
        if "bed" not in self.placed:
            return 0
        _, _, f = self.placed["bed"]
        n = 0
        for side in (1, -1):
            dx, _, dz = turn((side, 0, 0), f)
            head = max(self.heads, key=lambda c: c[0] * dx + c[1] * dz)   # the outermost head on that side
            x, z = head[0] + dx, head[1] + dz
            if self.fits(pid, x, self.s + 1, z, OPP[f]):
                self.write(pid, x, self.s + 1, z, OPP[f])
                n += 1
        return n

    def run(self, pid, anchor):
        """A piece in a row with an anchor along the same wall, facing the same way (kitchen units beside the sink)."""
        if anchor not in self.placed:
            return self.against(pid)
        x, z, f = self.placed[anchor]
        row = self.placed.setdefault(anchor + "_row", [(x, z)])
        for side in (1, -1):
            dx, _, dz = turn((side, 0, 0), f)
            end = max(row, key=lambda c: c[0] * dx + c[1] * dz)
            tx, tz = end[0] + dx, end[1] + dz
            if self.fits(pid, tx, self.s + 1, tz, f):
                self.write(pid, tx, self.s + 1, tz, f)
                row.append((tx, tz))
                return True
        return self.against(pid, near=anchor)

    def switch(self):
        """The light switch: inside the room beside its (first) door, on the wall the door is in, at hand height (spec of
        the furniture thread: apt_switch turns this room's ceiling lamps)."""
        y = self.s + 2
        for x, z in sorted(self.cells):
            for d, (dx, dz) in DIRS.items():
                if self.h.ch(x + dx, z + dz) not in ("D", "E"):
                    continue
                for px, pz in ((dz, -dx), (-dz, dx)):
                    cx, cz = x + px, z + pz
                    if (cx, cz) in self.cells and (self.h.ch(cx + dx, cz + dz) or "") in SOLID and \
                            self.h.tpl.get(self.h.ox + cx, y, self.h.oz + cz) is None:
                        self.h.at(cx, y, cz, "apt_switch", {"facing": OPP[d], "lit": "true"})
                        return True
        return False

    def wall_room(self, x, z, f):
        """How many cells of free wall run on both sides of (x, z) for pieces facing f (room for a kitchen row)."""
        n = 0
        for side in (1, -1):
            dx, _, dz = turn((side, 0, 0), f)
            cx, cz = x + dx, z + dz
            while self.free(cx, self.s + 1, cz) and self.backed(cx, cz, f):
                n += 1
                cx, cz = cx + dx, cz + dz
        return n

    def front(self, pid, anchor, dist=1, name=None, facing_back=False):
        """A piece in front of an anchor (a coffee table before the sofa), standing free; facing_back: it looks at the
        anchor (a chair at the desk)."""
        if anchor not in self.placed:
            return False
        x, z, f = self.placed[anchor]
        pf = OPP[f] if facing_back else f
        for d in range(dist, dist + 2):
            tx, tz = x + DIRS[f][0] * d, z + DIRS[f][1] * d
            if self.fits(pid, tx, self.s + 1, tz, pf, backed=False):
                self.write(pid, tx, self.s + 1, tz, pf)
                if name:
                    self.placed[name] = (tx, tz, f)
                return True
        return False

    def table(self, pid, chair, chairs=2):
        """A table standing free or by a wall, chairs round it facing it."""
        y = self.s + 1
        for x, z, f in self.spots():
            if self.fits(pid, x, y, z, f, backed=False) and not self.backed(x, z, f) or self.fits(pid, x, y, z, f):
                cells = [(x + turn(c, f)[0], z + turn(c, f)[2]) for c in CAT[pid][2]]
                around = [(cx + dx, cz + dz, d) for cx, cz in cells for d, (dx, dz) in DIRS.items()
                          if (cx + dx, cz + dz) not in cells]
                ok = [(ax, az, OPP[d]) for ax, az, d in around if self.free(ax, y, az)]
                if len(ok) < chairs:
                    continue
                self.write(pid, x, y, z, f)
                self.r.shuffle(ok)
                used = set()
                for ax, az, fd in ok:
                    if len(used) >= chairs or (ax, az) in used:
                        continue
                    if self.fits(chair, ax, y, az, fd, backed=False):
                        self.write(chair, ax, y, az, fd)
                        used.add((ax, az))
                return True
        return False

    def rug(self, pid):
        """A floor rug where the floor is free (it lies on the floor cells, so it goes in after the furniture)."""
        y = self.s + 1
        for x, z, f in self.spots():
            if self.fits(pid, x, y, z, f, flat=True, backed=False):
                self.write(pid, x, y, z, f)
                return True
        return False


NEW_SERIES = ("i-209a", "p-44")   # towers whose flats may be done up in the 2000s and ring with a chime


def maybe(r, p):
    return r.random() < p


def kitchen(rm, flat):
    r, st = rm.r, flat["set"]
    if flat["modern"]:   # done up in the 2000s: a glossy lime set, a tall fridge
        rm.against("apt2_kitchen_sink", name="sink", row=True)
        for pid in (r.choice(["apt_stove", "apt_stove_kettle"]), "apt2_kitchen_base", "apt2_kitchen_plain", "apt2_fridge"):
            rm.run(pid, "sink")
        for _ in range(3):
            rm.above("apt2_kitchen_wall", "apt2_kitchen_")
        rm.table("apt_kitchen_table", "apt_chair", chairs=r.choice([2, 3]))
        if maybe(r, 0.5):
            rm.against("apt_wall_clock", height=1)
        return
    rm.against(f"apt_kitchen_sink_{st}", name="sink", row=True)
    rm.run(r.choice(["apt_stove", "apt_stove_kettle"]), "sink")
    rm.run(f"apt_kitchen_base_{st}", "sink")
    rm.run(f"apt_kitchen_jars_{st}", "sink")
    rm.run(r.choice(["apt_fridge", "apt_fridge_saratov", "apt_fridge_minsk", "apt_fridge_biryusa"]), "sink")
    for _ in range(2):
        rm.above(f"apt_wall_cupboard_{st}", "apt_kitchen_")
    rm.above(f"apt_dish_rack_{st}", "apt_kitchen_sink")
    if maybe(r, 0.6):
        rm.against("apt_gas_heater", height=1)
    rm.table("apt_kitchen_table", r.choice(["apt_stool", "apt_chair"]), chairs=r.choice([2, 3]))
    for pid, p in (("apt_kitchen_shelf", 0.6), ("apt_wall_radio", 0.5), ("apt_wall_clock", 0.3)):
        if maybe(r, p):
            rm.against(pid, height=1)


def bath(rm, flat):
    r = rm.r
    rm.against("apt_bathtub", name="bath")
    side = rm.h.s["rooms"][rm.letter][0]
    if not any(s == side and kind == "wc" for s, kind in rm.h.s["rooms"].values()):   # the toilet: here, or in its own WC
        rm.against("apt_toilet")
    rm.against("apt_washbasin")
    rm.against(r.choice(["apt_washer", "apt_washer", "apt_washer_malyutka", "apt_washer_sibir"]))
    rm.over("apt_dryer_liana", "apt_bathtub")         # the drying lines over the bath, under the ceiling
    for pid, p in (("apt_laundry_basket", 0.7), ("apt_basin_zinc", 0.5), ("apt_dryer_rack", 0.25)):
        if maybe(r, p):
            rm.against(pid)
    rm.against("apt_towel_rail", height=1)


def wc(rm, flat):
    """The separate WC of the stalinkas: the toilet, a shelf over it, a roll of paper."""
    rm.against("apt_toilet")
    if maybe(rm.r, 0.6):
        rm.against("apt_kitchen_shelf", height=1)


def hall(rm, flat):
    r = rm.r
    rm.against("apt_coat_rack")
    rm.against("apt_shoe_cabinet")
    if maybe(r, 0.7):
        rm.against("apt_hall_mirror")
    if maybe(r, 0.5):
        rm.against("apt_wall_phone")
    rm.against("apt_electric_meter", height=2)
    for x, z in rm.doors():      # the mat inside the flat's door
        if any(rm.h.ch(x + dx, z + dz) == "E" for dx, dz in DIRS.values()):
            for f in DIRS:
                if rm.fits("apt_door_mat", x, rm.s + 1, z, f, flat=True, backed=False):
                    rm.write("apt_door_mat", x, rm.s + 1, z, f)
                    break
    if maybe(r, 0.3) and not flat["rug"]:
        flat["rug"] = rm.rug("apt_runner")


def living(rm, flat):
    r = rm.r
    colour = flat["sofa"]
    if flat["modern"]:
        rm.against("apt2_sofa_corner", name="sofa") and rm.front("apt_coffee_table", "sofa", name="coffee")
        rm.against("apt2_tv_stand")
        rm.against(r.choice(["apt_bookcase", "apt_sideboard"]))
        rm.against(r.choice(["apt_ficus", "apt_aloe"]))
        if maybe(r, 0.5):
            rm.against("apt_floor_lamp")
        rm.against(r.choice(["apt_picture_wave", "apt_wall_clock"]), height=1)
        return
    if rm.against(f"apt_sofa_{colour}", name="sofa"):
        rm.front("apt_coffee_table", "sofa", name="coffee")
        if flat["wall_carpet"]:
            flat["wall_carpet"] = not rm.above("apt_wall_carpet", "apt_sofa_")
    for _ in range(r.choice([1, 2])):
        rm.against(f"apt_armchair_{colour if colour in ('red', 'green') else 'brown'}", near="sofa")
    rm.against(r.choice(["apt_wall_unit", "apt_wall_unit", "apt_sideboard"]))
    rm.against("apt_tv")
    if maybe(r, 0.15):
        rm.against("apt_piano") and rm.against("apt_piano_stool")
    for pid, p in (("apt_radiola", 0.4), ("apt_bookcase", 0.5), ("apt_floor_lamp", 0.4)):
        if maybe(r, p):
            rm.against(pid)
    rm.against(r.choice(["apt_ficus", "apt_aloe"]))
    if maybe(r, 0.5):
        rm.table(r.choice(["apt_dining_table", "apt_feast_table"]), "apt_chair", chairs=r.choice([2, 4]))
    for pid, p in (("apt_wall_clock", 0.5), ("apt_picture_bears", 0.4), ("apt_picture_wave", 0.4), ("apt_sconce", 0.3)):
        if maybe(r, p):
            rm.against(pid, height=1)


def bedroom(rm, flat):
    r = rm.r
    if rm.bed(None, double=True) or rm.bed("apt_bed"):
        flat["bed"] = rm.placed["bed"]
        rm.beside("apt_nightstand")
        if flat["wall_carpet"]:
            flat["wall_carpet"] = not rm.above("apt_wall_carpet_high", "apt_bed_double", height=1)
    rm.against("apt2_wardrobe_coupe" if flat["modern"] else "apt_wardrobe")
    for pid, p in (("apt_chest_drawers", 0.7), ("apt_dressing_table", 0.6), ("apt_chair_clothes", 0.5), ("apt_suitcases", 0.3),
                   ("apt_ironing_board", 0.25)):
        if maybe(r, p):
            rm.against(pid)


def room(rm, flat):
    """A room of its own: a child's, an old woman's or a student's, by the flat's lot."""
    r = rm.r
    who = r.choice(["child", "child", "granny", "student"])
    if who == "granny":
        rm.bed("apt_bed_iron")
        rm.against("apt_wardrobe")
        rm.against("apt_sewing_machine")
        rm.against("apt_chest_drawers")
        rm.against(r.choice(["apt_ficus", "apt_aloe"]))
        rm.against("apt_picture_wave", height=1)
        rm.against("apt_chair")
    else:
        if who == "child" and maybe(r, 0.35):
            rm.against("apt_crib")
        rm.bed("apt_bed")
        if flat["modern"]:   # the computer desk of the 2000s
            rm.against("apt2_pc_desk", name="desk")
            rm.front("apt2_office_chair", "desk", facing_back=True)
        else:
            rm.against("apt_desk", name="desk")
            rm.front("apt_chair", "desk", facing_back=True)
        rm.against("apt_bookcase")
        rm.against("apt_wardrobe")
        if who == "child":
            rm.against("apt_toy_box")
            if maybe(r, 0.4):
                rm.against("apt_rocking_horse")
        rm.against(r.choice(["apt_picture_bears", "apt_picture_wave", "apt_wall_clock"]), height=1)
    if "bed" in rm.placed and not flat["bed"]:
        flat["bed"] = rm.placed["bed"]


def pantry(rm, flat):
    """The pantry (кладовка): what doesn't fit in the flat, a bulb under the ceiling."""
    r = rm.r
    for pid, p in (("apt_bicycle", 0.5), ("apt_sled", 0.5), ("apt_suitcases", 0.8), ("apt_kitchen_shelf", 0.6),
                   ("apt_pram", 0.25), ("apt_ironing_board", 0.4), ("apt_bookcase", 0.5)):
        if maybe(r, p):
            rm.against(pid, height=1 if CAT[pid][1] == "WALL" else 0)


def doorbell(rm, flat):
    """The bell button on the landing beside the flat's door (hand height, on the wall next to the door) and the bell in the
    hall over the door: a Soviet one in the old houses, mostly a ding-dong chime in the newer towers (furniture thread)."""
    y = rm.s + 2
    for x, z in rm.doors():
        for d, (dx, dz) in DIRS.items():
            ex, ez = x + dx, z + dz
            if rm.h.ch(ex, ez) != "E":
                continue
            lx, lz = ex + dx, ez + dz                       # the landing in front of the door
            for px, pz in ((dz, -dx), (-dz, dx)):
                bx, bz = lx + px, lz + pz
                if rm.h.ch(bx, bz) == "s" and (rm.h.ch(ex + px, ez + pz) or "") in SOLID and \
                        rm.h.tpl.get(rm.h.ox + bx, y, rm.h.oz + bz) in (None, "air"):   # the staircase is cleared to air
                    rm.h.at(bx, y, bz, "apt_bell_button", {"facing": d, "lit": "true"})
                    break
            bell = "apt_doorbell_chime" if flat["new"] and maybe(rm.r, 0.75) else "apt_doorbell"
            rm.placed["door"] = (x, z, d)
            rm.against(bell, height=2, near="door")
            return


def stalin(rm, flat):
    """The stalinka's rooms under their 4-block ceilings (Dmitriy: they looked empty): the tall pieces - an oak buffet, a
    bookcase to the ceiling, a floor clock, a palm, Shishkin hung high - the bronze chandelier on its rod in place of the
    lamp, and last the plaster cornice round the walls wherever the top row is free."""
    r, h = rm.r, rm.h
    top = rm.s + h.top
    if rm.kind == "living":
        rm.against("apt_buffet_stalin")
        rm.against("apt_painting_rye", height=2)
        for pid, p in (("apt_floor_clock", 0.5), ("apt_palm_tub", 0.45)):
            if maybe(r, p):
                rm.against(pid)
    elif rm.kind == "room":
        for _ in range(r.choice((1, 2, 2))):
            rm.against("apt_bookcase_tall")
        if maybe(r, 0.35):
            rm.against("apt_floor_clock")
    elif rm.kind == "bedroom" and maybe(r, 0.25):
        rm.against("apt_palm_tub")
    if rm.kind in ("living", "room"):     # the chandelier: the ceiling lamp's cell, the cell under it for the ring of arms
        for x, z in rm.cells:
            name = h.tpl.get(h.ox + x, top, h.oz + z) or ""
            if name.startswith("apt_") and name != "apt_molding" and h.tpl.get(h.ox + x, top - 1, h.oz + z) is None:
                if CAT.get(name, ("", ""))[1] == "CEILING":
                    h.tpl.cells.pop((h.ox + x, top, h.oz + z))
                    if rm.fits("apt_chandelier_long", x, top, z, N, backed=False):
                        rm.write("apt_chandelier_long", x, top, z, N)
                    else:
                        h.at(x, top, z, name, {"facing": N, "lit": "true"})
                    break
    if rm.kind in ("living", "room", "bedroom", "hall"):
        for x, z, f in rm.spots():
            if rm.fits("apt_molding", x, top, z, f):
                rm.write("apt_molding", x, top, z, f)


KINDS = {"pantry": pantry, "kitchen": kitchen, "bath": bath, "wc": wc, "hall": hall, "living": living, "bedroom": bedroom, "room": room}


def public_kinds():
    import public   # the public buildings' rooms (public.py imports house, which imports this module only when building)
    return public.FURNISH


def furnish(house):
    """Every flat of every floor, room by room; then the flats' floor rugs; then the landings' door mats."""
    plan_rooms = house.s["rooms"]
    for k in range(1, house.floors + 1):
        house.on_floor(k)
        for sec in range(house.n):
            for side in sorted({sd for sd, _ in plan_rooms.values()}):
                r = random.Random(f"{house.series}:{house.colour}:{house.n}:{k}:{sec}:{side}{house.salt}")
                flat = {"set": r.choice(["white", "wood"]), "sofa": r.choice(["red", "green", "blue"]), "bed": None,
                        "rug": False, "wall_carpet": r.random() < 0.3, "new": house.series in NEW_SERIES, "modern": False}
                carpet = r.random() < 0.33
                if flat["new"]:     # the towers of the 70s-80s: some flats done up in the 2000s, no carpets there
                    flat["modern"] = r.random() < 0.4
                    carpet = carpet and not flat["modern"]
                    flat["wall_carpet"] = flat["wall_carpet"] and not flat["modern"]
                rooms = []
                for letter, (s_, kind) in plan_rooms.items():
                    if s_ != side:
                        continue
                    cells = [(x, z) for (x, z), c in house.grid.items() if c == letter and house.section_of(x) == sec]
                    if cells:
                        rooms.append(Room(house, k, letter, kind, cells, random.Random(f"{r.random()}")))
                        rooms[-1].fin = f"fin:{house.series}:{house.colour}:{house.n}:{k}:{sec}:{side}{house.salt}"
                order = ["hall", "kitchen", "bath", "wc", "living", "bedroom", "room", "pantry"]
                rooms.sort(key=lambda rm: order.index(rm.kind) if rm.kind in order else len(order))
                for rm in rooms:
                    rm.switch()
                    (KINDS.get(rm.kind) or public_kinds()[rm.kind])(rm, flat)
                    if house.s.get("stalin"):
                        stalin(rm, flat)
                if carpet and not flat["rug"]:
                    for rm in rooms:
                        if rm.kind in ("living", "bedroom", "room"):
                            if any(rm.rug(pid) for pid in r.sample(["apt_rug", "apt_rug_blue", "apt_rug_turkmen"], 3)):
                                break
                for rm in rooms:
                    if rm.kind == "hall":
                        doorbell(rm, flat)
                        break
                rec = next((f for f in house.flats if f.section == sec and f.floor == k and f.side == side), None)
                if rec is not None and flat["bed"]:
                    x, z, _ = flat["bed"]
                    rec.bed = (house.ox + x, house.slab(k) + 1, house.oz + z)
    house.on_floor(None)
