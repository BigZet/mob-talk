"""The kindergarten's furniture (civic.py kindergarten2, after public-refs/sheets int_kg_*): the rainbow locker banks and the
low padded benches of the changing room, the group room's round tables, the doll's corner with its toy kitchen and doll's
bed, the easel, the soft modules and the little slide, the nature corner with the aquarium, the toilet's row of low
washbasins and the towel cabinet with the children's pictures, the potty rack, the group's crockery cupboard, the music
hall's puppet theatre and the instruments, the nature calendar on the wall.
Same conventions as fur_models.py (north space, front at low z, the back against the wall at z 16, x to the viewer's left)."""
import fur_paint as P
from fur_paint import rgb, ramp
from furniture import Furn, Model, Pic, mat

RAINBOW = [rgb(c) for c in ("d84a3a", "e88a30", "e8c030", "6ab040", "3a90c8", "7a5ab8")]


def textures():
    mat("kid_pad_red", P.fabric(32, 32, rgb("c8483a"), "kpadr", weave=1, nap=True))
    mat("kid_pad_blue", P.fabric(32, 32, rgb("3a80c0"), "kpadb", weave=1, nap=True))
    mat("kid_pad_green", P.fabric(32, 32, rgb("5aa848"), "kpadg", weave=1, nap=True))
    mat("kid_pad_yellow", P.fabric(32, 32, rgb("e8c040"), "kpady", weave=1, nap=True))
    mat("birch_ply", P.wood(32, 32, rgb("e0c89a"), "birchply", vertical=True, polish=0.3, knots=False))
    mat("birch_ply_h", P.wood(32, 32, rgb("e0c89a"), "birchplyh", vertical=False, polish=0.3, knots=False))
    mat("aqua_water", P.glass(32, 32, (90, 160, 200, 255)))
    mat("gravel", P.fabric(32, 32, rgb("9a8a70"), "gravel", weave=2, nap=False))
    mat("enamel_white", P.enamel(32, 32, rgb("eeeeea"), "enw", chips=1))
    mat("potty_red", P.enamel(32, 32, rgb("c8402e"), "pottyr", chips=0))
    mat("potty_blue", P.enamel(32, 32, rgb("3a74b8"), "pottyb", chips=0))
    mat("screen_cloth", P.fabric(32, 32, rgb("3a5aa0"), "puppet", weave=1, nap=True))


# ---------------------------------------------------------------- the changing room (раздевальня)

def locker_bank():
    """A bank of five narrow lockers on legs: a birch plywood carcass, the doors in rainbow colours with rounded tops and
    a round knob each, a hat shelf with open cells along the top."""
    def doors(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("e0c89a"))
        w = cv.w / 5
        for k in range(5):
            x0, x1 = round(k * w) + 1, round((k + 1) * w) - 1
            c = RAINBOW[k]
            t = ramp(c, 5, 0.75, 1.2)
            cv.rect(x0, 3, x1, cv.h - 1, t[2])
            cv.rect(x0 + 1, 2, x1 - 1, 3, t[2])                  # the rounded top
            cv.vline(x0, 3, cv.h - 1, t[3])
            cv.vline(x1 - 1, 3, cv.h - 1, t[1])
            cv.disc(x1 - 3, cv.h // 2, 1, rgb("f4f4f0"))

    m = Model()
    for x in (0, 30.5):
        m.box((x, 0, 4), (x + 1.5, 30, 16), "birch_ply")
    m.box((1.5, 3, 4.5), (30.5, 24, 16), "birch_ply", north=Pic("kglock_doors", doors), down=None)
    m.box((1.5, 2, 4), (30.5, 3, 16), "birch_ply_h")
    m.box((1.5, 24, 4), (30.5, 25, 16), "birch_ply_h")
    m.box((1.5, 29, 4), (30.5, 30, 16), "birch_ply_h")
    for x in (7.5, 13.5, 19.5, 25.5):                            # the cells of the hat shelf
        m.box((x, 25, 4.5), (x + 1, 29, 16), "birch_ply")
    m.box((3, 25, 8), (6, 27.5, 13), "laundry_pink")             # a hat, a pair of mittens
    m.box((15, 25, 8), (18, 26, 13), "kid_pad_blue")
    return m


def kid_bench(colour):
    """The changing room's low bench, padded in leatherette, on birch legs."""
    def build():
        m = Model()
        m.box((0.5, 5, 4), (31.5, 7.5, 12), f"kid_pad_{colour}")
        for x in (1.5, 28):
            m.box((x, 0, 5), (x + 2.5, 5, 11), "birch_ply")
        m.box((4, 2, 7.5), (28, 3, 8.5), "birch_ply_h")
        return m
    return build


# ---------------------------------------------------------------- the group room

def round_table():
    """A round group table (an octagon of laminate) on a birch frame, a plasticine set and a cup of pencils on it."""
    m = Model()
    m.box((1, 8, 3), (15, 9, 13), "kid_top")
    m.box((3, 8, 1), (13, 9, 3), "kid_top")
    m.box((3, 8, 13), (13, 9, 15), "kid_top")
    m.box((2, 7, 4), (14, 8, 12), "birch_ply_h", up=None)
    for x, z in ((3, 3), (11.5, 3), (3, 11.5), (11.5, 11.5)):
        m.box((x, 0, z), (x + 1.5, 7, z + 1.5), "birch_ply", up=None)
    m.box((4, 9, 5), (9, 9.5, 9), "blue_paint")                  # the plasticine board
    m.box((5, 9.5, 6), (6.5, 10.5, 7.5), "red_paint")
    m.box((7, 9.5, 6.5), (8, 10, 8), "yellow_paint")
    m.box((10.5, 9, 9), (12.5, 12, 11), "plastic_white")           # the cup of pencils
    for i, c in enumerate(("red_paint", "green_paint", "blue_paint")):
        m.box((10.75 + i * 0.5, 12, 9.75), (11.25 + i * 0.5, 14, 10.25), c)
    return m


def toy_kitchen():
    """The dolls' corner: a toy kitchen of painted plywood a child's height, with the stove's rings and the oven door, a
    sink with its tap, a saucepan and a kettle, the shelf over it with cups."""
    def front(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("f2ece0"))
        cv.bevel(1, 2, cv.w // 2 - 1, cv.h - 2, rgb("ffffff"), rgb("c8c0b0"))
        cv.rect(4, 6, cv.w // 2 - 4, cv.h - 6, rgb("2a2622"))
        cv.hline(4, cv.w // 2 - 4, 4, rgb("c83a2a"))
        cv.bevel(cv.w // 2 + 1, 2, cv.w - 1, cv.h - 2, rgb("ffffff"), rgb("c8c0b0"))
        cv.disc(cv.w * 3 // 4, cv.h // 2, 1, rgb("c83a2a"))
        for k in range(3):
            cv.disc(5 + k * 4, 2, 0.8, rgb("1a1a1a"))

    m = Model()
    m.box((0.5, 0, 6), (31.5, 13, 16), "plastic_white", north=Pic("toykit_front", front))
    m.box((0.5, 13, 6), (31.5, 14, 16), "kid_pad_yellow")
    for x, z in ((3, 8), (9, 8), (3, 12), (9, 12)):               # the rings
        m.box((x, 14, z), (x + 3, 14.5, z + 2.5), "iron_black")
    m.box((19, 13.5, 8), (28, 14.5, 14), "steel_sink")              # the sink let into the top
    m.box((23, 14, 14.5), (24, 18, 15.5), "chrome")
    m.box((23, 17, 11.5), (24, 18, 14.5), "chrome")
    m.box((2.5, 14.5, 7.5), (6.5, 17, 11), "red_paint")          # a saucepan, a kettle
    m.box((9, 14.5, 11.5), (12, 17, 14.5), "blue_paint")
    m.box((0.5, 24, 12), (31.5, 25, 16), "birch_ply_h")           # the cup shelf
    for x in (3, 9, 15, 21, 27):
        m.box((x, 25, 13), (x + 2, 27, 15), "cup_enamel")
    return m


def doll_bed():
    """A doll's cot with a doll tucked in under a pink blanket, a pram's worth of small."""
    m = Model()
    for x in (2, 13):
        m.box((x, 0, 3), (x + 1, 8, 4), "birch_ply")
        m.box((x, 0, 12), (x + 1, 8, 13), "birch_ply")
    for z in (3, 12):
        m.box((3, 4, z), (13, 9, z + 1), "birch_ply_h")
    for x in (2, 13):
        m.box((x, 4, 4), (x + 1, 6, 12), "birch_ply_h")
    m.box((3, 3, 4), (13, 5, 12), "linen", down=None)
    m.box((3.5, 5, 6.5), (12.5, 6, 11.5), "laundry_pink", down=None)
    m.box((6, 5, 4.25), (10, 6.5, 6.5), "pillow", down=None)
    m.box((7, 5.5, 4.75), (9, 7.5, 6.25), "plastic_beige", down=None)   # the doll's head on the pillow
    return m


def easel():
    """A double-sided easel on splayed legs, a child's sun and house on the paper, a tray of gouache jars."""
    def drawing(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("f6f4ec"))
        cv.disc(cv.w - 6, 5, 3, rgb("e8b830"))
        for dx, dy in ((0, -5), (-5, 0), (-4, 4), (4, -4)):
            cv.set(cv.w - 6 + dx, 5 + dy, rgb("e8b830"))
        cv.rect(5, cv.h - 11, 15, cv.h - 4, rgb("c8402e"))
        for k in range(6):
            cv.hline(4 + k, 16 - k, cv.h - 12 - k, rgb("7a4a2a"))
        cv.rect(9, cv.h - 8, 11, cv.h - 4, rgb("3a6ab8"))
        cv.hline(0, cv.w, cv.h - 3, rgb("4a9a48"))
        cv.hline(0, cv.w, cv.h - 2, rgb("4a9a48"))

    m = Model()
    m.box((2, 8, 6.5), (14, 24, 7), "paper", north=Pic("easel_drawing", drawing))
    m.box((1.5, 7.5, 7), (14.5, 24.5, 8), "birch_ply")
    for x in (2, 13):
        m.box((x, 0, 4), (x + 1, 26, 5), "birch_ply", rot={"origin": [x + 0.5, 0, 8], "axis": "x", "angle": 22.5})
        m.box((x, 0, 11), (x + 1, 26, 12), "birch_ply", rot={"origin": [x + 0.5, 0, 8], "axis": "x", "angle": -22.5})
    m.box((1.5, 6.5, 4.5), (14.5, 7.5, 7), "birch_ply_h")
    for i, c in enumerate(("red_paint", "blue_paint", "yellow_paint", "green_paint")):
        m.box((2.5 + i * 3, 7.5, 5), (4.5 + i * 3, 9, 6.5), c)
    return m


def soft_blocks():
    """Soft play modules in vinyl: a big cube, a half-cylinder arch and a wedge, in the primary colours."""
    m = Model()
    m.box((0.5, 0, 1), (8.5, 8, 9), "kid_pad_red")
    m.box((9.5, 0, 2), (15.5, 6, 8), "kid_pad_yellow")
    m.box((1, 0, 10), (15, 4, 15), "kid_pad_blue")
    m.box((3, 4, 10), (13, 6, 15), "kid_pad_blue")
    m.box((2, 8, 2), (7, 13, 7), "kid_pad_green")
    return m


def kid_slide():
    """A small indoor slide two blocks long: a step ladder with rails at the back, the chute sloping to the front."""
    m = Model()
    for x in (1, 14):
        m.box((x, 0, 28), (x + 1, 16, 29.5), "plastic_white")
        m.box((x, 16, 26), (x + 1, 22, 27), "plastic_white")
    for y in (3, 7, 11):
        m.box((2, y, 28), (14, y + 1, 30), "kid_pad_yellow")
    m.box((2, 15, 19.5), (14, 16, 30), "kid_pad_yellow")
    m.box((3, 0, 0.5), (13, 1, 5), "kid_pad_red")                 # the run-out at the foot
    rot = {"origin": [8, 1, 5], "axis": "x", "angle": ROT}
    m.box((3, 1, 5), (13, 2.5, 25), "kid_pad_red", rot=rot)        # the chute, from the platform down to the run-out
    m.box((2, 1, 5), (3, 4, 25), "kid_pad_blue", rot=rot)
    m.box((13, 1, 5), (14, 4, 25), "kid_pad_blue", rot=rot)
    return m


# ---------------------------------------------------------------- the nature corner

def aquarium():
    """The nature corner's aquarium on its cabinet: green weed, gravel, a few goldfish, a lamp lid."""
    def tank(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("6aaad0"))
        cv.vgrad(0, 0, cv.w, cv.h, [rgb("8ac4e0"), rgb("5a9ac8"), rgb("3a7aa8")])
        cv.rect(0, cv.h - 3, cv.w, cv.h, rgb("9a8a70"))
        for x in (3, 8, 22, 27):
            for y in range(cv.h - 12 + (x % 4), cv.h - 3):
                cv.set(x + (y % 2), y, rgb("3a8a3a"))
        for x, y in ((12, 6), (18, 10), (6, 12)):
            cv.rect(x, y, x + 3, y + 2, rgb("e8782a"))
            cv.set(x - 1, y + 1, rgb("e8782a"))
        cv.outline(0, 0, cv.w, cv.h, rgb("2a2c2e"))

    m = Model()
    m.box((0.5, 0, 3), (15.5, 11, 15), "birch_ply")
    m.box((1, 11, 4), (15, 19, 14), "aqua_water", north=Pic("aquarium_tank", tank), up=None, down=None)
    m.box((1, 11, 4), (15, 12, 14), "gravel", faces=("up",))
    m.box((0.75, 19, 3.75), (15.25, 20.5, 14.25), "plastic_black")
    return m


def nature_shelf():
    """The nature corner's stepped stand: pots of geranium, aloe and a cactus, a watering can, a jar of onions sprouting."""
    m = Model()
    for i, (y, z) in enumerate(((0, 4), (6, 8), (12, 12))):
        m.box((0.5, y, z), (31.5, y + 6, 16), "birch_ply_h")
    pots = ((2, 6, 5, "geranium"), (8, 6, 5, "aloe"), (14, 6, 5, "leaf"), (20, 6, 5, "geranium"), (26, 6, 5, "leaf"),
            (4, 12, 9, "aloe"), (12, 12, 9, "leaf"), (22, 12, 9, "geranium"), (6, 18, 13, "leaf"), (18, 18, 13, "aloe"))
    for x, y, z, plant in pots:
        m.box((x, y, z), (x + 3.5, y + 3, z + 2.5), "terracotta")
        m.box((x + 0.5, y + 3, z + 0.25), (x + 3, y + 5.5, z + 2.25), plant)
    m.box((26, 12, 9), (30, 15, 11), "glass", up=None)              # the onions in a jar
    m.box((27, 15, 9.5), (28, 18, 10.5), "leaf")
    m.box((26, 18, 13), (30, 21, 15), "green_paint")                # the watering can
    m.box((24, 19.5, 13.5), (26, 20.5, 14.5), "green_paint")
    return m


def nature_calendar():
    """The calendar of nature on the wall: the season's tree, the weather, the day of the week to move along."""
    def board(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("f2ead8"))
        cv.outline(0, 0, cv.w, cv.h, rgb("3a6ab8"))
        cv.rect(2, 2, cv.w // 2 - 1, cv.h - 2, rgb("cfe4f2"))
        cv.rect(cv.w // 4 - 1, cv.h - 10, cv.w // 4 + 1, cv.h - 2, rgb("7a4a2a"))
        cv.disc(cv.w // 4, cv.h - 13, 5, rgb("e8a030"))
        cv.disc(cv.w // 2 + 6, 6, 3, rgb("e8b830"))
        for k in range(7):
            cv.rect(cv.w // 2 + 2 + k * 4, cv.h - 6, cv.w // 2 + 5 + k * 4, cv.h - 3, RAINBOW[k % 6])
        cv.rect(cv.w // 2 + 2, 12, cv.w - 3, 13, rgb("7a7a76"))

    m = Model()
    m.box((-6, 4, 15), (22, 18, 16), "birch_ply", north=Pic("nature_calendar", board))
    return m


# ---------------------------------------------------------------- the toilet and washroom

def kid_washbasins():
    """The children's row of three low washbasins, each with its own little mirror and the tap."""
    m = Model()
    m.box((0, 8, 4), (32, 10, 16), "enamel_white", up=None)
    for x0 in (1, 11.5, 22):
        m.box((x0, 9, 5), (x0 + 9, 9.5, 14), "enamel_in")
        m.box((x0 + 4, 10, 14), (x0 + 5, 13, 15), "chrome")
        m.box((x0 + 4, 12, 11.5), (x0 + 5, 13, 14), "chrome")
        m.box((x0 + 1, 15, 15.5), (x0 + 8, 21, 16), "glass", faces=("north",))
    m.box((1, 0, 13), (31, 8, 15), "tile_white")
    m.box((0, 10, 4), (32, 10.5, 5), "enamel_white")
    m.box((0, 10, 15), (32, 10.5, 16), "enamel_white")
    return m


def towel_cabinet():
    """The towel cabinet: an open board of hooks under the children's pictures, a towel on each in another colour."""
    def board(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("e0c89a"))
        for k in range(6):
            x = 2 + k * (cv.w - 4) // 6
            cv.rect(x, 2, x + 6, 7, rgb("f6f4ec"))
            cv.disc(x + 3, 4.5, 1.5, RAINBOW[k])

    m = Model()
    m.box((0, 6, 15), (32, 30, 16), "birch_ply", north=Pic("towel_board", board))
    for k in range(6):
        x = 1.5 + k * 5
        m.box((x, 10, 13.5), (x + 4, 24, 15), f"laundry_{['white', 'pink', 'grey', 'plaid', 'white', 'pink'][k]}")
        m.box((x + 1.5, 24, 14), (x + 2.5, 25, 15), "chrome")
    return m


def potty_rack():
    """The potty rack (горшечная): a birch rack of pigeonholes, an enamelled potty in each with the child's sign."""
    m = Model()
    for x in (0, 31):
        m.box((x, 0, 6), (x + 1, 18, 16), "birch_ply")
    for y in (0, 8.5, 17):
        m.box((1, y, 6), (31, y + 1, 16), "birch_ply_h")
    m.box((1, 1, 15.5), (31, 17, 16), "birch_ply", faces=("north",))
    for row, y in enumerate((1, 9.5)):
        for k in range(5):
            x = 2 + k * 6
            m.box((x, y, 8), (x + 4.5, y + 4, 13), "potty_red" if (k + row) % 2 else "potty_blue", up="enamel_in")
    return m


# ---------------------------------------------------------------- the group's pantry and the music hall

def dish_cupboard():
    """The group's crockery cupboard (буфетная): white cabinets below, a plate rack and the glazed doors above with the
    children's enamel mugs and the bowls."""
    m = Model()
    m.box((0.5, 0, 4), (31.5, 14, 16), "enamel_white")
    m.box((0.5, 14, 3.5), (31.5, 15, 16), "steel_counter")
    for x in (8, 16, 24):
        m.box((x, 1, 3.5), (x + 0.5, 13, 4), "plastic_grey")
    m.box((0.5, 20, 9), (31.5, 32, 16), "enamel_white", north=None)
    m.box((1, 20.5, 9), (31, 31.5, 9.5), "glass", faces=("north",))
    for i in range(7):
        m.box((2 + i * 4, 21, 11), (4.5 + i * 4, 23.5, 14), "cup_enamel")
    for i in range(6):
        m.box((3 + i * 4.5, 26, 12), (6 + i * 4.5, 30, 13), "plate")
    m.box((4, 15, 6), (14, 18, 14), "plate")                     # a stack of bowls ready
    m.box((18, 15, 7), (24, 21, 13), "aluminium_pot", up="kompot")  # the kettle of kompot
    return m


def puppet_theatre():
    """The puppet theatre's screen (ширма): a plywood frame two blocks wide with a blue cloth front and a red curtain over
    the stage opening, a hare and a fox peeping over it."""
    def front(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("3a5aa0"))
        for x in range(0, cv.w, 4):
            cv.vline(x, 0, cv.h, rgb("2e4a88"))
        for k in range(7):
            cv.disc(4 + k * (cv.w - 8) / 6, cv.h - 5, 1.5, rgb("e8c040"))
        P.text(cv, 6, 4, "ТЕАТР", rgb("e8c040"))

    m = Model()
    m.box((0, 0, 7), (32, 18, 9), "screen_cloth", north=Pic("puppet_front", front))
    m.box((-0.5, 18, 6.5), (32.5, 19, 9.5), "birch_ply_h")
    m.box((4, 19, 7.5), (28, 30, 8.5), "velvet_flat")
    m.box((6, 19, 6.5), (26, 29, 7.5), "velvet", faces=("north",))
    m.box((9, 19, 7), (12, 24, 9), "plastic_grey", down=None)                  # the hare's head and its ears
    m.box((9.5, 24, 7.75), (10.5, 28, 8.25), "plastic_grey")
    m.box((10.5, 24, 7.75), (11.5, 28, 8.25), "plastic_grey")
    m.box((20, 19, 7), (24, 23, 9), "plastic_orange", down=None)
    return m


def instruments():
    """The music hall's instruments on a low bench: a metallophone, two tambourines, maracas and a drum."""
    m = Model()
    m.box((0.5, 6, 3), (31.5, 7.5, 13), "birch_ply_h")
    for x in (2, 28):
        m.box((x, 0, 4), (x + 2, 6, 12), "birch_ply")
    for i, c in enumerate(RAINBOW_KEYS):
        m.box((2.5 + i * 1.5, 7.5, 4 + i * 0.25), (3.5 + i * 1.5, 8.25, 11.5 - i * 0.25), c)
    m.box((16, 7.5, 5), (21, 9, 10), "lightwood")                   # a tambourine
    m.box((16.5, 9, 5.5), (20.5, 9.5, 9.5), "paper")
    m.box((23, 7.5, 4), (30, 13, 11), "red_paint", up="paper")      # the drum
    m.box((22, 7.5, 11), (24, 10, 12.5), "yellow_paint")             # the maracas
    return m


ROT = -45
RAINBOW_KEYS = ["red_paint", "yellow_paint", "green_paint", "blue_paint", "red_paint", "yellow_paint", "green_paint",
                "blue_paint"]


def make():
    textures()
    Furn("pub_kid_locker_bank", "Шкафчики для раздевалки", "Changing room locker bank", lambda: locker_bank(),
         color="ORANGE")
    for c in ("red", "blue", "green", "yellow"):
        Furn(f"pub_kid_bench_{c}", "Скамейка в раздевалке", "Changing room bench", kid_bench(c), seat=7.5, color=c.upper(),
             sound="WOOL")
    Furn("pub_kid_round_table", "Круглый детский столик", "Round child's table", lambda: round_table(), color="YELLOW")
    Furn("pub_toy_kitchen", "Игрушечная кухня", "Toy kitchen", lambda: toy_kitchen(), color="WHITE")
    Furn("pub_doll_bed", "Кроватка для кукол", "Doll's cot", lambda: doll_bed(), color="PINK")
    Furn("pub_easel", "Мольберт", "Easel", lambda: easel(), color="OAK_TAN")
    Furn("pub_soft_blocks", "Мягкие модули", "Soft play blocks", lambda: soft_blocks(), color="RED", sound="WOOL")
    Furn("pub_kid_slide", "Детская горка", "Indoor slide", lambda: kid_slide(), color="YELLOW")
    Furn("pub_aquarium", "Аквариум", "Aquarium", lambda: aquarium(), color="LIGHT_BLUE", sound="GLASS", light=6)
    Furn("pub_nature_shelf", "Уголок природы", "Nature corner", lambda: nature_shelf(), color="GREEN")
    Furn("pub_nature_calendar", "Календарь природы", "Nature calendar", lambda: nature_calendar(), mount="WALL",
         color="WHITE", solid=False)
    Furn("pub_kid_washbasins", "Детские умывальники", "Children's washbasins", lambda: kid_washbasins(), color="WHITE",
         sound="STONE")
    Furn("pub_towel_cabinet", "Вешалка для полотенец", "Towel board", lambda: towel_cabinet(), mount="WALL", color="WHITE",
         solid=False)
    Furn("pub_potty_rack", "Горшечная", "Potty rack", lambda: potty_rack(), color="OAK_TAN")
    Furn("pub_dish_cupboard", "Буфет группы", "Group's crockery cupboard", lambda: dish_cupboard(), color="WHITE",
         sound="GLASS")
    Furn("pub_puppet_theatre", "Кукольный театр", "Puppet theatre", lambda: puppet_theatre(), color="BLUE", sound="WOOL")
    Furn("pub_instruments", "Детские музыкальные инструменты", "Children's instruments", lambda: instruments(),
         color="RED")
