"""Cars of the panel-block cities (spec §7): Soviet and Russian cars as one sees them in Pushchino in the 1980s–2000s, built
as voxels of 1/16 block by tools/city2/carvox.py, at their real sizes (1 block = 1 m).

    python3 -I tools/city2/cars.py                 # writes models, textures, CarTypes.java, lang
    python3 -I tools/city2/cars.py preview DIR [car ...]   # previews from six sides (and with doors open) into DIR

Writes
    assets/mobtalk/models/car/<car>.json          parts and quads (read by client.game.CarClient)
    assets/mobtalk/textures/entity/car/<car>/<scheme>.png   one atlas per paint scheme (colour or livery)
    assets/mobtalk/textures/entity/car/<car>/glow_<channel>.png   lamps that light up (head, tail, blue, red, turn)
    src/main/java/io/github/mobtalk/game/city/CarTypes.java   sizes, seats, doors, wheels, schemes (generated)

Every car has the parts a driving car needs: the body, four wheels (spin about x, the front two also steer about y),
doors that swing on their front hinges, the steering wheel (turns with the front wheels) and, for a livery that has one,
a roof beacon. The driver sits on the left (+x), as in every Soviet car.
"""
import json
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import carvox as V  # noqa: E402
from carvox import (AIR, PAINT, GLASS, CHROME, BLACK, TIRE, RIM, HEAD, TAIL, TURN, SEAT, DASH, FLOOR, PLATE, STEER,  # noqa: E402
                    UNDER, GRILLE, BLUE, RED, TRIM, PAINT2, ALU, LAMP, REVERSE, MIRROR, CANVAS, RUST, Vox, pl)
import fur_paint as FP  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "mobtalk")
JAVA = os.path.join(ROOT, "src", "main", "java", "io", "github", "mobtalk", "game", "city", "CarTypes.java")

M = 16.0  # units a metre


def rgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], float)


# ---------------------------------------------------------------- paint schemes
# Factory colours with their Soviet names (AvtoVAZ, GAZ, AZLK catalogues); a livery is a scheme with stripes and words.
COLOURS = {
    "white": ("Белая ночь", "#e6e4dc"), "snow": ("Снежная королева", "#f1f1ec"), "corrida": ("Коррида", "#b2221d"),
    "valentina": ("Валентина", "#6e1a2a"), "safari": ("Сафари", "#c9b68a"), "baltika": ("Балтика", "#6f93b6"),
    "murena": ("Мурена", "#22545e"), "orange": ("Апельсин", "#d7682a"), "lime": ("Липа зелёная", "#8fa24a"),
    "ochre": ("Охра золотистая", "#c49a3c"), "asphalt": ("Мокрый асфальт", "#4b5157"), "black": ("Чёрный", "#1d1e21"),
    "silver": ("Серебристый", "#a9aeb3"), "aquamarine": ("Аквамарин", "#3f8f8a"), "cherry": ("Вишня", "#5a1420"),
    "olive": ("Защитный", "#5d6340"), "beige": ("Бежевый", "#d8c9a4"), "sky": ("Голубой", "#8fb5d4"),
    "yellow": ("Жёлтый", "#e2b931"), "green": ("Зелёный", "#3c6b3a"), "quartz": ("Кварц", "#7d8a8f"),
    "plum": ("Мускат", "#4e3a52"), "cream": ("Слоновая кость", "#e5dcc0"), "red": ("Красный", "#b8302a"),
}
# liveries: base colour, stripe colour, words on the doors, beacon on the roof
LIVERIES = {
    "police": dict(name="Полиция", base="#eef0f0", stripe="#1f4fa8", word="ПОЛИЦИЯ", letters="#1f4fa8", beacon=True),
    "militsiya": dict(name="Милиция", base="#e3b52e", stripe="#1f3f98", word="МИЛИЦИЯ", letters="#f2f2ee", beacon=True, on_stripe=True),
    "taxi": dict(name="Такси", base="#e6c02c", checker=True, word="", beacon=False, roof_sign="ТАКСИ"),
    "ambulance": dict(name="Скорая помощь", base="#eeeeea", stripe="#c8231f", word="СКОРАЯ", letters="#c8231f", beacon=True, red_beacon=True),
}


# ---------------------------------------------------------------- the generic body

class Car:
    """One car being built: the body's voxels (vox 0), extra voxel blocks (wheels, steering wheel, beacon), the parts."""

    def __init__(self, cid, length, width, height, margin=4):
        self.id = cid
        self.L, self.W, self.H = length, width, height
        nx = int(math.ceil(width)) + 2 * margin
        nz = int(math.ceil(length)) + 2 * margin
        ny = int(math.ceil(height)) + margin
        self.body = Vox(nx, ny, nz, (-nx / 2, 0, -nz / 2))
        self.voxes = [self.body]
        self.parts = [dict(name="body", role="body", pivot=(0, 0, 0))]
        self.seats = []        # (x, y, z) units: where a sitter's seat surface is
        self.doors = []        # part index per door, in seat order where it serves a seat
        self.wheels = []
        self.info = {}

    def part(self, name, role, pivot=(0, 0, 0), **kw):
        self.parts.append(dict(name=name, role=role, pivot=tuple(float(v) for v in pivot), **kw))
        return len(self.parts) - 1

    def add_vox(self, vox):
        self.voxes.append(vox)
        return vox


def wheel(car, name, cx, cy, cz, r, width, outward, front, hub="steel", s=0.5, dish=None):
    """A wheel of radius r and the given width (units), its own voxels at s units a cell, the hub facing `outward` (+1: +x)."""
    n = int(math.ceil(2 * r / s)) + 2
    w = int(math.ceil(width / s))
    v = Vox(w, n, n, (-w * s / 2, -n * s / 2, -n * s / 2), s)
    X, Y, Z = v.grids()
    R = np.sqrt(Y ** 2 + Z ** 2)
    side = X * outward  # > 0 toward the outside
    hw = w * s / 2
    tire = R < r
    v.set(tire, TIRE)
    rim_r = r * 0.62
    # the rim sits a little inside the tyre's outer wall; the hub cap stands out of it
    v.set((R < rim_r) & (side > hw - 1.0 * s - 0.01), AIR)
    v.set((R < rim_r) & (side <= hw - 1.0 * s) & (side > hw - 2.0 * s), RIM)
    if hub == "cap":
        v.set((R < rim_r * 0.75) & (side > hw - 1.0 * s - 0.01) & (side < hw), CHROME)
    elif hub == "alloy":
        v.set((R < rim_r * 0.35) & (side > hw - 1.0 * s - 0.01) & (side < hw), ALU)
    elif hub == "uaz":
        v.set((R < rim_r * 0.42) & (side > hw - 1.0 * s - 0.01) & (side < hw), RIM)
    v.set((R < rim_r) & (side <= hw - 2.0 * s) & (side > -hw + 1.0 * s), RIM)
    pi = car.part(name, "wheel", (cx, cy, cz), front=bool(front), radius=r, side=outward)
    v.part[:] = pi
    v.local = (cx, cy, cz)
    car.add_vox(v)
    car.wheels.append(dict(part=pi, x=cx, y=cy, z=cz, r=r, front=bool(front)))
    return v


def steering_wheel(car, x, y, z, r=3.0, tilt=25.0, spokes=3):
    """The steering wheel: a ring in its own plane (local x-y, facing -z toward the driver), tilted back by `tilt` degrees."""
    s = 0.5
    n = int(math.ceil(2 * r / s)) + 2
    v = Vox(n, n, 2, (-n * s / 2, -n * s / 2, -s), s)
    X, Y, Z = v.grids()
    R = np.sqrt(X ** 2 + Y ** 2)
    v.set((R < r) & (R > r - 0.9) & (Z < 0), STEER)
    if spokes == 3:
        v.set((R < r - 0.5) & (abs(Y) < 0.5) & (Z < 0), STEER)
        v.set((R < r - 0.5) & (abs(X) < 0.5) & (Y < 0) & (Z < 0), STEER)
    else:
        v.set((R < r - 0.5) & (abs(Y) < 0.5) & (Z < 0), STEER)
    v.set((R < 1.1) & (Z > -0.6), STEER)
    v.set((R < 0.6) & (Z < 0), CHROME)
    pi = car.part("steering", "steer", (x, y, z), tilt=tilt)
    v.part[:] = pi
    v.local = (x, y, z)
    car.add_vox(v)
    return v


def beacon(car, y, z, length=6.0, width=None, red=False):
    """A roof bar of blue lamps (blue and red for an ambulance, two blue for the police), its own part: only liveries with a
    beacon draw it."""
    s = 0.5
    width = width or car.W * 0.55
    nx, nz = int(width / s), int(length / s / 2.6)
    v = Vox(nx, 4, nz, (-nx * s / 2, 0, -nz * s / 2), s)
    X, Y, Z = v.grids()
    v.set(Y < 0.5, BLACK)
    v.set((Y > 0.5) & (abs(X) > 0.4), BLUE)
    v.set((Y > 0.5) & (abs(X) <= 0.4), CHROME)
    if red:
        v.set((Y > 0.5) & (X < -0.4), RED)
    v.set((Y > 1.5) & (abs(X) < width / 2 - 0.6) & (abs(Z) < nz * s / 2 - 0.6) & (abs(X) > 0.4), AIR)
    pi = car.part("beacon", "beacon", (0, y, z))
    v.part[:] = pi
    v.local = (0, y, z)
    car.add_vox(v)
    return v


# ---------------------------------------------------------------- painting

def ramp_col(c, k):
    return np.clip(c * k, 0, 255)


def text_decal(mesh, mask, words, z_centre, y_centre, height, colour, out, side_sign=None, px=None):
    """Writes `words` in the 5-row pixel font on the car's sides, centred at (z_centre, y_centre), `height` units tall (the
    font's 5 rows). On the left side (+x) the text reads toward -z (front on the reader's left), on the right toward +z."""
    rows = 5
    glyphs = [FP.glyph(ch) for ch in words.upper()]
    widths = [len(g[0]) for g in glyphs]
    total = sum(widths) + len(glyphs) - 1
    cell = height / rows
    P = mesh.P
    for sgn in (1, -1):
        m = mask & (mesh.dir == (0 if sgn > 0 else 1))
        if not m.any():
            continue
        # reading coordinate: from the text's left edge, in font cells
        along = (z_centre - P[:, 2]) * sgn if sgn > 0 else (P[:, 2] - z_centre)
        col = np.floor(along / cell + total / 2).astype(int)
        row = np.floor((y_centre + height / 2 - P[:, 1]) / cell).astype(int)
        ok = m & (row >= 0) & (row < rows) & (col >= 0) & (col < total)
        bits = np.zeros(total * rows, bool)
        x = 0
        for g, w in zip(glyphs, widths):
            for j, r in enumerate(g):
                for i, ch in enumerate(r):
                    if ch == "X":
                        bits[j * total + x + i] = True
            x += w + 1
        sel = np.nonzero(ok)[0]
        hit = bits[row[sel] * total + col[sel]]
        out[sel[hit]] = colour


class Look:
    """What a car's painter needs besides the mesh: the region of the cabin (for the trim inside), the paint, the seat cloth."""

    def __init__(self, cabin=None, seat="#6b4a32", trim="#5a5550", headliner="#cfc8b8", dash="#2b2b2d", floor="#2a2826",
                 grille=None, extra=None):
        self.cabin = cabin            # fn(P) -> bool: inside the passengers' room
        self.seat = rgb(seat)
        self.trim = rgb(trim)
        self.headliner = rgb(headliner)
        self.dash = rgb(dash)
        self.floor = rgb(floor)
        self.grille = grille          # fn(mesh, sel, out) -> paints the grille's texels
        self.extra = extra            # fn(mesh, out, scheme) -> car-specific decals


BAYER = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) / 16.0


def colours(car, look, scheme):
    """The painter of one scheme: returns fn(mesh) -> texel colours."""
    liv = LIVERIES.get(scheme)
    paint = rgb(liv["base"]) if liv else rgb(COLOURS[scheme][1])

    def fn(mesh):
        n = mesh.mat.size
        out = np.zeros((n, 4))
        out[:, 3] = 255
        mat, P, d = mesh.mat, mesh.P, mesh.dir
        nrm = mesh.normal()
        tu, tv = mesh.tu, mesh.tv
        dith = BAYER[tv % 4, tu % 4]

        def put(m, c):
            out[m, :3] = c

        base = {
            CHROME: rgb("#c9ced3"), BLACK: rgb("#262628"), TIRE: rgb("#232325"), RIM: rgb("#8d9093"), HEAD: rgb("#dfe3dc"),
            TAIL: rgb("#a3201c"), TURN: rgb("#d9822b"), SEAT: look.seat, DASH: look.dash, FLOOR: look.floor,
            PLATE: rgb("#e8e8e2"), STEER: rgb("#1e1e20"), UNDER: rgb("#3a3836"), GRILLE: rgb("#1c1c1e"), BLUE: rgb("#2f5fe0"),
            RED: rgb("#d8261e"), TRIM: look.trim, ALU: rgb("#b4b8bb"), LAMP: rgb("#f1eedc"), REVERSE: rgb("#e9e9e4"),
            MIRROR: rgb("#9fb3bf"), CANVAS: rgb("#5b5e45"), RUST: rgb("#7a4a2a"), PAINT2: rgb("#e9e7e0"),
        }
        for m_, c in base.items():
            put(mat == m_, c)
        pm = mat == PAINT
        put(pm, paint)
        # faces: tops a little lighter, undersides dark
        up, down = d == 2, d == 3
        out[pm & up, :3] = ramp_col(out[pm & up, :3], 1.06)
        low = P[:, 1] < car.info.get("belt", 1e9) - 2.0
        out[np.isin(mat, (PAINT, PAINT2)) & down & low, :3] = base[UNDER]
        out[np.isin(mat, (PAINT, PAINT2)) & down & ~low, :3] *= 0.86
        # inside the cabin: trim on the walls, the headliner overhead, the floor mat
        if look.cabin is not None:
            cz = car.info.get("cabin_z", 0.0)
            toward = np.stack([-P[:, 0], np.zeros(n), cz - P[:, 2]], 1)
            belt_y = car.info.get("belt", 0)
            # sideways faces looking into the cabin; floors under the belt line; ceilings (the roof's underside) high up
            inward = ((np.einsum("ij,ij->i", nrm, toward) > 0) & (d != 2) & (d != 3)) | ((d == 2) & (P[:, 1] < belt_y)) | \
                     ((d == 3) & (P[:, 1] > car.info.get("roof", 1e9) - 2.5))
            inside = look.cabin(P) & np.isin(mat, (PAINT, PAINT2, UNDER)) & inward
            put(inside & (down), look.headliner)
            put(inside & ~down & ~up, look.trim)
            put(inside & up, look.floor)
        # glass: dark blue-green, a light diagonal streak, see-through
        g = mat == GLASS
        streak = ((P[:, 2] + P[:, 1] * 0.9 + P[:, 0] * 0.7) % 22.0) < 1.6
        soft = (((P[:, 2] + P[:, 1] * 0.9 + P[:, 0] * 0.7) % 22.0) >= 1.6) & (((P[:, 2] + P[:, 1] * 0.9 + P[:, 0] * 0.7) % 22.0) < 3.0)
        put(g, rgb("#2c4450"))
        out[g, 3] = 105
        put(g & streak, rgb("#7d9eab"))
        out[g & streak, 3] = 135
        put(g & soft, rgb("#4f6c78"))
        out[g & soft, 3] = 115
        # seen from inside the glass is clear, with no streaks (they filled the driver's view)
        if look.cabin is not None:
            cz = car.info.get("cabin_z", 0.0)
            into = np.einsum("ij,ij->i", nrm, np.stack([-P[:, 0], car.info.get("roof", 20) * 0.6 - P[:, 1], cz - P[:, 2]], 1)) > 0
            gi = g & into
            put(gi, rgb("#3a5560"))
            out[gi, 3] = 55
        # chrome: a bright upper half and a darker lower band, like a horizon in it
        ch = np.isin(mat, (CHROME, ALU)) & (mesh.vid == 0)
        frac = (P[:, 1] * 2) % 2
        out[ch & (d < 2) | ch & (d > 3), :3] = np.where((frac[ch & (d < 2) | ch & (d > 3)] < 1)[:, None],
                                                        ramp_col(out[ch & (d < 2) | ch & (d > 3), :3], 0.78),
                                                        ramp_col(out[ch & (d < 2) | ch & (d > 3), :3], 1.08))
        # tyres: a tread on the rolling face, a lighter ring on the wall
        t = mat == TIRE
        roll = t & (d >= 2)
        tread = (np.floor(np.arctan2(P[:, 1] - 0, P[:, 2]) * 6) % 2 == 0)
        out[roll & (dith < 0.5), :3] = rgb("#1a1a1b")
        # wheel hubs: dark centre and spokes-like dither on the cap
        hub = np.isin(mat, (CHROME, ALU, RIM)) & (mesh.vid > 0) & (d < 2)
        if hub.any():
            loc = P - np.array([0, 0, 0])
            for w in car.wheels:
                near = hub & (abs(P[:, 2] - w["z"]) < w["r"] + 1) & (abs(P[:, 1] - w["y"]) < w["r"] + 1) & (np.sign(P[:, 0]) == np.sign(w["x"]))
                rr = np.sqrt((P[:, 1] - w["y"]) ** 2 + (P[:, 2] - w["z"]) ** 2)
                ang = np.arctan2(P[:, 1] - w["y"], P[:, 2] - w["z"])
                out[near & (rr < 0.8), :3] = rgb("#3a3b3d")
                out[near & (rr > 0.8) & (np.floor(ang / (np.pi / 4)) % 2 == 0), :3] *= 0.82
                out[near & (rr > w["r"] * 0.55), :3] *= 0.9
        # lamps: a lens grid
        for lm in (HEAD, TAIL, TURN, LAMP, REVERSE, BLUE, RED):
            mm = mat == lm
            grid = ((tu % 3 == 0) | (tv % 3 == 0)) & mm
            out[grid, :3] = ramp_col(out[grid, :3], 0.82 if lm != HEAD else 0.9)
        # plates: a black frame and dark marks for the number
        pmask = mat == PLATE
        if pmask.any():
            pz = pmask & (d >= 4)
            lo_y, hi_y = P[pz, 1].min(), P[pz, 1].max()
            xs = P[:, 0]
            col = np.floor((xs + 20) * 2).astype(int)
            row = np.floor((P[:, 1] - lo_y) * 2).astype(int)
            rows = int(round((hi_y - lo_y) * 2)) + 1
            glyph = (col // 3) * 7 + 3
            mark = pz & (row >= 1) & (row <= rows - 2) & (col % 3 != 0) & (((col % 3 == 1) & (glyph % 3 != 0)) | (row == 1) | (row == rows - 2) & (glyph % 2 == 0))
            out[mark, :3] = rgb("#26262a")
            out[pmask & np.any(mesh.edge, axis=1), :3] = rgb("#1e1e22")
        # liveries
        if liv:
            sides = pm & (d < 2)
            if liv.get("stripe"):
                y0, y1 = car.info["stripe"]
                if liv.get("on_stripe"):  # the words in white on a wide stripe
                    y0, y1 = car.info["word"][1] - 1.75, car.info["word"][1] + 1.75
                put(sides & (P[:, 1] > y0) & (P[:, 1] < y1), rgb(liv["stripe"]))
            if liv.get("word"):
                zc, yc, h = car.info["word"]
                text_decal(mesh, sides, liv["word"], zc, yc, h, rgb(liv["letters"]), out[:, :3])
            if liv.get("checker"):
                y0, y1 = car.info["stripe"]
                band = sides & (P[:, 1] > y0) & (P[:, 1] < y1)
                cell = np.floor(P[:, 2] / 1.5).astype(int) + np.floor(P[:, 1] / 1.5).astype(int)
                put(band & (cell % 2 == 0), rgb("#1b1b1b"))
                put(band & (cell % 2 == 1), rgb("#f2f2ee"))
        if look.grille:
            look.grille(mesh, mat == GRILLE, out)
        sides_all = pm & (d < 2) & (mesh.vid == 0)
        if "band" in car.info and not liv:
            y0, y1, bc = car.info["band"]
            put(sides_all & (P[:, 1] > y0) & (P[:, 1] < y1), rgb(bc))
        if "moulding" in car.info:
            y0, y1 = car.info["moulding"]
            band = sides_all & (P[:, 1] > y0) & (P[:, 1] < y1)
            put(band, rgb("#1f1f21"))
            put(band & (abs(P[:, 1] - (y0 + y1) / 2) < 0.26), rgb("#b9bec3"))
        for zh, yh in car.info.get("handles", []):
            hm = sides_all & (abs(P[:, 2] - zh) < 1.0) & (abs(P[:, 1] - yh) < 0.5)
            put(hm, rgb("#c7ccd1"))
            put(hm & (P[:, 1] < yh), rgb("#7f8489"))
        if look.extra:
            look.extra(mesh, out, scheme)
        # pixel-art bevels: a lighter top edge, a darker bottom edge on every surface; seams of doors and lids dark
        e = mesh.edge
        # no bevels on the greenhouse's stepped pillars (they made teeth of the slopes), nor on the glass
        belt_y = car.info.get("belt", 1e9)
        solidish = ~np.isin(mat, (GLASS,)) & ~((mesh.vid == 0) & (P[:, 1] > belt_y + 0.5) & (P[:, 1] < car.info.get("roof", 0) - 1.0) & (car.info.get("kind", "car") == "car"))
        lit = solidish & e[:, 2] & (d != 2) & (d != 3)
        dark = solidish & e[:, 3] & (d != 2) & (d != 3)
        out[lit, :3] = ramp_col(out[lit, :3], 1.16)
        out[dark, :3] = ramp_col(out[dark, :3], 0.78)
        side_e = solidish & (e[:, 0] | e[:, 1] | e[:, 4] | e[:, 5]) & (d >= 2) & (d <= 3)
        out[side_e & up, :3] = ramp_col(out[side_e & up, :3], 1.1)
        edges_v = solidish & (e[:, 4] | e[:, 5] | e[:, 0] | e[:, 1]) & (d < 2 | (d > 3))
        out[edges_v & ~lit & ~dark, :3] = ramp_col(out[edges_v & ~lit & ~dark, :3], 0.92)
        sm = mesh.seam & np.isin(mat, (PAINT, PAINT2))
        out[sm, :3] = ramp_col(out[sm, :3], 0.42)
        return np.clip(out, 0, 255).astype(np.uint8)
    return fn


def glow_fn(channel):
    def fn(mesh):
        n = mesh.mat.size
        out = np.zeros((n, 4))
        m = mesh.glow_mask(channel)
        col = {V.G_HEAD: (255, 250, 225), V.G_TAIL: (255, 60, 40), V.G_BLUE: (80, 130, 255), V.G_RED: (255, 50, 40),
               V.G_TURN: (255, 170, 60)}[channel]
        out[m, :3] = col
        out[m, 3] = 255
        return out.astype(np.uint8)
    return fn


# ---------------------------------------------------------------- the cars
CARS = {}


def car(fn):
    CARS[fn.__name__] = fn
    return fn


def three_box(c, p):
    """A Soviet three-box saloon (or, with p['hatch'], a hatchback/estate) from its profile.

    p: L, W, H, wheelbase, front (front axle z), r (wheel radius), track, clear (sill bottom), belt (window sill height),
    hood (hood height at the front), deck (trunk height), cowl_z, ws_top_z (roof front), roof_rear_z, rw_bottom_z,
    tumble (how much narrower the roof is), b_z (B pillar), doors (2 or 4), corner (plan chamfer)."""
    body = c.body
    L, W, H = p["L"], p["W"], p["H"]
    hw = W / 2
    zf, zr = L / 2, -L / 2
    belt, roof = p["belt"], H
    X, Y, Z = body.grids()
    # plan: chamfered corners; a gentle taper of the nose and tail
    corner = p.get("corner", 1.5)
    half = hw - np.maximum(0, np.maximum(Z - (zf - corner), (zr + corner) - Z)) * 0.9
    half = half - np.where(Y > belt, (Y - belt) / max(1, roof - belt) * p["tumble"], 0)
    # side profile of the lower body: bottom line and top line along z
    bottom = pl(Z, p["bottom"])
    top = pl(Z, p["top"])
    lower = (abs(X) < half) & (Y > bottom) & (Y < np.minimum(top, belt + 0.01) + 0.0) & (Z > zr) & (Z < zf)
    lower |= (abs(X) < half) & (Y > bottom) & (Y < top) & (Z > zr) & (Z < zf)
    # the greenhouse: from the windshield's foot to the rear window's foot, sloped ends
    ws_foot, ws_top, rr_top, rw_foot = p["cowl_z"], p["ws_top_z"], p["roof_rear_z"], p["rw_bottom_z"]
    t = np.clip((Y - belt) / (roof - belt), 0, 1)
    front_edge = ws_foot + (ws_top - ws_foot) * t
    rear_edge = rw_foot + (rr_top - rw_foot) * t
    green = (abs(X) < half) & (Y >= belt) & (Y < roof) & (Z < front_edge) & (Z > rear_edge)
    shell = lower | green
    body.set(shell, PAINT)
    # the cabin's hollow: walls (doors) two cells thick, the floor, the roof one cell
    floor = p.get("floor", p["clear"] + 2.5)
    fw, rw = p["cabin_front"], p["cabin_rear"]
    hollow_low = (abs(X) < half - 2) & (Y > floor) & (Y < belt) & (Z < fw) & (Z > rw)
    hollow_green = V.erode(green) & (abs(X) < half - 1)
    body.set(hollow_low | hollow_green, AIR)
    # windows: the greenhouse's skin is glass except the roof, the pillars and the frames
    skin = green & ~hollow_green & ~hollow_low
    glass = skin & (Y < roof - 1.0) & (Y > belt + 0.6)
    pillars = np.zeros_like(glass)
    for z0, z1 in p["pillars"]:
        pillars |= (Z > z0) & (Z < z1)
    side = abs(X) > half - 1.2
    # the windshield and the rear window keep a frame at their sides; the A and C pillars run along the slopes
    a_pillar = (Z > front_edge - 1.6) & side
    c_pillar = (Z < rear_edge + p.get("c_width", 1.6)) & side
    frame_ws = (abs(X) > half - 1.6) & ~side & ((Z > front_edge - 2.2) | (Z < rear_edge + 2.2))
    win = glass & ~(pillars & side) & ~a_pillar & ~c_pillar & ~frame_ws
    body.set(win, GLASS)
    body.set(green & (Y > roof - 1.0), PAINT)
    # wheel arches and wells
    for wz in (p["front"], p["front"] - p["wheelbase"]):
        R = np.sqrt((Y - p["r"]) ** 2 + (Z - wz) ** 2)
        arch = (R < p["r"] + 1.6) & (Y < p["r"] + p["r"] + 1.6)
        body.set(arch & (abs(X) > half - 5.5), AIR)
        well = (R < p["r"] + 2.6) & (R >= p["r"] + 1.6) & (abs(X) > half - 5.5) & (abs(X) < half - 0.9) & body.solid()
        above = np.zeros_like(well)
        above[:, :-2, :] = body.solid()[:, 2:, :]
        well &= above
        body.set(well, UNDER)
    c.info.update(belt=belt, roof=roof, floor=floor, hw=hw, cabin_z=(p["cabin_front"] + p["cabin_rear"]) / 2)
    c.half = half
    return dict(X=X, Y=Y, Z=Z, half=half, front_edge=front_edge, rear_edge=rear_edge, green=green, hollow=hollow_low | hollow_green)


def doors(c, g, p, spans, hinge_pad=0.0):
    """Doors: each span (z_front, z_rear, seat) cuts the outer two cells of the side between the sill and the roof frame on
    both sides into a part hinged at its front edge."""
    body = c.body
    X, Y, Z, half = g["X"], g["Y"], g["Z"], g["half"]
    roof = c.info["roof"]
    out = []
    for zf_, zr_, seat_l, seat_r in spans:
        for sgn, seat in ((1, seat_l), (-1, seat_r)):
            if seat is None:
                continue
            region = (X * sgn > half - 2.01) & (Y > p["sill"]) & (Y < roof - 1.0) & (Z < zf_) & (Z > zr_) & body.solid()
            # above the belt a door takes only its glass and the frame row over it: the pillars stay with the body
            above = Y >= c.info["belt"]
            frame = np.zeros_like(region)
            glass_cells = region & above & (body.mat == GLASS)
            frame[:, 1:, :] = glass_cells[:, :-1, :]
            region = region & (~above | glass_cells | (frame & (body.mat == PAINT)))
            for wz in (p["front"], p["front"] - p["wheelbase"]):
                region &= np.sqrt((Y - p["r"]) ** 2 + (Z - wz) ** 2) > p["r"] + 2.6
            name = ("door_" + ("front" if zf_ > 0 else "rear") + ("_left" if sgn > 0 else "_right")) if len(spans) > 1 else ("door_left" if sgn > 0 else "door_right")
            pi = c.part(name, "door", (sgn * c.info["hw"], 0, zf_), side=sgn, seat=seat, open=70.0, door=len(c.doors))
            body.part[region] = pi
            c.doors.append(pi)
            out.append(pi)
    return out


# ---- ВАЗ-2107

def lada_classic_front(c, g, p, style="2107"):
    body = c.body
    X, Y, Z, half = g["X"], g["Y"], g["Z"], g["half"]
    zf = p["L"] / 2
    # the front panel: headlamps, the grille, the indicators wrapping the corners
    face = (Z > zf - 1.01) & body.solid()
    if style == "2107":
        body.set(face & (abs(X) > 3.2) & (abs(X) < 10.6) & (Y > 7.6) & (Y < 10.6), BLACK)
        body.set(face & (abs(X) > 3.7) & (abs(X) < 10.1) & (Y > 8.1) & (Y < 10.1), HEAD)
        body.set(face & (abs(X) > 10.6) & (Y > 7.6) & (Y < 10.6), TURN)
        body.set((Z > zf - 3.5) & (abs(X) > half - 1.01) & (Y > 7.6) & (Y < 10.6) & body.solid(), TURN)
        # the tall chrome grille, standing out a cell, with the hood's centre bulge above it
        body.box(-3, 6.5, zf - 1, 3, 11.8, zf + 1, CHROME)
        body.box(-2.5, 7.0, zf - 0.5, 2.5, 11.3, zf + 1.01, GRILLE)
    elif style == "2106":
        for cxs in (5.0, 8.4):
            R = np.sqrt((abs(X) - cxs) ** 2 + (Y - 9.1) ** 2)
            body.set(face & (R < 1.7), HEAD)
            body.set(face & (R >= 1.7) & (R < 2.2), CHROME)
        body.set(face & (abs(X) < 3.0) & (Y > 7.4) & (Y < 10.8), GRILLE)
        body.set(face & (abs(X) > 10.6) & (Y > 8.0) & (Y < 10.0), TURN)


def lada_classic_rear(c, g, p, style="2107"):
    body = c.body
    X, Y, Z, half = g["X"], g["Y"], g["Z"], g["half"]
    zr = -p["L"] / 2
    face = (Z < zr + 1.01) & body.solid()
    # the big tail-lamp blocks: red, amber, white reverse in a ribbed housing that wraps a little round the corner
    body.set(face & (abs(X) > 5.2) & (Y > 7.5) & (Y < 11.0), TAIL)
    body.set(face & (abs(X) > 9.6) & (Y > 7.5) & (Y < 11.0), TURN)
    body.set(face & (abs(X) > 5.2) & (abs(X) < 6.8) & (Y > 7.5) & (Y < 11.0), REVERSE)
    body.set((Z < zr + 2.5) & (abs(X) > half - 1.01) & (Y > 7.5) & (Y < 11.0) & body.solid(), TAIL)
    # the plate on the panel between, the boot lid's chrome strip with its lock
    body.box(-4.0, 7.6, zr - 0.6, 4.0, 9.8, zr + 0.2, PLATE)
    body.set(face & (abs(X) < 5.2) & (Y > 10.4) & (Y < 11.0), CHROME)


def bumpers(c, p, front_y=(5.5, 7.5), rear_y=(5.5, 7.5), mat=ALU, caps=BLACK, wrap=3.5, depth=1.0):
    body = c.body
    hw = c.info["hw"]
    zf, zr = p["L"] / 2, -p["L"] / 2
    for z0, z1, (y0, y1) in ((zf - 1, zf + depth, front_y), (zr - depth, zr + 1, rear_y)):
        body.box(-hw + 0.6, y0, z0, hw - 0.6, y1, z1, mat)
        body.box(-hw + 0.6, y0, z0, hw - 0.6, y0 + 0.6, z1, BLACK)
        # the ends wrap round the corners, in black rubber caps
        zz0, zz1 = (zf - wrap, zf + depth) if z0 > 0 else (zr - depth, zr + wrap)
        body.box(hw - 2.5, y0, zz0, hw + 0.6, y1, zz1, caps)
        body.box(-hw - 0.6, y0, zz0, -hw + 2.5, y1, zz1, caps)


def mirror(c, z, y, mat=BLACK):
    hw = c.info["hw"]
    c.body.box(hw - 0.2, y, z - 1.0, hw + 1.6, y + 1.2, z + 0.2, mat)
    c.body.box(-hw - 1.6, y, z - 1.0, -hw + 0.2, y + 1.2, z + 0.2, mat)


def interior(c, p, seats_front_z, rear_z, dash_z, steer_xyz, bench=True, seat_h=7.5, back_h=15.5, headrest=True, rear_seat=True):
    body = c.body
    hw = c.info["hw"]
    floor = c.info["floor"]
    # dashboard: a shelf across the car under the windshield, the instrument hood before the driver
    body.box(-hw + 2, c.info["belt"] - 3.5, dash_z - 2.5, hw - 2, c.info["belt"] + 0.6, dash_z + 4, DASH, only=[AIR])
    body.box(steer_xyz[0] - 2.5, c.info["belt"] + 0.4, dash_z - 1, steer_xyz[0] + 2.5, c.info["belt"] + 1.4, dash_z + 1.5, DASH, only=[AIR])
    body.box(-hw + 2, floor - 0.1, p["cabin_rear"] + 0.5, hw - 2, floor + 0.6, dash_z, FLOOR, only=[AIR, PAINT])
    # front seats: two buckets with headrests; the driver's at +x
    sx = steer_xyz[0]
    for x in (sx, -sx):
        z = seats_front_z
        body.box(x - 3, floor, z - 3, x + 3, seat_h, z + 2.5, SEAT, only=[AIR])
        body.box(x - 3, seat_h, z - 4, x + 3, back_h, z - 2.6, SEAT, only=[AIR])
        if headrest:
            body.box(x - 1.8, back_h, z - 3.8, x + 1.8, back_h + 2.5, z - 2.8, SEAT, only=[AIR])
        c.seats.append((x, seat_h, z))
    if rear_seat:
        z = rear_z
        body.box(-hw + 2, floor, z - 2.5, hw - 2, seat_h, z + 3, SEAT, only=[AIR])
        body.box(-hw + 2, seat_h, z - 4, hw - 2, back_h - 0.5, z - 2.5, SEAT, only=[AIR])
        c.seats.append((sx, seat_h, z))
        c.seats.append((-sx, seat_h, z))
    # the steering column
    x, y, zs = steer_xyz
    body.box(x - 0.5, y - 2.5, zs + 0.5, x + 0.5, y - 0.5, dash_z, BLACK, only=[AIR])
    steering_wheel(c, x, y, zs)


def cabin_region(c, p):
    hw = c.info["hw"]
    belt, roof, floor = c.info["belt"], c.info["roof"], c.info["floor"]
    zf, zr = p["cabin_front"] + 2.5, p["cabin_rear"] - 1

    def fn(P):
        return (abs(P[:, 0]) < hw - 0.9) & (P[:, 1] > floor - 0.6) & (P[:, 1] < roof - 0.4) & (P[:, 2] < zf) & (P[:, 2] > zr)
    return fn


def grille_bars(step=1.0, horizontal=False, frame=None):
    def fn(mesh, sel, out):
        q = mesh.P[:, 1] if horizontal else mesh.P[:, 0]
        bars = sel & ((np.floor(q / step * 2) % 2) == 0)
        out[bars, :3] = rgb("#a9aeb3")
    return fn


def finish(c, p, look):
    c.look = look
    c.info.setdefault("stripe", (c.info["belt"] - 3.5, c.info["belt"] - 1.5))
    return c


@car
def vaz2107():
    """ВАЗ-2107 «семёрка» (1982–2012): 4.145 × 1.620 × 1.435 m, wheelbase 2.424, 13-inch wheels."""
    p = dict(L=66.3, W=25.9, H=23.0, wheelbase=38.8, front=19.4, r=4.6, clear=3.0, belt=13.6, sill=3.6, tumble=2.0,
             cowl_z=9.0, ws_top_z=2.6, roof_rear_z=-14.6, rw_bottom_z=-20.0, cabin_front=7.4, cabin_rear=-17.0,
             pillars=[(-5.2, -3.6)], corner=1.2,
             bottom=[(-33.2, 4.6), (-30, 3.4), (30, 3.4), (33.2, 4.6)],
             top=[(-33.2, 12.6), (-21, 13.3), (8, 13.6), (12, 13.1), (33.2, 11.8)])
    c = Car("vaz2107", p["L"], p["W"], p["H"])
    c.info.update(name="ВАЗ-2107 «Жигули»", name_en="VAZ-2107 Zhiguli")
    g = three_box(c, p)
    doors(c, g, p, [(9.0, -4.4, 0, 1), (-4.4, -16.6, 2, 3)])
    lada_classic_front(c, g, p, "2107")
    lada_classic_rear(c, g, p, "2107")
    bumpers(c, p, front_y=(5.0, 7.6), rear_y=(5.0, 7.6))
    mirror(c, 8.2, 14.2)
    interior(c, p, seats_front_z=-0.5, rear_z=-11.0, dash_z=7.0, steer_xyz=(6.4, 12.4, 3.6))
    for z in (p["front"], p["front"] - p["wheelbase"]):
        for sgn in (1, -1):
            wheel(c, ("wheel_front" if z > 0 else "wheel_rear") + ("_left" if sgn > 0 else "_right"), sgn * (p["W"] / 2 - 2.0),
                  p["r"], z, p["r"], 3.0, sgn, z > 0, hub="cap")
    beacon(c, p["H"], -4.0)
    c.body.box(-4.0, 5.2, p["L"] / 2 + 0.9, 4.0, 7.4, p["L"] / 2 + 1.6, PLATE)
    c.info.update(word=(-4.0, 11.75, 2.5), stripe=(8.4, 10.4), moulding=(8.4, 9.0), handles=[(-3.2, 12.3), (-15.0, 12.3)])
    return finish(c, p, Look(cabin=cabin_region(c, p), seat="#5e3d2b", trim="#4e4842", grille=grille_bars(1.0)))


def wheels4(c, p, width=3.0, hub="cap", inset=2.0, rear_track=None):
    for z in (p["front"], p["front"] - p["wheelbase"]):
        for sgn in (1, -1):
            x = p["W"] / 2 - inset
            wheel(c, ("wheel_front" if z > 0 else "wheel_rear") + ("_left" if sgn > 0 else "_right"), sgn * x, p["r"], z, p["r"], width, sgn,
                  z > 0, hub=hub)


def face_cells(c, front=True, depth=1.01):
    """The nose's (or the tail's) skin: per x, y column the outermost solid cells along z, `depth` units deep."""
    body = c.body
    solid = body.solid()
    nz = body.shape[2]
    if front:
        last = nz - 1 - np.argmax(solid[:, :, ::-1], axis=2)
    else:
        last = np.argmax(solid, axis=2)
    k = np.arange(nz)[None, None, :]
    cells = max(1, int(round(depth / body.s)))
    m = (k > last[:, :, None] - cells) & (k <= last[:, :, None]) if front else (k < last[:, :, None] + cells) & (k >= last[:, :, None])
    return m & solid & solid.any(axis=2)[:, :, None]


def round_lamps(c, xs, y, r, mat=HEAD, ring=CHROME, front=True, ring_w=0.6, depth=1.01, protrude=0.0):
    """Round lamps on the nose (or the tail): a lens and a chrome ring, mirrored across x."""
    body = c.body
    X, Y, Z = body.grids()
    face = face_cells(c, front, depth)
    for x in xs:
        R = np.sqrt((abs(X) - x) ** 2 + (Y - y) ** 2)
        body.set(face & (R < r), mat)
        if ring is not None:
            body.set(face & (R >= r) & (R < r + ring_w), ring)
        if protrude:
            zf = c.L / 2
            z0, z1 = (zf, zf + protrude) if front else (-zf - protrude, -zf)
            body.set((R < r + ring_w) & (Z > z0 - 0.01) & (Z < z1), ring if ring is not None else mat)
            body.set((R < r) & (Z > z1 - 1.01) & (Z < z1), mat)


def rect_face(c, x0, x1, y0, y1, mat, front=True, mirror=True, depth=1.01, only=None):
    body = c.body
    X, Y, Z = body.grids()
    ax = abs(X) if mirror else X
    m = face_cells(c, front, depth) & (ax > x0) & (ax < x1) & (Y > y0) & (Y < y1)
    if only is not None:
        m &= np.isin(body.mat, only)
    body.set(m, mat)


def side_rect(c, z0, z1, y0, y1, mat, depth=1.01, only=None):
    """Cells on both sides' skin (the outermost `depth` units) in a z, y window."""
    body = c.body
    X, Y, Z = body.grids()
    m = (abs(X) > c.half - depth) & (Y > y0) & (Y < y1) & (Z > z0) & (Z < z1) & body.solid()
    if only is not None:
        m &= np.isin(body.mat, only)
    body.set(m, mat)


@car
def moskvich412():
    """«Москвич-412» (АЗЛК, 1967–1997), late IE look: 4.25 × 1.55 × 1.48 m, wheelbase 2.40, rectangular lamps in a full-width grille."""
    p = dict(L=68.0, W=24.8, H=23.6, wheelbase=38.4, front=19.0, r=4.6, clear=3.0, belt=13.8, sill=3.6, tumble=2.2,
             cowl_z=9.6, ws_top_z=2.4, roof_rear_z=-12.8, rw_bottom_z=-20.6, cabin_front=8.0, cabin_rear=-17.6,
             pillars=[(-4.8, -3.4)], corner=1.6, c_width=2.4,
             bottom=[(-34, 5.0), (-31, 3.4), (31, 3.4), (34, 5.0)],
             top=[(-34, 13.0), (-22, 13.6), (8, 13.8), (13, 13.2), (34, 12.0)])
    c = Car("moskvich412", p["L"], p["W"], p["H"])
    c.info.update(name="«Москвич-412»", name_en="Moskvich-412",
                  schemes=["sky", "cherry", "beige", "aquamarine", "white", "ochre", "green", "asphalt"])
    g = three_box(c, p)
    doors(c, g, p, [(9.6, -4.1, 0, 1), (-4.1, -16.8, 2, 3)])
    # the nose: a black full-width grille with a chrome frame, rectangular lamps at its ends, indicators under them
    rect_face(c, 0, 11.4, 7.4, 11.2, CHROME, mirror=False)
    rect_face(c, -11.0, 11.0, 7.8, 10.8, GRILLE, mirror=False)
    rect_face(c, 6.6, 10.6, 8.0, 10.6, HEAD)
    rect_face(c, 6.6, 10.6, 5.4, 6.6, TURN)
    c.body.box(-1.2, 8.2, 33.9, 1.2, 10.4, 34.6, CHROME)  # the badge
    # the tail: tall lamps at the corners (red over amber over white), the plate in a chrome-edged recess
    rect_face(c, 8.6, 12.0, 6.2, 12.4, TAIL, front=False)
    rect_face(c, 8.6, 12.0, 6.2, 7.8, TURN, front=False)
    rect_face(c, 8.6, 12.0, 7.8, 8.8, REVERSE, front=False)
    c.body.box(-4.0, 7.4, -34.6, 4.0, 9.6, -33.8, PLATE)
    bumpers(c, p, front_y=(4.8, 7.0), rear_y=(4.8, 7.0), mat=CHROME, caps=CHROME, wrap=4.5)
    c.body.box(-4.0, 3.6, 34.9, 4.0, 5.6, 35.5, PLATE)
    mirror(c, 8.6, 14.4, CHROME)
    interior(c, p, seats_front_z=-0.2, rear_z=-11.8, dash_z=7.6, steer_xyz=(6.0, 12.6, 3.8), headrest=False)
    wheels4(c, p, hub="cap")
    c.info.update(moulding=(9.6, 10.2), handles=[(-2.8, 12.4), (-15.2, 12.4)])
    return finish(c, p, Look(cabin=cabin_region(c, p), seat="#3e4a5a", trim="#454a52", grille=grille_bars(1.0, horizontal=True)))


@car
def gaz24():
    """ГАЗ-24 «Волга» (1970–1985): 4.735 × 1.800 × 1.490 m, wheelbase 2.80, 14-inch wheels, two round lamps either side of a
    wide chrome grille of vertical bars, bumpers with fangs."""
    p = dict(L=75.8, W=28.8, H=23.8, wheelbase=44.8, front=21.4, r=5.3, clear=3.0, belt=14.2, sill=3.8, tumble=2.4,
             cowl_z=9.6, ws_top_z=2.6, roof_rear_z=-16.6, rw_bottom_z=-23.6, cabin_front=8.0, cabin_rear=-20.4,
             pillars=[(-5.8, -4.2)], corner=1.6, c_width=2.6,
             bottom=[(-37.9, 5.0), (-34, 3.4), (34, 3.4), (37.9, 5.0)],
             top=[(-37.9, 13.4), (-25, 14.0), (8, 14.2), (14, 13.6), (37.9, 12.4)])
    c = Car("gaz24", p["L"], p["W"], p["H"])
    c.info.update(name="ГАЗ-24 «Волга»", name_en="GAZ-24 Volga",
                  schemes=["black", "white", "beige", "quartz", "murena", "cherry", "sky", "taxi"])
    g = three_box(c, p)
    doors(c, g, p, [(10.2, -5.0, 0, 1), (-5.0, -19.6, 2, 3)])
    rect_face(c, 0, 9.6, 6.8, 11.6, CHROME, mirror=False)
    rect_face(c, -9.0, 9.0, 7.2, 11.2, GRILLE, mirror=False)
    round_lamps(c, [11.4], 9.4, 1.7, protrude=0.0)
    rect_face(c, 9.6, 13.4, 6.4, 7.4, TURN)
    c.body.box(-0.8, 11.6, 33.0, 0.8, 12.6, 38.4, CHROME, only=[AIR, PAINT])  # the bonnet's chrome strip
    rect_face(c, 9.8, 13.6, 7.0, 12.0, TAIL, front=False)
    rect_face(c, 9.8, 13.6, 7.0, 8.2, TURN, front=False)
    c.body.box(-4.0, 8.2, -38.6, 4.0, 10.4, -37.6, PLATE)
    rect_face(c, 0, 9.0, 11.2, 11.8, CHROME, front=False, mirror=False)
    bumpers(c, p, front_y=(5.0, 7.0), rear_y=(5.0, 7.0), mat=CHROME, caps=CHROME, wrap=5.0)
    zf = p["L"] / 2
    for x in (5.0, -5.0):  # the fangs
        c.body.box(x - 0.6, 4.6, zf + 0.5, x + 0.6, 8.6, zf + 1.6, CHROME)
        c.body.box(x - 0.6, 4.6, -zf - 1.6, x + 0.6, 8.6, -zf - 0.5, CHROME)
    c.body.box(-4.0, 3.4, zf + 1.0, 4.0, 5.2, zf + 1.6, PLATE, only=[AIR])
    mirror(c, 9.2, 14.8, CHROME)
    interior(c, p, seats_front_z=-0.6, rear_z=-13.6, dash_z=7.8, steer_xyz=(7.0, 12.8, 3.8), headrest=False)
    wheels4(c, p, hub="cap", width=3.4)
    beacon(c, p["H"], -6.0)
    c.info.update(moulding=(10.2, 10.8), handles=[(-3.6, 12.9), (-18.0, 12.9)], stripe=(10.8, 13.0), word=(-5.0, 11.9, 2.5))
    return finish(c, p, Look(cabin=cabin_region(c, p), seat="#7a5b3c", trim="#5c4f42", grille=grille_bars(0.75)))


@car
def zaz968():
    """ЗАЗ-968 «Запорожец» (1971–1979): 3.73 × 1.54 × 1.40 m, wheelbase 2.16, two doors, the engine at the back breathing
    through the «ears» on the rear wings."""
    p = dict(L=59.6, W=24.6, H=22.4, wheelbase=34.6, front=17.6, r=4.4, clear=3.0, belt=13.4, sill=3.6, tumble=2.6,
             cowl_z=8.0, ws_top_z=1.4, roof_rear_z=-11.8, rw_bottom_z=-17.8, cabin_front=6.6, cabin_rear=-15.2,
             pillars=[], corner=2.2, c_width=3.2,
             bottom=[(-29.8, 5.2), (-27, 3.4), (27, 3.4), (29.8, 5.2)],
             top=[(-29.8, 12.4), (-20, 13.2), (6, 13.4), (11, 12.6), (29.8, 11.2)])
    c = Car("zaz968", p["L"], p["W"], p["H"])
    c.info.update(name="ЗАЗ-968 «Запорожец»", name_en="ZAZ-968 Zaporozhets",
                  schemes=["orange", "lime", "sky", "beige", "red", "white", "plum", "olive"])
    g = three_box(c, p)
    doors(c, g, p, [(8.4, -9.4, 0, 1)])
    X, Y, Z = c.body.grids()
    # the nose has no grille: a chrome «moustache» across, round lamps
    round_lamps(c, [8.6], 9.2, 1.6)
    rect_face(c, 0, 6.4, 8.6, 9.4, CHROME, mirror=False)
    rect_face(c, 8.0, 10.8, 6.2, 7.0, TURN)
    # the ears: air scoops standing out on the rear wings behind the doors
    half = c.half
    for sgn in (1, -1):
        ear = (X * sgn > half - 0.5) & (X * sgn < half + 1.6) & (Y > 10.0) & (Y < 13.2) & (Z > -18.6) & (Z < -10.6)
        ear &= (Y - 10.0) < (Z + 18.6) * 0.9
        c.body.set(ear, PAINT)
        c.body.set(ear & (X * sgn > half + 0.6) & (Y > 10.6) & (Y < 12.6) & (Z > -17.4) & (Z < -11.4), BLACK)
    # the tail: the engine lid's louvres, small lamps, the plate
    rect_face(c, 0, 8.0, 9.0, 11.6, GRILLE, front=False, mirror=False)
    rect_face(c, 8.8, 11.8, 7.0, 10.0, TAIL, front=False)
    rect_face(c, 8.8, 11.8, 7.0, 7.8, TURN, front=False)
    c.body.box(-3.8, 6.4, -30.6, 3.8, 8.6, -29.6, PLATE)
    bumpers(c, p, front_y=(4.8, 6.4), rear_y=(4.8, 6.4), mat=CHROME, caps=CHROME, wrap=3.0, depth=0.8)
    c.body.box(-3.8, 3.0, 30.2, 3.8, 4.8, 30.8, PLATE, only=[AIR])
    mirror(c, 7.6, 14.0, CHROME)
    interior(c, p, seats_front_z=-2.6, rear_z=-11.0, dash_z=6.6, steer_xyz=(5.8, 12.2, 2.6), headrest=False)
    wheels4(c, p, hub="cap", width=2.8)
    c.info.update(moulding=(9.4, 10.0), handles=[(-8.0, 12.2)])
    return finish(c, p, Look(cabin=cabin_region(c, p), seat="#5a3a2e", trim="#4c4440", grille=grille_bars(0.75, horizontal=True)))


@car
def vaz2114():
    """ВАЗ-2114 «Самара» (2001–2013): a five-door hatchback, 4.12 × 1.65 × 1.40 m, wheelbase 2.46; body-coloured bumpers with
    a black rubbing strip, narrow lamps either side of a slim grille."""
    p = dict(L=65.9, W=26.4, H=22.4, wheelbase=39.4, front=20.2, r=4.6, clear=2.8, belt=13.4, sill=3.6, tumble=2.4,
             cowl_z=12.6, ws_top_z=3.0, roof_rear_z=-21.4, rw_bottom_z=-30.6, cabin_front=11.0, cabin_rear=-25.4,
             pillars=[(-5.0, -3.4)], corner=2.4, c_width=2.0,
             bottom=[(-33, 4.6), (-30, 3.2), (30, 3.2), (33, 4.6)],
             top=[(-33, 13.0), (-28, 13.4), (12, 13.4), (18, 12.0), (33, 10.0)])
    c = Car("vaz2114", p["L"], p["W"], p["H"])
    c.info.update(name="ВАЗ-2114 «Самара»", name_en="VAZ-2114 Samara",
                  schemes=["silver", "plum", "asphalt", "black", "cherry", "white", "quartz", "aquamarine", "red", "police"])
    g = three_box(c, p)
    doors(c, g, p, [(12.6, -4.2, 0, 1), (-4.2, -20.6, 2, 3)])
    X, Y, Z = c.body.grids()
    # the nose: slim slanted lamps and a black grille strip between them, the bumper of body colour
    rect_face(c, 2.8, 11.6, 8.2, 9.8, HEAD)
    rect_face(c, 9.6, 11.6, 8.2, 9.8, TURN)
    rect_face(c, 0, 2.8, 8.4, 9.6, GRILLE, mirror=False)
    c.body.box(-0.8, 8.6, 32.9, 0.8, 9.4, 33.6, CHROME)
    rect_face(c, 0, 10.0, 4.4, 5.2, BLACK, mirror=False, depth=2.0, only=[PAINT])
    rect_face(c, 3.0, 8.0, 5.6, 6.8, GRILLE, depth=1.01)
    # the tail: lamps at the hatch's corners, the plate under the hatch, a black strip on the bumper
    rect_face(c, 7.6, 12.4, 8.6, 12.4, TAIL, front=False)
    rect_face(c, 7.6, 12.4, 10.8, 11.6, TURN, front=False)
    rect_face(c, 7.6, 9.0, 8.6, 10.0, REVERSE, front=False)
    c.body.box(-4.0, 8.8, -33.6, 4.0, 11.0, -32.8, PLATE)
    rect_face(c, 0, 12.0, 5.0, 5.8, BLACK, front=False, mirror=False, depth=2.0, only=[PAINT])
    c.body.box(-4.0, 6.0, 32.9, 4.0, 8.0, 33.5, PLATE, only=[AIR])
    mirror(c, 11.6, 14.0, BLACK)
    interior(c, p, seats_front_z=1.4, rear_z=-12.6, dash_z=10.4, steer_xyz=(6.2, 12.4, 5.6))
    side_rect(c, -32, 32, 6.6, 7.4, BLACK, depth=1.01, only=[PAINT])
    wheels4(c, p, hub="alloy")
    beacon(c, p["H"], -4.0)
    c.info.update(handles=[(-2.6, 12.0), (-19.0, 12.0)], stripe=(8.0, 10.0), word=(-4.0, 11.4, 2.5))
    return finish(c, p, Look(cabin=cabin_region(c, p), seat="#3b3d42", trim="#3f4146", headliner="#b9b7b0", grille=None))


def arches(c, p, X, Y, Z, half, depth=5.5):
    body = c.body
    for wz in (p["front"], p["front"] - p["wheelbase"]):
        R = np.sqrt((Y - p["r"]) ** 2 + (Z - wz) ** 2)
        body.set((R < p["r"] + 1.6) & (abs(X) > half - depth), AIR)
        well = (R < p["r"] + 2.6) & (R >= p["r"] + 1.6) & (abs(X) > half - depth) & (abs(X) < half - 0.9) & body.solid()
        above = np.zeros_like(well)
        above[:, :-2, :] = body.solid()[:, 2:, :]
        body.set(well & above, UNDER)


def van(c, p):
    """A one-box body (the UAZ «loaf», the PAZ bus): a tall box whose nose and tail follow profiles in height, the roof's edges
    rounded, a skin one cell thick round a hollow saloon. p: L, W, H, bottom (z, y) profile, nose [(y, z)], tail [(y, z)],
    round (roof edge radius), floor, belt (for the stripe), r, front, wheelbase."""
    body = c.body
    L, W, H = p["L"], p["W"], p["H"]
    hw = W / 2
    X, Y, Z = body.grids()
    nose = pl(Y, p["nose"])
    tail = pl(Y, p["tail"])
    bottom = pl(Z, p["bottom"])
    rr = p["round"]
    ax = np.abs(X)
    corner = (ax > hw - rr) & (Y > H - rr) & (np.sqrt((ax - (hw - rr)) ** 2 + (Y - (H - rr)) ** 2) > rr)
    # the plan's corners rounded too (front and back)
    pr = p.get("plan_round", 2.0)
    fc = (ax > hw - pr) & (Z > nose - pr) & (np.sqrt((ax - (hw - pr)) ** 2 + (Z - (nose - pr)) ** 2) > pr)
    rc = (ax > hw - pr) & (Z < tail + pr) & (np.sqrt((ax - (hw - pr)) ** 2 + (Z - (tail + pr)) ** 2) > pr)
    shell = (ax < hw) & (Y > bottom) & (Y < H) & (Z < nose) & (Z > tail) & ~corner & ~fc & ~rc
    body.set(shell, PAINT)
    floor = p["floor"]
    hollow = V.erode(shell) & (Y > floor) & (Z < p.get("cabin_front", 1e9)) & (Z > p.get("cabin_rear", -1e9))
    body.set(hollow, AIR)
    half = np.full(X.shape, hw) + 0 * Y + 0 * Z
    arches(c, p, X, Y, Z, half)
    c.info.update(belt=p["belt"], roof=H, floor=floor, hw=hw, cabin_z=0.0)
    c.half = half
    return dict(X=X, Y=Y, Z=Z, half=half, shell=shell, hollow=hollow)


def windows(c, g, rects, front=None, rear=None):
    """Glass: side windows (z0, z1, y0, y1) on both sides (a 4th item 'l' or 'r' keeps one side), the windshield and the rear
    window as (y0, y1, x_half, mullion) on the nose or the tail skin."""
    body = c.body
    X, Y, Z = g["X"], g["Y"], g["Z"]
    hw = c.info["hw"]
    skin = g["shell"] & ~g["hollow"]
    for r in rects:
        z0, z1, y0, y1 = r[:4]
        side = r[4] if len(r) > 4 else None
        m = skin & (abs(X) > hw - 1.5) & (Z > z0) & (Z < z1) & (Y > y0) & (Y < y1)
        if side == "l":
            m &= X > 0
        elif side == "r":
            m &= X < 0
        body.set(m & body.solid(), GLASS)
    for spec, sgn in ((front, 1), (rear, -1)):
        if spec is None:
            continue
        y0, y1, xh, mull = spec
        zz = Z * sgn
        m = skin & (abs(X) < xh) & (Y > y0) & (Y < y1) & (zz > pl(Y, [(0, 0), (c.H, 0)]) + 0)  # any z: limited below
        # only the cells of the end skin: the outermost cell along z at this x, y
        end = np.zeros_like(m)
        nz = body.shape[2]
        solid = body.solid()
        if sgn > 0:
            last = nz - 1 - np.argmax(solid[:, :, ::-1], axis=2)
        else:
            last = np.argmax(solid, axis=2)
        has = solid.any(axis=2)
        ix, iy = np.nonzero(has)
        end[ix, iy, last[ix, iy]] = True
        m &= end
        if mull:
            m &= abs(X) > mull
        body.set(m, GLASS)


def door_rect(c, g, name, z0, z1, y0, y1, side, seat, hinge="front", open_deg=75.0, seats=None):
    """A door cut from one side's skin (two cells deep), hinged at its front (or rear) edge."""
    body = c.body
    X, Y, Z = g["X"], g["Y"], g["Z"]
    hw = c.info["hw"]
    region = (X * side > hw - 2.01) & (Z > z0) & (Z < z1) & (Y > y0) & (Y < y1) & body.solid()
    hz = z1 if hinge == "front" else z0
    pi = c.part(name, "door", (side * hw, 0, hz), side=side, seat=seat, open=open_deg if hinge == "front" else -open_deg,
                door=len(c.doors), seats=seats or [])
    body.part[region] = pi
    c.doors.append(pi)
    return pi


def back_doors(c, g, x0, x1, y0, y1, seat=-1, split=True):
    """Doors in the tail (the van's two leaves), hinged at their outer edges."""
    body = c.body
    X, Y, Z = g["X"], g["Y"], g["Z"]
    zr = -c.L / 2
    tail_cells = (Z < zr + 3.0) & body.solid() & ~np.isin(body.mat, [AIR])
    out = []
    for sgn in ((1, -1) if split else (1,)):
        region = tail_cells & (X * sgn >= (0 if split else -x1)) & (abs(X) < x1) & (Y > y0) & (Y < y1)
        region &= (Z < pl(Y, [(0, zr + 2.0), (c.H, zr + 2.0)]))
        pi = c.part("door_back" + ("_left" if sgn > 0 else "_right"), "door", (sgn * x1, 0, zr), side=sgn, seat=seat, open=95.0,
                    door=len(c.doors))
        body.part[region] = pi
        c.doors.append(pi)
        out.append(pi)
    return out


@car
def niva():
    """ВАЗ-2121 «Нива» (1977–): three doors, 3.72 × 1.68 × 1.64 m, wheelbase 2.20, 16-inch wheels, round lamps in a black
    grille, high off the ground."""
    p = dict(L=59.5, W=26.9, H=26.2, wheelbase=35.2, front=18.0, r=5.5, clear=3.6, belt=16.0, sill=4.4, tumble=2.2,
             cowl_z=10.8, ws_top_z=3.6, roof_rear_z=-27.8, rw_bottom_z=-29.2, cabin_front=9.0, cabin_rear=-27.4,
             pillars=[(-8.2, -6.4)], corner=1.8, c_width=1.6,
             bottom=[(-29.75, 6.0), (-26, 4.4), (26, 4.4), (29.75, 6.0)],
             top=[(-29.75, 15.2), (-24, 16.0), (10, 16.0), (14, 15.4), (29.75, 14.4)])
    c = Car("niva", p["L"], p["W"], p["H"])
    c.info.update(name="ВАЗ-2121 «Нива»", name_en="VAZ-2121 Niva",
                  schemes=["beige", "olive", "white", "orange", "corrida", "sky", "lime", "asphalt"])
    g = three_box(c, p)
    doors(c, g, p, [(11.0, -7.4, 0, 1)])
    # the nose: a black grille panel across with round lamps at its ends
    rect_face(c, 0, 12.6, 8.8, 13.4, GRILLE, mirror=False)
    round_lamps(c, [9.4], 11.0, 1.9, ring=CHROME, ring_w=0.5)
    rect_face(c, 9.0, 12.8, 7.4, 8.6, TURN)
    c.body.box(-1.6, 10.4, 29.4, 1.6, 12.0, 30.2, CHROME)
    # the tail: the hatch's glass high, lamps low at the corners, the plate between
    rect_face(c, 9.2, 12.8, 8.8, 12.2, TAIL, front=False)
    rect_face(c, 9.2, 12.8, 11.2, 12.2, TURN, front=False)
    c.body.box(-3.8, 9.6, -30.4, 3.8, 11.8, -29.6, PLATE)
    bumpers(c, p, front_y=(6.0, 8.2), rear_y=(6.0, 8.2), mat=CHROME, caps=BLACK, wrap=3.0)
    c.body.box(-3.8, 6.2, 30.2, 3.8, 8.2, 30.8, PLATE, only=[AIR])
    # plastic wheel-arch flares
    X, Y, Z = g["X"], g["Y"], g["Z"]
    mirror(c, 10.0, 16.6, BLACK)
    interior(c, p, seats_front_z=-1.0, rear_z=-16.0, dash_z=9.0, steer_xyz=(6.6, 15.0, 4.0), seat_h=9.5, back_h=17.5)
    wheels4(c, p, hub="steel", width=3.4)
    c.info.update(handles=[(-6.0, 14.8)], steer=30.0)
    return finish(c, p, Look(cabin=cabin_region(c, p), seat="#4a3c30", trim="#3f3a35"))


@car
def uaz452():
    """УАЗ-452 «буханка» (1965–): the cab-over van, 4.36 × 1.94 × 2.07 m, wheelbase 2.30, 15-inch wheels; round lamps either
    side of a slotted grille, the roof's edges round as a loaf's; the driver's and the passenger's doors, a double door on the
    right into the saloon, two leaves at the back."""
    p = dict(L=69.8, W=31.0, H=33.0, wheelbase=36.8, front=22.0, r=6.4, floor=8.0, belt=19.0, round=8.0, plan_round=4.5,
             bottom=[(-34.9, 7.0), (-31, 6.0), (31, 6.0), (34.9, 7.0)],
             nose=[(0, 33.9), (6, 34.9), (17, 34.9), (21, 34.3), (27, 32.6), (33, 28.6)],
             tail=[(0, -34.9), (28, -34.9), (33, -33.0)])
    c = Car("uaz452", p["L"], p["W"], p["H"])
    c.info.update(name="УАЗ-452 «буханка»", name_en="UAZ-452 «Bukhanka»", kind="van",
                  schemes=["olive", "white", "sky", "beige", "green", "police", "ambulance"], steer=34.0)
    g = van(c, p)
    X, Y, Z = g["X"], g["Y"], g["Z"]
    # the cab and the saloon apart: a bulkhead behind the seats
    c.body.box(-15.4, p["floor"], 9.0, 15.4, 22.0, 10.0, TRIM, only=[AIR])
    windows(c, g, [(11.0, 23.0, 19.5, 28.5), (-30.0, -20.0, 19.5, 28.0), (-18.0, -7.0, 19.5, 28.0, "l"), (-5.0, 8.0, 19.5, 28.0, "l"),
                   (-18.0, -7.0, 19.5, 28.0, "r"), (-5.0, 8.0, 19.5, 28.0, "r")],
            front=(19.6, 28.4, 13.2, 0.6), rear=(19.6, 28.0, 12.6, 1.2))
    # nose: round lamps low and wide, the vertical slots of the grille between them, the indicators on top of the wings
    round_lamps(c, [11.0], 13.2, 2.0, ring=CHROME, ring_w=0.6)
    rect_face(c, 0, 5.6, 9.6, 16.4, GRILLE, mirror=False)
    rect_face(c, 0, 5.6, 9.6, 16.4, GRILLE, mirror=False)
    rect_face(c, 11.4, 14.6, 17.4, 18.6, TURN)
    # the back: lamps at the corners, the plate in the middle, spare-wheel-less leaves
    rect_face(c, 12.0, 15.0, 12.0, 15.6, TAIL, front=False)
    rect_face(c, 12.0, 15.0, 15.6, 16.6, TURN, front=False)
    c.body.box(-3.8, 9.2, -35.6, 3.8, 11.4, -34.8, PLATE)
    bumpers(c, p, front_y=(6.6, 8.6), rear_y=(6.6, 8.6), mat=BLACK, caps=BLACK, wrap=2.0, depth=1.2)
    c.body.box(-3.8, 6.8, 36.0, 3.8, 8.6, 36.6, PLATE, only=[AIR])
    # the cab: seats on the engine's sides, the engine cover between them, the dashboard under the windshield
    hw = c.info["hw"]
    c.body.box(-4.0, p["floor"], 14.0, 4.0, 14.0, 30.0, DASH, only=[AIR])
    c.body.box(-hw + 2, 15.0, 29.0, hw - 2, 19.0, 33.0, DASH, only=[AIR])
    for x in (8.4, -8.4):
        c.body.box(x - 3.4, p["floor"], 14.5, x + 3.4, 13.0, 20.5, SEAT, only=[AIR])
        c.body.box(x - 3.4, 13.0, 11.0, x + 3.4, 22.0, 12.6, SEAT, only=[AIR])
        c.seats.append((x, 13.0, 17.0))
    # the saloon: benches along the sides
    for sgn in (1, -1):
        c.body.box(sgn * (hw - 1.0), p["floor"], -30.0, sgn * (hw - 6.0), 12.5, -6.0, SEAT, only=[AIR])
        c.body.box(sgn * (hw - 1.0), 12.5, -30.0, sgn * (hw - 2.4), 20.0, -6.0, SEAT, only=[AIR])
        for z in (-26.0, -18.0, -10.0):
            c.seats.append((sgn * (hw - 3.6), 12.5, z))
    c.body.box(-hw + 2, p["floor"] - 0.2, -33.0, hw - 2, p["floor"] + 0.6, 30.0, FLOOR, only=[AIR, PAINT])
    steering_wheel(c, 8.4, 20.5, 25.0, r=3.4, tilt=55.0)
    c.body.box(7.9, 14.0, 26.0, 8.9, 20.0, 30.0, BLACK, only=[AIR])
    door_rect(c, g, "door_driver", 11.0, 23.0, 8.0, 29.0, 1, 0)
    door_rect(c, g, "door_passenger", 11.0, 23.0, 8.0, 29.0, -1, 1)
    door_rect(c, g, "door_saloon_front", -5.0, 8.0, 8.0, 29.0, -1, -1, hinge="front", seats=[2, 3, 4, 5, 6, 7])
    door_rect(c, g, "door_saloon_rear", -18.0, -5.0, 8.0, 29.0, -1, -1, hinge="rear", seats=[])
    back_doors(c, g, 0, 13.6, 8.0, 29.0)
    for sgn in (1, -1):  # the mirrors on their long arms
        c.body.box(sgn * hw, 24.0, 22.4, sgn * (hw + 2.4), 25.0, 23.4, BLACK)
        c.body.box(sgn * (hw + 1.6), 20.0, 22.4, sgn * (hw + 2.6), 25.0, 23.4, MIRROR)
    for z in (p["front"], p["front"] - p["wheelbase"]):
        for sgn in (1, -1):
            wheel(c, ("wheel_front" if z > 0 else "wheel_rear") + ("_left" if sgn > 0 else "_right"), sgn * (hw - 2.6), p["r"], z, p["r"], 3.8, sgn,
                  z > 0, hub="uaz")
    beacon(c, p["H"], 18.0, red=False)
    c.info.update(stripe=(12.0, 14.6), word=(-12.0, 16.4, 2.5), handles=[(13.0, 17.0), (-3.0, 17.0)])
    return finish(c, p, Look(cabin=lambda P: (abs(P[:, 0]) < hw - 0.9) & (P[:, 1] > p["floor"] - 0.6) & (P[:, 1] < p["H"] - 0.9) & (abs(P[:, 2]) < 34),
                             seat="#3c3a34", trim="#c7c1ae", headliner="#d3cdb8", grille=grille_bars(0.75)))


@car
def paz3205():
    """ПАЗ-3205 (1989–): the small town bus, 6.93 × 2.50 × 2.95 m, wheelbase 3.60, 20-inch wheels; the passengers' doors on the
    right (one in the front overhang, one before the rear axle), the driver's own door on the left, a route board over the
    split windshield."""
    p = dict(L=110.8, W=40.0, H=47.2, wheelbase=57.6, front=29.8, r=7.8, floor=14.0, belt=24.0, round=7.0, plan_round=3.0,
             bottom=[(-55.4, 9.0), (-50, 7.0), (50, 7.0), (55.4, 9.0)],
             nose=[(0, 54.6), (8, 55.4), (22, 55.4), (26, 54.8), (44, 53.0), (47.2, 51.0)],
             tail=[(0, -55.4), (42, -55.4), (47.2, -53.4)])
    c = Car("paz3205", p["L"], p["W"], p["H"], margin=4)
    c.info.update(name="ПАЗ-3205", name_en="PAZ-3205 bus", kind="bus",
                  schemes=["yellow", "orange", "cream", "sky", "white", "red"], steer=38.0)
    g = van(c, p)
    X, Y, Z = g["X"], g["Y"], g["Z"]
    hw = c.info["hw"]
    side_windows = [(z, z + 11.0, 25.0, 39.5) for z in (-50.0, -37.5, -25.0, 2.5, 15.0)]
    side_windows += [(-12.5, -1.5, 25.0, 39.5, "l"), (28.0, 39.0, 25.0, 39.5, "l"), (28.0, 39.0, 25.0, 39.5, "r")]
    windows(c, g, side_windows, front=(23.0, 42.0, 18.6, 0.8), rear=(27.0, 40.0, 16.0, 1.0))
    # the route board over the windshield, the lamps and grille low on the nose
    rect_face(c, 0, 17.0, 42.6, 45.4, BLACK, mirror=False)
    rect_face(c, 0, 12.0, 43.2, 44.8, LAMP, mirror=False)
    rect_face(c, 12.6, 18.0, 11.0, 14.6, HEAD)
    rect_face(c, 12.6, 18.0, 15.0, 16.4, TURN)
    rect_face(c, 0, 10.0, 10.6, 19.6, GRILLE, mirror=False)
    rect_face(c, 10.0, 12.0, 10.6, 19.6, CHROME)
    rect_face(c, 13.0, 18.0, 13.0, 21.0, TAIL, front=False)
    rect_face(c, 13.0, 18.0, 21.0, 22.6, TURN, front=False)
    c.body.box(-4.0, 22.6, -56.2, 4.0, 24.8, -55.4, PLATE)
    bumpers(c, p, front_y=(7.6, 10.4), rear_y=(7.6, 10.4), mat=BLACK, caps=BLACK, wrap=3.0, depth=1.4)
    c.body.box(-4.0, 7.8, 56.8, 4.0, 10.0, 57.4, PLATE, only=[AIR])
    # doors: two folding leaves on the right (drawn as one leaf each, hinged forward), the driver's small door on the left
    door_rect(c, g, "door_front", 41.0, 52.0, 6.0, 41.0, -1, -1, seats=list(range(1, 32)))
    door_rect(c, g, "door_rear", -12.5, -1.5, 6.0, 41.0, -1, -1, seats=[])
    door_rect(c, g, "door_driver", 40.0, 50.0, 14.0, 41.0, 1, 0)
    # the saloon: the driver's seat and wheel in the front left, rows of double seats both sides of the aisle
    c.body.box(-hw + 1.5, p["floor"] - 0.3, -54.0, hw - 1.5, p["floor"] + 0.6, 53.0, FLOOR, only=[AIR, PAINT])
    c.body.box(-hw + 1.5, 6.0, 40.0, -hw + 12.0, p["floor"], 53.0, FLOOR, only=[AIR, PAINT])  # the step in the front door
    c.body.box(-hw + 1.5, 14.0, 50.0, hw - 1.5, 23.4, 54.6, DASH, only=[AIR, PAINT])
    c.body.box(4.0, 22.0, 48.0, hw - 1.5, 26.0, 54.0, DASH, only=[AIR])
    c.body.box(8.0, p["floor"], 40.0, 17.0, 21.5, 46.0, SEAT, only=[AIR])
    c.body.box(8.0, 21.5, 38.6, 17.0, 34.0, 40.0, SEAT, only=[AIR])
    c.seats.append((12.5, 21.5, 43.0))
    steering_wheel(c, 12.5, 29.0, 47.0, r=4.4, tilt=60.0)
    c.body.box(12.0, 22.0, 47.0, 13.0, 28.0, 51.0, BLACK, only=[AIR])
    for z in (30.0, 22.0, 14.0, 6.0, -6.0, -14.0, -22.0, -30.0, -38.0, -46.0):
        for sgn in (1, -1):
            if sgn < 0 and -14.0 < z < 0.0:
                continue  # the rear door's well
            x0, x1 = sgn * (hw - 1.5), sgn * 5.0
            c.body.box(x0, p["floor"], z - 2.5, x1, 21.0, z + 3.0, SEAT, only=[AIR])
            c.body.box(x0, 21.0, z - 3.5, x1, 30.0, z - 2.0, SEAT, only=[AIR])
            for x in (sgn * (hw - 5.0), sgn * 8.5):
                c.seats.append((x, 21.0, z))
    c.body.box(-hw + 1.5, 36.0, -54.0, hw - 1.5, 37.0, 53.0, ALU, only=[AIR])  # the handrails
    for z in (p["front"], p["front"] - p["wheelbase"]):
        for sgn in (1, -1):
            wheel(c, ("wheel_front" if z > 0 else "wheel_rear") + ("_left" if sgn > 0 else "_right"), sgn * (hw - 3.4), p["r"], z, p["r"], 4.8, sgn,
                  z > 0, hub="uaz")
    c.info.update(band=(22.0, 24.0, "#efe9d8"), stripe=(16.0, 19.0))
    return finish(c, p, Look(cabin=lambda P: (abs(P[:, 0]) < hw - 0.9) & (P[:, 1] > 6.5) & (P[:, 1] < p["H"] - 0.9) & (abs(P[:, 2]) < 55),
                             seat="#7b3a2c", trim="#c9c2ad", headliner="#dcd6c4", grille=grille_bars(1.0, horizontal=True)))


# ---------------------------------------------------------------- driver

def build(cid):
    c = CARS[cid]()
    mesh = V.Mesh(c.voxes, c.parts)
    c.mesh = mesh
    return c


def schemes_of(c):
    return c.info.get("schemes", ["white", "corrida", "safari", "baltika", "valentina", "murena", "orange", "lime", "asphalt", "police", "militsiya"])


def pose_fn(c, open_doors=False, steer=0.0):
    def pose(part):
        role = part["role"]
        if role == "door" and open_doors:
            return V.rot_y(-part["side"] * part["open"])
        if role == "wheel" and part.get("front"):
            return V.rot_y(steer)
        if role == "steer":
            return V.rot_x(part.get("tilt", 0))
        if role == "beacon":
            return None
        return None
    return pose


def preview_car(cid, out_dir, scheme=None):
    c = build(cid)
    scheme = scheme or schemes_of(c)[0]
    atlas = c.mesh.paint(colours(c, c.look, scheme))
    os.makedirs(out_dir, exist_ok=True)
    shots = [("side", 0, 0), ("front34", 35, 18), ("rear34", 145, 18), ("front", 90, 5), ("rear", -90, 5), ("top", 0, 89),
             ("below", 30, -25), ("right", 180, 0)]
    from PIL import Image
    tiles = []
    for name, yaw, pitch in shots:
        hidden = [] if LIVERIES.get(scheme, {}).get("beacon") else ["beacon"]
        for part in c.parts:
            part["hidden"] = part["name"] in hidden
        im = V.preview(c.mesh, atlas, yaw, pitch, size=(720, 420), pose=pose_fn(c))
        tiles.append(im)
    im = V.preview(c.mesh, atlas, 30, 20, size=(720, 420), pose=pose_fn(c, open_doors=True, steer=25))
    tiles.append(im)
    W, H = 720, 420
    sheet = Image.new("RGB", (W * 3, H * 3))
    for i, t in enumerate(tiles):
        sheet.paste(t, ((i % 3) * W, (i // 3) * H))
    path = os.path.join(out_dir, f"{cid}-{scheme}.png")
    sheet.save(path)
    atlas.save(os.path.join(out_dir, f"{cid}-{scheme}-atlas.png"))
    print(path, len(c.mesh.quads), "quads", c.mesh.W, "x", c.mesh.H)


def main(argv):
    if len(argv) > 1 and argv[1] == "preview":
        out = argv[2]
        ids = argv[3:] or list(CARS)
        for cid in ids:
            scheme = None
            if ":" in cid:
                cid, scheme = cid.split(":")
            preview_car(cid, out, scheme)
        return
    export_all(argv[1:] or list(CARS))


CHANNELS = {"head": V.G_HEAD, "tail": V.G_TAIL, "blue": V.G_BLUE, "red": V.G_RED, "turn": V.G_TURN}


def export_all(ids):
    """Models, atlases, glow maps and the Java table for every car."""
    types = []
    for cid in CARS:
        c = build(cid)
        if cid in ids:
            mesh = c.mesh
            data = mesh.export(dict(id=cid))
            mdir = os.path.join(ASSETS, "models", "car")
            os.makedirs(mdir, exist_ok=True)
            with open(os.path.join(mdir, cid + ".json"), "w") as f:
                json.dump(data, f, separators=(",", ":"))
            tdir = os.path.join(ASSETS, "textures", "entity", "car", cid)
            os.makedirs(tdir, exist_ok=True)
            for old in os.listdir(tdir):
                os.remove(os.path.join(tdir, old))
            for scheme in schemes_of(c):
                mesh.paint(colours(c, c.look, scheme)).save(os.path.join(tdir, scheme + ".png"), optimize=True)
            for ch, bit in CHANNELS.items():
                if mesh.glow_mask(bit).any():
                    mesh.paint(glow_fn(bit)).save(os.path.join(tdir, "glow_" + ch + ".png"), optimize=True)
            print(cid, len(mesh.quads), "quads", mesh.W, "x", mesh.H, len(schemes_of(c)), "schemes")
        types.append(c)
    write_java(types)
    write_assets(types)


def write_assets(types):
    """The parking marker's blockstate, models and icon; the names in both languages."""
    from PIL import Image
    def dump(path, data):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            json.dump(data, f, indent=1, ensure_ascii=False)
    dump(os.path.join(ASSETS, "blockstates", "parking_spot.json"),
         {"variants": {f"facing={d}": {"model": "mobtalk:block/parking_spot"} for d in ("north", "south", "east", "west")}})
    dump(os.path.join(ASSETS, "models", "block", "parking_spot.json"), {"textures": {"particle": "mobtalk:item/parking_spot"}, "elements": []})
    dump(os.path.join(ASSETS, "models", "item", "parking_spot.json"), {"parent": "minecraft:item/generated", "textures": {"layer0": "mobtalk:item/parking_spot"}})
    # the road sign «Место стоянки» (6.4): a white P on blue in a white rim
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for x in range(1, 15):
        for y in range(1, 15):
            im.putpixel((x, y), (240, 240, 240, 255) if x in (1, 14) or y in (1, 14) else (34, 84, 176, 255))
    for x, y in [(5, 4), (6, 4), (7, 4), (8, 4), (5, 5), (9, 5), (5, 6), (9, 6), (5, 7), (6, 7), (7, 7), (8, 7), (5, 8), (5, 9), (5, 10), (5, 11),
                 (6, 5), (6, 6), (6, 8), (6, 9), (6, 10), (6, 11), (9, 7) if False else (8, 5), (8, 6)]:
        im.putpixel((x, y), (250, 250, 250, 255))
    os.makedirs(os.path.join(ASSETS, "textures", "item"), exist_ok=True)
    im.save(os.path.join(ASSETS, "textures", "item", "parking_spot.png"))
    names = {"ru_ru": {"entity.mobtalk.car": "Машина", "block.mobtalk.parking_spot": "Парковочное место (маркер машины)"},
             "en_us": {"entity.mobtalk.car": "Car", "block.mobtalk.parking_spot": "Parking spot (car marker)"}}
    for c in types:
        names["ru_ru"][f"car.mobtalk.{c.id}"] = c.info["name"]
        names["en_us"][f"car.mobtalk.{c.id}"] = c.info["name_en"]
    for lang, entries in names.items():
        path = os.path.join(ASSETS, "lang", lang + ".json")
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        data.update(entries)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
            f.write("\n")


def jf(v):
    return f"{v:.4f}f"


def write_java(types):
    rows = []
    for c in types:
        sch = schemes_of(c)
        names = [LIVERIES[s]["name"] if s in LIVERIES else COLOURS[s][0] for s in sch]
        beacon = [s for s in sch if LIVERIES.get(s, {}).get("beacon")]
        front = max(w["z"] for w in c.wheels)
        rear = min(w["z"] for w in c.wheels)
        seat_door = []
        for si in range(len(c.seats)):
            hit = [c.parts[d]["door"] for d in c.doors if c.parts[d].get("seat") in (si, -1) or si in (c.parts[d].get("seats") or [])]
            seat_door.append(hit[0] if hit else (c.parts[c.doors[0]]["door"] if c.doors else -1))
        seats = ", ".join(f"new Seat({jf(x / M)}, {jf(y / M)}, {jf(z / M)})" for x, y, z in c.seats)
        rows.append(
            f'        new Type("{c.id}", "{c.info["name"]}", "{c.info["name_en"]}", {jf(c.L / M)}, {jf(c.W / M)}, {jf(c.H / M)},\n'
            f'                {jf(c.wheels[0]["r"] / M)}, {jf(front / M)}, {jf(rear / M)}, {jf(c.info.get("steer", 32.0))}, "{c.info.get("kind", "car")}",\n'
            f'                List.of({seats}),\n'
            f'                List.of({", ".join(str(x) for x in seat_door)}), {len(c.doors)},\n'
            f'                List.of({", ".join(chr(34) + s + chr(34) for s in sch)}),\n'
            f'                List.of({", ".join(chr(34) + s + chr(34) for s in names)}),\n'
            f'                Set.of({", ".join(chr(34) + s + chr(34) for s in beacon)}))')
    src = f"""package io.github.mobtalk.game.city;

import java.util.List;
import java.util.Set;

/** GENERATED by tools/city2/cars.py — do not edit. The city's car types at their real sizes (blocks = metres): the seats
 * (driver first, on the left, +x) and the door each one is reached by, the axles, the paint schemes (factory colours and liveries) and which schemes carry a beacon.
 * The models are assets/mobtalk/models/car/<id>.json, the textures textures/entity/car/<id>/<scheme>.png. */
public final class CarTypes {{
    private CarTypes() {{ }}

    /** A seat: the point (car space: x left, y up, z forward) a sitter's seat surface is at. */
    public record Seat(float x, float y, float z) {{ }}

    /** kind: car, van or bus. */
    public record Type(String id, String name, String nameEn, float length, float width, float height, float wheelRadius,
                       float frontAxle, float rearAxle, float maxSteer, String kind, List<Seat> seats, List<Integer> seatDoor, int doors,
                       List<String> schemes,
                       List<String> schemeNames, Set<String> beaconSchemes) {{
        public int scheme(String name) {{ int i = schemes.indexOf(name); return Math.max(0, i); }}
        public float wheelBase() {{ return frontAxle - rearAxle; }}
    }}

    public static final List<Type> ALL = List.of(
{(","+chr(10)).join(rows)});

    public static Type get(String id) {{
        for (Type t : ALL) if (t.id().equals(id)) return t;
        return null;
    }}
}}
"""
    os.makedirs(os.path.dirname(JAVA), exist_ok=True)
    with open(JAVA, "w") as f:
        f.write(src)


if __name__ == "__main__":
    main(sys.argv)
