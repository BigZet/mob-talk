"""Рисует текстуры клеток 16×16: металл прутьев (железо, золото) и дно (дерево, золото).
Запуск: python -I tools/draw_textures.py <папка textures/block>."""
import random, sys
from PIL import Image

out = sys.argv[1]
rnd = random.Random(7)

def metal(name, dark, mid, light):
    # Поперёк прута (u) — блик в первой трети, вдоль (v) — лёгкая зернистость.
    profile = [0.15, 0.45, 0.85, 1.0, 0.8, 0.6, 0.5, 0.42, 0.36, 0.3, 0.26, 0.22, 0.2, 0.18, 0.12, 0.05]
    img = Image.new('RGBA', (16, 16))
    for x in range(16):
        k = profile[x]
        for y in range(16):
            n = rnd.uniform(-0.04, 0.04)
            t = min(1.0, max(0.0, k + n))
            lo, hi = (dark, mid) if t < 0.5 else (mid, light)
            f = t * 2 if t < 0.5 else (t - 0.5) * 2
            img.putpixel((x, y), tuple(int(lo[i] + (hi[i] - lo[i]) * f) for i in range(3)) + (255,))
    img.save(f'{out}/{name}.png')

def base(name, a, b, rim):
    # Круглый поддон: доски с полосами, по краю тёмный обод.
    img = Image.new('RGBA', (16, 16))
    for x in range(16):
        for y in range(16):
            stripe = (x // 4) % 2
            c = a if stripe == 0 else b
            if x % 4 == 0: c = tuple(int(v * 0.8) for v in c)
            n = rnd.randint(-6, 6)
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if d > 6.6: c = rim
            img.putpixel((x, y), tuple(max(0, min(255, v + n)) for v in c) + (255,))
    img.save(f'{out}/{name}.png')

metal('cage_iron', (58, 60, 66), (150, 154, 160), (236, 238, 242))
metal('cage_gold', (122, 70, 12), (222, 170, 40), (255, 246, 170))
base('cage_base_wood', (112, 78, 46), (98, 66, 38), (60, 40, 22))
base('cage_base_gold', (214, 168, 52), (196, 150, 40), (140, 92, 20))
