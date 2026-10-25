"""The night bandits' whistle, synthesised here (so it is ours, GPL like the rest): two sharp finger whistles, the signal of a
gang that is about to strike or running off ("шухер"). Writes assets/mobtalk/sounds/bandit/whistle*.ogg (mono, heard from where
they play) and their entry in assets/mobtalk/sounds.json; played by io.github.mobtalk.game.BanditController.

    python3 -I tools/bandits/sounds.py      # needs numpy and ffmpeg
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


def glide(f0, f1, seconds, wobble=0.0):
    """A whistle tone sliding from f0 to f1 (Hz), with a breathy edge and a slight vibrato."""
    n = int(RATE * seconds)
    x = np.arange(n) / RATE
    curve = np.linspace(0, 1, n) ** 0.6
    freq = f0 + (f1 - f0) * curve + wobble * np.sin(2 * np.pi * 7 * x)
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    tone = np.sin(phase) + 0.08 * np.sin(2 * phase)
    rng = np.random.default_rng(int(f0 + f1))
    breath = rng.standard_normal(n)
    breath = np.convolve(breath, np.ones(6) / 6, mode="same") * 0.06
    env = np.ones(n)
    a, r = int(RATE * 0.02), int(RATE * 0.06)
    env[:a] = np.linspace(0, 1, a)
    env[-r:] = np.linspace(1, 0, r)
    return (tone + breath) * env


def track(parts):
    out = np.zeros(int(RATE * (max(at + len(p) / RATE for p, at in parts) + 0.05)))
    for part, at in parts:
        i = int(RATE * at)
        out[i:i + len(part)] += part
    return out / np.max(np.abs(out)) * 0.8


def write(name, samples):
    folder = os.path.join(ASSETS, "sounds", "bandit")
    os.makedirs(folder, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, name + ".wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes((samples * 32767).astype(np.int16).tobytes())
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav, "-c:a", "libvorbis", "-q:a", "4",
                        os.path.join(folder, name + ".ogg")], check=True)


def main():
    # Signal whistle: a short rise and a long fall-rise, the street "эй, свои".
    write("whistle1", track([(glide(1700, 2700, 0.22), 0.0), (glide(2600, 2100, 0.18), 0.24), (glide(2100, 2900, 0.42, 25), 0.44)]))
    # Alarm whistle ("шухер!"): two sharp rising blasts.
    write("whistle2", track([(glide(1900, 3000, 0.3, 15), 0.0), (glide(1900, 3100, 0.38, 20), 0.36)]))
    path = os.path.join(ASSETS, "sounds.json")
    with open(path, encoding="utf-8") as f:
        sounds = json.load(f)
    sounds["bandit_whistle"] = {"subtitle": "subtitle.mobtalk.bandit_whistle",
                                "sounds": [{"name": "mobtalk:bandit/whistle1", "attenuation_distance": 48},
                                           {"name": "mobtalk:bandit/whistle2", "attenuation_distance": 48}]}
    with open(path, "w", encoding="utf-8") as f:
        json.dump(sounds, f, ensure_ascii=False, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
