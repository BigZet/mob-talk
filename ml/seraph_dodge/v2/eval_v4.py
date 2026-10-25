"""Выбор копии v4: попадания против лучника-человека (сценарного, бой v4) и обученного лучника, плюс разнообразие движения.

python eval_v4.py runs/sp3/duel_u500.pt runs/sp4/duel_u*.pt --archer runs/sp4/duel.pt
    # на копию: попадания (человек / обученный / среднее), отбито, в минуту — рывков, мини, смен стороны, телепортов,
    # разброс дистанции и высоты, энтропия манёвров (рывок, мини, смена стороны, телепорт; бит, максимум 2)
"""
import argparse
import sys

import numpy as np
import torch

from duel import A_ACT, EP_LEN, S_ACT, Duel
from eval_v2 import load


def run(net, n, seed, archer=None):
    env = Duel(n, seed=seed, scripted_frac=0.0 if archer is not None else 1.0, human_frac=0.0 if archer is not None else 1.0, v4=True)
    zero_a = np.zeros((n, A_ACT), np.float32)
    tot = {}; dist = []; height = []
    for _ in range(EP_LEN):
        st, si, at, ai = (torch.as_tensor(x) for x in env.obs())
        with torch.no_grad():
            s_a = np.zeros((n, S_ACT), np.float32) if net is None else net.act(st, si, deterministic=True)[0].numpy()
            a_a = zero_a if archer is None else archer.act(at, ai, deterministic=True)[0].numpy()
        _, _, done, info = env.step(s_a, a_a)
        dist.append(np.linalg.norm((env.bpos - env.ppos)[:, [0, 2]], axis=1)); height.append(env.bpos[:, 1].copy())
        if info["done_stats"] is not None:
            for k, v in info["done_stats"].items():
                tot[k] = tot.get(k, 0.0) + float(v.sum())
    d, h = np.stack(dist), np.stack(height)
    return tot, float(d.std(0).mean()), float(h.std(0).mean())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ckpt", nargs="+")
    ap.add_argument("--archer", required=True)
    ap.add_argument("--envs", type=int, default=256)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--threads", type=int, default=8)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    torch.set_num_threads(a.threads)
    archer = load(a.archer, "a")
    mins = a.envs * EP_LEN / 1200
    print("копия | человек | обученный | среднее | отбито (чел.) | рывков/мин | мини/мин | смен стороны/мин | телепортов/мин | "
          "sd дистанции | sd высоты | энтропия манёвров", flush=True)
    best = None
    for path in a.ckpt + [None]:
        net = None if path is None else load(path, "s")
        (th, dsd, hsd), (tt, _, _) = run(net, a.envs, a.seed), run(net, a.envs, a.seed, archer)
        hh, ht = th["arrow_hits"] / max(th["shots"], 1), tt["arrow_hits"] / max(tt["shots"], 1)
        use = np.array([th["dashes"], th["minis"], th["flips"], th["teleports"]])
        p = use / use.sum() if use.sum() > 0 else np.zeros(4)
        ent = float(-(p[p > 0] * np.log2(p[p > 0])).sum())
        mean = (hh + ht) / 2
        print(f"{path or 'без сети'} | {hh:.3f} | {ht:.3f} | {mean:.3f} | {th['reflects'] / max(th['shots'], 1):.3f} | "
              f"{th['dashes'] / mins:.1f} | {th['minis'] / mins:.1f} | {th['flips'] / mins:.1f} | {th['teleports'] / mins:.1f} | "
              f"{dsd:.2f} | {hsd:.2f} | {ent:.2f}", flush=True)
        if path is not None and (best is None or mean <= best[0]):
            best = (mean, path)
    print(f"лучшая по среднему: {best[1]} ({best[0]:.3f})", flush=True)


if __name__ == "__main__":
    main()
