"""Step 6: fine-tune Whisper (LoRA by default) on dataset/train.jsonl, Russian transcription.

Fits next to a running game: fp16 autocast, gradient checkpointing, small batch with
gradient accumulation. Validation WER (greedy) is measured before training and
after each epoch; the best adapter is kept in runs/<name>/best.
"""
from __future__ import annotations

import argparse
import json
import math
import random
import time

import jiwer
import numpy as np
import soundfile as sf
import torch
from torch.utils.data import DataLoader, Dataset

from common import read_jsonl, wer_text, work_dir


class Clips(Dataset):
    def __init__(self, rows, root, processor):
        self.rows, self.root, self.processor = rows, root, processor

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, index):
        row = self.rows[index]
        audio, rate = sf.read(self.root / row["path"], dtype="float32")
        features = self.processor.feature_extractor(audio, sampling_rate=rate, return_tensors="np").input_features[0]
        labels = self.processor.tokenizer(row["text"]).input_ids
        return features, labels, row["text"]


class Collate:
    """Picklable for DataLoader workers on Windows (spawn)."""

    def __init__(self, decoder_start):
        self.decoder_start = decoder_start

    def __call__(self, batch):
        features = torch.from_numpy(np.stack([item[0] for item in batch]))
        longest = max(len(item[1]) for item in batch)
        labels = torch.full((len(batch), longest), -100, dtype=torch.long)
        for row, (_, ids, _) in enumerate(batch):
            labels[row, :len(ids)] = torch.tensor(ids)
        if (labels[:, 0] == self.decoder_start).all():
            labels = labels[:, 1:]  # the model prepends <|startoftranscript|> itself
        return features, labels, [item[2] for item in batch]


def evaluate(model, loader, processor, device, limit_batches=0):
    model.eval()
    references, hypotheses = [], []
    with torch.no_grad(), torch.autocast("cuda", dtype=torch.float16, enabled=device == "cuda"):
        for number, (features, _, texts) in enumerate(loader):
            if limit_batches and number >= limit_batches:
                break
            generated = model.generate(input_features=features.to(device), language="ru", task="transcribe",
                                       num_beams=1, max_new_tokens=160)
            hypotheses += processor.batch_decode(generated, skip_special_tokens=True)
            references += texts
    model.train()
    pairs = [(wer_text(r), wer_text(h)) for r, h in zip(references, hypotheses) if wer_text(r)]
    return jiwer.wer([p[0] for p in pairs], [p[1] for p in pairs]), list(zip(references, hypotheses))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="openai/whisper-small")
    parser.add_argument("--name", default="mobtalk-small-ru")
    parser.add_argument("--full", action="store_true", help="full fine-tune instead of LoRA")
    parser.add_argument("--rank", type=int, default=32)
    parser.add_argument("--epochs", type=int, default=4)
    parser.add_argument("--batch", type=int, default=8)
    parser.add_argument("--accumulate", type=int, default=2)
    parser.add_argument("--lr", type=float, default=None)
    parser.add_argument("--warmup", type=int, default=15)
    parser.add_argument("--max-minutes", type=float, default=110)
    parser.add_argument("--seed", type=int, default=13)
    parser.add_argument("--dtype", choices=("float32", "float16"), default="float32",
                        help="base weights; float16 halves VRAM for LoRA on large models")
    args = parser.parse_args()
    from transformers import WhisperForConditionalGeneration, WhisperProcessor

    random.seed(args.seed)
    torch.manual_seed(args.seed)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    root = work_dir()
    out = root / "runs" / args.name
    out.mkdir(parents=True, exist_ok=True)
    cache = str(root / "cache" / "hf")
    processor = WhisperProcessor.from_pretrained(args.base, cache_dir=cache, language="russian", task="transcribe")
    model = WhisperForConditionalGeneration.from_pretrained(args.base, cache_dir=cache,
                                                            dtype=getattr(torch, args.dtype))
    model.config.forced_decoder_ids = None
    model.generation_config.forced_decoder_ids = None
    model.config.apply_spec_augment = True
    model.config.mask_time_prob = 0.05
    model.config.mask_feature_prob = 0.05
    model.config.use_cache = False
    if not args.full:
        from peft import LoraConfig, get_peft_model
        model = get_peft_model(model, LoraConfig(r=args.rank, lora_alpha=2 * args.rank, lora_dropout=0.05,
            target_modules=["q_proj", "k_proj", "v_proj", "out_proj", "fc1", "fc2"], bias="none"))
        model.print_trainable_parameters()
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False}) \
        if args.full else model.base_model.model.gradient_checkpointing_enable(
            gradient_checkpointing_kwargs={"use_reentrant": False})
    model.to(device)

    train_rows = read_jsonl(root / "dataset" / "train.jsonl")
    val_rows = read_jsonl(root / "dataset" / "val.jsonl")
    start = processor.tokenizer.convert_tokens_to_ids("<|startoftranscript|>")
    train = DataLoader(Clips(train_rows, root, processor), batch_size=args.batch, shuffle=True,
                       collate_fn=Collate(start), num_workers=2, persistent_workers=True)
    val = DataLoader(Clips(val_rows, root, processor), batch_size=8, collate_fn=Collate(start), num_workers=2)
    lr = args.lr or (1e-5 if args.full else 3e-4)
    parameters = [p for p in model.parameters() if p.requires_grad]
    optimizer = torch.optim.AdamW(parameters, lr=lr, weight_decay=0.01)
    steps = math.ceil(len(train) / args.accumulate) * args.epochs
    schedule = torch.optim.lr_scheduler.LambdaLR(optimizer, lambda s: min(1.0, (s + 1) / args.warmup)
                                                 * max(0.0, (steps - s) / max(1, steps - args.warmup)))
    scaler = torch.amp.GradScaler(enabled=device == "cuda")

    history = {"args": vars(args), "train_clips": len(train_rows), "val_clips": len(val_rows), "epochs": []}
    base_wer, _ = evaluate(model, val, processor, device)
    history["val_wer_before"] = round(base_wer, 4)
    print(f"val WER before: {base_wer:.4f}", flush=True)
    best = base_wer
    started = time.monotonic()
    step = 0
    stop = False
    for epoch in range(args.epochs):
        running = 0.0
        for index, (features, labels, _) in enumerate(train):
            with torch.autocast("cuda", dtype=torch.float16, enabled=device == "cuda"):
                loss = model(input_features=features.to(device), labels=labels.to(device)).loss / args.accumulate
            scaler.scale(loss).backward()
            running += loss.item()
            if (index + 1) % args.accumulate == 0 or index + 1 == len(train):
                scaler.unscale_(optimizer)
                torch.nn.utils.clip_grad_norm_(parameters, 1.0)
                scaler.step(optimizer)
                scaler.update()
                optimizer.zero_grad(set_to_none=True)
                schedule.step()
                step += 1
                if step % 25 == 0:
                    peak = torch.cuda.max_memory_allocated() / 2**20 if device == "cuda" else 0
                    print(f"epoch {epoch + 1} step {step}/{steps} loss {running / 25:.4f} "
                          f"lr {schedule.get_last_lr()[0]:.2e} peak {peak:.0f} MiB "
                          f"{(time.monotonic() - started) / 60:.1f} min", flush=True)
                    running = 0.0
            if (time.monotonic() - started) / 60 > args.max_minutes:
                print("time budget reached", flush=True)
                stop = True
                break
        wer, samples = evaluate(model, val, processor, device)
        history["epochs"].append({"epoch": epoch + 1, "val_wer": round(wer, 4), "minutes":
                                  round((time.monotonic() - started) / 60, 1)})
        print(f"epoch {epoch + 1}: val WER {wer:.4f}", flush=True)
        if wer < best:
            best = wer
            model.save_pretrained(out / "best")
            processor.save_pretrained(out / "best")
            with (out / "val-samples.txt").open("w", encoding="utf-8") as target:
                for reference, hypothesis in samples:
                    target.write(f"REF: {reference}\nHYP: {hypothesis}\n\n")
        if stop:
            break
    history["val_wer_best"] = round(best, 4)
    history["peak_mib"] = round(torch.cuda.max_memory_allocated() / 2**20) if device == "cuda" else 0
    (out / "history.json").write_text(json.dumps(history, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(history, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
