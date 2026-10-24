"""Большой меч Войны (Revelation 6:4, the rider on the red horse): a broad two-handed blade of blackened steel with a red
sheen and notches hacked into its edges, a guard of two horns curving up toward the blade, a long black grip and a heavy
dark pommel."""
from weaponlib import *

dark_metal = metal(BLACK_STEEL, (20, 18, 22), (90, 86, 96))
parts = [
    Part("sword", children=[
        Part("hilt", boxes=[
            Box((-1.6, 8.4, -1.6), (3.2, 3.2, 3.2), dark_metal),
            Box((-1.0, 0.2, -1.0), (2.0, 8.2, 2.0), wrap(BLACK_WRAP, (70, 20, 20))),
            Box((-2.6, -1.6, -1.2), (5.2, 1.8, 2.4), dark_metal),
        ], children=[
            Part("horn_left", pivot=(-2.6, -0.7, 0), rotation=(0, 0, -0.75), boxes=[Box((-4.0, -0.8, -0.8), (4.0, 1.6, 1.6), pointed(dark_metal, 1.0))]),
            Part("horn_right", pivot=(2.6, -0.7, 0), rotation=(0, 0, 0.75), boxes=[Box((0, -0.8, -0.8), (4.0, 1.6, 1.6), pointed(dark_metal, 1.0))]),
        ]),
        Part("blade", pivot=(0, -1.6, 0), boxes=[Box((-3.0, -30, -0.5), (6.0, 30, 1.0), pointed(war_blade(), 1.2))]),
    ]),
]

if __name__ == "__main__":
    weapon_build("WarSword", "war_sword", parts, size=64, seed=81)
