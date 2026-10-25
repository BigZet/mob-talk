"""МФЦ «Госуслуги» (track «чиновники», the city's one-window office): textures (32 px a block, pixel art), block and item models
and blockstates of the hall's furniture, the ticket item and the window numbers. Run from the repository root:
python3 tools/mfc/mfc.py

Scale as in the cities' rules (docs: rebuild-spec §1, §4.1): 1 block = 1 m, a seat 0.5 m, a desk 0.75 m, the visitor's shelf
1 m. Models face north (the visitor's side); the clerk or guard sits on the south side. Nothing vanilla is referenced: every
texture is drawn here."""
import json
import math
import os
import random
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from font import text, centred  # noqa: E402

ASSETS = os.path.join("src", "main", "resources", "assets", "mobtalk")
TEX = os.path.join(ASSETS, "textures", "block", "mfc")
N = 32  # pixels a block


def write_json(path, value):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False, indent=2)
        f.write("\n")


# ---------------------------------------------------------------- drawing helpers

def rgba(c, a=255):
    return c + (a,) if len(c) == 3 else c


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c[:3])


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def canvas(c=(0, 0, 0), a=255, w=N, h=N):
    return Image.new("RGBA", (w, h), rgba(c, a))


def rect(img, x0, y0, x1, y1, c, a=255):
    px = img.load()
    for y in range(max(0, y0), min(img.height, y1)):
        for x in range(max(0, x0), min(img.width, x1)):
            px[x, y] = rgba(c, a)


def grain(img, k, seed, rows=False):
    """Darkens and lightens the pixels a little: plastic or paint (dots) or brushed metal (rows)."""
    rnd = random.Random(seed)
    px = img.load()
    row = [rnd.uniform(-k, k) for _ in range(img.height)]
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            d = row[y] * 0.7 + rnd.uniform(-k, k) * 0.3 if rows else rnd.uniform(-k, k)
            px[x, y] = (max(0, min(255, int(r * (1 + d)))), max(0, min(255, int(g * (1 + d)))), max(0, min(255, int(b * (1 + d)))), a)
    return img


def bevel(img, x0, y0, x1, y1, light, dark):
    rect(img, x0, y0, x1, y0 + 1, light)
    rect(img, x0, y0, x0 + 1, y1, light)
    rect(img, x0, y1 - 1, x1, y1, dark)
    rect(img, x1 - 1, y0, x1, y1, dark)


BLUE, BLUE_D, BLUE_L = (32, 92, 176), (20, 58, 120), (96, 150, 222)
WHITE, GREY, GRAPHITE = (236, 238, 240), (150, 156, 164), (52, 56, 62)
ORANGE = (236, 132, 36)
LED_G, LED_R, LED_Y = (90, 240, 110), (255, 80, 60), (255, 206, 60)


# ---------------------------------------------------------------- textures

def t_steel():
    img = canvas((178, 184, 192))
    grain(img, 0.06, 1, rows=True)
    for y in (0, 31):
        rect(img, 0, y, 32, y + 1, (206, 212, 218))
    return img


def t_graphite():
    return grain(canvas(GRAPHITE), 0.05, 2)


def t_white():
    img = grain(canvas(WHITE), 0.025, 3)
    return img


def t_blue():
    img = canvas(BLUE)
    for y in range(32):
        rect(img, 0, y, 32, y + 1, mix(BLUE_L, BLUE, min(1, y / 20)))
    return grain(img, 0.04, 4)


def t_seat():
    """Moulded seat plastic: blue with a lighter rim and two ribs."""
    img = t_blue()
    bevel(img, 0, 0, 32, 32, BLUE_L, BLUE_D)
    for y in (10, 21):
        rect(img, 2, y, 30, y + 1, shade(BLUE, 0.85))
    return img


def t_wood():
    """Light beech laminate of the counters: long thin grain lines."""
    rnd = random.Random(5)
    img = canvas((214, 178, 128))
    px = img.load()
    for y in range(32):
        k = 1 + 0.06 * ((y * 7) % 5 - 2) / 2
        for x in range(32):
            px[x, y] = rgba(shade((214, 178, 128), k + rnd.uniform(-0.02, 0.02)))
    for _ in range(9):
        y, x0, ln = rnd.randrange(32), rnd.randrange(32), rnd.randrange(6, 20)
        for x in range(x0, min(32, x0 + ln)):
            px[x, y] = rgba((176, 136, 88))
    return img


def t_counter_front():
    """The counter's front: a white panel with a blue band and an orange pin line, kick plate below."""
    img = t_white()
    rect(img, 0, 6, 32, 13, BLUE)
    rect(img, 0, 13, 32, 14, ORANGE)
    rect(img, 0, 28, 32, 32, GRAPHITE)
    grain(img, 0.02, 6)
    return img


def t_glass():
    img = canvas((200, 228, 244), 70)
    px = img.load()
    for i in range(8):  # two diagonal glints
        for d in (0, 1):
            x, y = 4 + i + d, 22 - i
            px[x, y] = (255, 255, 255, 150)
            px[x + 10, y - 6] = (255, 255, 255, 110)
    # The «speaking holes»: a ring of small dots low in the middle.
    for x, y in ((14, 25), (16, 24), (18, 25), (14, 27), (18, 27), (16, 28), (16, 26)):
        px[x, y] = (60, 70, 80, 200)
    return img


def t_screen():
    """The ticket terminal's touch screen: a header with the office's name, five service buttons and «Возьмите талон»."""
    img = canvas((236, 242, 248))
    rect(img, 0, 0, 32, 7, BLUE)
    centred(img, 16, 1, "МФЦ", WHITE)
    buttons = [("ПАСПОРТ", BLUE), ("СПРАВКИ", BLUE), ("ПЕНСИЯ", BLUE), ("ЖКХ", BLUE)]
    for i, (label, c) in enumerate(buttons):
        y = 8 + i * 5
        rect(img, 2, y, 30, y + 4, (255, 255, 255))
        bevel(img, 2, y, 30, y + 4, (255, 255, 255), (170, 182, 196))
        rect(img, 3, y + 1, 5, y + 3, ORANGE)  # the button's icon
        # Labels are too long for 3x5 letters at this size: the first word as text, small grey lines for the rest.
        rect(img, 7, y + 1, 7 + min(20, len(label) * 3), y + 2, shade(c, 0.9))
        rect(img, 7, y + 2, 7 + min(14, len(label) * 2), y + 3, (150, 160, 175))
    rect(img, 0, 28, 32, 32, (220, 228, 238))
    rect(img, 6, 29, 26, 31, ORANGE)
    return img


def t_board():
    """The queue board (64×32, two metres wide): black LED screen, the header «ТАЛОН · №», six called tickets in two columns in
    green, the newest in red."""
    img = canvas((14, 16, 18), w=64)
    px = img.load()
    for y in range(0, 32, 2):  # the LED grid
        for x in range(0, 64, 2):
            px[x, y] = (24, 26, 30, 255)
    for col in (0, 32):
        text(img, col + 2, 1, "ТАЛОН", LED_Y)
        text(img, col + 26, 1, "№", LED_Y)
        rect(img, col + 1, 7, col + 31, 8, (60, 60, 40))
    rows = [("А014", "3", LED_R), ("Б007", "1", LED_G), ("А013", "5", LED_G), ("В002", "2", LED_G), ("А012", "4", LED_G), ("Б006", "1", LED_G)]
    for i, (t, w, c) in enumerate(rows):
        col, y = (i // 3) * 32, 10 + (i % 3) * 7
        text(img, col + 2, y, t, c)
        text(img, col + 19, y, "→", c)
        text(img, col + 27, y, w, c)
    rect(img, 31, 2, 33, 30, (40, 42, 46))
    return img


def t_number(n):
    """A window's number plate: a white field in a blue frame, «ОКНО» small and the big number."""
    img = canvas(BLUE)
    rect(img, 1, 1, 31, 31, WHITE)
    rect(img, 1, 1, 31, 9, BLUE)
    centred(img, 16, 2, "ОКНО", WHITE)
    centred(img, 16, 12, str(n), BLUE_D, scale=3)
    bevel(img, 0, 0, 32, 32, BLUE_L, BLUE_D)
    return img


def t_paper():
    img = canvas((246, 244, 236))
    for y in range(4, 30, 3):
        rect(img, 3, y, 29 - (y * 5) % 9, y + 1, (160, 168, 186))
    rect(img, 3, 1, 18, 3, BLUE)
    rect(img, 22, 24, 28, 30, (200, 60, 60), 200)  # a stamp
    return grain(img, 0.02, 7)


def t_info():
    """The «образцы заполнения» stand: a blue header and four pinned sample forms with red ticks."""
    img = canvas((226, 230, 236))
    rect(img, 0, 0, 32, 6, BLUE)
    centred(img, 16, 0, "БЛАНКИ", WHITE)
    for i, (x, y) in enumerate(((2, 8), (17, 8), (2, 20), (17, 20))):
        rect(img, x, y, x + 13, y + 11, (250, 250, 246))
        bevel(img, x, y, x + 13, y + 11, (255, 255, 255), (180, 186, 196))
        for r in range(2, 10, 2):
            rect(img, x + 2, y + r, x + 11 - (r + i) % 4, y + r + 1, (150, 158, 176))
        rect(img, x + 9, y + 7, x + 10, y + 9, (210, 40, 40))
        rect(img, x + 10, y + 6, x + 11, y + 7, (210, 40, 40))
        img.load()[x + 6, y] = rgba(ORANGE)  # the pin
    return img


def t_leaves():
    """A ficus: glossy oval leaves, dark green with a light edge toward the light and a vein, thick in the middle of the crown and
    thinning toward its edge, on a transparent ground (cut out). Thin twigs show between them."""
    rnd = random.Random(8)
    img = canvas((0, 0, 0), 0)
    px = img.load()
    for _ in range(10):  # twigs
        x, y = 16, 31
        for _ in range(20):
            x += rnd.choice((-1, 0, 1)); y -= 1
            if 0 <= x < 32 and 0 <= y < 32:
                px[x, y] = (92, 70, 46, 255)
    for _ in range(95):
        r = rnd.random() ** 0.6 * 14
        a = rnd.uniform(0, 6.283)
        cx, cy = int(16 + math.cos(a) * r), int(14 + math.sin(a) * r * 1.05)
        base = (30 + rnd.randrange(18), 92 + rnd.randrange(46), 36 + rnd.randrange(18))
        for dy in range(-2, 3):
            for dx in range(-2, 2):
                if (dx + 0.5) ** 2 / 4 + dy ** 2 / 6.5 <= 1 and 0 <= cx + dx < 32 and 0 <= cy + dy < 32:
                    k = 1.3 if dx == -2 or dy == -2 else 0.78 if dy == 2 else 1
                    px[cx + dx, cy + dy] = rgba(shade(base, k))
        if 0 <= cx < 32 and 0 <= cy < 32:
            px[cx, cy] = rgba(shade(base, 0.68))
    return img


def t_pot():
    img = canvas((232, 230, 224))
    rect(img, 0, 0, 32, 4, (250, 250, 248))
    rect(img, 0, 26, 32, 32, (200, 198, 192))
    rect(img, 0, 12, 32, 14, BLUE)
    return grain(img, 0.03, 9)


def t_soil():
    rnd = random.Random(10)
    img = canvas((70, 48, 30))
    px = img.load()
    for _ in range(80):
        px[rnd.randrange(32), rnd.randrange(32)] = rgba((96 + rnd.randrange(20), 68, 40))
    return img


def t_bottle():
    img = canvas((120, 186, 236), 150)
    rect(img, 2, 0, 5, 32, (230, 246, 255), 190)
    rect(img, 0, 10, 32, 12, (90, 150, 210), 170)
    rect(img, 0, 22, 32, 24, (90, 150, 210), 170)
    return img


def t_cooler_front():
    img = t_white()
    rect(img, 6, 4, 26, 18, (214, 218, 224))
    rect(img, 9, 8, 12, 12, (40, 110, 210))  # cold tap
    rect(img, 20, 8, 23, 12, (220, 50, 40))  # hot tap
    rect(img, 6, 18, 26, 20, GREY)
    return img


def t_curtain():
    img = canvas((40, 64, 120))
    for x in range(32):
        k = 0.8 + 0.3 * abs(((x % 8) - 4) / 4)
        rect(img, x, 0, x + 1, 32, shade((40, 64, 120), k))
    return grain(img, 0.03, 11)


def t_photo_side():
    """The photo booth's side: white with four sample 3×4 portraits and the price."""
    img = t_white()
    rect(img, 0, 0, 32, 5, ORANGE)
    centred(img, 16, 0, "ФОТО", WHITE)
    for i in range(4):
        x, y = 3 + (i % 2) * 14, 8 + (i // 2) * 12
        rect(img, x, y, x + 9, y + 11, (240, 240, 240))
        bevel(img, x, y, x + 9, y + 11, (255, 255, 255), (170, 170, 170))
        rect(img, x + 3, y + 2, x + 6, y + 5, (226, 184, 150))  # a face
        rect(img, x + 3, y + 1, x + 6, y + 2, (60, 40, 30) if i % 2 else (190, 150, 80))
        rect(img, x + 2, y + 6, x + 7, y + 10, (60, 70, 90) if i < 2 else (120, 40, 50))
    return img


def t_crt():
    """The guard's CCTV monitor: four grey camera views and a red REC dot."""
    img = canvas((30, 34, 30))
    for i in range(4):
        x, y = 2 + (i % 2) * 15, 2 + (i // 2) * 15
        rect(img, x, y, x + 13, y + 13, (90 + i * 10, 104 + i * 8, 96))
        rect(img, x + 2, y + 8, x + 11, y + 13, (60, 66, 62))  # floor
        rect(img, x + 4 + i, y + 4, x + 6 + i, y + 9, (40, 44, 42))  # someone
    rect(img, 28, 1, 30, 3, LED_R)
    return grain(img, 0.08, 12)


def t_beige():
    return grain(canvas((206, 198, 176)), 0.03, 13)


def t_guard_front():
    """The guard post's front panel: dark with «ОХРАНА» in white on a blue band."""
    img = grain(canvas((60, 64, 72)), 0.04, 14)
    rect(img, 0, 8, 32, 17, BLUE_D)
    centred(img, 16, 10, "ОХРАНА", WHITE)
    rect(img, 0, 28, 32, 32, (30, 32, 36))
    return img


def t_logbook():
    img = canvas((246, 242, 228))
    rect(img, 15, 0, 17, 32, (180, 170, 150))
    for y in range(4, 30, 3):
        rect(img, 2, y, 13, y + 1, (120, 130, 170))
        rect(img, 19, y, 29 - (y % 7), y + 1, (120, 130, 170))
    return img


def t_detector():
    """The walk-through metal detector's post: grey with a vertical strip of green LEDs on the inner edge."""
    img = canvas((196, 200, 206))
    grain(img, 0.03, 15)
    for y in range(2, 30, 3):
        rect(img, 13, y, 15, y + 2, LED_G)
    rect(img, 2, 2, 8, 6, GRAPHITE)  # the maker's badge
    return img


def t_sign():
    """The hall's sign (64×32, two metres wide): a document icon in orange and white, «МФЦ» big in white on blue, an orange line
    and «ГОСУСЛУГИ» below."""
    img = canvas(BLUE, w=64)
    for y in range(32):
        rect(img, 0, y, 64, y + 1, mix(BLUE_L, BLUE, min(1, y / 20)))
    grain(img, 0.03, 4)
    rect(img, 6, 4, 17, 19, WHITE)  # the document icon
    rect(img, 12, 4, 17, 9, ORANGE)
    for y in (10, 13, 16):
        rect(img, 8, y, 15, y + 1, BLUE_L)
    text(img, 22, 5, "МФЦ", WHITE, scale=2)
    rect(img, 4, 21, 60, 22, ORANGE)
    centred(img, 32, 24, "ГОСУСЛУГИ", WHITE)
    bevel(img, 0, 0, 64, 32, BLUE_L, BLUE_D)
    return img


def t_belt():
    img = canvas(BLUE_D)
    rect(img, 0, 3, 32, 5, BLUE_L)
    rect(img, 0, 11, 32, 13, BLUE_L)
    for x in range(2, 32, 8):
        rect(img, x, 6, x + 3, 10, WHITE)
    return img


def t_black():
    return grain(canvas((22, 22, 24)), 0.05, 16)


def t_red():
    return canvas((210, 40, 34))


def t_ticket_item():
    """The ticket (16 px item): a white slip curled at the corner, «А015», a barcode."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    rect(img, 3, 1, 13, 15, (248, 248, 244))
    rect(img, 3, 1, 13, 3, BLUE)
    text(img, 3, 4, "А015", (30, 30, 34))
    for x in range(4, 12):
        if x % 3:
            rect(img, x, 11, x + 1, 14, (40, 40, 44))
    px = img.load()
    px[12, 14] = (0, 0, 0, 0)
    px[11, 14] = (210, 210, 206, 255)
    px[12, 13] = (210, 210, 206, 255)
    for y in range(1, 15):  # the shadow edge
        px[13, y] = (190, 190, 186, 255)
    return img


TEXTURES = {
    "steel": t_steel, "graphite": t_graphite, "white": t_white, "blue": t_blue, "seat": t_seat, "wood": t_wood,
    "counter_front": t_counter_front, "glass": t_glass, "screen": t_screen, "board": t_board, "paper": t_paper, "info": t_info,
    "leaves": t_leaves, "pot": t_pot, "soil": t_soil, "bottle": t_bottle, "cooler_front": t_cooler_front, "curtain": t_curtain,
    "photo_side": t_photo_side, "crt": t_crt, "beige": t_beige, "guard_front": t_guard_front, "logbook": t_logbook,
    "detector": t_detector, "sign": t_sign, "belt": t_belt, "black": t_black, "red": t_red,
}
WINDOWS = range(1, 10)


# ---------------------------------------------------------------- models

def face(tex, uv=None, rot=None):
    f = {"texture": tex}
    if uv is not None:
        f["uv"] = uv
    if rot:
        f["rotation"] = rot
    return f


def auto_uv(fr, to, ch):
    """The face's UV as Minecraft would take it from the element's position, moved back into 0..16: parts that reach above the
    block (up to 2 m tall pieces) or out of it would otherwise sample the neighbouring sprites of the atlas."""
    x1, y1, z1 = fr
    x2, y2, z2 = to
    u1, u2 = {"n": (16 - x2, 16 - x1), "s": (x1, x2), "e": (16 - z2, 16 - z1), "w": (z1, z2), "u": (x1, x2), "d": (x1, x2)}[ch]
    v1, v2 = {"u": (z1, z2), "d": (16 - z2, 16 - z1)}.get(ch, (16 - y2, 16 - y1))

    def fit(a, b):
        if b - a >= 16:
            return 0, 16
        while a < 0:
            a, b = a + 16, b + 16
        while b > 16:
            a, b = a - 16, b - 16
        return (a, b) if a >= 0 else (0, b - a)
    u1, u2 = fit(u1, u2)
    v1, v2 = fit(v1, v2)
    return [u1, v1, u2, v2]


def box(fr, to, tex, faces="nsewud", uvs=None, rotation=None):
    names = {"n": "north", "s": "south", "e": "east", "w": "west", "u": "up", "d": "down"}
    out = {}
    for ch in faces:
        t = tex[ch] if isinstance(tex, dict) else tex
        uv = (uvs or {}).get(ch) or auto_uv(fr, to, ch)
        out[names[ch]] = face(t, uv)
    e = {"from": fr, "to": to, "faces": out}
    if rotation:
        e["rotation"] = rotation
    return e


def T(*names):
    return {n: "mobtalk:block/mfc/" + n for n in names} | {"particle": "mobtalk:block/mfc/" + names[0]}


def model(textures, elements):
    return {"parent": "block/block", "textures": textures, "elements": elements}


FULL = [0, 0, 16, 16]


def m_terminal():
    """Ticket terminal, 1.75 m: a steel foot, a graphite column with a blue side band and the ticket slot, the screen tilted back
    under a blue header with «МФЦ»."""
    tilt = {"angle": -22.5, "axis": "x", "origin": [8, 19, 7]}
    els = [
        box([3, 0, 4], [13, 1, 12], "#steel"),
        box([5, 1, 6], [11, 15, 11], "#graphite"),
        box([4.9, 3, 7], [5, 13, 10], "#blue", "w"), box([11, 3, 7], [11.1, 13, 10], "#blue", "e"),
        box([6, 11, 5.6], [10, 12, 6], "#black", "nsewud"),  # the printer slot
        box([7, 11.2, 5.2], [9, 11.4, 6], "#paper", "nsewud", {c: [2, 2, 6, 4] for c in "nsewud"}),  # a ticket sticking out
        box([2, 15, 4], [14, 24, 9], "#graphite", rotation=tilt),
        box([3, 16, 3.9], [13, 23, 4], "#screen", "n", {"n": FULL}, rotation=tilt),
        box([2, 24, 4], [14, 28, 10], {"n": "#blue", "s": "#graphite", "e": "#blue", "w": "#blue", "u": "#graphite", "d": "#graphite"}),
        box([3, 24.5, 3.9], [13, 27.5, 4], "#sign", "n", {"n": [5, 1, 13.75, 3.75]}),
    ]
    return model(T("graphite", "steel", "blue", "black", "paper", "screen", "sign"), els)


def m_board():
    """Queue board on the wall: a thin graphite case and the LED screen, two steel brackets into the wall."""
    els = [
        box([-8, 3, 14], [24, 13, 16], "#graphite", uvs={"n": [0, 0, 16, 5], "s": [0, 0, 16, 5]}),
        box([-7.5, 3.5, 13.9], [23.5, 12.5, 14], "#board", "n", {"n": FULL}),
        box([-2, 13, 15], [-1, 14, 16], "#steel"), box([17, 13, 15], [18, 14, 16], "#steel"),
    ]
    return model(T("graphite", "board", "steel"), els)


def m_window(n):
    """Reception window: the clerk's desk (0.75 m) on the south, the visitors' shelf (1 m) on the north, a glass screen with the
    speaking holes and a slot at the bottom, steel frame, the number plate on top; on the desk a monitor turned to the clerk,
    a keyboard and a tray of forms."""
    els = [
        box([0, 0, 4], [16, 12, 16], {"n": "#front", "s": "#wood", "e": "#wood", "w": "#wood", "u": "#wood", "d": "#wood"},
            uvs={"n": FULL}),
        box([0, 12, 3.5], [16, 13, 16], "#wood"),  # the clerk's desk top
        box([0, 12, 0], [16, 16, 4], {"n": "#front", "s": "#wood", "e": "#wood", "w": "#wood", "u": "#wood", "d": "#wood"},
            uvs={"n": [0, 0, 16, 8]}),  # the visitors' raised front
        box([0, 16, -0.5], [16, 16.75, 4.5], "#wood"),  # the visitors' shelf
        box([0.5, 17.5, 4], [15.5, 27, 4.25], "#glass", "ns", {"n": [0, 0, 16, 16], "s": [0, 0, 16, 16]}),
        box([0, 16.75, 3.75], [0.5, 28, 4.5], "#steel"), box([15.5, 16.75, 3.75], [16, 28, 4.5], "#steel"),
        box([0.5, 27, 3.75], [15.5, 28, 4.5], "#steel"),
        box([4, 28, 3.75], [12, 32, 4.5], {"n": "#number", "s": "#number", "e": "#steel", "w": "#steel", "u": "#steel", "d": "#steel"},
            uvs={"n": FULL, "s": FULL}),
        # On the desk: a monitor facing south (the clerk), its stand, a keyboard, forms.
        box([9, 13, 9], [10.5, 15, 10], "#graphite"),
        box([7, 15, 8.5], [14, 20, 9.5], {"n": "#graphite", "s": "#screen", "e": "#graphite", "w": "#graphite", "u": "#graphite", "d": "#graphite"},
            uvs={"s": FULL}),
        box([7, 13, 11], [13, 13.5, 13], "#graphite"),
        box([2, 13, 6], [6, 13.75, 11], "#paper", "nsewu", {"u": FULL}),
    ]
    return model(T("wood", "steel", "glass", "graphite", "screen", "paper") | {"front": "mobtalk:block/mfc/counter_front",
                                                                          "number": f"mobtalk:block/mfc/window_{n}"}, els)


def m_chairs():
    """A row of two waiting chairs: steel T-legs and beam, two moulded blue seats (0.5 m) with backs, the sitters face north."""
    els = [
        box([0.5, 0, 5], [1.5, 1, 12], "#steel"), box([14.5, 0, 5], [15.5, 1, 12], "#steel"),
        box([0.5, 1, 8], [1.5, 6.5, 9], "#steel"), box([14.5, 1, 8], [15.5, 6.5, 9], "#steel"),
        box([0, 6.5, 7.5], [16, 7.25, 9.5], "#steel"),
        box([0.75, 7.25, 3], [7.5, 8, 11.5], "#seat"), box([8.5, 7.25, 3], [15.25, 8, 11.5], "#seat"),
        box([0.75, 8, 11.5], [7.5, 15.5, 12.25], "#seat"), box([8.5, 8, 11.5], [15.25, 15.5, 12.25], "#seat"),
        box([3.5, 6.5, 11.5], [4.5, 8, 12], "#steel"), box([11.5, 6.5, 11.5], [12.5, 8, 12], "#steel"),
    ]
    return model(T("seat", "steel"), els)


def m_form_desk():
    """High desk for filling in forms (1.1 m): a steel post on a round foot, a beech top, a rack of blank forms at the back and
    a pen on a chain."""
    els = [
        box([3, 0, 3], [13, 0.75, 13], "#steel"),
        box([7, 0.75, 7], [9, 17, 9], "#steel"),
        box([0.5, 17, 0.5], [15.5, 18, 15.5], "#wood"),
        box([1, 18, 12.5], [15, 23, 13.5], "#white"),
        box([1.5, 19, 12.4], [7.5, 22.5, 12.5], "#paper", "n", {"n": [2, 2, 14, 10]}),
        box([8.5, 19, 12.4], [14.5, 22.5, 12.5], "#paper", "n", {"n": [16, 2, 28, 10]}),
        box([3, 18, 3], [9, 18.25, 10], "#paper", "u", {"u": FULL}),
        box([10.5, 18, 5], [11.25, 18.6, 10], "#blue"),
        box([10.75, 18, 10], [11, 18.2, 12.5], "#steel"),
    ]
    return model(T("wood", "steel", "white", "paper", "blue"), els)


def m_info():
    """Stand «образцы заполнения», 1.9 m: two steel legs on feet and a board with the sample forms on both sides."""
    els = [
        box([1.5, 0, 5], [3.5, 0.75, 11], "#steel"), box([12.5, 0, 5], [14.5, 0.75, 11], "#steel"),
        box([2, 0.75, 7.5], [3, 12, 8.5], "#steel"), box([13, 0.75, 7.5], [14, 12, 8.5], "#steel"),
        box([0.5, 12, 7.25], [15.5, 30, 8.75], {"n": "#info", "s": "#info", "e": "#steel", "w": "#steel", "u": "#steel", "d": "#steel"},
            uvs={"n": FULL, "s": FULL}),
    ]
    return model(T("info", "steel"), els)


def m_cooler():
    """Water cooler: a white body with the cold and hot taps, a drip tray and an upturned blue bottle."""
    els = [
        box([4, 0, 4], [12, 16, 12], {"n": "#front", "s": "#white", "e": "#white", "w": "#white", "u": "#white", "d": "#white"},
            uvs={"n": FULL}),
        box([5, 9, 3.25], [6.5, 10, 4], "#blue"), box([9.5, 9, 3.25], [11, 10, 4], "#red"),
        box([5, 6, 3], [11, 6.5, 4], "#steel"),
        box([5, 16, 5], [11, 24, 11], "#bottle"),
        box([7, 24, 7], [9, 25, 9], "#bottle"),
    ]
    return model(T("white", "steel", "blue", "red", "bottle") | {"front": "mobtalk:block/mfc/cooler_front"}, els)


def m_plant():
    """A ficus in a white pot with a blue band: soil, a trunk and two crossed planes of leaves, 1.8 m."""
    els = [
        box([4, 0, 4], [12, 7, 12], "#pot"),
        box([4.5, 6.5, 4.5], [11.5, 6.75, 11.5], "#soil", "u"),
        box([7.5, 6.75, 7.5], [8.5, 14, 8.5], "#soil"),
    ]
    for angle in (45, -45):
        els.append({"from": [0, 10, 8], "to": [16, 29, 8], "rotation": {"angle": angle, "axis": "y", "origin": [8, 8, 8], "rescale": True},
                    "shade": False, "faces": {"north": face("#leaves", FULL), "south": face("#leaves", FULL)}})
    return model(T("pot", "soil", "leaves"), els)


def m_stanchion():
    """Queue post with a retractable belt across the block (a row of posts makes the queue's lane)."""
    els = [
        box([5.5, 0, 5.5], [10.5, 0.75, 10.5], "#graphite"),
        box([7.25, 0.75, 7.25], [8.75, 15, 8.75], "#steel"),
        box([7, 15, 7], [9, 16, 9], "#graphite"),
        box([0, 13, 7.9], [7.25, 14.25, 8.1], "#belt", "nsud", {"n": [0, 0, 16, 2.5], "s": [0, 0, 16, 2.5]}),
        box([8.75, 13, 7.9], [16, 14.25, 8.1], "#belt", "nsud", {"n": [0, 0, 16, 2.5], "s": [0, 0, 16, 2.5]}),
    ]
    return model(T("steel", "graphite", "belt"), els)


def m_photo():
    """Photo booth for the 3×4 photo, 2 m: white walls with sample photos on the sides, a blue curtain at the front, an orange
    «ФОТО» header, a stool inside."""
    els = [
        box([0, 0, 1], [1, 30, 16], {"n": "#white", "s": "#white", "e": "#white", "w": "#side", "u": "#white", "d": "#white"},
            uvs={"w": [0, 0, 16, 16]}),
        box([15, 0, 1], [16, 30, 16], {"n": "#white", "s": "#white", "e": "#side", "w": "#white", "u": "#white", "d": "#white"},
            uvs={"e": [0, 0, 16, 16]}),
        box([1, 0, 15], [15, 30, 16], "#white"),
        box([0, 30, 0], [16, 32, 16], {"n": "#side", "s": "#white", "e": "#white", "w": "#white", "u": "#white", "d": "#white"},
            uvs={"n": [0, 0, 16, 2.5]}),
        box([1, 2, 1.5], [15, 30, 2], "#curtain", "nsd", {"n": FULL, "s": FULL}),
        box([0, 0, 0], [16, 1, 1], "#graphite"),
        box([5, 0, 8], [11, 7, 13], "#graphite"),
    ]
    return model(T("white", "side", "curtain", "graphite") | {"side": "mobtalk:block/mfc/photo_side"}, els)


def m_detector():
    """Walk-through metal detector, 2 m: two posts with LED strips and a top beam with a green lamp; one walks through."""
    els = [
        box([0, 0, 5], [2.5, 30, 11], "#detector", uvs={"e": [0, 0, 16, 16], "w": [0, 0, 16, 16]}),
        box([13.5, 0, 5], [16, 30, 11], "#detector", uvs={"e": [0, 0, 16, 16], "w": [0, 0, 16, 16]}),
        box([0, 30, 5], [16, 32, 11], "#detector", uvs={"n": [0, 0, 16, 2], "s": [0, 0, 16, 2]}),
        box([7, 29.25, 7], [9, 30, 9], "#lamp"),
    ]
    return model(T("detector") | {"lamp": "mobtalk:block/mfc/board"}, els)


def m_guard_desk():
    """Guard post: a dark desk with «ОХРАНА» on the front, an old CCTV monitor turned to the guard, the visitors' log open toward
    them and a desk lamp."""
    els = [
        box([0, 0, 2], [16, 12, 16], {"n": "#front", "s": "#graphite", "e": "#graphite", "w": "#graphite", "u": "#graphite", "d": "#graphite"},
            uvs={"n": FULL}),
        box([0, 12, 1.5], [16, 13, 16], "#wood"),
        box([8, 13, 8], [15, 19, 14], {"n": "#beige", "s": "#crt", "e": "#beige", "w": "#beige", "u": "#beige", "d": "#beige"},
            uvs={"s": FULL}),
        box([9, 14, 4], [14, 18, 8], "#beige"),
        box([1.5, 13, 3], [7.5, 13.4, 8], "#log", "nsewu", {"u": FULL}),
        box([2.5, 13, 11], [4.5, 13.5, 13], "#graphite"), box([3.25, 13.5, 11.75], [3.75, 18, 12.25], "#steel"),
        box([2.25, 17, 10.5], [4.75, 18.5, 13], "#blue"),
    ]
    return model(T("graphite", "wood", "beige", "crt", "steel", "blue") | {"front": "mobtalk:block/mfc/guard_front", "log": "mobtalk:block/mfc/logbook"}, els)


def m_sign():
    """The hall's wall sign «МФЦ · ГОСУСЛУГИ», a blue plate on two stand-offs."""
    els = [
        box([-8, 2, 15], [24, 18, 15.5], "#sign", "nsewud", {"n": FULL, "s": [0, 0, 1, 1], "e": [0, 0, 1, 1], "w": [0, 0, 1, 1], "u": [0, 0, 1, 1], "d": [0, 0, 1, 1]}),
        box([-4, 9, 15.5], [-3, 10, 16], "#steel"), box([19, 9, 15.5], [20, 10, 16], "#steel"),
    ]
    return model(T("sign", "steel"), els)


MODELS = {"mfc_terminal": m_terminal, "mfc_board": m_board, "mfc_chairs": m_chairs, "mfc_form_desk": m_form_desk, "mfc_info": m_info,
          "mfc_cooler": m_cooler, "mfc_plant": m_plant, "mfc_stanchion": m_stanchion, "mfc_photo": m_photo, "mfc_detector": m_detector,
          "mfc_guard_desk": m_guard_desk, "mfc_sign": m_sign}
ROT = (("north", 0), ("east", 90), ("south", 180), ("west", 270))


def write_assets():
    os.makedirs(TEX, exist_ok=True)
    for name, fn in TEXTURES.items():
        fn().save(os.path.join(TEX, name + ".png"))
    for n in WINDOWS:
        t_number(n).save(os.path.join(TEX, f"window_{n}.png"))
    for name, fn in MODELS.items():
        write_json(os.path.join(ASSETS, "models", "block", name + ".json"), fn())
        write_json(os.path.join(ASSETS, "models", "item", name + ".json"), {"parent": "mobtalk:block/" + name})
        write_json(os.path.join(ASSETS, "blockstates", name + ".json"),
                   {"variants": {f"facing={d}": {"model": "mobtalk:block/" + name} | ({"y": r} if r else {}) for d, r in ROT}})
    for n in WINDOWS:
        write_json(os.path.join(ASSETS, "models", "block", f"mfc_window_{n}.json"), m_window(n))
    write_json(os.path.join(ASSETS, "models", "item", "mfc_window.json"), {"parent": "mobtalk:block/mfc_window_1"})
    write_json(os.path.join(ASSETS, "blockstates", "mfc_window.json"),
               {"variants": {f"facing={d},number={n}": {"model": f"mobtalk:block/mfc_window_{n}"} | ({"y": r} if r else {})
                             for d, r in ROT for n in WINDOWS}})
    item = os.path.join(ASSETS, "textures", "item")
    t_ticket_item().save(os.path.join(item, "mfc_ticket.png"))
    write_json(os.path.join(ASSETS, "models", "item", "mfc_ticket.json"), {"parent": "item/generated", "textures": {"layer0": "mobtalk:item/mfc_ticket"}})


if __name__ == "__main__":
    write_assets()
    print("ok")
