"""The street kiosks (ларьки) of a Russian town at a bus stop: the blue «Союзпечать» with newspapers, «Крокодил» and crosswords,
the white «Шаурма» with its turning spit, and the green «Табак · Пиво» with beer, sunflower seeds, rusks, dried roach and gum.
Each kiosk is a little hut of concrete with a sign over a window, and the window's counter is a display block run by a
vendor who takes the money on the spot (as at the market). The lot «Остановка» holds the three kiosks, the bus stop, benches,
lindens and lamps (ours/kiosk/ostanovka), added to the city's house pools.

Run by tools/city/shop.py after the market (textures and models go with the shop's, in block/shop/). The blocks are registered
in io.github.mobtalk.game.ShopContent; their goods are drawn by ShopDisplayRenderer; the goods' items by ShopFoods.
"""
import json
import os
import random

import decor
from decor import bevel, box, new, noise_fill, rng, shade
from shop_goods import Sprite, ellipse

ROOT, ASSETS, DATA = decor.ROOT, decor.ASSETS, decor.DATA
shop = None  # tools/city/shop.py, as it runs


def bind(module):
    global shop
    shop = module


KIOSKS = {  # block id: (body colour, band colour, the hut's concrete, the band's concrete, sign lines)
    "kiosk_press": ((52, 92, 170, 255), (240, 240, 236, 255), "minecraft:blue_concrete", "minecraft:white_concrete", ["СОЮЗПЕЧАТЬ"]),
    "kiosk_shawarma": ((236, 234, 228, 255), (196, 40, 36, 255), "minecraft:white_concrete", "minecraft:red_concrete", ["ШАУРМА", "24 часа"]),
    "kiosk_beer": ((46, 110, 62, 255), (236, 196, 40, 255), "minecraft:green_concrete", "minecraft:yellow_concrete", ["ТАБАК", "ПИВО"]),
}


# ---------------------------------------------------------------- the goods' sprites

def newspaper():
    """A folded newspaper: grey columns of print, a red masthead, a photo."""
    s = Sprite()
    paper = (232, 228, 214)
    s.rect(2, 3, 13, 13, paper)
    s.rect(14, 4, 14, 13, (196, 192, 178))  # the fold's thickness
    s.rect(3, 4, 12, 5, (196, 36, 36))      # the masthead
    for y in range(7, 13):
        for x in range(3, 13):
            if x in (7, 8):
                continue
            if (x + y * 3) % 5 and y % 2:
                s.put(x, y, (110, 110, 110))
    s.rect(9, 7, 12, 9, (90, 96, 104))      # the photo
    s.outline((60, 58, 52))
    return s


def krokodil():
    """«Крокодил», the satirical magazine: a red cover with a green crocodile's head and the white title band."""
    s = Sprite()
    s.rect(3, 2, 12, 14, (214, 50, 44))
    s.rect(13, 3, 13, 14, (150, 30, 28))
    s.rect(3, 3, 12, 4, (244, 240, 230))
    for x in range(4, 12, 2):
        s.put(x, 3, (200, 40, 36))
    s.lit(ellipse(8, 10, 4, 2.6), [(30, 90, 40), (50, 130, 56), (80, 170, 70), (130, 200, 100)])
    for x in (6, 9):
        s.put(x, 9, (250, 250, 250))
    for x in range(5, 11, 2):
        s.put(x, 11, (250, 250, 250))
    s.outline((70, 20, 20))
    return s


def crossword():
    """A crossword booklet: black-and-white grid, a yellow cover strip."""
    s = Sprite()
    s.rect(3, 3, 12, 13, (246, 246, 240))
    s.rect(3, 3, 12, 5, (240, 200, 50))
    for y in range(6, 13):
        for x in range(3, 13):
            if (x * 7 + y * 3) % 11 == 0:
                s.put(x, y, (30, 30, 30))
            elif x % 2 == 0 or y % 2 == 0:
                s.put(x, y, (170, 170, 170))
    s.outline((60, 60, 56))
    return s


def shawarma():
    """Шаурма: a lavash roll in foil, open at the top with meat, cabbage and sauce."""
    s = Sprite()
    lavash = [(176, 132, 72), (210, 168, 102), (232, 196, 132), (246, 222, 170)]
    s.lit(ellipse(8, 9, 3.6, 6.4, 20, 0.7), lavash)
    foil = [(130, 134, 140), (176, 180, 186), (214, 218, 222), (240, 242, 246)]
    for y in range(10, 16):
        for x in range(2, 15):
            if s.get(x, y)[3]:
                s.put(x, y, foil[2] if (x + y) % 3 else foil[3])
    for x, y, c in ((7, 3, (150, 70, 40)), (8, 3, (120, 60, 34)), (9, 4, (90, 160, 70)), (6, 4, (240, 236, 220)),
                    (8, 4, (196, 70, 50)), (7, 5, (240, 236, 220)), (9, 3, (150, 70, 40))):
        s.put(x, y, c)
    s.outline((70, 50, 30))
    return s


def cheburek():
    """A big half-moon cheburek, fried golden with bubbles."""
    s = Sprite()
    gold = [(168, 104, 40), (204, 140, 60), (230, 176, 86), (248, 210, 130)]
    s.lit(lambda x, y: ellipse(8, 11, 7, 6)(x, y) if y <= 11.5 else None, gold)
    for a in range(1, 15, 2):  # the crimped edge
        s.put(a, 11, gold[0])
    for x, y in ((5, 8), (9, 7), (11, 9), (7, 10)):
        s.put(x, y, gold[3])
    s.outline((80, 46, 18))
    return s


def belyash():
    """A belyash: a round fried pie with a hole showing the meat."""
    s = Sprite()
    s.lit(ellipse(8, 9, 6, 5), [(160, 96, 38), (198, 132, 56), (226, 170, 84), (246, 206, 126)])
    for x, y in ((7, 8), (8, 8), (8, 9), (7, 9)):
        s.put(x, y, (130, 70, 50))
    s.outline((80, 46, 18))
    return s


def tea_cup():
    """Tea in a plastic cup with a tea bag's tag hanging over the rim."""
    s = Sprite()
    cup = [(176, 180, 184), (214, 216, 220), (236, 238, 240), (250, 250, 252)]
    for y in range(5, 15):
        inset = (y - 5) // 4
        for x in range(4 + inset, 13 - inset):
            t = (x - 4) / 8
            s.put(x, y, cup[3] if t < 0.25 else cup[2] if t < 0.6 else cup[1])
    s.rect(4, 4, 12, 5, (150, 74, 30))   # the tea
    s.rect(4, 4, 12, 4, (186, 100, 40))
    s.line(11, 4, 13, 8, (230, 230, 230))
    s.rect(13, 8, 14, 10, (220, 190, 60))  # the tag
    s.outline((70, 72, 78))
    return s


def beer():
    """A brown bottle of «Жигулёвское»: a cream label with red, a gold cap."""
    s = Sprite()
    brown = [(50, 26, 10), (86, 46, 16), (120, 70, 26), (170, 110, 50)]
    for y in range(6, 15):
        for x in range(5, 11):
            t = (x - 5) / 5
            s.put(x, y, brown[3] if t < 0.2 else brown[2] if t < 0.5 else brown[1] if t < 0.85 else brown[0])
    for y in range(1, 6):
        for x in range(7, 9 + (1 if y > 3 else 0)):
            s.put(x, y, brown[2] if x == 7 else brown[1])
    s.rect(7, 0, 8, 0, (226, 186, 60))
    s.rect(5, 9, 10, 12, (236, 220, 170))
    s.rect(5, 10, 10, 10, (196, 40, 30))
    s.rect(7, 4, 8, 4, (236, 220, 170))
    s.outline((30, 16, 6))
    return s


def sukhariki():
    """A bag of rusks: a crinkled foil bag, red and gold, a rusk on the front."""
    s = Sprite()
    s.rect(4, 2, 11, 14, (204, 48, 40))
    s.rect(4, 2, 11, 3, (230, 196, 80))
    s.rect(4, 13, 11, 14, (230, 196, 80))
    for x in range(4, 12, 2):
        s.put(x, 2, (176, 140, 50))
        s.put(x, 14, (176, 140, 50))
    s.rect(6, 7, 9, 10, (214, 160, 80))
    s.rect(6, 7, 9, 7, (238, 196, 120))
    s.rect(5, 4, 5, 12, (232, 110, 100))  # the foil's shine
    s.outline((80, 20, 16))
    return s


def vobla():
    """Dried roach (вобла): a stiff silvery-gold fish."""
    s = Sprite()
    s.lit(ellipse(7.5, 8.5, 6, 2.6, -25, 0.8), [(110, 96, 60), (160, 140, 90), (200, 184, 130), (230, 220, 180)])
    for x, y in ((13, 4), (14, 3), (14, 5), (13, 5)):  # the tail
        s.put(x, y, (150, 128, 80))
    s.put(3, 11, (30, 30, 30))  # the eye
    for x, y in ((6, 9), (8, 8), (10, 7)):
        s.put(x, y, (130, 112, 70))
    s.outline((60, 50, 30))
    return s


def gum():
    """A pack of chewing gum: a slim pink pack, white stripe."""
    s = Sprite()
    s.rect(5, 3, 10, 13, (236, 110, 170))
    s.rect(11, 4, 11, 13, (190, 70, 130))
    s.rect(5, 7, 10, 8, (250, 246, 246))
    s.rect(6, 3, 9, 3, (250, 180, 210))
    s.outline((90, 30, 60))
    return s


def loyalty_card():
    """The supermarket's loyalty card: red plastic with a yellow band, the white «У» of «Универсам», a magnetic stripe."""
    s = Sprite()
    s.rect(1, 4, 14, 12, (200, 36, 40))
    s.rect(1, 4, 14, 4, (226, 80, 80))
    s.rect(1, 10, 14, 10, (240, 200, 50))
    for x, y in ((4, 6), (4, 7), (5, 8), (6, 7), (6, 6), (5, 9), (4, 9)):  # «У»
        s.put(x, y, (250, 250, 250))
    s.rect(8, 6, 12, 6, (250, 230, 230))
    s.rect(8, 8, 11, 8, (250, 230, 230))
    s.outline((80, 16, 18))
    return s


GOODS = {
    "newspaper": newspaper, "krokodil": krokodil, "crossword": crossword,
    "shawarma": shawarma, "cheburek": cheburek, "belyash": belyash, "tea_cup": tea_cup,
    "beer": beer, "sukhariki": sukhariki, "vobla": vobla, "gum": gum,
    "loyalty_card": loyalty_card,
}


# ---------------------------------------------------------------- textures and models

def make_textures():
    for kid, (body, band, *_rest) in KIOSKS.items():
        img = new()
        noise_fill(img, body, 0.04, rng(kid))
        for x in range(16):  # a ribbed metal panel
            if x % 4 == 0:
                for y in range(16):
                    img.putpixel((x, y), shade(body, 0.82))
        bevel(img, (0, 0, 16, 16), 1.08, 0.8)
        shop.tex("ks_" + kid[6:], img)
        img = new()
        noise_fill(img, band, 0.03, rng(kid + "band"))
        shop.tex("ks_" + kid[6:] + "_band", img)
    shop.paint("ks_sill", (200, 202, 206, 255), 0.03)
    img = new()  # the doner on its spit
    noise_fill(img, (150, 84, 44, 255), 0.1, rng("ks_doner"))
    for y in range(0, 16, 3):
        for x in range(16):
            img.putpixel((x, y), (186, 116, 60, 255))
    shop.tex("ks_doner", img)
    img = new()  # bottles on the back shelf
    noise_fill(img, (40, 40, 44, 255), 0.03, rng("ks_shelf"))
    for x in range(1, 16, 3):
        for y in range(5, 15):
            img.putpixel((x, y), (96, 56, 20, 255) if x % 2 else (40, 110, 50, 255))
        img.putpixel((x, 4), (220, 180, 60, 255))
    shop.tex("ks_bottles", img)
    img = new()  # a rack of newspapers and magazines pinned up behind the glass
    r = random.Random("ks_press")
    noise_fill(img, (230, 226, 210, 255), 0.03, r)
    for x0 in (0, 5, 10):
        for y0 in (0, 8):
            c = r.choice([(196, 40, 36, 255), (40, 90, 170, 255), (240, 200, 50, 255), (90, 150, 80, 255)])
            for y in range(y0 + 1, y0 + 7):
                for x in range(x0 + 1, x0 + 5):
                    img.putpixel((x, y), c if y < y0 + 3 else (200, 196, 182, 255) if (x + y) % 2 else (150, 150, 150, 255))
    shop.tex("ks_rack", img)


def counter(kid):
    """The kiosk's window: a panel up to the sill, the steel sill out over the pavement (the goods lie on it), and behind it
    whatever the kiosk keeps (a spit, bottles, magazines). The vendor stands inside, behind the block."""
    k = kid[6:]
    els = [box((0, 0, 1), (16, 9, 16), "body"), box((0, 9, -1.5), (16, 10, 9), "sill"),
           box((0, 7.2, 0.5), (16, 8.6, 1), "strip", ["north", "up", "down", "east", "west"])]
    tex = {"body": "ks_" + k, "sill": "ks_sill", "strip": "shop_price_strip"}
    if k == "shawarma":  # the vertical spit at the back
        els += [box((11.5, 10, 11.5), (12.5, 31, 12.5), "sill"), box((9.5, 13, 9.5), (14.5, 25, 14.5), "doner"),
                box((10, 25, 10), (14, 27, 14), "doner"), box((9, 10, 9), (15, 11, 15), "sill"),
                box((14.8, 12, 9), (15.6, 27, 15), "band")]  # the grill's heater
        tex.update({"doner": "ks_doner", "band": "ks_shawarma_band"})
    elif k == "beer":
        els += [box((0, 10, 14), (16, 26, 16), "bottles", ["north", "up"]), box((0, 17.6, 12.5), (16, 18.2, 16), "sill")]
        tex["bottles"] = "ks_bottles"
    else:
        els += [box((0, 10, 14.5), (16, 30, 16), "rack", ["north"])]
        tex["rack"] = "ks_rack"
    shop.model(kid, tex, els, "body", ao=False)


def make_models():
    for kid in KIOSKS:
        counter(kid)


# ---------------------------------------------------------------- the lot

LOT = "ours/kiosk/ostanovka"


def kiosk_hut(y, x0, z0, kid, G):
    """A hut three wide (along the street) and three deep, its window counter in the middle of the front (x0), a door at the
    back; the band with the sign round the top."""
    body, band, wall, trim, lines = KIOSKS[kid]
    for dx in range(3):
        for dz in range(3):
            for dy in (1, 2, 3):
                x, z = x0 + dx, z0 + dz
                edge = dx in (0, 2) or dz in (0, 2)
                y.set(x, G + dy, z, (trim if dy == 3 else wall) if edge else "minecraft:air")
            y.ground(x0 + dx, z0 + dz, "minecraft:smooth_stone")
            y.set(x0 + dx, G + 4, z0 + dz, "minecraft:smooth_stone_slab", {"type": "bottom", "waterlogged": "false"})
    y.set(x0, G + 1, z0 + 1, "mobtalk:" + kid, {"facing": "west"})            # the window
    y.set(x0, G + 2, z0 + 1, "minecraft:air")
    for dz in (0, 2):                                                          # glass round the window
        y.set(x0, G + 2, z0 + dz, "minecraft:glass_pane", {"north": "true", "south": "true", "east": "false", "west": "false", "waterlogged": "false"})
    y.set(x0 + 1, G + 3, z0 + 1, "minecraft:light", {"level": "13", "waterlogged": "false"})
    for half, dy in (("lower", 1), ("upper", 2)):                              # the back door
        y.set(x0 + 2, G + dy, z0 + 1, "minecraft:spruce_door", {"facing": "west", "half": half, "hinge": "left", "open": "false", "powered": "false"})
    y.set(x0 - 1, G + 3, z0 + 1, "minecraft:dark_oak_wall_sign", {"facing": "west", "waterlogged": "false"}, glowing_sign(lines))


SIGN_NBT = None  # pieces.sign_nbt, as build_lot runs


def glowing_sign(lines):
    """The kiosk's name in glowing white on the dark board, readable from the road at dusk too."""
    tag = SIGN_NBT(lines)
    front = tag.value["front_text"].value
    front["color"].value = "white"
    front["has_glowing_text"].value = 1
    return tag


def build_lot(pieces):
    global SIGN_NBT
    SIGN_NBT = pieces.sign_nbt
    import landmarks
    from yards import G
    lots = []
    for suffix, connector in (("", "minecraft:building_entrance"), ("_5th", "minecraft:building_entrance_5th")):
        y = landmarks.Lot(random.Random("ostanovka"), pieces.sign_nbt)
        sx, sy, sz = landmarks.SIZE
        for x in range(sx):
            for z in range(sz):
                y.ground(x, z, "mobtalk:paving_grey" if x < 12 else "minecraft:grass_block")
        for z in range(sz):
            y.ground(0, z, "mobtalk:paving_red")
        # the bus stop by the road: the sign, a bench, a bin
        y.thing(1, 9, "bus_stop_sign", "west")
        y.thing(2, 10, "bench", "west")
        y.thing(2, 11, "bench", "west")
        y.thing(2, 12, "urn", "west")
        # the kiosks in a row facing the street, a pace back from the stop
        kiosk_hut(y, 5, 1, "kiosk_press", G)
        kiosk_hut(y, 5, 14, "kiosk_shawarma", G)
        kiosk_hut(y, 5, 17, "kiosk_beer", G)
        y.thing(4, 13, "urn", "west")
        y.thing(4, 5, "bin_metal", "west")
        for x, z in ((4, 7), (4, 20)):
            y.post_lamp(x, z)
        # a little square behind: lindens, benches facing the stop, a dumpster by the back
        for x, z in ((14, 3), (14, 17), (20, 10)):
            y.linden(x, z, poplar=y.r.random() < 0.3)
        for z in (8, 12):
            y.thing(10, z, "bench", "west")
        y.thing(22, 2, "dumpster", "west")
        y.connector(connector, sz // 2)
        rel = LOT + suffix
        pieces.save(rel, "", y.tag())
        lots.append(rel)
    return lots


def add_lot(pieces, share=0.04):
    """The bus stop with its kiosks on about one plot in twenty-five of the house pools."""
    lots = build_lot(pieces)
    for rel, suffix in pieces.HOUSE_POOLS.items():
        path = os.path.join(DATA, "worldgen", "template_pool", "city", rel + ".json")
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        data["elements"] = [e for e in data["elements"] if "/ours/kiosk/" not in e["element"].get("location", "")]
        weight = max(1, round(sum(e["weight"] for e in data["elements"]) * share))
        for lot in lots:
            if lot.endswith("_5th") != bool(suffix):
                continue
            for w in [150] * (weight // 150) + ([weight % 150] if weight % 150 else []):
                data["elements"].append({"weight": w, "element": {"location": "mobtalk:city/" + lot, "processors": "minecraft:empty",
                                                                  "projection": "rigid", "element_type": "minecraft:single_pool_element"}})
        pieces.write_json("worldgen/template_pool/city/" + rel + ".json", data)
    print("kiosks:", len(lots), "templates")


# ---------------------------------------------------------------- items and names

def save_items():
    idir = os.path.join(ASSETS, "textures", "item")
    for name, draw in GOODS.items():
        draw().img.save(os.path.join(idir, name + ".png"))
        decor.write(os.path.join(ASSETS, "models", "item", name + ".json"), {"parent": "minecraft:item/generated", "textures": {"layer0": f"mobtalk:item/{name}"}})


HEADLINES = [
    ("Жители жалуются: голем опять стоит посреди дороги", "Residents complain: the golem is standing in the road again"),
    ("В универсаме завоз пельменей — очередь с утра", "Pelmeni delivered to the supermarket: a queue since morning"),
    ("Учёные доказали: крипер — не друг человека", "Scientists prove the creeper is not man's friend"),
    ("Глава администрации обещал починить фонари к зиме", "The head of administration promises to fix the lamps by winter"),
    ("Огородники района: урожай свёклы небывалый", "District gardeners: an unheard-of beetroot harvest"),
    ("Кто ворует тележки из универсама? Расследование", "Who steals the supermarket's carts? An investigation"),
    ("Погода: ночью возможны зомби, днём ясно", "Weather: zombies possible at night, clear by day"),
    ("Ветеран труда сорок лет торгует на рынке квасом", "A labour veteran has sold kvass at the market for forty years"),
    ("Скандал: продавщица отказалась уступить изумруд", "Scandal: a vendor refused to knock an emerald off"),
    ("Новый маршрут автобуса так никто и не видел", "Nobody has yet seen the new bus route"),
    ("Сосед построил дачу выше ратуши — проверка", "A neighbour built a dacha taller than the town hall: an inspection"),
    ("Кроссворд на последней странице. Ответы — в следующем номере", "Crossword on the back page. Answers in the next issue"),
]
JOKES = [
    ("Карикатура: охранник универсама спит на посту, мимо идёт крипер с тележкой", "A cartoon: the shop guard asleep at his desk, a creeper wheels a cart past"),
    ("«— Почём огурцы? — Для вас, молодой человек, как для всех»", "\"How much are the cucumbers?\" \"For you, young man, same as for everyone\""),
    ("Карикатура: чиновник прячет конверт в папку «Благоустройство»", "A cartoon: an official tucks an envelope into the «Improvements» folder"),
    ("«Купил шаурму в ларьке. Ларёк закрыт на санобработку»", "\"Bought a shawarma at the kiosk. The kiosk is closed for disinfection\""),
    ("Карикатура: житель меняет изумруд на изумруд и доволен", "A cartoon: a villager trades an emerald for an emerald and is pleased"),
    ("«— Это у вас касса самообслуживания? — Нет, это у вас»", "\"Is this your self-checkout?\" \"No, it is yours\""),
]
CLUES = [
    ("По горизонтали: «Зелёный, взрывается, 6 букв»", "Across: «Green, explodes, 7 letters»"),
    ("По вертикали: «Деревенский житель, торгует изумрудами»", "Down: «Village dweller, trades in emeralds»"),
    ("По горизонтали: «Квас из бочки, цвет её»", "Across: «Kvass from a barrel: its colour»"),
    ("По вертикали: «Пельмени без неё — не пельмени»", "Down: «Pelmeni are not pelmeni without it»"),
    ("По горизонтали: «Тот, кто ловит у рамки»", "Across: «The one who catches you at the gate»"),
    ("Не сходится. Кто-то вписал «ГОЛЕМ» поперёк", "It does not fit. Someone wrote «GOLEM» across"),
]

NAMES = {
    "item.mobtalk.newspaper": ("Газета «Районная правда»", "The «District Pravda» newspaper"),
    "item.mobtalk.krokodil": ("Журнал «Крокодил»", "«Krokodil» magazine"),
    "item.mobtalk.crossword": ("Сборник кроссвордов", "Crossword booklet"),
    "item.mobtalk.shawarma": ("Шаурма", "Shawarma"),
    "item.mobtalk.cheburek": ("Чебурек", "Cheburek"),
    "item.mobtalk.belyash": ("Беляш", "Belyash"),
    "item.mobtalk.tea_cup": ("Чай в стаканчике", "Tea in a paper cup"),
    "item.mobtalk.beer": ("Пиво «Жигулёвское»", "«Zhigulevskoye» beer"),
    "item.mobtalk.sukhariki": ("Сухарики", "Rusks"),
    "item.mobtalk.vobla": ("Вобла", "Dried roach"),
    "item.mobtalk.gum": ("Жвачка", "Chewing gum"),
    "block.mobtalk.kiosk_press": ("Окошко «Союзпечати»", "«Soyuzpechat» kiosk window"),
    "block.mobtalk.kiosk_shawarma": ("Окошко шаурмичной", "Shawarma kiosk window"),
    "block.mobtalk.kiosk_beer": ("Окошко ларька «Табак · Пиво»", "«Tobacco · Beer» kiosk window"),
    "mobtalk.kiosk.read.empty": ("Всё прочитано от корки до корки.", "Read from cover to cover."),
    "mobtalk.kiosk.call.press.0": ("Свежая «Районная правда»! Вся правда про голема!", "Fresh «District Pravda»! The whole truth about the golem!"),
    "mobtalk.kiosk.call.press.1": ("Кроссворды, сканворды, «Крокодил» — подходим!", "Crosswords, «Krokodil», come and look!"),
    "mobtalk.kiosk.call.press.2": ("Программа передач на неделю, молодой человек?", "The TV guide for the week, young man?"),
    "mobtalk.kiosk.call.press.3": ("Открытки к празднику остались, берите!", "Holiday postcards left, take one!"),
    "mobtalk.kiosk.call.shawarma.0": ("Шаурма горячая, сырная, большая!", "Hot shawarma, with cheese, a big one!"),
    "mobtalk.kiosk.call.shawarma.1": ("Брат, шаурму будешь? Свежая, клянусь!", "Brother, want a shawarma? Fresh, I swear!"),
    "mobtalk.kiosk.call.shawarma.2": ("Чебуреки только из масла!", "Chebureks straight out of the oil!"),
    "mobtalk.kiosk.call.shawarma.3": ("Чай горячий, с сахаром, подходи греться!", "Hot tea with sugar, come and warm up!"),
    "mobtalk.kiosk.call.beer.0": ("Пиво холодное, вобла есть!", "Cold beer, dried roach too!"),
    "mobtalk.kiosk.call.beer.1": ("Семечки, сухарики, жвачка — всё есть.", "Seeds, rusks, gum: we have it all."),
    "mobtalk.kiosk.call.beer.2": ("Паспорт покажем, молодой человек? Шучу, шучу.", "Show me your passport, young man? Just kidding."),
    "mobtalk.kiosk.call.beer.3": ("Квас тоже есть, если что.", "There is kvass too, if you like."),
}


def lang():
    path = os.path.join(ASSETS, "lang")
    for i, code in enumerate(("ru_ru", "en_us")):
        f = os.path.join(path, code + ".json")
        data = json.load(open(f, encoding="utf-8"))
        for key, pair in NAMES.items():
            data[key] = pair[i]
        for item, lines in (("newspaper", HEADLINES), ("krokodil", JOKES), ("crossword", CLUES)):
            for n, pair in enumerate(lines):
                data[f"mobtalk.kiosk.read.{item}.{n}"] = pair[i]
        decor.write(f, data)
    # the counts the game picks lines from (io.github.mobtalk.game.ShopFoods.Readable)
    return {"newspaper": len(HEADLINES), "krokodil": len(JOKES), "crossword": len(CLUES)}
