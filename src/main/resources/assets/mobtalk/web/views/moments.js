// Моменты для монтажа (идея З4): сессии записи игроков, моменты с таймкодами от начала записи, полоса времени, правки
// (название, звезда, скрыть), ручная метка, сдвиг таймкодов, экспорт глав YouTube / CSV / EDL / XML. Новые моменты — вживую.
import { h, icon, replace } from '../lib/dom.js';
import { api } from '../lib/api.js';
import { on } from '../lib/sse.js';
import * as fmt from '../lib/fmt.js';
import { seg, ask, confirm, busy, empty, errorBox, skeleton, toast, toastError, copyText, switchInput } from '../lib/ui.js';
import { navigate, setParams } from '../app.js';

export const TYPES = {
  god: { title: 'Бог', icon: 'divine', k: 7 },
  combat: { title: 'Бой', icon: 'warn', k: 8 },
  village: { title: 'Жители', icon: 'mobs', k: 3 },
  manual: { title: 'Метка', icon: 'moments', k: 1 },
};
const WEIGHTS = [{ value: '1', title: '★ и выше' }, { value: '2', title: '★★' }, { value: '3', title: '★★★' }];
const FORMATS = [
  { value: 'youtube', title: 'Главы YouTube', hint: 'текст для описания видео' },
  { value: 'csv', title: 'CSV', hint: 'таблица: Excel, Google Таблицы' },
  { value: 'edl', title: 'EDL', hint: 'маркеры DaVinci Resolve, Premiere' },
  { value: 'xml', title: 'XML', hint: 'Final Cut 7 / Premiere, маркеры' },
];
const SOURCE = { streamer_join: 'вход стримера', any_join: 'вход игрока', command: '/clip start', admin: 'админка', obs: 'OBS' };
const stars = n => '★'.repeat(Math.max(1, Math.min(3, n || 1)));
const pname = p => p?.name || p?.uuid || '';

export default function momentsView(root, route) {
  const p = route.params;
  const f = { session: route.id || null, minWeight: p.w || '1', everyone: p.all === '1', hidden: p.hidden === '1' };
  let sessions = [], data = null, seq = 0;
  const sessionBox = h('div');
  const head = h('div.card', { style: { marginBottom: '16px' } });
  const strip = h('div.mstrip', { role: 'img', 'aria-label': 'Моменты на полосе времени записи' });
  const list = h('div.list', { 'aria-live': 'off' });
  const pendingBox = h('div');
  const countEl = h('span.sub');

  replace(root,
    h('div.page-head', h('h1', 'Моменты'),
      h('button.btn.sm', { type: 'button', onclick: e => busy(e.currentTarget, markNow) }, icon('moments', 'sm'), 'Отметить сейчас'),
      h('button.btn.sm', { type: 'button', onclick: e => busy(e.currentTarget, startStop) }, icon('play', 'sm'), 'Сессия записи')),
    sessionBox, head,
    h('section.card.flush', h('div.card-head', { style: { padding: '14px 16px 0' } }, h('h2', 'Моменты'), countEl), strip, list, pendingBox));

  // ---------- сессии ----------
  async function loadSessions() {
    replace(sessionBox, skeleton(1, 36));
    try {
      sessions = (await api.get('moments/sessions')).sessions || [];
      if (!f.session || !sessions.some(s => s.id === f.session)) f.session = sessions[0]?.id || null;
      drawSessions();
      await loadMoments(true);
    } catch (e) { replace(sessionBox, errorBox(e, loadSessions)); }
  }
  function drawSessions() {
    if (!sessions.length) {
      replace(sessionBox, h('div.card', { style: { marginBottom: '16px' } }, empty('Сессий записи ещё нет. Она открывается сама, когда входит стример (настройка «Моменты» во вкладке «Настройки»), по /clip start в игре, кнопкой «Сессия записи» выше или из OBS через F7.')));
      return;
    }
    const select = h('select.select', { 'aria-label': 'Сессия записи', style: { flex: '1 1 260px' }, onchange: e => { f.session = e.target.value; remember(); loadMoments(true); } },
      sessions.map(s => h('option', { value: s.id, selected: s.id === f.session },
        `${pname(s.player)} · ${fmt.dateTime(s.start)} · ${fmt.duration(s.seconds)}${s.open ? ' · идёт' : ''} · моментов: ${s.moments}`)));
    replace(sessionBox, h('div.card', { style: { marginBottom: '16px' } }, h('div.row', select)));
  }
  const session = () => sessions.find(s => s.id === f.session) || data?.session || null;
  function remember() { setParams({ w: f.minWeight === '1' ? '' : f.minWeight, all: f.everyone ? '1' : '', hidden: f.hidden ? '1' : '' }, f.session); }

  function drawHead() {
    const s = session();
    if (!s) { replace(head); head.hidden = true; return; }
    head.hidden = false;
    const exportLink = fm => h('a.btn.sm', { href: '#', download: '', title: fm.hint,
      onclick: e => { e.currentTarget.href = api.href('moments/export', { session: s.id, format: fm.value, minWeight: f.minWeight, everyone: f.everyone || null }); } },
      icon('download', 'sm'), fm.title);
    replace(head,
      h('div.row', { style: { marginBottom: '12px' } },
        s.open ? h('span.badge.good', 'идёт запись') : h('span.badge', 'закончена'),
        h('span', `${pname(s.player)}: ${fmt.dateTimeFull(s.start)}${s.end ? ' — ' + fmt.timeSec(s.end) : ''}`),
        h('span.muted', `· ${SOURCE[s.source] || s.source}${s.output ? ` (${s.output === 'stream' ? 'эфир' : s.output === 'record' ? 'запись' : s.output})` : ''}`),
        s.shift ? h('span.badge.accent', `сдвиг ${s.shift > 0 ? '+' : ''}${Math.round(s.shift / 1000)} с`) : null,
        h('button.btn.sm.ghost', { type: 'button', title: 'Сдвинуть все таймкоды, если видео началось раньше или позже', onclick: e => busy(e.currentTarget, shift) }, 'Сдвиг'),
        h('button.btn.sm.ghost', { type: 'button', title: 'Указать точное время начала видео', onclick: e => busy(e.currentTarget, setStart) }, 'Начало')),
      h('div.toolbar',
        seg(WEIGHTS, f.minWeight, v => { f.minWeight = v; remember(); loadMoments(); }, 'Наименьшая важность'),
        h('label.row', switchInput({ checked: f.everyone, label: 'Моменты всех игроков', onChange: v => { f.everyone = v; remember(); loadMoments(); } }), h('span', 'всех игроков')),
        h('label.row', switchInput({ checked: f.hidden, label: 'Показывать скрытые', onChange: v => { f.hidden = v; remember(); loadMoments(); } }), h('span', 'скрытые')),
        h('span', { style: { flex: '1 1 auto' } }),
        FORMATS.map(exportLink)));
  }

  // ---------- моменты ----------
  async function loadMoments(first = false) {
    const my = ++seq;
    drawHead();
    if (!f.session) { replace(list); replace(strip); replace(pendingBox); countEl.textContent = ''; return; }
    if (first) replace(list, skeleton(6));
    try {
      const r = await api.get('moments', { session: f.session, minWeight: f.minWeight, everyone: f.everyone || null, hidden: f.hidden || null });
      if (my !== seq) return;
      data = r;
      const i = sessions.findIndex(s => s.id === r.session?.id);
      if (i >= 0) sessions[i] = { ...sessions[i], ...r.session, moments: sessions[i].moments };
      drawHead();
      drawList();
    } catch (e) { if (my === seq) replace(list, errorBox(e, () => loadMoments(true))); }
  }

  function drawList() {
    const items = data?.items || [];
    countEl.textContent = items.length ? `${items.length} ${fmt.plural(items.length, 'момент', 'момента', 'моментов')}` : '';
    drawStrip(items);
    replace(list, items.length ? items.map(row) : empty('В этой сессии пока нет моментов выше выбранной важности.'));
    const pending = data?.pending || [];
    replace(pendingBox, pending.length ? h('p.muted', { style: { padding: '10px 16px', margin: 0 } },
      `Собирается: ${pending.map(x => `${x.title} (${stars(x.weight)})`).join('; ')}. Попадёт в список, когда закончится окно склейки.`) : null);
  }

  function drawStrip(items) {
    const s = session();
    const total = Math.max(60, s?.seconds || 0, ...items.map(m => m.timecode || 0));
    replace(strip, h('div.mstrip-track'),
      items.map(m => {
        const t = TYPES[m.type] || TYPES.manual;
        return h(`button.mstrip-dot.w${m.weight || 1}`, { type: 'button', title: `${clock(m.timecode)} ${m.title}`,
          style: { left: `${((m.timecode || 0) / total) * 100}%`, background: `var(--s${t.k})` },
          onclick: () => document.getElementById(`m-${m.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' }) });
      }),
      h('span.mstrip-end', fmt.duration(total)));
  }

  function row(m) {
    const t = TYPES[m.type] || TYPES.manual;
    const replay = m.replay && Object.values(m.replay)[0];
    return h('div.list-item.mrow', { id: `m-${m.id}`, style: m.hidden ? { opacity: 0.55 } : null },
      h(`span.kind-ico.k${t.k}`, icon(t.icon)),
      h('div.body',
        h('div.line', h('button.btn.sm.ghost.num', { type: 'button', title: 'Скопировать таймкод', style: { marginRight: '6px' }, onclick: () => copyText(clock(m.timecode)) }, clock(m.timecode)),
          h('span', { style: { color: 'var(--warn)' } }, stars(m.weight)), ' ', m.title),
        m.quote && h('div.text-2', { style: { fontStyle: 'italic', margin: '2px 0' } }, `«${m.quote}»`),
        h('div.meta', h('span', t.title), (m.players || []).map(pl => h('span', pname(pl))), m.mob && h('span', pname(m.mob)),
          m.by && h('span', `отметил ${m.by}`), m.title !== m.originalTitle && h('span', { title: m.originalTitle }, 'переименован'),
          replay && h('button.btn.sm.ghost', { type: 'button', title: replay, onclick: () => copyText(replay) }, icon('copy', 'sm'), 'файл повтора'),
          h('time', { datetime: new Date(m.start).toISOString() }, fmt.timeSec(m.start)))),
      h('div.row.mact',
        h('button.btn.icon.ghost', { type: 'button', 'aria-pressed': String(!!m.starred), title: m.starred ? 'Убрать звезду' : 'В отбор (звезда)', style: { color: m.starred ? 'var(--warn)' : null },
          onclick: e => busy(e.currentTarget, () => patch(m, { starred: !m.starred })) }, m.starred ? '★' : '☆'),
        h('button.btn.sm.ghost', { type: 'button', onclick: e => busy(e.currentTarget, () => rename(m)) }, 'Название'),
        h('button.btn.sm.ghost', { type: 'button', onclick: e => busy(e.currentTarget, () => patch(m, { hidden: !m.hidden })) }, m.hidden ? 'Вернуть' : 'Скрыть'),
        (m.sources || []).length ? h('button.btn.sm.ghost', { type: 'button', title: 'Запись в истории', onclick: () => navigate('history', m.sources[0]) }, icon('history', 'sm')) : null));
  }

  async function patch(m, change) {
    await api.patch(`moments/${encodeURIComponent(m.id)}`, change);
    await loadMoments();
  }
  async function rename(m) {
    const r = await ask({ title: 'Название момента', text: 'Так он попадёт в главы и маркеры.', fields: [{ key: 'title', title: 'Название', value: m.title }], ok: 'Сохранить' });
    if (r && r.title.trim() && r.title.trim() !== m.title) await patch(m, { title: r.title.trim() });
  }
  async function shift() {
    const s = session();
    const r = await ask({ title: 'Сдвиг таймкодов', text: 'Положительный — если видео началось раньше, чем открылась сессия; отрицательный — если позже.',
      fields: [{ key: 'sec', title: 'Секунды', type: 'number', value: String(Math.round((s.shift || 0) / 1000)) }], ok: 'Сдвинуть' });
    if (!r) return;
    await api.post(`moments/sessions/${encodeURIComponent(s.id)}`, { shift: Math.round(Number(r.sec || 0) * 1000) });
    await loadSessions();
  }
  async function setStart() {
    const s = session();
    const local = new Date(s.start - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 19);
    const r = await ask({ title: 'Начало видео', text: 'Время, когда на самом деле началась запись. Таймкоды пересчитаются.',
      fields: [{ key: 'at', title: 'Начало', type: 'datetime-local', value: local }], ok: 'Сохранить' });
    if (!r || !r.at) return;
    await api.post(`moments/sessions/${encodeURIComponent(s.id)}`, { start: new Date(r.at).getTime() });
    await loadSessions();
  }

  async function pickPlayer(title, text, ok, extra = []) {
    const players = ((await api.get('players')).players || []).filter(x => x.online);
    if (!players.length) { toast('Сейчас на сервере никого нет', 'bad'); return null; }
    const r = await ask({ title, text, ok, fields: [{ key: 'uuid', title: 'Игрок', options: players.map(x => ({ value: x.uuid, title: x.name })), value: session()?.player?.uuid }, ...extra] });
    if (!r) return null;
    const pl = players.find(x => x.uuid === r.uuid);
    return { ...r, player: { uuid: pl.uuid, name: pl.name } };
  }
  async function markNow() {
    const r = await pickPlayer('Отметить момент', 'Метка встанет на 30 секунд раньше, чем сейчас: смешное обычно уже случилось.', 'Отметить',
      [{ key: 'title', title: 'Название (можно пусто)', placeholder: 'Петя в лаве' }]);
    if (!r) return;
    await api.post('moments', { player: r.player, title: r.title.trim() || null, back: 30 });
    toast('Момент отмечен');
    await loadSessions();
  }
  async function startStop() {
    const open = sessions.filter(s => s.open);
    const r = await pickPlayer('Сессия записи', open.length ? `Идёт запись: ${open.map(s => pname(s.player)).join(', ')}.` : 'Таймкоды моментов считаются от начала сессии.',
      'Готово', [{ key: 'action', title: 'Действие', options: [{ value: 'start', title: 'Начать' }, { value: 'stop', title: 'Закончить' }], value: open.length ? 'stop' : 'start' },
        { key: 'back', title: 'Начать раньше на, с', type: 'number', value: '0' }]);
    if (!r) return;
    if (r.action === 'stop' && !await confirm({ title: 'Закончить сессию?', text: `Запись игрока ${r.player.name} закончится, моменты останутся.`, ok: 'Закончить' })) return;
    const res = await api.post('moments/sessions', { player: r.player, action: r.action, back: Number(r.back || 0) });
    f.session = res.session?.id || f.session;
    remember();
    await loadSessions();
  }

  // ---------- вживую ----------
  let timer = null;
  const soon = () => { clearTimeout(timer); timer = setTimeout(() => loadSessions().catch(toastError), 800); };
  const off = on('history', rec => { if (rec && (rec.kind === 'moment' || rec.kind === 'moment_edit' || rec.kind === 'rec_session')) soon(); });

  loadSessions();
  return {
    update(r) { if (r.id && r.id !== f.session) { f.session = r.id; drawSessions(); loadMoments(true); } },
    destroy() { off(); clearTimeout(timer); },
  };
}

/** Секунды записи → «1:02:03» / «2:03» (как в главах YouTube). */
function clock(sec) {
  const s = Math.max(0, Math.floor(sec || 0)), hh = Math.floor(s / 3600), mm = Math.floor(s / 60) % 60, ss = s % 60;
  const two = n => String(n).padStart(2, '0');
  return hh ? `${hh}:${two(mm)}:${two(ss)}` : `${mm}:${two(ss)}`;
}
