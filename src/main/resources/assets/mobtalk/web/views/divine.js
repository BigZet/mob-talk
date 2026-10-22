// Бог (Божественный блок): души с грехами, лестницей кар, проклятиями и баном; живые боссы; вердикты; статистика кар;
// действия источника divineblock. Без мода в сборке API отвечает 503 — показываем это спокойно.
import { h, icon, replace, avatar } from '../lib/dom.js';
import { api } from '../lib/api.js';
import { on } from '../lib/sse.js';
import * as fmt from '../lib/fmt.js';
import { RANGES, rangeMs, seg, load, confirm, busy, toast, empty, errorBox, skeleton, table } from '../lib/ui.js';
import { timeChart } from '../lib/chart.js';
import { recordRow, pname } from '../lib/record.js';
import { drawActions } from './settings.js';
import { setParams } from '../app.js';

const ACT = { wrath: 'Кары', miracle: 'Блага', none: 'Отказы' };

export default function divine(root, route) {
  let range = route.params.range || '7d';
  const cleanup = [], charts = [];
  let actions = [];
  const soulsBox = h('div.card.flush'), activeBox = h('div'), verdicts = h('div.list'), statBox = h('div.chart'), actionsBox = h('div');
  replace(root, h('div.page-head', h('h1', 'Бог')), h('div', skeleton(4)));

  // Сначала узнаём, есть ли мод: один запрос, 503 — понятная плашка вместо раздела.
  Promise.all([api.get('divine/souls'), api.get('sources/divineblock/actions').catch(() => ({ actions: [] }))]).then(([first, act]) => {
    actions = act.actions || [];
    replace(root,
      h('div.page-head', h('h1', 'Бог')),
      h('div.stack.lg',
        activeBox,
        h('section', h('h2.section-title', { style: { marginBottom: '12px' } }, 'Души'), soulsBox),
        h('div.grid.two',
          h('section.card', h('div.card-head', h('h2', 'Кары и блага'), seg(RANGES.slice(2), range, v => { range = v; setParams({ range: v }); drawStats(); }, 'Период')), statBox),
          h('section.card.flush', h('div.card-head', { style: { padding: '14px 16px 0' } }, h('h2', 'Последние вердикты'), h('a.btn.sm.ghost', { href: '#/history?kind=verdict' }, 'Все', icon('chevron', 'sm'))), verdicts)),
        actionsBox));
    drawSouls(first);
    drawActive();
    drawStats();
    load(verdicts, () => api.get('history', { kind: 'verdict', limit: 10 }), r => (r.items.length ? r.items.map(x => recordRow(x)) : empty('Вердиктов ещё не было.')), { lines: 5 });
    drawActions({ id: 'divineblock', title: 'Божественный блок' }, actions, actionsBox);
  }).catch(e => replace(root, h('div.page-head', h('h1', 'Бог')), errorBox(e.status === 503 ? { status: 503, message: 'Божественного блока нет в этой сборке или он ещё не подключён к админке.' } : e, () => divine(root, route))));

  // ---------- души ----------
  function drawSouls(r) {
    const souls = r.souls || [];
    replace(soulsBox, souls.length ? table([
      { key: 'player', title: 'Игрок', value: s => pname(s.player).toLowerCase(), render: s => h('div.row.nowrap', avatar(pname(s.player)), h('div', h('div', { style: { fontWeight: 600 } }, pname(s.player)), s.banned && h('span.badge.bad', 'изгнан навечно'))) },
      { key: 'sins', title: 'Грехи', r: true, desc: true, render: s => h(`span.badge${s.sins > 5 ? '.bad' : s.sins ? '.warn' : ''}`, fmt.num(s.sins)) },
      { key: 'ladder', title: 'Лестница кар', desc: true, render: s => h('div.row.nowrap', { title: `ступень ${s.ladder}`, 'aria-label': `ступень ${s.ladder}` }, h('div.steps', { style: { width: '90px' } }, Array.from({ length: 6 }, (_, i) => h(`i${i < s.ladder ? '.done' : ''}`))), h('span.num.muted', s.ladder)) },
      { key: 'curses', title: 'Проклятия', sortable: false, render: s => (s.curses?.length ? h('div.chips', s.curses.map(c => h('span.badge.warn', c.title, ' ', h('span.num.countdown', { dataset: { until: c.until } }, fmt.countdown(c.until))))) : h('span.muted', 'нет')) },
      { key: 'act', title: '', sortable: false, render: s => soulActions(s) },
    ], souls, { sort: { key: 'sins', dir: -1 } }) : empty('Душ пока нет: никто не молился.'));
  }
  function soulActions(s) {
    const perPlayer = actions.filter(a => a.args?.some(x => x.key === 'player'));
    if (!perPlayer.length) return null;
    return h('div.row.nowrap', perPlayer.filter(a => a.id !== 'unban' || s.banned).map(a => {
      const btn = h(`button.btn.sm${a.danger ? '.danger' : ''}`, { type: 'button', onclick: async e => {
        e.stopPropagation();
        if (!(await confirm({ title: `${a.title}: ${pname(s.player)}?`, consequence: a.danger || 'Подействует сразу.', ok: a.title, danger: !!a.danger }))) return;
        busy(btn, async () => { const r = await api.post(`sources/divineblock/actions/${encodeURIComponent(a.id)}`, { args: { player: pname(s.player) } }); toast(r.message || 'Готово'); reloadSouls(); drawActive(); });
      } }, a.title);
      return btn;
    }));
  }
  const reloadSouls = () => api.get('divine/souls').then(drawSouls).catch(e => replace(soulsBox, errorBox(e, reloadSouls)));

  // ---------- активное ----------
  function drawActive() {
    load(activeBox, () => api.get('divine/active'), a => {
      const bosses = a.bosses || [], curses = a.curses || [];
      return h('div.grid.two',
        h('section.card', h('div.card-head', h('h2', 'Живые боссы'), h('span.badge', bosses.length)),
          bosses.length ? h('div.stack', bosses.map(b => {
            const pct = b.maxHealth ? b.health / b.maxHealth : 0;
            return h('div', h('div.row', h('b.grow', b.title), h('span.muted', { style: { fontSize: '13px' } }, `${fmt.num(b.health)} / ${fmt.num(b.maxHealth)} · ${fmt.ago(b.since)}`)),
              h(`div.meter${pct < 0.3 ? '.bad' : ''}`, { style: { marginTop: '6px', height: '10px' }, role: 'meter', 'aria-label': `Здоровье ${b.title}`, 'aria-valuenow': Math.round(pct * 100), 'aria-valuemin': 0, 'aria-valuemax': 100 }, h('i', { style: { width: `${pct * 100}%`, background: 'var(--s8)' } })));
          })) : h('p.muted', 'Боссов нет — бог спокоен.')),
        h('section.card', h('div.card-head', h('h2', 'Проклятия сейчас'), h('span.badge', curses.length)),
          curses.length ? h('div.list', curses.map(c => h('div.list-item', { style: { padding: '8px 0' } }, avatar(pname(c.player)), h('div.body', h('div', { style: { fontWeight: 600 } }, c.title), h('div.meta', pname(c.player))),
            h('span.badge.warn.num.countdown', { dataset: { until: c.until } }, fmt.countdown(c.until))))) : h('p.muted', 'Никто не проклят.')));
    }, { lines: 4 });
  }
  const timer = setInterval(() => root.querySelectorAll('.countdown').forEach(el => (el.textContent = fmt.countdown(+el.dataset.until))), 1000);
  cleanup.push(() => clearInterval(timer));

  // ---------- статистика ----------
  async function drawStats() {
    charts.splice(0).forEach(c => c.destroy());
    replace(statBox, skeleton(1, 180));
    try {
      const to = Date.now(), bucket = rangeMs(range) > 2 * 86400e3 ? 'day' : 'hour';
      const r = await api.get('history/stats', { kind: 'verdict', by: 'data.act', bucket, from: to - rangeMs(range), to });
      replace(statBox);
      const keys = Object.keys(r.series || {}).sort((a, b) => Object.keys(ACT).indexOf(a) - Object.keys(ACT).indexOf(b));
      const slot = { wrath: 8, miracle: 3, none: 7 };
      charts.push(timeChart(statBox, { t: r.t || [], series: keys.map(k => ({ label: ACT[k] || k, data: r.series[k], slot: slot[k] })), bars: true, stacked: true, total: true, daily: bucket === 'day', yMin: 0, emptyText: 'Вердиктов за период нет' }));
    } catch (e) { replace(statBox, errorBox(e, drawStats)); }
  }

  cleanup.push(on('history', rec => { if (rec?.kind === 'verdict' || rec?.kind === 'boss' || rec?.kind === 'ban') { if (soulsBox.isConnected) { reloadSouls(); drawActive(); } } }));
  return { destroy() { cleanup.forEach(f => f()); charts.forEach(c => c.destroy()); } };
}
