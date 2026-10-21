"""Generates the village cemetery templates (data/divineblock/structure/cemetery/*.nbt), their jigsaw pools, the tree
templates and a standalone test structure. Each piece is laid out from its own seed: one main path from the entrance,
fenced family plots packed along it and behind them, squeeze lanes, crosses, monuments, mounds and slabs, benches and
tables, abandoned overgrown plots, old unfenced graves at the edges, lilacs and a few trees. Run from the repository root: python scripts/cemetery/structures.py

Template frame (before the jigsaw rotates it): x 0..20 across, z along the path, y 0 is the ground layer (paths replace the top
block, everything else stands on y 1). The entrance jigsaw sits at (10, 0, 0) facing north."""
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(__file__))
import nbt  # noqa: E402

ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "data", "divineblock")
NS = "divineblock"
W, H = 21, 24  # tall enough (with room for slopes) for a tree child: the jigsaw only lets a child grow inside its parent's box
CLEAR = 8  # air from y 1 up to here clears the ground the cemetery stands on
PATH_X = 10
START_DEPTH, ROWS_DEPTH, END_DEPTH = 27, 24, 7
DATA_VERSION = 3955
VARIANTS = 3


def save_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2); f.write("\n")


class Template:
    def __init__(self, depth):
        self.blocks = {}   # (x, y, z) -> (name, props, nbt)
        self.depth = depth

    def set(self, x, y, z, name, nbt_data=None, **props):
        if 0 <= x < W and 0 <= y < H and 0 <= z < self.depth:
            self.blocks[(x, y, z)] = (name if ":" in name else "minecraft:" + name, {k: str(v).lower() for k, v in props.items()}, nbt_data)

    def get(self, x, y, z):
        return self.blocks.get((x, y, z), (None,))[0]

    def free(self, x, z):
        return self.get(x, 1, z) in (None, "minecraft:air")

    def write(self, path):
        palette, index, blocks = [], {}, []
        for (x, y, z), (name, props, data) in sorted(self.blocks.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
            key = (name, tuple(sorted(props.items())))
            if key not in index:
                index[key] = len(palette)
                entry = {"Name": name}
                if props: entry["Properties"] = nbt.Compound({k: nbt.String(v) for k, v in props.items()})
                palette.append(nbt.Compound(entry))
            b = {"pos": nbt.List(nbt.INT, [nbt.Int(x), nbt.Int(y), nbt.Int(z)]), "state": nbt.Int(index[key])}
            if data: b["nbt"] = nbt.Compound({k: nbt.Int(v) if isinstance(v, int) else nbt.String(v) for k, v in data.items()})
            blocks.append(nbt.Compound(b))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        nbt.write(path, {
            "size": nbt.List(nbt.INT, [nbt.Int(W), nbt.Int(H), nbt.Int(self.depth)]),
            "entities": nbt.List(nbt.COMPOUND, []),
            "blocks": nbt.List(nbt.COMPOUND, blocks),
            "palette": nbt.List(nbt.COMPOUND, palette),
            "DataVersion": nbt.Int(DATA_VERSION),
        })


def jigsaw(t, x, y, z, orientation, name, pool, target, final_state, joint="rollable", priority=0):
    """priority: the jigsaw's selection priority. Children are placed in the order their jigsaws were picked, so the next
    stretch of the path goes first and a tree never grows where a later piece then follows the crown instead of the ground."""
    t.set(x, y, z, "jigsaw", {"id": "minecraft:jigsaw", "name": name, "pool": pool, "target": target, "final_state": final_state,
                               "joint": joint, "selection_priority": priority, "placement_priority": 0}, orientation=orientation)


# Two kinds of section, as on the reference photos: a modern dense one (black granite, black fences, varnished crosses, few
# trees) and an old overgrown one (rusty fences, marble, stone crosses, ferns, more trees).
MODERN = dict(monuments=[("granite_stele", 30), ("roofed_cross", 18), ("wooden_cross", 12), ("granite_cross", 14), ("white_metal_cross", 8),
                         ("iron_cross", 8), ("star_obelisk", 6), ("marble_stone", 4)],
              colors=["black"] * 7 + ["blue"] * 2 + ["rusty"], abandoned=0.06, slab=0.45, wild=0.1, trees=(0, 1, 1, 2))
OLD = dict(monuments=[("old_cross", 20), ("gothic_marble_stone", 18), ("stone_trunk_cross", 10), ("marble_stone", 12), ("iron_cross", 14),
                      ("white_metal_cross", 10), ("wooden_cross", 8), ("star_obelisk", 8)],
           colors=["rusty"] * 6 + ["black"] * 3 + ["blue"] * 2, abandoned=0.3, slab=0.05, wild=0.45, trees=(2, 2, 3))
STYLE = MODERN
TALL = {"wooden_cross", "roofed_cross", "old_cross", "iron_cross", "star_obelisk", "granite_cross", "white_metal_cross", "stone_trunk_cross",
        "sector_sign"}
PATH = (9, 10, 11)


def pick(rnd, table):
    return rnd.choices([n for n, _ in table], [w for _, w in table])[0]


def monument(t, x, z, name, facing):
    if name in TALL:
        t.set(x, 1, z, NS + ":" + name, facing=facing, half="lower", waterlogged=False)
        t.set(x, 2, z, NS + ":" + name, facing=facing, half="upper", waterlogged=False)
    else:
        t.set(x, 1, z, NS + ":" + name, facing=facing, waterlogged=False)


def grave(t, rnd, x_head, z, toward, name=None, overgrown=False):
    """A monument at the head and a two block mound toward the alley (toward = +1 east, -1 west)."""
    facing = "east" if toward > 0 else "west"
    name = name or pick(rnd, STYLE["monuments"])
    monument(t, x_head, z, name, facing)
    slab = not overgrown and name in ("granite_stele", "granite_cross") and rnd.random() < STYLE["slab"] * 2
    for dx in (1, 2):
        if slab: t.set(x_head + dx * toward, 1, z, NS + ":grave_slab", facing=facing, waterlogged=False)
        else: t.set(x_head + dx * toward, 1, z, NS + ":grave_mound", facing=facing, waterlogged=False, overgrown=overgrown)


def ground(t, x, z, rnd, kind):
    if kind == "gravel":
        t.set(x, 0, z, rnd.choices(["gravel", "coarse_dirt", "dirt_path"], [6, 3, 1])[0])
    elif kind == "path":
        t.set(x, 0, z, rnd.choices(["coarse_dirt", "dirt_path", "grass_block", "gravel"], [4, 3, 2, 1])[0])
    elif kind == "edge":  # the sides of the main path: trodden earth, fallen leaves, grass
        t.set(x, 0, z, rnd.choices(["coarse_dirt", "podzol", "grass_block", "dirt_path", "rooted_dirt"], [3, 3, 3, 1, 1])[0])
    elif kind == "plot":
        t.set(x, 0, z, rnd.choices(["gravel", "coarse_dirt"], [3, 1])[0])


def plants(t, rnd, x, z, wild):
    if not t.free(x, z): return
    roll = rnd.random()
    if wild:
        if roll < 0.32:
            t.set(x, 1, z, "tall_grass", half="lower"); t.set(x, 2, z, "tall_grass", half="upper")
        elif roll < 0.62: t.set(x, 1, z, "short_grass")
        elif roll < 0.72: t.set(x, 1, z, "fern")
        elif roll < 0.76: t.set(x, 1, z, rnd.choice(["dandelion", "cornflower", "oxeye_daisy"]))
    elif roll < 0.22:
        t.set(x, 1, z, "short_grass")


def connect(t):
    """Fences join their neighbours the way the game would, gates join along their own line."""
    for (x, y, z), (name, props, data) in list(t.blocks.items()):
        if not name.endswith("_grave_fence"): continue
        for side, (dx, dz) in (("north", (0, -1)), ("south", (0, 1)), ("east", (1, 0)), ("west", (-1, 0))):
            other = t.blocks.get((x + dx, y, z + dz))
            on = False
            if other and other[0].endswith("_grave_fence"): on = True
            elif other and other[0].endswith("_grave_gate"):
                on = (other[1]["facing"] in ("east", "west")) == (dx == 0)
            props[side] = "true" if on else "false"


# Plot frames, west of the path (side -1) and mirrored east of it: front plots open onto the path, back plots onto the
# squeeze lanes between blocks of plots. Each block is two plots deep along the path; neighbours share their fences.
FRONT = {-1: dict(x0=4, x1=8, head=5, toward=1, near=None, gate_x=8), 1: dict(x0=12, x1=16, head=15, toward=-1, near=None, gate_x=12)}
BACK = {-1: dict(x0=0, x1=4, head=1, toward=1, near=None, gate_x=3), 1: dict(x0=16, x1=20, head=19, toward=-1, near=None, gate_x=17)}


def fence_ring(t, rnd, f, z0, z1, color, abandoned):
    for x in range(f["x0"], f["x1"] + 1):
        for z in range(z0, z1 + 1):
            if x not in (f["x0"], f["x1"]) and z not in (z0, z1): continue
            here = t.get(x, 1, z)
            if here and (here.endswith("_grave_fence") or here.endswith("_grave_gate")): continue  # shared with a neighbour
            if abandoned and rnd.random() < 0.3: t.set(x, 1, z, "air"); continue
            t.set(x, 1, z, f"{NS}:{color}_grave_fence", north=False, south=False, east=False, west=False, waterlogged=False)


def plot_inside(t, rnd, f, z0, z1, gate, abandoned):
    """Graves at the head, toward the gate; flowers, a wreath, a lampada, a bench or a table in what is left."""
    rows = list(range(z0 + 1, z1))
    gz = gate[1]
    graves = rows if rnd.random() < 0.55 else [rnd.choice(rows)]
    for z in graves:
        name = "old_cross" if abandoned and rnd.random() < 0.75 else None
        grave(t, rnd, f["head"], z, f["toward"], name=name, overgrown=abandoned or rnd.random() < 0.08)
    facing = "east" if f["toward"] > 0 else "west"
    for z in rows:
        if z in graves: continue
        t.tree_spots.append((f["head"] + f["toward"], z))
        if not abandoned and rnd.random() < 0.55:
            t.set(f["head"] + f["toward"], 1, z, NS + ":grave_table", facing="north", waterlogged=False)
            t.set(f["head"] + 2 * f["toward"], 1, z, NS + ":grave_bench", facing="north", waterlogged=False)
            if rnd.random() < 0.6: t.set(f["head"], 1, z, NS + ":artificial_flowers", facing=facing, waterlogged=False)
            continue
        for i, item in enumerate(("funeral_wreath", "grave_lampada", "artificial_flowers")):
            x = f["head"] + i * f["toward"]
            if t.free(x, z) and rnd.random() < (0.35 if abandoned else 0.7):
                t.set(x, 1, z, NS + ":" + item, facing=facing, waterlogged=False)
    if f["near"] is not None and not abandoned:
        for z in rows:
            if z == gz or not t.free(f["near"], z): continue  # keep the way in from the gate clear
            roll = rnd.random()
            if roll < 0.5: t.set(f["near"], 1, z, NS + ":grave_bench", facing="north" if z == rows[-1] else "south", waterlogged=False)
            elif roll < 0.75: t.set(f["near"], 1, z, NS + ":grave_table", facing="north", waterlogged=False)
    for x in range(f["x0"] + 1, f["x1"]):
        for z in rows:
            if not abandoned and rnd.random() < 0.85 and t.get(x, 0, z) is None: ground(t, x, z, rnd, "plot")
            plants(t, rnd, x, z, abandoned)


def block_of_plots(t, rnd, zs, side, stats):
    """Four plots: two along the path, two behind them; z zs..zs+6, lanes at zs-1 and zs+7."""
    plots = []
    for f, kind in ((FRONT[side], "front"), (BACK[side], "back")):
        for i, (z0, z1) in enumerate(((zs, zs + 3), (zs + 3, zs + 6))):
            abandoned = rnd.random() < STYLE["abandoned"]
            if kind == "front":
                gate = (f["gate_x"], rnd.choice((z0 + 1, z0 + 2)), "east" if side < 0 else "west")
            else:
                gate = (f["gate_x"], z0 if i == 0 else z1, "north" if i == 0 else "south")
            plots.append((f, z0, z1, gate, abandoned, rnd.choice(STYLE["colors"])))
    for f, z0, z1, gate, abandoned, color in plots:
        x, z, facing = gate
        t.set(x, 1, z, f"{NS}:{color}_grave_gate", facing=facing, open=abandoned and rnd.random() < 0.6, in_wall=False, powered=False)
    for f, z0, z1, gate, abandoned, color in plots:
        fence_ring(t, rnd, f, z0, z1, color, abandoned)
        stats["plots"] += 1; stats["abandoned"] += abandoned
    for f, z0, z1, gate, abandoned, color in plots: plot_inside(t, rnd, f, z0, z1, gate, abandoned)


def lane(t, rnd, z):
    for x in range(W):
        if x in PATH: continue
        if rnd.random() < 0.35: ground(t, x, z, rnd, "path")
        plants(t, rnd, x, z, rnd.random() < 0.3 + STYLE["wild"])
        if x in (3, W - 4): t.tree_spots.append((x, z))


def old_graves_row(t, rnd, z):
    """Unfenced old graves across the cemetery's edge, heads away from the path; a lilac now and then."""
    for side, heads in ((-1, (0, 3, 6)), (1, (20, 17, 14))):
        toward = 1 if side < 0 else -1
        for head in heads:
            roll = rnd.random()
            if roll < 0.7:
                grave(t, rnd, head, z, toward, name=rnd.choice(["old_cross", "old_cross", "wooden_cross", "marble_stone", "roofed_cross"]),
                      overgrown=rnd.random() < 0.7)
            elif roll < 0.85:
                t.set(head + toward, 1, z, "lilac", half="lower"); t.set(head + toward, 2, z, "lilac", half="upper")
            else:
                t.tree_spots.append((head + toward, z))
            for dx in range(3):
                plants(t, rnd, head + dx * toward, z, True)


def trees(t, rnd, count):
    """A few trees here and there, never in rows: one per eight to twelve plots, at spots no grave or fence took."""
    # A crown reaches 3 blocks around the trunk and must stay inside this piece, or the jigsaw drops the tree.
    spots = [(x, z) for x, z in t.tree_spots if 3 <= x <= W - 4 and 3 <= z <= t.depth - 4
             and (t.free(x, z) or t.get(x, 1, z) in ("minecraft:short_grass", "minecraft:tall_grass"))]
    rnd.shuffle(spots)
    placed = []
    for x, z in spots:
        if len(placed) >= count: break
        if any(abs(x - px) + abs(z - pz) < 7 for px, pz in placed): continue
        for y in range(1, 3): t.set(x, y, z, "air")
        jigsaw(t, x, 0, z, "up_north", "minecraft:empty", f"{NS}:cemetery/trees", "minecraft:bottom", "minecraft:grass_block")
        placed.append((x, z))
    return len(placed)


def piece(seed, depth, entrance, exit_to=None, kind="rows", style=MODERN):
    """One stretch of the cemetery along the path. entrance: the name of the jigsaw at (10, 0, 0) facing north; exit_to:
    (pool, target) of the jigsaw at the far end of the path facing south."""
    global STYLE
    STYLE = style
    rnd = random.Random(seed)
    t = Template(depth)
    t.tree_spots = []
    stats = {"plots": 0, "abandoned": 0}
    for x in range(W):
        for z in range(depth):
            for y in range(1, CLEAR): t.set(x, y, z, "air")
    path_end = depth - 1 if exit_to else 3
    for z in range(path_end + 1):
        for x in PATH: ground(t, x, z, rnd, "path" if x == PATH_X else "edge")
    if kind == "end":
        lane(t, rnd, 0)
        old_graves_row(t, rnd, 1)
        old_graves_row(t, rnd, 3)
        monument(t, PATH_X, 4, "roofed_cross", "north")  # a tall memorial cross where the path ends
        for x in range(W):
            for z in (2, 5, 6):
                if x not in PATH or z > 4: plants(t, rnd, x, z, True)
        stats["trees"] = 0  # too shallow for a crown; lilacs stand here instead
    else:
        lane(t, rnd, 0)
        for zs in range(1, depth - 6, 8):
            for side in (-1, 1): block_of_plots(t, rnd, zs, side, stats)
            if zs + 7 < depth: lane(t, rnd, zs + 7)
        if kind == "start":
            old_graves_row(t, rnd, 25)
            for x in range(W): plants(t, rnd, x, 26, rnd.random() < 0.4)
        # A blue sector sign by the path at one of the lanes, and a bin at the first one.
        lanes = [z for z in range(0, depth, 8)]
        sz = rnd.choice(lanes[1:] or lanes)
        sx, sf = rnd.choice(((8, "east"), (12, "west")))
        monument(t, sx, sz, "sector_sign", sf)
        if kind == "start" or rnd.random() < 0.4:
            bx = rnd.choice((7, 13))
            t.set(bx, 1, 0, NS + ":cemetery_bin", facing="north", waterlogged=False)
        stats["trees"] = trees(t, rnd, rnd.choice(STYLE["trees"]))
    jigsaw(t, PATH_X, 0, 0, "north_up", entrance, "minecraft:empty", "minecraft:empty", "minecraft:coarse_dirt")
    if exit_to:
        jigsaw(t, PATH_X, 0, depth - 1, "south_up", f"{NS}:cemetery_next", exit_to[0], exit_to[1], "minecraft:coarse_dirt", priority=10)
    connect(t)
    return t, stats


class TreeTemplate(Template):
    """A tree on its own small template, trunk at (R, 0, R); a jigsaw at the trunk's foot hooks it onto the ground jigsaw."""
    def __init__(self, radius, height):
        super().__init__(2 * radius + 1)
        self.radius, self.height, self.width = radius, height, 2 * radius + 1

    def set(self, x, y, z, name, nbt_data=None, **props):
        if 0 <= x < self.width and 0 <= y < self.height and 0 <= z < self.width:
            self.blocks[(x, y, z)] = (name if ":" in name else "minecraft:" + name, {k: str(v).lower() for k, v in props.items()}, nbt_data)

    def write(self, path):
        global W, H
        saved = W, H
        W, H = self.width, self.height
        try: super().write(path)
        finally: W, H = saved


def leaves(t, x, y, z, kind):
    if t.get(x, y, z) is None:
        t.set(x, y, z, kind + "_leaves", distance=1, persistent=True, waterlogged=False)


def tree(kind, seed):
    """Hand-rolled shapes in the vanilla spirit: a round oak, a tall thin birch, a spruce cone, a pine with a high crown."""
    rnd = random.Random(seed)
    if kind == "oak":
        trunk = rnd.randint(5, 7); r = 3; t = TreeTemplate(r, trunk + 3)
        log, leaf = "oak_log", "oak"
        for y in range(trunk - 3, trunk + 2):
            rad = 3 if y < trunk else 2 if y == trunk else 1
            for dx in range(-rad, rad + 1):
                for dz in range(-rad, rad + 1):
                    if abs(dx) == rad and abs(dz) == rad and rnd.random() < 0.7: continue
                    if dx * dx + dz * dz > rad * rad + 1 and rnd.random() < 0.5: continue
                    leaves(t, r + dx, y, r + dz, leaf)
    elif kind == "birch":
        trunk = rnd.randint(8, 11); r = 2; t = TreeTemplate(r, trunk + 2)
        log, leaf = "birch_log", "birch"
        for y in range(trunk - 4, trunk + 2):
            rad = 2 if y < trunk - 1 else 1
            for dx in range(-rad, rad + 1):
                for dz in range(-rad, rad + 1):
                    if abs(dx) == rad and abs(dz) == rad and (rad == 1 or rnd.random() < 0.6): continue
                    leaves(t, r + dx, y, r + dz, leaf)
    elif kind == "spruce":
        trunk = rnd.randint(8, 11); r = 3; t = TreeTemplate(r, trunk + 2)
        log, leaf = "spruce_log", "spruce"
        rad = 0
        for y in range(trunk + 1, 1, -1):  # from the tip down, widening every other layer
            for dx in range(-rad, rad + 1):
                for dz in range(-rad, rad + 1):
                    if abs(dx) + abs(dz) > rad + (1 if rad > 1 else 0): continue
                    leaves(t, r + dx, y, r + dz, leaf)
            if rad == 0 or (trunk + 1 - y) % 2 == 1: rad = min(3, rad + 1)
            elif rad > 1 and rnd.random() < 0.5: rad -= 1
        t.set(r, trunk + 1, r, "spruce_leaves", distance=1, persistent=True, waterlogged=False)
    else:  # pine
        trunk = rnd.randint(11, 14); r = 2; t = TreeTemplate(r, trunk + 2)
        log, leaf = "spruce_log", "spruce"
        for y in range(trunk - 3, trunk + 2):
            rad = 2 if trunk - 2 <= y < trunk else 1
            for dx in range(-rad, rad + 1):
                for dz in range(-rad, rad + 1):
                    if abs(dx) == rad and abs(dz) == rad: continue
                    leaves(t, r + dx, y, r + dz, leaf)
    for y in range(trunk):
        t.set(t.radius, y, t.radius, log, axis="y")
    # Leaves were placed around the trunk; the trunk wins where they met.
    jigsaw(t, t.radius, 0, t.radius, "down_north", "minecraft:bottom", "minecraft:empty", "minecraft:empty", f"minecraft:{log}[axis=y]")
    return t


TREE_KINDS = (("oak", False, 3), ("birch", False, 3), ("spruce", True, 3), ("pine", True, 2))


def write_trees():
    elements = []
    for kind, cold, variants in TREE_KINDS:
        for i in range(1, variants + 1):
            name = f"tree_{kind}_{i}"
            tree(kind, 500 + 101 * i + 7 * len(kind)).write(os.path.join(ROOT, "structure", "cemetery", name + ".nbt"))
            weight = {"oak": 2, "birch": 3, "spruce": 3, "pine": 2}[kind]
            elements.append({"element": {"element_type": "minecraft:single_pool_element", "location": f"{NS}:cemetery/{name}",
                                         "processors": f"{NS}:cemetery_{'conifer' if cold else 'broadleaf'}", "projection": "rigid"}, "weight": weight})
    wg = os.path.join(ROOT, "worldgen")
    save_json(os.path.join(wg, "template_pool", "cemetery", "trees.json"), {"fallback": "minecraft:empty", "elements": elements})
    for name, cold in (("conifer", True), ("broadleaf", False)):
        save_json(os.path.join(wg, "processor_list", f"cemetery_{name}.json"),
                  {"processors": [{"processor_type": f"{NS}:cemetery_tree", "cold": cold}]})
    for old in (os.path.join(wg, "configured_feature", "cemetery_tree.json"), os.path.join(wg, "placed_feature", "cemetery_tree.json")):
        if os.path.exists(old): os.remove(old)


def main():
    wg = os.path.join(ROOT, "worldgen")
    pools = {"start": [], "rows": [], "end": []}
    shapes = (("start", START_DEPTH, f"{NS}:cemetery_entrance", ("rows", f"{NS}:cemetery_rows"), (MODERN, MODERN, OLD)),
              ("rows", ROWS_DEPTH, f"{NS}:cemetery_rows", ("end", f"{NS}:cemetery_end"), (MODERN, OLD, OLD)),
              ("end", END_DEPTH, f"{NS}:cemetery_end", None, (OLD, OLD)))
    for n, (kind, depth, entrance, exit_to, styles) in enumerate(shapes):
        for i, style in enumerate(styles, 1):
            name = f"cemetery_{i}" if kind == "start" else f"{kind}_{i}"
            exit_pool = (f"{NS}:cemetery/{exit_to[0]}", exit_to[1]) if exit_to else None
            t, stats = piece(1000 * (n + 1) + i * 37, depth, entrance, exit_pool, kind, style)
            t.write(os.path.join(ROOT, "structure", "cemetery", name + ".nbt"))
            print(f"{name} ({'old' if style is OLD else 'modern'}): {W}x{depth}, plots {stats['plots']} (abandoned {stats['abandoned']}), trees {stats['trees']}")
            pools[kind].append({"element": {"element_type": "minecraft:single_pool_element", "location": f"{NS}:cemetery/{name}",
                                            "processors": f"{NS}:cemetery", "projection": "terrain_matching"}, "weight": 1})
    for kind, elements in pools.items():
        save_json(os.path.join(wg, "template_pool", "cemetery", kind + ".json"), {"fallback": "minecraft:empty", "elements": elements})
    write_trees()
    save_json(os.path.join(wg, "processor_list", "cemetery.json"), {"processors": [{"processor_type": "minecraft:rule", "rules": [
        {"input_predicate": {"predicate_type": "minecraft:random_block_match", "block": "minecraft:gravel", "probability": 0.12},
         "location_predicate": {"predicate_type": "minecraft:always_true"}, "output_state": {"Name": "minecraft:coarse_dirt"}},
        {"input_predicate": {"predicate_type": "minecraft:block_match", "block": "minecraft:gravel"},
         "location_predicate": {"predicate_type": "minecraft:block_match", "block": "minecraft:water"}, "output_state": {"Name": "minecraft:spruce_planks"}},
    ]}]})
    save_json(os.path.join(wg, "structure", "cemetery.json"), {
        "type": "minecraft:jigsaw", "biomes": "#minecraft:is_overworld", "step": "surface_structures", "spawn_overrides": {},
        "terrain_adaptation": "beard_thin", "start_pool": f"{NS}:cemetery/start", "size": 4,
        "start_height": {"absolute": 0}, "project_start_to_heightmap": "WORLD_SURFACE_WG", "max_distance_from_center": 80,
        "use_expansion_hack": False})
    print("cemetery structures written")


if __name__ == "__main__":
    main()
