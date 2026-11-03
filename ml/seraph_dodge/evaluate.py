"""Доля попаданий по Серафиму без уклонения и с обученной сетью, по оружию и меткости игрока.

python evaluate.py runs/v1/seraph_dodge.zip
"""
import sys

import numpy as np
import torch
from stable_baselines3 import PPO

from sim import BOW, CROSSBOW, EP_LEN, POTION, SNOWBALL, TRIDENT, SeraphArena

NAMES = [([BOW], "лук"), ([CROSSBOW], "арбалет"), ([TRIDENT], "трезубец"), ([SNOWBALL], "снежки"), (None, "всё вперемешку")]


def run(policy, weapons, skill, n=256, eps=2, seed=11):
    env = SeraphArena(n, seed=seed, weapons=weapons, skill=skill, dodge=policy is not None)
    o = env.reset_all()
    shots = hits = 0
    for _ in range(EP_LEN * eps):
        if policy is None:
            a = np.zeros((n, 4))
        else:
            with torch.no_grad():
                a = policy(torch.as_tensor(o)).numpy()
        o, r, d, info, _ = env.step(a)
        shots += info["shots"].sum(); hits += info["hits"].sum()
    return hits / max(shots, 1)


def load(path):
    model = PPO.load(path, device="cpu")
    pol = model.policy
    def act(obs):
        feats = pol.mlp_extractor.policy_net(pol.extract_features(obs, pol.pi_features_extractor))
        return torch.clamp(pol.action_net(feats), -1, 1)
    return act


if __name__ == "__main__":
    act = load(sys.argv[1])
    print(f"{'оружие':16s} {'меткость':>9s} {'без сети':>9s} {'с сетью':>9s}")
    for skill, sname in [(0.5, "снайпер"), (1.5, "средний"), (3.0, "мазила")]:
        for w, name in NAMES:
            b = run(None, w, skill)
            p = run(act, w, skill)
            print(f"{name:16s} {sname:>9s} {b:9.0%} {p:9.0%}")
