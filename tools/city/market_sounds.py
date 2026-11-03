"""The market's and the kiosks' sounds, synthesised like the shop's (tools/city/shop_sounds.py): the murmur of a crowd between
the stalls, the clink of iron weights on the enamel scales, the cleaver at the meat counter, and the shawarma grill's sizzle.
Writes assets/mobtalk/sounds/market/*.ogg and their sounds.json entries; played by io.github.mobtalk.game.MarketController.

    python3 -I tools/city/market_sounds.py      # needs numpy and ffmpeg
"""
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from shop_sounds import ASSETS, RATE, bell, env, lowpass, place, t, write_ogg  # noqa: E402


def crowd(seed):
    """Many people talking at once a little way off: noise shaped into vowels (two formants that wander), syllables
    coming and going, a voice now and then a bit nearer."""
    r = np.random.default_rng(seed)
    seconds = 7.0
    out = np.zeros(int(RATE * seconds))
    for _ in range(14):  # voices
        pitch = r.uniform(110, 260)
        start = r.uniform(0, seconds - 1.5)
        length = r.uniform(0.8, 2.4)
        x = t(length)
        syll = np.clip(np.sin(2 * np.pi * r.uniform(3, 5.5) * x + r.uniform(0, 6)), 0, 1) ** 1.5  # syllables
        glottal = np.sign(np.sin(2 * np.pi * pitch * (1 + 0.05 * np.sin(2 * np.pi * 1.3 * x)) * x)) * 0.5
        voice = glottal + r.normal(0, 0.3, len(x))
        f1 = lowpass(voice, r.uniform(0.04, 0.08))
        f2 = lowpass(voice, r.uniform(0.15, 0.25)) - lowpass(voice, 0.08)
        v = (f1 + 0.6 * f2) * syll * env(len(x), 0.08, 0.2) * r.uniform(0.15, 0.45)
        place(out, v, start)
    bed = lowpass(r.normal(0, 1, len(out)), 0.03) * 0.25  # the hubbub's bed
    out = lowpass(out + bed, 0.35)
    return out * env(len(out), 0.6, 0.9)


def scales(seed):
    """Weights put on the scales: two or three iron knocks with a short ring of the enamel pan."""
    r = np.random.default_rng(seed)
    out = np.zeros(int(RATE * 0.9))
    at = 0.0
    for _ in range(r.integers(2, 4)):
        n = int(RATE * 0.01)
        place(out, r.normal(0, 1, n) * np.exp(-np.linspace(0, 8, n)) * 0.6, at)
        place(out, bell(r.uniform(1300, 1900), 0.4, 9, ((1, 1), (2.4, 0.4), (4.1, 0.2))) * 0.35, at)
        at += r.uniform(0.15, 0.28)
    return out


def chop(seed):
    """A cleaver into the block: a dull thud and a wooden knock."""
    r = np.random.default_rng(seed)
    x = t(0.35)
    thud = np.sin(2 * np.pi * (90 + 60 * np.exp(-30 * x)) * x) * np.exp(-14 * x)
    knock = lowpass(r.normal(0, 1, len(x)), 0.25) * np.exp(-40 * x) * 0.8
    return (thud + knock) * env(len(x), 0.001, 0.05)


def sizzle():
    """The shawarma grill: hot fat crackling."""
    r = np.random.default_rng(31)
    seconds = 3.0
    out = lowpass(r.normal(0, 1, int(RATE * seconds)), 0.6) * 0.12
    k = 0.0
    while k < seconds - 0.05:
        n = int(RATE * 0.004)
        place(out, r.normal(0, 1, n) * np.exp(-np.linspace(0, 7, n)) * r.uniform(0.2, 0.9), k)
        k += r.exponential(0.02)
    return out * env(len(out), 0.3, 0.5)


SOUNDS = {
    "crowd": [crowd(1), crowd(2)],
    "scales": [scales(3), scales(4)],
    "chop": [chop(5), chop(6)],
    "sizzle": [sizzle()],
}
SUBTITLES = {"crowd": ("Гомон рынка", "Market hubbub"), "scales": ("Звякают гири", "Weights clink"),
             "chop": ("Рубят мясо", "Cleaver chops"), "sizzle": ("Шкворчит гриль", "Grill sizzles")}


def main():
    out_dir = os.path.join(ASSETS, "sounds", "market")
    os.makedirs(out_dir, exist_ok=True)
    index_path = os.path.join(ASSETS, "sounds.json")
    index = json.load(open(index_path, encoding="utf-8"))
    for name, variants in SOUNDS.items():
        files = []
        for i, samples in enumerate(variants):
            file = name if len(variants) == 1 else f"{name}{i + 1}"
            write_ogg(samples, os.path.join(out_dir, file + ".ogg"))
            files.append(f"mobtalk:market/{file}")
        index[f"market.{name}"] = {"subtitle": f"subtitle.mobtalk.market.{name}", "sounds": files}
    with open(index_path, "w", encoding="utf-8") as f:
        json.dump(index, f, indent=2, ensure_ascii=False)
        f.write("\n")
    for i, code in enumerate(("ru_ru", "en_us")):
        p = os.path.join(ASSETS, "lang", code + ".json")
        data = json.load(open(p, encoding="utf-8"))
        for name, pair in SUBTITLES.items():
            data[f"subtitle.mobtalk.market.{name}"] = pair[i]
        with open(p, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
            f.write("\n")
    print("market sounds:", sum(len(v) for v in SOUNDS.values()), "files")


if __name__ == "__main__":
    main()
