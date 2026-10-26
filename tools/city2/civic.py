"""Public buildings from their own typical projects (2026-10-26 redo, the plan in /mnt/project-files/cities/public-refs/):
not the flats' engine with other rooms but buildings of several volumes of their own heights, each with its own wide
public staircases that open onto the corridors, a front door in a vestibule (tambour) with steps, ribbon windows, halls
two storeys high.

    t, rooms = civic.school("silicate")   # the Moscow school 65-426/1 «самолётик» after the BTI plans of school No. 1543

Scale (scale.py draws the flats twice the drawings): a public plan is drawn 1.5 times its metres, a storey is 5 blocks
(4 rows of room: the 3.3 m of a school against the 2.7 m of a flat).

The plans are drawn here by code, not by hand: a Plan is a grid of series.py characters ("#" wall, "W" window, "D" door,
"X" front door, "S" a staircase's window wall, "s" the staircase, " " outside, letters rooms). A Civic is a House
(house.py) with the public staircases (pstair), the front door, the roofs and the halls of two storeys (voids) of its own.
"""
import house as H
import compose
import scale
import series as S

N, E, SO, W = H.N, H.E, H.SO, H.W
STOREY = 5
TURN = {N: SO, SO: N, E: W, W: E}


class Plan:
    """A plan grid L x D, all outside to begin with."""

    def __init__(self, L, D):
        self.L, self.D = L, D
        self.g = [[" "] * L for _ in range(D)]

    def put(self, x, z, ch):
        if 0 <= x < self.L and 0 <= z < self.D:
            self.g[z][x] = ch

    def get(self, x, z):
        return self.g[z][x] if 0 <= x < self.L and 0 <= z < self.D else " "

    def box(self, x0, z0, x1, z1, ch="#"):
        """A walled box: the walls on its edges, ch inside... or filled with ch when ch is a wall."""
        for z in range(z0, z1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, z, ch)

    def room(self, x0, z0, x1, z1, ch):
        """Room cells x0..x1, z0..z1 (the walls round it are drawn separately)."""
        self.box(x0, z0, x1, z1, ch)

    def walls(self, x0, z0, x1, z1):
        """A wall ring round the box."""
        for x in range(x0, x1 + 1):
            self.put(x, z0, "#")
            self.put(x, z1, "#")
        for z in range(z0, z1 + 1):
            self.put(x0, z, "#")
            self.put(x1, z, "#")

    def windows(self, x0, x1, z, pier=4, glass=3):
        """A ribbon of windows along a facade row: `glass` panes, a pier, and so on, centred in x0..x1."""
        span = x1 - x0 + 1
        unit = glass + 1
        n = max(1, (span + 1) // unit)
        used = n * unit - 1
        start = x0 + (span - used) // 2
        for i in range(n):
            for j in range(glass):
                x = start + i * unit + j
                if x0 <= x <= x1:
                    self.put(x, z, "W")

    def windows_z(self, x, z0, z1, glass=3):
        span = z1 - z0 + 1
        unit = glass + 1
        n = max(1, (span + 1) // unit)
        used = n * unit - 1
        start = z0 + (span - used) // 2
        for i in range(n):
            for j in range(glass):
                z = start + i * unit + j
                if z0 <= z <= z1:
                    self.put(x, z, "W")

    def rows(self):
        return ["".join(r) for r in self.g]

    def mirror_x(self, letters=None):
        """The right half as the left one mirrored about the middle column."""
        letters = letters or {}
        for z in range(self.D):
            for x in range(self.L // 2):
                ch = self.g[z][x]
                self.g[z][self.L - 1 - x] = letters.get(ch, ch)


class Civic(H.House):
    """A public volume: the House's walls, windows, floors, rooms and furniture, its own staircases and roof."""

    def build(self):
        t = super().build()
        self.voids()
        return t

    # -------------------------------------------------------------- the halls two storeys high
    def voids(self):
        """Rooms of floor k whose letters the series lists in "voids"[k]: the slab under them is taken out, and with it the
        lamps and whatever hung under the ceiling of the floor below: one hall two storeys high."""
        for k, letters in self.s.get("voids", {}).items():
            self.on_floor(k)
            s = self.slab(k)
            cells = {(bx, bz) for (bx, bz), c in self.grid.items() if c in letters}
            for bx, bz in cells:
                for y in (s, s - 1):
                    # the hall's own furniture (the stage curtain's pelmet hangs through the slab) stays, lamps go
                    name = self.tpl.get(self.ox + bx, y, self.oz + bz) or ""
                    if not name.startswith(("pub_", "apt_")) or any(w in name for w in LAMPS):
                        self.at(bx, y, bz, "minecraft:air")
                # bats and mobs: the hall is lit even with its lamps off, by hidden light blocks in the slab's row
                if bx % 5 == 2 and bz % 5 == 2 and self.tpl.get(self.ox + bx, s, self.oz + bz) in (None, "air"):
                    self.at(bx, s, bz, "minecraft:light", {"level": "11", "waterlogged": "false"})
            # the slab's edge in the hall's walls: the facade's band and the partitions' floor would show as a stripe of
            # brick and concrete across the hall, so the wall runs on plastered through it
            walls = {(bx + dx, bz + dz) for bx, bz in cells for dx, dz in H.DIRS.values()} - cells
            for wx, wz in walls:
                name = self.tpl.get(self.ox + wx, s, self.oz + wz) or ""
                if name.startswith("facade_") and self.is_facade(wx, wz):
                    self.at(wx, s, wz, f"facade_{self.colour}_mid", {"facing": self.outward(wx, wz), "inner": "plaster"})
                elif name == "floor_concrete":
                    self.at(wx, s, wz, "wall_mid", {d: "plaster" for d in H.DIRS})
            self.on_floor(None)

    # -------------------------------------------------------------- the roof: a parapet, the bitumen, nothing of a flat's
    def roof_level(self):
        R, col = self.roof, self.colour
        for (bx, bz), c in self.grid.items():
            if self.is_facade(bx, bz) and (bx, bz) not in self.joints:
                corner = self.convex(bx, bz)
                self.at(bx, R, bz, f"facade_{col}_{'edge' if corner else 'band'}")
                self.at(bx, R + 1, bz, f"facade_{col}_{'edge' if corner else 'band'}")
            else:
                self.at(bx, R, bz, "roof_bitumen")
        for vx, vz in self.s.get("vents", []):
            self.at(vx, R + 1, vz, "vent_shaft", {"facing": N})
            self.at(vx, R + 2, vz, "vent_cap", {"facing": N})

    # -------------------------------------------------------------- the front door
    def facade_cell(self, bx, bz, s, k, c):
        if c != "X":
            return super().facade_cell(bx, bz, s, k, c)
        out = self.outward(bx, bz)
        look = H.OPP[out]
        col = self.colour
        self.at(bx, s, bz, "plinth" if k == 1 else f"facade_{col}_band")
        if k != 1:
            return self.window(bx, bz, s, out, self.inner_finish(bx, bz, k), k)
        left = (bx + H.DIRS[H.LEFT[look]][0], bz + H.DIRS[H.LEFT[look]][1])
        hinge = "right" if self.ch(*left) == "X" else "left"
        for n, row in enumerate(self.rows):
            if n < 2:
                self.at(bx, s + 1 + n, bz, self.s.get("front_door", "door_entrance"),
                        {"facing": look, "half": "lower" if n == 0 else "upper", "hinge": hinge, "open": "false",
                         "powered": "false"})
            else:           # the transom: glass over the doors
                fr = self.frame(bx, bz, k)
                props = {"facing": out, "frame": fr} if row == "mid" else {"facing": out, "inner": self.inner_finish(bx, bz, k),
                                                                          "frame": fr}
                self.at(bx, s + 1 + n, bz, f"window_{col}_{row}", props)
        dx, dz = H.DIRS[out]
        side = H.DIRS[H.LEFT[out]]
        for w in (-1, 0, 1):            # the steps: a landing at the floor, a step down to the pavement
            px, pz = bx + dx + side[0] * w, bz + dz + side[1] * w
            if self.outside(px, pz):
                self.at(px, s, pz, "floor_terrazzo")
            px, pz = bx + 2 * dx + side[0] * w, bz + 2 * dz + side[1] * w
            if self.outside(px, pz):
                self.at(px, s, pz, "step_terrazzo", {"type": "bottom", "facing": look, "rail": "none", "waterlogged": "false"})

    # -------------------------------------------------------------- the public staircases
    def stairs(self, i):
        for spec in self.s.get("pstairs", []):
            self.pstair(*spec)

    def pstair(self, x0, zw, look, f=3):
        """A public staircase: two flights of f blocks with a well between them, its window wall at row zw facing `look`
        (N: the stair lies south of the wall, SO: north of it). Per storey the first flight climbs from the floor landing
        (two rows, open onto the corridor or the lobby: no wall, no door) towards the window, the half-landing runs under
        the window, the second flight climbs back. The top floor has its landing and a plain window, no flight to the
        roof. Laid out in the staircase's own frame (lx across, lz from the window wall in) and turned into place."""
        Wd = 2 * f + 1
        south = look == SO      # the window wall faces south: the frame turned half round

        def P(lx, lz):
            return (x0 + (Wd - 1 - lx), zw - lz) if south else (x0 + lx, zw + lz)

        def F(d):
            return TURN[d] if south else d

        A = list(range(f))
        M = f
        B = list(range(f + 1, Wd))
        n1 = self.storey - 1
        rows1 = [5 - j + 1 for j in range(n1)]          # the first flight, from the landing towards the window
        mid = range(1, 1 + 6 - n1)                       # the half-landing, under the window
        fl = (7, 8)                                       # the floor landing
        F_ = self.floors

        def at_half(s, h):
            v = 2 * s + 2 + h
            return (v + 1) // 2 - 1, "bottom" if v % 2 else "top"

        hl_y, hl_type = at_half(0, n1 + 1)

        def put(lx, y, lz, name, props=None):
            x, z = P(lx, lz)
            self.at(x, y, z, name, props)

        def step(lx, y, lz, half, facing, rail="none"):
            put(lx, y, lz, "step_terrazzo", {"type": half, "facing": F(facing), "rail": rail, "waterlogged": "false"})

        for k in range(1, F_ + 1):
            s = self.slab(k)
            for lx in range(Wd):
                for lz in range(1, 9):
                    for y in range(s, s + self.storey):
                        put(lx, y, lz, "minecraft:air")
                    if k == 1:
                        put(lx, s, lz, "floor_terrazzo")
        for k in range(1, F_ + 1):
            s = self.slab(k)
            for lx in range(Wd):
                for lz in fl:
                    step(lx, s, lz, "top", N, "front" if (lx == M and lz == fl[0] and k < F_) else "none")
            put(0, s + self.top, fl[1], H.LAMP_STAIR, {"facing": F(E), "lit": "true"})
            if k == F_:
                for lx in range(1, Wd - 1):
                    x, z = P(lx, 0)
                    self.window(x, z, s, F(N), "paint_stair")
                break
            for lx in A:                                  # the first flight, towards the window
                for j, lz in enumerate(rows1):
                    y, half = at_half(s, j + 1)
                    step(lx, y, lz, half, N, "right" if lx == A[-1] else "none")
            for lx in range(Wd):                          # the half-landing
                for lz in mid:
                    step(lx, s + hl_y, lz, hl_type, SO, "front" if (lx == M and lz == mid[-1]) else "none")
            for lx in B:                                  # the second flight back up
                for j, lz in enumerate(reversed(rows1)):
                    y, half = at_half(s, n1 + 2 + j)
                    step(lx, y, lz, half, SO, "right" if lx == B[0] else "none")
            for lx in range(1, Wd - 1):                   # the window over the half-landing
                x, z = P(lx, 0)
                fr = self.frame(x, z, None)
                for n, row in enumerate(self.rows):
                    put(lx, s + hl_y + 1 + n, 0, f"window_{self.colour}_{row}",
                        {"facing": F(N), "frame": fr} if row == "mid" else {"facing": F(N), "inner": "paint_stair", "frame": fr})
            put(0, s + hl_y + self.top, 2, H.LAMP_STAIR, {"facing": F(E), "lit": "true"})


def _series(name, plan, rooms, floors, **kw):
    S.SERIES[name] = dict({"plan": plan.rows() if isinstance(plan, Plan) else plan, "rooms": rooms, "whole": True,
                           "storey": STOREY, "chute": False, "curtains": False, "frame": "pvc", "public": True,
                           "floors": floors}, **kw)
    if kw.get("pstairs"):
        S.SERIES[name]["stair"] = True


def _join(t, x0, z0, cells, y0, y1, door="door_room"):
    """Openings through the walls where two volumes abut (compose keeps each one's wall): air, or a door pair."""
    for x, z, facing, hinge in cells:
        for y in range(y0, y1):
            name = "minecraft:air"
            props = None
            if door and y < y0 + 2:
                name, props = door, {"facing": facing, "half": "lower" if y == y0 else "upper", "hinge": hinge, "open": "true",
                                     "powered": "false"}
            t.set(x - x0, y, z - z0, name, props)
    _clear_openings(t, [(x - x0, z - z0) for x, z, _, _ in cells], y0, y1)


KEEP = ("tube_lamp", "wall_puck")
LAMPS = ("lamp", "chandelier", "ceiling_plate", "puck")   # what a hall's void takes out with the slab   # lamps on the ceiling and the walls may stay by an opening


def _clear_openings(t, cells, y0, y1, reach=2):
    """Each volume was furnished before the openings were cut, as if the joint were a wall: whatever stands within `reach`
    of an opening (a bench across it, a stand hung on the wall that is gone) is taken out whole, every cell of the piece."""
    def base(name):
        n = name.split(":")[1]
        return n[:-5] if n.endswith("_part") else n

    seen = set()
    for cx, cz in cells:
        for x in range(cx - reach, cx + reach + 1):
            for z in range(cz - reach, cz + reach + 1):
                for y in range(y0, y1 + 1):
                    c = t.cells.get((x, y, z))
                    if not c or (x, y, z) in seen or not c[0].startswith(("mobtalk:pub_", "mobtalk:apt_")):
                        continue
                    pid = base(c[0])
                    if any(k in pid for k in KEEP):
                        continue
                    todo, piece = [(x, y, z)], set()
                    while todo:                    # the piece: its connected cells of the same id
                        q = todo.pop()
                        if q in piece:
                            continue
                        d = t.cells.get(q)
                        if not d or not d[0].startswith("mobtalk:") or base(d[0]) != pid:
                            continue
                        piece.add(q)
                        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                            todo.append((q[0] + dx, q[1] + dy, q[2] + dz))
                    for q in piece:
                        t.set(*q, "minecraft:air")
                    seen |= piece


# ======================================================================== the school 65-426/1 «самолётик»
# The teaching block (102 x 25 of the 68.55 x 16.48 m): a corridor of 4 down the middle on the upper floors, classrooms
# 12 x 9 both sides; two staircases in towers that stand out a block from the back facade by the corridor, their landings
# open onto it through a little lobby; a toilet beside each. The ground floor: the big hall with pillars in the middle
# of the front (the recreation and the cloakroom), the vestibule (a tambour of one storey) in front of it, the head's,
# the office and the doctor's at the back, the passage to the hall block off the back in the middle. The passage (9 x 22,
# two storeys, glazed) leads to the hall block (83 x 19): the canteen with its kitchen, the library and the changing rooms
# on the ground floor; the gym and the assembly hall two storeys high over them, the stair and a lobby between them.
TL, TD = 105, 27        # the teaching block with the towers' row
TOWER = (32, 66)        # the staircases' first columns (7 wide), their window wall the towers' row 26
MIRROR = {"a": "A", "b": "J", "i": "I", "d": "K", "e": "L", "f": "F", "g": "G", "h": "H"}   # the east half's rooms (not D E B W S X)


def _teaching(k):
    p = Plan(TL, TD)
    p.box(0, 0, TL - 1, 25, "#")
    # the front band (rows 1..9), the corridor (11..14), the back band (16..24)
    front = [(1, 12), (14, 25), (27, 38), (40, 51)]
    back = [(1, 12), (14, 24), (26, 30), (40, 51)]      # class, class, the toilet, class (the tower at 31..39)
    letters_f = "abid"
    for (x0, x1), ch in zip(front, letters_f):
        p.room(x0, 1, x1, 9, ch)
    p.room(1, 11, 51, 14, "c")
    for (x0, x1), ch in zip(back, "efgh"):
        p.room(x0, 16, x1, 24, ch)
    # the tower: the staircase 7 wide (32..38) rows 18..25, its window wall at 26; the lobby rows 15..17 open to the corridor
    p.room(32, 15, 38, 17, "c")
    p.room(32, 18, 38, 25, "s")
    for z in range(16, 27):
        p.put(31, z, "#")
        p.put(39, z, "#")
    for x in range(31, 40):
        p.put(x, 26, "#")
    for x in range(32, 39):
        p.put(x, 26, "S")
    for z in range(16, 18):     # the lobby's sides: the toilet's wall, the classroom's wall
        p.put(31, z, "#")
    p.mirror_x(MIRROR)
    # the middle column (52): a wall between the halves, the corridor through it
    for z in range(0, 26):
        p.put(52, z, "#")
    p.room(52, 11, 52, 14, "c")
    # windows: the front facade, the back facade, the end walls
    for (x0, x1) in front + [(TL - 1 - b, TL - 1 - a) for a, b in front]:
        p.windows(x0, x1, 0)
    for (x0, x1) in [(1, 12), (14, 24), (40, 51)]:
        p.windows(x0, x1, 25)
        p.windows(TL - 1 - x1, TL - 1 - x0, 25)
    for x in (26, 28, 30):      # the toilets: small windows
        p.put(x, 25, "W")
        p.put(TL - 1 - x, 25, "W")
    for z0, z1 in ((1, 9), (16, 24)):
        p.windows_z(0, z0, z1)
        p.windows_z(TL - 1, z0, z1)
    p.windows_z(0, 12, 13, glass=2)
    p.windows_z(TL - 1, 12, 13, glass=2)
    # doors off the corridor: each classroom near its blackboard end, the toilets, the lobby is open
    for (x0, x1) in front:
        p.put(x0 + 2, 10, "D")
        p.put(TL - 1 - x0 - 2, 10, "D")
    for (x0, x1) in [(1, 12), (14, 24), (40, 51)]:
        p.put(x0 + 2, 15, "D")
        p.put(TL - 1 - x0 - 2, 15, "D")
    p.put(28, 15, "D")
    p.put(TL - 1 - 28, 15, "D")
    for x in range(32, 39):     # the lobby: no wall to the corridor
        p.put(x, 15, "c")
        p.put(TL - 1 - x, 15, "c")
    if k == 1:
        _teaching_ground(p)
    elif k == 2:
        p.room(46, 16, 58, 24, "p")      # the passage's landing: a lobby across the back band in the middle
        for z in range(16, 25):
            p.put(45, z, "#")
            p.put(59, z, "#")
            p.put(52, z, "p")
        for x in range(46, 59):
            p.put(x, 15, "c" if 47 <= x <= 57 else "#")
        for x in range(46, 59):
            p.put(x, 25, "#")
        p.room(40, 16, 44, 24, "h")
        p.room(60, 16, 64, 24, "H")
    return p


def _teaching_ground(p):
    # the hall: the middle of the front band and the corridor (x 27..77), pillars every 6, the cloakroom in its east part
    p.room(27, 1, 77, 14, "v")
    for x in range(27, 78):
        p.put(x, 10, "v")
    for x in (33, 39, 45, 59, 65, 71):
        for z in (5, 10):
            p.put(x, z, "#")
    p.room(58, 1, 77, 7, "r")               # the cloakroom: its barrier along row 8 (furniture), open to the hall
    for x in range(27, 78):
        p.put(x, 0, "#")
    p.windows(27, 51, 0, glass=4)
    p.windows(53, 77, 0, glass=4)
    for x in range(48, 57):                 # the tambour abuts here: the doors through (compose joins)
        p.put(x, 0, "#")
    # the back band in the middle: the passage's lobby, the head's and the office beside it, the doctor's
    p.room(46, 16, 58, 24, "p")
    for z in range(16, 25):
        p.put(45, z, "#")
        p.put(59, z, "#")
    for x in range(46, 59):
        p.put(x, 15, "v")
        p.put(x, 25, "#")
    p.room(40, 16, 44, 24, "m")              # the doctor's
    p.room(60, 16, 64, 24, "o")              # the office


_T_ROOMS = {"a": "classroom", "b": "classroom", "i": "classroom", "I": "classroom", "c": "corridor", "d": "classroom", "e": "classroom", "f": "classroom",
            "g": "wc_pub", "h": "staff", "A": "classroom", "J": "classroom", "K": "classroom", "L": "classroom",
            "F": "classroom", "G": "wc_pub", "H": "office", "v": "vestibule", "r": "cloakroom", "p": "corridor",
            "m": "medical", "o": "office"}
_T_ROOMS = {k: ("S", v) for k, v in _T_ROOMS.items()}


def _tambour():
    p = Plan(11, 5)
    p.box(0, 0, 10, 4, "#")
    p.room(1, 1, 9, 3, "v")
    for x in (4, 5, 6):
        p.put(x, 0, "X")
    for x in (2, 8):
        p.put(x, 0, "W")
    p.put(0, 2, "W")
    p.put(10, 2, "W")
    return p


def _passage():
    p = Plan(9, 22)
    p.box(0, 0, 8, 21, "#")
    p.room(1, 1, 7, 20, "c")
    p.windows_z(0, 1, 20, glass=4)
    p.windows_z(8, 1, 20, glass=4)
    return p


HL, HD = 83, 19


def _halls(k):
    p = Plan(HL, HD)
    p.box(0, 0, HL - 1, HD - 1, "#")
    # the core: the lobby off the passage (38..44, rows 1..9), the staircase (38..44, rows 10..17, window wall 18)
    p.room(38, 1, 44, 9, "c" if k < 3 else "t")
    p.room(38, 10, 44, 17, "s")
    for x in range(38, 45):
        p.put(x, 18, "S")
    if k == 3:                               # the staff room up top: walled off the landing, one door to it
        for x in range(38, 45):
            p.put(x, 9, "#")
        p.put(41, 9, "D")
    if k == 1:
        p.room(1, 1, 36, 8, "L")             # the library
        p.room(1, 10, 36, 17, "R")           # its reading hall
        p.room(46, 1, 68, 17, "e")           # the canteen
        p.room(70, 1, 81, 17, "q")           # the kitchen
        for x0, x1 in ((1, 36), (46, 68), (70, 81)):
            p.windows(x0, x1, 0)
        for x0, x1 in ((1, 36), (46, 68), (70, 81)):
            p.windows(x0, x1, HD - 1)
        p.put(37, 4, "D")
        p.put(45, 4, "D")
        p.put(45, 6, "D")
        p.put(69, 8, "D")
        p.put(27, 9, "D")
        p.put(HL - 1, 9, "X")               # the kitchen's back door
    else:
        g, a = ("y", "a") if k == 2 else ("Y", "Z")
        p.room(8, 1, 36, 17, g)              # the gym
        p.room(46, 1, 81, 17, a)             # the assembly hall
        for z in range(1, 18):
            p.put(7, z, "#")
        if k == 2:                           # the changing rooms at its west end, a store over them
            p.room(1, 1, 6, 8, "w")
            p.room(1, 10, 6, 17, "x")
            p.put(7, 4, "D")
            p.put(7, 13, "D")
        else:
            p.room(1, 1, 6, 17, "u")
        if k == 2:
            p.put(37, 4, "D")
            p.put(37, 5, "D")
            p.put(45, 4, "D")
            p.put(45, 5, "D")
        for x0, x1 in ((8, 36), (46, 76)):     # none along the stage (x 77..81): its wings are dark
            p.windows(x0, x1, 0, glass=4)
            p.windows(x0, x1, HD - 1, glass=4)
        p.windows(1, 6, 0, glass=2)
        p.windows(1, 6, HD - 1, glass=2)
    for z0, z1 in ((1, 17),):
        p.windows_z(0, z0, z1, glass=4)
        if k == 1:                           # the kitchen's end; above it the wall behind the stage is blind
            p.windows_z(HL - 1, z0, z1, glass=4)
    return p


_H_ROOMS = {"c": "corridor", "t": "staff", "w": "changing", "x": "changing", "L": "library", "R": "library", "e": "canteen",
            "q": "kitchen_pub", "y": "gym", "u": "store", "a": "assembly", "Y": "void", "Z": "void"}
_H_ROOMS = {k: ("S", v) for k, v in _H_ROOMS.items()}


def _register():
    if "school2" in S.SERIES:
        return
    t1, t2, t3 = _teaching(1), _teaching(2), _teaching(3)
    _series("school2", t3, _T_ROOMS, 3, floor_plans={1: t1.rows(), 2: t2.rows()},
            pstairs=[(TOWER[0], 26, SO), (TL - 1 - TOWER[0] - 6, 26, SO)], joints=[(48, 0, 56, 0), (47, 25, 57, 25)])
    _series("school2-tambour", _tambour(), {"v": ("S", "vestibule")}, 1, joints=[(0, 4, 10, 4)])
    _series("school2-passage", _passage(), {"c": ("S", "corridor")}, 2, joints=[(0, 0, 8, 0), (0, 21, 8, 21)])
    h1, h2, h3 = _halls(1), _halls(2), _halls(3)
    _series("school2-halls", h3, _H_ROOMS, 3, floor_plans={1: h1.rows(), 2: h2.rows()}, pstairs=[(38, 18, SO)],
            joints=[(37, 0, 45, 0)], voids={3: "YZ"})
    for kind in ("void",):
        H.FINISH.setdefault("void", "plaster")
        H.FLOOR.setdefault("void", "floor_parquet")
        H.LAMPS.setdefault("void", ["pub_tube_lamp"])


def school(colour="silicate", seed=0):
    _register()
    main = Civic("school2", 1, 3, colour, seed=seed + 1)
    main.build()
    tam = Civic("school2-tambour", 1, 1, colour, seed=seed + 2)
    tam.build()
    pas = Civic("school2-passage", 1, 2, colour, seed=seed + 3)
    pas.build()
    hal = Civic("school2-halls", 1, 3, colour, seed=seed + 4)
    hal.build()
    z_main = tam.depth
    parts = [compose.Part(tam, 0, 47, 0), compose.Part(main, 0, 0, z_main),
             compose.Part(pas, 0, 48, z_main + 26), compose.Part(hal, 0, 52 - 41, z_main + 26 + pas.depth)]
    t, flats = compose.compose(parts)
    foot = set().union(*(p.foot for p in parts))
    x0 = min(x for x, _ in foot) - scale.MARGIN
    z0 = min(z for _, z in foot) - scale.MARGIN
    s1 = main.slab(1) + 1
    # the tambour into the hall: three openings
    _join(t, x0, z0, [(x, z, SO, "left") for x in (51, 52, 53) for z in (z_main - 1, z_main)], s1, s1 + 3, door=None)
    # the passage into the teaching block and into the hall block, on both its floors
    zb = z_main + 25
    zh = z_main + 26 + pas.depth
    for k in (1, 2):
        y = main.slab(k) + 1
        cells = [(x, z, SO, "left" if x % 2 else "right") for x in (51, 52, 53) for z in (zb, zb + 1)]
        cells += [(x, z, SO, "left" if x % 2 else "right") for x in (51, 52, 53) for z in (zh - 1, zh)]
        _join(t, x0, z0, cells, y, y + 3, door=None)
    return t, flats


# ---------------------------------------------------------------- the furnishing (rooms.py looks it up in public.FURNISH)
# After the Moscow references (public-refs/sheets int_school_*): the cloakroom's white mesh bays in rows, the canteen's
# serving line along the kitchen wall, the library's free-standing stacks and a separate reading room, the assembly hall's
# stage with its curtain and pelmet and rows of folding seats, the recreations' stands, fountain and bell.

def _box(rm):
    xs = [x for x, _ in rm.cells]
    zs = [z for _, z in rm.cells]
    return min(xs), min(zs), max(xs), max(zs)


def _put(rm, pid, x, z, facing, height=0, backed=True):
    y = rm.s + 1 + height
    if rm.fits(pid, x, y, z, facing, backed=backed):
        rm.write(pid, x, y, z, facing)
        return True
    return False


def _wall(rm, pid, side, height=0, tries=None):
    """The piece against the room's `side` wall (the wall to the west, north...), facing into the room, at the first free
    cell along it from a random start."""
    x0, z0, x1, z1 = _box(rm)
    face = H.OPP[side]
    if side in (H.W, H.E):
        x = x0 if side == H.W else x1
        line = [(x, z) for z in range(z0, z1 + 1)]
    else:
        z = z0 if side == H.N else z1
        line = [(x, z) for x in range(x0, x1 + 1)]
    k = rm.r.randrange(len(line))
    for x, z in (line[k:] + line[:k])[:tries]:
        if _put(rm, pid, x, z, face, height):
            return True
    return False


def _windows(rm, flowers=0.0):
    """Under every window the radiator, in the cell before it (nothing else stands there); in the classrooms most sills
    carry the class's flower pots."""
    h = rm.h
    for x, z in sorted(rm.cells):
        for d, (dx, dz) in H.DIRS.items():
            if h.ch(x + dx, z + dz) == "W" and h.tpl.get(h.ox + x, rm.s + 1, h.oz + z) is None:
                pid = "apt_radiator"
                if rm.r.random() < flowers:
                    pid = rm.r.choice(["pub_sill_flowers_a", "pub_sill_flowers_a", "pub_sill_flowers_b"])
                rm.write(pid, x, rm.s + 1, z, H.OPP[d])
                break


def _scatter(rm, pid, n, flat=True):
    """`n` of a small piece on free floor cells anywhere in the room (satchels by the desks)."""
    k = 0
    for x, z, f in rm.spots():
        if k >= n:
            break
        if rm.fits(pid, x, rm.s + 1, z, f, flat=flat, backed=False):
            rm.write(pid, x, rm.s + 1, z, f)
            k += 1


def _void(rm, flat):
    import public
    public._lamps(rm, 5)


def classroom(rm, flat):
    """The blackboard on the west wall with the class facing it, the teacher's desk, three columns of desks, glazed cabinets
    on the back wall, a portrait or a table over the side; the third floor's last room is the chemistry room."""
    import public
    _windows(rm, flowers=0.2)
    if rm.k == 3 and rm.letter == "K":
        return chemistry(rm, flat)
    public.classroom(rm, flat)
    for _ in range(2):
        _wall(rm, "pub_class_cabinet", H.E) or rm.against("pub_class_cabinet")
    if rm.r.random() < 0.5:
        for pid in ("pub_portrait_pushkin", "pub_portrait_tolstoy"):
            rm.against(pid, height=2)
    else:
        rm.against("pub_wall_map", height=1)
    rm.against("pub_wall_newspaper", height=1)
    rm.against("apt_wall_clock", height=2)
    rm.against("apt_wall_radio", height=2)
    rm.against("pub_waste_bin")
    _scatter(rm, "pub_satchels", rm.r.randint(2, 5))


def chemistry(rm, flat):
    """The chemistry room: the demonstration bench before the board, the fume cupboard in the corner, Mendeleev's table,
    the pupils' desks."""
    import public
    x0, z0, x1, z1 = _box(rm)
    zc = (z0 + z1) // 2
    _put(rm, "pub_blackboard", x0, zc, "east", height=1)
    _put(rm, "pub_poster_table", x0, zc - 3, "east", height=1) or rm.against("pub_poster_table", height=1)
    _put(rm, "pub_lab_bench", x0 + 2, zc + 1, "east", backed=False) or _put(rm, "pub_lab_bench", x0 + 2, zc, "east", backed=False)
    for z in (z0, z1):
        if _put(rm, "pub_fume_cupboard", x0, z, "east"):
            break
    for x in range(x0 + 5, x1, 2):
        for z in (z0 + 1, z0 + 4, z0 + 7):
            if z + 1 <= z1:
                _put(rm, "pub_school_desk", x, z, "east", backed=False)
    _wall(rm, "pub_class_cabinet", H.E)
    public._lamps(rm)


def corridor(rm, flat):
    """A recreation: the stands and the timetable on the walls, the drinking fountain, the bell over the doors, benches
    and big plants in tubs by the windows."""
    import public
    _windows(rm, flowers=0.3)
    n = max(1, len(rm.cells) // 60)               # a long corridor gets its things all along it
    for pid in ("pub_honours_board", "pub_stand_school", "pub_stand_timetable"):
        if not flat.get(pid) or rm.r.random() < 0.3:
            flat[pid] = rm.against(pid, height=1)
    for _ in range(n):
        for pid in ("pub_wall_newspaper", "pub_wall_map", "pub_stand_school"):
            rm.against(pid, height=1)
        rm.against("pub_fire_cabinet")
        rm.against("pub_drinking_fountain")
        rm.against("pub_school_bell", height=3)
        rm.against("apt_wall_clock", height=2)
        for _ in range(3):
            rm.against("pub_gym_bench")
        rm.against("pub_waste_bin")
        for _ in range(2):
            rm.against(rm.r.choice(["apt_ficus", "apt_palm_tub"]))
    public._lamps(rm, 5)


def vestibule(rm, flat):
    """The ground hall: the trophy cabinets, the honours board and «Наша школа», the watchman's desk by the door, palms."""
    import public
    _windows(rm)
    for _ in range(2):
        rm.against("pub_trophy_cabinet")
    rm.against("pub_fire_cabinet")
    rm.against("apt_hall_mirror")
    rm.against("apt_wall_clock", height=2)
    rm.against("pub_stand_timetable", height=1)
    rm.against("pub_honours_board", height=1)
    rm.against("pub_stand_school", height=1)
    rm.against("pub_school_bell", height=3)
    if rm.against("apt_desk", name="watch"):
        rm.front("apt_chair", "watch", facing_back=True)
    for _ in range(3):
        rm.against("apt_palm_tub")
    for _ in range(4):
        rm.against("pub_gym_bench")
    rm.against("pub_waste_bin")
    public._lamps(rm)


def cloakroom(rm, flat):
    """Rows of white mesh bays, back to back, with aisles between, one bay a class: coats on most of them."""
    import public
    _windows(rm)
    x0, z0, x1, z1 = _box(rm)
    rows = []
    z = z0
    while z <= z1:
        rows.append((z, "south"))
        if z + 3 <= z1:
            rows.append((z + 3, "north"))
        z += 4
    for z, f in rows:
        for x in range(x0 + 1, x1):
            if (x - x0) % 6 == 0:                  # a gap every five bays to get through
                continue
            pid = "pub_cloak_section" if rm.r.random() < 0.7 else "pub_cloak_section_empty"
            _put(rm, pid, x, z, f, backed=False)
    public._lamps(rm)


def library(rm, flat):
    """The lending library: the issue desk by the door, the card catalogue, rows of double-sided stacks with aisles; the
    reading room (R): rows of tables with the green lamps, the newspapers' stand, the bookcases round the walls."""
    import public
    _windows(rm, flowers=0.4)
    x0, z0, x1, z1 = _box(rm)
    if rm.letter == "R":
        for x in range(x0 + 2, x1 - 2, 4):         # rows of tables, chairs on both long sides
            for z in range(z0 + 2, z1 - 1, 3):
                if _put(rm, "pub_reading_table", x, z, "north", backed=False):
                    for dx in (0, 1):
                        _put(rm, "apt_chair", x + dx, z - 1, "south", backed=False)
                        _put(rm, "apt_chair", x + dx, z + 1, "north", backed=False)
        for _ in range(8):
            if not rm.against(rm.r.choice(["apt_bookcase_tall", "apt_bookcase"])):
                break
        rm.against("pub_portrait_tolstoy", height=2)
        public._lamps(rm)
        return
    if rm.against("pub_issue_desk", name="issue", near=None):
        rm.front("apt_chair", "issue", facing_back=True)
    rm.against("pub_card_files")
    rm.against("pub_card_files")
    for x in range(x0 + 3, x1 - 1, 3):
        for z in range(z0 + 1, z1):
            _put(rm, "pub_library_stack", x, z, "east", backed=False)
    for _ in range(6):
        if not rm.against("apt_bookcase_tall"):
            break
    public._lamps(rm)


def canteen(rm, flat):
    """The serving line along the kitchen's wall (east), the dining tables in rows, the kompot glasses on them."""
    import public
    _windows(rm)
    x0, z0, x1, z1 = _box(rm)
    for z in range(z0, z1 + 1):
        pid = "pub_serving_pans" if z % 3 else "pub_serving_kompot"
        _put(rm, pid, x1, z, "west")
    for _ in range(4):                             # wash your hands before the meal: the basins by the door
        rm.against("apt_washbasin")
    rm.against("pub_menu_board", height=1)
    rm.against("pub_stand_school", height=1)
    for _ in range(2):
        rm.against("pub_waste_bin")
    for x in range(x0 + 1, x1 - 3, 3):            # the tables in rows, four stools at each
        for z in range(z0 + 2, z1 - 1, 3):
            if _put(rm, "pub_canteen_table", x, z, "north", backed=False):
                for dx in (0, 1):
                    _put(rm, "apt_stool", x + dx, z - 1, "south", backed=False)
                    _put(rm, "apt_stool", x + dx, z + 1, "north", backed=False)
    public._lamps(rm)


def kitchen_pub(rm, flat):
    """The canteen's kitchen (пищеблок), after the references: an island of ranges and boiling pans back to back down the
    middle with a way round both ends, the roasting ovens and refrigerated cabinets along the walls, washing baths and
    steel prep tables in rows, racks of pots, the dough mixer, the vegetable corner with the peeler and crates."""
    import public
    x0, z0, x1, z1 = _box(rm)
    xm = (x0 + x1) // 2
    cook = ["pub_boiling_pan", "pub_kitchen_range", "pub_kitchen_range", "pub_kitchen_range"]
    for i, z in enumerate(range(z0 + 3, z1 - 2)):
        _put(rm, cook[i % len(cook)], xm, z, "west", backed=False)
        _put(rm, cook[(i + 2) % len(cook)], xm + 1, z, "east", backed=False)
    rows = (("wash", ["pub_wash_bath", "pub_wash_bath", "pub_wash_bath", "apt_kitchen_sink_white"]),
            ("ovens", ["pub_oven_cabinet", "pub_oven_cabinet", "pub_oven_cabinet"]),
            ("prep", ["pub_prep_table_board", "pub_prep_table_grinder", "pub_prep_table_bowls", "pub_prep_table_board"]),
            ("cold", ["pub_fridge_cabinet", "pub_fridge_cabinet"]),
            ("racks", ["pub_kitchen_rack", "pub_kitchen_rack", "pub_kitchen_rack"]),
            ("dough", ["pub_dough_mixer", "pub_prep_table_bowls"]),
            ("veg", ["pub_potato_peeler", "pub_veg_crates", "pub_veg_crates"]))
    for name, pids in rows:
        if rm.against(pids[0], name=name, row=True):
            for pid in pids[1:]:
                rm.run(pid, name)
    for _ in range(3):                             # more steel tables standing free between the walls and the island
        rm.table("pub_prep_table_bowls" if rm.r.random() < 0.5 else "pub_prep_table_board", "apt_stool", chairs=0)
    _wall(rm, "pub_fire_cabinet", rm.r.choice((H.N, H.W)), 1)
    _scatter(rm, "pub_waste_bin", 2)
    public._lamps(rm)


def assembly(rm, flat):
    """The assembly hall: the stage across the east end a block high with steps up, its curtain drawn to the sides and
    the pelmet across, the lectern and the piano on it; rows of folding seats facing it with an aisle up the middle."""
    import public
    x0, z0, x1, z1 = _box(rm)
    h, y = rm.h, rm.s + 1
    sx = x1 - 4                                    # the stage's front edge
    for x in range(sx, x1 + 1):
        for z in range(z0, z1 + 1):
            h.at(x, y, z, "minecraft:dark_oak_planks" if x == sx else "floor_parquet")
    for z in (z0, z1):                             # steps up at both ends
        h.at(sx - 1, y, z, "minecraft:dark_oak_stairs", {"facing": "east", "half": "bottom", "shape": "straight",
                                                         "waterlogged": "false"})
    cx = sx + 1                                    # the curtain's line
    for z in range(z0, z1 + 1):
        if z <= z0 + 2 or z >= z1 - 2:
            h.at(cx, y + 1, z, "pub_stage_curtain", {"facing": "west", "lit": "true"})
            for i in (1, 2):
                h.at(cx, y + 1 + i, z, "pub_stage_curtain_part", {"facing": "west", "lit": "true", "part": str(i)})
        h.at(cx, y + 4, z, "pub_stage_curtain_top", {"facing": "west", "lit": "true"})
        for i in (1, 2):
            h.at(cx, y + 4 + i, z, "pub_stage_curtain_top_part", {"facing": "west", "lit": "true", "part": str(i)})
    zc = (z0 + z1) // 2
    rm.write("pub_lectern", sx, y + 1, zc + 3, "west")
    rm.write("apt_piano", x1, y + 1, zc - 3, "west")
    for x in range(x0 + 2, sx - 2, 2):
        for z in range(z0 + 1, z1):
            if abs(z - zc) > 0:
                _put(rm, "pub_theatre_seat", x, z, "east", backed=False)
    public._lamps(rm)


def gym(rm, flat):
    """The gym as before, and the vaulting buck with its springboard set out."""
    import public
    _windows(rm)
    x0, z0, x1, z1 = _box(rm)
    zc = (z0 + z1) // 2
    _put(rm, "pub_football_goal", x0, zc, "east")
    _put(rm, "pub_football_goal", x1, zc, "west")
    public.gym(rm, flat)
    for _ in range(4):
        rm.against("pub_gym_bench")
    rm.against("pub_ball_rack")
    xm = (x0 + x1) // 2
    if _put(rm, "pub_vaulting_horse", xm, zc, "west", backed=False):
        _put(rm, "pub_springboard", xm - 2, zc, "west", backed=False)
    rm.against("pub_school_bell", height=3)


def _plain(fn, flowers=0.0):
    """The rooms public.py furnishes (the staff room, the head's, the doctor's, the toilets...) with the windows' radiators
    and flowers first."""
    def furnish(rm, flat):
        _windows(rm, flowers)
        fn(rm, flat)
    return furnish


def _hook():
    import public
    public.FURNISH.setdefault("void", _void)
    for kind, fl in (("staff", 0.6), ("office", 0.6), ("medical", 0.3), ("store", 0.0), ("wc_pub", 0.0), ("changing", 0.0),
                     ("kitchen_pub", 0.0)):
        public.FURNISH[kind] = _plain(getattr(public, kind) if kind != "kitchen_pub" else kitchen_pub, fl)
    for kind, fn in (("classroom", classroom), ("corridor", corridor), ("vestibule", vestibule), ("cloakroom", cloakroom),
                     ("library", library), ("canteen", canteen), ("assembly", assembly),
                     ("gym", gym)):
        public.FURNISH[kind] = fn
    public.KINDS["library"] = ("plaster", "floor_parquet")
    H.FINISH["library"] = "plaster"


_hook()
