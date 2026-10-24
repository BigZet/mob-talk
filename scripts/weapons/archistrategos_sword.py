"""Меч архистратига: the straight sword of the icons of the Archangel Michael, leader of the heavenly host. A pale steel blade
with a light running down its fuller (it glows blue-white in the dark), a gold guard whose ends curve a little toward the
blade, a grip wrapped in red and a round gold pommel."""
from weaponlib import *

parts = [
    Part("sword", children=[
        Part("hilt", boxes=[
            Box((-1.4, 6.0, -1.4), (2.8, 2.8, 2.8), gold_leaf(ornament=False)),
            Box((-0.9, 0.2, -0.9), (1.8, 6.0, 1.8), wrap(RED_WRAP, shade(RED_WRAP, 0.5))),
            Box((-3.6, -1.5, -1.0), (7.2, 1.6, 2.0), gold_leaf(ornament=False)),
        ], children=[
            Part("guard_left", pivot=(-3.6, -0.7, 0), rotation=(0, 0, -0.35), boxes=[Box((-2.6, -0.8, -0.9), (2.6, 1.6, 1.8), gold_leaf(ornament=False))]),
            Part("guard_right", pivot=(3.6, -0.7, 0), rotation=(0, 0, 0.35), boxes=[Box((0, -0.8, -0.9), (2.6, 1.6, 1.8), gold_leaf(ornament=False))]),
        ]),
        Part("blade", pivot=(0, -1.5, 0), boxes=[Box((-2.0, -28, -0.4), (4.0, 28, 0.8), pointed(holy_blade(), 1.6))]),
    ]),
]

if __name__ == "__main__":
    weapon_build("ArchistrategosSword", "archistrategos_sword", parts, size=64, seed=31)
