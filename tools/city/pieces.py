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
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nbt  # noqa: E402

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


def main():
    write_json("worldgen/template_pool/city/ours/hospital.json",
               pool(HOSPITAL_POOL, "mobtalk:city/houses/hospital_a/main", "mobtalk:city/village_common"))
    for kind, (centre, _street_pool, street, processors) in CITIES.items():
        hospital_street(kind, street, processors)
        admin_centre(kind, centre)
        structure(kind)
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
