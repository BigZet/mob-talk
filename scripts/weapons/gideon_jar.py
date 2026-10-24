"""Кувшин Гедеона (Judges 7:16–20): an earthen pitcher with two small handles and a torch standing in its neck, the flame
hidden in the jar until it is broken; here the flame shows above the neck and glows."""
from weaponlib import *

parts = [
    Part("jar", children=[
        Part("body", boxes=[
            Box((-2.6, 2.6, -2.6), (5.2, 1.6, 5.2), clay()),
            Box((-3.4, -2.2, -3.4), (6.8, 4.8, 6.8), clay()),
            Box((-2.8, -3.8, -2.8), (5.6, 1.6, 5.6), clay()),
            Box((-1.6, -5.8, -1.6), (3.2, 2.0, 3.2), clay()),
            Box((-2.0, -6.6, -2.0), (4.0, 0.8, 4.0), clay()),
        ], children=[
            Part("handle_left", pivot=(-3.4, -2.6, 0), boxes=[Box((-1.2, 0, -0.5), (1.2, 3.0, 1.0), clay()), Box((-1.2, 0, -0.5), (0.6, 0.8, 1.0), clay())]),
            Part("handle_right", pivot=(3.4, -2.6, 0), boxes=[Box((0, 0, -0.5), (1.2, 3.0, 1.0), clay()), Box((0.6, 0, -0.5), (0.6, 0.8, 1.0), clay())]),
        ]),
        Part("torch", pivot=(0, -6.6, 0), rotation=(0, 0, 0.12), boxes=[Box((-0.5, -5.0, -0.5), (1.0, 5.0, 1.0), wood((120, 84, 46), (70, 46, 26)))], children=[
            Part("flame", pivot=(0, -5.0, 0), boxes=[Box((-0.8, -1.6, -0.8), (1.6, 1.6, 1.6), fire())], children=[
                Part("flame_tongue_0", pivot=(0, -1.6, 0), boxes=[Box((-0.7, -1.8, -0.1), (1.4, 1.8, 0.2), flame_tongue())]),
                Part("flame_tongue_1", pivot=(0, -1.6, 0), rotation=(0, 1.57, 0), boxes=[Box((-0.7, -1.8, -0.1), (1.4, 1.8, 0.2), flame_tongue())]),
            ]),
        ]),
    ]),
]

if __name__ == "__main__":
    weapon_build("GideonJar", "gideon_jar", parts, size=64, seed=71)
