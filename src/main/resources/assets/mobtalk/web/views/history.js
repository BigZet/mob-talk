// История: единый поиск по всем видам записей с фильтрами в адресе, бесконечная прокрутка, карточка записи с трассой,
// экспорт CSV/JSONL, новые записи вживую.
import { h, icon, replace, debounce } from '../lib/dom.js';
import { api } from '../lib/api.js';
import { on } from '../lib/sse.js';
import * as fmt from '../lib/fmt.js';
import { seg, busy, empty, errorBox, skeleton, toastError } from '../lib/ui.js';
import { KINDS, recordRow, openRecord } from '../lib/record.js';
import { navigate, setParams, parseRoute } from '../app.js';

const PERIODS = [
  { value: '1h', title: '1 ч', ms: 3600e3 }, { value: '24h', title: '24 ч', ms: 86400e3 }, { value: '7d', title: '7 д', ms: 7 * 86400e3 },
  { value: '30d', title: '30 д', ms: 30 * 86400e3 }, { value: 'all', title: 'Всё', ms: null },
];
const KIND_GROUPS = [
  ['dialogue', 'witness', 'village_talk', 'gift', 'trade', 'relation_change', 'rumor', 'scene', 'wedding'],
  ['verdict', 'ban', 'boss'],
  ['moment', 'rec_session'],
  ['player_join', 'player_leave', 'death', 'command', 'config_change', 'admin_login', 'error', 'cost'],
];

export default function historyView(root, route) {
  const p = route.params;
  const f = { kind: p.kind ? p.kind.split(',') : [], player: p.player || '', mob: p.mob || '', q: p.q || '', period: p.period || '7d' };
  const list = h('div.list', { 'aria-live': 'off' });
  const more = h('div.more');
  const fresh = h('button.btn.sm.primary', { type: 'button', hidden: true, style: { position: 'sticky', top: 'calc(var(--top-h) + 8px)', zIndex: 3, margin: '0 auto 8px', display: 'flex' } });
  const countEl = h('span.sub');
  let cursor = null, loading = false, seq = 0, pending = [];

  const qInput = h('input.input', { type: 'search', placeholder: 'Текст: вопрос, ответ, ошибка…', value: f.q, 'aria-label': 'Поиск по тексту' });
  const playerInput = h('input.input', { type: 'search', placeholder: 'Игрок', value: f.player, 'aria-label': 'Игрок: ник или UUID', style: { flex: '0 1 160px' } });
  const mobInput = h('input.input', { type: 'search', placeholder: 'Моб', value: f.mob, 'aria-label': 'Моб: имя или UUID', style: { flex: '0 1 160px' } });
  const chips = h('div.chips', { role: 'group', 'aria-label': 'Виды записей' });
  const drawChips = () => replace(chips,
    h('button.chip', { type: 'button', 'aria-pressed': String(!f.kind.length), onclick: () => { f.kind = []; changed(); } }, 'Все виды'),
    KIND_GROUPS.flat().map(k => h('button.chip', { type: 'button', 'aria-pressed': String(f.kind.includes(k)), onclick: () => { f.kind = f.kind.includes(k) ? f.kind.filter(x => x !== k) : [...f.kind, k]; changed(); } },
      KINDS[k].k && h('span.dot', { style: { background: `var(--s${KINDS[k].k})` } }), KINDS[k].title)));
  const exportLink = fmtName => h('a.btn.sm', { href: '#', download: '', onclick: e => { e.currentTarget.href = api.href('history/export', { format: fmtName, ...params() }); } }, icon('download', 'sm'), fmtName.toUpperCase());

  replace(root,
    h('div.page-head', h('h1', 'История'), exportLink('csv'), exportLink('jsonl')),
    h('div.card', { style: { marginBottom: '16px' } },
      h('div.toolbar', h('div.search', icon('search'), qInput), playerInput, mobInput,
        seg(PERIODS, f.period, v => { f.period = v; changed(); }, 'Период')),
      chips),
    fresh,
    h('section.card.flush', h('div.card-head', { style: { padding: '14px 16px 0' } }, h('h2', 'Записи'), countEl), list, more));

  function params() {
    const per = PERIODS.find(x => x.value === f.period);
    return { kind: f.kind, player: f.player, mob: f.mob, q: f.q, from: per?.ms ? Date.now() - per.ms : null };
  }
  function changed() {
    setParams({ kind: f.kind.join(','), player: f.player, mob: f.mob, q: f.q, period: f.period });
    drawChips();
    page(true);
  }
  const typed = debounce(() => { f.q = qInput.value.trim(); f.player = playerInput.value.trim(); f.mob = mobInput.value.trim(); changed(); }, 300);
  [qInput, playerInput, mobInput].forEach(i => i.addEventListener('input', typed));

  let lastDay = null;
  function appendItems(items) {
    for (const rec of items) {
      const dk = new Date(rec.t).toDateString();
      if (dk !== lastDay) { lastDay = dk; list.append(h('div.list-item', { style: { padding: '8px 16px', background: 'var(--surface-2)', fontSize: '12.5px', fontWeight: 600, color: 'var(--text-2)' } }, fmt.day(rec.t), ', ', new Date(rec.t).toLocaleDateString('ru-RU', { weekday: 'long' }))); }
      list.append(recordRow(rec, { onOpen: r => navigate('history', r.id, parseRoute().params) }));
    }
  }
  async function page(first) {
    const my = first ? ++seq : seq;
    if (first) { cursor = null; lastDay = null; pending = []; fresh.hidden = true; replace(list, skeleton(8)); replace(more); countEl.textContent = ''; }
    loading = true;
    try {
      const r = await api.get('history', { ...params(), cursor, limit: 50 });
      if (my !== seq) return;
      if (first) replace(list);
      appendItems(r.items || []);
      if (first && !r.items?.length) list.append(empty('Ничего не найдено. Попробуйте снять фильтры или расширить период.'));
      cursor = r.next;
      countEl.textContent = `показано ${list.querySelectorAll('button.list-item').length}${cursor ? '+' : ''}`;
      replace(more, cursor ? h('button.btn', { type: 'button', onclick: e => busy(e.currentTarget, () => page(false)) }, 'Показать ещё') : list.children.length > 1 ? h('span.muted', 'Это всё.') : null);
    } catch (e) {
      if (my !== seq) return;
      if (first) replace(list, errorBox(e, () => page(true))); else toastError(e);
    } finally { loading = false; }
  }
  const io = new IntersectionObserver(es => { if (es.some(e => e.isIntersecting) && cursor && !loading) page(false); }, { rootMargin: '400px' });
  io.observe(more);

  const off = on('history', rec => {
    if (!rec) return;
    const pl = f.player.toLowerCase(), mb = f.mob.toLowerCase();
    if (f.kind.length && !f.kind.includes(rec.kind)) return;
    if (pl && ![rec.player?.name, rec.player?.uuid].some(x => String(x || '').toLowerCase() === pl)) return;
    if (mb && ![rec.mob?.name, rec.mob?.uuid].some(x => String(x || '').toLowerCase() === mb)) return;
    if (f.q && !String(rec.text || '').toLowerCase().includes(f.q.toLowerCase())) return;
    pending.unshift(rec);
    fresh.hidden = false;
    fresh.textContent = `Новых записей: ${pending.length} — показать`;
  });
  fresh.addEventListener('click', () => { window.scrollTo({ top: 0, behavior: 'smooth' }); page(true); });

  drawChips();
  page(true);

  let open = null;
  function update(r) {
    if (open && open.id !== r.id) { open.d.close(); open = null; }
    if (r.id && !open) {
      const d = openRecord({ id: r.id, kind: '' });
      d.dlg.addEventListener('close', () => { open = null; setTimeout(() => { if (location.hash.startsWith(`#/history/${encodeURIComponent(r.id)}`)) navigate('history', null, r.params); }); });
      open = { id: r.id, d };
    }
  }
  update(route);
  return { update, destroy() { io.disconnect(); off(); open?.d.close(); } };
}
