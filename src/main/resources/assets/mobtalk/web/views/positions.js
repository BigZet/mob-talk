// Где игроки: карта сверху (X/Z) по мирам, таблица координат с обновлением раз в 2 с и телепорт игрока —
// к другому игроку, на точку возрождения, на спавн мира, на сохранённую точку или по координатам.
import { h, icon, replace } from '../lib/dom.js';
import { api } from '../lib/api.js';
import * as fmt from '../lib/fmt.js';
import { seg, busy, toast, empty, errorBox, skeleton, table, openDialog, copyText } from '../lib/ui.js';
import { pname } from '../lib/record.js';
import { WorldMap } from '../lib/worldmap.js';
import { on } from '../lib/sse.js';
import { confirm, ask } from '../lib/ui.js';

export const WORLDS = { 'minecraft:overworld': 'Верхний мир', 'minecraft:the_nether': 'Незер', 'minecraft:the_end': 'Энд' };
export const worldTitle = w => WORLDS[w] || String(w || '—').replace(/^minecraft:/, '');
export const coords = p => (p && p.x != null ? `${Math.round(p.x)} ${Math.round(p.y)} ${Math.round(p.z)}` : '—');

export const unavailable = e => e?.status === 404 || e?.status === 503;
const NOT_YET = { status: 503, message: 'Сервер пока не отдаёт местоположение игроков: эту часть API ещё подключают.' };

/** Окно телепорта игрока. positions — онлайн-игроки (для «к игроку»), waypoints — сохранённые точки. */
export async function teleportDialog(target, { positions = [], waypoints = null, onDone } = {}) {
  if (waypoints == null) waypoints = await api.get('waypoints').then(r => r.waypoints || []).catch(() => []);
  const others = positions.filter(p => p.player.uuid !== target.uuid);
  const modes = [
    others.length && { value: 'player', title: 'К игроку' },
    { value: 'respawn', title: 'На его точку возрождения' },
    { value: 'spawn', title: 'На спавн мира' },
    waypoints.length && { value: 'waypoint', title: 'На сохранённую точку' },
    { value: 'coords', title: 'По координатам' },
  ].filter(Boolean);
  let mode = modes[0].value;
  const here = positions.find(p => p.player.uuid === target.uuid);
  const toPlayer = h('select.select#tp-player', others.map(p => h('option', { value: p.player.uuid }, `${pname(p.player)} — ${worldTitle(p.world)}, ${coords(p)}`)));
  const wp = h('select.select#tp-wp', waypoints.map(w => h('option', { value: w.id }, `${w.name} — ${worldTitle(w.world)}, ${coords(w)}`)));
  const world = h('select.select#tp-world', Object.entries(WORLDS).map(([v, t]) => h('option', { value: v, selected: v === (here?.world || 'minecraft:overworld') }, t)));
  const num = (id, v) => h(`input.input.num#${id}`, { type: 'number', inputmode: 'decimal', step: 'any', value: v != null ? Math.round(v) : '' });
  const x = num('tp-x', here?.x), y = num('tp-y', here?.y ?? 80), z = num('tp-z', here?.z);
  const parts = {
    player: h('div', h('label.lbl', { for: 'tp-player' }, 'Куда'), toPlayer),
    waypoint: h('div', h('label.lbl', { for: 'tp-wp' }, 'Точка'), wp),
    coords: h('div.stack', { style: { gap: '8px' } }, h('div', h('label.lbl', { for: 'tp-world' }, 'Мир'), world),
      h('div.row.nowrap', ...[['X', x], ['Y', y], ['Z', z]].map(([l, el]) => h('div.grow', h('label.lbl', { for: el.id }, l), el)))),
    respawn: h('p.text-2', 'Кровать или якорь возрождения игрока; если их нет — спавн мира.'),
    spawn: h('p.text-2', 'Точка появления новых игроков в верхнем мире.'),
  };
  const box = h('div');
  const modeChips = h('div.chips', { role: 'group', 'aria-label': 'Куда переносить' });
  const show = () => {
    replace(modeChips, modes.map(m => h('button.chip', { type: 'button', 'aria-pressed': String(m.value === mode), onclick: () => { mode = m.value; err.textContent = ''; show(); } }, m.title)));
    replace(box, parts[mode]);
  };
  const err = h('p', { role: 'alert', style: { color: 'var(--bad-ink)', fontSize: '13.5px' } });
  const go = h('button.btn.primary', { type: 'submit' }, 'Телепортировать');
  const dlg = h('dialog', { 'aria-labelledby': 'tp-t' }, h('form.dlg', {
    onsubmit: e => {
      e.preventDefault();
      let body;
      if (mode === 'player') body = { toPlayer: toPlayer.value };
      else if (mode === 'waypoint') body = { waypoint: wp.value };
      else if (mode === 'coords') {
        const v = [x, y, z].map(i => parseFloat(String(i.value).replace(',', '.')));
        if (v.some(n => !Number.isFinite(n))) { err.textContent = 'Нужны три числа: X, Y и Z.'; return; }
        if (v[1] < -64 || v[1] > 320) { err.textContent = 'Y должен быть от −64 до 320.'; return; }
        body = { world: world.value, x: v[0], y: v[1], z: v[2] };
      } else body = { to: mode };
      busy(go, async () => {
        const r = await api.post(`players/${encodeURIComponent(target.uuid)}/teleport`, body);
        toast(r.message || 'Перенесён');
        dlg.close();
        onDone?.();
      });
    },
  },
    h('h2#tp-t', `Телепорт: ${target.name}`),
    here ? h('p.text-2', `Сейчас: ${worldTitle(here.world)}, ${coords(here)}`) : null,
    modeChips,
    box, err,
    h('div.actions', h('button.btn', { type: 'button', onclick: () => dlg.close() }, 'Отмена'), go)));
  show();
  openDialog(dlg);
}

const TRACKS = [{ value: 'off', title: 'Без следов' }, { value: '1h', title: '1 ч', ms: 3600e3 }, { value: '6h', title: '6 ч', ms: 6 * 3600e3 }, { value: '24h', title: '24 ч', ms: 86400e3 }, { value: '7d', title: '7 д', ms: 7 * 86400e3 }];

/** Вкладка «Где игроки» раздела «Игроки»: карта мира сверху со следами, таблица координат, точки телепорта. */
export function positionsTab(box, { onOpen, params = {}, setParams = () => {} } = {}) {
  let world = params.world || null, stopped = false, waypoints = [], lastData = null, lastEvent = 0, trackRange = params.tracks || '6h', knownWorlds = null;
  const stamp = h('span.muted', { style: { fontSize: '12.5px' } });
  const worldBox = h('span'), followSel = h('select.select', { 'aria-label': 'Следить за игроком', style: { width: 'auto' } });
  const head = h('div.toolbar', worldBox, followSel, seg(TRACKS, trackRange, v => { trackRange = v; setParams({ tracks: v }); loadTracks(); }, 'Следы'), h('span.spacer'), stamp);
  const host = h('div.wmap-host');
  const mapCard = h('section.card.flush', host);
  const listBox = h('section.card.flush'), wpBox = h('section.card');
  replace(box, head, h('div.stack.lg', mapCard, listBox, wpBox));
  const map = new WorldMap(host, {
    world: world || 'minecraft:overworld', center: params.mx != null ? { x: +params.mx, z: +params.mz } : null, scale: params.ms ? +params.ms : 1,
    onView: v => setParams({ mx: v.x, mz: v.z, ms: v.scale }), onPick: uuid => onOpen?.(uuid),
  });
  let centered = params.mx != null;
  followSel.addEventListener('change', () => { if (followSel.value) map.followPlayer(followSel.value); else map.follow = null; });
  const switchWorld = w => { if (w === world) return; world = w; setParams({ world: w }); map.setWorld(w); worldsKey = ''; centered = false; loadTracks(); loadMarkers(); if (lastData) tick(false, lastData); };

  let worldsKey = '';
  function drawWorlds(worlds, ps) {
    const key = worlds.join(',') + '|' + world + '|' + ps.map(p => p.world).join(',');
    if (key === worldsKey) return;
    worldsKey = key;
    replace(worldBox, worlds.length > 1 ? seg(worlds.map(w => ({ value: w, title: `${worldTitle(w)} · ${ps.filter(p => p.world === w).length}` })), world, switchWorld, 'Мир') : h('b', worldTitle(world)));
  }
  async function tick(first, pushed) {
    try {
      const r = pushed || await api.get('players/positions');
      if (stopped) return;
      lastData = r;
      const ps = (r.players || []).filter(p => p.online !== false);
      if (!world) { world = ps[0]?.world || 'minecraft:overworld'; map.setWorld(world); }
      const worlds = [...new Set([...(knownWorlds || []), ...ps.map(p => p.world), world])];
      drawWorlds(worlds, ps);
      stamp.textContent = `обновлено ${fmt.timeSec(r.t || Date.now())}`;
      const here = ps.filter(p => p.world === world);
      map.setPlayers(here);
      if (!centered && here.length) { centered = true; map.fit(); }
      const options = ['', ...here.map(p => p.player.uuid)].join(',');
      if (followSel.dataset.k !== options) {
        followSel.dataset.k = options;
        replace(followSel, h('option', { value: '' }, 'Не следить'), here.map(p => h('option', { value: p.player.uuid, selected: map.follow === p.player.uuid }, `Следить: ${pname(p.player)}`)));
      }
      // Свежие точки дописываем к следам, чтобы путь рос без перезапроса.
      if (trackRange !== 'off') {
        for (const p of here) {
          let t = map.tracks.find(x => x.player.uuid === p.player.uuid);
          if (!t) { t = { player: p.player, points: [] }; map.tracks.push(t); }
          const lastPt = t.points[t.points.length - 1];
          if (!lastPt || Math.hypot(lastPt[1] - p.x, lastPt[3] - p.z) > 2) t.points.push([r.t || Date.now(), p.x, p.y, p.z]);
        }
        map.drawOverlay();
      }
      if (!listBox.contains(document.activeElement)) replace(listBox, ps.length ? table([
        { key: 'name', title: 'Игрок', value: p => pname(p.player).toLowerCase(), render: p => h('a', { href: `#/players/${encodeURIComponent(p.player.uuid)}`, onclick: e => { if (onOpen) { e.preventDefault(); onOpen(p.player.uuid); } } }, pname(p.player)) },
        { key: 'world', title: 'Мир', render: p => worldTitle(p.world) },
        { key: 'xyz', title: 'X Y Z', sortable: false, render: p => h('button.btn.ghost.sm.mono', { type: 'button', title: 'Скопировать команду /tp', onclick: () => copyText(`/execute in ${p.world} run tp ${pname(p.player)} ${coords(p)}`) }, coords(p), icon('copy', 'sm')) },
        { key: 'biome', title: 'Биом', render: p => (p.biome ? String(p.biome).replace(/^minecraft:/, '').replaceAll('_', ' ') : '—') },
        { key: 'health', title: 'Здоровье', r: true, render: p => (p.health != null ? `${Math.round(p.health / 2 * 10) / 10} ♥` : '—') },
        { key: 'tp', title: '', sortable: false, render: p => h('div.row.nowrap',
          h('button.btn.sm.ghost', { type: 'button', onclick: () => { switchWorld(p.world); map.followPlayer(p.player.uuid); followSel.value = p.player.uuid; mapCard.scrollIntoView({ behavior: 'smooth', block: 'center' }); } }, 'На карте'),
          h('button.btn.sm', { type: 'button', onclick: () => teleportDialog(p.player, { positions: ps, waypoints, onDone: () => tick() }) }, 'Телепорт…')) },
      ], ps, { sort: { key: 'name', dir: 1 } }) : h('div', empty('Онлайн никого нет.')));
    } catch (e) {
      if (stopped) return;
      if (unavailable(e)) { replace(mapCard, errorBox(NOT_YET)); replace(listBox); stopped = true; return; }
      if (first) replace(listBox, errorBox(e, () => tick(true)));
    }
  }
  async function loadTracks() {
    map.showTracks = trackRange !== 'off';
    if (trackRange === 'off') { map.setTracks([]); return; }
    const to = Date.now();
    try {
      const r = await api.get('players/tracks', { world: world || 'minecraft:overworld', from: to - TRACKS.find(t => t.value === trackRange).ms, to, points: 2000 });
      if (!stopped) map.setTracks(r.tracks || []);
    } catch { map.setTracks([]); }
  }
  async function loadMarkers() {
    try { const r = await api.get('map/markers', { world: world || 'minecraft:overworld' }); if (!stopped) map.setMarkers(r.markers || []); }
    catch { map.setMarkers(waypoints.filter(w => w.world === world).map(w => ({ kind: 'waypoint', id: w.id, name: w.name, x: w.x, z: w.z }))); }
  }
  api.get('map/info', { world: world || 'minecraft:overworld' }).then(i => {
    knownWorlds = i.worlds || null;
    map.setTiling(i.tileSize, i.maxZoom);
    if (!centered && i.spawn && !(lastData?.players || []).some(p => p.world === world)) { centered = true; map.center(i.spawn.x, i.spawn.z, 0.5); }
    if (lastData) tick(false, lastData);
  }).catch(() => {});

  // Точки телепорта: список, добавить (вручную, где стоит игрок или центр карты), показать, удалить.
  function drawWaypoints() {
    const add = h('button.btn.sm', { type: 'button' }, 'Добавить точку');
    add.addEventListener('click', async () => {
      const ps = lastData?.players || [];
      const r = await ask({ title: 'Новая точка', ok: 'Сохранить', fields: [
        { key: 'name', title: 'Название', placeholder: 'Колокол в Дубках' },
        { key: 'from', title: 'Откуда взять координаты', options: [{ value: 'center', title: 'Центр карты' }, ...ps.map(p => ({ value: p.player.uuid, title: `Где стоит ${pname(p.player)}` })), { value: '', title: 'Ввести вручную' }] },
        { key: 'world', title: 'Мир (если вручную)', options: Object.entries(WORLDS).map(([value, title]) => ({ value, title })) },
        { key: 'xyz', title: 'X Y Z (если вручную)', placeholder: '120 64 -300' }] });
      if (!r || !r.name.trim()) return;
      let body;
      const src = ps.find(p => p.player.uuid === r.from);
      if (src) body = { name: r.name.trim(), world: src.world, x: Math.round(src.x), y: Math.round(src.y), z: Math.round(src.z) };
      else if (r.from === 'center') body = { name: r.name.trim(), world: world || 'minecraft:overworld', x: Math.round(map.cx), y: 80, z: Math.round(map.cz) };
      else {
        const v = r.xyz.trim().split(/[\s,;]+/).map(Number);
        if (v.length !== 3 || v.some(n => !Number.isFinite(n))) return toast('Нужны три числа через пробел: X Y Z', 'bad');
        body = { name: r.name.trim(), world: r.world, x: v[0], y: v[1], z: v[2] };
      }
      busy(add, async () => { await api.post('waypoints', body); toast('Точка сохранена'); await loadWaypoints(); });
    });
    replace(wpBox, h('div.card-head', h('h2', 'Точки телепорта'), add),
      waypoints.length ? h('div.list', waypoints.map(w => {
        const del = h('button.btn.sm.ghost', { type: 'button', 'aria-label': `Удалить точку ${w.name}` }, icon('close', 'sm'));
        del.addEventListener('click', async () => {
          if (await confirm({ title: `Удалить «${w.name}»?`, consequence: 'Точка пропадёт из списка телепорта. Игроков это не затронет.', ok: 'Удалить', danger: true }))
            busy(del, async () => { await api.del(`waypoints/${encodeURIComponent(w.id)}`); toast('Удалено'); await loadWaypoints(); });
        });
        const show = h('button.btn.sm.ghost', { type: 'button', onclick: () => { switchWorld(w.world); map.follow = null; centered = true; map.center(w.x, w.z, Math.max(map.scale, 2)); mapCard.scrollIntoView({ behavior: 'smooth', block: 'center' }); } }, 'На карте');
        return h('div.list-item', { style: { padding: '8px 0' } }, h('div.body', h('div', { style: { fontWeight: 600 } }, w.name), h('div.meta', worldTitle(w.world), h('span.mono', coords(w)))), show, del);
      })) : h('p.muted', 'Точек нет. Сохраните важные места — дом, деревню, храм — чтобы переносить туда в одно касание.'));
  }
  async function loadWaypoints() {
    try { waypoints = (await api.get('waypoints')).waypoints || []; drawWaypoints(); loadMarkers(); } catch (e) { if (unavailable(e)) wpBox.hidden = true; else replace(wpBox, errorBox(e, loadWaypoints)); }
  }

  loadWaypoints();
  loadTracks();
  const offs = [
    on('positions', d => { lastEvent = Date.now(); if (!stopped) tick(false, d); }),
    on('map-tile', d => d && map.refreshTile(d.world, d.zoom, d.tx, d.tz)),
  ];
  tick(true);
  // Основной путь — событие positions по SSE; опрос — запасной, если событий нет.
  const timer = setInterval(() => { if (!stopped && document.visibilityState === 'visible' && Date.now() - lastEvent > 4000) tick(); }, 2000);
  const trackTimer = setInterval(() => { if (!stopped && document.visibilityState === 'visible') loadTracks(); }, 60_000);
  return () => { stopped = true; clearInterval(timer); clearInterval(trackTimer); offs.forEach(f => f()); map.destroy(); };
}

/** Блок «Местоположение» в карточке игрока. */
export function positionBlock(player, online) {
  const box = h('div', skeleton(2));
  const draw = () => api.get(`players/${encodeURIComponent(player.uuid)}/position`).then(p => {
    const btn = h('button.btn', { type: 'button', disabled: !online, title: online ? '' : 'Телепорт — только в сети' }, 'Телепортировать…');
    btn.addEventListener('click', async () => {
      const all = await api.get('players/positions').then(r => r.players || []).catch(() => []);
      teleportDialog(player, { positions: all, onDone: draw });
    });
    replace(box, h('dl.kv', h('dt', online ? 'Сейчас' : 'Последний раз'), h('dd', `${worldTitle(p.world)}, `, h('span.mono', coords(p))),
      p.biome ? [h('dt', 'Биом'), h('dd', String(p.biome).replace(/^minecraft:/, '').replaceAll('_', ' '))] : null,
      h('dt', 'Возрождение'), h('dd', p.respawn ? [`${worldTitle(p.respawn.world)}, `, h('span.mono', coords(p.respawn))] : 'спавн мира')),
      h('div.row', { style: { marginTop: '10px' } }, btn, h('a.btn.ghost', { href: '#/players?view=map' }, 'На карте')));
  }).catch(e => replace(box, unavailable(e) ? h('p.muted', NOT_YET.message) : errorBox(e, draw)));
  draw();
  return box;
}
