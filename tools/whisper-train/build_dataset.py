"""Step 5: filter labelled clips down to the player's clean speech and split train/val.

Keeps clips scored as the player's voice (speaker.py), drops TTS-matched text,
low-confidence or hallucinated labels, and normalizes names/terms. Validation is
~10 % of two-minute blocks, so neighbouring clips never straddle the split.
Writes dataset/train.jsonl, dataset/val.jsonl and dataset/stats.json.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter

from common import normalize_text, read_jsonl, wer_text, work_dir, write_jsonl

HALLUCINATIONS = re.compile(r"(субтитр|продолжение следует|спасибо за просмотр|подписывайтесь|редактор|"
                            r"корректор|dimatorzok|amara|благодарю за внимание|ставьте лайк|"
                            r"^\W*(музыка|аплодисменты|смех|динамичная музыка)\W*$)", re.IGNORECASE)


def reason(clip, label, speaker, threshold, args):
    text = label.get("text", "").strip()
    words = len(wer_text(text).split())
    if speaker.get("tag") == "mob":
        return "tts_text_match"
    if speaker.get("tag") != "user" and speaker.get("user_score", 0) < threshold:
        return "not_player_voice"
    if speaker.get("tag") == "user" and speaker.get("user_score", 0) < 0.3:
        return "voice_contradicts_text_tag"  # e.g. a mob repeating the player's words
    if not words:
        return "empty_text"
    if HALLUCINATIONS.search(text):
        return "hallucination"
    if label["no_speech_prob"] >= args.max_no_speech:
        return "no_speech"
    if label["avg_logprob"] < args.min_logprob:
        return "low_confidence"
    if label["compression_ratio"] > 2.2:
        return "repetitive"
    rate = len(wer_text(text)) / clip["duration"]
    if not 2.0 <= rate <= 25.0:
        return "implausible_rate"
    return ""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--min-logprob", type=float, default=-0.7)
    parser.add_argument("--max-no-speech", type=float, default=0.5)
    parser.add_argument("--val-share", type=int, default=10, help="percent of 2-minute blocks for validation")
    args = parser.parse_args()
    root = work_dir()
    clips = {row["id"]: row for row in read_jsonl(root / "clips.jsonl")}
    labels = {row["id"]: row for row in read_jsonl(root / "labels.jsonl")}
    speakers = {row["id"]: row for row in read_jsonl(root / "speaker.jsonl")}
    threshold = float((root / "speaker-threshold.txt").read_text(encoding="utf-8").split()[0])
    reasons, train, val = Counter(), [], []
    for clip_id, clip in clips.items():
        if clip_id not in labels or clip_id not in speakers:
            reasons["unlabelled"] += 1
            continue
        why = reason(clip, labels[clip_id], speakers[clip_id], threshold, args)
        if why:
            reasons[why] += 1
            continue
        row = {"id": clip_id, "path": clip["path"], "duration": clip["duration"],
               "raw": labels[clip_id]["text"], "text": normalize_text(labels[clip_id]["text"]),
               "tag": speakers[clip_id]["tag"], "user_score": speakers[clip_id]["user_score"],
               "avg_logprob": labels[clip_id]["avg_logprob"]}
        block = f"{clip['recording']}/{int(clip['start'] // 120)}"
        bucket = int(hashlib.sha1(block.encode()).hexdigest(), 16) % 100
        (val if bucket < args.val_share else train).append(row)
    write_jsonl(root / "dataset" / "train.jsonl", train)
    write_jsonl(root / "dataset" / "val.jsonl", val)
    total = sum(c["duration"] for c in clips.values())

    def hours(rows):
        return round(sum(r["duration"] for r in rows) / 3600, 3)

    stats = {"clips_total": len(clips), "clips_hours": round(total / 3600, 3),
             "train_clips": len(train), "train_hours": hours(train), "val_clips": len(val), "val_hours": hours(val),
             "rejected": dict(reasons.most_common()), "speaker_threshold": threshold,
             "changed_by_normalization": sum(r["raw"].strip() != r["text"] for r in train + val),
             "filters": {"min_logprob": args.min_logprob, "max_no_speech": args.max_no_speech,
                         "max_compression_ratio": 2.2, "chars_per_second": [2.0, 25.0]}}
    (root / "dataset" / "stats.json").write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(stats, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
