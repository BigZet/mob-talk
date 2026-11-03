"""Арт игры в 21 (docs/cards-21.md): свой пиксель-арт, целиком нарисован этим скриптом; лицензия — как у мода.

    python tools/cards_art.py                 # иконка колоды и атлас стола в src/*/resources
    python tools/cards_art.py --sheet P.png   # лист образцов (8 карт крупно) для показа

Атлас textures/gui/cards.png, 512×512 (пиксель-арт, рисуется без сглаживания):
  y 0–191: 52 лица по 32×48 по порядку Card.code() — пики, червы, бубны, трефы; от двойки до туза;
  y 192: рубашки красная, синяя, зелёная (x 0, 32, 64), пустая карта (x 96), тень карты 36×52 (x 128);
  y 256: фишки-изумруды 24×16 номиналом 1, 5, 9 (x 0, 24, 48), тень фишки 28×10 (x 72);
  y 288: иконки кнопок 12×12 через 16 (ICON_ORDER); y 304: изумруд 10×10 (x 0), эмблема 32×32 (x 32).
Крупные гладкие текстуры: card_table.png (стол 1024×576: борт, золото, сукно, шов, разметка мест), card_portrait.png (рамка
портрета), card_nameplate.png (табличка с именем), card_lamp.png (луч лампы).
Иконка предмета 16×16: textures/item/card_deck.png.
"""
import argparse
import os

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ITEM = os.path.join(ROOT, "src", "main", "resources", "assets", "mobtalk", "textures", "item", "card_deck.png")
ATLAS = os.path.join(ROOT, "src", "client", "resources", "assets", "mobtalk", "textures", "gui", "cards.png")

W, H = 32, 48
T = (0, 0, 0, 0)
INK = (40, 32, 44, 255)
PAPER = (247, 242, 229, 255)
PAPER_SHADE = (226, 216, 196, 255)
PAPER_LIGHT = (255, 252, 244, 255)
RED = (200, 38, 46, 255)
RED_DARK = (138, 22, 30, 255)
BLACK = (36, 34, 42, 255)
BLACK_SOFT = (78, 76, 88, 255)
GOLD = (232, 188, 72, 255)
GOLD_DARK = (168, 120, 36, 255)
# Житель: кожа, нос, брови, глаза.
SKIN = (190, 132, 98, 255)
SKIN_DARK = (150, 98, 70, 255)
NOSE = (166, 110, 80, 255)
BROW = (72, 46, 34, 255)
EYE = (52, 140, 64, 255)
EYE_WHITE = (240, 240, 236, 255)

# ---------------------------------------------------------------- шрифт индекса (3×5) и масти

GLYPHS = {
    "2": ["XXX", "..X", "XXX", "X..", "XXX"],
    "3": ["XXX", "..X", ".XX", "..X", "XXX"],
    "4": ["X.X", "X.X", "XXX", "..X", "..X"],
    "5": ["XXX", "X..", "XXX", "..X", "XXX"],
    "6": ["XXX", "X..", "XXX", "X.X", "XXX"],
    "7": ["XXX", "..X", ".X.", ".X.", ".X."],
    "8": ["XXX", "X.X", "XXX", "X.X", "XXX"],
    "9": ["XXX", "X.X", "XXX", "..X", "XXX"],
    "1": [".X", "XX", ".X", ".X", ".X"],
    "0": ["XXX", "X.X", "X.X", "X.X", "XXX"],
    "В": ["XX.", "X.X", "XX.", "X.X", "XX."],
    "Д": [".XX.", ".X.X", ".X.X", "XXXX", "X..X"],
    "К": ["X.X", "XX.", "X..", "XX.", "X.X"],
    "Т": ["XXX", ".X.", ".X.", ".X.", ".X."],
}
LABELS = ["2", "3", "4", "5", "6", "7", "8", "9", "10", "В", "Д", "К", "Т"]

PIPS = {  # 5×5
    "S": ["..X..", ".XXX.", "XXXXX", "X.X.X", "..X.."],
    "H": [".X.X.", "XXXXX", "XXXXX", ".XXX.", "..X.."],
    "D": ["..X..", ".XXX.", "XXXXX", ".XXX.", "..X.."],
    "C": [".XXX.", ".XXX.", "XXXXX", "XXXXX", "..X.."],
}
SMALL = {  # 3×3 для угла под индексом
    "S": [".X.", "XXX", ".X."],
    "H": ["X.X", "XXX", ".X."],
    "D": [".X.", "XXX", ".X."],
    "C": [".X.", "XXX", "X.X"],
}
SUITS = ["S", "H", "D", "C"]  # порядок Suit: пики, червы, бубны, трефы


def color_of(suit):
    return RED if suit in ("H", "D") else BLACK


def stamp(img, x, y, rows, color, flip=False):
    px = img.load()
    h = len(rows)
    w = len(rows[0])
    for dy, row in enumerate(rows):
        for dx, ch in enumerate(row):
            if ch != "X":
                continue
            tx, ty = (x + dx, y + dy) if not flip else (x + w - 1 - dx, y + h - 1 - dy)
            px[tx, ty] = color


def text(img, x, y, label, color, flip=False):
    """Индекс: глифы через пиксель. При flip рисуется перевёрнутым так, чтобы (x, y) был правым нижним углом."""
    glyphs = [GLYPHS[c] for c in (["1", "0"] if label == "10" else [label])]
    width = sum(len(g[0]) for g in glyphs) + len(glyphs) - 1
    if not flip:
        cx = x
        for g in glyphs:
            stamp(img, cx, y, g, color)
            cx += len(g[0]) + 1
    else:
        cx = x - width + 1
        for g in reversed(glyphs):
            stamp(img, cx, y - 4, g, color, flip=True)
            cx += len(g[0]) + 1
    return width


def blank_card(img, ox, oy, fill=PAPER):
    px = img.load()
    for y in range(H):
        for x in range(W):
            corner = (x in (0, W - 1) and y in (0, 1, H - 2, H - 1)) or (y in (0, H - 1) and x in (0, 1, W - 2, W - 1))
            if corner:
                continue
            edge = x in (0, W - 1) or y in (0, H - 1) or (x in (1, W - 2) and y in (1, H - 2))
            if edge:
                px[ox + x, oy + y] = INK
            else:
                c = fill
                if x >= W - 3 or y >= H - 3:
                    c = PAPER_SHADE if fill == PAPER else c
                if (x <= 2 or y <= 2) and fill == PAPER:
                    c = PAPER_LIGHT
                px[ox + x, oy + y] = c


def corners(img, ox, oy, label, suit):
    """Индекс и масть 5×5 в левом верхнем углу (x 2..6, y 3..13) и они же, перевёрнутые, в правом нижнем (x 25..29, y 34..44)."""
    c = color_of(suit)
    text(img, ox + 2, oy + 3, label, c)
    stamp(img, ox + 2, oy + 9, PIPS[suit], c)
    text(img, ox + W - 3, oy + H - 4, label, c, flip=True)
    stamp(img, ox + W - 7, oy + H - 14, PIPS[suit], c, flip=True)


# Раскладка очков на поле 32×48: середина колонки x = 8/13.5/19, ряды y ≈ 8..36 (верхний левый угол пипа 5×5).
COL = {"L": 8, "M": 13, "R": 18}
LAYOUT = {
    2: [("M", 8), ("M", 34)],
    3: [("M", 8), ("M", 21), ("M", 34)],
    4: [("L", 8), ("R", 8), ("L", 34), ("R", 34)],
    5: [("L", 8), ("R", 8), ("M", 21), ("L", 34), ("R", 34)],
    6: [("L", 8), ("R", 8), ("L", 21), ("R", 21), ("L", 34), ("R", 34)],
    7: [("L", 8), ("R", 8), ("M", 14), ("L", 21), ("R", 21), ("L", 34), ("R", 34)],
    8: [("L", 8), ("R", 8), ("M", 14), ("L", 21), ("R", 21), ("M", 28), ("L", 34), ("R", 34)],
    9: [("L", 8), ("R", 8), ("L", 17), ("R", 17), ("M", 21), ("L", 25), ("R", 25), ("L", 34), ("R", 34)],
    10: [("L", 8), ("R", 8), ("M", 12), ("L", 17), ("R", 17), ("L", 25), ("R", 25), ("M", 30), ("L", 34), ("R", 34)],
}


def number_card(img, ox, oy, rank, suit):
    blank_card(img, ox, oy)
    corners(img, ox, oy, LABELS[rank - 2], suit)
    for col, y in LAYOUT[rank]:
        flip = y > 22
        stamp(img, ox + COL[col] + 1, oy + y, PIPS[suit], color_of(suit), flip=flip)


# ---------------------------------------------------------------- картинки: жители

def rect(img, x0, y0, x1, y1, color):
    px = img.load()
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[x, y] = color


def villager_head(img, x, y, hat=None):
    """Голова жителя 10×12 с носом: (x, y) — левый верхний угол лба."""
    rect(img, x, y, x + 9, y + 9, SKIN)
    rect(img, x, y + 9, x + 9, y + 9, SKIN_DARK)
    rect(img, x + 1, y + 3, x + 8, y + 3, BROW)          # одна бровь
    for ex in (x + 2, x + 6):
        img.putpixel((ex, y + 4), EYE_WHITE)
        img.putpixel((ex + 1, y + 4), EYE)
    rect(img, x + 4, y + 4, x + 5, y + 10, NOSE)          # нос
    rect(img, x + 4, y + 10, x + 5, y + 10, SKIN_DARK)
    rect(img, x + 2, y + 8, x + 3, y + 8, SKIN_DARK)      # рот
    rect(img, x + 6, y + 8, x + 7, y + 8, SKIN_DARK)


def robe(img, x, y, w, h, color, trim=None, belt=None):
    rect(img, x, y, x + w - 1, y + h - 1, color)
    if trim:
        rect(img, x + w // 2 - 1, y, x + w // 2, y + h - 1, trim)
    if belt:
        rect(img, x, y + 3, x + w - 1, y + 3, belt)


def arms(img, x, y, w, sleeve):
    """Руки жителя, скрещённые на груди: рукава поперёк и кисти по краям."""
    rect(img, x, y, x + w - 1, y + 3, sleeve)
    rect(img, x, y + 3, x + w - 1, y + 3, SKIN_DARK)
    rect(img, x + 1, y + 1, x + 2, y + 2, SKIN)
    rect(img, x + w - 3, y + 1, x + w - 2, y + 2, SKIN)


# Рамка картинки: x 7..24, y 3..44 — между индексами углов; внутри 16×40.
FX0, FX1, FY0, FY1 = 7, 24, 3, 44


def frame(img, ox, oy, suit):
    c = color_of(suit)
    for x in range(FX0, FX1 + 1):
        img.putpixel((ox + x, oy + FY0), c)
        img.putpixel((ox + x, oy + FY1), c)
    for y in range(FY0, FY1 + 1):
        img.putpixel((ox + FX0, oy + y), c)
        img.putpixel((ox + FX1, oy + y), c)


def jack_farmer(img, ox, oy, suit):
    """Валет — фермер в соломенной шляпе, с мотыгой. Внутри рамки x 8..23."""
    blank_card(img, ox, oy)
    corners(img, ox, oy, "В", suit)
    frame(img, ox, oy, suit)
    straw, straw_dark = (226, 196, 104, 255), (170, 132, 56, 255)
    shirt, shirt_dark = (122, 84, 52, 255), (92, 60, 38, 255)
    hx, hy = ox + 11, oy + 12
    rect(img, hx + 1, hy - 5, hx + 8, hy - 3, straw)          # тулья
    rect(img, hx + 1, hy - 3, hx + 8, hy - 3, straw_dark)
    rect(img, hx - 2, hy - 2, hx + 11, hy - 1, straw)         # поля
    rect(img, hx - 2, hy - 1, hx + 11, hy - 1, straw_dark)
    villager_head(img, hx, hy)
    robe(img, ox + 10, oy + 23, 12, 20, shirt, trim=shirt_dark, belt=straw_dark)
    rect(img, ox + 10, oy + 23, ox + 21, oy + 23, (90, 140, 70, 255))   # зелёный ворот
    arms(img, ox + 10, oy + 29, 12, (104, 70, 44, 255))
    rect(img, ox + 22, oy + 14, ox + 22, oy + 42, (120, 90, 50, 255))   # черенок мотыги
    rect(img, ox + 20, oy + 13, ox + 23, oy + 13, (150, 150, 158, 255))  # лезвие
    rect(img, ox + 20, oy + 14, ox + 20, oy + 14, (110, 110, 118, 255))


def queen_librarian(img, ox, oy, suit):
    """Дама — библиотекарша в очках и белой шапочке, с красной книгой."""
    blank_card(img, ox, oy)
    corners(img, ox, oy, "Д", suit)
    frame(img, ox, oy, suit)
    white, white_dark = (240, 236, 226, 255), (178, 170, 156, 255)
    book, book_dark = (150, 34, 40, 255), (100, 20, 26, 255)
    hx, hy = ox + 11, oy + 12
    rect(img, hx - 1, hy - 4, hx + 10, hy - 1, white_dark)     # шапочка с каймой
    rect(img, hx, hy - 3, hx + 9, hy - 2, white)
    rect(img, hx - 1, hy - 1, hx + 10, hy - 1, (200, 40, 48, 255))
    villager_head(img, hx, hy)
    rect(img, hx + 1, hy + 4, hx + 3, hy + 4, GOLD_DARK)       # очки
    rect(img, hx + 6, hy + 4, hx + 8, hy + 4, GOLD_DARK)
    img.putpixel((hx + 2, hy + 4), EYE)
    img.putpixel((hx + 7, hy + 4), EYE)
    robe(img, ox + 10, oy + 23, 12, 20, white, trim=white_dark)
    rect(img, ox + 10, oy + 23, ox + 10, oy + 42, white_dark)
    rect(img, ox + 21, oy + 23, ox + 21, oy + 42, white_dark)
    rect(img, ox + 10, oy + 23, ox + 21, oy + 23, (200, 40, 48, 255))
    rect(img, ox + 12, oy + 29, ox + 19, oy + 35, book)        # книга в руках
    rect(img, ox + 12, oy + 29, ox + 12, oy + 35, book_dark)
    rect(img, ox + 14, oy + 31, ox + 18, oy + 31, GOLD)
    rect(img, ox + 14, oy + 33, ox + 17, oy + 33, GOLD_DARK)


def king_smith(img, ox, oy, suit):
    """Король — кузнец в короне, в кожаном фартуке, с молотом."""
    blank_card(img, ox, oy)
    corners(img, ox, oy, "К", suit)
    frame(img, ox, oy, suit)
    apron, apron_dark = (54, 50, 58, 255), (30, 26, 34, 255)
    sleeve = (120, 108, 100, 255)
    hx, hy = ox + 11, oy + 12
    rect(img, hx, hy - 3, hx + 9, hy - 1, GOLD)                # корона
    for tx in (hx, hx + 3, hx + 6, hx + 9):
        img.putpixel((tx, hy - 4), GOLD)
    rect(img, hx, hy - 1, hx + 9, hy - 1, GOLD_DARK)
    img.putpixel((hx + 4, hy - 2), RED)
    img.putpixel((hx + 5, hy - 2), RED)
    villager_head(img, hx, hy)
    rect(img, hx + 1, hy + 3, hx + 3, hy + 5, apron_dark)      # повязка на глазу, как у оружейника
    rect(img, hx, hy + 3, hx + 9, hy + 3, apron_dark)
    robe(img, ox + 10, oy + 23, 12, 20, sleeve)
    rect(img, ox + 12, oy + 24, ox + 19, oy + 42, apron)
    rect(img, ox + 12, oy + 24, ox + 19, oy + 24, apron_dark)
    arms(img, ox + 10, oy + 29, 12, (100, 90, 84, 255))
    rect(img, ox + 22, oy + 18, ox + 22, oy + 42, (120, 90, 50, 255))   # рукоять молота
    rect(img, ox + 20, oy + 15, ox + 23, oy + 18, (146, 146, 156, 255))  # боёк
    rect(img, ox + 20, oy + 18, ox + 23, oy + 18, (100, 100, 110, 255))

BIG = {  # 15×15 большие масти для тузов
    "H": [".XXXX...XXXX...", "XXXXXX.XXXXXX..", "XXXXXXXXXXXXX..", "XXXXXXXXXXXXX..", "XXXXXXXXXXXXX..", ".XXXXXXXXXXX...",
          "..XXXXXXXXX....", "...XXXXXXX.....", "....XXXXX......", ".....XXX.......", "......X........"],
    "D": ["......X......", ".....XXX.....", "....XXXXX....", "...XXXXXXX...", "..XXXXXXXXX..", ".XXXXXXXXXXX.", "XXXXXXXXXXXXX",
          ".XXXXXXXXXXX.", "..XXXXXXXXX..", "...XXXXXXX...", "....XXXXX....", ".....XXX.....", "......X......"],
    "C": ["....XXXXX....", "...XXXXXXX...", "...XXXXXXX...", "...XXXXXXX...", ".XXXXXXXXXXX.", "XXXXXXXXXXXXX", "XXXXXXXXXXXXX",
          "XXXXXX.XXXXXX", ".XXXX.X.XXXX.", "......X......", ".....XXX.....", "....XXXXX...."],
}


def ace(img, ox, oy, suit):
    blank_card(img, ox, oy)
    corners(img, ox, oy, "Т", suit)
    rows = BIG[suit]
    stamp(img, ox + (W - len(rows[0])) // 2, oy + (H - len(rows)) // 2, rows, color_of(suit))


CREEPER = (88, 178, 64, 255)
CREEPER_LIGHT = (128, 210, 96, 255)
CREEPER_DARK = (48, 120, 40, 255)
SPADE_BIG = ["........X........", ".......XXX.......", "......XXXXX......", ".....XXXXXXX.....", "....XXXXXXXXX....",
             "...XXXXXXXXXXX...", "..XXXXXXXXXXXXX..", ".XXXXXXXXXXXXXXX.", "XXXXXXXXXXXXXXXXX", "XXXXXXXXXXXXXXXXX",
             "XXXXXXXXXXXXXXXXX", "XXXXXXXXXXXXXXXXX", ".XXXXXXX.XXXXXXX.", "..XXXX..X..XXXX..", "........X........",
             ".......XXX.......", "......XXXXX......", ".....XXXXXXX....."]


def ace_creeper(img, ox, oy):
    """Туз пик — крипер: большая пика в зелени крипера и его лицо."""
    blank_card(img, ox, oy)
    corners(img, ox, oy, "Т", "S")
    x0, y0 = ox + (W - 17) // 2, oy + 14
    px = img.load()
    for dy, row in enumerate(SPADE_BIG):
        for dx, ch in enumerate(row):
            if ch == "X":
                # Пятнистая кожа крипера.
                n = (dx * 7 + dy * 13 + (dx * dy) % 5) % 9
                px[x0 + dx, y0 + dy] = CREEPER_LIGHT if n == 0 else CREEPER_DARK if n in (3, 7) else CREEPER
    # Лицо крипера: глаза 2×2 и рот.
    face = BLACK
    rect(img, x0 + 5, y0 + 6, x0 + 6, y0 + 7, face)
    rect(img, x0 + 10, y0 + 6, x0 + 11, y0 + 7, face)
    rect(img, x0 + 7, y0 + 8, x0 + 9, y0 + 9, face)
    rect(img, x0 + 6, y0 + 9, x0 + 6, y0 + 11, face)
    rect(img, x0 + 10, y0 + 9, x0 + 10, y0 + 11, face)
    rect(img, x0 + 7, y0 + 10, x0 + 9, y0 + 10, face)


# ---------------------------------------------------------------- рубашка, фишки, сукно, эмблема

BACKS = [((140, 30, 42, 255), (98, 18, 28, 255), (186, 60, 70, 255)),
         ((36, 64, 140, 255), (22, 40, 96, 255), (76, 108, 186, 255)),
         ((34, 110, 62, 255), (20, 72, 40, 255), (72, 156, 96, 255))]

EMERALD = ["....XX....", "...XGGX...", "..XGLLGX..", ".XGLLGGGX.", "XGLGGGGGGX", "XGGGGGGDGX", ".XGGGGDGX.", "..XGGDGX..",
           "...XGGX...", "....XX...."]
EM = {"X": (18, 82, 46, 255), "G": (44, 176, 92, 255), "L": (150, 240, 180, 255), "D": (28, 128, 66, 255)}


def emerald(img, x, y):
    for dy, row in enumerate(EMERALD):
        for dx, ch in enumerate(row):
            if ch in EM:
                img.putpixel((x + dx, y + dy), EM[ch])


def bubble(img, x, y, color, dark):
    """Эмблема Mob Talk: пузырь речи 14×11 с хвостиком."""
    rows = ["..XXXXXXXXXX..", ".XOOOOOOOOOOX.", "XOOOOOOOOOOOOX", "XOOOOOOOOOOOOX", "XOOOOOOOOOOOOX", "XOOOOOOOOOOOOX",
            ".XOOOOOOOOOOX.", "..XXXOXXXXXX..", "....XOX.......", "....XX........", "...X.........."]
    for dy, row in enumerate(rows):
        for dx, ch in enumerate(row):
            if ch == "X":
                img.putpixel((x + dx, y + dy), dark)
            elif ch == "O":
                img.putpixel((x + dx, y + dy), color)


def back(img, ox, oy, colors):
    base, dark, light = colors
    blank_card(img, ox, oy, fill=base)
    px = img.load()
    for y in range(4, H - 4):
        for x in range(4, W - 4):
            # ромбовая сетка
            if (x + y) % 6 == 0 or (x - y) % 6 == 0:
                px[ox + x, oy + y] = dark
            elif (x + y) % 6 == 3 and (x - y) % 6 == 3:
                px[ox + x, oy + y] = light
    for x in range(3, W - 3):
        px[ox + x, oy + 3] = GOLD_DARK
        px[ox + x, oy + H - 4] = GOLD_DARK
    for y in range(3, H - 3):
        px[ox + 3, oy + y] = GOLD_DARK
        px[ox + W - 4, oy + y] = GOLD_DARK
    # медальон с эмблемой
    rect(img, ox + 7, oy + 15, ox + 24, oy + 32, dark)
    rect(img, ox + 8, oy + 16, ox + 23, oy + 31, PAPER)
    bubble(img, ox + 9, oy + 17, (255, 255, 255, 255), INK)
    emerald(img, ox + 11, oy + 18)


CHIP_W, CHIP_H = 24, 16
# Окантовка по номиналу: 1 — серебро, 5 — красная, 9 — золото (светлый, основной, тёмный).
RIMS = {1: ((236, 240, 246, 255), (176, 184, 198, 255), (104, 110, 124, 255)),
        5: ((246, 112, 112, 255), (200, 40, 48, 255), (120, 18, 26, 255)),
        9: ((255, 226, 132, 255), (226, 176, 58, 255), (146, 98, 22, 255))}
GEM = ((178, 250, 204, 255), (64, 200, 116, 255), (34, 150, 82, 255), (16, 96, 52, 255))  # блик, свет, тон, тень


def chip(img, x, y, value):
    """Фишка-изумруд 24×16 под лёгким углом: эллипс сверху (22×10) и ребро толщиной 3 с насечками цвета номинала."""
    light, rim, dark = RIMS[value]
    px = img.load()
    cx, cy, rx, ry = 11.5, 4.5, 11.0, 4.6
    for shift in (3, 2, 1):  # ребро снизу вверх
        for py in range(CHIP_H):
            for qx in range(CHIP_W):
                nx, ny = (qx - cx) / rx, (py - cy - shift) / ry
                if nx * nx + ny * ny <= 1.0:
                    stripe = int((qx + 1) / 3) % 2 == 0 and abs(nx) < 0.92
                    side = rim if stripe else GEM[3]
                    if shift == 3 and ny > 0.2:
                        side = dark if stripe else (10, 64, 34, 255)
                    px[x + qx, y + py] = side
    for py in range(CHIP_H):
        for qx in range(CHIP_W):
            nx, ny = (qx - cx) / rx, (py - cy) / ry
            r = nx * nx + ny * ny
            if r > 1.0:
                continue
            if r > 0.62:
                c = light if ny < -0.1 and nx < 0.4 else rim if ny < 0.45 else dark
            elif r > 0.52:
                c = GEM[3]
            else:
                # Огранка: верхняя площадка шестигранником и четыре грани вокруг.
                if abs(nx) * 0.9 + abs(ny) * 0.55 < 0.36:
                    c = GEM[0] if nx < -0.05 and ny < 0 else GEM[1]
                elif nx < 0 and ny < 0:
                    c = GEM[1]
                elif nx >= 0 and ny >= 0:
                    c = GEM[3]
                else:
                    c = GEM[2]
            px[x + qx, y + py] = c
    # Блик.
    px[x + 7, y + 2] = (255, 255, 255, 255)
    px[x + 8, y + 2] = (230, 255, 240, 255)
    px[x + 6, y + 3] = (210, 255, 228, 255)


# Иконки кнопок 12×12: X — тёмный контур, O — заливка, L — блик, G — золото, R — красный.
ICONS = {
    "hit": ["..XXXXXX....", "..XOOOOX....", "..XOOOOX.GG.", "..XOOOOX.GG.", "..XOOOOXGGGG", "..XOOOOXGGGG", "..XOOOOX.GG.",
            "..XOOOOX.GG.", "..XOOOOX....", "..XOOOOX....", "..XXXXXX....", "............"],
    "stand": ["....X.X.....", "...XOXOX.X..", "...XOXOXXOX.", ".X.XOXOXXOX.", "XOXXOOOOOOX.", "XOOXOOOOOOX.",
              ".XOOOOOOOOX.", "..XOOOOOOOX.", "..XOOOOOOX..", "...XOOOOOX..", "...XOOOOX...", "....XXXX...."],
    "double": ["............", "X...X.XXXX..", ".X.X.....X..", "..X...XXXX..", ".X.X..X.....", "X...X.XXXX..",
               "............", "..GGGGGGGG..", ".G........G.", ".GGGGGGGGGG.", "..GGGGGGGG..", "............"],
    "vabank": [".....GG.....", "....GGGG....", "...GGGGGG...", ".....GG.....", "..GGGGGGGG..", ".GLLOOOOOOG.",
               ".GGGGGGGGGG.", ".GLOOOOOOOG.", ".GGGGGGGGGG.", ".GLOOOOOOOG.", ".GGGGGGGGGG.", "..GGGGGGGG.."],
    "split": ["XXXXX..XXXXX", "XOOOX..XOOOX", "XOOOX..XOOOX", "XOOOX..XOOOX", "XOOOX..XOOOX", "XOOOX..XOOOX",
              "XOOOX..XOOOX", "XXXXX..XXXXX", "............", "..G......G..", ".GGG....GGG.", "..G......G.."],
    "catch": ["............", "....XXXX....", "..XXOOOOXX..", ".XOOOXXOOOX.", "XOOOXRRXOOOX", "XOOOXRRXOOOX",
              ".XOOOXXOOOX.", "..XXOOOOXX..", "....XXXX....", "............", "..G.G..G.G..", "............"],
    "leave": ["XXXXXX......", "XOOOOX......", "XOOOOX...G..", "XOOOOX....G.", "XOOOGGGGGGGG", "XOOOOX....G.",
              "XOOOOX...G..", "XOOOOX......", "XOOOOX......", "XOOOOX......", "XXXXXX......", "............"],
    "deal": ["...XXXXX....", "..XRRRRRX...", "..XRGGGRX...", ".XXXXXRRX...", ".XRRRRRXX...", ".XRGGGRX....",
             ".XRRRRRX....", ".XXXXXXX....", "........G...", ".........G..", "..GGGGGGGGG.", ".........G.."],
    "unbet": ["............", "....G.......", "...GG.......", "..GGGGGGG...", "...GG....G..", "....G.....G.",
              "..........G.", ".........G..", "..GGGGGGG...", "............", "............", "............"],
    "say": ["............", ".XXXXXXXXXX.", "XOOOOOOOOOOX", "XOGGOOGGOOOX", "XOOOOOOOOOOX", "XOGGGOGGOOOX",
            "XOOOOOOOOOOX", ".XXXXOXXXXX.", "....XOX.....", "....XX......", "...X........", "............"],
    "sit": ["............", "...XXXXXX...", "...XOOOOX...", "...XOOOOX...", "...XOOOOX...", ".XXXXXXXXXX.",
            ".XOOOOOOOOX.", ".XXXXXXXXXX.", ".X.X....X.X.", ".X.X....X.X.", ".X......X...", "............"],
}
ICON_COLORS = {"X": (30, 26, 38, 255), "O": (238, 232, 216, 255), "L": (255, 255, 255, 255), "G": (246, 206, 107, 255), "R": (200, 46, 52, 255)}
ICON_ORDER = ["hit", "stand", "double", "vabank", "split", "catch", "leave", "deal", "unbet", "say", "sit"]


def icon(img, x, y, name):
    for dy, row in enumerate(ICONS[name]):
        for dx, ch in enumerate(row):
            if ch in ICON_COLORS:
                img.putpixel((x + dx, y + dy), ICON_COLORS[ch])


def shadow(img, x, y):
    """Мягкая тень карты 36×52 (карта 32×48 и размытие по краю)."""
    px = img.load()
    for dy in range(52):
        for dx in range(36):
            ex = max(0, 2 - dx, dx - 33) / 2.0
            ey = max(0, 2 - dy, dy - 49) / 2.0
            a = max(0.0, 1.0 - (ex * ex + ey * ey) ** 0.5)
            px[x + dx, y + dy] = (0, 0, 0, int(110 * a))


def chip_shadow(img, x, y):
    px = img.load()
    for dy in range(10):
        for dx in range(28):
            nx, ny = (dx - 13.5) / 14.0, (dy - 4.5) / 5.0
            r = nx * nx + ny * ny
            if r < 1:
                px[x + dx, y + dy] = (0, 0, 0, int(120 * (1 - r)))


def emblem(img, x, y):
    bubble(img, x + 9, y + 9, (255, 255, 255, 255), INK)
    emerald(img, x + 11, y + 10)


# ---------------------------------------------------------------- сборка

def face(img, ox, oy, code):
    suit = SUITS[code // 13]
    rank = code % 13 + 2  # 2..14
    if rank <= 10:
        number_card(img, ox, oy, rank, suit)
    elif rank == 11:
        jack_farmer(img, ox, oy, suit)
    elif rank == 12:
        queen_librarian(img, ox, oy, suit)
    elif rank == 13:
        king_smith(img, ox, oy, suit)
    elif suit == "S":
        ace_creeper(img, ox, oy)
    else:
        ace(img, ox, oy, suit)


def atlas():
    img = Image.new("RGBA", (512, 512), T)
    for code in range(52):
        face(img, (code % 13) * W, (code // 13) * H, code)
    for i, colors in enumerate(BACKS):
        back(img, i * W, 192, colors)
    blank_card(img, 96, 192)
    shadow(img, 128, 192)
    for i, value in enumerate((1, 5, 9)):
        chip(img, i * CHIP_W, 256, value)
    chip_shadow(img, 72, 256)
    for i, name in enumerate(ICON_ORDER):
        icon(img, i * 16, 288, name)
    emerald(img, 0, 304)
    emblem(img, 32, 304)
    return img


# ---------------------------------------------------------------- стол, рамка портрета, табличка, лампа (гладкие, крупные)

TABLE_W, TABLE_H = 1024, 576
SS = 2  # рисуем вдвое крупнее и уменьшаем: ровные края
RAIL, TRIM = 30, 6  # ширина борта и золотой окантовки в пикселях итоговой текстуры
R_TOP, R_BOTTOM = 70, 210  # скругления углов стола
# Разметка мест (доли ширины и высоты стола) — та же, что в CardTableScreen.LAYOUT.
DEALER_ZONE = (0.50, 0.335, 0.34, 0.20)
SHOE = (0.86, 0.29)
SEAT_ZONES = [  # центр карт, размер зоны карт, центр круга ставки, радиус круга (доля ширины)
    ((0.50, 0.745), (0.30, 0.22), (0.50, 0.555), 0.040),
    ((0.785, 0.655), (0.20, 0.20), (0.665, 0.500), 0.033),
    ((0.215, 0.655), (0.20, 0.20), (0.335, 0.500), 0.033),
    ((0.165, 0.300), (0.20, 0.20), (0.165, 0.470), 0.033),
]


def rounded_sdf(X, Y, x0, y0, x1, y1, r_top, r_bottom):
    """Знаковое расстояние до прямоугольника со скруглениями (сверху r_top, снизу r_bottom): < 0 внутри."""
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    hw, hh = (x1 - x0) / 2, (y1 - y0) / 2
    r = np.where(Y < cy, r_top, r_bottom)
    qx = np.abs(X - cx) - (hw - r)
    qy = np.abs(Y - cy) - (hh - r)
    outside = np.sqrt(np.maximum(qx, 0) ** 2 + np.maximum(qy, 0) ** 2)
    inside = np.minimum(np.maximum(qx, qy), 0)
    return outside + inside - r


def smooth(edge0, edge1, x):
    t = np.clip((x - edge0) / (edge1 - edge0), 0, 1)
    return t * t * (3 - 2 * t)


def noise(shape, scale, seed):
    """Плавный шум: случайная сетка, растянутая бикубически."""
    g = np.random.default_rng(seed).random((max(2, shape[0] // scale + 2), max(2, shape[1] // scale + 2)))
    small = Image.fromarray((g * 255).astype(np.uint8), "L")
    return np.asarray(small.resize((shape[1], shape[0]), Image.BICUBIC), dtype=np.float32) / 255.0


def table():
    w, h = TABLE_W * SS, TABLE_H * SS
    Y, X = np.mgrid[0:h, 0:w].astype(np.float32)
    d = rounded_sdf(X, Y, 0, 0, w, h, R_TOP * SS, R_BOTTOM * SS)
    depth = -d / SS  # глубина от внешнего края, в пикселях итоговой текстуры
    rgb = np.zeros((h, w, 3), np.float32)
    alpha = smooth(-0.5, 1.0, depth)

    # Борт: лакированное красное дерево, волокна вдоль края, скруглённый профиль, блик лампы.
    t = np.clip(depth / RAIL, 0, 1)
    grain = 0.5 + 0.5 * np.sin(depth * 2.3 + noise((h, w), 40, 1) * 9 + noise((h, w), 7, 2) * 2.5)
    wood = np.stack([92 + 46 * grain, 36 + 20 * grain, 22 + 10 * grain], -1)
    profile = 0.42 + 0.7 * np.sin(np.pi * t) ** 0.7
    top_light = 0.85 + 0.25 * (1 - Y / h)
    rail = wood * (profile * top_light)[..., None]
    spec = np.exp(-((t - 0.3) / 0.07) ** 2) * (0.55 + 0.45 * (1 - Y / h))
    rail += spec[..., None] * np.array([255, 236, 210]) * 0.55
    rail -= (np.exp(-((t - 0.97) / 0.05) ** 2) * 40)[..., None]
    rgb = np.where((depth < RAIL)[..., None], rail, rgb)

    # Золотая окантовка: полукруглый профиль и тонкие тёмные линии по краям.
    g = np.clip((depth - RAIL) / TRIM, 0, 1)
    gold_profile = 0.55 + 0.6 * np.sin(np.pi * g)
    gold = np.array([226, 176, 70])[None, None, :] * gold_profile[..., None] + (np.exp(-((g - 0.35) / 0.12) ** 2) * 70)[..., None]
    edge_line = (np.minimum(g, 1 - g) < 0.12)[..., None]
    gold = np.where(edge_line, gold * 0.45, gold)
    trim_zone = ((depth >= RAIL) & (depth < RAIL + TRIM))[..., None]
    rgb = np.where(trim_zone, gold, rgb)

    # Сукно: ткань (мелкий шум и плетение), узор ромбами, свет лампы в центре, тень от борта у края.
    fd = depth - RAIL - TRIM
    rng = np.random.default_rng(5)
    weave = rng.normal(0, 1, (h, w)).astype(np.float32)
    weave = np.asarray(Image.fromarray(np.clip(weave * 40 + 128, 0, 255).astype(np.uint8), "L").resize((w, h)), np.float32) / 255 - 0.5
    cross = ((X.astype(int) + Y.astype(int)) % (3 * SS) == 0) * -0.04 + ((X.astype(int) - Y.astype(int)) % (3 * SS) == 0) * -0.03
    cloud = noise((h, w), 120, 3) - 0.5
    felt = np.array([24, 104, 58], np.float32)[None, None, :] * (1 + weave[..., None] * 0.12 + cross[..., None] + cloud[..., None] * 0.08)
    # Узор: ромбы с точками, едва темнее сукна.
    step = 64 * SS
    u = (X + Y * 0.0) % step - step / 2
    v = (Y % step) - step / 2
    diamond = np.abs(np.abs(u) + np.abs(v) - step * 0.36) < 1.2 * SS
    dots = (np.abs(u) < 2 * SS) & (np.abs(v) < 2 * SS)
    felt = np.where((diamond | dots)[..., None], felt * 0.95, felt)
    lx, ly = (X / w - 0.5), (Y / h - 0.45) * 1.25
    pool = 1.03 - 0.06 * np.clip(np.sqrt(lx * lx + ly * ly) * 1.25, 0, 1) ** 1.6  # почти ровный свет: без пятна в центре
    felt = felt * pool[..., None]
    lip = 1 - 0.55 * np.exp(-np.maximum(fd, 0) / 10.0)  # тень борта на сукне
    felt = felt * lip[..., None]
    # Шов: канавка и стежки в 18 пикселях от окантовки.
    seam = np.abs(fd - 18) < 1.0
    groove = np.abs(fd - 18) < 1.8
    gy, gx = np.gradient(fd)
    along = np.where(np.abs(gx) > np.abs(gy), Y, X) / SS  # вдоль края: по вертикали у боковых сторон, по горизонтали у верха и низа
    stitch = seam & ((along % 9) < 5)
    felt = np.where(groove[..., None], felt * 0.7, felt)
    felt = np.where(stitch[..., None], np.array([150, 196, 150])[None, None, :] * pool[..., None] * 0.95, felt)
    rgb = np.where((fd >= 0)[..., None], felt, rgb)

    img = Image.fromarray(np.concatenate([np.clip(rgb, 0, 255), (alpha * 255)[..., None]], -1).astype(np.uint8), "RGBA")
    markings(img)
    return img.resize((TABLE_W, TABLE_H), Image.LANCZOS)


def markings(img):
    """Золотая печать на сукне: зона карт жителя, круги ставок и зоны карт мест, место колоды, надпись."""
    from PIL import ImageDraw
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    dr = ImageDraw.Draw(layer)
    w, h = img.size
    ink = (240, 200, 100, 120)
    faint = (240, 200, 100, 60)
    lw = 2 * SS

    def zone(cx, cy, zw, zh, color, radius=14):
        dr.rounded_rectangle([(cx - zw / 2) * w, (cy - zh / 2) * h, (cx + zw / 2) * w, (cy + zh / 2) * h], radius=radius * SS, outline=color, width=lw)

    zone(*DEALER_ZONE, ink, 18)
    sx, sy = SHOE
    zone(sx, sy, 0.085, 0.2, faint, 10)
    for (cards, size, bet, radius) in SEAT_ZONES:
        zone(cards[0], cards[1], size[0], size[1], faint)
        bx, by, r = bet[0] * w, bet[1] * h, radius * w
        dr.ellipse([bx - r, by - r, bx + r, by + r], outline=ink, width=lw)
        r2 = r - 6 * SS
        dr.ellipse([bx - r2, by - r2, bx + r2, by + r2], outline=faint, width=SS)
        # ромбик-изумруд сверху круга
        e = 5 * SS
        dr.polygon([(bx, by - r - e), (bx + e, by - r), (bx, by - r + e), (bx - e, by - r)], fill=(70, 200, 120, 170), outline=ink)
    img.alpha_composite(layer)


def portrait_frame():
    """Рамка портрета жителя 120×144: резное дерево, золотой кант, окно 88×108 по центру прозрачное."""
    w, h, ss = 120, 144, 4
    W2, H2 = w * ss, h * ss
    Y, X = np.mgrid[0:H2, 0:W2].astype(np.float32)
    outer = -rounded_sdf(X, Y, 0, 0, W2, H2, 14 * ss, 14 * ss) / ss
    inner_margin = 16
    rgb = np.zeros((H2, W2, 3), np.float32)
    t = np.clip(outer / inner_margin, 0, 1)
    grain = 0.5 + 0.5 * np.sin(outer * 2.1 + noise((H2, W2), 30, 11) * 8)
    wood = np.stack([96 + 44 * grain, 40 + 20 * grain, 24 + 10 * grain], -1)
    profile = 0.45 + 0.65 * np.sin(np.pi * t) ** 0.8
    rgb = wood * profile[..., None] * (0.9 + 0.2 * (1 - Y / H2))[..., None]
    rgb += (np.exp(-((t - 0.3) / 0.08) ** 2) * 120)[..., None] * np.array([1.0, 0.92, 0.8])
    # Золото: внешний кант и внутренний.
    for at, width in ((1.5, 2.2), (inner_margin - 3.0, 2.6)):
        gz = np.abs(outer - at) < width
        gp = 1 - np.abs(outer - at) / width
        gold = np.array([226, 176, 70])[None, None, :] * (0.6 + 0.6 * gp)[..., None]
        rgb = np.where(gz[..., None], gold, rgb)
    window = outer >= inner_margin
    alpha = np.where(window, 0, smooth(-0.5, 0.8, outer)) * 255
    # Тень внутрь окна по краю: немного тёмного стекла.
    edge = (outer >= inner_margin) & (outer < inner_margin + 4)
    alpha = np.where(edge, 120 * (1 - (outer - inner_margin) / 4), alpha)
    rgb = np.where(edge[..., None], 10, rgb)
    img = Image.fromarray(np.concatenate([np.clip(rgb, 0, 255), alpha[..., None]], -1).astype(np.uint8), "RGBA")
    return img.resize((w, h), Image.LANCZOS)


def nameplate():
    """Табличка с именем 160×28: тёмное дерево, золотая рамка, заклёпки."""
    w, h, ss = 160, 28, 4
    W2, H2 = w * ss, h * ss
    Y, X = np.mgrid[0:H2, 0:W2].astype(np.float32)
    outer = -rounded_sdf(X, Y, 0, 0, W2, H2, 8 * ss, 8 * ss) / ss
    grain = 0.5 + 0.5 * np.sin(Y / ss * 1.7 + noise((H2, W2), 24, 13) * 7)
    rgb = np.stack([58 + 26 * grain, 26 + 12 * grain, 18 + 8 * grain], -1) * (1.15 - 0.3 * Y / H2)[..., None]
    gz = np.abs(outer - 2.0) < 1.6
    rgb = np.where(gz[..., None], np.array([226, 176, 70]) * (0.75 + 0.35 * (1 - Y / H2))[..., None], rgb)
    for rx in (7, w - 7):
        rivet = (X / ss - rx) ** 2 + (Y / ss - h / 2) ** 2 < 4.5
        rgb = np.where(rivet[..., None], np.array([250, 214, 120]), rgb)
    alpha = smooth(-0.5, 0.8, outer) * 255
    img = Image.fromarray(np.concatenate([np.clip(rgb, 0, 255), alpha[..., None]], -1).astype(np.uint8), "RGBA")
    return img.resize((w, h), Image.LANCZOS)


def lamp():
    """Луч лампы над столом 256×256: тёплый конус сверху вниз, мягкие края (рисуется сложением)."""
    s = 256
    Y, X = np.mgrid[0:s, 0:s].astype(np.float32)
    y = Y / s
    half = 0.08 + 0.42 * y
    across = np.abs(X / s - 0.5) / half
    a = np.clip(1 - across, 0, 1) ** 1.6 * (0.35 + 0.65 * (1 - y)) * smooth(0.0, 0.08, y)
    rgb = np.stack([np.full_like(a, 255), np.full_like(a, 214), np.full_like(a, 150)], -1)
    return Image.fromarray(np.concatenate([rgb, (a * 255)[..., None]], -1).astype(np.uint8), "RGBA")


def deck_icon():
    img = Image.new("RGBA", (16, 16), T)
    px = img.load()

    def card(x0, y0, w, h, face_up):
        for y in range(y0, y0 + h):
            for x in range(x0, x0 + w):
                if (x in (x0, x0 + w - 1)) and (y in (y0, y0 + h - 1)):
                    continue
                if x in (x0, x0 + w - 1) or y in (y0, y0 + h - 1):
                    px[x, y] = INK
                elif face_up:
                    px[x, y] = PAPER if x < x0 + w - 2 or y < y0 + h - 2 else PAPER_SHADE
                else:
                    px[x, y] = BACKS[0][0] if (x + y) % 2 else BACKS[0][1]
    card(1, 4, 9, 11, False)
    card(3, 2, 9, 11, False)
    card(6, 3, 9, 12, True)
    stamp(img, 8, 6, PIPS["H"], RED)
    px[7, 4] = RED
    return img


def sheet(path):
    """Лист образцов: 8 карт крупно (×6) на сукне — показать Дмитрию до финального атласа."""
    img = atlas()
    picks = [12 * 1 + 0 * 13 - 12 + 12, 13 + 9, 26 + 10, 39 + 11, 12, 13 + 12, 39 + 6, None]  # туз пик, В♥, Д♦, К♣, Т♠ ещё раз нет — см. ниже
    picks = [12, 13 + 9, 26 + 10, 39 + 11, 13 + 12, 26 + 5, 39 + 8, "back"]
    scale = 6
    pad = 12
    out = Image.new("RGBA", (pad + len(picks) * (W * scale + pad), H * scale + pad * 2), (28, 104, 58, 255))
    for i, code in enumerate(picks):
        if code == "back":
            card = img.crop((0, 192, W, 192 + H))
        else:
            card = img.crop(((code % 13) * W, (code // 13) * H, (code % 13) * W + W, (code // 13) * H + H))
        card = card.resize((W * scale, H * scale), Image.NEAREST)
        out.alpha_composite(card, (pad + i * (W * scale + pad), pad))
    out.save(path)
    print("sheet:", path)


def rays():
    """Солнечные лучи 256×256 для золотого очка: 14 клиньев, яркие у центра, мягкие края (рисуется сложением, вращается)."""
    s = 256
    Y, X = np.mgrid[0:s, 0:s].astype(np.float32)
    dx, dy = X / s - 0.5, Y / s - 0.5
    r = np.sqrt(dx * dx + dy * dy) * 2
    a = np.arctan2(dy, dx)
    wedge = np.cos(a * 7) * 0.5 + 0.5
    wedge = np.clip((wedge - 0.45) / 0.55, 0, 1) ** 1.5
    radial = np.clip(1 - r, 0, 1) ** 1.2
    core = np.exp(-(r / 0.18) ** 2)
    alpha = np.clip(wedge * radial * 0.9 + core * 0.8, 0, 1)
    rgb = np.full((s, s, 3), 255, np.float32)
    return Image.fromarray(np.concatenate([rgb, (alpha * 255)[..., None]], -1).astype(np.uint8), "RGBA")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sheet", default="")
    args = parser.parse_args()
    if args.sheet:
        sheet(args.sheet)
        return
    gui = os.path.dirname(ATLAS)
    os.makedirs(gui, exist_ok=True)
    deck_icon().save(ITEM)
    atlas().save(ATLAS)
    table().save(os.path.join(gui, "card_table.png"))
    portrait_frame().save(os.path.join(gui, "card_portrait.png"))
    nameplate().save(os.path.join(gui, "card_nameplate.png"))
    rays().save(os.path.join(gui, "card_rays.png"))
    print("textures:", gui)


if __name__ == "__main__":
    main()