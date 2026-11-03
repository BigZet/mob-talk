"""Imports the Soviet city part of kogtyv-Towny and Village (MIT, (c) 2023-2025 kogtyv) into Mob Talk's own namespace.

TAV writes everything into the ``minecraft:`` namespace and replaces vanilla villages; we take only its ``village/ussr``
templates and pools (without the adobe village and the test pieces), move them to ``mobtalk:city/...``, and rewrite every
reference inside pools, processor lists and the templates' jigsaw blocks, so nothing of vanilla or CTOV is touched.

    python3 -I tools/city/import_tav.py path/to/kogtyv-TownyAndVillage-1.5.1-Fabric-1.21.jar

Writes into src/main/resources/data/mobtalk/ (structure/city, worldgen/template_pool/city, worldgen/processor_list/city,
loot_table/chests/city) and prints what it could not resolve. Run it again after a TAV update; our own pieces live elsewhere
(tools/city/pieces.py) and are not overwritten.
"""
import json
import os
import re
import shutil
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nbt  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "src", "main", "resources", "data", "mobtalk")

SRC_STRUCT = "data/minecraft/structure/village/ussr/"
SRC_POOL = "data/minecraft/worldgen/template_pool/village/ussr/"
# The adobe village is Central Asian rather than Russian; the test pieces are the author's sandbox.
SKIP = ("saman/", "village/saman/", "village/houses/", "testeficate/", "testificate/")

# Vanilla pools that TAV's pieces may point at and that exist without TAV.
VANILLA_POOLS = {
    "minecraft:village/plains/villagers", "minecraft:village/plains/decor", "minecraft:village/plains/trees",
    "minecraft:village/plains/streets", "minecraft:village/plains/houses", "minecraft:village/plains/terminators",
    "minecraft:village/common/iron_golem", "minecraft:village/common/animals", "minecraft:village/common/cats",
    "minecraft:village/taiga/villagers", "minecraft:village/snowy/villagers", "minecraft:empty",
}
# TAV's processor lists are in the minecraft namespace too; these are its own (not vanilla).
TAV_PROCESSORS = {"village_common", "village_cement_street", "set_air_flats", "set_cobblestone", "set_dirt", "set_stone",
                  "set_tuff", "patern_andesite", "patern_bunker_a", "patern_cobblestone", "farm_common", "ignore_barrier",
                  "set_air", "set_air_undeground", "set_stone_briques", "village_plains_streets", "village_taiga_streets"}
TAV_LOOT = {"village_plains_home": "home", "village_taiga_home": "taiga_home", "village_blacksmith": "blacksmith",
            "village_plains_iron_sequret": "iron_secret"}

unresolved = {}


def skip(rel):
    return rel.startswith(SKIP)


def rewrite(s, pools):
    """Maps one string from TAV's data to ours. Pools that exist neither in TAV's city nor in vanilla become empty."""
    if "minecraft:" not in s:
        return s
    for tav, ours in TAV_LOOT.items():
        s = s.replace(f"minecraft:chests/{tav}", f"mobtalk:chests/city/{ours}")
    s = s.replace("minecraft:chests/saman/divar1", "mobtalk:chests/city/home")
    s = s.replace("minecraft:village/plains/house/twobalkon9/flats", "mobtalk:city/houses/twobalkon9/flats")
    s = s.replace("minecraft:village/villagers/", "mobtalk:city/villagers/")
    s = s.replace("minecraft:village/ussr/", "mobtalk:city/")
    m = re.fullmatch(r"minecraft:([a-z_]+)", s)
    if m and m.group(1) in TAV_PROCESSORS:
        return "mobtalk:city/" + m.group(1)
    if m and m.group(1).startswith("roof_colour"):
        return "mobtalk:city/" + m.group(1)
    if re.fullmatch(r"minecraft:roof_colour/[a-z_]+", s):
        return s.replace("minecraft:", "mobtalk:city/")
    return s


def pool_ref(s, pools):
    """A pool reference that will not resolve becomes minecraft:empty (TAV leaves a few dangling ones)."""
    if s in pools or s in VANILLA_POOLS:
        return s
    if s.startswith(("mobtalk:city/", "minecraft:village/")):
        unresolved[s] = unresolved.get(s, 0) + 1
        return "minecraft:empty"
    return s


def main(jar):
    z = zipfile.ZipFile(jar)
    names = z.namelist()
    for sub in ("structure/city", "worldgen/template_pool/city", "worldgen/processor_list/city", "loot_table/chests/city"):
        shutil.rmtree(os.path.join(OUT, sub), ignore_errors=True)

    # Pools first: their names are what jigsaw blocks may point at.
    pool_files = {}
    for n in names:
        if n.endswith(".json") and n.startswith(SRC_POOL) and not skip(n[len(SRC_POOL):]):
            pool_files[n] = json.loads(z.read(n))
    for extra in ("data/minecraft/worldgen/template_pool/village/villagers/multi.json",
                  "data/minecraft/worldgen/template_pool/village/villagers/taiga.json",
                  "data/minecraft/worldgen/template_pool/village/villagers/taiga_specific.json",
                  "data/minecraft/worldgen/template_pool/village/plains/house/twobalkon9/flats.json"):
        pool_files[extra] = json.loads(z.read(extra))
    pools = set()
    for n, data in pool_files.items():
        # The id is the file's path; TAV's "name" fields are sometimes missing or stale.
        data["name"] = rewrite("minecraft:" + n.split("/template_pool/", 1)[1][:-5], None)
        pools.add(data["name"])

    def fix_json(o):
        if isinstance(o, dict):
            return {k: fix_json(v) for k, v in o.items()}
        if isinstance(o, list):
            return [fix_json(v) for v in o]
        if isinstance(o, str):
            return rewrite(o, pools)
        return o

    templates = set()
    for n in names:
        if n.endswith(".nbt") and n.startswith(SRC_STRUCT) and not skip(n[len(SRC_STRUCT):]):
            templates.add("mobtalk:city/" + n[len(SRC_STRUCT):-4])
        if n.endswith(".nbt") and n.startswith("data/minecraft/structure/village/villagers/"):
            templates.add("mobtalk:city/villagers/" + n[len("data/minecraft/structure/village/villagers/"):-4])

    for n, data in pool_files.items():
        data = fix_json(data)
        data["fallback"] = pool_ref(data.get("fallback", "minecraft:empty"), pools)
        kept = []
        for e in data["elements"]:
            loc = e["element"].get("location")
            if loc and loc.startswith("mobtalk:") and loc not in templates:
                unresolved["template " + loc] = unresolved.get("template " + loc, 0) + 1
                continue
            kept.append(e)
        data["elements"] = kept
        path = data["name"].split(":", 1)[1]
        dest = os.path.join(OUT, "worldgen", "template_pool", path + ".json")
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
            f.write("\n")

    count = 0
    for n in names:
        if not n.endswith(".nbt"):
            continue
        if n.startswith(SRC_STRUCT) and not skip(n[len(SRC_STRUCT):]):
            rel = n[len(SRC_STRUCT):]
        elif n.startswith("data/minecraft/structure/village/villagers/"):
            rel = "villagers/" + n[len("data/minecraft/structure/village/villagers/"):]
        else:
            continue
        name, tag = nbt.load(z.read(n))
        nbt.walk_strings(tag, lambda s: rewrite(s, pools))
        palette = tag.value["palette"].value
        for b in tag.value["blocks"].value:
            if palette[b.value["state"].value].value["Name"].value == "minecraft:jigsaw" and "nbt" in b.value:
                d = b.value["nbt"].value
                d["pool"].value = pool_ref(d["pool"].value, pools)
        dest = os.path.join(OUT, "structure", "city", rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "wb") as f:
            f.write(nbt.dump(name, tag))
        count += 1

    for p in TAV_PROCESSORS:
        src = f"data/minecraft/worldgen/processor_list/{p}.json"
        if src in names:
            dest = os.path.join(OUT, "worldgen", "processor_list", "city", p + ".json")
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "w", encoding="utf-8") as f:
                json.dump(fix_json(json.loads(z.read(src))), f, indent=2, ensure_ascii=False)
                f.write("\n")
    for n in names:
        if n.startswith("data/minecraft/worldgen/processor_list/roof_colour/") and n.endswith(".json"):
            dest = os.path.join(OUT, "worldgen", "processor_list", "city", "roof_colour", os.path.basename(n))
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "w", encoding="utf-8") as f:
                json.dump(fix_json(json.loads(z.read(n))), f, indent=2, ensure_ascii=False)
                f.write("\n")
    for tav, ours in TAV_LOOT.items():
        dest = os.path.join(OUT, "loot_table", "chests", "city", ours + ".json")
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "w", encoding="utf-8") as f:
            json.dump(fix_json(json.loads(z.read(f"data/minecraft/loot_table/chests/{tav}.json"))), f, indent=2, ensure_ascii=False)
            f.write("\n")

    print(f"templates {count}, pools {len(pool_files)}")
    for k, v in sorted(unresolved.items()):
        print(f"  unresolved x{v}: {k}")


if __name__ == "__main__":
    main(sys.argv[1])
