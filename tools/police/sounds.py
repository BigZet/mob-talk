"""The police sounds from real Russian recordings, cut into variations, and the loudhailer lines.

Sirens: two CC0 recordings from Freesound (credits in docs/police.md):
  - nikitralala, «russian_police.flac» (freesound.org/s/239948): a modern patrol car's SGU in St Petersburg, 9 May 2014;
  - Selector, «old soviet police sirens» (freesound.org/s/682859): Soviet police sirens.
Each cut is cleaned (the crowd and the wind under the siren are cut off below the horn's range, a light denoise), faded at the
ends, levelled and written mono (Minecraft only places mono sounds in the world). Minecraft picks one variant at random each time.

Loudhailer: short lines read by the Piper voices ru_RU-dmitri / ru_RU-denis (datasets CC0), then put through a horn: a narrow band,
a hard clip, a metal ring and the street's echo.

    python3 -I tools/police/sounds.py SRC_DIR PIPER_BIN VOICES_DIR

SRC_DIR holds the two Freesound files (any audio format ffmpeg reads), named by their sound id first (239948_*, 682859_*).
"""
import glob
import os
import subprocess
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "src", "main", "resources", "assets", "mobtalk", "sounds", "police")

# (name, source id, start s, end s): the alarm sirens.
SIRENS = [
    ("siren_soviet_1", "682859", 6.35, 14.55),   # the Soviet wail: a fast rise, a long fall, over and over
    ("siren_soviet_2", "682859", 14.55, 22.85),
    ("siren_soviet_3", "682859", 52.70, 57.95),  # the two-tone warble
    ("siren_modern_1", "239948", 28.55, 36.80),  # the patrol car's wail by hand: down, then up to the top
]
# The «кряк»: the patrol car's short buzzing call to a driver, each burst on its own (the rest of that recording is the crowd).
QUACKS = [
    ("quack_1", "239948", 27.83, 28.05),
    ("quack_2", "239948", 28.12, 28.47),
    ("quack_3", "239948", 52.62, 52.95),
    ("quack_4", "239948", 53.02, 53.48),
]
# The loudhailer: (name, voice, text). The arrest, the alarm, the cell, the road.
LINES = [
    ("megaphone_stop_1", "dmitri", "Водитель, прижмитесь к обочине и остановитесь!"),
    ("megaphone_stop_2", "denis", "Внимание! Водитель, остановитесь!"),
    ("megaphone_police_1", "dmitri", "Внимание! Работает полиция!"),
    ("megaphone_police_2", "denis", "Стоять! Полиция!"),
    ("megaphone_disperse_1", "denis", "Граждане, расходитесь! Не мешайте работе полиции!"),
    ("megaphone_disperse_2", "denis", "Граждане, освободите проезжую часть!"),
    ("megaphone_hands_1", "denis", "Руки за голову! Лицом к машине!"),
    ("megaphone_hands_2", "denis", "Оставайтесь на месте! Руки, чтобы я видел!"),
    ("megaphone_give_up_1", "dmitri", "Вы окружены! Сдавайтесь!"),
    ("megaphone_give_up_2", "denis", "Сопротивление бесполезно! Выходите с поднятыми руками!"),
]

CLEAN = "highpass=f=480,lowpass=f=5200,afftdn=nr=14:nf=-38,acompressor=threshold=0.35:ratio=2.5:attack=5:release=120"
HORN = ("highpass=f=400,lowpass=f=3800,equalizer=f=1700:t=q:w=1.2:g=6,volume=2.5,"
        "asoftclip=type=tanh,aecho=0.8:0.6:24|130|260:0.35|0.22|0.10,highpass=f=420,lowpass=f=3800")


def source(src_dir, sound_id):
    found = sorted(glob.glob(os.path.join(src_dir, sound_id + "_*")))
    if not found:
        sys.exit("no file " + sound_id + "_* in " + src_dir)
    return found[0]


def cut(src, start, end, out, fade=0.12):
    length = end - start
    fade = min(fade, length / 8)
    chain = (f"{CLEAN},afade=t=in:d={fade},afade=t=out:st={length - fade * 2:.3f}:d={fade * 2},"
             "loudnorm=I=-14:TP=-1.5:LRA=11")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-ss", f"{start}", "-t", f"{length}", "-i", src, "-af", chain,
                    "-ar", "44100", "-ac", "1", "-c:a", "libvorbis", "-q:a", "5", out], check=True)


def line(piper, voices, voice, text, out, tmp):
    raw = os.path.join(tmp, "line.wav")
    model = os.path.join(voices, f"ru_RU-{voice}-medium.onnx")
    subprocess.run([piper, "-m", model, "-f", raw, "--length-scale", "1.0", "--sentence-silence", "0.1"], input=text.encode("utf-8"),
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", raw, "-af", HORN + ",apad=pad_dur=0.35,loudnorm=I=-14:TP=-1.5",
                    "-ar", "44100", "-ac", "1", "-c:a", "libvorbis", "-q:a", "5", out], check=True)
    os.remove(raw)


def main(args):
    if len(args) != 3:
        sys.exit(__doc__)
    src_dir, piper, voices = args
    os.makedirs(OUT, exist_ok=True)
    for name, sid, start, end in SIRENS + QUACKS:
        cut(source(src_dir, sid), start, end, os.path.join(OUT, name + ".ogg"))
    for name, voice, text in LINES:
        line(piper, voices, voice, text, os.path.join(OUT, name + ".ogg"), OUT)
    print("written to", os.path.normpath(OUT))


if __name__ == "__main__":
    main(sys.argv[1:])
