"""The casino resort «Фортуна»: a Las Vegas strip resort as one structure template.

    python3 -I tools/casino/resort.py [plan OUT.png]

Writes src/main/resources/data/mobtalk/structure/casino/resort.nbt (80 x 40 x 74), placed by CasinoResort as a rare standalone
structure. The ground is the plaza's paving at y = 2 (two layers of foundation under it, so the pool has a floor); the approach is
from the north (z = 0).

What stands there, front to back (a hotel may come later as its own building):
  - the plaza: a long pool of dancing jets lit from under the water, a bridge over it to the casino, palms, two giant red dice,
    lamps, and at the corner the pylon sign, «ФОРТУНА» in neon letters from top to bottom between running lights;
  - the casino: a rotunda forty-six blocks across. A ring of fluted columns carries a lit cornice; behind it the round wall with
    tall windows; over the hall a drum of windows and a glass dome on gold ribs studded with lights, a lantern and a spire on top.
    The portico in front has «★КАЗИНО★» in its frieze under a pediment. Inside: a round bar in the middle under the great
    chandelier, the table pits (twenty-one, roulette, cards) round it, a ring of columns, and between them and the wall the
    slot machines under a lit coffered ceiling, jackpot boards on the walls and the cashiers' cage at the back;
  - behind the rotunda a lawn with palms and lamps.
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nbt as N  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "src", "main", "resources", "data", "mobtalk", "structure", "casino")
DATA_VERSION = 3955
SX, SY, SZ = 80, 40, 74
G = 2  # the plaza's paving
F = G + 1  # the casino's floor, one step up; people stand at F + 1
CX, CZ = 40, 42  # the rotunda's centre (the middle of block 40, 42)
CEIL = 13  # the coffered ceiling of the ring round the hall
ROOF = CEIL + 1
DRUM_R, DOME_Y, DOME_H = 12, 20, 11  # the dome: over the drum, a half-ellipsoid
WALL_R, COLONNADE_R = 19, 22
GARDEN_R = 31  # the lawn behind the rotunda reaches this far from its axis

QUARTZ, PILLAR, GOLD, DARK = "smooth_quartz", "quartz_pillar", "gold_block", "polished_blackstone"
LETTERS = "КАЗИНО★ФРТУ"


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


def polar(x, z):
    """Distance from the rotunda's axis and the angle in degrees, 0 to the north (-z), 90 to the east."""
    dx, dz = x - CX, z - CZ
    return math.hypot(dx, dz), math.degrees(math.atan2(dx, -dz)) % 360


def outward(x, z):
    """The horizontal facing that points away from the rotunda's axis most nearly."""
    dx, dz = x - CX, z - CZ
    if abs(dx) >= abs(dz):
        return "east" if dx > 0 else "west"
    return "south" if dz > 0 else "north"


def inward(x, z):
    return {"east": "west", "west": "east", "north": "south", "south": "north"}[outward(x, z)]


def ring(r0, r1):
    """The cells whose middles lie between the two radii."""
    for x in range(SX):
        for z in range(SZ):
            r, _ = polar(x, z)
            if r0 <= r < r1:
                yield x, z


def word(lot, text, x, y, z, facing, step):
    """A sign of two-block neon letters on a wall: the top row at y, the letters running to the viewer's right one cell after
    another along step (dx, dz). Returns the cells used."""
    for ch in text:
        i = LETTERS.index(ch)
        for dz_ in range(1 if ch == "★" else 2):
            cx, cz = x + step[0] * dz_, z + step[1] * dz_
            lot.casino(cx, y, cz, "casino_letter", facing=facing, letter=i, part=dz_)
            lot.casino(cx, y - 1, cz, "casino_letter", facing=facing, letter=i, part=2 + dz_)
        n = 1 if ch == "★" else 2
        x, z = x + step[0] * n, z + step[1] * n


# ---------------------------------------------------------------- the ground and the plaza

def ground(lot):
    lot.fill(0, 0, 0, SX - 1, 0, SZ - 1, "stone")
    lot.fill(0, 1, 0, SX - 1, 1, SZ - 1, "dirt")
    for x in range(SX):
        for z in range(SZ):
            # the plaza's paving: smooth sandstone in a grid of cut lines, a border of polished granite
            edge = x in (0, SX - 1) or z in (0, SZ - 1)
            lot.put(x, G, z, "polished_granite" if edge else ("cut_sandstone" if (x % 6 == 0 or z % 6 == 0) else "smooth_sandstone"))


def pool(lot):
    """The pool across the front: two blocks deep, lit from the floor, rows of jets; a bridge to the portico over the middle."""
    x0, x1, z0, z1 = 6, 73, 3, 13
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            rim = x in (x0, x1) or z in (z0, z1)
            bridge = 36 <= x <= 44
            if rim and not bridge:
                lot.put(x, G, z, "polished_diorite")
                lot.put(x, G + 1, z, "smooth_quartz_slab", type="bottom", waterlogged=False)
                continue
            if bridge:
                lot.put(x, G, z, "polished_diorite" if x in (36, 44) else "red_terracotta" if x in (39, 40, 41) else "smooth_quartz")
                if x in (36, 44):
                    lot.put(x, G + 1, z, "quartz_slab" if z % 3 else GOLD, **({"type": "bottom", "waterlogged": False} if z % 3 else {}))
                continue
            lot.put(x, 0, z, "sea_lantern" if (x + z) % 4 == 0 else "prismarine_bricks")
            lot.put(x, 1, z, "water", level=0)
            lot.put(x, G, z, "water", level=0)
    # the jets: a long line down the middle of each half and a zigzag of shorter ones either side
    for x in list(range(x0 + 2, 35, 2)) + list(range(46, x1 - 1, 2)):
        lot.casino(x, G, 8, "casino_fountain_jet", waterlogged=True)
        lot.put(x, 0, 8, "sea_lantern")
        if x % 4 == 0:
            for z in (5, 11):
                lot.casino(x, G, z, "casino_fountain_jet", waterlogged=True)
                lot.put(x, 0, z, "sea_lantern")
    # lamps on the bridge
    for z in (z0, z1):
        for x in (36, 44):
            lamp(lot, x, G + 1, z, 3)


def lamp(lot, x, y, z, h=3):
    for i in range(h):
        lot.put(x, y + i, z, "polished_blackstone_wall", up=True, north="none", south="none", east="none", west="none", waterlogged=False)
    lot.put(x, y + h, z, GOLD)
    lot.put(x, y + h + 1, z, "lantern", hanging=False, waterlogged=False)


def palm(lot, x, z, h, y=G + 1):
    """A palm h blocks tall whose trunk bows out and comes back upright under the crown, turned its own way."""
    facing = ("north", "east", "south", "west")[(x * 31 + z * 17) % 4]
    for i in range(h):
        lot.casino(x, y + i, z, "casino_palm_trunk", facing=facing, bend=round(3 * math.sin(math.pi * i / h)))
    lot.casino(x, y + h, z, "casino_palm", facing=facing)


def die(lot, x0, z0, faces):
    """A giant red die, three blocks a side, white pips; faces: (north, east, up) pip counts."""
    pips = {1: [(1, 1)], 2: [(0, 0), (2, 2)], 3: [(0, 0), (1, 1), (2, 2)], 4: [(0, 0), (2, 0), (0, 2), (2, 2)],
            5: [(0, 0), (2, 0), (1, 1), (0, 2), (2, 2)], 6: [(0, 0), (0, 1), (0, 2), (2, 0), (2, 1), (2, 2)]}
    y0 = G + 1
    lot.fill(x0, y0, z0, x0 + 2, y0 + 2, z0 + 2, "red_concrete")
    n, e, u = faces
    for (a, b) in pips[n]:
        lot.put(x0 + 2 - a, y0 + 2 - b, z0, "white_concrete")
    for (a, b) in pips[e]:
        lot.put(x0 + 2, y0 + 2 - b, z0 + a, "white_concrete")
    for (a, b) in pips[u]:
        lot.put(x0 + a, y0 + 2, z0 + b, "white_concrete")
    for (a, b) in pips[7 - n]:
        lot.put(x0 + a, y0 + 2 - b, z0 + 2, "white_concrete")
    for (a, b) in pips[7 - e]:
        lot.put(x0, y0 + 2 - b, z0 + 2 - a, "white_concrete")


def plaza(lot):
    pool(lot)
    # the walk from the bridge to the portico: a red carpet between gold-capped posts
    for z in range(14, 19):
        for x in range(36, 45):
            lot.put(x, G, z, "polished_diorite" if x in (36, 44) else "smooth_quartz")
        for x in (39, 40, 41):
            lot.put(x, G + 1, z, "red_carpet")
    for z in (14, 15):
        for x in (37, 43):
            lot.casino(x, G + 1, z, "casino_rope", facing="north")
    # palms along the pool's back edge and the plaza's sides, dice by the walk
    for x in (8, 15, 22, 29, 51, 58, 65, 72):
        palm(lot, x, 15, 6 + (x * 7) % 3)
    for z in (22, 30, 38, 46, 54, 62):
        palm(lot, 3, z, 6 + z % 3)
        palm(lot, SX - 4, z, 7 - z % 2)
    die(lot, 30, 16, (5, 3, 1))
    die(lot, 47, 16, (6, 2, 4))
    for (x, z) in ((10, 19), (24, 19), (56, 19), (70, 19), (10, 66), (70, 66)):
        lamp(lot, x, G + 1, z, 4)


def pylon(lot):
    """The pylon sign at the north-east corner: a black shaft four blocks wide, «ФОРТУНА» down its face in letters two blocks tall
    between columns of running lights, a gold crown with a star of light on top."""
    x0, x1, z0, z1 = 72, 75, 2, 5
    top = G + 33
    lot.fill(x0 - 1, G + 1, z0 - 1, x1 + 1, G + 3, z1 + 1, "polished_blackstone_bricks")
    lot.fill(x0 - 1, G + 4, z0 - 1, x1 + 1, G + 4, z1 + 1, GOLD)
    lot.fill(x0, G + 5, z0, x1, top, z1, DARK)
    lot.fill(x0, top + 1, z0, x1, top + 1, z1, GOLD)
    lot.fill(x0 + 1, top + 2, z0 + 1, x1 - 1, top + 2, z1 - 1, GOLD)
    lot.put(x0 + 1, top + 3, z0 + 1, "end_rod", facing="up")
    lot.put(x1 - 1, top + 3, z1 - 1, "end_rod", facing="up")
    lot.put(x0 + 1, top + 3, z1 - 1, "sea_lantern")
    lot.put(x1 - 1, top + 3, z0 + 1, "sea_lantern")
    # the face to the north: letters down the middle (to the viewer's right is -x), lights at the edges
    y = top - 1
    for ch in "ФОРТУНА":
        word(lot, ch, x1 - 1, y, z0 - 1, "north", (-1, 0))
        y -= 3
    for yy in range(G + 5, top + 1):
        lot.casino(x0, yy, z0 - 1, "casino_marquee", facing="north")
        lot.casino(x1, yy, z0 - 1, "casino_marquee", facing="north")
    for yy in range(G + 5, top + 1, 2):
        for z in range(z0, z1 + 1):
            lot.casino(x0 - 1, yy, z, "casino_marquee", facing="west")
            lot.casino(x1 + 1, yy, z, "casino_marquee", facing="east")
    # under the letters the jackpot board
    for i, x in enumerate((x1, x1 - 1, x1 - 2)):
        lot.casino(x, G + 6, z0 - 1, "casino_jackpot", facing="north", part=i)


# ---------------------------------------------------------------- the rotunda

def rotunda(lot):
    # the platform: three steps up all round, the colonnade's floor
    for x, z in ring(0, COLONNADE_R + 2.5):
        lot.put(x, G, z, DARK)
        lot.put(x, F, z, "polished_diorite" if polar(x, z)[0] >= WALL_R + 1 else QUARTZ)
    # the wall: two blocks thick, quartz outside, damask and wood wainscot inside, tall windows between the columns
    for x, z in ring(WALL_R - 1, WALL_R + 1):
        r, a = polar(x, z)
        outer = r >= WALL_R
        window = (a % 15) < 5 and not (a < 12 or a > 348)
        for y in range(F + 1, CEIL + 1):
            if outer:
                name = GOLD if y in (F + 1, CEIL) else ("yellow_stained_glass" if window and F + 3 <= y <= CEIL - 2 else QUARTZ)
            else:
                name = "mobtalk:casino_wainscot" if y <= F + 2 else ("yellow_stained_glass" if window and F + 3 <= y <= CEIL - 2 else
                                                                     "mobtalk:casino_wallpaper")
            lot.put(x, y, z, name)
    # the colonnade: fluted columns on a ring, the lit cornice over them
    for k in range(32):
        a = math.radians(k * 360 / 32)
        x, z = round(CX + COLONNADE_R * math.sin(a)), round(CZ - COLONNADE_R * math.cos(a))
        if abs(x - CX) <= 8 and z < CZ:
            continue  # the portico stands there
        for y in range(F + 1, CEIL):
            lot.casino(x, y, z, "casino_column")
    for x, z in ring(DRUM_R - 0.5, COLONNADE_R + 1.5):
        lot.put(x, CEIL, z, QUARTZ if polar(x, z)[0] >= WALL_R + 1 else "mobtalk:casino_coffer")
        lot.put(x, ROOF, z, QUARTZ)
    for x, z in ring(COLONNADE_R + 0.5, COLONNADE_R + 1.5):
        lot.put(x, CEIL, z, GOLD)
        lot.put(x, ROOF + 1, z, "quartz_bricks")
    # running lights round the cornice and the parapet, a lamp on every fourth post
    for x, z in ring(COLONNADE_R + 1.5, COLONNADE_R + 2.5):
        lot.casino(x, CEIL, z, "casino_marquee", facing=outward(x, z))
        lot.casino(x, ROOF + 1, z, "casino_marquee", facing=outward(x, z))
    # the drum: a ring of windows on gold piers, a cornice of lights
    for x, z in ring(DRUM_R - 0.5, DRUM_R + 0.5):
        _, a = polar(x, z)
        for y in range(ROOF + 1, DOME_Y):
            lot.put(x, y, z, GOLD if (a % 30) < 6 or y in (ROOF + 1, DOME_Y - 1) else "orange_stained_glass")
    for x, z in ring(DRUM_R + 0.5, DRUM_R + 1.5):
        lot.casino(x, DOME_Y - 1, z, "casino_marquee", facing=outward(x, z))
        lot.put(x, ROOF + 1, z, QUARTZ)
    dome(lot)


def dome(lot):
    """A glass dome on twelve gold ribs and two gold rings, lights where they cross; a gold lantern and a spire on top."""
    def inside(x, y, z):
        r, _ = polar(x, z)
        return (r / (DRUM_R + 0.5)) ** 2 + ((y - DOME_Y + 0.5) / DOME_H) ** 2 <= 1.0
    for y in range(DOME_Y, DOME_Y + DOME_H + 1):
        for x in range(CX - DRUM_R - 1, CX + DRUM_R + 2):
            for z in range(CZ - DRUM_R - 1, CZ + DRUM_R + 2):
                if not inside(x, y, z):
                    continue
                if all(inside(x + dx, y + dy, z + dz) for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, 0, 1), (0, 0, -1))):
                    continue
                r, a = polar(x, z)
                rib = r > 3 and min(a % 30, 30 - a % 30) * math.radians(1) * r < 0.5
                band = y == DOME_Y + 5 and r > 3
                name = "sea_lantern" if rib and band else GOLD if rib or band else "yellow_stained_glass"
                lot.put(x, y, z, name)
    top = DOME_Y + DOME_H + 1
    lot.fill(CX - 1, top, CZ - 1, CX + 1, top + 3, CZ + 1, GOLD)
    for (dx, dz) in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        for y in (top + 1, top + 2):
            lot.put(CX + dx, y, CZ + dz, "glass")
    lot.put(CX, top + 1, CZ, "sea_lantern")
    lot.put(CX, top + 2, CZ, "sea_lantern")
    lot.put(CX, top + 4, CZ, GOLD)
    for y in (top + 5, top + 6):
        lot.put(CX, y, CZ, "end_rod", facing="up")


def portico(lot):
    """The portico: eight columns in front, a black frieze with «★КАЗИНО★» between running lights, a stepped pediment, doors."""
    x0, x1 = 32, 48
    zf = CZ - COLONNADE_R - 3  # the front row of columns
    for x in range(x0, x1 + 1):
        for z in range(zf, CZ - WALL_R + 1):
            lot.put(x, F, z, QUARTZ if (x + z) % 2 else "polished_diorite")
        for z in range(zf - 1, zf):
            lot.put(x, G + 1, z, "quartz_stairs", facing="south", half="bottom", shape="straight", waterlogged=False)
    for x in (39, 40, 41):
        for z in range(zf, CZ - WALL_R + 3):
            if lot.free(x, F + 1, z):
                lot.put(x, F + 1, z, "red_carpet")
    for x in (x0, 34, 36, 38, 42, 44, 46, x1):
        for y in range(F + 1, CEIL - 3):
            lot.casino(x, y, zf, "casino_column")
    # the frieze and the sign
    lot.fill(x0, CEIL - 3, zf, x1, CEIL + 1, CZ - WALL_R, QUARTZ)
    lot.fill(x0, CEIL - 3, zf, x1, CEIL, zf, DARK)
    lot.fill(x0, CEIL + 1, zf, x1, CEIL + 1, zf, GOLD)
    for x in range(x0, x1 + 1):
        lot.casino(x, CEIL - 3, zf - 1, "casino_marquee", facing="north")
        lot.casino(x, CEIL, zf - 1, "casino_marquee", facing="north")
    for y in (CEIL - 2, CEIL - 1):
        lot.casino(x0, y, zf - 1, "casino_marquee", facing="north")
        lot.casino(x1, y, zf - 1, "casino_marquee", facing="north")
    word(lot, "★КАЗИНО★", 47, CEIL - 1, zf - 1, "north", (-1, 0))
    # the pediment: steps of quartz edged in gold, a lamp at the peak
    for i in range(5):
        lot.fill(x0 + 1 + i * 2, CEIL + 2 + i, zf, x1 - 1 - i * 2, CEIL + 2 + i, zf + 3, QUARTZ)
        lot.put(x0 + 1 + i * 2, CEIL + 2 + i, zf, GOLD)
        lot.put(x1 - 1 - i * 2, CEIL + 2 + i, zf, GOLD)
    lot.put(40, CEIL + 7, zf, GOLD)
    lot.put(40, CEIL + 8, zf, "end_rod", facing="up")
    # the ceiling of the porch: coffers; the doors: an opening five wide in the wall with gold jambs
    lot.fill(x0 + 1, CEIL - 4, zf + 1, x1 - 1, CEIL - 4, CZ - WALL_R - 1, "mobtalk:casino_coffer")
    zw0, zw1 = CZ - WALL_R - 1, CZ - WALL_R + 1
    for z in range(zw0, zw1 + 2):
        for x in range(38, 43):
            for y in range(F + 1, F + 6):
                if (x, y, z) in lot.g and polar(x, z)[0] >= WALL_R - 1.5:
                    del lot.g[(x, y, z)]
    for z in range(zw0, zw1 + 1):
        for y in range(F + 1, F + 7):
            for x in (37, 43):
                if polar(x, z)[0] >= WALL_R - 1.5:
                    lot.put(x, y, z, GOLD)
        for x in range(37, 44):
            if polar(x, z)[0] >= WALL_R - 1.5:
                lot.put(x, F + 6, z, GOLD)


# ---------------------------------------------------------------- the hall

def hall(lot):
    stand = F + 1
    # the floor: marble round the bar, a red carpet with gold borders in the pits, purple under the machines, gold rings between
    for x, z in ring(0, WALL_R - 1):
        r, a = polar(x, z)
        lot.put(x, F, z, ("polished_blackstone" if (x + z) % 2 else "polished_diorite") if r < 5 else
                "red_wool" if r < DRUM_R - 0.5 else "purple_wool")  # under the furniture the carpet's colour shows
        if r < 5:
            continue
        carpet = "casino_carpet_red" if r < DRUM_R - 0.5 else "casino_carpet_purple"
        if abs(r - (DRUM_R - 0.5)) < 0.5:
            carpet = "yellow_carpet"
        if (a < 5 or a > 355) and r >= 5:
            carpet = "red_carpet"  # the way in from the doors
        lot.casino(x, stand, z, carpet) if carpet.startswith("casino_") else lot.put(x, stand, z, carpet)
    # the columns that carry the drum
    for k in range(12):
        a = math.radians(k * 30 + 15)
        x, z = round(CX + DRUM_R * math.sin(a)), round(CZ - DRUM_R * math.cos(a))
        for y in range(stand, CEIL):
            lot.casino(x, y, z, "casino_column")
    for x, z in ring(DRUM_R - 0.5, DRUM_R + 0.5):
        lot.put(x, CEIL, z, GOLD)
    bar(lot, stand)
    pits(lot, stand)
    machines(lot, stand)
    lights(lot)


def bar(lot, stand):
    """The round bar in the middle: a counter ring, shelves of bottles inside round a gold-and-glass column of light, stools."""
    for x, z in ring(0, 5):
        r, _ = polar(x, z)
        if 3.5 <= r < 4.5:
            lot.casino(x, stand, z, "casino_bar_counter", facing=outward(x, z))
        elif 2.3 <= r < 3.5:
            lot.casino(x, stand, z, "casino_bar_shelf", facing=inward(x, z))
            lot.casino(x, stand + 1, z, "casino_bar_shelf", facing=inward(x, z))
    for y in range(stand, stand + 9):
        lot.put(CX, y, CZ, GOLD if y % 3 == 0 else "sea_lantern")
        for (dx, dz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            lot.put(CX + dx, y, CZ + dz, "glass" if y % 3 else GOLD)
    for k in range(16):
        a = math.radians(k * 360 / 16 + 11)
        x, z = round(CX + 5.2 * math.sin(a)), round(CZ - 5.2 * math.cos(a))
        if lot.name(x, stand, z) in ("mobtalk:casino_carpet_red", "minecraft:yellow_carpet", "minecraft:red_carpet", "minecraft:air"):
            lot.casino(x, stand, z, "casino_bar_stool", facing=inward(x, z))
    lot.casino(CX + 4, stand + 1, CZ, "casino_chips", facing="east", chips=3)


def twenty_one(lot, x, y, z, facing):
    """facing north or south: the dealer's side opposite, the players' chairs one cell beyond the table's far row."""
    d = -1 if facing == "north" else 1
    lot.casino(x, y, z, "casino_blackjack_table", facing=facing)
    for (dx, dz) in ((-1, 0), (1, 0), (-1, d), (0, d), (1, d)):
        lot.casino(x + dx, y, z + dz, "casino_part")
    for dx in (-1, 0, 1):
        lot.casino(x + dx, y, z + 2 * d, "casino_chair", facing="south" if d < 0 else "north")


def roulette(lot, x, y, z, facing):
    lot.casino(x, y, z, "casino_roulette_table", facing=facing)
    lot.casino(x - 1, y, z, "casino_part")
    lot.casino(x + 1, y, z, "casino_part")
    d = -1 if facing == "north" else 1
    for sx in (x - 1, x + 1):
        lot.casino(sx, y, z + d, "casino_bar_stool", facing="south" if d < 0 else "north")


def cards(lot, x, y, z, facing):
    """A card table along x (facing north or south), chairs on both long sides."""
    d = -1 if facing == "north" else 1
    lot.casino(x, y, z, "casino_poker_table", facing=facing)
    for (dx, dz) in ((-1, 0), (1, 0), (-1, d), (0, d), (1, d)):
        lot.casino(x + dx, y, z + dz, "casino_part")
    for dx in (-1, 0, 1):
        lot.casino(x + dx, y, z - d, "casino_chair", facing="north" if d < 0 else "south")
        lot.casino(x + dx, y, z + 2 * d, "casino_chair", facing="south" if d < 0 else "north")


def pits(lot, stand):
    """Between the bar and the columns: twenty-one to the west and east, roulette and cards to the north-west, north-east and south."""
    twenty_one(lot, CX - 8, stand, CZ - 2, "north")
    twenty_one(lot, CX - 8, stand, CZ + 3, "south")
    twenty_one(lot, CX + 8, stand, CZ - 2, "north")
    twenty_one(lot, CX + 8, stand, CZ + 3, "south")
    roulette(lot, CX - 5, stand, CZ - 7, "north")
    roulette(lot, CX + 5, stand, CZ - 7, "north")
    cards(lot, CX - 4, stand, CZ + 8, "south")
    cards(lot, CX + 4, stand, CZ + 8, "south")


def machines(lot, stand):
    """Slot machines all round the ring under the coffers, facing the hall, with stools; jackpot boards over them on the west,
    east and south walls; the cashiers' cage at the back."""
    cells = sorted(ring(WALL_R - 2.5, WALL_R - 1.5), key=lambda c: polar(*c)[1])
    for i, (x, z) in enumerate(cells):
        _, a = polar(x, z)
        if a < 14 or a > 346 or 165 < a < 195:
            continue  # the doors, the cashiers
        lot.casino(x, stand, z, "casino_slot_machine", facing=inward(x, z), half="lower")
        lot.casino(x, stand + 1, z, "casino_slot_machine", facing=inward(x, z), half="upper")
    for (x, z) in ring(WALL_R - 3.5, WALL_R - 2.5):
        _, a = polar(x, z)
        if not (a < 14 or a > 346 or 165 < a < 195) and (x + z) % 2 == 0:
            lot.casino(x, stand, z, "casino_bar_stool", facing=outward(x, z))
    # banks of machines back to back across the ring, on the diagonals
    for k in (45, 135, 225, 315):
        a = math.radians(k)
        for rr in (14.5, 15.5):
            x, z = round(CX + rr * math.sin(a)), round(CZ - rr * math.cos(a))
            for (dx, dz) in ((0, 0), (1, 0), (0, 1), (1, 1)):
                face = "south" if dz else "north"
                lot.casino(x + dx, stand, z + dz, "casino_slot_machine", facing=face, half="lower")
                lot.casino(x + dx, stand + 1, z + dz, "casino_slot_machine", facing=face, half="upper")
    # jackpot boards high on the wall over the machines, west, east and south-west / south-east
    for (x, z0, facing, step) in ((CX - WALL_R + 2, CZ + 1, "east", -1), (CX + WALL_R - 2, CZ - 1, "west", 1)):
        for i in range(3):
            lot.casino(x, stand + 4, z0 + step * i, "casino_jackpot", facing=facing, part=i)
            lot.casino(x, stand + 6, z0 + step * i, "casino_jackpot", facing=facing, part=i)
    # the cashiers: a cage of windows at the back, facing the hall
    zc = CZ + WALL_R - 3
    for x in range(CX - 3, CX + 4):
        lot.casino(x, stand, zc, "casino_cashier", facing="north")
        if x in (CX - 3, CX + 3):
            lot.put(x, stand + 1, zc, GOLD)
            lot.put(x, stand + 2, zc, GOLD)
        else:
            lot.put(x, stand + 2, zc, "iron_bars", north=False, south=False, east=True, west=True, waterlogged=False)
    for x in range(CX - 3, CX + 4):
        lot.put(x, stand + 3, zc, GOLD)
    for i, x in enumerate((CX + 1, CX, CX - 1)):
        lot.casino(x, stand + 4, zc, "casino_jackpot", facing="north", part=i)


def lights(lot):
    """The great chandelier under the dome on a gold chain, four more round it, small ones in the ring under the coffers."""
    top = DOME_Y + DOME_H
    for y in range(DOME_Y + 1, top):
        lot.put(CX, y, CZ, "chain", axis="y", waterlogged=False)
    for y in (DOME_Y, DOME_Y - 2, DOME_Y - 4):
        lot.casino(CX, y, CZ, "casino_grand_chandelier", facing="north")
        if y > DOME_Y - 4:
            lot.put(CX, y - 1, CZ, "chain", axis="y", waterlogged=False)
    for (dx, dz) in ((6, 0), (-6, 0), (0, 6), (0, -6), (4, 4), (-4, 4), (4, -4), (-4, -4)):
        y = CEIL + 2
        while y < SY and lot.free(CX + dx, y, CZ + dz):  # up to the dome's glass
            lot.put(CX + dx, y, CZ + dz, "chain", axis="y", waterlogged=False)
            y += 1
        lot.casino(CX + dx, CEIL + 1, CZ + dz, "casino_grand_chandelier", facing="north")
    for k in range(24):
        a = math.radians(k * 15 + 7.5)
        x, z = round(CX + 16 * math.sin(a)), round(CZ - 16 * math.cos(a))
        lot.casino(x, CEIL - 1, z, "casino_chandelier", facing="north")


# ---------------------------------------------------------------- the garden

def garden(lot):
    """Behind the rotunda: a lawn in a half ring, palms round it, lamps along its edge."""
    for x, z in ring(COLONNADE_R + 2.5, GARDEN_R):
        if z > CZ - 4:
            lot.put(x, G, z, "grass_block", snowy=False)
    k = 0
    for x, z in sorted(ring(COLONNADE_R + 4.5, COLONNADE_R + 5.5), key=lambda c: polar(*c)[1]):
        _, a = polar(x, z)
        if 100 <= a <= 260:
            k += 1
            if k % 4 == 0:
                palm(lot, x, z, 5 + k % 3)
    for x, z in ring(GARDEN_R - 1.5, GARDEN_R - 0.5):
        _, a = polar(x, z)
        if 95 <= a <= 265 and (x + z) % 7 == 0:
            lamp(lot, x, G + 1, z, 2)


def villagers():
    """Four bouncers by the doors, croupiers at the tables, a few guests."""
    out = []
    stand = F + 1
    people = [(38, CZ - WALL_R - 3, "mobtalk:casino_bouncer", 180), (42, CZ - WALL_R - 3, "mobtalk:casino_bouncer", 180),
              (38, CZ - WALL_R + 2, "mobtalk:casino_bouncer", 0), (42, CZ - WALL_R + 2, "mobtalk:casino_bouncer", 0),
              (CX - 8, CZ, "minecraft:nitwit", 0), (CX + 8, CZ, "minecraft:nitwit", 0), (CX, CZ + 5, "minecraft:librarian", 0),
              (CX - 10, CZ - 10, "minecraft:cleric", 0), (CX + 10, CZ + 10, "minecraft:farmer", 0), (CX - 3, CZ - 5, "minecraft:mason", 0)]
    for (x, z, prof, yaw) in people:
        v = N.c(id=N.Tag(N.STRING, "minecraft:villager"), PersistenceRequired=N.Tag(N.BYTE, 1), Xp=N.Tag(N.INT, 10),
                VillagerData=N.c(profession=N.Tag(N.STRING, prof), level=N.Tag(N.INT, 2), type=N.Tag(N.STRING, "minecraft:plains")),
                Rotation=N.Tag(N.LIST, [N.Tag(N.FLOAT, float(yaw)), N.Tag(N.FLOAT, 0.0)], N.FLOAT),
                Pos=N.Tag(N.LIST, [N.Tag(N.DOUBLE, x + 0.5), N.Tag(N.DOUBLE, float(stand)), N.Tag(N.DOUBLE, z + 0.5)], N.DOUBLE))
        out.append(N.c(blockPos=N.Tag(N.LIST, [N.Tag(N.INT, x), N.Tag(N.INT, stand), N.Tag(N.INT, z)], N.INT),
                       pos=N.Tag(N.LIST, [N.Tag(N.DOUBLE, x + 0.5), N.Tag(N.DOUBLE, float(stand)), N.Tag(N.DOUBLE, z + 0.5)], N.DOUBLE), nbt=v))
    return out


def build():
    lot = Lot()
    ground(lot)
    plaza(lot)
    garden(lot)
    rotunda(lot)
    portico(lot)
    hall(lot)
    pylon(lot)
    return lot


def write(lot):
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
        blocks.append(N.Tag(N.COMPOUND, {"pos": N.Tag(N.LIST, [N.Tag(N.INT, v) for v in pos], N.INT), "state": N.Tag(N.INT, index[key])}))
    root = N.c(DataVersion=N.Tag(N.INT, DATA_VERSION), size=N.Tag(N.LIST, [N.Tag(N.INT, v) for v in (SX, SY, SZ)], N.INT),
               palette=N.Tag(N.LIST, palette, N.COMPOUND), blocks=N.Tag(N.LIST, blocks, N.COMPOUND),
               entities=N.Tag(N.LIST, villagers(), N.COMPOUND))
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "resort.nbt"), "wb") as f:
        f.write(N.dump("", root))


def plan(out, lot):
    """Top-down: the highest block in each column, coloured by kind."""
    from PIL import Image
    cell = 8
    img = Image.new("RGB", (SX * cell, SZ * cell), (30, 30, 34))
    colours = {"water": (60, 120, 220), "smooth_quartz": (235, 230, 220), "gold_block": (240, 200, 60), "yellow_stained_glass": (250, 230, 120),
               "orange_stained_glass": (240, 150, 60), "sea_lantern": (200, 240, 240), "polished_blackstone": (40, 36, 44)}
    for x in range(SX):
        for z in range(SZ):
            for y in range(SY - 1, -1, -1):
                n = lot.name(x, y, z).split(":")[-1]
                if n != "air":
                    c = colours.get(n, (180, 40, 50) if n.startswith("casino_") else (130, 125, 115))
                    k = 0.55 + 0.45 * y / SY
                    img.paste(tuple(int(v * k) for v in c), (x * cell, z * cell, x * cell + cell - 1, z * cell + cell - 1))
                    break
    img.save(out)


def main():
    lot = build()
    write(lot)
    if len(sys.argv) > 2 and sys.argv[1] == "plan":
        plan(sys.argv[2], lot)
    print("casino resort:", len(lot.g), "blocks")


if __name__ == "__main__":
    main()
