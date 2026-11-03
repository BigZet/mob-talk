"""Finish variants of the flats' furniture (wood, upholstery, enamel, oilcloth, blankets, pictures): the same models, the same
cells and the same bounding boxes as their base piece, only the paint differs, so a flat does not look like its neighbour.

A variant is its base piece's model with the pixels that match a rule (a hue range, optionally restricted to named materials or
pictures) moved to a new colour; the shading of every texel is kept. Pieces are named <base>__<tag>. make() writes
variants.json: for every base piece, per family (wood, fabric, ...), tag -> piece id; rooms.py picks one finish per family and
flat, so a flat stays of one piece while the next one differs.
"""

import colorsys
import json
import math
import os
import random

import fur_paint as P
from furniture import FURN, MATS, Furn, Pic, face_rect

HERE = os.path.dirname(os.path.abspath(__file__))

WOOD = dict(lo=14, hi=38, smin=0.3)
WOODS = {
    "birch": ((40, 0.34, 0.80), "берёза", "birch"),
    "mahogany": ((9, 0.62, 0.40), "красное дерево", "mahogany"),
    "wenge": ((20, 0.40, 0.24), "венге", "wenge"),
}
FABRICS = {
    "mustard": ((42, 0.72, 0.62), "горчичный", "mustard"),
    "teal": ((178, 0.5, 0.42), "бирюзовый", "teal"),
    "plum": ((300, 0.38, 0.38), "сливовый", "plum"),
    "grey": ((215, 0.10, 0.52), "серый", "grey"),
    "rust": ((16, 0.72, 0.58), "рыжий", "rust"),
}
KITCHEN = {
    "mint": ((150, 0.22, 0.86), "мятный", "mint"),
    "sky": ((200, 0.28, 0.88), "голубой", "sky blue"),
    "butter": ((48, 0.38, 0.92), "кремово-жёлтый", "butter yellow"),
    "grey": ((215, 0.07, 0.68), "серый", "grey"),
}
ENAMEL = {
    "rose": ((350, 0.12, 0.93), "розовый", "rose"),
    "mint": ((150, 0.15, 0.90), "мятный", "mint"),
    "sky": ((200, 0.18, 0.92), "голубой", "sky blue"),
    "ivory": ((45, 0.22, 0.90), "кремовый", "ivory"),
}
CLOTH = {
    "green": ((130, 0.50, 0.55), "зелёная клеёнка", "green oilcloth"),
    "blue": ((215, 0.55, 0.65), "синяя клеёнка", "blue oilcloth"),
    "yellow": ((46, 0.70, 0.85), "жёлтая клеёнка", "yellow oilcloth"),
}
BLANKET = {
    "blue": ((215, 0.50, 0.50), "синее одеяло", "blue blanket"),
    "green": ((110, 0.40, 0.42), "зелёное одеяло", "green blanket"),
    "grey": ((210, 0.08, 0.55), "серое одеяло", "grey blanket"),
    "purple": ((280, 0.35, 0.40), "лиловое одеяло", "purple blanket"),
}
SPREAD = {
    "rose": ((350, 0.20, 0.90), "розовое покрывало", "rose bedspread"),
    "blue": ((210, 0.22, 0.88), "голубое покрывало", "blue bedspread"),
    "mint": ((150, 0.18, 0.88), "мятное покрывало", "mint bedspread"),
}
STOOL = {
    "white": ((40, 0.06, 0.92), "белый", "white"),
    "red": ((4, 0.72, 0.62), "красный", "red"),
    "green": ((145, 0.45, 0.50), "зелёный", "green"),
    "blue": ((210, 0.50, 0.55), "синий", "blue"),
}
KITWOOD = {
    "oak": ((27, 0.62, 0.50), "дуб", "oak"),
    "whitewash": ((35, 0.18, 0.90), "беленый дуб", "whitewashed"),
}
HUES = {
    "hue1": (100, "другие тона", "other tones"),
    "hue2": (215, "холодные тона", "cool tones"),
}

FAMILIES = {}  # family -> [tags]
BASES = {}  # base id -> family -> tag -> variant id
ALIASES = {}  # base id -> the base whose variants it shares


def _hsv(c):
    h, s, v = colorsys.rgb_to_hsv(c[0] / 255, c[1] / 255, c[2] / 255)
    return h * 360, s, v


def _match(c, r):
    if c[3] == 0:
        return False
    h, s, v = _hsv(c)
    if not (r.get("smin", 0) <= s <= r.get("smax", 1) and v >= r.get("vmin", 0)):
        return False
    lo, hi = r.get("lo", 0), r.get("hi", 360)
    return lo <= h <= hi if lo <= hi else (h >= lo or h <= hi)


def _mean(cvs, r):
    n = sx = sy = ss = sv = 0
    for cv in cvs:
        for y in range(cv.h):
            for x in range(cv.w):
                c = cv.px[x, y]
                if _match(c, r):
                    h, s, v = _hsv(c)
                    n += 1
                    sx += math.cos(math.radians(h))
                    sy += math.sin(math.radians(h))
                    ss += s
                    sv += v
    if not n:
        return None
    return math.degrees(math.atan2(sy, sx)) % 360, ss / n, sv / n


def _apply(cv, r, mean):
    for y in range(cv.h):
        for x in range(cv.w):
            c = cv.px[x, y]
            if not _match(c, r):
                continue
            h, s, v = _hsv(c)
            if "rot" in r:
                h += r["rot"]
            else:
                th, ts, tv = r["to"]
                mh, ms, mv = mean
                if ms < 0.15:
                    h, s = th, max(0, min(1, ts + (s - ms)))
                else:
                    h, s = h + (th - mh), max(0, min(1, s + (ts - ms)))
                v = min(1, v * tv / mv)
            rr, g, b = colorsys.hsv_to_rgb((h % 360) / 360, s, v)
            cv.px[x, y] = (round(rr * 255), round(g * 255), round(b * 255), c[3])


def _copy(cv):
    n = P.Canvas(cv.w, cv.h)
    n.img = cv.img.copy()
    n.px = n.img.load()
    return n


def _paint(pic, face, el):
    if pic.cv is None:
        R = pic.area or face_rect(face, el.f, el.t)
        W, H = R[2] - R[0], R[3] - R[1]
        pic.cv = P.Canvas(max(1, round(W * 2)), max(1, round(H * 2)))
        pic.painter(pic.cv, W, H)
    return pic.cv


def _models(m):
    return list(m.values()) if isinstance(m, dict) else [m]


def recolor(model, vid, rules):
    """A copy of `model` (or of every part of a dict of models) with the rules applied."""
    parts = _models(model)
    names = {}  # name -> (canvas, owner) of every face the rules may touch
    for part in parts:
        for e in part.els:
            for fc, v in e.faces.items():
                if isinstance(v, str):
                    names[v] = MATS[v]
                else:
                    names[id(v)] = (v, fc, e)
    flat = {}
    for key, v in names.items():
        if isinstance(v, tuple):
            pic, fc, e = v
            flat[key] = (pic.name, _paint(pic, fc, e))
        else:
            flat[key] = (key, v)
    work = {k: _copy(cv) for k, (_, cv) in flat.items()}
    for r in rules:
        sel = [
            k
            for k, (n, _) in flat.items()
            if (not r.get("only") or n in r["only"]) and n not in r.get("skip", ())
        ]
        mean = None
        if "to" in r:
            mean = _mean([work[k] for k in sel], r)
            assert mean, f"{vid}: rule {r} matches nothing"
        for k in sel:
            _apply(work[k], r, mean)
    changed = {}
    for k, cv in work.items():
        if any(
            cv.px[x, y] != flat[k][1].px[x, y] for x in range(cv.w) for y in range(cv.h)
        ):
            changed[k] = cv
    assert changed, f"{vid}: nothing changed"
    out = {}
    pics = {}
    for key, part in zip(model.keys() if isinstance(model, dict) else [None], parts):
        copy = type(part)()
        for e in part.els:
            faces = {}
            for fc, v in e.faces.items():
                k = v if isinstance(v, str) else id(v)
                if k in changed:
                    if isinstance(v, str):
                        nm = f"{vid}__{v}"
                        MATS.setdefault(nm, changed[k])
                        faces[fc] = nm
                    else:
                        if k not in pics:
                            pics[k] = Pic(v.name, None, v.area)
                            pics[k].cv = changed[k]
                        faces[fc] = pics[k]
                else:
                    faces[fc] = v
            ne = type(e)(e.f, e.t, faces, e.rot, e.shade)
            copy.els.append(ne)
        out[key] = copy
    return out[None] if not isinstance(model, dict) else out


def variant(base, fam, tag, ru, en, rules):
    b = next(f for f in FURN if f.id == base)
    vid = f"{base}__{tag}"

    def build(*a):
        m = b.build(*a)
        m = m() if callable(m) else m
        return recolor(m, vid, rules)

    Furn(
        vid,
        f"{b.ru} ({ru})",
        f"{b.en} ({en})",
        build,
        mount=b.mount,
        light=b.light,
        toggle=b.toggle,
        seat=b.seat,
        color=b.color,
        sound=b.sound,
        solid=b.solid,
        cutout=b.cutout,
        kind=b.kind,
        item_scale=b.item_scale,
    )
    FAMILIES.setdefault(fam, [])
    if tag not in FAMILIES[fam]:
        FAMILIES[fam].append(tag)
    BASES.setdefault(base, {}).setdefault(fam, {})[tag] = vid


def wood(base, tags=WOODS, skip=(), only=None, fam="wood"):
    for tag, (to, ru, en) in tags.items():
        variant(base, fam, tag, ru, en, [dict(WOOD, to=to, skip=skip, only=only)])


def make():
    for base in (
        "apt_wall_unit",
        "apt_coffee_table",
        "apt_wardrobe",
        "apt_sideboard",
        "apt_bookcase",
        "apt_bookcase_tall",
        "apt_dressing_table",
        "apt_wall_clock",
        "apt_desk",
        "apt_stool",
    ):
        skip = {"apt_coffee_table": ("mag",), "apt_desk": ("globe",)}.get(base, ())
        wood(base, skip=skip)
    wood("apt_chair", skip=("fabric_brown", "seat"))
    wood("apt_kitchen_table", skip=("glass_tea", "brass", "oilcloth", "oilcloth_side"))
    wood("apt_bed", only=("head_back", "head", "foot", "walnut"))
    for base in (
        "apt_kitchen_base_wood",
        "apt_kitchen_jars_wood",
        "apt_kitchen_sink_wood",
        "apt_wall_cupboard_wood",
    ):
        wood(
            base,
            KITWOOD,
            skip=("plate", "plate_rim", "cup_enamel", "black"),
            fam="kitwood",
        )
    wood("apt_stool", STOOL, fam="stool")

    red = dict(lo=335, hi=12, smin=0.3)
    for base in ("apt_sofa_red", "apt_armchair_red"):
        for tag, (to, ru, en) in FABRICS.items():
            variant(base, "fabric", tag, ru, en, [dict(red, to=to)])
    for base, tgt in (
        ("apt_sofa_green", "apt_sofa_red"),
        ("apt_sofa_blue", "apt_sofa_red"),
        ("apt_armchair_green", "apt_armchair_red"),
        ("apt_armchair_brown", "apt_armchair_red"),
    ):
        ALIASES[base] = tgt

    paint = dict(smax=0.4)
    for base in (
        "apt_kitchen_base_white",
        "apt_kitchen_jars_white",
        "apt_kitchen_sink_white",
        "apt_wall_cupboard_white",
    ):
        for tag, (to, ru, en) in KITCHEN.items():
            variant(
                base,
                "kitchen",
                tag,
                ru,
                en,
                [dict(paint, to=to, only=("enamel", "doors"))],
            )
    for tag, (to, ru, en) in CLOTH.items():
        variant(
            "apt_kitchen_table",
            "cloth",
            tag,
            ru,
            en,
            [dict(lo=335, hi=20, smin=0.3, to=to, only=("oilcloth", "oilcloth_side"))],
        )
    for tag, (to, ru, en) in BLANKET.items():
        variant(
            "apt_bed",
            "blanket",
            tag,
            ru,
            en,
            [dict(lo=330, hi=50, smin=0.15, to=to, only=("blanket", "blanket_edge"))],
        )
    for tag, (to, ru, en) in SPREAD.items():
        variant(
            "apt_bed_iron",
            "spread",
            tag,
            ru,
            en,
            [dict(smax=0.4, to=to, only=("spread", "bedspread_side"))],
        )
    wood_rule = lambda skip=(), only=None: lambda to: dict(WOOD, to=to, skip=skip, only=only)
    cloth_rule = lambda to: dict(lo=335, hi=20, smin=0.3, to=to, only=("oilcloth", "oilcloth_side"))
    blanket_rule = lambda to: dict(lo=330, hi=50, smin=0.15, to=to, only=("blanket", "blanket_edge"))
    for base, fam, other, ors, wr in (
        ("apt_kitchen_table", "cloth+wood", CLOTH, cloth_rule,
         wood_rule(skip=("glass_tea", "brass", "oilcloth", "oilcloth_side"))),
        ("apt_bed", "blanket+wood", BLANKET, blanket_rule, wood_rule(only=("head_back", "head", "foot", "walnut"))),
    ):
        for wtag, (wto, wru, wen) in WOODS.items():
            for otag, (oto, oru, oen) in other.items():
                variant(base, fam, f"{otag}_{wtag}", f"{oru}, {wru}", f"{oen}, {wen}", [wr(wto), ors(oto)])
    out = {
        "families": FAMILIES,
        "bases": {b: dict(f) for b, f in BASES.items()},
        "aliases": ALIASES,
    }
    with open(os.path.join(HERE, "variants.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1, sort_keys=True)
