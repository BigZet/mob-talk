"""Mutations that multiply real labelled cubes: quarter turns and mirrors, palette swaps within a family (wood species, stone
kinds), partial destruction (a ruin of the same place), random clutter (3–10 % of cells), and a new terrain from a background
cube around the built part. Every mutation keeps the creature's feet and head cells as they were.

A cube here is a pair of uint8 arrays [16,16,16] (x, y, z): material ids and block-family ids. Palette swaps work on block names
(the family of a swapped block does not change, so for the classifier's input they are the identity; they matter for renders).
"""
import numpy as np
from dataset import MAT, FAM, MATERIALS, FAMILIES

FX, FY, FZ = 8, 7, 8
_NATURAL_FAM = np.zeros(32, dtype=bool)
for _f in ('NATURAL_STONE', 'SOIL', 'SAND_GRAVEL', 'ORE', 'ICE_SNOW', 'WATER_PLANT', 'LOG'): _NATURAL_FAM[FAM[_f]] = True
_NATURAL_MAT = np.zeros(16, dtype=bool)
for _m in ('AIR', 'SOLID', 'WATER', 'LEAVES', 'PLANT', 'LAVA', 'UNBREAKABLE'): _NATURAL_MAT[MAT[_m]] = True
_BUILT_FAM = np.zeros(32, dtype=bool)
for _f in ('PLANKS', 'MASONRY', 'GLASS', 'WOOL', 'BED', 'STORAGE', 'WORKSTATION', 'LIGHT', 'RAIL', 'CROP', 'REDSTONE', 'DECORATION', 'METAL',
           'COLORED', 'SANDSTONE', 'COBWEB_SPAWNER'):
    _BUILT_FAM[FAM[_f]] = True
_BUILT_MAT = np.zeros(16, dtype=bool)
for _m in ('GLASS', 'DOOR_OPEN', 'DOOR_CLOSED', 'FENCE', 'CLIMBABLE', 'FARMLAND', 'MACHINE', 'HAZARD'): _BUILT_MAT[MAT[_m]] = True

WOODS = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry']
STONES = [['stone_bricks', 'deepslate_bricks', 'polished_blackstone_bricks', 'tuff_bricks', 'mud_bricks'],
          ['cobblestone', 'cobbled_deepslate', 'blackstone'], ['stone', 'deepslate', 'andesite', 'tuff', 'diorite', 'granite']]


def keep_creature(out_mat, out_fam, mat, fam):
    out_mat[FX, FY:FY + 2, FZ] = mat[FX, FY:FY + 2, FZ]; out_fam[FX, FY:FY + 2, FZ] = fam[FX, FY:FY + 2, FZ]
    out_mat[FX, FY - 1, FZ] = mat[FX, FY - 1, FZ]; out_fam[FX, FY - 1, FZ] = fam[FX, FY - 1, FZ]


def _transform(a, k, flip_x, flip_z):
    a = np.rot90(a, k, axes=(0, 2))
    if flip_x: a = a[::-1, :, :]
    if flip_z: a = a[:, :, ::-1]
    return a


def turn(mat, fam, k, flip_x=False, flip_z=False):
    """Quarter turns around the vertical axis and mirrors; the creature's cell (8, 7, 8) of the even-sized cube is rolled back into
    place afterwards (the cell that falls off one edge comes back on the other: one column of the 16)."""
    marker = np.zeros((16, 1, 16), dtype=bool); marker[FX, 0, FZ] = True
    mx, _, mz = np.argwhere(_transform(marker, k, flip_x, flip_z))[0]
    return [np.ascontiguousarray(np.roll(_transform(a, k, flip_x, flip_z), (FX - mx, FZ - mz), axis=(0, 2))) for a in (mat, fam)]


def palette_swap(names, rng):
    """New block names with one wood species and one stone kind replaced consistently."""
    wood_from, wood_to = rng.choice(WOODS, 2, replace=False)
    group = STONES[rng.integers(len(STONES))]
    stone_from, stone_to = rng.choice(group, 2, replace=False)
    out = []
    for n in names:
        b = n.split(':', 1)[-1]
        if b.startswith(wood_from + '_'): b = wood_to + b[len(wood_from):]
        elif b == stone_from or b.startswith(stone_from + '_'): b = stone_to + b[len(stone_from):]
        out.append('minecraft:' + b)
    return out


def ruin(mat, fam, rng, fraction=None):
    """Knocks out a share of the built cells (not terrain) inside a random sub-box: a half-collapsed version of the place."""
    m, f = mat.copy(), fam.copy()
    built = _BUILT_FAM[fam] | _BUILT_MAT[mat]
    lo = rng.integers(0, 8, 3); hi = lo + rng.integers(6, 17, 3)
    box = np.zeros_like(built); box[lo[0]:hi[0], lo[1]:hi[1], lo[2]:hi[2]] = True
    fraction = fraction if fraction is not None else rng.uniform(0.1, 0.35)
    hit = built & box & (rng.random(mat.shape) < fraction)
    m[hit] = MAT['AIR']; f[hit] = FAM['OTHER']
    keep_creature(m, f, mat, fam)
    return m, f


def clutter(mat, fam, rng, fraction=None):
    """Random cells become random materials and families: torches, dropped blocks, a stray plant."""
    m, f = mat.copy(), fam.copy()
    fraction = fraction if fraction is not None else rng.uniform(0.03, 0.10)
    hit = rng.random(mat.shape) < fraction
    m[hit] = rng.integers(0, len(MATERIALS), hit.sum()); f[hit] = rng.integers(0, len(FAMILIES), hit.sum())
    keep_creature(m, f, mat, fam)
    return m, f


def reterrain(mat, fam, bg_mat, bg_fam):
    """The built part of a cube set into the terrain of a background cube: built cells and their 1-cell neighbourhood (rooms, doorways)
    stay, everything else comes from the background."""
    built = _BUILT_FAM[fam] | _BUILT_MAT[mat]
    if built.sum() < 8: return mat.copy(), fam.copy()
    near = built.copy()
    for axis in range(3):
        for shift in (1, -1): near |= np.roll(built, shift, axis=axis)
    # Rooms: passable cells enclosed between built cells on both sides along x or z stay too.
    m, f = bg_mat.copy(), bg_fam.copy()
    m[near] = mat[near]; f[near] = fam[near]
    keep_creature(m, f, mat, fam)
    return m, f


def variants(mat, fam, count, rng, backgrounds=None):
    """count mutated copies of one cube: each a random turn/mirror plus one or two of the heavier mutations."""
    out = []
    for i in range(count):
        m, f = turn(mat, fam, int(rng.integers(4)), bool(rng.integers(2)), bool(rng.integers(2)))
        kinds = rng.choice(['none', 'ruin', 'clutter', 'reterrain'], size=2, replace=False, p=[0.25, 0.25, 0.25, 0.25])
        for kind in kinds:
            if kind == 'ruin': m, f = ruin(m, f, rng)
            elif kind == 'clutter': m, f = clutter(m, f, rng)
            elif kind == 'reterrain' and backgrounds is not None and len(backgrounds[0]):
                j = int(rng.integers(len(backgrounds[0])))
                m, f = reterrain(m, f, backgrounds[0][j], backgrounds[1][j])
        out.append((m, f))
    return out
