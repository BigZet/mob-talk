"""Тренажёр уклонения Серафима: упрощённая арена с физикой Minecraft 1.21.1, векторно на numpy.

Один шаг = один тик сервера (20 в секунду). Всё в блоках и блоках за тик, как в игре.
Полёт Серафима повторяет SeraphEntity.hold()/pursue() и LivingEntity.travel(); поверх него
политика добавляет «взмах крыльев»: ускорение в сторону и смещение высоты, ограниченное запасом сил.
Снаряды повторяют PersistentProjectileEntity/ThrownEntity: шаг, сопротивление 0.99, гравитация.
"""
import numpy as np

# ---- Серафим (DivineBosses.SERAPH: 3.4 x 7.2, SeraphEntity.HOVER = 3.5). ----
W, H = 3.4, 7.2
HOVER = 3.5
ORBIT_R = 12.0           # в игре сейчас 7; с сетью Серафим кружит дальше, иначе от стрелы не уйти
BASE_CAP = 0.32          # предел горизонтальной скорости в pursue()
STEER_ACC = 0.04         # плавное подруливание за тик при действии 1.0
STEER_CAP = 0.08         # насколько подруливание поднимает предел скорости
DODGE_LIFT = 2.0         # на сколько блоков действие сдвигает желаемую высоту
DASH_SPEED = 1.0         # рывок крыльями: блоков за тик
DASH_TICKS = 5           # сколько тиков длится рывок (~4 блока)
DASH_CD = 50             # перезарядка рывка, 2.5 с
HIT_MARGIN = 0.3         # ProjectileUtil раздувает рамку цели на 0.3

# ---- Оружие игрока. ----
NONE, BOW, CROSSBOW, TRIDENT, SNOWBALL, POTION = range(6)
N_WEAPONS = 6
# скорость, гравитация, разброс (divergence), дальность всплеска (0 = попадание рамкой)
SPEC = {
    BOW: (3.0, 0.05, 1.0, 0.0),
    CROSSBOW: (3.15, 0.05, 1.0, 0.0),
    TRIDENT: (2.5, 0.05, 1.0, 0.0),
    SNOWBALL: (1.5, 0.03, 1.0, 0.0),
    POTION: (0.5, 0.05, 1.0, 4.0),
}
EYE = 1.62
MAX_PROJ = 8      # снарядов в полёте на одну арену
K_OBS = 4         # сколько ближайших снарядов видит политика
EP_LEN = 600      # 30 секунд


def bow_power(t):
    f = t / 20.0
    return np.minimum((f * f + 2 * f) / 3.0, 1.0)


class SeraphArena:
    """N независимых арен. step(action[N,3]) -> obs, reward, done, info."""

    obs_dim = 3 + 1 + 1 + 3 + 1 + N_WEAPONS + 1 + 1 + 3 + 1 + K_OBS * 9

    def __init__(self, n, seed=0, skill=None, weapons=None, dodge=True, mastery=1.0):
        self.n = n
        self.rng = np.random.default_rng(seed)
        self.skill_fixed = skill        # градусы ошибки прицела, None = случайно
        self.weapons_fixed = weapons    # список оружия, None = случайно
        self.dodge = dodge
        self.mastery = mastery     # доля рывков, которые Серафим действительно делает (настройка «умение»)
        self.reset_all()

    # ---------------------------------------------------------------- reset
    def reset_all(self):
        self._alloc()
        self._reset(np.ones(self.n, bool))
        return self.obs()

    def _alloc(self):
        n = self.n
        self.bpos = np.zeros((n, 3)); self.bvel = np.zeros((n, 3)); self.orbit = np.zeros(n)
        self.energy = np.ones(n); self.dash_cd = np.zeros(n, int); self.dash_left = np.zeros(n, int)
        self.dash_dir = np.zeros((n, 2)); self.t = np.zeros(n, int); self.age = np.zeros(n, int)
        self.ppos = np.zeros((n, 3)); self.pvel = np.zeros((n, 3)); self.pdir = np.zeros(n)
        self.weapon = np.zeros(n, int); self.use = np.zeros(n, int); self.hold = np.zeros(n, int)
        self.cool = np.zeros(n, int); self.aim_err = np.zeros(n); self.lead = np.zeros(n, bool)
        self.look = np.zeros((n, 3)); self.loaded = np.zeros(n, bool)
        self.mix = np.zeros((n, N_WEAPONS))
        self.q_on = np.zeros((n, MAX_PROJ), bool); self.q_pos = np.zeros((n, MAX_PROJ, 3))
        self.q_vel = np.zeros((n, MAX_PROJ, 3)); self.q_kind = np.zeros((n, MAX_PROJ), int)
        self.q_age = np.zeros((n, MAX_PROJ), int)
        self.stat_shots = np.zeros(n); self.stat_hits = np.zeros(n)

    def _reset(self, m):
        k = int(m.sum())
        if k == 0:
            return
        r = self.rng
        self.ppos[m] = 0.0
        self.pvel[m] = 0.0
        self.pdir[m] = r.uniform(0, 2 * np.pi, k)
        ang = r.uniform(0, 2 * np.pi, k)
        dist = r.uniform(8, 16, k)
        self.bpos[m] = np.stack([np.cos(ang) * dist, np.full(k, HOVER), np.sin(ang) * dist], 1)
        self.bvel[m] = 0.0
        self.orbit[m] = ang
        self.energy[m] = 1.0; self.dash_cd[m] = 0; self.dash_left[m] = 0
        self.t[m] = 0
        self.age[m] = r.integers(0, 1000, k)
        self.use[m] = 0; self.hold[m] = 0; self.cool[m] = r.integers(0, 30, k)
        self.loaded[m] = False
        sk = self.skill_fixed if self.skill_fixed is not None else None
        self.aim_err[m] = sk if sk is not None else r.uniform(0.3, 3.0, k)
        self.lead[m] = r.random(k) < 0.7
        mix = np.zeros((k, N_WEAPONS))
        allowed = self.weapons_fixed or [BOW, CROSSBOW, TRIDENT, SNOWBALL, POTION]
        base = np.array([0, 0.45, 0.2, 0.1, 0.15, 0.1])
        for w in range(1, N_WEAPONS):
            if w in allowed:
                mix[:, w] = base[w]
        mix /= mix.sum(1, keepdims=True)
        self.mix[m] = mix
        idx = np.nonzero(m)[0]
        for j, i in enumerate(idx):
            self.weapon[i] = r.choice(N_WEAPONS, p=mix[j])
        self.q_on[m] = False
        self.stat_shots[m] = 0; self.stat_hits[m] = 0

    # ------------------------------------------------------------------ obs
    def _frame(self):
        """Поворот вокруг вертикали: ось x смотрит от Серафима на игрока."""
        d = self.ppos - self.bpos
        a = np.arctan2(d[:, 2], d[:, 0])
        return np.cos(a), np.sin(a)

    @staticmethod
    def _rot(v, c, s):
        # v[..., 3], c/s broadcastable to v[..., 0]
        x = v[..., 0] * c + v[..., 2] * s
        z = -v[..., 0] * s + v[..., 2] * c
        return np.stack([x, v[..., 1], z], -1)

    def obs(self):
        n = self.n
        c, s = self._frame()
        center = self.bpos + np.array([0, H / 2, 0])
        rel_t = self._rot(self.ppos + np.array([0, EYE, 0]) - center, c, s) / 20.0
        bv = self._rot(self.bvel, c, s) / 0.6
        height = ((self.bpos[:, 1] - HOVER) / 3.0)[:, None]
        onehot = np.eye(N_WEAPONS)[self.weapon]
        charge = np.zeros(n)
        bowish = (self.weapon == BOW) & (self.use > 0)
        charge[bowish] = bow_power(self.use[bowish])
        tri = (self.weapon == TRIDENT) & (self.use > 0)
        charge[tri] = np.minimum(self.use[tri] / 10.0, 1.0)
        cb = self.weapon == CROSSBOW
        charge[cb & ~self.loaded] = np.minimum(self.use[cb & ~self.loaded] / 25.0, 1.0)
        charge[cb & self.loaded] = 1.0
        look = self._rot(self.look, c, s)
        to_b = center - (self.ppos + np.array([0, EYE, 0]))
        to_b /= np.linalg.norm(to_b, axis=1, keepdims=True) + 1e-9
        aim_off = np.arccos(np.clip((self.look * to_b).sum(1), -1, 1))[:, None] / 0.5
        drawing = ((self.use > 0) & ~((self.weapon == CROSSBOW) & self.loaded)).astype(float)[:, None]   # в игре: getItemUseTime() > 0
        # снаряды: ближайшие по времени сближения
        rel = self.q_pos - center[:, None, :]
        v = self.q_vel
        vv = (v * v).sum(-1) + 1e-9
        tca = np.clip(-(rel * v).sum(-1) / vv, 0, 40)
        miss = np.linalg.norm(rel + v * tca[..., None], axis=-1)
        approaching = self.q_on & ((rel * v).sum(-1) < 0)
        key = np.where(approaching, tca + miss * 0.2, 1e9)
        order = np.argsort(key, 1)[:, :K_OBS]
        pr = []
        for k in range(K_OBS):
            i = order[:, k]
            on = approaching[np.arange(n), i]
            p = self._rot(rel[np.arange(n), i], c, s) / 20.0
            pv = self._rot(v[np.arange(n), i], c, s) / 3.0
            splash = (self.q_kind[np.arange(n), i] == POTION).astype(float)
            t_hit = np.minimum(tca[np.arange(n), i] / 20.0, 1.0)
            feat = np.concatenate([p, pv, splash[:, None], t_hit[:, None]], 1) * on[:, None]
            pr.append(np.concatenate([feat, on[:, None].astype(float)], 1))
        o = np.concatenate([bv, height, self.energy[:, None], rel_t, drawing, onehot, charge[:, None],
                            self.loaded[:, None].astype(float), look, aim_off] + pr, 1)
        return o.astype(np.float32)

    # ----------------------------------------------------------------- step
    def step(self, action):
        n = self.n
        r = self.rng
        a = np.clip(np.asarray(action, dtype=float).reshape(n, 4), -1, 1)
        if not self.dodge:
            a = a * 0
        c, s = self._frame()
        # действие в рамке «к игроку» -> мир
        a_w = self._rot(a[:, :3], c, -s)
        steer = a_w[:, [0, 2]]
        lift = a[:, 1]
        # рывок крыльями: по сигналу, если перезарядка прошла
        go = (a[:, 3] > 0) & (self.dash_cd == 0)
        if self.mastery < 1.0:
            skip = go & (self.rng.random(n) >= self.mastery)
            self.dash_cd[skip] = DASH_CD // 2   # промедлил: рывок пропал, крылья всё равно устали
            go &= ~skip
        dirn = steer / (np.linalg.norm(steer, axis=1, keepdims=True) + 1e-9)
        self.dash_dir[go] = dirn[go]
        self.dash_left[go] = DASH_TICKS
        self.dash_cd[go] = DASH_CD
        self.dash_cd = np.maximum(self.dash_cd - 1, 0)
        dashing = self.dash_left > 0
        self.dash_left = np.maximum(self.dash_left - 1, 0)
        self.energy = 1.0 - self.dash_cd / DASH_CD     # в наблюдении: готовность рывка

        # ---- полёт Серафима: pursue() ----
        self.orbit += 0.02
        goal = self.ppos[:, [0, 2]] + np.stack([np.cos(self.orbit), np.sin(self.orbit)], 1) * ORBIT_R
        flat = goal - self.bpos[:, [0, 2]]
        fl = np.linalg.norm(flat, axis=1, keepdims=True)
        push = np.where(fl < 0.5, 0, flat / (fl + 1e-9) * 0.05)
        nxt = self.bvel[:, [0, 2]] * 0.9 + push + steer * STEER_ACC
        nxt = np.where(dashing[:, None], self.dash_dir * DASH_SPEED, nxt)
        cap = np.where(dashing, DASH_SPEED, BASE_CAP + STEER_CAP * np.linalg.norm(a[:, [0, 2]], axis=1).clip(0, 1))
        hl = np.linalg.norm(nxt, axis=1)
        scale = np.where(hl > cap, cap / (hl + 1e-9), 1)
        nxt *= scale[:, None]
        mag_eff = np.linalg.norm(a[:, [0, 2]], axis=1).clip(0, 1)
        # ---- hold(): высота над землёй (земля y=0) ----
        wanted = HOVER + np.sin(self.age * 0.05) * 0.6 + lift * DODGE_LIFT
        wanted = np.maximum(wanted, 0.05)
        dy = np.clip((wanted - self.bpos[:, 1]) * 0.08, -0.35, 0.3)
        vy = self.bvel[:, 1] * 0.6 + dy + lift * 0.06
        self.bvel = np.stack([nxt[:, 0], vy, nxt[:, 1]], 1)
        # ---- travel(): сдвиг, затем сопротивление воздуха ----
        self.bpos = self.bpos + self.bvel
        self.bpos[:, 1] = np.maximum(self.bpos[:, 1], 0.0)
        self.bvel *= np.array([0.91, 0.98, 0.91])
        self.age += 1

        # ---- игрок ----
        self._player(r)

        # ---- снаряды ----
        hits = self._projectiles()
        self.stat_hits += hits

        self.t += 1
        # ---- награда ----
        d = np.linalg.norm((self.ppos - self.bpos)[:, [0, 2]], axis=1)
        band = np.maximum(0, d - 16) + np.maximum(0, 6 - d)
        rew = -1.0 * hits - 0.002 * mag_eff ** 2 - 0.01 * go - 0.01 * band - 0.003 * np.abs(self.bpos[:, 1] - HOVER)
        done = self.t >= EP_LEN
        info = {"hits": hits.copy(), "shots": self._last_shots.copy()}
        if done.any():
            info["final"] = (self.stat_shots[done].copy(), self.stat_hits[done].copy(), self.weapon[done].copy())
        obs_before_reset = None
        if done.any():
            obs_before_reset = self.obs()
            self._reset(done)
        return self.obs(), rew.astype(np.float32), done, info, obs_before_reset

    # ------------------------------------------------------------- player
    def _player(self, r):
        n = self.n
        # ходьба: медленный случайный стрейф, на земле
        self.pdir += r.normal(0, 0.15, n)
        sp = np.where(self.use > 0, 0.03, 0.12)   # с натянутым луком игрок почти стоит
        self.pvel = np.stack([np.cos(self.pdir) * sp, np.zeros(n), np.sin(self.pdir) * sp], 1)
        self.ppos += self.pvel
        # прицел: на центр Серафима с упреждением и поправкой на падение
        self._last_shots = np.zeros(n)
        eye = self.ppos + np.array([0, EYE, 0])
        center = self.bpos + np.array([0, H / 2, 0])
        w = self.weapon
        speed = np.array([0, 3.0, 3.15, 2.5, 1.5, 0.5])[w]
        grav = np.array([0, 0.05, 0.05, 0.05, 0.03, 0.05])[w]
        tgt = center.copy()
        for _ in range(2):
            t = np.linalg.norm(tgt - eye, axis=1) / np.maximum(speed, 0.1)
            tgt = center + self.bvel * (t[:, None] * self.lead[:, None])
        aim = tgt - eye
        aim /= np.linalg.norm(aim, axis=1, keepdims=True) + 1e-9
        self.look = aim

        self.cool = np.maximum(self.cool - 1, 0)
        ready = self.cool == 0
        fire = np.zeros(n, bool)
        power = np.ones(n)
        # лук: натянуть 12–30 тиков, иногда держать (приманка), отпустить
        bw = ready & (w == BOW)
        start = bw & (self.use == 0)
        self.hold[start] = r.integers(12, 30, start.sum()) + np.where(r.random(start.sum()) < 0.3,
                                                                       r.integers(10, 60, start.sum()), 0)
        self.use[bw] += 1
        rel = bw & (self.use >= self.hold)
        fire |= rel
        power[rel] = bow_power(self.use[rel])
        # трезубец: держать не меньше 10 тиков
        tr = ready & (w == TRIDENT)
        start = tr & (self.use == 0)
        self.hold[start] = r.integers(10, 30, start.sum())
        self.use[tr] += 1
        rel_t = tr & (self.use >= self.hold)
        fire |= rel_t
        # арбалет: зарядка 25 тиков, выстрел после 0–30 тиков
        cb = ready & (w == CROSSBOW)
        load = cb & ~self.loaded
        self.use[load] += 1
        done_load = load & (self.use >= 25)
        self.loaded[done_load] = True
        self.use[done_load] = 0
        self.hold[done_load] = r.integers(0, 30, done_load.sum())
        shoot_cb = cb & self.loaded & ~done_load
        self.use[shoot_cb] += 1
        rel_c = shoot_cb & (self.use >= self.hold)
        fire |= rel_c
        self.loaded[rel_c] = False
        # снежки и зелья: броски по таймеру
        th = ready & ((w == SNOWBALL) | (w == POTION))
        fire |= th

        idx = np.nonzero(fire)[0]
        for i in idx:
            self._spawn(i, w[i], power[i], tgt[i], eye[i])
        self.use[fire] = 0
        cd = np.array([0, 2, 2, 60, 6, 40])[w]
        cd_r = np.where(w == SNOWBALL, r.integers(3, 10, n), np.where(w == POTION, r.integers(30, 70, n), cd))
        self.cool[fire] = cd_r[fire]
        self._last_shots[fire] = 1
        self.stat_shots += fire
        # иногда меняет оружие
        sw = ready & (self.use == 0) & (r.random(n) < 0.004)
        for i in np.nonzero(sw)[0]:
            self.weapon[i] = r.choice(N_WEAPONS, p=self.mix[i])
            self.loaded[i] = False

    @staticmethod
    def _ballistic(eye, tgt, speed, g):
        """Угол вверх, при котором снаряд с сопротивлением 0.99 проходит через цель (как прицеливается опытный игрок)."""
        flat = np.hypot(*(tgt - eye)[[0, 2]])
        dy = (tgt - eye)[1]
        lo, hi = np.arctan2(dy, flat), np.deg2rad(80)
        for _ in range(18):
            mid = (lo + hi) / 2
            vx, vy, x, y = np.cos(mid) * speed, np.sin(mid) * speed, 0.0, 0.0
            for _ in range(120):
                if x >= flat:
                    break
                x += vx; y += vy; vx *= 0.99; vy = vy * 0.99 - g
            if x < flat or y < dy:
                lo = mid
            else:
                hi = mid
        return (lo + hi) / 2

    def _spawn(self, i, kind, power, tgt, eye):
        r = self.rng
        sp, g, div, _ = SPEC[int(kind)]
        sp_eff = sp * (power if kind == BOW else 1.0)
        pitch = self._ballistic(eye, tgt, sp_eff, g)
        yaw = np.arctan2((tgt - eye)[2], (tgt - eye)[0])
        d = np.array([np.cos(pitch) * np.cos(yaw), np.sin(pitch), np.cos(pitch) * np.sin(yaw)])
        # ошибка игрока: поворот на случайный угол
        d = d + np.deg2rad(self.aim_err[i]) * r.normal(0, 1, 3)
        d /= np.linalg.norm(d)
        d = d + r.triangular(-1, 0, 1, 3) * 0.0172275 * div
        v = d * sp_eff + np.array([self.pvel[i, 0], 0, self.pvel[i, 2]])
        free = np.nonzero(~self.q_on[i])[0]
        slot = free[0] if len(free) else int(np.argmax(self.q_age[i]))
        self.q_on[i, slot] = True
        self.q_pos[i, slot] = eye - np.array([0, 0.1, 0])
        self.q_vel[i, slot] = v
        self.q_kind[i, slot] = kind
        self.q_age[i, slot] = 0

    def _projectiles(self):
        n = self.n
        hits = np.zeros(n)
        lo = self.bpos - np.array([W / 2 + HIT_MARGIN, HIT_MARGIN, W / 2 + HIT_MARGIN])
        hi = self.bpos + np.array([W / 2 + HIT_MARGIN, H + HIT_MARGIN, W / 2 + HIT_MARGIN])
        p0 = self.q_pos
        v = self.q_vel
        # пересечение отрезка p0 -> p0+v с рамкой (метод плит)
        inv = 1.0 / np.where(np.abs(v) < 1e-9, 1e-9, v)
        t1 = (lo[:, None, :] - p0) * inv
        t2 = (hi[:, None, :] - p0) * inv
        tmin = np.minimum(t1, t2).max(-1)
        tmax = np.maximum(t1, t2).min(-1)
        box_hit = self.q_on & (tmax >= np.maximum(tmin, 0)) & (tmin <= 1)
        p1 = p0 + v
        ground = self.q_on & (p1[..., 1] <= 0)
        splash = self.q_kind == POTION
        # прямое попадание
        direct = box_hit & ~splash
        hits += direct.sum(1)
        # зелье: разбилось о рамку или о землю, задевает в радиусе 4 по горизонтали и 2 по вертикали
        burst = splash & (box_hit | ground)
        if burst.any():
            bp = np.where(box_hit[..., None], p0 + v * np.clip(tmin, 0, 1)[..., None], p1)
            cx = np.clip(bp[..., 0], lo[:, None, 0], hi[:, None, 0]) - bp[..., 0]
            cz = np.clip(bp[..., 2], lo[:, None, 2], hi[:, None, 2]) - bp[..., 2]
            cy = np.clip(bp[..., 1], lo[:, None, 1], hi[:, None, 1]) - bp[..., 1]
            near = (np.hypot(cx, cz) <= 4.0) & (np.abs(cy) <= 2.0)
            hits += (burst & near).sum(1)
        gone = box_hit | ground
        self.q_on &= ~gone
        # полёт: сдвиг, сопротивление, гравитация
        self.q_pos = p1
        g = np.where(self.q_kind == SNOWBALL, 0.03, 0.05)
        self.q_vel = self.q_vel * 0.99
        self.q_vel[..., 1] -= g
        self.q_age += 1
        far = np.linalg.norm(self.q_pos - self.bpos[:, None, :], axis=-1) > 60
        self.q_on &= ~(far | (self.q_age > 100))
        return hits
