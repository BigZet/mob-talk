"""Quick looks at a template without the game: a top-down slice per row, each block drawn by the colour of its texture."""
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
TEX = os.path.join(HERE, "..", "..", "src", "main", "resources", "assets", "mobtalk", "textures", "block")
_cache = {}


def colour(name, props):
    key = (name, props.get("inner") or props.get("north"))
    if key in _cache:
        return _cache[key]
    short = name.split(":")[1]
    guess = {"wall_lo": f"city2/{props.get('north', 'plaster')}_lo", "wall_hi": f"city2/{props.get('north', 'plaster')}_hi",
             "radiator": None, "ceiling_lamp": "city2/plafond", "step_terrazzo": "city2/terrazzo"}.get(short)
    cands = [guess] if guess else []
    cands += [f"city2/{short}", f"city2/{short.replace('floor_', '')}", f"city2/{short}_bottom", f"city/{short}"]
    if short.startswith("facade_") or short.startswith("window_"):
        cands.insert(0, "city2/panel_" + short.split("_")[1])
    c = (255, 0, 255)
    if name == "minecraft:air":
        c = (20, 20, 24)
    else:
        for cand in cands:
            if cand and os.path.exists(os.path.join(TEX, cand + ".png")):
                im = Image.open(os.path.join(TEX, cand + ".png")).convert("RGBA").resize((1, 1), Image.BOX)
                c = im.getpixel((0, 0))[:3]
                break
        if short.startswith("window"):
            c = (120, 170, 220)
        if short.startswith("door"):
            c = (180, 60, 40)
    _cache[key] = c
    return c


def slices(t, out, scale=8):
    os.makedirs(out, exist_ok=True)
    sx, sy, sz = t.size
    for y in range(sy):
        img = Image.new("RGB", (sx * scale, sz * scale))
        px = img.load()
        for x in range(sx):
            for z in range(sz):
                name, props = t.cells.get((x, y, z), ("minecraft:air", {}))
                c = colour(name, props)
                for i in range(scale):
                    for j in range(scale):
                        px[x * scale + i, z * scale + j] = c
        img.save(os.path.join(out, f"y{y:02d}.png"))
