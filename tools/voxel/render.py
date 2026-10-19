"""Renders a 16³ cube of blocks as flat-coloured pictures for a vision model: no textures, one colour per block kind, the creature
as a red column at its feet. Orthographic ray casting in numpy; PNG written by hand (no PIL).

A collage is 2×2 panels of 256 px. View set 'a': isometric from the north-east and from the south-west (ground below the feet
mostly cut away), the same from above with everything over the creature's head removed, and the floor plan at the feet.
View set 'b': the other two isometric corners, a cut-away from the other side and a vertical section through the creature.
"""
import struct, zlib
import numpy as np

FX, FY, FZ = 8, 7, 8

_RULES = [  # (substring, rgb) — first match wins
    ('lava', (1.0, 0.45, 0.0)), ('water', (0.2, 0.35, 0.85)), ('bubble_column', (0.2, 0.35, 0.85)), ('bedrock', (0.12, 0.12, 0.12)),
    ('obsidian', (0.16, 0.1, 0.24)), ('glass_pane', (0.72, 0.88, 0.95)), ('glass', (0.72, 0.88, 0.95)), ('iron_bars', (0.55, 0.55, 0.62)),
    ('ice', (0.6, 0.78, 0.98)), ('snow', (0.95, 0.97, 1.0)), ('torch', (1.0, 0.85, 0.25)), ('lantern', (1.0, 0.8, 0.3)), ('glowstone', (1.0, 0.85, 0.4)),
    ('sea_lantern', (0.8, 0.95, 0.9)), ('rail', (0.6, 0.5, 0.38)), ('_bed', (0.8, 0.15, 0.15)), ('chest', (0.62, 0.42, 0.14)), ('barrel', (0.5, 0.35, 0.18)),
    ('crafting_table', (0.55, 0.36, 0.2)), ('furnace', (0.42, 0.42, 0.42)), ('anvil', (0.3, 0.3, 0.32)), ('bookshelf', (0.55, 0.38, 0.22)),
    ('spawner', (0.15, 0.2, 0.3)), ('cobweb', (0.9, 0.9, 0.9)), ('tnt', (0.85, 0.2, 0.15)), ('piston', (0.6, 0.55, 0.4)), ('dispenser', (0.45, 0.45, 0.45)),
    ('hopper', (0.3, 0.3, 0.3)), ('observer', (0.4, 0.4, 0.4)), ('redstone', (0.75, 0.1, 0.1)), ('pressure_plate', (0.6, 0.6, 0.55)),
    ('iron_door', (0.8, 0.8, 0.8)), ('_door', (0.5, 0.36, 0.2)), ('trapdoor', (0.5, 0.38, 0.22)), ('fence_gate', (0.55, 0.42, 0.26)),
    ('nether_brick', (0.25, 0.1, 0.12)), ('_fence', (0.6, 0.47, 0.3)), ('cobblestone_wall', (0.45, 0.45, 0.45)), ('ladder', (0.6, 0.48, 0.3)),
    ('farmland', (0.33, 0.2, 0.1)), ('wheat', (0.85, 0.75, 0.3)), ('carrots', (0.9, 0.5, 0.1)), ('potatoes', (0.75, 0.65, 0.3)), ('beetroots', (0.6, 0.15, 0.2)),
    ('hay_block', (0.85, 0.7, 0.2)), ('pumpkin', (0.9, 0.55, 0.1)), ('melon', (0.4, 0.6, 0.15)), ('leaves', (0.2, 0.5, 0.15)), ('azalea', (0.35, 0.6, 0.2)),
    ('cherry_leaves', (0.95, 0.65, 0.8)), ('_log', (0.4, 0.3, 0.17)), ('_wood', (0.4, 0.3, 0.17)), ('stem', (0.5, 0.25, 0.3)), ('mushroom_block', (0.7, 0.3, 0.25)),
    ('spruce_', (0.45, 0.32, 0.18)), ('birch_', (0.8, 0.72, 0.5)), ('dark_oak_', (0.3, 0.2, 0.1)), ('jungle_', (0.65, 0.45, 0.32)), ('acacia_', (0.7, 0.38, 0.2)),
    ('mangrove_', (0.45, 0.2, 0.18)), ('cherry_', (0.85, 0.6, 0.55)), ('crimson_', (0.5, 0.2, 0.3)), ('warped_', (0.2, 0.5, 0.5)), ('bamboo', (0.75, 0.7, 0.35)),
    ('oak_', (0.68, 0.53, 0.32)), ('_planks', (0.66, 0.52, 0.32)), ('stone_brick', (0.55, 0.55, 0.55)), ('bricks', (0.6, 0.3, 0.25)),
    ('mossy_cobblestone', (0.4, 0.5, 0.38)), ('cobblestone', (0.45, 0.45, 0.45)), ('cobbled_deepslate', (0.3, 0.3, 0.32)), ('deepslate', (0.3, 0.3, 0.33)),
    ('grass_block', (0.36, 0.6, 0.25)), ('dirt_path', (0.6, 0.5, 0.28)), ('mud', (0.3, 0.27, 0.26)), ('podzol', (0.4, 0.28, 0.15)), ('mycelium', (0.45, 0.38, 0.45)),
    ('dirt', (0.47, 0.33, 0.22)), ('clay', (0.6, 0.62, 0.68)), ('red_sand', (0.75, 0.4, 0.2)), ('sandstone', (0.85, 0.78, 0.55)), ('sand', (0.88, 0.83, 0.6)),
    ('gravel', (0.55, 0.52, 0.5)), ('terracotta', (0.65, 0.4, 0.3)), ('netherrack', (0.45, 0.15, 0.15)), ('end_stone', (0.86, 0.86, 0.62)),
    ('quartz', (0.92, 0.9, 0.86)), ('prismarine', (0.35, 0.65, 0.6)), ('copper', (0.75, 0.45, 0.3)), ('tuff', (0.42, 0.43, 0.4)), ('blackstone', (0.17, 0.15, 0.18)),
    ('basalt', (0.3, 0.3, 0.32)), ('calcite', (0.88, 0.88, 0.86)), ('granite', (0.6, 0.42, 0.35)), ('diorite', (0.78, 0.78, 0.78)), ('andesite', (0.55, 0.55, 0.55)),
    ('_ore', (0.5, 0.5, 0.5)), ('stone', (0.5, 0.5, 0.5)), ('moss', (0.35, 0.5, 0.2)), ('cactus', (0.3, 0.55, 0.2)), ('magma', (0.6, 0.25, 0.1)),
    ('fire', (1.0, 0.55, 0.1)), ('campfire', (0.9, 0.5, 0.15)), ('bell', (0.95, 0.8, 0.2)), ('cauldron', (0.3, 0.3, 0.3)), ('composter', (0.5, 0.36, 0.2)),
    ('lectern', (0.6, 0.45, 0.28)), ('smoker', (0.4, 0.38, 0.35)), ('blast_furnace', (0.4, 0.4, 0.42)), ('grindstone', (0.6, 0.6, 0.6)),
    ('loom', (0.7, 0.6, 0.45)), ('table', (0.55, 0.42, 0.28)), ('stonecutter', (0.55, 0.55, 0.55)), ('kelp', (0.25, 0.5, 0.2)), ('coral', (0.9, 0.4, 0.5)),
    ('sign', (0.65, 0.5, 0.3)), ('banner', (0.9, 0.9, 0.9)), ('chain', (0.35, 0.35, 0.4)), ('iron', (0.8, 0.8, 0.8)), ('gold', (0.95, 0.8, 0.2)),
]
_COLOURS = {'white': (0.93, 0.93, 0.93), 'orange': (0.9, 0.5, 0.15), 'magenta': (0.75, 0.3, 0.7), 'light_blue': (0.4, 0.65, 0.9), 'yellow': (0.95, 0.85, 0.2),
            'lime': (0.5, 0.8, 0.2), 'pink': (0.95, 0.6, 0.7), 'light_gray': (0.6, 0.6, 0.6), 'gray': (0.3, 0.3, 0.32), 'cyan': (0.15, 0.55, 0.6),
            'purple': (0.45, 0.2, 0.65), 'blue': (0.2, 0.25, 0.65), 'brown': (0.45, 0.3, 0.2), 'green': (0.3, 0.45, 0.15), 'red': (0.65, 0.15, 0.15),
            'black': (0.1, 0.1, 0.12)}
# Thin or see-through things a vision model need not see as blocks.
_SKIP = ('air', 'short_grass', 'tall_grass', 'fern', 'flower', 'poppy', 'dandelion', 'tulip', 'orchid', 'allium', 'bluet', 'daisy', 'cornflower',
         'lily_of_the_valley', 'dead_bush', 'seagrass', 'vine', 'glow_lichen', 'sculk_vein', 'button', 'tripwire', 'structure_void', 'light', 'moss_carpet')


def colour(name):
    n = name.split(':', 1)[-1]
    if n.endswith('air') or n == 'void_air' or any(n == s or (s not in ('air', 'light') and s in n) for s in _SKIP): return None
    if any(w in n for w in ('wool', 'carpet', 'concrete', 'stained', 'terracotta', 'candle', 'shulker')):
        for c, rgb in sorted(_COLOURS.items(), key=lambda kv: -len(kv[0])):
            if n.startswith(c + '_'): return rgb
    for key, rgb in _RULES:
        if key in n: return rgb
    return (0.68, 0.68, 0.68)


def colours(palette):
    lut = np.zeros((len(palette) + 1, 3), dtype=np.float32)
    solid = np.zeros(len(palette) + 1, dtype=bool)
    for i, n in enumerate(palette):
        c = colour(n)
        if c is not None: lut[i] = c; solid[i] = True
    return lut, solid


def raycast(occ, col, azimuth, elevation, size=256, zoom=1.0):
    """occ [16,16,16] bool (x, y, z), col [16,16,16,3]. Orthographic view from the given azimuth/elevation (degrees)."""
    a, e = np.radians(azimuth), np.radians(elevation)
    d = -np.array([np.cos(e) * np.sin(a), np.sin(e), np.cos(e) * np.cos(a)])  # looking direction (x east, y up, z south)
    right = np.cross(d, [0, 1, 0]); right /= np.linalg.norm(right)
    up = np.cross(right, d)
    half = 14.5 / zoom
    u = np.linspace(-half, half, size)
    U, V = np.meshgrid(u, -u)
    centre = np.array([8.0, 8.0, 8.0])
    origins = centre[None, None, :] + U[..., None] * right + V[..., None] * up - d * 30
    img = np.ones((size, size, 3), dtype=np.float32) * np.array([0.93, 0.95, 0.98], dtype=np.float32)
    # Each ray only marches inside the cube's box (slab test), in short steps.
    o = origins.reshape(-1, 3); dd = np.where(np.abs(d) < 1e-9, 1e-9, d)
    t1, t2 = (0 - o) / dd, (16 - o) / dd
    tmin = np.minimum(t1, t2).max(axis=1); tmax = np.maximum(t1, t2).min(axis=1)
    live = np.nonzero(tmax > tmin)[0]
    flat = img.reshape(-1, 3)
    if not len(live): return img
    o, tmin, tmax = o[live], tmin[live], tmax[live]
    step = 0.25
    hit = np.zeros(len(live), dtype=bool)
    prev = np.floor(o + d * (tmin[:, None] - 0.01)).astype(np.int32)
    for k in range(int((tmax - tmin).max() / step) + 2):
        t = tmin + k * step + 0.001
        active = ~hit & (t < tmax)
        if not active.any(): break
        p = o + d * t[:, None]
        idx = np.floor(p).astype(np.int32).clip(0, 15)
        now = active & occ[idx[:, 0], idx[:, 1], idx[:, 2]]
        if now.any():
            c = col[idx[now, 0], idx[now, 1], idx[now, 2]]
            moved = idx[now] - prev[now]
            shade = np.where(moved[:, 1] != 0, 1.0, np.where(moved[:, 0] != 0, 0.82, 0.64)).astype(np.float32)
            flat[live[now]] = c * shade[:, None]
            hit |= now
        prev = np.where(active[:, None], idx, prev)
    return img


def plan(occ, col, y):
    """Top-down floor plan of one layer (what stands at the feet, with the floor colour where nothing does)."""
    img = np.ones((16, 16, 3), dtype=np.float32) * 0.93
    for x in range(16):
        for z in range(16):
            if occ[x, y, z]: img[z, x] = col[x, y, z]
            elif occ[x, y - 1, z]: img[z, x] = col[x, y - 1, z] * 0.55 + 0.4
    return np.kron(img, np.ones((16, 16, 1), dtype=np.float32))


def section(occ, col, z):
    img = np.ones((16, 16, 3), dtype=np.float32) * 0.93
    for x in range(16):
        for y in range(16):
            if occ[x, y, z]: img[15 - y, x] = col[x, y, z]
    return np.kron(img, np.ones((16, 16, 1), dtype=np.float32))


def scene(blocks, palette):
    lut, solid = colours(palette)
    occ = solid[blocks]
    col = lut[blocks]
    occ = occ.copy(); col = col.copy()
    occ[FX, FY:FY + 2, FZ] = True; col[FX, FY:FY + 2, FZ] = (0.95, 0.05, 0.05)  # the creature
    return occ, col


def collage(blocks, palette, views='a'):
    occ, col = scene(blocks, palette)
    low = occ.copy(); low[:, :max(0, FY - 3), :] = False  # most of the ground under the feet
    cut = low.copy(); cut[:, FY + 3:, :] = False  # and everything over the head
    if views == 'a':
        panels = [raycast(low, col, 45, 32), raycast(low, col, 225, 32), raycast(cut, col, 135, 55), plan(occ, col, FY)]
    else:
        panels = [raycast(low, col, 135, 32), raycast(low, col, 315, 32), raycast(cut, col, 315, 55), section(occ, col, FZ)]
    for p in panels: p[:2, :] = p[-2:, :] = p[:, :2] = p[:, -2:] = 0.3
    top = np.concatenate(panels[:2], axis=1); bottom = np.concatenate(panels[2:], axis=1)
    return (np.clip(np.concatenate([top, bottom], axis=0), 0, 1) * 255).astype(np.uint8)


def png(rgb):
    h, w, _ = rgb.shape
    raw = b''.join(b'\x00' + rgb[y].tobytes() for y in range(h))
    def chunk(kind, data):
        c = struct.pack('>I', len(data)) + kind + data
        return c + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
