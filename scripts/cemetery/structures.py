"""Generates the village cemetery templates (data/divineblock/structure/cemetery/*.nbt), their jigsaw pools, the birch feature
and a standalone test structure. Each variant is laid out from its own seed: a gravel alley from the entrance, a cross path,
fenced family plots on both sides with crosses, monuments, mounds, benches and tables, abandoned overgrown plots, old
unfenced graves at the back, lilacs and birches. Run from the repository root: python scripts/cemetery/structures.py

Template frame (before the jigsaw rotates it): x 0..20 across, z 0..26 deep, y 0 is the ground layer (paths replace the top
block, everything else stands on y 1). The entrance jigsaw sits at (10, 0, 0) facing north."""
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(__file__))
import nbt  # noqa: E402

ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "data", "divineblock")
NS = "divineblock"
W, H, D = 21, 8, 27
ALLEY = (9, 11)
CROSS_PATH_Z = 13
DATA_VERSION = 3955
VARIANTS = 3


def save_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2); f.write("\n")


class Template:
    def __init__(self):
        self.blocks = {}   # (x, y, z) -> (name, props, nbt)

    def set(self, x, y, z, name, nbt_data=None, **props):
        if 0 <= x < W and 0 <= y < H and 0 <= z < D:
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
            if data: b["nbt"] = nbt.Compound({k: nbt.String(v) for k, v in data.items()})
            blocks.append(nbt.Compound(b))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        nbt.write(path, {
            "size": nbt.List(nbt.INT, [nbt.Int(W), nbt.Int(H), nbt.Int(D)]),
            "entities": nbt.List(nbt.COMPOUND, []),
            "blocks": nbt.List(nbt.COMPOUND, blocks),
            "palette": nbt.List(nbt.COMPOUND, palette),
            "DataVersion": nbt.Int(DATA_VERSION),
        })


def jigsaw(t, x, y, z, orientation, name, pool, target, final_state, joint="rollable"):
    t.set(x, y, z, "jigsaw", {"id": "minecraft:jigsaw", "name": name, "pool": pool, "target": target,
                               "final_state": final_state, "joint": joint}, orientation=orientation)


MONUMENTS = [("wooden_cross", 30), ("iron_cross", 14), ("roofed_cross", 16), ("star_obelisk", 9), ("granite_stele", 21), ("marble_stone", 10)]
TALL = {"wooden_cross", "roofed_cross", "old_cross", "iron_cross", "star_obelisk"}


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
    monument(t, x_head, z, name or pick(rnd, MONUMENTS), facing)
    for dx in (1, 2):
        t.set(x_head + dx * toward, 1, z, NS + ":grave_mound", facing=facing, waterlogged=False, overgrown=overgrown)


def ground(t, x, z, rnd, kind):
    if kind == "gravel":
        t.set(x, 0, z, rnd.choices(["gravel", "coarse_dirt", "dirt_path"], [6, 3, 1])[0])
    elif kind == "path":
        t.set(x, 0, z, rnd.choices(["coarse_dirt", "dirt_path", "grass_block", "gravel"], [4, 3, 2, 1])[0])
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


def plot(t, rnd, x0, z0, depth, side, abandoned=False):
    """A fenced family plot next to the alley. side -1: west of the alley (gate on its east fence), +1: east of it."""
    w = 6
    xs = range(x0, x0 + w)
    gate_x = xs[-1] if side < 0 else xs[0]
    color = rnd.choice(["black", "black", "blue"])
    fence, gate = f"{NS}:{color}_grave_fence", f"{NS}:{color}_grave_gate"
    gate_z = z0 + depth // 2
    for x in xs:
        for z in range(z0, z0 + depth):
            edge = x in (xs[0], xs[-1]) or z in (z0, z0 + depth - 1)
            if not edge:
                if not abandoned and rnd.random() < 0.85: ground(t, x, z, rnd, "plot")
                continue
            if x == gate_x and z == gate_z:
                t.set(x, 1, z, gate, facing="east" if side < 0 else "west", open=abandoned and rnd.random() < 0.6, in_wall=False, powered=False)
            elif abandoned and rnd.random() < 0.3:
                plants(t, rnd, x, z, True)
            else:
                t.set(x, 1, z, fence, north=False, south=False, east=False, west=False, waterlogged=False)
    # Inside: graves at the back, a bench and a table by the gate, flowers, a wreath and a lampada beside the graves.
    toward = -side  # graves point toward the alley
    head = xs[1] if side < 0 else xs[-2]
    rows = list(range(z0 + 1, z0 + depth - 1))
    graves = rows[:2] if len(rows) >= 3 and rnd.random() < 0.6 else rows[:1]
    if abandoned:
        graves = rows[:rnd.randint(1, len(rows) - 1)]
    for z in graves:
        name = "old_cross" if abandoned and rnd.random() < 0.7 else None
        grave(t, rnd, head, z, toward, name=name, overgrown=abandoned)
    near_gate = gate_x - 1 if side < 0 else gate_x + 1  # the column just inside the gate
    spare = [z for z in rows if z not in graves]
    if not abandoned:
        bench_z = rows[0] if rows[0] != gate_z else rows[-1]
        if t.free(near_gate, bench_z):
            t.set(near_gate, 1, bench_z, NS + ":grave_bench", facing="north" if rnd.random() < 0.5 else "south", waterlogged=False)
        if rnd.random() < 0.6:
            tz = rows[-1] if bench_z != rows[-1] else rows[0]
            if t.free(near_gate, tz) and tz != gate_z:
                t.set(near_gate, 1, tz, NS + ":grave_table", facing="north", waterlogged=False)
        for z in spare:
            for x, item in ((head, "funeral_wreath"), (head + toward, "grave_lampada"), (head + 2 * toward, "artificial_flowers")):
                if t.free(x, z) and rnd.random() < 0.75:
                    t.set(x, 1, z, NS + ":" + item, facing="east" if toward > 0 else "west", waterlogged=False)
        if not spare:
            z = graves[0]
            if t.free(near_gate, z): t.set(near_gate, 1, z, NS + ":artificial_flowers", facing="north", waterlogged=False)
    for x in xs:
        for z in range(z0, z0 + depth):
            plants(t, rnd, x, z, abandoned)


def layout(seed):
    rnd = random.Random(seed)
    t = Template()
    for x in range(W):
        for z in range(D):
            for y in range(1, H): t.set(x, y, z, "air")
    for z in range(D):
        for x in range(ALLEY[0], ALLEY[1] + 1): ground(t, x, z, rnd, "gravel")
    for x in range(1, W - 1):
        if not ALLEY[0] <= x <= ALLEY[1]: ground(t, x, CROSS_PATH_Z, rnd, "path")
    jigsaw(t, 10, 0, 0, "north_up", f"{NS}:cemetery_entrance", "minecraft:empty", "minecraft:empty", "minecraft:gravel")

    abandoned_left = rnd.randrange(4)
    n = 0
    for side, x0 in ((-1, 3), (1, 12)):
        for z_from, z_to in ((1, CROSS_PATH_Z - 1), (CROSS_PATH_Z + 1, D - 1)):
            z = z_from
            while z_to - z >= 4:
                depth = min(rnd.choice([5, 5, 6]), z_to - z)
                if depth < 5 and z_to - z >= 5: depth = 5
                if depth < 4: break
                if rnd.random() < 0.12:
                    # an unfenced family row instead of a plot
                    for zz in range(z + 1, z + depth - 1, 2):
                        grave(t, rnd, x0 + 1 if side < 0 else x0 + 4, zz, -side)
                else:
                    plot(t, rnd, x0, z, depth, side, abandoned=(n == abandoned_left or rnd.random() < 0.12))
                    n += 1
                z += depth + 1

    # Old unfenced graves along the back edges, lilacs and birches between them.
    for x, toward in ((0, 1), (W - 1, -1)):
        z = 1
        while z < D - 1:
            roll = rnd.random()
            if z == CROSS_PATH_Z: z += 1; continue
            if roll < 0.45 and t.free(x, z) and t.free(x + toward, z) and t.free(x + 2 * toward, z):
                name = rnd.choice(["old_cross", "old_cross", "wooden_cross", "marble_stone"])
                grave(t, rnd, x, z, toward, name=name, overgrown=rnd.random() < 0.7)
                z += 2
            elif roll < 0.6 and t.free(x, z):
                t.set(x, 1, z, "lilac", half="lower"); t.set(x, 2, z, "lilac", half="upper")
                z += 2
            else:
                z += 1
    birches = 0
    spots = [(x, z) for x in (0, 1, 2, W - 3, W - 2, W - 1, 8, 12) for z in range(1, D - 1)]
    rnd.shuffle(spots)
    for x, z in spots:
        if birches >= 5: break
        if not t.free(x, z) or t.get(x, 0, z) is not None: continue
        if any(t.get(x + dx, 1, z + dz) not in (None, "minecraft:air", "minecraft:short_grass") for dx in (-1, 0, 1) for dz in (-1, 0, 1)): continue
        jigsaw(t, x, 0, z, "up_north", "minecraft:empty", f"{NS}:cemetery/trees", "minecraft:bottom", "minecraft:grass_block")
        birches += 1
    for x in range(W):
        for z in range(D):
            if t.get(x, 0, z) is None: plants(t, rnd, x, z, x in (0, W - 1) or rnd.random() < 0.15)
    connect(t)
    return t


def main():
    pool = []
    for i in range(1, VARIANTS + 1):
        layout(1000 + i * 37).write(os.path.join(ROOT, "structure", "cemetery", f"cemetery_{i}.nbt"))
        pool.append({"element": {"element_type": "minecraft:single_pool_element", "location": f"{NS}:cemetery/cemetery_{i}",
                                 "processors": f"{NS}:cemetery", "projection": "terrain_matching"}, "weight": 1})
    wg = os.path.join(ROOT, "worldgen")
    save_json(os.path.join(wg, "template_pool", "cemetery", "start.json"), {"fallback": "minecraft:empty", "elements": pool})
    save_json(os.path.join(wg, "template_pool", "cemetery", "trees.json"), {"fallback": "minecraft:empty", "elements": [
        {"element": {"element_type": "minecraft:feature_pool_element", "feature": f"{NS}:cemetery_birch", "projection": "rigid"}, "weight": 1}]})
    save_json(os.path.join(wg, "placed_feature", "cemetery_birch.json"), {"feature": "minecraft:birch", "placement": []})
    save_json(os.path.join(wg, "processor_list", "cemetery.json"), {"processors": [{"processor_type": "minecraft:rule", "rules": [
        {"input_predicate": {"predicate_type": "minecraft:random_block_match", "block": "minecraft:gravel", "probability": 0.12},
         "location_predicate": {"predicate_type": "minecraft:always_true"}, "output_state": {"Name": "minecraft:coarse_dirt"}},
        {"input_predicate": {"predicate_type": "minecraft:block_match", "block": "minecraft:gravel"},
         "location_predicate": {"predicate_type": "minecraft:block_match", "block": "minecraft:water"}, "output_state": {"Name": "minecraft:spruce_planks"}},
    ]}]})
    save_json(os.path.join(wg, "structure", "cemetery.json"), {
        "type": "minecraft:jigsaw", "biomes": "#minecraft:is_overworld", "step": "surface_structures", "spawn_overrides": {},
        "terrain_adaptation": "beard_thin", "start_pool": f"{NS}:cemetery/start", "size": 2,
        "start_height": {"absolute": 0}, "project_start_to_heightmap": "WORLD_SURFACE_WG", "max_distance_from_center": 40,
        "use_expansion_hack": False})
    print("cemetery structures written")


if __name__ == "__main__":
    main()
