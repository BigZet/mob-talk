"""Soviet yard fences: chain-link mesh on pipe posts («сетка-рабица»), welded sections with a diamond lattice (round schools,
kindergartens and yards) and the concrete PO-2 panel with its diamond relief («забор-ромбик»). Draws the textures in code and
writes the block models, blockstates, item models, loot tables, tags and names. The Java side is io.github.mobtalk.game.Fences
(blocks, connections, posts, gates, the API) and client.game.CityBuildClient (render layer).

    python3 -I tools/city2/fences.py [preview OUT.png]

A fence block is one metre of fence, one block high; stacked blocks make a taller fence (school: 2, kindergarten: 1 or 2,
PO-2: 2). Each block works out by itself which way it joins (like a vanilla fence), whether it is the bottom, middle or top
of a stack (`part`: the top carries the rail or the knuckled mesh edge) and whether it shows a post (`post`): at ends,
corners, beside a gate, where the fence steps up or down, and every `SPACING` blocks along a straight run (by the world
coordinate, so posts line up whoever builds the fence). Gates are two-block leaves hung in the fence line (a wicket), two
of them with opposite hinges make a double gate that opens together.

For templates: line(tpl, ...), gate(tpl, ...) and finish(tpl) write fences into a house.Tpl, see there.
"""
import json
import os
import random
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "mobtalk")
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "mobtalk")
TEXDIR = "city2/fence"          # textures/block/city2/fence, models/block/city2/fence
T = 32                          # texels a block

# kind -> colour -> (ru, en, paint); paint is the main colour of the wire or the paint
KINDS = {
    "chainlink": {
        "galv": ("оцинкованная", "galvanized", (166, 171, 175)),
        "green": ("зелёная", "green", (64, 124, 66)),
    },
    "lattice": {
        "green": ("зелёный", "green", (58, 118, 62)),
        "blue": ("голубой", "blue", (62, 112, 168)),
        "yellow": ("жёлтый", "yellow", (206, 168, 58)),
        "red": ("красный", "red", (160, 58, 46)),
    },
    "concrete": {
        "grey": ("серый", "grey", (160, 158, 151)),
    },
}
SPACING = {"chainlink": 3, "lattice": 3, "concrete": 4}     # posts along a straight run, mirrored in Fences.java
PARTS = ("single", "bottom", "middle", "top")
HAS_TOP = ("single", "top")
HAS_BOTTOM = ("single", "bottom")

TEX = {}
MODELS = {}
BLOCKSTATES = {}
ITEMS = {}
NAMES = {}


def fence_id(kind, colour):
    return "concrete_fence" if kind == "concrete" else f"{kind}_fence_{colour}"


def gate_id(kind, colour):
    return None if kind == "concrete" else f"{kind}_gate_{colour}"


def all_fences():
    return [fence_id(k, c) for k in KINDS for c in KINDS[k]]


def all_gates():
    return [gate_id(k, c) for k in KINDS for c in KINDS[k] if gate_id(k, c)]


# ---------------------------------------------------------------- pixel helpers

def rnd(name):
    return random.Random("fence:" + name)


def img(w=T, h=None, c=(0, 0, 0, 0)):
    return Image.new("RGBA", (w, h or w), c)


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(c, k):
    return tuple(clamp(v * k) for v in c[:3]) + (255,)


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(clamp(a[i] * (1 - t) + b[i] * t) for i in range(3)) + (255,)


def put(im, x, y, c):
    if 0 <= x < im.width and 0 <= y < im.height:
        im.putpixel((int(x), int(y)), tuple(c[:3]) + (255,))


def get(im, x, y):
    return im.getpixel((int(x), int(y)))


RUST = (128, 70, 38)
RUST_DARK = (88, 46, 26)


# ---------------------------------------------------------------- chain-link mesh

def chainlink_mesh(colour, paint, top, bottom):
    """Diamond wire mesh, 8 texels a cell (four cells a metre: real ones are 5 cm, but those melt into grey at this scale).
    The wires run in zigzags and hook into each other at the left and right corners of every cell: those knuckles are drawn
    a texel wider and brighter. A top (bottom) edge cuts the mesh at row 2 (row 29) with the bent-over knuckles of the
    selvage; the tension wire is a model element."""
    r = rnd(f"chain:{colour}:{top}:{bottom}")
    im = img()
    galv = colour == "galv"
    lo, hi = (2 if top else -1), (29 if bottom else T + 1)
    for y in range(T):
        if not lo <= y <= hi:
            continue
        for x in range(T):
            a, b = (x + y - 2) % 8, (x - y + 2) % 8
            if a and b:
                continue
            k = 1.06 if a == 0 else 0.94            # "/" wires catch the light, "\" ones are in shade
            if a == 0 and b == 0:
                k = 1.28                           # a knuckle: the bend shines
            c = shade(paint, k * r.uniform(0.92, 1.07))
            if galv and r.random() < 0.05:
                c = mix(c, (226, 228, 222), 0.6)     # white rust on zinc
            if galv and r.random() < 0.025:
                c = mix(c, RUST, r.uniform(0.4, 0.8))
            if not galv and r.random() < 0.03:
                c = mix(c, (150, 154, 150), 0.7)     # PVC skin rubbed off down to the steel
            put(im, x, y, c)
            if a == 0 and b == 0 and (x + y) % 8 == 2 and lo < y < hi:
                put(im, x + 1, y, shade(paint, 1.0 * r.uniform(0.9, 1.05)))   # the hook of the knuckle
    for edge, row in ((top, lo), (bottom, hi)):
        if not edge:
            continue
        for x in range(T):
            if (x - row + 2) % 8 in (0, 7) or (x + row - 2) % 8 in (0, 1):
                put(im, x, row, shade(paint, 1.2 * r.uniform(0.92, 1.05)))
                if (x + row - 2) % 8 == 0:
                    put(im, x, row + (1 if row == lo else -1), shade(paint, 0.8))
    return im


def pipe_texture(colour, paint, kind):
    """Painted or galvanized steel: uniform paint with streaks, chips and a little rust (the face shading of the box does
    the rest)."""
    r = rnd(f"pipe:{kind}:{colour}")
    im = img()
    galv = colour == "galv"
    streak = [r.uniform(0.94, 1.06) for _ in range(T)]
    for y in range(T):
        for x in range(T):
            c = shade(paint, streak[x] * r.uniform(0.95, 1.05) * (0.92 if galv else 1.0))
            if galv and r.random() < 0.06:
                c = mix(c, (220, 222, 216), 0.5)
            elif not galv and r.random() < 0.025:
                c = mix(c, RUST if r.random() < 0.6 else (90, 90, 88), r.uniform(0.5, 0.9))
            put(im, x, y, c)
    return im


# ---------------------------------------------------------------- welded diamond lattice

def lattice_texture(colour, paint):
    """Flat steel strips welded into diamonds, 16 texels a diamond (two to a metre), each strip two texels wide with a lit
    upper edge and a shaded lower one; weld beads where strips cross; chipped paint shows the old coat and rust."""
    r = rnd(f"lattice:{colour}")
    im = img()
    old = {"green": (64, 104, 150), "blue": (70, 122, 70), "yellow": (66, 118, 66), "red": (70, 118, 66)}[colour]
    chips = set()
    for _ in range(14):
        cx, cy = r.randrange(T), r.randrange(T)
        chips.add((cx, cy))
        if r.random() < 0.5:
            chips.add(((cx + 1) % T, cy))
    for y in range(T):
        for x in range(T):
            a, b = (x + y) % 16, (x - y) % 16
            on_a, on_b = a in (15, 0), b in (0, 1)
            if not (on_a or on_b):
                continue
            if on_a and on_b:
                k = 0.86 if (a, b) == (0, 1) else 1.0           # weld: a darker bead in the middle
            elif on_a:
                k = 1.16 if a == 15 else 0.8
            else:
                k = 1.08 if b == 0 else 0.84
            c = shade(paint, k * r.uniform(0.94, 1.05))
            if (x, y) in chips:
                c = mix(old, RUST, 0.35) if r.random() < 0.5 else shade(RUST, r.uniform(0.8, 1.1))
            put(im, x, y, c)
    return im


# ---------------------------------------------------------------- concrete PO-2

def concrete_base(r, im, box, base, amount=0.06):
    x0, y0, x1, y1 = box
    for y in range(y0, y1):
        for x in range(x0, x1):
            k = 1 + r.uniform(-amount, amount)
            if r.random() < 0.04:
                k *= 0.82                                       # air pores
            put(im, x, y, shade(base, k))


def concrete_panel(paint, top, bottom):
    """The PO-2 face: rows of diamond pyramids 16 texels across with grooves between them, lit from the upper left; a smooth
    band along the top (bottom) edge of the panel; rain streaks under the top, moss and splashed dirt along the foot."""
    r = rnd(f"po2:{top}:{bottom}")
    im = img()
    concrete_base(r, im, (0, 0, T, T), paint)
    for y in range(T):
        for x in range(T):
            a, b = (x + y) % 16, (x - y) % 16
            da, db = min(a, 16 - a), min(b, 16 - b)
            if da == 0 or db == 0:
                k = 0.7
            elif da <= db:
                k = 1.13 if a < 8 else 0.83
            else:
                k = 1.03 if b > 8 else 0.92
            put(im, x, y, shade(get(im, x, y), k))
    if top:
        concrete_base(r, im, (0, 2, T, 6), shade(paint, 1.04), 0.04)
        for x in range(T):
            put(im, x, 2, shade(get(im, x, 2), 1.1))
            put(im, x, 6, shade(get(im, x, 6), 0.8))
    if bottom:
        concrete_base(r, im, (0, 26, T, 30), shade(paint, 0.98), 0.04)
        for x in range(T):
            put(im, x, 25, shade(get(im, x, 25), 0.8))
    # rain streaks from the top edge (fainter on middle blocks: the streak keeps going)
    for _ in range(5 if top else 3):
        sx = r.randrange(T)
        y0 = 2 if top else 0
        length = r.randrange(8, 22) if top else r.randrange(4, 14)
        for y in range(y0, min(T, y0 + length)):
            t = 1 - (y - y0) / length
            put(im, sx, y, shade(get(im, sx, y), 1 - 0.18 * t))
    if bottom:
        for x in range(T):
            for y in range(22, 30):
                t = (y - 22) / 8
                if r.random() < 0.18 + 0.4 * t:
                    c = (86, 102, 58) if r.random() < 0.55 else (104, 90, 70)
                    put(im, x, y, mix(get(im, x, y), c, r.uniform(0.3, 0.7) * t + 0.15))
    return im


def concrete_plain(name, paint, amount=0.05):
    r = rnd(name)
    im = img()
    concrete_base(r, im, (0, 0, T, T), paint, amount)
    for _ in range(6):
        put(im, r.randrange(T), r.randrange(T), shade(paint, 0.75))
    return im


# ---------------------------------------------------------------- item icons

def icon(kind, colour, paint, gate):
    """A 32 x 32 inventory picture: a stretch of fence between two posts, or a gate leaf with its latch."""
    im = img()
    pipe = TEX[f"{kind}_{colour}_pipe"] if kind != "concrete" else TEX["concrete_post"]
    if kind == "chainlink":
        fill = chainlink_mesh(colour, paint, True, True)
    elif kind == "lattice":
        fill = TEX[f"lattice_{colour}"]
    else:
        fill = TEX["concrete_single"]
    y0, y1 = (4, 30)
    for y in range(y0, y1):
        for x in range(3, 29):
            c = fill.getpixel((x, y - y0 + 2 + (0 if kind != "concrete" else 0)))
            if c[3]:
                put(im, x, y, c)
    if gate:
        frame = shade(paint, 1.0)
        for x in range(4, 28):
            put(im, x, 4, shade(frame, 1.15))
            put(im, x, 5, frame)
            put(im, x, 28, frame)
            put(im, x, 29, shade(frame, 0.7))
        for y in range(4, 30):
            for x, k in ((4, 1.15), (5, 0.9), (26, 1.1), (27, 0.75)):
                put(im, x, y, shade(frame, k))
        for y in range(15, 18):
            for x in range(23, 26):
                put(im, x, y, shade((70, 70, 72), 1.2 if y == 15 else 0.9))
    else:
        w = 3 if kind != "concrete" else 4
        for px in (2, 29 - w):
            for y in range(2 if kind != "chainlink" else 3, 31):
                for i in range(w):
                    c = pipe.getpixel((i, y))
                    put(im, px + i, y, shade(c, (1.2, 1.0, 0.85, 0.7)[i]))
        if kind == "lattice":
            for x in range(3, 29):
                put(im, x, 5, shade(paint, 1.15))
                put(im, x, 6, shade(paint, 0.85))
                put(im, x, 27, shade(paint, 1.1))
                put(im, x, 28, shade(paint, 0.8))
    return im


# ---------------------------------------------------------------- models

def face(uv, tex, cull=None):
    f = {"uv": [round(v, 4) for v in uv], "texture": "#" + tex}
    if cull:
        f["cullface"] = cull
    return f


def box(frm, to, tex, faces="nsewud", cull=None):
    """An element with every listed face showing a corner of `tex` the size of the face (a plain pipe or concrete texture)."""
    (x0, y0, z0), (x1, y1, z1) = frm, to
    sx, sy, sz = x1 - x0, y1 - y0, z1 - z0
    fx = lambda w, h: [0, 0, min(16, w), min(16, h)]
    out = {}
    for f, (w, h) in {"n": (sx, sy), "s": (sx, sy), "e": (sz, sy), "w": (sz, sy), "u": (sx, sz), "d": (sx, sz)}.items():
        if f in faces:
            name = {"n": "north", "s": "south", "e": "east", "w": "west", "u": "up", "d": "down"}[f]
            out[name] = face(fx(w, h), tex, (cull or {}).get(f))
    return {"from": [round(v, 4) for v in frm], "to": [round(v, 4) for v in to], "faces": out}


def plane_x(x, y0, y1, z0, z1, tex, u0=0, u1=8):
    """A flat sheet in the x = const plane (an arm running north), seen from east and west."""
    uv = [u0, 16 - y1, u1, 16 - y0]
    return {"from": [x, y0, z0], "to": [x, y1, z1], "shade": True,
            "faces": {"east": face(uv, tex), "west": face([uv[2], uv[1], uv[0], uv[3]], tex)}}


def plane_z(z, y0, y1, x0, x1, tex):
    uv = [x0, 16 - y1, x1, 16 - y0]
    return {"from": [x0, y0, z], "to": [x1, y1, z], "shade": True,
            "faces": {"north": face([uv[2], uv[1], uv[0], uv[3]], tex), "south": face(uv, tex)}}


def model(elements, textures, ao=True):
    m = {"textures": textures, "elements": elements}
    if not ao:
        m["ambientocclusion"] = False
    return m


def make_fence_models(kind, colour):
    """Per part: a post and a north arm (the blockstate turns it to the other sides)."""
    base = f"mobtalk:block/{TEXDIR}/"
    fid = fence_id(kind, colour)
    for part in PARTS:
        top, bottom = part in HAS_TOP, part in HAS_BOTTOM
        if kind == "chainlink":
            pipe = base + f"chainlink_{colour}_pipe"
            mesh = base + f"chainlink_{colour}_mesh_{part}"
            post = [box((7, 0, 7), (9, 16, 9), "pipe", "nsewu" if not top else "nsew")]
            if top:
                post.append(box((6.75, 16, 6.75), (9.25, 16.75, 9.25), "pipe"))         # the cap
                post.append(box((6.5, 14.5, 6.5), (9.5, 15.25, 9.5), "pipe", "nsewud"))  # the wire clamp
            if bottom:
                post.append(box((6.75, 0, 6.75), (9.25, 0.75, 9.25), "pipe", "nsewu"))  # the foot collar
            side = [plane_x(8, 1 if bottom else 0, 15 if top else 16, 0, 8, "mesh")]
            if top:
                side.append(box((7.75, 14.75, 0), (8.25, 15.25, 8), "pipe", "ewud"))
            if bottom:
                side.append(box((7.75, 0.75, 0), (8.25, 1.25, 8), "pipe", "ewud"))
            tex = {"pipe": pipe, "mesh": mesh, "particle": pipe}
            ao = False
        elif kind == "lattice":
            pipe = base + f"lattice_{colour}_pipe"
            lat = base + f"lattice_{colour}"
            post = [box((6.5, 0, 6.5), (9.5, 16, 9.5), "pipe", "nsewu")]
            if top:
                post.append(box((6, 16, 6), (10, 16.5, 10), "pipe"))
            side = [plane_x(8, 2.5 if bottom else 0, 14 if top else 16, 0, 8, "lattice")]
            if top:
                side.append(box((7.25, 14, 0), (8.75, 15.5, 8), "pipe", "ewud"))
            if bottom:
                side.append(box((7.25, 1, 0), (8.75, 2.5, 8), "pipe", "ewud"))
            tex = {"pipe": pipe, "lattice": lat, "particle": pipe}
            ao = False
        else:
            post_t = base + "concrete_post"
            post = [box((5, 0, 5), (11, 16, 11), "post", "nsewu")]
            if top:
                post.append(box((4.5, 16, 4.5), (11.5, 17, 11.5), "post"))
            y1 = 15 if top else 16
            panel = base + f"concrete_{part}"
            side = [{"from": [6.75, 0, 0], "to": [9.25, y1, 8],
                     "faces": {"east": face([0, 16 - y1, 8, 16], "panel"), "west": face([8, 16 - y1, 0, 16], "panel")}}]
            if top:
                side[0]["faces"]["up"] = face([0, 0, 2.5, 8], "top")
            tex = {"post": post_t, "panel": panel, "top": base + "concrete_cap", "particle": post_t}
            ao = True
        MODELS[f"{fid}_post_{part}"] = model(post, tex, ao)
        MODELS[f"{fid}_side_{part}"] = model(side, tex, ao)
    parts = []
    for part in PARTS:
        parts.append({"when": {"part": part, "post": "true"}, "apply": {"model": f"mobtalk:block/{TEXDIR}/{fid}_post_{part}"}})
        for d, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            apply = {"model": f"mobtalk:block/{TEXDIR}/{fid}_side_{part}"}
            if rot:
                apply["y"] = rot
            parts.append({"when": {"part": part, d: "true"}, "apply": apply})
    BLOCKSTATES[fid] = {"multipart": parts}
    ITEMS[fid] = {"parent": "minecraft:item/generated", "textures": {"layer0": f"mobtalk:block/{TEXDIR}/icon_{fid}"}}
    ru, en, _ = KINDS[kind][colour]
    NAMES[fid] = {
        "chainlink": (f"Сетка-рабица ({ru})", f"Chain-link fence ({en})"),
        "lattice": (f"Сварной забор с ромбами ({ru})", f"Welded diamond fence ({en})"),
        "concrete": ("Бетонный забор ПО-2 «ромбик»", "Concrete fence PO-2 (diamonds)"),
    }[kind]


def leaf(u0, u1, y0, y1, t0, t1, hinge, opened):
    """A box of a gate leaf given along the leaf (u from the hinge, 0..16), up (y) and across it (t, around the fence line),
    placed for a gate facing north: hinge left is the west edge, an open leaf swings north (out of the cell, -z)."""
    if not opened:
        xs = (u0, u1) if hinge == "left" else (16 - u1, 16 - u0)
        return (xs[0], y0, 8 + t0), (xs[1], y1, 8 + t1)
    xs = (1 + t0, 1 + t1) if hinge == "left" else (15 - t1, 15 - t0)
    return (xs[0], y0, 8 - u1), (xs[1], y1, 8 - u0)


def make_gate_models(kind, colour):
    base = f"mobtalk:block/{TEXDIR}/"
    gid = gate_id(kind, colour)
    pipe = base + f"{kind}_{colour}_pipe"
    fill = base + (f"chainlink_{colour}_mesh_middle" if kind == "chainlink" else f"lattice_{colour}")
    th = 0.75 if kind == "chainlink" else 0.9
    for half in ("lower", "upper"):
        for hinge in ("left", "right"):
            for opened in (False, True):
                els = []
                lower = half == "lower"
                ys = (1, 16) if lower else (0, 15)
                bars = [(0.5, 2, ys[0], ys[1]), (14, 15.5, ys[0], ys[1])]                      # hinge and latch stiles
                bars.append((2, 14, 1, 2.5) if lower else (2, 14, 13.5, 15))                   # bottom / top rail
                if kind == "lattice":
                    bars.append((2, 14, 15, 16) if lower else (2, 14, 0, 1))                   # the middle rail
                for u0, u1, y0, y1 in bars:
                    frm, to = leaf(u0, u1, y0, y1, -th, th, hinge, opened)
                    els.append(box(frm, to, "pipe"))
                for y0, y1 in ((3, 4.5), ) if lower else ((10.5, 12),):                        # hinge knuckle
                    frm, to = leaf(0, 0.5, y0, y1, -th - 0.25, th + 0.25, hinge, opened)
                    els.append(box(frm, to, "pipe"))
                if lower:                                                                      # the latch: a bolt and its handle
                    frm, to = leaf(12.5, 15.5, 13, 14, -th - 0.75, th + 0.75, hinge, opened)
                    els.append(box(frm, to, "pipe"))
                fy = (2.5, 15 if kind == "lattice" else 16) if lower else (1 if kind == "lattice" else 0, 13.5)
                frm, to = leaf(2, 14, fy[0], fy[1], 0, 0, hinge, opened)
                if not opened:
                    els.append(plane_z(8, fy[0], fy[1], frm[0], to[0], "fill"))
                else:
                    els.append(plane_x(frm[0], fy[0], fy[1], frm[2], to[2], "fill", 2, 14))
                name = f"{gid}_{half}_{hinge}" + ("_open" if opened else "")
                MODELS[name] = model(els, {"pipe": pipe, "fill": fill, "particle": pipe}, False)
    variants = {}
    for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for half in ("lower", "upper"):
            for hinge in ("left", "right"):
                for opened in (False, True):
                    v = {"model": f"mobtalk:block/{TEXDIR}/{gid}_{half}_{hinge}" + ("_open" if opened else "")}
                    if rot:
                        v["y"] = rot
                    variants[f"facing={facing},half={half},hinge={hinge},open={str(opened).lower()}"] = v
    BLOCKSTATES[gid] = {"variants": variants}
    ITEMS[gid] = {"parent": "minecraft:item/generated", "textures": {"layer0": f"mobtalk:block/{TEXDIR}/icon_{gid}"}}
    ru, en, _ = KINDS[kind][colour]
    NAMES[gid] = {
        "chainlink": (f"Калитка из рабицы ({ru})", f"Chain-link gate ({en})"),
        "lattice": (f"Калитка с ромбами ({ru[:-2] + 'ая'})", f"Welded diamond gate ({en})"),
    }[kind]


def make_textures():
    for kind, colours in KINDS.items():
        for colour, (_, _, paint) in colours.items():
            if kind == "chainlink":
                TEX[f"chainlink_{colour}_pipe"] = pipe_texture(colour, shade(paint, 0.9) if colour == "galv" else shade(paint, 0.92), kind)
                for part in PARTS:
                    TEX[f"chainlink_{colour}_mesh_{part}"] = chainlink_mesh(colour, paint, part in HAS_TOP, part in HAS_BOTTOM)
            elif kind == "lattice":
                TEX[f"lattice_{colour}_pipe"] = pipe_texture(colour, paint, kind)
                TEX[f"lattice_{colour}"] = lattice_texture(colour, paint)
            else:
                for part in PARTS:
                    TEX[f"concrete_{part}"] = concrete_panel(paint, part in HAS_TOP, part in HAS_BOTTOM)
                TEX["concrete_post"] = concrete_plain("post", shade(paint, 0.96))
                TEX["concrete_cap"] = concrete_plain("cap", shade(paint, 1.05))
    for kind, colours in KINDS.items():
        for colour, (_, _, paint) in colours.items():
            TEX[f"icon_{fence_id(kind, colour)}"] = icon(kind, colour, paint, False)
            if gate_id(kind, colour):
                TEX[f"icon_{gate_id(kind, colour)}"] = icon(kind, colour, paint, True)


def make_models():
    for kind, colours in KINDS.items():
        for colour in colours:
            make_fence_models(kind, colour)
            if gate_id(kind, colour):
                make_gate_models(kind, colour)


# ---------------------------------------------------------------- templates

def is_fence(name):
    return bool(name) and name.split(":")[-1] in all_fences()


def is_gate(name):
    return bool(name) and name.split(":")[-1] in all_gates()


def line(tpl, x0, z0, x1, z1, y, fid, height=2):
    """An axis-aligned run of fence `fid` from (x0, z0) to (x1, z1) inclusive, `height` blocks high standing on row y.
    Call finish(tpl) once all fences and gates of the template are in."""
    assert fid in all_fences(), fid
    assert x0 == x1 or z0 == z1, "runs are straight"
    for x in range(min(x0, x1), max(x0, x1) + 1):
        for z in range(min(z0, z1), max(z0, z1) + 1):
            if is_gate(tpl.cells.get((x, y, z), ("",))[0]):
                continue
            for h in range(height):
                tpl.set(x, y + h, z, fid)


def gate(tpl, x, y, z, facing, gid, double=False):
    """A wicket (or with double=True a double gate, its second leaf one step to the right seen along `facing`) in a fence
    line, its lower half on row y. facing: the side the leaves swing out to; the fence line runs across it."""
    assert gid in all_gates(), gid
    right = {"north": (1, 0), "east": (0, 1), "south": (-1, 0), "west": (0, -1)}[facing]
    leaves = [((x, z), "left")] + ([((x + right[0], z + right[1]), "right")] if double else [])
    for (lx, lz), hinge in leaves:
        for half, yy in (("lower", y), ("upper", y + 1)):
            tpl.set(lx, yy, lz, gid, {"facing": facing, "half": half, "hinge": hinge, "open": "false", "powered": "false"})


def finish(tpl):
    """Works out every fence cell's joins, part and post the way the game does (Fences.FenceLineBlock.refresh)."""
    def name(x, y, z):
        return tpl.cells.get((x, y, z), ("minecraft:air", {}))[0]

    for (x, y, z), (n, props) in list(tpl.cells.items()):
        if not is_fence(n):
            continue
        fid = n.split(":")[-1]
        kind = fid.split("_")[0]
        joins = {}
        for d, (dx, dz) in (("north", (0, -1)), ("south", (0, 1)), ("east", (1, 0)), ("west", (-1, 0))):
            o = name(x + dx, y, z + dz)
            g = tpl.cells.get((x + dx, y, z + dz), ("", {}))[1].get("facing")
            joins[d] = is_fence(o) or (is_gate(o) and (g in ("north", "south")) == (dx != 0))
        up, down = is_fence(name(x, y + 1, z)), is_fence(name(x, y - 1, z))
        part = ("middle" if down else "bottom") if up else ("top" if down else "single")
        straight_x = joins["east"] and joins["west"] and not joins["north"] and not joins["south"]
        straight_z = joins["north"] and joins["south"] and not joins["east"] and not joins["west"]
        post = not (straight_x or straight_z)
        for d, (dx, dz) in (("north", (0, -1)), ("south", (0, 1)), ("east", (1, 0)), ("west", (-1, 0))):
            if not joins[d]:
                continue
            o = name(x + dx, y, z + dz)
            if is_gate(o) or is_fence(name(x + dx, y + 1, z + dz)) != up or is_fence(name(x + dx, y - 1, z + dz)) != down:
                post = True
        if not post:
            post = (x if straight_x else z) % SPACING[kind] == 0
        props = {d: str(v).lower() for d, v in joins.items()}
        props.update({"part": part, "post": str(post).lower(), "waterlogged": "false"})
        tpl.cells[(x, y, z)] = (n, props)


# ---------------------------------------------------------------- writing

def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1, ensure_ascii=False)
        f.write("\n")


def add_tag(path, values):
    have = json.load(open(path)) if os.path.exists(path) else {"replace": False, "values": []}
    have["values"] = [v for v in have["values"] if v not in values] + values
    write(path, have)


def save_all():
    tdir = os.path.join(ASSETS, "textures", "block", TEXDIR)
    os.makedirs(tdir, exist_ok=True)
    for name, im in TEX.items():
        im.save(os.path.join(tdir, name + ".png"))
    for name, m in MODELS.items():
        write(os.path.join(ASSETS, "models", "block", TEXDIR, name + ".json"), m)
    for name, bs in BLOCKSTATES.items():
        write(os.path.join(ASSETS, "blockstates", name + ".json"), bs)
        write(os.path.join(ASSETS, "models", "item", name + ".json"), ITEMS[name])
        entry = {"type": "minecraft:item", "name": f"mobtalk:{name}"}
        if name in all_gates():
            entry["conditions"] = [{"condition": "minecraft:block_state_property", "block": f"mobtalk:{name}",
                                    "properties": {"half": "lower"}}]
        pools = [{"rolls": 1, "entries": [entry], "conditions": [{"condition": "minecraft:survives_explosion"}]}]
        write(os.path.join(DATA, "loot_table", "blocks", name + ".json"), {"type": "minecraft:block", "pools": pools})
    for lang, i in (("ru_ru", 0), ("en_us", 1)):
        p = os.path.join(ASSETS, "lang", lang + ".json")
        with open(p, encoding="utf-8") as f:
            d = json.load(f)
        for name, titles in NAMES.items():
            d[f"block.mobtalk.{name}"] = titles[i]
        with open(p, "w", encoding="utf-8") as f:
            json.dump(d, f, indent=2, ensure_ascii=False)
            f.write("\n")
    tags = os.path.join(ROOT, "src", "main", "resources", "data", "minecraft", "tags", "block")
    fences = [f"mobtalk:{f}" for f in all_fences()]
    gates = [f"mobtalk:{g}" for g in all_gates()]
    add_tag(os.path.join(tags, "fences.json"), fences)           # mobs path round them as round any fence
    add_tag(os.path.join(tags, "wooden_doors.json"), gates)      # villagers open gates
    add_tag(os.path.join(tags, "mineable", "pickaxe.json"), fences + gates)


def preview(out):
    """A contact sheet of the textures, 4x, for a look without the game."""
    names = list(TEX)
    cell, cols = 136, 8
    sheet = img(cols * cell, ((len(names) + cols - 1) // cols) * cell, (92, 132, 170, 255))
    for i, n in enumerate(names):
        t = TEX[n]
        sheet.alpha_composite(t.resize((128, 128), Image.NEAREST), ((i % cols) * cell + 4, (i // cols) * cell + 4))
    sheet.save(out)


def main():
    make_textures()
    make_models()
    if len(sys.argv) > 2 and sys.argv[1] == "preview":
        preview(sys.argv[2])
    save_all()
    print(f"fences: {len(TEX)} textures, {len(MODELS)} models, {len(BLOCKSTATES)} blocks")


if __name__ == "__main__":
    main()
