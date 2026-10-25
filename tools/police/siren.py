"""The Russian police siren (СГУ) for the city alarm, synthesized here so it is all our own (CC0): the «кряк» (the short rasping
quack a patrol car gives to stop a driver), the wail (a slow rise and fall) and the yelp (the same sweep, fast). An SGU is an
electronic tone generator driving a horn speaker, so: a square-ish wave swept in pitch, clipped, band-limited to a horn's range and
given a little outdoor echo. Writes mono .ogg (Minecraft only places mono sounds in the world) into the mod's sounds folder.

    python3 tools/police/siren.py
"""
import os
import subprocess
import sys
import wave

import numpy as np

RATE = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "assets", "mobtalk", "sounds", "police")


def tone(freq):
    """A tone whose pitch follows `freq` (Hz per sample): a square with rounded edges plus a little of the saw, like an SGU's
    output stage."""
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    square = np.tanh(4.0 * np.sin(phase))
    saw = 2 * ((phase / (2 * np.pi)) % 1.0) - 1
    return 0.8 * square + 0.2 * saw


def wail(seconds=4.0, low=560, high=1420):
    """One rise and fall: up quickly, a short hold at the top, down slowly (the motor-siren curve the electronic ones copy)."""
    t = np.linspace(0, 1, int(RATE * seconds), endpoint=False)
    up = 0.42
    curve = np.where(t < up, np.clip(np.sin(t / up * np.pi / 2), 0, 1) ** 0.8, np.clip(np.cos((t - up) / (1 - up) * np.pi / 2), 0, 1) ** 1.4)
    return tone(low + (high - low) * curve)


def yelp(seconds=2.4, low=620, high=1500, period=0.27):
    t = np.arange(int(RATE * seconds)) / RATE
    x = (t % period) / period
    curve = np.where(x < 0.5, x * 2, 2 - x * 2) ** 0.9
    return tone(low + (high - low) * curve)


def quack(seconds=0.34):
    """«Кряк»: a harsh burst that falls in pitch, chopped by a buzz into the rasping «кр-р-я»."""
    n = int(RATE * seconds)
    t = np.arange(n) / RATE
    freq = 980 * np.exp(-t / seconds * 1.15) + 260
    buzz = 0.55 + 0.45 * np.sign(np.sin(2 * np.pi * 62 * t))
    env = np.minimum(1, t / 0.012) * np.minimum(1, (seconds - t) / 0.05)
    return np.clip(1.6 * tone(freq) * buzz, -1, 1) * env


def silence(seconds):
    return np.zeros(int(RATE * seconds))


def fade(x, ms=12):
    n = int(RATE * ms / 1000)
    x = x.copy()
    x[:n] *= np.linspace(0, 1, n)
    x[-n:] *= np.linspace(1, 0, n)
    return x


def write(name, samples, echo=True):
    os.makedirs(OUT, exist_ok=True)
    raw = os.path.join(OUT, name + ".wav")
    pcm = (np.clip(samples, -1, 1) * 0.85 * 32767).astype("<i2")
    with wave.open(raw, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(pcm.tobytes())
    # The horn speaker: no deep bass, no fizz, a presence peak; then the street's echo off the blocks of flats.
    chain = "highpass=f=380,lowpass=f=4200,equalizer=f=1800:t=q:w=1.2:g=4,acompressor=threshold=0.5:ratio=3"
    if echo:
        chain += ",aecho=0.8:0.55:90|210:0.25|0.12"
    chain += ",alimiter=limit=0.9"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", raw, "-af", chain, "-ac", "1", "-c:a", "libvorbis", "-q:a", "4",
                    os.path.join(OUT, name + ".ogg")], check=True)
    os.remove(raw)


def main():
    q = lambda: np.concatenate([fade(quack()), silence(0.09)])
    # The alarm loop, about ten seconds (the alarm sounds again every ten): two quacks, two wails, a yelp, a wail.
    siren = np.concatenate([q(), q(), silence(0.15), wail(), wail(3.4), yelp(2.0)])
    write("siren", fade(siren, 40))
    write("wail", fade(wail(), 30))
    write("yelp", fade(yelp(), 30))
    write("quack", np.concatenate([q(), q()]), echo=True)
    print("written to", os.path.normpath(OUT))


if __name__ == "__main__":
    sys.exit(main())
