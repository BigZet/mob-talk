"""Проверка Серафима против сценарного лучника v1: сеть из duel.pt (детерминированно, mu) и без сети (нулевые действия).

python eval_v2.py runs/sp1/duel.pt                 # 256 арен, один эпизод (600 тиков), на CPU
python eval_v2.py runs/sp1_u*.pt --archer runs/sp1/duel.pt
    # таблица: каждая копия против сценарного и против обученного лучника (ключ "a", детерминированно);
    # лучшая — с наименьшим средним из двух долей попаданий (при равенстве — более поздняя)

Чистая мера: в обучении B_arrow_hit смешивает сценарного лучника и лучников из пула.
"""
import argparse
import sys

import numpy as np
import torch

from duel import A_ACT, EP_LEN, S_ACT, Duel
from selfplay import make

KEYS = ("shots", "arrow_hits", "reflects", "reflect_hits", "dashes")


def run(net, n, seed, archer=None):
    env = Duel(n, seed=seed, scripted_frac=0.0 if archer is not None else 1.0)
    zero_a = np.zeros((n, A_ACT), np.float32)
    total = {k: 0.0 for k in KEYS}; episodes = 0
    for _ in range(EP_LEN):
        st, si, at, ai = (torch.as_tensor(x) for x in env.obs())
        with torch.no_grad():
            s_a = np.zeros((n, S_ACT), np.float32) if net is None else net.act(st, si, deterministic=True)[0].numpy()
            a_a = zero_a if archer is None else archer.act(at, ai, deterministic=True)[0].numpy()
        _, _, done, info = env.step(s_a, a_a)
        if info["done_stats"] is not None:
            episodes += int(done.sum())
            for k in KEYS:
                total[k] += float(info["done_stats"][k].sum())
    return total, episodes


def hit(total):
    return total["arrow_hits"] / max(total["shots"], 1)


def line(name, total, episodes):
    return (f"{name}: попадания {hit(total):.3f} ({int(total['arrow_hits'])}/{int(total['shots'])}), "
            f"отражений/эп {total['reflects'] / episodes:.2f} (в стрелка {total['reflect_hits'] / episodes:.2f}), "
            f"рывков/эп {total['dashes'] / episodes:.2f}, эпизодов {episodes}")


def load(path, key):
    net = make(key).eval()
    net.load_state_dict(torch.load(path, map_location="cpu")[key])
    return net


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ckpt", nargs="+")
    ap.add_argument("--archer", default=None, help="duel.pt, чей обученный лучник (ключ a) стреляет во вторую колонку")
    ap.add_argument("--envs", type=int, default=256)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--threads", type=int, default=2)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    torch.set_num_threads(a.threads)
    if a.archer is None:
        for path in a.ckpt:
            print(line(f"сеть {path}", *run(load(path, "s"), a.envs, a.seed)), flush=True)
        print(line("без сети", *run(None, a.envs, a.seed)), flush=True)
        return
    archer = load(a.archer, "a")
    print("копия | попадания сценарного | попадания обученного | среднее | отражений/эп (сцен./обуч.)", flush=True)
    best = None
    for path in a.ckpt:
        net = load(path, "s")
        (ts, es), (tt, et) = run(net, a.envs, a.seed), run(net, a.envs, a.seed, archer)
        mean = (hit(ts) + hit(tt)) / 2
        print(f"{path} | {hit(ts):.3f} | {hit(tt):.3f} | {mean:.3f} | {ts['reflects'] / es:.2f} / {tt['reflects'] / et:.2f}", flush=True)
        if best is None or mean <= best[0]:
            best = (mean, path)
    (ts, _), (tt, _) = run(None, a.envs, a.seed), run(None, a.envs, a.seed, archer)
    print(f"без сети | {hit(ts):.3f} | {hit(tt):.3f} | {(hit(ts) + hit(tt)) / 2:.3f} | 0 / 0", flush=True)
    print(f"лучшая: {best[1]} (среднее {best[0]:.3f})", flush=True)


if __name__ == "__main__":
    main()
