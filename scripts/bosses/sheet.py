"""Joins a boss's showcase screenshots into one labelled contact sheet for review: python scripts/bosses/sheet.py <id> [out.png]"""
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
VIEWS = ["front", "side", "back", "below", "head", "hands", "top", "upper"]


def sheet(boss, out):
    shots = os.path.join(ROOT, "run", "screenshots")
    tiles = []
    for view in VIEWS:
        path = os.path.join(shots, "boss-showcase-%s-%s.png" % (boss, view))
        if os.path.exists(path):
            tiles.append((view, Image.open(path).convert("RGB")))
    if not tiles:
        raise SystemExit("no showcase screenshots for " + boss)
    w, h = 427, 240
    cols = 3
    rows = (len(tiles) + cols - 1) // cols
    board = Image.new("RGB", (w * cols, h * rows), (20, 20, 20))
    draw = ImageDraw.Draw(board)
    for i, (view, img) in enumerate(tiles):
        x, y = (i % cols) * w, (i // cols) * h
        board.paste(img.resize((w, h), Image.LANCZOS), (x, y))
        draw.rectangle((x, y, x + 60, y + 14), fill=(0, 0, 0))
        draw.text((x + 3, y + 2), view, fill=(255, 220, 120))
    board.save(out)
    print(out)


if __name__ == "__main__":
    sheet(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "run", "screenshots", "sheet-%s.png" % sys.argv[1]))
