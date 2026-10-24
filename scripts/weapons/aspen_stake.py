"""Осиновый кол (a folk belief, not church teaching): a stake of pale wood whittled to a point, three faceted lengths
narrowing to the tip, with bark left on the grip end in dark rings."""
from weaponlib import *

pale = wood(BIRCH, BIRCH_DARK)
parts = [
    Part("stake", children=[
        Part("grip", boxes=[Box((-1.4, 0, -1.4), (2.8, 8.0, 2.8), wood(BIRCH, BIRCH_DARK, rings=5))]),
        Part("shaft", pivot=(0, 0, 0), boxes=[
            Box((-1.2, -7.0, -1.2), (2.4, 7.0, 2.4), pale),
            Box((-0.9, -12.0, -0.9), (1.8, 5.0, 1.8), pale),
            Box((-0.6, -15.0, -0.6), (1.2, 3.0, 1.2), pale),
        ], children=[
            Part("point", pivot=(0, -15.0, 0), boxes=[Box((-0.6, -3.6, -0.15), (1.2, 3.6, 0.3), pointed(pale, 3.0)), Box((-0.15, -3.6, -0.6), (0.3, 3.6, 1.2), pale)]),
        ]),
    ]),
]

if __name__ == "__main__":
    weapon_build("AspenStake", "aspen_stake", parts, size=64, seed=101)
