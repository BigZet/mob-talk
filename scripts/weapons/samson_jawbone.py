"""Ослиная челюсть Самсона (Judges 15:15–16): the lower jaw of an ass, a curve of bone segments with a row of teeth along the
top, the hinge end thick and the chin end wrapped in leather for a grip."""
from weaponlib import *

jaw = bone()
tooth = solid(BONE_LIGHT, k=0.06)

segments = []
for i in range(6):
    # The jaw curves: each segment turns a little further about Z, and grows toward the hinge.
    size = 2.0 + i * 0.3
    segments.append(Part("jaw_%d" % i, pivot=(0, -4.2 * i, 0), rotation=(0, 0, -0.12 * i), boxes=[
        Box((-size / 2, -4.4, -size * 0.45), (size, 4.6, size * 0.9), jaw),
        Box((size / 2 - 0.2, -3.6, -0.4), (1.0, 1.4, 0.8), tooth),
        Box((size / 2 - 0.2, -1.6, -0.4), (1.0, 1.4, 0.8), tooth),
    ]))

parts = [
    Part("jawbone", children=[
        Part("grip", pivot=(0, 2.0, 0), boxes=[Box((-1.1, 0, -1.0), (2.2, 5.2, 2.0), wrap(LEATHER, LEATHER_DARK))]),
        Part("bone", children=segments + [
            Part("hinge", pivot=(0, -4.2 * 6 + 1.0, 0), rotation=(0, 0, -0.72), boxes=[Box((-1.8, -3.0, -1.5), (3.6, 3.4, 3.0), jaw)]),
        ]),
    ]),
]

if __name__ == "__main__":
    weapon_build("SamsonJawbone", "samson_jawbone", parts, size=64, seed=61)
