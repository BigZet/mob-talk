"""Cuts the roulette's church sounds out of the chants the mod already has (sounds/chants, their licences in credits.txt).

Run from the repository root: python scripts/roulette/cut_roulette_chants.py
Writes sounds/roulette/: trezvon.ogg (the Valaam bells ringing, from punishment_65), blagovest.ogg (a stroke of the great
bell, from punishment_67), choir_doom.ogg (a sustained chord of «Се Жених грядет», from punishment_63), pomiluy.ogg (the
opening of «Господи, помилуй», from blessing_46). Each cut is chosen by loudness (the fullest stretch, or the strongest
stroke), faded in and out and levelled; the offsets are printed and go into the credits.
"""
import os
import subprocess

import numpy as np

ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "assets", "divineblock", "sounds")
RATE = 44100


def load(name):
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", os.path.join(ROOT, "chants", name + ".ogg"), "-f", "f32le", "-ac", "1", "-ar", str(RATE), "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).astype(float)


def rms(x, window=0.05):
    n = int(RATE * window)
    frames = len(x) // n
    return np.sqrt((x[:frames * n].reshape(frames, n) ** 2).mean(axis=1)), n


def fullest(x, seconds, within=None):
    """The start (in samples) of the loudest and steadiest stretch of `seconds`."""
    level, n = rms(x)
    span = int(seconds * RATE / n)
    limit = len(level) - span if within is None else min(len(level) - span, int(within * RATE / n))
    best, at = -1, 0
    for i in range(0, max(1, limit)):
        part = level[i:i + span]
        score = part.mean() - 0.5 * part.std()
        if score > best:
            best, at = score, i
    return at * n


def stroke(x, within=60):
    """The start of the strongest onset (a bell stroke) in the first `within` seconds."""
    level, n = rms(x, 0.02)
    limit = min(len(level) - 1, int(within * RATE / n))
    rise = np.diff(level[:limit + 1])
    i = int(np.argmax(rise))
    return max(0, i * n - int(0.03 * RATE))


def cut(x, start, seconds, fade_in=0.02, fade_out=0.8, peak=0.85):
    part = x[start:start + int(seconds * RATE)].copy()
    t = np.arange(len(part)) / RATE
    part *= np.minimum(1, t / fade_in) * np.minimum(1, (seconds - t) / fade_out)
    return part / max(1e-9, np.abs(part).max()) * peak


def write(name, samples):
    data = (np.clip(samples, -1, 1) * 32767).astype(np.int16).tobytes()
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "s16le", "-ar", str(RATE), "-ac", "1", "-i", "-", "-c:a", "libvorbis", "-q:a", "5",
                    os.path.join(ROOT, "roulette", name + ".ogg")], input=data, check=True)


def main():
    os.makedirs(os.path.join(ROOT, "roulette"), exist_ok=True)
    jobs = [
        ("trezvon", "punishment_65", lambda x: fullest(x, 5.0), 5.0, 0.05, 1.2),
        ("blagovest", "punishment_67", lambda x: stroke(x), 6.0, 0.01, 2.5),
        ("choir_doom", "punishment_63", lambda x: fullest(x, 5.5), 5.5, 0.3, 1.5),
        ("pomiluy", "blessing_46", lambda x: fullest(x, 5.0, within=25), 5.0, 0.2, 1.2),
    ]
    for name, source, pick, seconds, fade_in, fade_out in jobs:
        x = load(source)
        start = pick(x)
        write(name, cut(x, start, seconds, fade_in, fade_out))
        print(f"{name}.ogg <- {source}.ogg from {start / RATE:.2f} s, {seconds} s")


if __name__ == "__main__":
    main()
