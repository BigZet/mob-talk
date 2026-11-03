"""Clothes of the МФЦ staff (track «чиновники», the city's one-window office): the operator at the window (women and men), the guard at
the door and the hall's administrator. Human villagers get 64x64 player-skin clothing (clothing/female|male|neutral/<job>), the
vanilla villager and zombie villager a profession overlay. Pixel art with folds and a little cloth grain, after the MCA sets.

usage: python3 tools/mfc/outfits.py   (from the repository root)
"""
import os
import random

from PIL import Image


HUMAN = os.path.join('src', 'client', 'resources', 'assets', 'mobtalk', 'human', 'clothing')
VANILLA = os.path.join('src', 'main', 'resources', 'assets', 'mobtalk', 'textures', 'entity')


def faces(u, v, w, h, d):
    return {'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d), 'right': (u, v + d, d, h), 'front': (u + d, v + d, w, h),
            'left': (u + d + w, v + d, d, h), 'back': (u + 2 * d + w, v + d, w, h)}


def shade(c, k):
    return tuple(max(0, min(255, int(ch * k))) for ch in c[:3])


SIDE = {'top': 1.08, 'front': 1.0, 'back': 0.92, 'right': 0.9, 'left': 0.9, 'bottom': 0.8}


def paint(img, box, fn, seed):
    """Paints each face of a box with fn(face, x, y, w, h) -> colour or None, then the face's light and a little cloth grain."""
    rnd = random.Random(seed)
    u, v, w, h, d = box
    for face, (x0, y0, fw, fh) in faces(u, v, w, h, d).items():
        for y in range(fh):
            for x in range(fw):
                c = fn(face, x, y, fw, fh)
                if c is None:
                    continue
                if len(c) == 3:
                    c = shade(c, SIDE[face] * (1 + rnd.uniform(-0.035, 0.035)))
                img.putpixel((x0 + x, y0 + y), c[:3] + (255,))


def flat(c):
    """A colour painted as is (no light, no grain): badges, letters, stitching."""
    return c + (255,)


NAVY, NAVY_D = (30, 44, 86), (20, 30, 60)
BLUE, BLUE_L = (40, 96, 180), (90, 146, 220)
ORANGE, WHITE, SHIRT = (236, 132, 36), (246, 246, 244), (236, 240, 246)
BLACK, BLACK_L = (28, 28, 32), (52, 54, 60)
SHOES, TIGHTS = (24, 22, 24), (70, 56, 50)
BADGE = (250, 250, 250)

P = {'body': (16, 16, 8, 12, 4), 'rarm': (40, 16, 4, 12, 4), 'larm': (32, 48, 4, 12, 4), 'rleg': (0, 16, 4, 12, 4), 'lleg': (16, 48, 4, 12, 4),
     'jacket': (16, 32, 8, 12, 4), 'rsleeve': (40, 32, 4, 12, 4), 'lsleeve': (48, 48, 4, 12, 4)}


def fold(x, y, w, h):
    """Soft vertical folds of a garment: a little darker in two columns, lighter at the top."""
    k = 1.0
    if x in (1, w - 2):
        k *= 0.93
    if y == 0:
        k *= 1.06
    return k


def operator_woman(vest, scarf, skirt, blouse=SHIRT, sleeves='short'):
    """The window operator: a fitted waistcoat over a white blouse, a knotted neckerchief, the name badge on a lanyard, a pencil
    skirt to the knee, tights and court shoes. sleeves='short' gives blouse sleeves to the elbow, 'long' a jacket's."""
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))

    def body(face, x, y, w, h):
        if face == 'top':
            return vest
        if face == 'bottom':
            return skirt
        if y >= 9:
            return shade(skirt, 0.85 if y == 9 else 1) if face != 'front' or x != 4 else shade(skirt, 0.8)  # the slit
        if face == 'front':
            if y <= 1 and 2 <= x <= 5:
                return scarf if y == 1 or x in (3, 4) else blouse
            if y == 2 and x in (3, 4):
                return shade(scarf, 0.8)  # the knot
            if 3 <= y <= 4 and x == 4:
                return scarf  # the tail
            if y <= 6 and 3 <= x <= 4:
                return blouse  # the V of the waistcoat
            if y == 4 and x == 1:
                return flat(BADGE)
            if y == 5 and x == 1:
                return flat(BLUE)
            if y <= 3 and x == 2:
                return BLUE_L  # the lanyard
            if y in (5, 7) and x == 5:
                return (200, 204, 214)  # buttons
            if y == 8:
                return shade(vest, 0.8)
            return shade(vest, fold(x, y, w, h))
        if face == 'back' and y in (3, 4) and 2 <= x <= 5:
            return shade(vest, 0.88)  # the back's belt
        return shade(vest, fold(x, y, w, h))

    paint(img, P['body'], body, 1)

    def arm(face, x, y, w, h):
        if face == 'bottom':
            return None
        if sleeves == 'short':
            if y <= 4:
                return blouse if face != 'top' else blouse
            if y == 5:
                return shade(blouse, 0.88)
            return None
        if y <= 9:
            return shade(vest, 0.95 if face in ('left', 'right') else 1)
        if y == 10:
            return blouse
        return None

    paint(img, P['rarm'], arm, 2)
    paint(img, P['larm'], arm, 3)

    def leg(face, x, y, w, h):
        if face == 'top':
            return skirt
        if face == 'bottom':
            return SHOES
        if y <= 3:
            return skirt
        if y >= 11:
            return SHOES
        if y == 10:
            return SHOES if face != 'front' or x in (0, 3) else shade(TIGHTS, 0.9)
        return TIGHTS

    paint(img, P['rleg'], leg, 4)
    paint(img, P['lleg'], leg, 5)
    return img


def operator_man(vest, tie, trousers, shirt=SHIRT):
    """The male operator: a white shirt with rolled cuffs, a waistcoat, a blue tie with an orange stripe, the badge and dark
    trousers with a crease."""
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))

    def body(face, x, y, w, h):
        if face == 'top':
            return vest
        if face == 'bottom':
            return trousers
        if y == 11:
            return (40, 34, 30)  # the belt
        if y >= 9:
            return trousers if not (face == 'front' and x == 3 and y == 10) else shade(trousers, 0.8)
        if face == 'front':
            if y <= 1 and 2 <= x <= 5:
                return shirt
            if 3 <= x <= 4 and 1 <= y <= 7:
                return (ORANGE if y == 4 else tie) if x == 3 or y > 1 else shirt
            if y <= 6 and x in (2, 5) and y >= 2:
                return shirt
            if y == 3 and x == 1:
                return flat(BADGE)
            if y == 4 and x == 1:
                return flat(BLUE)
            if y in (6, 8) and x == 5:
                return (200, 204, 214)
            return shade(vest, fold(x, y, w, h))
        if face == 'back':
            return shade(shirt, 0.92) if 2 <= x <= 5 and y <= 8 else vest  # the waistcoat's satin back shows the shirt's colour
        return vest

    paint(img, P['body'], body, 6)

    def arm(face, x, y, w, h):
        if face == 'bottom':
            return None
        if y <= 6:
            return shirt
        if y == 7:
            return shade(shirt, 0.86)  # the rolled cuff
        return None

    paint(img, P['rarm'], arm, 7)
    paint(img, P['larm'], arm, 8)

    def leg(face, x, y, w, h):
        if face == 'top':
            return trousers
        if face == 'bottom':
            return SHOES
        if y >= 10:
            return SHOES if not (y == 10 and face == 'front' and x == 1) else (70, 66, 62)
        if face == 'front' and x == 1:
            return shade(trousers, 1.15)
        return trousers

    paint(img, P['rleg'], leg, 9)
    paint(img, P['lleg'], leg, 10)
    return img


def guard(skirt=False):
    """The guard: a black uniform shirt with shoulder straps, a chevron on the arm and reflective bands across the back, a belt with a
    radio and a baton, black cargo trousers (or a skirt) and boots."""
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))

    def body(face, x, y, w, h):
        if face == 'top':
            return BLACK_L if x in (0, 1, 6, 7) else BLACK  # the shoulder straps
        if face == 'bottom':
            return BLACK
        if y == 9:
            return (60, 50, 40) if not (face == 'front' and x in (3, 4)) else flat((180, 180, 186))  # belt and buckle
        if y >= 10:
            return BLACK
        if face == 'front':
            if y <= 1 and 2 <= x <= 5:
                return BLACK_L if y == 0 else BLACK
            if x == 4 and 1 <= y <= 8:
                return shade(BLACK, 1.4)  # the button placket
            if y in (2, 3) and x in (1, 2, 6):
                return shade(BLACK, 1.35)  # pocket flaps
            if y == 3 and x == 6:
                return flat(BADGE)  # the badge
            return BLACK
        if face == 'right' and y == 9:
            return flat((30, 30, 30))
        return BLACK

    paint(img, P['body'], body, 11)
    # The back: two reflective grey bands with a blue one between them, as on a security jacket (eight pixels hold no word).
    u, v, w, h, d = P['body']
    bx, by = u + 2 * d + w, v + d
    for x in range(8):
        img.putpixel((bx + x, by + 2), (196, 200, 206, 255))
        img.putpixel((bx + x, by + 3), (40, 80, 160, 255))
        img.putpixel((bx + x, by + 4), (196, 200, 206, 255))

    def arm(face, x, y, w, h):
        if face == 'bottom':
            return None
        if y <= 9:
            if face == 'right' and 1 <= y <= 3 and 1 <= x <= 2:
                return flat((200, 170, 40) if y == 1 else (40, 80, 160))  # the chevron
            return BLACK
        if y == 10:
            return BLACK_L
        return None

    paint(img, P['rarm'], arm, 12)
    paint(img, P['larm'], arm, 13)

    def leg(face, x, y, w, h):
        if face == 'top':
            return BLACK
        if face == 'bottom':
            return SHOES
        if skirt:
            if y <= 3:
                return BLACK
            if y >= 9:
                return SHOES
            return TIGHTS
        if y >= 9:
            return SHOES if y > 9 else (44, 42, 44)  # boots
        if face in ('left', 'right') and 4 <= y <= 6:
            return shade(BLACK, 1.35)  # the cargo pocket
        return BLACK

    paint(img, P['rleg'], leg, 14)
    paint(img, P['lleg'], leg, 15)
    # The radio on the belt (left hip) and the baton (right side) on the jacket layer.
    jx, jy = P['jacket'][0] + 4, P['jacket'][1] + 4
    img.putpixel((jx + 6, jy + 8), (20, 20, 20, 255))
    img.putpixel((jx + 6, jy + 9), (20, 20, 20, 255))
    img.putpixel((jx + 7, jy + 7), (60, 60, 60, 255))
    return img


def administrator(blazer, scarf, trousers, woman=True):
    """The hall administrator who meets visitors: a blue blazer, an orange scarf (or tie), the badge «Администратор» and a tablet
    held to the chest by the right arm."""
    img = operator_woman(blazer, scarf, trousers, sleeves='long') if woman else operator_man(blazer, scarf, trousers)

    def arm(face, x, y, w, h):
        if face == 'bottom':
            return None
        if face == 'left' and 4 <= y <= 9:
            return flat((40, 44, 50)) if y not in (4, 9) else flat((20, 20, 24))  # the tablet against the inner side
        if y <= 9:
            return blazer
        if y == 10:
            return SHIRT
        return None

    paint(img, P['rarm'], arm, 16)
    return img


def vanilla(main, accent, lower, collar=SHIRT, guard_=False):
    """The vanilla villager's overlay: the robe in the uniform's colour, the collar or scarf, the badge, the lower part, arms."""
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    V = {'body': (16, 20, 8, 12, 6), 'robe': (0, 38, 8, 20, 6), 'arms': (44, 22, 4, 8, 4), 'armsmid': (40, 38, 8, 4, 4)}

    def body(face, x, y, w, h):
        if face == 'bottom':
            return None
        if face == 'front' and y <= 1 and 2 <= x <= 5:
            return collar if not guard_ else BLACK_L
        if face == 'front' and y == 2 and x in (3, 4) and not guard_:
            return accent
        return main

    paint(img, V['body'], body, 21)

    def robe(face, x, y, w, h):
        if face == 'bottom':
            return None
        if face == 'top':
            return main
        if face == 'front':
            if y <= 1 and 2 <= x <= 5:
                return collar if not guard_ else BLACK_L
            if 2 <= y <= 3 and x in (3, 4) and not guard_:
                return accent
            if y == 4 and x == 1:
                return flat(BADGE)
            if y == 11:
                return (60, 50, 40) if guard_ else shade(main, 0.75)
        if y >= 12:
            return lower
        return shade(main, fold(x, y, w, h))

    paint(img, V['robe'], robe, 22)

    def arms(face, x, y, w, h):
        if face == 'bottom':
            return None
        if guard_ and face == 'front' and y in (1, 2) and x in (1, 2):
            return flat((40, 80, 160))
        return main if not (y >= 6 and face == 'front') else (SHIRT if not guard_ else BLACK_L)

    paint(img, V['arms'], arms, 23)
    paint(img, V['armsmid'], lambda f, x, y, w, h: main, 24)
    return img


HUMAN_SETS = {
    ('female', 'mfc_operator'): [operator_woman(NAVY, ORANGE, NAVY_D), operator_woman(BLUE, ORANGE, NAVY), operator_woman(NAVY, BLUE_L, NAVY_D, sleeves='long')],
    ('male', 'mfc_operator'): [operator_man(NAVY, BLUE, NAVY_D), operator_man(BLUE, NAVY, (40, 42, 50))],
    ('male', 'mfc_guard'): [guard()],
    ('female', 'mfc_guard'): [guard(skirt=True)],
    ('female', 'mfc_admin'): [administrator(BLUE, ORANGE, NAVY_D)],
    ('male', 'mfc_admin'): [administrator(BLUE, ORANGE, NAVY_D, woman=False)],
}
VANILLA_SETS = {'mfc_operator': vanilla(NAVY, ORANGE, NAVY_D), 'mfc_guard': vanilla(BLACK, BLACK_L, BLACK, guard_=True),
                'mfc_admin': vanilla(BLUE, ORANGE, NAVY_D)}


def write():
    for (sex, job), imgs in HUMAN_SETS.items():
        folder = os.path.join(HUMAN, sex, job)
        os.makedirs(folder, exist_ok=True)
        for i, img in enumerate(imgs):
            img.save(os.path.join(folder, f'{i}.png'))
    for kind in ('villager', 'zombie_villager'):
        folder = os.path.join(VANILLA, kind, 'profession')
        os.makedirs(folder, exist_ok=True)
        for job, img in VANILLA_SETS.items():
            img.save(os.path.join(folder, job + '.png'))


if __name__ == '__main__':
    write()
    print('ok')
