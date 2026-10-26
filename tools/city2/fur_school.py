"""The new school's furniture (civic.py, 65-426/1 after the Moscow references in /mnt/project-files/cities/public-refs):
the assembly hall's stage curtain and folding theatre seats and lectern, the canteen's serving line and its kitchen's
range and boiling pan, the cloakroom's mesh sections, the library's double-sided stacks, reading tables with the green lamp
and the issue desk, the chemistry room's bench and fume cupboard, the gym's vaulting horse and springboard, the
recreation's drinking fountain, the corridors' stands, portraits, bell and trophy cabinet, the classroom's glazed cabinet.
Same conventions as fur_models.py (north space, front at low z, the back against the wall at z 16, x to the viewer's left)."""
import random

import fur_paint as P
from fur_paint import rgb, shade, ramp
from furniture import Furn, Model, Pic, mat
from fur_models import WALNUT, OAK, panel, books

VELVET = rgb("7a1e26")
STEEL = rgb("b4bac0")
CLOAK_WHITE = rgb("e8ebe8")
LAB_BLACK = rgb("2a2c2e")
LEATHER = rgb("5a3420")


def velvet_tex():
    """Heavy stage velvet hanging in folds: dark troughs and lit crests across, a soft pile."""
    cv = P.Canvas(32, 32, VELVET)
    t = ramp(VELVET, 6, 0.5, 1.35)
    r = random.Random("velvet")
    for x in range(32):
        k = (x % 8) / 8
        c = t[1] if k < 0.2 else t[2] if k < 0.4 else t[4] if k < 0.6 else t[3] if k < 0.8 else t[2]
        for y in range(32):
            cv.set(x, y, c if r.random() > 0.08 else t[max(0, t.index(c) - 1)])
    return cv


def cloak_mesh():
    """The welded wire of the cloakroom's partitions: white wire on a 4-pixel grid, see-through between."""
    cv = P.Canvas(32, 32, (0, 0, 0, 0))
    for k in range(0, 32, 4):
        cv.hline(0, 32, k, CLOAK_WHITE)
        cv.vline(k, 0, 32, shade(CLOAK_WHITE, 0.85))
    return cv


def textures():
    mat("cloak_mesh", cloak_mesh())
    mat("velvet", velvet_tex())
    mat("velvet_flat", P.fabric(32, 32, VELVET, "velf", weave=1, nap=True))
    mat("fringe_gold", P.fabric(32, 32, rgb("c8a040"), "fringe", weave=1, nap=False))
    mat("seat_velvet", P.fabric(32, 32, rgb("8a2a2e"), "seatv", weave=2, nap=True))
    mat("iron_grey", P.metal(32, 32, rgb("4a4e52"), vertical=True))
    mat("steel_counter", P.metal(32, 32, STEEL, vertical=False))
    mat("steel_counter_v", P.metal(32, 32, STEEL, vertical=True))
    mat("cloak_white", P.enamel(32, 32, CLOAK_WHITE, "cloakw", chips=2))
    mat("lab_black", P.enamel(32, 32, LAB_BLACK, "labb", chips=1))
    mat("lab_white", P.enamel(32, 32, rgb("e6e4dc"), "labw", chips=2))
    mat("leather_brown", P.fabric(32, 32, LEATHER, "leatherb", weave=1, nap=False))
    mat("library_wood", P.wood(32, 32, rgb("a87444"), "libw", vertical=True, polish=0.6))
    mat("library_wood_h", P.wood(32, 32, rgb("a87444"), "libwh", vertical=False, polish=0.6))
    mat("food_kasha", P.fabric(32, 32, rgb("e8d0a0"), "kasha", weave=1, nap=False))
    mat("food_soup", P.enamel(32, 32, rgb("c86a3a"), "soup", chips=0))
    mat("bread", P.fabric(32, 32, rgb("c89050"), "bread", weave=1, nap=False))
    mat("cutlet", P.fabric(32, 32, rgb("7a4a2a"), "cutlet", weave=1, nap=False))
    mat("kompot", P.glass(32, 32, rgb("b86a5a")))
    mat("flask", P.glass(32, 32, rgb("c8e0e8")))
    mat("cup_gold", P.metal(32, 32, rgb("d8b048"), vertical=True))
    mat("pennant_red", P.fabric(32, 32, rgb("c42a2a"), "pennant", weave=1, nap=False))
    mat("geranium", P.fabric(32, 32, rgb("d83a3a"), "geran", weave=1, nap=False))
    mat("violet", P.fabric(32, 32, rgb("8a4ab0"), "violet", weave=1, nap=False))
    mat("aluminium_pot", P.metal(32, 32, rgb("c8ccd0"), vertical=True))
    mat("cutting_board", P.wood(32, 32, rgb("c8a070"), "cboard", vertical=False, polish=0.2, knots=False))
    mat("potato", P.fabric(32, 32, rgb("b89058"), "potato", weave=1, nap=False))
    mat("sack", P.fabric(32, 32, rgb("a89068"), "sack", weave=2, nap=False))
    mat("dough", P.fabric(32, 32, rgb("ece0c0"), "dough", weave=1, nap=False))
    mat("cabbage", P.fabric(32, 32, rgb("9ac070"), "cabbage", weave=1, nap=False))
    mat("crate_wood", P.wood(32, 32, rgb("b89868"), "crate", vertical=False, polish=0.0, spacing=8))
    mat("kitchen_green", P.enamel(32, 32, rgb("6a9a80"), "kgreen", chips=2))


# ---------------------------------------------------------------- the assembly hall

def stage_curtain(top):
    """One block of the stage's curtain, three blocks tall: velvet in deep folds hung from a rail; the top piece carries the
    pelmet (ламбрекен) with its gold fringe. Two stacked make the portal of a school stage."""
    def build():
        m = Model()
        for k in range(4):                         # four folds, alternately forward and back
            z0 = 9 if k % 2 else 11
            m.box((k * 4, 0, z0), (k * 4 + 4, 47 if top else 48, z0 + 3), "velvet", up=None, down=None)
        if top:
            m.box((0, 36, 7), (16, 48, 9), "velvet_flat", south=None)
            m.box((0, 34, 6.75), (16, 36, 9), "fringe_gold")
            m.box((0, 47, 9), (16, 48, 16), "iron_grey")
        return m
    return build


def theatre_seat():
    """A folding seat of the assembly hall: a velvet seat and back on a cast-iron side with a wooden armrest."""
    m = Model()
    m.box((0, 0, 4), (1.5, 11, 14), "iron_grey")                          # the sides (shared with the next seat)
    m.box((14.5, 0, 4), (16, 11, 14), "iron_grey")
    m.box((0, 11, 3), (1.5, 12, 13), "oak")                               # the armrests
    m.box((14.5, 11, 3), (16, 12, 13), "oak")
    m.box((1.5, 6, 3), (14.5, 8, 12), "seat_velvet")                       # the seat, let down
    m.box((1.5, 4, 11.5), (14.5, 6, 12), "plywood")
    m.box((1.5, 8, 12), (14.5, 21, 14.5), "seat_velvet", south="plywood")   # the back
    m.box((2, 21, 12.25), (14, 22, 14.25), "oak")
    return m


def lectern():
    """The lectern (трибуна) on the stage: a panelled box narrowing up to a sloped desk, the school's emblem on the front,
    a microphone on its goose neck."""
    def front(cv, W, H):
        panel(cv, 0, 0, cv.w, cv.h, WALNUT, "trib", vertical=True)
        cx = cv.w // 2
        cv.disc(cx, cv.h * 0.35, cv.w * 0.22, rgb("c8a040"))       # a round emblem: the book and the star
        cv.disc(cx, cv.h * 0.35, cv.w * 0.18, rgb("a82820"))
        cv.rect(cx - 4, int(cv.h * 0.35) - 1, cx + 4, int(cv.h * 0.35) + 3, rgb("f0ece0"))
        cv.vline(cx, int(cv.h * 0.35) - 1, int(cv.h * 0.35) + 3, rgb("8a8a86"))

    m = Model()
    m.box((2, 0, 3), (14, 20, 13), "walnut", north=Pic("lectern_front", front), down=None)
    m.box((1, 20, 2), (15, 21.5, 14), "walnut", rot={"origin": [8, 20, 8], "axis": "x", "angle": 22.5})
    m.box((1.5, 0, 2.5), (14.5, 1, 13.5), "walnut")
    m.box((11, 21, 10), (12, 26, 11), "black")                             # the microphone
    m.box((10.5, 25, 7.5), (12.5, 27, 9.5), "black")
    m.box((4, 22, 6), (9, 22.5, 10), "paper")
    return m


# ---------------------------------------------------------------- the canteen and its kitchen

def serving_line(kind):
    """One block of the serving line (линия раздачи): a stainless counter with the tray slide of three tubes in front,
    a glass shelf over it; on it the day's dishes - porridge and soup in steel pans, or glasses of kompot and bread."""
    def build():
        m = Model()
        m.box((0, 0, 5), (16, 14, 16), "steel_counter_v", up=None)
        m.box((0, 14, 4), (16, 15, 16), "steel_counter")
        for y in (11, 12.5, 14):                                           # the tray slide
            m.box((0, y, 0.5), (16, y + 0.75, 1.25), "chrome_h")
        for x in (0.5, 15):
            m.box((x, 0, 1), (x + 0.75, 14, 1.75), "chrome")
            m.box((x, 11, 1.75), (x + 0.75, 11.75, 5), "chrome")
        m.box((0, 23, 7), (16, 23.5, 13), "glass_shelf")                    # the sneeze-glass shelf on two posts
        for x in (1, 14.5):
            m.box((x, 15, 12), (x + 0.5, 23, 12.5), "chrome")
        if kind == "pans":
            for x0, food in ((1, "food_kasha"), (8.5, "food_soup")):
                m.box((x0, 15, 6), (x0 + 6.5, 19, 12), "steel_sink", up=None)
                m.box((x0 + 0.5, 18, 6.5), (x0 + 6, 18.5, 11.5), food, faces=("up",))
            m.box((3, 23.5, 8), (7, 24, 12), "plate")
            m.box((4, 24, 9), (6, 25, 11), "cutlet")
            m.box((9, 23.5, 8), (13, 24, 12), "plate")
            m.box((9.5, 24, 8.5), (12.5, 25, 11.5), "food_kasha")
        else:
            for k in range(6):                                             # kompot in faceted glasses
                x, z = 1.5 + (k % 3) * 4.5, 6 + (k // 3) * 3.5
                m.box((x, 15, z), (x + 2, 18, z + 2), "kompot")
            m.box((2, 23.5, 8), (14, 24, 12), "plate")
            for x in (3, 6.5, 10):
                m.box((x, 24, 8.5), (x + 3, 25.5, 11.5), "bread")
        return m
    return build


def kitchen_range():
    """The kitchen's electric range (плита электрическая промышленная ЭП): a steel body with the oven door and its
    knobs, black square hot plates on top, a big stock pot on one."""
    def door(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("c0c6cc"))
        cv.outline(2, 8, cv.w - 2, cv.h - 3, rgb("6a7076"))
        cv.rect(4, 3, cv.w - 4, 5, rgb("2a2c2e"))
        for k in range(4):
            cv.disc(5 + k * (cv.w - 10) / 3, 4, 1.5, rgb("1a1a1a"))
        cv.hline(6, cv.w - 6, 11, rgb("e8ecf0"))

    m = Model()
    m.box((0, 0, 1), (16, 13, 16), "steel_counter_v", north=Pic("range_door", door))
    m.box((0, 13, 1), (16, 14, 16), "black")
    for x, z in ((1, 2), (8.5, 2), (1, 9), (8.5, 9)):
        m.box((x, 14, z), (x + 6.5, 14.5, z + 6), "iron_black")
    m.box((1.5, 14.5, 2.5), (7, 23, 7.5), "steel_sink", up="water")       # the stock pot
    m.box((1, 21, 4), (1.5, 22, 6), "steel_sink")
    m.box((7, 21, 4), (7.5, 22, 6), "steel_sink")
    m.box((9, 14.5, 9.5), (14.5, 16, 14.5), "iron_black")                  # a griddle pan
    return m


def boiling_pan():
    """The boiling pan (котёл пищеварочный КПЭ): a tall steel drum with a lid on a hinge, a pressure gauge, a tap."""
    m = Model()
    m.box((1.5, 0, 1.5), (14.5, 18, 14.5), "steel_counter_v", up=None)
    m.box((1, 18, 1), (15, 19.5, 15), "steel_counter")
    m.box((6, 19.5, 6), (10, 20.5, 10), "chrome")
    m.box((3, 10, 0.5), (6, 13, 1.5), "dial_dark")
    m.box((10, 3, 0), (12, 5, 1.5), "chrome")
    return m


def prep_table(kind):
    """The kitchen's steel production table (стол производственный СП): a steel top with an upstand at the back, a shelf
    below with aluminium pots and basins; on top a cutting board with a loaf, the meat grinder, or bowls of cabbage."""
    def build():
        m = Model()
        m.box((0, 14, 0), (16, 15, 16), "steel_counter")
        m.box((0, 15, 15), (16, 18, 16), "steel_counter_v")
        for x, z in ((0.5, 0.5), (14, 0.5), (0.5, 13.5), (14, 13.5)):
            m.box((x, 0, z), (x + 1.5, 14, z + 1.5), "steel_counter_v")
        m.box((0.25, 3, 0.25), (15.75, 3.5, 15.25), "steel_counter")
        m.box((2, 3.5, 3), (8, 9, 9), "aluminium_pot", up="water")          # a stock pot and a basin on the shelf
        m.box((9, 3.5, 4), (14.5, 6, 12), "aluminium_pot", up="steel_sink")
        if kind == "board":
            m.box((2, 15, 2), (12, 16, 10), "cutting_board")
            m.box((3, 16, 3), (9, 19, 6), "bread")
            m.box((9.5, 16, 7), (12.5, 16.5, 7.5), "chrome")                  # the knife
            m.box((12.5, 16, 6.5), (15, 17, 8), "black")
        elif kind == "grinder":                                               # мясорубка МИМ: a motor and the auger
            m.box((3, 15, 5), (10, 21, 12), "kitchen_green")
            m.box((10, 17, 7), (15, 20, 10), "chrome")
            m.box((11, 20, 7.5), (14, 24, 9.5), "chrome")
            m.box((0.5, 15, 0.5), (5, 18, 4.5), "aluminium_pot", up="cutlet")
        else:
            m.box((1.5, 15, 2), (7.5, 18, 8), "aluminium_pot", up="cabbage")
            m.box((8.5, 15, 3), (14.5, 17, 9), "aluminium_pot", up="potato")
            m.box((3, 15, 10), (13, 15.5, 14), "cutting_board")
        return m
    return build


def kitchen_rack():
    """A steel kitchen rack (стеллаж кухонный), two blocks high: four slatted shelves of stock pots, frying pans, stacks of
    plates and the aluminium mugs."""
    m = Model()
    for x, z in ((0.5, 1), (14, 1), (0.5, 13.5), (14, 13.5)):
        m.box((x, 0, z), (x + 1.5, 30, z + 1.5), "steel_counter_v")
    for y in (2, 10, 18, 26):
        m.box((0.25, y, 0.75), (15.75, y + 0.5, 15.25), "steel_counter")
    m.box((1.5, 2.5, 2), (8, 9, 8.5), "aluminium_pot", up="steel_sink")
    m.box((9, 2.5, 3), (14.5, 7, 13), "aluminium_pot", up="steel_sink")
    m.box((2, 10.5, 3), (7, 13.5, 13), "plate")                                 # plates in stacks
    m.box((8.5, 10.5, 3), (13.5, 14, 13), "plate")
    m.box((1.5, 18.5, 2), (14.5, 19.5, 14), "iron_black")                       # frying pans, one on another
    m.box((2, 19.5, 2.5), (14, 20.5, 13.5), "iron_black")
    m.box((14, 19.5, 7), (15.5, 20, 9), "iron_black")
    m.box((2, 26.5, 3), (6, 30, 7), "aluminium_pot", up="steel_sink")
    m.box((7, 26.5, 3), (11, 30, 7), "aluminium_pot", up="steel_sink")
    m.box((4, 26.5, 9), (12, 28.5, 14), "cup_enamel")
    return m


def oven_cabinet():
    """The roasting oven (шкаф жарочный ШЖЭ-3): three steel sections one above another, each with its door, window,
    handle and a dial, on legs; cutlets are baked here on sheets."""
    def doors(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("c0c6cc"))
        sh = cv.h // 3
        for k in range(3):
            y0 = k * sh
            cv.bevel(1, y0 + 1, cv.w - 1, y0 + sh - 1, rgb("dfe4e8"), rgb("7a8086"))
            cv.rect(6, y0 + 4, cv.w - 10, y0 + sh - 5, rgb("2a2622"))
            cv.rect(7, y0 + 5, cv.w - 11, y0 + 7, rgb("6a4a2a"))
            cv.hline(5, cv.w - 9, y0 + 2, rgb("1a1a1a"))
            cv.disc(cv.w - 5, y0 + sh // 2, 2, rgb("1a1a1a"), ring=rgb("e8ecf0"))
            cv.set(cv.w - 5, y0 + sh // 2 - 1, rgb("e83a2a"))

    m = Model()
    m.box((0.5, 3, 1), (15.5, 30, 16), "steel_counter_v", north=Pic("oven_doors", doors))
    for x in (1, 13.5):
        m.box((x, 0, 2), (x + 1.5, 3, 3.5), "black")
        m.box((x, 0, 13), (x + 1.5, 3, 14.5), "black")
    for y in (9, 18, 27):
        m.box((3, y, 0), (11, y + 1, 1), "chrome")
    return m


def wash_bath():
    """The washing bath (ванна моечная двухсекционная): two deep steel tubs side by side on legs, a mixer tap with a long
    spout over them, a draining shelf below."""
    m = Model()
    for x0 in (0.5, 8):                       # each tub solid to its water, the rim standing round it
        m.box((x0, 8, 1), (x0 + 7.5, 12.5, 14), "steel_sink", up="water")
        m.box((x0, 12.5, 1), (x0 + 7.5, 14, 2), "steel_sink", south="steel_counter_v")
        m.box((x0, 12.5, 13), (x0 + 7.5, 14, 14), "steel_sink", north="steel_counter_v")
        m.box((x0, 12.5, 2), (x0 + 1, 14, 13), "steel_sink", east="steel_counter_v")
        m.box((x0 + 6.5, 12.5, 2), (x0 + 7.5, 14, 13), "steel_sink", west="steel_counter_v")
    for x, z in ((0.5, 1), (14, 1), (0.5, 12.5), (14, 12.5)):
        m.box((x, 0, z), (x + 1.5, 8, z + 1.5), "steel_counter_v")
    m.box((0.25, 2, 0.75), (15.75, 2.5, 14.25), "steel_counter")
    m.box((0.5, 14, 14), (15.5, 20, 16), "steel_counter_v")
    m.box((7, 20, 14.5), (9, 23, 15.5), "chrome")
    m.box((7.5, 22, 7), (8.5, 23, 14.5), "chrome")
    m.box((7.5, 20, 7), (8.5, 22, 8), "chrome")
    m.box((3, 2.5, 3), (7, 5, 10), "aluminium_pot", up="steel_sink")
    return m


def fridge_cabinet():
    """The kitchen's refrigerated cabinet (шкаф холодильный ШХ-0,8): a tall white enamel box with two doors, the
    thermometer and the maker's badge, the compressor grille at the top."""
    def front(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("e8e8e2"))
        cv.rect(0, 0, cv.w, 6, rgb("8a8e92"))
        for x in range(2, cv.w - 2, 3):
            cv.vline(x, 1, 5, rgb("4a4e52"))
        cv.vline(cv.w // 2, 6, cv.h, rgb("9a9a94"))
        cv.bevel(1, 7, cv.w // 2 - 1, cv.h - 2, rgb("fafaf6"), rgb("b0b0aa"))
        cv.bevel(cv.w // 2 + 1, 7, cv.w - 1, cv.h - 2, rgb("fafaf6"), rgb("b0b0aa"))
        for x in (cv.w // 2 - 3, cv.w // 2 + 2):
            cv.rect(x, cv.h // 2 - 3, x + 1, cv.h // 2 + 4, rgb("6a6e72"))
        cv.rect(4, 10, 7, 18, rgb("f0f0f0"))
        cv.vline(5, 12, 17, rgb("d83a3a"))
        cv.rect(cv.w - 10, 9, cv.w - 4, 11, rgb("c8a040"))

    m = Model()
    m.box((0.5, 0, 1), (15.5, 30, 16), "lab_white", north=Pic("shx_front", front))
    return m


def dough_mixer():
    """The dough mixer (тестомес ТММ-1М): a green cast column with its arm over a steel bowl on a trolley, dough in it."""
    m = Model()
    m.box((1, 0, 9), (6, 22, 15), "kitchen_green")
    m.box((1, 18, 3), (6, 22, 9), "kitchen_green")
    m.box((3, 10, 5), (4, 18, 6), "chrome")
    m.box((6.5, 2, 2), (15, 11, 12), "steel_sink", up=None)
    m.box((7, 2.5, 2.5), (14.5, 9, 11.5), "dough", north=None, south=None, east=None, west=None, down=None)
    for x in (7, 14):
        m.box((x, 0, 3), (x + 1, 2, 4), "black")
        m.box((x, 0, 10), (x + 1, 2, 11), "black")
    return m


def potato_peeler():
    """The potato peeler (картофелечистка МОК-150): a steel drum on a stand with its lid and loading chute, a basin under
    the outlet, and a sack of potatoes by it."""
    m = Model()
    m.box((2, 0, 4), (11, 4, 13), "iron_grey")
    m.box((2.5, 4, 4.5), (10.5, 14, 12.5), "steel_counter_v")
    m.box((3, 14, 5), (10, 15, 12), "aluminium_pot")
    m.box((4.5, 15, 6.5), (8.5, 17, 10.5), "black")
    m.box((4, 7, 2.5), (9, 10, 4.5), "steel_sink")                 # the outlet
    m.box((3.5, 0, 0.5), (9.5, 3, 4), "aluminium_pot", up="potato")
    m.box((11.5, 0, 6), (15.5, 10, 13), "sack", up="potato")
    return m


def veg_crates():
    """Wooden crates of potatoes, beets and cabbages stacked in the kitchen's vegetable corner."""
    m = Model()
    for (x0, x1), food, y in (((0.5, 7.5), "potato", 0), ((8.5, 15.5), "cabbage", 0), ((0.5, 7.5), "potato", 7)):
        m.box((x0, y, 1), (x1, y + 6, 15), "crate_wood", up=None)
        m.box((x0 + 0.5, y + 1, 1.5), (x1 - 0.5, y + 5.5, 14.5), food, north=None, south=None, east=None, west=None, down=None)
    return m


# ---------------------------------------------------------------- the cloakroom

def cloak_section(coats):
    """One bay of the school cloakroom: a white steel frame two blocks high, a mesh between the classes' bays, a hat shelf,
    the double hooks, a bench along the bottom with shoe bags; coats on the hooks in term time."""
    def build():
        m = Model()
        for x in (0, 15):
            m.box((x, 0, 2), (x + 1, 31, 3), "cloak_white")
            m.box((x, 0, 13), (x + 1, 31, 14), "cloak_white")
            m.box((x + 0.25, 2, 3), (x + 0.75, 30, 13), "cloak_mesh", up=None, down=None, shade=False)
        for z in (2, 13):                                                  # the top rails and the hat shelf
            m.box((1, 31, z), (15, 32, z + 1), "cloak_white")
        m.box((1, 27, 3.25), (15, 27.5, 12.75), "cloak_mesh", shade=False)
        m.box((0, 23, 7.5), (16, 24, 8.5), "cloak_white")                  # the hook bar
        for x in (2, 6, 10, 14):
            m.box((x - 0.5, 20, 6), (x + 0.5, 23, 7), "chrome")
            m.box((x - 0.5, 20, 9), (x + 0.5, 23, 10), "chrome")
        m.box((1, 5, 3), (15, 6, 13), "lightwood")                          # the bench
        m.box((1, 0, 7.5), (15, 5, 8.5), "cloak_white")
        if coats:
            for x, z, c in ((1.1, 2.6, "coat_brown"), (5.3, 3.1, "coat_grey"), (9.6, 2.7, "coat_red"), (5.7, 9.3, "coat_brown"),
                            (10.4, 9.6, "coat_grey")):
                m.box((x, 9, z), (x + 3.8, 21, z + 3.8), c)
            m.box((2.1, 6, 4.1), (4.9, 9, 6.9), "canvas_blue")             # a shoe bag and a satchel
            m.box((10.2, 6, 10.1), (13.8, 10, 12.4), "leatherette")
            m.box((3, 28, 5), (8, 30, 10), "scarf")                        # a hat on the shelf
        return m
    return build


def class_sign():
    """The class's plate over its cloakroom bay or door: «5 А» on white enamel."""
    def plate(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("f0f0ea"))
        cv.outline(0, 0, cv.w, cv.h, rgb("2a4a8a"))
        P.text(cv, cv.w // 2 - P.text_width("5 А") // 2, cv.h // 2 - 3, "5 А", rgb("2a4a8a"))

    m = Model()
    m.box((3, 4, 15), (13, 10, 16), "enamel", north=Pic("class_sign", plate))
    return m


# ---------------------------------------------------------------- the library

def library_stack():
    """A free-standing double-sided bookstack two blocks high: books on both faces, the shelf mark on its end."""
    def face(seed):
        def paint(cv, W, H):
            cv.rect(0, 0, cv.w, cv.h, rgb("3a2416"))
            t = ramp(rgb("a87444"), 6, 0.6, 1.2)
            rows = 6
            hh = (cv.h - 6) // rows
            for k in range(rows):
                y0 = 2 + k * hh
                books(cv, 2, y0 + 1, cv.w - 2, y0 + hh - 1, f"{seed}{k}")
                cv.rect(0, y0 + hh - 1, cv.w, y0 + hh + 1, t[3])
            cv.rect(0, 0, 2, cv.h, t[2])
            cv.rect(cv.w - 2, 0, cv.w, cv.h, t[1])
            cv.rect(0, 0, cv.w, 2, t[4])
            cv.rect(0, cv.h - 4, cv.w, cv.h, t[1])
        return paint

    def end(cv, W, H):
        panel(cv, 0, 0, cv.w, cv.h, rgb("a87444"), "stackend", vertical=True, frame=1)
        cv.rect(cv.w // 2 - 6, 10, cv.w // 2 + 6, 18, rgb("f0ece0"))
        P.text(cv, cv.w // 2 - P.text_width("84") // 2, 11, "84", rgb("2a2a2a"))

    m = Model()
    m.box((0, 0, 2), (16, 32, 14), "library_wood", north=Pic("stack_n", face("sn")), south=Pic("stack_s", face("ss")),
          east=Pic("stack_e", end), west=Pic("stack_w", end))
    return m


def reading_table():
    """The reading-room table, two blocks long: oak on square legs, a green-shaded lamp, an open book and a newspaper."""
    m = Model()
    m.box((0, 11, 1), (32, 12.5, 15), "oak")
    m.box((0.5, 9, 1.5), (31.5, 11, 14.5), "oak", up=None)
    for x in (1, 29.5):
        for z in (2, 12.5):
            m.box((x, 0, z), (x + 1.5, 9, z + 1.5), "oak", up=None)
    m.box((14, 12.5, 6.5), (18, 13, 9.5), "brass")                         # the green lamp in the middle
    m.box((15.5, 13, 8), (16.5, 18, 9), "brass")
    m.box((12.5, 17.5, 5.5), (19.5, 19.5, 9.5), "lampshade_green", rot={"origin": [16, 18.5, 7.5], "axis": "x", "angle": -22.5})
    m.box((4, 12.5, 4), (11, 13, 9), "paper")                              # an open book
    m.box((7.25, 13, 4.5), (7.75, 13.5, 8.5), "lacquer_red")
    m.box((22, 12.5, 3), (29, 13, 11), "paper")                         # a newspaper
    return m


def issue_desk():
    """The librarian's issue desk (кафедра выдачи), two blocks: a panelled front higher than a desk, a top with the readers'
    record cards in their box, a stamp, a pile of returned books."""
    def front(cv, W, H):
        panel(cv, 0, 0, cv.w, cv.h, rgb("a87444"), "issue", vertical=True)
        for k in range(3):
            x = 2 + k * (cv.w - 4) // 3
            cv.bevel(x + 1, 3, x + (cv.w - 4) // 3 - 1, cv.h - 3, shade(rgb("a87444"), 1.15), shade(rgb("a87444"), 0.7))

    m = Model()
    m.box((0, 0, 3), (32, 17, 13), "library_wood", north=Pic("issue_front", front), down=None)
    m.box((0, 17, 2), (32, 18.5, 13.5), "library_wood_h")
    m.box((0, 11, 13), (32, 12, 16), "library_wood_h")                     # the librarian's writing shelf behind
    m.box((4, 18.5, 5), (12, 22, 10), "walnut", up="paper")               # the formulars' box
    m.box((15, 18.5, 6), (17, 20.5, 8), "black")                          # the stamp
    m.box((22, 18.5, 4), (29, 20, 10), "books_stack")
    m.box((23, 20, 5), (28, 21.5, 9), "books_stack")
    return m


# ---------------------------------------------------------------- the chemistry room

def lab_bench():
    """The demonstration bench of the chemistry room, two blocks: white cupboards, a black acid-proof top, a small sink with
    a goose-neck tap, a rack of test tubes, flasks and a spirit lamp."""
    def doors(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("e6e4dc"))
        for k in range(4):
            x = k * cv.w // 4
            cv.outline(x + 1, 2, x + cv.w // 4 - 1, cv.h - 2, rgb("9a9890"))
            cv.rect(x + cv.w // 8 - 1, 5, x + cv.w // 8 + 1, 9, rgb("6a6a6a"))

    m = Model()
    m.box((0, 0, 3), (32, 13, 15), "lab_white", north=Pic("lab_doors", doors))
    m.box((0, 13, 2), (32, 14.5, 15.5), "lab_black")
    m.box((22, 13.5, 5), (29, 14.6, 12), "steel_sink", up="black")         # the sink and its tap
    m.box((25, 14.5, 12.5), (26, 21, 13.5), "chrome")
    m.box((25, 20, 9.5), (26, 21, 12.5), "chrome")
    m.box((3, 14.5, 7), (10, 17, 9), "lightwood")                          # the test-tube rack
    for x in (3.5, 5.5, 7.5):
        m.box((x, 15, 7.5), (x + 1, 20, 8.5), "flask")
    m.box((12, 14.5, 5), (16, 19, 9), "flask")                             # flasks
    m.box((13.25, 19, 6.25), (14.75, 22, 7.75), "flask")
    m.box((17, 14.5, 9), (19.5, 17, 11.5), "glass_amber")
    m.box((17.5, 17, 9.5), (19, 18, 11), "brass")
    return m


def fume_cupboard():
    """The fume cupboard (вытяжной шкаф): a white cabinet two blocks high, the glazed sash half up, a black top inside with
    a retort on its stand, the duct going up into the ceiling."""
    m = Model()
    m.box((0, 0, 2), (16, 13, 16), "lab_white")
    m.box((0, 13, 2), (16, 14, 16), "lab_black")
    for x in (0, 15):
        m.box((x, 14, 2), (x + 1, 28, 16), "lab_white")
    m.box((1, 14, 15), (15, 28, 16), "lab_white")
    m.box((0, 28, 2), (16, 32, 16), "lab_white")
    m.box((1, 21, 2.5), (15, 28, 3), "glass", up=None, down=None)           # the sash, raised half
    m.box((1.25, 20.5, 2.25), (14.75, 21.5, 3.25), "alu")
    m.box((6, 32, 7), (10, 34, 11), "steel_counter_v")
    m.box((4, 14.5, 9), (5, 22, 10), "iron_black")                           # a retort on its stand
    m.box((3, 14, 8), (8, 14.5, 12), "iron_black")
    m.box((5, 18, 8), (10, 22, 12), "flask")
    m.box((10, 20, 9.5), (14, 21, 10.5), "flask")
    return m


# ---------------------------------------------------------------- the gym

def vaulting_horse():
    """The vaulting buck (козёл): a leather body on four splayed legs with height screws."""
    m = Model()
    m.box((3, 14, 4), (13, 19, 12), "leather_brown")
    m.box((3.5, 13, 4.5), (12.5, 14, 11.5), "lightwood")
    for x, z in ((4, 5), (11, 5), (4, 10), (11, 10)):
        m.box((x, 1, z), (x + 1.5, 13, z + 1.5), "lightwood", up=None, down=None)
        m.box((x - 0.25, 0, z - 0.25), (x + 1.75, 1, z + 1.75), "black")
    m.box((5.5, 6, 5.5), (11, 7, 6.25), "chrome")
    m.box((5.5, 6, 10.25), (11, 7, 11), "chrome")
    return m


def springboard():
    """The springboard (гимнастический мостик): a sloped wooden board on its frame, a rubber strip on the top."""
    m = Model()
    m.box((1, 0, 1), (15, 1, 12), "gym_wood_h")
    m.box((1, 1, 0), (15, 2.5, 14), "gym_wood_h", rot={"origin": [8, 1, 0], "axis": "x", "angle": -22.5})
    m.box((2, 2.5, 8), (14, 3, 13), "rubber_black", rot={"origin": [8, 1, 0], "axis": "x", "angle": -22.5})
    m.box((1, 0, 12), (15, 5, 15), "gym_wood")
    return m


# ---------------------------------------------------------------- the corridors and the recreations

def drinking_fountain():
    """The drinking fountain (питьевой фонтанчик) of a school recreation: a long white enamel trough on two brackets, three
    chrome nozzles, the tiled splash-back."""
    m = Model()
    m.box((0, 13, 3), (16, 14, 13), "enamel", up="enamel_in")              # the trough: a bottom and its rims
    m.box((0, 14, 3), (16, 16, 4), "enamel")
    m.box((0, 14, 12), (16, 16, 13), "enamel")
    m.box((0, 14, 4), (1, 16, 12), "enamel")
    m.box((15, 14, 4), (16, 16, 12), "enamel")
    m.box((1, 14.5, 4), (15, 15, 12), "water", faces=("up",))
    for x in (3, 7.5, 12):
        m.box((x, 16, 7), (x + 1.5, 18, 8.5), "chrome")
        m.box((x + 0.25, 18, 7.25), (x + 1.25, 18.5, 8.25), "chrome")
    for x in (2, 13):
        m.box((x, 5, 11), (x + 1, 13, 15), "chrome")
    m.box((7, 5, 9), (9, 13, 11), "chrome")                                # the drain pipe
    m.box((0, 13, 15), (16, 26, 16), "tile_white", faces=("north",))
    return m


def school_stand(kind):
    """A corridor stand two blocks wide: «Наша школа» with the photos of the classes, or «Расписание уроков» with the
    week's timetable on its cards."""
    def school(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("2a5a8a"))
        P.text(cv, cv.w // 2 - P.text_width("НАША ШКОЛА") // 2, 3, "НАША ШКОЛА", rgb("f0e8c8"))
        r = random.Random("stand")
        for row in range(2):
            for c in range(4):
                x, y = 4 + c * (cv.w - 6) // 4, 13 + row * (cv.h - 16) // 2
                w, h = (cv.w - 6) // 4 - 4, (cv.h - 16) // 2 - 4
                cv.rect(x, y, x + w, y + h, rgb("f0f0ea"))
                cv.rect(x + 1, y + 1, x + w - 1, y + h - 1, rgb("9aa6a0") if r.random() < 0.5 else rgb("b0a890"))
                for k in range(4):                         # a class photo: rows of tiny heads
                    cv.set(x + 2 + k * (w - 3) // 4, y + h // 2, rgb("d8b8a0"))
                    cv.set(x + 3 + k * (w - 3) // 4, y + h // 2 + 3, rgb("d8b8a0"))

    def timetable(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("e8e2c8"))
        P.text(cv, cv.w // 2 - P.text_width("РАСПИСАНИЕ") // 2, 3, "РАСПИСАНИЕ", rgb("8a2020"))
        days = ["ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ"]
        for i, d in enumerate(days):
            x = 3 + i * (cv.w - 4) // 6
            P.text(cv, x + 1, 12, d, rgb("2a4a8a"))
            for k in range(6):
                y = 20 + k * (cv.h - 22) // 6
                cv.hline(x + 1, x + (cv.w - 4) // 6 - 2, y, rgb("6a6a66"))
                cv.hline(x + 1, x + (cv.w - 4) // 6 - 5 - (k + i) % 4, y + 2, rgb("9a9a96"))
        cv.outline(0, 0, cv.w, cv.h, rgb("6a4a2c"))

    painter = {"school": school, "timetable": timetable}[kind]

    def build():
        m = Model()
        m.box((-14, 6, 15.25), (30, 28, 16), "paper", north=Pic(f"stand_{kind}", painter), faces=("north",), shade=False)
        m.box((-15, 5, 14.75), (31, 6, 16), "board_frame")
        m.box((-15, 28, 14.75), (31, 29, 16), "board_frame")
        m.box((-15, 6, 14.75), (-14, 28, 16), "board_frame", up=None, down=None)
        m.box((30, 6, 14.75), (31, 28, 16), "board_frame", up=None, down=None)
        return m
    return build


def portrait(who):
    """A portrait in a thin black frame over the literature room's blackboard: Pushkin with his sideburns, or Tolstoy with
    his beard, the name on a strip below."""
    def paint(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("e8e0c8"))
        cx, cy = cv.w / 2, cv.h * 0.42
        cv.rect(int(cx - 9), int(cy + 7), int(cx + 9), cv.h - 7, rgb("2a2a30"))      # the coat
        cv.rect(int(cx - 2), int(cy + 6), int(cx + 2), int(cy + 10), rgb("f0f0ea"))  # the collar
        cv.disc(cx, cy, 6, rgb("d8b098"))                                            # the face
        hair = rgb("3a2a1e") if who == "pushkin" else rgb("8a8a86")
        if who == "pushkin":
            for dx in range(-7, 8):
                for dy in range(-9, -3):
                    if dx * dx + (dy + 3) ** 2 < 50 and (dx + dy) % 3:
                        cv.set(int(cx + dx), int(cy + dy), hair)
            for dy in range(-2, 5):                                                   # the sideburns
                cv.set(int(cx - 6), int(cy + dy), hair)
                cv.set(int(cx + 5), int(cy + dy), hair)
        else:
            cv.rect(int(cx - 5), int(cy - 7), int(cx + 5), int(cy - 4), hair)
            for dy in range(1, 12):                                                   # the beard
                w = max(1, 6 - dy // 3)
                cv.hline(int(cx - w), int(cx + w), int(cy + dy), hair)
        cv.set(int(cx - 2), int(cy - 1), rgb("2a2a2a"))
        cv.set(int(cx + 2), int(cy - 1), rgb("2a2a2a"))
        name = "ПУШКИН" if who == "pushkin" else "ТОЛСТОЙ"
        cv.rect(0, cv.h - 7, cv.w, cv.h, rgb("f0ece0"))
        P.text(cv, cv.w // 2 - P.text_width(name) // 2, cv.h - 6, name, rgb("2a2a2a"))
        cv.outline(0, 0, cv.w, cv.h, rgb("1a1a1a"))

    def build():
        m = Model()
        m.box((0.5, 0, 15.25), (15.5, 20, 16), "black", north=Pic(f"portrait_{who}", paint))
        return m
    return build


def school_bell():
    """The electric bell (звонок) high on a corridor wall: a red dome, the striker, the cable in its conduit."""
    m = Model()
    m.box((5, 6, 15), (11, 12, 16), "lacquer_red")
    m.box((5.5, 6.5, 13), (10.5, 11.5, 15), "red_paint")
    m.box((7, 7.5, 12), (9, 10.5, 13), "red_paint")
    m.box((7.5, 4, 12.5), (8.5, 6, 13.5), "chrome")
    m.box((7.75, 12, 15.25), (8.25, 16, 15.75), "black")
    return m


def trophy_cabinet():
    """The trophy cabinet of the lobby, two blocks high: walnut with glass doors, the sports cups, pennants and diplomas."""
    m = Model()
    m.box((0, 0, 4), (16, 10, 16), "walnut")
    m.box((0, 30, 4), (16, 32, 16), "walnut")
    for x in (0, 15):
        m.box((x, 10, 4), (x + 1, 30, 16), "walnut")
    m.box((1, 10, 15), (15, 30, 16), "felt_green", faces=("north",))
    for y in (17, 24):
        m.box((1, y, 5), (15, y + 0.5, 15), "glass_shelf")
    m.box((1, 10, 4.5), (15, 30, 5), "glass", up=None, down=None)
    m.box((7.75, 10, 4.25), (8.25, 30, 5.25), "walnut")
    for x, y, h in ((2.5, 10, 6), (7, 10, 4), (11, 10, 5), (3.5, 17.5, 5), (10, 17.5, 4)):   # the cups
        m.box((x, y, 9), (x + 2.5, y + 1, 11.5), "cup_gold")
        m.box((x + 0.5, y + 1, 9.5), (x + 2, y + h - 2, 11), "cup_gold")
        m.box((x - 0.25, y + h - 2, 8.75), (x + 2.75, y + h, 11.75), "cup_gold")
    for x in (3, 9):                                                       # pennants on the top shelf
        m.box((x, 24.5, 13), (x + 4, 29, 13.5), "pennant_red")
    m.box((6, 25, 14), (11, 29, 14.5), "paper")
    return m


def class_cabinet():
    """The classroom's glazed cabinet two blocks high (шкаф для пособий): a cupboard below, glass above with the rolled maps,
    a globe, the teaching folders and a plaster bust."""
    def low(cv, W, H):
        panel(cv, 0, 0, cv.w // 2, cv.h, rgb("c89a5a"), "ccl", vertical=True, frame=1)
        panel(cv, cv.w // 2, 0, cv.w, cv.h, rgb("c89a5a"), "ccr", vertical=True, frame=1)

    def shelves(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("4a3020"))
        books(cv, 2, 2, cv.w - 2, cv.h // 2 - 1, "cc1")
        books(cv, 2, cv.h // 2 + 1, cv.w - 2, cv.h - 2, "cc2")
        cv.hline(0, cv.w, cv.h // 2, rgb("c89a5a"))

    m = Model()
    m.box((0, 0, 5), (16, 12, 16), "lightwood", north=Pic("cab_low", low))
    m.box((0, 12, 5), (16, 13, 16), "lightwood")
    m.box((0, 31, 5), (16, 32, 16), "lightwood")
    for x in (0, 15):
        m.box((x, 13, 5), (x + 1, 31, 16), "lightwood")
    m.box((1, 13, 15), (15, 31, 16), "lightwood", north=Pic("cab_books", shelves), faces=("north",))
    m.box((1, 22, 6), (15, 22.5, 15), "glass_shelf")
    m.box((2, 13, 7), (4, 21, 9), "paper")                                # rolled maps
    m.box((4.5, 13, 8), (6, 20, 9.5), "paper")
    m.box((9, 13, 8), (10, 14.5, 9), "brass")                             # a globe
    m.box((7.5, 14.5, 6.5), (11.5, 18.5, 10.5), "globe")
    m.box((10, 22.5, 9), (13, 26, 12), "plate_rim")                       # a plaster bust
    m.box((10.75, 26, 9.75), (12.25, 28, 11.25), "plate_rim")
    m.box((1, 13, 5.5), (15, 31, 6), "glass", up=None, down=None)
    m.box((7.75, 13, 5.25), (8.25, 31, 6.25), "lightwood")
    return m


# ---------------------------------------------------------------- what fills a school: the windows, the walls, the floor

def sill_flowers(kind):
    """The window's cast-iron radiator under the sill, and on the sill the pots of the class's flowers: a red geranium and
    an aloe, or a tradescantia trailing over and a violet. Stands in the room's cell in front of the window."""
    def build():
        m = Model()
        for i in range(10):                        # the MS-140 radiator, as fur_models.radiator
            x = 0.3 + i * 1.55
            m.box((x, 1.5, 12.5), (x + 1.2, 8, 15.5), "radiator", down=None)
        m.box((0, 2.5, 13.5), (16, 3.5, 14.5), "radiator")
        m.box((0, 6.5, 13.5), (16, 7.5, 14.5), "radiator")
        pots = ((1.5, "geranium"), (6.5, "aloe"), (11.5, "geranium")) if kind == "a" else ((2, "trad"), (9, "violet"))
        for x, plant in pots:
            m.box((x, 13, 11.5), (x + 3.5, 16, 15), "pot_clay", down=None)
            m.box((x + 0.5, 15.75, 12), (x + 3, 15.75, 14.5), "soil", faces=("up",))
            if plant == "geranium":
                m.box((x - 0.5, 16, 11), (x + 4, 20, 15.5), "leaf")
                for dx, dz in ((0, 11.5), (2, 13), (3, 11)):
                    m.box((x + dx, 20, dz), (x + dx + 1.5, 21.5, dz + 1.5), "geranium")
            elif plant == "aloe":
                m.box((x + 1, 16, 12.5), (x + 2.5, 22, 14), "aloe")
                m.box((x, 16, 13), (x + 1, 20, 13.75), "aloe", rot={"origin": [x + 1, 16, 13], "axis": "z", "angle": 22.5})
                m.box((x + 2.5, 16, 13), (x + 3.5, 20, 13.75), "aloe", rot={"origin": [x + 2.5, 16, 13], "axis": "z", "angle": -22.5})
            elif plant == "trad":                  # the tradescantia hangs down over the sill's edge
                m.box((x - 1, 16, 10.5), (x + 4.5, 18, 15.5), "leaf")
                m.box((x - 0.5, 9, 10), (x + 1, 16, 11), "leaf", faces=("north", "east", "west"))
                m.box((x + 2.5, 11, 10), (x + 4, 16, 11), "leaf", faces=("north", "east", "west"))
            else:
                m.box((x - 0.5, 16, 11.5), (x + 4, 17.5, 15), "leaf_dark")
                m.box((x + 0.5, 17.5, 12.5), (x + 3, 18.5, 14), "violet")
        return m
    return build


def fire_cabinet():
    """The fire hose cabinet (пожарный кран): a red steel box on the wall with «ПК» and the hose reel behind the glass, the
    red extinguisher beside it on its bracket."""
    def door(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("c42a22"))
        cv.outline(0, 0, cv.w, cv.h, rgb("8a1a14"))
        cv.rect(3, 3, cv.w - 3, cv.h // 2, rgb("e8ecee"))
        cv.disc(cv.w / 2, cv.h / 4 + 1, cv.h / 6, rgb("c8b080"), ring=rgb("6a5a40"))
        P.text(cv, cv.w // 2 - P.text_width("ПК") // 2, cv.h // 2 + 3, "ПК", rgb("f4f0e8"))

    m = Model()
    m.box((1, 4, 12), (11, 20, 16), "red_paint", north=Pic("fire_door", door))
    m.box((12, 0, 13), (15, 9, 16), "red_paint")                           # the extinguisher on the floor by it
    m.box((12.75, 9, 13.75), (14.25, 11, 15.25), "black")
    m.box((13, 11, 12.5), (15, 11.5, 15), "black")
    return m


def wall_newspaper():
    """The class's wall newspaper (стенгазета) on drawing paper, two blocks wide: the title in red, columns of handwriting,
    drawings in felt pen, a photo pinned on."""
    def paint(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("f4f0e2"))
        P.text(cv, cv.w // 2 - P.text_width("ШКОЛЬНАЯ ЖИЗНЬ") // 2, 2, "ШКОЛЬНАЯ ЖИЗНЬ", rgb("c42a2a"))
        r = random.Random("gazeta")
        for col in range(3):
            x0 = 3 + col * (cv.w - 4) // 3
            for k in range(9):
                y = 12 + k * 3
                cv.hline(x0, x0 + (cv.w - 4) // 3 - 3 - r.randrange(5), y, rgb("4a5a8a"))
        cv.disc(cv.w * 0.78, cv.h * 0.72, 5, rgb("f0c040"))               # a sun drawn in felt pen
        cv.rect(int(cv.w * 0.4), int(cv.h * 0.62), int(cv.w * 0.4) + 10, int(cv.h * 0.62) + 8, rgb("8a9a90"))
        cv.outline(0, 0, cv.w, cv.h, rgb("c8c0a8"))

    m = Model()
    m.box((0, 4, 15.5), (32, 22, 16), "paper", north=Pic("wall_newspaper", paint))
    return m


def wall_map():
    """The wall map of the country on its two wooden rods, rolled down over the side wall: the greens and browns of the
    relief, the blue of the seas, the title strip."""
    def paint(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("9ac8e0"))                        # the seas
        r = random.Random("map")
        for y in range(6, cv.h - 4):                                    # the land: a broad mass, ragged at the coasts
            x0 = 4 + int(3 * abs(((y * 7) % 11) - 5) / 5) + (y // 9)
            x1 = cv.w - 6 - int(4 * abs(((y * 5) % 13) - 6) / 6) - (cv.h - y) // 12
            for x in range(x0, x1):
                k = (x * 3 + y * 5 + r.randrange(3)) % 17
                c = rgb("7ab060") if k < 7 else rgb("b8c870") if k < 11 else rgb("d8b870") if k < 14 else rgb("b08a50")
                cv.set(x, y, c)
        for k in range(5):                                              # rivers
            x, y = 10 + k * 9, 8
            while y < cv.h - 6:
                cv.set(x, y, rgb("4a80c0"))
                y += 1
                x += r.choice((-1, 0, 0, 1))
        cv.rect(0, 0, cv.w, 4, rgb("f0ece0"))
        P.text(cv, 3, 0, "ФИЗИЧЕСКАЯ КАРТА", rgb("2a2a2a"))

    m = Model()
    m.box((-8, 4, 15.5), (24, 28, 16), "paper", north=Pic("wall_map", paint))
    m.box((-9, 28, 15), (25, 29, 16), "lightwood")
    m.box((-9, 3, 15), (25, 4, 16), "lightwood")
    m.box((7.5, 29, 15.5), (8.5, 31, 16), "black")
    return m


def menu_board():
    """The canteen's menu board: «МЕНЮ» and the day's dishes and prices chalked on, a wooden frame."""
    def paint(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("2c4636"))
        chalk = rgb("e8ece4")
        P.text(cv, cv.w // 2 - P.text_width("МЕНЮ") // 2, 2, "МЕНЮ", rgb("f0d060"))
        for k, line in enumerate(("КАША", "СУП", "КОТЛЕТА", "КОМПОТ")):
            P.text(cv, 3, 10 + k * 7, line, chalk)
            P.text(cv, cv.w - 3 - P.text_width("15"), 10 + k * 7, "15", chalk)

    m = Model()
    m.box((-8, 4, 15.25), (24, 26, 16), "board_frame", north=Pic("menu_board", paint))
    return m


def waste_bin():
    """A steel waste bin with a pedal, by the canteen's and the corridor's walls."""
    m = Model()
    m.box((4, 0, 4), (12, 11, 12), "steel_counter_v", up=None)
    m.box((4.5, 0.5, 4.5), (11.5, 10.5, 11.5), "black", faces=("up",))
    m.box((3.5, 11, 3.5), (12.5, 12, 12.5), "steel_counter")
    m.box((6, 0, 2.5), (10, 1, 4), "black")
    return m


def football_goal():
    """The handball/mini-football goal at the gym's end: a white steel frame three blocks wide with the net behind."""
    m = Model()
    for x in (-16, 30):
        m.box((x, 0, 2), (x + 2, 30, 4), "enamel")
    m.box((-16, 30, 2), (32, 32, 4), "enamel")
    m.box((-14, 0, 15.5), (30, 30, 16), "net", faces=("north", "south"), shade=False)
    m.box((-14, 29.5, 4), (30, 30, 15.5), "net", faces=("up", "down"), shade=False)
    for x in (-15.5, 30.5):
        m.box((x, 0, 4), (x + 1, 30, 15.5), "net", faces=("east", "west"), shade=False)
    return m


def ball_rack():
    """The ball cage: a steel wire box on castors full of basketballs and volleyballs."""
    m = Model()
    for x in (1, 14):
        for z in (2, 13):
            m.box((x, 2, z), (x + 1, 14, z + 1), "tube_steel")
    m.box((1, 1, 2), (15, 2, 14), "tube_steel")
    m.box((1, 14, 2), (15, 14.5, 14), "net", shade=False)
    for x, y, z, c in ((2.5, 2, 3.5, "hoop_red"), (8, 2, 3.5, "plate_rim"), (2.5, 2, 8.5, "plate_rim"), (8, 2, 8.5, "hoop_red"),
                       (5, 7, 6, "hoop_red"), (9, 7.5, 8.5, "plate_rim")):
        m.box((x, y, z), (x + 5, y + 5, z + 5), c)
    for x in (1.5, 14.5):
        m.box((x - 0.5, 0, 2.5), (x + 0.5, 1, 3.5), "black")
    m.box((2, 2, 1.5), (14, 14, 2), "net", faces=("north", "south"), shade=False)
    return m


def satchels():
    """A satchel and a sports bag dropped on the floor by a desk."""
    m = Model()
    m.box((2, 0, 4), (8, 7, 7), "leatherette")
    m.box((3, 7, 5), (7, 8, 6), "black")
    m.box((9, 0, 7), (15, 4, 13), "canvas_blue")
    return m


def make():
    textures()
    mat("tile_white", P.tiles(32, 32, rgb("eeeee8"), rgb("c8c8c0"), size=8))
    Furn("pub_stage_curtain", "Занавес сцены", "Stage curtain", stage_curtain(False), color="RED", sound="WOOL", solid=False)
    Furn("pub_stage_curtain_top", "Занавес сцены с ламбрекеном", "Stage curtain with pelmet", stage_curtain(True), color="RED",
         sound="WOOL", solid=False)
    Furn("pub_theatre_seat", "Кресло актового зала", "Assembly hall seat", lambda: theatre_seat(), seat=7, color="RED",
         sound="WOOL")
    Furn("pub_lectern", "Трибуна", "Lectern", lambda: lectern(), color="BROWN")
    Furn("pub_serving_pans", "Линия раздачи (горячее)", "Serving line (hot dishes)", serving_line("pans"), color="WHITE_GRAY",
         sound="METAL")
    Furn("pub_serving_kompot", "Линия раздачи (компот и хлеб)", "Serving line (kompot and bread)", serving_line("kompot"),
         color="WHITE_GRAY", sound="METAL")
    Furn("pub_kitchen_range", "Плита электрическая", "Kitchen range", lambda: kitchen_range(), color="WHITE_GRAY", sound="METAL")
    for kind, ru, en in (("board", "с разделочной доской", "with a cutting board"), ("grinder", "с мясорубкой", "with a meat grinder"),
                         ("bowls", "с тазами", "with basins")):
        Furn(f"pub_prep_table_{kind}", f"Стол производственный {ru}", f"Kitchen prep table {en}", prep_table(kind),
             color="WHITE_GRAY", sound="METAL")
    Furn("pub_kitchen_rack", "Стеллаж кухонный", "Kitchen rack", lambda: kitchen_rack(), color="WHITE_GRAY", sound="METAL")
    Furn("pub_oven_cabinet", "Шкаф жарочный", "Roasting oven", lambda: oven_cabinet(), color="WHITE_GRAY", sound="METAL")
    Furn("pub_wash_bath", "Ванна моечная", "Washing bath", lambda: wash_bath(), color="WHITE_GRAY", sound="METAL")
    Furn("pub_fridge_cabinet", "Шкаф холодильный", "Kitchen refrigerator", lambda: fridge_cabinet(), color="WHITE", sound="METAL")
    Furn("pub_dough_mixer", "Тестомес", "Dough mixer", lambda: dough_mixer(), color="GREEN", sound="METAL")
    Furn("pub_potato_peeler", "Картофелечистка", "Potato peeler", lambda: potato_peeler(), color="WHITE_GRAY", sound="METAL")
    Furn("pub_veg_crates", "Ящики с овощами", "Vegetable crates", lambda: veg_crates(), color="BROWN", sound="WOOD")
    Furn("pub_boiling_pan", "Пищеварочный котёл", "Boiling pan", lambda: boiling_pan(), color="WHITE_GRAY", sound="METAL")
    Furn("pub_cloak_section", "Секция гардероба", "Cloakroom section", cloak_section(True), color="WHITE", sound="METAL",
         seat=6, cutout=True)
    Furn("pub_cloak_section_empty", "Секция гардероба (пустая)", "Cloakroom section (empty)", cloak_section(False),
         color="WHITE", sound="METAL", seat=6, cutout=True)
    Furn("pub_class_sign", "Табличка класса", "Class sign", lambda: class_sign(), mount="WALL", color="WHITE", solid=False)
    Furn("pub_library_stack", "Двусторонний стеллаж", "Double-sided bookstack", lambda: library_stack(), color="BROWN")
    Furn("pub_reading_table", "Стол читального зала", "Reading table", lambda: reading_table(), color="BROWN", light=10)
    Furn("pub_issue_desk", "Кафедра выдачи книг", "Library issue desk", lambda: issue_desk(), color="BROWN")
    Furn("pub_lab_bench", "Демонстрационный стол", "Chemistry bench", lambda: lab_bench(), color="WHITE", sound="GLASS")
    Furn("pub_fume_cupboard", "Вытяжной шкаф", "Fume cupboard", lambda: fume_cupboard(), color="WHITE", sound="GLASS")
    Furn("pub_vaulting_horse", "Гимнастический козёл", "Vaulting buck", lambda: vaulting_horse(), color="BROWN", sound="WOOL")
    Furn("pub_springboard", "Гимнастический мостик", "Springboard", lambda: springboard(), color="OAK_TAN")
    Furn("pub_drinking_fountain", "Питьевой фонтанчик", "Drinking fountain", lambda: drinking_fountain(), color="WHITE",
         sound="METAL")
    Furn("pub_stand_school", "Стенд «Наша школа»", "School stand", school_stand("school"), mount="WALL", color="BLUE",
         solid=False)
    Furn("pub_stand_timetable", "Расписание уроков", "Timetable stand", school_stand("timetable"), mount="WALL",
         color="WHITE", solid=False)
    Furn("pub_portrait_pushkin", "Портрет Пушкина", "Pushkin portrait", portrait("pushkin"), mount="WALL", color="BLACK",
         solid=False)
    Furn("pub_portrait_tolstoy", "Портрет Толстого", "Tolstoy portrait", portrait("tolstoy"), mount="WALL", color="BLACK",
         solid=False)
    Furn("pub_school_bell", "Школьный звонок", "School bell", lambda: school_bell(), mount="WALL", color="RED", sound="METAL",
         solid=False)
    Furn("pub_trophy_cabinet", "Шкаф с кубками", "Trophy cabinet", lambda: trophy_cabinet(), color="BROWN", sound="GLASS")
    Furn("pub_class_cabinet", "Шкаф для пособий", "Classroom cabinet", lambda: class_cabinet(), color="OAK_TAN", sound="GLASS")
    Furn("pub_sill_flowers_a", "Радиатор и цветы на подоконнике", "Radiator and sill flowers", sill_flowers("a"), color="GREEN",
         sound="METAL", solid=False)
    Furn("pub_sill_flowers_b", "Радиатор и традесканция", "Radiator and tradescantia", sill_flowers("b"), color="GREEN",
         sound="METAL", solid=False)
    Furn("pub_fire_cabinet", "Пожарный кран и огнетушитель", "Fire hose cabinet", lambda: fire_cabinet(), mount="WALL",
         color="RED", sound="METAL", solid=False)
    Furn("pub_wall_newspaper", "Стенгазета", "Wall newspaper", lambda: wall_newspaper(), mount="WALL", color="WHITE", solid=False)
    Furn("pub_wall_map", "Физическая карта", "Wall map", lambda: wall_map(), mount="WALL", color="GREEN", solid=False)
    Furn("pub_menu_board", "Меню столовой", "Canteen menu", lambda: menu_board(), mount="WALL", color="GREEN", solid=False)
    Furn("pub_waste_bin", "Урна", "Waste bin", lambda: waste_bin(), color="WHITE_GRAY", sound="METAL")
    Furn("pub_football_goal", "Ворота для мини-футбола", "Mini-football goal", lambda: football_goal(), color="WHITE",
         sound="METAL", solid=False, cutout=True)
    Furn("pub_ball_rack", "Корзина с мячами", "Ball cage", lambda: ball_rack(), color="GRAY", sound="METAL", cutout=True)
    Furn("pub_satchels", "Портфели на полу", "Satchels", lambda: satchels(), color="BROWN", sound="WOOL", solid=False)
