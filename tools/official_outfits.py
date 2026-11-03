"""Generates the officials' clothes (track «чиновники»): human villagers (64x64 player-skin layout, folder per profession under
human/clothing/neutral) and the vanilla villager's profession overlays (villager and zombie villager layout).

usage: python3 tools/official_outfits.py <repo root>
"""
import sys, os
from PIL import Image

ROOT = sys.argv[1]
HUMAN = os.path.join(ROOT, 'src/client/resources/assets/mobtalk/human/clothing/neutral')
VANILLA = os.path.join(ROOT, 'src/main/resources/assets/mobtalk/textures/entity')

def faces(u, v, w, h, d):
    return {'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d), 'right': (u, v + d, d, h), 'front': (u + d, v + d, w, h),
            'left': (u + d + w, v + d, d, h), 'back': (u + 2 * d + w, v + d, w, h)}

def paint(img, box, fn):
    u, v, w, h, d = box
    for face, (x0, y0, fw, fh) in faces(u, v, w, h, d).items():
        for y in range(fh):
            for x in range(fw):
                c = fn(face, x, y, fw, fh)
                if c is not None: img.putpixel((x0 + x, y0 + y), c + (255,) if len(c) == 3 else c)

def shade(c, k): return tuple(max(0, min(255, int(ch * k))) for ch in c)

GOLD, SHIRT, SHOES = (222, 184, 62), (244, 244, 240), (24, 20, 18)

# ---- Human villagers: the player-skin layout ----
P = {'body': (16, 16, 8, 12, 4), 'rarm': (40, 16, 4, 12, 4), 'larm': (32, 48, 4, 12, 4), 'rleg': (0, 16, 4, 12, 4), 'lleg': (16, 48, 4, 12, 4)}

def human_suit(jacket, trousers, tie, watch=True, pin=True, folder=False):
    """A two-piece suit with a long tie down to the belt, a white shirt collar, buttons, a gold watch on the left wrist and a lapel
    pin; folder=True gives the inspector a grey cardboard folder under the right arm (a patch on the arm's inner side)."""
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    def body(face, x, y, w, h):
        if face == 'top': return jacket
        if face == 'bottom': return trousers
        if face == 'front':
            if 3 <= x <= 4 and 1 <= y <= 8: return tie if y < 8 else shade(tie, 0.8)  # long tie, its tip darker
            if y <= 1 and 2 <= x <= 5: return SHIRT  # collar
            if 2 <= y <= 4 and x in (2, 5): return SHIRT  # shirt in the V
            if 2 <= y <= 7 and x in (1, 6): return shade(jacket, 1.3)  # lapels
            if pin and y == 3 and x == 1: return GOLD  # lapel pin
            if y in (9, 10) and x == 5: return GOLD  # buttons
            if y == 11: return shade(jacket, 0.65)  # belt line
            return jacket
        if face == 'back': return jacket if y < 11 else shade(jacket, 0.65)
        return jacket if y < 11 else shade(jacket, 0.65)
    paint(img, P['body'], body)
    def arm(left):
        def fn(face, x, y, w, h):
            if face == 'top': return jacket
            if face == 'bottom': return None  # the hand
            if y <= 9: return jacket if y < 9 else shade(jacket, 0.8)
            if y == 10:
                if left and watch and face in ('front', 'left', 'right', 'back'): return GOLD if face != 'back' else shade(GOLD, 0.7)
                return SHIRT  # cuff
            if folder and not left and face == 'left' and 3 <= y <= 9: return (150, 140, 120)
            return None
        return fn
    paint(img, P['rarm'], arm(False)); paint(img, P['larm'], arm(True))
    def leg(face, x, y, w, h):
        if face == 'top': return trousers
        if face == 'bottom': return SHOES
        if y >= 10: return SHOES if not (y == 10 and face == 'front' and x == 1) else (70, 65, 60)
        if face == 'front' and x == 1: return shade(trousers, 1.15)  # crease
        return trousers
    paint(img, P['rleg'], leg); paint(img, P['lleg'], leg)
    return img

def human_clerk(cardigan, skirt, blouse):
    """The clerk: a buttoned cardigan over a light blouse, a dark skirt to the knee, the badge on the chest."""
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    def body(face, x, y, w, h):
        if face == 'top': return cardigan
        if face == 'bottom': return skirt
        if face == 'front':
            if y <= 2 and 2 <= x <= 5: return blouse
            if 3 <= x <= 4 and y <= 8: return blouse if y <= 6 else cardigan
            if y in (4, 6, 8) and x == 4: return (200, 200, 205)  # buttons
            if y == 3 and x == 1: return (60, 110, 190)  # badge
            if y >= 9: return skirt
            return cardigan
        return cardigan if y < 9 else skirt
    paint(img, P['body'], body)
    def arm(face, x, y, w, h):
        if face == 'top': return cardigan
        if face == 'bottom': return None
        if y <= 9: return cardigan
        return None
    paint(img, P['rarm'], arm); paint(img, P['larm'], arm)
    def leg(face, x, y, w, h):
        if face == 'top': return skirt
        if face == 'bottom': return (40, 30, 30)
        if y <= 4: return skirt  # skirt to the knee, stockings below
        if y >= 10: return (40, 30, 30)
        return None
    paint(img, P['rleg'], leg); paint(img, P['lleg'], leg)
    return img

human = {
    'official_head': [human_suit((30, 30, 36), (26, 26, 30), (150, 24, 34)), human_suit((28, 38, 70), (24, 32, 60), (120, 26, 40)),
                      human_suit((74, 74, 80), (60, 60, 66), (40, 60, 120))],
    'inspector': [human_suit((96, 82, 60), (80, 68, 50), (60, 70, 40), watch=False, pin=False, folder=True),
                  human_suit((70, 78, 66), (58, 64, 54), (110, 40, 40), watch=False, pin=False, folder=True),
                  human_suit((84, 84, 92), (70, 70, 78), (50, 50, 60), watch=False, pin=False, folder=True)],
    'clerk': [human_clerk((120, 60, 70), (40, 40, 48), (236, 232, 222)), human_clerk((70, 90, 120), (36, 36, 44), (240, 240, 236)),
              human_clerk((110, 100, 80), (48, 40, 36), (232, 226, 210))],
}
for name, imgs in human.items():
    os.makedirs(os.path.join(HUMAN, name), exist_ok=True)
    for i, img in enumerate(imgs): img.save(os.path.join(HUMAN, name, f'{i}.png'))

# ---- Vanilla villager: body 16,20 (8x12x6), robe 0,38 (8x20x6), crossed arms 44,22 (4x8x4) and 40,38 (8x4x4) ----
V = {'body': (16, 20, 8, 12, 6), 'robe': (0, 38, 8, 20, 6), 'arms': (44, 22, 4, 8, 4), 'armsmid': (40, 38, 8, 4, 4)}

def vanilla_suit(jacket, trousers, tie, watch=True):
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    def body(face, x, y, w, h):
        if face == 'top': return jacket
        if face == 'bottom': return None
        if face == 'front':
            if 3 <= x <= 4 and 1 <= y <= 7: return tie
            if y <= 1 and 2 <= x <= 5: return SHIRT
            if 2 <= y <= 3 and x in (2, 5): return SHIRT
            return jacket
        return jacket
    paint(img, V['body'], body)
    def robe(face, x, y, w, h):
        if face == 'top': return jacket
        if face == 'bottom': return None
        if face == 'front':
            if 3 <= x <= 4 and 1 <= y <= 9: return tie if y < 9 else shade(tie, 0.8)
            if y <= 1 and 2 <= x <= 5: return SHIRT
            if 2 <= y <= 3 and x in (2, 5): return SHIRT
            if 2 <= y <= 8 and x in (1, 6): return shade(jacket, 1.3)
            if y in (10, 12) and x == 5: return GOLD
            if y == 13: return shade(jacket, 0.6)  # belt
            if y >= 14: return trousers if not (x in (3, 4) and y < 19) else shade(trousers, 0.85)
            return jacket
        if y == 13: return shade(jacket, 0.6)
        return jacket if y < 13 else trousers
    paint(img, V['robe'], robe)
    def arms(face, x, y, w, h):
        if face == 'top': return jacket
        if face == 'bottom': return None
        if y >= 6 and face == 'front': return GOLD if watch and y == 6 else SHIRT
        return jacket
    paint(img, V['arms'], arms)
    def mid(face, x, y, w, h):
        if face in ('top', 'bottom'): return jacket
        return jacket
    paint(img, V['armsmid'], mid)
    return img

def vanilla_clerk(cardigan, skirt, blouse):
    img = vanilla_suit(cardigan, skirt, blouse, watch=False)
    return img

vanilla = {'official_head': vanilla_suit((30, 30, 36), (26, 26, 30), (150, 24, 34)),
           'inspector': vanilla_suit((96, 82, 60), (80, 68, 50), (60, 70, 40), watch=False),
           'clerk': vanilla_clerk((120, 60, 70), (40, 40, 48), (236, 232, 222))}
for kind in ('villager', 'zombie_villager'):
    folder = os.path.join(VANILLA, kind, 'profession')
    os.makedirs(folder, exist_ok=True)
    for name, img in vanilla.items(): img.save(os.path.join(folder, f'{name}.png'))
print('ok')
