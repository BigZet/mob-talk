"""Self-play PPO: Серафим и лучник учатся друг против друга, плюс пул старых версий и сценарный лучник из v1.

python selfplay.py --envs 1024 --updates 3000 --device cuda --out runs/sp1      # на 3080
python selfplay.py --envs 128 --updates 20 --out runs/smoke                     # проверка на CPU

Группы арен в каждом сборе опыта:
  A (50%): текущий Серафим против текущего лучника — учатся оба;
  B (30%): текущий Серафим против старого лучника из пула или сценарного (половина) — учится Серафим;
  C (20%): старый Серафим из пула против текущего лучника — учится лучник.
Предсказатели (aux) учатся вместе с политикой:
  Серафим: «лучник отпустит тетиву в ближайшие 4 тика» и «куда сместится лучник за 10 тиков»;
  лучник: «Серафим сделает рывок в ближайшие 4 тика» и «куда сместится Серафим за 10 тиков».
"""
import argparse
import copy
import json
import os
import time

import numpy as np
import torch
import torch.nn.functional as F

from duel import A_ACT, A_TOK, ITEM_F, S_ACT, S_TOK, T, Duel, EYE, H, rot
from model import AgentNet

AUX = 4
SOON, AHEAD = 4, 10


def make(kind):
    return AgentNet(S_TOK if kind == "s" else A_TOK, ITEM_F, S_ACT if kind == "s" else A_ACT, AUX, T)


class Buffer:
    def __init__(self, steps, n, tok, act, device):
        self.tok = torch.zeros(steps, n, T, tok); self.items = torch.zeros(steps, n, 8, ITEM_F)
        self.act = torch.zeros(steps, n, act); self.logp = torch.zeros(steps, n); self.val = torch.zeros(steps, n)
        self.rew = torch.zeros(steps, n); self.done = torch.zeros(steps, n); self.learn = torch.zeros(n, dtype=torch.bool)
        self.event = torch.zeros(steps, n)          # выстрел / рывок соперника на этом тике
        self.opp = torch.zeros(steps, n, 3)         # положение соперника в мире
        self.frame = torch.zeros(steps, n, 2)       # cos, sin рамки агента


def labels(buf, steps):
    """Цели предсказателей и маска (нет конца эпизода в окне)."""
    ev, opp, done, fr = buf.event, buf.opp, buf.done, buf.frame
    soon = torch.zeros_like(ev); ok = torch.zeros_like(ev); move = torch.zeros_like(opp)
    for t in range(steps - AHEAD):
        broken = done[t:t + AHEAD].sum(0) > 0
        soon[t] = (ev[t + 1:t + 1 + SOON].sum(0) > 0).float()
        d = opp[t + AHEAD] - opp[t]
        c, s = fr[t, :, 0], fr[t, :, 1]
        move[t] = torch.stack([d[:, 0] * c + d[:, 2] * s, d[:, 1], -d[:, 0] * s + d[:, 2] * c], -1) / 4
        ok[t] = (~broken).float()
    return soon, move, ok


def gae(buf, last_val, steps, gamma, lam):
    adv = torch.zeros_like(buf.rew); last = torch.zeros_like(last_val)
    for t in reversed(range(steps)):
        nxt = last_val if t == steps - 1 else buf.val[t + 1]
        nonterm = 1 - buf.done[t]
        delta = buf.rew[t] + gamma * nxt * nonterm - buf.val[t]
        last = delta + gamma * lam * nonterm * last
        adv[t] = last
    return adv, adv + buf.val


def update(net, opt, buf, adv, ret, soon, move, ok, steps, a, dev):
    idx = buf.learn.nonzero().squeeze(-1)
    if idx.numel() == 0:
        return {}
    def flat(x):
        return x[:, idx].reshape(-1, *x.shape[2:])
    tok, items, act, logp0 = flat(buf.tok), flat(buf.items), flat(buf.act), flat(buf.logp)
    adv_f, ret_f, soon_f, move_f, ok_f = flat(adv), flat(ret), flat(soon), flat(move), flat(ok)
    adv_f = (adv_f - adv_f.mean()) / (adv_f.std() + 1e-8)
    N = tok.shape[0]; stats = {"pi": 0, "v": 0, "aux_soon": 0, "aux_move": 0, "ent": 0}; k = 0
    for _ in range(a.epochs):
        perm = torch.randperm(N)
        for st in range(0, N, a.batch):
            j = perm[st:st + a.batch]
            tb, ib = tok[j].to(dev), items[j].to(dev)
            mu, v, aux = net(tb, ib)
            dist = torch.distributions.Normal(mu, net.log_std.exp())
            lp = dist.log_prob(act[j].to(dev)).sum(-1)
            ratio = (lp - logp0[j].to(dev)).exp()
            A = adv_f[j].to(dev)
            pi = -torch.min(ratio * A, ratio.clamp(1 - a.clip, 1 + a.clip) * A).mean()
            vl = F.mse_loss(v, ret_f[j].to(dev))
            m = ok_f[j].to(dev)
            l_soon = (F.binary_cross_entropy_with_logits(aux[:, 0], soon_f[j].to(dev), reduction="none") * m).sum() / (m.sum() + 1)
            l_move = (((aux[:, 1:4] - move_f[j].to(dev)) ** 2).sum(-1) * m).sum() / (m.sum() + 1)
            ent = dist.entropy().sum(-1).mean()
            loss = pi + 0.5 * vl + a.aux * (l_soon + l_move) - a.ent * ent
            opt.zero_grad(); loss.backward(); torch.nn.utils.clip_grad_norm_(net.parameters(), 0.5); opt.step()
            for key, val in (("pi", pi), ("v", vl), ("aux_soon", l_soon), ("aux_move", l_move), ("ent", ent)):
                stats[key] += float(val.detach())
            k += 1
    return {key: val / k for key, val in stats.items()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--envs", type=int, default=256)
    ap.add_argument("--steps", type=int, default=128)
    ap.add_argument("--updates", type=int, default=500)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--out", default="runs/sp1")
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--epochs", type=int, default=4)
    ap.add_argument("--batch", type=int, default=4096)
    ap.add_argument("--clip", type=float, default=0.2)
    ap.add_argument("--ent", type=float, default=0.002)
    ap.add_argument("--aux", type=float, default=0.2)
    ap.add_argument("--gamma", type=float, default=0.98)
    ap.add_argument("--lam", type=float, default=0.95)
    ap.add_argument("--snapshot", type=int, default=20)
    ap.add_argument("--resume", default=None)
    ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    dev = torch.device(a.device)
    torch.manual_seed(a.seed)
    if a.device == "cpu":
        torch.set_num_threads(max(1, os.cpu_count() - 1))
    n, S = a.envs, a.steps
    env = Duel(n, seed=a.seed)
    sn, an = make("s").to(dev), make("a").to(dev)
    if a.resume:
        ck = torch.load(a.resume, map_location=dev); sn.load_state_dict(ck["s"]); an.load_state_dict(ck["a"])
    so, ao = torch.optim.Adam(sn.parameters(), lr=a.lr), torch.optim.Adam(an.parameters(), lr=a.lr)
    pool_s, pool_a = [copy.deepcopy(sn.state_dict())], [copy.deepcopy(an.state_dict())]
    old_s, old_a = make("s").to(dev).eval(), make("a").to(dev).eval()
    nA, nB = int(n * 0.5), int(n * 0.3)
    grp = np.array(["A"] * nA + ["B"] * nB + ["C"] * (n - nA - nB))
    log = open(os.path.join(a.out, "log.jsonl"), "a")
    t0 = time.time()
    ep_stats = {}
    for u in range(1, a.updates + 1):
        old_s.load_state_dict(pool_s[np.random.randint(len(pool_s))]); old_a.load_state_dict(pool_a[np.random.randint(len(pool_a))])
        # в группе B половина арен — сценарный лучник из v1
        env.scripted[:] = False
        env.scripted[(grp == "B") & (np.arange(n) % 2 == 0)] = True
        bs, ba = Buffer(S, n, S_TOK, S_ACT, dev), Buffer(S, n, A_TOK, A_ACT, dev)
        bs.learn = torch.as_tensor(grp != "C"); ba.learn = torch.as_tensor(grp != "B")
        use_old_s = torch.as_tensor(grp == "C", device=dev); use_old_a = torch.as_tensor(grp == "B", device=dev)
        for t in range(S):
            st, si, at, ai = (torch.as_tensor(x) for x in env.obs())
            with torch.no_grad():
                s_a, s_lp, s_v = sn.act(st.to(dev), si.to(dev))
                a_a, a_lp, a_v = an.act(at.to(dev), ai.to(dev))
                if use_old_s.any():
                    o, _, _ = old_s.act(st.to(dev), si.to(dev)); s_a = torch.where(use_old_s[:, None], o, s_a)
                if use_old_a.any():
                    o, _, _ = old_a.act(at.to(dev), ai.to(dev)); a_a = torch.where(use_old_a[:, None], o, a_a)
            c, s = env._frame(env.bpos, env.ppos); c2, s2 = env._frame(env.ppos, env.bpos)
            bs.tok[t], bs.items[t], bs.act[t], bs.logp[t], bs.val[t] = st, si, s_a.cpu(), s_lp.cpu(), s_v.cpu()
            ba.tok[t], ba.items[t], ba.act[t], ba.logp[t], ba.val[t] = at, ai, a_a.cpu(), a_lp.cpu(), a_v.cpu()
            bs.opp[t] = torch.as_tensor(env.ppos); ba.opp[t] = torch.as_tensor(env.bpos)
            bs.frame[t] = torch.as_tensor(np.stack([c, s], 1)); ba.frame[t] = torch.as_tensor(np.stack([c2, s2], 1))
            rs, ra, done, info = env.step(s_a.cpu().numpy(), a_a.cpu().numpy())
            bs.rew[t], ba.rew[t] = torch.as_tensor(rs), torch.as_tensor(ra)
            bs.done[t] = ba.done[t] = torch.as_tensor(done, dtype=torch.float32)
            bs.event[t] = torch.as_tensor(info["released"], dtype=torch.float32)
            ba.event[t] = torch.as_tensor(info["dashed"], dtype=torch.float32)
            if info["done_stats"] is not None:
                g = grp[done]
                for key, v in info["done_stats"].items():
                    for gg in "ABC":
                        ep_stats.setdefault(gg, {}).setdefault(key, 0.0)
                        ep_stats[gg][key] += float(v[g == gg].sum())
        st, si, at, ai = (torch.as_tensor(x).to(dev) for x in env.obs())
        with torch.no_grad():
            _, vs, _ = sn(st, si); _, va, _ = an(at, ai)
        out = {"update": u, "steps": u * S * n, "sec": round(time.time() - t0)}
        for kind, net, opt, buf, lv in (("s", sn, so, bs, vs.cpu()), ("a", an, ao, ba, va.cpu())):
            adv, ret = gae(buf, lv, S, a.gamma, a.lam)
            soon, move, ok = labels(buf, S)
            for key, val in update(net, opt, buf, adv, ret, soon, move, ok, S, a, dev).items():
                out[f"{kind}_{key}"] = round(val, 4)
        if u % a.snapshot == 0:
            pool_s.append(copy.deepcopy(sn.state_dict())); pool_a.append(copy.deepcopy(an.state_dict()))
            pool_s, pool_a = pool_s[-12:], pool_a[-12:]
        if ep_stats:
            for gg, d in ep_stats.items():
                if d.get("shots", 0) > 0:
                    out[f"{gg}_arrow_hit"] = round(d["arrow_hits"] / d["shots"], 3)
                if d.get("volleys", 0) > 0:
                    out[f"{gg}_feather_hit_per_volley"] = round(d["feather_hits"] / d["volleys"], 3)
                out[f"{gg}_reflects"] = d.get("reflects", 0); out[f"{gg}_reflect_hits"] = d.get("reflect_hits", 0)
            ep_stats = {}
        log.write(json.dumps(out) + "\n"); log.flush()
        if u % 5 == 0 or u == 1:
            print(json.dumps(out), flush=True)
        if u % 50 == 0 or u == a.updates:
            torch.save({"s": sn.state_dict(), "a": an.state_dict()}, os.path.join(a.out, "duel.pt"))


if __name__ == "__main__":
    main()
