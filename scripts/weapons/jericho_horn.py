"""Трубы Иерихона (Joshua 6:20): a ram's horn, a shofar, a curve of ridged horn segments widening from the mouthpiece to the
bell, bound with two copper bands. The mouthpiece is at the grip, the bell curves up and away."""
from weaponlib import *

segments = []
for i in range(7):
    w = 1.4 + i * 0.55
    style = copper() if i in (2, 5) else horn()
    segments.append(Part("horn_%d" % i, pivot=(0, -3.4 * i, 0), rotation=(0, 0, 0.17 * i), boxes=[Box((-w / 2, -3.6, -w / 2), (w, 3.8, w), style)]))

parts = [
    Part("horn", children=[
        Part("mouthpiece", pivot=(0, 1.6, 0), boxes=[Box((-0.9, 0, -0.9), (1.8, 2.4, 1.8), copper())]),
        Part("body", children=segments + [
            Part("bell", pivot=(0, -3.4 * 7 + 0.6, 0), rotation=(0, 0, 1.2), boxes=[Box((-2.9, -2.6, -2.9), (5.8, 2.8, 5.8), horn()), Box((-2.1, -3.0, -2.1), (4.2, 0.6, 4.2), solid((60, 44, 30), k=0.1))]),
        ]),
    ]),
]

if __name__ == "__main__":
    weapon_build("JerichoHorn", "jericho_horn", parts, size=64, seed=91)
