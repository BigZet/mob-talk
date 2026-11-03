"""Floor rugs at real sizes and with real patterns: a big "Persian" medallion rug, a Turkmen rug of guls, a hall runner and the
bedside rug. Each is a half-pixel plane painted at two texels a pixel, with knotted fringe along the short ends.
Same conventions as fur_models.py (north space, front at low z, x to the viewer's left)."""
import math

from fur_paint import rgb, mix, ramp
from furniture import Furn, Model, Pic, mat
import fur_paint as P

PERSIAN = dict(field=rgb("8e1c24"), border=rgb("1c2850"), ivory=rgb("e6d2a2"), gold=rgb("c8923c"), dark=rgb("20140e"), blue=rgb("3a5a8a"))
PERSIAN_BLUE = dict(field=rgb("23406a"), border=rgb("6e1c22"), ivory=rgb("e6d4a6"), gold=rgb("c89a44"), dark=rgb("141a26"), blue=rgb("8e2a2a"))
TURKMEN = dict(field=rgb("6e1a1c"), border=rgb("3a1210"), ivory=rgb("e8d8b4"), gold=rgb("d07a2a"), dark=rgb("1e1010"), blue=rgb("24345a"))
RUNNER = dict(field=rgb("7a2626"), border=rgb("2a3a2a"), ivory=rgb("e2d0a0"), gold=rgb("c08a38"), dark=rgb("1e1612"), blue=rgb("3a6a4a"))


def flower(cv, x, y, petal, centre):
    for dx, dy in ((0, -2), (0, 2), (-2, 0), (2, 0), (-1, -1), (1, -1), (-1, 1), (1, 1)):
        cv.set(x + dx, y + dy, petal)
    cv.set(x, y, centre)


def frame(cv, p, band):
    """Outer guard stripes, the main border (a vine with rosettes on a dark ground), inner guard; returns the field's inset."""
    w, h = cv.w, cv.h
    for y in range(h):
        for x in range(w):
            e = min(x, w - 1 - x, y, h - 1 - y)
            if e == 0:
                c = p["dark"]
            elif e == 1:
                c = p["field"]
            elif e == 2:
                c = p["ivory"] if (x + y) % 4 else p["gold"]
            elif e == 3:
                c = p["dark"]
            elif e < 4 + band:
                c = p["border"]
            elif e == 4 + band:
                c = p["dark"]
            elif e == 5 + band:
                c = p["ivory"] if (x + y) % 2 else p["gold"]
            elif e == 6 + band:
                c = p["dark"]
            else:
                continue
            cv.set(x, y, c)
    # the vine: a zigzag along the middle of the border band, a rosette at every turn
    mid = 4 + band // 2
    step = 8
    for t in range(0, 2 * (w + h)):
        if t < w:
            x, y, along = t, mid, t
        elif t < w + h:
            x, y, along = w - 1 - mid, t - w, t - w
        elif t < 2 * w + h:
            x, y, along = w - 1 - (t - w - h), h - 1 - mid, t - w - h
        else:
            x, y, along = mid, h - 1 - (t - 2 * w - h), t - 2 * w - h
        if min(x, w - 1 - x, y, h - 1 - y) < mid - 1:
            continue
        off = (along % step) - step // 2
        zig = 1 if (along // step) % 2 else -1
        dx, dy = (0, zig * (abs(off) // 2 - 1)) if t < w or (2 * w + h > t >= w + h) else (zig * (abs(off) // 2 - 1), 0)
        cv.set(x + dx, y + dy, p["gold"])
        if along % step == 0:
            flower(cv, x, y, p["ivory"], p["field"])
    return 7 + band


def stepped(ax, ay):
    """A stepped lozenge distance: the pixel-staircase outline of knotted medallions."""
    return (ax // 2 * 2) + (ay // 2 * 2)


def persian(cv, W, H, p=PERSIAN):
    w, h = cv.w, cv.h
    band = max(6, min(w, h) // 9)
    t = ramp(p["field"], 5, 0.82, 1.15)
    for y in range(h):  # abrash: the field's red shifts a little from row to row of wool, as hand-dyed yarn does
        tone = t[2] if (y // 7) % 3 else t[1 if (y // 7) % 2 else 3]
        for x in range(w):
            cv.set(x, y, tone)
    inset = frame(cv, p, band)
    fx0, fy0, fx1, fy1 = inset, inset, w - inset, h - inset
    cx, cy = (w - 1) / 2, (h - 1) / 2
    fw, fh = fx1 - fx0, fy1 - fy0
    # small motifs scattered on a staggered lattice over the field
    for j, y in enumerate(range(fy0 + 4, fy1 - 3, 8)):
        for x in range(fx0 + 4 + (j % 2) * 4, fx1 - 3, 8):
            cv.set(x, y, p["ivory"]); cv.set(x - 1, y, p["dark"]); cv.set(x + 1, y, p["dark"])
            cv.set(x, y - 1, p["gold"]); cv.set(x, y + 1, p["gold"])
    # corner spandrels: quarter lozenges in the border colour, outlined in ivory
    r = min(fw, fh) // 4
    for qx, qy in ((fx0, fy0), (fx1 - 1, fy0), (fx0, fy1 - 1), (fx1 - 1, fy1 - 1)):
        for y in range(fy0, fy1):
            for x in range(fx0, fx1):
                d = stepped(abs(x - qx), abs(y - qy))
                if d < r:
                    cv.set(x, y, p["border"] if d < r - 2 else p["ivory"])
                    if d < r - 5 and (x + y) % 4 == 0:
                        cv.set(x, y, p["gold"])
    # the central medallion: rings of a stepped lozenge with pendants along the long axis, a star in the middle
    a, b = fw * 0.30, fh * 0.30
    for y in range(fy0, fy1):
        for x in range(fx0, fx1):
            ax, ay = abs(x - cx) / a * 20, abs(y - cy) / b * 20
            d = stepped(int(ax), int(ay))
            if d < 20:
                if d >= 18:
                    c = p["ivory"]
                elif d >= 15:
                    c = p["border"]
                elif d >= 13:
                    c = p["gold"]
                elif d >= 6:
                    c = p["field"] if (int(ax) + int(ay)) % 6 else p["blue"]
                elif d >= 4:
                    c = p["ivory"]
                else:
                    c = p["dark"]
                cv.set(x, y, c)
    for s in (-1, 1):  # pendants
        py = int(cy + s * (b + 4))
        for k in range(4):
            for x in range(int(cx) - (3 - k), int(cx) + (3 - k) + 1):
                cv.set(x, py + s * k, p["border"] if k < 3 else p["gold"])
        cv.set(int(cx), py, p["ivory"])
    flower(cv, int(cx), int(cy), p["gold"], p["ivory"])


def turkmen(cv, W, H, p=TURKMEN):
    w, h = cv.w, cv.h
    cv.rect(0, 0, w, h, p["field"])
    band = max(5, min(w, h) // 10)
    inset = frame(cv, p, band)
    fx0, fy0, fx1, fy1 = inset + 1, inset + 1, w - inset - 1, h - inset - 1
    cols = max(2, (fx1 - fx0) // 18)
    rows = max(3, (fy1 - fy0) // 16)
    gw, gh = (fx1 - fx0) / cols, (fy1 - fy0) / rows
    quarters = (p["ivory"], p["blue"], p["gold"], p["field"])
    for j in range(rows):
        for i in range(cols):
            cx, cy = fx0 + (i + 0.5) * gw, fy0 + (j + 0.5) * gh
            rx, ry = gw * 0.42, gh * 0.40
            for y in range(int(cy - ry) - 1, int(cy + ry) + 2):
                for x in range(int(cx - rx) - 1, int(cx + rx) + 2):
                    dx, dy = abs(x + 0.5 - cx) / rx, abs(y + 0.5 - cy) / ry
                    if dx > 1 or dy > 1 or dx + dy > 1.45:
                        continue  # the gul: an octagon
                    edge = dx > 0.85 or dy > 0.85 or dx + dy > 1.3
                    if edge:
                        c = p["dark"]
                    else:
                        q = (x + 0.5 > cx) + 2 * (y + 0.5 > cy)
                        c = quarters[(q + i + j) % 4]
                        if abs(x + 0.5 - cx) < 1 or abs(y + 0.5 - cy) < 1:
                            c = p["dark"]
                        if dx + dy < 0.35:
                            c = p["ivory"] if dx + dy > 0.15 else p["dark"]
                    cv.set(x, y, c)
            # little diamonds between guls
            if i < cols - 1 and j < rows - 1:
                mx, my = int(fx0 + (i + 1) * gw), int(fy0 + (j + 1) * gh)
                for dy in range(-2, 3):
                    for dx in range(-(2 - abs(dy)), 3 - abs(dy)):
                        cv.set(mx + dx, my + dy, p["gold"] if abs(dx) + abs(dy) < 2 else p["dark"])


def runner_pic(cv, W, H, p=RUNNER):
    w, h = cv.w, cv.h
    cv.rect(0, 0, w, h, p["field"])
    band = 4
    inset = frame(cv, p, band)
    cx = (w - 1) / 2
    # a chain of lozenges down the middle, hooks between them
    period = 24
    for y in range(inset, h - inset):
        k = (y - inset) % period - period // 2
        half = (w - 2 * inset) / 2 - 2
        for x in range(inset, w - inset):
            dx = abs(x - cx)
            d = stepped(int(dx * 2), int(abs(k) * 2 * half / (period / 2)))
            if d < half * 2:
                cv.set(x, y, p["ivory"] if d > half * 2 - 3 else (p["blue"] if d > half else p["gold"] if d > 3 else p["dark"]))
        if k == 0:
            for x in range(inset, w - inset):
                if (x + y) % 2 == 0:
                    cv.set(x, y, p["dark"])


def rug(x0, z0, x1, z1, painter, name, fringe=True):
    m = Model()
    m.box((x0, 0, z0), (x1, 0.5, z1), "carpet_side", up=Pic(name, painter), down=None)
    if fringe:  # knotted fringe out of both short ends (the ends along x)
        x = x0 + 0.5
        while x + 0.5 <= x1 - 0.5:
            m.box((x, 0, z0 - 1), (x + 0.5, 0.5, z0), "carpet_fringe", down=None, south=None)
            m.box((x, 0, z1), (x + 0.5, 0.5, z1 + 1), "carpet_fringe", down=None, north=None)
            x += 1
    return m


def make():
    Furn("apt_rug", "Ковёр на пол, большой (3×4)", "Big floor carpet (3x4)",
         lambda: rug(-15, -14.5, 31, 46.5, lambda cv, W, H: persian(cv, W, H), "persian"), color="RED", sound="WOOL", solid=False)
    Furn("apt_rug_blue", "Ковёр на пол, синий (3×4)", "Big blue floor carpet (3x4)",
         lambda: rug(-15, -14.5, 31, 46.5, lambda cv, W, H: persian(cv, W, H, PERSIAN_BLUE), "persian_b"), color="BLUE", sound="WOOL",
         solid=False)
    Furn("apt_rug_turkmen", "Туркменский ковёр (2×3)", "Turkmen carpet (2x3)",
         lambda: rug(0.5, -14.5, 31.5, 30.5, lambda cv, W, H: turkmen(cv, W, H), "turkmen"), color="RED", sound="WOOL", solid=False)
    Furn("apt_runner", "Ковровая дорожка (1×4)", "Hall runner (1x4)",
         lambda: rug(2, -15, 14, 47, lambda cv, W, H: runner_pic(cv, W, H), "runner", fringe=False), color="RED", sound="WOOL",
         solid=False)
