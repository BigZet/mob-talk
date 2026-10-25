"""Top-down map of a saved world area (region files), for checking city layouts without a game client.

    python3 -I tools/city/render_map.py <world dir> <x0> <z0> <x1> <z1> <out.png> [scale]

Colours are approximate (by block name); roofs get darker with height so tall blocks stand out.
"""
import math
import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nbt  # noqa: E402
from PIL import Image  # noqa: E402

COLORS = [
    ("water", (52, 92, 196)), ("lava", (230, 90, 20)), ("grass_block", (94, 150, 60)), ("short_grass", (94, 150, 60)),
    ("tall_grass", (94, 150, 60)), ("leaves", (50, 110, 40)), ("fern", (80, 130, 60)), ("flower", (200, 180, 60)),
    ("dandelion", (230, 210, 40)), ("poppy", (200, 40, 40)), ("snow", (240, 240, 245)), ("ice", (160, 190, 250)),
    ("sand", (220, 210, 160)), ("gravel", (130, 125, 120)), ("dirt_path", (150, 120, 70)), ("dirt", (130, 95, 60)),
    ("farmland", (110, 80, 50)), ("wheat", (200, 180, 80)), ("concrete_powder", (120, 120, 125)),
    ("smooth_stone", (165, 165, 165)), ("polished_andesite", (140, 145, 145)), ("andesite", (135, 135, 135)),
    ("cobblestone", (115, 115, 115)), ("stone_brick", (120, 120, 120)), ("stone", (125, 125, 125)), ("tuff", (105, 105, 95)),
    ("white_terracotta", (210, 180, 160)), ("terracotta", (160, 90, 60)), ("brick", (150, 75, 60)),
    ("white_concrete", (230, 230, 230)), ("light_gray", (160, 160, 160)), ("gray", (90, 90, 95)), ("black", (30, 30, 30)),
    ("red", (170, 40, 40)), ("orange", (220, 120, 40)), ("yellow", (230, 200, 50)), ("lime", (120, 190, 40)),
    ("green", (70, 100, 40)), ("cyan", (40, 140, 150)), ("light_blue", (110, 160, 220)), ("blue", (50, 60, 160)),
    ("purple", (120, 50, 160)), ("magenta", (180, 70, 170)), ("pink", (230, 140, 170)), ("brown", (110, 75, 45)),
    ("white", (225, 225, 225)), ("quartz", (230, 225, 215)), ("calcite", (225, 225, 220)), ("diorite", (200, 200, 200)),
    ("granite", (150, 105, 85)), ("spruce", (110, 80, 50)), ("birch", (200, 185, 130)), ("dark_oak", (70, 50, 30)),
    ("oak", (160, 130, 80)), ("jungle", (160, 115, 80)), ("acacia", (170, 95, 50)), ("mangrove", (120, 50, 45)),
    ("bamboo", (190, 170, 80)), ("glass", (190, 220, 230)), ("iron", (200, 200, 200)), ("copper", (190, 110, 80)),
    ("bell", (230, 190, 40)), ("mobtalk:", (255, 0, 255)),
]
SKIP = ("air", "short_grass", "tall_grass", "fern", "torch", "rail", "button", "pressure_plate", "carpet", "snow")


def color(name):
    for key, c in COLORS:
        if key in name:
            return c
    return (150, 150, 150)


def chunks(world, cx0, cz0, cx1, cz1):
    regions = {}
    for cx in range(cx0, cx1 + 1):
        for cz in range(cz0, cz1 + 1):
            rx, rz = cx >> 5, cz >> 5
            if (rx, rz) not in regions:
                p = os.path.join(world, "region", f"r.{rx}.{rz}.mca")
                regions[(rx, rz)] = open(p, "rb").read() if os.path.exists(p) else None
            data = regions[(rx, rz)]
            if not data:
                continue
            i = 4 * ((cx & 31) + (cz & 31) * 32)
            off = int.from_bytes(data[i:i + 3], "big") * 4096
            if off == 0:
                continue
            length, kind = struct.unpack(">iB", data[off:off + 5])
            raw = data[off + 5:off + 4 + length]
            yield cx, cz, nbt.load(zlib.decompress(raw) if kind == 2 else raw)[1]


def column_blocks(chunk):
    """{section y: (palette names, indexer)}"""
    out = {}
    for sec in chunk.value.get("sections", nbt.Tag(nbt.LIST, [])).value:
        bs = sec.value.get("block_states")
        if bs is None:
            continue
        pal = [p.value["Name"].value for p in bs.value["palette"].value]
        data = bs.value["data"].value if "data" in bs.value else None
        out[sec.value["Y"].value] = (pal, data)
    return out


def block_at(secs, x, y, z):
    s = secs.get(y >> 4)
    if s is None:
        return "minecraft:air"
    pal, data = s
    if data is None:
        return pal[0]
    bits = max(4, math.ceil(math.log2(len(pal))))
    per = 64 // bits
    idx = ((y & 15) * 16 + z) * 16 + x
    word = data[idx // per] & ((1 << 64) - 1)
    return pal[(word >> ((idx % per) * bits)) & ((1 << bits) - 1)]


def main(world, x0, z0, x1, z1, out, scale=2):
    w, h = x1 - x0 + 1, z1 - z0 + 1
    img = Image.new("RGB", (w, h), (0, 0, 0))
    px = img.load()
    for cx, cz, chunk in chunks(world, x0 >> 4, z0 >> 4, x1 >> 4, z1 >> 4):
        secs = column_blocks(chunk)
        hm = chunk.value.get("Heightmaps", nbt.c()).value.get("WORLD_SURFACE")
        if hm is None:
            continue
        ys = []
        for word in hm.value:
            word &= (1 << 64) - 1
            for k in range(7):
                ys.append((word >> (9 * k)) & 511)
        bottom = -64
        for lz in range(16):
            for lx in range(16):
                wx, wz = cx * 16 + lx, cz * 16 + lz
                if not (x0 <= wx <= x1 and z0 <= wz <= z1):
                    continue
                y = ys[lz * 16 + lx] + bottom - 1
                name = block_at(secs, lx, y, lz)
                while y > bottom and any(s in name for s in SKIP) and "block" not in name:
                    y -= 1
                    name = block_at(secs, lx, y, lz)
                r, g, b = color(name)
                shade = max(0.55, min(1.25, 1 + (y - 70) / 60))
                px[wx - x0, wz - z0] = (min(255, int(r * shade)), min(255, int(g * shade)), min(255, int(b * shade)))
    img = img.resize((w * scale, h * scale), Image.NEAREST)
    img.save(out)


if __name__ == "__main__":
    a = sys.argv
    main(a[1], int(a[2]), int(a[3]), int(a[4]), int(a[5]), a[6], int(a[7]) if len(a) > 7 else 2)
