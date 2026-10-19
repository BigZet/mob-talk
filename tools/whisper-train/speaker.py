"""Step 4: tell the player's voice from mob TTS voices, music and game sounds.

1. Text tags: a clip whose pseudo-label matches a nearby chat line "Вы: …" is the
   player; one that matches a mob reply is TTS (the mod logs both, see chatlog.py).
2. Speaker embeddings (SpeechBrain ECAPA, VoxCeleb) for every clip.
3. A logistic regression on the tagged embeddings scores every clip; the
   threshold keeps >= 98 % precision on held-out tagged clips.
Writes speaker.jsonl: id, tag (user/mob/""), match ratio, user score.
"""
from __future__ import annotations

import argparse
import bisect
from datetime import datetime, timedelta
from difflib import SequenceMatcher

import numpy as np
import soundfile as sf
import torch

from chatlog import read_chat
from common import read_jsonl, wer_text, work_dir, write_jsonl


def similarity(clip: str, line: str) -> tuple[float, int]:
    """(ratio, characters in shared runs of >= 5 characters)."""
    matcher = SequenceMatcher(None, clip, line, autojunk=False)
    shared = sum(block.size for block in matcher.get_matching_blocks() if block.size >= 5)
    return matcher.ratio(), shared


def tag_clips(clips, labels, chat):
    """user: the clip is a logged "Вы:" phrase; mob: the clip contains a logged mob reply
    (also when the player talks over the TTS, so mixed clips are rejected)."""
    times = [entry["time"] for entry in chat]
    texts = [wer_text(entry["text"]) for entry in chat]
    tags = {}
    for clip in clips:
        text = wer_text(labels.get(clip["id"], {}).get("text", ""))
        if len(text) < 6:
            continue
        start = datetime.fromisoformat(clip["wallclock"])
        end = start + timedelta(seconds=clip["duration"])
        low = bisect.bisect_left(times, start - timedelta(seconds=45))
        high = bisect.bisect_right(times, end + timedelta(seconds=60))
        user, mob = 0.0, 0.0
        for index in range(low, high):
            ratio, shared = similarity(text, texts[index])
            if chat[index]["speaker"] == "user":
                user = max(user, ratio, shared / len(text) if len(text.split()) >= 4 else 0.0)
            elif shared >= 20 or shared >= 0.6 * len(text):
                mob = max(mob, shared / len(text))
        if mob >= 0.3:
            tags[clip["id"]] = ("mob", round(mob, 3))
        elif user >= 0.75:
            tags[clip["id"]] = ("user", round(user, 3))
    return tags


def embed(clips, root, device):
    from huggingface_hub import snapshot_download
    from speechbrain.inference.speaker import EncoderClassifier
    from speechbrain.utils.fetching import LocalStrategy
    source = snapshot_download("speechbrain/spkrec-ecapa-voxceleb", cache_dir=str(root / "cache" / "hf"))
    model = EncoderClassifier.from_hparams(source,
                                           savedir=str(root / "cache" / "ecapa"), run_opts={"device": device},
                                           local_strategy=LocalStrategy.COPY)
    vectors = np.zeros((len(clips), 192), dtype=np.float32)
    with torch.no_grad():
        for index, clip in enumerate(clips):
            audio, _ = sf.read(root / clip["path"], dtype="float32")
            batch = torch.from_numpy(audio).unsqueeze(0).to(device)
            vectors[index] = model.encode_batch(batch).squeeze().cpu().numpy()
            if (index + 1) % 500 == 0:
                print(f"embedded {index + 1}/{len(clips)}", flush=True)
    return vectors / np.linalg.norm(vectors, axis=1, keepdims=True)


def fit_logistic(x, y, epochs=400):
    weights = torch.zeros(x.shape[1], requires_grad=True)
    bias = torch.zeros(1, requires_grad=True)
    xs, ys = torch.from_numpy(x), torch.from_numpy(y.astype(np.float32))
    positive = float(ys.mean())
    balance = torch.where(ys > 0, 0.5 / max(positive, 1e-6), 0.5 / max(1 - positive, 1e-6))
    optimizer = torch.optim.LBFGS([weights, bias], max_iter=epochs)

    def closure():
        optimizer.zero_grad()
        loss = torch.nn.functional.binary_cross_entropy_with_logits(xs @ weights + bias, ys, weight=balance)
        loss = loss + 1e-3 * weights.pow(2).sum()
        loss.backward()
        return loss

    optimizer.step(closure)
    return lambda v: torch.sigmoid(torch.from_numpy(v) @ weights + bias).detach().numpy()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--precision", type=float, default=0.98)
    parser.add_argument("--embed-only", action="store_true", help="only cache embeddings (no labels needed)")
    args = parser.parse_args()
    torch.set_num_threads(6)
    root = work_dir()
    clips = read_jsonl(root / "clips.jsonl")
    cache = root / "embeddings.npy"
    ids_cache = root / "embeddings.ids"
    if cache.exists() and ids_cache.read_text(encoding="utf-8").split("\n") == [c["id"] for c in clips]:
        vectors = np.load(cache)
    else:
        vectors = embed(clips, root, args.device)
        np.save(cache, vectors)
        ids_cache.write_text("\n".join(c["id"] for c in clips), encoding="utf-8")
    if args.embed_only:
        return 0
    labels = {row["id"]: row for row in read_jsonl(root / "labels.jsonl")}
    keep = [index for index, clip in enumerate(clips) if clip["id"] in labels]
    clips, vectors = [clips[index] for index in keep], vectors[keep]
    tags = tag_clips(clips, labels, read_chat())
    print(f"text tags: {sum(t[0] == 'user' for t in tags.values())} player, "
          f"{sum(t[0] == 'mob' for t in tags.values())} mob of {len(clips)} clips")
    tagged = [i for i, clip in enumerate(clips) if clip["id"] in tags]
    y = np.array([tags[clips[i]["id"]][0] == "user" for i in tagged])
    # Hold out every fifth tagged clip to choose the threshold honestly.
    rng = np.random.default_rng(7)
    order = rng.permutation(len(tagged))
    held, train = order[::5], np.setdiff1d(order, order[::5])
    score = fit_logistic(vectors[[tagged[i] for i in train]], y[train])
    held_scores = score(vectors[[tagged[i] for i in held]])
    threshold = 0.5
    for candidate in np.linspace(0.3, 0.99, 70):
        chosen = held_scores >= candidate
        if chosen.sum() and y[held][chosen].mean() >= args.precision:
            threshold = float(candidate)
            break
    chosen = held_scores >= threshold
    precision = float(y[held][chosen].mean()) if chosen.sum() else 0.0
    recall = float(chosen[y[held]].mean()) if y[held].sum() else 0.0
    print(f"held-out tagged clips: {len(held)}, threshold {threshold:.2f}, precision {precision:.3f}, "
          f"recall {recall:.3f}")
    score = fit_logistic(vectors[tagged], y)  # final model on all tags
    scores = score(vectors)
    rows = [{"id": clip["id"], "tag": tags.get(clip["id"], ("", 0))[0], "match": tags.get(clip["id"], ("", 0))[1],
             "user_score": round(float(s), 4)} for clip, s in zip(clips, scores)]
    write_jsonl(root / "speaker.jsonl", rows)
    (root / "speaker-threshold.txt").write_text(
        f"{threshold:.3f}\nprecision={precision:.3f} recall={recall:.3f} held={len(held)}\n", encoding="utf-8")
    print(f"clips scored as player: {(scores >= threshold).sum()} of {len(clips)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
