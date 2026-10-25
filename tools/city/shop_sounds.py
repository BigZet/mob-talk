"""The supermarket's sounds, synthesised here (so they are ours, GPL like the rest): the scanner's beep at the till, the till
drawer's ring, the anti-theft gate's alarm, the PA chime before an announcement, the hum of the refrigerated cases, a cart's
rattle and a short tune of shop music. Writes assets/mobtalk/sounds/shop/*.ogg (mono, so they are heard from where they play)
and their entries in assets/mobtalk/sounds.json; played by io.github.mobtalk.game.ShopController.

    python3 -I tools/city/shop_sounds.py      # needs numpy and ffmpeg
"""
import json
import os
import subprocess
import tempfile
import wave

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "mobtalk")
RATE = 44100


def t(seconds):
    return np.arange(int(RATE * seconds)) / RATE


def env(n, attack=0.005, release=0.02):
    """A flat envelope with short ramps at both ends (no clicks)."""
    e = np.ones(n)
    a, r = int(RATE * attack), int(RATE * release)
    if a:
        e[:a] = np.linspace(0, 1, a)
    if r:
        e[-r:] = np.linspace(1, 0, r)
    return e


def bell(freq, seconds, decay=2.5, partials=((1, 1), (2.76, 0.35), (5.4, 0.12))):
    x = t(seconds)
    out = sum(a * np.sin(2 * np.pi * freq * k * x) * np.exp(-decay * k ** 0.5 * x) for k, a in partials)
    return out * env(len(x), 0.002, 0.05)


def place(track, sound, at):
    i = int(RATE * at)
    end = min(len(track), i + len(sound))
    track[i:end] += sound[:end - i]


def lowpass(x, alpha):
    y = np.zeros_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc += alpha * (v - acc)
        y[i] = acc
    return y


def beep(freq):
    x = t(0.11)
    s = np.sin(2 * np.pi * freq * x) + 0.18 * np.sign(np.sin(2 * np.pi * freq * x))
    return s * env(len(x), 0.003, 0.012) * 0.5


def drawer():
    out = np.zeros(int(RATE * 0.9))
    r = np.random.default_rng(7)
    slide = lowpass(r.normal(0, 1, int(RATE * 0.25)), 0.08) * np.linspace(0.2, 1, int(RATE * 0.25)) * 1.6
    place(out, slide, 0.0)
    place(out, bell(2093, 0.65, 4) * 0.45, 0.22)  # the ring as the drawer opens
    place(out, lowpass(r.normal(0, 1, int(RATE * 0.04)), 0.3) * 0.8, 0.24)  # its knock
    return out


def alarm():
    out = np.zeros(int(RATE * 1.3))
    for i in range(6):
        f = 2400 if i % 2 == 0 else 3100
        x = t(0.17)
        s = np.sign(np.sin(2 * np.pi * f * x)) * 0.25 + np.sin(2 * np.pi * f * x) * 0.4
        place(out, s * env(len(x), 0.004, 0.02), i * 0.2)
    return out


def chime():
    """«Дин-дон-дин»: the shop's public address before an announcement."""
    out = np.zeros(int(RATE * 3.2))
    for i, f in enumerate((659.25, 523.25, 783.99, 1046.5)):
        place(out, bell(f, 2.2, 1.6) * 0.42, i * 0.42)
    return out


def hum():
    """A refrigerated case: the mains hum of its compressor, a fan's hiss, swelling and fading."""
    x = t(4.0)
    r = np.random.default_rng(11)
    tone = 0.5 * np.sin(2 * np.pi * 100 * x) + 0.25 * np.sin(2 * np.pi * 200 * x + 0.4) + 0.08 * np.sin(2 * np.pi * 300 * x)
    tone *= 1 + 0.15 * np.sin(2 * np.pi * 0.7 * x)
    hiss = lowpass(r.normal(0, 1, len(x)), 0.05) * 0.5
    return (tone + hiss) * env(len(x), 0.8, 1.2) * 0.5


def rattle(seed):
    """A cart on tiles: a run of wheel ticks and the basket's wire shaking."""
    r = np.random.default_rng(seed)
    out = np.zeros(int(RATE * 0.7))
    tick = 0.0
    while tick < 0.6:
        n = int(RATE * 0.012)
        click = r.normal(0, 1, n) * np.exp(-np.linspace(0, 6, n))
        ring = np.sin(2 * np.pi * r.uniform(1800, 3200) * t(0.05)) * np.exp(-60 * t(0.05)) * 0.3
        place(out, click * r.uniform(0.3, 0.7), tick)
        place(out, ring, tick)
        tick += r.uniform(0.03, 0.08)
    rumble = lowpass(r.normal(0, 1, len(out)), 0.02) * 1.5
    return (out + rumble) * env(len(out), 0.05, 0.15) * 0.6


def music():
    """Shop music: an easy electric-piano tune over C - Am - F - G, about twenty seconds."""
    beat = 0.5
    bars = [(48, (60, 64, 67)), (45, (57, 60, 64)), (41, (57, 60, 65)), (43, (55, 59, 62))] * 2
    melody = [72, 74, 76, 79, 76, 74, 72, None, 69, 72, 76, 74, 72, 69, 67, None, 65, 69, 72, 74, 72, 69, 65, None,
              67, 71, 74, 72, 71, 67, 62, None] * 2
    total = len(bars) * 4 * beat * 1.0 + 2
    out = np.zeros(int(RATE * total))

    def note(m):
        return 440 * 2 ** ((m - 69) / 12)

    def epiano(f, seconds, vol):
        x = t(seconds)
        s = np.sin(2 * np.pi * f * x + 0.6 * np.sin(2 * np.pi * f * 2 * x) * np.exp(-3 * x))
        return s * np.exp(-2.2 * x) * env(len(x), 0.004, 0.08) * vol

    for i, (root, chord) in enumerate(bars):
        at = i * 4 * beat
        place(out, epiano(note(root), 2.0, 0.35), at)
        place(out, epiano(note(root + 7), 1.0, 0.18), at + 2 * beat)
        for k in (1, 3):
            for m in chord:
                place(out, epiano(note(m), 0.6, 0.09), at + k * beat)
    for i, m in enumerate(melody[: len(bars) * 4]):
        if m is not None:
            place(out, epiano(note(m), 0.9, 0.22), i * beat)
    return out * env(len(out), 0.5, 2.0)


SOUNDS = {
    "beep": [beep(2700), beep(2500), beep(2900)],
    "drawer": [drawer()],
    "alarm": [alarm()],
    "chime": [chime()],
    "hum": [hum()],
    "cart": [rattle(1), rattle(2), rattle(3)],
    "music": [music()],
}
SUBTITLES = {"beep": ("Пик сканера", "Scanner beeps"), "drawer": ("Звякает касса", "Till rings"),
             "alarm": ("Пищит антикражная рамка", "Anti-theft gate alarm"), "chime": ("Объявление в магазине", "Shop announcement chime"),
             "hum": ("Гудит холодильник", "Fridge hums"), "cart": ("Грохочет тележка", "Cart rattles"), "music": ("Музыка в магазине", "Shop music")}


def write_ogg(samples, path):
    peak = np.max(np.abs(samples)) or 1
    pcm = (samples / peak * 0.85 * 32767).astype(np.int16)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        name = tmp.name
    with wave.open(name, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", name, "-ac", "1", "-c:a", "libvorbis", "-q:a", "4", path], check=True)
    os.remove(name)


def main():
    out_dir = os.path.join(ASSETS, "sounds", "shop")
    os.makedirs(out_dir, exist_ok=True)
    index_path = os.path.join(ASSETS, "sounds.json")
    index = json.load(open(index_path, encoding="utf-8"))
    for name, variants in SOUNDS.items():
        files = []
        for i, samples in enumerate(variants):
            file = name if len(variants) == 1 else f"{name}{i + 1}"
            write_ogg(samples, os.path.join(out_dir, file + ".ogg"))
            files.append({"name": f"mobtalk:shop/{file}", "stream": name == "music"} if name == "music" else f"mobtalk:shop/{file}")
        index[f"shop.{name}"] = {"subtitle": f"subtitle.mobtalk.shop.{name}", "sounds": files}
    with open(index_path, "w", encoding="utf-8") as f:
        json.dump(index, f, indent=2, ensure_ascii=False)
        f.write("\n")
    lang = os.path.join(ASSETS, "lang")
    for i, code in enumerate(("ru_ru", "en_us")):
        p = os.path.join(lang, code + ".json")
        data = json.load(open(p, encoding="utf-8"))
        for name, pair in SUBTITLES.items():
            data[f"subtitle.mobtalk.shop.{name}"] = pair[i]
        data["mobtalk.settings.shopSounds"] = ("Звуки магазина", "Shop sounds")[i]
        data["mobtalk.settings.shopSounds.tooltip"] = (
            "Пик сканера на кассах, звон кассы, писк рамки, гул холодильников, объявления, музыка и грохот тележек.",
            "Scanner beeps at the tills, the till's ring, the gate's alarm, humming fridges, announcements, music and rattling carts.")[i]
        with open(p, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
            f.write("\n")
    print("shop sounds:", sum(len(v) for v in SOUNDS.values()), "files")


if __name__ == "__main__":
    main()
