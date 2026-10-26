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


def make():
    textures()
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
