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
                    UNDER, GRILLE, BLUE, RED, TRIM, PAINT2, ALU, LAMP, REVERSE, MIRROR, CANVAS, RUST, WOOD, TANK, FRONT,
                    Vox, pl)
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
    "sage": ("Серо-зелёный", "#8fa39a"),
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


PAINTS = (PAINT, PAINT2, FRONT, TANK)

BAYER = np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) / 16.0


def colours(car, look, scheme):
    """The painter of one scheme: returns fn(mesh) -> texel colours."""
    liv = LIVERIES.get(scheme)
    combo = car.info.get("combos", {}).get(scheme)
    paint = rgb(liv["base"]) if liv else rgb(combo["paint"]) if combo else rgb(COLOURS[scheme][1])
    combo = combo or {}

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
            CHROME: rgb("#c9ced3"), BLACK: rgb("#262628"), TIRE: rgb("#232325"), RIM: rgb("#8d9093"), HEAD: rgb("#cdd6d9"),
            TAIL: rgb("#b0231d"), TURN: rgb("#e08a2c"), SEAT: look.seat, DASH: look.dash, FLOOR: look.floor,
            PLATE: rgb("#e8e8e2"), STEER: rgb("#1e1e20"), UNDER: rgb("#3a3836"), GRILLE: rgb("#1c1c1e"), BLUE: rgb("#2f5fe0"),
            RED: rgb("#d8261e"), TRIM: look.trim, ALU: rgb("#b4b8bb"), LAMP: rgb("#f1eedc"), REVERSE: rgb("#e9e9e4"),
            MIRROR: rgb("#9fb3bf"), CANVAS: rgb("#5b5e45"), RUST: rgb("#7a4a2a"), PAINT2: rgb("#e9e7e0"),
            WOOD: rgb(combo.get("wood", "#7a6a50")), TANK: rgb(combo.get("tank", "#e5bf1e")),
            FRONT: rgb(combo["front"]) if "front" in combo else paint,
        }
        for m_, c in base.items():
            put(mat == m_, c)
        pm = mat == PAINT
        put(pm, paint)
        if "lower" in combo:
            put((mat == PAINT) & (P[:, 1] < combo["lower_y"]) & (mesh.vid == 0), rgb(combo["lower"]))
        put(mat == PAINT2, rgb(combo["paint2"]) if "paint2" in combo else paint if combo else base[PAINT2])
        pm = np.isin(mat, PAINTS)
        # faces: tops a little lighter, undersides dark
        up, down = d == 2, d == 3
        out[pm & up, :3] = ramp_col(out[pm & up, :3], 1.06)
        low = P[:, 1] < car.info.get("belt", 1e9) - 2.0
        out[np.isin(mat, PAINTS) & down & low, :3] = base[UNDER]
        out[np.isin(mat, PAINTS) & down & ~low, :3] *= 0.86
        # inside the cabin: trim on the walls, the headliner overhead, the floor mat
        if look.cabin is not None:
            cz = car.info.get("cabin_z", 0.0)
            toward = np.stack([-P[:, 0], np.zeros(n), cz - P[:, 2]], 1)
            belt_y = car.info.get("belt", 0)
            # sideways faces looking into the cabin; floors under the belt line; ceilings (the roof's underside) high up
            inward = ((np.einsum("ij,ij->i", nrm, toward) > 0) & (d != 2) & (d != 3)) | ((d == 2) & (P[:, 1] < belt_y)) | \
                     ((d == 3) & (P[:, 1] > car.info.get("roof", 1e9) - 2.5))
            inside = look.cabin(P) & np.isin(mat, PAINTS + (UNDER,)) & inward
            put(inside & (down), look.headliner)
            put(inside & ~down & ~up, look.trim)
            put(inside & up, look.floor)
        # glass: dark blue-green, a light diagonal streak, see-through
        g = mat == GLASS
        streak = ((P[:, 2] + P[:, 1] * 0.9 + P[:, 0] * 0.7) % 22.0) < 1.6
        soft = (((P[:, 2] + P[:, 1] * 0.9 + P[:, 0] * 0.7) % 22.0) >= 1.6) & (((P[:, 2] + P[:, 1] * 0.9 + P[:, 0] * 0.7) % 22.0) < 3.0)
        put(g, rgb("#3b5866"))
        out[g, 3] = 62
        put(g & streak, rgb("#9fbecb"))
        out[g & streak, 3] = 92
        put(g & soft, rgb("#6d8c99"))
        out[g & soft, 3] = 74
        # the windshield clearest of all: only a faint tint and a single soft glint
        if "ws_z" in car.info:
            ws = g & ((d == 4) | (d == 2)) & (P[:, 2] > car.info["ws_z"])
            put(ws, rgb("#4a6a78"))
            out[ws, 3] = 40
            put(ws & streak, rgb("#a9c6d2"))
            out[ws & streak, 3] = 70
        # seen from inside the glass is clear, with no streaks (they filled the driver's view)
        if look.cabin is not None:
            cz = car.info.get("cabin_z", 0.0)
            into = np.einsum("ij,ij->i", nrm, np.stack([-P[:, 0], car.info.get("roof", 20) * 0.6 - P[:, 1], cz - P[:, 2]], 1)) > 0
            gi = g & into
            put(gi, rgb("#45626e"))
            out[gi, 3] = 30
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
        # lamps: a ribbed lens, darker toward its rim, a glint in the headlamps
        anyedge = np.any(mesh.edge, axis=1)
        for lm in (HEAD, TAIL, TURN, LAMP, REVERSE, BLUE, RED):
            mm = mat == lm
            grid = ((tu % 3 == 0) | (tv % 3 == 0)) & mm
            out[grid, :3] = ramp_col(out[grid, :3], 0.82 if lm != HEAD else 0.88)
            out[mm & anyedge, :3] = ramp_col(out[mm & anyedge, :3], 0.72)
        hm = (mat == HEAD) & ~anyedge
        put(hm & (dith < 0.12), rgb("#ffffff"))
        put(hm & (dith > 0.88), rgb("#9fb0b8"))
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
            look.grille(mesh, mat == GRILLE, out, base)
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
        for z0, z1, y0, y1 in car.info.get("louvre", []):
            # louvres: dark slits with a light lip over each
            lm = sides_all & (P[:, 2] > z0) & (P[:, 2] < z1) & (P[:, 1] > y0) & (P[:, 1] < y1)
            ph = (P[:, 1] - y0) % 1.0
            put(lm & (ph < 0.5), rgb("#1d1e1c"))
            out[lm & (ph >= 0.5), :3] = ramp_col(out[lm & (ph >= 0.5), :3], 1.15)
        if look.extra:
            look.extra(mesh, out, scheme)
        # curves built of steps are shaded as the curves they stand for: every texel lit as Minecraft would light the
        # smoothed body there (relative to the light its own face gets), a glossy line along the shoulders
        ns = mesh.smooth_normal()
        yaws = range(0, 360, 45)
        ratio = np.clip(V.mc_light(ns, yaws) / V.mc_light(nrm, yaws), 0.55, 1.7)
        curved = np.einsum("ij,ij->i", ns, nrm) < 0.9
        # only the steps of a real curve: flat panels keep their own light (the wheel arches' hollows would smudge them)
        ratio[~curved] = 1.0
        body_t = (mesh.vid == 0) & (mat != GLASS)
        out[body_t, :3] *= ratio[body_t][:, None]
        gloss = body_t & np.isin(mat, PAINTS) & (ns[:, 1] > 0.45) & (ns[:, 1] < 0.8)
        out[gloss, :3] = ramp_col(out[gloss, :3], 1.08)
        # pixel-art bevels: a lighter top edge, a darker bottom edge on every flat surface; seams of doors and lids dark
        e = mesh.edge.copy()
        e[curved & body_t] = False
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
        sm = mesh.seam & np.isin(mat, PAINTS)
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
    a_pillar = (Z > front_edge - p.get("a_width", 2.4)) & side
    c_pillar = (Z < rear_edge + p.get("c_width", 2.4)) & side
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
        # the big rectangular headlamp blocks (two lenses each behind one glass) in chrome rims, the indicators wrapping the
        # corners, and between them the wide chrome-framed grille of fine mesh standing out a cell, the bonnet's bulge over it
        body.set(face & (abs(X) > 5.0) & (abs(X) < 11.2) & (Y > 7.6) & (Y < 11.8), CHROME)
        body.set(face & (abs(X) > 5.5) & (abs(X) < 10.7) & (Y > 8.1) & (Y < 11.3), HEAD)
        body.set(face & (abs(X) > 7.9) & (abs(X) < 8.4) & (Y > 8.1) & (Y < 11.3), CHROME)
        body.set(face & (abs(X) > 11.2) & (Y > 7.4) & (Y < 10.4), TURN)
        body.set((Z > zf - 3.5) & (abs(X) > half - 1.01) & (Y > 7.4) & (Y < 10.4) & body.solid(), TURN)
        body.box(-5.0, 7.0, zf - 1, 5.0, 12.8, zf + 1, CHROME)
        body.box(-4.5, 7.5, zf - 0.5, 4.5, 12.3, zf + 1.01, GRILLE)
        body.box(-1.2, 9.6, zf + 0.9, 1.2, 11.0, zf + 1.4, CHROME)
        # the bonnet's raised middle over the grille, running back to the windshield
        body.set((abs(X) < 5.0) & (Y > 12.6) & (Y < 13.6) & (Z > p.get("cowl_z", 0) + 1) & (Z < zf - 0.2) & ~body.solid()
                 & (Y < pl(Z, p["top"]) + 0.8), PAINT)  # the badge in the grille's middle
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


def grille_mesh(step=1.0, colour="#9a9fa4"):
    """A grille of fine bars both ways (the Zhiguli's)."""
    def fn(mesh, sel, out, pal=None):
        P = mesh.P
        bars = sel & (((np.floor(P[:, 0] / step * 2) % 2) == 0) | ((np.floor(P[:, 1] / step * 2) % 2) == 0))
        out[bars, :3] = rgb(colour)
    return fn


def grille_bars(step=1.0, horizontal=False, frame=None, of=None):
    """Bars across a grille, light metal or (`of`) the colour of another material, such as the face they are cut in."""
    def fn(mesh, sel, out, pal=None):
        q = mesh.P[:, 1] if horizontal else mesh.P[:, 0]
        bars = sel & ((np.floor(q / step * 2) % 2) == 0)
        out[bars, :3] = pal[of] if of is not None and pal is not None else rgb("#a9aeb3")
    return fn


def finish(c, p, look):
    c.look = look
    # the roof's underside over each seat (a rounded roof is lower over the outer seats than at its crown)
    X, Y, Z = (np.broadcast_to(a, c.body.mat.shape) for a in c.body.grids())
    solid = c.body.solid()
    top = c.info.get("ceiling", c.H)
    c.info["seat_ceiling"] = []
    for sx, sy, sz in c.seats:
        col = solid & (abs(X - sx) < 1.0) & (abs(Z - sz) < 1.0) & (Y > sy + 10.0)
        c.info["seat_ceiling"].append(min(top, Y[col].min() - 0.5) if col.any() else top)
    c.info.setdefault("stripe", (c.info["belt"] - 3.5, c.info["belt"] - 1.5))
    c.info.setdefault("ws_z", c.info.get("cabin_z", 0.0))
    return c


@car
def vaz2107():
    """ВАЗ-2107 «семёрка» (1982–2012): 4.145 × 1.620 × 1.435 m, wheelbase 2.424, 13-inch wheels."""
    # measured off a clean side photo (1 unit = 1/16 m): front overhang 0.6 m, rear 1.1 m; the greenhouse long and upright
    # (roof 11 → -14.5), the B pillar at the middle, the bonnet and the boot high and flat, the belt at 0.89 m
    p = dict(L=66.3, W=25.9, H=23.0, wheelbase=38.8, front=23.4, r=4.6, clear=3.0, belt=14.2, sill=3.6, tumble=1.4,
             cowl_z=16.4, ws_top_z=10.8, roof_rear_z=-14.4, rw_bottom_z=-19.8, cabin_front=14.6, cabin_rear=-17.6,
             pillars=[(-0.4, 1.2)], corner=1.2, a_width=2.4, c_width=3.2,
             bottom=[(-33.2, 4.6), (-30, 3.4), (30, 3.4), (33.2, 4.6)],
             top=[(-33.2, 12.8), (-29, 13.4), (-20.5, 14.0), (16.4, 14.2), (28, 13.4), (33.2, 12.8)])
    c = Car("vaz2107", p["L"], p["W"], p["H"])
    c.info.update(name="ВАЗ-2107 «Жигули»", name_en="VAZ-2107 Zhiguli")
    g = three_box(c, p)
    doors(c, g, p, [(16.2, 0.4, 0, 1), (0.4, -14.6, 2, 3)])
    lada_classic_front(c, g, p, "2107")
    lada_classic_rear(c, g, p, "2107")
    bumpers(c, p, front_y=(5.0, 7.6), rear_y=(5.0, 7.6))
    mirror(c, 15.4, 14.8)
    interior(c, p, seats_front_z=4.6, rear_z=-9.6, dash_z=14.0, steer_xyz=(6.4, 13.0, 10.6))
    for z in (p["front"], p["front"] - p["wheelbase"]):
        for sgn in (1, -1):
            wheel(c, ("wheel_front" if z > 0 else "wheel_rear") + ("_left" if sgn > 0 else "_right"), sgn * (p["W"] / 2 - 2.0),
                  p["r"], z, p["r"], 3.0, sgn, z > 0, hub="cap")
    beacon(c, p["H"], -4.0)
    c.body.box(-4.0, 5.2, p["L"] / 2 + 0.9, 4.0, 7.4, p["L"] / 2 + 1.6, PLATE)
    c.info.update(word=(-4.0, 11.75, 2.5), stripe=(8.4, 10.4), moulding=(8.4, 9.0), handles=[(2.6, 12.6), (-12.2, 12.6)])
    return finish(c, p, Look(cabin=cabin_region(c, p), seat="#5e3d2b", trim="#4e4842", grille=grille_mesh(1.0)))


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


def side_cells(c, side, depth=2.01):
    """A side's skin (side +1: +x, -1: -x): per y, z row the outermost solid cells along x, `depth` units deep."""
    solid = c.body.solid()
    nx = c.body.shape[0]
    k = np.arange(nx)[:, None, None]
    cells = max(1, int(round(depth / c.body.s)))
    if side > 0:
        last = nx - 1 - np.argmax(solid[::-1, :, :], axis=0)
        m = (k > last[None] - cells) & (k <= last[None])
    else:
        last = np.argmax(solid, axis=0)
        m = (k < last[None] + cells) & (k >= last[None])
    return m & solid & solid.any(axis=0)[None]


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
    # the greenhouse measured off a side photo: the roof runs back to -16.4, the B pillar behind the middle
    p = dict(L=68.0, W=24.8, H=23.6, wheelbase=38.4, front=19.0, r=4.6, clear=3.0, belt=13.8, sill=3.6, tumble=2.2,
             cowl_z=10.2, ws_top_z=2.6, roof_rear_z=-16.4, rw_bottom_z=-21.8, cabin_front=8.0, cabin_rear=-19.6,
             pillars=[(-8.0, -6.6)], corner=1.6, a_width=2.4, c_width=2.6,
             bottom=[(-34, 5.0), (-31, 3.4), (31, 3.4), (34, 5.0)],
             top=[(-34, 13.0), (-22, 13.6), (8, 13.8), (13, 13.2), (34, 12.0)])
    c = Car("moskvich412", p["L"], p["W"], p["H"])
    c.info.update(name="«Москвич-412»", name_en="Moskvich-412",
                  schemes=["sky", "cherry", "beige", "aquamarine", "white", "ochre", "green", "asphalt"])
    g = three_box(c, p)
    doors(c, g, p, [(8.8, -7.3, 0, 1), (-7.3, -19.8, 2, 3)])
    # the nose: a full-width chrome grille of fine vertical bars, the round lamps in chrome rings at its ends, indicators
    # under them, the badge in the middle; the bumper has rubber-tipped fangs
    rect_face(c, 0, 11.6, 6.8, 11.6, CHROME, mirror=False)
    rect_face(c, -7.4, 7.4, 7.4, 11.0, GRILLE, mirror=False)
    round_lamps(c, [9.4], 9.2, 1.8, ring=CHROME, ring_w=0.6)
    rect_face(c, 6.6, 10.6, 5.4, 6.6, TURN)
    c.body.box(-1.2, 8.2, 33.9, 1.2, 10.4, 34.6, CHROME)  # the badge
    for x in (4.6, -4.6):
        c.body.box(x - 0.6, 4.2, 34.6, x + 0.6, 8.0, 35.8, CHROME)
        c.body.box(x - 0.6, 6.4, 35.2, x + 0.6, 8.0, 36.0, BLACK)
    # the tail: tall lamps at the corners (red over amber over white), the plate in a chrome-edged recess
    rect_face(c, 8.6, 12.0, 6.2, 12.4, TAIL, front=False)
    rect_face(c, 8.6, 12.0, 6.2, 7.8, TURN, front=False)
    rect_face(c, 8.6, 12.0, 7.8, 8.8, REVERSE, front=False)
    c.body.box(-4.0, 7.4, -34.6, 4.0, 9.6, -33.8, PLATE)
    bumpers(c, p, front_y=(4.8, 7.0), rear_y=(4.8, 7.0), mat=CHROME, caps=CHROME, wrap=4.5)
    c.body.box(-4.0, 3.6, 34.9, 4.0, 5.6, 35.5, PLATE)
    mirror(c, 8.6, 14.4, CHROME)
    interior(c, p, seats_front_z=-0.6, rear_z=-14.4, dash_z=7.6, steer_xyz=(6.0, 12.6, 3.8), headrest=False)
    wheels4(c, p, hub="cap")
    c.info.update(moulding=(9.6, 10.2), handles=[(-5.8, 12.4), (-18.6, 12.4)])
    return finish(c, p, Look(cabin=cabin_region(c, p), seat="#3e4a5a", trim="#454a52", grille=grille_bars(1.0)))


@car
def gaz24():
    """ГАЗ-24 «Волга» (1970–1985): 4.735 × 1.800 × 1.490 m, wheelbase 2.80, 14-inch wheels, two round lamps either side of a
    wide chrome grille of vertical bars, bumpers with fangs."""
    # measured off a side photo (1 unit = 1/16 m): front overhang 0.75 m, the long rear one 1.2 m; the roof from 9.4 to -14.2,
    # the B pillar at the middle
    p = dict(L=75.8, W=28.8, H=23.8, wheelbase=44.8, front=26.0, r=5.3, clear=3.0, belt=14.6, sill=3.8, tumble=1.6,
             cowl_z=15.8, ws_top_z=9.4, roof_rear_z=-14.2, rw_bottom_z=-21.2, cabin_front=14.0, cabin_rear=-18.4,
             pillars=[(-0.8, 0.8)], corner=1.6, a_width=2.4, c_width=2.8,
             bottom=[(-37.9, 5.0), (-34, 3.4), (34, 3.4), (37.9, 5.0)],
             top=[(-37.9, 13.0), (-30, 13.8), (-21.2, 14.4), (15.8, 14.6), (28, 13.6), (37.9, 12.8)])
    c = Car("gaz24", p["L"], p["W"], p["H"])
    c.info.update(name="ГАЗ-24 «Волга»", name_en="GAZ-24 Volga",
                  schemes=["black", "white", "beige", "quartz", "murena", "cherry", "sky", "taxi"])
    g = three_box(c, p)
    doors(c, g, p, [(15.8, 0.0, 0, 1), (0.0, -15.0, 2, 3)])
    rect_face(c, 0, 13.6, 6.6, 12.2, CHROME, mirror=False)
    rect_face(c, -9.0, 9.0, 7.0, 11.8, GRILLE, mirror=False)
    round_lamps(c, [11.4], 9.4, 1.8, protrude=0.0)
    rect_face(c, 9.6, 13.4, 6.4, 7.4, TURN)
    X, Y, Z = g["X"], g["Y"], g["Z"]
    top = pl(Z, p["top"])
    sol = c.body.solid()
    surf = sol & ~np.roll(sol, -1, axis=1)
    c.body.set((abs(X) < 0.8) & (Z > 17.0) & surf & (c.body.mat == PAINT), CHROME)  # the bonnet's chrome strip
    # the tall tail lamps in chrome: red over amber over the white reverse light
    rect_face(c, 10.2, 14.0, 6.4, 12.8, CHROME, front=False)
    rect_face(c, 10.7, 13.5, 9.8, 12.4, TAIL, front=False)
    rect_face(c, 10.7, 13.5, 7.9, 9.6, TURN, front=False)
    rect_face(c, 10.7, 13.5, 6.8, 7.7, REVERSE, front=False)
    c.body.box(-4.0, 8.2, -38.6, 4.0, 10.4, -37.6, PLATE)
    rect_face(c, 0, 9.0, 11.2, 11.8, CHROME, front=False, mirror=False)
    bumpers(c, p, front_y=(5.0, 7.0), rear_y=(5.0, 7.0), mat=CHROME, caps=CHROME, wrap=5.0)
    zf = p["L"] / 2
    for x in (5.0, -5.0):  # the fangs
        c.body.box(x - 0.6, 4.6, zf + 0.5, x + 0.6, 8.6, zf + 1.6, CHROME)
        c.body.box(x - 0.6, 4.6, -zf - 1.6, x + 0.6, 8.6, -zf - 0.5, CHROME)
    c.body.box(-4.0, 3.4, zf + 1.0, 4.0, 5.2, zf + 1.6, PLATE, only=[AIR])
    mirror(c, 15.2, 15.2, CHROME)
    interior(c, p, seats_front_z=4.4, rear_z=-9.6, dash_z=13.6, steer_xyz=(7.0, 13.2, 10.4), headrest=False)
    wheels4(c, p, hub="cap", width=3.4)
    beacon(c, p["H"], -6.0)
    c.info.update(moulding=(10.2, 10.8), handles=[(2.2, 13.2), (-12.8, 13.2)], stripe=(10.8, 13.0), word=(-5.0, 11.9, 2.5))
    return finish(c, p, Look(cabin=cabin_region(c, p), seat="#7a5b3c", trim="#5c4f42", grille=grille_bars(1.0)))


@car
def zaz968():
    """ЗАЗ-968 «Запорожец» (1971–1979): 3.73 × 1.54 × 1.40 m, wheelbase 2.16, two doors, the engine at the back breathing
    through the «ears» on the rear wings."""
    # measured off a side photo (1 unit = 1/16 m): the roof from 7.8 to -13.1, the door 14 → -2, the axles at 19.2 / -15.2
    p = dict(L=59.6, W=24.6, H=22.4, wheelbase=34.4, front=19.2, r=4.4, clear=3.0, belt=13.4, sill=3.6, tumble=1.8,
             cowl_z=13.6, ws_top_z=7.8, roof_rear_z=-13.0, rw_bottom_z=-17.2, cabin_front=12.0, cabin_rear=-15.0,
             pillars=[(-3.2, -1.2)], corner=2.2, a_width=2.4, c_width=3.0,
             bottom=[(-29.8, 5.2), (-27, 3.4), (27, 3.4), (29.8, 5.2)],
             top=[(-29.8, 11.8), (-24, 13.2), (-17.2, 13.6), (13.6, 13.6), (22, 12.6), (29.8, 11.4)])
    c = Car("zaz968", p["L"], p["W"], p["H"])
    c.info.update(name="ЗАЗ-968 «Запорожец»", name_en="ZAZ-968 Zaporozhets",
                  schemes=["orange", "lime", "sky", "beige", "red", "white", "plum", "olive"])
    g = three_box(c, p)
    doors(c, g, p, [(14.0, -2.2, 0, 1)])
    X, Y, Z = c.body.grids()
    # the nose has no grille: a chrome «moustache» across, round lamps
    round_lamps(c, [8.6], 9.2, 1.6)
    rect_face(c, 0, 6.4, 8.6, 9.4, CHROME, mirror=False)
    rect_face(c, 8.0, 10.8, 6.2, 7.0, TURN)
    # the ears: air scoops standing out on the rear wings behind the doors
    half = c.half
    for sgn in (1, -1):
        ear = (X * sgn > half - 0.5) & (X * sgn < half + 1.6) & (Y > 10.0) & (Y < 13.2) & (Z > -12.0) & (Z < -3.6)
        ear &= (Y - 10.0) < (Z + 12.0) * 0.9
        c.body.set(ear, PAINT)
        c.body.set(ear & (X * sgn > half + 0.6) & (Y > 10.6) & (Y < 12.6) & (Z > -10.8) & (Z < -4.4), BLACK)
    # the tail: the engine lid's louvres, small lamps, the plate
    rect_face(c, 0, 8.0, 9.0, 11.6, GRILLE, front=False, mirror=False)
    rect_face(c, 8.8, 11.8, 7.0, 10.0, TAIL, front=False)
    rect_face(c, 8.8, 11.8, 7.0, 7.8, TURN, front=False)
    c.body.box(-3.8, 6.4, -30.6, 3.8, 8.6, -29.6, PLATE)
    bumpers(c, p, front_y=(4.8, 6.4), rear_y=(4.8, 6.4), mat=CHROME, caps=CHROME, wrap=3.0, depth=0.8)
    c.body.box(-3.8, 3.0, 30.2, 3.8, 4.8, 30.8, PLATE, only=[AIR])
    mirror(c, 13.0, 14.0, CHROME)
    interior(c, p, seats_front_z=3.2, rear_z=-8.6, dash_z=11.8, steer_xyz=(5.8, 12.6, 8.8), headrest=False)
    wheels4(c, p, hub="cap", width=2.8)
    c.info.update(moulding=(9.4, 10.0), handles=[(-0.8, 12.2)])
    return finish(c, p, Look(cabin=cabin_region(c, p), seat="#5a3a2e", trim="#4c4440", grille=grille_bars(1.0, horizontal=True)))


@car
def vaz2114():
    """ВАЗ-2114 «Самара» (2001–2013): a five-door hatchback, 4.12 × 1.65 × 1.40 m, wheelbase 2.46; body-coloured bumpers with
    a black rubbing strip, narrow lamps either side of a slim grille."""
    p = dict(L=65.9, W=26.4, H=22.4, wheelbase=39.4, front=20.2, r=4.6, clear=2.8, belt=13.4, sill=3.6, tumble=2.4,
             cowl_z=12.6, ws_top_z=3.0, roof_rear_z=-21.4, rw_bottom_z=-30.6, cabin_front=11.0, cabin_rear=-25.4,
             pillars=[(-5.0, -3.4)], corner=2.4, c_width=2.0,
             bottom=[(-33, 4.6), (-30, 3.2), (30, 3.2), (33, 4.6)],
             top=[(-33, 13.0), (-28, 13.4), (12, 13.4), (19, 12.4), (33, 11.2)])
    c = Car("vaz2114", p["L"], p["W"], p["H"])
    c.info.update(name="ВАЗ-2114 «Самара»", name_en="VAZ-2114 Samara",
                  schemes=["silver", "plum", "asphalt", "black", "cherry", "white", "quartz", "aquamarine", "red", "police"])
    g = three_box(c, p)
    doors(c, g, p, [(12.6, -4.2, 0, 1), (-4.2, -20.6, 2, 3)])
    X, Y, Z = c.body.grids()
    # the nose: slim slanted lamps and a black grille strip between them, the bumper of body colour
    # the lamp units are big (the 2114's «eyes»): the head lamp's top slants down to the outer amber indicator
    rect_face(c, 3.2, 12.4, 7.0, 11.0, CHROME)
    lamp = face_cells(c) & (abs(X) > 3.5) & (abs(X) < 12.1) & (Y > 7.2) & (Y < 10.8 - np.maximum(abs(X) - 9.0, 0) * 0.5)
    c.body.set(lamp, HEAD)
    c.body.set(lamp & (abs(X) > 10.2), TURN)
    rect_face(c, -1, 3.2, 8.4, 10.0, GRILLE)
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


def arches(c, p, X, Y, Z, half, depth=5.5, grow=1.6):
    body = c.body
    for wz in (p["front"], p["front"] - p["wheelbase"]):
        R = np.sqrt((Y - p["r"]) ** 2 + (Z - wz) ** 2)
        body.set((R < p["r"] + grow) & (abs(X) > half - depth), AIR)
        well = (R < p["r"] + grow + 1.0) & (R >= p["r"] + grow) & (abs(X) > half - depth) & (abs(X) < half - 0.9) & body.solid()
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
    region = (X * side > hw - 2.01 - side_inset(c, Y))
    region = region & (Z > z0) & (Z < z1) & (Y > y0) & (Y < y1) & body.solid()
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
    # checked against a side photo: the belt high (1.06 m), the bonnet nearly as high, the axles at 19 / -16.6
    p = dict(L=59.5, W=26.9, H=26.2, wheelbase=35.2, front=19.0, r=5.5, clear=3.6, belt=17.0, sill=4.4, tumble=2.2,
             cowl_z=10.8, ws_top_z=3.6, roof_rear_z=-27.8, rw_bottom_z=-29.2, cabin_front=9.0, cabin_rear=-27.4,
             pillars=[(-8.2, -6.4)], corner=1.8, c_width=1.6,
             bottom=[(-29.75, 6.0), (-26, 4.4), (26, 4.4), (29.75, 6.0)],
             top=[(-29.75, 16.0), (-24, 17.0), (10.8, 17.0), (17, 16.2), (29.75, 15.2)])
    c = Car("niva", p["L"], p["W"], p["H"])
    c.info.update(name="ВАЗ-2121 «Нива»", name_en="VAZ-2121 Niva",
                  schemes=["beige", "olive", "white", "orange", "corrida", "sky", "lime", "asphalt"])
    g = three_box(c, p)
    doors(c, g, p, [(11.0, -7.4, 0, 1)])
    # the nose: a black grille panel across with round lamps at its ends
    rect_face(c, -1, 12.6, 9.2, 14.2, GRILLE)
    round_lamps(c, [9.4], 11.7, 1.9, ring=CHROME, ring_w=0.5)
    rect_face(c, 9.0, 12.8, 7.4, 8.6, TURN)
    c.body.box(-1.6, 11.0, 29.4, 1.6, 12.6, 30.2, CHROME)
    # the tail: the hatch's glass high, lamps low at the corners, the plate between
    rect_face(c, 9.2, 12.8, 8.8, 12.2, TAIL, front=False)
    rect_face(c, 9.2, 12.8, 11.2, 12.2, TURN, front=False)
    c.body.box(-3.8, 9.6, -30.4, 3.8, 11.8, -29.6, PLATE)
    bumpers(c, p, front_y=(6.0, 8.2), rear_y=(6.0, 8.2), mat=CHROME, caps=BLACK, wrap=3.0)
    c.body.box(-3.8, 6.2, 30.2, 3.8, 8.2, 30.8, PLATE, only=[AIR])
    # plastic wheel-arch flares
    X, Y, Z = g["X"], g["Y"], g["Z"]
    mirror(c, 10.0, 17.6, BLACK)
    interior(c, p, seats_front_z=-1.0, rear_z=-16.0, dash_z=9.0, steer_xyz=(6.6, 15.8, 4.0), seat_h=9.5, back_h=17.5)
    wheels4(c, p, hub="steel", width=3.4)
    c.info.update(handles=[(-6.0, 15.6)], steer=30.0)
    return finish(c, p, Look(cabin=cabin_region(c, p), seat="#4a3c30", trim="#3f3a35"))


def rounded_rect(a, b, ha, hb, r):
    """Inside a rectangle |a| < ha, |b| < hb with its corners rounded by r (arrays a, b centred on the rectangle)."""
    da, db = np.maximum(abs(a) - (ha - r), 0), np.maximum(abs(b) - (hb - r), 0)
    return (abs(a) < ha) & (abs(b) < hb) & (da ** 2 + db ** 2 < r ** 2)


def hw_at(c, Y):
    """The half-width of a blown-out body at height Y: widest at bulge_y, curving in by `tumble` to the roof and by `tuck`
    to the sills (quadratically, so the sides are one smooth curve)."""
    hw, yb, tumble, tuck, H, y0, pw = c.info["hw_profile"]
    up = np.clip((Y - yb) / (H - yb), 0, 1)
    down = np.clip((yb - Y) / max(1.0, yb - y0), 0, 1)
    return hw - tumble * up ** pw - tuck * down ** 2


def side_inset(c, Y):
    """How far in from the widest half-width the skin lies at height Y."""
    if "hw_profile" in c.info:
        return c.info["hw_profile"][0] - hw_at(c, Y)
    return np.maximum(0, Y - c.info["belt"]) / max(1.0, c.H - c.info["belt"]) * c.info.get("tumble", 0.0)


def loaf(c, p):
    """A rounded one-box body (the UAZ «loaf», the PAZ bus) and its hollow: the side profile (nose and tail as z at height y,
    bottom as y along z), a cross-section with big rounded top corners, a plan with rounded corners (front ones by height).
    p: W, H, nose [(y, z)], tail [(y, z)], bottom [(z, y)], r_top, r_bottom, r_front [(y, r)], r_rear, floor, belt."""
    body = c.body
    hw, H = p["W"] / 2, p["H"]
    X, Y, Z = body.grids()
    ax = abs(X)
    nose, tail, bottom = pl(Y, p["nose"]), pl(Y, p["tail"]), pl(Z, p["bottom"])
    # a face blown out like a loaf: the nose bulges in plan too, furthest forward in the middle
    nose = nose - p.get("nose_bulge", 0.0) * (ax / hw) ** p.get("bulge_pow", 2.0)
    # cross-section: the top corners rounded by r_top, the bottom ones by r_bottom
    rt, rb = p["r_top"], p["r_bottom"]
    # the sides lean in over the belt (tumblehome); a blown-out body («bulge_y») curves in above and below its widest line
    if "bulge_y" in p:
        c.info["hw_profile"] = (hw, p["bulge_y"], p.get("tumble", 0.0), p.get("tuck", 0.0), H, p["bottom"][1][1], p.get("tumble_pow", 2.0))
    hwy = hw_at(c, Y) if "bulge_y" in p else hw - np.maximum(0, Y - p["belt"]) / (H - p["belt"]) * p.get("tumble", 0.0)
    hwt = (float(hw_at(c, np.array(H - rt / 2))) if "bulge_y" in p else hw - p.get("tumble", 0.0) * (1 - rt / 2 / (H - p["belt"])))
    sec = (ax < hwy) & (Y > bottom) & (Y < H)
    top_c = (ax > hwt - rt) & (Y > H - rt) & ((ax - (hwt - rt)) ** 2 + (Y - (H - rt)) ** 2 > rt ** 2)
    bot_c = (ax > hw - rb) & (Y < bottom + rb) & ((ax - (hw - rb)) ** 2 + (Y - (bottom + rb)) ** 2 > rb ** 2)
    sec &= ~top_c & ~bot_c
    # plan: the front corners' radius by height, the rear ones fixed
    rf = pl(Y, p["r_front"])
    rr = p["r_rear"]
    fc = (ax > hwy - rf) & (Z > nose - rf) & ((ax - (hwy - rf)) ** 2 + (Z - (nose - rf)) ** 2 > rf ** 2)
    rc = (ax > hwy - rr) & (Z < tail + rr) & ((ax - (hwy - rr)) ** 2 + (Z - (tail + rr)) ** 2 > rr ** 2)
    shell = sec & (Z < nose) & (Z > tail) & ~fc & ~rc
    body.set(shell, PAINT)
    floor = p["floor"]
    hollow = V.erode(shell) & (Y > floor) & (Z < p.get("cabin_front", 1e9)) & (Z > p.get("cabin_rear", -1e9))
    body.set(hollow, AIR)
    half = np.full(X.shape, hw) + 0 * Y + 0 * Z
    c.info.update(belt=p["belt"], roof=H, floor=floor, hw=hw, cabin_z=0.0, tumble=p.get("tumble", 0.0))
    c.half = half
    g = dict(X=X, Y=Y, Z=Z, half=half, shell=shell, hollow=hollow)
    arches(c, p, X, Y, Z, half, depth=p.get("arch_depth", 6.0), grow=p.get("arch_grow", 1.8))
    return g


def skin_of(c, g):
    return g["shell"] & ~g["hollow"] & c.body.solid()


def side_glass(c, g, z0, z1, y0, y1, side=0, r=1.5, mat=GLASS):
    """A side window with rounded corners on one side (+1, -1) or both (0), cut into the skin."""
    X, Y, Z = g["X"], g["Y"], g["Z"]
    outer = (side_cells(c, 1) & (X > 0)) | (side_cells(c, -1) & (X < 0)) if not side else side_cells(c, side) & (X * side > 0)
    m = skin_of(c, g) & outer & rounded_rect(Z - (z0 + z1) / 2, Y - (y0 + y1) / 2, abs(z1 - z0) / 2, (y1 - y0) / 2, r)
    c.body.set(m, mat)
    return m


def end_glass(c, g, front, x0, x1, y0, y1, r=1.5, mirror=True):
    """A window in the nose (front) or the tail skin: |x| in (x0, x1) (mirrored) between y0 and y1, its corners rounded."""
    X, Y, Z = g["X"], g["Y"], g["Z"]
    face = face_cells(c, front, depth=1.01) & ~np.isin(c.body.mat, [AIR])
    xm = (x0 + x1) / 2
    for sgn in ((1, -1) if mirror else (1,)):
        m = face & rounded_rect(X * sgn - xm, Y - (y0 + y1) / 2, (x1 - x0) / 2, (y1 - y0) / 2, r)
        c.body.set(m, GLASS)


def pod_lamp(c, x, y, zface, r, mat, ring=CHROME, ring_w=0.6, out=1.0, housing=PAINT, front=True, depth=5.0):
    """A round lamp standing out of the nose (or the tail) in its own housing: a cylinder along z from inside the face to
    `out` units before it, the lens and its chrome ring on the end; mirrored across x."""
    body = c.body
    X, Y, Z = body.grids()
    sg = 1 if front else -1
    for sx in (1, -1):
        R = np.sqrt((X - sx * x) ** 2 + (Y - y) ** 2)
        zz = Z * sg
        tip = zface + out
        pod = (R < r + ring_w + 0.4) & (zz < tip) & (zz > zface - depth)
        body.set(pod & ~body.solid() | pod & (body.mat == PAINT), housing)
        endc = (zz < tip) & (zz > tip - 1.01)
        body.set(endc & (R < r), mat)
        if ring is not None:
            body.set(endc & (R >= r) & (R < r + ring_w), ring)


@car
def uaz452():
    """УАЗ-452 «буханка» (УАЗ-2206, 1965–): the cab-over van, 4.36 × 1.94 × 2.09 m, wheelbase 2.30, 15-inch wheels; everything
    round as a loaf: the roof, the corners, the round lamps standing out of the face either side of a trapezoid grille, one
    orange beacon on the roof's front; the driver's and the passenger's doors over the front wheels, a double door on
    the right into the saloon, two leaves at the back, a drip rail along the roof."""
    # measured off side and front photos (1 unit = 1/16 m): overhangs 0.9 m front, 1.0 m back; the face upright to the
    # lamps, a short cowl, the windshield at 1.40–1.91 m; windows 1.44–1.90 m under a thin roof edge
    p = dict(L=69.8, W=31.0, H=33.0, wheelbase=36.8, front=19.6, r=6.2, floor=9.0, belt=21.0,
             r_top=8.0, r_bottom=3.0, r_rear=5.0, r_front=[(0, 9.0), (9.0, 6.2), (19.0, 6.2), (22.0, 6.5), (33, 6.5)], tumble=1.2, tumble_pow=3.0, bulge_y=18.0, tuck=1.2,
             bottom=[(-34.9, 9.0), (-32, 7.6), (32, 7.6), (34.9, 9.0)],
             nose=[(6.4, 33.2), (8.0, 34.2), (10.0, 34.7), (12.0, 34.9), (17.0, 34.9), (19.0, 34.5), (21.0, 33.5),
                   (22.4, 32.6), (30.6, 29.8), (31.8, 28.8), (32.6, 27.4), (33.0, 25.6)],
             nose_bulge=1.4, bulge_pow=4.0,
             tail=[(7.6, -33.8), (10.0, -34.5), (13.0, -34.9), (18.0, -34.9), (23.0, -34.5), (27.0, -33.9), (29.6, -33.2),
                   (31.4, -32.2), (32.4, -31.2), (33.0, -30.0)],
             arch_depth=6.5, arch_grow=1.9)
    c = Car("uaz452", p["L"], p["W"], p["H"] + 1)
    c.info.update(name="УАЗ-452 «буханка»", name_en="UAZ-452 «Bukhanka»", kind="van",
                  schemes=["olive", "white", "sky", "beige", "green", "orange", "sage", "police", "ambulance"], steer=34.0)
    g = loaf(c, p)
    X, Y, Z = g["X"], g["Y"], g["Z"]
    body = c.body
    hw = c.info["hw"]
    # the cab and the saloon apart: a bulkhead behind the seats
    body.box(-hw + 1, p["floor"], 11.2, hw - 1, 23.0, 12.2, TRIM, only=[AIR])
    # glass: the one-piece windshield, the doors' windows (with the vent wing's frame), the saloon's windows, the back leaves'
    end_glass(c, g, True, -(hw - 2.6), hw - 2.6, 22.4, 30.6, r=1.4, mirror=False)
    for sgn in (1, -1):
        side_glass(c, g, 17.0, 26.8, 23.0, 28.8, sgn, r=1.0)
    body.set(skin_of(c, g) & (side_cells(c, 1) | side_cells(c, -1)) & (abs(Z - 23.4) < 0.5) & (Y > 22.6) & (Y < 30.8), PAINT)
    # the saloon's windows as on the photos: one behind each front door, two big ones each halved by a thin sliding-pane
    # frame, a small one at the back; all in black rubber seals
    sides = side_cells(c, 1) | side_cells(c, -1)
    for z0, z1, side in ((10.8, 14.6, -1), (6.2, 10.0, -1), (6.6, 14.8, 1), (-8.6, 5.2, 0), (-26.4, -11.2, 0), (-33.0, -28.6, 0)):
        side_glass(c, g, z0, z1, 22.8, 29.4, side, r=1.4)
        seal = rounded_rect(Z - (z0 + z1) / 2, Y - 26.1, abs(z1 - z0) / 2 + 0.6, 3.3 + 0.6, 1.8)
        body.set(skin_of(c, g) & sides & seal & (body.mat == PAINT) & ((X > 0) if side == 1 else (X < 0) if side == -1 else True), BLACK)
    for zd in (-18.8, -1.7):
        body.set(skin_of(c, g) & sides & (abs(Z - zd) < 0.5) & (Y > 22.6) & (Y < 29.6), BLACK)
    end_glass(c, g, False, 1.4, 11.6, 21.6, 30.0, r=1.4)
    # the drip rail along the roof's sides and over the windshield
    rail = (abs(X) > 13.2) & (abs(X) < 15.0) & (Y > 30.0) & (Y < 31.0) & (Z > -33.5) & (Z < 27.0) & ~body.solid()
    near = np.zeros_like(rail)
    s = body.solid()
    near[1:, :, :] |= s[:-1, :, :]
    near[:-1, :, :] |= s[1:, :, :]
    body.set(rail & near, PAINT)
    # the face: round lamps in their pods, the indicators under them, the trapezoid grille sunk a cell, the nose plate
    # the lamps sit flush in the blown-out face: lens and chrome ring painted on its skin
    face = face_cells(c, True, depth=1.01)
    for sx in (1, -1):
        R = np.sqrt((X - sx * 8.8) ** 2 + (Y - 15.6) ** 2)
        body.set(face & (R < 1.9), HEAD)
        body.set(face & (R >= 1.9) & (R < 2.5), CHROME)
        Rt = np.sqrt((X - sx * 9.4) ** 2 + (Y - 11.4) ** 2)
        body.set(face & (Rt < 1.0), TURN)
    trap = (Y > 9.8) & (Y < 14.0) & (abs(X) < 8.4 - (Y - 9.8) * 0.95)
    rim = (Y > 9.2) & (Y < 14.6) & (abs(X) < 9.0 - (Y - 9.2) * 0.95)
    body.set(face & rim & ~trap & (body.mat == PAINT), ALU)
    body.set(face & trap & (body.mat == PAINT), GRILLE)
    body.set(face & (abs(X) < 1.4) & (Y > 17.6) & (Y < 18.6), CHROME)  # the emblem
    # one round orange beacon on the roof's front, in the middle, on a black base
    Rb = np.sqrt(X ** 2 + (Z - 25.0) ** 2)
    body.set((Rb < 1.9) & (Y > 32.0) & (Y < 33.8) & ~body.solid(), BLACK)
    body.set((Rb < 1.5) & (Y > 33.8) & (Y < 35.6 - np.maximum(Rb - 0.8, 0) * 0.8), TURN)
    # the back: tall lamps at the corners (amber over red), the plate between, the leaves' handle
    rect_face(c, 11.2, 13.6, 14.4, 16.8, TURN, front=False)
    rect_face(c, 11.2, 13.6, 9.8, 14.4, TAIL, front=False)
    body.box(-3.8, 9.4, -35.6, 3.8, 11.6, -34.8, PLATE)
    body.box(-1.6, 17.0, -35.4, 1.6, 17.8, -34.6, CHROME)
    # bumpers: black steel bars, the plate on the front one
    # bumpers: slim black bars hugging the body's foot
    body.box(-hw + 1.6, 7.2, 33.0, hw - 1.6, 9.2, 35.4, BLACK, only=[AIR])
    body.box(-hw + 1.2, 6.8, -35.4, hw - 1.2, 8.8, -33.8, BLACK, only=[AIR])
    body.box(-3.8, 9.2, 34.6, 3.8, 11.2, 35.1, PLATE)
    # under it all: the frame's rails, the axles with their springs
    body.box(-8.6, 6.0, -33.0, -6.4, 7.7, 33.0, BLACK, only=[AIR])
    body.box(6.4, 6.0, -33.0, 8.6, 7.7, 33.0, BLACK, only=[AIR])
    for z in (p["front"], p["front"] - p["wheelbase"]):
        body.box(-hw + 4.6, p["r"] - 1.0, z - 1.0, hw - 4.6, p["r"] + 1.0, z + 1.0, BLACK, only=[AIR])
        body.box(-2.2, p["r"] - 2.0, z - 2.2, 2.2, p["r"] + 2.0, z + 2.2, BLACK, only=[AIR])
        for sgn in (1, -1):
            body.box(sgn * 6.6, p["r"] + 1.0, z - 6.0, sgn * 8.4, 7.7, z + 6.0, BLACK, only=[AIR])
    # the cab: seats either side of the engine's cover, the dashboard under the windshield
    body.box(-4.0, p["floor"], 15.0, 4.0, 15.0, 31.0, DASH, only=[AIR])
    body.box(-hw + 2, 17.0, 29.0, hw - 2, 21.6, 33.0, DASH, only=[AIR])
    for x in (8.6, -8.6):
        body.box(x - 3.4, p["floor"], 15.0, x + 3.4, 14.5, 20.5, SEAT, only=[AIR])
        body.box(x - 3.4, 14.5, 12.4, x + 3.4, 24.0, 14.0, SEAT, only=[AIR])
        c.seats.append((x, 14.5, 17.4))
    # the saloon: benches along the sides
    for sgn in (1, -1):
        body.box(sgn * (hw - 1.0), p["floor"], -31.0, sgn * (hw - 6.0), 13.0, -6.0, SEAT, only=[AIR])
        body.box(sgn * (hw - 1.0), 13.0, -31.0, sgn * (hw - 2.4), 20.5, -6.0, SEAT, only=[AIR])
        for z in (-27.0, -19.0, -11.0):
            c.seats.append((sgn * (hw - 3.6), 13.0, z))
    body.box(-hw + 2, p["floor"] - 0.2, -33.0, hw - 2, p["floor"] + 0.6, 31.0, FLOOR, only=[AIR, PAINT])
    steering_wheel(c, 8.6, 23.0, 25.8, r=3.4, tilt=55.0)
    body.box(8.1, 16.0, 26.8, 9.1, 22.4, 30.0, BLACK, only=[AIR])
    door_rect(c, g, "door_driver", 15.2, 27.6, 7.0, 31.0, 1, 0)
    door_rect(c, g, "door_passenger", 15.2, 27.6, 7.0, 31.0, -1, 1)
    door_rect(c, g, "door_saloon_front", 10.4, 15.2, 7.0, 31.0, -1, -1, hinge="front", seats=[2, 3, 4, 5, 6, 7])
    door_rect(c, g, "door_saloon_rear", 5.6, 10.4, 7.0, 31.0, -1, -1, hinge="rear", seats=[])
    back_doors(c, g, 0, 13.4, 8.6, 31.0)
    for sgn in (1, -1):  # the mirrors on their long arms
        body.box(sgn * (hw - 0.4), 23.0, 27.4, sgn * (hw + 2.6), 23.8, 28.2, BLACK)
        body.box(sgn * (hw + 2.0), 22.0, 27.2, sgn * (hw + 2.8), 26.6, 28.4, MIRROR)
    for z in (p["front"], p["front"] - p["wheelbase"]):
        for sgn in (1, -1):
            wheel(c, ("wheel_front" if z > 0 else "wheel_rear") + ("_left" if sgn > 0 else "_right"), sgn * (hw - 2.6), p["r"], z,
                  p["r"], 3.8, sgn, z > 0, hub="uaz")
    beacon(c, p["H"], 20.0, red=False)
    c.info.update(stripe=(13.0, 15.4), word=(-14.0, 17.0, 2.5), handles=[(17.0, 20.6), (6.4, 20.6)],
                  louvre=[(1.0, 4.0, 17.6, 20.6)])
    return finish(c, p, Look(cabin=lambda P: (abs(P[:, 0]) < hw - 0.9) & (P[:, 1] > p["floor"] - 0.6) & (P[:, 1] < p["H"] - 0.9) & (abs(P[:, 2]) < 34),
                             seat="#3c3a34", trim="#8c877a", headliner="#a9a493", grille=grille_mesh(0.75, "#3a3d3b")))


@car
def paz3205():
    """ПАЗ-3205 (1989–): the small town bus, 6.93 × 2.50 × 2.95 m, wheelbase 3.60, 20-inch wheels. A box with rounded
    edges; the big two-piece windshield under a black band of vents, two black grilles across the face, the round lamps in
    black housings at its lower corners, a light bumper; on the right the folding doors (one before the front axle, one in
    the middle), the driver's own door on the left, windows with their sliding upper panes, two hatches on the roof."""
    p = dict(L=110.8, W=40.0, H=47.2, wheelbase=57.6, front=29.4, r=7.8, floor=14.0, belt=24.0,
             r_top=5.0, r_bottom=1.5, r_rear=2.5, r_front=[(0, 2.5), (47.2, 2.5)], tumble=0.8,
             bottom=[(-55.4, 9.0), (-50, 7.2), (50, 7.2), (55.4, 9.0)],
             nose=[(7.2, 54.6), (9.0, 55.4), (22.6, 55.4), (23.6, 55.0), (41.4, 53.8), (42.2, 54.2), (45.6, 54.0), (47.2, 52.6)],
             tail=[(7.2, -54.6), (9.0, -55.4), (44.0, -55.4), (47.2, -53.6)],
             arch_depth=6.5, arch_grow=2.0)
    c = Car("paz3205", p["L"], p["W"], p["H"] + 1, margin=4)
    c.info.update(name="ПАЗ-3205", name_en="PAZ-3205 bus", kind="bus", steer=38.0,
                  combos={"white_green": dict(name="Белый с зелёным низом", paint="#eceae2", lower="#5fbf3c", lower_y=17.0),
                          "yellow": dict(name="Жёлтый", paint="#e6b52c"),
                          "white_blue": dict(name="Белый с синим низом", paint="#eceae2", lower="#2f6fb8", lower_y=17.0),
                          "orange": dict(name="Оранжевый", paint="#d9762d"),
                          "cream": dict(name="Слоновая кость", paint="#e5dcc0", lower="#b8392c", lower_y=12.0),
                          "sky": dict(name="Голубой", paint="#8fb5d4")})
    c.info["schemes"] = list(c.info["combos"])
    g = loaf(c, p)
    X, Y, Z = g["X"], g["Y"], g["Z"]
    body = c.body
    hw = c.info["hw"]
    # glass: the windshield's two panes, the side windows, the doors' tall panes, the rear window
    end_glass(c, g, True, 0.8, hw - 2.2, 23.8, 41.0, r=1.5)
    right = [(27.6, 39.4), (14.2, 26.2), (1.6, 12.8), (-24.0, -13.0), (-37.4, -25.4), (-52.2, -38.8)]
    left = [(27.6, 38.4), (14.2, 26.2), (1.6, 12.8), (-11.0, 0.2), (-24.0, -13.0), (-37.4, -25.4), (-52.2, -38.8)]
    for z0, z1 in right:
        side_glass(c, g, z0, z1, 24.6, 39.8, -1, r=1.0)
    for z0, z1 in left:
        side_glass(c, g, z0, z1, 24.6, 39.8, 1, r=1.0)
    side_glass(c, g, 40.4, 49.6, 24.6, 39.8, 1, r=1.0)                       # the driver's door
    for z0, z1 in ((42.4, 46.8), (47.4, 51.8), (-10.8, -5.6), (-5.0, -0.4)):  # the folding leaves
        side_glass(c, g, z0, z1, 9.6, 39.8, -1, r=0.8)
    # the sliding upper panes: a frame across each side window
    body.set((body.mat == GLASS) & (abs(X) > hw - 2.5) & (Y > 35.0) & (Y < 36.0) & (abs(Z) < 40.0), BLACK)
    end_glass(c, g, False, -12.0, 12.0, 31.0, 40.6, r=1.5, mirror=False)
    # the face: the black band of vents over the windshield, the grilles, the lamps in their housings, the bumper
    rect_face(c, 0, hw - 1.6, 42.4, 45.8, GRILLE, mirror=False)
    rect_face(c, 2.6, hw - 2.4, 15.6, 19.4, GRILLE)
    rect_face(c, 0, 8.6, 10.4, 14.6, GRILLE, mirror=False)
    rect_face(c, 9.6, hw - 2.0, 9.6, 14.8, BLACK)
    pod_lamp(c, 14.6, 12.2, 55.4, 1.7, HEAD, ring=CHROME, ring_w=0.5, out=0.3, housing=BLACK, depth=1.0)
    rect_face(c, 10.4, 12.2, 10.6, 13.8, TURN)
    body.box(-3.0, 20.4, 55.2, 3.0, 21.4, 55.9, CHROME)                         # the ПАЗ badge
    for z0, z1 in ((54.6, 56.8), (-56.8, -54.6)):
        body.box(-hw + 0.6, 6.4, z0, hw - 0.6, 9.6, z1, ALU)
    body.box(-4.0, 6.8, 56.8, 4.0, 9.0, 57.4, PLATE)
    # the back: tall lamps at the corners, the plate, the engine-less flat panel
    rect_face(c, 14.6, 17.6, 16.4, 22.6, TAIL, front=False)
    rect_face(c, 14.6, 17.6, 14.0, 16.4, TURN, front=False)
    rect_face(c, 14.6, 17.6, 12.4, 14.0, REVERSE, front=False)
    body.box(-4.0, 10.4, -56.2, 4.0, 12.6, -55.4, PLATE)
    # the roof's hatches, the mirrors on their arms
    for z in (24.0, -16.0):
        body.box(-4.4, p["H"] - 0.6, z - 4.4, 4.4, p["H"] + 0.6, z + 4.4, PAINT2)
    for sgn in (1, -1):
        body.box(sgn * (hw - 0.4), 38.4, 53.0, sgn * (hw + 3.0), 39.2, 53.8, BLACK)
        body.box(sgn * (hw + 2.2), 31.6, 52.6, sgn * (hw + 3.2), 38.6, 54.2, MIRROR)
    # doors: the folding pairs on the right (drawn as one leaf each), the driver's door on the left
    door_rect(c, g, "door_front", 41.6, 52.6, 6.0, 41.0, -1, -1, seats=list(range(1, 32)))
    door_rect(c, g, "door_rear", -11.6, 0.2, 6.0, 41.0, -1, -1, seats=[])
    door_rect(c, g, "door_driver", 39.6, 50.4, 14.0, 41.0, 1, 0)
    # the saloon
    body.box(-hw + 1.5, p["floor"] - 0.3, -54.0, hw - 1.5, p["floor"] + 0.6, 53.0, FLOOR, only=[AIR, PAINT])
    body.box(-hw + 1.5, 6.0, 41.0, -hw + 12.0, p["floor"], 53.0, FLOOR, only=[AIR, PAINT])
    body.box(-hw + 1.5, 6.0, -11.6, -hw + 8.0, p["floor"], 0.2, FLOOR, only=[AIR, PAINT])
    body.box(-hw + 1.5, 14.0, 50.0, hw - 1.5, 23.4, 54.4, DASH, only=[AIR, PAINT])
    body.box(4.0, 22.0, 48.0, hw - 1.5, 26.0, 54.0, DASH, only=[AIR])
    body.box(8.0, p["floor"], 40.0, 17.0, 21.5, 46.0, SEAT, only=[AIR])
    body.box(8.0, 21.5, 38.6, 17.0, 34.0, 40.0, SEAT, only=[AIR])
    c.seats.append((12.5, 21.5, 43.0))
    steering_wheel(c, 12.5, 29.0, 47.0, r=4.4, tilt=60.0)
    body.box(12.0, 22.0, 47.0, 13.0, 28.0, 51.0, BLACK, only=[AIR])
    for z in (32.0, 24.0, 16.0, 8.0, -6.0, -14.0, -22.0, -30.0, -38.0, -46.0):
        for sgn in (1, -1):
            if sgn < 0 and -14.0 < z < 3.0:
                continue  # the middle door's well
            x0, x1 = sgn * (hw - 1.5), sgn * 5.0
            body.box(x0, p["floor"], z - 2.5, x1, 21.0, z + 3.0, SEAT, only=[AIR])
            body.box(x0, 21.0, z - 3.5, x1, 30.0, z - 2.0, SEAT, only=[AIR])
            for x in (sgn * (hw - 5.0), sgn * 8.5):
                c.seats.append((x, 21.0, z))
    for x in (-4.4, 4.4):  # the handrails over the aisle and the poles holding them
        body.box(x - 0.5, 40.0, -50.0, x + 0.5, 41.0, 38.0, ALU, only=[AIR])
    for z in (36.0, 12.0, -16.0, -40.0):
        for x in (-4.4, 4.4):
            if x < 0 and -14.0 < z < 3.0:
                continue
            body.box(x - 0.5, p["floor"], z - 0.5, x + 0.5, 40.0, z + 0.5, ALU, only=[AIR])
    for z in (p["front"], p["front"] - p["wheelbase"]):
        for sgn in (1, -1):
            wheel(c, ("wheel_front" if z > 0 else "wheel_rear") + ("_left" if sgn > 0 else "_right"), sgn * (hw - 3.4), p["r"], z,
                  p["r"], 4.8, sgn, z > 0, hub="uaz")
        body.box(-hw + 5.0, p["r"] - 1.2, z - 1.2, hw - 5.0, p["r"] + 1.2, z + 1.2, BLACK, only=[AIR])
    body.box(-10.0, 5.6, -50.0, -7.6, 8.0, 50.0, BLACK, only=[AIR])
    body.box(7.6, 5.6, -50.0, 10.0, 8.0, 50.0, BLACK, only=[AIR])
    c.info.update(stripe=(17.6, 19.6), word=(-20.0, 21.0, 2.5), louvre=[(27.0, 34.0, 13.0, 18.0)],
                  handles=[(42.0, 20.0)])
    return finish(c, p, Look(cabin=lambda P: (abs(P[:, 0]) < hw - 0.9) & (P[:, 1] > 6.5) & (P[:, 1] < p["H"] - 0.9) & (abs(P[:, 2]) < 55),
                             seat="#7b3a2c", trim="#c9c2ad", headliner="#dcd6c4", grille=grille_bars(1.0, horizontal=True)))


def side_text(mesh, mask, words, z_centre, y_centre, height, colour, out):
    """Words in the pixel font on both sides of a curved surface (a tank): every texel of `mask` is placed by its z and y,
    the side by the sign of its x; on the left side (+x) the text reads toward -z, on the right toward +z."""
    rows = 5
    glyphs = [FP.glyph(ch) for ch in words.upper()]
    widths = [len(g[0]) for g in glyphs]
    total = sum(widths) + len(glyphs) - 1
    cell = height / rows
    P = mesh.P
    bits = np.zeros(total * rows, bool)
    x = 0
    for g, w in zip(glyphs, widths):
        for j, r in enumerate(g):
            for i, ch in enumerate(r):
                if ch == "X":
                    bits[j * total + x + i] = True
        x += w + 1
    for sgn in (1, -1):
        m = mask & (P[:, 0] * sgn > 0)
        along = (z_centre - P[:, 2]) if sgn > 0 else (P[:, 2] - z_centre)
        col = np.floor(along / cell + total / 2).astype(int)
        row = np.floor((y_centre + height / 2 - P[:, 1]) / cell).astype(int)
        ok = m & (row >= 0) & (row < rows) & (col >= 0) & (col < total)
        sel = np.nonzero(ok)[0]
        hit = bits[row[sel] * total + col[sel]]
        out[sel[hit]] = colour


def wood_extra(car, stakes, top):
    """The painter's extra for a wooden bed: planks with dark seams, the steel stakes and the top rail; the lettering of
    a tank (car.info['tank_word'])."""
    def fn(mesh, out, scheme):
        mat, P, d = mesh.mat, mesh.P, mesh.dir
        w = mat == WOOD
        side = w & (d < 2)
        ends = w & (d >= 4)
        flat = w & ((d == 2) | (d == 3))
        tu, tv = mesh.tu, mesh.tv
        # planks: a seam every 2.25 units on the boards, along the floor every 2 across
        seam_b = (side | ends) & (((P[:, 1] - 0.2) % 2.25) < 0.5)
        out[seam_b, :3] = ramp_col(out[seam_b, :3], 0.62)
        seam_f = flat & ((P[:, 0] % 2.0) < 0.5) & (P[:, 1] < top - 1.5)
        out[seam_f, :3] = ramp_col(out[seam_f, :3], 0.7)
        grain = w & (BAYER[tv % 4, tu % 4] < 0.25)
        out[grain, :3] = ramp_col(out[grain, :3], 0.92)
        # the steel stakes and the hinges: dark bands across the side boards, a lighter edge
        for z in stakes:
            st = side & (abs(P[:, 2] - z) < 0.75)
            out[st, :3] = ramp_col(out[st, :3], 0.68)
            out[side & (abs(P[:, 2] - z - 0.75) < 0.25), :3] *= 1.12
        rail = (side | ends) & (P[:, 1] > top - 0.75)
        out[rail, :3] = ramp_col(out[rail, :3], 0.8)
        if car.info.get("tank_word"):
            word, zc, yc, h, col = car.info["tank_word"]
            combo = car.info["combos"].get(scheme, {})
            side_text(mesh, (mat == TANK) & (abs(mesh.smooth_normal()[:, 0]) > 0.55), word, zc, yc, h,
                      rgb(combo.get("letters", col)), out[:, :3])
    return fn


def gaz53_chassis(c, p):
    """The GAZ-53's front end and cab: the bumper, the bonnet between the bulging wings with the lamps in them, the
    radiator's grille of vertical slots, the cab with its two-piece windshield, the running boards, the frame and axles,
    the wheels (twin at the back). p: the truck's dimensions in units."""
    body = c.body
    X, Y, Z = body.grids()
    ax = abs(X)
    zf = c.L / 2
    r = p["r"]
    fz = p["front"]
    # the front end is short and blunt: a broad bonnet between wings that stand almost as high, one flat face across the
    # whole width (white on many trucks) with the grille of vertical slots, the lamps at its sides, the bumper under it
    FZ = p["face"]
    htop = pl(Z, [(21.0, 25.8), (FZ, 24.6)])
    bon = (ax < 10.2) & (Y > 11.6) & (Y < htop) & (Z > 21.0) & (Z < FZ)
    rnd = (ax > 10.2 - 2.6) & (Y > htop - 2.6) & ((ax - 7.6) ** 2 + (Y - (htop - 2.6)) ** 2 > 2.6 ** 2)
    rnd_f = (Z > FZ - 2.0) & (Y > htop - 2.0) & ((Z - (FZ - 2.0)) ** 2 + (Y - (htop - 2.0)) ** 2 > 2.0 ** 2)
    body.set(bon & ~rnd & ~rnd_f, PAINT)
    wtop = pl(Z, [(18.0, 11.6), (21.0, 14.8), (24.0, 18.2), (27.0, 20.8), (31.0, 22.6), (35.0, 23.2), (FZ, 23.2)])
    wing = (ax > 9.6) & (ax < 18.4) & (Y > 10.4) & (Y < wtop) & (Z > 18.0) & (Z < FZ)
    ro = (ax > 18.4 - 4.2) & (Y > wtop - 4.2) & ((ax - 14.2) ** 2 + (Y - (wtop - 4.2)) ** 2 > 4.2 ** 2)
    rplan = (ax > 18.4 - 3.0) & (Z > FZ - 3.0) & ((ax - 15.4) ** 2 + (Z - (FZ - 3.0)) ** 2 > 3.0 ** 2)
    rfront = (Z > FZ - 2.0) & (Y > wtop - 2.0) & ((Z - (FZ - 2.0)) ** 2 + (Y - (wtop - 2.0)) ** 2 > 2.0 ** 2)
    body.set(wing & ~ro & ~rplan & ~rfront, PAINT)
    # the wheel arches cut out of the wings; the wells' insides dark
    R = np.sqrt((Y - r) ** 2 + (Z - fz) ** 2)
    body.set((R < r + 1.6) & (ax > 9.0), AIR)
    well = (R < r + 2.6) & (R >= r + 1.6) & (ax > 9.4) & (ax < 18.0) & body.solid()
    body.set(well & (body.mat == PAINT), UNDER)
    # the face: the front two cells of the bonnet and the wings, the grille sunk in it, four slots over it, the lamps
    face = (Z > FZ - 2.01) & (Y > 11.6) & (body.mat == PAINT)
    body.set(face, FRONT)
    slots = (Z > FZ - 1.01) & rounded_rect(X, Y - 16.9, 6.8, 4.3, 1.4)
    body.set(slots & (body.mat == FRONT), GRILLE)
    body.set((Z > FZ - 2.01) & (Z < FZ - 1.0) & rounded_rect(X, Y - 16.9, 6.8, 4.3, 1.4), BLACK)
    for x in (-4.6, -1.6, 1.6, 4.6):
        body.set((Z > FZ - 1.01) & (abs(X - x) < 1.0) & (Y > 22.0) & (Y < 22.8) & (body.mat == FRONT), BLACK)
    body.set((Z > FZ - 1.01) & (abs(X) < 1.2) & (Y > 23.0) & (Y < 23.8) & (body.mat == FRONT), CHROME)  # the badge
    pod_lamp(c, 12.8, 16.6, FZ, 1.8, HEAD, ring=CHROME, ring_w=0.5, out=0.5, housing=BLACK, depth=1.0)
    pod_lamp(c, 13.4, 21.4, FZ, 0.8, TURN, ring=None, out=0.3, housing=BLACK, depth=1.0)
    for sgn in (1, -1):
        body.box(sgn * 17.9, 18.4, 36.2, sgn * 18.9, 19.6, 37.6, TURN)         # the indicators on the wings' sides
    # the bumper: a black channel, two hooks, the plate in the middle
    body.box(-17.6, 8.8, FZ - 0.4, 17.6, 12.0, FZ + 2.2, BLACK)
    for x in (-9.6, 9.6):
        body.box(x - 0.6, 12.0, FZ + 0.2, x + 0.6, 13.4, FZ + 1.6, BLACK)
    body.box(-3.8, 9.2, FZ + 2.2, 3.8, 11.4, FZ + 2.8, PLATE)
    # the cab: rounded on top, the windshield leaning back, the back wall upright
    H = p["cab_h"]
    cfront = pl(Y, [(11.0, 22.6), (25.8, 22.6), (26.6, 22.2), (33.6, 20.6), (H, 17.6)])
    cab = (ax < 16.2) & (Y > 11.8) & (Y < H) & (Z > 4.6) & (Z < cfront)
    tc = (ax > 16.2 - 5.0) & (Y > H - 5.0) & ((ax - 11.2) ** 2 + (Y - (H - 5.0)) ** 2 > 25.0)
    rb = (Z < 4.6 + 3.0) & (Y > H - 3.0) & ((Z - 7.6) ** 2 + (Y - (H - 3.0)) ** 2 > 9.0)
    pc = (ax > 16.2 - 2.0) & (Z > cfront - 2.0) & ((ax - 14.2) ** 2 + (Z - (cfront - 2.0)) ** 2 > 4.0)
    shell = cab & ~tc & ~rb & ~pc
    body.set(shell, PAINT)
    floor = 13.4
    hollow = V.erode(shell) & (Y > floor)
    body.set(hollow, AIR)
    g = dict(X=X, Y=Y, Z=Z, shell=shell, hollow=hollow, half=np.full(X.shape, 16.2) + 0 * Y + 0 * Z)
    c.half = g["half"]
    c.info.update(belt=25.8, roof=H, floor=floor, hw=16.2, cabin_z=13.0, kind="truck")
    end_glass(c, g, True, 0.6, 14.0, 26.6, 33.4, r=1.0)
    for sgn in (1, -1):
        side_glass(c, g, 9.0, 20.6, 26.2, 33.4, sgn, r=1.0)
    body.set(skin_of(c, g) & (ax > 16.2 - 2.5) & (abs(Z - 17.4) < 0.5) & (Y > 26.2) & (Y < 33.4), PAINT)
    end_glass(c, g, False, -6.0, 6.0, 27.6, 32.6, r=1.4, mirror=False)
    # inside: the seats, the dashboard, the big wheel
    body.box(-14.4, 21.8, 19.6, 14.4, 25.8, 22.0, DASH, only=[AIR])
    body.box(-14.4, floor - 0.1, 5.6, 14.4, floor + 0.6, 21.6, FLOOR, only=[AIR, PAINT])
    for x in (8.0, -7.4):
        body.box(x - 3.8, floor, 7.2, x + 3.8, 19.6, 13.4, SEAT, only=[AIR])
        body.box(x - 3.8, 19.6, 5.6, x + 3.8, 30.0, 7.4, SEAT, only=[AIR])
        c.seats.append((x, 19.6, 10.2))
    steering_wheel(c, 8.0, 26.0, 17.0, r=4.0, tilt=48.0)
    body.box(7.5, 21.0, 17.6, 8.5, 25.4, 21.0, BLACK, only=[AIR])
    door_rect(c, g, "door_left", 7.4, 21.0, 11.8, 34.6, 1, 0)
    door_rect(c, g, "door_right", 7.4, 21.0, 11.8, 34.6, -1, 1)
    for sgn in (1, -1):
        body.box(sgn * 15.0, 9.6, 6.0, sgn * 18.8, 10.6, 18.6, BLACK)               # the running boards
        body.box(sgn * 16.0, 29.0, 21.2, sgn * 19.8, 29.6, 21.8, BLACK)             # the mirrors on their rods
        body.box(sgn * 18.4, 18.6, 25.6, sgn * 19.0, 29.6, 26.2, PAINT)
        body.box(sgn * 19.2, 26.4, 20.8, sgn * 20.2, 31.8, 22.2, MIRROR)
    c.info.update(handles=[(8.6, 25.0)])
    # the frame, the axles and springs
    body.box(-9.4, 10.4, -49.0, -7.4, 13.6, 43.0, BLACK, only=[AIR])
    body.box(7.4, 10.4, -49.0, 9.4, 13.6, 43.0, BLACK, only=[AIR])
    for z in (fz, p["front"] - p["wheelbase"]):
        body.box(-16.0, r - 1.2, z - 1.2, 16.0, r + 1.2, z + 1.2, BLACK, only=[AIR])
        body.box(-2.6, r - 2.6, z - 2.6, 2.6, r + 2.4, z + 2.6, BLACK, only=[AIR])
        for sgn in (1, -1):
            body.box(sgn * 7.4, r + 1.0, z - 8.0, sgn * 9.4, 10.6, z + 8.0, BLACK, only=[AIR])
    body.box(10.6, 9.4, -16.0, 16.0, 14.6, -4.0, BLACK, only=[AIR])  # the fuel tank
    for sgn in (1, -1):
        wheel(c, "wheel_front" + ("_left" if sgn > 0 else "_right"), sgn * 14.2, r, fz, r, 4.2, sgn, True, hub="uaz")
        wheel(c, "wheel_rear" + ("_left" if sgn > 0 else "_right"), sgn * 15.2, r, fz - p["wheelbase"], r, 3.8, sgn, False, hub="uaz")
        wheel(c, "wheel_rear_inner" + ("_left" if sgn > 0 else "_right"), sgn * 11.0, r, fz - p["wheelbase"], r, 3.8, sgn, False, hub="steel")
        body.box(sgn * 9.6, 4.0, -38.4, sgn * 18.4, 13.6, -37.8, BLACK, only=[AIR])  # the mud flaps
    # the back of the frame: the lamps on their brackets
    for sgn in (1, -1):
        body.box(sgn * 13.6, 11.0, -50.0, sgn * 17.0, 12.0, -48.6, BLACK)
        body.box(sgn * 14.2, 12.0, -50.6, sgn * 16.6, 13.8, -49.6, TAIL)
    return g


GAZ53 = dict(L=102.3, W=38.1, cab_h=35.4, wheelbase=59.2, front=31.6, r=7.7, face=42.0)


@car
def gaz53():
    """ГАЗ-53А (1965–1993), the bortovoy: 6.40 × 2.38 × 2.22 m, wheelbase 3.70, 8.25-20 tyres, twin at the back; the cab and
    the wings of one colour, the wooden bed (3.74 × 2.17 m, its boards 0.68 m high) painted or bare."""
    p = dict(GAZ53)
    c = Car("gaz53", p["L"], p["W"], 37.0)
    c.info.update(name="ГАЗ-53 бортовой", name_en="GAZ-53 truck", steer=36.0,
                  combos={"sky": dict(name="Голубой, зелёный кузов", paint="#86b4d6", wood="#5d8c58"),
                          "blue": dict(name="Синий", paint="#3d7fc0", wood="#3d7fc0"),
                          "green": dict(name="Зелёный", paint="#4d7a4a", wood="#6b8a50"),
                          "olive": dict(name="Защитный", paint="#5d6340", wood="#5d6340"),
                          "orange": dict(name="Оранжевый, некрашеный кузов", paint="#d7682a", wood="#8a7454"),
                          "white_front": dict(name="Голубой, белая морда", paint="#5d9fc4", front="#e8e6de", wood="#8a7454")})
    c.info["schemes"] = list(c.info["combos"])
    gaz53_chassis(c, p)
    body = c.body
    zr = -c.L / 2
    # the bed: crossbeams on the frame, the plank floor, the boards (front higher), the steel stakes
    for z in np.arange(-48.0, 2.0, 7.0):
        body.box(-18.6, 13.6, z - 0.6, 18.6, 17.2, z + 0.6, BLACK, only=[AIR])
    body.box(-19.0, 17.2, zr, 19.0, 18.8, 3.4, WOOD)
    for sgn in (1, -1):
        body.box(sgn * 17.9, 18.8, zr, sgn * 19.05, 29.6, 3.4, WOOD)
    body.box(-19.05, 18.8, 2.4, 19.05, 31.0, 3.4, WOOD)
    body.box(-19.05, 18.8, zr, 19.05, 29.6, zr + 1.0, WOOD)
    body.box(-3.8, 15.0, zr - 0.6, 3.8, 17.2, zr, PLATE)
    c.info.update(ceiling=35.4)
    return finish(c, p, Look(cabin=lambda P: (abs(P[:, 0]) < 15.2) & (P[:, 1] > 12.8) & (P[:, 1] < 34.6) & (P[:, 2] > 5.2) & (P[:, 2] < 22.0),
                             seat="#3b342d", trim="#4a463f", headliner="#5a564e", dash="#262626", grille=grille_bars(1.1, of=FRONT),
                             extra=wood_extra(c, stakes=[-44.0, -32.0, -20.0, -8.0], top=29.6)))


@car
def gaz53milk():
    """ГАЗ-53 with the milk tank (АЦПТ-3,3): the same chassis carrying an oval tank of 3300 litres, two hatches on top with a
    railing, the pump's box at the back; «МОЛОКО» in big letters along the tank."""
    p = dict(GAZ53)
    c = Car("gaz53milk", p["L"], p["W"], 43.0)
    c.info.update(name="ГАЗ-53 молоковоз", name_en="GAZ-53 milk tanker", steer=36.0,
                  combos={"milk": dict(name="Бирюзовый, белая морда", paint="#3e8790", front="#ecebe4", tank="#e8c21a", letters="#2a7486"),
                          "milk_blue": dict(name="Синий", paint="#3d7fc0", tank="#e8c21a", letters="#2c5f9a"),
                          "milk_white": dict(name="Голубой, белая цистерна", paint="#86b4d6", tank="#e9e8e2", letters="#2e64a8")})
    c.info["schemes"] = list(c.info["combos"])
    gaz53_chassis(c, p)
    body = c.body
    X, Y, Z = body.grids()
    zr = -c.L / 2
    # the tank: an ellipse 31 × 20 units round, its ends domed
    z0, z1 = -48.4, 2.6
    yc, a, b = 29.6, 15.6, 10.0
    dz = np.maximum(0, np.maximum(Z - (z1 - 3.0), (z0 + 3.0) - Z)) / 3.0
    e = (X / a) ** 2 + ((Y - yc) / b) ** 2 + dz ** 2
    body.set((e < 1.0) & (Z > z0) & (Z < z1), TANK)
    # its saddles on the frame
    for z in (-4.0, -24.0, -44.0):
        body.box(-13.6, 13.6, z - 1.2, 13.6, 22.0, z + 1.2, BLACK, only=[AIR])
    body.box(-9.6, 13.6, -48.0, 9.6, 15.2, 2.0, BLACK, only=[AIR])
    # the hatches with their lids, the railing along the top, the ladder at the back
    for z in (-11.0, -35.0):
        R = np.sqrt(X ** 2 + (Z - z) ** 2)
        body.set((R < 3.2) & (Y > yc + b - 1.5) & (Y < yc + b + 1.2), TANK)
        body.set((R < 2.4) & (Y >= yc + b + 1.2) & (Y < yc + b + 1.9), TANK)
        body.set((R < 0.8) & (Y >= yc + b + 1.9) & (Y < yc + b + 2.5), CHROME)
    for sgn in (1, -1):
        body.box(sgn * 5.0, yc + b + 2.4, -42.0, sgn * 5.6, yc + b + 3.0, -4.0, CHROME)
        for z in (-41.0, -23.0, -5.0):
            body.box(sgn * 5.0, yc + b - 1.0, z - 0.3, sgn * 5.6, yc + b + 2.4, z + 0.3, CHROME, only=[AIR])
    for y in np.arange(16.0, 38.0, 3.0):
        body.box(-12.0, y, -50.6, -7.0, y + 0.6, -50.0, CHROME, only=[AIR])
    body.box(-12.6, 14.0, -50.6, -12.0, 39.0, -50.0, CHROME, only=[AIR])
    body.box(-7.0, 14.0, -50.6, -6.4, 39.0, -50.0, CHROME, only=[AIR])
    # the pump's box under the tank's back end
    body.box(2.0, 12.0, -50.2, 16.0, 19.6, -44.0, ALU, only=[AIR])
    body.box(-3.8, 11.6, -50.8, 3.8, 13.8, -50.2, PLATE)
    c.info.update(ceiling=35.4, tank_word=("МОЛОКО", -22.6, 29.6, 7.5, "#2a7486"))
    return finish(c, p, Look(cabin=lambda P: (abs(P[:, 0]) < 15.2) & (P[:, 1] > 12.8) & (P[:, 1] < 34.6) & (P[:, 2] > 5.2) & (P[:, 2] < 22.0),
                             seat="#3b342d", trim="#4a463f", headliner="#5a564e", dash="#262626", grille=grille_bars(1.1, of=FRONT),
                             extra=wood_extra(c, stakes=[], top=0)))


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
        combos = c.info.get("combos", {})
        names = [LIVERIES[s]["name"] if s in LIVERIES else combos[s]["name"] if s in combos else COLOURS[s][0] for s in sch]
        beacon = [s for s in sch if LIVERIES.get(s, {}).get("beacon")]
        front = max(w["z"] for w in c.wheels)
        rear = min(w["z"] for w in c.wheels)
        seat_door = []
        for si in range(len(c.seats)):
            hit = [c.parts[d]["door"] for d in c.doors if c.parts[d].get("seat") in (si, -1) or si in (c.parts[d].get("seats") or [])]
            seat_door.append(hit[0] if hit else (c.parts[c.doors[0]]["door"] if c.doors else -1))
        seats = ", ".join(f"new Seat({jf(x / M)}, {jf(y / M)}, {jf(z / M)}, {jf(h / M)})" for (x, y, z), h in zip(c.seats, c.info["seat_ceiling"]))
        rows.append(
            f'        new Type("{c.id}", "{c.info["name"]}", "{c.info["name_en"]}", {jf(c.L / M)}, {jf(c.W / M)}, {jf(c.H / M)},\n'
            f'                {jf(c.wheels[0]["r"] / M)}, {jf(front / M)}, {jf(rear / M)}, {jf(c.info.get("steer", 32.0))}, "{c.info.get("kind", "car")}",\n'
            f'                {jf(c.info.get("ceiling", c.H) / M)},\n'
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

    /** A seat: the point (car space: x left, y up, z forward) a sitter's seat surface is at, and the roof's underside over it. */
    public record Seat(float x, float y, float z, float ceiling) {{ }}

    /** kind: car, van, bus or truck; ceiling: the roof over the seats (a truck's cab is lower than its load). */
    public record Type(String id, String name, String nameEn, float length, float width, float height, float wheelRadius,
                       float frontAxle, float rearAxle, float maxSteer, String kind, float ceiling, List<Seat> seats, List<Integer> seatDoor, int doors,
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
