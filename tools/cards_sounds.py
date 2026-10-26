"""Звуки стола игры в 21 (docs/cards-21.md): синтез numpy, кодирование ffmpeg в ogg vorbis. Свои, лицензия — как у мода.

    python tools/cards_sounds.py    # src/client/resources/assets/mobtalk/sounds/cards/*.ogg

deal — карта скользит по сукну; flip — щелчок переворота; chips — фишки стукаются; shuffle — тасовка веером.
"""
import os
import subprocess
import tempfile
import wave

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "client", "resources", "assets", "mobtalk", "sounds", "cards")
RATE = 44100
rng = np.random.default_rng(21)


def t(seconds):
    return np.arange(int(RATE * seconds)) / RATE


def envelope(n, attack, decay):
    x = np.arange(n) / RATE
    env = np.minimum(1.0, x / max(attack, 1e-4)) * np.exp(-x / decay)
    return env


def lowpass(signal, cutoff):
    # Однополюсный фильтр: мягкий шелест вместо белого шума.
    a = np.exp(-2 * np.pi * cutoff / RATE)
    out = np.empty_like(signal)
    y = 0.0
    for i, v in enumerate(signal):
        y = (1 - a) * v + a * y
        out[i] = y
    return out


def highpass(signal, cutoff):
    return signal - lowpass(signal, cutoff)


def swish(length, cutoff_from, cutoff_to, gain):
    n = int(RATE * length)
    noise = rng.standard_normal(n)
    # Полоса шелеста ползёт сверху вниз: карта замедляется.
    parts = np.array_split(noise, 8)
    shaped = np.concatenate([highpass(lowpass(p, cutoff_from + (cutoff_to - cutoff_from) * i / 7), 600) for i, p in enumerate(parts)])
    x = np.arange(n) / n
    env = np.sin(np.pi * np.minimum(1, x * 1.4)) ** 1.5
    return shaped * env * gain


def click(freq, decay, gain):
    n = int(RATE * decay * 6)
    x = np.arange(n) / RATE
    tone = np.sin(2 * np.pi * freq * x) + 0.5 * np.sin(2 * np.pi * freq * 2.7 * x)
    noise = rng.standard_normal(n) * 0.3
    return (tone + noise) * envelope(n, 0.0005, decay) * gain


def place(buffer, sound, at):
    i = int(RATE * at)
    end = min(len(buffer), i + len(sound))
    buffer[i:end] += sound[:end - i]


def deal():
    buf = np.zeros(int(RATE * 0.3))
    place(buf, swish(0.22, 5200, 2400, 0.5), 0.0)
    place(buf, click(1800, 0.004, 0.25), 0.2)
    return buf


def flip():
    buf = np.zeros(int(RATE * 0.25))
    place(buf, swish(0.08, 6000, 4000, 0.35), 0.0)
    place(buf, click(2400, 0.003, 0.45), 0.07)
    place(buf, click(1300, 0.005, 0.25), 0.075)
    return buf


def chips():
    buf = np.zeros(int(RATE * 0.45))
    times = [0.0, 0.06, 0.1, 0.17, 0.21]
    for k, at in enumerate(times):
        place(buf, click(2900 + 230 * k + rng.uniform(-80, 80), 0.012, 0.5 - 0.06 * k), at)
    return buf


def shuffle():
    buf = np.zeros(int(RATE * 0.9))
    at = 0.02
    for k in range(26):
        place(buf, swish(0.03, 6500, 5000, 0.18), at)
        place(buf, click(2000 + rng.uniform(-300, 300), 0.002, 0.15), at + 0.02)
        at += 0.025 + 0.004 * np.sin(k)
    place(buf, swish(0.12, 3000, 1500, 0.4), at + 0.05)
    return buf


def write(name, signal):
    signal = signal / max(1e-6, np.max(np.abs(signal))) * 0.8
    pcm = (signal * 32767).astype(np.int16)
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, name + ".wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(pcm.tobytes())
        target = os.path.join(OUT, name + ".ogg")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "4", target], check=True)
        print(target)


def main():
    os.makedirs(OUT, exist_ok=True)
    write("deal", deal())
    write("flip", flip())
    write("chips", chips())
    write("shuffle", shuffle())


if __name__ == "__main__":
    main()
