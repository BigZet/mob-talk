"""Тренажёр v2: дуэль Серафима и лучника, оба управляются сетями (или лучник по сценарию из v1).

Один шаг = один тик сервера. Физика как в v1 (sim.py): полёт Серафима повторяет SeraphEntity, снаряды повторяют ванильные.
Новое:
  * Серафим атакует «огненными перьями» (каст 20 тиков, пока каст идёт, он не рулит: так в SacredBoss), сам решает
    когда и куда целить с упреждением;
  * «огненный взмах»: Серафим смыкает крылья (3 тика, видно), затем 6 тиков отбивает стрелы обратно в лучника,
    отдых 80 тиков; во время взмаха рывка нет;
  * лучник ходит, натягивает и отпускает тетиву, целится с поправкой, сам уворачивается от перьев;
  * каждый видит другого с задержкой REACT тиков (как человек) и помнит последние T тиков.
Наблюдение каждого агента: tokens [n, T, F] (история по тикам), items [n, P, 11] (снаряды), mask [n, P].
"""
import numpy as np

# ---- Серафим ----
W, H = 3.4, 7.2
FACE = 0.57
HOVER = 3.5
ORBIT_R = 12.0
BASE_CAP = 0.32
STEER_ACC, STEER_CAP, LIFT = 0.06, 0.16, 2.0
DASH_TICKS, DASH_CD = 8, 60
# рывок ~8.5 блока с плавным разгоном и торможением (блоков за тик по тикам рывка)
DASH_PROFILE = np.array([0.7, 1.3, 1.6, 1.6, 1.4, 1.0, 0.6, 0.3])
DASH_SPEED = float(DASH_PROFILE.max())
SMOOTH = 0.75            # руление и высота сглажены: steer = SMOOTH*steer + (1-SMOOTH)*новое
DEFLECT_WIND, DEFLECT_ACTIVE, DEFLECT_CD = 3, 6, 80
CAST_TICKS, ATTACK_CD = 20, 45          # после выстрела перьями ждать ещё ATTACK_CD тиков
FEATHERS, FEATHER_SPEED, FEATHER_LIFE, FEATHER_R, FEATHER_SPREAD = 5, 0.5, 60, 0.35, 0.14
REFLECT_SPEED = 2.0
HIT_MARGIN = 0.3
# ---- лучник ----
PW, PH, EYE = 0.6, 1.8, 1.62
WALK, DRAW_SLOW = 0.2158, 0.2
ARENA = 30.0
AIM_NOISE_DEG = 0.7
# ---- снаряды ----
ARROW, REFLECTED, FEATHER = 0, 1, 2
MAXP = 16
P_OBS = 8
ITEM_F = 11
# ---- память ----
T = 16
REACT = 2
EP_LEN = 600

S_ACT = 8   # sx, lift, sz, dash, deflect, attack, aim_side, aim_up
A_ACT = 5   # forward, strafe, draw, aim_side, aim_up
S_TOK = 23
A_TOK = 20


def bow_power(t):
    f = t / 20.0
    return np.minimum((f * f + 2 * f) / 3.0, 1.0)


def rot(v, c, s):
    x = v[..., 0] * c + v[..., 2] * s
    z = -v[..., 0] * s + v[..., 2] * c
    return np.stack([x, v[..., 1], z], -1)


def unrot(v, c, s):
    return rot(v, c, -s)


def ballistic(flat, dy, speed, g=0.05):
    """Угол вверх (векторно), при котором стрела с сопротивлением 0.99 проходит через (flat, dy)."""
    lo = np.arctan2(dy, flat); hi = np.full_like(lo, np.deg2rad(80))
    for _ in range(14):
        mid = (lo + hi) / 2
        vx, vy = np.cos(mid) * speed, np.sin(mid) * speed
        x = np.zeros_like(mid); y = np.zeros_like(mid); done = np.zeros(mid.shape, bool); yat = np.full_like(mid, -1e9)
        for _ in range(60):
            x = np.where(done, x, x + vx); y = np.where(done, y, y + vy)
            vx = vx * 0.99; vy = vy * 0.99 - g
            newly = (~done) & (x >= flat)
            yat = np.where(newly, y, yat); done |= newly
        low = (~done) | (yat < dy)
        lo = np.where(low, mid, lo); hi = np.where(low, hi, mid)
    return (lo + hi) / 2


class Duel:
    def __init__(self, n, seed=0, scripted_frac=0.0, react=REACT):
        self.n = n
        self.rng = np.random.default_rng(seed)
        self.scripted_frac = scripted_frac
        self.react = react
        self._alloc()
        self.reset(np.ones(n, bool))

    # ------------------------------------------------------------------ state
    def _alloc(self):
        n = self.n
        z3 = lambda: np.zeros((n, 3))
        self.bpos, self.bvel = z3(), z3()
        self.orbit = np.zeros(n); self.age = np.zeros(n, int); self.t = np.zeros(n, int)
        self.dash_cd = np.zeros(n, int); self.dash_left = np.zeros(n, int); self.dash_dir = np.zeros((n, 2)); self.steer = np.zeros((n, 2)); self.lift = np.zeros(n)
        self.defl_cd = np.zeros(n, int); self.defl_t = np.zeros(n, int)   # >0: идёт взмах, считает вниз от WIND+ACTIVE
        self.cast_t = np.zeros(n, int); self.atk_cd = np.zeros(n, int); self.cast_aim = np.zeros((n, 2))
        self.ppos, self.pvel = z3(), z3()
        self.draw = np.zeros(n, int); self.look = z3(); self.pcool = np.zeros(n, int)
        self.scripted = np.zeros(n, bool)
        self.s_hold = np.zeros(n, int); self.s_dir = np.zeros(n); self.s_lead = np.zeros(n, bool)
        self.q_on = np.zeros((n, MAXP), bool); self.q_pos = np.zeros((n, MAXP, 3)); self.q_vel = np.zeros((n, MAXP, 3))
        self.q_kind = np.zeros((n, MAXP), int); self.q_age = np.zeros((n, MAXP), int)
        self.released = np.zeros(n, bool); self.dashed = np.zeros(n, bool)
        D = self.react
        self.s_hist = np.zeros((n, T + D, S_TOK), np.float32); self.a_hist = np.zeros((n, T + D, A_TOK), np.float32)
        self.s_items = np.zeros((n, D + 1, P_OBS, ITEM_F), np.float32); self.a_items = np.zeros((n, D + 1, P_OBS, ITEM_F), np.float32)
        self.stats = {k: np.zeros(n) for k in ("shots", "arrow_hits", "volleys", "feather_hits", "reflects", "reflect_hits", "dashes")}

    def reset(self, m):
        k = int(m.sum())
        if k == 0:
            return
        r = self.rng
        self.ppos[m] = 0; self.pvel[m] = 0
        ang = r.uniform(0, 2 * np.pi, k); dist = r.uniform(8, 16, k)
        self.bpos[m] = np.stack([np.cos(ang) * dist, np.full(k, HOVER), np.sin(ang) * dist], 1)
        self.bvel[m] = 0; self.orbit[m] = ang; self.age[m] = r.integers(0, 1000, k); self.t[m] = 0
        for a in (self.dash_cd, self.dash_left, self.defl_cd, self.defl_t, self.cast_t, self.draw, self.pcool):
            a[m] = 0
        self.steer[m] = 0; self.lift[m] = 0
        self.atk_cd[m] = r.integers(20, 60, k)
        self.scripted[m] = r.random(k) < self.scripted_frac
        self.s_hold[m] = 0; self.s_dir[m] = r.uniform(0, 2 * np.pi, k); self.s_lead[m] = r.random(k) < 0.7
        self.q_on[m] = False
        for v in self.stats.values():
            v[m] = 0
        # история: заполнить первым кадром
        st, at = self._tokens()
        si, ai = self._items()
        self.s_hist[m] = st[m][:, None, :]; self.a_hist[m] = at[m][:, None, :]
        self.s_items[m] = si[m][:, None]; self.a_items[m] = ai[m][:, None]

    # ------------------------------------------------------------ observation
    def _frame(self, frm, to):
        d = to - frm
        a = np.arctan2(d[:, 2], d[:, 0])
        return np.cos(a), np.sin(a)

    def _tokens(self):
        n = self.n
        c, s = self._frame(self.bpos, self.ppos)
        center = self.bpos + np.array([0, H / 2, 0])
        eye = self.ppos + np.array([0, EYE, 0])
        drawing = (self.draw > 0).astype(float)
        pull = bow_power(self.draw) * drawing
        to_b = center - eye; to_b /= np.linalg.norm(to_b, axis=1, keepdims=True) + 1e-9
        aim_off = np.arccos(np.clip((self.look * to_b).sum(1), -1, 1)) / 0.5
        dashing = (self.dash_left > 0).astype(float)
        wind = ((self.defl_t > DEFLECT_ACTIVE)).astype(float)
        active = ((self.defl_t > 0) & (self.defl_t <= DEFLECT_ACTIVE)).astype(float)
        casting = np.where(self.cast_t > 0, 1 - self.cast_t / CAST_TICKS, 0.0)
        st = np.concatenate([
            rot(self.bvel, c, s) / 0.6, ((self.bpos[:, 1] - HOVER) / 3)[:, None],
            (1 - self.dash_cd / DASH_CD)[:, None], (1 - self.defl_cd / DEFLECT_CD)[:, None], (1 - self.atk_cd / ATTACK_CD).clip(0, 1)[:, None],
            casting[:, None], (wind + active)[:, None],
            rot(eye - center, c, s) / 20, rot(self.pvel, c, s) / WALK,
            drawing[:, None], pull[:, None], rot(self.look, c, s), aim_off[:, None],
            (self.pcool > 0).astype(float)[:, None], dashing[:, None],
        ], 1)
        # лучник смотрит из своей рамки: ось x на Серафима
        c2, s2 = self._frame(self.ppos, self.bpos)
        at = np.concatenate([
            rot(self.pvel, c2, s2) / WALK, drawing[:, None], pull[:, None], (self.pcool > 0).astype(float)[:, None],
            rot(center - eye, c2, s2) / 20, rot(self.bvel, c2, s2) / 0.6,
            dashing[:, None], wind[:, None], active[:, None], casting[:, None],
            rot(self.look, c2, s2), aim_off[:, None],
        ], 1)
        return st.astype(np.float32), at.astype(np.float32)

    def _items_for(self, who):
        """Снаряды, летящие к агенту: [n, P, 11] в его рамке."""
        n = self.n
        if who == "s":
            c, s = self._frame(self.bpos, self.ppos)
            center = self.bpos + np.array([0, H / 2, 0])
            mine = self.q_kind != ARROW         # своё не важно, кроме стрел лучника
            danger = self.q_on & ~mine
        else:
            c, s = self._frame(self.ppos, self.bpos)
            center = self.ppos + np.array([0, PH / 2, 0])
            danger = self.q_on & (self.q_kind != ARROW)
        rel = self.q_pos - center[:, None]
        v = self.q_vel
        vv = (v * v).sum(-1) + 1e-9
        along = (rel * v).sum(-1)
        tca = np.clip(-along / vv, 0, 40)
        miss = np.linalg.norm(rel + v * tca[..., None], axis=-1)
        ok = danger & (along < 0)
        key = np.where(ok, tca + miss * 0.2, 1e9)
        order = np.argsort(key, 1)[:, :P_OBS]
        idx = np.arange(n)[:, None]
        on = ok[idx, order]
        pr = rot(rel[idx, order], c[:, None], s[:, None]) / 20
        pv = rot(v[idx, order], c[:, None], s[:, None]) / 3
        kind = np.eye(3)[self.q_kind[idx, order]]
        f = np.concatenate([pr, pv, kind, np.minimum(tca[idx, order] / 20, 1)[..., None], on[..., None]], -1)
        return (f * on[..., None]).astype(np.float32)

    def _items(self):
        return self._items_for("s"), self._items_for("a")

    def obs(self):
        """(seraph_tokens, seraph_items, archer_tokens, archer_items) с задержкой реакции."""
        return (self.s_hist[:, :T], self.s_items[:, 0], self.a_hist[:, :T], self.a_items[:, 0])

    def _push_obs(self):
        st, at = self._tokens()
        si, ai = self._items()
        self.s_hist = np.concatenate([self.s_hist[:, 1:], st[:, None]], 1)
        self.a_hist = np.concatenate([self.a_hist[:, 1:], at[:, None]], 1)
        self.s_items = np.concatenate([self.s_items[:, 1:], si[:, None]], 1)
        self.a_items = np.concatenate([self.a_items[:, 1:], ai[:, None]], 1)

    # ------------------------------------------------------------------ step
    def step(self, s_act, a_act):
        n, r = self.n, self.rng
        s_act = np.clip(np.asarray(s_act, float), -1, 1)
        a_act = np.clip(np.asarray(a_act, float), -1, 1)
        rs = np.zeros(n); ra = np.zeros(n)
        self.released[:] = False; self.dashed[:] = False

        # ================= Серафим =================
        c, s = self._frame(self.bpos, self.ppos)
        steer_w = unrot(s_act[:, :3] * np.array([1, 0, 1]), c, s)[:, [0, 2]]
        self.steer = SMOOTH * self.steer + (1 - SMOOTH) * steer_w
        self.lift = SMOOTH * self.lift + (1 - SMOOTH) * s_act[:, 1]
        lift = self.lift
        casting = self.cast_t > 0
        # взмах
        go_def = (s_act[:, 4] > 0) & (self.defl_cd == 0) & ~casting & (self.dash_left == 0)
        self.defl_t[go_def] = DEFLECT_WIND + DEFLECT_ACTIVE; self.defl_cd[go_def] = DEFLECT_CD
        # рывок
        go_dash = (s_act[:, 3] > 0) & (self.dash_cd == 0) & ~casting & (self.defl_t == 0)
        dirn = steer_w / (np.linalg.norm(steer_w, axis=1, keepdims=True) + 1e-9)
        self.dash_dir[go_dash] = dirn[go_dash]; self.dash_left[go_dash] = DASH_TICKS; self.dash_cd[go_dash] = DASH_CD
        self.dashed |= go_dash; self.stats["dashes"] += go_dash
        # атака перьями: начать каст
        go_atk = (s_act[:, 5] > 0) & (self.atk_cd == 0) & ~casting & (self.dash_left == 0)
        self.cast_t[go_atk] = CAST_TICKS; self.cast_aim[go_atk] = s_act[go_atk][:, 6:8]
        rs -= 0.002 * go_dash + 0.002 * go_def
        casting = self.cast_t > 0
        dashing = self.dash_left > 0
        # pursue(): во время каста не вызывается (SacredBoss.mobTick возвращается раньше)
        self.orbit += 0.02
        goal = self.ppos[:, [0, 2]] + np.stack([np.cos(self.orbit), np.sin(self.orbit)], 1) * ORBIT_R
        flat = goal - self.bpos[:, [0, 2]]
        fl = np.linalg.norm(flat, axis=1, keepdims=True)
        push = np.where(fl < 0.5, 0, flat / (fl + 1e-9) * 0.05)
        mag = np.linalg.norm(self.steer, axis=1).clip(0, 1)
        nxt = self.bvel[:, [0, 2]] * 0.9 + push + self.steer * STEER_ACC
        dspeed = DASH_PROFILE[np.clip(DASH_TICKS - self.dash_left, 0, DASH_TICKS - 1)]
        nxt = np.where(dashing[:, None], self.dash_dir * dspeed[:, None], nxt)
        cap = np.where(dashing, dspeed, BASE_CAP + STEER_CAP * mag)
        hl = np.linalg.norm(nxt, axis=1); nxt *= np.where(hl > cap, cap / (hl + 1e-9), 1)[:, None]
        nxt = np.where(casting[:, None], self.bvel[:, [0, 2]], nxt)
        lift_eff = np.where(casting, 0, lift)
        # hold()
        wanted = np.maximum(HOVER + np.sin(self.age * 0.05) * 0.6 + lift_eff * LIFT, 0.05)
        dy = np.clip((wanted - self.bpos[:, 1]) * 0.08, -0.35, 0.3)
        vy = self.bvel[:, 1] * 0.6 + dy + lift_eff * 0.06
        self.bvel = np.stack([nxt[:, 0], vy, nxt[:, 1]], 1)
        self.bpos = self.bpos + self.bvel; self.bpos[:, 1] = np.maximum(self.bpos[:, 1], 0)
        self.bvel *= np.array([0.91, 0.98, 0.91])
        self.age += 1
        # таймеры
        self.dash_left = np.maximum(self.dash_left - 1, 0); self.dash_cd = np.maximum(self.dash_cd - 1, 0)
        self.defl_t = np.maximum(self.defl_t - 1, 0); self.defl_cd = np.maximum(self.defl_cd - 1, 0)
        self.atk_cd = np.maximum(self.atk_cd - 1, 0)
        fire = (self.cast_t == 1)
        self.cast_t = np.maximum(self.cast_t - 1, 0)
        for i in np.nonzero(fire)[0]:
            self._feathers(i)
        self.atk_cd[fire] = ATTACK_CD
        self.stats["volleys"] += fire

        # ================= лучник =================
        self._archer(a_act)

        # ================= снаряды =================
        hit_b, hit_p_feather, hit_p_reflect, reflected = self._projectiles()
        self.stats["arrow_hits"] += hit_b; self.stats["feather_hits"] += hit_p_feather
        self.stats["reflects"] += reflected; self.stats["reflect_hits"] += hit_p_reflect
        rs += -1.0 * hit_b + 0.5 * hit_p_feather + 1.0 * hit_p_reflect + 0.1 * reflected
        ra += 1.0 * hit_b - 0.5 * hit_p_feather - 1.0 * hit_p_reflect
        d = np.linalg.norm((self.ppos - self.bpos)[:, [0, 2]], axis=1)
        rs -= 0.01 * (np.maximum(0, d - 16) + np.maximum(0, 6 - d)) + 0.003 * np.abs(self.bpos[:, 1] - HOVER) + 0.002 * mag ** 2

        self.t += 1
        self._push_obs()
        done = self.t >= EP_LEN
        info = {"done_stats": {k: v[done].copy() for k, v in self.stats.items()} if done.any() else None,
                "released": self.released.copy(), "dashed": self.dashed.copy()}
        if done.any():
            self.reset(done)
        return rs.astype(np.float32), ra.astype(np.float32), done, info

    # ---------------------------------------------------------------- archer
    def _archer(self, a_act):
        n, r = self.n, self.rng
        c2, s2 = self._frame(self.ppos, self.bpos)
        drawing = self.draw > 0
        # сценарный лучник из v1 (стоит, тянет полностью 22–30 тиков, иногда держит дольше, целит с упреждением)
        sc = self.scripted
        if sc.any():
            a_act = a_act.copy()
            self.s_dir[sc] += r.normal(0, 0.15, sc.sum())
            a_act[sc, 0] = 0; a_act[sc, 1] = np.sin(self.s_dir[sc]) * 0.5
            start = sc & ~drawing & (self.pcool == 0)
            self.s_hold[start] = r.integers(22, 30, start.sum()) + np.where(r.random(start.sum()) < 0.3, r.integers(10, 50, start.sum()), 0)
            a_act[sc, 2] = np.where(self.draw[sc] < self.s_hold[sc], 1, -1)
            a_act[sc, 3:5] = 0
        # ходьба в рамке «на Серафима»
        want = unrot(np.stack([a_act[:, 0], np.zeros(n), a_act[:, 1]], 1), c2, s2)
        speed = WALK * np.where(drawing, DRAW_SLOW, 1.0)
        wl = np.linalg.norm(want, axis=1, keepdims=True)
        want = np.where(wl > 1, want / (wl + 1e-9), want) * speed[:, None]
        self.pvel = self.pvel * 0.4 + want * 0.6
        self.pvel[:, 1] = 0
        self.ppos += self.pvel
        rr = np.linalg.norm(self.ppos[:, [0, 2]], axis=1)
        over = rr > ARENA
        self.ppos[over, 0] *= ARENA / rr[over]; self.ppos[over, 2] *= ARENA / rr[over]
        # прицел: баллистика на центр Серафима + поправка сети (упреждение) в блоках
        eye = self.ppos + np.array([0, EYE, 0])
        center = self.bpos + np.array([0, H / 2, 0])
        lead = np.zeros((n, 3))
        if sc.any():
            dist = np.linalg.norm(center - eye, axis=1)
            lead[sc] = self.bvel[sc] * (dist[sc] / 3.0 * self.s_lead[sc])[:, None]
        off = unrot(np.stack([np.zeros(n), a_act[:, 4] * 3, a_act[:, 3] * 4], 1), c2, s2)
        tgt = center + off + lead
        to = tgt - eye
        flat = np.hypot(to[:, 0], to[:, 2])
        pw = np.where(drawing, bow_power(self.draw), 1.0)
        pitch = ballistic(flat, to[:, 1], 3.0 * np.maximum(pw, 0.3))
        yaw = np.arctan2(to[:, 2], to[:, 0])
        self.look = np.stack([np.cos(pitch) * np.cos(yaw), np.sin(pitch), np.cos(pitch) * np.sin(yaw)], 1)
        # тетива
        self.pcool = np.maximum(self.pcool - 1, 0)
        pulled = self.draw.copy()
        want_draw = a_act[:, 2] > 0
        start = want_draw & ~drawing & (self.pcool == 0)
        self.draw[start] = 1
        self.draw[want_draw & drawing] += 1
        rel = ~want_draw & drawing
        fire = rel & (pulled >= 3)
        self.draw[rel] = 0
        for i in np.nonzero(fire)[0]:
            d = self.look[i] + np.deg2rad(AIM_NOISE_DEG) * r.normal(0, 1, 3)
            d /= np.linalg.norm(d)
            d = d + r.triangular(-1, 0, 1, 3) * 0.0172275
            sp = 3.0 * bow_power(pulled[i])
            self._spawn(i, ARROW, eye[i] - np.array([0, 0.1, 0]), d * sp + np.array([self.pvel[i, 0], 0, self.pvel[i, 2]]))
        self.pcool[fire] = 2
        self.released |= fire
        self.stats["shots"] += fire

    # --------------------------------------------------------------- seraph
    def _feathers(self, i):
        r = self.rng
        frm = self.bpos[i] + np.array([0, H * FACE, 0])
        c, s = self._frame(self.bpos[i:i + 1], self.ppos[i:i + 1])
        aim_pt = self.ppos[i] + np.array([0, EYE, 0]) + unrot(np.array([[0, self.cast_aim[i, 1] * 2, self.cast_aim[i, 0] * 4]]), c, s)[0]
        aim = aim_pt - frm; aim /= np.linalg.norm(aim)
        for k in range(FEATHERS):
            sp = (k - (FEATHERS - 1) / 2) * FEATHER_SPREAD
            dirv = np.array([aim[0] * np.cos(sp) - aim[2] * np.sin(sp), aim[1] + (r.random() - 0.5) * 0.08,
                             aim[0] * np.sin(sp) + aim[2] * np.cos(sp)])
            dirv /= np.linalg.norm(dirv)
            self._spawn(i, FEATHER, frm.copy(), dirv * FEATHER_SPEED)

    def _spawn(self, i, kind, pos, vel):
        free = np.nonzero(~self.q_on[i])[0]
        slot = free[0] if len(free) else int(np.argmax(self.q_age[i]))
        self.q_on[i, slot] = True; self.q_pos[i, slot] = pos; self.q_vel[i, slot] = vel
        self.q_kind[i, slot] = kind; self.q_age[i, slot] = 0

    # ---------------------------------------------------------- projectiles
    @staticmethod
    def _seg_box(p0, v, lo, hi):
        inv = 1.0 / np.where(np.abs(v) < 1e-9, 1e-9, v)
        t1 = (lo[:, None, :] - p0) * inv; t2 = (hi[:, None, :] - p0) * inv
        tmin = np.minimum(t1, t2).max(-1); tmax = np.maximum(t1, t2).min(-1)
        return (tmax >= np.maximum(tmin, 0)) & (tmin <= 1), tmin

    def _projectiles(self):
        n, r = self.n, self.rng
        p0, v = self.q_pos, self.q_vel
        blo = self.bpos - np.array([W / 2 + HIT_MARGIN, HIT_MARGIN, W / 2 + HIT_MARGIN])
        bhi = self.bpos + np.array([W / 2 + HIT_MARGIN, H + HIT_MARGIN, W / 2 + HIT_MARGIN])
        hb, _ = self._seg_box(p0, v, blo, bhi)
        arrows = self.q_on & (self.q_kind == ARROW)
        hit_box = arrows & hb
        active = ((self.defl_t > 0) & (self.defl_t <= DEFLECT_ACTIVE))[:, None]
        refl = hit_box & active
        hit_b = (hit_box & ~active).sum(1).astype(float)
        # отражённые стрелы летят обратно в лучника
        eye = self.ppos + np.array([0, EYE, 0])
        for i, j in zip(*np.nonzero(refl)):
            d = eye[i] - p0[i, j]; d /= np.linalg.norm(d) + 1e-9
            d = d + np.deg2rad(2.0) * r.normal(0, 1, 3); d /= np.linalg.norm(d)
            self.q_vel[i, j] = d * REFLECT_SPEED; self.q_kind[i, j] = REFLECTED; self.q_age[i, j] = 0
        gone = hit_box & ~active
        # попадания в лучника
        m = np.array([PW / 2 + FEATHER_R, FEATHER_R, PW / 2 + FEATHER_R])
        plo = self.ppos - m; phi = self.ppos + np.array([PW / 2 + FEATHER_R, PH + FEATHER_R, PW / 2 + FEATHER_R])
        hp, _ = self._seg_box(self.q_pos, self.q_vel, plo, phi)
        hit_feather = self.q_on & (self.q_kind == FEATHER) & hp
        mr = np.array([PW / 2 + HIT_MARGIN, HIT_MARGIN, PW / 2 + HIT_MARGIN])
        hr, _ = self._seg_box(self.q_pos, self.q_vel, self.ppos - mr, self.ppos + np.array([PW / 2 + HIT_MARGIN, PH + HIT_MARGIN, PW / 2 + HIT_MARGIN]))
        hit_refl = self.q_on & (self.q_kind == REFLECTED) & hr & ~refl
        gone |= hit_feather | hit_refl
        # движение
        p1 = self.q_pos + self.q_vel
        gone |= self.q_on & (p1[..., 1] <= 0) & (self.q_kind != FEATHER)
        self.q_pos = p1
        grav = np.where(self.q_kind == FEATHER, 0.0, 0.05)[..., None] * np.array([0, 1, 0])
        drag = np.where(self.q_kind == FEATHER, 1.0, 0.99)[..., None]
        self.q_vel = self.q_vel * drag - grav
        self.q_age += 1
        far = np.linalg.norm(self.q_pos - self.bpos[:, None], axis=-1) > 60
        old = np.where(self.q_kind == FEATHER, self.q_age > FEATHER_LIFE, self.q_age > 100)
        self.q_on &= ~(gone | far | old)
        return hit_b, hit_feather.sum(1).astype(float), hit_refl.sum(1).astype(float), refl.sum(1).astype(float)
