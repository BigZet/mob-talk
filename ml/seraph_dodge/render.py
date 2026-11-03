"""Видео из тренажёра: слева Серафим без сети, справа с сетью, вид из-за спины игрока.

python render.py runs/v1/seraph_dodge.zip out.mp4 --weapon bow --seconds 20
"""
import argparse

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import torch
from matplotlib.animation import FFMpegWriter
from matplotlib.patches import Rectangle

from evaluate import load
from sim import BOW, CROSSBOW, EYE, H, SNOWBALL, TRIDENT, W, SeraphArena

WEAPONS = {"bow": [BOW], "crossbow": [CROSSBOW], "trident": [TRIDENT], "snowball": [SNOWBALL], "mix": None}
TITLE = {"bow": "лук", "crossbow": "арбалет", "trident": "трезубец", "snowball": "снежки", "mix": "всё вперемешку"}


CAM = {}


def view(env):
    """Проекция на плоскость поперёк взгляда игрока: (сдвиг вбок, высота). Камера доворачивает плавно, как человек,
    поэтому рывок виден как скачок вбок."""
    d = env.bpos[0] - env.ppos[0]
    f = np.array([d[0], 0, d[2]]); f /= np.linalg.norm(f) + 1e-9
    prev = CAM.get(id(env), f)
    f = prev * 0.85 + f * 0.15; f /= np.linalg.norm(f) + 1e-9
    CAM[id(env)] = f
    side = np.array([-f[2], 0, f[0]])
    def p(x):
        rel = x - env.ppos[0]
        return rel @ side, x[1]
    return p, f


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model"); ap.add_argument("out")
    ap.add_argument("--weapon", default="bow"); ap.add_argument("--seconds", type=int, default=20)
    ap.add_argument("--skill", type=float, default=1.0); ap.add_argument("--seed", type=int, default=5)
    a = ap.parse_args()
    act = load(a.model)
    envs = [SeraphArena(1, seed=a.seed, weapons=WEAPONS[a.weapon], skill=a.skill, dodge=False),
            SeraphArena(1, seed=a.seed, weapons=WEAPONS[a.weapon], skill=a.skill, dodge=True)]
    obs = [e.reset_all() for e in envs]
    flash = [0, 0]; shots = [0, 0]; hits = [0, 0]
    trails = [{}, {}]

    plt.rcParams["font.family"] = "DejaVu Sans"
    fig, axes = plt.subplots(1, 2, figsize=(12.8, 7.2), dpi=100)
    fig.patch.set_facecolor("#10151b")
    writer = FFMpegWriter(fps=20, bitrate=3000)
    with writer.saving(fig, a.out, 100):
        for t in range(a.seconds * 20):
            for k, env in enumerate(envs):
                with torch.no_grad():
                    act_k = act(torch.as_tensor(obs[k])).numpy() if k == 1 else np.zeros((1, 4))
                obs[k], r, d, info, _ = env.step(act_k)
                shots[k] += info["shots"][0]; hits[k] += info["hits"][0]
                if info["hits"][0]:
                    flash[k] = 6
                ax = axes[k]
                ax.clear(); ax.set_facecolor("#18212b")
                ax.set_xlim(-9, 9); ax.set_ylim(-0.5, 13); ax.set_aspect("equal")
                ax.tick_params(colors="#7d8a97"); [s.set_color("#2b3541") for s in ax.spines.values()]
                ax.axhline(0, color="#4d5b49", lw=6)
                p, fwd = view(env)
                bx, by = p(env.bpos[0])
                color = "#ff5a5a" if flash[k] > 0 else "#f2c14e"
                ax.add_patch(Rectangle((bx - W / 2, by), W, H, fc=color, alpha=0.35, ec=color, lw=2))
                ax.plot([bx], [by + H * 0.57], "o", ms=14, color="#fff3c4")
                flash[k] = max(0, flash[k] - 1)
                # снаряды: только те, что впереди игрока, со следом
                tr = trails[k]
                for i in range(env.q_on.shape[1]):
                    if env.q_on[0, i]:
                        x, y = p(env.q_pos[0, i])
                        tr.setdefault(i, []).append((x, y)); tr[i] = tr[i][-6:]
                        xs, ys = zip(*tr[i])
                        ax.plot(xs, ys, "-", color="#9ad1ff", lw=1.5, alpha=0.7)
                        ax.plot([x], [y], "o", ms=4, color="#d8f0ff")
                    else:
                        tr.pop(i, None)
                # игрок: прицел и натяжение
                ax.plot([0], [EYE], "s", ms=9, color="#7fe0a8")
                if env.use[0] > 0:
                    ax.text(0, EYE - 1.0, "натягивает" if env.weapon[0] != CROSSBOW else "заряжает", color="#7fe0a8",
                            ha="center", fontsize=10)
                if env.loaded[0]:
                    ax.text(0, EYE - 1.0, "заряжен", color="#7fe0a8", ha="center", fontsize=10)
                if k == 1:
                    e = env.energy[0]
                    ax.add_patch(Rectangle((-8.5, 12.2), 6 * e, 0.4, fc="#86a6ff"))
                    ax.text(-8.5, 11.5, "рывок готов" if e >= 1 else "крылья отдыхают", color="#98a4b1", fontsize=9)
                    if env.dash_left[0] > 0:
                        ax.text(bx, by + H + 0.4, "рывок!", color="#86a6ff", ha="center", fontsize=12, weight="bold")
                name = "Без сети (как сейчас)" if k == 0 else "С обученной сетью"
                rate = hits[k] / max(shots[k], 1)
                ax.set_title(f"{name}\nпопаданий {hits[k]:.0f} из {shots[k]:.0f} ({rate:.0%})", color="#e3e9ef", fontsize=13)
            fig.suptitle(f"Серафим, вид из-за спины игрока · {TITLE[a.weapon]} · {t / 20:4.1f} с", color="#e3e9ef", fontsize=14)
            writer.grab_frame()
    print("shots", shots, "hits", hits)


if __name__ == "__main__":
    main()
