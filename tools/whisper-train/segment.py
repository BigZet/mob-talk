"""Step 2: cut each recording into speech clips with Silero VAD (bundled in faster-whisper).

Clips are 1..15 s, like push-to-talk phrases in the mod. Writes clips/<rec>/<n>.wav
and clips.jsonl with recording, start/end offsets and wall-clock start.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta

import numpy as np
import soundfile as sf
from faster_whisper.vad import VadOptions, get_speech_timestamps

from common import MAX_CLIP_SECONDS, SAMPLE_RATE, work_dir, write_jsonl


def merge(spans, max_gap: float, max_len: float):
    merged = []
    for start, end in spans:
        if merged and start - merged[-1][1] <= max_gap and end - merged[-1][0] <= max_len:
            merged[-1][1] = end
        else:
            merged.append([start, end])
    return merged


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--min-seconds", type=float, default=1.0)
    parser.add_argument("--merge-gap", type=float, default=0.35)
    args = parser.parse_args()
    root = work_dir()
    rows = []
    for wav in sorted((root / "audio").glob("*.wav")):
        audio, rate = sf.read(wav, dtype="float32")
        assert rate == SAMPLE_RATE
        options = VadOptions(threshold=0.5, min_silence_duration_ms=400, speech_pad_ms=150,
                             max_speech_duration_s=MAX_CLIP_SECONDS - 0.5, min_speech_duration_ms=400)
        spans = [(s["start"] / rate, s["end"] / rate) for s in get_speech_timestamps(audio, options)]
        spans = merge(spans, args.merge_gap, MAX_CLIP_SECONDS - 0.5)
        recorded = datetime.strptime(wav.stem, "%Y-%m-%d %H-%M-%S")
        out = root / "clips" / wav.stem.replace(" ", "_")
        out.mkdir(parents=True, exist_ok=True)
        kept = 0.0
        for number, (start, end) in enumerate(spans):
            if end - start < args.min_seconds:
                continue
            clip = audio[int(start * rate):int(end * rate)]
            path = out / f"{number:05d}.wav"
            sf.write(path, clip, rate, subtype="PCM_16")
            peak = float(np.abs(clip).max())
            rows.append({"id": f"{wav.stem.replace(' ', '_')}/{number:05d}", "recording": wav.stem,
                         "path": str(path.relative_to(root)).replace("\\", "/"), "start": round(start, 2),
                         "end": round(end, 2), "duration": round(end - start, 2), "peak": round(peak, 4),
                         "wallclock": (recorded + timedelta(seconds=start)).isoformat(timespec="seconds")})
            kept += end - start
        print(f"{wav.stem}: {len(audio) / rate / 60:.1f} min audio, {kept / 60:.1f} min speech-like clips")
    write_jsonl(root / "clips.jsonl", rows)
    print(f"total {len(rows)} clips, {sum(r['duration'] for r in rows) / 3600:.2f} h")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
