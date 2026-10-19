"""Step 7: merge the LoRA adapter and convert the model to CTranslate2 for faster-whisper.

The CTranslate2 folder is written to the work directory and, with --install, copied
into the mod's models directory as models/<name> (choose it with whisperModel=<name>).
int8_float16 weights load as int8_float16 on CUDA and as int8 on CPU.
"""
from __future__ import annotations

import argparse
import shutil
from pathlib import Path

import torch

from common import work_dir
from label import mod_model_dirs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", default="mobtalk-small-ru")
    parser.add_argument("--base", default="openai/whisper-small")
    parser.add_argument("--name", default="mobtalk-small-ru")
    parser.add_argument("--quantization", default="int8_float16")
    parser.add_argument("--install", type=Path, nargs="?", const=Path("auto"),
                        help="copy into the mod's models dir (auto-detected) or the given models dir")
    args = parser.parse_args()
    from ctranslate2.converters import TransformersConverter
    from transformers import WhisperForConditionalGeneration, WhisperProcessor

    root = work_dir()
    best = root / "runs" / args.run / "best"
    merged = root / "merged" / args.name
    processor = WhisperProcessor.from_pretrained(best)
    if (best / "adapter_config.json").exists():
        from peft import PeftModel
        base = WhisperForConditionalGeneration.from_pretrained(args.base, cache_dir=str(root / "cache" / "hf"),
                                                               dtype=torch.float32)
        model = PeftModel.from_pretrained(base, best).merge_and_unload()
    else:
        model = WhisperForConditionalGeneration.from_pretrained(best, dtype=torch.float32)
    model.config.use_cache = True
    model.generation_config.forced_decoder_ids = None
    model.save_pretrained(merged)
    processor.save_pretrained(merged)
    target = root / "ct2" / args.name
    TransformersConverter(str(merged), copy_files=["tokenizer.json", "preprocessor_config.json"]).convert(
        str(target), quantization=args.quantization, force=True)
    print(f"CTranslate2 model: {target}")
    if args.install:
        models = mod_model_dirs() if args.install == Path("auto") else [args.install]
        if not models:
            raise SystemExit("mod models directory not found; pass --install <models dir>")
        for directory in models:
            destination = directory / args.name
            if destination.exists():
                shutil.rmtree(destination)
            shutil.copytree(target, destination)
            print(f"installed: {destination}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
