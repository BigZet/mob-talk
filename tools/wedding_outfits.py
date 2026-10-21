"""Generates Mob Talk's wedding outfits (track M) on the 64x64 player-skin layout."""
import sys, os
from PIL import Image

OUT = sys.argv[1]
# box name -> (u, v, w, h, d) ; overlay boxes share the shape
BOXES = {
    'body': (16, 16, 8, 12, 4), 'jacket': (16, 32, 8, 12, 4),
    'rarm': (40, 16, 4, 12, 4), 'larm': (32, 48, 4, 12, 4), 'rsleeve': (40, 32, 4, 12, 4), 'lsleeve': (48, 48, 4, 12, 4),
    'rleg': (0, 16, 4, 12, 4), 'lleg': (16, 48, 4, 12, 4), 'rpants': (0, 32, 4, 12, 4), 'lpants': (0, 48, 4, 12, 4),
}
def faces(u, v, w, h, d):
    return {'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d), 'right': (u, v + d, d, h), 'front': (u + d, v + d, w, h),
            'left': (u + d + w, v + d, d, h), 'back': (u + 2 * d + w, v + d, w, h)}

def paint(img, box, fn):
    u, v, w, h, d = BOXES[box]
    for face, (x0, y0, fw, fh) in faces(u, v, w, h, d).items():
        for y in range(fh):
            for x in range(fw):
                c = fn(face, x, y, fw, fh)
                if c is not None: img.putpixel((x0 + x, y0 + y), c + (255,) if len(c) == 3 else c)

def shade(c, k): return tuple(max(0, min(255, int(ch * k))) for ch in c)

def dress(main, accent, lace, bride):
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    def body(face, x, y, w, h):
        if face in ('top',): return main
        if face == 'bottom': return main
        if face in ('front', 'back') and y <= 1 and 2 <= x <= 5: return None  # вырез
        if y == 7: return accent  # пояс
        if face == 'front' and y == 7 and x == 5: return shade(accent, 0.8)
        if y < 7 and (x + y) % 3 == 0: return lace
        return main if y < 7 else shade(main, 0.97 if (x % 2) else 1.0)
    paint(img, 'body', body)
    def skirt_over(face, x, y, w, h):
        if face in ('top', 'bottom'): return None
        if y >= 8: return main if y < 11 else shade(main, 0.9)
        return None
    paint(img, 'jacket', skirt_over)
    def arm(face, x, y, w, h):
        if face == 'top': return main
        if face == 'bottom': return None
        if y <= 1: return main
        if bride and y <= 9: return lace if (x + y) % 2 else main  # длинный кружевной рукав
        return None
    paint(img, 'rarm', arm); paint(img, 'larm', arm)
    def leg(face, x, y, w, h):
        if face == 'top': return main
        if face == 'bottom': return shade(main, 0.85)
        if y == 11: return shade(main, 0.88)
        return main if (x + y) % 5 else lace
    paint(img, 'rleg', leg); paint(img, 'lleg', leg)
    def flare(face, x, y, w, h):
        if face in ('top',): return None
        if face == 'bottom': return shade(main, 0.85)
        return main if y < 11 else shade(main, 0.88)
    paint(img, 'rpants', flare); paint(img, 'lpants', flare)
    return img

def suit(jacket, trousers, shirt, tie, flower, vest=None):
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    def body(face, x, y, w, h):
        if face == 'top': return jacket if vest is None else shirt
        if face == 'bottom': return trousers
        if face == 'front':
            if y <= 1 and 3 <= x <= 4: return tie  # бабочка/галстук
            if vest is None:
                if y <= 3 and 2 <= x <= 5: return shirt
                if 4 <= y <= 6 and 3 <= x <= 4: return shirt
                if 2 <= y <= 6 and x in (2, 5): return shade(jacket, 1.35)  # лацканы
                if y in (8, 10) and x == 4: return (210, 175, 70)  # пуговицы
                if y == 3 and x == 1: return flower  # бутоньерка
                if y == 11: return shade(jacket, 0.7)
                return jacket
            if 3 <= x <= 4 and y <= 7: return tie if y >= 2 else shirt
            if x in (0, 7) or y <= 1: return shirt
            if y == 11: return shade(trousers, 0.7)
            return vest if (x, y) not in ((3, 9), (4, 9)) else (200, 170, 80)
        if face == 'back':
            if vest is not None: return vest if 1 <= x <= 6 and y >= 2 else shirt
            return jacket if y < 11 else shade(jacket, 0.7)
        return (jacket if vest is None else shirt) if y < 11 else shade(trousers, 0.7)
    paint(img, 'body', body)
    def arm(face, x, y, w, h):
        cloth = jacket if vest is None else shirt
        if face == 'top': return cloth
        if face == 'bottom': return None
        if y <= 9: return cloth
        if y == 10: return shirt if vest is None else None
        return None
    paint(img, 'rarm', arm); paint(img, 'larm', arm)
    def leg(face, x, y, w, h):
        shoes = (25, 20, 18)
        if face == 'top': return trousers
        if face == 'bottom': return shoes
        if y >= 10: return shoes if not (y == 10 and face == 'front' and x == 1) else (70, 65, 60)
        if face in ('right', 'left') and x == (0 if face == 'right' else 3) and vest is None: return shade(trousers, 0.75)  # лампас
        return trousers
    paint(img, 'rleg', leg); paint(img, 'lleg', leg)
    return img

W, IVORY = (246, 244, 238), (240, 232, 214)
outfits = {
    'bride': [dress(W, (236, 196, 205), (222, 224, 232), True), dress(IVORY, (214, 180, 112), (228, 220, 200), True),
              dress(W, (186, 205, 236), (226, 226, 236), True)],
    'groom': [suit((28, 28, 32), (24, 24, 28), (246, 246, 246), (20, 20, 22), (200, 40, 50)),
              suit((30, 40, 78), (26, 34, 64), (246, 246, 246), (150, 30, 45), (240, 240, 240)),
              suit((70, 70, 76), (58, 58, 64), (246, 246, 246), (120, 30, 60), (200, 40, 50))],
    'bridesmaid': [dress((236, 170, 190), (250, 236, 240), (244, 200, 214), False), dress((190, 170, 226), (246, 240, 250), (212, 198, 236), False),
                   dress((160, 200, 236), (246, 246, 250), (196, 222, 244), False)],
    'groomsman': [suit((90, 90, 96), (78, 78, 86), (236, 238, 244), (110, 120, 170), (240, 210, 80), vest=(96, 96, 104)),
                  suit((150, 120, 90), (120, 96, 72), (240, 236, 226), (70, 100, 70), (240, 240, 240), vest=(140, 112, 84)),
                  suit((60, 70, 90), (54, 62, 80), (226, 236, 248), (160, 60, 60), (240, 210, 80), vest=(62, 72, 94))],
}
for name, imgs in outfits.items():
    os.makedirs(os.path.join(OUT, name), exist_ok=True)
    for i, img in enumerate(imgs): img.save(os.path.join(OUT, name, f'{i}.png'))
print('ok')
