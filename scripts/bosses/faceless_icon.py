"""Безликий лик: a living icon. A half-length bishop-saint is painted on a gold ground in the recessed field (ковчег) of a
board: a cinnabar phelonion with dark folds, light ridges and gold assist lines (ассист), a white omophorion with black
crosses, a blessing hand and a jewelled Gospel; but where the face should be there is only a blank, burning gold oval
under a great glowing nimbus with a raised gold crown (венец). The board sits in a heavy chased gold oklad with carved
corners, leaves and inset stones, under a kokoshnik crest with an eight-pointed cross. Three red lampadas hang from it
on chains and votive chains with little crosses hang at its sides. The board is two leaves over a core of light, so the
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

    # The nimbus: a glowing disc with tooled rays and a cinnabar double rim.
    cx, cy = P(*NIMBUS_C)
    R = NIMBUS_R * D
    for y in range(int(cy - R) - 2, int(cy + R) + 3):
        for x in range(int(cx - R) - 2, int(cx + R) + 3):
            r = math.hypot(x - cx, y - cy)
            if r <= R:
                ang = math.atan2(y - cy, x - cx)
                c = mix(FACE_LIGHT, NIMBUS, r / R)
                if int((ang + math.pi) / (2 * math.pi) * 36) % 2 == 0 and r > R * 0.45:
                    c = shade(c, 0.93)
                if abs(r - R + 2) < 1.2 or abs(r - R + 6) < 0.8:
                    c = CINNABAR
                pix[x, y] = c
                g = shade(c, 0.85 if r < R - 3 else 0.0)
                if r < R - 3:
                    glow.putpixel((x, y), g + (255,))
    # Hair and a long beard framing the face (the face itself is the raised blank oval).
    hx, hy = HEAD
    poly([(hx - 7.2, hy - 4), (hx - 6, hy - 8.5), (hx - 3, hy - 9.6), (hx + 3, hy - 9.6), (hx + 6, hy - 8.5), (hx + 7.2, hy - 4),
          (hx + 7.4, hy + 3), (hx + 6.6, hy + 9), (hx + 3.6, hy + 13.2), (hx, hy + 14.4), (hx - 3.6, hy + 13.2), (hx - 6.6, hy + 9),
          (hx - 7.4, hy + 3)], HAIR)
    for k in range(-5, 6):
        dr.line([P(hx + k * 1.1, hy + 6), P(hx + k * 0.8, hy + 13.5 - abs(k) * 0.5)], fill=shade(HAIR, 1.4), width=1)
    # Neck and the collar of the podriznik.
    poly([(hx - 2.4, hy + 9), (hx + 2.4, hy + 9), (hx + 2.8, hy + 14), (hx - 2.8, hy + 14)], SKIN)
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
    # Shoulder folds curving over the arms.
    for s in (-1, 1):
        for k in range(4):
            arc = [(s * (6 + k * 3.4 + t * 0.6), 1.5 + k * 0.3 + t * 1.5 - (t * t) * 0.05) for t in range(9)]
            dr.line([P(*q) for q in arc], fill=PHELONION_DARK, width=2)
            dr.line([P(q[0], q[1] + 0.5) for q in arc], fill=GOLD_LIGHT, width=1)
    # The omophorion: a white band over both shoulders, crossing at the chest and hanging down the middle, with black crosses.
    band = [(-18.5, 3.2), (-11, 1.6), (-3, 3.4), (0, 6.6), (3, 3.4), (11, 1.6), (18.5, 3.2), (18.5, 7.2), (11, 5.6), (3, 8.4),
            (2.8, 30.5), (-2.8, 30.5), (-3, 8.4), (-11, 5.6), (-18.5, 7.2)]
    poly(band, WHITE, outline=shade(WHITE, 0.6), width=2)
    for (x, y) in [(-14, 4.2), (14, 4.2), (0, 13), (0, 23)]:
        for (dx0, dy0, dx1, dy1) in [(-1.3, 0, 1.3, 0), (0, -1.3, 0, 1.3)]:
            dr.line([P(x + dx0, y + dy0), P(x + dx1, y + dy1)], fill=(20, 16, 16), width=D)
    for y in range(9, 31, 3):
        dr.line([P(-2.8, y), P(2.8, y)], fill=shade(WHITE, 0.82), width=1)
    # The blessing hand (the saint's right, the viewer's left) raised before the chest.
    bx, by = -9, 13
    poly([(bx - 2.2, by + 5), (bx - 2.6, by), (bx - 1.8, by - 4.6), (bx - 0.6, by - 5.6), (bx + 0.6, by - 4.8), (bx + 1.8, by - 3),
          (bx + 2.4, by + 1), (bx + 2.0, by + 5)], SKIN, outline=(90, 52, 30), width=2)
    for k in range(3):
        dr.line([P(bx - 1.6 + k * 1.1, by - 1), P(bx - 1.4 + k * 1.1, by - 4.6 + abs(k - 1) * 0.6)], fill=(110, 66, 40), width=1)
    poly([(bx - 3.2, by + 5), (bx + 3, by + 5), (bx + 3.6, by + 9.5), (bx - 3.8, by + 9.5)], PHELONION_DARK)
    # The Gospel in the left hand: a gold cover with a cross and stones, red page edges, a hand veiled beneath.
    gx0, gy0, gx1, gy1 = 6, 10, 16, 23
    poly([(gx0, gy0), (gx1, gy0), (gx1, gy1), (gx0, gy1)], GOLD_DARK)
    poly([(gx0 + 0.6, gy0 + 0.6), (gx1 - 0.6, gy0 + 0.6), (gx1 - 0.6, gy1 - 0.6), (gx0 + 0.6, gy1 - 0.6)], GOLD_LIGHT, outline=GOLD_DARK, width=2)
    poly([(gx1 - 0.6, gy0 + 0.6), (gx1 + 0.8, gy0 + 1.2), (gx1 + 0.8, gy1 + 0.6), (gx1 - 0.6, gy1 - 0.6)], CINNABAR)
    mx, my = (gx0 + gx1) / 2, (gy0 + gy1) / 2
    dr.line([P(mx, gy0 + 2), P(mx, gy1 - 2)], fill=CINNABAR, width=D)
    dr.line([P(mx - 2.6, my - 1.6), P(mx + 2.6, my - 1.6)], fill=CINNABAR, width=D)
    for (sx, sy, col) in [(gx0 + 1.8, gy0 + 1.8, GEM_BLUE), (gx1 - 1.8, gy0 + 1.8, GEM_GREEN), (gx0 + 1.8, gy1 - 1.8, GEM_GREEN),
                          (gx1 - 1.8, gy1 - 1.8, GEM_BLUE), (mx, my - 1.6, GEM_RED)]:
        dr.ellipse([P(sx - 0.8, sy - 0.8), P(sx + 0.8, sy + 0.8)], fill=col, outline=GOLD_DARK)
    poly([(gx0 - 1.5, gy1 - 1), (gx1 + 1.5, gy1 - 1), (gx1 + 2, gy1 + 4), (gx0 - 2, gy1 + 4)], WHITE, outline=shade(WHITE, 0.6), width=2)
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
    # Craquelure: the old paint is crazed.
    for _ in range(70):
        x, y = rnd.randrange(W), rnd.randrange(H)
        for _ in range(rnd.randrange(6, 18)):
            nx, ny = x + rnd.randint(-3, 3), y + rnd.randint(-3, 3)
            if not (0 <= nx < W and 0 <= ny < H):
                break
            dr.line([(x, y), (nx, ny)], fill=shade(img.getpixel((x, y)), 0.84), width=1)
            x, y = nx, ny
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
        if f.side == "back":
            for row in (f.h // 5, f.h - f.h // 5):
                for u in range(f.w):
                    for k in range(d):
                        p.put(f, u, row + k, shade((36, 24, 14), p.noise(0.05)))
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
        for v in range(f.h):
            for u in range(f.w):
                if (u + v) % 3 == 0:
                    p.put(f, u, v, shade(GOLD_DARK, p.noise(0.1)))
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
    """A cabochon: deep colour, a dark rim, a bright highlight at the upper left."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                dx, dy = (u + 0.5) / f.w - 0.5, (v + 0.5) / f.h - 0.5
                r = math.hypot(dx, dy) * 2
                c = shade(color, 1.25 - 0.6 * r)
                if f.side in ("front", "top") and math.hypot(dx + 0.18, dy + 0.18) < 0.13:
                    c = (255, 250, 236)
                p.put(f, u, v, c, shade(color, 0.25) if f.side == "front" else None)
    return paint


def pearl(p, f, opts):
    for v in range(f.h):
        for u in range(f.w):
            p.put(f, u, v, shade((236, 230, 214), p.noise(0.05) * (1.1 - 0.3 * v / max(1, f.h))))


def face_slab(y0, y1, half):
    """One slab of the blank face: burning gold, brightest at the centre of the oval, no features at all."""
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                x = (u + 0.5) / f.w * 2 - 1
                y = y0 + (v + 0.5) / f.h * (y1 - y0)
                if f.side == "front":
                    r = math.hypot(x * half / 5.0, y / 6.6)
                    c = mix((255, 252, 230), FACE_LIGHT, min(1.0, r))
                    c = mix(c, (236, 176, 70), max(0.0, r - 0.75) * 3)
                else:
                    c = (236, 176, 70)
                c = shade(c, p.noise(0.03))
                p.put(f, u, v, c, c)
    return paint


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
chain_gold = solid(GOLD_DARK, k=0.18)


def chain_links(prefix, top, length, link=1.6, thick=0.4, width=0.9):
    parts = []
    for i in range(int(length / (link * 0.8))):
        parts.append(Part("%s_%d" % (prefix, i), pivot=(0, top + i * link * 0.8, 0), rotation=(0, (math.pi / 2) * (i % 2), 0),
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
    """A votive chain at the frame's side: links, a medallion and a little gold cross that swing and lag."""
    lower = Part(name + "_lower", pivot=(0, 12.4, 0), boxes=[
        Box((-1.6, 10.2, -0.4), (3.2, 3.2, 0.8), gold_trim),
        Box((-0.9, 10.9, -0.7), (1.8, 1.8, 0.4), gem(GEM_RED if side < 0 else GEM_BLUE)),
        Box((-0.35, 13.4, -0.3), (0.7, 5.6, 0.6), gold_trim),
        Box((-1.6, 14.8, -0.3), (3.2, 0.7, 0.6), gold_trim),
        Box((-0.9, 13.9, -0.3), (1.8, 0.5, 0.6), gold_trim),
    ], children=chain_links(name + "_llink", 0, 10.4))
    return Part(name, pivot=(x, y, -1.5), children=chain_links(name + "_link", 0, 12.6) + [lower])


# ---- The board, the margins and the oklad, split into two leaves over the core of light. ----

def leaf(name, s):
    """One half of the icon (s = -1 is the -X half): board, margins, oklad and its carved corners and stones."""
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
        # The oklad.
        Box(o(-30 if s < 0 else 26, -38, -3.5), (4, 76, 7), chased),
        Box(o(-26 if s < 0 else 0, -38, -3.5), (26, 4, 7), chased),
        Box(o(-26 if s < 0 else 0, 34, -3.5), (26, 4, 7), chased),
    ]
    children = []
    # Stones and pearls along the frame.
    colours = [GEM_RED, GEM_BLUE, GEM_GREEN]
    n = 0
    for y in range(-26, 27, 9):
        children.append(Part("%s_side_gem_%d" % (name, n), pivot=o(s * 28, y, -3.5), boxes=[
            Box((-1.1, -1.5, -1.0), (2.2, 3.0, 1.0), gem(colours[n % 3])),
            Box((-0.5, -2.9, -0.7), (1.0, 1.0, 0.7), pearl),
            Box((-0.5, 1.9, -0.7), (1.0, 1.0, 0.7), pearl),
        ]))
        n += 1
    for x in (8, 18):
        for y in (-36, 36):
            children.append(Part("%s_rail_gem_%d" % (name, n), pivot=o(s * x, y, -3.5), boxes=[
                Box((-1.5, -1.1, -1.0), (3.0, 2.2, 1.0), gem(colours[n % 3])),
                Box((-2.9, -0.5, -0.7), (1.0, 1.0, 0.7), pearl),
                Box((1.9, -0.5, -0.7), (1.0, 1.0, 0.7), pearl),
            ]))
            n += 1
    # Carved corners: a diamond plate with a big stone and four pearls, and acanthus leaves breaking the outline.
    for cy in (-36, 36):
        cx = s * 28
        corner = Part("%s_corner_%d" % (name, 0 if cy < 0 else 1), pivot=o(cx, cy, -3.5), children=[
            Part("%s_corner_plate_%d" % (name, 0 if cy < 0 else 1), rotation=(0, 0, math.pi / 4), boxes=[Box((-4.2, -4.2, -1.2), (8.4, 8.4, 1.2), chased)]),
        ], boxes=[
            Box((-1.6, -1.6, -2.4), (3.2, 3.2, 1.2), gem(GEM_RED if cy < 0 else GEM_GREEN)),
            Box((-3.6, -0.5, -1.9), (1.0, 1.0, 0.7), pearl), Box((2.6, -0.5, -1.9), (1.0, 1.0, 0.7), pearl),
            Box((-0.5, -3.6, -1.9), (1.0, 1.0, 0.7), pearl), Box((-0.5, 2.6, -1.9), (1.0, 1.0, 0.7), pearl),
        ])
        out = math.atan2(cy, cx)  # pointing away from the icon's centre
        for k, da in enumerate((-0.55, 0, 0.55)):
            a = out + da
            corner.children.append(Part("%s_acanthus_%d_%d" % (name, 0 if cy < 0 else 1, k), pivot=(math.cos(a) * 3.5, math.sin(a) * 3.5, -0.6),
                                        rotation=(0, 0, a - math.pi / 2 + (0.25 if k != 1 else 0) * (1 if da > 0 else -1)),
                                        boxes=[Box((-1.2, 0, -0.5), (2.4, 5.5 - abs(da) * 2, 1.0), gold_leaf(ornament=False))],
                                        children=[Part("%s_acanthus_tip_%d_%d" % (name, 0 if cy < 0 else 1, k), pivot=(0, 5.2 - abs(da) * 2, 0),
                                                       rotation=(0, 0, 0.5 * (1 if k != 0 else -1)), boxes=[Box((-0.7, 0, -0.4), (1.4, 2.4, 0.8), gold_trim)])]))
        children.append(corner)
    return Part(name, pivot=(hinge, 0, 0), boxes=boxes, children=children)


def crest():
    """A kokoshnik crest of stepped gold arches with a medallion, under an eight-pointed cross."""
    tiers = [(34, 3.0), (28, 2.8), (22, 2.6), (15, 2.4), (8, 2.2)]
    boxes, y = [], -38
    for w, h in tiers:
        boxes.append(Box((-w / 2, y - h, -2.5), (w, h, 5), chased if w > 12 else gold_trim))
        y -= h
    # Scroll ends of the lowest tier.
    scrolls = [Part("crest_scroll_%d" % (s + 1), pivot=(s * 17, -39.5, -2.0), rotation=(0, 0, s * 0.7),
                    boxes=[Box((-1.2, -5, -0.6), (2.4, 5, 1.2), gold_leaf(ornament=False))]) for s in (-1, 1)]
    medallion = Part("crest_medallion", pivot=(0, -44, -2.5), boxes=[
        Box((-2.2, -2.2, -1.4), (4.4, 4.4, 1.4), gem(GEM_RED)),
        Box((-3.2, -3.2, -0.6), (6.4, 6.4, 0.6), gold_trim),
    ])
    top = y
    cross = Part("crest_cross", pivot=(0, top, 0), boxes=[
        Box((-0.9, -15, -0.9), (1.8, 15, 1.8), gold_trim),
        Box((-2.4, -13.2, -0.8), (4.8, 1.3, 1.6), gold_trim),
        Box((-5.4, -10.4, -0.8), (10.8, 1.8, 1.6), gold_trim),
        Box((-1.0, -10.6, -1.5), (2.0, 2.2, 0.8), gem(GEM_RED)),
        Box((-6.2, -10.7, -1.0), (1.2, 2.4, 2.0), gold_trim),
        Box((5.0, -10.7, -1.0), (1.2, 2.4, 2.0), gold_trim),
        Box((-1.0, -16.0, -1.0), (2.0, 1.2, 2.0), gold_trim),
    ], children=[Part("crest_cross_foot", pivot=(0, -4.6, 0), rotation=(0, 0, 0.32), boxes=[Box((-3.2, -0.65, -0.8), (6.4, 1.3, 1.6), gold_trim)])])
    return Part("crest", boxes=boxes, children=scrolls + [medallion, cross])


def venets():
    """The raised gold crown around the nimbus: plates set on its rim, with stones."""
    part = Part("venets", pivot=(NIMBUS_C[0], NIMBUS_C[1], -0.4))
    n = 22
    for i in range(n):
        a = 2 * math.pi * i / n
        r = NIMBUS_R - 0.7
        boxes = [Box((-2.3, -0.8, -1.0), (4.6, 1.6, 1.0), gold_leaf(ornament=False))]
        if i % 2 == 0:
            boxes.append(Box((-0.7, -0.7, -1.7), (1.4, 1.4, 0.7), gem([GEM_RED, GEM_BLUE, GEM_GREEN][(i // 2) % 3])))
        part.children.append(Part("venets_%d" % i, pivot=(math.sin(a) * r, -math.cos(a) * r, 0), rotation=(0, 0, a), boxes=boxes))
    return part


def blank_face():
    """The blank face: an oval stepped out of slabs that burn with gold light."""
    part = Part("face", pivot=(HEAD[0], HEAD[1], -0.4))
    rows, a, b = 8, 5.0, 6.6
    for i in range(rows):
        y0 = -b + 2 * b * i / rows
        y1 = y0 + 2 * b / rows
        yc = (y0 + y1) / 2
        half = a * math.sqrt(max(0.05, 1 - (yc / b) ** 2))
        part.boxes.append(Box((-half, y0, -1.0), (2 * half, y1 - y0, 1.0), face_slab(y0, y1, half), flat=True))
    return part


core = Part("core", children=[
    Part("light", boxes=[Box((-3, -33, 0.6), (6, 66, 1.8), light_core, flat=True)]),
    venets(), blank_face(), crest(),
    lampada("lamp_c", 0, 38, 4.0, 1.0),
    lampada("lamp_r", -18, 38, 2.0, 0.8),
    lampada("lamp_l", 18, 38, 2.0, 0.8),
    votive("votive_r", -31.2, -34, -1),
    votive("votive_l", 31.2, -34, 1),
])

parts = [Part("icon", pivot=(0, -28, 0), children=[core, leaf("leaf_r", -1), leaf("leaf_l", 1)])]

if __name__ == "__main__":
    build("FacelessIcon", "faceless_icon", parts, size=256, seed=21, density=D)
