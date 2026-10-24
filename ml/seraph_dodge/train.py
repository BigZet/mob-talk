"""Обучение уклонения Серафима: PPO (Stable-Baselines3) на векторном тренажёре sim.py.

python train.py --steps 6000000 --out runs/v1
"""
import argparse
import os

import gymnasium as gym
import numpy as np
import torch
from stable_baselines3 import PPO
from stable_baselines3.common.callbacks import BaseCallback
from stable_baselines3.common.vec_env import VecEnv

from sim import SeraphArena


class ArenaVecEnv(VecEnv):
    def __init__(self, n, seed=0, **kw):
        self.arena = SeraphArena(n, seed=seed, **kw)
        obs_space = gym.spaces.Box(-np.inf, np.inf, (SeraphArena.obs_dim,), np.float32)
        act_space = gym.spaces.Box(-1.0, 1.0, (4,), np.float32)
        super().__init__(n, obs_space, act_space)
        self._actions = None
        self.ep_shots, self.ep_hits = [], []

    def reset(self):
        return self.arena.reset_all()

    def step_async(self, actions):
        self._actions = actions

    def step_wait(self):
        obs, rew, done, info, terminal = self.arena.step(self._actions)
        infos = [{} for _ in range(self.num_envs)]
        if done.any():
            shots, hits, _ = info["final"]
            self.ep_shots.extend(shots.tolist()); self.ep_hits.extend(hits.tolist())
            for j, i in enumerate(np.nonzero(done)[0]):
                infos[i]["terminal_observation"] = terminal[i]
                infos[i]["TimeLimit.truncated"] = True
        return obs, rew, done, infos

    def close(self): pass
    def get_attr(self, name, indices=None): return [getattr(self, name)] * self.num_envs
    def set_attr(self, name, value, indices=None): setattr(self, name, value)
    def env_method(self, *a, **k): return [None] * self.num_envs
    def env_is_wrapped(self, wrapper_class, indices=None): return [False] * self.num_envs
    def seed(self, seed=None): return [None] * self.num_envs


class HitRate(BaseCallback):
    def _on_step(self):
        env = self.training_env
        if len(env.ep_shots) >= 64:
            s, h = sum(env.ep_shots), sum(env.ep_hits)
            self.logger.record("arena/hit_rate", h / max(s, 1))
            env.ep_shots.clear(); env.ep_hits.clear()
        return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--steps", type=int, default=6_000_000)
    ap.add_argument("--envs", type=int, default=128)
    ap.add_argument("--out", default="runs/v1")
    ap.add_argument("--resume", default=None)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    torch.set_num_threads(max(1, os.cpu_count() - 1))
    env = ArenaVecEnv(a.envs, seed=7)
    if a.resume:
        model = PPO.load(a.resume, env=env)
    else:
        model = PPO("MlpPolicy", env, n_steps=256, batch_size=8192, n_epochs=5, learning_rate=3e-4,
                    gamma=0.98, gae_lambda=0.95, ent_coef=0.003, clip_range=0.2,
                    policy_kwargs=dict(net_arch=[128, 128], activation_fn=torch.nn.Tanh, log_std_init=-0.5),
                    verbose=1, tensorboard_log=a.out, device="cpu")
    model.learn(total_timesteps=a.steps, callback=HitRate(), progress_bar=False)
    model.save(os.path.join(a.out, "seraph_dodge"))


if __name__ == "__main__":
    main()
