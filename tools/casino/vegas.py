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
    return strip("slot_screen", frames, timed=timed)


def slot_marquee():
    """The topper's lit sign (its top half is used): «777» in gold on a deep blue field, a row of bulbs above and below that
    chase in two frames."""
    frames = []
    for phase in (0, 1):
        img = D.new()
        px = img.load()
        for y in range(16):
            for x in range(16):
                px[x, y] = D.rgba(D.shade((30, 40, 110), 1.2 - y * 0.05))
        for x in range(16):
            for y in (0, 7):
                lit = (x + phase + y) % 2 == 0
                px[x, y] = D.rgba((255, 244, 200) if lit else (170, 120, 50))
        sevens = ["###", "..#", ".#.", ".#."]
        for i in range(3):
            x0 = 2 + i * 4
            for gy, row in enumerate(sevens):
                for gx, c in enumerate(row):
                    if c == "#":
                        px[x0 + gx, 2 + gy] = D.rgba((255, 236, 150) if gy == 0 else D.GOLD)
        frames.append(img)
    return strip("slot_marquee", frames, 8)


def led_strip():
    """The cabinet's edge light: a soft warm glow (amber to gold) that runs slowly up it."""
    frames = []
    for f in range(8):
        img = D.new()
        px = img.load()
        for y in range(16):
            k = 0.5 + 0.5 * math.cos((y / 16.0 + f / 8.0) * 2 * math.pi)
            c = tuple(int(a + (b - a) * k) for a, b in zip((200, 90, 30), (255, 220, 120)))
            for x in range(16):
                px[x, y] = c + (255,)
        frames.append(img)
    return strip("led_strip", frames, 3)


def slot_body():
    """The belly glass under the button deck: the game's art lit from behind, a big gold «777» with a red edge on deep blue,
    soft rays round it."""
    img = D.new()
    px = img.load()
    for y in range(16):
        for x in range(16):
            ray = (math.atan2(y - 8, x - 7.5) * 8 / math.pi) % 2 < 1
            px[x, y] = D.rgba(D.shade((36, 48, 128) if ray else (30, 40, 110), 1.15 - abs(y - 8) * 0.03))
    big = ["####", "...#", "..#.", ".#..", ".#..", ".#.."]
    for i, x0 in enumerate((1, 6, 11)):
        for gy, row in enumerate(big):
            for gx, c in enumerate(row):
                if c == "#" and x0 + gx < 16:
                    px[x0 + gx, 5 + gy] = D.rgba((255, 236, 150) if gy == 0 else D.GOLD)
                    if 6 + gy < 16 and big[gy + 1 if gy + 1 < len(big) else gy][gx] != "#":
                        px[x0 + gx, 6 + gy] = D.rgba((150, 24, 30))
    for x in range(16):
        px[x, 0] = D.rgba(D.CHROME)
        px[x, 15] = D.shade(D.CHROME, 0.7)
    return D.tex("slot_body", img)


def slot_side():
    """The cabinet's sides: glossy black with a soft highlight and a thin chrome edge."""
    img = D.new()
    D.noise_fill(img, (30, 28, 34), 0.02, D.rng("slot_side"))
    px = img.load()
    for y in range(16):
        px[2, y] = D.rgba((54, 52, 60))
        px[3, y] = D.rgba((42, 40, 48))
        px[15, y] = D.shade(D.CHROME, 0.8)
    return D.tex("slot_side", img)


def slot_machine():
    """A Vegas video slot two blocks tall, glossy black. Lower block: a plinth with a chrome kick strip, the lit belly glass,
    the button deck sticking out towards the player, the bottom of the game screen. Upper block: the rest of the screen in a
    chrome bezel (the reels spin and stop one by one), the lit topper with «777» between chasing bulbs, a candle lamp on top.
    Warm light strips run up both front edges."""
    P = "palette"
    side = "side"
    cut = 16 * 8.5 / 13  # the screen is 13 pixels tall over the two blocks: where the texture splits between them
    lower = [
        D.box((1.5, 0, 2), (14.5, 1.5, 15), "side"),
        D.pal_box((1.5, 0.4, 1.8), (14.5, 1.0, 2.0), "chrome"),
        D.box((2, 1.5, 3), (14, 16, 15), {"north": side, "south": side, "east": side, "west": side}),
        D.box((2.6, 2, 2.6), (13.4, 9.4, 3), {"north": ("body", [0, 0, 16, 16])}),
        D.pal_box((2.2, 1.6, 2.5), (13.8, 2, 3), "chrome"),
        D.pal_box((2.2, 9.4, 2.5), (13.8, 9.8, 3), "chrome"),
        # the deck: a ledge with the buttons on it, a chrome nose, a gold lip under it
        D.box((1.5, 9.8, 0.8), (14.5, 11.2, 8), {"up": ("buttons", [0, 0, 16, 16]), "north": "chrome_t", "east": side, "west": side,
                                                 "down": side}),
        D.pal_box((1.5, 9.5, 0.8), (14.5, 9.8, 2.2), "gold"),
        # the bottom of the screen in its bezel
        D.pal_box((2, 11.2, 2.5), (14, 16, 3), "chrome_dark"),
        D.box((2.8, 11.6, 2.4), (13.2, 16, 2.5), {"north": ("screen", [2, cut, 14, 16])}),
    ]
    lower.append(D.pal_box((10.6, 11.2, 1.4), (13.4, 11.7, 3.6), "red"))  # the big spin button
    lower.append(D.pal_box((10.9, 11.7, 1.7), (13.1, 11.9, 3.3), "flame_core"))
    for x0 in (1.6, 13.8):  # the light strips up the front edges
        lower.append(D.box((x0, 1.5, 2.3), (x0 + 0.6, 16, 2.9), {f: ("led", [0, 0, 1, 16]) for f in D.FACES}))
    D.model("casino_slot_machine_lower", {"body": "slot_body", "side": "slot_side", "buttons": "slot_buttons", "chrome_t": "chrome", "screen": "slot_screen",
                                          "led": "led_strip", P: P}, lower, particle="side", ao=False)
    upper = [
        D.box((2, 0, 3), (14, 9, 15), {"north": side, "south": side, "east": side, "west": side}),
        D.pal_box((2, 0, 2.5), (14, 9, 3), "chrome_dark"),
        D.box((2.8, 0, 2.4), (13.2, 8.4, 2.5), {"north": ("screen", [2, 0, 14, cut])}),
        D.pal_box((2, 8.4, 2.3), (14, 9, 3), "chrome"),
        # the topper: a lit sign a little wider than the cabinet, chrome round it, a gold crown and the candle
        D.box((1.5, 9, 2), (14.5, 14, 13), {"north": ("marquee", [1.5, 0, 14.5, 8]), "south": side, "east": side, "west": side,
                                            "up": side}),
        D.pal_box((1.3, 8.8, 1.8), (14.7, 9.2, 13.2), "chrome"),
        D.pal_box((1.3, 13.8, 1.8), (14.7, 14.2, 13.2), "chrome"),
        D.pal_box((4, 14.2, 3), (12, 15, 11), "gold"),
        D.pal_box((7, 15, 6), (9, 15.6, 8), "chrome"),
        D.pal_box((7.3, 15.6, 6.3), (8.7, 17.2, 7.7), "red"),
        D.pal_box((7.3, 17.2, 6.3), (8.7, 18.4, 7.7), "flame_core"),
    ]
    for x0 in (1.6, 13.8):
        upper.append(D.box((x0, 0, 2.3), (x0 + 0.6, 8.8, 2.9), {f: ("led", [0, 0, 1, 8.8]) for f in D.FACES}))
    D.model("casino_slot_machine_upper", {"side": "slot_side", "screen": "slot_screen", "marquee": "slot_marquee", "led": "led_strip",
                                          P: P}, upper, particle="side", ao=False)


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

def mix(a, b, t):
    return tuple(int(x + (y - x) * t) for x, y in zip(a, b))


def carpet(name, base, line, accent, dark):
    """A casino carpet that tiles: a calm field with a faint lattice and one small medallion per tile, low contrast so a big
    floor reads as rich colour rather than a flicker of dots."""
    img = D.new()
    D.noise_fill(img, base, 0.025, D.rng(name))
    px = img.load()
    soft = mix(base, line, 0.35)
    for y in range(16):
        for x in range(16):
            if (x + y) % 16 == 0 or (x - y) % 16 == 0:
                px[x, y] = D.rgba(soft)
    for dx in range(-2, 3):
        for dy in range(-2, 3):
            d = abs(dx) + abs(dy)
            if d == 2:
                px[8 + dx, 8 + dy] = D.rgba(mix(base, accent, 0.45))
            elif d == 0:
                px[8, 8] = D.rgba(mix(base, accent, 0.6))
            elif d == 1:
                px[8 + dx, 8 + dy] = D.rgba(mix(base, dark, 0.5))
    return D.tex(name, img)


def damask():
    """Wallpaper: deep red damask, a fleur a shade lighter than the ground (tone on tone), one per tile."""
    img = D.new()
    base = (110, 16, 26)
    D.noise_fill(img, base, 0.02, D.rng("damask"))
    px = img.load()
    fleur = ["...#...", "..###..", ".#.#.#.", "#..#..#", "..###..", ".#.#.#.", "#.....#"]
    for gy, row in enumerate(fleur):
        for gx, c in enumerate(row):
            if c == "#":
                px[4 + gx, 4 + gy] = D.rgba(mix(base, (196, 140, 56), 0.3 if gy < 4 else 0.22))
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
            px[x, y] = D.shade(ivory, (1.04, 1.0, 0.92, 1.0)[x % 4])  # soft flutes, ivory on ivory
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
            px[x, y] = D.rgba((255, 248, 226)) if (i + phase) % 3 == 0 else D.rgba((246, 222, 160))
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
    """A palm frond seen from above along its length (u from the base to the tip): a pale midrib and a solid fan of leaflets
    swept towards the tip, narrowing to it; a darker line every two texels marks where one leaflet ends and the next begins."""
    img = D.new()
    px = img.load()
    for u in range(16):
        reach = 7.2 * (1 - (u / 17.0) ** 1.4)
        for side in (-1, 1):
            for k in range(1, int(round(reach)) + 1):
                y = 8 + k - 1 if side > 0 else 7 - k + 1
                y = y + side
                if not 0 <= y < 16:
                    continue
                t = k / max(1.0, reach)
                c = mix((104, 172, 66), (38, 104, 40), t)
                if (u - k // 2) % 2 == 0:
                    c = D.shade(c, 0.82)
                px[u, y] = D.rgba(c)
        px[u, 7] = D.rgba((156, 172, 88))
        px[u, 8] = D.rgba((126, 152, 72))
    return D.tex("palm_leaf", img)


def palm_bark():
    """A palm's bark: grey-brown with the scars of old fronds as offset diamond rings."""
    img = D.new()
    D.noise_fill(img, (128, 102, 74), 0.05, D.rng("palm_bark"))
    px = img.load()
    for y in range(16):
        for x in range(16):
            if (y + (x // 4) % 2 * 2) % 4 == 0:
                px[x, y] = D.rgba((92, 72, 52))
            elif (y + (x // 4) % 2 * 2) % 4 == 1:
                px[x, y] = D.rgba((150, 124, 92))
    return D.tex("palm_bark", img)


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
    slot_screen()
    slot_marquee()
    slot_body()
    slot_side()
    led_strip()
    felt_print()
    carpet("carpet_red", (128, 20, 30), (214, 168, 60), (250, 220, 120), (60, 10, 20))
    carpet("carpet_blue", (24, 40, 110), (200, 160, 70), (240, 210, 110), (12, 18, 50))
    carpet("carpet_purple", (70, 22, 96), (210, 170, 70), (250, 220, 130), (34, 10, 48))
    damask()
    wainscot()
    column_textures()
    coffer()
    jackpot()
    palm_leaf()
    die_faces()
    palm_bark()
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


BENDS = 4  # how far a trunk piece leans: each step moves it 1.5 pixels over, so a tall palm can curve


def leaf_piece(f_len, y, start, angle, uv_span, width=10):
    """A piece of a frond running north (-z) from z = start at height y, tilted down by angle (negative), using the leaf
    texture's u range uv_span (base to tip)."""
    w0, w1 = 8 - width / 2, 8 + width / 2
    u0, u1 = uv_span
    face = ("leaf", [u0, 0, u1, 16], 270)
    return D.box((w0, y, start - f_len), (w1, y + 0.1, start), {"up": face, "down": face}, faces=("up", "down"),
                 rotation={"origin": [8, y, start], "axis": "x", "angle": angle})


CROWN_Y = 6.5  # where the fronds leave the crown's knob
EDGE_DROP = 8 * math.tan(math.radians(22.5))  # how far a frond has fallen by the edge of the crown's block


def palm_crown():
    """The crown of a palm on top of its trunk: a knob of old frond bases, coconuts under it, four short fronds rising in the
    middle, four level ones on the diagonals, and the first part of the four long ones, which droop out over the block's edges
    and go on in the neighbouring blocks (casino_palm_frond)."""
    els = [D.box((5, 0, 5), (11, 4, 11), "bark"), D.box((6, 4, 6), (10, 7, 10), "bark")]
    for (cx, cz, cy) in ((6.2, 6.2, 2.2), (9.8, 6.4, 1.6), (6.6, 9.8, 1.4), (9.6, 9.6, 2.4)):
        els.append(D.pal_box((cx - 1.3, cy, cz - 1.3), (cx + 1.3, cy + 2.6, cz + 1.3), "walnut"))
        els.append(D.pal_box((cx - 0.4, cy + 2.6, cz - 0.4), (cx + 0.4, cy + 3.2, cz + 0.4), "gold_dark"))
    seg = 8 / math.cos(math.radians(22.5))
    for spin in (0, 90, 180, 270):
        # the long fronds' first part, from the knob to the edge, and a short frond rising over it; each is built pointing north
        # and turned about the block's middle (only quarter turns of whole elements are possible, so the pieces are laid out
        # for each side by hand)
        pieces = [leaf_piece(seg, CROWN_Y, 8, -22.5, (0, 5)), leaf_piece(7, CROWN_Y + 0.8, 8, 22.5, (4, 16), width=8)]
        for el in pieces:
            els.append(turned(el, spin))
    for (f, t) in (((8, 6.8, 3.5), (32, 6.9, 12.5)), ((-16, 6.8, 3.5), (8, 6.9, 12.5))):
        uv = [0, 0, 16, 16] if f[0] >= 8 else [16, 0, 0, 16]
        for r in (45, -45):
            els.append(D.box(f, t, {"up": ("leaf", uv), "down": ("leaf", uv)}, faces=("up", "down"),
                             rotation={"origin": [8, 6.8, 8], "axis": "y", "angle": r}))
    D.model("casino_palm", {"bark": "palm_bark", "leaf": "palm_leaf", "palette": "palette"}, els, particle="leaf", ao=False)


def turned(el, spin):
    """An element built pointing north, turned clockwise (seen from above) by spin degrees about the block's middle; a tilt
    about x becomes a tilt about z for the east and west sides."""
    if spin == 0:
        return el
    import copy
    e = copy.deepcopy(el)
    def pt(x, z):
        for _ in range(spin // 90):
            x, z = 16 - z, x
        return x, z
    (x0, y0, z0), (x1, y1, z1) = e["from"], e["to"]
    a, b = pt(x0, z0), pt(x1, z1)
    e["from"] = [round(min(a[0], b[0]), 3), y0, round(min(a[1], b[1]), 3)]
    e["to"] = [round(max(a[0], b[0]), 3), y1, round(max(a[1], b[1]), 3)]
    r = e.get("rotation")
    if r:
        ox, oz = pt(r["origin"][0], r["origin"][2])
        r["origin"] = [round(ox, 3), r["origin"][1], round(oz, 3)]
        if r["axis"] == "x":
            # north (-z) tilting down by -angle about x: east is +x, which tilts down about z by the same sign as north's x tilt
            # flipped; south is x again with the opposite sign; west is z with north's sign
            ang = r["angle"]
            r["axis"], r["angle"] = {90: ("z", ang), 180: ("x", -ang), 270: ("z", -ang)}[spin]
    for face in e["faces"].values():
        face["rotation"] = (face.get("rotation", 0) + spin) % 360
        if face["rotation"] == 0:
            del face["rotation"]
    return e


def palm_frond():
    """The outer part of one of a palm crown's long fronds, in the block beside the crown (north of it when facing north): it
    comes over the edge where the crown's part ends and arches on out and down."""
    y = CROWN_Y - EDGE_DROP
    l1 = 12
    els = [leaf_piece(l1, y, 16, -22.5, (5, 12))]
    z2 = 16 - l1 * math.cos(math.radians(22.5))
    y2 = y - l1 * math.sin(math.radians(22.5))
    els.append(leaf_piece(9, y2, z2, -45, (12, 16)))
    # a second, wider frond just under it, splitting off earlier and falling more steeply: the crown looks full from below
    l3 = 8
    els.append(leaf_piece(l3, y - 1, 16, -22.5, (5, 11), width=13))
    els.append(leaf_piece(8, y - 1 - l3 * math.sin(math.radians(22.5)), 16 - l3 * math.cos(math.radians(22.5)), -45, (11, 16), width=12))
    D.model("casino_palm_frond", {"leaf": "palm_leaf"}, els, particle="leaf", ao=False)


def palm_trunk(bend):
    """A piece of a palm's trunk, slim and ringed, set over by its bend."""
    dx = bend * 1.5
    els = [D.box((5 + dx, 0, 5), (11 + dx, 16, 11), "bark"), D.box((4.6 + dx, 0, 4.6), (11.4 + dx, 1.5, 11.4), "bark"),
           D.box((4.6 + dx, 8, 4.6), (11.4 + dx, 9.5, 11.4), "bark")]
    D.model(f"casino_palm_trunk_{bend}", {"bark": "palm_bark"}, els, particle="bark")


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
    palm_frond()
    for bend in range(BENDS):
        palm_trunk(bend)
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
    "casino_palm_frond": ("Пальмовая ветвь", "Palm frond"),
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
    W(j(D.ASSETS, "blockstates", "casino_palm_trunk.json"), {"variants": {
        f"bend={b},facing={f}": ({"model": D.NS + f"casino_palm_trunk_{b}", "y": y} if y else {"model": D.NS + f"casino_palm_trunk_{b}"})
        for b in range(BENDS) for f, y in D.FACINGS}})
    W(j(D.ASSETS, "models", "item", "casino_palm_trunk.json"), {"parent": D.NS + "casino_palm_trunk_0"})
    D.loot("casino_palm_trunk")
    for name in ("casino_palm", "casino_palm_frond"):
        W(j(D.ASSETS, "blockstates", name + ".json"), {"variants": D.facing_variants(name)})
        W(j(D.ASSETS, "models", "item", name + ".json"), {"parent": D.NS + name, "display": D.SMALL})
        D.loot(name)
    W(j(D.ASSETS, "blockstates", "casino_fountain_jet.json"), {"variants": {"": {"model": D.NS + "casino_fountain_jet"}}})
    W(j(D.ASSETS, "models", "item", "casino_fountain_jet.json"), {"parent": D.NS + "casino_fountain_jet", "display": D.SMALL})
    D.loot("casino_fountain_jet")
