"""Pixel-art painting for the flat furniture (tools/city2/furniture.py): 32 texels a block, i.e. two texels a model pixel.

Everything is drawn in code from small colour ramps, the way Minecraft's own textures are made: flat fields of one tone, a
lighter top-left and darker bottom-right edge on every panel, grain and weave as regular strokes, a little ordered dither
where two tones meet. No per-pixel noise (it flickers when mip-mapped) and no photographs.
"""
import math
import random

from PIL import Image


def rgb(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


def shade(c, k):
    return tuple(max(0, min(255, round(v * k))) for v in c[:3]) + (c[3] if len(c) > 3 else 255,)


def mix(a, b, t):
    return tuple(round(a[i] * (1 - t) + b[i] * t) for i in range(3)) + (255,)


def ramp(c, n=5, lo=0.62, hi=1.22):
    """`n` tones of one colour, dark to light, with a slight hue shift (shadows cooler, lights warmer) like hand-picked palettes."""
    out = []
    for i in range(n):
        t = i / (n - 1)
        k = lo + (hi - lo) * t
        r, g, b = c[:3]
        warm = (t - 0.5) * 0.10
        out.append(tuple(max(0, min(255, round(v))) for v in (r * k * (1 + warm), g * k, b * k * (1 - warm))) + (255,))
    return out


BAYER = ((0, 8, 2, 10), (12, 4, 14, 6), (3, 11, 1, 9), (15, 7, 13, 5))


class Canvas:
    def __init__(self, w=32, h=32, fill=(0, 0, 0, 0)):
        self.w, self.h = w, h
        self.img = Image.new("RGBA", (w, h), fill)
        self.px = self.img.load()

    def get(self, x, y):
        return self.px[x, y]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h and c is not None:
            if len(c) > 3 and c[3] < 255 and c[3] > 0:
                c = mix(self.px[x, y], c, c[3] / 255)
            self.px[x, y] = c

    def rect(self, x0, y0, x1, y1, c):
        for y in range(max(0, y0), min(self.h, y1)):
            for x in range(max(0, x0), min(self.w, x1)):
                self.px[x, y] = c

    def hline(self, x0, x1, y, c):
        for x in range(x0, x1):
            self.set(x, y, c)

    def vline(self, x, y0, y1, c):
        for y in range(y0, y1):
            self.set(x, y, c)

    def outline(self, x0, y0, x1, y1, c):
        self.hline(x0, x1, y0, c)
        self.hline(x0, x1, y1 - 1, c)
        self.vline(x0, y0, y1, c)
        self.vline(x1 - 1, y0, y1, c)

    def bevel(self, x0, y0, x1, y1, light, dark, width=1):
        """A raised panel: light top and left edge, dark bottom and right edge."""
        for k in range(width):
            self.hline(x0 + k, x1 - k, y0 + k, light)
            self.vline(x0 + k, y0 + k, y1 - k, light)
            self.hline(x0 + k + 1, x1 - k, y1 - 1 - k, dark)
            self.vline(x1 - 1 - k, y0 + k + 1, y1 - k, dark)

    def inset(self, x0, y0, x1, y1, light, dark):
        """A sunken panel or groove: the reverse of bevel."""
        self.bevel(x0, y0, x1, y1, dark, light)

    def tone(self, x0, y0, x1, y1, k):
        for y in range(max(0, y0), min(self.h, y1)):
            for x in range(max(0, x0), min(self.w, x1)):
                self.px[x, y] = shade(self.px[x, y], k)

    def dither(self, x0, y0, x1, y1, a, b, t):
        """Ordered (Bayer) blend of two tones: `t` of the area gets `b`."""
        for y in range(max(0, y0), min(self.h, y1)):
            for x in range(max(0, x0), min(self.w, x1)):
                self.px[x, y] = b if BAYER[y % 4][x % 4] < t * 16 else a

    def vgrad(self, x0, y0, x1, y1, tones):
        """Bands of tones top to bottom with dithered seams."""
        n = len(tones)
        hgt = max(1, y1 - y0)
        for y in range(y0, y1):
            f = (y - y0) / hgt * (n - 1)
            i = min(n - 2, int(f)) if n > 1 else 0
            t = f - i
            for x in range(x0, x1):
                if n == 1:
                    c = tones[0]
                else:
                    c = tones[i + 1] if BAYER[y % 4][x % 4] < t * 16 else tones[i]
                self.set(x, y, c)

    def hgrad(self, x0, y0, x1, y1, tones):
        n = len(tones)
        wid = max(1, x1 - x0)
        for x in range(x0, x1):
            f = (x - x0) / wid * (n - 1)
            i = min(n - 2, int(f)) if n > 1 else 0
            t = f - i
            for y in range(y0, y1):
                c = tones[0] if n == 1 else (tones[i + 1] if BAYER[y % 4][x % 4] < t * 16 else tones[i])
                self.set(x, y, c)

    def disc(self, cx, cy, r, c, ring=None):
        for y in range(int(cy - r - 1), int(cy + r + 2)):
            for x in range(int(cx - r - 1), int(cx + r + 2)):
                d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                if d <= r:
                    self.set(x, y, ring if ring is not None and d > r - 1 else c)

    def ellipse(self, cx, cy, rx, ry, c):
        for y in range(int(cy - ry - 1), int(cy + ry + 2)):
            for x in range(int(cx - rx - 1), int(cx + rx + 2)):
                if ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2 <= 1:
                    self.set(x, y, c)

    def paste(self, other, x, y):
        for yy in range(other.h):
            for xx in range(other.w):
                c = other.px[xx, yy]
                if c[3]:
                    self.set(x + xx, y + yy, c)

    def crop(self, x0, y0, x1, y1):
        out = Canvas(x1 - x0, y1 - y0)
        for y in range(y0, y1):
            for x in range(x0, x1):
                if 0 <= x < self.w and 0 <= y < self.h:
                    out.px[x - x0, y - y0] = self.px[x, y]
        return out

    def flip_h(self):
        out = Canvas(self.w, self.h)
        for y in range(self.h):
            for x in range(self.w):
                out.px[self.w - 1 - x, y] = self.px[x, y]
        return out


# ---------------------------------------------------------------- materials

def wood(w, h, base, seed, vertical=False, polish=0.0, knots=True, spacing=4):
    """Veneer: a mid tone, long wavy grain strokes one and two steps darker, a polished sheen as a soft diagonal light band."""
    r = random.Random(seed)
    t = ramp(base, 6, 0.70, 1.18)
    cv = Canvas(w, h, t[3])
    L, S = (h, w) if not vertical else (w, h)  # strokes run along S
    lines = []
    y = r.uniform(0, spacing)
    while y < L + 2:
        lines.append(y)
        y += r.uniform(spacing * 0.75, spacing * 1.35)
    for k, y0 in enumerate(lines):
        amp, per, ph = r.uniform(0.3, 1.2), r.uniform(9, 22), r.uniform(0, 6.3)
        tone = t[2] if k % 3 else t[1]
        gap0 = r.randrange(0, S)
        for s in range(S):
            if (s - gap0) % S < r.choice((0, 0, 3)):
                continue
            yy = int(round(y0 + amp * math.sin(s / per * 6.28 + ph)))
            x, yv = (s, yy) if not vertical else (yy, s)
            cv.set(x, yv, tone)
            if k % 2 == 0:
                x2, y2 = (s, yy + 1) if not vertical else (yy + 1, s)
                if 0 <= x2 < w and 0 <= y2 < h and cv.get(x2, y2) == t[3]:
                    cv.set(x2, y2, t[4])
    if knots and w * h >= 512:
        for _ in range(max(1, w * h // 1500)):
            kx, ky = r.randrange(3, w - 3), r.randrange(3, h - 3)
            cv.ellipse(kx, ky, 1.6 if not vertical else 1.0, 1.0 if not vertical else 1.6, t[1])
            cv.set(kx, ky, t[0])
    if polish:
        for yv in range(h):
            for x in range(w):
                d = ((x + yv * 0.8) % (w + h)) / (w + h)
                if 0.18 < d < 0.18 + 0.12 * polish or 0.55 < d < 0.55 + 0.05 * polish:
                    cv.set(x, yv, shade(cv.get(x, yv), 1.10))
    return cv


def fabric(w, h, base, seed, weave=2, nap=True):
    """Upholstery or wool: a two-tone weave every `weave` texels, a soft nap of lighter threads."""
    r = random.Random(seed)
    t = ramp(base, 5, 0.78, 1.15)
    cv = Canvas(w, h, t[2])
    for y in range(h):
        for x in range(w):
            if (x // weave + y // weave) % 2 == 0:
                cv.set(x, y, t[3] if (x + y) % 3 else t[2])
            elif (x + 2 * y) % 7 == 0:
                cv.set(x, y, t[1])
    if nap:
        for _ in range(w * h // 40):
            cv.set(r.randrange(w), r.randrange(h), t[4])
    return cv


def enamel(w, h, base, seed=0, chips=0):
    """Painted metal or enamel: one flat tone (a sheen band would repeat on every block of a tall piece), the odd dark chip."""
    r = random.Random(seed)
    t = ramp(base, 5, 0.80, 1.06)
    cv = Canvas(w, h, t[3])
    for _ in range(chips):
        cv.set(r.randrange(w), r.randrange(h), t[0])
    return cv


def metal(w, h, base, vertical=True):
    """Brushed or chromed metal: parallel light and dark reflection bands."""
    t = ramp(base, 6, 0.55, 1.25)
    cv = Canvas(w, h, t[3])
    pattern = (3, 3, 4, 5, 4, 3, 2, 2, 1, 2, 3, 3, 4, 3)
    n = w if vertical else h
    for i in range(n):
        tone = t[pattern[i % len(pattern)]]
        if vertical:
            cv.vline(i, 0, h, tone)
        else:
            cv.hline(0, w, i, tone)
    return cv


def tiles(w, h, base, grout, size=8):
    t = ramp(base, 4, 0.85, 1.08)
    cv = Canvas(w, h, t[2])
    for y in range(h):
        for x in range(w):
            if x % size == 0 or y % size == 0:
                cv.set(x, y, grout)
            elif x % size == 1 or y % size == 1:
                cv.set(x, y, t[3])
            elif x % size == size - 1 or y % size == size - 1:
                cv.set(x, y, t[1])
    return cv


def glass(w, h, tint=(150, 190, 205, 255), frame=None):
    t = ramp(tint, 5, 0.70, 1.20)
    cv = Canvas(w, h, t[2])
    for y in range(h):
        for x in range(w):
            d = (x - y) % (w + h)
            if d in (3, 4, 9) or d in (w + h - 6, w + h - 5):
                cv.set(x, y, t[4])
    if frame:
        cv.outline(0, 0, w, h, frame)
    return cv


# ---------------------------------------------------------------- a 3x5 Cyrillic pixel font for labels and signs

FONT = {
    "А": ".X.|X.X|XXX|X.X|X.X", "Б": "XXX|X..|XX.|X.X|XX.", "В": "XX.|X.X|XX.|X.X|XX.", "Г": "XXX|X..|X..|X..|X..",
    "Д": ".XX|.XX|X.X|XXX|X.X", "Е": "XXX|X..|XX.|X..|XXX", "Ж": "X.X|X.X|.X.|X.X|X.X", "З": "XX.|..X|.X.|..X|XX.",
    "И": "X.X|X.X|XXX|XXX|X.X", "К": "X.X|XX.|X..|XX.|X.X", "Л": ".XX|X.X|X.X|X.X|X.X", "М": "X.X|XXX|XXX|X.X|X.X",
    "Н": "X.X|X.X|XXX|X.X|X.X", "О": ".X.|X.X|X.X|X.X|.X.", "П": "XXX|X.X|X.X|X.X|X.X", "Р": "XX.|X.X|XX.|X..|X..",
    "С": ".XX|X..|X..|X..|.XX", "Т": "XXX|.X.|.X.|.X.|.X.", "У": "X.X|X.X|.XX|..X|XX.", "Ф": ".X.|XXX|XXX|.X.|.X.",
    "Х": "X.X|X.X|.X.|X.X|X.X", "Ц": "X.X|X.X|X.X|XXX|..X", "Ч": "X.X|X.X|.XX|..X|..X", "Ш": "X.X|X.X|XXX|XXX|XXX",
    "Ы": "X.X|X.X|XXX|X.X|XXX", "Ь": "X..|X..|XX.|X.X|XX.", "Э": "XX.|..X|.XX|..X|XX.", "Ю": "X.X|XXX|XXX|XXX|X.X",
    "Я": ".XX|X.X|.XX|X.X|X.X", " ": "...|...|...|...|...", "-": "...|...|XXX|...|...", ".": "...|...|...|...|.X.",
    "0": "XXX|X.X|X.X|X.X|XXX", "1": ".X.|XX.|.X.|.X.|XXX", "2": "XX.|..X|.X.|X..|XXX", "3": "XX.|..X|.X.|..X|XX.",
    "4": "X.X|X.X|XXX|..X|..X", "5": "XXX|X..|XX.|..X|XX.", "6": ".XX|X..|XXX|X.X|XXX", "7": "XXX|..X|.X.|.X.|.X.",
    "8": "XXX|X.X|XXX|X.X|XXX", "9": "XXX|X.X|XXX|..X|XX.", "№": "X.X|XXX|XXX|X.X|X.X",
    # letters that do not fit three columns are wider
    "И": "X..X|X..X|X.XX|XX.X|X..X", "Й": "X..X|X.XX|X.XX|XX.X|X..X", "М": "X...X|XX.XX|X.X.X|X...X|X...X",
    "Ш": "X.X.X|X.X.X|X.X.X|X.X.X|XXXXX", "Щ": "X.X.X.|X.X.X.|X.X.X.|XXXXX.|....X.", "Ж": "X.X.X|X.X.X|.XXX.|X.X.X|X.X.X",
    "Ю": "X..X.|X.X.X|XXX.X|X.X.X|X..X.", "Ы": "X...X|X...X|XXX.X|X.X.X|XXX.X", "Д": ".XXX.|.X.X.|.X.X.|XXXXX|X...X",
    "Н": "X..X|X..X|XXXX|X..X|X..X", "Ъ": "XX..|.X..|.XX.|.X.X|.XX.", "Ф": ".X.|XXX|X.X|XXX|.X.",
}


def glyph(ch):
    return FONT.get(ch, FONT[" "]).split("|")


def text(cv, x, y, s, c, gap=1):
    """Writes `s` in the 5-row font (3 columns, wide letters more) with its top-left at (x, y); returns the width used."""
    x0 = x
    for ch in s.upper():
        g = glyph(ch)
        for j, row in enumerate(g):
            for i, v in enumerate(row):
                if v == "X":
                    cv.set(x + i, y + j, c)
        x += len(g[0]) + gap
    return x - x0 - gap


def text_width(s, gap=1):
    return sum(len(glyph(ch)[0]) + gap for ch in s.upper()) - gap
