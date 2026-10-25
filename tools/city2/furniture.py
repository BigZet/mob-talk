"""Interior models of the new city (rebuild spec §4, stage 5): a Soviet flat and its stairwell, made to the player's scale.

    python3 -I tools/city2/furniture.py                  # textures, models, blockstates, items, loot, names, CityFurniture.java
    python3 -I tools/city2/furniture.py preview OUT.png [id ...]   # also a contact sheet: every model from two sides, a player beside it

Scale: 1 block = 1 m, a model pixel = 6.25 cm; a room is 2 blocks high (the city thread's house: floor slab + two rows of air),
so nothing taller than 2 blocks and nothing hanging below 1.85 m (29.6 px). Textures are 32 texels a block (2 a pixel).

A model is drawn once in "north space": its front faces north (low z), its back stands against z = 16, and it may span several
cells (a sofa 1x3, a wall unit 3x2). The exporter cuts it into one model per cell; the first cell (0, 0, 0) is the block the
player places, the others are its invisible-to-the-player parts (io.github.mobtalk.game.FurnitureBlocks). Every model is checked
before it is written: faces lying in one plane over each other (z-fighting), parts thinner than a texel, boxes leaving their cells.
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fur_paint as P  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "mobtalk")
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "mobtalk")
TEXDIR = "block/city2/furniture"
MODDIR = "block/city2/furniture"
FACES = ("north", "south", "east", "west", "up", "down")

MATS = {}   # tileable 32x32 materials: name -> Canvas
TILES = {}  # textures cut from pictures: name -> Canvas
FURN = []   # everything to export, in order


def mat(name, cv):
    """Registers a tileable material (32x32) for boxes that just need a surface."""
    assert cv.w == 32 and cv.h == 32, name
    MATS[name] = cv
    return name


class Pic:
    """A picture painted onto one face of one box, at two texels a pixel: painter(canvas, width_px, height_px)."""
    def __init__(self, name, painter, area=None):
        self.name, self.painter, self.area = name, painter, area  # area: the face rectangle it covers (default: its box's face)
        self.cv = None


# ---------------------------------------------------------------- geometry

def face_rect(face, f, t):
    """The face's rectangle in its own texture space (u to the viewer's right, v down), unbounded model pixels."""
    x0, y0, z0 = f
    x1, y1, z1 = t
    return {"north": (16 - x1, 16 - y1, 16 - x0, 16 - y0), "south": (x0, 16 - y1, x1, 16 - y0),
            "east": (16 - z1, 16 - y1, 16 - z0, 16 - y0), "west": (z0, 16 - y1, z1, 16 - y0),
            "up": (x0, z0, x1, z1), "down": (x0, 16 - z1, x1, 16 - z0)}[face]


class El:
    def __init__(self, f, t, faces, rot=None, shade=True):
        self.f, self.t, self.faces, self.rot, self.shade = [float(v) for v in f], [float(v) for v in t], faces, rot, shade


class Model:
    def __init__(self):
        self.els = []

    def box(self, f, t, m, faces=FACES, rot=None, shade=True, **over):
        """A box with material `m` on `faces`; keyword overrides per face: north=Pic(...) or a material name or None (no face)."""
        fs = {}
        for fc in faces:
            fs[fc] = m
        for fc, v in over.items():
            if v is None:
                fs.pop(fc, None)
            else:
                fs[fc] = v
        self.els.append(El(f, t, fs, rot, shade))
        return self

    def add(self, other, dx=0, dy=0, dz=0):
        for e in other.els:
            r = None
            if e.rot:
                r = dict(e.rot, origin=[e.rot["origin"][0] + dx, e.rot["origin"][1] + dy, e.rot["origin"][2] + dz])
            self.els.append(El([e.f[0] + dx, e.f[1] + dy, e.f[2] + dz], [e.t[0] + dx, e.t[1] + dy, e.t[2] + dz], dict(e.faces), r, e.shade))
        return self


CURTAIN_TIERS = 3  # a flat's clear height: the floor slab plus three blocks of air
OVERHANG = 7  # a ground piece may poke this far into the cell above (a kettle, a tap, jars) without taking that cell


def split(el, ycut=True):
    """Cuts an unrotated box at every cell edge; inner faces of the cut are dropped."""
    if el.rot:
        return [el]
    cuts = []
    for a in range(3):
        pts = [el.f[a]]
        if a == 1 and not ycut:
            cuts.append([el.f[1], el.t[1]])
            continue
        k = math.floor(el.f[a] / 16) * 16 + 16
        while k < el.t[a]:
            pts.append(k)
            k += 16
        pts.append(el.t[a])
        cuts.append(pts)
    out = []
    for i in range(len(cuts[0]) - 1):
        for j in range(len(cuts[1]) - 1):
            for k in range(len(cuts[2]) - 1):
                f = [cuts[0][i], cuts[1][j], cuts[2][k]]
                t = [cuts[0][i + 1], cuts[1][j + 1], cuts[2][k + 1]]
                keep = {}
                for fc, v in el.faces.items():
                    if (fc == "west" and f[0] != el.f[0]) or (fc == "east" and t[0] != el.t[0]) or \
                       (fc == "down" and f[1] != el.f[1]) or (fc == "up" and t[1] != el.t[1]) or \
                       (fc == "north" and f[2] != el.f[2]) or (fc == "south" and t[2] != el.t[2]):
                        continue
                    keep[fc] = v
                sub = El(f, t, keep, None, el.shade)
                sub.parent = el
                out.append(sub)
    return out


def cell_of(el, low=False):
    c = [(el.f[a] + el.t[a]) / 2 for a in range(3)]
    if low:
        c[1] = 0
    return tuple(math.floor(v / 16) for v in c)


def is_low(model):
    """A ground piece that pokes only a little into the cell above keeps to one row, so a kettle or a tap does not claim the
    space of the wall cupboard over it; its model simply reaches up a few pixels."""
    return min(e.f[1] for e in model.els) >= 0 and max(e.t[1] for e in model.els) <= 16 + OVERHANG


def fit(a, b):
    if b - a > 16:
        return 0, 16
    base = math.floor(a / 16) * 16
    a, b = a - base, b - base
    if b > 16:
        a, b = 0, b - a
    return a, b


def resolve(el, face, owner):
    """The texture name and uv of one face: a tileable material by position, or the right tile of a picture."""
    v = el.faces[face]
    src = getattr(el, "parent", el)
    u0, v0, u1, v1 = face_rect(face, el.f, el.t)
    if isinstance(v, Pic):
        R = v.area or face_rect(face, src.f, src.t)
        if v.cv is None:
            W, H = R[2] - R[0], R[3] - R[1]
            v.cv = P.Canvas(max(1, round(W * 2)), max(1, round(H * 2)))
            v.painter(v.cv, W, H)
        cu, cv_ = math.floor((u0 + u1) / 2 / 16), math.floor((v0 + v1) / 2 / 16)
        name = f"{owner}_{v.name}_{cu + 4}{cv_ + 4}"
        if name not in TILES:
            tile = P.Canvas(32, 32)
            for ty in range(32):
                for tx in range(32):
                    uu, vv = cu * 16 + (tx + 0.5) / 2, cv_ * 16 + (ty + 0.5) / 2
                    px, py = int((uu - R[0]) * 2), int((vv - R[1]) * 2)
                    if 0 <= px < v.cv.w and 0 <= py < v.cv.h:
                        tile.px[tx, ty] = v.cv.px[px, py]
            TILES[name] = tile
        return name, [u0 - cu * 16, v0 - cv_ * 16, u1 - cu * 16, v1 - cv_ * 16]
    if el.rot:
        a, b = fit(u0, u1)
        c, d = fit(v0, v1)
        return v, [a, c, b, d]
    bu, bv = math.floor(u0 / 16 + 1e-6) * 16, math.floor(v0 / 16 + 1e-6) * 16
    return v, [u0 - bu, v0 - bv, u1 - bu, v1 - bv]


# ---------------------------------------------------------------- checks

class ModelError(Exception):
    pass


def check(fid, model):
    """Z-fighting (two faces of one direction in one plane over each other), parts under a texel thick, rotation angles."""
    errs = []
    els = model.els
    axis = {"north": (2, 0), "south": (2, 1), "west": (0, 0), "east": (0, 1), "down": (1, 0), "up": (1, 1)}
    for i, e in enumerate(els):
        if e.rot and e.rot["angle"] not in (-45, -22.5, 0, 22.5, 45):
            errs.append(f"element {i}: angle {e.rot['angle']}")
        for a in range(3):
            d = e.t[a] - e.f[a]
            if d < 0:
                errs.append(f"element {i}: inverted on axis {a}")
            elif 0 < d < 0.5 - 1e-6:
                errs.append(f"element {i}: {d:g} px thick on axis {a} (under a texel)")
            if e.f[a] < -16 or e.t[a] > 48:
                errs.append(f"element {i}: outside -16..48 on axis {a}")
    for i in range(len(els)):
        for j in range(i + 1, len(els)):
            a, b = els[i], els[j]
            if a.rot or b.rot:
                continue
            for fc, (ax, side) in axis.items():
                if fc not in a.faces or fc not in b.faces:
                    continue
                pa = a.t[ax] if side else a.f[ax]
                pb = b.t[ax] if side else b.f[ax]
                if abs(pa - pb) > 1e-6:
                    continue
                o = [k for k in range(3) if k != ax]
                ov = all(min(a.t[k], b.t[k]) - max(a.f[k], b.f[k]) > 1e-6 for k in o)
                if ov:
                    errs.append(f"z-fighting: elements {i} and {j}, {fc} faces at {pa:g}")
    if errs:
        raise ModelError(fid + ":\n  " + "\n  ".join(errs[:12]))


# ---------------------------------------------------------------- the furniture list

class Furn:
    """One placeable thing. kind: decor | bed | curtain. mount: GROUND | WALL | CEILING. seat: seat height in pixels (0: none)."""
    def __init__(self, fid, ru, en, build, mount="GROUND", light=0, toggle=False, seat=0, color="OAK_TAN", sound="WOOD",
                 solid=True, cutout=False, kind="decor", item_scale=None):
        self.id, self.ru, self.en, self.build = fid, ru, en, build
        self.mount, self.light, self.toggle, self.seat = mount, light, toggle, seat
        self.color, self.sound, self.solid, self.cutout, self.kind = color, sound, solid, cutout, kind
        self.item_scale = item_scale
        def made(*a):
            m = build(*a)
            return m() if callable(m) else m
        self.model = made(True) if toggle else made()
        self.model_off = made(False) if toggle else None
        FURN.append(self)


def cells_of(model):
    cells = {}
    low = is_low(model)
    for e in model.els:
        for s in split(e):  # cut at y 16 too (so every face maps inside one texture tile), but kept in the ground cell
            cells.setdefault(cell_of(s, low), []).append(s)
    return cells


def element_json(e, owner):
    faces = {}
    for fc in FACES:
        if fc in e.faces:
            name, uv = resolve(e, fc, owner)
            faces[fc] = {"texture": "#" + name, "uv": [round(v, 4) for v in uv]}
    out = {"from": [round(v, 4) for v in e.f], "to": [round(v, 4) for v in e.t], "faces": faces}
    if e.rot:
        out["rotation"] = e.rot
    if not e.shade:
        out["shade"] = False
    return out


def model_json(els, owner, offset=(0, 0, 0), scale=1.0, centre=None):
    """A block model of `els` moved by -offset cells (and for items scaled about `centre`)."""
    elements = []
    textures = {}
    for e in els:
        j = element_json(e, owner)
        for fc in j["faces"].values():
            textures[fc["texture"][1:]] = None
        ox, oy, oz = (o * 16 for o in offset)
        f = [j["from"][0] - ox, j["from"][1] - oy, j["from"][2] - oz]
        t = [j["to"][0] - ox, j["to"][1] - oy, j["to"][2] - oz]
        if scale != 1.0:
            f = [centre[a] + (f[a] - centre[a]) * scale for a in range(3)]
            t = [centre[a] + (t[a] - centre[a]) * scale for a in range(3)]
        j["from"], j["to"] = [round(v, 4) for v in f], [round(v, 4) for v in t]
        if "rotation" in j:
            o = j["rotation"]["origin"]
            o = [o[0] - ox, o[1] - oy, o[2] - oz]
            if scale != 1.0:
                o = [centre[a] + (o[a] - centre[a]) * scale for a in range(3)]
            j["rotation"] = dict(j["rotation"], origin=[round(v, 4) for v in o])
        elements.append(j)
    tex = {k: f"mobtalk:{TEXDIR}/{k}" for k in textures}
    if tex:
        tex["particle"] = next(iter(tex.values()))
    return {"parent": "minecraft:block/block", "textures": tex, "elements": elements}


def outline_boxes(els, cell):
    """The collision and outline boxes of one cell, in its own 0..16 space: the boxes of its pieces (merged when many)."""
    bx = []
    cx, cy, cz = (c * 16 for c in cell)
    for e in els:
        f = [max(0, e.f[0] - cx), max(0, e.f[1] - cy), max(0, e.f[2] - cz)]
        t = [min(16, e.t[0] - cx), min(16, e.t[1] - cy), min(16, e.t[2] - cz)]
        if e.rot:  # a turned piece: its whole cell span
            f, t = [max(0, v) for v in f], [min(16, v) for v in t]
        if all(t[a] - f[a] > 0.4 for a in range(3)):
            bx.append(f + t)
    if not bx:
        return [[0, 0, 0, 16, 1, 16]]
    if len(bx) > 6:
        lo = [min(b[a] for b in bx) for a in range(3)]
        hi = [max(b[a + 3] for b in bx) for a in range(3)]
        return [lo + hi]
    return bx


# ---------------------------------------------------------------- writing

def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2, ensure_ascii=False)
        fh.write("\n")


DISPLAY = {"gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
           "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
           "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
           "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
           "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
           "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]}}
ROT = (("north", 0), ("east", 90), ("south", 180), ("west", 270))


def mref(name):
    return f"mobtalk:{MODDIR}/{name}"


def item_model(fu, model):
    """The whole piece shrunk into one block for the hand and inventory: scaled about its centre, stood on y = 0, centred."""
    lo = [min(e.f[a] for e in model.els) for a in range(3)]
    hi = [max(e.t[a] for e in model.els) for a in range(3)]
    ext = max(hi[a] - lo[a] for a in range(3))
    s = min(1.0, 16 / ext) if fu.item_scale is None else fu.item_scale
    if s == 1.0 and all(lo[a] >= 0 and hi[a] <= 16 for a in range(3)):
        j = model_json(model.els, fu.id)
    else:
        centre = [(lo[a] + hi[a]) / 2 for a in range(3)]
        j = model_json(model.els, fu.id, scale=s, centre=centre)
        shift = [8 - centre[0], -(centre[1] + (lo[1] - centre[1]) * s), 8 - centre[2]]
        for el in j["elements"]:
            el["from"] = [round(el["from"][a] + shift[a], 4) for a in range(3)]
            el["to"] = [round(el["to"][a] + shift[a], 4) for a in range(3)]
            if "rotation" in el:
                el["rotation"]["origin"] = [round(el["rotation"]["origin"][a] + shift[a], 4) for a in range(3)]
    j["display"] = DISPLAY
    return j


def loot(name):
    return {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"mobtalk:{name}"}],
                                                  "conditions": [{"condition": "minecraft:survives_explosion"}]}]}


EXPORT = {}  # id -> dict(cells, boxes) for the Java list


def export_decor(fu):
    for m in (fu.model, fu.model_off):
        if m is not None:
            check(fu.id, m)
    cells = cells_of(fu.model)
    order = sorted(cells, key=lambda c: (c != (0, 0, 0), c[1], c[0], c[2]))
    assert (0, 0, 0) in cells, fu.id + ": nothing in the placed cell"
    names = {}
    for i, c in enumerate(order):
        names[c] = fu.id if i == 0 else f"{fu.id}_p{i}"
        write(os.path.join(ASSETS, "models", MODDIR, names[c] + ".json"), model_json(cells[c], fu.id, c))
        if fu.model_off is not None:
            off = cells_of(fu.model_off)
            write(os.path.join(ASSETS, "models", MODDIR, names[c] + "_off.json"), model_json(off.get(c, []), fu.id, c))
    lit = (lambda on: f",lit={'true' if on else 'false'}") if fu.toggle else (lambda on: "")

    def var(name, y, on):
        n = name if on or not fu.toggle else name + "_off"
        return {"model": mref(n), "y": y} if y else {"model": mref(n)}
    write(os.path.join(ASSETS, "blockstates", fu.id + ".json"), {"variants": {
        f"facing={f}{lit(on)}": var(fu.id, y, on) for f, y in ROT for on in ((True, False) if fu.toggle else (True,))}})
    if len(order) > 1:
        write(os.path.join(ASSETS, "blockstates", fu.id + "_part.json"), {"variants": {
            f"facing={f},part={i}{lit(on)}": var(names[c], y, on) for f, y in ROT
            for i, c in enumerate(order + ([order[1]] if len(order) == 2 else [])) if i  # a two-cell piece: part=2 never happens
            for on in ((True, False) if fu.toggle else (True,))}})
    write(os.path.join(ASSETS, "models", "item", fu.id + ".json"), item_model(fu, fu.model))
    write(os.path.join(DATA, "loot_table", "blocks", fu.id + ".json"), loot(fu.id))
    EXPORT[fu.id] = {"cells": order, "boxes": [outline_boxes(cells[c], c) for c in order]}


def export_bed(fu):
    """A bed is a vanilla-style two-block bed: the foot is the placed cell (0,0,0), the head the cell north of it (0,0,-1)."""
    check(fu.id, fu.model)
    cells = cells_of(fu.model)
    assert set(cells) == {(0, 0, 0), (0, 0, -1)}, (fu.id, list(cells))
    for part, c in (("foot", (0, 0, 0)), ("head", (0, 0, -1))):
        write(os.path.join(ASSETS, "models", MODDIR, f"{fu.id}_{part}.json"), model_json(cells[c], fu.id, c))
    write(os.path.join(ASSETS, "blockstates", fu.id + ".json"), {"variants": {
        f"facing={f},part={p}": ({"model": mref(f"{fu.id}_{p}"), "y": y} if y else {"model": mref(f"{fu.id}_{p}")})
        for f, y in ROT for p in ("head", "foot")}})
    write(os.path.join(ASSETS, "models", "item", fu.id + ".json"), item_model(fu, fu.model))
    write(os.path.join(DATA, "loot_table", "blocks", fu.id + ".json"), {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
        {"type": "minecraft:item", "name": f"mobtalk:{fu.id}"}], "conditions": [
        {"condition": "minecraft:block_state_property", "block": f"mobtalk:{fu.id}", "properties": {"part": "head"}},
        {"condition": "minecraft:survives_explosion"}]}]})
    EXPORT[fu.id] = {"cells": [(0, 0, 0), (0, 0, -1)], "boxes": [outline_boxes(cells[(0, 0, 0)], (0, 0, 0)),
                                                                   outline_boxes(cells[(0, 0, -1)], (0, 0, -1))]}


def export_curtain(fu):
    """A curtain: CURTAIN_TIERS cells high (floor to ceiling), pieces chosen by state (open, edges of the row) through a multipart
    blockstate. fu.model is a dict piece -> Model over all tiers (y 0..16 * CURTAIN_TIERS)."""
    pieces = fu.model
    for k, m in pieces.items():
        check(fu.id + "/" + k, m)
        for c, els in cells_of(m).items():
            write(os.path.join(ASSETS, "models", MODDIR, f"{fu.id}_{k}_t{c[1]}.json"), model_json(els, fu.id, c))
    have = {(k, c[1]) for k, m in pieces.items() for c in cells_of(m)}
    rules = []
    conds = {"rod": {}, "rod_end_l": {"left": "true"}, "rod_end_r": {"right": "true"}, "tulle": {},
             "closed": {"open": "false"}, "open_l": {"open": "true", "left": "true"}, "open_r": {"open": "true", "right": "true"},
             "radiator": {"radiator": "true"}}
    for f, y in ROT:
        for k, cond in conds.items():
            for tier in range(CURTAIN_TIERS):
                if (k, tier) not in have:
                    continue
                when = dict(cond, facing=f, tier=str(tier))
                apply = {"model": mref(f"{fu.id}_{k}_t{tier}")}
                if y:
                    apply["y"] = y
                rules.append({"when": when, "apply": apply})
    write(os.path.join(ASSETS, "blockstates", fu.id + ".json"), {"multipart": rules})
    whole = Model()
    for k in ("rod", "rod_end_l", "rod_end_r", "tulle", "closed"):
        if k in pieces:
            whole.add(pieces[k])
    write(os.path.join(ASSETS, "models", "item", fu.id + ".json"), item_model(fu, whole))
    write(os.path.join(DATA, "loot_table", "blocks", fu.id + ".json"), {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
        {"type": "minecraft:item", "name": f"mobtalk:{fu.id}"}], "conditions": [
        {"condition": "minecraft:block_state_property", "block": f"mobtalk:{fu.id}", "properties": {"tier": "0"}},
        {"condition": "minecraft:survives_explosion"}]}]})
    EXPORT[fu.id] = {"cells": [(0, t, 0) for t in range(CURTAIN_TIERS)], "boxes": [[[0, 0, 11, 16, 16, 16]]] * CURTAIN_TIERS}


def bleed(img):
    """Spread the colours of a tile's painted texels into its unused, fully transparent ones (alpha stays 0), so the game's
    mipmaps don't average in black and darken the edge of a picture seen from afar."""
    px = img.load()
    w, h = img.size
    edge = [(x, y) for y in range(h) for x in range(w) if px[x, y][3] == 0]
    done = {(x, y) for y in range(h) for x in range(w) if px[x, y][3] > 0}
    if not done:
        return
    while edge:
        nxt = []
        fill = {}
        for x, y in edge:
            near = [px[a, b] for a, b in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)) if (a, b) in done]
            if near:
                fill[(x, y)] = tuple(sum(c[i] for c in near) // len(near) for i in range(3)) + (0,)
            else:
                nxt.append((x, y))
        if not fill:
            break
        for p, c in fill.items():
            px[p] = c
            done.add(p)
        edge = nxt


def save_textures():
    tdir = os.path.join(ASSETS, "textures", TEXDIR)
    os.makedirs(tdir, exist_ok=True)
    keep = set()
    for name, cv in list(MATS.items()) + list(TILES.items()):
        if cv.img.mode == "RGBA":
            bleed(cv.img)
        cv.img.save(os.path.join(tdir, name + ".png"))
        keep.add(name + ".png")
    for old in os.listdir(tdir):
        if old not in keep:
            os.remove(os.path.join(tdir, old))


def clean_models():
    d = os.path.join(ASSETS, "models", MODDIR)
    if os.path.isdir(d):
        for old in os.listdir(d):
            os.remove(os.path.join(d, old))


def save_names():
    for lang, i in (("ru_ru", 0), ("en_us", 1)):
        p = os.path.join(ASSETS, "lang", lang + ".json")
        with open(p, encoding="utf-8") as fh:
            d = json.load(fh)
        for fu in FURN:
            d[f"block.mobtalk.{fu.id}"] = (fu.ru, fu.en)[i]
            if len(EXPORT[fu.id]["cells"]) > 1 and fu.kind == "decor":
                d[f"block.mobtalk.{fu.id}_part"] = (fu.ru, fu.en)[i]
        d["entity.mobtalk.seat"] = ("Сиденье", "Seat")[i]
        with open(p, "w", encoding="utf-8") as fh:
            json.dump(d, fh, indent=2, ensure_ascii=False)
            fh.write("\n")
    beds = [f"mobtalk:{fu.id}" for fu in FURN if fu.kind == "bed"]
    write(os.path.join(ROOT, "src", "main", "resources", "data", "minecraft", "tags", "block", "beds.json"), {"replace": False, "values": beds})


def num(v):
    return f"{v:g}"


def write_java():
    rows = []
    for fu in FURN:
        ex = EXPORT[fu.id]
        cells = ", ".join("{" + ", ".join(str(v) for v in c) + "}" for c in ex["cells"])
        boxes = ", ".join("{" + ", ".join("{" + ", ".join(num(v) for v in b) + "}" for b in bs) + "}" for bs in ex["boxes"])
        rows.append(f'            new FurnitureBlocks.F("{fu.id}", FurnitureBlocks.Kind.{fu.kind.upper()}, FurnitureBlocks.Mount.{fu.mount}, '
                    f'new int[][] {{{cells}}},\n                    new double[][][] {{{boxes}}},\n                    '
                    f'{num(fu.seat)}, {str(fu.toggle).lower()}, {fu.light}, MapColor.{fu.color}, BlockSoundGroup.{fu.sound}, '
                    f'{str(fu.solid).lower()}, {str(fu.cutout).lower()})')
    src = ('package io.github.mobtalk.game;\n\nimport java.util.List;\nimport net.minecraft.block.MapColor;\n'
           'import net.minecraft.sound.BlockSoundGroup;\n\n'
           '/** The furniture of the new city\'s flats and stairwells. Generated by tools/city2/furniture.py (models, cells, outlines);\n'
           ' * {@link FurnitureBlocks} registers them. Do not edit by hand. */\n'
           'final class CityFurniture {\n    private CityFurniture() { }\n\n    static final List<FurnitureBlocks.F> ALL = List.of(\n'
           + ",\n".join(rows) + ");\n}\n")
    with open(os.path.join(ROOT, "src", "main", "java", "io", "github", "mobtalk", "game", "CityFurniture.java"), "w", encoding="utf-8") as fh:
        fh.write(src)


# ---------------------------------------------------------------- preview

LIGHT = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}


def corners(face, f, t):
    """Top-left corner, right and down vectors of a face as seen from outside (matching the uv layout)."""
    x0, y0, z0 = f
    x1, y1, z1 = t
    dx, dy, dz = x1 - x0, y1 - y0, z1 - z0
    return {"north": ((x1, y1, z0), (-dx, 0, 0), (0, -dy, 0)), "south": ((x0, y1, z1), (dx, 0, 0), (0, -dy, 0)),
            "east": ((x1, y1, z1), (0, 0, -dz), (0, -dy, 0)), "west": ((x0, y1, z0), (0, 0, dz), (0, -dy, 0)),
            "up": ((x0, y1, z0), (dx, 0, 0), (0, 0, dz)), "down": ((x0, y0, z1), (dx, 0, 0), (0, 0, -dz))}[face]


NORMAL = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}


def rot_point(p, rot):
    if not rot:
        return np.array(p, float)
    o = np.array(rot["origin"], float)
    a = math.radians(rot["angle"])
    x, y, z = np.array(p, float) - o
    c, s = math.cos(a), math.sin(a)
    if rot["axis"] == "x":
        y, z = y * c - z * s, y * s + z * c
    elif rot["axis"] == "y":
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return np.array((x, y, z)) + o


def texture_of(name):
    cv = MATS.get(name) or TILES.get(name)
    return np.asarray(cv.img, dtype=np.uint8)


def render(models, yaw=35, pitch=28, size=320, extent=None):
    """An orthographic look at models [(Model, owner, (dx, dy, dz))], z-buffered, with Minecraft's face shading."""
    img = np.zeros((size, size, 4), np.uint8)
    img[..., :3] = (44, 48, 56)
    img[..., 3] = 255
    zb = np.full((size, size), -1e9)
    cyw, syw = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
    allp = [v for m, _, d in models for e in m.els for v in (np.array(e.f) + d, np.array(e.t) + d)]
    lo, hi = np.min(allp, axis=0), np.max(allp, axis=0)
    mid = (lo + hi) / 2
    ext = extent or max(hi - lo) * 1.15
    scale = size / ext

    def proj(p):
        x, y, z = p - mid
        rx = x * cyw - z * syw
        rz = x * syw + z * cyw
        vy = y * cp - rz * sp
        depth = y * sp + rz * cp
        return np.array((size / 2 + rx * scale, size / 2 - vy * scale, depth))
    view = np.array((syw * cp, sp, cyw * cp))  # towards the viewer, in model space
    for m, owner, d in models:
        d = np.array(d, float)
        for e in [s for e0 in m.els for s in split(e0)]:  # as the game sees it: one picture tile a cell
            for fc in e.faces:
                n = np.array(NORMAL[fc], float)
                if e.rot:
                    n = rot_point(n + np.array(e.rot["origin"]), e.rot) - np.array(e.rot["origin"])
                if np.dot(n, view) <= 1e-6:
                    continue
                name, uv = resolve(e, fc, owner)
                tex = texture_of(name)
                o, r, dn = corners(fc, e.f, e.t)
                O = proj(rot_point(o, e.rot) + d)
                R = proj(rot_point(np.add(o, r), e.rot) + d) - O
                D = proj(rot_point(np.add(o, dn), e.rot) + d) - O
                M = np.array(((R[0], D[0]), (R[1], D[1])))
                if abs(np.linalg.det(M)) < 1e-9:
                    continue
                inv = np.linalg.inv(M)
                xs = [O[0], O[0] + R[0], O[0] + D[0], O[0] + R[0] + D[0]]
                ys = [O[1], O[1] + R[1], O[1] + D[1], O[1] + R[1] + D[1]]
                x0, x1 = max(0, int(min(xs))), min(size - 1, int(max(xs)) + 1)
                y0, y1 = max(0, int(min(ys))), min(size - 1, int(max(ys)) + 1)
                if x1 < x0 or y1 < y0:
                    continue
                gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
                px, py = gx - O[0], gy - O[1]
                a = inv[0, 0] * px + inv[0, 1] * py
                b = inv[1, 0] * px + inv[1, 1] * py
                ins = (a >= 0) & (a <= 1) & (b >= 0) & (b <= 1)
                depth = O[2] + a * R[2] + b * D[2] + 0.002
                u = uv[0] + a * (uv[2] - uv[0])
                v = uv[1] + b * (uv[3] - uv[1])
                th, tw = tex.shape[:2]
                tu = np.clip((u / 16 * tw).astype(int), 0, tw - 1)
                tv = np.clip((v / 16 * th).astype(int), 0, th - 1)
                col = tex[tv, tu]
                ins &= col[..., 3] > 0
                sub = zb[y0:y1 + 1, x0:x1 + 1]
                ins &= depth > sub
                k = LIGHT[fc] if e.shade else 1.0
                region = img[y0:y1 + 1, x0:x1 + 1]
                region[ins, :3] = (col[ins, :3] * k).astype(np.uint8)
                sub[ins] = depth[ins]
    return Image.fromarray(img, "RGBA")


def player_model():
    """A 0.6 x 1.8 m player figure for scale in the previews."""
    m = Model()
    m.box((0, 0, 2), (4, 12, 6), "pv_legs").box((4, 0, 2), (8, 12, 6), "pv_legs")
    m.box((0, 12, 2), (8, 24, 6), "pv_shirt").box((-4, 12, 2), (0, 24, 6), "pv_skin").box((8, 12, 2), (12, 24, 6), "pv_skin")
    m.box((0, 24, 0), (8, 29, 8), "pv_skin").box((0, 28.5, -0.5), (8, 32, 8.5), "pv_hair")
    return m


def preview(out, only=None):
    mat("pv_legs", P.Canvas(32, 32, (52, 60, 140, 255)))
    mat("pv_shirt", P.Canvas(32, 32, (40, 170, 170, 255)))
    mat("pv_skin", P.Canvas(32, 32, (200, 150, 120, 255)))
    mat("pv_hair", P.Canvas(32, 32, (70, 46, 26, 255)))
    pl = player_model()
    items = [fu for fu in FURN if not only or fu.id in only]
    cell = 300
    cols = 3
    sheet = Image.new("RGBA", (cell * 2 * cols, (cell + 18) * ((len(items) + cols - 1) // cols)), (30, 30, 34, 255))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("DejaVuSans.ttf", 12)  # Cyrillic labels; the default bitmap font has none
    except OSError:
        try:
            font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 12)
        except OSError:
            font = None
    for i, fu in enumerate(items):
        if fu.kind == "curtain":
            m = Model()
            for k in ("rod", "rod_end_l", "rod_end_r", "tulle", "closed"):
                if k in fu.model:
                    m.add(fu.model[k])
            m2 = Model()
            for k in ("rod", "rod_end_l", "rod_end_r", "tulle", "open_l", "open_r"):
                if k in fu.model:
                    m2.add(fu.model[k])
            views = [render([(m, fu.id, (0, 0, 0)), (pl, "pv", (-16, 0, -8))], 200, 20, cell),
                     render([(m2, fu.id, (0, 0, 0)), (pl, "pv", (-16, 0, -8))], 200, 20, cell)]
        else:
            lo = min(e.f[0] for e in fu.model.els)
            scene = [(fu.model, fu.id, (0, 0, 0)), (pl, "pv", (lo - 14, 0, 0))]
            if fu.mount == "CEILING":
                scene = [(fu.model, fu.id, (0, 16, 0)), (pl, "pv", (lo - 14, 0, 0))]
            views = [render(scene, 150 + 0, 25, cell), render(scene, 215, 25, cell)]
        x, y = (i % cols) * cell * 2, (i // cols) * (cell + 18)
        for k, v in enumerate(views):
            sheet.paste(v, (x + k * cell, y + 18))
        draw.text((x + 4, y + 3), f"{fu.id}  {fu.ru}", fill=(230, 230, 230, 255), font=font)
    sheet.save(out)


def main():
    sys.modules["furniture"] = sys.modules[__name__]  # fur_models imports this module by name: one copy, not two
    import fur_models
    fur_models.make()
    clean_models()
    bad = []
    for fu in FURN:
        try:
            {"decor": export_decor, "bed": export_bed, "curtain": export_curtain}[fu.kind](fu)
        except ModelError as e:
            bad.append(str(e))
    if bad:
        print("MODEL CHECK FAILED\n" + "\n".join(bad))
        sys.exit(1)
    save_textures()
    save_names()
    write_java()
    if len(sys.argv) > 2 and sys.argv[1] == "preview":
        preview(sys.argv[2], sys.argv[3:])
    print("furniture:", len(FURN), "pieces,", len(MATS), "materials,", len(TILES), "picture tiles")


if __name__ == "__main__":
    main()
