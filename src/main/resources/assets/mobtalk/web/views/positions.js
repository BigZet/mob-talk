// Где игроки: карта сверху (X/Z) по мирам, таблица координат с обновлением раз в 2 с и телепорт игрока —
// к другому игроку, на точку возрождения, на спавн мира, на сохранённую точку или по координатам.
import { h, icon, replace } from '../lib/dom.js';
import { api } from '../lib/api.js';
import * as fmt from '../lib/fmt.js';
import { seg, busy, toast, empty, errorBox, skeleton, table, openDialog, copyText } from '../lib/ui.js';
import { pname } from '../lib/record.js';
import { on } from '../lib/sse.js';
import { confirm, ask } from '../lib/ui.js';

export const WORLDS = { 'minecraft:overworld': 'Верхний мир', 'minecraft:the_nether': 'Незер', 'minecraft:the_end': 'Энд' };
export const worldTitle = w => WORLDS[w] || String(w || '—').replace(/^minecraft:/, '');
export const coords = p => (p && p.x != null ? `${Math.round(p.x)} ${Math.round(p.y)} ${Math.round(p.z)}` : '—');
const SVGNS = 'http://www.w3.org/2000/svg';
const svg = (tag, attrs = {}, ...kids) => { const el = document.createElementNS(SVGNS, tag); for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v); el.append(...kids); return el; };

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

/** Карта сверху: точки игроков (и сохранённые точки) одного мира. */
function mapView(list, waypoints, width = 800) {
  const W = Math.max(320, Math.round(width)), H = W < 600 ? Math.round(W * 0.85) : Math.round(W * 0.5), pad = W < 600 ? 34 : 40;
  const pts = [...list.map(p => ({ x: p.x, z: p.z })), ...waypoints.map(w => ({ x: w.x, z: w.z }))];
  if (!pts.length) return empty('В этом мире никого нет.');
  let minX = Math.min(...pts.map(p => p.x)), maxX = Math.max(...pts.map(p => p.x)), minZ = Math.min(...pts.map(p => p.z)), maxZ = Math.max(...pts.map(p => p.z));
  const span = Math.max(64, maxX - minX, (maxZ - minZ) * (W / H)) * 1.15;
  const cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2, spanZ = span * (H / W);
  minX = cx - span / 2; maxX = cx + span / 2; minZ = cz - spanZ / 2; maxZ = cz + spanZ / 2;
  const sx = v => pad + ((v - minX) / (maxX - minX)) * (W - 2 * pad), sz = v => pad + ((v - minZ) / (maxZ - minZ)) * (H - 2 * pad);
  const step = [16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192].find(s => span / s <= 8) || 16384;
  const grid = [];
  for (let gx = Math.ceil(minX / step) * step; gx <= maxX; gx += step) grid.push(svg('line', { x1: sx(gx), x2: sx(gx), y1: pad, y2: H - pad, class: 'grid' }), svg('text', { x: sx(gx), y: H - pad + 16, 'text-anchor': 'middle', class: 'tick' }, String(gx)));
  for (let gz = Math.ceil(minZ / step) * step; gz <= maxZ; gz += step) grid.push(svg('line', { x1: pad, x2: W - pad, y1: sz(gz), y2: sz(gz), class: 'grid' }), svg('text', { x: pad - 6, y: sz(gz) + 4, 'text-anchor': 'end', class: 'tick' }, String(gz)));
  const marks = waypoints.map(w => svg('g', { class: 'wp' }, svg('rect', { x: sx(w.x) - 5, y: sz(w.z) - 5, width: 10, height: 10, transform: `rotate(45 ${sx(w.x)} ${sz(w.z)})` }), svg('text', { x: sx(w.x) + 10, y: sz(w.z) + 4 }, w.name)));
  const people = list.map((p, i) => svg('g', { class: 'pl' }, svg('title', {}, `${pname(p.player)}: ${coords(p)}`),
    svg('circle', { cx: sx(p.x), cy: sz(p.z), r: 7, style: `fill: var(--s${(i % 8) + 1})` }),
    p.yaw != null ? svg('line', { x1: sx(p.x), y1: sz(p.z), x2: sx(p.x) - Math.sin((p.yaw * Math.PI) / 180) * 16, y2: sz(p.z) + Math.cos((p.yaw * Math.PI) / 180) * 16, class: 'dir', style: `stroke: var(--s${(i % 8) + 1})` }) : '',
    svg('text', { x: sx(p.x) + 11, y: sz(p.z) - 9, class: 'name' }, pname(p.player))));
  return h('div.map-wrap', svg('svg', { viewBox: `0 0 ${W} ${H}`, class: 'map', role: 'img', 'aria-label': `Карта: ${list.map(p => `${pname(p.player)} ${coords(p)}`).join('; ')}` },
    svg('rect', { x: pad, y: pad, width: W - 2 * pad, height: H - 2 * pad, class: 'frame' }), ...grid, ...marks, ...people,
    svg('text', { x: W - pad, y: pad - 10, 'text-anchor': 'end', class: 'tick' }, 'север ↑ · X вправо, Z вниз')));
}

/** Вкладка «Где игроки» раздела «Игроки». Возвращает функцию остановки опроса. */
export function positionsTab(box, { onOpen } = {}) {
  let world = null, timer = null, stopped = false, waypoints = [], lastHead = '';
  const stamp = h('span.muted', { style: { fontSize: '12.5px' } });
  const head = h('div.toolbar'), mapBox = h('section.card'), listBox = h('section.card.flush');
  replace(box, head, h('div.stack.lg', mapBox, listBox));
  replace(mapBox, skeleton(1, 300));
  let lastData = null, lastEvent = 0;
  async function tick(first, pushed) {
    try {
      const r = pushed || await api.get('players/positions');
      if (stopped) return;
      lastData = r;
      const ps = (r.players || []).filter(p => p.online !== false);
      const worlds = [...new Set(ps.map(p => p.world))];
      if (!world || !worlds.includes(world)) world = worlds[0] || 'minecraft:overworld';
      const headKey = worlds.map(w => `${w}:${ps.filter(p => p.world === w).length}`).join(',') + '|' + world;
      if (headKey !== lastHead) {
        lastHead = headKey;
        replace(head, worlds.length > 1 ? seg(worlds.map(w => ({ value: w, title: `${worldTitle(w)} · ${ps.filter(p => p.world === w).length}` })), world, v => { world = v; tick(false, lastData); }, 'Мир') : h('span.text-2', worldTitle(world)),
          h('span.spacer'), stamp);
      }
      stamp.textContent = `обновлено ${fmt.timeSec(r.t || Date.now())}`;
      replace(mapBox, mapView(ps.filter(p => p.world === world), waypoints.filter(w => w.world === world), mapBox.clientWidth - 32 || 800));
      if (!listBox.contains(document.activeElement)) replace(listBox, ps.length ? table([
        { key: 'name', title: 'Игрок', value: p => pname(p.player).toLowerCase(), render: p => h('a', { href: `#/players/${encodeURIComponent(p.player.uuid)}`, onclick: e => { if (onOpen) { e.preventDefault(); onOpen(p.player.uuid); } } }, pname(p.player)) },
        { key: 'world', title: 'Мир', render: p => worldTitle(p.world) },
        { key: 'xyz', title: 'X Y Z', sortable: false, render: p => h('button.btn.ghost.sm.mono', { type: 'button', title: 'Скопировать команду /tp', onclick: () => copyText(`/execute in ${p.world} run tp ${pname(p.player)} ${coords(p)}`) }, coords(p), icon('copy', 'sm')) },
        { key: 'biome', title: 'Биом', render: p => (p.biome ? String(p.biome).replace(/^minecraft:/, '').replaceAll('_', ' ') : '—') },
        { key: 'health', title: 'Здоровье', r: true, render: p => (p.health != null ? `${Math.round(p.health / 2 * 10) / 10} ♥` : '—') },
        { key: 'tp', title: '', sortable: false, render: p => h('button.btn.sm', { type: 'button', onclick: () => teleportDialog(p.player, { positions: ps, waypoints, onDone: () => tick() }) }, 'Телепорт…') },
      ], ps, { sort: { key: 'name', dir: 1 } }) : h('div', empty('Онлайн никого нет.')));
    } catch (e) {
      if (stopped) return;
      if (unavailable(e)) { replace(mapBox, errorBox(NOT_YET)); replace(listBox); replace(head); stopped = true; return; }
      if (first) replace(mapBox, errorBox(e, () => tick(true)));
    }
  }
  // Точки телепорта: список, добавить (вручную или где стоит игрок), удалить.
  const wpBox = h('section.card');
  box.querySelector('.stack.lg').append(wpBox);
  function drawWaypoints() {
    const add = h('button.btn.sm', { type: 'button' }, 'Добавить точку');
    add.addEventListener('click', async () => {
      const ps = lastData?.players || [];
      const r = await ask({ title: 'Новая точка', ok: 'Сохранить', fields: [
        { key: 'name', title: 'Название', placeholder: 'Колокол в Дубках' },
        { key: 'from', title: 'Откуда взять координаты', options: [{ value: '', title: 'Ввести вручную' }, ...ps.map(p => ({ value: p.player.uuid, title: `Где стоит ${pname(p.player)}` }))] },
        { key: 'world', title: 'Мир (если вручную)', options: Object.entries(WORLDS).map(([value, title]) => ({ value, title })) },
        { key: 'xyz', title: 'X Y Z (если вручную)', placeholder: '120 64 -300' }] });
      if (!r || !r.name.trim()) return;
      let body;
      const src = ps.find(p => p.player.uuid === r.from);
      if (src) body = { name: r.name.trim(), world: src.world, x: Math.round(src.x), y: Math.round(src.y), z: Math.round(src.z) };
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
        return h('div.list-item', { style: { padding: '8px 0' } }, h('div.body', h('div', { style: { fontWeight: 600 } }, w.name), h('div.meta', worldTitle(w.world), h('span.mono', coords(w)))), del);
      })) : h('p.muted', 'Точек нет. Сохраните важные места — дом, деревню, храм — чтобы переносить туда в одно касание.'));
  }
  async function loadWaypoints() {
    try { waypoints = (await api.get('waypoints')).waypoints || []; drawWaypoints(); if (lastData) tick(false, lastData); } catch (e) { if (unavailable(e)) wpBox.hidden = true; else replace(wpBox, errorBox(e, loadWaypoints)); }
  }
  loadWaypoints();
  const off = on('positions', d => { lastEvent = Date.now(); if (!stopped) tick(false, d); });
  tick(true);
  // Основной путь — событие positions по SSE; опрос — запасной, если событий нет.
  timer = setInterval(() => { if (!stopped && document.visibilityState === 'visible' && Date.now() - lastEvent > 4000) tick(); }, 2000);
  return () => { stopped = true; clearInterval(timer); off(); };
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
