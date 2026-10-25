"""More Soviet fridges beside the ZIL: the small rounded «Саратов», the tall two-door «Минск» and the cream «Бирюса» with a lace
napkin on top. Same conventions as fur_models.py (north space, front at low z, x to the viewer's left)."""
from fur_paint import rgb, mix, ramp
from furniture import Furn, Model, Pic, mat
from fur_models import ENAMEL, lace_doily
import fur_paint as P

CREAM = rgb("efe6cc")


def script(cv, x, y, n, c):
    """A chrome script logo, too small for letters: a slanted line of loops, as the badges read from a few steps away."""
    for i in range(n):
        cv.set(x + i, y + (1 if i % 3 == 1 else 0), c)
        if i % 3 == 0:
            cv.set(x + i, y - 1, c)


def enamel_face(cv, base):
    t = ramp(base, 6, 0.72, 1.06)
    cv.rect(0, 0, cv.w, cv.h, t[4])
    cv.outline(0, 0, cv.w, cv.h, t[2])
    cv.hline(1, cv.w - 1, 1, t[5])
    cv.vline(1, 1, cv.h - 1, t[5])
    cv.vline(cv.w - 2, 2, cv.h - 1, t[3])
    return t


def saratov():
    def door(cv, W, H):
        t = enamel_face(cv, ENAMEL)
        # the rounded top shows as a shaded band, the chrome nameplate under it, a ventilation grille at the foot
        cv.hline(2, cv.w - 2, 2, t[3])
        cv.rect(4, 6, cv.w - 4, 10, rgb("d4d8dc"))
        cv.outline(4, 6, cv.w - 4, 10, rgb("8a9096"))
        script(cv, 6, 8, cv.w - 12, rgb("b02020"))
        for y in range(cv.h - 5, cv.h - 1, 2):
            cv.hline(3, cv.w - 3, y, t[1])

    def build():
        m = Model()
        # the little «Саратов»: 1.05 m, rounded shoulders, a chrome lever on the side
        m.box((4, 0, 6), (12, 1, 15), "black", down=None, up=None)
        m.box((3.5, 1, 5.5), (12.5, 16, 15.5), "enamel", down=None, up=None, north=Pic("door", door))
        m.box((4, 16, 6), (12, 16.75, 15), "enamel", down=None)
        m.box((10, 6, 4.5), (11, 9.5, 5.5), "chrome")
        return m
    return build


def minsk():
    def door(cv, W, H):
        t = enamel_face(cv, ENAMEL)
        # two doors: the freezer on top, a black rubber gap between them, the badge on the big door
        gap = cv.h * 9 // 27
        cv.hline(1, cv.w - 1, gap, rgb("3a3a3c"))
        cv.hline(1, cv.w - 1, gap + 1, t[5])
        cv.hline(1, cv.w - 1, gap - 1, t[3])
        cv.rect(5, gap + 5, cv.w - 5, gap + 10, rgb("d4d8dc"))
        cv.outline(5, gap + 5, cv.w - 5, gap + 10, rgb("8a9096"))
        script(cv, 7, gap + 7, cv.w - 14, rgb("2a4a8a"))
        for y in range(cv.h - 5, cv.h - 1, 2):
            cv.hline(3, cv.w - 3, y, t[1])

    def build():
        m = Model()
        # the two-chamber «Минск-15»: 1.75 m, a freezer door over the main one, a long chrome handle on each
        m.box((3, 0, 5.5), (13, 1, 15), "black", down=None, up=None)
        m.box((2.5, 1, 5), (13.5, 28, 15.5), "enamel", down=None, north=Pic("door", door))
        for y0, y1 in ((20.5, 26), (9, 17)):
            m.box((10.5, y0, 3.75), (11.5, y1, 4.5), "chrome")
            m.box((10.75, y0 + 0.5, 4.5), (11.25, y0 + 1.5, 5), "chrome", north=None, south=None)
            m.box((10.75, y1 - 1.5, 4.5), (11.25, y1 - 0.5, 5), "chrome", north=None, south=None)
        return m
    return build


def biryusa():
    def door(cv, W, H):
        t = enamel_face(cv, CREAM)
        # a recessed handle groove down the side, the badge at the top
        cv.rect(cv.w - 6, 12, cv.w - 3, 28, t[1])
        cv.vline(cv.w - 6, 12, 28, t[0])
        cv.rect(4, 4, cv.w - 8, 8, rgb("c8a860"))
        script(cv, 5, 6, cv.w - 14, rgb("6a3a1a"))
        for y in range(cv.h - 5, cv.h - 1, 2):
            cv.hline(3, cv.w - 3, y, t[1])

    def top(cv, W, H):
        t = ramp(CREAM, 6, 0.72, 1.06)
        cv.rect(0, 0, cv.w, cv.h, t[4])
        cv.outline(0, 0, cv.w, cv.h, t[2])
        lace_doily(cv, cv.w // 2, cv.h // 2, 7)

    def build():
        m = Model()
        # the cream «Бирюса»: 1.4 m, a lace napkin on top with a little vase on it
        m.box((3.5, 0, 5.5), (12.5, 1, 15), "black", down=None, up=None)
        m.box((3, 1, 5), (13, 22, 15.5), "enamel_cream", down=None, north=Pic("door", door), up=Pic("top", top))
        m.box((7, 22, 9), (9, 25, 11), "glass_red", down=None)
        m.box((7.5, 25, 9.5), (8.5, 26, 10.5), "glass_red", down=None)
        return m
    return build


def make():
    mat("enamel_cream", P.enamel(32, 32, CREAM))
    Furn("apt_fridge_saratov", "Холодильник «Саратов»", "Saratov fridge", saratov(), color="WHITE", sound="METAL")
    Furn("apt_fridge_minsk", "Холодильник «Минск» (двухкамерный)", "Minsk two-door fridge", minsk(), color="WHITE", sound="METAL")
    Furn("apt_fridge_biryusa", "Холодильник «Бирюса»", "Biryusa fridge", biryusa(), color="WHITE", sound="METAL")
