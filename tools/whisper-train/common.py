"""Shared paths, glossary and text normalization for the Whisper fine-tune pipeline.

Audio, datasets and checkpoints live in the work directory (default
%LOCALAPPDATA%/MobTalk/train), never in the repository.
"""
from __future__ import annotations

import json
import os
import re
import shutil
from pathlib import Path

SAMPLE_RATE = 16000
MAX_CLIP_SECONDS = 15.0  # the mod never records longer than 15 s

# Hotwords of MobTalkConfig.whisperHotwords plus names heard in the recordings
# (timecode notes and mob names from the Minecraft chat log).
HOTWORDS = ["Minecraft", "Майнкрафт", "крипер", "зомби", "скелет", "пиглин", "эндермен", "варден",
            "тихоня", "нюхач", "лавомерка"]
NAMES = ["Ладко", "Прохор", "Аксинья", "Квасок", "Пескоступ", "Кострел", "Громорог", "Пепельный След",
         "Бледный Клык", "Медный Кулак", "Ржавый Порог", "Тихий Подрывник", "Старый Засов", "Книжник Тихон",
         "Хрюн Торгаш", "Тихий Шип"]
TERMS = ["голем", "житель", "жители", "разоритель", "незерит", "алмаз", "изумруд", "незер", "энд", "огниво",
         "динамит", "лава", "верстак", "эндер", "слизень", "пиглин", "визер", "ифрит", "гаст", "шалкер"]
GLOSSARY_PROMPT = ", ".join(HOTWORDS + NAMES)

# Spelling fixes for names/terms Whisper writes inconsistently. Patterns match the
# stem only, so inflected endings (Ладко/Ладка, крипера/криперу) are kept.
SPELLING = [
    (r"\b[Мм]айн[ -]?[Кк]рафт", "Майнкрафт"),
    (r"\b([Кк])рипп?ер", r"\1рипер"),
    (r"\b([Ээ])нд[еэ]рм[еа]н", r"\1ндермен"),
    (r"\b([Вв])ард[еэ]н", r"\1арден"),
    (r"\b[Лл]а[дт]к(?=(?:о|а|у|е|ой|ом)\b)", "Ладк"),
    (r"\b[Лл]одко\b", "Ладко"),
    (r"\b[Пп]рох[оа]р", "Прохор"),
    (r"\b[АаОо]кс[еи]н[ьи](?=[яюие]\b)", "Аксинь"),
    (r"\b[Кк]ва[сз]ок", "Квасок"),
]


def work_dir() -> Path:
    base = os.environ.get("MOBTALK_TRAIN_DIR")
    if base:
        return Path(base)
    local = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    return local / "MobTalk" / "train"


def ffmpeg(tool: str = "ffmpeg") -> str:
    found = shutil.which(tool)
    if found:
        return found
    packages = Path(os.environ.get("LOCALAPPDATA", "")) / "Microsoft" / "WinGet" / "Packages"
    for candidate in sorted(packages.glob(f"Gyan.FFmpeg_*/*/bin/{tool}.exe")):
        return str(candidate)
    raise SystemExit(f"{tool} not found: install it with 'winget install Gyan.FFmpeg'")


def normalize_text(text: str) -> str:
    """Canonical spelling of names/terms; keeps punctuation and case of the rest."""
    text = re.sub(r"\s+", " ", text).strip()
    for pattern, replacement in SPELLING:
        text = re.sub(pattern, replacement, text)
    return text


def _number_words(match: re.Match) -> str:
    try:
        from num2words import num2words
        return " " + num2words(int(match.group(0)), lang="ru") + " "
    except (ImportError, ValueError, OverflowError):
        return match.group(0)


def wer_text(text: str) -> str:
    """Text form used for WER: lower case, ё→е, digits as words, no punctuation."""
    text = re.sub(r"\d+", _number_words, normalize_text(text)).lower().replace("ё", "е")
    text = re.sub(r"[^\w\s-]", " ", text)
    text = re.sub(r"(?<!\w)-|-(?!\w)", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8") as source:
        return [json.loads(line) for line in source if line.strip()]


def write_jsonl(path: Path, rows) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as target:
        for row in rows:
            target.write(json.dumps(row, ensure_ascii=False) + "\n")
    temporary.replace(path)
