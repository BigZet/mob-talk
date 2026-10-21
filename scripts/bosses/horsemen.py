"""Четыре всадника (Revelation 6:1-8): a great warhorse and its rider, drawn four times from one box layout. Each rider has
its own texture (white, red, black, pale) and the renderer picks it by the boss's phase; the gear is in separate parts the
model shows or hides by phase: a crown and a bow, a helm and a great sword, a hood and the scales on chains, Death's hood,
shroud and scythe, and the smoke or the fumes of Hades around the horse.

The horse is built like a horse: a barrel lofted from sections, a deep chest, round haunches, an arched neck in four
segments with a mane of locks, a long head with a muzzle, nostrils, cheeks and ears, legs with forearm, knee, cannon,
fetlock and hoof, feathering over the hooves, a tail of locks in six segments, a caparison with a gilt hem and a saddle.
The riders wear closed helms with burning eye slits or hoods of darkness; Death is a skull.
Built about 55 units tall and drawn at scale 1.45 (about five blocks), 4 pixels per unit."""
from boxtex import *

HIP_Z = 12.0  # the horse's root pivot: the hip joint, so it can rear on its hind legs

RIDERS = {
    "white": dict(coat=(232, 228, 216), coat_light=(255, 252, 242), coat_dark=(178, 170, 156), dapple=True,
                  mane=(250, 246, 232), mane_tip=(232, 196, 112), mane_glow=None, feather_hair=(246, 240, 226),
                  hoof=(150, 120, 70), hoof_band=GOLD, horse_eye=(40, 34, 40), horse_eye_glow=None,
                  cloth=(236, 230, 214), cloth_hl=(255, 252, 240), trim=GOLD, emblem="crown",
                  plate=(222, 220, 212), plate_trim=GOLD, leather=(120, 80, 44),
                  skin=(222, 184, 146), eye_glow=None, face="white",
                  chanfron=GOLD, fume=(230, 230, 220), fume_glow=None, steel=(230, 230, 222)),
    "red": dict(coat=(164, 40, 22), coat_light=(214, 86, 40), coat_dark=(86, 14, 10), embers=True,
                mane=(210, 40, 12), mane_tip=(255, 214, 110), mane_glow="fire", feather_hair=(50, 26, 22),
                hoof=(34, 22, 18), hoof_band=(120, 30, 16), horse_eye=FLAME, horse_eye_glow=FLAME,
                cloth=(130, 18, 22), cloth_hl=(214, 70, 60), trim=GOLD_DARK, emblem="sword",
                plate=(118, 40, 32), plate_trim=(196, 140, 60), leather=(60, 30, 20),
                skin=(186, 122, 92), eye_glow=None, face="red",
                chanfron=(110, 40, 30), fume=(120, 104, 96), fume_glow="embers", steel=(206, 196, 190)),
    "black": dict(coat=(46, 44, 54), coat_light=(112, 114, 142), coat_dark=(14, 14, 18), sheen=True,
                  mane=(22, 20, 26), mane_tip=(70, 70, 92), mane_glow=None, feather_hair=(20, 18, 22),
                  hoof=(56, 48, 40), hoof_band=GOLD_DARK, horse_eye=(200, 170, 90), horse_eye_glow=(150, 120, 60),
                  cloth=(28, 24, 30), cloth_hl=(84, 76, 70), trim=GOLD_DARK, emblem="scales",
                  plate=(44, 42, 48), plate_trim=GOLD_DARK, leather=(40, 30, 24),
                  skin=(30, 26, 30), eye_glow=(226, 196, 120), face="dark",
                  chanfron=GOLD_DARK, fume=(40, 40, 44), fume_glow=None, steel=GOLD),
    "pale": dict(coat=(172, 190, 152), coat_light=(212, 226, 192), coat_dark=(96, 114, 90), ribs=True, ghost=True,
                 mane=(150, 236, 186), mane_tip=(220, 255, 236), mane_glow="ghost", feather_hair=(120, 150, 128),
                 hoof=(74, 80, 68), hoof_band=(110, 116, 96), horse_eye=(130, 255, 200), horse_eye_glow=(130, 255, 200),
                 cloth=(52, 60, 52), cloth_hl=(96, 108, 92), trim=(150, 150, 120), emblem="skull", tattered=True,
                 plate=(70, 76, 66), plate_trim=(120, 120, 100), leather=(46, 44, 38),
                 skin=BONE, eye=(120, 255, 190), eye_glow=(120, 255, 190), face="skull",
                 chanfron=BONE, fume=(110, 230, 170), fume_glow="ghost", steel=(196, 214, 196)),
}


# ---- Styles. Each takes the rider's colours. ----

def coat(r, ribs=False):
    """Horse hair: a fine slanted grain, light along the back and dark under the belly; dapples on the white, a blue sheen
    on the black, smouldering specks on the red, mottling, ribs and a faint ghost light on the pale."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        W, H = f.w, f.h
        dapples = []
        if r.get("dapple") and f.side in ("left", "right", "top") and W > 4 * d:
            for _ in range(max(1, W * H // (36 * d * d))):
                dapples.append((p.rand.uniform(0, W), p.rand.uniform(0, H), p.rand.uniform(1.0, 1.8) * d))
        for v in range(H):
            for u in range(W):
                t = v / max(1, H - 1)
                if f.side == "top":
                    c = r["coat_light"]
                elif f.side == "bottom":
                    c = r["coat_dark"]
                else:
                    c = mix(r["coat_light"], r["coat"], min(1.0, t * 2.2))
                    c = mix(c, r["coat_dark"], max(0.0, t - 0.55) * 1.4)
                grain = 0.95 + 0.05 * math.sin((u * 1.7 + v * 0.6) / d + p.rand.random() * 0.8)
                c = shade(c, grain * p.noise(0.03))
                for (cx, cy, rad) in dapples:
                    dd = math.hypot(u - cx, v - cy)
                    if dd < rad:
                        c = mix(c, r["coat_dark"], 0.18 * (dd / rad))
                if r.get("sheen") and f.side in ("left", "right", "front", "back") and 0.12 < t < 0.3:
                    c = mix(c, r["coat_light"], 0.35 * math.sin((t - 0.12) / 0.18 * math.pi))
                glow = None
                if r.get("embers") and t > 0.6 and p.rand.random() < 0.012:
                    c = glow = (255, 120 + p.rand.randrange(80), 30)
                if r.get("ghost"):
                    if p.rand.random() < 0.08:
                        c = mix(c, r["coat_dark"], 0.25)
                    glow = (14, 34, 24)
                p.put(f, u, v, c, glow)
        if ribs and r.get("ribs") and f.side in ("left", "right"):
            for k in range(6):
                u0 = int(W * (0.12 + 0.14 * k))
                for v in range(int(H * 0.22), int(H * 0.8)):
                    uu = u0 + int(math.sin(v / max(1, H) * 2.4) * d * 1.2)
                    for w in range(max(1, d // 2)):
                        p.put(f, uu + w, v, shade(r["coat_light"], 1.05), (20, 50, 34))
                    p.put(f, uu + max(1, d // 2), v, shade(r["coat_dark"], 0.85))
    return paint


def glow_of(r, c, k):
    if r["mane_glow"] == "fire":
        return shade(c, k)
    if r["mane_glow"] == "ghost":
        return shade((90, 220, 160), k)
    return None


def mane(r, ragged=True):
    """A lock of mane or tail: strands running along its length, root to tip; fire on the red, ghost light on the pale."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        W, H = f.w, f.h
        phase = p.rand.random() * 6
        for v in range(H):
            t = v / max(1, H - 1)
            for u in range(W):
                if f.side in ("top", "bottom"):
                    c = shade(r["mane"], 0.8)
                    p.put(f, u, v, c, glow_of(r, c, 0.4))
                    continue
                strand = 0.78 + 0.32 * max(0.0, math.sin(u * 2.4 / max(1, d) + phase + math.sin(v * 0.2) * 0.8))
                if r["mane_glow"] == "fire":
                    c = mix(mix((120, 10, 6), r["mane"], min(1, t * 2)), r["mane_tip"], max(0, t - 0.5) * 2)
                else:
                    c = mix(r["mane"], r["mane_tip"], t * 0.7)
                c = shade(c, strand * p.noise(0.04))
                if ragged and t > 0.78 and (u * 7 + int(phase)) % 5 < 2 + int((t - 0.78) * 14):
                    p.put(f, u, v, None)
                    continue
                p.put(f, u, v, c, glow_of(r, c, 0.25 + 0.75 * t))
    return paint


def feathering(r):
    """Long hair over the hooves: silky, pale at the ends, frayed."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            t = v / max(1, f.h - 1)
            for u in range(f.w):
                if t > 0.6 and p.rand.random() < (t - 0.6) * 2.2:
                    p.put(f, u, v, None)
                    continue
                c = shade(r["feather_hair"], (0.8 + 0.25 * max(0.0, math.sin(u * 2.6 / max(1, d)))) * p.noise(0.05))
                g = None
                if r["mane_glow"] == "ghost":
                    g = shade((60, 160, 120), t)
                elif r["mane_glow"] == "fire" and t > 0.5 and p.rand.random() < 0.08:
                    c = g = (255, 110, 30)
                p.put(f, u, v, c, g)
    return paint


def hoof(r):
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                c = shade(r["hoof"], (0.9 + 0.1 * math.sin(u * 1.3 / max(1, d))) * p.noise(0.05))
                if f.side not in ("top", "bottom") and v < d:
                    c = r["hoof_band"]
                if f.side not in ("top", "bottom") and v >= f.h - d:
                    c = shade(r["hoof_band"], 0.8)
                p.put(f, u, v, c)
    return paint


def eye_style(r):
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                c = r["horse_eye"]
                if u + v < 2:
                    c = (250, 250, 240)
                p.put(f, u, v, c, r["horse_eye_glow"])
    return paint


def nostril(r):
    def paint(p, f, opts):
        for v in range(f.h):
            for u in range(f.w):
                c = shade(r["coat_dark"], 0.5)
                g = None
                if r["mane_glow"] == "fire" and f.side == "front":
                    c = g = (255, 120, 40) if (u + v) % 3 else (255, 200, 90)
                if r["mane_glow"] == "ghost" and f.side == "front":
                    c = g = (100, 230, 170)
                p.put(f, u, v, c, g)
    return paint


def caparison(r, hem=True):
    """The barding: the rider's cloth in folds, a gilt hem with dots, dagged at the bottom; the pale one hangs in rags."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        drapery(r["cloth"], highlight=r["cloth_hl"], hem=r["trim"] if hem else None, hem_width=2)(p, f, opts)
        if f.side in ("left", "right", "front", "back"):
            for u in range(f.w):
                cut = int((1 + math.sin(u * math.pi / (2 * d))) * d * 0.7)
                for v in range(f.h - cut, f.h):
                    p.put(f, u, v, None)
            if r.get("tattered"):
                for _ in range(f.w * f.h // (20 * d * d)):
                    cx, cy = p.rand.randrange(f.w), p.rand.randrange(f.h // 2, f.h)
                    for dv in range(p.rand.randrange(d, 3 * d)):
                        p.put(f, cx, cy + dv, None)
                        p.put(f, cx + 1, cy + dv, None)
            if hem:
                v = f.h - 3 * d
                for u in range(0, f.w):
                    p.put(f, u, v, shade(r["trim"], 1.25))
    return paint


def medallion(r):
    """The rider's sign on a round gilt plate: a crown, a sword, the scales, a skull."""
    def paint(p, f, opts):
        W, H = f.w, f.h
        R = min(W, H) / 2
        for v in range(H):
            for u in range(W):
                if f.side not in ("left", "right"):
                    p.put(f, u, v, r["trim"])
                    continue
                dd = math.hypot(u + 0.5 - W / 2, v + 0.5 - H / 2)
                if dd > R:
                    p.put(f, u, v, None)
                elif dd > R * 0.82:
                    p.put(f, u, v, shade(r["trim"], 1.15 if (u + v) % 4 else 0.8))
                else:
                    p.put(f, u, v, shade(r["cloth"], 0.75))
        if f.side not in ("left", "right"):
            return
        s = max(1, int(R / 6))
        cx, cy = W // 2, H // 2
        g = GOLD_LIGHT
        sign = r["emblem"]

        def dot(x, y, c=g):
            for a in range(s):
                for b in range(s):
                    p.put(f, cx + x * s + a, cy + y * s + b, c)
        if sign == "crown":
            for x in range(-3, 4):
                dot(x, 1); dot(x, 2)
            for x in (-3, 0, 3):
                dot(x, 0); dot(x, -1)
            for x in (-3, 0, 3):
                dot(x, -2, CINNABAR)
        elif sign == "sword":
            for y in range(-4, 3):
                dot(0, y, (230, 220, 210))
            for x in range(-2, 3):
                dot(x, 1)
            dot(0, 3); dot(0, 4)
        elif sign == "scales":
            for x in range(-3, 4):
                dot(x, -2)
            for y in range(-3, 3):
                dot(0, y)
            for x in (-4, -3, -2, 2, 3, 4):
                dot(x, 1)
            dot(-3, 0); dot(3, 0)
        else:
            for x in range(-2, 3):
                for y in range(-3, 1):
                    dot(x, y, BONE)
            dot(-1, -1, BLACK); dot(1, -1, BLACK); dot(0, 0, BLACK)
            for x in (-1, 1):
                dot(x, 1, BONE)
    return paint


def leather(r, studs=True):
    def paint(p, f, opts):
        d = opts.get("density", 1)
        for v in range(f.h):
            for u in range(f.w):
                c = shade(r["leather"], p.noise(0.06))
                if studs and f.side != "bottom" and (u in (d, f.w - 1 - d) or v in (d, f.h - 1 - d)) and (u + v) % (2 * d) == 0:
                    c = GOLD_LIGHT
                p.put(f, u, v, c)
    return paint


def plate(r, rivets=True, emblem=False):
    """Plate armour: a polished gradient with a bright ridge, a gilt border and rivets; on the breastplate an Orthodox cross."""
    def paint(p, f, opts):
        d = opts.get("density", 1)
        W, H = f.w, f.h
        base = r["plate"]
        for v in range(H):
            for u in range(W):
                x = abs(u + 0.5 - W / 2) / max(1, W / 2)
                c = mix(shade(base, 1.35), shade(base, 0.7), x * 0.8 + v / max(1, H) * 0.3)
                if abs(u + 0.5 - W / 2) < d * 0.5 and f.side in ("front", "back") and not emblem:
                    c = shade(base, 1.5)
                if (u < d or v < d or u >= W - d or v >= H - d) and min(W, H) > 3 * d:
                    c = shade(r["plate_trim"], p.noise(0.08))
                if rivets and min(W, H) > 5 * d and (u in (int(1.5 * d), W - 1 - int(1.5 * d))) and (v // d) % 2 == 1 and v % d == 0:
                    c = GOLD_LIGHT
                p.put(f, u, v, shade(c, p.noise(0.04)))
        if emblem and f.side == "front" and r["emblem"] != "skull":
            orthodox_cross(p, f, W // 2, H // 2, int(H * 0.74), shade(r["plate_trim"], 1.2), thickness=max(1, d // 2 + 1))
    return paint


def torso(r):
    """The rider's body: the cuirass, or on Death a black rag over bare ribs."""
    if r["face"] != "skull":
        return plate(r, emblem=True)

    def paint(p, f, opts):
        d = opts.get("density", 1)
        W, H = f.w, f.h
        for v in range(H):
            for u in range(W):
                p.put(f, u, v, shade(r["cloth"], p.noise(0.1)))
        if f.side != "front":
            return
        for k in range(5):
            y = int(H * (0.12 + 0.15 * k))
            for u in range(int(W * 0.18), int(W * 0.82)):
                if abs(u - W // 2) < d // 2:
                    continue
                yy = y + int(abs(u - W / 2) / W * 2 * d)
                for w in range(max(1, d // 2 + 1)):
                    p.put(f, u, yy + w, shade(BONE, 0.95 + 0.1 * p.rand.random()))
        for v in range(int(H * 0.08), int(H * 0.9)):
            for w in range(max(1, d // 2)):
                p.put(f, W // 2 - d // 4 + w, v, BONE)
    return paint


def limb(r, part="arm"):
    """The rider's sleeves and greaves: plate, or bone in black rags on Death."""
    if r["face"] == "skull":
        def paint(p, f, opts):
            for v in range(f.h):
                for u in range(f.w):
                    bone = abs(u + 0.5 - f.w / 2) < f.w * 0.28
                    c = shade(BONE, 0.9 + 0.15 * p.rand.random()) if bone else shade(r["cloth"], 0.6 * p.noise(0.1))
                    if part == "leg":
                        c = shade(r["cloth"], p.noise(0.1))
                    p.put(f, u, v, c)
        return paint
    return plate(r, rivets=False)


def rider_face(r):
    """No painted man's face: the White and the Red ride in closed helms with a burning eye slit (white enamel and gold, or
    dark red steel), the Black is darkness under his hood with two pale gold eyes, Death is a skull."""
    kind = r["face"]
    if kind == "skull":
        def paint(p, f, opts):
            d = opts.get("density", 1)
            W, H = f.w, f.h
            for v in range(H):
                for u in range(W):
                    p.put(f, u, v, shade(BONE, (1.05 - 0.25 * v / H) * p.noise(0.05)))
            if f.side != "front":
                return
            for side in (-1, 1):
                cx, cy = W / 2 + side * W * 0.22, H * 0.42
                for v in range(H):
                    for u in range(W):
                        if ((u - cx) / (W * 0.15)) ** 2 + ((v - cy) / (H * 0.13)) ** 2 < 1:
                            dd = math.hypot(u - cx, v - cy)
                            c = r["eye"] if dd < d * 0.8 else (14, 16, 14)
                            p.put(f, u, v, c, r["eye_glow"] if dd < d * 0.8 else None)
            for v in range(int(H * 0.56), int(H * 0.66)):
                for u in range(W // 2 - d // 2 - 1, W // 2 + d // 2 + 1):
                    p.put(f, u, v, (20, 20, 18))
            for u in range(int(W * 0.2), int(W * 0.8)):
                for v in range(int(H * 0.74), int(H * 0.9)):
                    p.put(f, u, v, (24, 24, 20) if (u // max(1, d // 2 + 1)) % 2 == 0 or v in (int(H * 0.74), int(H * 0.82)) else BONE)
        return paint
    if kind == "dark":
        def paint(p, f, opts):
            d = opts.get("density", 1)
            W, H = f.w, f.h
            for v in range(H):
                for u in range(W):
                    p.put(f, u, v, shade((12, 10, 14), p.noise(0.1)))
            if f.side != "front":
                return
            for side in (-1, 1):
                cx, cy = int(W / 2 + side * W * 0.2), int(H * 0.5)
                for dv in range(-d // 2, d // 2 + 1):
                    for du in range(-d, d + 1):
                        if abs(du) / d + abs(dv) / max(1, d / 2) <= 1.0:
                            p.put(f, cx + du, cy + dv, r["eye_glow"], r["eye_glow"])
        return paint
    steel = r["plate"]
    slit = (255, 220, 130) if kind == "white" else (255, 110, 30)

    def paint(p, f, opts):
        d = opts.get("density", 1)
        W, H = f.w, f.h
        for v in range(H):
            for u in range(W):
                x = abs(u + 0.5 - W / 2) / max(1, W / 2)
                c = mix(shade(steel, 1.3), shade(steel, 0.75), x * 0.7 + 0.3 * v / max(1, H))
                p.put(f, u, v, shade(c, p.noise(0.04)))
        if f.side != "front":
            return
        trim = r["plate_trim"]
        for v in range(H):
            for w in range(max(1, d // 2)):
                p.put(f, W // 2 - d // 4 + w, v, shade(trim, 1.1))
        ey = int(H * 0.38)
        for v in range(ey - d // 2, ey):
            for u in range(int(W * 0.1), int(W * 0.9)):
                p.put(f, u, v, trim)
        for v in range(ey, ey + max(2, d)):
            for u in range(int(W * 0.12), int(W * 0.88)):
                if abs(u + 0.5 - W / 2) > d // 2:
                    p.put(f, u, v, slit, slit)
        for k in range(3):
            for side in (-1, 1):
                u = W // 2 + side * (d + k * d)
                for v in range(int(H * 0.62), int(H * 0.86), max(2, d // 2 + 1)):
                    p.put(f, u, v, (20, 14, 12))
    return paint


def blade(r, glow_edge=None, point=False):
    """Steel along the long axis with a bright edge; it burns on the red rider. `point` cuts the top of the flat faces to a
    sharp tip."""
    def paint(p, f, opts):
        W, H = f.w, f.h
        for v in range(H):
            for u in range(W):
                if point and f.side in ("front", "back") and abs(u + 0.5 - W / 2) > (W / 2) * (v + 0.5) / max(1, H):
                    p.put(f, u, v, None)
                    continue
                across = abs(u + 0.5 - W / 2) / max(1, W / 2)
                c = mix(shade(r["steel"], 1.2), shade(r["steel"], 0.7), across)
                if across < 0.18 and f.side in ("front", "back"):
                    c = shade(r["steel"], 0.6)
                edge = across > 0.75
                g = None
                if glow_edge and (edge or f.side in ("left", "right")):
                    c = g = mix(glow_edge, (255, 255, 230), 0.3 if edge else 0)
                p.put(f, u, v, shade(c, p.noise(0.04)), g)
    return paint


def curved_blade(r, glow_edge=None, point=False):
    """The scythe blade: dark iron along the spine, a bright edge underneath; the last piece is cut to a point."""
    def paint(p, f, opts):
        W, H = f.w, f.h
        for v in range(H):
            t = v / max(1, H - 1)
            for u in range(W):
                # The flat faces of a piece running along -Z are "left"/"right": u runs along the blade.
                if point and f.side in ("left", "right"):
                    along = u / max(1, W - 1) if f.side == "left" else 1 - u / max(1, W - 1)
                    if t > along * 1.05:
                        p.put(f, u, v, None)
                        continue
                c = mix((60, 64, 60), r["steel"], t)
                g = None
                if t > 0.7 and f.side != "top":
                    c = (236, 244, 230)
                    g = glow_edge
                p.put(f, u, v, shade(c, p.noise(0.05)), g)
    return paint


def fume(r):
    """A wisp of smoke on a thin plane: soft, rising, transparent at the edges; embers on the red, ghost light on the pale."""
    def paint(p, f, opts):
        W, H = f.w, f.h
        ph = p.rand.random() * 6
        for v in range(H):
            t = v / max(1, H - 1)
            for u in range(W):
                if f.side not in ("front", "back", "left", "right"):
                    p.put(f, u, v, None)
                    continue
                centre = W / 2 + math.sin(t * 5 + ph) * W * 0.18
                half = W / 2 * (0.35 + 0.65 * t) * (0.8 + 0.2 * math.sin(v * 0.4 + ph))
                a = abs(u + 0.5 - centre) / max(1, half)
                if a > 1 or (a > 0.85 and p.rand.random() < 0.5):
                    p.put(f, u, v, None)
                    continue
                c = shade(r["fume"], (0.85 + 0.3 * (1 - a)) * p.noise(0.06))
                g = None
                if r["fume_glow"] == "ghost":
                    g = shade((70, 190, 130), (1 - a) * (0.4 + 0.6 * t))
                elif r["fume_glow"] == "embers":
                    # Smoke lit from below by the burning hooves: fire at the root, grey at the top, sparks.
                    heat = max(0.0, t - 0.35) / 0.65
                    c = mix(c, (255, 130, 40), heat * 0.85)
                    g = shade((255, 110, 30), heat * (1 - a * 0.6)) if heat > 0 else None
                    if p.rand.random() < 0.03:
                        c = g = (255, 200, 90)
                p.put(f, u, v, c, g)
    return paint


def jewel(color):
    return solid(color, glow=True, k=0.05)


# ---- Builders. ----

def loft(sections, style_at):
    """The barrel of the horse: between each pair of sections (z, half width, top, bottom) a core box and a wider, shorter
    side box, so the cross-section is an octagon and the silhouette swells and tucks like a body."""
    boxes = []
    for i in range(len(sections) - 1):
        z0, w0, t0, b0 = sections[i]
        z1, w1, t1, b1 = sections[i + 1]
        hw, top, bot = (w0 + w1) / 2, (t0 + t1) / 2, (b0 + b1) / 2
        depth, z = bot - top, z0 - HIP_Z
        style = style_at(i)
        boxes.append(Box((-hw * 0.74, top, z), (hw * 1.48, depth, z1 - z0), style))
        boxes.append(Box((-hw, top + depth * 0.15, z), (hw * 2, depth * 0.66, z1 - z0), style))
    return boxes


def chain(parts):
    """Links a list of parts parent to child and returns the first."""
    for a, b in zip(parts, parts[1:]):
        a.children.append(b)
    return parts[0]


def lock(name, pivot, rotation, length, width, style, segments=2, droop=0.18):
    """A lock of hair as a short chain, so it can curve and stream."""
    seg = length / segments
    parts = []
    for k in range(segments):
        w = width * (1 - 0.25 * k / max(1, segments - 1))
        parts.append(Part("%s_%d" % (name, k), pivot=pivot if k == 0 else (0, seg - 0.2, 0),
                          rotation=rotation if k == 0 else (droop, 0, 0),
                          boxes=[Box((-w / 2, 0, -w * 0.35), (w, seg + 0.2, w * 0.7), style)]))
    return chain(parts)


def hoof_part(r, name, y):
    fh = feathering(r)
    hair = []
    for i in range(9):
        a = 2 * math.pi * i / 9
        hair.append(Part("%s_hair_%d" % (name, i), pivot=(math.sin(a) * 1.55, -1.4, math.cos(a) * 1.65), rotation=(0.32, a, 0),
                         boxes=[Box((-0.95, 0, -0.15), (1.9, 3.9 - 0.4 * (i % 2), 0.3), fh)]))
    return Part(name, pivot=(0, y, 0), boxes=[
        Box((-1.35, -0.2, -1.5), (2.7, 1.3, 2.9), coat(r)),
        Box((-1.95, 0.9, -2.35), (3.9, 1.95, 4.1), hoof(r)),
    ], children=hair)


def front_leg(r, name, x):
    st = coat(r)
    return Part(name, pivot=(x, 3, -13 - HIP_Z), boxes=[
        Box((-2.3, -4, -2.8), (4.6, 7, 5.6), st),
        Box((-2.0, 3, -2.3), (4.0, 4, 4.6), st),
        Box((-1.65, 7, -1.9), (3.3, 2.8, 3.8), st),
    ], children=[
        Part(name + "_low", pivot=(0, 9.4, 0), boxes=[
            Box((-1.85, -0.8, -2.0), (3.7, 2.8, 3.8), st),
            Box((-1.25, 1.8, -1.25), (2.5, 6.2, 2.5), st),
            Box((-0.85, 2.2, 0.7), (1.7, 5.2, 1.0), st),
            Box((-1.6, 7.6, -1.6), (3.2, 2.1, 3.4), st),
        ], children=[hoof_part(r, name + "_hoof", 9.2)]),
    ])


def hind_leg(r, name, x):
    st = coat(r)
    return Part(name, pivot=(x, -1, 11 - HIP_Z), rotation=(0.36, 0, 0), boxes=[
        Box((-2.7, -5, -3.9), (5.4, 8.5, 7.6), st),
        Box((-2.2, 3, -2.9), (4.4, 5.6, 5.4), st),
        Box((-1.75, 8.4, -2.1), (3.5, 4.4, 4.0), st),
    ], children=[
        Part(name + "_low", pivot=(0, 12.6, 0), rotation=(-0.36, 0, 0), boxes=[
            Box((-1.7, -1.2, -1.6), (3.4, 3.2, 3.6), st),
            Box((-0.9, -1.8, 1.4), (1.8, 2.2, 1.4), st),
            Box((-1.25, 1.8, -1.25), (2.5, 7.0, 2.5), st),
            Box((-0.85, 2.2, 0.7), (1.7, 6.0, 1.0), st),
            Box((-1.55, 8.4, -1.6), (3.1, 2.1, 3.3), st),
        ], children=[hoof_part(r, name + "_hoof", 10.1)]),
    ])


def horse_head(r):
    """A long horse head: the poll at the pivot, the face running toward -Z and down the neck's pitch."""
    st, mn = coat(r), mane(r)
    gold_s = gold_leaf(ornament=False)
    chanfron = plate(dict(r, plate=r["chanfron"], plate_trim=shade(r["chanfron"], 0.7)), rivets=False)
    ears = []
    for side in (-1, 1):
        ears.append(Part("ear_%s" % ("l" if side > 0 else "r"), pivot=(side * 1.7, -2.6, -1.2), rotation=(-0.2, 0, side * 0.22), boxes=[
            Box((-0.8, -2.4, -0.5), (1.6, 2.6, 1.0), st),
            Box((-0.5, -3.5, -0.3), (1.0, 1.2, 0.6), st),
        ]))
    forelock = [lock("forelock_%d" % i, pivot=(-0.7 + 0.7 * i, -3.1, -1.8), rotation=(-1.45, 0.1 * (i - 1), 0),
                     length=4.2, width=1.1, style=mn, droop=0.12) for i in range(3)]
    smoke = [Part("nostril_smoke_%d" % i, pivot=(side * 1.6, 0.6, -14.2), rotation=(0.9, side * 0.5, 0),
                  boxes=[Box((-1.6, 0, 0), (3.2, 6, 0.1), fume(r), flat=True)]) for i, side in enumerate((-1, 1))]
    return Part("horse_head", pivot=(0, -4.6, 0), rotation=(0.08, 0, 0), boxes=[
        Box((-2.8, -3.0, -5.0), (5.6, 6.0, 6.0), st),        # cranium and poll
        Box((-3.1, 0.2, -5.4), (6.2, 4.4, 4.6), st),         # round cheeks
        Box((-2.6, 4.2, -4.8), (5.2, 1.2, 3.6), st),         # under the cheeks
        Box((-2.5, -2.9, -8.4), (5.0, 4.8, 3.6), st),        # forehead, narrowing
        Box((-2.1, -2.6, -11.8), (4.2, 4.3, 3.6), st),       # nasal bone
        Box((-1.95, -2.2, -14.2), (3.9, 4.4, 2.6), st),      # muzzle
        Box((-1.55, -1.8, -15.0), (3.1, 3.6, 1.0), st),      # rounded front of the muzzle
        Box((-1.75, 2.0, -14.4), (3.5, 0.9, 3.0), st),       # lower lip
        Box((-1.4, 2.6, -13.2), (2.8, 1.2, 2.4), st),        # chin
        Box((-1.8, 1.9, -10.6), (3.6, 1.5, 5.0), st),        # jaw
        Box((-2.2, -1.7, -14.7), (0.5, 1.4, 1.6), nostril(r)),
        Box((1.7, -1.7, -14.7), (0.5, 1.4, 1.6), nostril(r)),
        Box((-2.95, -1.9, -7.2), (0.4, 1.2, 1.6), eye_style(r)),
        Box((2.55, -1.9, -7.2), (0.4, 1.2, 1.6), eye_style(r)),
        Box((-2.95, -3.0, -7.8), (0.7, 0.9, 2.2), st),       # brows over the eyes
        Box((2.25, -3.0, -7.8), (0.7, 0.9, 2.2), st),
        Box((-2.3, -2.8, -11.2), (4.6, 4.7, 0.6), leather(r)),   # noseband
        Box((-2.95, -3.2, -2.4), (5.9, 0.6, 0.7), leather(r)),   # browband
        Box((-2.45, -0.4, -12.6), (0.5, 0.9, 0.9), gold_s),      # bit rings
        Box((1.95, -0.4, -12.6), (0.5, 0.9, 0.9), gold_s),
        Box((-1.2, -3.25, -8.6), (2.4, 0.5, 4.8), chanfron),     # a narrow plate down the forehead
        Box((-0.8, -2.95, -11.4), (1.6, 0.5, 2.8), chanfron),
    ], children=ears + forelock + smoke)


def neck_chain(r):
    """The arched neck in four tapering segments with a crest of mane locks falling to both sides; the head at its end."""
    st, mn = coat(r), mane(r)
    lengths = (6.2, 5.6, 5.0, 4.6)
    pitches = (0.42, 0.12, 0.17, 0.26)
    widths = (6.4, 5.4, 4.8, 4.3)
    depths = (9.4, 8.0, 7.0, 6.2)
    parts = []
    for i in range(4):
        L, w, dd = lengths[i], widths[i], depths[i]
        boxes = [Box((-w / 2, -L - 0.4, -dd / 2), (w, L + 0.8, dd), st),
                 Box((-w * 0.38, -L - 0.2, -dd / 2 - 0.6), (w * 0.76, L + 0.4, 1.0), st),
                 Box((-0.75, -L - 0.4, dd / 2 - 0.9), (1.5, L + 0.8, 1.6), mn)]
        locks = []
        for k in range(2):
            for side in (-1, 1):
                ln = 7.5 + 1.5 * ((i + k) % 2) - 0.5 * i
                locks.append(lock("mane_%d_%d_%s" % (i, k, "l" if side > 0 else "r"), pivot=(side * 0.5, -L * (0.3 + 0.45 * k), dd / 2 - 0.2),
                                  rotation=(0.22 + 0.12 * k, 0, side * (0.85 + 0.15 * ((i + k) % 2))), length=ln, width=1.4, style=mn))
        parts.append(Part("neck_%d" % i, pivot=(0, -6, -14.5 - HIP_Z) if i == 0 else (0, -lengths[i - 1], 0),
                          rotation=(pitches[i], 0, 0), boxes=boxes, children=locks))
    parts[-1].children.append(horse_head(r))
    return chain(parts)


def tail_chain(r):
    mn, tip = mane(r, ragged=False), mane(r)
    parts = []
    for i in range(6):
        L = 4.4
        spread = 0.5 + 0.28 * i
        st = tip if i == 5 else mn
        boxes = [Box((-1.2, -0.2, -1.2), (2.4, L + 0.4, 2.4), st)]
        for k, (sx, sz) in enumerate(((-1, 0), (1, 0), (0, 1), (0, -1))):
            boxes.append(Box((sx * spread - 0.65, -0.1, sz * spread * 0.8 - 0.65), (1.3, L + 0.6 + 0.4 * (k % 2), 1.3), st))
        if i == 0:
            boxes.insert(0, Box((-1.6, -0.6, -1.6), (3.2, 3.0, 3.2), coat(r)))
        parts.append(Part("tail_%d" % i, pivot=(0, -8.6, 19.0 - HIP_Z) if i == 0 else (0, L, 0),
                          rotation=(0.55, 0, 0) if i == 0 else (-0.1 if i < 3 else -0.05, 0, 0), boxes=boxes))
    return chain(parts)


def fumes(r):
    """Smoke (the red horse) or the fumes of Hades (the pale one): crossed wisps rising round the hooves and trailing behind."""
    style = fume(r)
    wisps = []
    spots = [(-4.5, 23.5, -13), (4.5, 23.5, -13), (-4.8, 23.5, 15), (4.8, 23.5, 15), (0, -2, 24), (-3, 6, 22), (3, 10, 20), (0, 16, 26)]
    for i, (x, y, zz) in enumerate(spots):
        size = 9 if i < 4 else 12
        wisps.append(Part("fume_%d" % i, pivot=(x, y, zz - HIP_Z), rotation=(0.15 if i < 4 else 0.5, 0.6 * i, 0), boxes=[
            Box((-size / 3, -size, 0), (size * 2 / 3, size, 0.1), style, flat=True),
            Box((0, -size, -size / 3), (0.1, size, size * 2 / 3), style, flat=True),
        ]))
    return Part("fumes", children=wisps)


def make_rider(r):
    skull = r["face"] == "skull"
    torso_s, arm_s, leg_s = torso(r), limb(r), limb(r, "leg")
    pl = plate(r)
    gold_s = gold_leaf(ornament=False)

    def pauldron(name, side):
        return Part(name, pivot=(side * 4.3, -9.4, 0), children=[
            Part("%s_%d" % (name, k), pivot=(side * 0.4, -0.6 + k * 1.5, 0), rotation=(0, 0, -side * (0.4 + 0.18 * k)),
                 boxes=[Box((-2.3, 0, -2.4), (4.6, 1.8, 4.8), pl)]) for k in range(3)])

    def arm(name, side, weapons):
        return Part(name, pivot=(side * 4.6, -8.8, 0), boxes=[
            Box((-1.3, -0.6, -1.3), (2.6, 5.6, 2.6), arm_s),
        ], children=[Part(name + "_fore", pivot=(0, 5.0, 0), boxes=[
            Box((-1.2, -0.3, -1.2), (2.4, 4.6, 2.4), arm_s),
            Box((-1.4, -0.6, -1.4), (2.8, 1.2, 2.8), arm_s if skull else solid(r["plate_trim"])),
            Box((-1.35, 4.2, -1.45), (2.7, 2.2, 2.9), arm_s if skull else pl),
        ], children=weapons)])

    def leg(name, side):
        return Part(name, pivot=(side * 2.3, -1.2, -0.6), rotation=(-0.8, 0, -side * 0.78), boxes=[
            Box((-1.55, -0.4, -1.55), (3.1, 7.8, 3.1), leg_s),
        ], children=[Part(name + "_shin", pivot=(0, 7.4, 0), rotation=(0.78, 0, side * 0.62), boxes=[
            Box((-1.3, -0.3, -1.35), (2.6, 7.0, 2.7), leg_s if skull else pl),
            Box((-1.4, 6.3, -2.9), (2.8, 1.6, 4.2), solid(BONE) if skull else solid(shade(r["plate"], 0.7))),
            Box((-1.9, 7.9, -2.4), (3.8, 0.6, 3.0), solid(r["plate_trim"])),  # stirrup
        ])])

    # Weapons and signs: every texture has all of them, the model shows them by phase.
    sword = Part("sword", pivot=(0, 5.6, -0.4), rotation=(1.45, 0.8, 0.12), boxes=[
        Box((-0.5, -1.2, -0.5), (1.0, 4.4, 1.0), leather(r, studs=False)),
        Box((-0.9, 3.0, -0.9), (1.8, 1.4, 1.8), gold_s),
        Box((-3.4, -2.2, -0.75), (6.8, 1.0, 1.5), gold_s),
        Box((-1.2, -27.6, -0.35), (2.4, 25.4, 0.7), burning_blade(core=(222, 208, 196)), flat=True),
    ])
    snath = (226, 214, 184) if skull else (70, 48, 30)
    edge = (120, 255, 190) if skull else None
    blade_parts = [Part("scythe_blade_%d" % k, pivot=(0, -0.2, 0) if k == 0 else (0, 0, -3.6), rotation=(0.1 if k == 0 else 0.17, 0, 0),
                        boxes=[Box((-0.2, 0, -3.8), (0.4, 2.6 - 0.4 * k, 3.8), curved_blade(r, glow_edge=edge, point=(k == 4)))])
                   for k in range(5)]
    scythe = Part("scythe", pivot=(0, 5.6, -0.2), rotation=(1.3, 0, 0.2), boxes=[
        Box((-0.45, -26, -0.45), (0.9, 34, 0.9), solid(snath, k=0.1)),
        Box((-0.6, -4, -0.6), (1.2, 1.4, 1.2), solid(shade(snath, 0.6))),
        Box((-0.7, -27.4, -0.7), (1.4, 1.8, 1.4), solid(shade(snath, 0.6))),
        Box((-0.25, -14, -2.6), (0.5, 0.8, 2.6), solid(snath)),
    ], children=[Part("scythe_head", pivot=(0, -26.6, 0), rotation=(-0.2, 0, 0), children=[chain(blade_parts)])])
    bow_limbs = []
    for side in (-1, 1):
        segs = [Part("bow_%s_%d" % ("up" if side < 0 else "down", k), pivot=(0, side * 1.6, 0) if k == 0 else (0, side * 3.0, 0),
                     rotation=((0.28 if k < 3 else -0.6) * side * (1 if k else 0.5), 0, 0),
                     boxes=[Box((-0.4, -0.1 if side > 0 else -3.1, -0.45), (0.8, 3.2, 0.9), gold_s if k in (0, 3) else solid((236, 228, 206), k=0.05))])
                for k in range(4)]
        bow_limbs.append(chain(segs))
    bow = Part("bow", pivot=(0, 5.6, -0.4), boxes=[
        Box((-0.55, -1.8, -0.55), (1.1, 3.6, 1.1), leather(r, studs=False)),
        Box((-0.12, -12.4, 3.5), (0.24, 24.8, 0.24), jewel(GOLD_LIGHT), flat=True),
    ], children=bow_limbs)
    chains = []
    for side in (-1, 1):
        s = "l" if side > 0 else "r"
        links = [Box((-0.12 if k % 2 else -0.3, 0.9 * k, -0.3 if k % 2 else -0.12), (0.24 if k % 2 else 0.6, 1.1, 0.6 if k % 2 else 0.24), gold_s)
                 for k in range(6)]
        load = Box((-0.9, -1.2, -0.9), (1.8, 1.2, 1.8), solid((214, 182, 92))) if side > 0 else Box((-0.6, -0.8, -0.6), (1.2, 0.8, 1.2), solid((150, 150, 150)))
        pan = Part("scale_pan_" + s, pivot=(0, 5.6, 0), boxes=[
            Box((-2.2, 0, -2.2), (4.4, 0.6, 4.4), gold_s),
            Box((-1.6, 0.6, -1.6), (3.2, 0.5, 3.2), gold_s),
        ], children=[Part("scale_load_" + s, pivot=(0, 0, 0), boxes=[load])])
        chains.append(Part("scale_chain_" + s, pivot=(side * 5.4, -6.8, 0), boxes=links, children=[pan]))
    scales = Part("scales", pivot=(0, 5.6, -0.4), rotation=(1.2, 0, 0), boxes=[
        Box((-0.5, -6.6, -0.5), (1.0, 8.6, 1.0), gold_s),
        Box((-5.8, -7.4, -0.45), (11.6, 0.9, 0.9), gold_s),
        Box((-0.8, -8.6, -0.8), (1.6, 1.6, 1.6), gold_s),
        Box((-0.4, -10.2, -0.4), (0.8, 1.8, 0.8), jewel(GOLD_LIGHT)),
    ], children=chains)
    quiver = Part("quiver", pivot=(-1.5, -6.5, 2.8), rotation=(0, 0, -0.45), boxes=[
        Box((-1.3, -3, -1.0), (2.6, 9, 2.0), leather(r)),
        Box((-1.4, -3.4, -1.1), (2.8, 0.8, 2.2), gold_s),
    ] + [Box((-0.9 + 0.6 * k, -6 + 0.4 * (k % 2), -0.5), (0.4, 3, 1.0), feather((250, 250, 240), GOLD), flat=True) for k in range(4)])

    crown = Part("crown", pivot=(0, -5.0, -0.1), boxes=[
        Box((-2.75, -1.2, -2.85), (5.5, 1.3, 0.4), gold_s), Box((-2.75, -1.2, 2.45), (5.5, 1.3, 0.4), gold_s),
        Box((-2.75, -1.2, -2.45), (0.4, 1.3, 4.9), gold_s), Box((2.35, -1.2, -2.45), (0.4, 1.3, 4.9), gold_s),
    ] + [Box((x - 0.35, -2.8 - (0.7 if x == 0 else 0), -2.85), (0.7, 1.7 + (0.7 if x == 0 else 0), 0.4), gold_s) for x in (-2.2, -1.1, 0, 1.1, 2.2)]
      + [Box((x - 0.35, -2.7, 2.45), (0.7, 1.6, 0.4), gold_s) for x in (-1.6, 0, 1.6)]
      + [Box((x - 0.3, -3.5 - (0.7 if x == 0 else 0), -2.95), (0.6, 0.6, 0.5), jewel(CINNABAR if x == 0 else (120, 200, 255))) for x in (-2.2, 0, 2.2)])
    crest = mane(dict(r, mane=(200, 30, 14), mane_tip=(255, 190, 80), mane_glow="fire"))
    helm = Part("helm", boxes=[
        Box((-2.7, -6.4, -2.9), (5.4, 2.6, 5.6), pl),
        Box((-2.1, -7.4, -2.3), (4.2, 1.1, 4.6), pl),
        Box((-2.75, -4.0, -2.9), (0.6, 3.9, 3.0), pl),           # cheek guards
        Box((2.15, -4.0, -2.9), (0.6, 3.9, 3.0), pl),
        Box((-2.8, -4.0, 0.0), (5.6, 4.6, 2.9), solid(shade(r["plate"], 0.8))),   # aventail
    ], children=[lock("helm_crest_%d" % k, pivot=(0, -7.2, -1.8 + 1.4 * k), rotation=(1.3 + 0.3 * k, 0, 0), length=6 + k, width=1.2,
                      style=crest) for k in range(3)])
    hood_cloth = drapery(shade(r["cloth"], 0.9), highlight=r["cloth_hl"])
    hood = Part("hood", boxes=[
        Box((-3.0, -6.9, -2.4), (6.0, 1.4, 5.6), hood_cloth),
        Box((-3.0, -6.6, 2.2), (6.0, 7.4, 1.2), hood_cloth),
        Box((-3.2, -6.4, -3.3), (0.8, 7.6, 5.8), hood_cloth),
        Box((2.4, -6.4, -3.3), (0.8, 7.6, 5.8), hood_cloth),
        Box((-2.8, -6.6, -3.9), (5.6, 1.5, 1.6), hood_cloth),
        Box((-1.6, -8.2, 0.8), (3.2, 1.6, 3.0), hood_cloth),
    ])
    head = Part("rider_head", pivot=(0, -10.9, -0.2), boxes=[
        Box((-2.3, -5.6, -2.6), (4.6, 5.6, 5.0), rider_face(r)),
    ], children=[crown, helm, hood])

    strips = []
    for i in range(5):
        segs = []
        for k in range(3):
            L = (7.5, 7.0, 6.5)[k]
            segs.append(Part("cloak_%d_%d" % (i, k), pivot=(-4.0 + 2.0 * i, 0, 0) if k == 0 else (0, (7.5, 7.0)[k - 1] - 0.3, 0),
                             rotation=(0.62 + 0.04 * math.sin(i * 1.9), 0.03 * (i - 2), 0) if k == 0 else (0.22 + 0.06 * k, 0, 0.02 * (i - 2)),
                             boxes=[Box((-1.15, 0, -0.25), (2.3, L, 0.5), caparison(r) if k == 2 else drapery(r["cloth"], highlight=r["cloth_hl"]))]))
        strips.append(chain(segs))
    cloak = Part("cloak", pivot=(0, -9.6, 2.7), children=strips)

    shroud = Part("shroud", pivot=(0, -9.6, 0), children=[
        Part("shroud_%d" % i, pivot=(math.sin(a) * 3.9, 0, -math.cos(a) * 3.0), rotation=(-0.25, -a, 0),
             boxes=[Box((-1.6, -0.5, -0.3), (3.2, 7.5 + 1.5 * math.sin(i * 2.3), 0.6), caparison(r, hem=False))])
        for i, a in enumerate([2 * math.pi * k / 8 for k in range(8)])])
    tassets = skirt("tassets", pivot=(0, -1.5, -0.2), top_radius=3.4, bottom_radius=4.6, height=5.0, panels=8,
                    style=caparison(r, hem=False) if skull else plate(r, rivets=False))

    body = Part("rider_body", pivot=(0, -2.4, 0), boxes=[
        Box((-2.9, -3.6, -2.1), (5.8, 3.8, 4.2), torso_s),
        Box((-3.7, -9.9, -2.6), (7.4, 6.6, 5.2), torso_s),
        Box((-3.1, -9.4, -3.1), (6.2, 5.0, 0.6), torso_s if skull else plate(r, emblem=True)),
        Box((-3.3, -3.9, -2.5), (6.6, 1.1, 5.0), solid(shade(r["cloth"], 0.7)) if skull else solid(r["plate_trim"])),
        Box((-1.0, -11.2, -1.0), (2.0, 1.6, 2.0), solid(r["skin"])),
        Box((-2.2, -10.6, -1.8), (4.4, 1.0, 3.6), solid(r["cloth"]) if skull else pl),
    ], children=[head, pauldron("pauldron_r", -1), pauldron("pauldron_l", 1),
                 arm("right_arm", -1, [sword, scythe]), arm("left_arm", 1, [bow, scales]),
                 cloak, shroud, quiver])
    return Part("rider", pivot=(0, -12.2, 0.5 - HIP_Z), boxes=[
        Box((-3.2, -2.6, -2.4), (6.4, 3.0, 4.8), solid(r["cloth"]) if skull else pl),
    ], children=[body, tassets, leg("rider_leg_r", -1), leg("rider_leg_l", 1)])


def make(r):
    st = coat(r)
    cap = caparison(r)
    gold_s = gold_leaf(ornament=False)
    sections = [(-18.5, 3.8, -3.0, 4.0), (-16.5, 5.4, -7.5, 6.0), (-12.5, 6.1, -9.4, 7.2), (-6, 6.5, -9.0, 7.6),
                (1, 6.6, -8.6, 7.2), (7, 6.4, -8.9, 5.4), (12, 6.4, -9.6, 3.6), (16.5, 5.2, -9.2, 2.4), (19.5, 3.0, -7.4, 0.2)]
    body = loft(sections, lambda i: coat(r, ribs=2 <= i <= 4))

    def z(v):
        return v - HIP_Z
    body += [
        Box((-2.3, -11.4, z(-13.5)), (4.6, 3.0, 8.0), st),                    # withers
        Box((-5.0, -2.5, z(-19.6)), (4.6, 9.0, 4.4), st),                     # chest, right and left
        Box((0.4, -2.5, z(-19.6)), (4.6, 9.0, 4.4), st),
        Box((-6.9, -8.4, z(9.5)), (6.6, 9.6, 8.0), st),                       # haunches, bevelled on top
        Box((0.3, -8.4, z(9.5)), (6.6, 9.6, 8.0), st),
        Box((-5.6, -10.5, z(10.0)), (5.3, 2.2, 7.2), st),
        Box((0.3, -10.5, z(10.0)), (5.3, 2.2, 7.2), st),
        Box((-5.6, -9.0, z(17.4)), (5.1, 9.0, 2.2), st),                      # buttocks, rounding off the rump
        Box((0.5, -9.0, z(17.4)), (5.1, 9.0, 2.2), st),
        Box((-4.4, -7.6, z(19.5)), (3.8, 6.4, 0.9), st),
        Box((0.6, -7.6, z(19.5)), (3.8, 6.4, 0.9), st),
        Box((-4.2, -1.0, z(-3)), (8.4, 1.2, 1.4), leather(r)),                # girth
    ]
    shoulders = [Part("shoulder_%s" % ("l" if s > 0 else "r"), pivot=(s * 5.1, -8.4, z(-11.0)), rotation=(-0.5, 0, -s * 0.06),
                      boxes=[Box((-1.3, 0, -3.4), (2.6, 10.0, 6.8), st)]) for s in (-1, 1)]

    # The caparison: a blanket over the back, panels down each flank with the sign in the middle, a peytral at the chest.
    barding = [Box((-7.1, -10.6, z(-10)), (14.2, 1.4, 20), cap)]
    panels = []
    for side in (-1, 1):
        s = "l" if side > 0 else "r"
        for i in range(6):
            zc = -9.5 + i * 3.6
            length = 14.5 + 0.8 * math.sin(i * 1.7)
            kids = []
            if i in (2, 3):
                kids.append(Part("medallion_%s_%d" % (s, i), pivot=(side * 0.35, 3.6, 1.8 if i == 2 else -1.8),
                                 boxes=[Box((-0.3, 0, -2.6), (0.6, 5.2, 5.2), medallion(r))]))
            panels.append(Part("barding_%s_%d" % (s, i), pivot=(side * 7.0, -9.8, z(zc)),
                               rotation=(0, 0, -side * (0.08 + 0.02 * math.sin(i * 2.1))),
                               boxes=[Box((-0.3, 0, -1.95), (0.6, length, 3.9), cap)], children=kids))
    for i in range(3):
        panels.append(Part("peytral_%d" % i, pivot=(-4.6 + i * 4.6, -3.2, z(-19.4)), rotation=(-0.12, 0.25 * (i - 1), 0),
                           boxes=[Box((-2.5, 0, -0.3), (5.0, 8.6 - 0.8 * abs(i - 1), 0.6), cap)]))

    saddle = Part("saddle", pivot=(0, -10.8, z(0.5)), boxes=[
        Box((-3.7, -1.2, -4.8), (7.4, 1.4, 10.0), leather(r)),
        Box((-2.6, -4.0, -6.0), (5.2, 3.0, 1.8), leather(r)),
        Box((-2.0, -4.8, -6.4), (4.0, 1.0, 1.6), gold_s),
        Box((-3.4, -4.6, 4.2), (6.8, 3.8, 1.8), leather(r)),
        Box((-3.6, -5.3, 4.6), (7.2, 0.9, 1.6), gold_s),
        Box((-4.4, -0.6, -3.4), (0.8, 6.5, 6.6), leather(r)),
        Box((3.6, -0.6, -3.4), (0.8, 6.5, 6.6), leather(r)),
    ])

    horse = Part("horse", pivot=(0, 0, HIP_Z), boxes=body + barding, children=shoulders + panels + [
        saddle, neck_chain(r), tail_chain(r), make_rider(r),
        front_leg(r, "leg_front_right", -3.7), front_leg(r, "leg_front_left", 3.7),
        hind_leg(r, "leg_back_right", -4.0), hind_leg(r, "leg_back_left", 4.0),
        fumes(r),
    ])
    return [horse]


if __name__ == "__main__":
    for i, name in enumerate(("white", "red", "black", "pale")):
        build("Horsemen", "horsemen_" + name, make(RIDERS[name]), size=256, seed=41 + i, density=4)
