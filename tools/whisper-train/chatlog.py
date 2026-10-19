"""Read Mob Talk chat lines from Minecraft logs: "Вы: …" (the player's recognized
speech) and "<mob name>: …" (mob replies that the TTS read aloud).

The lines are only hints for speaker tagging: the player's text is large-v3 output
from the raw microphone, mob text is what TTS voiced.
"""
from __future__ import annotations

import gzip
import re
from datetime import date, datetime, time, timedelta
from pathlib import Path

LINE = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\] \[Render thread/INFO\]: \[System\] \[CHAT\] ([^:\[\]<>]{1,40}): (.+)$")
GZ_NAME = re.compile(r"^(\d{4}-\d{2}-\d{2})-\d+\.log\.gz$")


def default_logs() -> Path:
    from os import environ
    return Path(environ.get("APPDATA", "")) / "PrismLauncher" / "instances" / "1.21.1-fabric-dev" / "minecraft" / "logs"


def _lines(path: Path) -> list[str]:
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8", errors="replace") as source:
        return source.read().splitlines()


def _parse(lines):
    for line in lines:
        match = LINE.match(line)
        if match:
            hh, mm, ss, speaker, text = match.groups()
            yield time(int(hh), int(mm), int(ss)), speaker.strip(), text.strip()


def read_chat(logs: Path | None = None) -> list[dict]:
    """Chat lines with wall-clock timestamps, deduplicated across copied log files."""
    logs = logs or default_logs()
    entries = {}
    for path in sorted(logs.glob("*.log*")):
        parsed = list(_parse(_lines(path)))
        if not parsed:
            continue
        stamped = []
        named = GZ_NAME.match(path.name)
        if named:  # rotated log: the name holds the start date; walk forward over midnight
            day = date.fromisoformat(named.group(1))
            previous = None
            for clock, speaker, text in parsed:
                if previous and clock < previous:
                    day += timedelta(days=1)
                previous = clock
                stamped.append((datetime.combine(day, clock), speaker, text))
        else:  # plain log: modification time belongs to the last line; walk backwards
            day = datetime.fromtimestamp(path.stat().st_mtime).date()
            following = None
            for clock, speaker, text in reversed(parsed):
                if following and clock > following:
                    day -= timedelta(days=1)
                following = clock
                stamped.append((datetime.combine(day, clock), speaker, text))
        for moment, speaker, text in stamped:
            if text.startswith("[Mob Talk]") or speaker.startswith("[") or "получил достижение" in text:
                continue
            entries[(moment, speaker, text)] = {"time": moment, "speaker": "user" if speaker == "Вы" else "mob",
                                                "name": speaker, "text": text}
    return sorted(entries.values(), key=lambda entry: entry["time"])


if __name__ == "__main__":
    chat = read_chat()
    users = sum(1 for e in chat if e["speaker"] == "user")
    print(f"{len(chat)} chat lines, {users} player lines, {len(chat) - users} mob lines")
    if chat:
        print(f"from {chat[0]['time']} to {chat[-1]['time']}")
