"""Праща Давида (1 Samuel 17:40, 49): two leather straps meeting in a cupped pouch, a finger loop at the end of one and a knot
at the end of the other, and a smooth stone from the brook lying in the pouch. The straps hang up from the pouch (the sling
hangs from the hand); the renderer spins the whole thing while it is being wound."""
from weaponlib import *

strap = wrap(LEATHER, LEATHER_DARK, pitch=6)
pouch = wrap(shade(LEATHER, 0.9), LEATHER_DARK, pitch=8)

parts = [
    Part("sling", children=[
        # The pouch: a shallow cup of three planes.
        Part("pouch", boxes=[
            Box((-2.4, -0.8, -1.4), (4.8, 1.2, 2.8), pouch),
            Box((-2.8, -1.8, -1.6), (0.8, 1.6, 3.2), pouch),
            Box((2.0, -1.8, -1.6), (0.8, 1.6, 3.2), pouch),
        ], children=[
            Part("stone", pivot=(0, -1.0, 0), boxes=[Box((-1.1, -1.4, -1.1), (2.2, 2.2, 2.2), pebble())]),
        ]),
        # Two straps rising from the pouch's ends, leaning in toward the hand.
        Part("strap_left", pivot=(-2.4, -1.4, 0), rotation=(0, 0, 0.17), boxes=[Box((-0.45, -14, -0.3), (0.9, 14, 0.6), strap)], children=[
            # The finger loop: a ring of four thin pieces.
            Part("loop", pivot=(0, -14, 0), boxes=[Box((-1.3, -2.6, -0.3), (0.6, 2.8, 0.6), strap), Box((0.7, -2.6, -0.3), (0.6, 2.8, 0.6), strap),
                                                   Box((-1.3, -2.6, -0.3), (2.6, 0.6, 0.6), strap), Box((-1.3, -0.4, -0.3), (2.6, 0.6, 0.6), strap)]),
        ]),
        Part("strap_right", pivot=(2.4, -1.4, 0), rotation=(0, 0, -0.17), boxes=[Box((-0.45, -14, -0.3), (0.9, 14, 0.6), strap)], children=[
            Part("knot", pivot=(0, -14, 0), boxes=[Box((-0.9, -1.4, -0.7), (1.8, 1.6, 1.4), wrap(LEATHER_DARK, LEATHER, pitch=2))]),
        ]),
    ]),
]

if __name__ == "__main__":
    weapon_build("DavidSling", "david_sling", parts, size=64, seed=11)
