"""Shared readers for the place datasets.

Formats (all written next to each other with one base name):
  <base>.mtvx   'MTVX', big-endian int32 version, count, cube, materials, labels (+ label names for version 1), then one cube of
                material ids per sample (uint8, x-major then y then z). Version 1 (generated scenes) has a label byte after each
                cube; version 2 (harvested or cut from saves) has none: labels live in the .jsonl.
  <base>.mtvb   version 2 only: the same cells as uint16 big-endian indices into the sample's palette.
  <base>.jsonl  version 2 only: one JSON object per sample (src, kind, structure/piece/template, biome, palette, ...).
  blocks.tsv    written by the harvester: for every block id its material (default, open/closed, lit/unlit) and its family, exactly
                as the game classifies them, so tools reading saved regions agree with the game.
"""
import json, os, struct
import numpy as np

MATERIALS = ['AIR', 'SOLID', 'UNBREAKABLE', 'GLASS', 'WATER', 'LAVA', 'DOOR_OPEN', 'DOOR_CLOSED', 'FENCE', 'CLIMBABLE', 'HAZARD', 'WOOD',
             'LEAVES', 'PLANT', 'FARMLAND', 'MACHINE']
FAMILIES = ['OTHER', 'LOG', 'PLANKS', 'NATURAL_STONE', 'SOIL', 'SAND_GRAVEL', 'MASONRY', 'GLASS', 'WOOL', 'BED', 'STORAGE', 'WORKSTATION', 'LIGHT',
            'RAIL', 'CROP', 'ORE', 'ICE_SNOW', 'REDSTONE', 'DECORATION', 'METAL', 'WATER_PLANT', 'COBWEB_SPAWNER', 'COLORED', 'SANDSTONE']
MAT = {m: i for i, m in enumerate(MATERIALS)}
FAM = {f: i for i, f in enumerate(FAMILIES)}
CUBE = 16
DATA = os.path.join(os.environ.get('LOCALAPPDATA', '.'), 'MobTalk', 'voxel')


def read_header(f):
    magic = f.read(4)
    assert magic == b'MTVX', magic
    version, count, cube, materials, classes = struct.unpack('>iiiii', f.read(20))
    names = []
    for _ in range(classes):
        (n,) = struct.unpack('>H', f.read(2))
        names.append(f.read(n).decode('utf-8'))
    return version, count, cube, materials, names


def read_v2(base):
    """Material cubes [n,16,16,16] uint8, block-index cubes [n,16,16,16] uint16 (or None), metadata list."""
    with open(base + '.mtvx', 'rb') as f:
        version, count, cube, materials, _ = read_header(f)
        assert version == 2, version
        raw = np.fromfile(f, dtype=np.uint8)
    count = len(raw) // cube ** 3 if count <= 0 else count
    cubes = raw[:count * cube ** 3].reshape(count, cube, cube, cube)
    blocks = None
    if os.path.exists(base + '.mtvb'):
        blocks = np.fromfile(base + '.mtvb', dtype='>u2')[:count * cube ** 3].reshape(count, cube, cube, cube).astype(np.uint16)
    with open(base + '.jsonl', encoding='utf-8') as f:
        meta = [json.loads(line) for line in f if line.strip()][:count]
    return cubes, blocks, meta


def write_v2(base, cubes, blocks, meta):
    os.makedirs(os.path.dirname(os.path.abspath(base)), exist_ok=True)
    with open(base + '.mtvx', 'wb') as f:
        f.write(b'MTVX'); f.write(struct.pack('>iiiii', 2, len(cubes), CUBE, len(MATERIALS), 0))
        f.write(np.ascontiguousarray(cubes, dtype=np.uint8).tobytes())
    if blocks is not None:
        np.ascontiguousarray(blocks, dtype='>u2').tofile(base + '.mtvb')
    with open(base + '.jsonl', 'w', encoding='utf-8') as f:
        for m in meta: f.write(json.dumps(m, ensure_ascii=False) + '\n')


class BlockTable:
    """block id -> material / family, as the game decided (blocks.tsv)."""
    def __init__(self, path=None):
        path = path or os.path.join(DATA, 'blocks.tsv')
        self.material, self.open, self.closed, self.lit, self.unlit, self.family = {}, {}, {}, {}, {}, {}
        with open(path, encoding='utf-8') as f:
            next(f)
            for line in f:
                b, m, o, c, l, u, fam = line.rstrip('\n').split('\t')
                self.material[b] = MAT[m]; self.family[b] = FAM[fam]
                if o != '-': self.open[b] = MAT[o]; self.closed[b] = MAT[c]
                if l != '-': self.lit[b] = MAT[l]; self.unlit[b] = MAT[u]

    def material_of(self, name, props=None):
        if props:
            if name in self.open and 'open' in props: return self.open[name] if props['open'] == 'true' else self.closed[name]
            if name in self.lit and 'lit' in props: return self.lit[name] if props['lit'] == 'true' else self.unlit[name]
        return self.material.get(name, MAT['SOLID'] if not name.endswith('air') else MAT['AIR'])

    def family_of(self, name):
        return self.family.get(name, 0)

    def family_cube(self, blocks, palette):
        lut = np.array([self.family_of(p) for p in palette] or [0], dtype=np.uint8)
        return lut[blocks]


def families_for(blocks, meta, table):
    """Family cubes for a v2 dataset from its block indices and palettes."""
    out = np.zeros(blocks.shape, dtype=np.uint8)
    for i, m in enumerate(meta):
        out[i] = table.family_cube(blocks[i], m['palette'])
    return out


def bases(folder):
    return sorted(os.path.join(folder, f[:-6]) for f in os.listdir(folder) if f.endswith('.jsonl'))
