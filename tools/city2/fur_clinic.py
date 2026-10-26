"""The polyclinic's furniture (civic.py polyclinic2, after public-refs/sheets int_clinic_*): the registry's glazed wall with
its numbered windows, the cloakroom's counter, the chemist's showcase, the electronic queue's terminal and the doctors'
timetable, the shoe-cover machine; the dentist's chair and unit, the X-ray, the laboratory's bench with the microscope,
the ENT unit, the physiotherapy set, the steriliser, the instrument table, the height meter, the wheelchair, the drip.
Same conventions as fur_models.py (north space, front at low z, the back against the wall at z 16, x to the viewer's left)."""
import fur_paint as P
from fur_paint import rgb, ramp, shade
from furniture import Furn, Model, Pic, mat

PANEL = rgb("d8dcd4")       # the registry's partition, painted light grey-green
BLUE = rgb("2a5a9a")        # the signs


def textures():
    mat("reg_panel", P.enamel(32, 32, PANEL, "regp", chips=0))
    mat("dental_blue", P.enamel(32, 32, rgb("5a9cc0"), "dentb", chips=0))
    mat("dental_seat", P.fabric(32, 32, rgb("4a8cb4"), "dents", weave=1, nap=False))
    mat("med_grey", P.enamel(32, 32, rgb("b8bcb8"), "medg", chips=0))
    mat("med_cream", P.enamel(32, 32, rgb("e8e4d4"), "medc", chips=1))
    mat("lead_grey", P.metal(32, 32, rgb("7a8288"), vertical=False))
    mat("sign_blue", P.enamel(32, 32, BLUE, "signb", chips=0))
    mat("screen_lit", P.enamel(32, 32, rgb("8ac8f0"), "scrl", chips=0))
    mat("pill_box", P.enamel(32, 32, rgb("f0f0e8"), "pillb", chips=0))
    mat("stand_white", P.enamel(32, 32, rgb("eef0ee"), "standw", chips=1))
    mat("lam_grey", P.enamel(32, 32, rgb("d4d2ca"), "lamg", chips=0))
    mat("lam_edge", P.enamel(32, 32, rgb("8a8a86"), "lame", chips=0))
    mat("med_card", _cards())
    mat("cardboard", P.enamel(32, 32, rgb("c4a070"), "cardb", chips=3))
    mat("safe_green", P.enamel(32, 32, rgb("5a6a58"), "safeg", chips=2))


def _cards():
    """The edges of a stack of out-patient cards (амбулаторные карты): cream card, a coloured tab now and then."""
    import random
    r = random.Random("medcard")
    cv = P.Canvas(32, 32, rgb("e8dcc0"))
    for y in range(0, 32, 2):
        cv.hline(0, 32, y, rgb("c8b898"))
        if r.random() < 0.4:
            x = r.randrange(0, 26)
            cv.hline(x, x + 6, y + 1, r.choice([rgb("c84a3a"), rgb("3a6aa0"), rgb("4a9a5a"), rgb("e0b030")]))
    return cv


def _plate(text, bg=BLUE, fg=rgb("f4f4ee")):
    def paint(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, bg)
        cv.outline(0, 0, cv.w, cv.h, shade(bg, 0.7))
        P.text(cv, cv.w // 2 - P.text_width(text) // 2, cv.h // 2 - 2, text, fg)
    return paint


# ---------------------------------------------------------------- the registry (регистратура), the cloakroom, the hall

def registry_window(n):
    """One bay of the registry's glazed wall, a storey high, standing in the opening onto the hall: a panelled base
    with the counter shelf, the glass over it with the little arched window the cards go through, the bay's number over
    it in a blue plate; inside, the clerk's side of the counter."""
    def build():
        def base(cv, W, H):
            cv.rect(0, 0, cv.w, cv.h, PANEL)
            cv.bevel(2, 2, cv.w - 2, cv.h - 2, shade(PANEL, 1.08), shade(PANEL, 0.8))

        m = Model()
        m.box((0, 0, 7), (16, 15, 9), "reg_panel", north=Pic("regw_base", base), south=Pic("regw_base", base), up=None)
        m.box((0, 15, 2.5), (16, 16, 13.5), "formica")              # the counter, both sides of the glass
        m.box((0, 16, 7.5), (1, 39.5, 8.5), "alu")
        m.box((15, 16, 7.5), (16, 39.5, 8.5), "alu")
        m.box((1, 22.5, 7.75), (15, 39.5, 8.25), "glass", up=None, down=None)
        m.box((1, 16, 7.75), (4.5, 22.5, 8.25), "glass", up=None, down=None)
        m.box((11.5, 16, 7.75), (15, 22.5, 8.25), "glass", up=None, down=None)
        m.box((4.5, 22, 7.6), (11.5, 22.5, 8.4), "alu")                     # the hatch's head
        m.box((0, 39.5, 7.5), (16, 48, 8.5), "sign_blue", north=Pic(f"regw_n{n}", _plate(f"ОКНО {n}")),
              south=Pic("regw_back", _plate("")), up=None)
        m.box((0, 48, 7), (16, 64, 9), "reg_panel")                         # the partition up to the ceiling
        m.box((5, 16, 3.5), (9, 16.5, 6.5), "paper")                       # a referral on the counter
        m.box((9.5, 16, 10), (13.5, 18.5, 13), "pc_beige")                  # the clerk's card box
        return m
    return build


def registry_glass():
    """The registry's glazed wall between its windows: a panelled base, glass to the ceiling, a sign band at the top."""
    m = Model()
    m.box((0, 0, 7), (16, 15, 9), "reg_panel", up=None)
    m.box((0, 15, 6.5), (16, 16, 9.5), "formica")
    m.box((0, 16, 7.5), (16, 16.5, 8.5), "alu")
    m.box((0.25, 16.5, 7.75), (15.75, 39.5, 8.25), "glass", up=None, down=None)
    m.box((0, 39.5, 7.5), (16, 48, 8.5), "sign_blue", north=Pic("regg_band", _plate("")),
          south=Pic("regw_back", _plate("")), up=None)
    m.box((0, 48, 7), (16, 64, 9), "reg_panel")
    return m


def cloak_counter():
    """The cloakroom's counter (гардероб): a wooden barrier with a top, the hook board with brass number tags on it."""
    def front(cv, W, H):
        P_ = rgb("8a5a34")
        cv.rect(0, 0, cv.w, cv.h, P_)
        for k in range(3):
            x = 1 + k * cv.w // 3
            cv.bevel(x, 2, x + cv.w // 3 - 2, cv.h - 2, shade(P_, 1.15), shade(P_, 0.7))

    m = Model()
    m.box((0, 0, 6), (16, 15, 9), "walnut", north=Pic("cloakc_front", front), down=None)
    m.box((0, 15, 4), (16, 16.5, 11), "formica")
    for x in (2, 6, 10):                                                     # the number tags waiting on the counter
        m.box((x, 16.5, 7), (x + 2, 17, 9), "brass")
    return m


def pharmacy_showcase():
    """The chemist's kiosk: a glass showcase counter two blocks long with packets of medicine on its shelves, the till and
    a stand of plasters on top, a glass screen with the window over it."""
    m = Model()
    m.box((0, 0, 4), (32, 2, 12), "walnut")
    for x in (0, 31):
        m.box((x, 2, 4), (x + 1, 14, 12), "alu", up=None, down=None)
    m.box((1, 2, 4), (31, 14, 4.5), "glass", up=None, down=None)
    m.box((1, 7, 4.5), (31, 7.5, 11.5), "glass_shelf")
    m.box((1, 14, 3.5), (32, 15, 12.5), "formica")
    m.box((1, 2, 11.5), (31, 14, 12), "walnut", north=None)
    cols = ["pill_box", "red_paint", "blue_paint", "pill_box", "green_paint", "yellow_paint"]
    for k in range(6):                                                       # the packets on the shelves
        x = 2 + k * 4.75
        m.box((x, 2, 6), (x + 3.5, 5, 9), cols[k])
        m.box((x + 0.5, 7.5, 6.5), (x + 3, 10.5, 9.5), cols[(k + 2) % 6])
    m.box((22, 15, 6), (29, 19, 11), "pc_beige")                             # the till
    m.box((23, 19, 6), (28, 20.5, 8), "plastic_black")
    m.box((3, 15, 7), (8, 21, 9), "pill_box")                                # the stand of plasters
    m.box((0.5, 15, 11.75), (31.5, 36, 12.25), "glass", up=None, down=None)
    m.box((0, 36, 11), (32, 40, 13), "sign_blue", north=Pic("pharm_sign", _plate("АПТЕЧНЫЙ ПУНКТ", rgb("2a8a5a"))))
    return m


def queue_terminal():
    """The electronic queue's terminal (инфомат): a grey pedestal, the screen tilted back and lit, the ticket slot."""
    def scr(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("e8f2fa"))
        cv.rect(0, 0, cv.w, 5, BLUE)
        for k in range(4):
            cv.rect(3, 8 + k * 6, cv.w - 3, 12 + k * 6, rgb("5a9ad0"))

    m = Model()
    m.box((3, 0, 5), (13, 1, 13), "plastic_grey")
    m.box((4.5, 1, 8), (11.5, 18, 12), "med_grey")
    m.box((6, 12, 7.5), (10, 13, 8), "plastic_black")                       # the ticket slot
    m.box((2, 18, 5), (14, 30, 13), "med_grey", north=Pic("queue_scr", scr), rot={"origin": [8, 18, 9], "axis": "x",
                                                                                     "angle": -22.5})
    m.box((0, 30, 9), (16, 33, 12), "sign_blue", north=Pic("queue_top", _plate("ОЧЕРЕДЬ")))
    return m


def doctor_schedule():
    """The doctors' timetable on the wall (расписание приёма врачей): a blue board two blocks wide, white lines of names,
    rooms and hours in a grid."""
    def board(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("f4f4ee"))
        cv.rect(0, 0, cv.w, 7, BLUE)
        P.text(cv, cv.w // 2 - P.text_width("РАСПИСАНИЕ") // 2, 1, "РАСПИСАНИЕ", rgb("f4f4ee"))
        for k in range(6):
            y = 10 + k * 4
            cv.hline(2, cv.w - 2, y + 3, rgb("c0c8d0"))
            cv.hline(3, 3 + 9 + (k * 5) % 7, y + 1, rgb("3a3a40"))
            for c in range(4):
                cv.hline(cv.w // 2 + c * 7, cv.w // 2 + c * 7 + 4, y + 1, rgb("3a6aa0"))
        cv.outline(0, 0, cv.w, cv.h, shade(BLUE, 0.8))

    m = Model()
    m.box((0, 2, 14.5), (32, 28, 16), "sign_blue", north=Pic("sched_board", board))
    return m


def shoe_cover_machine():
    """The shoe-cover machine (бахилы): a blue box on a stand with its coin slot, the step you put your foot into, a sign;
    the bin for the used ones beside it."""
    m = Model()
    m.box((1, 0, 5), (11, 1, 14), "plastic_grey")
    m.box((2, 1, 5.5), (10, 4, 13.5), "plastic_blue")                        # the step the shoe goes into
    m.box((3, 4, 9), (9, 12, 13), "plastic_grey")
    m.box((0.5, 12, 6), (15.5, 24, 14), "plastic_blue", north=Pic("bahil_front", _plate("БАХИЛЫ")))
    m.box((11.5, 20, 5.5), (13, 21, 6), "chrome")
    return m


# ---------------------------------------------------------------- the doctors' rooms

def dental_chair():
    """The dentist's chair, two blocks long: a cream base and column, the seat and the reclined back in blue, the headrest,
    an arm, the foot plate."""
    m = Model()
    m.box((8, 0, 3), (24, 1, 13), "med_cream")
    m.box((13, 1, 5), (19, 6, 11), "med_cream")
    m.box((4, 6, 3), (20, 9, 13), "dental_seat")                             # the seat
    m.box((0, 4, 4), (4, 7, 12), "dental_seat")                              # the foot end
    m.box((0, 3.5, 4.5), (4, 4, 11.5), "chrome")
    rot = {"origin": [20, 9, 8], "axis": "z", "angle": 45}
    m.box((20, 6, 3.5), (32, 9, 12.5), "dental_seat", rot=rot)                    # the back, leant back
    m.box((27, 15, 5), (31, 18, 11), "dental_seat", rot={"origin": [27, 15, 8], "axis": "z", "angle": 22.5})
    m.box((6, 9, 2), (18, 10.5, 3.5), "med_cream")                           # the arm
    m.box((8, 6, 2.25), (9.5, 9, 2.75), "chrome")
    return m


def dental_unit():
    """The dental unit beside the chair: a cream column with the spittoon bowl and its glass, the instrument tray on an arm
    with the drill handpieces, the round lamp on a boom over the chair."""
    m = Model()
    m.box((4, 0, 8), (12, 1, 14), "med_cream")
    m.box((6, 1, 9), (10, 22, 13), "med_cream")
    m.box((2, 14, 9.5), (7, 16, 13.5), "enamel_white")                       # the spittoon
    m.box((2.5, 15, 10), (6, 16.25, 13), "steel_sink")
    m.box((8.5, 16, 9.5), (9.5, 19, 10.5), "glass")
    m.box((10, 18, 4), (16, 19, 12), "med_cream")                            # the tray
    for x in (11, 12.75, 14.5):
        m.box((x, 19, 5), (x + 1, 19.5, 9.5), "chrome")
    m.box((7, 22, 10), (9, 32, 12), "chrome")                                 # the lamp's boom
    m.box((7, 32, 3), (9, 33.5, 12), "chrome")
    m.box((5, 30, 1), (11, 33, 4), "plastic_white", down="bulb")
    return m


def xray():
    """The X-ray (рентген-аппарат): a table two blocks long on a pedestal, the column at its head with the tube on its
    arm over the table, the lead-grey bucky under the top, a warning plate."""
    m = Model()
    m.box((4, 0, 4), (28, 7, 12), "med_grey")                                # the pedestal
    m.box((0, 7, 1), (32, 9, 15), "med_cream")                               # the top
    m.box((2, 9, 2), (25.5, 9.5, 14), "linen", down=None)
    m.box((10, 5, 3), (22, 6.75, 13), "lead_grey", down=None)                   # the bucky tray
    m.box((26, 9, 13.5), (30, 42, 16), "med_grey")                           # the column
    m.box((14, 34, 12), (28, 37, 15), "med_grey")                            # the arm
    m.box((13, 26, 6), (21, 34, 15), "lead_grey")                            # the tube head
    m.box((14.5, 24, 7.5), (19.5, 26, 13.5), "plastic_black")                # the collimator
    m.box((26, 42, 13), (30, 46, 16), "sign_blue", north=Pic("xray_warn", _plate("!", rgb("d8b02a"), rgb("1a1a1a"))))
    return m


def lab_table():
    """The laboratory's bench, two blocks long, white with a black top: the microscope, a rack of test tubes with stoppers,
    a small centrifuge, a tray of slides, a lamp."""
    m = Model()
    m.box((0.5, 0, 3), (31.5, 13, 15), "lab_white")
    m.box((0, 13, 2), (32, 14, 15.5), "lab_black")
    m.box((4, 14, 6), (10, 15, 12), "iron_black")                            # the microscope: the foot
    m.box((8, 15, 8.5), (9.5, 24, 10.5), "iron_black")                       # the limb
    m.box((6, 17, 7), (10, 18, 11), "iron_black")                            # the stage
    m.box((5.5, 21, 8.5), (8, 26, 10.5), "iron_black", rot={"origin": [7, 22, 9.5], "axis": "x", "angle": 22.5})
    m.box((6, 18.5, 8.75), (7.5, 21, 10.25), "chrome")                       # the objectives
    m.box((13, 14, 7), (21, 16, 11), "lightwood")                            # the test tube rack
    for k in range(5):
        x = 13.5 + k * 1.5
        m.box((x, 16, 8.5), (x + 1, 20, 9.5), "glass")
        m.box((x, 20, 8.5), (x + 1, 21, 9.5), "red_paint" if k % 2 else "blue_paint")
    m.box((23, 14, 5), (29, 18, 11), "pc_beige")                             # the centrifuge
    m.box((24, 18, 6), (28, 18.5, 10), "lab_black")
    m.box((14, 14, 12), (20, 14.5, 14), "glass")                             # the slides
    return m


def ent_unit():
    """The ENT unit (ЛОР-комбайн), two blocks wide: a cream console with the instrument trays and the spray guns, the
    swivel lamp, the doctor's head mirror on a hook; the patient's turning chair stands before it."""
    m = Model()
    m.box((0.5, 0, 6), (31.5, 14, 15.5), "med_cream")
    m.box((0, 14, 5), (32, 15, 16), "formica")
    m.box((2, 15, 7), (12, 15.5, 13), "chrome")                              # the tray of mirrors and specula
    for x in (3, 5, 7, 9):
        m.box((x, 15.5, 8), (x + 0.75, 16, 12), "steel")
    for x in (15, 18):                                                       # the spray guns in their holders
        m.box((x, 15, 12), (x + 2, 21, 14), "plastic_grey")
    m.box((22, 15, 8), (30, 20, 14), "med_grey")                             # the suction box
    m.box((28, 20, 12), (29.5, 34, 13.5), "chrome")                          # the lamp
    m.box((24, 32, 9), (30, 36, 14), "plastic_white", down="bulb")
    m.box((2, 18, 15), (6, 26, 16), "chrome")                                # the head mirror on its band
    m.box((3, 21, 14.5), (5, 23, 15), "glass")
    return m


def physio_unit():
    """A physiotherapy set (УВЧ) on its trolley: a grey cabinet with the dials and the lamps, two round electrodes on
    jointed arms, a folded sheet."""
    def face(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("a8b0aa"))
        for k in range(3):
            cv.disc(5 + k * 10, cv.h // 2, 2, rgb("2a2a2a"), ring=rgb("e0e0d8"))
        cv.disc(cv.w - 4, 4, 1, rgb("e83a2a"))

    m = Model()
    m.box((2, 0, 4), (14, 1, 13), "chrome")
    for x in (2.5, 12.5):
        m.box((x, 1, 5), (x + 1, 12, 6), "chrome")
        m.box((x, 1, 11), (x + 1, 12, 12), "chrome")
    m.box((2, 5, 4.5), (14, 5.5, 12.5), "steel")
    m.box((3, 5.5, 6), (13, 7.5, 11), "linen", down=None)
    m.box((1.5, 12, 4), (14.5, 22, 13), "med_grey", north=Pic("physio_face", face))
    for x in (3, 12):                                                        # the arms and their electrodes
        m.box((x, 22, 9), (x + 1, 28, 10), "chrome")
        m.box((x - 1.5, 27, 5), (x + 2.5, 31, 9), "plastic_black")
    return m


def sterilizer():
    """A dry-heat steriliser (ГП-40) on a white cabinet: the cream box with its door and dial and the clock-switch, the
    tray of instruments beside it."""
    def door(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("e8e4d4"))
        cv.bevel(2, 2, cv.w * 2 // 3, cv.h - 2, rgb("f8f6ee"), rgb("b8b4a4"))
        cv.disc(cv.w * 5 // 6, cv.h // 3, 2, rgb("e8e8e0"), ring=rgb("3a3a3a"))
        cv.disc(cv.w * 5 // 6, cv.h * 2 // 3, 1, rgb("e83a2a"))

    m = Model()
    m.box((0.5, 0, 3), (15.5, 14, 15.5), "lab_white")
    m.box((0, 14, 2.5), (16, 15, 16), "formica")
    m.box((1.5, 15, 5), (11.5, 25, 15), "med_cream", north=Pic("steril_door", door))
    m.box((12.5, 15, 7), (15, 16.5, 13), "chrome")                           # the bix (стерилизационная коробка)
    m.box((12.75, 16.5, 7.5), (14.75, 18, 12.5), "steel")
    return m


def instrument_table():
    """The instrument table (инструментальный столик): chrome tubes, two glass shelves, instruments on a sheet, a bix."""
    m = Model()
    for x in (1, 14):
        for z in (3, 13):
            m.box((x, 0, z), (x + 1, 14, z + 1), "chrome", up=None)
    m.box((2, 4, 4), (14, 4.5, 13), "glass_shelf")
    m.box((1, 14, 3), (15, 14.5, 14), "glass_shelf")
    m.box((2, 14.5, 4), (14, 15, 13), "linen", down=None)
    for x in (3.5, 5.5, 7.5):
        m.box((x, 15, 5), (x + 0.75, 15.5, 11), "steel")
    m.box((9.5, 15, 7), (13.5, 18, 11), "chrome")
    m.box((3, 4.5, 6), (8, 6.5, 11), "enamel_white")                         # the kidney dish on the lower shelf
    return m


def height_meter():
    """The height meter (ростомер): a wooden platform, the post with its scale in centimetres, the sliding head bar."""
    def scale(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("f0e8d0"))
        for y in range(0, cv.h, 2):
            cv.hline(0, 2 if y % 10 else cv.w - 1, y, rgb("2a2a2a"))

    m = Model()
    m.box((1, 0, 2), (15, 2, 15), "oak")
    m.box((6, 2, 13), (10, 40, 15), "oak", north=Pic("height_scale", scale))
    m.box((7, 30, 4), (9, 31, 13), "lightwood")                              # the head bar
    m.box((6.5, 29.5, 12), (9.5, 31.5, 13.5), "brass")
    m.box((2, 2, 4), (14, 3, 7), "rubber_black")                             # the heel stop
    return m


def wheelchair():
    """A wheelchair: the black seat and back on a chrome frame, the two big wheels with their push rims, castors in front,
    the foot plates."""
    m = Model()
    for x in (1, 13.5):
        m.box((x, 0, 6), (x + 1.5, 12, 16), "tyre", rot=None)                 # the big wheels
        m.box((x + (1.5 if x < 5 else -0.5), 2, 8), (x + (2 if x < 5 else 0), 10, 14), "chrome")
    for x in (3.5, 11):
        m.box((x, 0, 1.5), (x + 1.5, 2, 3.5), "rubber_black")                 # the castors
        m.box((x + 0.25, 2, 2), (x + 1.25, 8, 3), "chrome")
    m.box((3.5, 8, 3), (12.5, 9, 13), "leatherette")                          # the seat
    m.box((3.5, 9, 12.5), (12.5, 20, 13.5), "leatherette")                    # the back
    for x in (3, 12.5):
        m.box((x, 9, 13), (x + 0.5, 22, 13.5), "chrome")
        m.box((x, 13, 4), (x + 0.5, 13.5, 13), "chrome")                      # the arm rests
    m.box((3.5, 3, 0.5), (12.5, 3.5, 2.5), "chrome")                            # the foot plates
    return m


def iv_stand():
    """A drip stand (капельница): a five-footed base on castors, the chrome pole, the hook with a glass bottle upside
    down and its tube."""
    m = Model()
    m.box((3, 0, 7.5), (13, 1, 8.5), "chrome")
    m.box((7.5, 0.25, 3), (8.5, 1.25, 13), "chrome")
    m.box((7.5, 1.25, 7.5), (8.5, 29, 8.5), "chrome")
    m.box((4, 29, 7.5), (12, 30, 8.5), "chrome")
    m.box((3, 21, 6.5), (6, 28, 9.5), "glass")                                # the bottle
    m.box((4, 20, 7.5), (5, 21, 8.5), "rubber")
    m.box((4.25, 8, 7.75), (4.75, 20, 8.25), "plastic_white")                 # the tube
    return m


# ---------------------------------------------------------------- what the polyclinic's rooms were missing (Dmitriy's walk)

def coat_stand(coat=None):
    """The hospital's free-standing coat stand: a white enamelled steel pole on a cross of four feet with black caps, four
    hooks splayed up at the top and four short ones curled under them; with `coat` a doctor's white coat (or a visitor's)
    hangs from one hook."""
    def build():
        m = Model()
        m.box((1.5, 0, 7.5), (14.5, 1, 8.5), "stand_white")
        m.box((7.5, 0.25, 1.5), (8.5, 1.25, 14.5), "stand_white")
        for x0, z0 in ((1, 7.25), (14.5, 7.25)):
            m.box((x0, 0, z0), (x0 + 0.5, 1.5, z0 + 1.5), "rubber_black")
        for x0, z0 in ((7.25, 1), (7.25, 14.5)):
            m.box((x0, 0, z0), (x0 + 1.5, 1.5, z0 + 0.5), "rubber_black")
        m.box((7.5, 1.25, 7.5), (8.5, 29, 8.5), "stand_white")
        m.box((7.25, 29, 7.25), (8.75, 29.5, 8.75), "stand_white")
        for ang, axis, f, t in ((45, "z", (8.5, 27, 7.75), (12, 27.5, 8.25)), (-45, "z", (4, 27, 7.75), (7.5, 27.5, 8.25)),
                                (45, "x", (7.75, 27, 4), (8.25, 27.5, 7.5)), (-45, "x", (7.75, 27, 8.5), (8.25, 27.5, 12))):
            m.box(f, t, "stand_white", rot={"origin": [8, 27.25, 8], "axis": axis, "angle": ang})
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):              # the short hooks, curled up at the tips
            x0 = 8.5 if dx > 0 else 5.5 if dx < 0 else 7.75
            z0 = 8.5 if dz > 0 else 5.5 if dz < 0 else 7.75
            m.box((x0, 24, z0), (x0 + (2 if dx else 0.5), 24.5, z0 + (2 if dz else 0.5)), "stand_white")
            tx = 10.25 if dx > 0 else 5.5 if dx < 0 else 7.75
            tz = 10.25 if dz > 0 else 5.5 if dz < 0 else 7.75
            m.box((tx, 24.5, tz), (tx + 0.5, 25.5, tz + 0.5), "stand_white")
        if coat:
            def front(cv, W, H):
                base = rgb("eeeee8")
                cv.rect(0, 0, cv.w, cv.h, base)
                cv.vline(cv.w // 2, 0, cv.h, shade(base, 0.8))                # the opening
                for y in range(3, cv.h - 4, 5):
                    cv.set(cv.w // 2 + 1, y, rgb("d8d8d0"))                   # the buttons
                for x0 in (1, cv.w // 2 + 2):
                    cv.outline(x0, cv.h * 2 // 3, x0 + cv.w // 2 - 4, cv.h * 2 // 3 + 5, shade(base, 0.82))  # pockets
                cv.hline(1, cv.w // 2 - 2, 5, shade(base, 0.85))              # the breast pocket's pen
                cv.set(3, 4, BLUE)

            m.box((9.25, 11, 4), (13.75, 24, 9.5), coat, north=Pic("coat_front", front))  # hung off the east hook
            m.box((9.75, 22.5, 3.5), (13.25, 24.5, 4), coat)              # the collar
            m.box((10.5, 24, 5.5), (12.5, 25, 7.5), coat)
        return m
    return build


def _screen_mis(cv, W, H):
    cv.rect(0, 0, cv.w, cv.h, rgb("e8eef4"))
    cv.rect(0, 0, cv.w, 3, BLUE)
    for k in range(5):
        cv.hline(2, 2 + 6 + (k * 7) % 11, 5 + k * 3, rgb("6a7a8a"))
        cv.hline(cv.w // 2 + 1, cv.w - 3, 5 + k * 3, rgb("b8c8d8"))


def doctor_desk(modern):
    """The doctor's desk: a grey laminated top with a dark edge on a tubular frame, a pedestal of three drawers on the
    right, the modesty panel behind; on it the stack of out-patient cards, a pen pot, and either the monitor and keyboard
    of the medical information system or, on the older one, the glass sheet, papers, the green table lamp, the telephone."""
    def build():
        def drawers(cv, W, H):
            cv.rect(0, 0, cv.w, cv.h, rgb("d4d2ca"))
            for k in range(3):
                y0 = k * cv.h // 3
                cv.bevel(1, y0 + 1, cv.w - 1, y0 + cv.h // 3 - 1, rgb("e4e2da"), rgb("a8a69e"))
                cv.hline(cv.w // 2 - 3, cv.w // 2 + 3, y0 + 3, rgb("6a6a66"))

        top = "lam_grey" if modern else "lightwood"
        m = Model()
        m.box((0, 12, 1.5), (16, 13, 16), top)
        m.box((0, 11.5, 1), (16, 12.5, 1.5), "lam_edge" if modern else "oak")
        m.box((9.5, 0, 2.5), (15.5, 11.5, 15.5), top, north=Pic("ddesk_drw" + ("" if modern else "_o"), drawers))
        for z in (2.5, 14.5):
            m.box((0.5, 0, z), (1.5, 11.5, z + 1), "chrome" if modern else "oak")
        m.box((1.5, 3, 14.75), (9.5, 11.5, 15.25), top)                   # the modesty panel
        m.box((9, 13, 3), (13.5, 15, 8.5), "med_card")                       # the stack of cards
        m.box((9, 15, 3), (13.5, 15.5, 8.5), "paper")
        m.box((14, 13, 3.5), (15.25, 15.5, 4.75), "plastic_black")           # the pen pot
        m.box((14.25, 15.5, 3.75), (14.75, 17, 4.25), "plastic_blue")
        if modern:
            m.box((2, 13, 10.5), (7, 13.5, 13.5), "plastic_black")
            m.box((4, 13.5, 12), (5, 16, 12.75), "plastic_black")
            m.box((0.5, 15, 11), (8.5, 21, 11.75), "plastic_black", north=Pic("ddesk_scr", _screen_mis))
            m.box((1.5, 13, 4), (8, 13.5, 7), "plastic_grey")                # the keyboard
            m.box((9.5, 13, 9.5), (11, 13.5, 11.5), "plastic_grey")           # the mouse
        else:
            m.box((0.5, 13, 2), (8.5, 13.5, 15.5), "glass", down=None)        # the glass sheet over the desk
            m.box((1.5, 13.5, 3), (6, 14, 8), "paper")
            m.box((5.5, 13.5, 12), (7.5, 14, 14), "brass")                   # the lamp
            m.box((6.25, 14, 12.75), (6.75, 18, 13.25), "brass")
            m.box((4.5, 18, 11), (8.5, 20, 14.5), "lampshade_green")
            m.box((1, 13.5, 9.5), (4.5, 15, 13), "phone_body")
        return m
    return build


def negatoscope():
    """The negatoscope on the wall: a white box with its milky panel lit from behind and two X-ray films clipped on it."""
    def films(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("f4f8fa"))
        for x0 in (2, cv.w // 2 + 1):
            cv.rect(x0, 2, x0 + cv.w // 2 - 3, cv.h - 2, rgb("1a2028"))
            cx = x0 + (cv.w // 2 - 3) // 2
            cv.rect(cx - 1, 4, cx + 1, cv.h - 4, rgb("c8d0d8"))              # a bone
            for y in range(6, cv.h - 5, 3):
                cv.hline(cx - 4, cx + 4, y, rgb("6a7480"))                   # ribs
        cv.outline(0, 0, cv.w, cv.h, rgb("c8ccd0"))

    m = Model()
    m.box((0.5, 3, 13.5), (15.5, 15, 16), "stand_white", north=Pic("negato", films))
    m.box((2, 2, 14), (14, 3, 16), "stand_white")
    m.box((12, 2.25, 13.5), (13.5, 2.75, 14), "plastic_black")
    return m


def med_trolley():
    """The procedure trolley: a chrome frame on castors, two steel shelves, on top a kidney dish, the bottles of
    antiseptic, a jar of cotton wool; below a bix and a box of syringes."""
    m = Model()
    for x in (1.5, 13.5):
        for z in (3, 13):
            m.box((x, 1.5, z), (x + 1, 15, z + 1), "chrome", up=None)
            m.box((x, 0, z), (x + 1, 1.5, z + 1), "rubber_black")
    m.box((1, 5, 2.5), (15, 5.5, 14.5), "steel")
    m.box((1, 15, 2.5), (15, 15.5, 14.5), "steel")
    m.box((2.5, 15.5, 4), (8, 16.5, 8), "enamel_white")
    m.box((10, 15.5, 4), (11.5, 19, 5.5), "glass_amber")
    m.box((12, 15.5, 4), (13.5, 18, 5.5), "bottle_green")
    m.box((9.5, 15.5, 9), (13, 19, 12.5), "glass")
    m.box((10, 16, 9.5), (12.5, 18, 12), "linen")
    m.box((3, 15.5, 10), (7.5, 16, 13), "linen")
    m.box((3, 5.5, 5), (8, 9.5, 11), "chrome")
    m.box((9.5, 5.5, 5), (13.5, 8, 12), "pill_box")
    return m


def linen_shelf():
    """The sister-housekeeper's shelving (стеллаж кастелянши): grey steel uprights and four shelves, two blocks high,
    stacks of folded white linen and gowns, cardboard boxes of supplies."""
    import random
    r = random.Random("linen")
    m = Model()
    for x in (0.5, 14.5):
        for z in (2, 14):
            m.box((x, 0, z), (x + 1, 30, z + 1), "iron_grey")
    for y in (1, 9, 17, 25):
        m.box((1, y, 2.5), (15, y + 0.5, 14.5), "iron_grey" if y != 25 else "steel")
    for y in (1.5, 9.5, 17.5, 25.5):
        x = 1.5
        while x < 13:
            w = r.choice([4, 4.5, 5])
            if x + w > 14.5:
                break
            if r.random() < 0.65:
                m.box((x, y, 4), (x + w, y + r.choice([3, 4, 5]), 13), r.choice(["linen", "linen", "towel", "laundry_white"]))
            else:
                m.box((x, y, 3.5), (x + w, y + r.choice([4, 5, 6]), 13.5), "cardboard")
            x += w + 0.5
    return m


def pharmacy_shelf():
    """The chemist's shelves behind the counter, two blocks high: white cabinets with drawers below, open shelves above
    packed with boxes of medicine in rows (blue, red, green and white packs), the green cross on the crown."""
    def packs(cv, W, H):
        import random
        r = random.Random("packs")
        cv.rect(0, 0, cv.w, cv.h, rgb("f0f0ea"))
        for y0 in range(0, cv.h, 8):
            x = 0
            while x < cv.w:
                w = r.choice([2, 3, 3, 4])
                c = r.choice([rgb("3a6ab0"), rgb("c83a3a"), rgb("3a9a5a"), rgb("f4f4f0"), rgb("e0a030"), rgb("7a4aa0")])
                cv.rect(x, y0 + 8 - r.choice([4, 5, 6]), x + w - 1, y0 + 7, c)
                x += w
            cv.hline(0, cv.w, y0 + 7, rgb("c8c8c0"))

    def drawers(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("f2f2ec"))
        for k in range(2):
            for j in range(4):
                x0, y0 = j * cv.w // 4, k * cv.h // 2
                cv.bevel(x0 + 1, y0 + 1, x0 + cv.w // 4 - 1, y0 + cv.h // 2 - 1, rgb("ffffff"), rgb("c0c0b8"))
                cv.hline(x0 + 2, x0 + 4, y0 + 3, rgb("8a8a86"))

    m = Model()
    m.box((0, 0, 4), (16, 10, 16), "lab_white", north=Pic("pharm_drw", drawers))
    m.box((0, 10, 3.5), (16, 10.5, 16), "formica")
    m.box((0, 10.5, 14.5), (16, 30, 16), "lab_white", north=Pic("pharm_packs", packs))
    for x in (0, 15.25):
        m.box((x, 10.5, 9), (x + 0.75, 30, 14.5), "lab_white")
    for y in (18, 25):
        m.box((0.75, y, 9), (15.25, y + 0.5, 14.5), "glass_shelf")
    import random
    r = random.Random("phshelf")
    for y in (10.5, 18.5, 25.5):
        x = 1
        while True:
            w = r.choice([1.5, 2, 2.5])
            if x + w > 15:
                break
            m.box((x, y, 11), (x + w, y + r.choice([2.5, 3, 3.5]), 14), r.choice(["pill_box", "plastic_blue", "plastic_red",
                                                                                   "glass_amber", "pill_box"]))
            x += w + 0.5
    m.box((0, 30, 9), (16, 32, 16), "lab_white")
    m.box((6.5, 30.5, 8.5), (9.5, 31.5, 9), "green_paint")                   # the green cross
    m.box((7.5, 30, 8.25), (8.5, 32, 8.75), "green_paint")
    return m


def safe():
    """A Soviet steel safe (несгораемый шкаф) on a low plinth: the green door with its key escutcheon and lever handle,
    the maker's plate."""
    def door(cv, W, H):
        cv.rect(0, 0, cv.w, cv.h, rgb("5a6a58"))
        cv.bevel(2, 2, cv.w - 2, cv.h - 2, rgb("6e7e6c"), rgb("3e4a3c"))
        cv.disc(cv.w * 3 // 4, cv.h // 2, 2, rgb("c8a848"), ring=rgb("2a2a2a"))
        cv.rect(cv.w // 2 - 3, 4, cv.w // 2 + 3, 6, rgb("c8a848"))

    m = Model()
    m.box((2, 0, 4), (14, 1, 15), "iron_black")
    m.box((2, 1, 4), (14, 15, 15), "safe_green", north=Pic("safe_door", door))
    m.box((4, 6, 3), (5, 10, 4), "chrome")
    m.box((4.25, 9.5, 3.25), (6.5, 10.25, 3.75), "chrome")
    return m


def make():
    textures()
    Furn("pub_coat_stand", "Напольная вешалка", "Coat stand", coat_stand(), color="WHITE", sound="METAL", solid=False)
    Furn("pub_coat_stand_coat", "Напольная вешалка с халатом", "Coat stand with a white coat", coat_stand("cloak_white"),
         color="WHITE", sound="METAL", solid=False)
    Furn("pub_doctor_desk", "Стол врача", "Doctor's desk", doctor_desk(True), color="LIGHT_GRAY")
    Furn("pub_doctor_desk_old", "Стол врача (старый)", "Doctor's desk (old)", doctor_desk(False), color="OAK_TAN")
    Furn("pub_negatoscope", "Негатоскоп", "Negatoscope", lambda: negatoscope(), mount="WALL", color="WHITE", light=8,
         solid=False)
    Furn("pub_med_trolley", "Процедурный столик", "Procedure trolley", lambda: med_trolley(), color="WHITE", sound="METAL")
    Furn("pub_linen_shelf", "Стеллаж с бельём", "Linen shelving", lambda: linen_shelf(), color="GRAY", sound="METAL")
    Furn("pub_pharmacy_shelf", "Аптечный стеллаж", "Chemist's shelving", lambda: pharmacy_shelf(), color="WHITE")
    Furn("pub_safe", "Сейф", "Safe", lambda: safe(), color="GREEN", sound="METAL")
    for n in range(1, 7):
        Furn(f"pub_registry_window_{n}", "Окно регистратуры", "Registry window", registry_window(n), color="LIGHT_BLUE",
             sound="GLASS")
    Furn("pub_registry_glass", "Стеклянная перегородка регистратуры", "Registry glass partition", lambda: registry_glass(),
         color="LIGHT_BLUE", sound="GLASS")
    Furn("pub_cloak_counter", "Стойка гардероба", "Cloakroom counter", lambda: cloak_counter(), color="BROWN")
    Furn("pub_pharmacy_showcase", "Витрина аптечного пункта", "Chemist's showcase", lambda: pharmacy_showcase(),
         color="GREEN", sound="GLASS")
    Furn("pub_queue_terminal", "Терминал электронной очереди", "Queue terminal", lambda: queue_terminal(), color="GRAY",
         light=5, sound="METAL")
    Furn("pub_doctor_schedule", "Расписание приёма врачей", "Doctors' timetable", lambda: doctor_schedule(), mount="WALL",
         color="BLUE", solid=False)
    Furn("pub_shoe_cover_machine", "Автомат бахил", "Shoe-cover machine", lambda: shoe_cover_machine(), color="BLUE",
         sound="METAL")
    Furn("pub_dental_chair", "Стоматологическое кресло", "Dentist's chair", lambda: dental_chair(), seat=9,
         color="LIGHT_BLUE")
    Furn("pub_dental_unit", "Стоматологическая установка", "Dental unit", lambda: dental_unit(), color="WHITE",
         sound="METAL")
    Furn("pub_xray", "Рентгеновский аппарат", "X-ray machine", lambda: xray(), color="GRAY", sound="METAL")
    Furn("pub_lab_table", "Лабораторный стол", "Laboratory bench", lambda: lab_table(), color="WHITE")
    Furn("pub_ent_unit", "ЛОР-комбайн", "ENT unit", lambda: ent_unit(), color="WHITE", sound="METAL")
    Furn("pub_physio_unit", "Аппарат физиотерапии", "Physiotherapy set", lambda: physio_unit(), color="GRAY",
         sound="METAL")
    Furn("pub_sterilizer", "Сухожаровой шкаф", "Steriliser", lambda: sterilizer(), color="WHITE", sound="METAL")
    Furn("pub_instrument_table", "Инструментальный столик", "Instrument table", lambda: instrument_table(),
         color="WHITE", sound="METAL")
    Furn("pub_height_meter", "Ростомер", "Height meter", lambda: height_meter(), color="OAK_TAN")
    Furn("pub_wheelchair", "Кресло-коляска", "Wheelchair", lambda: wheelchair(), seat=9, color="BLACK", sound="METAL")
    Furn("pub_iv_stand", "Капельница", "Drip stand", lambda: iv_stand(), color="WHITE", sound="METAL", solid=False)
