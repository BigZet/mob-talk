"""The lift's sounds, synthesised (no recordings): the motor's hum (a seamless loop), the start and the stop with the brake and
the relays, the door operator's whir with the classic slam, the call button, the floor relay's click, the alarm bell.

    python3 -I tools/city2/lift_sounds.py

Writes assets/mobtalk/sounds/lift/*.ogg (ffmpeg, Vorbis) and their entries in assets/mobtalk/sounds.json.
"""
import json
import os
import subprocess
import tempfile
import wave

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "mobtalk")
SR = 44100
rng = np.random.default_rng(1964)


def t(seconds):
    return np.arange(int(seconds * SR)) / SR


def lowpass(x, cutoff):
    """One-pole low-pass, run twice (a gentle 12 dB slope)."""
    a = np.exp(-2 * np.pi * cutoff / SR)
    for _ in range(2):
        y = np.empty_like(x)
        acc = 0.0
        for i, v in enumerate(x):
            acc = (1 - a) * v + a * acc
            y[i] = acc
        x = y
    return x


def bandnoise(n, lo, hi):
    spec = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / SR)
    spec[(f < lo) | (f > hi)] = 0
    out = np.fft.irfft(spec, n)
    return out / (np.abs(out).max() + 1e-9)


def env(n, attack, release, total=None):
    e = np.ones(n)
    a, r = int(attack * SR), int(release * SR)
    if a:
        e[:a] = np.linspace(0, 1, a)
    if r:
        e[-r:] *= np.linspace(1, 0, r) ** 2
    return e


def click(length=0.012, tone=2400, decay=400, level=1.0):
    x = t(length)
    return level * np.sin(2 * np.pi * tone * x) * np.exp(-decay * x) + level * 0.6 * bandnoise(len(x), 800, 9000) * np.exp(-decay * 1.6 * x)


def thump(length=0.35, f0=70, level=1.0):
    x = t(length)
    f = f0 * (1 + 0.6 * np.exp(-x * 30))
    ph = 2 * np.pi * np.cumsum(f) / SR
    return level * (np.sin(ph) + 0.3 * np.sin(2.1 * ph)) * np.exp(-x * 11) + level * 0.25 * bandnoise(len(x), 60, 900) * np.exp(-x * 25)


def place(buf, sound, at):
    i = int(at * SR)
    j = min(len(buf), i + len(sound))
    buf[i:j] += sound[:j - i]


def motor(seconds, spin=None):
    """The winch heard through the shaft: 100 Hz mains hum with its harmonics, the worm gear's whine, rope and roller rumble.
    spin(x) in 0..1 scales the speed (start and stop)."""
    x = t(seconds)
    s = np.ones_like(x) if spin is None else spin(x)
    ph = 2 * np.pi * np.cumsum(100 * (0.4 + 0.6 * s)) / SR
    hum = 0.5 * np.sin(ph) + 0.25 * np.sin(2 * ph + 0.3) + 0.12 * np.sin(3 * ph + 1.1) + 0.06 * np.sin(5 * ph)
    whine = 0.08 * np.sin(2 * np.pi * np.cumsum(410 * s) / SR) * (0.7 + 0.3 * np.sin(2 * np.pi * 0.5 * x))
    rumble = 0.35 * bandnoise(len(x), 40, 260) * (0.8 + 0.2 * np.sin(2 * np.pi * 1.25 * x))
    rattle = 0.05 * bandnoise(len(x), 1500, 4000) * (np.sin(2 * np.pi * 6 * x) > 0.6)
    return (hum * s + whine + rumble * s + rattle * s) * 0.6


def motor_loop():
    """4 s; the noise parts wrap seamlessly because band noise from an FFT is periodic over the buffer."""
    return motor(4.0)


def start():
    out = np.zeros(int(1.6 * SR))
    place(out, click(0.02, 1800, 300, 0.9), 0.0)          # the contactor
    place(out, click(0.015, 2600, 500, 0.6), 0.06)
    place(out, thump(0.3, 55, 0.7), 0.12)                 # the brake lets go
    m = motor(1.45, lambda x: np.clip(x / 1.1, 0, 1) ** 0.7)
    place(out, m * env(len(m), 0.15, 0.25), 0.12)
    return out


def stop():
    out = np.zeros(int(1.4 * SR))
    m = motor(0.7, lambda x: np.clip(1 - x / 0.7, 0, 1))
    place(out, m * env(len(m), 0.0, 0.1), 0.0)
    place(out, click(0.02, 1500, 260, 0.9), 0.62)          # contactor off
    place(out, thump(0.5, 48, 1.0), 0.66)                  # the brake clamps: the cabin's shudder
    place(out, 0.3 * bandnoise(int(0.3 * SR), 200, 2500) * np.exp(-t(0.3) * 18), 0.66)
    return out


def door(close):
    """The door operator: a small motor whirring up and down, the rollers on the track; the old doors end with a slam."""
    n = int((1.3 if close else 1.1) * SR)
    x = t(n / SR)
    run = np.clip(np.sin(np.pi * np.clip(x / 0.85, 0, 1)), 0, 1)
    ph = 2 * np.pi * np.cumsum(170 + 140 * run) / SR
    whir = 0.3 * (np.sin(ph) + 0.4 * np.sin(2 * ph)) * run
    roll = 0.45 * bandnoise(n, 150, 1800) * run * (0.85 + 0.15 * np.sin(2 * np.pi * 11 * x))
    out = whir + roll
    if close:
        place(out, thump(0.45, 62, 1.3), 0.82)             # бах
        place(out, 0.6 * bandnoise(int(0.2 * SR), 300, 5000) * np.exp(-t(0.2) * 30), 0.82)
        place(out, click(0.01, 3000, 600, 0.4), 0.9)
    else:
        place(out, thump(0.2, 90, 0.35), 0.86)
    return out


def button():
    out = np.zeros(int(0.12 * SR))
    place(out, click(0.01, 3200, 900, 0.8), 0)
    place(out, click(0.012, 2200, 700, 0.6), 0.05)
    return out


def relay():
    out = np.zeros(int(0.15 * SR))
    place(out, click(0.012, 2000, 500, 0.6), 0)
    place(out, click(0.012, 1700, 500, 0.45), 0.035)
    return lowpass(out, 3500)


def bell():
    """An electric bell: a striker hammering a gong at 22 Hz, two inharmonic partials."""
    x = t(1.6)
    strike = (np.sin(2 * np.pi * 22 * x) > 0.2).astype(float)
    strike = lowpass(strike, 300)
    tone = np.sin(2 * np.pi * 1870 * x) + 0.6 * np.sin(2 * np.pi * 2710 * x) + 0.3 * np.sin(2 * np.pi * 4120 * x)
    return 0.5 * tone * strike * env(len(x), 0.01, 0.4)


def write_ogg(path, samples, peak=0.85):
    samples = samples / (np.abs(samples).max() + 1e-9) * peak
    pcm = (samples * 32767).astype(np.int16)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        with wave.open(tmp.name, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(pcm.tobytes())
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", tmp.name, "-c:a", "libvorbis", "-q:a", "5", path], check=True)
        os.unlink(tmp.name)


SOUNDS = {   # id: (maker, peak, subtitle)
    "lift_motor": (motor_loop, 0.6, "lift_motor"),
    "lift_start": (start, 0.85, "lift_start"),
    "lift_stop": (stop, 0.85, "lift_stop"),
    "lift_door_open": (lambda: door(False), 0.7, "lift_door_open"),
    "lift_door_close": (lambda: door(True), 0.9, "lift_door_close"),
    "lift_button": (button, 0.7, "lift_button"),
    "lift_relay": (relay, 0.6, "lift_relay"),
    "lift_bell": (bell, 0.8, "lift_bell"),
}


def main():
    out = os.path.join(ASSETS, "sounds", "lift")
    os.makedirs(out, exist_ok=True)
    p = os.path.join(ASSETS, "sounds.json")
    with open(p, encoding="utf-8") as f:
        table = json.load(f)
    for sid, (make, peak, sub) in SOUNDS.items():
        name = sid.removeprefix("lift_")
        write_ogg(os.path.join(out, name + ".ogg"), make(), peak)
        table[sid] = {"subtitle": f"subtitles.mobtalk.{sub}", "sounds": [{"name": f"mobtalk:lift/{name}"}]}
        print("sound", sid)
    with open(p, "w", encoding="utf-8") as f:
        json.dump(table, f, indent=2, ensure_ascii=False)
        f.write("\n")


if __name__ == "__main__":
    main()
