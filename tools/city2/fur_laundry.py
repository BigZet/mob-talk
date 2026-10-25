"""Washing day in a Soviet bathroom: the «Малютка» on its stool, the «Сибирь» twin tub, a folding drying rack and the «лиана» over
the bath hung with washing, a zinc basin with washing to soak and a wicker laundry basket. Same conventions as fur_models.py (north
space, front at low z, x to the viewer's left)."""
from fur_paint import rgb, mix, ramp
from furniture import Furn, Model, Pic, mat
from fur_models import ENAMEL
import fur_paint as P


def materials():
    mat("laundry_white", P.fabric(32, 32, rgb("eeeae0"), "lwh", weave=1))
    mat("laundry_pink", P.fabric(32, 32, rgb("d88a9a"), "lpk"))
    mat("laundry_grey", P.fabric(32, 32, rgb("8a8a86"), "lgr"))
    plaid = P.fabric(32, 32, rgb("3a5a8a"), "lpl", weave=1)
    for i in range(32):
        if i % 8 in (0, 1):
            plaid.hline(0, 32, i, rgb("c8d0dc"))
            plaid.vline(i, 0, 32, rgb("c8d0dc"))
    mat("laundry_plaid", plaid)
    mat("plastic_grey", P.enamel(32, 32, rgb("9a9c98")))
    mat("zinc", P.metal(32, 32, rgb("a8acaa"), vertical=False))
    wick = P.Canvas(32, 32, rgb("b8905a"))
    t = ramp(rgb("b8905a"), 5, 0.7, 1.15)
    for y in range(32):
        for x in range(32):
            wick.set(x, y, t[3] if (x // 2 + y // 2) % 2 else t[1])
    mat("wicker", wick)


def badge(cv, x, y, w, c):
    for i in range(w):
        cv.set(x + i, y + (1 if i % 3 == 1 else 0), c)


def malyutka():
    def panel(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("4a4c4e"))
        cv.outline(0, 0, cv.w, cv.h, rgb("2a2c2e"))
        cv.disc(cv.w / 2, cv.h / 2 + 1, 2.2, rgb("d8d8d4"), ring=rgb("8a8a86"))
        badge(cv, 2, 2, cv.w - 4, rgb("c8c8c4"))

    def build():
        m = Model()
        # a kitchen stool and on it the «Малютка»: a white tank, a grey lid, the motor box on the front with its timer knob
        for x, z in ((3.5, 4), (11.5, 4), (3.5, 12), (11.5, 12)):
            m.box((x, 0, z), (x + 1, 6, z + 1), "lightwood", up=None)
        m.box((3, 6, 3.5), (13, 7, 13.5), "lightwood")
        m.box((4.5, 7, 5.5), (11.5, 14, 12.5), "enamel", down=None)
        m.box((4, 14, 5), (12, 15, 13), "plastic_grey", down=None)
        m.box((7, 15, 8), (9, 15.5, 10), "plastic_grey", down=None)
        m.box((5.5, 8, 4), (10.5, 13, 5.5), "plastic_grey", south=None, north=Pic("panel", panel))
        # the drain hose hooked over the stool's edge
        m.box((11.5, 9, 9), (12.5, 10, 10), "black", west=None)
        m.box((12.5, 3, 9), (13.5, 10, 10), "black")
        return m
    return build


def sibir():
    def front(cv, W, H):
        t = ramp(ENAMEL, 6, 0.72, 1.06)
        cv.rect(0, 0, cv.w, cv.h, t[4])
        cv.outline(0, 0, cv.w, cv.h, t[2])
        cv.rect(0, 4, cv.w, 7, rgb("3a6ab0"))  # the blue stripe
        cv.rect(3, 10, 13, 14, rgb("d4d8dc"))
        cv.outline(3, 10, 13, 14, rgb("8a9096"))
        badge(cv, 5, 12, 6, rgb("b02020"))
        cv.vline(cv.w * 3 // 5, 8, cv.h - 3, t[2])  # the line between the two tubs
        for y in range(cv.h - 5, cv.h - 1, 2):
            cv.hline(3, cv.w - 3, y, t[1])

    def top(cv, W, H):
        t = ramp(ENAMEL, 6, 0.72, 1.06)
        cv.rect(0, 0, cv.w, cv.h, t[4])
        # the wash tub's big lid on the right, the spinner's round lid on the left (seen from the front)
        cv.rect(cv.w * 2 // 5 + 1, 1, cv.w - 2, cv.h - 1, rgb("c8ccd0"))
        cv.outline(cv.w * 2 // 5 + 1, 1, cv.w - 2, cv.h - 1, rgb("8a9096"))
        cv.disc(cv.w // 5, cv.h // 2, cv.w // 6, rgb("c8ccd0"), ring=rgb("8a9096"))

    def build():
        m = Model()
        # the twin-tub «Сибирь»: wash on one side, spin dry on the other, timers on a raised panel at the back
        m.box((1, 0, 3), (15, 1, 15), "black", down=None, up=None)
        m.box((0.5, 1, 2.5), (15.5, 14, 15.5), "enamel", down=None, north=Pic("front", front), up=Pic("top", top))
        m.box((0.5, 14, 12.5), (15.5, 16.5, 15.5), "enamel", down=None)
        for x in (3, 7, 11.5):
            m.box((x, 14.5, 12), (x + 1.5, 16, 12.5), "black", south=None)
        return m
    return build


def hang(m, x0, x1, z, bottom, top, material):
    """A piece of washing thrown over a rail: one thick fold hanging either side of it."""
    m.box((x0, bottom, z - 0.75), (x1, top + 0.75, z + 0.75), material)


def dryer_rack():
    def build():
        m = Model()
        # the folding floor rack (two cells long): end frames, a top rail each side and five wire lines between
        for x in (0.5, 30.5):
            for z in (2, 13.5):
                m.box((x, 0, z), (x + 1, 14, z + 0.5), "chrome", up=None)
            m.box((x, 14, 2), (x + 1, 14.5, 14), "chrome")
        for z in (4.25, 6.75, 9.25, 11.75):
            m.box((1.5, 14, z), (30.5, 14.5, z + 0.5), "chrome", faces=("north", "south", "up", "down"))
        # the washing: a striped towel, a plaid shirt, a sheet, a pink nightdress, three socks
        hang(m, 3, 10, 4.5, 6, 14, "towel")
        hang(m, 12, 19, 7, 7.5, 14, "laundry_plaid")
        hang(m, 2, 29, 9.5, 3, 14, "laundry_white")
        hang(m, 21, 28, 12, 5, 14, "laundry_pink")
        for x in (22, 24.5, 27):
            hang(m, x, x + 1.5, 4.5, 10.5, 14, "laundry_grey")
        return m
    return build


def dryer_liana():
    def build():
        m = Model()
        # the «лиана»: ceiling brackets at both ends, rods let down on cords over the bath, washing on them
        for x in (0.5, 30.5):
            m.box((x, 15, 2), (x + 1, 16, 14), "enamel")
            for z in (3, 6.5, 10, 13.5):
                m.box((x + 0.25, 10.5, z - 0.25), (x + 0.75, 15, z + 0.25), "laundry_white", up=None, down=None)
        for z in (3, 6.5, 10, 13.5):
            m.box((0.5, 10, z - 0.5), (31.5, 10.5, z + 0.5), "enamel")
        hang(m, 2, 12, 3, 1, 10, "laundry_white")
        hang(m, 14, 20, 6.5, 3, 10, "laundry_plaid")
        hang(m, 22, 29, 6.5, 2, 10, "towel")
        hang(m, 4, 10, 10, 4, 10, "laundry_pink")
        for x in (14, 16.5, 19, 21.5):
            hang(m, x, x + 1.5, 13.5, 6, 10, "laundry_grey")
        return m
    return build


def zinc_basin():
    def inside(cv, W, H):
        t = ramp(rgb("a8acaa"), 5, 0.7, 1.15)
        cv.rect(0, 0, cv.w, cv.h, t[3])
        cv.rect(2, 2, cv.w - 2, cv.h - 2, rgb("8aa6b0"))  # soapy water
        for (x, y, c) in ((5, 6, rgb("3a5a8a")), (12, 10, rgb("d88a9a")), (8, 14, rgb("eeeae0"))):
            cv.rect(x, y, x + 6, y + 4, c)
        for x in range(3, cv.w - 3, 4):
            cv.set(x, 3 + x % 3, rgb("f4f8fa"))

    def build():
        m = Model()
        # a galvanised basin on the floor with washing left to soak, ribbed sides and two handles
        m.box((2, 0, 2), (14, 4, 14), "zinc", up=Pic("inside", inside))
        m.box((1.5, 3.5, 1.5), (14.5, 4.5, 14.5), "zinc", up=None, down=None)
        m.box((6, 3, 1), (10, 3.5, 1.5), "zinc", south=None)
        m.box((6, 3, 14.5), (10, 3.5, 15), "zinc", north=None)
        return m
    return build


def laundry_basket():
    def build():
        m = Model()
        # a wicker basket with a lid, a sleeve hanging out from under it
        m.box((4, 0, 5), (12, 10, 13), "wicker")
        m.box((3.5, 10, 4.5), (12.5, 11, 13.5), "wicker")
        m.box((7, 11, 8), (9, 11.5, 10), "wicker", down=None)
        m.box((9, 7, 4.5), (11, 10, 5), "laundry_plaid", south=None)
        return m
    return build


def make():
    materials()
    Furn("apt_washer_malyutka", "Стиральная машина «Малютка» на табурете", "Malyutka washer on a stool", malyutka(), color="WHITE",
         sound="WOOD")
    Furn("apt_washer_sibir", "Стиральная машина «Сибирь» (двухбаковая)", "Sibir twin-tub washer", sibir(), color="WHITE",
         sound="METAL")
    Furn("apt_dryer_rack", "Сушилка для белья напольная", "Folding drying rack", dryer_rack(), color="WHITE", sound="METAL",
         solid=False)
    Furn("apt_dryer_liana", "Сушилка «лиана» с бельём", "Ceiling clothes airer", dryer_liana(), mount="CEILING", color="WHITE",
         sound="WOOL", solid=False)
    Furn("apt_basin_zinc", "Оцинкованный таз с бельём", "Zinc basin with washing", zinc_basin(), color="IRON_GRAY", sound="METAL")
    Furn("apt_laundry_basket", "Корзина для белья", "Wicker laundry basket", laundry_basket(), color="OAK_TAN", sound="WOOD")
