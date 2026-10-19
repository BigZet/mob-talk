"""Weak labels for player-built places by rule. Input: a 16³ cube (x, y, z) of material ids, block-family ids and block names,
the creature's feet at (8, 7, 8). Each rule is narrow and sure of itself; a cube where no rule is sure is 'unknown' and stays out
of training. Every rule that fired is reported, the first in PRIORITY wins.
"""
from collections import deque
import numpy as np
from dataset import MAT, FAM

PASSABLE = np.zeros(16, dtype=bool)
for _m in ('AIR', 'WATER', 'DOOR_OPEN', 'CLIMBABLE', 'PLANT'): PASSABLE[MAT[_m]] = True
ARTIFICIAL = np.zeros(32, dtype=bool)
for _f in ('PLANKS', 'MASONRY', 'GLASS', 'WOOL', 'BED', 'STORAGE', 'WORKSTATION', 'LIGHT', 'RAIL', 'METAL', 'DECORATION', 'REDSTONE', 'COLORED'):
    ARTIFICIAL[FAM[_f]] = True
NATURAL = np.zeros(32, dtype=bool)
for _f in ('NATURAL_STONE', 'SOIL', 'SAND_GRAVEL', 'ORE'): NATURAL[FAM[_f]] = True

PRIORITY = ['storage', 'cage', 'trap', 'farm', 'pier', 'bridge', 'mine', 'basement', 'house', 'pen', 'road', 'tower', 'wall']
FX, FY, FZ = 8, 7, 8


def flood(passable):
    """Cells reachable from the feet through passable cells; whether the flood touched a side or the top of the cube."""
    seen = np.zeros_like(passable)
    if not passable[FX, FY, FZ]: return seen, False
    q = deque([(FX, FY, FZ)]); seen[FX, FY, FZ] = True; edge = False
    while q:
        x, y, z = q.popleft()
        if x in (0, 15) or z in (0, 15) or y == 15: edge = True
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            a, b, c = x + dx, y + dy, z + dz
            if 0 <= a < 16 and 0 <= b < 16 and 0 <= c < 16 and passable[a, b, c] and not seen[a, b, c]:
                seen[a, b, c] = True; q.append((a, b, c))
    return seen, edge


def features(mat, fam, names):
    """Counts and shape facts the rules read; also stored in the dataset for later analysis."""
    p = PASSABLE[mat]
    reach, edge = flood(p)
    solid = ~p
    art = ARTIFICIAL[fam] & solid | (fam == FAM['RAIL']) | (fam == FAM['LIGHT'])
    names_arr = np.asarray(names, dtype=object)
    def count_name(*parts):
        return int(sum(1 for n in names_arr.ravel() if any(part in n for part in parts)))
    column = p[FX, FY + 2:, FZ]
    roof = None if column.all() else int(np.argmin(column)) + 2
    walls = 0
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        for d in range(1, 8):
            x, z = FX + dx * d, FZ + dz * d
            if not (p[x, FY, z] and p[x, FY + 1, z]): walls += 1; break
    def run(dx, dz):
        n = 0
        for d in range(1, 8):
            x, z = FX + dx * d, FZ + dz * d
            if not (0 <= x < 16 and 0 <= z < 16) or not (p[x, FY, z] and p[x, FY + 1, z]): break
            n += 1
        return n
    span_x, span_z = run(1, 0) + run(-1, 0) + 1, run(0, 1) + run(0, -1) + 1
    top = solid[:, 11:, :]
    top_natural = (NATURAL[fam[:, 11:, :]] & top).sum() / max(1, top.sum())
    below = mat[FX, FY - 1, FZ]
    under_gap = int(p[FX, 1:FY - 1, FZ][::-1].cumprod().sum())  # passable cells right under the cell below the feet
    return dict(
        volume=int(reach.sum()), open=bool(edge), roof=roof, walls=walls, sky=roof is None, span_x=span_x, span_z=span_z,
        underground=bool(roof is not None and top.mean() > 0.6 and top_natural > 0.6),
        beds=int((fam == FAM['BED']).sum()), doors=int(((mat == MAT['DOOR_OPEN']) | (mat == MAT['DOOR_CLOSED'])).sum()),
        storage=int((fam == FAM['STORAGE']).sum()), farmland=int((mat == MAT['FARMLAND']).sum()), crops=int((fam == FAM['CROP']).sum()),
        water=int((mat == MAT['WATER']).sum()), lava=int((mat == MAT['LAVA']).sum()), fence=int((mat == MAT['FENCE']).sum()),
        rails=int((fam == FAM['RAIL']).sum()), lights=int((fam == FAM['LIGHT']).sum()), machines=int((mat == MAT['MACHINE']).sum()),
        triggers=count_name('pressure_plate', 'tripwire', 'observer', 'target'), bars=count_name('iron_bars'), glass=int((mat == MAT['GLASS']).sum()),
        artificial=int(art.sum()), planks=int((fam == FAM['PLANKS']).sum()), path=count_name('dirt_path'),
        spawner=count_name('spawner'), cobweb=count_name('cobweb'),
        below_artificial=bool(ARTIFICIAL[fam[FX, FY - 1, FZ]] and not PASSABLE[below]), below_planks=bool(fam[FX, FY - 1, FZ] == FAM['PLANKS']),
        under_gap=under_gap,
        farm_near=bool((mat[FX - 2:FX + 3, FY - 1, FZ - 2:FZ + 3] == MAT['FARMLAND']).any()),
        furniture=int((fam == FAM['WORKSTATION']).sum()),
        water_near=bool((mat[FX - 3:FX + 4, FY - 2:FY + 1, FZ - 3:FZ + 4] == MAT['WATER']).any()),
        cage_sides=_cage_sides(mat, names_arr))


def _cage_sides(mat, names):
    sides = 0
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        for d in range(1, 3):
            x, z = FX + dx * d, FZ + dz * d
            if mat[x, FY, z] == MAT['GLASS'] or 'iron_bars' in names[x, FY, z]: sides += 1; break
    return sides


def _fence_ring(mat):
    sides = 0
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        for d in range(1, 8):
            x, z = FX + dx * d, FZ + dz * d
            if mat[x, FY, z] == MAT['FENCE'] or mat[x, FY - 1, z] == MAT['FENCE'] or mat[x, FY, z] in (MAT['DOOR_CLOSED'], MAT['DOOR_OPEN']):
                sides += 1; break
    return sides


def _line(art2d):
    """Longest straight run of built cells in the xz projection along x or z, and the thickness across it."""
    best = 0
    for axis in (0, 1):
        a = art2d if axis == 0 else art2d.T
        for row in a:
            n = m = 0
            for v in row:
                n = n + 1 if v else 0; m = max(m, n)
            best = max(best, m)
    return best


def label(mat, fam, names, tower=False):
    """(winning key or 'unknown', every rule that fired, the features)."""
    f = features(mat, fam, names)
    fired = []
    if f['storage'] >= 4: fired.append('storage')
    if not f['open'] and f['volume'] <= 30 and f['cage_sides'] >= 3 and f['bars'] + f['glass'] >= 6: fired.append('cage')
    if f['machines'] >= 2 and (f['triggers'] >= 1 or f['lava'] >= 1): fired.append('trap')
    if (f['farmland'] >= 6 and f['water'] >= 1 or f['crops'] >= 6 and f['farmland'] >= 3) and f['farm_near']: fired.append('farm')
    if f['water'] >= 30 and f['below_planks'] and f['water_near'] and f['sky']: fired.append('pier')
    if f['below_artificial'] and f['under_gap'] >= 3 and f['sky'] and min(f['span_x'], f['span_z']) <= 5: fired.append('bridge')
    if f['underground'] and not f['sky'] and max(f['span_x'], f['span_z']) >= 6 and min(f['span_x'], f['span_z']) <= 3 and (f['rails'] >= 1 or f['lights'] >= 1):
        fired.append('mine')
    if f['underground'] and not f['open'] and f['volume'] <= 250 and f['artificial'] >= 20 and f['beds'] + f['storage'] + f['furniture'] >= 1             and f['spawner'] == 0 and f['cobweb'] == 0:
        fired.append('basement')
    if not f['underground'] and f['roof'] is not None and f['roof'] <= 7 and f['walls'] >= 3 and f['artificial'] >= 15             and (f['beds'] >= 1 or f['doors'] >= 1 and f['planks'] >= 8) and f['spawner'] == 0:
        fired.append('house')
    if f['fence'] >= 12 and f['sky'] and _fence_ring(mat) >= 3: fired.append('pen')
    if f['sky'] and (f['rails'] >= 4 or f['path'] >= 8): fired.append('road')
    if tower and not f['underground']: fired.append('tower')
    built = (ARTIFICIAL[fam] & ~PASSABLE[mat])[:, FY:FY + 3, :].any(axis=1) | (mat[:, FY:FY + 2, :] == MAT['FENCE']).any(axis=1)
    if f['sky'] and _line(built) >= 10 and built.sum() <= 3 * _line(built): fired.append('wall')
    win = next((k for k in PRIORITY if k in fired), 'unknown')
    return win, fired, f


# What a vanilla piece is in the rules' terms: rules judged on harvested pieces whose truth comes from the registry.
MEANING = {'village_house': 'house', 'village_church': 'house', 'village_smithy': 'house', 'village_workshop': 'house', 'igloo': 'house',
           'swamp_hut': 'house', 'village_farm': 'farm', 'village_pen': 'pen', 'village_street': 'road', 'mineshaft': 'mine'}


def main():
    """Precision and coverage of the rules on harvested vanilla pieces, where the registry knows the truth."""
    import collections, os, sys
    from dataset import DATA, BlockTable, bases, read_v2, families_for
    import labels
    table = BlockTable()
    fired_n, right, cover, total = collections.Counter(), collections.Counter(), collections.Counter(), collections.Counter()
    pairs = collections.Counter()
    for base in bases(os.path.join(DATA, sys.argv[1] if len(sys.argv) > 1 else 'harvest')):
        cubes, blocks, meta = read_v2(base)
        fams = families_for(blocks, meta, table)
        for i, m in enumerate(meta):
            truth = labels.fine_of(m)
            if truth is None: continue
            names = np.array(m['palette'], dtype=object)[blocks[i]]
            win, fired, _ = label(cubes[i], fams[i], names)
            want = MEANING.get(truth)
            total[truth] += 1
            if win != 'unknown':
                fired_n[win] += 1
                pairs[(win, truth)] += 1
                if want == win: right[win] += 1
            if want and win == want: cover[truth] += 1
    print('rule: fired / matches the registry meaning (precision)')
    for k in PRIORITY:
        if fired_n[k]: print(f'  {k:9s} {fired_n[k]:5d}  {right[k] / fired_n[k]:.0%}   top truths: {[(t, n) for (w, t), n in pairs.most_common() if w == k][:4]}')
    print('coverage of pieces with a meaning:')
    for t, w in MEANING.items():
        if total[t]: print(f'  {t:17s} {total[t]:5d}  rule "{w}" fires on {cover[t] / total[t]:.0%}')
    unknown = sum(total.values()) - sum(fired_n.values())
    print(f'all labelled harvested cubes: {sum(total.values())}, rules sure on {sum(fired_n.values())}, unknown {unknown}')


if __name__ == '__main__':
    main()
