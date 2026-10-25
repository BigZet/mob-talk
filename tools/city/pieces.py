"""Mob Talk's own city pieces and structures on top of the imported Soviet templates (tools/city/import_tav.py).

Every city starts at its administration (the city hall of the imported set) and the first street out of it carries the
hospital, so each city has both. Writes into src/main/resources/data/mobtalk/:
  structure/city/ours/...              modified copies of imported templates
  worldgen/template_pool/city/ours/... their pools
  worldgen/structure/city_*.json       the city structures (placed by io.github.mobtalk.game.CityWorldgen)
  tags/worldgen/biome/has_structure/city.json

    python3 -I tools/city/pieces.py
"""
import copy
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nbt  # noqa: E402
import yards  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "mobtalk")

# Kind: (admin centre template, street pool that leaves it, long street template of that pool, processors of the street).
CITIES = {
    "small": ("centre/meriya_5th", "5th_brick/street", "streets/5th_brick/road/street_6", "mobtalk:city/village_common"),
    "mix": ("medium_mix/center/meriya", "medium_mix/street/smooth_stone", "medium_mix/street/smooth_stone/street_7",
            "mobtalk:city/village_cement_street"),
    "brick": ("medium_brick/center/meriya", "medium_brick/street/cobblestone", "medium_brick/street/cobblestone/street_7",
              "mobtalk:city/village_cement_street"),
    "ninth": ("medium_9th/center/soviet_rada", "medium_9th/street/polished_andesite",
              "medium_9th/street/polished_andesite/street_6", "mobtalk:city/village_cement_street"),
}
HOSPITAL_POOL = "mobtalk:city/ours/hospital"
BIOMES = ["minecraft:plains", "minecraft:sunflower_plains", "minecraft:meadow", "minecraft:forest", "minecraft:flower_forest",
          "minecraft:birch_forest", "minecraft:old_growth_birch_forest", "minecraft:taiga", "minecraft:snowy_plains",
          "minecraft:snowy_taiga"]


def load(rel):
    with open(os.path.join(DATA, "structure", "city", rel + ".nbt"), "rb") as f:
        return nbt.load(f.read())


def save(rel, name, tag):
    dest = os.path.join(DATA, "structure", "city", rel + ".nbt")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "wb") as f:
        f.write(nbt.dump(name, tag))


def write_json(rel, data):
    dest = os.path.join(DATA, rel)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def jigsaws(tag):
    """(block compound, palette properties, jigsaw nbt) of every jigsaw block in the template."""
    pal = tag.value["palette"].value
    for b in tag.value["blocks"].value:
        p = pal[b.value["state"].value].value
        if p["Name"].value == "minecraft:jigsaw" and "nbt" in b.value:
            yield b, p["Properties"].value, b.value["nbt"].value


def pos(b):
    return [x.value for x in b.value["pos"].value]


def state_index(tag, name):
    """The palette index of a plain block state, added when missing."""
    pal = tag.value["palette"].value
    for i, p in enumerate(pal):
        if p.value["Name"].value == name and "Properties" not in p.value:
            return i
    pal.append(nbt.c(Name=nbt.Tag(nbt.STRING, name)))
    return len(pal) - 1


def pool(name, location, processors, projection="rigid", legacy=False):
    return {"name": name, "fallback": "minecraft:empty", "elements": [{"weight": 1, "element": {
        "element_type": "minecraft:legacy_single_pool_element" if legacy else "minecraft:single_pool_element",
        "location": location, "processors": processors, "projection": projection}}]}


def hospital_street(kind, street, processors):
    """The long street with one building plot on its west side kept for the hospital; the east side keeps its houses."""
    name, tag = load(street)
    length = tag.value["size"].value[2].value
    west = [(b, props, d) for b, props, d in jigsaws(tag) if props["orientation"].value == "west_up"
            and d["name"].value.startswith("minecraft:building_entrance")]
    middle = min(west, key=lambda j: abs(pos(j[0])[2] - length // 2))
    air = state_index(tag, "minecraft:air")
    keep = []
    for b in tag.value["blocks"].value:
        hit = next((j for j in west if j[0] is b), None)
        if hit is None:
            keep.append(b)
        elif b is middle[0]:
            d = b.value["nbt"].value
            d["pool"].value = HOSPITAL_POOL
            d["target"].value = "minecraft:building_entrance"
            d["placement_priority"] = nbt.Tag(nbt.INT, 10)  # before the neighbours' plots take the room
            keep.append(b)
        else:  # the other plots on that side stay empty so the hospital fits
            b.value["state"].value = air
            del b.value["nbt"]
            keep.append(b)
    tag.value["blocks"].value = keep
    rel = f"ours/{kind}/hospital_street"
    save(rel, name, tag)
    write_json(f"worldgen/template_pool/city/ours/{kind}/hospital_street.json",
               pool(f"mobtalk:city/ours/{kind}/hospital_street", f"mobtalk:city/{rel}", processors, "terrain_matching", True))


def admin_centre(kind, centre):
    """The city hall centre whose first street is the hospital street."""
    name, tag = load(centre)
    street = next(d for _, _, d in jigsaws(tag) if "street" in d["name"].value)
    street["pool"].value = f"mobtalk:city/ours/{kind}/hospital_street"
    street["placement_priority"] = nbt.Tag(nbt.INT, 10)  # the hospital street (and so the hospital) is laid out first
    save(f"ours/{kind}/admin_centre", name, tag)
    write_json(f"worldgen/template_pool/city/ours/{kind}/start.json",
               pool(f"mobtalk:city/ours/{kind}/start", f"mobtalk:city/ours/{kind}/admin_centre", "mobtalk:city/village_common"))


def structure(kind):
    write_json(f"worldgen/structure/city_{kind}.json", {
        "type": "minecraft:jigsaw",
        "biomes": "#mobtalk:has_structure/city",
        "step": "surface_structures",
        "spawn_overrides": {},
        "terrain_adaptation": "beard_thin",
        "start_pool": f"mobtalk:city/ours/{kind}/start",
        "size": 7,
        "start_height": {"absolute": 0},
        "project_start_to_heightmap": "WORLD_SURFACE_WG",
        "max_distance_from_center": 100,
        "use_expansion_hack": True,
    })


# ---------------------------------------------------------------- decoration of the imported pieces

DIRS = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}
SOLID_SKIP = ("air", "glass", "pane", "door", "jigsaw", "structure_void", "bars", "fence", "leaves", "torch", "lantern", "flower",
              "grass", "fern", "slab", "stairs", "trapdoor", "carpet", "sign", "banner", "button", "rail", "chain", "wall")


class Template:
    """A structure template as a grid of block names and properties, which decorations can be added to."""

    def __init__(self, rel):
        self.rel = rel
        self.name, self.tag = load(rel)
        v = self.tag.value
        self.size = [x.value for x in v["size"].value]
        self.palette = v["palette"].value
        self.grid = {}
        for b in v["blocks"].value:
            p = self.palette[b.value["state"].value].value
            props = {k: x.value for k, x in p["Properties"].value.items()} if "Properties" in p else {}
            self.grid[tuple(pos(b))] = (p["Name"].value, props, b)
        self.added = 0

    def name_at(self, x, y, z):
        cell = self.grid.get((x, y, z))
        return cell[0] if cell else None

    def air(self, x, y, z):
        return self.name_at(x, y, z) in ("minecraft:air", "minecraft:cave_air")

    def solid(self, x, y, z):
        n = self.name_at(x, y, z)
        return n is not None and not any(k in n for k in SOLID_SKIP)

    def state(self, name, props=None):
        for i, p in enumerate(self.palette):
            if p.value["Name"].value != name:
                continue
            have = {k: x.value for k, x in p.value["Properties"].value.items()} if "Properties" in p.value else {}
            if have == (props or {}):
                return i
        entry = nbt.c(Name=nbt.Tag(nbt.STRING, name))
        if props:
            entry.value["Properties"] = nbt.c(**{k: nbt.Tag(nbt.STRING, v) for k, v in props.items()})
        self.palette.append(entry)
        return len(self.palette) - 1

    def put(self, x, y, z, name, props=None, block_nbt=None):
        """Sets a cell (replacing what the template had there)."""
        if not (0 <= x < self.size[0] and 0 <= y < self.size[1] and 0 <= z < self.size[2]):
            return False
        block = nbt.c(pos=nbt.Tag(nbt.LIST, [nbt.Tag(nbt.INT, x), nbt.Tag(nbt.INT, y), nbt.Tag(nbt.INT, z)], nbt.INT),
                      state=nbt.Tag(nbt.INT, self.state(name, props)))
        if block_nbt is not None:
            block.value["nbt"] = block_nbt
        old = self.grid.get((x, y, z))
        blocks = self.tag.value["blocks"].value
        if old:
            blocks[blocks.index(old[2])] = block
        else:
            blocks.append(block)
        self.grid[(x, y, z)] = (name, props or {}, block)
        self.added += 1
        return True

    def open_sky(self, x, y, z, height=6):
        return all(self.air(x, y + k, z) or (y + k >= self.size[1]) for k in range(height))

    def ray_out(self, x, y, z, dx, dz):
        """Open air (or connectors) from here to the template's edge: a porch under a roof still faces the street."""
        while True:
            n = self.name_at(x, y, z)
            if n is None:
                return True
            if n not in ("minecraft:air", "minecraft:cave_air", "minecraft:jigsaw", "minecraft:structure_void"):
                return False
            x, z = x + dx, z + dz

    def save(self):
        save(self.rel, self.name, self.tag)


def sign_nbt(lines, wood="dark_oak"):
    msgs = [json.dumps({"text": t}, ensure_ascii=False) for t in (lines + ["", "", "", ""])[:4]]
    text = nbt.c(messages=nbt.Tag(nbt.LIST, [nbt.Tag(nbt.STRING, m) for m in msgs], nbt.STRING),
                 color=nbt.Tag(nbt.STRING, "black"), has_glowing_text=nbt.Tag(nbt.BYTE, 0))
    back = nbt.c(messages=nbt.Tag(nbt.LIST, [nbt.Tag(nbt.STRING, '""')] * 4, nbt.STRING),
                 color=nbt.Tag(nbt.STRING, "black"), has_glowing_text=nbt.Tag(nbt.BYTE, 0))
    return nbt.c(id=nbt.Tag(nbt.STRING, "minecraft:sign"), front_text=text, back_text=back, is_waxed=nbt.Tag(nbt.BYTE, 1))


def entrance_y(t):
    ys = [p[1] for p, (n, props, b) in t.grid.items() if n == "minecraft:jigsaw" and "nbt" in b.value
          and "building_entrance" in b.value["nbt"].value["name"].value]
    return min(ys) if ys else None


def street_doors(t, ey):
    """Ground floor doors that open to the open air: (door cell, direction to the outside)."""
    out = []
    for (x, y, z), (n, props, _) in t.grid.items():
        if not n.endswith("_door") or props.get("half") != "lower" or abs(y - ey) > 1:
            continue
        axis = props.get("facing")
        for d in ((axis, OPPOSITE[axis]) if axis in DIRS else ()):
            dx, dz = DIRS[d]
            if t.air(x + dx, y, z + dz) and t.solid(x + dx, y - 1, z + dz) and (
                    t.open_sky(x + dx, y, z + dz) or t.ray_out(x + dx, y, z + dz, dx, dz)):
                out.append(((x, y, z), d))
                break
    return out


def decorate_building(t, r, admin=False, hospital=False):
    """Benches and a bin by each street door, a notice board beside it, air conditioners and dishes on the walls; at the
    administration a flag and a sign, at the hospital the red cross and a sign."""
    ey = entrance_y(t)
    if ey is None:
        ey = 1  # a town centre: its ground floor is level with the streets' connectors
    doors = street_doors(t, ey)
    done = set()
    signed = False
    for (x, y, z), out in doors:
        if (x // 3, z // 3) in done:  # double doors: one set of benches
            continue
        done.add((x // 3, z // 3))
        dx, dz = DIRS[out]
        ox, oz = x + dx, z + dz  # the cell outside the door
        side = (dz, dx) if dx == 0 else (dz, dx)
        along = [(-dz, dx), (dz, -dx)]  # both ways along the wall
        wall_facing = out
        # a notice board on the wall next to the door, at eye level
        for ax, az in along:
            bx, bz = ox + ax, oz + az
            if t.air(bx, y + 1, bz) and t.solid(bx - dx, y + 1, bz - dz):
                t.put(bx, y + 1, bz, "mobtalk:notice_board", {"facing": wall_facing})
                break
        if (admin or hospital) and not signed:
            spots = [(ox + ax * k, y + h, oz + az * k) for h in (1, 2) for k in (2, 3, 1) for ax, az in along]
            for bx, by, bz in spots:
                if t.air(bx, by, bz) and t.solid(bx - dx, by, bz - dz):
                    t.put(bx, by, bz, "minecraft:dark_oak_wall_sign", {"facing": wall_facing, "waterlogged": "false"},
                          sign_nbt(["Администрация", "города"] if admin else ["Городская", "больница"]))
                    signed = True
                    break
        # a bench on each side of the door, backs to the wall, and a bin beside one of them
        for i, (ax, az) in enumerate(along):
            for k in (2, 3):
                bx, bz = ox + ax * k, oz + az * k
                if t.air(bx, y, bz) and t.solid(bx, y - 1, bz) and t.air(bx, y + 1, bz):
                    t.put(bx, y, bz, "mobtalk:bench", {"facing": out})
                    ux, uz = bx + ax, bz + az
                    if i == 0 and t.air(ux, y, uz) and t.solid(ux, y - 1, uz):
                        t.put(ux, y, uz, "mobtalk:urn", {"facing": out})
                    break
    if admin:
        place_flag(t, ey, r)
    if hospital:
        place_cross(t, ey)
    # air conditioners and satellite dishes on the outer walls above the ground floor
    sx, sy, sz = t.size
    for (x, y, z), (n, props, _) in list(t.grid.items()):
        if y < ey + 4 or not t.solid(x, y, z) or "glass" in n:
            continue
        for d, (dx, dz) in DIRS.items():
            ax, az = x + dx, z + dz
            if not (t.air(ax, y, az) and t.air(ax, y - 1, az) and t.air(ax + dx, y, az + dz) and t.air(ax, y + 1, az)):
                continue
            # only on a wall that faces out of the building: open air all the way to the template's edge
            reach = 1
            while t.air(ax + dx * reach, y, az + dz * reach):
                reach += 1
            if t.name_at(ax + dx * reach, y, az + dz * reach) is not None:
                continue
            roll = r.random()
            if roll < 0.035:
                t.put(ax, y, az, "mobtalk:air_conditioner", {"facing": d})
            elif roll < 0.045:
                t.put(ax, y, az, "mobtalk:satellite_dish", {"facing": d})


def place_cross(t, ey):
    """One big red cross on the hospital's front (the west wall, toward the street), in the middle, a floor under the roof."""
    sx, sy, sz = t.size
    spots = []
    for z in range(sz):
        wall = []
        for y in range(ey + 2, sy):
            x = 0
            while x < sx and not t.solid(x, y, z):
                x += 1
            if 0 < x < sx and t.air(x - 1, y, z) and t.ray_out(x - 1, y, z, -1, 0):
                wall.append((y, x - 1))
        if len(wall) >= 3:
            face = max({x for _, x in wall}, key=[x for _, x in wall].count)  # the plain wall, not a cornice or a porch
            ys = [y for y, x in wall if x == face]
            y = ys[-1] - 1 if ys[-1] - 1 in ys else ys[-1]  # under the roof line
            spots.append((abs(z - sz / 2), -y, face, y, z))
    if spots:
        _, _, x, y, z = min(spots)
        t.put(x, y, z, "mobtalk:red_cross", {"facing": "west"})


def place_flag(t, ey, r):
    """The Russian flag on a pole on the square in front of the administration: the open paved cell nearest the middle."""
    sx, sy, sz = t.size
    best = None
    for (x, y, z), (n, props, _) in t.grid.items():
        if y != ey - 1 or not t.solid(x, y, z) or not t.open_sky(x, y + 1, z, 5):
            continue
        if any(not t.air(x + dx, y + 1, z + dz) for dx in (-1, 0, 1) for dz in (-1, 0, 1)):
            continue
        d = abs(x - sx / 2) + abs(z - sz / 2)
        if best is None or d < best[0]:
            best = (d, x, y, z)
    if best:
        _, x, y, z = best
        for k in (1, 2, 3):
            t.put(x, y + k, z, "mobtalk:flagpole", {"facing": "north"})
        t.put(x, y + 4, z, "mobtalk:flag", {"facing": r.choice(list(DIRS))})


def decorate_street(t, r):
    """Street lamps along the pavement, every twelve blocks, by turns on either side, arms over the road."""
    sx, sy, sz = t.size
    if sx != 5:
        return
    for z in range(4, sz - 3, 12):
        x, facing = (0, "east") if (z // 12) % 2 == 0 else (4, "west")
        if t.name_at(x, 0, z) is None or not t.solid(x, 0, z):
            continue
        cell = t.name_at(x, 1, z)
        if cell not in ("minecraft:air", "minecraft:jigsaw"):
            continue
        b = t.grid[(x, 1, z)][2] if cell == "minecraft:jigsaw" else None
        if b is not None and "building_entrance" not in b.value["nbt"].value["name"].value:
            continue  # never a street's own connector
        t.put(x, 1, z, "mobtalk:lamp_pole", {"facing": facing})
        t.put(x, 2, z, "mobtalk:lamp_pole", {"facing": facing})
        t.put(x, 3, z, "mobtalk:lamp_pole", {"facing": facing})
        t.put(x, 4, z, "mobtalk:street_lamp", {"facing": facing})


def decorate_all():
    root = os.path.join(DATA, "structure", "city")
    counts = {"buildings": 0, "streets": 0, "blocks": 0}
    for dp, _dn, fn in os.walk(root):
        for f in sorted(fn):
            rel = os.path.relpath(os.path.join(dp, f), root)[:-4].replace(os.sep, "/")
            if rel.startswith("ours/") and not rel.endswith(("admin_centre", "hospital_street")):
                continue
            t = Template(rel)
            names = set()
            for (n, props, b) in t.grid.values():
                if n == "minecraft:jigsaw" and "nbt" in b.value:
                    names.add(b.value["nbt"].value["name"].value)
            r = random.Random(rel)
            if any("street" in n or "cross" in n for n in names) and ("street" in rel or "road" in rel or "sidewalk" in rel):
                decorate_street(t, r)
                kind = "streets"
            elif any("building_entrance" in n for n in names) or rel.endswith("admin_centre"):
                decorate_building(t, r, admin=rel.endswith("admin_centre") or "meriya" in rel or "rada" in rel,
                                  hospital="hospital" in rel and "street" not in rel)
                kind = "buildings"
            else:
                continue
            if t.added:
                t.save()
                counts[kind] += 1
                counts["blocks"] += t.added
    print("decorated:", counts)


# ---------------------------------------------------------------- sign texts: Russian, roubles, nothing personal or political

SIGNS = {
    ("", "                   Ї Д", "", ""): ("", "                   Е Д", "", ""),
    ("", "А Л Ь Н Я             ", "", ""): ("", "Т И П О Г Р А Ф И Я", "", ""),
    ("", "BedComFORT", "", ""): ("", "Мебель", "", ""),
    ("", "CІЛЬПОМАРКЕТ", "", ""): ("", "УНИВЕРСАМ", "", ""),
    ("", "Youipun", "Великий", "Ушкотопец"): ("", "Памятник", "основателям", "города"),
    ("", "__banjamin", "Рыжий кот", ""): ("", "Памятник", "рыжему коту", ""),
    ("", "Бухгалтерія", "", ""): ("", "Бухгалтерия", "", ""),
    ("", "Білуга", "", ""): ("", "Белуга", "", ""),
    ("", "ЗАВОД", "БОМБ!", ""): ("", "ЗАВОД", "ЖБИ", ""),
    ("", "КАТРАН", "(рибна крамниця)", ""): ("", "РЫБА", "(магазин)", ""),
    ("", "КАФЕ", "ДНІПРОВСЬКЕ", ""): ("", "КАФЕ", "ВОЛЖСКОЕ", ""),
    ("", "ЗАВОД ПО", "МЕТАЛУРГИИ", ""): ("", "ЗАВОД ПО", "МЕТАЛЛУРГИИ", ""),
    ("", "ЛАБОРАТОРІЯ", "", ""): ("", "ЛАБОРАТОРИЯ", "", ""),
    ("", "ОПАСНЫЙ", "ТВАР!!!", ""): ("", "ОПАСНЫЙ", "ТОВАР!!!", ""),
    ("", "ПЛАТА", "1", ""): ("", "ПАЛАТА", "1", ""),
    ("", "СМІТТЯ", "", ""): ("", "МУСОР", "", ""),
    ("", "ТРІСКА", "", ""): ("", "ТРЕСКА", "", ""),
    ("", "ТУНЕЦЬ", "", ""): ("", "ТУНЕЦ", "", ""),
    ("", "Сайда", "", ""): ("", "Сайда", "", ""),
    ("+-------------+", "USSR", "VINYL", "+-------------+"): ("+-------------+", "ПЛАСТИНКИ", "МЕЛОДИЯ", "+-------------+"),
    ("Vyenya Kogtyv", "2018-2024", "R.I.P my cat :(", "#FreeUkraine"): ("Здесь жил", "кот Веня", "", ""),
    ("АЗОВСТАЛЬ", "КАЧЕСТВЕННАЯ", "СТАЛЬ", "СТРАНЫ"): ("МЕТКОМБИНАТ", "КАЧЕСТВЕННАЯ", "СТАЛЬ", "СТРАНЫ"),
    ("ДЕСЕРТИ", "ВІД", "30 грн", ""): ("ДЕСЕРТЫ", "ОТ", "60 руб.", ""),
    ("КАСА", "1", "", ""): ("КАССА", "1", "", ""),
    ("КАСА", "2", "", ""): ("КАССА", "2", "", ""),
    ("КАСА", "3", "", ""): ("КАССА", "3", "", ""),
    ("КАСА", "4", "", ""): ("КАССА", "4", "", ""),
    ("Металеве броня,", "Шкіряна броня,", "Підшивка", ""): ("Броня из железа,", "броня из кожи,", "подшивка", ""),
    ("САЛАТИ", "ВІД", "45 грн", ""): ("САЛАТЫ", "ОТ", "90 руб.", ""),
    ("СВІЖА", "ВИПІЧКА", "від", "35 грн"): ("СВЕЖАЯ", "ВЫПЕЧКА", "от", "70 руб."),
    ("СМАЖЕНЕ", "М'ЯСО", "від", "140 грн"): ("ЖАРЕНОЕ", "МЯСО", "от", "280 руб."),
    ("", "МОЛОКО", "СГУЩЁНОЕ", ""): ("", "МОЛОКО", "СГУЩЁННОЕ", ""),
    ("МОЛОКО", "СГУЩЁНОЕ", "", ""): ("МОЛОКО", "СГУЩЁННОЕ", "", ""),
}


def sign_line(raw):
    """(text, the parsed JSON component or None)"""
    try:
        v = json.loads(raw)
    except ValueError:
        return raw, None
    if isinstance(v, str):
        return v, None
    if isinstance(v, dict):
        return v.get("text", ""), v
    return raw, None


def russify_signs():
    root = os.path.join(DATA, "structure", "city")
    changed = 0
    for dp, _dn, fn in os.walk(root):
        for f in sorted(fn):
            rel = os.path.relpath(os.path.join(dp, f), root)[:-4].replace(os.sep, "/")
            name, tag = load(rel)
            dirty = False
            for b in tag.value["blocks"].value:
                if "nbt" not in b.value:
                    continue
                n = b.value["nbt"].value
                for side in ("front_text", "back_text"):
                    if side not in n:
                        continue
                    msgs = n[side].value["messages"].value
                    lines = [sign_line(m.value) for m in msgs]
                    new = SIGNS.get(tuple(t for t, _ in lines))
                    if new is None:
                        continue
                    for m, (_t, comp), text in zip(msgs, lines, new):
                        comp = dict(comp or {})
                        comp["text"] = text
                        m.value = json.dumps(comp, ensure_ascii=False)
                    dirty = True
            if dirty:
                save(rel, name, tag)
                changed += 1
    print("signs rewritten in", changed, "templates")


# House pools that streets hang their side connectors on, with the connector the houses there must have.
HOUSE_POOLS = {"5th_brick/house": "_5th", "medium_brick/house/variant_1": "", "small_5th/house/variant_1": "",
               "medium_9th/house/variant_1": "", "medium_mix/house/variant_1": "", "corgi/house/variant_1": ""}
YARD_SHARE = 0.25  # of the plots along a street


def add_yards():
    """Our courtyards between the houses: about a quarter of the plots, the three kinds equally."""
    built = yards.build_all(save)
    for rel, suffix in HOUSE_POOLS.items():
        path = os.path.join(DATA, "worldgen", "template_pool", "city", rel + ".json")
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        data["elements"] = [e for e in data["elements"] if "/ours/yard/" not in e["element"].get("location", "")]
        total = sum(e["weight"] for e in data["elements"])
        mine = [b for b in built if b.endswith(suffix) and (suffix or not b.endswith("_5th"))]
        weight = max(1, round(total * YARD_SHARE / (1 - YARD_SHARE) / len(mine)))
        for b in mine:
            for w in [150] * (weight // 150) + ([weight % 150] if weight % 150 else []):  # a pool weight is at most 150
                data["elements"].append({"weight": w, "element": {"location": "mobtalk:city/" + b, "processors": "minecraft:empty",
                                                                      "projection": "rigid", "element_type": "minecraft:single_pool_element"}})
        write_json("worldgen/template_pool/city/" + rel + ".json", data)
    print("yards:", len(built), "templates")


def main():
    write_json("worldgen/template_pool/city/ours/hospital.json",
               pool(HOSPITAL_POOL, "mobtalk:city/houses/hospital_a/main", "mobtalk:city/village_common"))
    for kind, (centre, _street_pool, street, processors) in CITIES.items():
        hospital_street(kind, street, processors)
        admin_centre(kind, centre)
        structure(kind)
    russify_signs()
    add_yards()
    decorate_all()
    write_json("tags/worldgen/biome/has_structure/city.json", {"replace": False, "values": BIOMES})
    # A city is a village to the game too (maps, /locate #village, Mob Talk's VillageSites).
    tag = os.path.join(ROOT, "src", "main", "resources", "data", "minecraft", "tags", "worldgen", "structure", "village.json")
    os.makedirs(os.path.dirname(tag), exist_ok=True)
    with open(tag, "w", encoding="utf-8") as f:
        json.dump({"replace": False, "values": [f"mobtalk:city_{k}" for k in CITIES]}, f, indent=2)
        f.write("\n")
    print("cities:", ", ".join(CITIES))


if __name__ == "__main__":
    main()
