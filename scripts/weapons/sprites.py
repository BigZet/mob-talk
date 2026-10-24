"""Draws the weapons as vanilla-style 16 px item sprites (original pixel art, GPL-3.0): the swords, the spear and the
stake lie on the sword diagonal (grip low left, point high right) as vanilla swords do; the bow, sling, pitcher and horn
are drawn as icons. Each sprite gets an outline in a darker shade of what it borders, as vanilla items have. Fire and holy
light go to a separate *_glow texture (drawn at full brightness by WeaponRenderer), animated through a .mcmeta.
The six blades also get a 32 px *_hand sprite for the hand (drawn there at twice the size, as the trident and the spyglass\nhave a hand model of their own). Writes textures/item/weapon/*.png (+ .mcmeta) and models/item/weapon/*.json; --preview\nalso writes enlarged sheets.
Run: python scripts/weapons/sprites.py [--preview out.png]"""
import json
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.normpath(os.path.join(HERE, "..", "..", "src", "main", "resources", "assets", "divineblock"))
TEX = os.path.join(ASSETS, "textures", "item", "weapon")
MODELS = os.path.join(ASSETS, "models", "item", "weapon")


def rgb(h):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255, 255)


def shade(c, k):
    return (max(0, min(255, int(c[0] * k))), max(0, min(255, int(c[1] * k))), max(0, min(255, int(c[2] * k))), c[3])


class Sprite:
    def __init__(self, n=16):
        self.n = n
        self.px = {}

    def put(self, x, y, c):
        if 0 <= x < self.n and 0 <= y < self.n:
            self.px[(x, y)] = c

    def diag(self, s, d, c):
        """A pixel by its place along the sword diagonal: s = x - y (grip -, point +), d = x + y - (n - 1) (across: - is
        the upper-left edge). Only cells where s and d have the parity of n - 1 exist."""
        m = self.n - 1
        if (s + d + m) % 2:
            return
        x, y = (s + d + m) // 2, (d + m - s) // 2
        self.put(x, y, c)

    def ascii(self, rows, pal):
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch in pal:
                    self.put(x, y, pal[ch])

    def outlined(self, k=0.42, skip=()):
        """Every empty pixel touching the sprite (4-neighbours) takes a dark shade of its most common neighbour."""
        out = dict(self.px)
        for y in range(self.n):
            for x in range(self.n):
                if (x, y) in self.px:
                    continue
                near = [self.px[p] for p in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)) if p in self.px and self.px[p] not in skip]
                if near:
                    base = max(set(near), key=near.count)
                    out[(x, y)] = shade(base, k)
        s = Sprite(self.n)
        s.px = out
        return s

    def image(self):
        img = Image.new("RGBA", (self.n, self.n), (0, 0, 0, 0))
        for (x, y), c in self.px.items():
            img.putpixel((x, y), c)
        return img


def sword(blade, guard, grip, pommel, s_tip=12, s_guard=-4, s_end=-12, wide=1, guard_half=3, edge=None):
    """A vanilla-shaped sword: blade from the guard to the point, |d| <= wide, light on the upper-left edge."""
    sp = Sprite()
    light, mid, dark = blade
    for s in range(s_guard + 1, s_tip + 1):
        taper = wide if s < s_tip - 1 else (1 if s == s_tip - 1 else 0)
        for d in range(-taper, taper + 1):
            c = light if d < 0 else (mid if d == 0 else dark)
            if edge and d == -taper and taper > 0:
                c = edge
            sp.diag(s, d, c)
    for s in (s_guard, s_guard - 1):
        for d in range(-guard_half, guard_half + 1):
            sp.diag(s, d, guard[0] if d < 0 else guard[1])
    for s in range(s_end + 1, s_guard - 1):
        for d in (-1, 0, 1):
            sp.diag(s, d, grip[(s // 2) % 2] if d <= 0 else shade(grip[0], 0.8))
    for s in (s_end, s_end - 1):
        for d in (-1, 0, 1):
            sp.diag(s, d, pommel[0] if d <= 0 else pommel[1])
    return sp


def cherub_sword():
    gold, gold_d = rgb(0xF5C542), rgb(0xB8860B)
    sp = sword((rgb(0xFFF3B0), rgb(0xFFB02E), rgb(0xE0571B)), (gold, gold_d), (rgb(0x8A2E12), rgb(0x6B220C)), (rgb(0xFF7A1A), gold_d))
    base = sp.outlined()
    glow_frames = []
    import random
    for f in range(4):
        r = random.Random(f * 31 + 7)
        g = Sprite()
        for s in range(-3, 13):
            for d in (-1, 0, 1):
                c = (rgb(0xFFF6C8) if d == 0 else rgb(0xFFC23A)) if (s + f) % 4 else rgb(0xFF8C1A)
                g.diag(s, d, c)
            # tongues of flame lick off the blade's edges
            if r.random() < 0.45:
                g.diag(s, -2 if r.random() < 0.5 else 2, rgb(0xFF9A2A) if r.random() < 0.6 else rgb(0xFFD64A))
            if s > 4 and r.random() < 0.18:
                g.diag(s, -3 if r.random() < 0.5 else 3, rgb(0xFF6A10))
        glow_frames.append(g)
    return base, glow_frames, 3


def archistrategos_sword():
    gold, gold_d = rgb(0xFFD95A), rgb(0xA87A16)
    sp = sword((rgb(0xF4FBFF), rgb(0xC9DDF2), rgb(0x8EA9C8)), (gold, gold_d), (rgb(0x2B4C9B), rgb(0x1E3570)),
               (rgb(0x5FA8FF), rgb(0x2A5BB5)), s_tip=13, guard_half=4)
    # wings on the guard's ends, raised toward the point
    for d in (-4, 4):
        sp.diag(-3, d - (1 if d < 0 else -1) * 0, gold)
    base = sp.outlined()
    frames = []
    for f in range(6):
        g = Sprite()
        for s in range(-2, 14):
            if (s - f * 3) % 18 in (0, 1, 2, 3):
                g.diag(s, 0 if s % 2 else -1, rgb(0xFFFFFF))
            elif s % 2:
                g.diag(s, 0, rgb(0xCFE6FF))
        g.diag(-11, 0 if True else 0, rgb(0x9FD0FF))
        g.diag(-12, -1, rgb(0x9FD0FF))
        frames.append(g)
    return base, frames, 3


def war_sword():
    sp = sword((rgb(0x8C8F98), rgb(0x5E616B), rgb(0x3B3D45)), (rgb(0x3A3A40), rgb(0x22222A)), (rgb(0x5A1414), rgb(0x3A0C0C)),
               (rgb(0xC0262B), rgb(0x6E1014)), s_tip=13, s_guard=-5, s_end=-13, wide=2, guard_half=4, edge=rgb(0xC8CCD4))
    # a blood groove down the blade and a red gem in the guard
    for s in range(-3, 10, 2):
        sp.diag(s, 0, rgb(0x8E1B1F))
    sp.diag(-5, -1, rgb(0xE0343A))
    base = sp.outlined(0.38)
    frames = []
    for f in range(4):
        g = Sprite()
        k = (0.55, 0.8, 1.0, 0.8)[f]
        for s in range(-3, 10, 2):
            g.diag(s, 0, shade(rgb(0xFF3B30), k))
        g.diag(-5, -1, shade(rgb(0xFF5A50), k))
        frames.append(g)
    return base, frames, 5


def george_spear():
    sp = Sprite()
    wood, wood_d = rgb(0x9C6B3C), rgb(0x6E4824)
    for s in range(-15, 8):
        sp.diag(s, 0 if s % 2 else -1, wood if s % 6 else wood_d)
        if s % 2 == 0:
            sp.diag(s, 1, wood_d)
    steel, steel_l, steel_d = rgb(0xC9CED6), rgb(0xF2F5F9), rgb(0x7D8592)
    for s in range(9, 16):
        w = 1 if s in (10, 11, 12, 13) else 0
        for d in range(-w, w + 1):
            sp.diag(s, d, steel_l if d < 0 else (steel if d == 0 else steel_d))
    for d in (-2, 0, 2):
        sp.diag(8, d, rgb(0xD4A93A))
    # the red pennant with a white cross hangs below the head, its fly cut in two tails
    red, red_d, white = rgb(0xC8202A), rgb(0x8A1018), rgb(0xF4F4F4)
    for s in range(1, 8):
        for d in range(2, 6):
            if s == 1 and d == 3 or s == 2 and d == 4:
                continue
            sp.diag(s, d, red if d < 5 else red_d)
    for s, d in ((5, 3), (4, 4), (6, 4), (5, 5), (3, 3), (7, 3)):
        sp.diag(s, d, white)
    return sp.outlined(), None, 0

def aspen_stake():
    sp = Sprite()
    wood, wood_l, wood_d = rgb(0xD9D2B4), rgb(0xEDE8D2), rgb(0xA89F80)
    bark, bark_d = rgb(0x8C9B86), rgb(0x5E6B5A)
    for s in range(-13, 13):
        w = 1 if s < 9 else (0 if s >= 11 else 1)
        for d in range(-w, w + 1):
            c = wood_l if d < 0 else (wood if d == 0 else wood_d)
            if s < 2:
                c = bark if d <= 0 else bark_d
                if s % 5 == 0:
                    c = rgb(0x3F4A3D)
            sp.diag(s, d, c)
    sp.diag(12, 0, rgb(0xF8F4E4))
    return sp.outlined(0.45), None, 0


def samson_jawbone():
    rows = [
        "................",
        ".............Bb.",
        "............TBBb",
        "...........tBBb.",
        "..........TBBb..",
        ".........tBBb...",
        "........TBBb....",
        ".......tBBb.....",
        "......TBBb......",
        ".....tBBb.......",
        "....BBBBb.......",
        "...BBBbb........",
        "..BLLBb.........",
        ".BLLLb..........",
        ".bLb............",
        "..b.............",
    ]
    sp = Sprite()
    sp.ascii(rows, {"B": rgb(0xE8DFC4), "b": rgb(0xB8AC8A), "T": rgb(0xFFF8D8), "t": rgb(0xC9B98C), "L": rgb(0x7A4A25)})
    return sp.outlined(0.45), None, 0

def nimrod_bow(pull=-1):
    """The stave from top left to bottom right bulging to the top-right corner; the string drawn back by {pull}."""
    sp = Sprite()
    wood, wood_d, gold, lapis = rgb(0x5A3A22), rgb(0x3A2414), rgb(0xE8B84A), rgb(0x2F5BC4)
    stave = [(3, 0), (4, 0), (5, 0), (6, 1), (7, 1), (8, 2), (9, 2), (10, 3), (11, 4), (12, 5), (13, 6), (13, 7), (14, 8), (14, 9), (15, 10), (15, 11), (15, 12)]
    stave = [(x, y) for x, y in stave]
    for i, (x, y) in enumerate(stave):
        sp.put(x, y, gold if i in (0, 8, 16) else (lapis if i == 8 else (wood if i % 3 else wood_d)))
    sp.put(10, 3, gold); sp.put(11, 3, lapis); sp.put(10, 4, lapis)
    a, b = (2, 1), (14, 13)
    string = rgb(0xEDE6D6)
    if pull < 0:
        for t in range(1, 12):
            sp.put(a[0] + t, a[1] + t, string)
    else:
        back = (1, 2, 3)[pull]
        cx, cy = 8 - back, 7 + back
        for p, q in ((a, (cx, cy)), ((cx, cy), b)):
            n = max(abs(q[0] - p[0]), abs(q[1] - p[1]))
            for t in range(n + 1):
                sp.put(round(p[0] + (q[0] - p[0]) * t / n), round(p[1] + (q[1] - p[1]) * t / n), string)
        # the arrow along the diagonal from the string to past the stave
        for t in range(0, 9 + back):
            x, y = cx + t, cy - t
            if (x, y) not in sp.px or t < 2:
                sp.put(x, y, rgb(0x8A6A3A) if t > 1 else rgb(0xF0F0F0))
        tip = (cx + 9 + back, cy - 9 - back)
        sp.put(*tip, rgb(0xD8D8DC)); sp.put(tip[0] - 1, tip[1], rgb(0x9A9AA4)); sp.put(tip[0], tip[1] + 1, rgb(0x9A9AA4))
    return sp.outlined(0.5)


def david_sling(wound=False):
    sp = Sprite()
    cord, leather, leather_d, stone = rgb(0xC9B48A), rgb(0x8B5A2B), rgb(0x5E3A1A), rgb(0xA0A0A4)
    rows = [
        "................",
        "..LL............",
        ".L..L...........",
        ".L..c...........",
        "..LLc...........",
        "....c...........",
        ".....c..........",
        ".....c....c.....",
        "......c..c......",
        "......c.c.......",
        ".......PPPP.....",
        "......PPSSPP....",
        "......PSSsSP....",
        ".......PSsPp....",
        "........Ppp.....",
        "................",
    ] if not wound else [
        "................",
        "..LL............",
        ".L..L...........",
        ".L..cc..........",
        "..LL..cc........",
        "........cc......",
        "..........cc....",
        "...........PPP..",
        "..........PSSPP.",
        "..........PSsSP.",
        "...........PsPp.",
        "..........cPpp..",
        "........cc......",
        "......cc........",
        ".....c..........",
        "................",
    ]
    sp.ascii(rows, {"L": cord, "c": cord, "P": leather, "p": leather_d, "S": stone, "s": shade(stone, 0.7)})
    return sp.outlined(0.45)


def gideon_jar():
    clay, clay_l, clay_d = rgb(0xC0703A), rgb(0xDB9258), rgb(0x8A4A22)
    rows = [
        "................",
        "................",
        "................",
        "................",
        "................",
        "......RRRR......",
        ".......nn.......",
        "......CCCC..H...",
        ".....CCLCCC.H...",
        "....CCLCCCCCH...",
        "....CLCCCCCdH...",
        "....CLCCCCCd....",
        "....CCCCCCdd....",
        ".....CCCCCd.....",
        "......DDDD......",
        "................",
    ]
    sp = Sprite()
    sp.ascii(rows, {"R": clay_l, "n": clay_d, "C": clay, "L": clay_l, "d": clay_d, "D": clay_d, "H": clay_d})
    # the torch's handle shows over the rim
    sp.put(7, 4, rgb(0x6E4824)); sp.put(8, 4, rgb(0x6E4824))
    base = sp.outlined(0.45)
    frames = []
    shapes = [
        ["........", "...Y....", "..YWY...", "..yWY..."],
        ["...Y....", "..YY....", "..YWY...", "..yWYy.."],
        ["....Y...", "...YY...", "..YWWY..", "..yWY..."],
        ["...Y....", "...YY...", "..YWYY..", "...WYy.."],
    ]
    for shape in shapes:
        g = Sprite()
        g.ascii(["....." + r for r in shape] + [""] * 12, {"Y": rgb(0xFFC23A), "W": rgb(0xFFF6C8), "y": rgb(0xFF7A1A)})
        frames.append(g)
    return base, frames, 3


def jericho_horn():
    horn, horn_l, horn_d, band = rgb(0xC8A46A), rgb(0xE6CC94), rgb(0x8E6E3E), rgb(0xD4A93A)
    rows = [
        "................",
        "...........BBB..",
        "..........BhhhB.",
        "..........HhhhH.",
        "..........HHhHH.",
        "...........HHH..",
        "...........GGH..",
        "..........HHG...",
        ".........HHHd...",
        "........HHGd....",
        ".......LHHd.....",
        ".....LLHHd......",
        "...LLHHdd.......",
        "..LHHdd.........",
        ".MMd............",
        "................",
    ]
    sp = Sprite()
    sp.ascii(rows, {"B": horn_l, "H": horn, "h": rgb(0x3A2A18), "L": horn_l, "d": horn_d, "G": band, "M": rgb(0x6E4824)})
    return sp.outlined(0.45), None, 0


# ---- The hand sprites: 32 px, drawn at twice the size about the grip (WeaponRenderer), so a pixel is as big as a
# vanilla one and the blades run about a block and three quarters. The grip is near pixel (7, 24): s = -18 on the diagonal.

def blade(sp, s0, s1, width, pal, cut=lambda s, d: False):
    """A blade along the diagonal: half width {width(s)}, the upper-left edge bright, the back dark."""
    for s in range(s0, s1 + 1):
        w = width(s)
        for d in range(-w, w + 1):
            if cut(s, d):
                continue
            if w == 0:
                c = pal["edge"]
            elif d <= -w + 1:
                c = pal["edge"]
            elif d >= w - 1:
                c = pal["back"]
            else:
                c = pal["light"] if d < 0 else (pal["mid"] if d == 0 else pal["dark"])
            sp.diag(s, d, c)


def bar(sp, s0, s1, half, light, mid, dark):
    for s in range(s0, s1 + 1):
        for d in range(-half, half + 1):
            sp.diag(s, d, light if s == s1 else (mid if s > s0 else dark))


def wings(sp, s0, s1, reach, light, dark):
    """A pair of wings off the guard, rising toward the point; feathers striped."""
    for s in range(s0, s1 + 1):
        k = s - s0
        lo, hi = 3 + k * 3 // 4, reach + k // 3
        for a in range(lo, hi + 1):
            c = light if (a - k) % 3 else dark
            sp.diag(s, a, c)
            sp.diag(s, -a, c)


def grip(sp, s0, s1, wrap, band):
    for s in range(s0, s1 + 1):
        for d in (-1, 0, 1):
            sp.diag(s, d, band if s % 4 == 0 else (wrap if d <= 0 else shade(wrap, 0.75)))


def pommel(sp, s_top, light, dark, gem):
    for i, s in enumerate(range(s_top, s_top - 4, -1)):
        half = (1, 2, 2, 1)[i]
        for d in range(-half, half + 1):
            sp.diag(s, d, light if d < 0 else dark)
    sp.diag(s_top - 1, 0, gem); sp.diag(s_top - 2, 0, gem)


def archistrategos_hand():
    sp = Sprite(32)
    gold, gold_l, gold_d = rgb(0xF7C948), rgb(0xFFF0A0), rgb(0xA87A16)
    pal = dict(edge=rgb(0xFFFFFF), light=rgb(0xE6F0FA), mid=rgb(0xA9CBF2), dark=rgb(0xB4C8DE), back=rgb(0x7F93B2))
    blade(sp, -9, 29, lambda s: 3 if s <= 18 else (2 if s <= 23 else (1 if s <= 26 else 0)), pal)
    wings(sp, -10, 0, 9, gold_l, gold_d)
    bar(sp, -12, -10, 5, gold_l, gold, gold_d)
    sapphire = rgb(0x3F7CFF)
    for s, d in ((-11, 0), (-10, -1), (-10, 1), (-12, -1), (-12, 1)):
        sp.diag(s, d, sapphire)
    grip(sp, -21, -13, rgb(0x2B4C9B), gold)
    pommel(sp, -22, gold_l, gold_d, sapphire)
    base = sp.outlined(0.4)
    frames = []
    for f in range(8):
        g = Sprite(32)
        for s in range(-8, 24):
            bright = (s - f * 5) % 40 < 5
            g.diag(s, 0, rgb(0xFFFFFF) if bright else rgb(0xCFE6FF))
            g.diag(s, -1, rgb(0xFFFFFF) if bright else rgb(0xB8D8FF)) if s % 2 == 0 else None
        for s, d in ((-11, 0), (-10, -1), (-10, 1)):
            g.diag(s, d, rgb(0x9FD0FF))
        g.diag(-23, 0, rgb(0x9FD0FF)); g.diag(-24, 0, rgb(0x9FD0FF))
        # a star of light runs off the point
        if f in (2, 3, 4):
            tip = [(29, 0), (28, -1), (28, 1), (27, 0)]
            for s, d in tip[: f - 1]:
                g.diag(s, d, rgb(0xFFFFFF))
        frames.append(g)
    return base, frames, 3


def cherub_hand():
    import random
    sp = Sprite(32)
    gold, gold_l, gold_d = rgb(0xF5C542), rgb(0xFFE89A), rgb(0xB8860B)
    pal = dict(edge=rgb(0xFFD84A), light=rgb(0xFFE98A), mid=rgb(0xFFF8D8), dark=rgb(0xFF9A2A), back=rgb(0xD9481A))
    width = lambda s: (2 + (1 if (s // 3) % 2 == 0 else 0)) if s <= 20 else (2 if s <= 24 else (1 if s <= 27 else 0))
    blade(sp, -9, 29, width, pal)
    wings(sp, -10, -4, 8, gold_l, gold_d)
    bar(sp, -12, -10, 5, gold_l, gold, gold_d)
    ruby = rgb(0xE0303A)
    for s, d in ((-11, 0), (-10, -1), (-10, 1)):
        sp.diag(s, d, ruby)
    grip(sp, -21, -13, rgb(0x8A1E1E), gold)
    pommel(sp, -22, gold_l, gold_d, rgb(0xFF7A1A))
    base = sp.outlined(0.4)
    frames = []
    for f in range(6):
        g = Sprite(32)
        blade(g, -9, 29, width, dict(edge=rgb(0xFFD84A), light=rgb(0xFFE98A), mid=rgb(0xFFFFF0), dark=rgb(0xFFB040), back=rgb(0xFF7A1A)))
        for s in range(-8, 28):
            for side in (-1, 1):
                r = random.Random((s - 2 * f) * 131 + side * 17)
                length = r.choice((0, 0, 1, 1, 2, 3))
                w = width(s)
                for k in range(1, length + 1):
                    c = (rgb(0xFFC23A), rgb(0xFF8C1A), rgb(0xE8501A))[min(2, k - 1)]
                    g.diag(s + k, side * (w + k), c)
        for s, d in ((-11, 0), (-10, -1), (-10, 1)):
            g.diag(s, d, rgb(0xFF5A50))
        frames.append(g)
    return base, frames, 2


def war_hand():
    sp = Sprite(32)
    iron, iron_l, iron_d = rgb(0x55555F), rgb(0x7C7C88), rgb(0x34343C)
    pal = dict(edge=rgb(0xD0D4DC), light=rgb(0x9599A3), mid=rgb(0x6A6E78), dark=rgb(0x4A4D56), back=rgb(0x2E3038))
    width = lambda s: 4 if s <= 16 else (3 if s <= 20 else (2 if s <= 24 else (1 if s <= 27 else 0)))
    blade(sp, -9, 29, width, pal, cut=lambda s, d: 0 <= s <= 18 and s % 4 in (0, 1) and d >= width(s))
    for s in range(-7, 15):
        for d in (-1, 0, 1):
            sp.diag(s, d, rgb(0x7A1418))
    bar(sp, -12, -10, 8, iron_l, iron, iron_d)
    for k, s in enumerate(range(-9, -5)):
        for d in (8 - k // 2, 9 - k // 2):
            sp.diag(s, d, iron_l); sp.diag(s, -d, iron_l)
    for s in (-14, -13):
        for d in (6, 7):
            sp.diag(s, d, iron); sp.diag(s, -d, iron)
    blood = rgb(0xD0262C)
    for s, d in ((-11, 0), (-10, -1), (-10, 1), (-12, -1), (-12, 1)):
        sp.diag(s, d, blood)
    grip(sp, -21, -13, rgb(0x4A0E0E), iron_d)
    pommel(sp, -22, iron_l, iron_d, blood)
    sp.diag(-26, 0, iron_l); sp.diag(-27, -1, iron_d); sp.diag(-27, 1, iron_d)
    base = sp.outlined(0.38)
    frames = []
    for f in range(4):
        k = (0.55, 0.8, 1.0, 0.8)[f]
        g = Sprite(32)
        for s in range(-7, 15):
            for d in (-1, 0, 1):
                g.diag(s, d, shade(rgb(0xFF3B30), k * (0.85 if d else 1.0)))
        for s, d in ((-11, 0), (-10, -1), (-10, 1), (-12, -1), (-12, 1), (-23, 0), (-24, 0)):
            g.diag(s, d, shade(rgb(0xFF5A50), k))
        # runes of war flicker along the flat
        for s in range(-4, 18, 5):
            if (s // 5 + f) % 3 == 0:
                g.diag(s, -3 if s % 2 else -2, shade(rgb(0xFF7060), k))
        frames.append(g)
    return base, frames, 5


def spear_hand():
    sp = Sprite(32)
    wood, wood_d, gold, gold_d = rgb(0x9C6B3C), rgb(0x6E4824), rgb(0xE8B84A), rgb(0xA87A16)
    for s in range(-27, 10):
        for d in (-1, 0, 1):
            sp.diag(s, d, gold if s in (-14, -13, -2, -1) else (wood if d <= 0 else wood_d))
    for s in (-29, -28):
        for d in (-1, 0, 1):
            sp.diag(s, d, gold_d)
    bar(sp, 10, 12, 2, rgb(0xFFE89A), gold, gold_d)
    pal = dict(edge=rgb(0xFFFFFF), light=rgb(0xE3E8EF), mid=rgb(0xC9CED6), dark=rgb(0x9AA3B0), back=rgb(0x6E7684))
    widths = [1, 2, 2, 3, 3, 3, 3, 3, 2, 2, 2, 1, 1, 1, 1, 0, 0, 0]
    blade(sp, 13, 30, lambda s: widths[s - 13], pal)

    white, white_d, red = rgb(0xF4F4F4), rgb(0xCFCFD6), rgb(0xC8202A)
    for s in range(-3, 10):
        for d in range(2, 13):
            if s <= -1 and 6 <= d <= 9:
                continue
            c = red if s in (3, 4) or d in (7, 8) else (white if d < 11 else white_d)
            sp.diag(s, d, c)
    return sp.outlined(0.42), None, 0


def stake_hand():
    import random
    sp = Sprite(32)
    r = random.Random(5)
    pal = dict(edge=rgb(0xF4F0DE), light=rgb(0xE6E0C6), mid=rgb(0xD9D2B4), dark=rgb(0xB8AF90), back=rgb(0x9C9374))
    bark = dict(edge=rgb(0xB4BEAC), light=rgb(0x9AA592), mid=rgb(0x8C9B86), dark=rgb(0x7E8A78), back=rgb(0x5E6B5A))
    blade(sp, -25, -9, lambda s: 2, bark)
    blade(sp, -8, 21, lambda s: 2, pal)
    blade(sp, 22, 29, lambda s: (2, 2, 1, 1, 1, 0, 0, 0)[s - 22], dict(pal, mid=rgb(0xF2EBD0), light=rgb(0xF2EBD0)))
    for s in range(-25, -9):
        if r.random() < 0.3:
            sp.diag(s, r.choice((-1, 0, 1)), rgb(0x3F4A3D))
    for s in (-9, -8):
        for d in (-2, -1, 0, 1, 2):
            sp.diag(s, d, rgb(0xC9B48A))
    cut = rgb(0x8A7A50)
    for s in range(2, 11):
        sp.diag(s, 0 if s % 2 == 0 else -1, cut) if s % 2 == 0 else None
    for s, d in ((7, -1), (7, 1), (6, -2), (6, 2), (8, -2), (8, 2)):
        sp.diag(s, d, cut)
    return sp.outlined(0.45), None, 0


def jawbone_hand():
    sp = Sprite(32)
    pal = dict(edge=rgb(0xFBF6E6), light=rgb(0xEFE7CF), mid=rgb(0xE2D8BC), dark=rgb(0xC4B898), back=rgb(0xA2967A))
    bend = lambda s: -round(2 * (1 - ((s - 4) / 22) ** 2))
    for s in range(-17, 27):
        w = 3 if -8 <= s <= 10 else (2 if s < 24 else 1)
        o = bend(s)
        for d in range(-w, w + 1):
            c = pal["edge"] if d <= -w + 1 else (pal["back"] if d >= w - 1 else (pal["light"] if d < 0 else pal["mid"]))
            sp.diag(s, d + o, c)
        if s >= -6 and s % 3 == 0:
            sp.diag(s, o - w - 1, rgb(0xFFF6D8)); sp.diag(s + 1, o - w - 2, rgb(0xFFF6D8)); sp.diag(s - 1, o - w - 2, rgb(0x8A7E5E))
    for s in range(-24, -14):
        for d in range(2, 2 + max(0, 8 - abs(s + 19))):
            sp.diag(s, d, pal["mid"] if d < 6 else pal["dark"])
    for s in range(-22, -15):
        for d in (-1, 0, 1):
            sp.diag(s, d + bend(s), rgb(0x7A4A25) if s % 3 else rgb(0x5A3418))
    return sp.outlined(0.45), None, 0


HAND = {"archistrategos_sword": archistrategos_hand, "cherub_sword": cherub_hand, "war_sword": war_hand,
        "george_spear": spear_hand, "aspen_stake": stake_hand, "samson_jawbone": jawbone_hand}


def save_frames(name, frames, frametime, n):
    sheet = Image.new("RGBA", (n, n * len(frames)), (0, 0, 0, 0))
    for i, fr in enumerate(frames):
        sheet.paste(fr.image(), (0, n * i))
    sheet.save(os.path.join(TEX, name + ".png"))
    with open(os.path.join(TEX, name + ".png.mcmeta"), "w", encoding="utf-8") as f:
        json.dump({"animation": {"frametime": frametime, "interpolate": True}}, f)
    write_model(name, name)

SPRITES = {"cherub_sword": cherub_sword, "archistrategos_sword": archistrategos_sword, "war_sword": war_sword,
             "george_spear": george_spear, "aspen_stake": aspen_stake, "samson_jawbone": samson_jawbone,
             "gideon_jar": gideon_jar, "jericho_horn": jericho_horn}


def write_model(name, layer):
    os.makedirs(MODELS, exist_ok=True)
    with open(os.path.join(MODELS, name + ".json"), "w", encoding="utf-8") as f:
        json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "divineblock:item/weapon/" + layer}}, f, indent=2)
        f.write("\n")


def save(name, img, frames=None, frametime=0):
    os.makedirs(TEX, exist_ok=True)
    img.save(os.path.join(TEX, name + ".png"))
    write_model(name, name)
    if frames:
        save_frames(name + "_glow", frames, frametime, img.size[0])


def main(preview=None):
    sheet = []
    for name, make in SPRITES.items():
        base, frames, ft = make()
        save(name, base.image(), frames, ft)
        sheet.append(base.image())
        if frames:
            sheet.append(frames[0].image())
    bow = nimrod_bow().image()
    save("nimrod_bow", bow)
    sheet.append(bow)
    for i in range(3):
        img = nimrod_bow(i).image()
        save("nimrod_bow_pulling_%d" % i, img)
        sheet.append(img)
    for wound in (False, True):
        img = david_sling(wound).image()
        save("david_sling" + ("_wound" if wound else ""), img)
        sheet.append(img)
    big = []
    for name, make in HAND.items():
        base, frames, ft = make()
        save(name + "_hand", base.image(), frames, ft)
        big.append(base.image())
        if frames:
            big.append(frames[0].image())
    if preview:
        cols = 6
        rows = (len(sheet) + cols - 1) // cols
        out = Image.new("RGBA", (cols * 17 * 12, rows * 17 * 12), (70, 70, 80, 255))
        for i, img in enumerate(sheet):
            out.paste(img.resize((192, 192), Image.NEAREST), ((i % cols) * 204, (i // cols) * 204), img.resize((192, 192), Image.NEAREST))
        out.save(preview)
        out = Image.new("RGBA", (5 * 33 * 8, 2 * 33 * 8), (70, 70, 80, 255))
        for i, img in enumerate(big):
            out.paste(img.resize((256, 256), Image.NEAREST), ((i % 5) * 264, (i // 5) * 264), img.resize((256, 256), Image.NEAREST))
        out.save(preview.replace(".png", "-hand.png"))


if __name__ == "__main__":
    main(sys.argv[2] if len(sys.argv) > 2 and sys.argv[1] == "--preview" else None)
