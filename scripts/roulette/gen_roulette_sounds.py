"""Synthesises the punishment roulette's sounds (original, made here in code) and encodes them to Ogg Vorbis with ffmpeg.

Run from the repository root: python scripts/roulette/gen_roulette_sounds.py
Writes src/main/resources/assets/divineblock/sounds/roulette/: lever, tick, thud, bell, big_bell, chime, choir, fanfare, coins,
riser, heartbeat, whoosh (.ogg).
The thunder of the final is the game's own (sounds.json points at minecraft:ambient/weather/thunder*).
"""
import os
import subprocess
import tempfile
import wave

import numpy as np
from scipy import signal

RATE = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "assets", "divineblock", "sounds", "roulette")
rng = np.random.default_rng(777)


def t(seconds):
    return np.arange(int(RATE * seconds)) / RATE


def env(length, attack, decay):
    x = t(length)
    return np.minimum(1, x / max(attack, 1e-4)) * np.exp(-x / decay)


def bandpass(x, low, high, order=2):
    sos = signal.butter(order, [low, high], btype="band", fs=RATE, output="sos")
    return signal.sosfilt(sos, x)


def highpass(x, cut, order=2):
    return signal.sosfilt(signal.butter(order, cut, btype="high", fs=RATE, output="sos"), x)


def lowpass(x, cut, order=2):
    return signal.sosfilt(signal.butter(order, cut, btype="low", fs=RATE, output="sos"), x)


def bell(fundamental, length, brightness=1.0, hum=0.6):
    """A church bell: the hum, prime, minor third, fifth, nominal and upper partials, each fading at its own pace."""
    x = t(length)
    partials = [(0.5, hum, 0.9), (1.0, 1.0, 0.55), (1.19, 0.55, 0.38), (1.5, 0.35, 0.3), (2.0, 0.6 * brightness, 0.22),
                (2.51, 0.25 * brightness, 0.14), (3.01, 0.2 * brightness, 0.1), (4.17, 0.12 * brightness, 0.06)]
    out = np.zeros_like(x)
    for ratio, amp, decay in partials:
        beat = 1 + 0.002 * np.sin(2 * np.pi * 1.3 * x)
        out += amp * np.sin(2 * np.pi * fundamental * ratio * x * beat) * np.exp(-x / (decay * length))
    strike = highpass(rng.standard_normal(len(x)), 2000) * np.exp(-x / 0.004) * 0.4
    return (out + strike) * np.minimum(1, x / 0.002)


def lever():
    click = highpass(rng.standard_normal(int(RATE * 0.03)), 1500) * env(0.03, 0.0005, 0.006)
    thump = np.sin(2 * np.pi * 140 * t(0.12)) * env(0.12, 0.001, 0.03)
    x = t(0.4)
    clank = sum(a * np.sin(2 * np.pi * f * x) for f, a in ((812, 0.5), (1273, 0.35), (1994, 0.25), (2861, 0.15))) * env(0.4, 0.001, 0.09)
    out = np.zeros(int(RATE * 0.45))
    out[:len(click)] += click
    out[:len(thump)] += thump * 0.8
    at = int(RATE * 0.05)
    out[at:at + len(clank)] += clank * 0.6
    return out


def tick():
    click = highpass(rng.standard_normal(int(RATE * 0.012)), 2500) * env(0.012, 0.0003, 0.0025)
    ping = np.sin(2 * np.pi * 2400 * t(0.04)) * env(0.04, 0.0005, 0.008) * 0.5
    out = np.zeros(int(RATE * 0.05))
    out[:len(click)] += click
    out[:len(ping)] += ping
    return out


def thud():
    x = t(0.35)
    sweep = 2 * np.pi * np.cumsum(55 + 60 * np.exp(-x / 0.04)) / RATE
    body = np.sin(sweep) * env(0.35, 0.002, 0.09)
    knock = lowpass(rng.standard_normal(len(x)), 900) * env(0.35, 0.001, 0.02) * 0.6
    return body + knock


def chime():
    """A pardon: small bright bells running up and ringing together."""
    out = np.zeros(int(RATE * 2.6))
    for i, f in enumerate((1046.5, 1318.5, 1568.0, 2093.0, 1568.0, 2093.0)):
        note = bell(f, 1.6, brightness=0.7, hum=0.2) * 0.4
        at = int(RATE * 0.09 * i)
        out[at:at + len(note)] += note[:len(out) - at]
    return out


def choir():
    """A short sung chord, «а-а»: four voices of sawtooth with vibrato, shaped by the formants of an open vowel."""
    length = 3.2
    x = t(length)
    out = np.zeros_like(x)
    for f, amp in ((130.8, 0.9), (164.8, 0.7), (196.0, 0.7), (261.6, 0.6), (329.6, 0.35)):
        for detune in (-0.004, 0.0, 0.005):
            vibrato = 1 + 0.006 * np.sin(2 * np.pi * (5.1 + detune * 200) * x + rng.uniform(0, 6))
            phase = np.cumsum(f * (1 + detune) * vibrato) / RATE
            out += amp * (2 * (phase % 1) - 1)
    voiced = bandpass(out, 600, 900) * 1.0 + bandpass(out, 1050, 1350) * 0.6 + bandpass(out, 2400, 2800) * 0.25 + lowpass(out, 300) * 0.3
    shape = np.minimum(1, x / 0.35) * np.minimum(1, (length - x) / 1.2)
    breath = bandpass(rng.standard_normal(len(x)), 1500, 5000) * 0.03
    return (voiced + breath) * np.clip(shape, 0, 1)


def fanfare():
    """The win: bells running up a major arpeggio and ringing together, casino-bright."""
    out = np.zeros(int(RATE * 3.0))
    notes = (523.25, 659.25, 783.99, 1046.5, 1318.5, 1568.0, 2093.0)
    for i, f in enumerate(notes):
        note = bell(f, 2.0 if i == len(notes) - 1 else 1.0, brightness=0.9, hum=0.25) * (0.5 if i < len(notes) - 1 else 0.8)
        at = int(RATE * 0.055 * i)
        out[at:at + len(note)] += note[:len(out) - at]
    return out


def coins():
    """A shower of coins: many small metal clinks, thick at first and thinning out."""
    length = 2.4
    out = np.zeros(int(RATE * length))
    for i in range(70):
        at = rng.uniform(0, length - 0.3) ** 1.6 / (length - 0.3) ** 0.6
        f = rng.uniform(3200, 6200)
        x = t(0.12)
        clink = sum(a * np.sin(2 * np.pi * f * k * x) for k, a in ((1, 1.0), (1.48, 0.5), (2.13, 0.3))) * np.exp(-x / rng.uniform(0.02, 0.05))
        start = int(RATE * at)
        out[start:start + len(clink)] += clink[:len(out) - start] * rng.uniform(0.3, 1.0)
    return highpass(out, 1500)


def riser():
    """Tension before the last reel: a tone and a noise rising for three seconds."""
    length = 3.0
    x = t(length)
    f = 110 * 2 ** (x / length * 2.5)
    tone = sum(np.sin(2 * np.pi * np.cumsum(f * k) / RATE) / k for k in (1, 2, 3))
    noise = bandpass(rng.standard_normal(len(x)), 800, 6000) * (x / length) ** 2 * 0.4
    shape = (x / length) ** 1.5 * np.minimum(1, (length - x) / 0.05)
    return (tone * 0.5 + noise) * shape


def heartbeat():
    """Lub-dub."""
    out = np.zeros(int(RATE * 0.6))
    for at, f, a in ((0.0, 55, 1.0), (0.18, 48, 0.7)):
        x = t(0.2)
        beat = np.sin(2 * np.pi * f * x) * np.exp(-x / 0.05) * a
        start = int(RATE * at)
        out[start:start + len(beat)] += beat
    return lowpass(out, 300)


def whoosh():
    """The big words flying at the player."""
    length = 0.7
    x = t(length)
    noise = rng.standard_normal(len(x))
    sweep = signal.sosfilt(signal.butter(2, [600, 3000], btype="band", fs=RATE, output="sos"), noise)
    return sweep * np.sin(np.pi * x / length) ** 2


def reverb(x, seconds=1.4, mix=0.25):
    tail = rng.standard_normal(int(RATE * seconds)) * np.exp(-t(seconds) / (seconds / 5))
    dry = np.concatenate([x, np.zeros(len(tail))])
    wet = signal.fftconvolve(x, lowpass(tail, 5000))[:len(dry)]
    wet = np.pad(wet, (0, len(dry) - len(wet)))
    wet /= max(1e-9, np.max(np.abs(wet))) / max(1e-9, np.max(np.abs(x)))
    return dry * (1 - mix) + wet * mix


def write(name, samples, peak=0.9, room=None):
    if room:
        samples = reverb(samples, *room)
    samples = samples / max(1e-9, np.max(np.abs(samples))) * peak
    data = (samples * 32767).astype(np.int16)
    os.makedirs(OUT, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, name + ".wav")
        with wave.open(path, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(data.tobytes())
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", path, "-c:a", "libvorbis", "-q:a", "5", os.path.join(OUT, name + ".ogg")], check=True)


def main():
    write("lever", lever(), 0.8)
    write("tick", tick(), 0.6)
    write("thud", thud(), 0.85)
    write("bell", bell(523.25, 2.4), 0.8, room=(1.2, 0.2))
    write("big_bell", bell(130.8, 5.0, brightness=0.8, hum=0.9), 0.95, room=(2.0, 0.3))
    write("chime", chime(), 0.75, room=(1.2, 0.25))
    write("choir", choir(), 0.8, room=(2.0, 0.35))
    write("fanfare", fanfare(), 0.8, room=(1.4, 0.25))
    write("coins", coins(), 0.7, room=(0.8, 0.15))
    write("riser", riser(), 0.7)
    write("heartbeat", heartbeat(), 0.9)
    write("whoosh", whoosh(), 0.6)


if __name__ == "__main__":
    main()
