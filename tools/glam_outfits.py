"""Generates the look of Mob Talk's mercantile city women: six glam outfits on the 64x64 player-skin layout (clothes layer of the
human villagers) and three handbags (item textures and 3D item models) that they carry in the off hand.

    python tools/glam_outfits.py            # writes into src/client/resources and src/main/resources
"""
import json, os, random
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), '..')
CLOTHES = os.path.join(ROOT, 'src/client/resources/assets/mobtalk/human/clothing/glam')
ITEM_TEX = os.path.join(ROOT, 'src/main/resources/assets/mobtalk/textures/item')
ITEM_MODEL = os.path.join(ROOT, 'src/main/resources/assets/mobtalk/models/item')

# box name -> (u, v, w, h, d); the overlay boxes (hat, jacket, sleeves, pants) share the shape of their base box
BOXES = {
    'head': (0, 0, 8, 8, 8), 'hat': (32, 0, 8, 8, 8),
    'body': (16, 16, 8, 12, 4), 'jacket': (16, 32, 8, 12, 4),
    'rarm': (40, 16, 4, 12, 4), 'larm': (32, 48, 4, 12, 4), 'rsleeve': (40, 32, 4, 12, 4), 'lsleeve': (48, 48, 4, 12, 4),
    'rleg': (0, 16, 4, 12, 4), 'lleg': (16, 48, 4, 12, 4), 'rpants': (0, 32, 4, 12, 4), 'lpants': (0, 48, 4, 12, 4),
}
def faces(u, v, w, h, d):
    return {'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d), 'right': (u, v + d, d, h), 'front': (u + d, v + d, w, h),
            'left': (u + d + w, v + d, d, h), 'back': (u + 2 * d + w, v + d, w, h)}

def paint(img, box, fn):
    u, v, w, h, d = BOXES[box]
    for face, (x0, y0, fw, fh) in faces(u, v, w, h, d).items():
        for y in range(fh):
            for x in range(fw):
                c = fn(face, x, y, fw, fh)
                if c is not None: img.putpixel((x0 + x, y0 + y), tuple(c) + (255,) if len(c) == 3 else tuple(c))

def shade(c, k): return tuple(max(0, min(255, int(ch * k))) for ch in c[:3])
def mix(a, b, t): return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))

GOLD, GOLD_HI, GOLD_LO = (226, 184, 72), (255, 230, 140), (160, 120, 40)
PEARL = (240, 236, 228)
BLACK = (22, 20, 24)

def accessories(img, rng, glasses=True, earrings=GOLD, lipstick=None):
    """Sunglasses over the eyes (head front rows 4-5), earrings on the head sides."""
    def head(face, x, y, w, h):
        if glasses and face == 'front':
            if y == 4 and x in range(0, 8): return GOLD if x in (0, 3, 4, 7) else BLACK
            if y == 5 and (x in (0, 1, 2) or x in (5, 6, 7)): return (40, 30, 48) if x not in (1, 6) else (90, 70, 110)
        if lipstick and face == 'front' and y == 6 and x in (3, 4): return None
        if earrings and face in ('right', 'left') and y in (5, 6) and x == (5 if face == 'right' else 2):
            return GOLD_HI if y == 5 else earrings
        return None
    paint(img, 'head', head)

def necklace(face, x, y, color, pendant=None):
    """A chain round the neck on the body's front: row 0-1 V shape."""
    if face != 'front': return None
    if y == 0 and x in (1, 6): return color
    if y == 1 and x in (2, 5): return color
    if y == 2 and x in (3, 4): return pendant or color
    return None

def heels(color, sole=BLACK, tall=3):
    def leg(face, x, y, w, h):
        if face == 'bottom': return sole
        if face == 'top': return None
        if y >= 12 - tall:
            if y == 11: return sole if not (face == 'back' and x == 1) else shade(sole, 0.6)
            if face == 'front' and y == 12 - tall and x in (1, 2): return shade(color, 1.25)
            return color
        return None
    return leg

def fur_coat(base, dark, inner, legs):
    rng = random.Random(1)
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    def fur(x, y, k=1.0):
        n = rng.random()
        return shade(base, k * (0.86 if n < 0.25 else 1.06 if n > 0.8 else 0.97)) if n > 0.06 else shade(dark, k)
    def body(face, x, y, w, h):
        if face == 'front' and 3 <= x <= 4 and y <= 6: return inner if y > 0 else shade(inner, 0.8)
        c = necklace(face, x, y, GOLD, (200, 30, 60))
        if c: return c
        return fur(x, y)
    paint(img, 'body', body)
    def coat(face, x, y, w, h):  # объёмная шуба поверх: воротник, полы до колен
        if face == 'top': return fur(x, y, 1.05)
        if face == 'bottom': return None
        if face == 'front' and 3 <= x <= 4 and 1 <= y <= 8: return None  # распахнута
        if y <= 1: return fur(x, y, 1.12)  # пушистый воротник
        return fur(x, y)
    paint(img, 'jacket', coat)
    def sleeve(face, x, y, w, h):
        if face == 'bottom': return None
        if y == 11: return None
        if y >= 9: return fur(x, y, 1.12)  # пушистые манжеты
        return fur(x, y)
    paint(img, 'rsleeve', sleeve); paint(img, 'lsleeve', sleeve)
    def arm(face, x, y, w, h):
        if face == 'top': return base
        if y == 11 and face == 'front' and x == 1: return GOLD  # перстень
        return None
    paint(img, 'rarm', arm); paint(img, 'larm', arm)
    def coat_hem(face, x, y, w, h):  # полы шубы на бёдрах
        if face in ('top', 'bottom'): return None
        if y <= 4: return fur(x, y, 0.98 if y < 4 else 1.1)
        return None
    paint(img, 'rpants', coat_hem); paint(img, 'lpants', coat_hem)
    def leg(face, x, y, w, h):
        if face == 'top': return legs
        if face == 'bottom': return BLACK
        if y >= 6: return BLACK if y < 11 else shade(BLACK, 0.7) if not (face == 'front' and y == 6) else (60, 50, 50)  # ботфорты
        return legs
    paint(img, 'rleg', leg); paint(img, 'lleg', leg)
    accessories(img, rng)
    return img

def leopard_dress():
    rng = random.Random(2)
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    base, spot, ring = (214, 160, 84), (60, 40, 24), (120, 78, 36)
    spots = set()
    def leo(face, box, x, y):
        key = (box, face, x, y)
        r = random.Random(hash(key) & 0xffff).random()
        if r < 0.10: return spot
        if r < 0.22: return ring
        return shade(base, 1.0 if r > 0.6 else 0.94)
    def body(face, x, y, w, h):
        if face == 'top': return leo(face, 'b', x, y)
        if face == 'bottom': return leo(face, 'b', x, y)
        if face in ('front', 'back') and y <= 1 and 2 <= x <= 5: return None  # вырез
        if y == 7: return GOLD if x % 2 == 0 else GOLD_HI  # золотой пояс-цепь
        c = necklace(face, x, y, GOLD_HI)
        if c and y <= 2: return c
        return leo(face, 'b', x, y)
    paint(img, 'body', body)
    def skirt(face, x, y, w, h):
        if face in ('top', 'bottom'): return None
        if 8 <= y <= 11: return leo(face, 'j', x, y)
        return None
    paint(img, 'jacket', skirt)
    def legs_top(face, x, y, w, h):  # мини: платье по верх бедра
        if face == 'top': return leo(face, 'l', x, y)
        if y <= 2: return leo(face, 'l' + str(w), x, y)
        return None
    paint(img, 'rpants', legs_top); paint(img, 'lpants', legs_top)
    paint(img, 'rleg', heels((20, 18, 20))); paint(img, 'lleg', heels((20, 18, 20)))
    def arm(face, x, y, w, h):
        if face == 'bottom' or face == 'top': return None
        if y == 8 and face in ('front', 'right'): return GOLD_HI  # браслет
        return None
    paint(img, 'rarm', arm); paint(img, 'larm', arm)
    accessories(img, rng)
    return img

def tracksuit(main, stripe, zipper=GOLD):
    rng = random.Random(3)
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    def velour(x, y): return shade(main, 1.04 if (x * 3 + y * 5) % 7 == 0 else 1.0 if (x + y) % 2 else 0.96)
    def body(face, x, y, w, h):
        if face == 'top': return velour(x, y)
        if face == 'bottom': return velour(x, y)
        if face == 'front':
            if 3 <= x <= 4 and y <= 1: return (250, 240, 236)  # топ под кофтой
            if x == 4 and y >= 1: return zipper if y % 2 else GOLD_LO
            if y == 11: return shade(main, 0.8)
        if face == 'back' and 1 <= y <= 3 and 2 <= x <= 5:  # стразы на спине
            return (255, 250, 255) if (x + y) % 2 == 0 else shade(main, 1.15)
        return velour(x, y)
    paint(img, 'body', body)
    def arm(face, x, y, w, h):
        if face == 'top': return velour(x, y)
        if face == 'bottom': return None
        if face in ('right', 'left') and x == 1 and y < 10: return stripe
        if y == 10: return shade(main, 0.8)
        if y == 11: return None
        return velour(x, y)
    paint(img, 'rarm', arm); paint(img, 'larm', arm)
    def leg(face, x, y, w, h):
        if face == 'top': return velour(x, y)
        if face == 'bottom': return (240, 240, 240)
        if y >= 10: return (250, 250, 250) if not (y == 11) else (200, 200, 205)  # белые кроссовки
        if face in ('right', 'left') and x == 1: return stripe
        return velour(x, y)
    paint(img, 'rleg', leg); paint(img, 'lleg', leg)
    accessories(img, rng, glasses=True, earrings=GOLD)
    return img

def evening_dress(main, sparkle):
    rng = random.Random(4)
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    def sequin(face, x, y):
        r = random.Random((x * 31 + y * 17 + len(face) * 7) & 0xffff).random()
        return sparkle if r < 0.12 else shade(main, 1.15) if r < 0.3 else main if r < 0.75 else shade(main, 0.9)
    def body(face, x, y, w, h):
        if face == 'top': return None if 2 <= x <= 5 else sequin(face, x, y)
        if face == 'bottom': return sequin(face, x, y)
        if face in ('front',) and y <= 2 and 2 <= x <= 5 and not (y == 2 and x in (3, 4)): return None  # глубокий вырез
        if face == 'back' and y <= 4 and 2 <= x <= 5: return None  # открытая спина
        c = necklace(face, x, y, PEARL)
        if c: return c
        return sequin(face, x, y)
    paint(img, 'body', body)
    def skirt_over(face, x, y, w, h):
        if face in ('top', 'bottom'): return None
        if y >= 9: return sequin(face + 'o', x, y)
        return None
    paint(img, 'jacket', skirt_over)
    def gown(face, x, y, w, h):  # длинное платье до щиколоток, разрез на правом бедре
        if face == 'top': return sequin(face, x, y)
        if face == 'bottom': return None
        if y >= 11: return None
        return sequin(face + 'g' + str(w), x, y)
    def gown_slit(face, x, y, w, h):
        if face == 'front' and x <= 1 and 3 <= y <= 10: return None
        return gown(face, x, y, w, h)
    paint(img, 'rpants', gown_slit); paint(img, 'lpants', gown)
    paint(img, 'rleg', heels(shade(main, 0.8), tall=2)); paint(img, 'lleg', heels(shade(main, 0.8), tall=2))
    def glove(face, x, y, w, h):  # длинные перчатки
        if face in ('top', 'bottom'): return None
        if y >= 5: return (24, 22, 26) if y != 8 or face != 'front' else sparkle
        return None
    paint(img, 'rarm', glove); paint(img, 'larm', glove)
    accessories(img, rng, glasses=False, earrings=PEARL)
    return img

def little_black_dress():
    rng = random.Random(5)
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    main = (28, 26, 32)
    def body(face, x, y, w, h):
        if face == 'top': return None if 2 <= x <= 5 else main
        if face == 'bottom': return main
        if face == 'front' and y == 0 and 1 <= x <= 6: return None
        if face == 'front' and y == 1 and x in (0, 1, 6, 7): return PEARL if x in (1, 6) else main
        if face == 'front' and y == 2 and x in (2, 5): return PEARL
        if face == 'front' and y == 3 and x in (3, 4): return PEARL
        if y == 6: return (200, 30, 50) if face == 'front' and x == 5 else shade(main, 1.4)  # пояс с брошью
        return shade(main, 1.15) if (x + y) % 6 == 0 else main
    paint(img, 'body', body)
    def skirt(face, x, y, w, h):
        if face in ('top', 'bottom'): return None
        if y >= 8: return main if y < 11 else shade(main, 1.3)
        return None
    paint(img, 'jacket', skirt)
    def legs_top(face, x, y, w, h):
        if face == 'top': return main
        if y <= 1: return main
        return None
    paint(img, 'rpants', legs_top); paint(img, 'lpants', legs_top)
    paint(img, 'rleg', heels((170, 20, 40))); paint(img, 'lleg', heels((170, 20, 40)))  # красные туфли
    accessories(img, rng, glasses=True, earrings=PEARL)
    return img

def white_suit():
    rng = random.Random(6)
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    main, top = (244, 240, 232), (232, 196, 200)
    def body(face, x, y, w, h):
        if face == 'top': return main
        if face == 'bottom': return main
        if face == 'front':
            if 2 <= x <= 5 and y <= 3: return top
            if y <= 5 and x in (2, 5): return shade(main, 0.9)  # лацканы
            if y == 8: return GOLD if x % 2 else GOLD_HI  # цепь-пояс
            if y == 5 and x == 3: return GOLD
        if y == 8: return GOLD if x % 2 else GOLD_HI
        return main if (x + y) % 5 else shade(main, 0.96)
    paint(img, 'body', body)
    def arm(face, x, y, w, h):
        if face == 'top': return main
        if face == 'bottom': return None
        if y <= 9: return main if y != 9 else shade(main, 0.9)
        if y == 10 and face == 'front': return GOLD_HI  # часы
        return None
    paint(img, 'rarm', arm); paint(img, 'larm', arm)
    def leg(face, x, y, w, h):
        if face == 'top': return main
        if face == 'bottom': return (200, 160, 120)
        if y == 11: return (210, 170, 128)  # бежевые лодочки
        if face == 'front' and x == 1 and y < 10: return shade(main, 0.92)  # стрелки на брюках
        return main
    paint(img, 'rleg', leg); paint(img, 'lleg', leg)
    accessories(img, rng, glasses=True)
    return img

OUTFITS = [
    fur_coat((236, 232, 226), (190, 184, 176), (210, 30, 70), (226, 196, 176)),  # белая норка
    fur_coat((120, 82, 56), (78, 52, 34), (24, 20, 26), (210, 180, 160)),        # соболь
    leopard_dress(),
    tracksuit((236, 120, 170), (250, 250, 250)),                                 # розовый велюр
    evening_dress((176, 20, 40), (255, 236, 200)),                               # красное в блёстках
    little_black_dress(),
    white_suit(),
]

# ---- Сумочки: 16x16 текстура, 3D-модель предмета ----
BAGS = {
    'handbag_pink': ((236, 120, 170), (250, 200, 224), GOLD),
    'handbag_leopard': ((214, 160, 84), (60, 40, 24), GOLD),
    'handbag_black': ((30, 28, 34), (70, 66, 80), GOLD_HI),
}
def bag_texture(main, pattern, metal):
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    rng = random.Random(7)
    for y in range(8):  # 0..7: бок сумки (кожа), квадрат 8x8 в левом верху
        for x in range(8):
            c = main
            if main[0] == 214:  # леопард
                r = rng.random(); c = pattern if r < 0.15 else (120, 78, 36) if r < 0.3 else main
            elif (x + y) % 4 == 0: c = shade(main, 1.12)
            if y == 0: c = shade(main, 1.25)
            if y == 7: c = shade(main, 0.75)
            img.putpixel((x, y), c + (255,))
    for y in range(8):  # 8..15: клапан и замок
        for x in range(8, 16):
            c = shade(main, 0.9) if y < 4 else main
            if y == 4: c = shade(main, 0.7)
            if 11 <= x <= 12 and 3 <= y <= 5: c = metal if y != 4 else GOLD_HI
            img.putpixel((x, y), c + (255,))
    for y in range(8, 10):  # ручка-цепочка
        for x in range(16): img.putpixel((x, y), (metal if (x + y) % 2 else GOLD_LO) + (255,))
    return img

def box(fr, to, uv_side, uv_front, uv_top):
    f = {'north': {'uv': uv_front, 'texture': '#t'}, 'south': {'uv': uv_front, 'texture': '#t'},
         'east': {'uv': uv_side, 'texture': '#t'}, 'west': {'uv': uv_side, 'texture': '#t'},
         'up': {'uv': uv_top, 'texture': '#t'}, 'down': {'uv': uv_top, 'texture': '#t'}}
    return {'from': fr, 'to': to, 'faces': f}

def bag_model(name):
    side, front, top, chain = [0, 0, 2, 8], [0, 0, 8, 8], [8, 0, 16, 4], [0, 8, 16, 10]
    elements = [
        box([4, 2, 6.5], [12, 8, 9.5], side, front, top),          # корпус
        box([3.9, 6, 6.4], [12.1, 8.2, 9.6], [8, 0, 10, 4], [8, 0, 16, 8], top),  # клапан
        box([5, 8, 7.75], [5.5, 12, 8.25], chain, chain, chain),   # ручка: левая стойка
        box([10.5, 8, 7.75], [11, 12, 8.25], chain, chain, chain), # правая стойка
        box([5, 11.5, 7.75], [11, 12, 8.25], chain, chain, chain), # верх ручки
    ]
    display = {
        # Висит на руке: ручка в кулаке, сумка ниже кисти.
        'thirdperson_righthand': {'rotation': [0, 90, 0], 'translation': [0, -3.5, 1.5], 'scale': [0.7, 0.7, 0.7]},
        'thirdperson_lefthand': {'rotation': [0, 90, 0], 'translation': [0, -3.5, 1.5], 'scale': [0.7, 0.7, 0.7]},
        'firstperson_righthand': {'rotation': [0, -20, 0], 'translation': [1, 1, 0], 'scale': [0.6, 0.6, 0.6]},
        'firstperson_lefthand': {'rotation': [0, -20, 0], 'translation': [1, 1, 0], 'scale': [0.6, 0.6, 0.6]},
        'gui': {'rotation': [20, -30, 0], 'translation': [0, 1, 0], 'scale': [1.0, 1.0, 1.0]},
        'ground': {'translation': [0, 1, 0], 'scale': [0.5, 0.5, 0.5]},
        'fixed': {'scale': [0.9, 0.9, 0.9]},
    }
    return {'textures': {'t': 'mobtalk:item/' + name, 'particle': 'mobtalk:item/' + name}, 'elements': elements, 'display': display}

if __name__ == '__main__':
    os.makedirs(CLOTHES, exist_ok=True)
    for i, img in enumerate(OUTFITS): img.save(os.path.join(CLOTHES, f'{i}.png'))
    for name, (main, pattern, metal) in BAGS.items():
        bag_texture(main, pattern, metal).save(os.path.join(ITEM_TEX, name + '.png'))
        with open(os.path.join(ITEM_MODEL, name + '.json'), 'w', encoding='utf-8') as out: json.dump(bag_model(name), out, indent=1)
    print('ok', len(OUTFITS), 'outfits,', len(BAGS), 'bags')
