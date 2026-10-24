"""Redraws every weapon's sprites, then the data (names, recipes, item models, tags, icons). Run: python scripts/weapons/all.py"""
import os
import runpy
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

if __name__ == "__main__":
    for name in ("sprites.py", "resources.py"):
        sys.argv = [os.path.join(HERE, name)]
        runpy.run_path(sys.argv[0], run_name="__main__")
