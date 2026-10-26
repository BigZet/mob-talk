"""Door bells, and a small set of early-2000s furniture for the newer high-rises: a corner sofa, a sliding-door wardrobe, a computer
desk with a beige CRT and tower, an office chair, a TV stand with a silver CRT and a DVD player, a two-door silver fridge, a kitchen
with glossy fronts, and a chrome ceiling light with three frosted shades. Same conventions as fur_models.py (north space, front at
low z, x to the viewer's left)."""
from fur_paint import rgb, mix, ramp, shade
from furniture import Furn, Model, Pic, mat
from fur_lamps import stack, mouth
from fur_models import panel, knob, handle_bar, wood_canvas, worktop_edge, tufted, piping, legs
import fur_paint as P

BEIGE_SOFA = rgb("c8b08a")
MILK_OAK = rgb("d8c4a0")   # «беленый дуб», the 2000s laminate
WENGE = rgb("4a3628")
GLOSS = rgb("8cb83a")      # the lime-green gloss kitchen fronts
SILVER = rgb("b4b8bc")
PC_BEIGE = rgb("dcd6c4")


def materials():
    mat("fabric_beige", P.fabric(32, 32, BEIGE_SOFA, "fb2k"))
    mat("milk_oak", P.wood(32, 32, MILK_OAK, "mo", vertical=True, polish=0.4, spacing=5))
    mat("wenge", P.wood(32, 32, WENGE, "wg", vertical=True, polish=0.6, spacing=4))
    mat("gloss_lime", P.enamel(32, 32, GLOSS))
    silver = P.Canvas(32, 32, SILVER)  # brushed silver plastic: only a faint grain, not chrome bands
    t = ramp(SILVER, 5, 0.92, 1.06)
    for x in range(32):
        silver.vline(x, 0, 32, t[(2, 2, 3, 2, 1, 2, 3, 3, 2, 4, 2, 2)[x % 12]])
    mat("silver", silver)
    mat("pc_beige", P.enamel(32, 32, PC_BEIGE))
    mat("plastic_black", P.enamel(32, 32, rgb("26272a")))
    mat("plastic_white", P.enamel(32, 32, rgb("eeeeea")))
    mat("worktop_2k", P.enamel(32, 32, rgb("d8cfbf")))
    mat("mesh", P.Canvas(32, 32, rgb("3a3c40")))


# ---------------------------------------------------------------- door bells

def bell_button():
    def build():
        m = Model()
        # the bell push by a flat's door on the landing, at 1.5 m: a round black button in an ivory square
        m.box((6, 8, 15), (10, 12, 16), "switch_plastic", south=None)
        m.box((7, 9, 14.5), (9, 11, 15), "plastic_black", south=None)
        return m
    return build


def soviet_bell():
    def build():
        m = Model()
        # the electric bell over the flat's door: a steel cup, the striker, the coil box behind it
        m.box((5, 5, 14), (11, 11, 16), "plastic_beige", south=None)
        m.box((5.5, 6, 11), (10.5, 11, 14), "chrome", south=None)
        m.box((7.5, 4.5, 12), (8.5, 6, 13), "black")
        m.box((7.75, 3.5, 12.25), (8.25, 4.5, 12.75), "black", up=None)
        return m
    return build


def chime():
    def front(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("eeeeea"))
        cv.outline(0, 0, cv.w, cv.h, rgb("b8b8b4"))
        for y in range(3, cv.h - 3, 2):  # the speaker grille
            for x in range(3, cv.w - 3, 2):
                cv.set(x, y, rgb("9a9a96"))

    def build():
        m = Model()
        # the 2000s ding-dong chime in the hall: a white plastic box with a grille
        m.box((4.5, 5, 14), (11.5, 11, 16), "plastic_white", south=None, north=Pic("front", front))
        return m
    return build


# ---------------------------------------------------------------- the living room

def corner_sofa():
    base = BEIGE_SOFA

    def seat_top(cv, W, H):
        cv.paste(P.fabric(cv.w, cv.h, base, "cst"), 0, 0)
        t = ramp(base)
        for x in range(0, cv.w, cv.w // 3):
            cv.vline(x, 0, cv.h, t[1])
        cv.hline(0, cv.w, 1, t[4])

    def build():
        m = Model()
        # an L-shaped corner sofa: the long run along the wall (two cells), the chaise coming forward on the left (x 16..32)
        m.box((0, 0, 3), (32, 2, 15), "plastic_black", down=None, up=None)
        m.box((16, 0, -14), (32, 2, 3), "plastic_black", down=None, up=None)
        m.box((2, 2, 2), (30, 7.5, 13), "fabric_beige", down=None, north=Pic("front", lambda cv, W, H: (
            cv.paste(P.fabric(cv.w, cv.h, base, "csf"), 0, 0), piping(cv, base, 1))), up=Pic("top", seat_top))
        m.box((16, 2, -14), (30, 7.5, 2), "fabric_beige", down=None, south=None,
              north=Pic("chaise_front", lambda cv, W, H: (cv.paste(P.fabric(cv.w, cv.h, base, "cch"), 0, 0), piping(cv, base, 1))),
              up=Pic("chaise_top", seat_top))
        m.box((0, 2, 13), (32, 15, 15.5), "fabric_beige", down=None, north=Pic("back", lambda cv, W, H: tufted(cv, base, "csb", step=10)))
        m.box((30, 2, -14), (32, 11, 13), "fabric_beige", down=None)          # the arm on the chaise side
        m.box((0, 2, 2), (2, 11, 13), "fabric_beige", down=None)              # the other arm
        for x, c in ((4, rgb("8a3a2a")), (10, rgb("c89a4a")), (22, rgb("8a3a2a"))):  # cushions leant on the back
            m.box((x, 7.5, 10.5), (x + 5, 12.5, 13), "fabric_beige", north=Pic(f"cushion{x}", lambda cv, W, H, c=c: (
                cv.paste(P.fabric(cv.w, cv.h, c, f"cu{x}"), 0, 0), cv.outline(0, 0, cv.w, cv.h, shade(c, 0.7)))), down=None)
        return m
    return build


def wardrobe_coupe():
    def doors(cv, W, H):
        # two sliding doors: one a mirror with sandblasted bamboo, one milk oak with a wenge strip; aluminium runners
        half = cv.w // 2
        wood_canvas(cv, MILK_OAK, "wcd")
        cv.rect(half, 0, cv.w, cv.h, rgb("aebcc4"))
        for y in range(cv.h):
            for x in range(half + 1, cv.w - 1):
                if (x - y) % 13 in (0, 1):
                    cv.set(x, y, rgb("d8e4ea"))
        for x in (half + 8, half + 20):  # frosted bamboo stems on the mirror
            cv.vline(x, 6, cv.h - 6, rgb("e8eef0"))
            for y in range(10, cv.h - 8, 9):
                cv.hline(x - 1, x + 2, y, rgb("f4f8fa"))
        cv.rect(4, cv.h // 2 - 6, half - 4, cv.h // 2 + 6, WENGE)
        for x in (0, half - 1, half, cv.w - 1):
            cv.vline(x, 0, cv.h, rgb("c8ccd0"))
        cv.hline(0, cv.w, 0, rgb("c8ccd0")); cv.hline(0, cv.w, cv.h - 1, rgb("c8ccd0"))

    def build():
        m = Model()
        # the built-in «шкаф-купе»: two cells wide, floor to 2.4 m, sliding doors
        m.box((0, 0, 3), (32, 38, 16), "milk_oak", north=Pic("doors", doors))
        return m
    return build


def pc_desk(on):
    def screen(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, PC_BEIGE)
        cv.bevel(0, 0, cv.w, cv.h, rgb("ece6d6"), rgb("a8a294"))
        x0, y0, x1, y1 = 2, 2, cv.w - 2, cv.h - 4
        if on:  # a desktop: green hills under a blue sky, icons, the taskbar
            cv.vgrad(x0, y0, x1, y1, [rgb("3a7ad8"), rgb("6aa4ec"), rgb("a8ccf4")])
            for x in range(x0, x1):
                hill = y0 + (y1 - y0) * 0.62 + ((x - x0 - (x1 - x0) / 2) ** 2) / ((x1 - x0) * 1.2)
                for y in range(int(hill), y1):
                    cv.set(x, y, rgb("4a9a3a") if y > hill + 1 else rgb("6ab84a"))
            for i in range(3):
                cv.rect(x0 + 1, y0 + 1 + i * 3, x0 + 3, y0 + 3 + i * 3, rgb("f0e4a0"))
            cv.rect(x0, y1 - 1, x1, y1, rgb("2a5ab8"))
            cv.rect(x0, y1 - 1, x0 + 3, y1, rgb("3aa83a"))
        else:
            cv.rect(x0, y0, x1, y1, rgb("2a3030"))
            cv.set(x0 + 1, y0 + 1, rgb("5a6060"))
        cv.set(cv.w - 4, cv.h - 2, rgb("4ad04a") if on else rgb("2a5a2a"))

    def tower(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, PC_BEIGE)
        cv.outline(0, 0, cv.w, cv.h, rgb("a8a294"))
        cv.rect(2, 2, cv.w - 2, 5, rgb("c8c2b0"))   # the CD drive
        cv.hline(3, cv.w - 3, 4, rgb("8a8478"))
        cv.rect(2, 7, cv.w - 2, 9, rgb("c8c2b0"))   # the floppy
        cv.hline(cv.w // 2 - 2, cv.w // 2 + 2, 8, rgb("3a3a3a"))
        cv.disc(cv.w / 2, cv.h - 6, 1.5, rgb("e8e2d0"), ring=rgb("8a8478"))
        cv.set(cv.w // 2 + 3, cv.h - 4, rgb("4ad04a") if on else rgb("2a5a2a"))

    def build():
        m = Model()
        # a corner-less computer desk in milk oak, two cells: the keyboard shelf, the tower in its bay, a CRT, speakers, a mouse
        m.box((0, 0, 4), (1, 12, 16), "milk_oak", down=None)
        m.box((31, 0, 4), (32, 12, 16), "milk_oak", down=None)
        m.box((22, 0, 4), (23, 12, 15), "milk_oak", down=None)
        m.box((1, 0, 15), (31, 12, 16), "milk_oak", north=None, down=None)
        m.box((0, 12, 2), (32, 13, 16), "milk_oak", down=None)
        m.box((2, 9, 3), (21, 9.5, 12), "milk_oak")                                            # the keyboard shelf
        m.box((5, 9.5, 4), (17, 10, 8), "pc_beige", down=None)                                  # the keyboard
        m.box((24, 0.5, 5), (30, 11, 14), "pc_beige", down=None, north=Pic("tower" + str(int(on)), tower))
        m.box((6.5, 13, 6), (17.5, 22, 15), "pc_beige", down=None, north=Pic("screen" + str(int(on)), screen))
        m.box((9, 13, 15), (15, 20, 15.75), "pc_beige", north=None, down=None)
        for x in (2, 19):                                                                       # speakers
            m.box((x, 13, 9), (x + 3, 18, 12), "plastic_black", down=None,
                  north=Pic(f"spk{x}", lambda cv, W, H: (cv.rect(0, 0, cv.w, cv.h, rgb("26272a")),
                                                         cv.disc(cv.w / 2, cv.h * 0.62, 2.2, rgb("4a4c50"), ring=rgb("15161a")))))
        m.box((21, 13, 4), (26, 13.5, 9), "plastic_blue", down=None)                            # mouse pad
        m.box((23, 13.5, 5.5), (24.5, 14.25, 7.5), "pc_beige", down=None)
        return m
    return build


def office_chair():
    def build():
        m = Model()
        # a black swivel chair: five spokes on castors, the gas lift, the seat, the back
        m.box((7.5, 0.5, 2), (8.5, 1.5, 14), "plastic_black")
        m.box((2, 0.5, 7.5), (7.5, 1.5, 8.5), "plastic_black", east=None)
        m.box((8.5, 0.5, 7.5), (14, 1.5, 8.5), "plastic_black", west=None)
        for x, z in ((7.5, 1.5), (7.5, 13.5), (1.5, 7.5), (13.5, 7.5)):
            m.box((x, 0, z), (x + 1, 0.5, z + 1), "rubber_black", up=None)
        m.box((7.25, 1.5, 7.25), (8.75, 6.5, 8.75), "chrome", up=None, down=None)
        m.box((3, 6.5, 2.5), (13, 8, 12.5), "leatherette", down=None)
        m.box((7.5, 8, 12.5), (8.5, 11, 13.5), "plastic_black")
        m.box((3.5, 10, 13), (12.5, 20, 14.5), "leatherette")
        for x in (2, 13):  # armrests
            m.box((x, 10.5, 4), (x + 1, 11.5, 11), "plastic_black")
            m.box((x + 0.25, 8, 8), (x + 0.75, 10.5, 9), "plastic_black", up=None, down=None)
        return m
    return build


def tv_stand(on):
    def screen(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, SILVER)
        cv.bevel(0, 0, cv.w, cv.h, rgb("dfe2e6"), rgb("80848a"))
        x0, y0, x1, y1 = 3, 2, cv.w - 3, cv.h - 5
        if on:  # a talk show: a studio in blue, two figures, the channel logo in the corner
            cv.vgrad(x0, y0, x1, y1, [rgb("1a3a7a"), rgb("2a5aa8")])
            cv.rect(x0 + 4, y0 + 6, x0 + 7, y1, rgb("d8a07a")); cv.rect(x0 + 4, y0 + 9, x0 + 7, y1, rgb("2a2a3a"))
            cv.rect(x1 - 8, y0 + 7, x1 - 5, y1, rgb("d8a07a")); cv.rect(x1 - 8, y0 + 10, x1 - 5, y1, rgb("8a2a2a"))
            cv.rect(x1 - 4, y0 + 1, x1 - 1, y0 + 3, rgb("f0f0f0"))
        else:
            cv.rect(x0, y0, x1, y1, rgb("262a2c"))
            cv.set(x0 + 1, y0 + 1, rgb("5a6060"))
        cv.hline(cv.w // 2 - 3, cv.w // 2 + 3, cv.h - 3, rgb("6a6e74"))
        cv.set(cv.w - 4, cv.h - 3, rgb("e04020") if on else rgb("602018"))

    def dvd(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("2a2c30"))
        cv.hline(2, cv.w // 2, 2, rgb("4a4c50"))
        cv.rect(cv.w - 9, 1, cv.w - 3, 3, rgb("1a2a3a"))
        if on:
            cv.hline(cv.w - 8, cv.w - 4, 2, rgb("4ac0f0"))

    def build():
        m = Model()
        # a wenge-and-glass TV stand, a DVD player on its shelf, a big silver CRT on top
        m.box((0, 0, 4), (16, 1, 15), "wenge", down=None)
        m.box((0, 1, 4), (1, 8, 15), "wenge", down=None, up=None)
        m.box((15, 1, 4), (16, 8, 15), "wenge", down=None, up=None)
        m.box((1, 1, 14), (15, 8, 15), "wenge", north=None, up=None, down=None)
        m.box((0, 8, 3.5), (16, 8.75, 15), "glass", down=None)
        m.box((1, 4, 4.5), (15, 4.5, 14), "glass")
        m.box((3, 4.5, 6), (13, 6, 13), "plastic_black", north=Pic("dvd" + str(int(on)), dvd))
        m.box((1, 8.75, 4), (15, 20, 11), "silver", north=Pic("screen" + str(int(on)), screen))
        m.box((3, 9.5, 11), (13, 18.5, 15), "plastic_black", north=None)
        return m
    return build


# ---------------------------------------------------------------- the kitchen

def fridge_2k():
    def doors(cv, W, H):
        t = ramp(SILVER, 6, 0.7, 1.12)
        cv.rect(0, 0, cv.w, cv.h, t[3])
        for y in range(0, cv.h, 3):
            cv.hline(0, cv.w, y, t[4])
        gap = cv.h * 13 // 32
        cv.hline(0, cv.w, gap, rgb("2a2a2c"))
        cv.outline(0, 0, cv.w, cv.h, t[1])
        cv.rect(4, 4, 12, 7, rgb("1e2a2e"))       # the freezer's little display
        cv.hline(5, 9, 5, rgb("4ac0f0"))

    def build():
        m = Model()
        # a two-door silver fridge, freezer on top, 1.85 m; recessed handles down the side
        m.box((1.5, 0, 3.5), (14.5, 1, 15), "plastic_black", down=None, up=None)
        m.box((1, 1, 3), (15, 30, 15.5), "silver", down=None, north=Pic("doors", doors))
        for y0, y1 in ((19, 28), (4, 16)):
            m.box((12, y0, 2.5), (13, y1, 3), "plastic_black", south=None)
        return m
    return build


def gloss_doors(cv, W, H, drawer=False):
    t = ramp(GLOSS, 6, 0.65, 1.2)
    cv.rect(0, 0, cv.w, cv.h, t[3])
    cv.outline(0, 0, cv.w, cv.h, t[0])
    cv.vline(cv.w // 2, 0, cv.h, t[0])
    for y in range(1, cv.h - 1):  # the gloss: a light streak across each door
        for x in range(1, cv.w - 1):
            if (x + y) % 17 in (0, 1):
                cv.set(x, y, t[5])
    if drawer:
        cv.hline(1, cv.w - 1, cv.h // 3, t[0])
    for x in (cv.w // 2 - 4, cv.w // 2 + 2):  # long brushed steel bar handles
        cv.hline(x, x + 3, 3, rgb("e4e8ec")); cv.hline(x, x + 3, 4, rgb("8c9096"))


def kitchen_2k(kind):
    def sink_top(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("d8cfbf"))
        st = ramp(rgb("b8bec4"), 5, 0.6, 1.2)
        bx0, by0, bx1, by1 = 4, 4, cv.w - 12, cv.h - 8
        cv.rect(bx0, by0, bx1, by1, st[4])
        cv.rect(bx0 + 1, by0 + 1, bx1 - 1, by1 - 1, st[2])
        cv.disc((bx0 + bx1) / 2, (by0 + by1) / 2, 1.5, st[0], ring=st[1])
        for y in range(by0, by1, 2):  # the drainer wing
            cv.hline(bx1 + 2, cv.w - 3, y, st[3])

    def build():
        m = Model()
        if kind == "wall":
            m.box((0, 6, 9), (16, 16, 16), "gloss_lime", south=None, north=Pic("doors", lambda cv, W, H: gloss_doors(cv, W, H)))
            return m
        m.box((0.5, 0, 2.5), (15.5, 1.5, 15.5), "silver", down=None, up=None)
        m.box((0, 1.5, 1.5), (16, 13, 16), "gloss_lime", down=None,
              north=Pic("doors", lambda cv, W, H: gloss_doors(cv, W, H, drawer=True)))
        m.box((0, 13, 0.5), (16, 14, 16), "worktop_2k", down=None, north=Pic("edge", lambda cv, W, H: worktop_edge(cv)),
              up=Pic("sink", sink_top) if kind == "sink" else None)
        if kind == "sink":  # a single-lever mixer
            m.box((5, 14, 13), (7, 15, 15), "chrome")
            m.box((5.5, 15, 13.5), (6.5, 19, 14.5), "chrome", up=None, down=None)
            m.box((5.5, 19, 10), (6.5, 20, 14.5), "chrome")
            m.box((5.75, 18, 10), (6.25, 19, 10.5), "chrome", up=None)
            m.box((5.75, 20, 13.5), (6.25, 21.5, 14), "chrome", down=None)
        elif kind == "plain":  # a wooden cutting board and a bread bin
            m.box((2, 14, 9), (10, 14.5, 15), "lightwood", down=None)
            m.box((10.5, 14, 8), (15, 18, 15), "plastic_white", down=None)
            m.box((10.25, 17.5, 7.5), (15.25, 18.25, 15.25), "plastic_white")
        else:  # a microwave on the worktop
            m.box((2, 14, 7), (12, 20, 15), "plastic_white", down=None, north=Pic("microwave", lambda cv, W, H: (
                cv.rect(0, 0, cv.w, cv.h, rgb("eeeeea")), cv.rect(1, 1, cv.w - 6, cv.h - 1, rgb("2a2c30")),
                cv.rect(cv.w - 5, 2, cv.w - 1, 4, rgb("1e2a2e")), cv.hline(cv.w - 4, cv.w - 2, 3, rgb("4af04a")),
                [cv.set(cv.w - 4 + (i % 2) * 2, 6 + (i // 2) * 2, rgb("9a9a96")) for i in range(6)])))
        return m
    return build


def spots(on):
    sh = "frosted_lit" if on else "frosted"

    def build():
        m = Model()
        # a chrome bar on the ceiling with three frosted glass bells: the 2000s «люстра на штанге»
        m.box((1, 15.25, 7), (15, 16, 9), "chrome")
        for x in (3, 8, 13):
            m.box((x - 0.5, 13, 7.5), (x + 0.5, 15.25, 8.5), "chrome", up=None, down=None)
            stack(m, x, 8, [(12, 13, 0.75), (11, 12, 1.25), (10, 11, 1.75), (9, 10, 2)], sh, shade=not on,
                  bottom=Pic("spot_mouth" + str(int(on)), mouth(rgb("e8e2cc"), on)), bottom_shade=not on)
        return m
    return build


def make():
    materials()
    Furn("apt_bell_button", "Кнопка дверного звонка", "Door bell push", bell_button(), mount="WALL", color="WHITE", sound="STONE",
         solid=False)
    Furn("apt_doorbell", "Дверной звонок (советский)", "Electric door bell", soviet_bell(), mount="WALL", color="IRON_GRAY",
         sound="METAL", solid=False)
    Furn("apt_doorbell_chime", "Дверной звонок «динь-дон»", "Ding-dong door chime", chime(), mount="WALL", color="WHITE",
         sound="STONE", solid=False)
    Furn("apt2_sofa_corner", "Угловой диван", "Corner sofa", corner_sofa(), seat=7.5, color="PALE_YELLOW", sound="WOOL")
    Furn("apt2_wardrobe_coupe", "Шкаф-купе", "Sliding-door wardrobe", wardrobe_coupe(), color="PALE_YELLOW", sound="WOOD")
    Furn("apt2_pc_desk", "Компьютерный стол с компьютером", "Computer desk with a PC", pc_desk, toggle=True, light=4, color="PALE_YELLOW",
         sound="WOOD")
    Furn("apt2_office_chair", "Компьютерное кресло", "Office chair", office_chair(), seat=8, color="BLACK", sound="WOOL")
    Furn("apt2_tv_stand", "Тумба с телевизором и DVD", "TV stand with a TV and a DVD player", tv_stand, toggle=True, light=4,
         color="BROWN", sound="WOOD")
    Furn("apt2_fridge", "Холодильник двухкамерный (серебристый)", "Silver two-door fridge", fridge_2k(), color="IRON_GRAY",
         sound="METAL")
    Furn("apt2_kitchen_base", "Кухонная тумба с микроволновкой", "Kitchen base with a microwave", kitchen_2k("base"), color="LIME",
         sound="WOOD")
    Furn("apt2_kitchen_plain", "Кухонная тумба с хлебницей", "Kitchen base with a bread bin", kitchen_2k("plain"), color="LIME",
         sound="WOOD")
    Furn("apt2_kitchen_sink", "Кухонная мойка (2000-е)", "Kitchen sink (2000s)", kitchen_2k("sink"), color="LIME", sound="WOOD")
    Furn("apt2_kitchen_wall", "Навесной шкафчик (2000-е)", "Wall cupboard (2000s)", kitchen_2k("wall"), mount="WALL", color="LIME",
         sound="WOOD")
    Furn("apt2_lamp_spots", "Люстра на штанге", "Chrome bar ceiling light", spots, mount="CEILING", toggle=True, light=14,
         color="WHITE", sound="GLASS", solid=False)
