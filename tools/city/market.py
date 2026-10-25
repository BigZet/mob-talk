"""The city market (рынок, as in any Russian town): stalls under striped awnings for vegetables and fruit, a tiled meat counter
with hams on hooks, a dairy stall, the old women's folding tables with an oilcloth, the vendor's enamel scales with iron
weights, the yellow «КВАС» barrel on wheels and sacks of potatoes; the vendor's apron and headscarf; and the market itself, a
paved lot behind an arch with three rows of stalls (ours/market/rynok), added to the city's house pools.

Run by tools/city/shop.py (textures and models go with the shop's, in block/shop/). The blocks are registered in
io.github.mobtalk.game.ShopContent (ids and outlines must match); the goods on the stalls are drawn by ShopDisplayRenderer.
"""
import json
import os
import random

from PIL import Image

import decor
from decor import bevel, box, new, noise_fill, rect, rng, shade

ROOT, ASSETS, DATA = decor.ROOT, decor.ASSETS, decor.DATA
shop = None  # tools/city/shop.py, as it runs (its textures and models are written together with these)


def bind(module):
    global shop
    shop = module


# ---------------------------------------------------------------- textures

def boards(name, base):
    """Painted boards, the paint worn at the edges: the stalls' counters."""
    r = rng(name)
    img = new()
    noise_fill(img, base, 0.04, r)
    for y in range(16):
        for x in range(16):
            if y % 4 == 3:
                img.putpixel((x, y), shade(base, 0.7))
            elif r.random() < 0.06:
                img.putpixel((x, y), (150, 120, 84, 255))  # bare wood where the paint is gone
    return shop.tex(name, img)


def awning(name, a, b):
    """Awning canvas in broad stripes, a scalloped hem at the front (the bottom rows)."""
    img = new()
    r = rng(name)
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), shade(a if (x // 4) % 2 == 0 else b, 1 + r.uniform(-0.03, 0.03)))
    for x in range(16):  # the hem
        if x % 4 in (1, 2):
            img.putpixel((x, 15), (0, 0, 0, 0))
    return shop.tex(name, img)


def tiles():
    img = new()
    noise_fill(img, (238, 240, 240, 255), 0.02, rng("mk_tile"))
    for i in range(16):
        for p in ((i, 0), (i, 8), (0, i), (8, i)):
            img.putpixel(p, (180, 186, 190, 255))
    return shop.tex("mk_tile", img)


def ham():
    img = new()
    noise_fill(img, (170, 70, 60, 255), 0.08, rng("mk_ham"))
    for y in range(16):
        for x in range(16):
            if (x * 3 + y) % 7 == 0:
                img.putpixel((x, y), (236, 214, 200, 255))  # the fat
    bevel(img, (0, 0, 16, 16), 1.1, 0.75)
    return shop.tex("mk_ham", img)


def oilcloth():
    """The old women's oilcloth: red and white checks with little flowers."""
    img = new()
    for y in range(16):
        for x in range(16):
            red = (x // 4 + y // 4) % 2 == 0
            img.putpixel((x, y), (206, 52, 52, 255) if red else (246, 240, 228, 255))
    for x, y in ((6, 2), (2, 6), (10, 14), (14, 10)):
        img.putpixel((x, y), (60, 140, 60, 255))
    return shop.tex("mk_oilcloth", img)


def enamel():
    img = new()
    noise_fill(img, (236, 236, 228, 255), 0.02, rng("mk_enamel"))
    bevel(img, (0, 0, 16, 16), 1.06, 0.82)
    rect(img, (5, 5, 11, 9), (30, 30, 34, 255))  # the dial
    for x in range(6, 10):
        img.putpixel((x, 6), (236, 236, 228, 255))
    img.putpixel((8, 7), (200, 40, 40, 255))
    return shop.tex("mk_enamel", img)


LETTERS = {  # 3x5 pixel letters
    "К": ["X.X", "XX.", "X..", "XX.", "X.X"],
    "В": ["XX.", "X.X", "XX.", "X.X", "XX."],
    "А": [".X.", "X.X", "XXX", "X.X", "X.X"],
    "С": [".XX", "X..", "X..", "X..", ".XX"],
}


def kvass_side():
    """The barrel's yellow side with «КВАС» in brown, hoops at the ends."""
    img = new()
    noise_fill(img, (236, 196, 40, 255), 0.04, rng("mk_kvass"))
    for y in range(16):
        img.putpixel((0, y), (170, 130, 20, 255))
        img.putpixel((15, y), (170, 130, 20, 255))
    for i, ch in enumerate("КВАС"):
        for dy, row in enumerate(LETTERS[ch]):
            for dx, c in enumerate(row):
                if c == "X":
                    img.putpixel((i * 4 + dx, 5 + dy), (110, 50, 20, 255))
    for x in range(16):
        img.putpixel((x, 0), shade((236, 196, 40, 255), 1.15))
        img.putpixel((x, 15), shade((236, 196, 40, 255), 0.7))
    return shop.tex("mk_kvass", img)


def burlap():
    img = new()
    r = rng("mk_burlap")
    for y in range(16):
        for x in range(16):
            k = 1.08 if (x + y) % 2 == 0 else 0.9
            img.putpixel((x, y), shade((176, 146, 98, 255), k * (1 + r.uniform(-0.05, 0.05))))
    return shop.tex("mk_burlap", img)


def potatoes():
    img = new()
    noise_fill(img, (120, 92, 56, 255), 0.05, rng("mk_potato_bg"))
    r = rng("mk_potato")
    for _ in range(9):
        cx, cy = r.randint(1, 14), r.randint(1, 14)
        for dx in (-1, 0, 1):
            for dy in (-1, 0):
                x, y = min(15, max(0, cx + dx)), min(15, max(0, cy + dy))
                img.putpixel((x, y), (196, 156, 96, 255) if dy else (214, 176, 112, 255))
    return shop.tex("mk_potato", img)


def make_textures():
    boards("mk_board_blue", (92, 132, 170, 255))
    boards("mk_board_green", (88, 140, 92, 255))
    shop.wood("mk_top", (190, 150, 100, 255), 0.1)
    awning("mk_awning_red", (206, 48, 44, 255), (244, 240, 230, 255))
    awning("mk_awning_blue", (52, 96, 186, 255), (244, 240, 230, 255))
    tiles()
    ham()
    oilcloth()
    enamel()
    kvass_side()
    shop.paint("mk_kvass_end", (210, 168, 30, 255), 0.05)
    shop.paint("mk_iron", (44, 44, 48, 255), 0.06)
    burlap()
    potatoes()


# ---------------------------------------------------------------- models

def counter(els, body, top="top", height=9):
    """A market counter: boards round three sides (open at the back, the vendor's side), a worn top, legs."""
    els += [box((0, 0, 1), (16, height, 2), body), box((0, 0, 2), (1, height, 16), body), box((15, 0, 2), (16, height, 16), body),
            box((0, height, 0.5), (16, height + 1, 16), top), box((1, 1, 2), (15, 1.5, 15.5), top, ["up"])]


def posts_and_awning(els, canvas):
    """Two posts at the back, an awning sloping down to the front over the customer's head (reaching y 30 at the back)."""
    els += [box((0.2, 10, 14.6), (1.2, 29, 15.6), "top"), box((14.8, 10, 14.6), (15.8, 29, 15.6), "top")]
    for i in range(6):  # the slope in steps (models turn only by 22.5 degrees; steps read as canvas sagging a little)
        z0, z1 = 16 - (i + 1) * 3.2, 16 - i * 3.2
        y = 29 - i * 0.9
        els.append(box((-0.5, y, z0 - 0.4), (16.5, y + 0.5, z1), "canvas", ["up", "down", "north", "east", "west"]))
    els.append(box((-0.5, 23.6, -3.6), (16.5, 24.6, -3.2), "canvas", ["north", "south", "east", "west"]))  # the hem
    return {"canvas": canvas}


def veg_stall():
    els = []
    counter(els, "body")
    for i in range(3):  # crates of vegetables on the top, tipped towards the customer
        x0, x1 = 0.3 + i * 5.2, 5.3 + i * 5.2
        els += [box((x0, 10, 1), (x1, 11, 13), "crate"), box((x0, 11, 0.6), (x1, 12.5, 1.4), "crate"),
                box((x0, 11, 12.2), (x1, 15, 13), "crate"), box((x0, 11, 1.4), (x0 + 0.5, 13.5, 12.2), "crate"),
                box((x1 - 0.5, 11, 1.4), (x1, 13.5, 12.2), "crate")]
    els.append(box((0, 7, 0.5), (16, 8.4, 1), "strip", ["north", "up", "down", "east", "west"]))
    tex = {"body": "mk_board_green", "top": "mk_top", "crate": "shop_crate", "strip": "shop_price_strip", "canvas": "mk_awning_red"}
    posts_and_awning(els, "mk_awning_red")
    shop.model("market_veg_stall", tex, els, "body", ao=False)


def meat_stall():
    els = [box((0, 0, 1), (16, 10, 16), "tile"), box((0, 10, 0.5), (16, 11, 16), "tile"),
           box((1, 11, 8), (15, 12.5, 15), "top")]  # the chopping board
    els.append(box((0, 8, 0.5), (16, 9.4, 1), "strip", ["north", "up", "down", "east", "west"]))
    els += [box((0.2, 11, 15), (1.2, 30, 16), "iron"), box((14.8, 11, 15), (15.8, 30, 16), "iron"),
            box((0.2, 28, 15.2), (15.8, 28.8, 15.8), "iron")]  # the rail
    for x in (3, 8, 13):  # hams on hooks
        els += [box((x - 0.2, 25.5, 15.3), (x + 0.2, 28, 15.7), "iron"),
                box((x - 1.6, 20, 14.4), (x + 1.6, 25.5, 16), "ham")]
    els.append(box((10, 12.5, 9), (14, 13, 10), "iron"))  # the cleaver
    shop.model("market_meat_stall", {"tile": "mk_tile", "top": "mk_top", "iron": "mk_iron", "ham": "mk_ham", "strip": "shop_price_strip"},
               els, "tile", ao=False)


def dairy_stall():
    els = []
    counter(els, "body")
    els.append(box((0, 7, 0.5), (16, 8.4, 1), "strip", ["north", "up", "down", "east", "west"]))
    tex = {"body": "mk_board_blue", "top": "mk_top", "strip": "shop_price_strip", "canvas": "mk_awning_blue"}
    posts_and_awning(els, "mk_awning_blue")
    shop.model("market_dairy_stall", tex, els, "body", ao=False)


def granny_table():
    els = [box((1.5, 0, 2.5), (2.5, 8, 3.5), "iron"), box((13.5, 0, 2.5), (14.5, 8, 3.5), "iron"),
           box((1.5, 0, 12.5), (2.5, 8, 13.5), "iron"), box((13.5, 0, 12.5), (14.5, 8, 13.5), "iron"),
           box((1, 8, 2), (15, 9, 14), "cloth"), box((1, 6.5, 2), (15, 8, 2.2), "cloth", ["north"]),  # the oilcloth hanging over
           box((4, 0, 14.5), (12, 5, 16), "top")]  # the old woman's stool behind
    shop.model("market_granny_table", {"iron": "mk_iron", "cloth": "mk_oilcloth", "top": "mk_top"}, els, "cloth", ao=False)


def scales():
    els = []
    counter(els, "body")
    els += [box((4, 10, 4), (12, 13, 10), "enamel"),          # the body with its dial at the front
            box((3, 13, 4.5), (8, 13.5, 9.5), "enamel"),      # the two pans
            box((8.5, 13, 4.5), (13, 13.5, 9.5), "enamel"),
            box((4.5, 13.5, 5.5), (6.5, 15, 7.5), "iron"),    # weights on one pan
            box((5, 15, 6.2), (6, 15.5, 6.8), "iron"),
            box((10, 13.5, 6), (11.5, 14.5, 7.5), "iron"),
            box((1.5, 10, 11), (3.5, 11.5, 13), "iron")]
    shop.model("market_scales", {"body": "mk_board_green", "top": "mk_top", "enamel": "mk_enamel", "iron": "mk_iron"}, els, "enamel", ao=False)


def kvass_barrel():
    side = {"north": ("side", None), "south": ("side", None), "up": ("end", None), "down": ("end", None),
            "east": ("end", None), "west": ("end", None)}
    els = [box((1, 5, 3), (15, 15, 13), side), box((1, 6, 2), (15, 14, 14), side), box((1, 4, 4), (15, 16, 12), side),
           box((0.5, 4, 3), (1, 16, 13), "iron"), box((15, 4, 3), (15.5, 16, 13), "iron"),  # the hoops
           box((0.5, 6, 2), (1, 14, 14), "iron"), box((15, 6, 2), (15.5, 14, 14), "iron"),
           box((7, 15.8, 6.5), (9, 16, 9.5), "iron"),            # the hatch
           box((7.4, 6, 1), (8.6, 8, 2), "iron"), box((7.6, 5, 0.6), (8.4, 6, 1.2), "iron"),  # the tap
           box((2, 3, 6), (14, 4, 10), "iron"),                  # the chassis
           box((6, 0, 1.5), (10, 4, 2.5), "wheel"), box((6, 0, 13.5), (10, 4, 14.5), "wheel")]
    shop.model("kvass_barrel", {"side": "mk_kvass", "end": "mk_kvass_end", "iron": "mk_iron", "wheel": "shop_cart_wheel"}, els, "side", ao=False)


def potato_sacks():
    els = []
    for x0, z0, h in ((1.5, 1.5, 10), (8.5, 2, 9), (4.5, 8.5, 11)):
        els += [box((x0, 0, z0), (x0 + 6, h, z0 + 6), "sack"), box((x0 + 0.5, h, z0 + 0.5), (x0 + 5.5, h + 0.6, z0 + 5.5), "potato", ["up"]),
                box((x0 + 2, h, z0 - 0.2), (x0 + 4, h + 1.4, z0 + 0.6), "sack")]  # the folded-down rim
    shop.model("potato_sacks", {"sack": "mk_burlap", "potato": "mk_potato"}, els, "sack", ao=False)


def make_models():
    veg_stall()
    meat_stall()
    dairy_stall()
    granny_table()
    scales()
    kvass_barrel()
    potato_sacks()


def vendor_texture():
    """The vendor: a white apron over a padded jacket and a flowered headscarf (64x64 villager overlay)."""
    out = os.path.join(ASSETS, "textures", "entity", "villager", "profession")
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    r = random.Random("vendor")
    jacket, apron, scarf = (70, 76, 96, 255), (242, 240, 232, 255), (196, 50, 60, 255)
    noise_fill(img, jacket, 0.05, r, (22, 20, 38, 26))
    noise_fill(img, jacket, 0.05, r, (16, 26, 44, 38))
    noise_fill(img, apron, 0.02, r, (20, 27, 28, 38))         # the apron's bib on the front
    noise_fill(img, apron, 0.02, r, (0, 44, 14, 58))          # and its skirt on the robe's front
    for x in range(0, 14, 3):
        img.putpixel((x + 1, 50), (210, 206, 196, 255))
    noise_fill(img, jacket, 0.05, r, (14, 44, 28, 58))
    noise_fill(img, jacket, 0.05, r, (44, 22, 52, 26))
    noise_fill(img, jacket, 0.05, r, (44, 26, 60, 34))
    noise_fill(img, scarf, 0.04, r, (32, 0, 64, 10))           # the headscarf on the hat layer
    for x in range(32, 64, 3):
        for y in (2, 6, 9):
            img.putpixel((x, y), (246, 220, 90, 255))
    img.save(os.path.join(out, "market_vendor.png"))


# ---------------------------------------------------------------- the market lot

MARKET = "ours/market/rynok"


def build_market(pieces):
    """The market on a city plot: an arch «РЫНОК» from the street, three rows of stalls (each vendor with three stalls and the
    scales), the old women along the north edge, the kvass barrel and potato sacks by the entrance, lamps and bins."""
    import landmarks
    from yards import G
    lots = []
    for suffix, connector in (("", "minecraft:building_entrance"), ("_5th", "minecraft:building_entrance_5th")):
        y = landmarks.Lot(random.Random("rynok"), pieces.sign_nbt)
        sx, sy, sz = landmarks.SIZE
        for x in range(sx):
            for z in range(sz):
                y.ground(x, z, "mobtalk:paving_grey" if (x + z) % 5 else "mobtalk:paving_red")
        # the arch over the way in
        for z in (7, 13):
            for k in range(1, 6):
                y.set(3, G + k, z, "minecraft:light_gray_concrete")
        for z in range(7, 14):
            y.set(3, G + 6, z, "minecraft:blue_concrete")
        y.sign(2, G + 6, 10, ["РЫНОК"])
        y.sign(2, G + 6, 8, ["Свежее"])
        y.sign(2, G + 6, 12, ["каждый день"])
        kinds = ["market_veg_stall", "market_veg_stall", "market_dairy_stall", "market_meat_stall"]
        r = random.Random("rynok-rows")

        def row(z, facing, x0, segments):
            for s in range(segments):
                kind = r.choice(kinds)
                for i, part in enumerate((kind, kind, "market_scales", kind)):
                    y.set(x0 + s * 5 + i, G + 1, z, "mobtalk:" + part, {"facing": facing})
        row(4, "south", 6, 3)   # the north row, its back to the edge
        row(9, "north", 6, 3)   # the middle rows back to back, vendors between them
        row(12, "south", 6, 3)
        row(17, "north", 6, 3)  # the south row
        for x, facing in ((6, "south"), (10, "south"), (14, "south"), (18, "south")):  # old women by the north fence
            y.set(x, G + 1, 1, "mobtalk:market_granny_table", {"facing": facing})
        y.set(5, G + 1, 2, "mobtalk:kvass_barrel", {"facing": "west"})
        y.set(5, G + 1, 18, "mobtalk:potato_sacks", {"facing": "west"})
        y.set(4, G + 1, 18, "mobtalk:potato_sacks", {"facing": "north"})
        for x, z in ((4, 5), (4, 15), (22, 5), (22, 15)):
            y.post_lamp(x, z)
        y.thing(4, 3, "bin_metal", "east")
        y.thing(22, 10, "bin_metal", "west")
        y.connector(connector, sz // 2)
        rel = MARKET + suffix
        pieces.save(rel, "", y.tag())
        lots.append(rel)
    return lots


def add_market(pieces, share=0.05):
    """The market on about one plot in twenty of the house pools: most cities have one."""
    lots = build_market(pieces)
    for rel, suffix in pieces.HOUSE_POOLS.items():
        path = os.path.join(DATA, "worldgen", "template_pool", "city", rel + ".json")
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        data["elements"] = [e for e in data["elements"] if "/ours/market/" not in e["element"].get("location", "")]
        weight = max(1, round(sum(e["weight"] for e in data["elements"]) * share))
        for lot in lots:
            if lot.endswith("_5th") != bool(suffix):
                continue
            for w in [150] * (weight // 150) + ([weight % 150] if weight % 150 else []):
                data["elements"].append({"weight": w, "element": {"location": "mobtalk:city/" + lot, "processors": "minecraft:empty",
                                                                  "projection": "rigid", "element_type": "minecraft:single_pool_element"}})
        pieces.write_json("worldgen/template_pool/city/" + rel + ".json", data)
    print("market:", len(lots), "templates")


NAMES = {
    "block.mobtalk.market_veg_stall": ("Прилавок с овощами", "Vegetable stall"),
    "block.mobtalk.market_meat_stall": ("Мясной прилавок", "Meat counter"),
    "block.mobtalk.market_dairy_stall": ("Молочный прилавок", "Dairy stall"),
    "block.mobtalk.market_granny_table": ("Столик бабушки", "Old woman's table"),
    "block.mobtalk.market_scales": ("Весы с гирями", "Scales with weights"),
    "block.mobtalk.kvass_barrel": ("Бочка кваса", "Kvass barrel"),
    "block.mobtalk.potato_sacks": ("Мешки картошки", "Sacks of potatoes"),
    "entity.minecraft.villager.market_vendor": ("Продавщица", "Market vendor"),
    "entity.minecraft.villager.mobtalk.market_vendor": ("Продавщица", "Market vendor"),
    "mobtalk.settings.cityMarkets": ("Рынки работают", "Markets work"),
    "mobtalk.settings.cityMarkets.tooltip": ("На городском рынке продавцы продают товар с прилавков за изумруды сразу, без кассы. Присесть + взять — поторговаться. Выключено: прилавки просто декор.",
                                             "At the city market the vendors sell from their stalls for emeralds on the spot, no till. Sneak + take: haggle. Off: the stalls are just decoration."),
    "mobtalk.settings.marketCalls": ("Продавцы зазывают", "Vendors call out"),
    "mobtalk.settings.marketCalls.tooltip": ("Продавщицы на рынке иногда окликают проходящих мимо игроков.", "Market vendors now and then call out to players passing by."),
    "mobtalk.tune.marketHagglePercent": ("Уступают при торге: %s%%", "Vendors give way: %s%%"),
    "mobtalk.tune.marketHagglePercent.tooltip": ("Как часто продавщица уступает, когда торгуешься: изумруд скидки или немного сверху. Одна продавщица торгуется раз в минуту.",
                                                 "How often a vendor gives way when you haggle: an emerald off, or a little extra. Each vendor haggles once a minute."),
    "mobtalk.market.no_vendor": ("Продавца нет на месте", "The vendor is not here"),
    "mobtalk.market.bought": ("%s ×%s — %s изумр.", "%s ×%s — %s emeralds"),
    "mobtalk.market.kvass.short": ("Квас — изумруд за две бутылки", "Kvass: an emerald for two bottles"),
    "mobtalk.market.kvass.poured": ("Налили две бутылки кваса", "Poured two bottles of kvass"),
    "mobtalk.market.say.sold_out.0": ("%s всё разобрали, приходите завтра.", "The %s is all gone, come tomorrow."),
    "mobtalk.market.say.sold_out.1": ("Кончилось, милок. Утром привезут.", "All gone, dear. More in the morning."),
    "mobtalk.market.say.sold_out.2": ("Нету уже. Возьмите другое, тоже хорошее.", "None left. Take something else, it's good too."),
    "mobtalk.market.say.haggle_again.0": ("Я ж уже скинула! Совсем без штанов меня оставить хотите?", "I already gave you a discount! Want me to go bankrupt?"),
    "mobtalk.market.say.haggle_again.1": ("Хватит торговаться, бери как есть.", "Enough haggling, take it as it is."),
    "mobtalk.market.say.haggle_again.2": ("Второй раз не уступлю, и не просите.", "I won't give way twice, don't ask."),
    "mobtalk.market.say.haggle_cheaper.0": ("Ну ладно, для вас — %s.", "Oh well, for you: %s."),
    "mobtalk.market.say.haggle_cheaper.1": ("Эх, уговорили. Давайте %s.", "Ah, you talked me into it. %s then."),
    "mobtalk.market.say.haggle_cheaper.2": ("Только вам и только сегодня — %s.", "Just for you, just today: %s."),
    "mobtalk.market.say.haggle_extra.0": ("Дешевле некуда, но сверху положу, с горкой.", "Can't go lower, but I'll add a bit on top."),
    "mobtalk.market.say.haggle_extra.1": ("Ладно, вот ещё немножко — на здоровье.", "All right, here's a little extra, enjoy."),
    "mobtalk.market.say.haggle_extra.2": ("Цену не сброшу, а довесок дам.", "Not a lower price, but a bit extra."),
    "mobtalk.market.say.haggle_no.0": ("Цена нормальная, у меня всё своё, с огорода!", "It's a fair price, all mine, from the garden!"),
    "mobtalk.market.say.haggle_no.1": ("Не торгуюсь. Не нравится — идите в универсам.", "No haggling. Don't like it, go to the supermarket."),
    "mobtalk.market.say.haggle_no.2": ("И так себе в убыток продаю!", "I'm selling at a loss as it is!"),
    "mobtalk.market.say.short.0": ("Это %s изумр., а у вас не хватает.", "That's %s emeralds and you're short."),
    "mobtalk.market.say.short.1": ("Денежку сначала покажите — %s.", "Show me the money first: %s."),
    "mobtalk.market.say.short.2": ("Не хватает, милок. %s надо.", "Not enough, dear. It's %s."),
    "mobtalk.market.say.thanks.0": ("Кушайте на здоровье!", "Enjoy your food!"),
    "mobtalk.market.say.thanks.1": ("Приходите ещё, я тут каждый день.", "Come again, I'm here every day."),
    "mobtalk.market.say.thanks.2": ("Всё свежее, сама вчера собирала!", "All fresh, picked it myself yesterday!"),
    "mobtalk.market.call.0": ("Подходите, всё свежее!", "Come on over, everything's fresh!"),
    "mobtalk.market.call.1": ("Молодой человек, помидорчики берём!", "Young man, take some tomatoes!"),
    "mobtalk.market.call.2": ("Девушка, попробуйте, сладкие!", "Miss, have a taste, they're sweet!"),
    "mobtalk.market.call.3": ("Картошечка своя, без химии!", "Our own potatoes, no chemicals!"),
    "mobtalk.market.call.4": ("Дёшево отдам, уже домой собираюсь!", "Cheap, I'm about to pack up!"),
    "mobtalk.market.call.5": ("Сметанка, творожок — домашние!", "Sour cream, cottage cheese, home-made!"),
    "mobtalk.market.call.6": ("Мясо парное, только утром!", "Fresh meat, this morning's!"),
    "mobtalk.market.call.7": ("Что ищете? Спрашивайте, всё есть!", "What are you after? Ask, we've got it all!"),
}


def lang():
    path = os.path.join(ASSETS, "lang")
    for i, code in enumerate(("ru_ru", "en_us")):
        f = os.path.join(path, code + ".json")
        data = json.load(open(f, encoding="utf-8"))
        for key, pair in NAMES.items():
            data[key] = pair[i]
        decor.write(f, data)


def acquirable():
    tag = os.path.join(ROOT, "src", "main", "resources", "data", "minecraft", "tags", "point_of_interest_type", "acquirable_job_site.json")
    have = json.load(open(tag, encoding="utf-8"))
    if "mobtalk:market_vendor" not in have["values"]:
        have["values"].append("mobtalk:market_vendor")
    decor.write(tag, have)
