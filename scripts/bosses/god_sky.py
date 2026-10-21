"""The sky textures of «Бог с булавой», drawn by code (GPL-3.0): soft cumulus puffs for the cloud bank he comes out of, a
round glow for the light inside and behind the clouds, and a shaft of light for the god rays.

god_sky.png (512×512) holds four sprites in quadrants: three cloud puffs (top-left, top-right, bottom-left) and the glow
(bottom-right). Every sprite fades to fully transparent well inside its quadrant, so no square edge ever shows. The puffs
are white with their own billows shaded (light from above); the renderer colours them per vertex: lit tops, storm-grey and
violet undersides. The glow and the ray are white with soft alpha (drawn additively). god_ray.png (64×256) is a vertical shaft: soft across, fading out toward the bottom.
Run: python scripts/bosses/god_sky.py"""
import math
import os
import random
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TEX_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "divineblock", "textures", "entity", "boss")
Q = 256


def smoothstep(a, b, x):
    t = max(0.0, min(1.0, (x - a) / (b - a)))
    return t * t * (3 - 2 * t)


def noise2(rnd_seed):
    """Smooth value noise on a 16x16 lattice, for wispy edges and soft billow detail."""
    rnd = random.Random(rnd_seed)
    grid = [[rnd.random() for _ in range(17)] for _ in range(17)]

    def at(x, y):
        x, y = (x + 4) * 4, (y + 4) * 4
        i, j = math.floor(x), math.floor(y)
        fx, fy = x - i, y - j
        fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
        i, j = i % 16, j % 16
        i1, j1 = (i + 1) % 16, (j + 1) % 16
        a = grid[j][i] * (1 - fx) + grid[j][i1] * fx
        b = grid[j1][i] * (1 - fx) + grid[j1][i1] * fx
        return a * (1 - fy) + b * fy
    return at


def puff(seed):
    """A cauliflower cumulus: soft round billows blended together (no hard disc edges), each lit at its top and shaded
    under, broken by smooth noise into wisps at the rim, inside an envelope that reaches zero alpha before the sprite's edge."""
    rnd = random.Random(seed)
    lumps = []
    for i in range(16):
        a = rnd.uniform(0, 2 * math.pi)
        r = rnd.uniform(0.0, 0.5) * (0.5 if i < 5 else 1.0)
        x, y = math.cos(a) * r, math.sin(a) * r * 0.65 + 0.08
        size = rnd.uniform(0.26, 0.4) * (1.3 if i < 5 else 1.0)
        lumps.append((x, y, size))
    n1, n2 = noise2(seed * 7 + 1), noise2(seed * 13 + 5)
    img = Image.new("RGBA", (Q, Q), (0, 0, 0, 0))
    px = img.load()
    for j in range(Q):
        for i in range(Q):
            x, y = (i + 0.5) / Q * 2 - 1, (j + 0.5) / Q * 2 - 1
            dens, lit, weight = 0.0, 0.0, 0.0
            for lx, ly, ls in lumps:
                d = math.hypot(x - lx, y - ly) / ls
                if d < 1.4:
                    w = 1 - smoothstep(0.2, 1.3, d)
                    dens += w
                    # Lit from above: the top of each billow bright, its underside darker.
                    lit += w * (0.5 + 0.5 * max(-1.0, min(1.0, -(y - ly) / ls)))
                    weight += w
            if weight <= 0:
                continue
            dens = min(1.0, dens * 0.9) * (0.75 + 0.5 * n1(x * 1.5, y * 1.5))
            envelope = 1 - smoothstep(0.6, 0.97, math.hypot(x, y * 1.2) + 0.12 * (n2(x * 2, y * 2) - 0.5))
            alpha = smoothstep(0.15, 0.85, dens) * envelope
            if alpha <= 0.01:
                continue
            v = 0.55 + 0.45 * (lit / weight) + 0.1 * (n2(x * 3, y * 3) - 0.5)
            c = int(120 + 135 * max(0.0, min(1.0, v)))
            px[i, j] = (c, c, min(255, c + 5), int(255 * alpha))
    return img


def glow():
    img = Image.new("RGBA", (Q, Q), (0, 0, 0, 0))
    px = img.load()
    for j in range(Q):
        for i in range(Q):
            r = math.hypot((i + 0.5) / Q * 2 - 1, (j + 0.5) / Q * 2 - 1)
            a = max(0.0, 1 - r) ** 2.2
            if a > 0.004:
                px[i, j] = (255, 255, 255, int(255 * a))
    return img


def ray():
    img = Image.new("RGBA", (64, 256), (0, 0, 0, 0))
    px = img.load()
    for j in range(256):
        t = j / 255
        for i in range(64):
            x = abs((i + 0.5) / 64 * 2 - 1)
            a = (1 - smoothstep(0.2, 1.0, x)) * (1 - t) ** 1.4 * smoothstep(0.0, 0.08, t)
            if a > 0.004:
                px[i, j] = (255, 255, 255, int(255 * a))
    return img


if __name__ == "__main__":
    sky = Image.new("RGBA", (2 * Q, 2 * Q), (0, 0, 0, 0))
    sky.paste(puff(3), (0, 0))
    sky.paste(puff(11), (Q, 0))
    sky.paste(puff(29), (0, Q))
    sky.paste(glow(), (Q, Q))
    os.makedirs(TEX_DIR, exist_ok=True)
    sky.save(os.path.join(TEX_DIR, "god_sky.png"))
    ray().save(os.path.join(TEX_DIR, "god_ray.png"))
    print("wrote god_sky.png, god_ray.png")
