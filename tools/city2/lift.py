"""Lifts of the 9- and 16-storey panel houses: a Soviet passenger lift (spec §3.2 p.4). Draws the textures in code and
writes the block models, the cabin's models (drawn by the cabin entity's renderer), the control panel's GUI sheet and the
names. The Java side is io.github.mobtalk.game.Lifts (blocks, the cabin entity, the API) and client.game.LiftClient.

    python3 -I tools/city2/lift.py [preview OUT.png]

A lift is a 2 x 2 shaft. On every floor the shaft wall facing the landing holds two door columns (`lift_door`, two blocks
high: a fixed panel and a sliding leaf each, the two leaves part in the middle and leave a 0.9 m doorway); the call button
(`lift_button`) hangs on the landing beside them. The winch (`lift_machine`) stands on the roof slab above the shaft and
finds its floors by the doors under it; the cabin is an entity it spawns. While the cabin stands at a floor, an invisible
`lift_floor` fills the shaft's cells in that floor's slab row, so people stand on the cabin's floor.

For templates: place(tpl, x, y, z, floors, storey, facing) writes all of it into a house.Tpl, see there.

Model space (all models here): the landing (or the cabin's door side) is north, z = 0; the blockstate turns it to the
door's facing. The cabin is 32 x 48 x 32 units (2 x 3 x 2 blocks), its floor's top at y = 0.
"""
import json
import math
import os
import random
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "city"))
import decor as D  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "mobtalk")
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "mobtalk")
TEXDIR = "city2/lift"           # textures/block/city2/lift, models/block/city2/lift

TEX = {}
MODELS = {}
BLOCKSTATES = {}
ITEMS = {}
NAMES = {}
PANEL_FLOORS = range(2, 18)     # control panels are drawn for 2..17 floors


# ---------------------------------------------------------------- pixel helpers

def rnd(name):
    return random.Random("lift:" + name)


def img(w, h=None, c=(0, 0, 0, 0)):
    return Image.new("RGBA", (w, h or w), c)


def put(im, x, y, c):
    if 0 <= x < im.width and 0 <= y < im.height:
        im.putpixel((int(x), int(y)), tuple(c[:3]) + ((c[3],) if len(c) > 3 else (255,)))


def get(im, x, y):
    return im.getpixel((int(x), int(y)))


def shade(c, k):
    return D.shade(tuple(c[:3]) + (255,), k)


def mix(a, b, t):
    return D.mix(a, b, max(0.0, min(1.0, t)))


def rect(im, x0, y0, x1, y1, c):
    for y in range(y0, y1):
        for x in range(x0, x1):
            put(im, x, y, c)


def noise(im, box, base, amount, r):
    x0, y0, x1, y1 = box
    for y in range(y0, y1):
        for x in range(x0, x1):
            put(im, x, y, shade(base, 1 + r.uniform(-amount, amount)))


def bevel(im, box, light=1.18, dark=0.72):
    x0, y0, x1, y1 = box
    for x in range(x0, x1):
        put(im, x, y0, shade(get(im, x, y0), light))
        put(im, x, y1 - 1, shade(get(im, x, y1 - 1), dark))
    for y in range(y0, y1):
        put(im, x0, y, shade(get(im, x0, y), light))
        put(im, x1 - 1, y, shade(get(im, x1 - 1, y), dark))


def disc(im, cx, cy, rad, c, rim=None, light=None):
    """A round button: rim darker, a highlight up-left."""
    for y in range(int(cy - rad - 1), int(cy + rad + 2)):
        for x in range(int(cx - rad - 1), int(cx + rad + 2)):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if d <= rad:
                k = 1.0
                if light and d < rad * 0.55 and (x + 0.5 - cx) + (y + 0.5 - cy) < -rad * 0.3:
                    k = light
                put(im, x, y, shade(c, k))
            elif rim and d <= rad + 1:
                put(im, x, y, rim)


# 3 x 5 pixel font: digits and the few letters the lift needs
FONT = {
    "0": ["###", "#.#", "#.#", "#.#", "###"], "1": [".#.", "##.", ".#.", ".#.", "###"],
    "2": ["##.", "..#", ".#.", "#..", "###"], "3": ["##.", "..#", ".#.", "..#", "##."],
    "4": ["#.#", "#.#", "###", "..#", "..#"], "5": ["###", "#..", "##.", "..#", "##."],
    "6": [".##", "#..", "###", "#.#", "###"], "7": ["###", "..#", ".#.", ".#.", ".#."],
    "8": ["###", "#.#", "###", "#.#", "###"], "9": ["###", "#.#", "###", "..#", "##."],
    "С": [".##", "#..", "#..", "#..", ".##"], "Т": ["###", ".#.", ".#.", ".#.", ".#."],
    "О": [".#.", "#.#", "#.#", "#.#", ".#."], "П": ["###", "#.#", "#.#", "#.#", "#.#"],
    "В": ["##.", "#.#", "##.", "#.#", "##."], "Ы": ["#..#", "#..#", "##.#", "#.##", "##.#"],
    "З": ["##.", "..#", ".#.", "..#", "##."], "Ц": ["#.#.", "#.#.", "#.#.", "#.#.", "####"],
    "Й": [".##.", "#..#", "#.##", "##.#", "#..#"], "Ж": ["#.#.#", "#.#.#", ".###.", "#.#.#", "#.#.#"],
    "И": ["#..#", "#..#", "#.##", "##.#", "#..#"], "Л": [".##", "#.#", "#.#", "#.#", "#.#"],
    "Ф": [".#.", "###", "#.#", "###", ".#."], "К": ["#.#", "#.#", "##.", "#.#", "#.#"],
    "Г": ["###", "#..", "#..", "#..", "#.."], "Ч": ["#.#", "#.#", "###", "..#", "..#"],
    "Е": ["###", "#..", "##.", "#..", "###"], " ": ["..", "..", "..", "..", ".."],
    "Р": ["##.", "#.#", "##.", "#..", "#.."], "А": [".#.", "#.#", "###", "#.#", "#.#"],
    "Х": ["#.#", "#.#", ".#.", "#.#", "#.#"], "Д": [".##.", ".#.#", ".#.#", "####", "#..#"],
    "М": ["#...#", "##.##", "#.#.#", "#...#", "#...#"], "Н": ["#.#", "#.#", "###", "#.#", "#.#"],
    "Я": [".##", "#.#", ".##", "#.#", "#.#"], "У": ["#.#", "#.#", ".##", "..#", "##."],
    ".": [".", ".", ".", ".", "#"], "♥": [".#.#.", "#####", "#####", ".###.", "..#.."],
    "+": ["...", ".#.", "###", ".#.", "..."], "Б": ["###", "#..", "##.", "#.#", "##."], "Ь": ["#..", "#..", "##.", "#.#", "##."], "/": ["..#", "..#", ".#.", "#..", "#.."],
}


def text_width(s):
    return sum(len(FONT[ch][0]) + 1 for ch in s) - 1


def text(im, x, y, s, c, scale=1):
    for ch in s:
        g = FONT[ch]
        for gy, row in enumerate(g):
            for gx, v in enumerate(row):
                if v == "#":
                    for sy in range(scale):
                        for sx in range(scale):
                            put(im, x + gx * scale + sx, y + gy * scale + sy, c)
        x += (len(g[0]) + 1) * scale


# ---------------------------------------------------------------- textures

def wall_texture(which="plain"):
    """The cabin's walls inside: wood-grain laminate in vertical sheets, aluminium mouldings, a rail at waist height, and what
    the tenants left: scratches, a marker scrawl, a burnt spot. 128 px for 64 units (2 texels a unit)."""
    r = rnd("wall")
    im = img(128)
    base = (148, 98, 58)
    for x in range(128):
        sheet = x // 32
        phase = r.uniform(0, 6.28) if x % 32 == 0 else None
        if phase is not None:
            sheet_phase = phase
        for y in range(128):
            gx = x % 32
            wave = math.sin(y / 9.0 + sheet * 1.7 + gx * 0.35) + 0.6 * math.sin(y / 3.1 + gx * 1.3 + sheet)
            k = 1.0 + 0.06 * wave + r.uniform(-0.025, 0.025)
            if (gx * 7 + sheet * 3) % 11 == 0:
                k *= 0.9
            put(im, x, y, shade(base, k))
    for x in range(0, 128, 32):  # aluminium mouldings between the sheets
        for y in range(128):
            put(im, x, y, (196, 198, 200))
            put(im, x + 1, y, (150, 152, 156))
    for y0 in (58,):  # the waist rail (about 1 m above the floor at v = 96 - 2*y)
        for x in range(128):
            put(im, x, y0, (210, 212, 214))
            put(im, x, y0 + 1, (176, 178, 182))
            put(im, x, y0 + 2, (120, 122, 126))
    for y in range(90, 96):  # kick plate at the floor: brushed steel
        for x in range(128):
            put(im, x, y, shade((170, 172, 176), 1.06 if y == 90 else 0.92 + 0.04 * ((x * 5 + y) % 3)))
    for _ in range(18):  # scratches
        x, y = r.randrange(4, 124), r.randrange(10, 86)
        dx, dy = r.choice([(1, 0), (1, 1), (1, -1), (2, 1)])
        for i in range(r.randrange(3, 9)):
            put(im, x + i * dx // 1, y + (i * dy) // 2, shade(get(im, min(127, x + i * dx), y), 1.3))
    ink = (34, 30, 46)
    if which == "back":   # the back wall, at eye height (v = 96 - 2 * y: y 1.6 m is v 32)
        text(im, 21, 30, "ЦОЙ ЖИВ", ink)
        text(im, 43, 40, "♥", (150, 30, 40))
    if which == "left":
        text(im, 30, 66, "ВОВА+ЛЕНА", ink)
    if which != "left":
        return im
    for _ in range(40):  # the burnt spot under the scrawl
        a, d = r.uniform(0, 6.28), r.uniform(0, 3.2)
        x, y = 104 + math.cos(a) * d, 52 + math.sin(a) * d * 0.7
        put(im, x, y, mix((40, 28, 20), get(im, x, y)[:3], d / 3.6))
    return im


def floor_texture():
    """Ribbed rubber over the cabin's floor, a steel sill strip at the door (v = 0)."""
    r = rnd("floor")
    im = img(128)
    base = (70, 66, 64)
    for y in range(128):
        for x in range(128):
            k = 1.0 + r.uniform(-0.04, 0.04)
            if (x + y) % 6 == 0 or (x - y) % 6 == 0:
                k *= 1.16
            put(im, x, y, shade(base, k))
    for y in range(0, 4):
        for x in range(128):
            put(im, x, y, shade((176, 178, 182), (1.15, 1.0, 0.9, 0.7)[y] * (0.95 + 0.05 * ((x // 2) % 2))))
    for _ in range(6):  # dried mud near the door
        x, y = r.randrange(10, 54), r.randrange(6, 20)
        for i in range(r.randrange(3, 7)):
            put(im, x + r.randrange(-2, 3), y + r.randrange(-2, 3), (92, 80, 62))
    return im


def ceiling_texture():
    """The ceiling seen from below: white painted sheet, the lamp's grille in the middle (drawn on the plafond part)."""
    r = rnd("ceiling")
    im = img(128)
    noise(im, (0, 0, 128, 128), (222, 220, 210), 0.03, r)
    for x in range(0, 128, 16):  # perforated vent holes along the back
        for y in (54, 58):
            put(im, x + 6, y, (120, 118, 112))
            put(im, x + 10, y, (120, 118, 112))
    for _ in range(5):  # soot from a lighter
        x, y = r.randrange(10, 50), r.randrange(10, 50)
        for i in range(12):
            put(im, x + r.randrange(-3, 4), y + r.randrange(-2, 3), shade(get(im, x, y), 0.6))
    return im


def lamp_texture():
    im = img(32)
    for y in range(32):
        for x in range(32):
            d = max(abs(x - 15.5), abs(y - 15.5)) / 16
            put(im, x, y, mix((255, 250, 228), (236, 228, 200), d))
    for x in range(32):  # the grille bars
        if x % 6 == 2:
            for y in range(32):
                put(im, x, y, (190, 190, 186))
    bevel(im, (0, 0, 32, 32), 1.0, 0.8)
    return im


def outer_texture(name, base):
    """Painted sheet steel with stiffening ribs and rivets: the cabin's outside, the shaft doors' back."""
    r = rnd(name)
    im = img(128)
    noise(im, (0, 0, 128, 128), base, 0.035, r)
    for x in range(0, 128, 32):
        for y in range(128):
            put(im, x, y, shade(base, 0.78))
            put(im, x + 1, y, shade(base, 1.12))
            if y % 12 == 6:
                put(im, x + 3, y, shade(base, 1.3))
                put(im, x + 3, y + 1, shade(base, 0.7))
    for _ in range(10):
        x = r.randrange(128)
        for y in range(r.randrange(128), 128):
            if r.random() < 0.6:
                put(im, x, y, shade(get(im, x, y), 0.93))
    return im


def door_texture(name, base, inside):
    """A door leaf on a 64 px sheet (8 x 31.5 units at about 8 texels a unit across, 2 down). The cabin's leaves: beige enamel
    with a brushed steel kick plate and a black rubber strip on the meeting edge (u 60..64). The landing doors: brown hammer
    paint, scratched, a slot for the emergency key near the top."""
    r = rnd(name)
    im = img(64)
    noise(im, (0, 0, 64, 64), base, 0.025, r)
    for y in range(64):
        for x in range(64):
            if ((x * 7 + y * 13) % 29) == 0:
                put(im, x, y, shade(get(im, x, y), 1.06 if inside else 0.9))
    if inside:
        for x in range(64):   # a pressed stiffening groove across the middle and the kick plate below
            put(im, x, 30, shade(base, 0.82))
            put(im, x, 31, shade(base, 1.12))
            for y in range(54, 64):
                put(im, x, y, shade((176, 178, 182), 0.92 + 0.08 * ((x * 3 + y) % 4 == 0) + (0.1 if y == 54 else 0)))
        for y in range(64):
            for x in range(58, 64):
                put(im, x, y, (34, 32, 30) if x > 59 else (70, 66, 60))
            put(im, 0, y, shade(base, 0.8))
        for _ in range(8):
            x, y = r.randrange(6, 54), r.randrange(4, 50)
            for i in range(r.randrange(2, 5)):
                put(im, x + i, y, shade(base, 0.86))
    else:
        for _ in range(14):
            x, y = r.randrange(64), r.randrange(8, 60)
            for i in range(r.randrange(2, 6)):
                put(im, x + i, y + (i // 2), shade(base, 1.28))
        rect(im, 26, 4, 30, 7, (30, 26, 24))  # the key slot near the top
        put(im, 27, 5, (90, 90, 90))
    return im


def frame_texture():
    """The steel portal round a landing door: dark grey, worn bright at the edge people touch."""
    r = rnd("frame")
    im = img(32)
    noise(im, (0, 0, 32, 32), (70, 72, 76), 0.05, r)
    for y in range(32):
        put(im, 0, y, (110, 112, 118))
        put(im, 31, y, (40, 40, 44))
    return im


def shaft_texture():
    r = rnd("shaft")
    im = img(32)
    noise(im, (0, 0, 32, 32), (128, 126, 120), 0.07, r)
    for _ in range(30):
        put(im, r.randrange(32), r.randrange(32), shade((128, 126, 120), r.choice([0.7, 0.8, 1.15])))
    for y in range(32):  # a grease streak down the guide rail
        put(im, 6, y, (60, 56, 50))
        put(im, 7, y, (84, 80, 72))
    return im


def panel_texture(floors):
    """The control panel on the cabin's right wall: brushed aluminium, a column of black round buttons with white numbers
    (two columns from ten floors up), red СТОП and the yellow ВЫЗОВ bell, the capacity plate. 4 texels a unit, the panel is
    6 x 20 units: 24 x 80 px in the corner of a 128 sheet."""
    r = rnd(f"panel{floors}")
    im = img(128)
    W, H = 24, 80
    for y in range(H):
        for x in range(W):
            put(im, x, y, shade((178, 180, 184), 1.0 + 0.05 * math.sin(y * 1.9) + r.uniform(-0.02, 0.02)))
    bevel(im, (0, 0, W, H), 1.2, 0.6)
    for x, y in ((2, 2), (W - 3, 2), (2, H - 3), (W - 3, H - 3)):  # screws
        put(im, x, y, (90, 90, 96))
    rect(im, 4, 4, W - 4, 11, (26, 26, 30))  # the plate: 320 kg
    text(im, 6, 5, "320", (230, 230, 230))
    cols = 1 if floors <= 6 else 2
    rows = math.ceil(floors / cols)
    top, bottom = 14, H - 17
    step = (bottom - top) / max(rows, 1)
    burnt = r.randrange(1, floors + 1)
    for i in range(floors):
        n = i + 1
        col = i // rows
        row = rows - 1 - (i % rows)
        cy = top + step * (row + 0.5)
        cx = 16 if cols == 1 else 7 + col * 10
        if n == burnt:  # burnt with a lighter: a melted brown blot
            disc(im, cx, cy, 2.6, (70, 46, 30), rim=(40, 34, 30))
            put(im, cx - 1, cy - 1, (110, 70, 40))
        else:
            disc(im, cx, cy, 2.6, (30, 30, 34), rim=(110, 112, 118), light=1.9)
        if cols == 1:
            s = str(n)
            text(im, int(cx - 4 - text_width(s)), int(cy - 2), s, (24, 24, 28))
    disc(im, W / 2, H - 12, 3.2, (196, 30, 26), rim=(110, 112, 118), light=1.5)     # СТОП
    disc(im, W / 2, H - 5, 2.6, (226, 196, 40), rim=(110, 112, 118), light=1.4)     # ВЫЗОВ (the bell)
    return im


def button_texture(lit):
    """The landing's call button: a small grey box with a round black button in a ring that glows red once pressed."""
    im = img(32)
    noise(im, (0, 0, 32, 32), (150, 152, 158), 0.03, rnd("btn"))
    bevel(im, (0, 0, 32, 32), 1.2, 0.65)
    ring = (255, 70, 50) if lit else (110, 40, 36)
    disc(im, 15.5, 15.5, 8, ring)
    disc(im, 15.5, 15.5, 5.6, (34, 34, 38), light=1.7)
    if lit:
        for y in range(32):
            for x in range(32):
                d = math.hypot(x - 15.5, y - 15.5)
                if 5.6 < d <= 8:
                    put(im, x, y, mix((255, 210, 170), ring, abs(d - 6.8) / 1.4))
    return im


def machine_textures():
    r = rnd("machine")
    green = (70, 110, 80)
    side = img(32)
    noise(side, (0, 0, 32, 32), green, 0.05, r)
    for y in range(4, 28, 4):  # cooling fins of the motor
        for x in range(32):
            put(side, x, y, shade(green, 0.7))
    plate = img(32)
    noise(plate, (0, 0, 32, 32), (60, 60, 64), 0.06, r)
    for y in range(0, 32, 8):
        for x in range(32):
            put(plate, x, y, (40, 40, 44))
    drum = img(32)
    for y in range(32):
        for x in range(32):
            put(drum, x, y, shade((120, 120, 124), 0.8 + 0.3 * math.sin(x / 32 * math.pi)))
        if y % 4 == 0:
            for x in range(32):
                put(drum, x, y, (60, 58, 56))
    cable = img(16)
    for y in range(16):
        for x in range(16):
            put(cable, x, y, shade((96, 92, 86), 0.85 + 0.3 * (((x + y) // 2) % 2)))
    return side, plate, drum, cable


def gui_texture():
    """textures/gui/lift_panel.png (256 x 256): the panel as the player sees it up close. 0,0: the plate 96 x 200;
    sprites at y 208: button black (16 x 16) at x 0, black lit at 16, burnt at 32, СТОП at 48, ВЫЗОВ at 64, hover ring at
    80; digits 6 x 10 (the font doubled) from x 96, one per 8 px, in white."""
    r = rnd("gui")
    im = img(256)
    W, H = 96, 200
    for y in range(H):
        for x in range(W):
            put(im, x, y, shade((178, 180, 184), 1.0 + 0.045 * math.sin(y * 1.3 + x * 0.05) + r.uniform(-0.02, 0.02)))
    bevel(im, (0, 0, W, H), 1.25, 0.55)
    bevel(im, (1, 1, W - 1, H - 1), 1.1, 0.8)
    for x, y in ((5, 5), (W - 6, 5), (5, H - 6), (W - 6, H - 6)):
        disc(im, x + 0.5, y + 0.5, 2, (120, 122, 128), rim=(70, 70, 76))
        put(im, x - 1, y, (60, 60, 64))
        put(im, x + 1, y, (60, 60, 64))
    rect(im, 12, 12, W - 12, 34, (24, 24, 28))  # the plate
    bevel(im, (12, 12, W - 12, 34), 0.8, 1.3)
    text(im, 17, 15, "ЛИФТ", (230, 230, 230), 1)
    text(im, 17, 23, "320 КГ", (200, 200, 200), 1)
    text(im, 52, 15, "4 ЧЕЛ", (200, 200, 200), 1)
    for _ in range(30):
        x, y = r.randrange(8, W - 8), r.randrange(40, H - 8)
        for i in range(r.randrange(2, 6)):
            put(im, x + i, y, shade(get(im, x + i, y), 1.25))
    y0 = 208

    def button(x, c, lit=False, light=1.8):
        cx, cy = x + 7.5, y0 + 7.5
        disc(im, cx, cy, 7.4, (96, 98, 104))
        disc(im, cx, cy, 6.6, (150, 152, 158))
        if lit:
            disc(im, cx, cy, 6.6, (255, 120, 70))
            disc(im, cx, cy, 5.6, (255, 196, 140))
        disc(im, cx, cy, 5.0, c, light=light)
    button(0, (30, 30, 34))
    button(16, (30, 30, 34), lit=True)
    button(32, (66, 44, 30), light=None)
    for _ in range(14):  # the melted plastic
        put(im, 32 + 7 + r.randrange(-3, 4), y0 + 7 + r.randrange(-3, 4), r.choice([(40, 26, 18), (100, 70, 44), (130, 96, 60)]))
    button(48, (200, 30, 26), light=1.5)
    button(64, (230, 200, 40), light=1.4)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            if 6.6 <= d <= 7.6:
                put(im, 80 + x, y0 + y, (255, 255, 255, 150))
    for i, ch in enumerate("0123456789"):
        text(im, 96 + i * 8, y0, ch, (240, 240, 240), 2)
    for i, ch in enumerate("0123456789"):  # dark digits for the panel's labels
        text(im, 96 + i * 8, y0 + 12, ch, (30, 30, 34), 2)
    text(im, 96, y0 + 26, "СТОП", (30, 30, 34), 1)
    text(im, 96, y0 + 34, "ВЫЗОВ", (30, 30, 34), 1)
    return im


# ---------------------------------------------------------------- styles
#
# Four looks, as lifts are met in Russian panel houses: "soviet" (the 1970s-80s cabin in wood-grain laminate, brown landing
# doors; the names without a suffix), "painted" (sheet steel painted two-tone like the stairwell, grey doors, stickers),
# "steel" (after the 2010s overhaul: brushed stainless, a mirror, LED light, steel doors), "plastic" (the 1990s-2000s
# replacement: cream embossed polymer panels with brown trim, blue landing doors).

STYLES = ("soviet", "painted", "steel", "plastic")


def sfx(style):
    return "" if style == "soviet" else "_" + style


def brushed(im, box, base, r, amount=0.05, streak=0.04):
    """Brushed metal: fine horizontal streaks."""
    x0, y0, x1, y1 = box
    for y in range(y0, y1):
        row = r.uniform(-streak, streak)
        for x in range(x0, x1):
            put(im, x, y, shade(base, 1 + row + r.uniform(-amount, amount) * 0.5))


def sticker(im, x, y, w, h, paper, ink, r):
    """A stuck-on advert: a paper slip, lines of print, a torn corner."""
    rect(im, x, y, x + w, y + h, paper)
    for ly in range(y + 2, y + h - 1, 3):
        for lx in range(x + 2, x + w - 2):
            if r.random() < 0.7:
                put(im, lx, ly, ink)
    put(im, x + w - 1, y, get(im, x + w - 1, y - 1) if y > 0 else paper)
    bevel(im, (x, y, x + w, y + h), 1.05, 0.85)


def wall_style(style, which):
    r = rnd(f"wall_{style}_{which}")
    im = img(128)
    if style == "painted":
        light, dark = (150, 180, 172), (66, 104, 92)
        for y in range(128):
            for x in range(128):
                put(im, x, y, shade(dark if y >= 60 else light, 1 + r.uniform(-0.03, 0.03)))
        for x in range(128):
            put(im, x, 59, (40, 60, 52))
            put(im, x, 60, shade(dark, 1.15))
        for x in range(0, 128, 32):   # the sheets' welded seams with a row of rivets
            for y in range(128):
                put(im, x, y, shade(get(im, x, y), 0.8))
                put(im, x + 1, y, shade(get(im, x + 1, y), 1.1))
                if y % 10 == 4:
                    put(im, x + 4, y, shade(get(im, x + 4, y), 1.3))
                    put(im, x + 4, y + 1, shade(get(im, x + 4, y + 1), 0.7))
        for _ in range(26):   # chipped paint: grey primer, a rust spot
            x, y = r.randrange(2, 126), r.randrange(8, 92)
            c = r.choice([(120, 118, 112), (128, 84, 56)])
            for i in range(r.randrange(2, 6)):
                put(im, x + r.randrange(-1, 2), y + r.randrange(-1, 2), c)
        for y in range(90, 96):
            for x in range(128):
                put(im, x, y, shade((96, 96, 92), 0.9 + 0.06 * ((x * 3 + y) % 3)))
        ink = (30, 30, 34)
        if which == "back":
            text(im, 18, 30, "КИНО", (180, 30, 30))
            sticker(im, 40, 26, 14, 10, (236, 232, 210), (60, 60, 70), r)
        elif which == "left":
            text(im, 10, 34, "СПАРТАК", (190, 30, 36))
            sticker(im, 44, 66, 12, 8, (240, 220, 80), ink, r)
        else:
            sticker(im, 6, 30, 12, 9, (236, 236, 236), (40, 40, 140), r)
            sticker(im, 36, 70, 10, 7, (236, 236, 236), ink, r)
        return im
    if style == "steel":
        base = (184, 188, 192)
        brushed(im, (0, 0, 128, 128), base, r)
        for x in range(0, 128, 32):
            for y in range(128):
                put(im, x, y, (110, 114, 118))
                put(im, x + 1, y, (214, 218, 222))
        for y in range(90, 96):   # a darker satin kick plate
            for x in range(128):
                put(im, x, y, shade((140, 144, 150), 1.08 if y == 90 else 0.95))
        if which == "back":   # the mirror over the rail
            for y in range(16, 56):
                for x in range(5, 59):
                    t = (y - 16) / 40
                    c = mix((150, 176, 190), (96, 118, 132), t)
                    if (x + y) % 23 < 3 or (x + y) % 23 == 7:
                        c = shade(c, 1.18)
                    put(im, x, y, c)
            for x in range(4, 60):
                put(im, x, 15, (90, 92, 96))
                put(im, x, 56, (90, 92, 96))
            for y in range(15, 57):
                put(im, 4, y, (90, 92, 96))
                put(im, 59, y, (90, 92, 96))
        for _ in range(22):   # scratches in the steel
            x, y = r.randrange(4, 124), r.randrange(10, 88)
            dx = r.choice([1, 2])
            for i in range(r.randrange(3, 10)):
                put(im, x + i * dx, y + i // 3, (226, 230, 234))
        if which == "left":   # a name scratched with a key
            text(im, 16, 40, "ДИМА", (222, 226, 230))
            text(im, 16, 46, "2015", (222, 226, 230))
        if which == "plain":
            sticker(im, 40, 34, 14, 10, (246, 246, 246), (180, 30, 30), r)   # the rules of use
        return im
    # plastic: cream polymer-coated steel embossed with small diamonds, brown plastic mouldings and trim
    base = (224, 210, 178)
    for y in range(128):
        for x in range(128):
            k = 1 + r.uniform(-0.02, 0.02)
            m = (x + y) % 8, (x - y) % 8
            if m[0] == 0 or m[1] == 0:
                k *= 0.93
            elif m[0] == 1 or m[1] == 1:
                k *= 1.04
            put(im, x, y, shade(base, k))
    brown = (112, 72, 44)
    for x in range(0, 128, 32):
        for y in range(128):
            for i in range(3):
                put(im, x + i, y, shade(brown, (1.2, 1.0, 0.75)[i]))
    for y in range(56, 61):   # a brown moulding at rail height
        for x in range(128):
            put(im, x, y, shade(brown, (1.25, 1.1, 1.0, 0.9, 0.7)[y - 56]))
    for y in range(88, 96):
        for x in range(128):
            put(im, x, y, shade(brown, 0.85 if y == 88 else 1.0 + r.uniform(-0.03, 0.03)))
    ink = (40, 40, 110)
    if which == "back":   # an advert board in an aluminium frame
        rect(im, 12, 18, 52, 50, (180, 182, 186))
        rect(im, 14, 20, 50, 48, (246, 244, 236))
        text(im, 16, 22, "РЕМОНТ", (190, 30, 30))
        text(im, 16, 30, "ОКОН", (190, 30, 30))
        for ly in range(38, 46, 3):
            for lx in range(16, 48):
                if r.random() < 0.7:
                    put(im, lx, ly, (40, 40, 44))
    elif which == "left":
        text(im, 8, 36, "ЗДЕСЬ БЫЛ", ink)
        text(im, 14, 42, "ВАСЯ", ink)
    for _ in range(12):
        x, y = r.randrange(4, 124), r.randrange(10, 86)
        for i in range(r.randrange(2, 6)):
            put(im, x + i, y, shade(get(im, x + i, y), 0.88))
    return im


def floor_style(style):
    r = rnd(f"floor_{style}")
    im = img(128)
    if style == "painted":   # brown linoleum, worn pale on the path from the door
        for y in range(128):
            for x in range(128):
                k = 1 + r.uniform(-0.04, 0.04)
                c = (120, 72, 46)
                if 20 < x < 44 and y < 50:
                    c = mix(c, (150, 118, 90), 0.4 - y / 140)
                put(im, x, y, shade(c, k))
    elif style == "steel":   # grey speckled granite tiles
        for y in range(128):
            for x in range(128):
                c = (118, 118, 120) if ((x // 32) + (y // 32)) % 2 == 0 else (106, 106, 110)
                v = r.random()
                if v < 0.08:
                    c = (60, 60, 64)
                elif v < 0.14:
                    c = (176, 174, 170)
                put(im, x, y, shade(c, 1 + r.uniform(-0.04, 0.04)))
                if x % 32 == 0 or y % 32 == 0:
                    put(im, x, y, (78, 78, 80))
    else:   # plastic: checkered linoleum
        for y in range(128):
            for x in range(128):
                c = (210, 190, 150) if ((x // 16) + (y // 16)) % 2 == 0 else (130, 88, 60)
                put(im, x, y, shade(c, 1 + r.uniform(-0.04, 0.04)))
    for y in range(0, 4):   # the steel sill at the door
        for x in range(128):
            put(im, x, y, shade((176, 178, 182), (1.15, 1.0, 0.9, 0.7)[y] * (0.95 + 0.05 * ((x // 2) % 2))))
    for _ in range(5):
        x, y = r.randrange(10, 54), r.randrange(6, 20)
        for i in range(r.randrange(3, 7)):
            put(im, x + r.randrange(-2, 3), y + r.randrange(-2, 3), (92, 80, 62))
    return im


def ceiling_style(style):
    r = rnd(f"ceiling_{style}")
    im = img(128)
    if style == "steel":
        brushed(im, (0, 0, 128, 128), (176, 180, 184), r)
        return im
    base = {"painted": (214, 208, 186), "plastic": (232, 222, 196)}[style]
    noise(im, (0, 0, 128, 128), base, 0.03, r)
    if style == "painted":
        for _ in range(9):   # soot from lighters, burnt matches stuck to it
            x, y = r.randrange(8, 56), r.randrange(8, 56)
            for i in range(14):
                put(im, x + r.randrange(-3, 4), y + r.randrange(-2, 3), shade(get(im, x, y), 0.55))
            put(im, x, y + 3, (40, 30, 24))
    return im


def lamp_style(style):
    im = img(32)
    if style == "steel":   # an LED panel: white field, a grid of brighter dots
        for y in range(32):
            for x in range(32):
                c = (250, 252, 255) if (x % 4 == 1 and y % 4 == 1) else (232, 238, 246)
                put(im, x, y, c)
        bevel(im, (0, 0, 32, 32), 0.9, 0.7)
        return im
    if style == "painted":   # a round milky plafond on a square plate
        noise(im, (0, 0, 32, 32), (200, 198, 188), 0.03, rnd("lamp_painted"))
        for y in range(32):
            for x in range(32):
                d = math.hypot(x - 15.5, y - 15.5)
                if d <= 14:
                    put(im, x, y, mix((255, 250, 232), (232, 222, 196), d / 14))
                elif d <= 15:
                    put(im, x, y, (150, 148, 140))
        return im
    for y in range(32):   # plastic: frosted with ribs
        for x in range(32):
            put(im, x, y, (250, 244, 224) if y % 5 else (226, 216, 190))
    bevel(im, (0, 0, 32, 32), 1.0, 0.75)
    return im


def leaf_style(style, inside):
    """A door leaf (see door_texture) in a style: the cabin's (inside) or the landing's."""
    r = rnd(f"leaf_{style}_{inside}")
    im = img(64)
    if style == "steel":
        brushed(im, (0, 0, 64, 64), (188, 192, 196) if inside else (176, 180, 186), r)
        for _ in range(10):
            x, y = r.randrange(64), r.randrange(4, 60)
            for i in range(r.randrange(3, 8)):
                put(im, x + i, y + i // 3, (228, 232, 236))
    else:
        base = {("painted", True): (150, 160, 152), ("painted", False): (130, 134, 138),
                ("plastic", True): (222, 208, 176), ("plastic", False): (52, 82, 140)}[(style, inside)]
        noise(im, (0, 0, 64, 64), base, 0.03, r)
        for y in range(64):
            for x in range(64):
                if ((x * 7 + y * 13) % 29) == 0:
                    put(im, x, y, shade(get(im, x, y), 0.92))
        for _ in range(16 if style == "painted" else 8):
            x, y = r.randrange(64), r.randrange(8, 60)
            for i in range(r.randrange(2, 6)):
                put(im, x + i, y + i // 2, shade(base, 1.3) if style == "painted" else shade(base, 0.85))
    if inside:
        if style == "plastic":
            for x in range(64):
                for y in (29, 30, 31):
                    put(im, x, y, shade((112, 72, 44), (1.2, 1.0, 0.75)[y - 29]))
        for x in range(64):
            for y in range(54, 64):
                put(im, x, y, shade((160, 164, 168) if style != "plastic" else (112, 72, 44), 0.92 + (0.1 if y == 54 else 0)))
        for y in range(64):
            for x in range(58, 64):
                put(im, x, y, (34, 32, 30) if x > 59 else (70, 66, 60))
            put(im, 0, y, shade(get(im, 1, y), 0.8))
    else:
        rect(im, 26, 4, 30, 7, (30, 26, 24))
        put(im, 27, 5, (90, 90, 90))
        if style == "painted":
            sticker(im, 6, 20, 12, 9, (236, 236, 236), (40, 40, 140), r)
    return im


def frame_style(style):
    r = rnd(f"frame_{style}")
    im = img(32)
    if style == "steel":
        brushed(im, (0, 0, 32, 32), (168, 172, 178), r)
    else:
        noise(im, (0, 0, 32, 32), {"painted": (84, 88, 90), "plastic": (96, 66, 44)}[style], 0.05, r)
    for y in range(32):
        put(im, 0, y, shade(get(im, 0, y), 1.3))
        put(im, 31, y, shade(get(im, 31, y), 0.6))
    return im


def button_style(style, lit):
    """The landing's call button: painted, a black square in a grey box and an orange lamp over it; steel, a steel disc in a
    blue light ring; plastic, the Soviet one in a beige box."""
    im = img(32)
    if style == "painted":
        noise(im, (0, 0, 32, 32), (120, 122, 126), 0.04, rnd("btn_painted"))
        bevel(im, (0, 0, 32, 32), 1.2, 0.65)
        disc(im, 15.5, 7, 3.4, (255, 170, 60) if lit else (120, 70, 30), rim=(60, 60, 64), light=1.3 if lit else None)
        rect(im, 9, 13, 23, 27, (60, 60, 64))
        rect(im, 10, 14, 22, 26, (30, 30, 32))
        bevel(im, (10, 14, 22, 26), 1.6, 0.7)
        return im
    if style == "steel":
        brushed(im, (0, 0, 32, 32), (180, 184, 190), rnd("btn_steel"))
        bevel(im, (0, 0, 32, 32), 1.15, 0.7)
        ring = (120, 220, 255) if lit else (90, 100, 110)
        disc(im, 15.5, 15.5, 8, ring)
        disc(im, 15.5, 15.5, 6.2, (200, 204, 210), light=1.15)
        put(im, 15, 13, (120, 124, 130))
        put(im, 16, 13, (120, 124, 130))   # the braille dots
        put(im, 15, 16, (120, 124, 130))
        if lit:
            for y in range(32):
                for x in range(32):
                    d = math.hypot(x - 15.5, y - 15.5)
                    if 6.2 < d <= 8:
                        put(im, x, y, mix((230, 250, 255), ring, abs(d - 7.1) / 1.0))
        return im
    noise(im, (0, 0, 32, 32), (214, 200, 170), 0.03, rnd("btn_plastic"))
    bevel(im, (0, 0, 32, 32), 1.15, 0.7)
    ring = (255, 70, 50) if lit else (110, 40, 36)
    disc(im, 15.5, 15.5, 8, ring)
    disc(im, 15.5, 15.5, 5.6, (90, 56, 36), light=1.5)
    return im


# the panels by style: plate colour, button body, ring, the burnt button's look
PANEL = {
    "soviet": {"plate": (178, 180, 184), "btn": (30, 30, 34), "ring": (110, 112, 118), "square": False},
    "painted": {"plate": (146, 150, 148), "btn": (28, 28, 30), "ring": (80, 82, 86), "square": True},
    "steel": {"plate": (196, 200, 204), "btn": (170, 174, 180), "ring": (120, 124, 130), "square": False, "display": True},
    "plastic": {"plate": (220, 206, 176), "btn": (96, 60, 38), "ring": (120, 84, 56), "square": False},
}


def panel_button(im, cx, cy, rad, c, rim, square, light=1.9):
    if square:
        x0, y0 = int(cx - rad), int(cy - rad)
        n = int(rad * 2) + 1
        rect(im, x0 - 1, y0 - 1, x0 + n + 1, y0 + n + 1, rim)
        rect(im, x0, y0, x0 + n, y0 + n, c)
        if light:
            put(im, x0, y0, shade(c, 3))
    else:
        disc(im, cx, cy, rad, c, rim=rim, light=light)


def panel_style(style, floors):
    """The cabin's control panel (see panel_texture) in a style."""
    p = PANEL[style]
    r = rnd(f"panel_{style}{floors}")
    im = img(128)
    W, H = 24, 80
    if style == "steel":
        brushed(im, (0, 0, W, H), p["plate"], r)
    else:
        for y in range(H):
            for x in range(W):
                put(im, x, y, shade(p["plate"], 1.0 + r.uniform(-0.025, 0.025)))
    bevel(im, (0, 0, W, H), 1.2, 0.6)
    for x, y in ((2, 2), (W - 3, 2), (2, H - 3), (W - 3, H - 3)):
        put(im, x, y, (90, 90, 96))
    if p.get("display"):   # the floor display: red digits on black
        rect(im, 5, 3, W - 5, 11, (16, 10, 10))
        text(im, 10, 4, "1", (255, 60, 40))
    else:
        rect(im, 4, 4, W - 4, 11, (26, 26, 30))
        text(im, 6, 5, "320" if style != "plastic" else "400", (230, 230, 230))
    cols = 1 if floors <= 6 else 2
    rows = math.ceil(floors / cols)
    top, bottom = 14, H - 17
    step = (bottom - top) / max(rows, 1)
    burnt = r.randrange(1, floors + 1) if style != "steel" else 0
    for i in range(floors):
        n = i + 1
        col = i // rows
        row = rows - 1 - (i % rows)
        cy = top + step * (row + 0.5)
        cx = 16 if cols == 1 else 7 + col * 10
        if n == burnt:
            panel_button(im, cx, cy, 2.6, (70, 46, 30), (40, 34, 30), p["square"], light=None)
            put(im, cx - 1, cy - 1, (110, 70, 40))
        else:
            panel_button(im, cx, cy, 2.6, p["btn"], p["ring"], p["square"], light=1.6 if style == "steel" else 1.9)
        if cols == 1:
            s = str(n)
            text(im, int(cx - 4 - text_width(s)), int(cy - 2), s, (24, 24, 28))
    panel_button(im, W / 2, H - 12, 3.2, (196, 30, 26), p["ring"], p["square"], light=1.5)
    panel_button(im, W / 2, H - 5, 2.6, (226, 196, 40), p["ring"], p["square"], light=1.4)
    return im


def gui_style(style):
    """textures/gui/lift_panel[_style].png: the panel up close, the same layout as gui_texture."""
    p = PANEL[style]
    r = rnd(f"gui_{style}")
    im = img(256)
    W, H = 96, 200
    if style == "steel":
        brushed(im, (0, 0, W, H), p["plate"], r, 0.04, 0.035)
    else:
        for y in range(H):
            for x in range(W):
                put(im, x, y, shade(p["plate"], 1.0 + r.uniform(-0.025, 0.025)))
    bevel(im, (0, 0, W, H), 1.25, 0.55)
    bevel(im, (1, 1, W - 1, H - 1), 1.1, 0.8)
    for x, y in ((5, 5), (W - 6, 5), (5, H - 6), (W - 6, H - 6)):
        disc(im, x + 0.5, y + 0.5, 2, shade(p["plate"], 0.7), rim=shade(p["plate"], 0.45))
        put(im, x - 1, y, (60, 60, 64))
        put(im, x + 1, y, (60, 60, 64))
    rect(im, 12, 12, W - 12, 34, (24, 24, 28) if not p.get("display") else (14, 8, 8))
    bevel(im, (12, 12, W - 12, 34), 0.8, 1.3)
    if p.get("display"):
        text(im, 17, 15, "ЛИФТ", (255, 70, 50), 1)
        text(im, 17, 23, "400 КГ", (220, 60, 40), 1)
        text(im, 56, 15, "5 ЧЕЛ", (220, 60, 40), 1)
    else:
        text(im, 17, 15, "ЛИФТ", (230, 230, 230), 1)
        text(im, 17, 23, "320 КГ" if style != "plastic" else "400 КГ", (200, 200, 200), 1)
        text(im, 52, 15, "4 ЧЕЛ" if style != "plastic" else "5 ЧЕЛ", (200, 200, 200), 1)
    for _ in range(30 if style != "steel" else 18):
        x, y = r.randrange(8, W - 8), r.randrange(40, H - 8)
        for i in range(r.randrange(2, 6)):
            put(im, x + i, y, shade(get(im, x + i, y), 1.2))
    if style == "painted":
        sticker(im, 62, 120, 22, 14, (236, 232, 210), (60, 60, 70), r)
    y0 = 208

    def button(x, c, lit=False, light=1.8):
        cx, cy = x + 7.5, y0 + 7.5
        if p["square"]:
            rect(im, x + 1, y0 + 1, x + 15, y0 + 15, (90, 92, 96))
            rect(im, x + 2, y0 + 2, x + 14, y0 + 14, (150, 152, 158))
            if lit:
                rect(im, x + 2, y0 + 2, x + 14, y0 + 14, (255, 150, 70))
            rect(im, x + 3, y0 + 3, x + 13, y0 + 13, c)
            if light:
                for i in range(3, 8):
                    put(im, x + i, y0 + 3, shade(c, light * 1.6))
            return
        disc(im, cx, cy, 7.4, shade(p["ring"], 0.85))
        disc(im, cx, cy, 6.6, shade(p["ring"], 1.3))
        if lit:
            on = (120, 220, 255) if style == "steel" else (255, 120, 70)
            disc(im, cx, cy, 6.6, on)
            disc(im, cx, cy, 5.6, mix(on, (255, 255, 255), 0.5))
        disc(im, cx, cy, 5.0, c, light=light)
    button(0, p["btn"], light=1.3 if style == "steel" else 1.8)
    button(16, p["btn"], lit=True, light=1.3 if style == "steel" else 1.8)
    if style == "steel":   # nobody burns steel: a scratched one
        button(32, p["btn"], light=1.3)
        for _ in range(6):
            put(im, 32 + 7 + r.randrange(-3, 4), y0 + 7 + r.randrange(-3, 4), (230, 234, 238))
    else:
        button(32, (66, 44, 30), light=None)
        for _ in range(14):
            put(im, 32 + 7 + r.randrange(-3, 4), y0 + 7 + r.randrange(-3, 4), r.choice([(40, 26, 18), (100, 70, 44), (130, 96, 60)]))
    button(48, (200, 30, 26), light=1.5)
    button(64, (230, 200, 40), light=1.4)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5) if not p["square"] else max(abs(x - 7.5), abs(y - 7.5)) + 0.6
            if 6.6 <= d <= 7.6:
                put(im, 80 + x, y0 + y, (255, 255, 255, 150))
    for i, ch in enumerate("0123456789"):
        text(im, 96 + i * 8, y0, ch, (240, 240, 240), 2)
    for i, ch in enumerate("0123456789"):
        text(im, 96 + i * 8, y0 + 12, ch, (30, 30, 34), 2)
    text(im, 96, y0 + 26, "СТОП", (30, 30, 34), 1)
    text(im, 96, y0 + 34, "ВЫЗОВ", (30, 30, 34), 1)
    return im


def style_textures(style):
    s = sfx(style)
    TEX["wall" + s] = wall_style(style, "plain")
    TEX["wall_back" + s] = wall_style(style, "back")
    TEX["wall_left" + s] = wall_style(style, "left")
    TEX["floor" + s] = floor_style(style)
    TEX["ceiling" + s] = ceiling_style(style)
    TEX["lamp" + s] = lamp_style(style)
    TEX["leaf_cab" + s] = leaf_style(style, True)
    TEX["leaf_shaft" + s] = leaf_style(style, False)
    TEX["frame" + s] = frame_style(style)
    TEX["button" + s] = button_style(style, False)
    TEX["button_lit" + s] = button_style(style, True)
    for n in PANEL_FLOORS:
        TEX[f"panel_{n}" + s] = panel_style(style, n)


def make_textures():
    TEX["wall"] = wall_texture()
    TEX["wall_back"] = wall_texture("back")
    TEX["wall_left"] = wall_texture("left")
    TEX["floor"] = floor_texture()
    TEX["ceiling"] = ceiling_texture()
    TEX["lamp"] = lamp_texture()
    TEX["outer"] = outer_texture("outer", (120, 128, 120))
    TEX["shaft_back"] = outer_texture("shaft_back", (96, 74, 60))
    TEX["leaf_cab"] = door_texture("leaf_cab", (214, 200, 168), True)
    TEX["leaf_shaft"] = door_texture("leaf_shaft", (110, 72, 52), False)
    TEX["frame"] = frame_texture()
    TEX["shaft"] = shaft_texture()
    TEX["button"] = button_texture(False)
    TEX["button_lit"] = button_texture(True)
    for n in PANEL_FLOORS:
        TEX[f"panel_{n}"] = panel_texture(n)
    TEX["machine_side"], TEX["machine_plate"], TEX["drum"], TEX["cable"] = machine_textures()
    for style in STYLES[1:]:
        style_textures(style)


# ---------------------------------------------------------------- models

DENSITY = {"wall": 2, "wall_back": 2, "wall_left": 2, "floor": 2, "ceiling": 2, "outer": 2, "shaft_back": 2}   # texels per unit on 128 px sheets


def uv_world(face, f, t, size, density, span=(32, 48, 32)):
    """UV of a face from its place in the whole object (so a pattern runs on across elements and parts): u from the
    coordinate that goes to the right of someone looking at the face, v from the top down."""
    X, Y, Z = span
    x0, y0, z0 = f
    x1, y1, z1 = t
    k = density * 16.0 / size
    if face == "north":
        u0, u1, v0, v1 = X - x1, X - x0, Y - y1, Y - y0
    elif face == "south":
        u0, u1, v0, v1 = x0, x1, Y - y1, Y - y0
    elif face == "east":
        u0, u1, v0, v1 = Z - z1, Z - z0, Y - y1, Y - y0
    elif face == "west":
        u0, u1, v0, v1 = z0, z1, Y - y1, Y - y0
    elif face == "up":
        u0, u1, v0, v1 = x0, x1, z0, z1
    else:
        u0, u1, v0, v1 = x0, x1, Z - z1, Z - z0
    return [round(max(0, min(16, v * k)), 4) for v in (u0, v0, u1, v1)]


def el(f, t, faces, span=(32, 48, 32)):
    """faces: face -> texture key, or (key, uv). Keys in DENSITY get world UVs."""
    e = {"from": [round(v, 4) for v in f], "to": [round(v, 4) for v in t], "faces": {}}
    for face, spec in faces.items():
        if isinstance(spec, tuple):
            key, uv = spec
            if uv is None:
                uv = D.auto_uv(face, f, t)
        else:
            key = spec
            uv = uv_world(face, f, t, 128, DENSITY[key], span) if key in DENSITY else D.auto_uv(face, f, t)
        e["faces"][face] = {"texture": "#" + key, "uv": uv}
    return e


def model(name, textures, elements, particle=None, ao=True):
    m = {"parent": "minecraft:block/block",
         "textures": {k: (v if ":" in v else f"mobtalk:block/{TEXDIR}/{v}") for k, v in textures.items()}}
    m["textures"].setdefault("particle", m["textures"][particle or next(iter(textures))])
    if elements is not None:
        m["elements"] = elements
    if not ao:
        m["ambientocclusion"] = False
    MODELS[name] = m


def split_parts(elements):
    """Cuts the cabin's elements at y = 32 (a block model reaches 32 at most): (lower part, upper part shifted down 32 and
    drawn two blocks higher)."""
    lo, hi = [], []
    for e in elements:
        y0, y1 = e["from"][1], e["to"][1]
        if y1 <= 32:
            lo.append(e)
            continue
        if y0 >= 32:
            e2 = json.loads(json.dumps(e))
            e2["from"][1] -= 32
            e2["to"][1] -= 32
            hi.append(e2)
            continue
        a, b = json.loads(json.dumps(e)), json.loads(json.dumps(e))
        a["to"][1] = 32
        b["from"][1] = 0
        b["to"][1] -= 32
        a["faces"].pop("up", None)
        b["faces"].pop("down", None)
        for part, cut_lo, cut_hi in ((a, y0, 32), (b, 32, y1)):  # crop the side faces' v to the piece
            for face in ("north", "south", "east", "west"):
                if face in part["faces"]:
                    uv = part["faces"][face]["uv"]
                    full = y1 - y0
                    v0, v1 = uv[1], uv[3]
                    top = v0 + (v1 - v0) * (y1 - cut_hi) / full
                    bot = v0 + (v1 - v0) * (y1 - cut_lo) / full
                    uv[1], uv[3] = round(top, 4), round(bot, 4)
        lo.append(a)
        hi.append(b)
    return lo, hi


def cabin_models():
    """The cabin: floor, three walls, the front with a 1 m doorway, the ceiling with its lamp, the control panel by the door
    on the right wall (as you face the doors from inside), the door operator box on the roof."""
    W = "wall"
    els = [
        el((0, -3, 0), (32, 0, 32), {"up": "floor", "down": "outer", "north": "outer", "south": "outer", "east": "outer", "west": "outer"}),
        el((0, 0, 0), (1, 45, 32), {"east": "wall_left", "west": "outer", "north": "outer", "south": "outer"}),        # left wall
        el((31, 0, 0), (32, 45, 32), {"west": W, "east": "outer", "north": "outer", "south": "outer"}),      # right wall
        el((1, 0, 31), (31, 45, 32), {"north": "wall_back", "south": "outer"}),                                        # back wall
        el((1, 0, 0), (8, 45, 1), {"south": W, "north": "outer", "east": ("frame", [0, 0, 1, 16])}),         # front, left of the door
        el((24, 0, 0), (31, 45, 1), {"south": W, "north": "outer", "west": ("frame", [0, 0, 1, 16])}),       # front, right
        el((8, 31.5, 0), (24, 45, 1), {"south": W, "north": "outer", "down": ("frame", [0, 0, 16, 1])}),       # over the door
        el((0, 45, 0), (32, 48, 32), {"down": "ceiling", "up": "outer", "north": "outer", "south": "outer", "east": "outer", "west": "outer"}),
        el((11, 44.4, 11), (21, 45, 21), {"down": ("lamp", [0, 0, 16, 16]), "north": ("frame", [0, 0, 16, 1]),
                                          "south": ("frame", [0, 0, 16, 1]), "east": ("frame", [0, 0, 16, 1]),
                                          "west": ("frame", [0, 0, 16, 1])}),
        el((5, 48, 1), (27, 52, 6), {"up": ("machine_plate", None), "north": ("machine_plate", None), "south": ("machine_plate", None),
                                     "east": ("machine_plate", None), "west": ("machine_plate", None)}),     # door operator
        el((14, 48, 14), (18, 56, 18), {"north": ("cable", None), "south": ("cable", None), "east": ("cable", None),
                                        "west": ("cable", None), "up": ("cable", None)}),                    # rope hitch
    ]
    # the waist rail along the side and back walls, a real bar you can lean on
    els.append(el((1, 15.5, 2), (1.8, 16.3, 30), {"up": ("frame", [0, 0, 16, 1]), "east": ("frame", [0, 0, 16, 1]), "down": ("frame", [0, 0, 16, 1])}))
    els.append(el((2, 15.5, 30.2), (30, 16.3, 31), {"up": ("frame", [0, 0, 16, 1]), "north": ("frame", [0, 0, 16, 1]), "down": ("frame", [0, 0, 16, 1])}))
    lo, hi = split_parts(els)
    for style in STYLES:   # the same body in every style, only the textures change
        s = sfx(style)
        tx = {"wall": "wall" + s, "wall_back": "wall_back" + s, "wall_left": "wall_left" + s, "floor": "floor" + s, "ceiling": "ceiling" + s,
              "outer": "outer", "frame": "frame" + s, "lamp": "lamp" + s, "machine_plate": "machine_plate", "cable": "cable"}
        model("cabin_lower" + s, tx, lo, particle="outer")
        model("cabin_upper" + s, tx, hi, particle="outer")
        for n in PANEL_FLOORS:  # the control panel: a plate on the right wall, 6 x 20 units, 4 texels a unit (uv = units / 2)
            model(f"cabin_panel_{n}" + s, {"p": f"panel_{n}" + s, "s": "frame" + s}, [
                el((30.2, 12, 3), (31, 32, 9), {"west": ("p", [0, 0, 3, 10]), "north": ("s", [0, 0, 1, 16]), "south": ("s", [0, 0, 1, 16]),
                                                "up": ("s", [0, 0, 1, 16]), "down": ("s", [0, 0, 1, 16])})], particle="s")
        # a leaf of the cabin's doors: 8 x 30 x 1 units, enamel inside, steel outside; drawn twice by the renderer
        for name, uv in (("cabin_leaf", [0, 0, 16, 16]), ("cabin_leaf_r", [16, 0, 0, 16])):   # the rubber edge meets in the middle
            model(name + s, {"l": "leaf_cab" + s, "o": "outer", "e": "frame" + s}, [
                el((0, 0, 0), (8, 31.5, 1), {"south": ("l", uv), "north": ("o", [0, 1, 4, 16]), "east": ("e", [0, 0, 1, 16]),
                                             "west": ("e", [0, 0, 1, 16]), "up": ("e", [0, 0, 16, 1])})], particle="l")


def door_models():
    """A landing door column, model space: landing north (z = 0), shaft south. This is the left column as seen from the
    shaft (x 0..16, the doorway's middle at x = 16); the right one is mirrored. Layers from the landing: the steel portal
    (z 0..1), the fixed panel (x 1..8, z 1..3), the leaf (z 3..4.5, drawn by the door's renderer: x 8..16 shut, slides 7
    units behind the fixed panel), the wall behind the fixed panel (x 0..8, z 4.5..16), the doorway x 8..16."""
    def column(mirror):
        def mx(f, t):
            if not mirror:
                return f, t
            return (16 - t[0], f[1], f[2]), (16 - f[0], t[1], t[2])

        def m(f, t, faces):
            if mirror:
                faces = {({"east": "west", "west": "east"}.get(k, k)): v for k, v in faces.items()}
            f, t = mx(f, t)
            return el(f, t, faces)
        lower = [
            m((0, 0, 0), (1, 16, 16), {"north": "frame", "east": "frame", "west": "frame", "south": "shaft"}),     # the portal's jamb
            m((1, 0, 0), (8, 16, 3), {"north": "leaf_shaft", "east": "frame"}),                                   # fixed panel
            m((1, 0, 3), (8, 16, 16), {"east": "shaft", "south": "shaft", "north": "shaft"}),                     # wall behind it
            m((8, 0, 0), (16, 0.6, 3), {"up": "frame", "north": "frame"}),                                       # the sill
        ]
        upper = [
            m((0, 0, 0), (1, 16, 16), {"north": "frame", "east": "frame", "west": "frame", "south": "shaft", "up": "frame"}),
            m((1, 0, 0), (8, 15.5, 3), {"north": "leaf_shaft", "east": "frame"}),
            m((1, 0, 3), (8, 16, 16), {"east": "shaft", "south": "shaft", "north": "shaft", "up": "shaft"}),
            m((1, 15.5, 0), (16, 16, 16), {"north": "frame", "down": "frame", "south": "shaft", "up": "shaft"}),   # the lintel
        ]
        return lower, upper
    for style in STYLES:
        s = sfx(style)
        tx = {"frame": "frame" + s, "leaf_shaft": "leaf_shaft" + s, "shaft": "shaft"}
        for side, mirror in (("left", False), ("right", True)):
            lo, hi = column(mirror)
            model(f"door_{side}_lower" + s, tx, lo, particle="frame")
            model(f"door_{side}_upper" + s, tx, hi, particle="frame")
        # the leaf, 8 x 30 x 1.5: painted outside (to the landing), bare steel to the shaft; at x 8..16 of the left column
        model("door_leaf" + s, {"l": "leaf_shaft" + s, "b": "shaft_back", "e": "frame" + s}, [
            el((0, 0, 0), (8, 31.5, 1.5), {"north": ("l", [0, 0.25, 8, 16]), "south": ("b", [0, 1, 4, 16]), "east": ("e", [0, 0, 1, 16]),
                                         "west": ("e", [0, 0, 1, 16]), "up": ("e", [0, 0, 16, 1]), "down": ("e", [0, 0, 16, 1])})],
            particle="l")
    rot = {"north": 0, "east": 90, "south": 180, "west": 270}
    variants = {}
    for f, y in rot.items():
        for side in ("left", "right"):
            for half in ("lower", "upper"):
                for open_ in ("false", "true"):
                    for style in STYLES:
                        v = {"model": f"mobtalk:block/{TEXDIR}/door_{side}_{half}{sfx(style)}"}
                        if y:
                            v["y"] = y
                        variants[f"facing={f},half={half},open={open_},side={side},style={style}"] = v
    BLOCKSTATES["lift_door"] = {"variants": variants}
    ITEMS["lift_door"] = {"parent": f"mobtalk:block/{TEXDIR}/door_left_lower"}
    NAMES["lift_door"] = ("Дверь шахты лифта", "Lift shaft door")


def button_models():
    """The call button on the landing wall (model north = the landing; the box hangs on the cell's south side, 1.05-1.4 m
    above the floor when the block is in the slab + 2 row)."""
    for style, lit in ((st, l) for st in STYLES for l in (False, True)):
        name = ("button_lit" if lit else "button") + sfx(style)
        model(name, {"b": name, "s": "frame" + sfx(style)}, [
            el((5.5, 0.5, 14.6), (10.5, 6.5, 16), {"north": ("b", [0, 0, 16, 16]), "east": ("s", [0, 0, 1, 16]), "west": ("s", [0, 0, 1, 16]),
                                                   "up": ("s", [0, 0, 16, 1]), "down": ("s", [0, 0, 16, 1])}),
            el((7.2, 2.6, 14.0), (8.8, 4.4, 14.6), {"north": ("b", [7, 7, 9, 9]), "east": ("b", [5, 7, 6, 9]), "west": ("b", [5, 7, 6, 9]),
                                                    "up": ("b", [7, 5, 9, 6]), "down": ("b", [7, 5, 9, 6])}),
        ], particle="s")
    rot = {"north": 0, "east": 90, "south": 180, "west": 270}
    BLOCKSTATES["lift_button"] = {"variants": {
        f"facing={f},lit={str(lit).lower()},style={st}": dict({"model": f"mobtalk:block/{TEXDIR}/{'button_lit' if lit else 'button'}{sfx(st)}"},
                                                             **({"y": y} if y else {}))
        for f, y in rot.items() for lit in (False, True) for st in STYLES}}
    ITEMS["lift_button"] = {"parent": f"mobtalk:block/{TEXDIR}/button"}
    NAMES["lift_button"] = ("Кнопка вызова лифта", "Lift call button")


def machine_models():
    """The winch on the roof slab over the shaft: a frame, the motor with fins, the rope drum, the brake, ropes down."""
    t = {"side": "machine_side", "plate": "machine_plate", "drum": "drum", "cable": "cable"}
    els = [
        el((0, 0, 0), (16, 2, 16), {f: ("plate", None) for f in ("up", "down", "north", "south", "east", "west")}),
        el((1, 2, 3), (8, 10, 13), {"north": ("side", None), "south": ("side", None), "west": ("plate", None), "east": ("plate", None),
                                    "up": ("side", None)}),
        el((9, 3, 4), (15, 9, 12), {"north": ("drum", None), "south": ("drum", None), "up": ("drum", None), "east": ("plate", None),
                                    "west": ("plate", None), "down": ("drum", None)}),
        el((8, 4, 6), (9, 8, 10), {f: ("plate", None) for f in ("north", "south", "up", "down")}),
        el((11.5, 0, 7.5), (12.5, 4, 8.5), {f: ("cable", None) for f in ("north", "south", "east", "west")}),
    ]
    model("machine", t, els, particle="side")
    BLOCKSTATES["lift_machine"] = {"variants": {"": {"model": f"mobtalk:block/{TEXDIR}/machine"}}}
    ITEMS["lift_machine"] = {"parent": f"mobtalk:block/{TEXDIR}/machine"}
    NAMES["lift_machine"] = ("Лебёдка лифта", "Lift winch")
    model("floor", {"particle": "floor"}, None)
    BLOCKSTATES["lift_floor"] = {"variants": {"": {"model": f"mobtalk:block/{TEXDIR}/floor"}}}
    NAMES["lift_floor"] = ("Пол кабины лифта", "Lift cabin floor")


def make_models():
    cabin_models()
    door_models()
    button_models()
    machine_models()


# ---------------------------------------------------------------- templates

def place(tpl, x, y, z, floors, storey, facing="north", style="soviet"):
    """Writes a lift into a house template (house.Tpl). (x, y, z): the shaft's 2 x 2 corner with the smallest x and z, in
    the slab row of floor 1; facing: where the doors look (to the landing). The house draws the shaft's walls itself; here
    the shaft is cleared, every floor gets its two door columns in the wall row on the landing side (rows slab + 1, slab +
    2) and its call button on the landing beside them (row slab + 2, the right of the doors seen from the landing), and
    the winch stands on the roof slab (row y + floors * storey) over the shaft. style: one of STYLES (pick_style gives a
    realistic mix); the cabin takes the doors' style."""
    assert style in STYLES, style
    d = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}[facing]
    p = {"north": (1, 0), "east": (0, 1), "south": (-1, 0), "west": (0, -1)}[facing]   # model +x, seen from the shaft: right
    cells = [(x + i, z + j) for i in (0, 1) for j in (0, 1)]
    # the door columns: the two shaft cells next to the landing, moved one step out; "left" (seen from the shaft) first
    front = sorted([c for c in cells if (c[0] + d[0], c[1] + d[1]) not in cells], key=lambda c: c[0] * p[0] + c[1] * p[1])
    left, right = [(c[0] + d[0], c[1] + d[1]) for c in front]
    button = (left[0] - p[0] + d[0], left[1] - p[1] + d[1])
    for k in range(floors):
        s = y + k * storey
        for cx, cz in cells:
            for yy in range(s, s + storey):
                tpl.set(cx, yy, cz, "minecraft:air")
        for (cx, cz), side in ((left, "left"), (right, "right")):
            for half, yy in (("lower", s + 1), ("upper", s + 2)):
                tpl.set(cx, yy, cz, "lift_door", {"facing": facing, "half": half, "open": "false", "side": side, "style": style})
        tpl.set(button[0], s + 2, button[1], "lift_button", {"facing": facing, "lit": "false", "style": style})
    top = y + floors * storey
    tpl.set(x, top, z, "lift_machine")


def pick_style(seed):
    """A style for a house's lift, by a seed (a house's or a section's): most were overhauled to steel, many are Soviet still."""
    r = random.Random(f"liftstyle:{seed}")
    return r.choices(STYLES, weights=(35, 20, 35, 10))[0]


# ---------------------------------------------------------------- writing

def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1, ensure_ascii=False)
        f.write("\n")


def save_all():
    tdir = os.path.join(ASSETS, "textures", "block", TEXDIR)
    os.makedirs(tdir, exist_ok=True)
    for name, im in TEX.items():
        im.save(os.path.join(tdir, name + ".png"))
    gdir = os.path.join(ASSETS, "textures", "gui")
    os.makedirs(gdir, exist_ok=True)
    gui_texture().save(os.path.join(gdir, "lift_panel.png"))
    for style in STYLES[1:]:
        gui_style(style).save(os.path.join(gdir, f"lift_panel{sfx(style)}.png"))
    for name, m in MODELS.items():
        write(os.path.join(ASSETS, "models", "block", TEXDIR, name + ".json"), m)
    for name, bs in BLOCKSTATES.items():
        write(os.path.join(ASSETS, "blockstates", name + ".json"), bs)
        if name in ITEMS:
            write(os.path.join(ASSETS, "models", "item", name + ".json"), ITEMS[name])
        drop = name if name != "lift_floor" else None
        pools = []
        if drop:
            entry = {"type": "minecraft:item", "name": f"mobtalk:{drop}"}
            if name == "lift_door":
                entry["conditions"] = [{"condition": "minecraft:block_state_property", "block": "mobtalk:lift_door",
                                        "properties": {"half": "lower"}}]
            pools = [{"rolls": 1, "entries": [entry], "conditions": [{"condition": "minecraft:survives_explosion"}]}]
        write(os.path.join(DATA, "loot_table", "blocks", name + ".json"), {"type": "minecraft:block", "pools": pools})
    for lang, i in (("ru_ru", 0), ("en_us", 1)):
        p = os.path.join(ASSETS, "lang", lang + ".json")
        with open(p, encoding="utf-8") as f:
            d = json.load(f)
        for name, titles in NAMES.items():
            d[f"block.mobtalk.{name}"] = titles[i]
        d["entity.mobtalk.lift_cabin"] = ("Кабина лифта", "Lift cabin")[i]
        for key, ru, en in LANG:
            d[key] = (ru, en)[i]
        with open(p, "w", encoding="utf-8") as f:
            json.dump(d, f, indent=2, ensure_ascii=False)
            f.write("\n")
    pick = os.path.join(ROOT, "src", "main", "resources", "data", "minecraft", "tags", "block", "mineable", "pickaxe.json")
    have = json.load(open(pick)) if os.path.exists(pick) else {"replace": False, "values": []}
    add = ["mobtalk:lift_door", "mobtalk:lift_button", "mobtalk:lift_machine"]
    have["values"] = [v for v in have["values"] if v not in add] + add
    write(pick, have)


LANG = [
    ("subtitles.mobtalk.lift_motor", "Гудит лифт", "Lift hums"),
    ("subtitles.mobtalk.lift_start", "Лифт трогается", "Lift starts"),
    ("subtitles.mobtalk.lift_stop", "Лифт останавливается", "Lift stops"),
    ("subtitles.mobtalk.lift_door_open", "Открываются двери лифта", "Lift doors open"),
    ("subtitles.mobtalk.lift_door_close", "Закрываются двери лифта", "Lift doors close"),
    ("subtitles.mobtalk.lift_button", "Нажата кнопка лифта", "Lift button pressed"),
    ("subtitles.mobtalk.lift_relay", "Щёлкает реле лифта", "Lift relay clicks"),
    ("subtitles.mobtalk.lift_bell", "Звонок диспетчеру", "Lift alarm bell"),
    ("mobtalk.lift.title", "Лифт", "Lift"),
    ("mobtalk.lift.floor", "Этаж %s", "Floor %s"),
    ("mobtalk.lift.stop", "СТОП", "STOP"),
    ("mobtalk.lift.bell", "ВЫЗОВ", "ALARM"),
    ("mobtalk.lift.dispatcher", "Диспетчер: «Слушаю… Ждите, мастер выехал»", "Dispatcher: “Listening… wait, the mechanic is on his way”"),
    ("mobtalk.lift.busy", "Лифт занят", "Lift is busy"),
    ("mobtalk.lift.called", "Лифт вызван", "Lift called"),
    ("mobtalk.tune.liftSpeedTenths", "Скорость лифта: %s десятых блока в секунду", "Lift speed: %s tenths of a block per second"),
    ("mobtalk.tune.liftSpeedTenths.tooltip", "1 блок = 1 м. Настоящий советский лифт ходит 1 м/с, 16-этажный — 1,4 м/с.",
     "1 block = 1 m. A real Soviet lift runs at 1 m/s, a 16-storey one at 1.4 m/s."),
    ("mobtalk.tune.liftDoorSeconds", "Двери лифта открыты: %s с", "Lift doors stay open: %s s"),
    ("mobtalk.tune.liftDoorSeconds.tooltip", "Сколько секунд двери стоят открытыми, если в проёме никого нет.",
     "How long the doors stay open when nobody is in the doorway."),
    ("mobtalk.tune.liftVillagerChance", "Жители ездят на лифте: %s%%", "Villagers ride lifts: %s%%"),
    ("mobtalk.tune.liftVillagerChance.tooltip", "Шанс в минуту, что житель у дверей лифта вызовет его и поедет на свой или случайный этаж. 0 — только лестница.",
     "Chance a minute that a villager by the lift doors calls it and rides to their own or a random floor. 0: stairs only."),
]


def preview(out):
    """A contact sheet of the textures, 4x, for a look without the game."""
    names = list(TEX)
    cell = 136
    cols = 6
    sheet = img(cols * cell, ((len(names) + cols - 1) // cols) * cell, (40, 40, 44, 255))
    for i, n in enumerate(names):
        t = TEX[n]
        k = max(1, 128 // t.width)
        sheet.alpha_composite(t.resize((t.width * k, t.height * k), Image.NEAREST), ((i % cols) * cell + 4, (i // cols) * cell + 4))
    g = gui_texture()
    sheet2 = img(sheet.width + 512, max(sheet.height, 512), (40, 40, 44, 255))
    sheet2.alpha_composite(sheet, (0, 0))
    sheet2.alpha_composite(g.resize((512, 512), Image.NEAREST), (sheet.width, 0))
    sheet2.save(out)


def main():
    make_textures()
    make_models()
    if len(sys.argv) > 2 and sys.argv[1] == "preview":
        preview(sys.argv[2])
        return
    save_all()
    print("lift:", len(BLOCKSTATES), "blocks,", len(MODELS), "models,", len(TEX), "textures")


if __name__ == "__main__":
    main()
