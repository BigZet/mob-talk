"""Проверка Серафима против сценарного лучника v1: сеть из duel.pt (детерминированно, mu) и без сети (нулевые действия).

python eval_v2.py runs/sp1/duel.pt                 # 256 арен, один эпизод (600 тиков), на CPU
python eval_v2.py runs/sp1_u*.pt --archer runs/sp1/duel.pt
python eval_v2.py runs/sp1_u1150.pt --recover --log logs/
python eval_v2.py runs/sp3/duel_u*.pt --v3 --human --archer runs/sp3/duel.pt
    # --v3: бой как в моде (касты, нисхождение, ульта, снежки, окна с оглушением); --human: сценарный лучник-человек
    # --recover: правило мода «после взмаха нет рывка, после рывка нет взмаха»;
    # --log: первая арена первого эпизода в seraph-fight-*.csv и seraph-events-*.csv — те же столбцы, что пишет мод
    #        (SeraphFightLog), чего тренажёр не знает (здоровье, фаза, окно, урон) — пусто
    # таблица: каждая копия против сценарного и против обученного лучника (ключ "a", детерминированно);
    # лучшая — с наименьшим средним из двух долей попаданий (при равенстве — более поздняя)

Чистая мера: в обучении B_arrow_hit смешивает сценарного лучника и лучников из пула.
"""
import argparse
import os
import sys

import numpy as np
import torch

from duel import A_ACT, CAST_TICKS, DEFLECT_ACTIVE, EP_LEN, S_ACT, S_TOK_V2, Duel, bow_power
from selfplay import make

KEYS = ("shots", "arrow_hits", "reflects", "reflect_hits", "dashes", "minis", "flips", "teleports")


FIGHT_HEADER = ("tick,hp,max_hp,phase,exposed,recovering,dist,height,dash_cd,deflect_cd,attack_cd,casting,dashing,"
                "deflecting,foe_drawing,foe_pull,out_sx,out_lift,out_sz,out_dash,out_deflect,out_feathers,out_aim_side,out_aim_up,"
                "out_shot_soon,did_dash,did_deflect,did_feathers,refused,"
                + ",".join("tok_" + n for n in ("vx", "vy", "vz", "height", "dash_ready", "deflect_ready", "attack_ready", "cast", "sweep",
                                               "eye_x", "eye_y", "eye_z", "foe_vx", "foe_vy", "foe_vz", "drawing", "pull", "look_x",
                                               "look_y", "look_z", "aim_off", "foe_cool", "dashing",
                                               "mini_ready", "mini", "tele_ready", "orbit_dir"))
                + "," + ",".join(f"it{k}_{n}" for k in range(8)
                                 for n in ("rx", "ry", "rz", "vx", "vy", "vz", "arrow", "reflected", "feather", "tca", "on"))
                + ",out_mini,out_side,out_radius,out_tele,did_mini,did_flip,did_tele,radius,orbit_dir")


def num(v):
    return "" if v is None else f"{float(v):.3f}"


class FightLog:
    """Арена 0 первого эпизода в формате мода (SeraphFightLog): строка на тик и события."""
    def __init__(self, folder, name):
        os.makedirs(folder, exist_ok=True)
        self.fight = open(os.path.join(folder, f"seraph-fight-{name}.csv"), "w", encoding="utf-8", newline="")
        self.events = open(os.path.join(folder, f"seraph-events-{name}.csv"), "w", encoding="utf-8", newline="")
        self.fight.write(FIGHT_HEADER + "\n"); self.events.write("tick,event,amount,raw,detail\n")

    def tick(self, env, t, s_a):
        i = 0
        flag = lambda b: "1" if b else "0"
        row = [str(t), "", "", str(env.phase[i]) if env.v3 else "", flag(env.exposed[i] > 0) if env.v3 else "", flag(env.recovering()[i]),
               num(np.linalg.norm(env.bpos[i] - env.ppos[i])), num(env.bpos[i, 1]),
               str(env.dash_cd[i]), str(env.defl_cd[i]), str(env.atk_cd[i]),
               num(1 - env.cast_t[i] / CAST_TICKS) if env.cast_t[i] > 0 else "",
               flag(env.dash_left[i] > 0), flag(0 < env.defl_t[i] <= DEFLECT_ACTIVE),
               flag(env.draw[i] > 0), num(bow_power(env.draw[i]) if env.draw[i] > 0 else 0)]
        row += [num(v) for v in s_a[i][:8]] + [""]
        row += [flag(env.dashed[i]), flag(env.deflected[i]), flag(env.attacked[i]), env.refused[i]]
        row += [num(v) for v in env.s_hist[i, -1]] + [num(v) for v in env.s_items[i, -1].reshape(-1)]   # признаки этого тика, как в моде
        extra = s_a[i][8:12] if s_a.shape[1] >= 12 else [None] * 4
        row += [num(v) for v in extra] + [flag(env.minis[i]), flag(env.flips[i]), flag(env.teles[i]), num(env.radius[i]), num(env.orbit_dir[i])]
        self.fight.write(",".join(row) + "\n")
        for event, amount, raw, detail in env.events:
            self.events.write(f"{t},{event},{num(amount)},{num(raw)},{detail}\n")
        env.events.clear()

    def close(self):
        self.fight.close(); self.events.close()


def run(net, n, seed, archer=None, recover=False, log=None, v3=False, human=False, v4=False):
    env = Duel(n, seed=seed, scripted_frac=0.0 if archer is not None else 1.0, recover=recover, v3=v3, human_frac=1.0 if human else 0.0, v4=v4)
    if log is not None:
        env.trace = 0
    zero_a = np.zeros((n, A_ACT), np.float32)
    total = {k: 0.0 for k in KEYS}; episodes = 0
    for t in range(EP_LEN):
        st, si, at, ai = (torch.as_tensor(x) for x in env.obs())
        with torch.no_grad():
            s_a = np.zeros((n, S_ACT), np.float32) if net is None else net.act(st, si, deterministic=True)[0].numpy()
            a_a = zero_a if archer is None else archer.act(at, ai, deterministic=True)[0].numpy()
        _, _, done, info = env.step(s_a, a_a)
        if log is not None:
            log.tick(env, t, np.clip(s_a, -1, 1))
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
            f"рывков/эп {total['dashes'] / episodes:.2f}, мини/эп {total['minis'] / episodes:.2f}, смен стороны/эп {total['flips'] / episodes:.2f}, "
            f"телепортов/эп {total['teleports'] / episodes:.2f}, эпизодов {episodes}")


class OldSeraph(torch.nn.Module):
    """Сеть v2/v3 (23 признака, 8 действий) в тренажёре v4: видит первые 23 признака, новые действия duel.py добивает сам."""
    def __init__(self, net):
        super().__init__(); self.net = net

    def act(self, tokens, items, deterministic=False):
        return self.net.act(tokens[..., :S_TOK_V2], items, deterministic)


def load(path, key):
    sd = torch.load(path, map_location="cpu")[key]
    if key == "s" and sd["emb.weight"].shape[1] == S_TOK_V2:
        from model import AgentNet
        from selfplay import AUX
        from duel import ITEM_F, S_ACT_V2, T
        net = AgentNet(S_TOK_V2, ITEM_F, S_ACT_V2, AUX, T).eval(); net.load_state_dict(sd)
        return OldSeraph(net).eval()
    net = make(key).eval()
    net.load_state_dict(sd)
    return net


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ckpt", nargs="+")
    ap.add_argument("--archer", default=None, help="duel.pt, чей обученный лучник (ключ a) стреляет во вторую колонку")
    ap.add_argument("--envs", type=int, default=256)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--recover", action="store_true", help="правило мода: после взмаха нет рывка, после рывка нет взмаха")
    ap.add_argument("--log", default=None, help="папка: арена 0 первой копии в формате лога боя мода")
    ap.add_argument("--v3", action="store_true", help="бой как в моде: касты, нисхождение, ульта, снежки, окна с оглушением")
    ap.add_argument("--human", action="store_true", help="сценарный лучник-человек вместо лучника v1")
    ap.add_argument("--v4", action="store_true", help="v3 и манёвры v4")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    torch.set_num_threads(a.threads)
    if a.archer is None:
        for k, path in enumerate(a.ckpt):
            log = FightLog(a.log, "sim-" + os.path.splitext(os.path.basename(path))[0]) if a.log and k == 0 else None
            print(line(f"сеть {path}", *run(load(path, "s"), a.envs, a.seed, recover=a.recover, log=log, v3=a.v3, human=a.human, v4=a.v4)), flush=True)
            if log is not None:
                log.close()
        print(line("без сети", *run(None, a.envs, a.seed, recover=a.recover, v3=a.v3, human=a.human, v4=a.v4)), flush=True)
        return
    archer = load(a.archer, "a")
    print("копия | попадания сценарного | попадания обученного | среднее | отражений/эп (сцен./обуч.)", flush=True)
    best = None
    for path in a.ckpt:
        net = load(path, "s")
        kw = dict(recover=a.recover, v3=a.v3, human=a.human, v4=a.v4)
        (ts, es), (tt, et) = run(net, a.envs, a.seed, **kw), run(net, a.envs, a.seed, archer, **kw)
        mean = (hit(ts) + hit(tt)) / 2
        print(f"{path} | {hit(ts):.3f} | {hit(tt):.3f} | {mean:.3f} | {ts['reflects'] / es:.2f} / {tt['reflects'] / et:.2f}", flush=True)
        if best is None or mean <= best[0]:
            best = (mean, path)
    kw = dict(recover=a.recover, v3=a.v3, human=a.human, v4=a.v4)
    (ts, _), (tt, _) = run(None, a.envs, a.seed, **kw), run(None, a.envs, a.seed, archer, **kw)
    print(f"без сети | {hit(ts):.3f} | {hit(tt):.3f} | {(hit(ts) + hit(tt)) / 2:.3f} | 0 / 0", flush=True)
    print(f"лучшая: {best[1]} (среднее {best[0]:.3f})", flush=True)


if __name__ == "__main__":
    main()
