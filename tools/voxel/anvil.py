"""A small read-only reader for Minecraft 1.18+ saves: NBT, region (.mca) files and chunk sections, enough to cut voxel cubes
and to list the vanilla structure starts a world recorded. No third-party packages; opens files read-only and never writes."""
import gzip, os, struct, zlib
import numpy as np

_END, _BYTE, _SHORT, _INT, _LONG, _FLOAT, _DOUBLE, _BYTES, _STRING, _LIST, _COMPOUND, _INTS, _LONGS = range(13)


class _Reader:
    __slots__ = ('b', 'i')

    def __init__(self, data):
        self.b = data; self.i = 0

    def string(self):
        (n,) = struct.unpack_from('>H', self.b, self.i); self.i += 2
        s = self.b[self.i:self.i + n].decode('utf-8', 'replace'); self.i += n
        return s

    def payload(self, t):
        b = self.b
        if t == _BYTE: v = struct.unpack_from('>b', b, self.i)[0]; self.i += 1; return v
        if t == _SHORT: v = struct.unpack_from('>h', b, self.i)[0]; self.i += 2; return v
        if t == _INT: v = struct.unpack_from('>i', b, self.i)[0]; self.i += 4; return v
        if t == _LONG: v = struct.unpack_from('>q', b, self.i)[0]; self.i += 8; return v
        if t == _FLOAT: v = struct.unpack_from('>f', b, self.i)[0]; self.i += 4; return v
        if t == _DOUBLE: v = struct.unpack_from('>d', b, self.i)[0]; self.i += 8; return v
        if t == _BYTES:
            (n,) = struct.unpack_from('>i', b, self.i); self.i += 4
            v = np.frombuffer(b, dtype=np.int8, count=n, offset=self.i); self.i += n; return v
        if t == _STRING: return self.string()
        if t == _LIST:
            et, n = struct.unpack_from('>bi', b, self.i); self.i += 5
            return [self.payload(et) for _ in range(n)]
        if t == _COMPOUND:
            out = {}
            while True:
                ct = b[self.i]; self.i += 1
                if ct == _END: return out
                name = self.string()
                out[name] = self.payload(ct)
        if t == _INTS:
            (n,) = struct.unpack_from('>i', b, self.i); self.i += 4
            v = np.frombuffer(b, dtype='>i4', count=n, offset=self.i); self.i += 4 * n; return v
        if t == _LONGS:
            (n,) = struct.unpack_from('>i', b, self.i); self.i += 4
            v = np.frombuffer(b, dtype='>i8', count=n, offset=self.i); self.i += 8 * n; return v
        raise ValueError(f'bad tag {t} at {self.i}')


def parse_nbt(data):
    r = _Reader(data)
    t = r.b[0]; r.i = 1
    r.string()
    return r.payload(t)


def read_level(path):
    with gzip.open(path, 'rb') as f:
        return parse_nbt(f.read())


class Region:
    """One .mca file: chunks by local coordinates, decoded lazily."""
    def __init__(self, path):
        self.path = path
        with open(path, 'rb') as f:
            self.data = f.read()
        name = os.path.basename(path).split('.')
        self.rx, self.rz = int(name[1]), int(name[2])

    def chunk_coords(self):
        for i in range(1024):
            off = struct.unpack_from('>I', self.data, i * 4)[0] if len(self.data) >= 4096 else 0
            if off >> 8: yield i % 32, i // 32

    def chunk(self, lx, lz):
        if len(self.data) < 8192: return None
        i = (lz % 32) * 32 + (lx % 32)
        loc = struct.unpack_from('>I', self.data, i * 4)[0]
        sector = loc >> 8
        if not sector: return None
        start = sector * 4096
        if start + 5 > len(self.data): return None
        length, kind = struct.unpack_from('>IB', self.data, start)
        raw = self.data[start + 5:start + 4 + length]
        if kind & 0x80: return None  # stored in an external .mcc file: rare, skipped
        if kind == 2: raw = zlib.decompress(raw)
        elif kind == 1: raw = gzip.decompress(raw)
        elif kind != 3: return None
        return parse_nbt(raw)


def section_blocks(section):
    """(palette list of (name, props), indices uint16[16,16,16] as [y][z][x]) or None for a section without block states."""
    states = section.get('block_states')
    if not states: return None
    palette = [(p.get('Name', 'minecraft:air'), p.get('Properties')) for p in states.get('palette', [])]
    data = states.get('data')
    if data is None or len(palette) <= 1:
        return palette, np.zeros((16, 16, 16), dtype=np.uint16)
    bits = max(4, int(np.ceil(np.log2(len(palette)))))
    per = 64 // bits
    words = data.astype('<i8').view(np.uint64)
    shifts = (np.arange(per, dtype=np.uint64) * np.uint64(bits))
    mask = np.uint64((1 << bits) - 1)
    values = ((words[:, None] >> shifts[None, :]) & mask).reshape(-1)[:4096]
    return palette, values.astype(np.uint16).reshape(16, 16, 16)


def structure_starts(chunk):
    """The structure starts recorded in a chunk: list of dicts with id and children (id, template, bb)."""
    out = []
    structures = chunk.get('structures') or {}
    for sid, start in (structures.get('starts') or {}).items():
        if start.get('id') == 'INVALID': continue
        children = []
        for c in start.get('Children') or []:
            bb = c.get('BB')
            if bb is None: continue
            template = c.get('Template', '')
            pool = c.get('pool_element')
            if isinstance(pool, dict):
                loc = pool.get('location')
                template = loc if isinstance(loc, str) else template
                if pool.get('element_type', '').endswith('empty_pool_element') or pool.get('element_type', '').endswith('feature_pool_element'):
                    template = None
            children.append(dict(id=c.get('id', ''), template=template, bb=[int(v) for v in bb]))
        out.append(dict(id=sid, chunk=(start.get('ChunkX'), start.get('ChunkZ')), children=children))
    return out


def heightmap(chunk, name='WORLD_SURFACE', min_y=-64):
    hm = (chunk.get('Heightmaps') or {}).get(name)
    if hm is None: return None
    words = hm.astype('<i8').view(np.uint64)
    bits, per = 9, 7
    shifts = (np.arange(per, dtype=np.uint64) * np.uint64(bits))
    values = ((words[:, None] >> shifts[None, :]) & np.uint64(511)).reshape(-1)[:256]
    return values.astype(np.int32).reshape(16, 16) + min_y  # [z][x], first free block above the surface


class World:
    """A save folder's overworld, read-only, with an LRU cache of decoded chunk sections."""
    def __init__(self, folder, cache=2048):
        self.folder = folder
        self.region_dir = os.path.join(folder, 'region')
        self.regions = {}
        self.cache, self.order, self.limit = {}, [], cache
        self.biomes, self.heights = {}, {}

    def biome(self, x, y, z):
        self.sections(x >> 4, z >> 4)
        return self.biomes.get((x >> 4, y >> 4, z >> 4), 'unknown')

    def height(self, x, z):
        """First free y above the ground (leaves ignored), or None."""
        self.sections(x >> 4, z >> 4)
        hm = self.heights.get((x >> 4, z >> 4))
        return None if hm is None else int(hm[z & 15, x & 15])

    def region(self, rx, rz):
        key = (rx, rz)
        if key not in self.regions:
            path = os.path.join(self.region_dir, f'r.{rx}.{rz}.mca')
            self.regions[key] = Region(path) if os.path.exists(path) else None
        return self.regions[key]

    def sections(self, cx, cz):
        """{section y: (palette, indices)} for a chunk; empty when not generated."""
        key = (cx, cz)
        if key in self.cache: return self.cache[key]
        reg = self.region(cx >> 5, cz >> 5)
        out = {}
        if reg is not None:
            try:
                chunk = reg.chunk(cx & 31, cz & 31)
            except Exception:
                chunk = None
            if chunk is not None and chunk.get('Status', '') in ('minecraft:full', 'full'):
                for s in chunk.get('sections') or []:
                    decoded = section_blocks(s)
                    if decoded is not None: out[int(s['Y'])] = decoded
                    b = s.get('biomes')
                    if b and b.get('palette'): self.biomes[(cx, int(s['Y']), cz)] = b['palette'][0] if len(b['palette']) == 1 else _biome_mode(b)
                hm = heightmap(chunk, 'MOTION_BLOCKING_NO_LEAVES')
                if hm is not None: self.heights[key] = hm
        self.cache[key] = out; self.order.append(key)
        if len(self.order) > self.limit:
            old = self.order.pop(0); self.cache.pop(old, None)
        return out

    def box(self, x0, y0, z0, sx, sy, sz):
        """Block states of a box as a list of (name, props) palette and an index array [x][y][z]; None when a chunk is missing."""
        palette, lookup = [('minecraft:air', None)], {('minecraft:air', None): 0}
        out = np.zeros((sx, sy, sz), dtype=np.uint16)
        for cx in range(x0 >> 4, (x0 + sx - 1 >> 4) + 1):
            for cz in range(z0 >> 4, (z0 + sz - 1 >> 4) + 1):
                secs = self.sections(cx, cz)
                if not secs: return None, None
                for sy_ in range(y0 >> 4, (y0 + sy - 1 >> 4) + 1):
                    if sy_ not in secs: continue
                    pal, idx = secs[sy_]
                    remap = np.empty(len(pal), dtype=np.uint16)
                    for i, (name, props) in enumerate(pal):
                        k = (name, _freeze(props))
                        if k not in lookup: lookup[k] = len(palette); palette.append((name, props))
                        remap[i] = lookup[k]
                    # world ranges covered by this section within the box
                    ax0, ax1 = max(x0, cx * 16), min(x0 + sx, cx * 16 + 16)
                    az0, az1 = max(z0, cz * 16), min(z0 + sz, cz * 16 + 16)
                    ay0, ay1 = max(y0, sy_ * 16), min(y0 + sy, sy_ * 16 + 16)
                    if ax0 >= ax1 or az0 >= az1 or ay0 >= ay1: continue
                    part = idx[ay0 - sy_ * 16:ay1 - sy_ * 16, az0 - cz * 16:az1 - cz * 16, ax0 - cx * 16:ax1 - cx * 16]  # [y][z][x]
                    out[ax0 - x0:ax1 - x0, ay0 - y0:ay1 - y0, az0 - z0:az1 - z0] = remap[part].transpose(2, 0, 1)
        return palette, out


def _biome_mode(b):
    pal = b['palette']; data = b.get('data')
    if data is None: return pal[0]
    bits = max(1, int(np.ceil(np.log2(len(pal))))); per = 64 // bits
    words = data.astype('<i8').view(np.uint64)
    shifts = np.arange(per, dtype=np.uint64) * np.uint64(bits)
    vals = ((words[:, None] >> shifts[None, :]) & np.uint64((1 << bits) - 1)).reshape(-1)[:64].astype(np.int64)
    return pal[int(np.bincount(vals, minlength=len(pal)).argmax())]


def _freeze(props):
    return None if not props else tuple(sorted(props.items()))
