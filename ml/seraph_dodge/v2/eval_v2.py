"""Проверка Серафима против сценарного лучника v1: сеть из duel.pt (детерминированно, mu) и без сети (нулевые действия).

python eval_v2.py runs/sp1/duel.pt                 # 256 арен, один эпизод (600 тиков), на CPU

Чистая мера: в обучении B_arrow_hit смешивает сценарного лучника и лучников из пула.
"""
import argparse

import numpy as np
import torch

from duel import A_ACT, EP_LEN, S_ACT, Duel
from selfplay import make

KEYS = ("shots", "arrow_hits", "reflects", "reflect_hits", "dashes")


def run(net, n, seed):
    env = Duel(n, seed=seed, scripted_frac=1.0)
    zero_a = np.zeros((n, A_ACT), np.float32)
    total = {k: 0.0 for k in KEYS}; episodes = 0
    for _ in range(EP_LEN):
        if net is None:
            s_a = np.zeros((n, S_ACT), np.float32)
        else:
            st, si, _, _ = (torch.as_tensor(x) for x in env.obs())
            with torch.no_grad():
                s_a = net.act(st, si, deterministic=True)[0].numpy()
        _, _, done, info = env.step(s_a, zero_a)
        if info["done_stats"] is not None:
            episodes += int(done.sum())
            for k in KEYS:
                total[k] += float(info["done_stats"][k].sum())
    return total, episodes


def line(name, total, episodes):
    hit = total["arrow_hits"] / max(total["shots"], 1)
    return (f"{name}: попадания {hit:.3f} ({int(total['arrow_hits'])}/{int(total['shots'])}), "
            f"отражений/эп {total['reflects'] / episodes:.2f} (в стрелка {total['reflect_hits'] / episodes:.2f}), "
            f"рывков/эп {total['dashes'] / episodes:.2f}, эпизодов {episodes}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ckpt")
    ap.add_argument("--envs", type=int, default=256)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--threads", type=int, default=2)
    a = ap.parse_args()
    torch.set_num_threads(a.threads)
    net = make("s").eval()
    net.load_state_dict(torch.load(a.ckpt, map_location="cpu")["s"])
    print(line("сеть", *run(net, a.envs, a.seed)), flush=True)
    print(line("без сети", *run(None, a.envs, a.seed)), flush=True)


if __name__ == "__main__":
    main()
