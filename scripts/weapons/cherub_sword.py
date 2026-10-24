"""Пламенный меч Херувима (Genesis 3:24): a long blade of fire on a gold hilt. The blade is a thin plane painted as fire with
a white-hot core and torn edges, six tongues of flame lick round it on their own planes (the renderer makes them breathe
and turns the blade slowly about its axis), the guard is a straight gold bar (not a cross), the grip is wrapped in crimson,
the pommel is a gold knob."""
from weaponlib import *

tongue = flame_tongue()
parts = [
    Part("sword", children=[
        # The hilt: pommel, wrapped grip and the straight guard.
        Part("hilt", boxes=[
            Box((-1.3, 6.4, -1.3), (2.6, 2.6, 2.6), gold_leaf(ornament=False)),
            Box((-0.9, 0.2, -0.9), (1.8, 6.4, 1.8), wrap(RED_WRAP, shade(RED_WRAP, 0.5))),
            Box((-5.0, -1.4, -1.1), (10.0, 1.6, 2.2), gold_leaf(ornament=False)),
            Box((-1.6, -2.4, -1.4), (3.2, 1.2, 2.8), gold_leaf(ornament=False)),
        ]),
        # The blade turns about its own axis: its pivot is at the guard.
        Part("blade", pivot=(0, -2.4, 0), boxes=[Box((-2.4, -26, -0.3), (4.8, 26, 0.6), flame_blade())], children=[
            Part("blade_flame_%d" % i, pivot=(0, -4 - i * 3.6, 0), rotation=(0, i * 1.05, 0),
                 boxes=[Box((0.6, -3.2, -0.15), (2.6, 3.2, 0.3), tongue)])
            for i in range(6)
        ]),
    ]),
]

if __name__ == "__main__":
    weapon_build("CherubSword", "cherub_sword", parts, size=64, seed=21)
