"""Копьё Георгия: the thin lance of the icon «Чудо Георгия о змии», a shaft wound red and white, a leaf-shaped steel head
with a sharp point, a small red pennant below the head (the renderer lets it flutter) and a steel cap on the butt."""
from weaponlib import *

parts = [
    Part("spear", children=[
        Part("shaft", boxes=[
            Box((-0.6, -22, -0.6), (1.2, 46, 1.2), spiral((214, 44, 40), (236, 230, 220), pitch=5)),
            Box((-0.8, 23.4, -0.8), (1.6, 1.8, 1.6), steel(edge=False)),
            Box((-0.8, -23.4, -0.8), (1.6, 1.6, 1.6), steel(edge=False)),
        ]),
        Part("head", pivot=(0, -23.4, 0), boxes=[Box((-1.4, -7.0, -0.35), (2.8, 7.0, 0.7), pointed(steel(), 1.8))]),
        Part("pennant", pivot=(0.6, -21.5, 0), boxes=[Box((0, 0, -0.1), (6.5, 3.2, 0.2), cloth((196, 34, 32), folds=True, trim=GOLD_DARK, trim_width=1))]),
    ]),
]

if __name__ == "__main__":
    weapon_build("GeorgeSpear", "george_spear", parts, size=96, seed=41)
