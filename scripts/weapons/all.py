"""Rebuilds every weapon's model and textures, then the data (names, recipes, item models, tags, icons). Run: python scripts/weapons/all.py"""
import os
import runpy
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
WEAPONS = ["david_sling", "cherub_sword", "archistrategos_sword", "george_spear", "nimrod_bow", "samson_jawbone", "gideon_jar", "war_sword",
           "jericho_horn", "aspen_stake"]

if __name__ == "__main__":
    for name in WEAPONS:
        print(name)
        sys.argv = [os.path.join(HERE, name + ".py")]
        runpy.run_path(sys.argv[0], run_name="__main__")
    sys.argv = [os.path.join(HERE, "resources.py")]
    runpy.run_path(sys.argv[0], run_name="__main__")
