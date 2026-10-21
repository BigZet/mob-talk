"""Безликий лик: a living icon. A half-length bishop-saint is painted on a gold ground in the recessed field (ковчег) of a
board: a cinnabar phelonion with dark folds, light ridges and gold assist lines (ассист), a white omophorion with black
crosses, a blessing hand and a jewelled Gospel; but where the face should be there is only a blank, burning gold oval
under a great glowing nimbus with a raised gold crown (венец). The board sits in a heavy chased gold oklad with carved
corners and inset stones, under an ogee kokoshnik crest with an eight-pointed cross. Three red lampadas hang from it
on chains and votive chains with eight-pointed crosses hang at its sides; gold lips and two battens finish the back. The board is two leaves over a core of light, so the
icon can crack open for its ultimate.

Built about 114 units tall and drawn at scale 0.8 (about six blocks with its hover), at 4 pixels per unit."""
from PIL import ImageDraw
from boxtex import *

D = 4  # pixels per unit; the board picture is painted at this density
OLIFA = (92, 64, 30)
FACE_LIGHT = (255, 240, 190)
NIMBUS = (250, 214, 120)
PHELONION = (150, 30, 26)
PHELONION_DARK = (82, 14, 14)
PHELONION_LIGHT = (214, 92, 64)
CHITON = (60, 46, 74)
WHITE = (226, 218, 196)
HAIR = (62, 44, 30)
SKIN = (196, 150, 104)
GEM_RED = (178, 22, 30)
GEM_BLUE = (36, 70, 160)
GEM_GREEN = (30, 120, 70)
LAMP_RED = (190, 30, 30)
HAIR_DARK = (34, 24, 18)
OMOPHOR = (236, 226, 200)
OMOPHOR_SHADE = (200, 184, 150)
FACE_GOLD = (246, 206, 120)

# Board: x -26..26, y -34..34 (icon-local units); the painted field inside the margins is x -22..22, y -30..30.
BW, BH = 52, 68
HEAD = (0, -12)       # centre of the blank face
NIMBUS_C = (0, -13)
NIMBUS_R = 15.0


def px(x, y):
    """Icon-local units to board-picture pixels."""
    return ((x + BW / 2) * D, (y + BH / 2) * D)


def paint_board():
    """The whole board picture, painted once; the two leaves each copy their half."""
    W, H = BW * D, BH * D
    img = Image.new("RGB", (W, H), GOLD)
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    rnd = random.Random(5)
    pix = img.load()
    # Gold ground: warm leaf squares with seams, darker toward the field's edges where the olifa has aged.
    for y in range(H):
        for x in range(W):
            ux, uy = x / D - BW / 2, y / D - BH / 2
            edge = min(ux + 22, 22 - ux, uy + 30, 30 - uy)
            c = mix(GOLD_LIGHT, GOLD, 0.35 + 0.3 * math.sin(x * 0.05 + y * 0.03))
            if x % (6 * D) == 0 or y % (6 * D) == 0:
                c = shade(c, 0.9)
            c = mix(OLIFA, c, max(0.0, min(1.0, 0.5 + edge * 0.12)))
            pix[x, y] = shade(c, 1 + (rnd.random() - 0.5) * 0.08)
    dr = ImageDraw.Draw(img)
    gd = ImageDraw.Draw(glow)

    def P(x, y):
        return px(x, y)

    def poly(points, fill, outline=None, width=1):
        dr.polygon([P(*q) for q in points], fill=fill)
        if outline:
            dr.line([P(*q) for q in points] + [P(*points[0])], fill=outline, width=width)

    def ocross(x, y, h, color, w):
        """An eight-pointed Orthodox cross centred at (x, y), `h` units tall, `w` pixels thick: the upright, the titulus, the
        crossbar and the footrest with its left end (as the viewer sees it) raised. Drawn in crisp rectangles."""
        top = y - h / 2
        X, T = P(x, top)
        _, B = P(x, top + h)
        X = int(round(X))

        def bar(y_units, half):
            x0, yy = P(x - half, y_units)
            x1, _ = P(x + half, y_units)
            dr.rectangle([int(x0), int(yy) - w // 2, int(x1), int(yy) - w // 2 + w - 1], fill=color)
        dr.rectangle([X - w // 2, int(T), X - w // 2 + w - 1, int(B)], fill=color)
        bar(top + h * 0.15, h * 0.17)
        bar(top + h * 0.34, h * 0.36)
        fx0, fy0 = P(x - h * 0.22, top + h * 0.68)
        fx1, fy1 = P(x + h * 0.22, top + h * 0.8)
        dr.polygon([(fx0, fy0 - w / 2), (fx1, fy1 - w / 2), (fx1, fy1 + w / 2), (fx0, fy0 + w / 2)], fill=color)

    # Craquelure first, faint dark-ochre, so the figure, the nimbus and the face are painted over it and stay clean.
    for _ in range(90):
        x, y = rnd.randrange(W), rnd.randrange(H)
        for _ in range(rnd.randrange(4, 10)):
            nx, ny = x + rnd.randint(-3, 3), y + rnd.randint(-3, 3)
            if not (0 <= nx < W and 0 <= ny < H):
                break
            dr.line([(x, y), (nx, ny)], fill=mix(img.getpixel((x, y)), (150, 100, 40), 0.35), width=1)
            x, y = nx, ny
    # The nimbus: a glowing gold disc with tooled rays and a deliberate cinnabar border.
    cx, cy = P(*NIMBUS_C)
    R = NIMBUS_R * D
    for y in range(int(cy - R) - 2, int(cy + R) + 3):
        for x in range(int(cx - R) - 2, int(cx + R) + 3):
            r = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if r <= R:
                ang = math.atan2(y - cy, x - cx)
                c = mix(NIMBUS, GOLD, r / R * 0.7)
                if int((ang + math.pi) / (2 * math.pi) * 36) % 2 == 0 and r > R * 0.45:
                    c = shade(c, 0.93)
                if r > R - 3:
                    pix[x, y] = CINNABAR
                    continue
                pix[x, y] = c
                glow.putpixel((x, y), shade(c, 0.42) + (255,))
    # Dark hair following the head: a rounded crown above the face, falling behind the neck to the shoulders.
    hx, hy = HEAD
    crown = [(hx + 7.0 * math.cos(t), hy - 1.0 - 8.2 * math.sin(t)) for t in [math.pi * k / 16 for k in range(17)]]
    falls = [(hx - 7.0, hy - 1.0), (hx - 7.2, hy + 4), (hx - 6.4, hy + 9), (hx - 4.6, hy + 12.5), (hx - 3.0, hy + 13.5),
             (hx + 3.0, hy + 13.5), (hx + 4.6, hy + 12.5), (hx + 6.4, hy + 9), (hx + 7.2, hy + 4), (hx + 7.0, hy - 1.0)]
    poly(crown + falls[::-1][1:], HAIR_DARK)
    for k in range(-6, 7):
        if k:
            dr.line([P(hx + k * 1.0, hy - 8.4 + abs(k) * 0.35), P(hx + k * 1.15, hy + 11 - abs(k) * 0.4)], fill=shade(HAIR_DARK, 1.45), width=1)
    # The neck runs down from the blank face into the collar, glowing like it.
    poly([(hx - 2.4, hy + 5), (hx + 2.4, hy + 5), (hx + 2.6, hy + 14.5), (hx - 2.6, hy + 14.5)], FACE_GOLD)
    for y in range(int(P(0, hy + 5)[1]), int(P(0, hy + 14.5)[1])):
        for x in range(int(P(hx - 2.6, 0)[0]), int(P(hx + 2.6, 0)[0]) + 1):
            if pix[x, y] == FACE_GOLD:
                glow.putpixel((x, y), shade(FACE_GOLD, 0.7) + (255,))
    # The phelonion: a wide cinnabar bell from the shoulders, beyond the frame at the bottom.
    shoulders = [(-21.5, 30.5), (-21.5, 6), (-19, 1.5), (-12, -0.5), (-5, 0.5), (0, 2.5), (5, 0.5), (12, -0.5), (19, 1.5), (21.5, 6), (21.5, 30.5)]
    poly(shoulders, PHELONION)
    # Its folds: dark grooves with light ridges (пробела) beside them, and gold assist hatching on the lights.
    folds = [(-17, 4, -19, 30), (-13, 3, -14, 30), (-9, 4.5, -9.5, 30), (9, 4.5, 9.5, 30), (13, 3, 14, 30), (17, 4, 19, 30)]
    for (x0, y0, x1, y1) in folds:
        steps = 24
        pts = [(x0 + (x1 - x0) * t / steps + math.sin(t / steps * 3.0) * 0.8, y0 + (y1 - y0) * t / steps) for t in range(steps + 1)]
        dr.line([P(*q) for q in pts], fill=PHELONION_DARK, width=D)
        dr.line([P(q[0] + 1.2, q[1]) for q in pts], fill=PHELONION_LIGHT, width=2)
        for t in range(2, steps, 2):
            q = pts[t]
            dr.line([P(q[0] + 1.6, q[1]), P(q[0] + 3.0, q[1] - 0.9)], fill=GOLD_LIGHT, width=1)
    for s in (-1, 1):
        for k in range(4):
            arc = [(s * (6 + k * 3.4 + t * 0.6), 1.5 + k * 0.3 + t * 1.5 - (t * t) * 0.05) for t in range(9)]
            dr.line([P(*q) for q in arc], fill=PHELONION_DARK, width=2)
            dr.line([P(q[0], q[1] + 0.5) for q in arc], fill=GOLD_LIGHT, width=1)
    # The omophorion: warm off-white wool over both shoulders and down the middle, with soft folds and clean black crosses.
    band = [(-18.5, 3.2), (-11, 1.6), (-3, 3.4), (0, 6.6), (3, 3.4), (11, 1.6), (18.5, 3.2), (18.5, 7.2), (11, 5.6), (3, 8.4),
            (2.8, 30.5), (-2.8, 30.5), (-3, 8.4), (-11, 5.6), (-18.5, 7.2)]
    poly(band, OMOPHOR)
    for (x0, y0, x1, y1) in [(-17, 3.4, -15.5, 6.8), (-8, 2.4, -6.8, 5.6), (8, 2.4, 6.8, 5.6), (17, 3.4, 15.5, 6.8)]:
        dr.line([P(x0, y0), P(x1, y1)], fill=OMOPHOR_SHADE, width=2)
    for y in range(10, 31, 4):
        dr.line([P(-2.6, y), P(2.6, y + 0.8)], fill=OMOPHOR_SHADE, width=2)
    dr.line([P(*q) for q in band] + [P(*band[0])], fill=shade(OMOPHOR_SHADE, 0.8), width=2)
    for (x, y, h) in [(-14, 4.5, 3.6), (14, 4.5, 3.6), (0, 15, 5.2), (0, 24.5, 5.2)]:
        ocross(x, y, h, (24, 18, 16), 3)
    # The blessing hand (the saint's right, the viewer's left): index and middle fingers raised, the thumb touching the
    # folded ring and little fingers.
    bx, by = -9, 13
    outline = (96, 58, 34)
    poly([(bx - 2.0, by + 4.5), (bx - 2.2, by - 0.5), (bx + 2.2, by - 0.5), (bx + 2.0, by + 4.5)], SKIN, outline=outline, width=2)
    poly([(bx - 2.0, by - 0.2), (bx - 2.0, by - 5.2), (bx - 0.9, by - 5.6), (bx - 0.4, by - 0.2)], SKIN, outline=outline, width=2)
    poly([(bx - 0.4, by - 0.2), (bx - 0.3, by - 5.9), (bx + 0.8, by - 6.2), (bx + 1.1, by - 0.2)], SKIN, outline=outline, width=2)
    poly([(bx + 1.0, by - 1.2), (bx + 2.6, by - 1.4), (bx + 2.8, by + 0.4), (bx + 1.2, by + 0.6)], shade(SKIN, 0.85), outline=outline, width=2)
    poly([(bx - 2.1, by + 1.6), (bx + 0.4, by + 0.2), (bx + 1.2, by + 0.8), (bx - 1.6, by + 2.6)], shade(SKIN, 0.92), outline=outline, width=2)
    poly([(bx - 3.2, by + 4.5), (bx + 3, by + 4.5), (bx + 3.6, by + 9.5), (bx - 3.8, by + 9.5)], PHELONION_DARK)
    # The Gospel in the left hand: a gold cover with a cross and stones, red page edges, two clasps, fingers below.
    gx0, gy0, gx1, gy1 = 6, 10, 16, 23
    poly([(gx0, gy0), (gx1, gy0), (gx1, gy1), (gx0, gy1)], GOLD_DARK)
    poly([(gx0 + 0.6, gy0 + 0.6), (gx1 - 0.6, gy0 + 0.6), (gx1 - 0.6, gy1 - 0.6), (gx0 + 0.6, gy1 - 0.6)], GOLD_LIGHT, outline=GOLD_DARK, width=2)
    poly([(gx1 - 0.6, gy0 + 0.6), (gx1 + 0.8, gy0 + 1.2), (gx1 + 0.8, gy1 + 0.6), (gx1 - 0.6, gy1 - 0.6)], CINNABAR)
    for cy0 in (gy0 + 3.5, gy1 - 4.5):
        poly([(gx1 - 0.2, cy0), (gx1 + 1.4, cy0), (gx1 + 1.4, cy0 + 1.0), (gx1 - 0.2, cy0 + 1.0)], GOLD_LIGHT, outline=GOLD_DARK, width=1)
    mx, my = (gx0 + gx1) / 2, (gy0 + gy1) / 2
    ocross(mx, my, 9, CINNABAR, 4)
    for (sx, sy, col) in [(gx0 + 1.8, gy0 + 1.8, GEM_BLUE), (gx1 - 1.8, gy0 + 1.8, GEM_GREEN), (gx0 + 1.8, gy1 - 1.8, GEM_GREEN),
                          (gx1 - 1.8, gy1 - 1.8, GEM_BLUE)]:
        dr.ellipse([P(sx - 0.8, sy - 0.8), P(sx + 0.8, sy + 0.8)], fill=col, outline=GOLD_DARK)
    for k in range(3):
        fx = gx0 + 1.0 + k * 1.3
        poly([(fx, gy1 - 1.2), (fx + 1.1, gy1 - 1.2), (fx + 1.1, gy1 + 1.6), (fx, gy1 + 1.6)], SKIN, outline=outline, width=1)
    # Faded cinnabar lettering either side of the nimbus.
    lr = random.Random(9)
    for (x0, y0) in [(-21, -28), (-21, -24.5), (14.5, -28), (14.5, -24.5)]:
        x = x0
        for _ in range(3):
            for k in range(5):
                if lr.random() < 0.6:
                    dr.line([P(x + lr.choice((0, 0.8, 1.6)), y0 + k * 0.6), P(x + lr.choice((0, 0.8, 1.6)), y0 + k * 0.6 + 0.6)], fill=CINNABAR, width=2)
            dr.line([P(x, y0), P(x + 1.6, y0)], fill=CINNABAR, width=2)
            x += 2.3
        dr.line([P(x0 - 0.4, y0 - 0.9), P(x - 0.6, y0 - 0.9)], fill=CINNABAR, width=1)
    return img, glow


BOARD, BOARD_GLOW = paint_board()


def board_leaf(x_from):
    """A leaf of the board: the front copies its half of the picture; the cut edge glows; the back is dark wood."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        if f.side == "front":
            for v in range(f.h):
                for u in range(f.w):
                    sx = int((x_from + BW / 2) * D + u * D / d)
                    sy = int(v * D / d)
                    sx = min(BOARD.width - 1, sx)
                    sy = min(BOARD.height - 1, sy)
                    g = BOARD_GLOW.getpixel((sx, sy))
                    p.put(f, u, v, BOARD.getpixel((sx, sy)), g[:3] if g[3] else None)
            return
        if f.side in ("left", "right"):
            for v in range(f.h):
                for u in range(f.w):
                    c = shade(CANDLE, p.noise(0.08))
                    p.put(f, u, v, c, c)
            return
        wood = (54, 36, 22)
        for v in range(f.h):
            for u in range(f.w):
                p.put(f, u, v, shade(wood, p.noise(0.1) * (0.9 + 0.1 * math.sin(u * 0.4 + v * 0.03))))
    return paint


def basma(p, f, opts):
    """The margins (поля): thin embossed gold with a running vine and punched dots."""
    d = opts.get("density", 1)
    gold_leaf(ornament=False)(p, f, opts)
    if f.side != "front":
        return
    along = f.w >= f.h
    L, Wd = (f.w, f.h) if along else (f.h, f.w)
    for a in range(L):
        b = int(Wd / 2 + math.sin(a / (2.5 * d)) * Wd * 0.28)
        for k in range(-1, 1):
            u, v = (a, b + k) if along else (b + k, a)
            p.put(f, u, v, GOLD_DARK)
        if a % (5 * d) == 0:
            for t in range(-d, d + 1):
                for s in range(-d // 2, d // 2 + 1):
                    if t * t + s * s * 4 <= d * d:
                        u, v = (a + t, b - d + s) if along else (b - d + s, a + t)
                        p.put(f, u, v, GOLD_LIGHT)
    for a in range(0, L, d):
        for b in (d // 2, Wd - 1 - d // 2):
            u, v = (a, b) if along else (b, a)
            p.put(f, u, v, GOLD_DARK)


def chased(p, f, opts):
    """The oklad: heavy gold chased with scrolls (repoussé), a beaded inner edge, darker sides."""
    d = opts.get("density", 1)
    gold_leaf(ornament=False)(p, f, opts)
    if f.side not in ("front", "top", "bottom", "left", "right", "back"):
        return
    if f.side != "front":
        # The sides are gold too, chased with fine lines and a punched row, so the frame reads as solid metal in profile.
        along = f.w >= f.h
        L, Wd = (f.w, f.h) if along else (f.h, f.w)
        for a in range(L):
            for b in range(Wd):
                u, v = (a, b) if along else (b, a)
                p.put(f, u, v, shade(mix((255, 226, 140), GOLD_LIGHT, 0.5), p.noise(0.05)))
                if b % (2 * d) == 0:
                    p.put(f, u, v, shade(GOLD, 0.85))
                elif b % (2 * d) == d and a % 3 == 0:
                    p.put(f, u, v, GOLD_LIGHT)
        return
    along = f.w >= f.h
    L, Wd = (f.w, f.h) if along else (f.h, f.w)
    period = 6 * d
    for a in range(L):
        for b in range(Wd):
            ph = (a % period) / period * 2 * math.pi
            r = math.hypot((a % period) - period / 2, b - Wd / 2)
            spiral = math.sin(r * 0.9 - math.atan2(b - Wd / 2, (a % period) - period / 2) * 2 + ph)
            u, v = (a, b) if along else (b, a)
            if spiral > 0.75 and r < Wd * 0.48:
                p.put(f, u, v, shade(GOLD_DARK, 1.05))
            elif spiral < -0.85 and r < Wd * 0.48:
                p.put(f, u, v, GOLD_LIGHT)
            if b < d // 2 + 1 or b >= Wd - d // 2 - 1:
                p.put(f, u, v, GOLD_LIGHT if (a // 2) % 2 == 0 else GOLD_DARK)


def gem(color):
    """A cabochon stone: deep colour, darker at the rim, a bright highlight at the upper left, a faint inner glow."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                dx, dy = (u + 0.5) / f.w - 0.5, (v + 0.5) / f.h - 0.5
                r = math.hypot(dx, dy) * 2
                c = shade(color, 1.3 - 0.6 * r)
                if f.side in ("front", "top") and math.hypot(dx + 0.18, dy + 0.18) < 0.14:
                    c = (255, 250, 236)
                p.put(f, u, v, c, shade(color, 0.22) if f.side == "front" else None)
    return paint


def bright_gold(p, f, opts):
    """Polished gold for settings, prongs and the cross: light, warm, a little darker toward the bottom."""
    for v in range(f.h):
        for u in range(f.w):
            t = v / max(1, f.h - 1)
            p.put(f, u, v, shade(mix((255, 228, 140), GOLD, 0.25 + 0.5 * t), p.noise(0.04)))


def wood_back(p, f, opts):
    """Dark old limewood for the battens on the back."""
    for v in range(f.h):
        for u in range(f.w):
            p.put(f, u, v, shade((62, 42, 24), p.noise(0.08) * (0.9 + 0.1 * math.sin(u * 0.5 + v * 0.07))))


def cabochon(name, pos, w, h, color):
    """A stone in a raised gold bezel with four shaded gold prongs."""
    boxes = [
        Box((-w / 2 - 0.35, -h / 2 - 0.35, -0.6), (w + 0.7, h + 0.7, 0.6), bright_gold),
        Box((-w / 2, -h / 2, -1.3), (w, h, 0.7), gem(color)),
        Box((-w / 2 + 0.4, -h / 2 + 0.4, -1.7), (w - 0.8, h - 0.8, 0.4), gem(shade(color, 1.15))),
    ]
    for sx in (-1, 1):
        for sy in (-1, 1):
            boxes.append(Box((sx * (w / 2 - 0.1) - 0.3, sy * (h / 2 - 0.1) - 0.3, -1.4), (0.6, 0.6, 0.8), bright_gold))
    return Part(name, pivot=pos, boxes=boxes)


def face_slab(y0, y1, half):
    """One slab of the blank face: bright gold-white at the centre falling off to gold, no features at all."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                x = (u + 0.5) / f.w * 2 - 1
                y = y0 + (v + 0.5) / f.h * (y1 - y0)
                r = math.hypot(x * half / 5.0, y / 6.6) if f.side == "front" else 1.0
                c = mix((255, 246, 212), (232, 172, 64), min(1.0, r ** 1.6))
                c = shade(c, p.noise(0.03))
                p.put(f, u, v, c, c)
    return paint


def neck_style(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            x = abs((u + 0.5) / f.w * 2 - 1)
            c = shade(mix((250, 220, 150), (226, 160, 60), x * 0.8 + v / max(1, f.h) * 0.3), p.noise(0.03))
            p.put(f, u, v, c, shade(c, 0.8))


def light_core(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            c = mix((255, 255, 240), CANDLE, abs((u + 0.5) / f.w - 0.5) * 1.6)
            p.put(f, u, v, c, c)


def lamp_glass(p, f, opts):
    """Red glass of the lampada, lit from within; the top burns."""
    for v in range(f.h):
        for u in range(f.w):
            if f.side == "top":
                p.put(f, u, v, CANDLE, CANDLE)
                continue
            t = v / max(1, f.h - 1)
            c = shade(mix((236, 80, 60), LAMP_RED, t), p.noise(0.08))
            p.put(f, u, v, c, shade(c, 0.75 - 0.35 * t))


gold_trim = gold_leaf(ornament=False)
chain_gold = solid((206, 160, 70), k=0.12)


def chain_links(prefix, top, length, link=1.6, thick=0.5, width=1.0):
    """Links overlapping by more than a third, alternating their plane, so the chain reads unbroken."""
    parts = []
    step = link * 0.6
    for i in range(int(length / step)):
        parts.append(Part("%s_%d" % (prefix, i), pivot=(0, top + i * step, 0), rotation=(0, (math.pi / 2) * (i % 2), 0),
                          boxes=[Box((-width / 2, 0, -thick / 2), (width, link, thick), chain_gold)]))
    return parts


def octagon(prefix, y, half, height, style, flat=False):
    """A regular octagonal slab of inradius `half`: four bars turned 45 degrees apart."""
    t = half * math.tan(math.pi / 8)
    return [Part("%s_%d" % (prefix, k), pivot=(0, y, 0), rotation=(0, k * math.pi / 4, 0),
                 boxes=[Box((-half, 0, -t), (2 * half, height, 2 * t), style, flat=flat)]) for k in range(4)]


def lampada(name, x, y, chain, k=1.0):
    """A hanging lampada: a chain, three gold stays from a ring to the rim, an octagonal cup of red glass with its flame,
    a gold bowl under it and a dropped finial."""
    top = chain
    stays = [Part("%s_stay_%d" % (name, i), pivot=(0, top, 0), rotation=(0.42, i * 2 * math.pi / 3, 0),
                  boxes=[Box((-0.2, 0, -0.2), (0.4, 3.4 * k, 0.4), chain_gold)]) for i in range(3)]
    cup = top + 3.0 * k
    return Part(name, pivot=(x, y, -0.5), boxes=[
        Box((-0.9, top - 0.6, -0.9), (1.8, 0.8, 1.8), gold_trim),
        Box((-0.25, cup + 0.2, -0.25), (0.5, 1.4 * k, 0.5), fire(), flat=True),
        Box((-0.6, cup + 6.6 * k, -0.6), (1.2, 1.6 * k, 1.2), gold_trim),
        Box((-0.35, cup + 8.2 * k, -0.35), (0.7, 1.2 * k, 0.7), gold_trim),
    ], children=chain_links(name + "_link", 0, chain) + stays
            + octagon(name + "_rim", cup, 2.7 * k, 0.8, gold_trim)
            + octagon(name + "_glass", cup + 0.8, 2.4 * k, 3.4 * k, lamp_glass, flat=True)
            + octagon(name + "_bowl", cup + 0.8 + 3.4 * k, 1.9 * k, 1.6 * k, gold_trim)
            + octagon(name + "_drop", cup + 0.8 + 5.0 * k, 1.1 * k, 0.9 * k, gold_trim))


def votive(name, x, y, side):
    """A votive chain beside the frame: unbroken links, a medallion with a stone, and an eight-pointed gold cross, which swing
    and lag."""
    lower = Part(name + "_lower", pivot=(0, 12.0, 0), boxes=[
        Box((-1.8, 10.0, -0.4), (3.6, 3.6, 0.8), bright_gold),
        Box((-1.0, 10.8, -0.8), (2.0, 2.0, 0.5), gem(GEM_RED if side < 0 else GEM_BLUE)),
        Box((-0.25, 13.6, -0.3), (0.5, 0.8, 0.6), bright_gold),
        Box((-0.55, 14.4, -0.4), (1.1, 9.0, 0.8), bright_gold),
        Box((-1.3, 15.4, -0.4), (2.6, 0.8, 0.8), bright_gold),
        Box((-2.7, 17.0, -0.4), (5.4, 1.1, 0.8), bright_gold),
    ], children=chain_links(name + "_llink", 0, 10.4) + [
        Part(name + "_foot", pivot=(0, 20.8, 0), rotation=(0, 0, 0.35), boxes=[Box((-1.8, -0.4, -0.4), (3.6, 0.8, 0.8), bright_gold)])])
    return Part(name, pivot=(x, y, -1.5), children=chain_links(name + "_link", 0, 12.4) + [lower])


COLOURS = [GEM_RED, GEM_BLUE, GEM_GREEN]


# ---- The board, the margins and the oklad's stiles, split into two leaves over the core of light. ----

def leaf(name, s):
    """One half of the icon (s = -1 is the -X half): board, margins, the stile of the oklad with its mouldings, stones,
    carved corners, the gold lip round the back and a batten across the back."""
    x0 = -BW / 2 if s < 0 else 0
    hinge = s * 30
    o = lambda x, y, z: (x - hinge, y, z)  # boxes are placed in icon space, the leaf pivots on its outer edge
    boxes = [
        Box(o(x0, -34, 0), (BW / 2, BH, 3), board_leaf(x0), flat=True),
        # Margins.
        Box(o(-26 if s < 0 else 0, -34, -1.2), (26, 4, 1.2), basma),
        Box(o(-26 if s < 0 else 0, 30, -1.2), (26, 4, 1.2), basma),
        Box(o(-26 if s < 0 else 22, -30, -1.2), (4, 60, 1.2), basma),
        # The stepped moulding inside the frame.
        Box(o(-26 if s < 0 else 0, -34, -2.2), (26, 1.5, 1.0), gold_trim),
        Box(o(-26 if s < 0 else 0, 32.5, -2.2), (26, 1.5, 1.0), gold_trim),
        Box(o(-26 if s < 0 else 24.5, -32.5, -2.2), (1.5, 65, 1.0), gold_trim),
        # The stile, its raised beads front and back, and the lip round the back.
        Box(o(-30 if s < 0 else 26, -38, -3.5), (4, 76, 7.5), chased),
        Box(o(-30 if s < 0 else 29, -38, -4.2), (1.0, 76, 0.7), bright_gold),
        Box(o(-26.9 if s < 0 else 26, -34.6, -4.0), (0.9, 69.2, 0.5), bright_gold),
        Box(o(-30 if s < 0 else 27.5, -38, 4.0), (2.5, 76, 0.6), bright_gold),
        # Battens (шпонки) across the back.
        Box(o(x0, -22.5, 3.0), (BW / 2, 3, 1.3), wood_back),
        Box(o(x0, 19.5, 3.0), (BW / 2, 3, 1.3), wood_back),
    ]
    children = []
    n = 0
    for y in range(-26, 27, 9):
        children.append(cabochon("%s_side_gem_%d" % (name, n), o(s * 28, y, -3.5), 2.2, 3.0, COLOURS[n % 3]))
        n += 1
    # Carved corners: a diamond plate, a round rosette and a big stone.
    for k, cy in enumerate((-36, 36)):
        corner = Part("%s_corner_%d" % (name, k), pivot=o(s * 28, cy, -3.5), children=[
            Part("%s_corner_plate_%d" % (name, k), rotation=(0, 0, math.pi / 4), boxes=[Box((-4.2, -4.2, -1.2), (8.4, 8.4, 1.2), chased)]),
            cabochon("%s_corner_gem_%d" % (name, k), (0, 0, -2.4), 3.0, 3.0, GEM_RED if cy < 0 else GEM_GREEN),
        ])
        rosette = octagon("%s_rosette_%d" % (name, k), 0, 3.0, 1.0, chased)
        for part in rosette:
            part.pivot, part.rotation = (0, 0, -1.6), (math.pi / 2, 0, part.rotation[1])
        corner.children += rosette
        children.append(corner)
    return Part(name, pivot=(hinge, 0, 0), boxes=boxes, children=children)


def rails():
    """The top and bottom rails of the oklad, each one piece, with beads, the back lip and stones."""
    boxes = []
    for y in (-38, 34):
        boxes += [
            Box((-26, y, -3.5), (52, 4, 7.5), chased),
            Box((-26, y if y < 0 else y + 3, -4.2), (52, 1.0, 0.7), bright_gold),
            Box((-26, y + 3.1 if y < 0 else y, -4.0), (52, 0.9, 0.5), bright_gold),
            Box((-26, y if y < 0 else y + 1.5, 4.0), (52, 2.5, 0.6), bright_gold),
        ]
    children = []
    n = 0
    for x in (-18, -8, 8, 18):
        for y in (-36, 36):
            children.append(cabochon("rail_gem_%d" % n, (x, y, -3.5), 3.0, 2.2, COLOURS[n % 3]))
            n += 1
    return Part("rails", boxes=boxes, children=children)


# The kokoshnik: an ogee arch whose sides swell out and then draw in to a point (half profile, x against height).
OGEE = [(17, 0), (16.8, 2.5), (15.8, 5), (14, 7.5), (11.2, 9.8), (8, 11.4), (5.2, 12.6), (3.2, 14.0), (1.8, 15.8), (0.8, 17.4), (0, 19)]


def ogee_half(h):
    for (x0, h0), (x1, h1) in zip(OGEE, OGEE[1:]):
        if h0 <= h <= h1:
            return x0 + (x1 - x0) * (h - h0) / max(1e-6, h1 - h0)
    return -1


def kokoshnik_fill(p, f, opts):
    """The arch's field: chased gold inside the ogee with a darker inner border, transparent outside it."""
    d = opts.get("density", 1)
    if f.side not in ("front", "back"):
        for v in range(f.h):
            for u in range(f.w):
                p.put(f, u, v, GOLD_DARK)
        return
    for v in range(f.h):
        for u in range(f.w):
            x = (u + 0.5) / d - f.w / d / 2
            h = (f.h - v - 0.5) / d
            half = ogee_half(h)
            if abs(x) > half:
                p.put(f, u, v, None)
                continue
            c = mix(GOLD_LIGHT, GOLD, 0.3 + 0.3 * math.sin(u * 0.4) * math.sin(v * 0.4))
            if half - abs(x) < 1.6 or h < 1.2:
                c = GOLD_DARK
            p.put(f, u, v, shade(c, p.noise(0.05)))


def crest():
    """A kokoshnik crest: the ogee field, a raised gold rim of short rotated pieces following its curve, a medallion with a
    stone, a finial at the point and the eight-pointed cross above."""
    base = -38
    rim = []
    for side in (-1, 1):
        for i, ((x0, h0), (x1, h1)) in enumerate(zip(OGEE, OGEE[1:])):
            mx, mh = side * (x0 + x1) / 2, (h0 + h1) / 2
            length = math.hypot(x1 - x0, h1 - h0)
            angle = math.atan2(-(h1 - h0), side * (x1 - x0))
            rim.append(Part("crest_rim_%d_%d" % (side + 1, i), pivot=(mx, base - mh, -0.2), rotation=(0, 0, angle),
                            boxes=[Box((-length / 2 - 0.35, -0.8, -1.4), (length + 0.7, 1.6, 2.8), bright_gold)]))
    top = base - OGEE[-1][1]
    medallion = cabochon("crest_medallion", (0, base - 7.5, -0.6), 3.6, 3.6, GEM_RED)
    ring = octagon("crest_medallion_ring", 0, 3.2, 0.6, chased)
    for part in ring:
        part.pivot, part.rotation = (0, base - 7.5, -0.6), (math.pi / 2, 0, part.rotation[1])
    cross = Part("crest_cross", pivot=(0, top - 1.2, 0), boxes=[
        Box((-0.9, -15, -0.9), (1.8, 15.6, 1.8), bright_gold),
        Box((-2.4, -13.0, -0.8), (4.8, 1.3, 1.6), bright_gold),
        Box((-5.4, -10.2, -0.8), (10.8, 1.8, 1.6), bright_gold),
        Box((-1.0, -10.4, -1.5), (2.0, 2.2, 0.8), gem(GEM_RED)),
    ], children=[Part("crest_cross_foot", pivot=(0, -4.6, 0), rotation=(0, 0, 0.32), boxes=[Box((-3.2, -0.65, -0.8), (6.4, 1.3, 1.6), bright_gold)])])
    finial = Part("crest_finial", pivot=(0, top, 0), boxes=[Box((-1.4, -1.4, -1.4), (2.8, 1.6, 2.8), bright_gold)])
    return Part("crest", boxes=[Box((-17, base - 19, -0.25), (34, 19, 0.5), kokoshnik_fill)], children=rim + [medallion] + ring + [finial, cross])


def venets():
    """The raised gold crown around the nimbus: plates set on its rim, with stones."""
    part = Part("venets", pivot=(NIMBUS_C[0], NIMBUS_C[1], -0.4))
    n = 22
    for i in range(n):
        a = 2 * math.pi * i / n
        r = NIMBUS_R - 0.7
        boxes = [Box((-2.3, -0.8, -1.0), (4.6, 1.6, 1.0), bright_gold)]
        if i % 2 == 0:
            boxes.append(Box((-0.7, -0.7, -1.7), (1.4, 1.4, 0.7), gem(COLOURS[(i // 2) % 3])))
        part.children.append(Part("venets_%d" % i, pivot=(math.sin(a) * r, -math.cos(a) * r, 0), rotation=(0, 0, a), boxes=boxes))
    return part


def blank_face():
    """The blank face: a smooth oval of burning gold slabs that runs on into a glowing neck down to the collar."""
    part = Part("face", pivot=(HEAD[0], HEAD[1], -0.4))
    rows, a, b = 12, 5.0, 6.6
    for i in range(rows):
        y0 = -b + 2 * b * i / rows
        y1 = y0 + 2 * b / rows
        yc = (y0 + y1) / 2
        half = a * math.sqrt(max(0.05, 1 - (yc / b) ** 2))
        if i >= rows - 2:
            half = max(half, 2.4)  # the chin widens into the neck rather than ending in a flat edge
        part.boxes.append(Box((-half, y0, -1.0), (2 * half, y1 - y0, 1.0), face_slab(y0, y1, half), flat=True))
    part.boxes.append(Box((-2.3, b - 0.4, -0.8), (4.6, 7.6, 0.6), neck_style, flat=True))
    return part


core = Part("core", children=[
    Part("light", boxes=[Box((-3, -33, 0.6), (6, 66, 1.8), light_core, flat=True)]),
    venets(), blank_face(), crest(), rails(),
    lampada("lamp_c", 0, 38, 4.0, 1.0),
    lampada("lamp_r", -18, 38, 2.0, 0.8),
    lampada("lamp_l", 18, 38, 2.0, 0.8),
    votive("votive_r", -32.6, -34, -1),
    votive("votive_l", 32.6, -34, 1),
])

parts = [Part("icon", pivot=(0, -28, 0), children=[core, leaf("leaf_r", -1), leaf("leaf_l", 1)])]

if __name__ == "__main__":
    build("FacelessIcon", "faceless_icon", parts, size=256, seed=21, density=D)
