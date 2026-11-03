"""Тренажёр v2/v3: дуэль Серафима и лучника, оба управляются сетями (или лучник по сценарию).

Один шаг = один тик сервера. Физика как в v1 (sim.py): полёт Серафима повторяет SeraphEntity, снаряды повторяют ванильные.
v2:
  * Серафим атакует «огненными перьями» (каст 20 тиков, пока каст идёт, он не рулит: так в SacredBoss), сам решает
    когда и куда целить с упреждением;
  * «огненный взмах»: Серафим смыкает крылья (3 тика, видно), затем 6 тиков отбивает стрелы обратно в лучника,
    отдых 80 тиков; во время взмаха рывка нет;
  * лучник ходит, натягивает и отпускает тетиву, целится с поправкой, сам уворачивается от перьев;
  * каждый видит другого с задержкой REACT тиков (как человек) и помнит последние T тиков.
v3 (Duel(v3=True)) — бой как в моде, а не только перестрелка:
  * касты по таймерам SacredBoss/SeraphEntity: «Огненный круг» (22 тика, кольцо по земле, прыжок спасает),
    «Нисхождение» (24 тика, затем 70 тиков на земле рядом с игроком), ульта «Пламень с небес» (60 тиков, затем круги
    со столпами вокруг игрока); пока каст или нисхождение, сеть не управляет, память идёт дальше;
  * снежки: попадание открывает слабое место на 6 с (оглушение: ни руля, ни рывка, ни взмаха, ни перьев), затем
    8 с перезарядки, как SeraphEntity.weakCooldownTicks; сеть видит снежки как снаряды игрока (вид «стрела»);
  * игрока отбрасывают попадания (как ванильный knockback) и толчок кольца, он прыгает;
  * фаза 1–3 на эпизод (перья 5/8, круги ульты 14/16/20, таймеры как в моде);
  * сценарный лучник-человек (human_frac): прицел держится на цели с лёгким упреждением под силу выпуска, натяжение
    40–60% времени, выпуск при силе 0,3–1,0, паузы без лука, ходьба/бег/зигзаги/подход и отход, прыжки, очереди снежков.
v4 (Duel(v4=True), включает v3) — разнообразие движения, четыре новых действия Серафима:
  * мини-уклонение: шаг ~2,4 блока за 4 тика, перезарядка 18 тиков (большой рывок остаётся, его перезарядка 45);
  * смена стороны облёта (знак выхода, перезарядка 40 тиков);
  * радиус облёта 6–18 блоков (подлёт и отход, сглажен);
  * телепорт за спину игрока: предупреждение 10 тиков (каст, видно), перезарядка 9 с;
  * награда за разнообразие: бонус за редко используемый манёвр, штраф за долгий облёт в одну сторону.
  Признаки Серафима v4: к 23 прежним добавлены 4 в конце (готовность мини, мини идёт, готовность телепорта, сторона облёта);
  сеть v2/v3 инициализирует новые столбцы и строки нулями (selfplay.widen).
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
RECOVER = 20             # recover=True: после взмаха столько тиков нет рывка, после рывка — взмаха (как SeraphMind.RECOVER в моде)
HIT_MARGIN = 0.3
# ---- v3: касты и окна (SacredBoss, SeraphEntity) ----
RING_CAST, RING_SPEED, RING_H = 22, 0.32, 1.2
DESCENT_CAST, DESCENT_TICKS, DESCENT_SPEED = 24, 70, 0.15
ULT_WARN, ZONE_R = 60, 1.8
EXPOSED_TICKS, WEAK_CD = 120, 160
KNOCK, RING_PUSH = 0.4, 0.6
# ---- v4: манёвры ----
MINI_PROFILE = np.array([0.5, 0.8, 0.7, 0.4])     # ~2,4 блока за 4 тика
MINI_TICKS, MINI_CD = len(MINI_PROFILE), 18
DASH_CD_V4 = 45
FLIP_CD = 40
RADIUS_MIN, RADIUS_MAX = 6.0, 18.0
TELE_WARN, TELE_CD = 10, 180
# виды кастов
C_NONE, C_FEATHERS, C_RING, C_DESCENT, C_ULT, C_TELE = 0, 1, 2, 3, 4, 5
CAST_LEN = {C_FEATHERS: CAST_TICKS, C_RING: RING_CAST, C_DESCENT: DESCENT_CAST, C_ULT: ULT_WARN, C_TELE: TELE_WARN}
# ---- лучник ----
PW, PH, EYE = 0.6, 1.8, 1.62
WALK, DRAW_SLOW, SPRINT = 0.2158, 0.2, 0.28
JUMP_V, GRAVITY_P = 0.42, 0.08
ARENA = 30.0
AIM_NOISE_DEG = 0.7
# ---- снаряды ----
ARROW, REFLECTED, FEATHER, SNOW = 0, 1, 2, 3
SNOW_SPEED, SNOW_G = 1.5, 0.03
MAXP = 16
P_OBS = 8
ITEM_F = 11
# ---- память ----
T = 16
REACT = 2
EP_LEN = 600

S_ACT = 12  # sx, lift, sz, dash, deflect, attack, aim_side, aim_up, mini, side, radius, teleport (v2/v3: первые 8)
S_ACT_V2 = 8
A_ACT = 5   # forward, strafe, draw, aim_side, aim_up
S_TOK = 27  # v2/v3: первые 23
S_TOK_V2 = 23
A_TOK = 20


def bow_power(t):
    f = t / 20.0
    return np.minimum((f * f + 2 * f) / 3.0, 1.0)


def draw_ticks(power):
    """Сколько тиков тянуть до силы power (обратная к bow_power)."""
    return np.ceil(20 * (-1 + np.sqrt(1 + 3 * np.clip(power, 0, 1)))).astype(int)


def rot(v, c, s):
    x = v[..., 0] * c + v[..., 2] * s
    z = -v[..., 0] * s + v[..., 2] * c
    return np.stack([x, v[..., 1], z], -1)


def unrot(v, c, s):
    return rot(v, c, -s)


def ballistic(flat, dy, speed, g=0.05):
    """Угол вверх (векторно), при котором снаряд с сопротивлением 0.99 проходит через (flat, dy)."""
    lo = np.arctan2(dy, flat); hi = np.full_like(lo, np.deg2rad(80))
    g = np.broadcast_to(g, lo.shape)
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
    def __init__(self, n, seed=0, scripted_frac=0.0, react=REACT, recover=False, v3=False, human_frac=0.0, v4=False):
        """recover: правило мода «после взмаха нет рывка, после рывка нет взмаха» (RECOVER тиков); в обучении sp1 его не было.
        v3: касты, нисхождение, ульта, снежки и окна с оглушением, отбрасывание и прыжки (см. шапку).
        human_frac: доля сценарных арен с лучником-человеком (остальные — сценарный лучник v1)."""
        self.n = n
        self.rng = np.random.default_rng(seed)
        self.scripted_frac = scripted_frac
        self.human_frac = human_frac
        self.react = react
        self.v4 = v4
        self.v3 = v3 or v4
        self.recover = recover or self.v3
        self.dash_cdmax = DASH_CD_V4 if v4 else DASH_CD
        self.roles_fixed = False  # selfplay v3: роли арен (сценарный или нет) держатся между эпизодами, их ставит сбор опыта
        self.trace = None        # номер арены, чьи события копятся в self.events (для лога боя, как в моде)
        self.events = []
        self._alloc()
        self.reset(np.ones(n, bool))

    # ------------------------------------------------------------------ state
    def _alloc(self):
        n = self.n
        z3 = lambda: np.zeros((n, 3))
        zi = lambda: np.zeros(n, int)
        self.bpos, self.bvel = z3(), z3()
        self.orbit = np.zeros(n); self.age = zi(); self.t = zi()
        self.dash_cd = zi(); self.dash_left = zi(); self.dash_dir = np.zeros((n, 2)); self.steer = np.zeros((n, 2)); self.lift = np.zeros(n)
        self.defl_cd = zi(); self.defl_t = zi()   # >0: идёт взмах, считает вниз от WIND+ACTIVE
        self.no_dash = zi(); self.no_defl = zi()  # recover: запреты после взмаха и после рывка
        self.cast_t = zi(); self.atk_cd = zi(); self.cast_aim = np.zeros((n, 2))
        self.cast_kind = zi(); self.cast_len = np.full(n, CAST_TICKS)
        # v3: таймеры атак и ульты SacredBoss, нисхождение, кольцо, круги ульты, слабое место, фаза
        self.atk_timer = zi(); self.ult_timer = zi(); self.descend = zi(); self.land = np.zeros((n, 2))
        self.ring_r = np.full(n, -1.0); self.ring_c = np.zeros((n, 2)); self.ring_max = np.zeros(n); self.ring_hit = np.zeros(n, bool)
        self.zone_t = np.zeros((n, 24), int); self.zone_c = np.zeros((n, 24, 2)); self.ult_left = zi(); self.ult_next = zi(); self.ult_step = zi()
        self.exposed = zi(); self.weak_cd = zi(); self.phase = np.ones(n, int)
        # v4: мини-уклонение, сторона и радиус облёта, телепорт, память манёвров для награды за разнообразие
        self.mini_left = zi(); self.mini_cd = zi(); self.mini_dir = np.zeros((n, 2)); self.orbit_dir = np.ones(n)
        self.flip_cd = zi(); self.since_flip = zi(); self.radius = np.full(n, ORBIT_R); self.tele_cd = zi()
        self.man_ema = np.zeros((n, 4)); self.minis = np.zeros(n, bool); self.flips = np.zeros(n, bool); self.teles = np.zeros(n, bool)
        self.ppos, self.pvel = z3(), z3()
        self.kb = z3(); self.pvy = np.zeros(n)
        self.draw = zi(); self.look = z3(); self.pcool = zi()
        self.scripted = np.zeros(n, bool); self.human = np.zeros(n, bool)
        self.s_hold = zi(); self.s_dir = np.zeros(n); self.s_lead = np.zeros(n, bool)
        # лучник-человек: режим ходьбы и его срок, пауза без лука, задуманная сила, снежки, смещение прицела
        self.h_mode = zi(); self.h_mode_t = zi(); self.h_side = np.ones(n); self.h_pause = zi(); self.h_power = np.ones(n)
        self.h_snow = np.zeros(n, bool); self.h_burst = zi(); self.h_burst_cd = zi(); self.h_aim = np.zeros((n, 2))
        self.q_on = np.zeros((n, MAXP), bool); self.q_pos = np.zeros((n, MAXP, 3)); self.q_vel = np.zeros((n, MAXP, 3))
        self.q_kind = np.zeros((n, MAXP), int); self.q_age = np.zeros((n, MAXP), int)
        self.released = np.zeros(n, bool); self.dashed = np.zeros(n, bool); self.threw = np.zeros(n, bool)
        self.deflected = np.zeros(n, bool); self.attacked = np.zeros(n, bool); self.refused = [""] * n
        self.last_pull = np.zeros(n)
        D = self.react
        self.s_hist = np.zeros((n, T + D, S_TOK), np.float32); self.a_hist = np.zeros((n, T + D, A_TOK), np.float32)
        self.s_items = np.zeros((n, D + 1, P_OBS, ITEM_F), np.float32); self.a_items = np.zeros((n, D + 1, P_OBS, ITEM_F), np.float32)
        self.stats = {k: np.zeros(n) for k in ("shots", "arrow_hits", "volleys", "feather_hits", "reflects", "reflect_hits", "dashes",
                                               "snows", "snow_hits", "windows", "ring_hits", "pillar_hits", "descents", "ults",
                                               "minis", "flips", "teleports")}

    def reset(self, m):
        k = int(m.sum())
        if k == 0:
            return
        r = self.rng
        self.ppos[m] = 0; self.pvel[m] = 0; self.kb[m] = 0; self.pvy[m] = 0
        ang = r.uniform(0, 2 * np.pi, k); dist = r.uniform(8, 16, k)
        self.bpos[m] = np.stack([np.cos(ang) * dist, np.full(k, HOVER), np.sin(ang) * dist], 1)
        self.bvel[m] = 0; self.orbit[m] = ang; self.age[m] = r.integers(0, 1000, k); self.t[m] = 0
        for a in (self.dash_cd, self.dash_left, self.defl_cd, self.defl_t, self.cast_t, self.draw, self.pcool, self.no_dash, self.no_defl,
                  self.cast_kind, self.descend, self.ult_left, self.exposed, self.weak_cd, self.h_burst, self.h_burst_cd,
                  self.mini_left, self.mini_cd, self.flip_cd, self.since_flip, self.tele_cd):
            a[m] = 0
        self.orbit_dir[m] = r.choice([-1.0, 1.0], k) if self.v4 else 1.0
        self.radius[m] = ORBIT_R; self.man_ema[m] = 0
        self.zone_t[m] = 0; self.ring_r[m] = -1
        self.steer[m] = 0; self.lift[m] = 0
        self.atk_cd[m] = r.integers(20, 60, k)
        # таймеры SacredBoss: первая атака через 2 с, ульта через 15 с; в середине боя — где угодно
        self.atk_timer[m] = r.integers(20, 140, k); self.ult_timer[m] = r.integers(60, 600, k)
        self.phase[m] = r.choice([1, 2, 3], k, p=[0.4, 0.35, 0.25])
        if not self.roles_fixed:
            self.scripted[m] = r.random(k) < self.scripted_frac
        self.human[m] = self.scripted[m] & (r.random(k) < self.human_frac)
        self.s_hold[m] = 0; self.s_dir[m] = r.uniform(0, 2 * np.pi, k); self.s_lead[m] = r.random(k) < 0.7
        self.h_mode[m] = r.integers(0, 4, k); self.h_mode_t[m] = r.integers(20, 80, k); self.h_side[m] = r.choice([-1.0, 1.0], k)
        self.h_pause[m] = r.integers(0, 30, k); self.h_power[m] = 1; self.h_snow[m] = r.random(k) < 0.6; self.h_aim[m] = 0
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
        casting = np.where(self.cast_t > 0, 1 - self.cast_t / self.cast_len, 0.0)
        # скорость игрока в признаке — только по земле (SeraphMind: foeVel без y)
        pflat = self.pvel * np.array([1, 0, 1])
        st = np.concatenate([
            rot(self.bvel, c, s) / 0.6, ((self.bpos[:, 1] - HOVER) / 3)[:, None],
            (1 - self.dash_cd / self.dash_cdmax)[:, None], (1 - self.defl_cd / DEFLECT_CD)[:, None], (1 - self.atk_cd / ATTACK_CD).clip(0, 1)[:, None],
            casting[:, None], (wind + active)[:, None],
            rot(eye - center, c, s) / 20, rot(pflat, c, s) / WALK,
            drawing[:, None], pull[:, None], rot(self.look, c, s), aim_off[:, None],
            (self.pcool > 0).astype(float)[:, None], dashing[:, None],
            (1 - self.mini_cd / MINI_CD)[:, None], (self.mini_left > 0).astype(float)[:, None],
            (1 - self.tele_cd / TELE_CD)[:, None], self.orbit_dir[:, None],
        ], 1)
        # лучник смотрит из своей рамки: ось x на Серафима
        c2, s2 = self._frame(self.ppos, self.bpos)
        at = np.concatenate([
            rot(pflat, c2, s2) / WALK, drawing[:, None], pull[:, None], (self.pcool > 0).astype(float)[:, None],
            rot(center - eye, c2, s2) / 20, rot(self.bvel, c2, s2) / 0.6,
            dashing[:, None], wind[:, None], active[:, None], casting[:, None],
            rot(self.look, c2, s2), aim_off[:, None],
        ], 1)
        return st.astype(np.float32), at.astype(np.float32)

    def _items_for(self, who):
        """Снаряды, летящие к агенту: [n, P, 11] в его рамке. Снежок Серафим видит как снаряд игрока (вид «стрела»), как в моде."""
        n = self.n
        if who == "s":
            c, s = self._frame(self.bpos, self.ppos)
            center = self.bpos + np.array([0, H / 2, 0])
            danger = self.q_on & ((self.q_kind == ARROW) | (self.q_kind == SNOW))
        else:
            c, s = self._frame(self.ppos, self.bpos)
            center = self.ppos + np.array([0, PH / 2, 0])
            danger = self.q_on & ((self.q_kind == REFLECTED) | (self.q_kind == FEATHER))
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
        kinds = self.q_kind[idx, order]
        kind = np.eye(3)[np.where(kinds == SNOW, ARROW, kinds)]
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

    def stunned(self):
        """Сеть не управляет: открыто слабое место или Серафим на земле после нисхождения (в моде SeraphMind.tick(stunned))."""
        return (self.exposed > 0) | (self.descend > 0)

    def recovering(self):
        """Как SeraphMind.recovering(): взмах кончился, рывка ещё нет (в моде в это время урон ×2)."""
        return (self.no_dash > 0) & (self.defl_t == 0)

    # ------------------------------------------------------------------ step
    def step(self, s_act, a_act):
        n, r = self.n, self.rng
        s_act = np.clip(np.asarray(s_act, float), -1, 1)
        a_act = np.clip(np.asarray(a_act, float), -1, 1)
        rs = np.zeros(n); ra = np.zeros(n)
        self.released[:] = False; self.dashed[:] = False; self.threw[:] = False
        if s_act.shape[1] < S_ACT:      # сеть v2/v3: без новых действий
            s_act = np.concatenate([s_act, np.tile([-1.0, 0.0, 0.0, -1.0], (n, 1))], 1)
        self.refused = [""] * n

        # ================= Серафим =================
        c, s = self._frame(self.bpos, self.ppos)
        stun = self.stunned()
        if self.v3:
            # SeraphMind.tick(stunned): ничего не делает и не рулит, начатые рывок и взмах обрываются
            s_act = s_act.copy(); s_act[stun, :8] = 0; s_act[stun, 3:6] = -1
            s_act[stun, 8] = -1; s_act[stun, 9] = 0; s_act[stun, 11] = -1
            self.dash_left[stun] = 0; self.defl_t[stun] = 0; self.mini_left[stun] = 0
        steer_w = unrot(s_act[:, :3] * np.array([1, 0, 1]), c, s)[:, [0, 2]]
        self.steer = SMOOTH * self.steer + (1 - SMOOTH) * steer_w
        self.lift = SMOOTH * self.lift + (1 - SMOOTH) * s_act[:, 1]
        lift = self.lift
        casting = self.cast_t > 0
        # взмах
        go_def = (s_act[:, 4] > 0) & (self.defl_cd == 0) & ~casting & (self.dash_left == 0) & (self.mini_left == 0)
        no_def = go_def & (self.no_defl > 0) if self.recover else np.zeros(n, bool)
        go_def &= ~no_def
        self.defl_t[go_def] = DEFLECT_WIND + DEFLECT_ACTIVE; self.defl_cd[go_def] = DEFLECT_CD
        if self.recover:
            self.no_dash[go_def] = DEFLECT_WIND + DEFLECT_ACTIVE + RECOVER
        self.deflected = go_def.copy()
        # рывок
        go_dash = (s_act[:, 3] > 0) & (self.dash_cd == 0) & ~casting & (self.defl_t == 0) & (self.mini_left == 0)
        no_dash = go_dash & (self.no_dash > 0) if self.recover else np.zeros(n, bool)
        go_dash &= ~no_dash
        if self.recover:
            self.no_defl[go_dash] = DASH_TICKS + RECOVER
        for i in np.nonzero(no_def | no_dash)[0]:
            self.refused[i] = " ".join(w for w, on in (("deflect:recover", no_def[i]), ("dash:recover", no_dash[i])) if on)
        dirn = steer_w / (np.linalg.norm(steer_w, axis=1, keepdims=True) + 1e-9)
        self.dash_dir[go_dash] = dirn[go_dash]; self.dash_left[go_dash] = DASH_TICKS; self.dash_cd[go_dash] = self.dash_cdmax
        self.dashed |= go_dash; self.stats["dashes"] += go_dash
        if self.v4:
            rs += self._maneuvers(s_act, steer_w, dirn, casting, stun, go_dash)
        # атака перьями: начать каст
        go_atk = (s_act[:, 5] > 0) & (self.atk_cd == 0) & ~(self.cast_t > 0) & (self.dash_left == 0) & (self.mini_left == 0)
        self._cast(go_atk, C_FEATHERS); self.cast_aim[go_atk] = s_act[go_atk][:, 6:8]
        self.attacked = go_atk.copy()
        rs -= 0.002 * go_dash + 0.002 * go_def
        if self.v3:
            self._fight_timers()
        casting = self.cast_t > 0
        dashing = self.dash_left > 0
        descending = self.descend > 0
        # pursue(): во время каста и открытого окна не вызывается (SacredBoss.mobTick возвращается раньше)
        self.orbit += np.where(self.v3 & (self.phase >= 3), 0.03, 0.02) * self.orbit_dir
        goal = self.ppos[:, [0, 2]] + np.stack([np.cos(self.orbit), np.sin(self.orbit)], 1) * self.radius[:, None]
        goal = np.where(descending[:, None], self.land, goal)
        flat = goal - self.bpos[:, [0, 2]]
        fl = np.linalg.norm(flat, axis=1, keepdims=True)
        push = np.where(fl < 0.5, 0, flat / (fl + 1e-9) * 0.05)
        mag = np.linalg.norm(self.steer, axis=1).clip(0, 1)
        nxt = self.bvel[:, [0, 2]] * 0.9 + push + np.where(descending[:, None], 0, self.steer * STEER_ACC)
        dspeed = DASH_PROFILE[np.clip(DASH_TICKS - self.dash_left, 0, DASH_TICKS - 1)]
        nxt = np.where(dashing[:, None], self.dash_dir * dspeed[:, None], nxt)
        mini = self.mini_left > 0
        mspeed = MINI_PROFILE[np.clip(MINI_TICKS - self.mini_left, 0, MINI_TICKS - 1)]
        nxt = np.where(mini[:, None], self.mini_dir * mspeed[:, None], nxt)
        cap = np.where(dashing, dspeed, np.where(mini, mspeed, np.where(descending, DESCENT_SPEED, BASE_CAP + STEER_CAP * mag)))
        hl = np.linalg.norm(nxt, axis=1); nxt *= np.where(hl > cap, cap / (hl + 1e-9), 1)[:, None]
        frozen = casting | (self.exposed > 0)
        nxt = np.where(frozen[:, None], self.bvel[:, [0, 2]], nxt)
        lift_eff = np.where(casting | stun, 0, lift)
        # hold()
        wanted = np.where(descending, 0.05, np.maximum(HOVER + np.sin(self.age * 0.05) * 0.6 + lift_eff * LIFT, 0.05))
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
        self.no_dash = np.maximum(self.no_dash - 1, 0); self.no_defl = np.maximum(self.no_defl - 1, 0)
        self.mini_left = np.maximum(self.mini_left - 1, 0); self.mini_cd = np.maximum(self.mini_cd - 1, 0)
        self.flip_cd = np.maximum(self.flip_cd - 1, 0); self.tele_cd = np.maximum(self.tele_cd - 1, 0)
        fire = self.cast_t == 1
        kinds = self.cast_kind.copy()
        self.cast_t = np.maximum(self.cast_t - 1, 0)
        self.cast_kind[self.cast_t == 0] = C_NONE
        for i in np.nonzero(fire & (kinds == C_FEATHERS))[0]:
            self._feathers(i)
        fired = fire & (kinds == C_FEATHERS)
        self.atk_cd[fired] = ATTACK_CD
        self.stats["volleys"] += fired
        if self.v3:
            self._strike(fire, kinds)
            rs += self._hazards()

        # ================= лучник =================
        self._archer(a_act)

        # ================= снаряды =================
        hit_b, hit_p_feather, hit_p_reflect, reflected, hit_snow = self._projectiles()
        if self.trace is not None:
            i = self.trace
            if self.released[i]: self.events.append(("shot", self.last_pull[i], None, ""))
            if self.threw[i]: self.events.append(("throw", None, None, "snowball"))
            self.events += [("arrow_hit", None, None, "window" if self.exposed[i] > 0 else "")] * int(hit_b[i])
            self.events += [("snowball_hit", None, None, "")] * int(hit_snow[i]) + [("deflected", 0, None, "")] * int(reflected[i])
            self.events += [("player_hurt", None, None, "feathers")] * int(hit_p_feather[i])
            self.events += [("player_hurt", None, None, "reflected")] * int(hit_p_reflect[i])
        self.stats["arrow_hits"] += hit_b; self.stats["feather_hits"] += hit_p_feather
        self.stats["reflects"] += reflected; self.stats["reflect_hits"] += hit_p_reflect; self.stats["snow_hits"] += hit_snow
        rs += -1.0 * hit_b + 0.5 * hit_p_feather + 1.0 * hit_p_reflect + 0.1 * reflected - 0.3 * hit_snow
        ra += 1.0 * hit_b - 0.5 * hit_p_feather - 1.0 * hit_p_reflect
        if self.v3:
            self._open_windows(hit_snow > 0)
        d = np.linalg.norm((self.ppos - self.bpos)[:, [0, 2]], axis=1)
        free = ~self.stunned() & ~(self.v3 & (self.cast_t > 0))   # в v3 касты и окна не штрафуются за место
        rs -= free * (0.01 * (np.maximum(0, d - 16) + np.maximum(0, 6 - d)) + 0.003 * np.abs(self.bpos[:, 1] - HOVER)) + 0.002 * mag ** 2

        self.t += 1
        self._push_obs()
        done = self.t >= EP_LEN
        info = {"done_stats": {k: v[done].copy() for k, v in self.stats.items()} if done.any() else None,
                "released": self.released.copy(), "dashed": self.dashed.copy()}
        if done.any():
            self.reset(done)
        return rs.astype(np.float32), ra.astype(np.float32), done, info

    # ------------------------------------------------------- v3: fight loop
    def _cast(self, m, kind):
        self.cast_t[m] = CAST_LEN[kind]; self.cast_len[m] = CAST_LEN[kind]; self.cast_kind[m] = kind

    def _fight_timers(self):
        """SacredBoss.mobTick с сетью: таймеры идут, только когда нет каста и окна; ульта раньше обычной атаки.
        attack() с сетью: на земле — кольцо (45), иначе пополам нисхождение (130) или кольцо (140); множитель фазы."""
        r = self.rng
        busy = (self.cast_t > 0) | (self.exposed > 0)
        tick = ~busy
        self.ult_timer[tick] -= 1
        ult = tick & (self.ult_timer <= 0)
        self._cast(ult, C_ULT)
        self.ult_timer[ult] = np.select([self.phase == 1, self.phase == 2], [600, 480], 360)[ult]
        self.stats["ults"] += ult
        tick &= ~ult
        self.atk_timer[tick] -= 1
        go = tick & (self.atk_timer <= 0)
        mult = np.select([self.phase <= 1, self.phase >= 3], [1.0, 0.7], 0.85)
        ground = go & (self.descend > 0)
        coin = r.random(self.n) < 0.5
        down = go & ~ground & coin & (self.age > 200)
        ring = go & ~down
        self._cast(ring, C_RING); self._cast(down, C_DESCENT)
        base = np.where(ground, 45, np.where(down, DESCENT_TICKS + 60, 140))
        self.atk_timer[go] = np.maximum(20, np.round(base * mult)).astype(int)[go]
        self.dash_left[ult | ring | down] = 0

    def _strike(self, fire, kinds):
        """Конец каста: кольцо пошло по земле, Серафим садится, начинаются круги ульты."""
        n, r = self.n, self.rng
        ring = fire & (kinds == C_RING)
        self.ring_r[ring] = 0.5; self.ring_c[ring] = self.bpos[ring][:, [0, 2]]
        self.ring_max[ring] = np.where(self.phase >= 3, 14, 11)[ring]; self.ring_hit[ring] = False
        down = fire & (kinds == C_DESCENT)
        if down.any():
            toward = self.bpos[:, [0, 2]] - self.ppos[:, [0, 2]]
            toward /= np.linalg.norm(toward, axis=1, keepdims=True) + 1e-9
            self.land[down] = (self.ppos[:, [0, 2]] + toward * 4)[down]
            self.descend[down] = DESCENT_TICKS
            self.stats["descents"] += down
        tele = fire & (kinds == C_TELE)
        if tele.any():
            # за спину: зеркально через игрока на том же расстоянии по земле (игрок смотрит на Серафима)
            new = 2 * self.ppos[:, [0, 2]] - self.bpos[:, [0, 2]]
            self.bpos[tele, 0] = new[tele, 0]; self.bpos[tele, 2] = new[tele, 1]; self.bvel[tele] = 0
            rel = self.bpos[:, [0, 2]] - self.ppos[:, [0, 2]]
            self.orbit[tele] = np.arctan2(rel[tele, 1], rel[tele, 0])
        ult = fire & (kinds == C_ULT)
        self.ult_left[ult] = np.select([self.phase >= 3, self.phase == 2], [20, 16], 14)[ult]
        self.ult_next[ult] = 0; self.ult_step[ult] = 0

    def _hazards(self):
        """Кольцо, круги со столпами, нисхождение и слабое место по тикам; награда Серафиму за попадания."""
        n, r = self.n, self.rng
        rs = np.zeros(n)
        self.descend = np.maximum(self.descend - 1, 0)
        # кольцо: |радиус − расстояние| < 0.9 и игрок не в прыжке выше RING_H
        on = self.ring_r >= 0
        self.ring_r = np.where(on, self.ring_r + RING_SPEED, self.ring_r)
        flat = np.linalg.norm(self.ppos[:, [0, 2]] - self.ring_c, axis=1)
        hit = on & ~self.ring_hit & (np.abs(flat - self.ring_r) < 0.9) & (self.ppos[:, 1] < RING_H)
        self.ring_hit |= hit
        self._knock(hit, np.concatenate([self.ring_c[:, :1], np.zeros((n, 1)), self.ring_c[:, 1:]], 1), RING_PUSH, 0.3)
        rs += 0.5 * hit; self.stats["ring_hits"] += hit
        self.ring_r[on & (self.ring_r >= self.ring_max)] = -1
        # круги ульты: каждые max(3, 120/count) тиков новый; каждый третий под игроком, остальные в 1.5–7.5
        go = (self.ult_left > 0) & (self.ult_next <= 0)
        warn = np.where(self.phase >= 3, 20, 28)
        for i in np.nonzero(go)[0]:
            slot = int(np.argmin(self.zone_t[i]))
            at = self.ppos[i, [0, 2]].copy()
            if self.ult_step[i] % 3 != 0:
                a = r.uniform(0, 2 * np.pi); rr = 1.5 + r.random() * 6
                at += np.array([np.cos(a), np.sin(a)]) * rr
            self.zone_c[i, slot] = at; self.zone_t[i, slot] = warn[i]
            self.ult_left[i] -= 1; self.ult_step[i] += 1
            count = {1: 14, 2: 16}.get(int(self.phase[i]), 20)
            self.ult_next[i] = max(3, 120 // count)
        self.ult_next = np.maximum(self.ult_next - 1, 0)
        live = self.zone_t > 0
        self.zone_t = np.where(live, self.zone_t - 1, 0)
        strike = live & (self.zone_t == 0)
        dz = np.linalg.norm(self.zone_c - self.ppos[:, None, [0, 2]], axis=-1)
        hitz = (strike & (dz <= ZONE_R + 0.4)).any(1)
        self._knock(hitz, self.bpos, KNOCK, 0.4)
        rs += 0.5 * hitz; self.stats["pillar_hits"] += hitz
        # слабое место
        closing = self.exposed == 1
        self.exposed = np.maximum(self.exposed - 1, 0)
        self.weak_cd[closing] = WEAK_CD
        self.weak_cd = np.where(self.exposed > 0, self.weak_cd, np.maximum(self.weak_cd - 1, 0))
        return rs

    def _open_windows(self, hit):
        """Снежок попал: слабое место на EXPOSED_TICKS, если готово (SacredBoss.expose: каст сбрасывается)."""
        ready = (self.exposed == 0) & (self.weak_cd == 0)
        go = hit & ready
        self.exposed[go] = EXPOSED_TICKS
        self.cast_t[go] = 0; self.cast_kind[go] = C_NONE
        self.dash_left[go] = 0; self.defl_t[go] = 0
        self.stats["windows"] += go
        if self.trace is not None and go[self.trace]:
            self.events.append(("window_open", None, None, ""))

    def _maneuvers(self, s_act, steer_w, dirn, casting, stun, go_dash):
        """v4: мини-уклонение, смена стороны, радиус облёта, телепорт; награда за разнообразие манёвров."""
        n = self.n
        rs = np.zeros(n)
        free = ~casting & ~stun & (self.dash_left == 0) & (self.defl_t == 0)
        go_mini = (s_act[:, 8] > 0) & (self.mini_cd == 0) & free & (self.mini_left == 0) & ~go_dash
        no_mini = go_mini & (self.no_dash > 0)          # после взмаха — отдых и для мини
        go_mini &= ~no_mini
        for i in np.nonzero(no_mini)[0]:
            self.refused[i] = (self.refused[i] + " mini:recover").strip()
        # без руля — шаг вбок от линии на игрока, в сторону знака руля по z
        c, s = self._frame(self.bpos, self.ppos)
        side = unrot(np.stack([np.zeros(n), np.zeros(n), np.where(s_act[:, 2] >= 0, 1.0, -1.0)], 1), c, s)[:, [0, 2]]
        weak = np.linalg.norm(steer_w, axis=1) < 0.2
        self.mini_dir[go_mini] = np.where(weak[:, None], side, dirn)[go_mini]
        self.mini_left[go_mini] = MINI_TICKS; self.mini_cd[go_mini] = MINI_CD
        self.minis = go_mini; self.stats["minis"] += go_mini
        want = np.where(s_act[:, 9] > 0.5, 1.0, np.where(s_act[:, 9] < -0.5, -1.0, self.orbit_dir))
        flip = (want != self.orbit_dir) & (self.flip_cd == 0) & ~casting & ~stun
        self.orbit_dir[flip] *= -1; self.flip_cd[flip] = FLIP_CD
        self.since_flip = np.where(flip, 0, self.since_flip + 1)
        self.flips = flip; self.stats["flips"] += flip
        target = 12 + 6 * s_act[:, 10]
        self.radius = np.where(casting | stun, self.radius, 0.95 * self.radius + 0.05 * np.clip(target, RADIUS_MIN, RADIUS_MAX))
        tele = (s_act[:, 11] > 0) & (self.tele_cd == 0) & free & (self.mini_left == 0) & ~go_mini & ~go_dash
        self._cast(tele, C_TELE); self.tele_cd[tele] = TELE_CD
        self.teles = tele; self.stats["teleports"] += tele
        # разнообразие: бонус за манёвр тем больше, чем реже он был в последние ~5 с; штраф за облёт в одну сторону дольше 15 с
        used = np.stack([go_dash, go_mini, flip, tele], 1).astype(float)
        share = self.man_ema / (self.man_ema.sum(1, keepdims=True) + 1e-6)
        rs += 0.03 * (used * (1 - share)).sum(1) * (self.man_ema.sum(1) > 0) + 0.01 * used.sum(1) * (self.man_ema.sum(1) == 0)
        self.man_ema = self.man_ema * 0.99 + used
        rs -= 0.0005 * (self.since_flip > 300)
        return rs

    def _knock(self, m, frm, strength, up):
        """Ванильный knockback: игрока толкает от точки frm (мир, [n,3]) по земле и чуть вверх."""
        if not m.any():
            return
        away = (self.ppos - frm)[:, [0, 2]]
        away /= np.linalg.norm(away, axis=1, keepdims=True) + 1e-9
        self.kb[m, 0] = away[m, 0] * strength; self.kb[m, 2] = away[m, 1] * strength
        on_ground = self.ppos[:, 1] <= 1e-6
        self.pvy[m & on_ground] = up

    # ---------------------------------------------------------------- archer
    def _human(self, a_act, sc):
        """Лучник-человек: режим ходьбы на 20–80 тиков, пауза без лука, натяжение до задуманной силы, очереди снежков."""
        n, r = self.n, self.rng
        h = self.human & sc
        if not h.any():
            return a_act
        a_act = a_act.copy()
        drawing = self.draw > 0
        self.h_mode_t[h] -= 1
        new = h & (self.h_mode_t <= 0)
        self.h_mode[new] = r.choice(4, new.sum(), p=[0.25, 0.3, 0.2, 0.25])
        self.h_mode_t[new] = r.integers(20, 80, new.sum()); self.h_side[new] = r.choice([-1.0, 1.0], new.sum())
        zig = (self.age // 10) % 2 == 0
        fwd = np.select([self.h_mode == 0, self.h_mode == 1, self.h_mode == 2, self.h_mode == 3],
                        [0.0, 0.0, np.where(zig, 0.6, -0.3), self.h_side * 0.8])
        side = np.select([self.h_mode == 0, self.h_mode == 1, self.h_mode == 2, self.h_mode == 3],
                         [0.0, self.h_side, np.where(zig, 1.0, -1.0), 0.2 * self.h_side])
        a_act[h, 0] = fwd[h]; a_act[h, 1] = side[h]
        # тетива: пауза → натяжение до h_power (иногда держит дольше) → выпуск
        idle = h & ~drawing
        self.h_pause[idle] -= 1
        start = idle & (self.h_pause <= 0) & (self.pcool == 0) & (self.h_burst == 0)
        k = int(start.sum())
        if k:
            p = np.where(r.random(k) < 0.4, 1.0, r.uniform(0.3, 1.0, k))
            self.h_power[start] = p
            self.s_hold[start] = draw_ticks(p) + np.where((p >= 1) & (r.random(k) < 0.3), r.integers(5, 25, k), 0)
            self.h_aim[start] = r.normal(0, 0.4, (k, 2))
        want = h & ((start | drawing) & (self.draw < self.s_hold))
        a_act[h, 2] = np.where(want[h], 1, -1)
        done = h & drawing & (self.draw >= self.s_hold)
        self.h_pause[done] = r.integers(5, 40, int(done.sum()))
        # очередь снежков в паузе
        go = h & ~drawing & self.h_snow & (self.h_burst == 0) & (self.h_pause > 8) & (r.random(n) < 0.015)
        self.h_burst[go] = r.integers(3, 9, int(go.sum())); self.h_burst_cd[go] = 0
        a_act[h, 3:5] = 0
        return a_act

    def _archer(self, a_act):
        n, r = self.n, self.rng
        c2, s2 = self._frame(self.ppos, self.bpos)
        drawing = self.draw > 0
        sc = self.scripted
        v1 = sc & ~self.human
        # сценарный лучник из v1 (стоит, тянет полностью 22–30 тиков, иногда держит дольше, целит с упреждением)
        if v1.any():
            a_act = a_act.copy()
            self.s_dir[v1] += r.normal(0, 0.15, v1.sum())
            a_act[v1, 0] = 0; a_act[v1, 1] = np.sin(self.s_dir[v1]) * 0.5
            start = v1 & ~drawing & (self.pcool == 0)
            self.s_hold[start] = r.integers(22, 30, start.sum()) + np.where(r.random(start.sum()) < 0.3, r.integers(10, 50, start.sum()), 0)
            a_act[v1, 2] = np.where(self.draw[v1] < self.s_hold[v1], 1, -1)
            a_act[v1, 3:5] = 0
        a_act = self._human(a_act, sc)
        # ходьба в рамке «на Серафима»; человек бегает (не с луком), прыгает
        want = unrot(np.stack([a_act[:, 0], np.zeros(n), a_act[:, 1]], 1), c2, s2)
        run = self.v3 & self.human & (self.h_mode == 2) & ~drawing
        speed = np.where(drawing, WALK * DRAW_SLOW, np.where(run, SPRINT, WALK))
        wl = np.linalg.norm(want, axis=1, keepdims=True)
        want = np.where(wl > 1, want / (wl + 1e-9), want) * speed[:, None]
        self.pvel = self.pvel * 0.4 + want * 0.6
        self.pvel[:, 1] = 0
        if self.v3:
            on_ground = self.ppos[:, 1] <= 1e-6
            jump = self.human & on_ground & (r.random(n) < np.where(drawing, 0.004, 0.02))
            self.pvy[jump] = JUMP_V
            self.pvel = self.pvel + self.kb
            self.kb *= np.where(on_ground, 0.546, 0.91)[:, None]
            self.ppos[:, 1] = np.maximum(self.ppos[:, 1] + self.pvy, 0)
            self.pvy = np.where(self.ppos[:, 1] <= 1e-6, 0, (self.pvy - GRAVITY_P) * 0.98)
        self.ppos += self.pvel * np.array([1, 0, 1])
        rr = np.linalg.norm(self.ppos[:, [0, 2]], axis=1)
        over = rr > ARENA
        self.ppos[over, 0] *= ARENA / rr[over]; self.ppos[over, 2] *= ARENA / rr[over]
        # прицел: баллистика на центр Серафима + поправка сети (упреждение) в блоках
        eye = self.ppos + np.array([0, EYE, 0])
        center = self.bpos + np.array([0, H / 2, 0])
        lead = np.zeros((n, 3))
        dist = np.linalg.norm(center - eye, axis=1)
        if sc.any():
            lead[sc] = self.bvel[sc] * (dist[sc] / 3.0 * self.s_lead[sc])[:, None]
        off = unrot(np.stack([np.zeros(n), a_act[:, 4] * 3, a_act[:, 3] * 4], 1), c2, s2)
        hum = self.human & sc
        off[hum] += unrot(np.stack([np.zeros(n), self.h_aim[:, 1], self.h_aim[:, 0]], 1), c2, s2)[hum]
        tgt = center + off + lead
        to = tgt - eye
        flat = np.hypot(to[:, 0], to[:, 2])
        pw = np.where(drawing, bow_power(self.draw), 1.0)
        # человек держит прицел под задуманную силу выпуска (а не под текущую), снежок — под свою баллистику
        pw = np.where(hum, self.h_power, pw)
        throwing = hum & (self.h_burst > 0) & ~drawing
        pitch = ballistic(flat, to[:, 1], 3.0 * np.maximum(pw, 0.3))
        if throwing.any():
            pitch[throwing] = ballistic(flat[throwing], to[throwing, 1], SNOW_SPEED, SNOW_G)
        yaw = np.arctan2(to[:, 2], to[:, 0])
        self.look = np.stack([np.cos(pitch) * np.cos(yaw), np.sin(pitch), np.cos(pitch) * np.sin(yaw)], 1)
        # тетива
        self.pcool = np.maximum(self.pcool - 1, 0)
        pulled = self.draw.copy()
        want_draw = (a_act[:, 2] > 0) & ~throwing
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
            self.last_pull[i] = bow_power(pulled[i])
            self._spawn(i, ARROW, eye[i] - np.array([0, 0.1, 0]), d * sp + np.array([self.pvel[i, 0], 0, self.pvel[i, 2]]))
        self.pcool[fire] = 2
        self.released |= fire
        self.stats["shots"] += fire
        # снежки: по одному каждые 4 тика
        if self.v3:
            self.h_burst_cd = np.maximum(self.h_burst_cd - 1, 0)
            toss = throwing & (self.h_burst_cd == 0)
            for i in np.nonzero(toss)[0]:
                d = self.look[i] + np.deg2rad(1.5) * r.normal(0, 1, 3); d /= np.linalg.norm(d)
                self._spawn(i, SNOW, eye[i] - np.array([0, 0.1, 0]), d * SNOW_SPEED + np.array([self.pvel[i, 0], 0, self.pvel[i, 2]]))
            self.h_burst[toss] -= 1; self.h_burst_cd[toss] = 4
            self.threw |= toss
            self.stats["snows"] += toss

    # --------------------------------------------------------------- seraph
    def _feathers(self, i):
        r = self.rng
        frm = self.bpos[i] + np.array([0, H * FACE, 0])
        c, s = self._frame(self.bpos[i:i + 1], self.ppos[i:i + 1])
        aim_pt = self.ppos[i] + np.array([0, EYE, 0]) + unrot(np.array([[0, self.cast_aim[i, 1] * 2, self.cast_aim[i, 0] * 4]]), c, s)[0]
        aim = aim_pt - frm; aim /= np.linalg.norm(aim)
        count = 8 if (self.v3 and self.phase[i] >= 2) else FEATHERS
        for k in range(count):
            sp = (k - (count - 1) / 2) * FEATHER_SPREAD
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
        snow_box = self.q_on & (self.q_kind == SNOW) & hb       # взмах снежки не отбивает (только PersistentProjectile)
        hit_snow = snow_box.sum(1).astype(float)
        # отражённые стрелы летят обратно в лучника
        eye = self.ppos + np.array([0, EYE, 0])
        for i, j in zip(*np.nonzero(refl)):
            d = eye[i] - p0[i, j]; d /= np.linalg.norm(d) + 1e-9
            d = d + np.deg2rad(2.0) * r.normal(0, 1, 3); d /= np.linalg.norm(d)
            self.q_vel[i, j] = d * REFLECT_SPEED; self.q_kind[i, j] = REFLECTED; self.q_age[i, j] = 0
        gone = (hit_box & ~active) | snow_box
        # попадания в лучника
        m = np.array([PW / 2 + FEATHER_R, FEATHER_R, PW / 2 + FEATHER_R])
        plo = self.ppos - m; phi = self.ppos + np.array([PW / 2 + FEATHER_R, PH + FEATHER_R, PW / 2 + FEATHER_R])
        hp, _ = self._seg_box(self.q_pos, self.q_vel, plo, phi)
        hit_feather = self.q_on & (self.q_kind == FEATHER) & hp
        mr = np.array([PW / 2 + HIT_MARGIN, HIT_MARGIN, PW / 2 + HIT_MARGIN])
        hr, _ = self._seg_box(self.q_pos, self.q_vel, self.ppos - mr, self.ppos + np.array([PW / 2 + HIT_MARGIN, PH + HIT_MARGIN, PW / 2 + HIT_MARGIN]))
        hit_refl = self.q_on & (self.q_kind == REFLECTED) & hr & ~refl
        gone |= hit_feather | hit_refl
        if self.v3:
            self._knock(hit_feather.any(1) | hit_refl.any(1), self.bpos, KNOCK, 0.4)
        # движение
        p1 = self.q_pos + self.q_vel
        gone |= self.q_on & (p1[..., 1] <= 0) & (self.q_kind != FEATHER)
        self.q_pos = p1
        g = np.select([self.q_kind == FEATHER, self.q_kind == SNOW], [0.0, SNOW_G], 0.05)
        grav = g[..., None] * np.array([0, 1, 0])
        drag = np.where(self.q_kind == FEATHER, 1.0, 0.99)[..., None]
        self.q_vel = self.q_vel * drag - grav
        self.q_age += 1
        far = np.linalg.norm(self.q_pos - self.bpos[:, None], axis=-1) > 60
        old = np.where(self.q_kind == FEATHER, self.q_age > FEATHER_LIFE, self.q_age > 100)
        self.q_on &= ~(gone | far | old)
        return hit_b, hit_feather.sum(1).astype(float), hit_refl.sum(1).astype(float), refl.sum(1).astype(float), hit_snow
