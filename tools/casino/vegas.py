"""The Las Vegas set (tools/casino/decor.py calls these): the detailed slot machine, the twenty-one, roulette and card tables and
the chair (they replace the plain first models of the same names), and the resort's own decor: patterned casino carpets, damask
wallpaper, a wood wainscot, a fluted gold-and-ivory column, a lit coffer for the ceiling, the progressive jackpot board, a palm
crown for a trunk of logs, and a giant die. Same pixel-art rules as decor.py: 16x16 textures (32 or 48 wide for boards two or three
blocks wide), one texel per model pixel, a limited palette; everything that should flicker is an animated texture.
"""
import colorsys
import math

D = None  # the tools/casino/decor.py module, set by it before any call


def strip(name, frames, frametime=None, timed=None):
    """An animated texture: a vertical strip of frames. timed: [(frame, ticks), ...] for frames held for different times."""
    w, h = frames[0].size
    img = D.new(w, h * len(frames))
    for i, f in enumerate(frames):
        img.paste(f, (0, h * i))
    D.TEX[name] = img
    anim = {"interpolate": False}
    if timed:
        anim["frametime"] = 1
        anim["frames"] = [{"index": i, "time": t} for i, t in timed]
    else:
        anim["frametime"] = frametime or 2
    D.ANIM[name] = {"animation": anim}
    return img


# ---------------------------------------------------------------- slot machine

SYMBOLS = {  # 4x4 reel symbols: rows, colour, highlight
    "7": (["####", "...#", "..#.", ".#.."], (214, 28, 34), (255, 140, 130)),
    "cherry": (["..#.", ".#.#", "#..#", "#..."], (206, 22, 44), (255, 130, 140)),
    "bell": ([".##.", ".##.", "####", "..#."], (238, 186, 40), (255, 240, 150)),
    "bar": (["####", "#..#", "####", "...."], (34, 30, 38), (120, 116, 130)),
    "gem": ([".##.", "####", ".##.", "...."], (52, 120, 230), (170, 210, 255)),
}
REEL = ["7", "cherry", "bell", "bar", "gem", "cherry", "7", "bell", "gem", "bar"]
PITCH = 5  # a symbol and a gap


def reel_column(start):
    """A reel's strip from symbol index start downward, 4 wide, long enough to scroll through."""
    img = D.new(4, PITCH * len(REEL) * 2, (236, 232, 222, 255))
    px = img.load()
    for k in range(len(REEL) * 2):
        rows, colour, hi = SYMBOLS[REEL[(start + k) % len(REEL)]]
        if REEL[(start + k) % len(REEL)] == "cherry":
            px[2, k * PITCH] = D.rgba((60, 140, 50))
            px[1, k * PITCH + 1] = D.rgba((60, 140, 50))
            px[3, k * PITCH + 1] = D.rgba((60, 140, 50))
        for gy, row in enumerate(rows):
            for gx, c in enumerate(row):
                if c == "#" and not (REEL[(start + k) % len(REEL)] == "cherry" and gy < 2):
                    px[gx, k * PITCH + gy] = D.rgba(hi if (gx + gy) == min(i + j for j, r in enumerate(rows) for i, cc in enumerate(r) if cc == "#") else colour)
    return img


def screen_frame(offsets, blur):
    """The reels behind the glass: three windows, each showing its strip from offsets[i] (texels); blur dims a spinning reel."""
    img = D.new()
    D.rect(img, (0, 0, 16, 16), (16, 18, 30))
    px = img.load()
    for i in range(3):
        col = reel_column(i * 3)
        x0 = 2 + i * 4
        for y in range(3, 12):
            for x in range(4):
                c = col.getpixel((x, (y - 3 + offsets[i]) % col.height))
                if blur[i]:
                    c = D.shade(c, 0.85)
                    if (y + offsets[i]) % 2:
                        c = D.shade((236, 232, 222), 0.8)
                k = 0.7 if y in (3, 11) else (0.86 if y in (4, 10) else 1.0)  # the reel's curve
                px[x0 + x, y] = D.shade(c, k)
        px[x0 + 3, 3] = D.shade(px[x0 + 3, 3], 0.7)
    for x in range(16):  # the chrome bezel
        px[x, 0] = D.shade(D.CHROME, 1.15)
        px[x, 1] = D.shade(D.CHROME, 0.8)
        px[x, 15] = D.shade(D.CHROME, 0.7)
    for y in range(16):
        px[0, y] = D.shade(D.CHROME, 1.05)
        px[15, y] = D.shade(D.CHROME, 0.75)
    for x in (1, 14):  # pay line arrows
        px[x, 7] = D.rgba((255, 220, 90))
    # the credit and win meters in red LEDs
    D.rect(img, (2, 13, 14, 15), (34, 10, 12))
    for x in range(3, 13):
        if x % 3:
            px[x, 13] = D.rgba((255, 70, 50))
    return img


def slot_screen():
    """Spinning reels that stop one by one on a pay line (7, cherries, 7), hold, and spin again."""
    frames, timed = [], []
    stop = [(0, ), (0, ), (0, )]
    target = [6 * PITCH - 2, 5 * PITCH - 2, 0 * PITCH - 2]  # the strip offsets that put 7 / cherry / 7 on the pay line
    target = [t % (PITCH * len(REEL) * 2) for t in target]
    for step in range(12):
        offs, blur = [], []
        for i in range(3):
            spinning = step < 6 + i * 2
            offs.append((target[i] + (12 - step) * 3) if spinning else target[i])
            blur.append(spinning)
        frames.append(screen_frame(offs, blur))
        timed.append((step, 2))
    timed[-1] = (11, 80)  # hold the result
    del stop
    return strip("slot_screen", frames, timed=timed)


def slot_marquee():
    """The topper: «777» in gold on a red field under an arch of bulbs that chase (two frames)."""
    frames = []
    for phase in (0, 1):
        img = D.new()
        D.rect(img, (0, 0, 16, 16), (176, 22, 30))
        px = img.load()
        for y in range(16):
            for x in range(16):
                px[x, y] = D.shade(px[x, y], 1.15 - y * 0.03)
        for x in range(16):  # the bulb row
            lit = (x + phase) % 2 == 0
            px[x, 0] = D.rgba((255, 250, 210) if lit else (150, 90, 40))
            px[x, 1] = D.rgba(D.GOLD_DARK)
        sevens = ["###", "..#", ".#.", ".#."]
        for i in range(3):
            x0 = 2 + i * 4
            for gy, row in enumerate(sevens):
                for gx, c in enumerate(row):
                    if c == "#":
                        px[x0 + gx, 3 + gy] = D.rgba((255, 250, 200) if gy == 0 else (255, 214, 70))
            px[x0 + 2, 7] = D.rgba((120, 10, 16))
        for x in range(16):
            px[x, 8] = D.rgba(D.GOLD)
            px[x, 9] = D.rgba(D.GOLD_DARK)
        # below the gold line: the game's name in small white dots, then the pay table rows
        for x in range(2, 14):
            if x % 2 == phase:
                px[x, 11] = D.rgba((255, 255, 255))
        for y in (13, 14):
            for x in range(2, 14):
                px[x, y] = D.rgba((250, 210, 80) if (x + y) % 3 else (110, 14, 20))
        frames.append(img)
    return strip("slot_marquee", frames, 6)


def led_strip():
    """A rainbow light strip: colour bands that run along it (one column of it is used on a thin edge)."""
    frames = []
    for f in range(8):
        img = D.new()
        px = img.load()
        for y in range(16):
            h = ((y + f * 2) % 16) / 16.0
            r, g, b = colorsys.hsv_to_rgb(h, 0.75, 1.0)
            for x in range(16):
                px[x, y] = (int(r * 255), int(g * 255), int(b * 255), 255)
        frames.append(img)
    return strip("led_strip", frames, 2)


def slot_body():
    """The cabinet front: lacquered red, a lit belly glass with a lucky horseshoe, a chrome plate with the bill acceptor."""
    img = D.lacquer("slot_body", D.LACQUER)
    px = img.load()
    D.rect(img, (3, 2, 13, 6), (190, 196, 204))
    D.bevel(img, (3, 2, 13, 6), 1.2, 0.7)
    D.rect(img, (5, 3, 11, 4), (24, 24, 28))  # the bill slot
    px[11, 4] = D.rgba((60, 230, 90))  # its green light
    D.rect(img, (2, 8, 14, 15), (250, 214, 120))  # the belly glass, lit from behind
    D.bevel(img, (2, 8, 14, 15), 1.1, 0.75)
    for (x, y) in ((5, 9), (4, 10), (4, 11), (5, 12), (10, 9), (11, 10), (11, 11), (10, 12), (6, 13), (9, 13), (7, 13), (8, 13)):
        px[x, y] = D.rgba((150, 100, 40))  # a horseshoe
    for (x, y) in ((6, 9), (9, 9)):
        px[x, y] = D.rgba((255, 255, 230))
    return img


def slot_side():
    img = D.lacquer("slot_side", (120, 14, 20))
    px = img.load()
    for y in range(2, 14):
        px[13, y] = D.rgba(D.GOLD)  # a gold pinstripe and a small 7 emblem
    for (x, y) in ((6, 6), (7, 6), (8, 6), (8, 7), (7, 8), (7, 9)):
        px[x, y] = D.rgba(D.GOLD)
    return img


def slot_machine():
    """A slot machine two blocks tall. Lower block: plinth with a chrome kick plate, the red cabinet with the lit belly glass and
    the bill acceptor, a chrome payout tray full of coins, the button deck with real buttons and a big spin button, rainbow light
    strips up the front corners. Upper block: the reels behind glass in a chrome bezel, a lit arched topper with «777» and a pay
    table, a red-and-white candle lamp on top, the lever on the right."""
    P = "palette"
    side = "side"
    lower = [
        D.box((1, 0, 2), (15, 1.5, 15), "black"),
        D.pal_box((1, 1.2, 1.8), (15, 1.6, 2.2), "chrome"),
        D.box((1.5, 1.5, 3), (14.5, 12, 14.5), {"north": "body", "south": side, "east": side, "west": side, "down": side}),
        # the payout tray: a chrome bowl sticking out, coins in it
        D.pal_box((3, 2.5, 1.2), (13, 3.2, 3), "chrome"),
        D.pal_box((3, 3.2, 1.2), (3.6, 4.6, 3), "chrome"),
        D.pal_box((12.4, 3.2, 1.2), (13, 4.6, 3), "chrome"),
        D.pal_box((3, 3.2, 1.2), (13, 4.6, 1.6), "chrome"),
    ]
    for (x, z) in ((4.5, 2), (6.8, 2.2), (9.5, 1.9), (11.2, 2.3)):
        lower.append(D.pal_box((x, 3.2, z - 0.4), (x + 1.2, 3.6, z + 0.4), "gold"))
    lower += [
        # the button deck, sloping towards the player, and its buttons
        D.box((1, 12, 0.5), (15, 13.5, 8), {"up": "buttons", "north": "gold_trim", "east": side, "west": side, "down": side, "south": side}),
        D.box((1, 11.4, 0.5), (15, 12, 8), "gold_trim"),
        D.box((1.5, 13.5, 3), (14.5, 16, 14.5), {"north": side, "south": side, "east": side, "west": side}),
    ]
    for (x, colour) in ((2, "yellow"), (4, "blue"), (6, "green"), (8, "white")):
        lower.append(D.pal_box((x, 13.5, 3.5), (x + 1.4, 14.1, 4.9), colour))
    lower.append(D.pal_box((10.5, 13.5, 3), (13.5, 14.4, 5.5), "red"))  # SPIN
    lower.append(D.pal_box((10.8, 14.4, 3.3), (13.2, 14.6, 5.2), "flame_core"))
    lower.append(D.pal_box((2, 13.5, 6), (6, 13.8, 7.5), "screen"))  # the card reader
    for x0 in (1, 14.4):  # the light strips up the front corners
        lower.append(D.box((x0, 1.5, 2.4), (x0 + 0.6, 16, 3), {f: ("led", [x0 % 16, 0, x0 % 16 + 0.6, 14.5]) for f in D.FACES}))
    D.model("casino_slot_machine_lower", {"body": "slot_body", "side": "slot_side", "buttons": "slot_buttons", "gold_trim": "gold_trim",
                                          "black": "leather", "led": "led_strip", P: P}, lower, particle="side")
    upper = [
        D.box((1.5, 0, 3), (14.5, 10, 14.5), {"north": side, "south": side, "east": side, "west": side}),
        D.box((2, 0.5, 2.5), (14, 9.5, 3), {"north": ("screen", [2, 3.5, 14, 12.5])}),
        D.pal_box((1, 0, 2), (15, 0.8, 3), "chrome"),
        D.pal_box((1, 9.5, 2), (15, 10.3, 3), "chrome"),
        D.pal_box((1, 0.8, 2), (2, 9.5, 3), "chrome"),
        D.pal_box((14, 0.8, 2), (15, 9.5, 3), "chrome"),
        D.box((2.6, 0.8, 2.2), (13.4, 9.2, 2.4), {"north": ("screen", [0, 0, 16, 16])}),  # the reels, a hair behind the glass line
    ]
    upper.pop()  # (the screen face above already shows them; kept out so the glass does not z-fight)
    upper += [
        # the topper: an arch of three steps, lit front, gold edges
        D.box((1, 10.3, 2.5), (15, 13.5, 14.5), {"north": ("marquee", [1, 0, 15, 3.2]), "south": side, "east": side, "west": side}),
        D.box((2.5, 13.5, 2.5), (13.5, 15, 14.5), {"north": ("marquee", [2.5, 3.2, 13.5, 4.7]), "south": side, "east": side, "west": side}),
        D.box((4.5, 15, 2.5), (11.5, 16, 14.5), {"north": ("marquee", [4.5, 4.7, 11.5, 5.7]), "south": side, "east": side, "west": side,
                                                 "up": side}),
        D.pal_box((0.6, 10.1, 2.2), (15.4, 10.5, 14.8), "gold"),
        D.pal_box((2.1, 13.4, 2.2), (13.9, 13.7, 14.8), "gold"),
        D.pal_box((4.1, 14.9, 2.2), (11.9, 15.2, 14.8), "gold"),
        # the candle lamp on top: a chrome foot, a red and a white lens
        D.pal_box((6.8, 16, 7.8), (9.2, 16.6, 10.2), "chrome"),
        D.pal_box((7.2, 16.6, 8.2), (8.8, 18.2, 9.8), "red"),
        D.pal_box((7.2, 18.2, 8.2), (8.8, 19.4, 9.8), "flame_core"),
        D.pal_box((7.5, 19.4, 8.5), (8.5, 19.8, 9.5), "chrome"),
        # the lever
        D.pal_box((14.5, 2, 7), (16, 4, 10), "chrome"),
        D.pal_box((15, 4, 8), (16, 12, 9), "chrome"),
        D.pal_box((14.6, 12, 7.1), (16.4, 13.8, 9.9), "red"),
    ]
    for x0 in (1, 14.4):
        upper.append(D.box((x0, 0, 2.4), (x0 + 0.6, 10.3, 3), {f: ("led", [x0 % 16, 4, x0 % 16 + 0.6, 14.3]) for f in D.FACES}))
    D.model("casino_slot_machine_upper", {"side": "slot_side", "screen": "slot_screen", "marquee": "slot_marquee", "gold_trim": "gold_trim",
                                          "led": "led_strip", P: P}, upper, particle="side", ao=False)


# ---------------------------------------------------------------- tables

def felt_print():
    """The printing on the twenty-one felt, 32x32 over the curve: two gold arcs, «21» between them, seven betting circles."""
    img = D.new(32, 32)
    px = img.load()
    gold = D.rgba((226, 200, 120))
    for a in range(0, 181, 2):  # arcs centred on the dealer (top middle)
        t = math.radians(a)
        for rad in (11, 15):
            x, y = 16 + rad * math.cos(t), 2 + rad * math.sin(t)
            if 0 <= int(x) < 32 and 0 <= int(y) < 32:
                px[int(x), int(y)] = gold
    two = ["##.", "..#", ".#.", "#..", "###"]
    one = [".#", "##", ".#", ".#", ".#"]
    for gy in range(5):
        for gx, c in enumerate(two[gy]):
            if c == "#":
                px[13 + gx, 6 + gy] = gold
        for gx, c in enumerate(one[gy]):
            if c == "#":
                px[17 + gx, 6 + gy] = gold
    for k in range(7):  # the betting circles on the outer arc
        t = math.radians(15 + k * 25)
        cx, cy = 16 + 22 * math.cos(t), 2 + 22 * math.sin(t)
        for a in range(0, 360, 30):
            x, y = int(round(cx + 2 * math.cos(math.radians(a)))), int(round(cy + 2 * math.sin(math.radians(a))))
            if 0 <= x < 32 and 0 <= y < 32:
                px[x, y] = D.rgba((240, 236, 220))
    return D.tex("bj_print", img)


def chip_roll(x, z, y, colour, length=4.0, w=1.5):
    """Chips lying in a rack slot: a roll along z, the chip edges as stripes across it."""
    side = "chip_" + colour + "_side"
    top = "chip_" + colour + "_top"
    faces = {"up": (side, [0, 0, w, length], 90), "east": (side, [0, 0, length, w]), "west": (side, [0, 0, length, w]),
             "north": (top, [0, 0, w, w]), "south": (top, [0, 0, w, w])}
    return D.box((x, y, z), (x + w, y + w, z + length), faces)


def blackjack_table():
    """The table for 21: three blocks wide, two deep, a half-moon. Printed felt, a tufted rail with a gold band, the dealer's ledge
    with a chip rack of seven colours, a card shoe and a discard holder, a limit placard, cards dealt and bets on the circles."""
    back = 14
    bands = D.ellipse_bands(8, back, 24, 28)
    els = []
    for (x0, x1, z0, z1) in bands:
        els.append(D.box((x0, 12, z0), (x1, 14, z1), {"up": "felt", "down": "walnut", "north": "mahogany", "south": "mahogany",
                                                         "east": "mahogany", "west": "mahogany"}))
    els.append(D.box((-8, 14.01, -14), (24, 14.03, 14), {"up": ("print", [0, 0, 16, 14])}, faces=("up",)))
    els += D.rail_for(bands, 14, 15.6, "leather")
    els += D.rail_for(bands, 11, 12, "gold_trim")
    els += D.rail_for(bands, 15.6, 15.9, "gold_trim", width=0.6)  # a gold piping on top of the rail
    els.append(D.box((-16, 12, back), (32, 14.5, 16), {"up": "mahogany", "north": "mahogany", "south": "mahogany", "east": "mahogany",
                                                         "west": "mahogany", "down": "walnut"}))
    # the chip rack: a chrome-edged tray sunk in the ledge, a roll of chips in each slot
    els.append(D.pal_box((0.5, 14, 9.5), (15.5, 14.4, 14.5), "chrome"))
    els.append(D.box((1, 14.4, 10), (15, 14.6, 14), "walnut"))
    for i, c in enumerate(["red", "black", "blue", "green", "purple", "red", "black"]):
        els.append(chip_roll(1.3 + i * 2, 10, 14.4, c))
    for x in range(8):
        els.append(D.pal_box((1 + x * 2 - 0.2, 14.4, 10), (1 + x * 2 + 0.1, 15.4, 14), "chrome"))
    # the card shoe (dealer's left) and the discard holder (right)
    els.append(D.box((21, 14, 8), (26, 16.5, 13), "black_box"))
    els.append(D.box((21.5, 14, 7), (25.5, 15.5, 8), "black_box"))
    els.append(D.box((22, 15.5, 7.6), (25, 16.3, 8.2), "card_back", rotation={"origin": [23.5, 16, 8], "axis": "x", "angle": -22.5}))
    els.append(D.box((21, 16.5, 8), (26, 16.8, 13), "gold_trim"))
    els.append(D.pal_box((-10, 14, 9), (-6, 17.5, 13), "glass_clear"))
    els.append(D.box((-9.6, 14, 9.4), (-6.4, 16.4, 12.6), "card_back"))
    # the limit placard on the ledge: a small gold-framed board
    els.append(D.pal_box((-3, 14.5, 14.5), (2, 17.5, 15), "black"))
    els.append(D.pal_box((-3.2, 17.5, 14.4), (2.2, 17.8, 15.1), "gold"))
    els.append(D.pal_box((-2.2, 15.5, 14.4), (1.2, 16.6, 14.5), "red", faces=("north",)))
    # hands dealt and bets on the circles
    for (x, z, rot) in ((-10, -3, 45), (-3, -9, 22.5), (6, -11.5, 0), (15, -9, -22.5), (22, -3, -45)):
        els.append(D.card(x, z, 14.05, True, rot))
        els.append(D.card(x + 1.2, z + 0.6, 14.2, True, rot))
    for (x, z, c, h) in ((-9, -7, "red", 2), (-1, -13, "black", 3), (7.5, -15, "blue", 2), (16, -13, "green", 4), (22.5, -7, "red", 1)):
        els += D.chip_stack(x, z, h, c, 14.04, 3)
    els.append(D.card(6.5, 4, 14.05, True, 0))
    els.append(D.card(9.8, 4, 14.05, False, 0))
    for (x, z) in ((-6, 2), (8, -4), (22, 2)):
        els += D.pedestal(x, z)
    D.model("casino_blackjack_table", {"felt": "felt", "mahogany": "mahogany", "walnut": "walnut", "leather": "leather",
                                       "gold_trim": "gold_trim", "black_box": "leather", "card_back": "card_back", "card_face": "card_face",
                                       "print": "bj_print", "palette": "palette", **D.chip_tex()}, els, particle="felt")


def poker_table():
    """The card table: a blue baize oval, a wooden racetrack inside a tufted rail, two cards and chips at each of six places,
    five cards up in the middle, the pot and the dealer button."""
    back = 0
    bands = D.ellipse_bands(8, back, 24, 15, both=True)
    els = []
    for (x0, x1, z0, z1) in bands:
        els.append(D.box((x0, 12, z0), (x1, 14, z1), {"up": "felt", "down": "walnut", "north": "mahogany", "south": "mahogany",
                                                         "east": "mahogany", "west": "mahogany"}))
    els += D.rail_for(bands, 14, 15.6, "leather", both=True)
    els += D.rail_for([(x0 + 1.5 if i == 0 else x0, x1 - 1.5 if i == len(bands) - 1 else x1, z0 + 2, z1 - 2)
                       for i, (x0, x1, z0, z1) in enumerate(bands)], 14, 14.4, "walnut", width=1.5, both=True)  # the racetrack
    els += D.rail_for(bands, 11, 12, "gold_trim", both=True)
    for k in range(5):  # the board: five cards up
        els.append(D.card(1.5 + k * 3.2, -2, 14.05, True, 0))
    for (x, z, rot) in ((-12, -6, 22.5), (-12, 3, -22.5), (5, -11, 0), (5, 8, 0), (22, -6, -22.5), (22, 3, 22.5)):
        els.append(D.card(x, z, 14.05, False, rot))
        els.append(D.card(x + 1.4, z + 0.4, 14.2, False, rot))
    for (x, z, c, h) in ((-9, -9, "black", 3), (-9, 6, "red", 2), (10, -11, "blue", 4), (10, 9, "green", 2), (20, -9, "purple", 3),
                         (20, 6, "black", 2), (6, 3, "red", 3), (9.5, 3.5, "blue", 2)):
        els += D.chip_stack(x, z, h, c, 14.04, 3)
    els.append(D.pal_box((13, 14.05, 3), (15, 14.6, 5), "white"))  # the dealer button
    for (x, z) in ((-5, 0), (8, 0), (21, 0)):
        els += D.pedestal(x, z)
    D.model("casino_poker_table", {"felt": "felt_blue", "mahogany": "mahogany", "walnut": "walnut", "leather": "leather",
                                   "gold_trim": "gold_trim", "card_back": "card_back", "card_face": "card_face", "palette": "palette",
                                   **D.chip_tex()}, els, particle="felt")


def roulette_table():
    """Roulette: three blocks long, one deep. The wheel in a two-tier wooden bowl with a chrome turret of four arms and the ball on
    the track; the printed layout; chip stacks on bets, the dolly; the croupier's rack of colour chips at the far end."""
    els = []
    els.append(D.box((-16, 12, 1), (32, 14, 15), {"up": "felt", "down": "walnut", "north": "mahogany", "south": "mahogany",
                                                   "east": "mahogany", "west": "mahogany"}))
    els.append(D.box((0, 14.01, 2), (32, 14.03, 14), {"up": ("layout", [0, 0, 16, 16])}, faces=("up",)))
    els.append(D.box((-16, 14, 0), (32, 15.6, 1.8), "leather"))
    els.append(D.box((-16, 14, 14.2), (32, 15.6, 16), "leather"))
    els.append(D.box((30.2, 14, 1.8), (32, 15.6, 14.2), "leather"))
    els.append(D.box((-16, 14, 1.8), (-14.2, 15.6, 14.2), "leather"))
    els.append(D.box((-16, 11, 0), (32, 12, 16), "gold_trim", faces=("north", "south", "east", "west", "down")))
    # the wheel: a wide wooden bowl, a gold ring, the head with its pockets, the turret
    els += D.octagon(-8, 8, 7.5, 14, 15.6, "mahogany")
    els += D.octagon(-8, 8, 6.6, 15.6, 16.6, "walnut")
    els += D.octagon(-8, 8, 6.9, 15.5, 15.7, "gold_trim")
    els.append(D.box((-14, 16.61, 2), (-2, 16.63, 14), {"up": "wheel"}, faces=("up",)))
    els.append(D.pal_box((-9, 16.6, 7), (-7, 18.2, 9), "chrome"))
    els.append(D.pal_box((-8.4, 18.2, 7.6), (-7.6, 19.2, 8.4), "chrome"))
    for (f, t) in (((-11.5, 17.6, 7.6), (-4.5, 18.2, 8.4)), ((-8.4, 17.6, 4.5), (-7.6, 18.2, 11.5))):
        els.append(D.pal_box(f, t, "chrome"))
    for (x, z) in ((-11.8, 8), (-4.2, 8), (-8, 4.2), (-8, 11.8)):
        els.append(D.pal_box((x - 0.5, 17.5, z - 0.5), (x + 0.5, 18.5, z + 0.5), "gold"))
    els.append(D.pal_box((-3.6, 16.6, 4.6), (-2.8, 17.4, 5.4), "white"))  # the ball
    # bets and the dolly
    els.append(D.pal_box((14, 14.03, 6), (15, 15.8, 7), "gold"))
    els.append(D.pal_box((13.7, 15.8, 5.7), (15.3, 16.4, 7.3), "crystal"))
    for (x, z, c, h) in ((5, 4.5, "red", 2), (18, 9, "black", 3), (25, 4, "purple", 1), (9, 10, "blue", 2), (21.5, 3, "green", 4)):
        els += D.chip_stack(x, z, h, c, 14.04, 2)
    # the croupier's colour chips at the far end
    els.append(D.pal_box((26, 14, 9), (30, 14.4, 14), "chrome"))
    for i, c in enumerate(["red", "blue", "green"]):
        els.append(chip_roll(26.3 + i * 1.2, 9.5, 14.4, c, 4, 1.1))
    for (x, z) in ((-8, 8), (8, 8), (24, 8)):
        els += D.pedestal(x, z)
    D.model("casino_roulette_table", {"felt": "felt", "layout": "roulette_layout", "wheel": "roulette_wheel", "mahogany": "mahogany",
                                      "walnut": "walnut", "leather": "leather", "gold_trim": "gold_trim", "palette": "palette",
                                      **D.chip_tex()}, els, particle="felt")


def chair():
    """The players' chair: gold cabriole legs, a deep red velvet seat with gold piping, a tufted rounded back with a gold frame
    and a crest, gold armrests. Faces north."""
    P = "palette"
    els = []
    for (x, z) in ((3, 3), (12, 3), (3, 12), (12, 12)):
        els.append(D.pal_box((x, 1.5, z), (x + 1, 7, z + 1), "gold"))
        els.append(D.pal_box((x - 0.3, 0, z - 0.3), (x + 1.3, 1.5, z + 1.3), "gold_dark"))
    els.append(D.box((2.5, 7, 2.5), (13.5, 9.5, 13.5), {"up": "velvet", "north": "velvet", "south": "velvet", "east": "velvet",
                                                        "west": "velvet", "down": "walnut"}))
    els.append(D.pal_box((2.3, 6.8, 2.3), (13.7, 7.2, 13.7), "gold"))
    els.append(D.box((3, 9.5, 12.5), (13, 19, 14.5), {"north": "velvet", "south": "walnut", "east": "walnut", "west": "walnut", "up": "walnut"}))
    els.append(D.box((2.5, 19, 12.2), (13.5, 20, 14.8), "gold_trim"))
    els.append(D.box((5, 20, 12.6), (11, 21, 14.4), "gold_trim"))  # the crest
    els.append(D.pal_box((7.3, 21, 13), (8.7, 22, 14), "gold"))
    els.append(D.box((2.5, 9.5, 12.2), (3.5, 19, 14.8), "gold_trim"))
    els.append(D.box((12.5, 9.5, 12.2), (13.5, 19, 14.8), "gold_trim"))
    for x in (2, 13):  # armrests
        els.append(D.pal_box((x, 12, 5), (x + 1, 12.8, 12.5), "gold"))
        els.append(D.pal_box((x, 9.5, 5), (x + 1, 12, 5.8), "gold"))
    D.model("casino_chair", {"velvet": "velvet", "walnut": "walnut", "gold_trim": "gold_trim", P: P}, els, particle="velvet")


# ---------------------------------------------------------------- resort decor

def carpet(name, base, line, accent, dark):
    """A patterned casino carpet that tiles: a gold lattice of diamonds, a medallion in each, flourishes between."""
    img = D.new()
    r = D.rng(name)
    D.noise_fill(img, base, 0.05, r)
    px = img.load()
    for y in range(16):
        for x in range(16):
            u, v = (x + y) % 16, (x - y) % 16
            if u in (0, 1) or v in (0, 1):
                px[x, y] = D.rgba(line) if (u + v) % 3 else D.shade(line, 0.8)
    for (cx, cy) in ((8, 0), (0, 8)):  # medallions in the diamonds
        for dx in range(-2, 3):
            for dy in range(-2, 3):
                x, y = (cx + dx) % 16, (cy + dy) % 16
                d = abs(dx) + abs(dy)
                if d == 2:
                    px[x, y] = D.rgba(accent)
                elif d == 0:
                    px[x, y] = D.rgba(line)
                elif d == 1:
                    px[x, y] = D.rgba(dark)
    for (x, y) in ((4, 4), (12, 12), (4, 12), (12, 4)):  # little stars where the lines cross
        for (dx, dy) in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            px[(x + dx) % 16, (y + dy) % 16] = D.rgba(accent)
    return D.tex(name, img)


def damask():
    """Wallpaper: deep red damask, a gold fleur repeating on a diamond grid, a fine stripe."""
    img = D.new()
    r = D.rng("damask")
    base = (110, 16, 26)
    D.noise_fill(img, base, 0.04, r)
    px = img.load()
    fleur = ["..#..", ".###.", "#.#.#", "..#..", ".#.#.", "#...#"]
    for (ox, oy) in ((1, 1), (9, 9)):
        for gy, row in enumerate(fleur):
            for gx, c in enumerate(row):
                if c == "#":
                    px[(ox + gx) % 16, (oy + gy) % 16] = D.rgba((196, 140, 56)) if gy < 3 else D.rgba((150, 96, 36))
    for y in range(16):
        px[0, y] = D.shade(base, 0.8)
        px[8, y] = D.shade(base, 0.9)
    return D.tex("damask", img)


def wainscot():
    img = D.wood("wainscot", D.MAHOGANY)
    px = img.load()
    D.rect(img, (2, 3, 14, 13), D.shade(D.MAHOGANY, 1.1))
    D.bevel(img, (2, 3, 14, 13), 1.25, 0.7)
    for x in range(16):
        px[x, 0] = D.rgba(D.GOLD)
        px[x, 1] = D.rgba(D.GOLD_DARK)
    return img


def column_textures():
    """The fluted column: ivory shaft with gold flutes; a gold base with a torus; a gold capital with leaves and volutes."""
    ivory = (236, 228, 208)
    img = D.new()
    px = img.load()
    for y in range(16):
        for x in range(16):
            f = x % 4
            px[x, y] = D.shade(ivory, (1.08, 0.95, 0.82, 0.95)[f])
            if f == 2:
                px[x, y] = D.shade(D.GOLD, 0.92 + 0.08 * ((y % 4) == 0))
    D.tex("column_shaft", img)
    base = D.new()
    D.noise_fill(base, D.GOLD, 0.04, D.rng("column_base"))
    bp = base.load()
    for x in range(16):
        for (y, k) in ((0, 1.3), (1, 1.1), (6, 0.75), (7, 1.25), (8, 1.1), (14, 0.8), (15, 0.7)):
            bp[x, y] = D.shade(D.GOLD, k)
        for y in range(9, 14):
            bp[x, y] = D.shade(ivory, 0.95)
    D.tex("column_base", base)
    cap = D.new()
    D.noise_fill(cap, D.GOLD, 0.04, D.rng("column_cap"))
    cp = cap.load()
    for x in range(16):
        cp[x, 15] = D.shade(D.GOLD, 0.7)
        cp[x, 0] = D.shade(D.GOLD, 1.3)
        cp[x, 1] = D.shade(D.GOLD, 1.1)
    for x0 in (1, 9):  # leaves
        for (dx, dy) in ((2, 5), (1, 6), (3, 6), (0, 7), (2, 7), (4, 7), (1, 8), (3, 8), (2, 9), (2, 10), (2, 11), (2, 12)):
            cp[x0 + dx, dy] = D.shade(D.GOLD, 0.72)
        for (dx, dy) in ((1, 5), (2, 6), (1, 7)):
            cp[x0 + dx, dy] = D.shade(D.GOLD, 1.25)
    for (x, y) in ((0, 2), (1, 2), (1, 3), (0, 3), (15, 2), (14, 2), (14, 3), (15, 3)):  # volutes
        cp[x, y] = D.shade(D.GOLD, 0.65)
    D.tex("column_cap", cap)


def coffer():
    """A ceiling coffer: a gold-framed square with a warm light in it, a ring of tiny bulbs (they twinkle in turn)."""
    frames = []
    for phase in range(3):
        img = D.new()
        D.noise_fill(img, (236, 228, 208), 0.03, D.rng("coffer"))
        px = img.load()
        D.rect(img, (2, 2, 14, 14), D.GOLD)
        D.bevel(img, (2, 2, 14, 14), 1.25, 0.7)
        D.rect(img, (4, 4, 12, 12), (255, 236, 180))
        D.rect(img, (6, 6, 10, 10), (255, 252, 236))
        ring = [(x, 3) for x in range(3, 13, 2)] + [(12, y) for y in range(5, 13, 2)] + [(x, 12) for x in range(11, 2, -2)] + \
               [(3, y) for y in range(11, 4, -2)]
        for i, (x, y) in enumerate(ring):
            px[x, y] = D.rgba((255, 255, 255)) if (i + phase) % 3 == 0 else D.rgba((255, 220, 140))
        frames.append(img)
    strip("coffer", frames, 6)


TINY = {  # 3x5 letters for small print
    "Д": [".###.", ".#.#.", ".#.#.", "#####", "#...#"], "Ж": ["#.#.#", "#.#.#", ".###.", "#.#.#", "#.#.#"], "Е": ["###", "#..", "##.", "#..", "###"],
    "К": ["#.#", "##.", "#..", "##.", "#.#"], "П": ["###", "#.#", "#.#", "#.#", "#.#"], "О": ["###", "#.#", "#.#", "#.#", "###"],
    "Т": ["###", ".#.", ".#.", ".#.", ".#."],
}
SEG = {  # seven-segment digits 3x5
    "0": ["###", "#.#", "#.#", "#.#", "###"], "1": [".#.", "##.", ".#.", ".#.", "###"], "2": ["###", "..#", "###", "#..", "###"],
    "3": ["###", "..#", "###", "..#", "###"], "4": ["#.#", "#.#", "###", "..#", "..#"], "5": ["###", "#..", "###", "..#", "###"],
    "6": ["###", "#..", "###", "#.#", "###"], "7": ["###", "..#", ".#.", ".#.", ".#."], "8": ["###", "#.#", "###", "#.#", "###"],
    "9": ["###", "#.#", "###", "..#", "###"],
}


def jackpot():
    """The progressive jackpot board, three blocks wide (48x16): «ДЖЕКПОТ» in gold over a running sum in red LEDs that counts up,
    bulbs round the frame."""
    frames = []
    for f, sum_ in enumerate(("1 777 777", "1 777 778", "1 777 779", "1 777 780")):
        img = D.new(48, 16)
        D.noise_fill(img, (20, 12, 18), 0.06, D.rng("jackpot"))
        px = img.load()
        for x in range(48):
            px[x, 0] = D.rgba(D.GOLD)
            px[x, 15] = D.rgba(D.GOLD_DARK)
            if x % 3 == 1:
                px[x, 0] = D.rgba((255, 250, 210) if (x // 3 + f) % 2 else (150, 90, 40))
                px[x, 15] = D.rgba((255, 236, 160) if (x // 3 + f + 1) % 2 else (130, 80, 36))
        for y in range(16):
            px[0, y] = D.rgba(D.GOLD)
            px[47, y] = D.rgba(D.GOLD_DARK)
        x = 8
        for ch in "ДЖЕКПОТ":
            for gy, row in enumerate(TINY[ch]):
                for gx, c in enumerate(row):
                    if c == "#":
                        px[x + gx, 2 + gy] = D.rgba((255, 214, 80) if gy else (255, 246, 190))
            x += len(TINY[ch][0]) + 1
        x = 7
        for ch in sum_:
            if ch == " ":
                x += 2
                continue
            for gy, row in enumerate(SEG[ch]):
                for gx, c in enumerate(row):
                    if c == "#":
                        px[x + gx, 8 + gy] = D.rgba((255, 64, 40))
                    else:
                        px[x + gx, 8 + gy] = D.rgba((60, 12, 14))
            x += 4
        frames.append(img)
    for part in range(3):
        strip(f"jackpot_{part}", [fr.crop((16 * part, 0, 16 * part + 16, 16)) for fr in frames], 20)


def palm_leaf():
    """A palm frond seen from above along its length (x): a midrib and leaflets that thin out to the tip, transparent between."""
    img = D.new()
    px = img.load()
    for x in range(16):
        width = max(1, int(7 * (1 - x / 18)))
        for dz in range(-width, width + 1):
            y = 8 + dz
            if (x + abs(dz)) % 2 == 0 or abs(dz) <= 1:
                px[x, y] = D.rgba((58, 140, 52) if abs(dz) > 1 else (110, 160, 60))
                if abs(dz) == width:
                    px[x, y] = D.rgba((40, 100, 36))
        px[x, 8] = D.rgba((150, 170, 80))
    return D.tex("palm_leaf", img)


def die_faces():
    pips = {1: [(7, 7)], 2: [(3, 3), (11, 11)], 3: [(3, 3), (7, 7), (11, 11)], 4: [(3, 3), (11, 3), (3, 11), (11, 11)],
            5: [(3, 3), (11, 3), (7, 7), (3, 11), (11, 11)], 6: [(3, 3), (11, 3), (3, 7), (11, 7), (3, 11), (11, 11)]}
    for n, spots in pips.items():
        img = D.new()
        D.noise_fill(img, (200, 24, 34), 0.04, D.rng("die"))
        px = img.load()
        for i in range(16):  # rounded edge highlight
            px[i, 0] = D.shade((200, 24, 34), 1.3)
            px[0, i] = D.shade((200, 24, 34), 1.2)
            px[i, 15] = D.shade((200, 24, 34), 0.7)
            px[15, i] = D.shade((200, 24, 34), 0.75)
        for (x, y) in spots:
            D.rect(img, (x - 1, y - 1, x + 2, y + 2), (246, 242, 232))
            px[x + 1, y + 1] = D.rgba((190, 184, 172))
            px[x - 1, y - 1] = D.rgba((255, 255, 255))
        D.tex(f"die_{n}", img)


def textures():
    led_strip()
    felt_print()
    carpet("carpet_red", (138, 18, 30), (214, 168, 60), (250, 220, 120), (60, 10, 20))
    carpet("carpet_blue", (24, 40, 110), (200, 160, 70), (240, 210, 110), (12, 18, 50))
    carpet("carpet_purple", (70, 22, 96), (210, 170, 70), (250, 220, 130), (34, 10, 48))
    damask()
    wainscot()
    column_textures()
    coffer()
    jackpot()
    palm_leaf()
    die_faces()
    fountain_textures()


# ---------------------------------------------------------------- fountain jet

def fountain_textures():
    """The jet's water (falling streaks with white crests, half see-through), the spray at its top and the foam ring at its foot."""
    frames = []
    for k in range(8):
        img = D.new(16, 16)
        px = img.load()
        for x in range(16):
            for y in range(16):
                yy = (y - 2 * k) % 16
                streak = (x * 7 + yy // 4 * 3) % 5
                c = (150, 210, 245) if streak else (210, 240, 255)
                if (yy + x * 3) % 8 == 0:
                    c = (255, 255, 255)
                px[x, y] = c + (200 if streak else 230,)
        frames.append(img)
    strip("fountain_water", frames, 2)
    frames = []
    for k in range(4):
        img = D.new(16, 16)
        r = D.rng("spray" + str(k))
        px = img.load()
        for _ in range(70):
            x, y = r.randrange(16), r.randrange(16)
            d = math.hypot(x - 7.5, y - 7.5)
            if d < 7.5:
                px[x, y] = (255, 255, 255, 240) if r.random() < 0.5 else (180, 225, 250, 200)
        frames.append(img)
    strip("fountain_spray", frames, 3)
    frames = []
    for k in range(4):
        img = D.new(16, 16)
        px = img.load()
        for x in range(16):
            for y in range(16):
                d = math.hypot(x - 7.5, y - 7.5)
                if 2.5 + k * 0.6 < d < 4.5 + k * 1.1 and d < 8:
                    a = 220 - k * 40
                    px[x, y] = ((255, 255, 255) if (x + y + k) % 3 else (190, 230, 250)) + (max(60, a),)
        frames.append(img)
    strip("fountain_foam", frames, 4)


def fountain_jet():
    """A jet of water two blocks tall: a thin bright core in a thicker see-through sleeve, a spray blooming at the top and a ring
    of foam where it falls back. Stands in a pool (the block is waterloggable)."""
    els = [D.box((6.5, 0, 6.5), (9.5, 31, 9.5), "water"),
           D.box((5, 1, 5), (11, 27, 11), "water", faces=("north", "south", "east", "west")),
           D.box((3.5, 26, 3.5), (12.5, 31.5, 12.5), "spray"),
           D.box((0, 29, 8), (16, 32, 8), "spray", faces=("north", "south"), rotation={"origin": [8, 29, 8], "axis": "y", "angle": 45}),
           D.box((0, 29, 8), (16, 32, 8), "spray", faces=("north", "south"), rotation={"origin": [8, 29, 8], "axis": "y", "angle": -45}),
           D.box((0, 0.3, 0), (16, 0.3, 16), "foam", faces=("up", "down"))]
    D.model("casino_fountain_jet", {"water": "fountain_water", "spray": "fountain_spray", "foam": "fountain_foam"}, els,
            particle="water", ao=False)


def palm_trunk():
    """A piece of a palm's trunk: a slim ringed column of bark, to stack under the crown."""
    els = [D.box((5, 0, 5), (11, 16, 11), "trunk"), D.box((4.5, 0, 4.5), (11.5, 2, 11.5), "trunk"),
           D.box((4.5, 8, 4.5), (11.5, 10, 11.5), "trunk")]
    D.model("casino_palm_trunk", {"trunk": "palette"}, els, particle="trunk")
    D.MODELS["casino_palm_trunk"]["textures"]["trunk"] = "minecraft:block/stripped_jungle_log"
    D.MODELS["casino_palm_trunk"]["textures"]["particle"] = "minecraft:block/stripped_jungle_log"


def palm_crown():
    """The crown of a palm, set on top of a trunk of logs: a knob of old frond bases, eight fronds (four drooping, four level
    between them, as one model turns only on one axis) and coconuts."""
    els = [D.box((5, 0, 5), (11, 4, 11), "trunk"), D.box((6, 4, 6), (10, 6, 10), "trunk")]
    for (cx, cz) in ((6.5, 6.5), (9.5, 6.5), (8, 9.5)):
        els.append(D.pal_box((cx - 1.2, 1, cz - 1.2), (cx + 1.2, 3.4, cz + 1.2), "walnut"))
    leaf = {"up": ("leaf", [0, 0, 16, 16]), "down": ("leaf", [0, 0, 16, 16])}
    # drooping fronds along the four sides: long thin plates tilted down 22.5 degrees from the crown
    els.append(D.box((8, 5, 0), (30, 5.1, 16), leaf, faces=("up", "down"), rotation={"origin": [8, 5, 8], "axis": "z", "angle": -22.5}))
    els.append(D.box((-14, 5, 0), (8, 5.1, 16), {"up": ("leaf", [16, 0, 0, 16]), "down": ("leaf", [16, 0, 0, 16])}, faces=("up", "down"),
                     rotation={"origin": [8, 5, 8], "axis": "z", "angle": 22.5}))
    els.append(D.box((0, 5, 8), (16, 5.1, 30), {"up": ("leaf", [0, 0, 16, 16], 90), "down": ("leaf", [0, 0, 16, 16], 90)},
                     faces=("up", "down"), rotation={"origin": [8, 5, 8], "axis": "x", "angle": 22.5}))
    els.append(D.box((0, 5, -14), (16, 5.1, 8), {"up": ("leaf", [0, 0, 16, 16], 270), "down": ("leaf", [0, 0, 16, 16], 270)},
                     faces=("up", "down"), rotation={"origin": [8, 5, 8], "axis": "x", "angle": -22.5}))
    # level fronds on the diagonals, a little higher
    for (f, t, rot) in (((8, 6.5, 0), (28, 6.6, 16), 45), ((-12, 6.5, 0), (8, 6.6, 16), 45)):
        uv = [0, 0, 16, 16] if f[0] == 8 else [16, 0, 0, 16]
        els.append(D.box(f, t, {"up": ("leaf", uv), "down": ("leaf", uv)}, faces=("up", "down"),
                         rotation={"origin": [8, 6.5, 8], "axis": "y", "angle": rot}))
        els.append(D.box(f, t, {"up": ("leaf", uv), "down": ("leaf", uv)}, faces=("up", "down"),
                         rotation={"origin": [8, 6.5, 8], "axis": "y", "angle": -rot}))
    D.model("casino_palm", {"trunk": "trunk", "leaf": "palm_leaf", "palette": "palette"}, els, particle="leaf", ao=False)
    D.MODELS["casino_palm"]["textures"]["trunk"] = "minecraft:block/stripped_jungle_log"


def models():
    slot_machine()
    blackjack_table()
    poker_table()
    roulette_table()
    chair()
    for c in ("red", "blue", "purple"):
        D.MODELS[f"casino_carpet_{c}"] = {"parent": "minecraft:block/carpet", "textures": {"wool": D.NS + f"carpet_{c}"}}
    D.MODELS["casino_wallpaper"] = {"parent": "minecraft:block/cube_all", "textures": {"all": D.NS + "damask"}}
    D.MODELS["casino_wainscot"] = {"parent": "minecraft:block/cube_column", "textures": {"side": D.NS + "wainscot", "end": D.NS + "walnut"}}
    for part, t in (("base", "column_base"), ("shaft", "column_shaft"), ("capital", "column_cap")):
        D.MODELS[f"casino_column_{part}"] = {"parent": "minecraft:block/cube_column",
                                             "textures": {"side": D.NS + t, "end": D.NS + ("column_shaft" if part == "shaft" else "gold_trim")}}
    D.MODELS["casino_coffer"] = {"parent": "minecraft:block/cube_bottom_top",
                                 "textures": {"bottom": D.NS + "coffer", "top": D.NS + "walnut", "side": D.NS + "gold_trim"}}
    for part in range(3):
        face = {"north": f"j{part}", "south": "walnut", "east": "gold_trim", "west": "gold_trim", "up": "gold_trim", "down": "gold_trim"}
        D.model(f"casino_jackpot_{part}", {f"j{part}": f"jackpot_{part}", "walnut": "walnut", "gold_trim": "gold_trim"},
                [D.box((0, 0, 13), (16, 16, 16), face)], particle="gold_trim", ao=False)
    D.MODELS["casino_die"] = {"parent": "minecraft:block/cube", "textures": {
        "particle": D.NS + "die_1", "north": D.NS + "die_1", "south": D.NS + "die_6", "east": D.NS + "die_3", "west": D.NS + "die_4",
        "up": D.NS + "die_5", "down": D.NS + "die_2"}}
    palm_crown()
    palm_trunk()
    fountain_jet()


NAMES = {
    "casino_carpet_red": ("Ковёр казино, красный", "Casino carpet, red"),
    "casino_carpet_blue": ("Ковёр казино, синий", "Casino carpet, blue"),
    "casino_carpet_purple": ("Ковёр казино, пурпурный", "Casino carpet, purple"),
    "casino_wallpaper": ("Обои с дамаском", "Damask wallpaper"),
    "casino_wainscot": ("Деревянная панель", "Wood wainscot"),
    "casino_column": ("Колонна с золотыми каннелюрами", "Fluted gold column"),
    "casino_coffer": ("Кессон с подсветкой", "Lit ceiling coffer"),
    "casino_jackpot": ("Табло джекпота", "Jackpot board"),
    "casino_palm": ("Крона пальмы", "Palm crown"),
    "casino_die": ("Огромный игральный кубик", "Giant die"),
    "casino_fountain_jet": ("Струя фонтана", "Fountain jet"),
    "casino_palm_trunk": ("Ствол пальмы", "Palm trunk"),
}


def save():
    W = D.write
    j = D.os.path.join
    for c in ("red", "blue", "purple"):
        n = f"casino_carpet_{c}"
        W(j(D.ASSETS, "blockstates", n + ".json"), {"variants": {"": {"model": D.NS + n}}})
        W(j(D.ASSETS, "models", "item", n + ".json"), {"parent": D.NS + n})
        D.loot(n)
    for n in ("casino_wallpaper", "casino_wainscot", "casino_coffer", "casino_die"):
        W(j(D.ASSETS, "blockstates", n + ".json"), {"variants": {"": {"model": D.NS + n}}})
        W(j(D.ASSETS, "models", "item", n + ".json"), {"parent": D.NS + n})
        D.loot(n)
    W(j(D.ASSETS, "blockstates", "casino_column.json"), {"variants": {f"part={p}": {"model": D.NS + f"casino_column_{p}"}
                                                                       for p in ("base", "shaft", "capital")}})
    W(j(D.ASSETS, "models", "item", "casino_column.json"), {"parent": D.NS + "casino_column_capital"})
    D.loot("casino_column")
    W(j(D.ASSETS, "blockstates", "casino_jackpot.json"), {"variants": {
        f"facing={f},part={p}": ({"model": D.NS + f"casino_jackpot_{p}", "y": y} if y else {"model": D.NS + f"casino_jackpot_{p}"})
        for p in range(3) for f, y in D.FACINGS}})
    W(j(D.ASSETS, "models", "item", "casino_jackpot.json"), {"parent": D.NS + "casino_jackpot_1", "display": D.DISPLAY})
    D.loot("casino_jackpot")
    W(j(D.ASSETS, "blockstates", "casino_palm.json"), {"variants": {"": {"model": D.NS + "casino_palm"}}})
    W(j(D.ASSETS, "models", "item", "casino_palm.json"), {"parent": D.NS + "casino_palm", "display": D.SMALL})
    D.loot("casino_palm")
    W(j(D.ASSETS, "blockstates", "casino_palm_trunk.json"), {"variants": {"": {"model": D.NS + "casino_palm_trunk"}}})
    W(j(D.ASSETS, "models", "item", "casino_palm_trunk.json"), {"parent": D.NS + "casino_palm_trunk"})
    D.loot("casino_palm_trunk")
    W(j(D.ASSETS, "blockstates", "casino_fountain_jet.json"), {"variants": {"": {"model": D.NS + "casino_fountain_jet"}}})
    W(j(D.ASSETS, "models", "item", "casino_fountain_jet.json"), {"parent": D.NS + "casino_fountain_jet", "display": D.SMALL})
    D.loot("casino_fountain_jet")
