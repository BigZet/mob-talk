"""Step 1: extract 16 kHz mono WAV from the original OBS recordings.

Only originals are used: files whose names contain tg/telegram/highlights are
re-encoded cuts of the same material and would duplicate speech.
"""
from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path

from common import SAMPLE_RATE, ffmpeg, work_dir

DERIVED = re.compile(r"(tg|telegram|highlights)", re.IGNORECASE)
NAME = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}-\d{2}-\d{2})\.mp4$")


def originals(videos: Path) -> list[Path]:
    return sorted(path for path in videos.glob("*.mp4") if NAME.match(path.name) and not DERIVED.search(path.name))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--videos", type=Path, default=Path.home() / "Videos")
    parser.add_argument("--min-seconds", type=float, default=60.0, help="skip tiny test recordings")
    args = parser.parse_args()
    target = work_dir() / "audio"
    target.mkdir(parents=True, exist_ok=True)
    probe = ffmpeg("ffprobe")
    for video in originals(args.videos):
        duration = float(subprocess.run([probe, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                                         str(video)], capture_output=True, text=True, check=True).stdout.strip())
        if duration < args.min_seconds:
            print(f"skip {video.name}: {duration:.0f} s")
            continue
        wav = target / (NAME.match(video.name).group(1) + ".wav")
        if wav.exists() and wav.stat().st_size > 0:
            print(f"have {wav.name}")
            continue
        print(f"extract {video.name} ({duration / 60:.1f} min)")
        subprocess.run([ffmpeg(), "-v", "error", "-y", "-i", str(video), "-vn", "-ac", "1", "-ar", str(SAMPLE_RATE),
                        "-c:a", "pcm_s16le", str(wav)], check=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
