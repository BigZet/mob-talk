"""Лук Нимрода (Genesis 10:9, the mighty hunter): a tall bow of brick-red wood, each limb in two bent segments ending in a
bronze tip, a leather grip, and a string in two halves pivoted at the tips (the renderer draws them back with the arrow).
Built lying along Y like the other weapons: the grip at the origin, the upper limb toward -Y."""
from weaponlib import *

limb = wood(BRICK_WOOD, BRICK_DARK)
tip = metal((170, 116, 54), (92, 56, 24), (236, 188, 108))
string = solid((228, 222, 200), k=0.04)
HALF = 2 * 7.0 * math.cos(0.18) + 1.2  # the string's half length from the grip to a tip, about


def limb_part(name, sign):
    """One limb: two segments bending back (toward -Z, away from the archer), the string's half hanging from the tip."""
    return Part(name, pivot=(0, sign * 3.0, 0), rotation=(sign * 0.18, 0, 0), boxes=[Box((-0.7, 0 if sign > 0 else -7.0, -0.6), (1.4, 7.0, 1.2), limb)], children=[
        Part(name + "_end", pivot=(0, sign * 7.0, 0), rotation=(sign * 0.34, 0, 0), boxes=[
            Box((-0.6, 0 if sign > 0 else -7.0, -0.5), (1.2, 7.0, 1.0), limb),
            Box((-0.7, 7.0 if sign > 0 else -8.2, -0.6), (1.4, 1.2, 1.2), tip),
        ], children=[
            # The string's half: from the tip back toward the grip, straight when the bow is at rest.
            Part(name + "_string", pivot=(0, sign * 7.6, 0.4), rotation=(-sign * 0.52, 0, 0),
                 boxes=[Box((-0.15, -HALF if sign > 0 else 0, -0.1), (0.3, HALF, 0.2), string)]),
        ]),
    ])


parts = [
    Part("bow", children=[
        Part("grip", boxes=[Box((-0.9, -3.2, -0.8), (1.8, 6.4, 1.6), wrap(LEATHER, LEATHER_DARK))]),
        limb_part("limb_upper", -1),
        limb_part("limb_lower", 1),
    ]),
]

if __name__ == "__main__":
    weapon_build("NimrodBow", "nimrod_bow", parts, size=64, seed=51)
