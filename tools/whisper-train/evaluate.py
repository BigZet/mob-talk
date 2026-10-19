"""Step 8: compare models on dataset/val.jsonl the way the mod uses them.

Direct mode loads each CTranslate2 model with faster-whisper and decodes like
tools/whisper_server.py (beam 5, Silero VAD, hotwords, no previous text).
Server mode (--server URL) posts the clips to a running whisper_server.py.
Reports WER, CER, name/term accuracy and latency per clip; writes eval/<label>.json.
"""
from __future__ import annotations

import argparse
import gc
import io
import json
import logging
import re
import sys
import time
import urllib.request
from pathlib import Path

import jiwer

from common import HOTWORDS, NAMES, TERMS, read_jsonl, wer_text, work_dir
from label import enable_nvidia_dlls, resolve_model

SERVER_HOTWORDS = "Minecraft, Майнкрафт, крипер, зомби, скелет, пиглин, эндермен, варден, тихоня, нюхач, лавомерка"
# Single-word names and terms only: parts of "Тихий Шип" or "Пепельный След" are everyday words.
STEMS = sorted({wer_text(word)[:max(4, min(6, len(word) - 1))] for word in HOTWORDS + NAMES + TERMS
                if " " not in word and len(word) >= 4})


def term_hits(reference: str, hypothesis: str) -> tuple[int, int]:
    def counts(text):
        found = {}
        for word in text.split():
            for stem in STEMS:
                if word.startswith(stem):
                    found[stem] = found.get(stem, 0) + 1
        return found
    wanted, got = counts(reference), counts(hypothesis)
    return sum(min(n, got.get(stem, 0)) for stem, n in wanted.items()), sum(wanted.values())


def speech_text(segments) -> str:
    # Same filter as tools/whisper_server.py.
    parts = [s.text.strip() for s in segments if s.text.strip() and s.no_speech_prob < 0.6 and s.avg_logprob >= -1.0]
    return " ".join(parts)[:2000].strip()


def direct(model_name, device, rows, root, beam, hotwords):
    from faster_whisper import WhisperModel
    model = WhisperModel(resolve_model(model_name), device=device,
                         compute_type="int8_float16" if device == "cuda" else "int8", cpu_threads=4, num_workers=1)
    model.transcribe(str(root / rows[0]["path"]), language="ru", beam_size=1)  # warm-up
    results = []
    for row in rows:
        body = (root / row["path"]).read_bytes()
        started = time.perf_counter()
        segments, info = model.transcribe(io.BytesIO(body), language="ru", beam_size=beam, temperature=0.0,
            vad_filter=True, vad_parameters={"min_silence_duration_ms": 500, "speech_pad_ms": 250},
            condition_on_previous_text=False, hotwords=hotwords)
        text = speech_text(segments) if info.duration_after_vad > 0 else ""
        results.append((text, (time.perf_counter() - started) * 1000))
    del model
    gc.collect()
    return results


def configure(url, model, hotwords, beam):
    """Switch a running whisper_server.py like the mod does (POST /configure) and wait until ready."""
    body = json.dumps({"model": model, "device": "auto", "language": "ru", "beamSize": beam, "vadSilenceMs": 500,
                       "cpuThreads": 4, "hotwords": hotwords}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url + "/configure", data=body, method="POST",
                                     headers={"Content-Type": "application/json; charset=utf-8"})
    urllib.request.urlopen(request, timeout=10).read()
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        with urllib.request.urlopen(url + "/health", timeout=5) as response:
            health = json.loads(response.read())
        if health.get("ready") and health.get("model") == model and health.get("hotwords") == hotwords:
            return health
        if health.get("status") == "error":
            raise SystemExit(f"server could not load {model}: {health.get('error')}")
        time.sleep(0.5)
    raise SystemExit("server did not become ready")


def server(url, rows, root):
    results = []
    for row in rows:
        body = (root / row["path"]).read_bytes()
        request = urllib.request.Request(url + "/transcribe", data=body, method="POST",
                                         headers={"Content-Type": "audio/wav"})
        started = time.perf_counter()
        with urllib.request.urlopen(request, timeout=120) as response:
            answer = json.loads(response.read())
        results.append((answer.get("text", ""), (time.perf_counter() - started) * 1000))
    return results


def score(rows, results):
    references = [wer_text(r["text"]) for r in rows]
    hypotheses = [wer_text(text) for text, _ in results]
    pairs = [(r, h) for r, h in zip(references, hypotheses) if r]
    hits = [term_hits(r, h) for r, h in pairs]
    latencies = sorted(ms for _, ms in results)
    seconds = sum(r["duration"] for r in rows)
    return {"clips": len(rows), "wer": round(jiwer.wer([p[0] for p in pairs], [p[1] for p in pairs]), 4),
            "cer": round(jiwer.cer([p[0] for p in pairs], [p[1] for p in pairs]), 4),
            "terms": f"{sum(h[0] for h in hits)}/{sum(h[1] for h in hits)}",
            "term_accuracy": round(sum(h[0] for h in hits) / max(1, sum(h[1] for h in hits)), 4),
            "ms_mean": round(sum(latencies) / len(latencies), 1),
            "ms_p50": round(latencies[len(latencies) // 2], 1),
            "ms_p90": round(latencies[int(len(latencies) * 0.9)], 1),
            "rtf": round(sum(latencies) / 1000 / seconds, 4)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("models", nargs="*", default=["large-v3", "small"],
                        help="catalogue names, model folder names in the mod's models dir, or paths")
    parser.add_argument("--server", help="evaluate a running whisper_server.py, e.g. http://127.0.0.1:63920")
    parser.add_argument("--label", help="result name for --server")
    parser.add_argument("--configure", nargs="+", metavar="MODEL",
                        help="with --server: switch the server to each model in turn (POST /configure) and evaluate")
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--beam-size", type=int, default=5)
    parser.add_argument("--hotwords", default=SERVER_HOTWORDS)
    parser.add_argument("--split", default="val")
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args()
    enable_nvidia_dlls()
    logging.getLogger("faster_whisper").setLevel(logging.ERROR)
    root = work_dir()
    rows = read_jsonl(root / "dataset" / f"{args.split}.jsonl")
    if args.limit:
        rows = rows[:args.limit]
    out = root / "eval"
    out.mkdir(exist_ok=True)
    if args.server:
        runs = [(m, m) for m in args.configure] if args.configure else [(args.label or "server", None)]
    else:
        runs = [(Path(m).name or m, m) for m in args.models]
    for label, model in runs:
        if args.server and model:
            health = configure(args.server, model, args.hotwords, args.beam_size)
            print(f"server ready: {health['model']} on {health['device']}", flush=True)
        results = server(args.server, rows, root) if args.server else \
            direct(model, args.device, rows, root, args.beam_size, args.hotwords)
        summary = {"label": label, "mode": "server" if args.server else f"direct-{args.device}", **score(rows, results)}
        (out / f"{re.sub(r'[^A-Za-z0-9._-]', '_', label)}-{summary['mode']}.json").write_text(json.dumps(
            {"summary": summary, "samples": [{"id": r["id"], "ref": r["text"], "hyp": t, "ms": round(ms, 1)}
                                             for r, (t, ms) in zip(rows, results)]}, ensure_ascii=False, indent=1),
            encoding="utf-8")
        print(json.dumps(summary, ensure_ascii=False), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
