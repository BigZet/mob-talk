"""Furnishes the city's supermarkets with the shop's own blocks (io.github.mobtalk.game.ShopContent): gondolas, bread racks,
refrigerated cases, chest freezers, produce stands, staffed tills and a self-checkout, anti-theft gates by the exits, a stack of
baskets and the guard's desk. The goods are picked by the game from the catalogue when a display first ticks.

  houses/corgi_fifth/shop/supermarket   «УНИВЕРСАМ» on the ground floor of a five-storey block (imported, 4 tills)
  ours/landmark/univermag[_5th]         the two-storey pavilion of tools/city/landmarks.py

Run by tools/city/shop.py (which pieces.py calls last). Idempotent: a template that already has a till is left alone.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pieces  # noqa: E402

SHOP = ("mobtalk:cash_register", "mobtalk:self_checkout")


def furnished(t):
    return any(n in SHOP for (n, _p, _b) in t.grid.values())


def put(t, x, y, z, name, facing):
    t.put(x, y, z, "mobtalk:" + name, {"facing": facing})


def air(t, x, y, z):
    t.put(x, y, z, "minecraft:air")


def corgi(rel="houses/corgi_fifth/shop/supermarket"):
    """The imported universam (20 x 15 inside the five-storey's ground floor): main door north at x 9, exits west past the tills.
    Rows of slabs become two-high gondolas and a fruit island, the north wall's lecterns refrigerated cases, the south wall's
    freezer chests our freezers; tills where the «КАССА» counters end, the cashiers on the lecterns' spots."""
    t = pieces.Template(rel)
    if furnished(t):
        return 0
    for z in (3, 5, 7):  # gondolas, two high, the slab caps and the stair ends gone
        for x in range(7, 17):
            for y in (1, 2):
                if 8 <= x <= 15:
                    put(t, x, y, z, "shop_shelf", "north")
                else:
                    air(t, x, y, z)
            air(t, x, 3, z)
    for x in range(7, 17):  # the fruit and vegetable island
        for y in (1, 2, 3):
            air(t, x, y, 9)
        if 8 <= x <= 15:
            put(t, x, 1, 9, "shop_produce", "north" if x % 2 else "south")
    for x in range(10, 18):  # refrigerated cases along the north wall
        put(t, x, 1, 1, "shop_fridge", "south")
    for x in (7, 8, 9, 11, 12, 13, 15, 16, 17):  # chest freezers along the south wall
        put(t, x, 1, 13, "shop_freezer", "north")
        air(t, x, 2, 13)
    for z in range(2, 8):  # bread racks along the east wall, clear of its doors
        put(t, 17, 1, z, "shop_bread_rack", "west")
    for x, y, z in ((2, 3, 4), (2, 3, 10), (17, 3, 10)):  # bells hung from the ceiling
        air(t, x, y, z)
    for z in (1, 7, 13):  # the cashiers' places
        air(t, 2, 1, z)
    put(t, 2, 1, 2, "cash_register", "south")
    put(t, 2, 1, 6, "cash_register", "north")
    put(t, 2, 1, 8, "self_checkout", "south")
    put(t, 2, 1, 12, "cash_register", "north")
    put(t, 2, 1, 4, "theft_gate", "east")
    put(t, 2, 1, 10, "theft_gate", "east")
    put(t, 8, 1, 1, "theft_gate", "south")
    put(t, 7, 1, 1, "basket_stack", "south")
    put(t, 5, 1, 13, "shop_guard_desk", "east")
    t.save()
    return 1


def pavilion(rel):
    """The landmark universam (inside x 4..11, z 2..18, floor at y 5, doors west at z 5 and 15; the ground floor is the shop's,
    the office upstairs is tools/city/landmarks.py's): tills and gates by the doors, gondolas and produce down the middle,
    cases, freezers and bread racks along the back wall."""
    t = pieces.Template(rel)
    if furnished(t):
        return 0
    y = 5
    for z in (3, 6, 9, 12, 15):  # the placeholder barrels and slabs
        air(t, 11, y, z)
        air(t, 6, y, z)
    for z in range(2, 16):  # (the stairs to the office upstairs are in the back corner, z 17..18)
        kind = "shop_fridge" if z <= 6 else "shop_freezer" if z <= 11 else "shop_bread_rack"
        put(t, 11, y, z, kind, "west")
    for z in range(3, 10):
        put(t, 8, y, z, "shop_shelf", "west")
        put(t, 8, y + 1, z, "shop_shelf", "west")
    for z in range(11, 16):
        put(t, 8, y, z, "shop_produce", "west")
    put(t, 5, y, 7, "cash_register", "west")
    put(t, 5, y, 10, "self_checkout", "west")
    put(t, 5, y, 13, "cash_register", "west")
    for z in (4, 6, 14, 16):
        put(t, 4, y, z, "theft_gate", "north")
    put(t, 4, y, 2, "basket_stack", "east")  # (the cart corral goes in the other corner, see carts())
    put(t, 5, y, 17, "shop_guard_desk", "west")
    t.save()
    return 1


def carts():
    """The cart corral (a later addition, so placed apart from the furnishing above): by the universam's north door, and in
    the pavilion's south corner instead of the second basket stack."""
    n = 0
    for rel, (x, y, z), facing in (("houses/corgi_fifth/shop/supermarket", (6, 1, 1), "north"),
                                   ("ours/landmark/univermag", (4, 5, 18), "west"), ("ours/landmark/univermag_5th", (4, 5, 18), "west")):
        if not os.path.exists(os.path.join(pieces.DATA, "structure", "city", rel + ".nbt")):
            continue
        t = pieces.Template(rel)
        if any(name == "mobtalk:cart_corral" for (name, _p, _b) in t.grid.values()):
            continue
        put(t, x, y, z, "cart_corral", facing)
        t.save()
        n += 1
    return n


def weigh():
    """The universam comes up three times as often as the café or the fish shop on the five-storey's ground floor."""
    path = os.path.join(pieces.DATA, "worldgen", "template_pool", "city", "houses", "corgi_fifth", "shops.json")
    with open(path, encoding="utf-8") as f:
        data = pieces.json.load(f)
    for e in data["elements"]:
        if e["element"].get("location", "").endswith("/supermarket"):
            e["weight"] = 3
    pieces.write_json("worldgen/template_pool/city/houses/corgi_fifth/shops.json", data)


def furnish():
    weigh()
    n = corgi()
    for rel in ("ours/landmark/univermag", "ours/landmark/univermag_5th"):
        if os.path.exists(os.path.join(pieces.DATA, "structure", "city", rel + ".nbt")):
            n += pavilion(rel)
    print("supermarkets furnished:", n, "· cart corrals:", carts())


if __name__ == "__main__":
    furnish()
