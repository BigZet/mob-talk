"""The police sounds from real Russian recordings, cut into variations, and the loudhailer lines.

Sirens: two CC0 recordings from Freesound (credits in docs/police.md):
  - nikitralala, «russian_police.flac» (freesound.org/s/239948): a modern patrol car's SGU in St Petersburg, 9 May 2014;
  - Selector, «old soviet police sirens» (freesound.org/s/682859): Soviet police sirens.
Each cut is cleaned (the crowd and the wind under the siren are cut off below the horn's range, a light denoise), faded at the
ends, levelled and written mono (Minecraft only places mono sounds in the world). Minecraft picks one variant at random each time.

Loudhailer: the lines below, each voiced once by three of five Russian ElevenLabs voices from the mod's catalog (eleven-v3 through
OpenRouter, a one-off paid run; the English tag sets the delivery and is not read). `jobs` prints the job list (name, voice id,
text) for that run; the takes come back as NAME.mp3 and are put through a horn here: a narrow band, a soft clip, a metal ring and
the street's echo.

    python3 -I tools/police/sounds.py jobs > jobs.txt
    python3 -I tools/police/sounds.py SRC_DIR TAKES_DIR

SRC_DIR holds the two Freesound files (any audio format ffmpeg reads), named by their sound id first (239948_*, 682859_*);
TAKES_DIR the voiced takes (alarm_1_egor.mp3 and so on).
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
# The loudhailer's Russian voices (ElevenLabs ids from assets/mobtalk/voices.json): each line gets three of them in turn.
VOICES = {"egor": "6A9D8WSMm4rFsg2DWFeE", "nester": "pM78bgjPVk0JXtaEnFoj", "evelon": "mOTbMAOniC3yoEvgo4bi",
          "maxim": "6YjFofyVXU1ASguf2hTJ", "vladimir": "NkBkAMqIBNjZUjXKAA7r"}
# (name, text): to the crowd in an alarm, to an offender in a chase, at the arrest, and to a driver (the road cop, later: the stop
# order the traffic rules, п. 6.11, let a patrol give by loudspeaker).
LINES = [
    ("alarm_1", "[commanding] Внимание! Говорит полиция! Граждане, соблюдайте общественный порядок!"),
    ("alarm_2", "[stern] Граждане, не скапливайтесь! Проходите, не задерживайтесь!"),
    ("alarm_3", "[commanding] Внимание! Освободите проезжую часть!"),
    ("chase_1", "[shouting] Стоять! Полиция!"),
    ("chase_2", "[shouting] Остановитесь! Работает полиция!"),
    ("chase_3", "[shouting] Не двигаться! Полиция!"),
    ("arrest_1", "[shouting] Лежать! Руки за голову!"),
    ("arrest_2", "[angry] Руки на капот! Ноги на ширину плеч!"),
    ("arrest_3", "[stern] Вы задержаны! Не оказывайте сопротивления!"),
    ("road_1", "[commanding] Водитель «Жигулей», прижмитесь вправо и остановитесь!"),
    ("road_2", "[stern] Водитель, принять вправо! Остановиться!"),
    ("road_3", "[commanding] Водители, освободите левую полосу!"),
]


def takes():
    """(take name, voice name, voice id, text) for every line in its three voices."""
    names = list(VOICES)
    for i, (name, text) in enumerate(LINES):
        for k in range(3):
            voice = names[(i + k) % len(names)]
            yield f"{name}_{voice}", voice, VOICES[voice], text


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


def horn(take, out):
    trim = "silenceremove=start_periods=1:start_threshold=-45dB,areverse,silenceremove=start_periods=1:start_threshold=-45dB,areverse"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", take, "-af", trim + "," + HORN + ",apad=pad_dur=0.35,loudnorm=I=-14:TP=-1.5",
                    "-ar", "44100", "-ac", "1", "-c:a", "libvorbis", "-q:a", "5", out], check=True)


def main(args):
    if args == ["jobs"]:
        for take, _, voice_id, text in takes():
            print(f"{take}\t{voice_id}\t{text}")
        return
    if len(args) != 2:
        sys.exit(__doc__)
    src_dir, takes_dir = args
    os.makedirs(OUT, exist_ok=True)
    for name, sid, start, end in SIRENS + QUACKS:
        cut(source(src_dir, sid), start, end, os.path.join(OUT, name + ".ogg"))
    for old in glob.glob(os.path.join(OUT, "megaphone_*.ogg")):
        os.remove(old)
    for take, _, _, _ in takes():
        horn(os.path.join(takes_dir, take + ".mp3"), os.path.join(OUT, "megaphone_" + take + ".ogg"))
    print("written to", os.path.normpath(OUT))


if __name__ == "__main__":
    main(sys.argv[1:])
