// Расходы: по дням, игрокам, видам и моделям за период; рядом — лимиты, которые можно поправить сразу.
import { h, icon, replace } from '../lib/dom.js';
import { api } from '../lib/api.js';
import * as fmt from '../lib/fmt.js';
import { seg, load, busy, toast, empty, errorBox, skeleton, table, stat } from '../lib/ui.js';
import { timeChart, barList } from '../lib/chart.js';
import { fieldControl } from './settings.js';
import { setParams } from '../app.js';
import { dayToSec } from './players.js';

const PERIODS = [{ value: '7d', title: '7 д', ms: 7 * 86400e3 }, { value: '30d', title: '30 д', ms: 30 * 86400e3 }, { value: '90d', title: '90 д', ms: 90 * 86400e3 }];
const KIND = { stt: 'Распознавание (STT)', llm: 'Диалоги (LLM)', tts: 'Озвучка (TTS)', jev: 'Решения Jev', witness: 'Очевидцы', village_talk: 'Разговоры в деревне' };
const LIMITS = [
  ['perPlayerDayUsd', 'На игрока в день', v => (v ? fmt.usd(v) : 'без лимита')],
  ['serverDayUsd', 'На сервер в день', v => (v ? fmt.usd(v) : 'без лимита')],
  ['perPlayerMinute', 'Запросов игрока в минуту', v => (v ? fmt.num(v) : 'без лимита')],
  ['maxDialoguesPerPlayer', 'Диалогов одновременно у игрока', v => (v ? fmt.num(v) : 'без лимита')],
];

export default function costs(root, route) {
  let period = route.params.period || '30d';
  const charts = [];
  const tiles = h('div.grid.stats.four'), dayBox = h('div.chart.tall'), playersBox = h('div'), kindBox = h('div'), modelBox = h('div'), limitsBox = h('div');
  replace(root,
    h('div.page-head', h('h1', 'Расходы'), seg(PERIODS, period, v => { period = v; setParams({ period: v }); draw(); }, 'Период')),
    h('div.stack.lg',
      tiles,
      h('section.card', h('div.card-head', h('h2', 'По дням')), dayBox),
      h('div.grid.side',
        h('section.card.flush', h('div.card-head', { style: { padding: '14px 16px 0' } }, h('h2', 'По игрокам')), playersBox),
        h('section.card', h('div.card-head', h('h2', 'Лимиты'), h('a.btn.sm.ghost', { href: '#/settings?source=mobtalk&q=лимит' }, 'Все настройки', icon('chevron', 'sm'))), limitsBox)),
      h('div.grid.two',
        h('section.card', h('div.card-head', h('h2', 'По видам')), kindBox),
        h('section.card.flush', h('div.card-head', { style: { padding: '14px 16px 0' } }, h('h2', 'По моделям')), modelBox))));

  function draw() {
    const to = Date.now(), from = to - PERIODS.find(p => p.value === period).ms;
    const get = group => api.get('costs', { group, from, to });
    charts.splice(0).forEach(c => c.destroy());
    replace(tiles, Array.from({ length: 4 }, () => h('div.card.stat', skeleton(2))));
    replace(dayBox, skeleton(1, 200));
    get('day').then(r => {
      const rows = r.rows || [];
      const days = rows.length || 1, total = r.totalUsd || 0, count = rows.reduce((a, x) => a + (x.count || 0), 0);
      const today = rows.find(x => x.key === new Date().toISOString().slice(0, 10).replaceAll('-', ''));
      replace(tiles,
        stat({ label: 'Всего за период', iconName: 'costs', value: fmt.usd(total), hint: `${fmt.num(count)} ${fmt.plural(count, 'запрос', 'запроса', 'запросов')}` }),
        stat({ label: 'В среднем в день', value: fmt.usd(total / days), hint: `за ${days} ${fmt.plural(days, 'день', 'дня', 'дней')} с расходами` }),
        stat({ label: 'Сегодня', value: fmt.usd(today?.usd || 0), hint: r.limits?.serverDayUsd ? `лимит сервера ${fmt.usd(r.limits.serverDayUsd)}` : 'лимит сервера не задан',
          tone: r.limits?.serverDayUsd && today?.usd > r.limits.serverDayUsd * 0.8 ? 'warn' : '' }),
        stat({ label: 'Дороже всего', value: (() => { const m = rows.reduce((a, x) => (x.usd > (a?.usd ?? -1) ? x : a), null); return m ? fmt.usd(m.usd) : '—'; })(), hint: (() => { const m = rows.reduce((a, x) => (x.usd > (a?.usd ?? -1) ? x : a), null); return m ? m.label || fmt.dayKey(m.key) : ''; })() }));
      replace(dayBox);
      const sorted = [...rows].sort((a, b) => dayToSec(a.key) - dayToSec(b.key));
      charts.push(timeChart(dayBox, { t: sorted.map(x => dayToSec(x.key)), series: [{ label: 'Расходы', data: sorted.map(x => x.usd), slot: 2 }], bars: true, daily: true, fmt: fmt.usd, height: 240 }));
      drawLimits(r.limits || {});
    }).catch(e => { replace(tiles); replace(dayBox, errorBox(e, draw)); });
    load(playersBox, () => get('player'), r => (r.rows?.length ? table([
      { key: 'label', title: 'Игрок', value: x => String(x.label || x.key).toLowerCase(), render: x => h('a', { href: `#/players/${encodeURIComponent(x.key)}` }, x.label || x.key) },
      { key: 'count', title: 'Запросов', r: true, desc: true, render: x => fmt.num(x.count) },
      { key: 'usd', title: 'Сумма', r: true, desc: true, render: x => h('b', fmt.usd(x.usd)) },
      { key: 'share', title: 'Доля', r: true, desc: true, value: x => x.usd, render: x => `${fmt.num((x.usd / (r.totalUsd || 1)) * 100)}%` },
    ], r.rows, { sort: { key: 'usd', dir: -1 } }) : empty('Расходов нет.')), { lines: 5 });
    load(kindBox, () => get('kind'), r => (r.rows?.length ? barList(r.rows.map((x, i) => ({ label: KIND[x.key] || x.label || x.key, value: x.usd, slot: i + 1, hint: `${fmt.num(x.count)} шт.` })), { fmt: fmt.usd }) : empty('Расходов нет.')));
    load(modelBox, () => get('model'), r => (r.rows?.length ? table([
      { key: 'label', title: 'Модель', value: x => String(x.label || x.key), render: x => h('span.mono', x.label || x.key) },
      { key: 'count', title: 'Вызовов', r: true, desc: true, render: x => fmt.num(x.count) },
      { key: 'units', title: 'Единиц', r: true, desc: true, render: x => fmt.num(x.units) },
      { key: 'usd', title: 'Сумма', r: true, desc: true, render: x => h('b', fmt.usd(x.usd)) },
    ], r.rows, { sort: { key: 'usd', dir: -1 } }) : empty('Расходов нет.')), { lines: 5 });
  }

  // Лимиты — обычные настройки источника mobtalk: находим поля по имени и правим на месте.
  async function drawLimits(limits) {
    replace(limitsBox, skeleton(4));
    let fields = [], values = {};
    try {
      const [s, v] = await Promise.all([api.get('sources'), api.get('sources/mobtalk/values')]);
      fields = (s.sources || []).find(x => x.id === 'mobtalk')?.groups.flatMap(g => g.fields) || [];
      values = v.values || {};
    } catch { /* правка недоступна — покажем значения */ }
    const draft = {};
    const save = h('button.btn.primary.sm', { type: 'button', hidden: true }, 'Применить');
    const rows = LIMITS.map(([k, title, show]) => {
      const f = fields.find(x => x.key === k || x.key.toLowerCase().endsWith(k.toLowerCase()));
      if (!f) return h('div.row', { style: { padding: '8px 0', borderBottom: '1px solid var(--border)' } }, h('span.grow', title), h('b', show(limits[k])));
      const ctl = fieldControl(f, values[f.key], v => { if (v === values[f.key]) delete draft[f.key]; else draft[f.key] = v; save.hidden = !Object.keys(draft).length; });
      return h('div.stack', { style: { gap: '6px', padding: '8px 0', borderBottom: '1px solid var(--border)' } }, h('div.row', h('span.grow', f.title || title, f.unit && h('span.muted', ` (${f.unit})`)), h('span.muted', { style: { fontSize: '12.5px' } }, '0 — без лимита')), ctl.el);
    });
    save.addEventListener('click', () => busy(save, async () => {
      const r = await api.patch('sources/mobtalk/values', { values: draft });
      for (const k of r.applied || []) { values[k] = draft[k]; delete draft[k]; }
      save.hidden = !Object.keys(draft).length;
      if (Object.keys(r.errors || {}).length) toast(Object.values(r.errors).join('; '), 'bad'); else toast('Лимиты применены');
    }));
    replace(limitsBox, h('div', rows), h('div.row', { style: { justifyContent: 'flex-end', marginTop: '12px' } }, save));
  }

  draw();
  return { destroy() { charts.forEach(c => c.destroy()); } };
}
