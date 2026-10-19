"""Step 3: pseudo-label clips with the current large-v3 (CTranslate2) model.

Labels are large-v3 output with a glossary prompt (names and game terms), so they
inherit large-v3 errors; see docs/whisper-finetune.md. Resumable: appends to
labels.jsonl and skips clips already labelled.
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import sys
import time
from pathlib import Path

from common import GLOSSARY_PROMPT, read_jsonl, work_dir


def enable_nvidia_dlls() -> None:
    if os.name != "nt":
        return
    root = Path(sys.prefix) / "Lib" / "site-packages" / "nvidia"
    dirs = sorted({p.parent for p in root.rglob("*.dll")}) if root.is_dir() else []
    torch_lib = Path(sys.prefix) / "Lib" / "site-packages" / "torch" / "lib"
    if torch_lib.is_dir():
        dirs.append(torch_lib)
    for directory in dirs:
        os.add_dll_directory(str(directory))
    os.environ["PATH"] = os.pathsep.join(map(str, dirs)) + os.pathsep + os.environ.get("PATH", "")


def mod_model_dirs() -> list[Path]:
    """The models folder the mod uses, found like WhisperProcess.findDirectory: the first
    MobTalk folder with venv/Scripts/python.exe, in %LOCALAPPDATA% or a packaged-app redirect
    (Codex installs land in Packages/OpenAI.Codex_*/LocalCache/Local/MobTalk)."""
    local = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    candidates = [local / "MobTalk"] + sorted((local / "Packages").glob("OpenAI.Codex_*/LocalCache/Local/MobTalk"))
    return [d / "models" for d in candidates if (d / "venv" / "Scripts" / "python.exe").is_file()][:1]


def resolve_model(name: str) -> str:
    """Local CTranslate2 directory for a catalogue name, a model folder name, or a path."""
    if Path(name).is_dir():
        return name
    repo = {"large-v3": "Systran--faster-whisper-large-v3", "small": "Systran--faster-whisper-small",
            "medium": "Systran--faster-whisper-medium",
            "large-v3-turbo": "mobiuslabsgmbh--faster-whisper-large-v3-turbo"}.get(name)
    for models in mod_model_dirs():
        if (models / name / "model.bin").exists():
            return str(models / name)
        if repo:
            for snapshot in sorted((models / f"models--{repo}").glob("snapshots/*")):
                if (snapshot / "model.bin").exists():
                    return str(snapshot)
    return name  # faster-whisper downloads catalogue names itself


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", default="large-v3")
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--compute-type", default=None)
    parser.add_argument("--threads", type=int, default=6)
    parser.add_argument("--beam-size", type=int, default=5)
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args()
    enable_nvidia_dlls()
    logging.getLogger("faster_whisper").setLevel(logging.ERROR)
    from faster_whisper import WhisperModel

    root = work_dir()
    out = root / "labels.jsonl"
    done = {row["id"] for row in read_jsonl(out)}
    clips = [row for row in read_jsonl(root / "clips.jsonl") if row["id"] not in done]
    if args.limit:
        clips = clips[:args.limit]
    compute = args.compute_type or ("int8_float16" if args.device == "cuda" else "int8")
    model = WhisperModel(resolve_model(args.model), device=args.device, compute_type=compute,
                         cpu_threads=args.threads, num_workers=1)
    started = time.monotonic()
    with out.open("a", encoding="utf-8") as target:
        for index, clip in enumerate(clips, 1):
            segments, info = model.transcribe(str(root / clip["path"]), language="ru", beam_size=args.beam_size,
                                              vad_filter=False, condition_on_previous_text=False,
                                              initial_prompt=GLOSSARY_PROMPT)
            segments = list(segments)
            text = " ".join(s.text.strip() for s in segments).strip()
            weight = sum(max(1, len(s.tokens)) for s in segments) or 1
            row = {"id": clip["id"], "text": text,
                   "avg_logprob": round(sum(s.avg_logprob * max(1, len(s.tokens)) for s in segments) / weight, 4)
                   if segments else -9.0,
                   "no_speech_prob": round(max((s.no_speech_prob for s in segments), default=1.0), 4),
                   "compression_ratio": round(max((s.compression_ratio for s in segments), default=0.0), 3),
                   "language_probability": round(info.language_probability, 3)}
            target.write(json.dumps(row, ensure_ascii=False) + "\n")
            if index % 100 == 0:
                target.flush()
                rate = index / (time.monotonic() - started)
                print(f"{index}/{len(clips)} clips, {rate:.1f}/s", flush=True)
    print(f"labelled {len(clips)} clips in {time.monotonic() - started:.0f} s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
