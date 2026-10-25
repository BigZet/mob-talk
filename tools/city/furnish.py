"""Furnishes the city's buildings with the furniture of tools/city/interior.py, by what the building is.

* The donor's furniture pieces (structure/city/furniture/...: jigsaw slots in every flat) are rebuilt by their kind, on the same
  footprint and with the same jigsaw: a kitchen set with a gas stove, sink and ZIL fridge, beds under a wall carpet, a sofa,
  a "стенка", a table with chairs, a chandelier, pictures, a rug.
* Inside the buildings themselves the Minecraft furniture is swapped by the building's kind: bookshelves become wall units in
  flats, filing cabinets in the administration, medicine cabinets in the hospital, shelves of goods in shops; lanterns become
  chandeliers in flats and ceiling lights in public buildings; scaffolding tables, furnace stoves and hopper sinks become ours.
  The villagers' job blocks stay (a lectern in the administration becomes a desk), so the professions still work.

Run from pieces.py after the decor pass; like it, not idempotent (the import starts from the donor again).
"""
import os
import random
import zlib

import nbt

P = None  # the pieces module

BED_COLOURS = {"lime": "green", "magenta": "red", "pink": "red", "cyan": "blue", "light_blue": "blue", "yellow": "brown", "orange": "brown",
               "purple": "blue", "light_gray": "white", "gray": "brown", "black": "brown"}
SOFAS = ("red", "green", "brown", "blue")


def block(name, facing=None):
    return (name if ":" in name else "mobtalk:" + name), ({"facing": facing} if facing else {})


def state_string(name, props):
    return name + ("[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]" if props else "")


class Room:
    """A template being refurnished: cells set in place (fast on big templates), jigsaw cells get the block as their final state."""

    def __init__(self, t):
        self.t = t
        self.changed = 0

    def jigsaw(self, x, y, z):
        cell = self.t.grid.get((x, y, z))
        return cell and cell[0] == "minecraft:jigsaw" and "nbt" in cell[2].value

    def set(self, x, y, z, b):
        name, props = b
        t = self.t
        if not (0 <= x < t.size[0] and 0 <= y < t.size[1] and 0 <= z < t.size[2]):
            return
        cell = t.grid.get((x, y, z))
        if cell and cell[0] == "minecraft:jigsaw" and "nbt" in cell[2].value:
            cell[2].value["nbt"].value["final_state"] = nbt.Tag(nbt.STRING, state_string(name, props))
            self.changed += 1
            return
        if cell:
            blk = cell[2]
            blk.value["state"] = nbt.Tag(nbt.INT, t.state(name, props))
            blk.value.pop("nbt", None)
            t.grid[(x, y, z)] = (name, props, blk)
        else:
            if name == "minecraft:air":
                return
            blk = nbt.c(pos=nbt.Tag(nbt.LIST, [nbt.Tag(nbt.INT, v) for v in (x, y, z)], nbt.INT), state=nbt.Tag(nbt.INT, t.state(name, props)))
            t.tag.value["blocks"].value.append(blk)
            t.grid[(x, y, z)] = (name, props, blk)
        self.changed += 1

    def clear(self):
        for (x, y, z), (n, _p, _b) in list(self.t.grid.items()):
            if n != "minecraft:air":
                self.set(x, y, z, AIR)


AIR = block("minecraft:air")


# ---------------------------------------------------------------- the furniture pieces

def kitchen(room, r, w):
    old = dict(room.t.grid)
    corner = [(x, z) for (x, y, z), (n, p, b) in old.items() if y == 0 and z == 1 and n not in ("minecraft:air", "minecraft:jigsaw")]
    room.clear()
    c = "white" if r.random() < 0.6 else "wood"
    sets = {2: [["gas_stove", "sink"]], 3: [["fridge", "gas_stove", "sink"], ["gas_stove", "sink", "cabinet"], ["cabinet", "gas_stove", "sink"]],
            4: [["fridge", "gas_stove", "cabinet", "sink"], ["sink", "cabinet", "gas_stove", "fridge"], ["fridge", "sink", "cabinet", "gas_stove"]]}
    row = r.choice(sets.get(w, [["cabinet"] * w]))
    for x, what in enumerate(row):
        if what == "fridge":
            room.set(x, 0, 0, block("fridge_bottom", "south"))
            room.set(x, 1, 0, block("fridge_top", "south"))
            continue
        room.set(x, 0, 0, block(what if what == "gas_stove" else f"kitchen_{what}_{c}", "south"))
        room.set(x, 2, 0, block(f"wall_cupboard_{c}", "south"))
    for x, z in corner:  # the corner set's leg along the side wall
        face = "west" if x == w - 1 else "east"
        room.set(x, 0, z, block(f"kitchen_cabinet_{c}", face))
        room.set(x, 2, z, block(f"wall_cupboard_{c}", face))


def recolour_bed(name):
    colour = name.split(":")[1][:-4]
    return f"minecraft:{BED_COLOURS.get(colour, colour)}_bed"


def bedroom(room, r, w):
    old = dict(room.t.grid)
    beds = {}
    for (x, y, z), (n, p, b) in old.items():
        if n.endswith("_bed"):
            beds[(x, y, z)] = (recolour_bed(n), p)
    jig = [(pos, b) for pos, (n, p, b) in old.items() if n == "minecraft:jigsaw" and "nbt" in b.value]
    along_z = any(p.get("facing") in ("north", "south") for _n, p in beds.values())
    room.clear()
    for pos, (n, p) in beds.items():
        room.set(*pos, (n, dict(p)))
    for pos, b in jig:
        fs = b.value["nbt"].value["final_state"].value
        if fs.split("[")[0].endswith("_bed"):
            room.set(*pos, (recolour_bed(fs.split("[")[0]), dict(kv.split("=") for kv in fs.split("[")[1].rstrip("]").split(","))))
            beds[pos] = None
    if along_z:
        return
    xs = sorted(x for (x, y, z) in beds if y == 0 and z == 0)
    for x in range(w):
        if x in xs:
            continue
        room.set(x, 0, 0, block("wardrobe_bottom", "south"))
        room.set(x, 1, 0, block("wardrobe_top", "south"))
    if len(xs) == 2 and xs[1] == xs[0] + 1:
        roll = r.random()
        if roll < 0.7:
            room.set(xs[0], 1, 0, block("wall_carpet_left", "south"))
            room.set(xs[1], 1, 0, block("wall_carpet_right", "south"))
        elif roll < 0.9:
            room.set(r.choice(xs), 1, 0, block(r.choice(("picture_forest", "picture_sea")), "south"))


def sofa(room, r, w):
    room.clear()
    col = r.choice(SOFAS)
    room.set(0, 0, 0, block(f"sofa_left_{col}", "south"))
    room.set(1, 0, 0, block(f"sofa_right_{col}", "south"))
    roll = r.random()
    if roll < 0.55:
        room.set(0, 1, 0, block("wall_carpet_left", "south"))
        room.set(1, 1, 0, block("wall_carpet_right", "south"))
    elif roll < 0.8:
        room.set(r.randrange(2), 1, 0, block(r.choice(("picture_forest", "picture_sea")), "south"))


def small_decor(room, r, w):
    room.clear()
    roll = r.random()
    if roll < 0.35:
        room.set(0, 0, 0, block("crt_tv", "south"))
    elif roll < 0.55:
        room.set(0, 0, 0, block("nightstand_lamp", "south"))
    elif roll < 0.75:
        room.set(0, 0, 0, block("floor_lamp", "south"))
    else:
        room.set(0, 0, 0, block(f"armchair_{r.choice(SOFAS)}", "south"))


def dresser_pair(room, r, w):
    room.clear()
    pair = r.choice([("dresser_tv", "floor_lamp"), ("dresser", "dresser_radio"), ("piano", "floor_lamp"), ("dresser_tv", "nightstand"),
                     ("radiator", "dresser")])
    for x, what in enumerate(pair[:w]):
        if what == "radiator":
            room.set(x, 0, 0, block("dresser_radio", "south"))
        else:
            room.set(x, 0, 0, block(what, "south"))
    if r.random() < 0.3:
        room.set(r.randrange(w), 2, 0, block("wall_clock", "south"))


def shelves(room, r, w):
    room.clear()
    h = room.t.size[1]
    roll = r.random()
    if w == 1:
        if roll < 0.5:
            column = ["stenka_doors", r.choice(("stenka_glass", "stenka_books")), "stenka_doors"]
        elif roll < 0.8:
            column = ["bookcase", "bookcase", None]
        else:
            column = ["wardrobe_bottom", "wardrobe_top", None]
        for y, what in enumerate(column[:h]):
            if what:
                room.set(0, y, 0, block(what, "south"))
        return
    if roll < 0.6:  # the wall unit: cupboards, crystal and books behind glass, cupboards on top
        middle = ["stenka_glass", "stenka_books"]
        r.shuffle(middle)
        for x in range(w):
            room.set(x, 0, 0, block("stenka_doors", "south"))
            room.set(x, 1, 0, block(middle[x % 2], "south"))
            if h > 2:
                room.set(x, 2, 0, block("stenka_doors", "south"))
    elif roll < 0.85:
        for x in range(w):
            room.set(x, 0, 0, block("bookcase", "south"))
            room.set(x, 1, 0, block("bookcase", "south"))
    else:
        for x in range(w):
            room.set(x, 0, 0, block("wardrobe_bottom", "south"))
            room.set(x, 1, 0, block("wardrobe_top", "south"))


def table_kind(r):
    roll = r.random()
    return "dining_table" if roll < 0.5 else ("kitchen_table" if roll < 0.8 else "table_samovar")


def tables(room, r, w):
    old = dict(room.t.grid)
    lamp = any(n.endswith("lantern") for n, p, b in old.values())
    legs = [x for (x, y, z), (n, p, b) in old.items() if y == 0 and n == "minecraft:scaffolding"]
    for (x, y, z), (n, p, b) in old.items():
        if n == "minecraft:jigsaw" and "nbt" in b.value and "scaffolding" in b.value["nbt"].value["final_state"].value:
            legs.append(x)
    room.clear()
    if w == 1:
        roll = r.random()
        what = (f"armchair_{r.choice(SOFAS)}" if roll < 0.3 else "coffee_table" if roll < 0.55 else "floor_lamp" if roll < 0.7
                else "table_samovar" if roll < 0.85 else "stool")
        room.set(0, 0, 0, block(what, "south"))
        return
    tx = legs[0] if legs else w // 2
    room.set(tx, 0, 0, block(table_kind(r), "south"))
    for x in range(w):
        if x != tx:
            room.set(x, 0, 0, block("chair", "east" if x < tx else "west"))
    if lamp:
        room.set(tx, 2, 0, block("chandelier", "south"))


def light(room, r, w, rel):
    room.clear()
    if "wall" in rel or "torch" in rel:
        room.set(0, 1, 0, block("sconce", "south"))
    else:
        room.set(0, 2, 0, block("chandelier", "south"))


def wall_decor(room, r, w):
    room.clear()
    roll = r.random()
    room.set(0, 1, 0, block("picture_forest" if roll < 0.35 else "picture_sea" if roll < 0.6 else "wall_clock" if roll < 0.85 else "sconce",
                            "south"))


def rug(room, r, w):
    room.clear()
    col = "red" if r.random() < 0.65 else "green"
    for (x, z), face in (((0, 0), "north"), ((1, 0), "east"), ((1, 1), "south"), ((0, 1), "west")):
        room.set(x, 0, z, block(f"rug_{col}", face))


def furnish_piece(rel):
    """Rebuilds one furniture template, the builder picked for a furniture template by where it lives."""
    t = P.Template(rel)
    w = t.size[0]
    r = random.Random(rel)
    name = rel[len("furniture/"):]
    room = Room(t)
    if name.startswith(("kitchens/", "kitchen_double")):
        kitchen(room, r, w)
    elif name.startswith(("bed", "beds/")):
        bedroom(room, r, w)
    elif name.startswith("decors/double"):
        sofa(room, r, w)
    elif name.startswith("decors/small"):
        small_decor(room, r, w)
    elif name.startswith(("decors/two", "kamod_double")):
        dresser_pair(room, r, w)
    elif name.startswith(("shelfs/", "shelf_double")):
        shelves(room, r, w)
    elif name.startswith(("tables/", "table_first", "table_double")):
        tables(room, r, w)
    elif name.startswith("lights/"):
        light(room, r, w, name)
    elif name.startswith("short_decors/buner"):
        wall_decor(room, r, w)
    elif name.startswith("short_decors/carpet"):
        rug(room, r, w)
    elif name.startswith("chest_n_larten"):
        room.set(0, 2, 0, block("chandelier", "south"))
    else:
        return None
    t.save()
    return name.split("/")[0] if "/" in name else name.rsplit("_", 1)[0]


# ---------------------------------------------------------------- inside the buildings

def kind_of(rel):
    if "admin_centre" in rel or "meriya" in rel or "rada" in rel:
        return "admin"
    if "hospital" in rel:
        return "hospital"
    if "lib" in rel:
        return "library"
    if "dining_hall" in rel:
        return "canteen"
    if any(k in rel for k in ("shop", "store", "market", "minishop")):
        return "shop"
    if any(k in rel for k in ("zavod", "factory", "drukarnia", "atelier", "laboratory", "stonecutter")):
        return "works"
    return "flat"


SHELF_GOODS = ("shop_shelf_tins", "shop_shelf_bread", "shop_shelf_bottles", "shop_shelf_jars")
PUBLIC = ("admin", "hospital", "library", "shop", "works", "canteen")


def stable(*key):
    return zlib.crc32(repr(key).encode())


def room_side(t, x, y, z):
    """The side of a cell that faces into a room: an air neighbour, preferring one with air above it too."""
    best = None
    for d, (dx, dz) in P.DIRS.items():
        if t.air(x + dx, y, z + dz) and indoors(t, x + dx, y, z + dz):
            if t.air(x + dx, y + 1, z + dz) or t.name_at(x + dx, y + 1, z + dz) is None:
                return d
            best = best or d
    return best


def indoors(t, x, y, z):
    """Under a roof, or (a storey's top layer, its ceiling being the next piece's floor) walled in on three sides or more."""
    if not t.open_sky(x, y + 1, z, 40):
        return True
    if t.size[1] <= 5 and max(t.size[0], t.size[2]) <= 8:  # a room piece of a storey
        return True
    walls = 0
    for dx, dz in P.DIRS.values():
        for k in range(1, 9):
            n = t.name_at(x + dx * k, y, z + dz * k)
            if n is None:
                break
            if n not in ("minecraft:air", "minecraft:cave_air"):
                walls += 1
                break
    return walls >= 3


def shelf_for(kind, t, x, y, z):
    k = stable(t.rel, x, z)
    if kind == "admin" or kind == "works":
        return "filing_cabinet" if k % 10 < 7 else "bookcase"
    if kind == "hospital":
        return "medicine_cabinet"
    if kind == "shop":
        return SHELF_GOODS[k % len(SHELF_GOODS)]
    if kind == "library":
        return "bookcase"
    return ("stenka_books", "stenka_glass", "stenka_doors", "bookcase")[(k + y) % 4]


def interiors(rel):
    t = P.Template(rel)
    kind = kind_of(rel)
    room = Room(t)
    public = kind in PUBLIC
    counts = {}

    def swap(pos, b):
        room.set(*pos, b)
        counts[b[0]] = counts.get(b[0], 0) + 1

    def clear_above(x, y, z):
        n = t.name_at(x, y + 1, z) or ""
        if n.endswith(("pressure_plate", "carpet", "tripwire_hook")) or n == "minecraft:tripwire_hook":
            room.set(x, y + 1, z, AIR)

    for (x, y, z), (n, p, b) in list(t.grid.items()):
        if n in ("minecraft:air", "minecraft:jigsaw") or not n.startswith("minecraft:"):
            continue
        hanging = p.get("hanging") == "true" or (n == "minecraft:end_rod" and p.get("facing") == "down")
        if n in ("minecraft:lantern", "minecraft:soul_lantern", "minecraft:end_rod"):
            if not hanging and not indoors(t, x, y, z):
                continue
            if hanging:
                swap((x, y, z), block("office_lamp" if public else "chandelier", "north"))
            elif n != "minecraft:end_rod":
                swap((x, y, z), block("table_lamp", "north"))
            continue
        if n in ("minecraft:bookshelf", "minecraft:chiseled_bookshelf"):
            face = p.get("facing") or room_side(t, x, y, z)
            if face and indoors(t, x, y, z):
                swap((x, y, z), block(shelf_for(kind, t, x, y, z), face))
            continue
        if n == "minecraft:scaffolding" and indoors(t, x, y, z):
            face = room_side(t, x, y, z) or "north"
            if kind == "admin" or kind == "works":
                what = "office_desk_lamp" if stable(rel, x, z) % 2 else "office_desk"
            elif kind == "shop":
                what = "shop_counter" if stable(rel, x, z) % 3 else "shop_counter_scales"
            else:
                what = ("dining_table", "kitchen_table", "table_samovar")[stable(rel, x, z) % 3] if kind == "flat" else "dining_table"
            swap((x, y, z), block(what, face))
            clear_above(x, y, z)
            continue
        if kind != "admin" and indoors(t, x, y, z):
            if n == "minecraft:furnace":
                swap((x, y, z), block("gas_stove", p.get("facing", "north")))
                clear_above(x, y, z)
            elif n == "minecraft:hopper":
                face = room_side(t, x, y, z)
                if face:
                    swap((x, y, z), block("kitchen_sink_white" if stable(rel) % 5 < 3 else "kitchen_sink_wood", face))
                    clear_above(x, y, z)
            elif n == "minecraft:crafting_table":
                face = room_side(t, x, y, z)
                if face:
                    swap((x, y, z), block("kitchen_cabinet_white" if stable(rel) % 5 < 3 else "kitchen_cabinet_wood", face))
                    clear_above(x, y, z)
        if kind == "admin" and n == "minecraft:lectern":
            face = room_side(t, x, y, z) or p.get("facing", "north")
            swap((x, y, z), block("office_desk_typewriter" if stable(rel, x, z) % 3 == 0 else "office_desk_lamp", face))
            clear_above(x, y, z)
        if kind == "shop" and n == "minecraft:crafting_table":
            face = room_side(t, x, y, z)
            if face:
                swap((x, y, z), block("cash_register", face))
    if room.changed:
        t.save()
    return kind, counts


def run(pieces):
    global P
    P = pieces
    root = os.path.join(P.DATA, "structure", "city")
    kinds, pieces_done, totals = {}, {}, {}
    for dp, _dn, fn in os.walk(root):
        for f in sorted(fn):
            rel = os.path.relpath(os.path.join(dp, f), root)[:-4].replace(os.sep, "/")
            if rel.startswith("furniture/"):
                k = furnish_piece(rel)
                if k:
                    pieces_done[k] = pieces_done.get(k, 0) + 1
                continue
            if rel.startswith(("ours/landmark", "ours/yard", "ours/prospekt")) or "/street" in rel or "/road" in rel:
                continue
            kind, counts = interiors(rel)
            if counts:
                kinds[kind] = kinds.get(kind, 0) + 1
                for b, c in counts.items():
                    totals[b] = totals.get(b, 0) + c
    print("furnished pieces:", pieces_done)
    print("furnished buildings:", kinds)
    print("indoor swaps:", dict(sorted(totals.items(), key=lambda kv: -kv[1])))
