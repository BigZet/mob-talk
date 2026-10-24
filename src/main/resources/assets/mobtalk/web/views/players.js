// Игроки: таблица с сортировкой и поиском; карточка игрока с правами, стримером, whitelist, баном, расходами и отношениями.
import { h, icon, replace, avatar, debounce } from '../lib/dom.js';
import { api } from '../lib/api.js';
import { on } from '../lib/sse.js';
import * as fmt from '../lib/fmt.js';
import { seg, table, load, drawer, confirm, ask, busy, toast, empty, hearts, kv, switchInput } from '../lib/ui.js';
import { timeChart } from '../lib/chart.js';
import { speciesTitle } from '../lib/record.js';
import { navigate, setParams, parseRoute } from '../app.js';
import { positionsTab, positionBlock } from './positions.js';
import { map3dTab } from './map3d.js';

const LEVEL = { ADMIN: 'админ', STREAMER: 'стример', PLAYER: 'игрок' };
const ACTIVITY = { talking: 'говорит с мобом', trading: 'торгуется', praying: 'молится', idle: 'свободен' };
export const dayToSec = key => { const m = /^(\d{4})-?(\d{2})-?(\d{2})$/.exec(String(key)); return m ? new Date(+m[1], +m[2] - 1, +m[3]).getTime() / 1000 : 0; };

function badges(p) {
  return [
    p.online ? h('span.badge.good', 'онлайн') : null,
    p.streamer ? h('span.badge.accent', 'стример') : null,
    p.level === 'ADMIN' || p.op ? h('span.badge', 'админ') : null,
    p.banned ? h('span.badge.bad', 'бан') : null,
    p.whitelisted === false ? h('span.badge.warn', 'не в whitelist') : null,
  ];
}

export default function players(root, route) {
  let filterBy = route.params.f || 'all';
  let q = route.params.q || '';
  let all = [];
  const cleanup = [];
  let view = ['map', '3d'].includes(route.params.view) ? route.params.view : 'list';
  let stopMap = null;
  const listBox = h('div.card.flush');
  const search = h('input.input', { type: 'search', placeholder: 'Ник или UUID', value: q, 'aria-label': 'Поиск игрока' });
  const addWl = h('button.btn', { type: 'button' }, icon('players', 'sm'), 'Добавить в whitelist');
  addWl.addEventListener('click', async () => {
    const r = await ask({ title: 'Добавить игрока в whitelist', fields: [{ key: 'name', title: 'Ник в Minecraft', value: '', placeholder: 'например, Steve_42' }], ok: 'Добавить',
      text: 'Игрок может ещё ни разу не заходить на сервер.' });
    if (!r || !r.name.trim()) return;
    busy(addWl, async () => { const a = await api.post('whitelist', { name: r.name.trim() }); toast(a.message || 'Добавлен'); reload(); });
  });
  const listTools = h('div.toolbar', h('div.search', icon('search'), search),
    seg([{ value: 'all', title: 'Все' }, { value: 'online', title: 'Онлайн' }, { value: 'banned', title: 'Бан' }, { value: 'nowl', title: 'Вне whitelist' }], filterBy, v => { filterBy = v; setParams({ f: v }); draw(); }, 'Фильтр игроков'), addWl);
  const mapBox = h('div');
  const showView = () => {
    stopMap?.(); stopMap = null;
    listTools.hidden = listBox.hidden = view !== 'list';
    mapBox.hidden = view === 'list';
    if (view === '3d') stopMap = map3dTab(mapBox);
    if (view === 'map') stopMap = positionsTab(mapBox, { onOpen: uuid => navigate('players', uuid, parseRoute().params), params: parseRoute().params, setParams });
  };
  replace(root,
    h('div.page-head', h('h1', 'Игроки'), seg([{ value: 'list', title: 'Список' }, { value: 'map', title: 'Карта' }, { value: '3d', title: '3D' }], view, v => { view = v; setParams({ view: v === 'list' ? '' : v }); showView(); }, 'Вид')),
    listTools, listBox, mapBox);
  cleanup.push(() => stopMap?.());
  search.addEventListener('input', debounce(() => { q = search.value.trim(); setParams({ q }); draw(); }, 150));

  function draw() {
    const s = q.toLowerCase();
    const rows = all.filter(p => (!s || p.name.toLowerCase().includes(s) || p.uuid.includes(s))
      && (filterBy === 'all' || (filterBy === 'online' && p.online) || (filterBy === 'banned' && p.banned) || (filterBy === 'nowl' && p.whitelisted === false)));
    replace(listBox, rows.length ? table([
      { key: 'name', title: 'Игрок', value: p => p.name.toLowerCase(), render: p => h('div.row.nowrap', avatar(p.name), h('div', h('div', { style: { fontWeight: 600 } }, p.name), h('div.row', { style: { gap: '4px', marginTop: '2px' } }, badges(p)))) },
      { key: 'lastSeen', title: 'Был', desc: true, value: p => (p.online ? Infinity : p.lastSeen), render: p => (p.online ? 'сейчас' : fmt.ago(p.lastSeen)) },
      { key: 'onlineSeconds', title: 'Время в игре', r: true, desc: true, render: p => fmt.duration(p.onlineSeconds) },
      { key: 'stt', title: 'Распознавание', sortable: false, render: p => sttCell(p.stt) },
      { key: 'dialogues', title: 'Диалоги', r: true, desc: true, render: p => fmt.num(p.dialogues) },
      { key: 'costTodayUsd', title: 'Сегодня', r: true, desc: true, render: p => fmt.usd(p.costTodayUsd) },
      { key: 'costTotalUsd', title: 'Всего', r: true, desc: true, render: p => fmt.usd(p.costTotalUsd) },
    ], rows, { onRow: p => navigate('players', p.uuid, route.params), rowLabel: p => `Открыть ${p.name}`, sort: { key: 'lastSeen', dir: -1 } }) : h('div', empty(all.length ? 'Никого не нашлось.' : 'Игроков ещё не было.')));
  }
  const reload = () => load(listBox, () => api.get('players'), r => { all = r.players || []; queueMicrotask(draw); return []; }, { lines: 6 });
  reload();
  cleanup.push(on('presence', () => api.get('players').then(r => { all = r.players || []; draw(); }).catch(() => {})));

  let open = null;
  function update(r) {
    const v = ['map', '3d'].includes(r.params.view) ? r.params.view : 'list';
    if (v !== view) { view = v; root.querySelectorAll('.page-head .seg button').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.v === v))); showView(); }
    if (open && open.uuid !== r.id) { open.d.close(); open = null; }
    if (r.id && !open) open = { uuid: r.id, d: card(r.id, () => { open = null; setTimeout(() => { if (location.hash.includes(`/players/${encodeURIComponent(r.id)}`)) navigate('players', null, r.params); }); },() => api.get('players').then(x => { all = x.players || []; draw(); })) };
  }
  update(route);
  showView();
  return { update, destroy() { cleanup.forEach(f => f()); open?.d.close(); } };
}

/** Карточка игрока в шторке. */
const GAMEMODES = { survival: 'выживание', creative: 'творческий', adventure: 'приключение', spectator: 'наблюдатель' };
const STT = { auto: 'авто', local: 'свой Whisper', server: 'сервер (OpenRouter)', text: 'только текст', '': '—' };
/** Какой путь распознавания игрок выбрал в F7 и каким пришла его последняя реплика. */
const WHISPER = s => (s.installed === true ? 'свой Whisper установлен' : s.installed === false ? 'своего Whisper нет' : 'про свой Whisper клиент не сообщил');
const sttLine = s => [`в настройках: ${STT[s.chosen] ?? s.chosen}`, WHISPER(s), s.effective && `получает: ${STT[s.effective] ?? s.effective}`,
  s.last && `последняя реплика: ${STT[s.last] ?? s.last}`].filter(Boolean).join(' · ');
/** Короткая ячейка списка: что игрок получает (и что выбрал, если это «авто»). */
const sttCell = s => (!s || !s.chosen ? h('span.muted', '—')
  : h('div', { title: sttLine(s) }, h('div', STT[s.effective] ?? STT[s.chosen] ?? s.chosen),
    h('div.muted', { style: { fontSize: '12px' } }, s.chosen === 'auto' ? 'авто' : 'выбрал сам', s.installed === true ? ' · Whisper есть' : s.installed === false ? ' · без Whisper' : '')));

export function card(uuid, onClose, onChanged = () => {}) {
  const d = drawer('Игрок', { onClose });
  const charts = [];
  d.dlg.addEventListener('close', () => charts.forEach(c => c.destroy()));
  const reload = () => load(d.body, () => api.get(`players/${encodeURIComponent(uuid)}`), p => {
    d.setTitle(p.name);
    const act = async (path, body, msg) => { const r = await api.post(`players/${encodeURIComponent(uuid)}/${path}`, body); toast(r?.message || msg); reload(); onChanged(); };
    const levelSel = h('select.select', { 'aria-label': 'Уровень прав', style: { width: 'auto' } }, ['PLAYER', 'ADMIN'].map(l => h('option', { value: l, selected: (p.level === 'ADMIN' || p.op ? 'ADMIN' : 'PLAYER') === l }, l === 'ADMIN' ? 'админ (оператор)' : 'игрок')));
    levelSel.addEventListener('change', async () => {
      const toAdmin = levelSel.value === 'ADMIN';
      if (!(await confirm({ title: toAdmin ? `Сделать ${p.name} админом?` : `Снять права админа с ${p.name}?`, consequence: toAdmin ? 'Админ получит все команды сервера и модов, включая баны и бога.' : 'Игрок потеряет команды модов.', ok: toAdmin ? 'Сделать админом' : 'Снять права', danger: toAdmin }))) { levelSel.value = toAdmin ? 'PLAYER' : 'ADMIN'; return; }
      busy(levelSel, () => act('level', { level: levelSel.value }, 'Уровень изменён'));
    });
    const streamerBtn = h('button.btn', { type: 'button' }, p.streamer ? 'Снять стримера' : 'Назначить стримером');
    streamerBtn.addEventListener('click', async () => {
      if (!(await confirm({ title: p.streamer ? 'Снять стримера?' : `Назначить ${p.name} стримером?`, consequence: p.streamer ? 'На сервере не будет стримера: зрители Twitch не смогут влиять на игру.' : 'Стример на сервере один — прежний перестанет им быть. Мобы и бог будут знать, кто ведёт трансляцию.', ok: p.streamer ? 'Снять' : 'Назначить' }))) return;
      busy(streamerBtn, async () => { await api.post('streamer', { uuid: p.streamer ? null : uuid }); toast(p.streamer ? 'Стример снят' : 'Стример назначен'); reload(); onChanged(); });
    });
    const kick = h('button.btn', { type: 'button', disabled: !p.online, title: p.online ? '' : 'Игрок не в сети' }, 'Кикнуть');
    kick.addEventListener('click', async () => { const r = await ask({ title: `Кикнуть ${p.name}?`, fields: [{ key: 'reason', title: 'Причина (игрок её увидит)', value: 'Кик из админки' }], ok: 'Кикнуть' }); if (r) busy(kick, () => act('kick', r, 'Игрок кикнут')); });
    const ban = h(`button.btn${p.banned ? '' : '.danger'}`, { type: 'button' }, p.banned ? 'Разбанить' : 'Забанить');
    ban.addEventListener('click', async () => {
      if (p.banned) { if (await confirm({ title: `Разбанить ${p.name}?`, consequence: 'Игрок сможет снова зайти на сервер.', ok: 'Разбанить' })) busy(ban, () => act('unban', {}, 'Бан снят')); return; }
      const r = await ask({ title: `Забанить ${p.name}?`, fields: [{ key: 'reason', title: 'Причина', value: '' }], ok: 'Забанить', danger: true, consequence: 'Игрок будет отключён и не сможет зайти, пока бан не снимут.' });
      if (r) busy(ban, () => act('ban', r, 'Игрок забанен'));
    });
    const wl = switchInput({ checked: p.whitelisted !== false, label: 'В whitelist', onChange: async on => {
      if (!on && !(await confirm({ title: `Убрать ${p.name} из whitelist?`, consequence: 'Игрок не сможет зайти, пока его не вернут в список.', ok: 'Убрать', danger: true }))) { wl.querySelector('input').checked = true; return; }
      act('whitelist', { on }, on ? 'Добавлен в whitelist' : 'Убран из whitelist').catch(e => { toast(e.message, 'bad'); reload(); });
    } });
    const modeSel = h('select.select', { 'aria-label': 'Режим игры', style: { width: 'auto' }, disabled: !p.online, title: p.online ? '' : 'Игрок не в сети' },
      Object.entries(GAMEMODES).map(([v, t]) => h('option', { value: v, selected: p.gamemode === v }, t)));
    modeSel.addEventListener('change', async () => {
      if (!(await confirm({ title: `Режим «${GAMEMODES[modeSel.value]}» для ${p.name}?`, consequence: 'Режим сменится сразу, игрок увидит это в игре.', ok: 'Сменить' }))) { modeSel.value = p.gamemode || 'survival'; return; }
      busy(modeSel, () => act('gamemode', { mode: modeSel.value }, 'Режим изменён'));
    });
    const chartEl = h('div.chart');
    const out = [
      h('div.row.nowrap', avatar(p.name, 'lg'), h('div.grow', h('h2', p.name), h('div.row', { style: { gap: '4px', marginTop: '4px' } }, badges(p)), h('div.muted.mono', { style: { marginTop: '4px', fontSize: '12px' } }, p.uuid))),
      kv([['Сейчас', p.online ? ACTIVITY[p.activity] || p.activity || 'в игре' : `не в сети, был ${fmt.ago(p.lastSeen)}`], ['Время в игре', fmt.duration(p.onlineSeconds)], ['Диалогов', fmt.num(p.dialogues)],
        ['Расходы', `${fmt.usd(p.costTodayUsd)} сегодня · ${fmt.usd(p.costTotalUsd)} всего`], p.stt && ['Распознавание речи', sttLine(p.stt)], p.banned && ['Причина бана', p.banReason || '—']]),
      h('section.stack', h('h3', 'Управление'),
        h('div.row', h('span.grow', 'Уровень прав'), levelSel),
        h('div.row', h('span.grow', 'Режим игры'), modeSel),
        h('label.row', h('span.grow', 'В whitelist'), wl),
        h('div.row', streamerBtn, kick, ban)),
      h('section.stack', h('h3', 'Местоположение'), positionBlock({ uuid, name: p.name }, p.online)),
      h('section', h('div.card-head', h('h3', 'Расходы за 30 дней')), chartEl),
      h('section.stack', h('div.row', h('h3.grow', 'Отношения с мобами'), h('a.btn.sm.ghost', { href: `#/history?player=${encodeURIComponent(uuid)}&kind=dialogue`, onclick: () => d.close() }, 'Диалоги игрока', icon('chevron', 'sm'))),
        p.relations?.length ? h('div.list.card.flush', p.relations.map(r => h('a.list-item', { href: `#/mobs/${encodeURIComponent(r.mob?.uuid || '')}`, onclick: () => d.close() },
          h('div.body', h('div', { style: { fontWeight: 600 } }, r.mob?.name), h('div.meta', speciesTitle(r.mob?.species), r.label && h('span.badge', r.label), h('span.num', `отношение ${r.relationship}`))),
          hearts(r.hearts ?? 0)))) : h('p.muted', 'Ни с кем из мобов ещё не знаком.')),
      h('section.stack', h('h3', 'Личные настройки'), h('p.muted', { style: { fontSize: '13px' } }, 'Игрок меняет их сам в игре; здесь только для просмотра.'),
        p.settings && Object.keys(p.settings).length ? kv(Object.entries(p.settings).map(([k, v]) => [h('span.mono', k), fmt.value(v)])) : h('p.muted', 'Нет данных.')),
    ];
    requestAnimationFrame(() => {
      const days = p.costByDay || [];
      if (chartEl.isConnected) charts.push(timeChart(chartEl, { t: days.map(x => dayToSec(x.day)), series: [{ label: 'Расходы', data: days.map(x => x.usd), slot: 2 }], bars: true, daily: true, fmt: fmt.usd, height: 160 }));
    });
    return out;
  }, { lines: 8 });
  reload();
  return d;
}
