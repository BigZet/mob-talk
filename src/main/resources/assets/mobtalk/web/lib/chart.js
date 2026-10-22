// Графики на uPlot (вендорен, MIT, Leon Sorokin). Одна ось Y на график; цвета — токены --s1…--s8 в фиксированном порядке;
// подсказка с точными значениями при наведении; тема и размер меняются на лету.
import { h } from './dom.js';
import { on } from './sse.js';
import * as fmt from './fmt.js';

const charts = new Set();
const css = name => getComputedStyle(document.documentElement).getPropertyValue(name).trim();
export const color = slot => css(`--s${slot}`);

function rebuildAll() { for (const c of charts) c.rebuild(); }
on('theme', () => requestAnimationFrame(rebuildAll));
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => requestAnimationFrame(rebuildAll));

const ruMonths = ['янв', 'фев', 'мар', 'апр', 'мая', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];
function timeTicks(u, splits) {
  const span = (u.scales.x.max ?? 0) - (u.scales.x.min ?? 0);
  return splits.map(s => {
    const d = new Date(s * 1000);
    const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
    return span > 2 * 86400 ? `${d.getDate()} ${ruMonths[d.getMonth()]}` : span > 20 * 3600 && d.getHours() === 0 && d.getMinutes() === 0 ? `${d.getDate()} ${ruMonths[d.getMonth()]}` : hm;
  });
}
function tipTime(sec, daily) {
  const d = new Date(sec * 1000);
  return daily ? `${d.getDate()} ${ruMonths[d.getMonth()]} ${d.getFullYear()}` : fmt.dateTime(d.getTime());
}

/** Подсказка: время и значения всех рядов у курсора. */
function tooltipPlugin(opts) {
  let tip;
  return {
    hooks: {
      init: u => {
        tip = h('div.u-tooltip', { hidden: true });
        u.over.append(tip);
        u.over.addEventListener('mouseleave', () => (tip.hidden = true));
      },
      setCursor: u => {
        const i = u.cursor.idx;
        if (i == null || u.cursor.left < 0) { tip.hidden = true; return; }
        const rows = [];
        opts.series.forEach((s, k) => {
          const raw = s.raw ? s.raw[i] : u.data[k + 1][i];
          if (raw == null && opts.series.length > 1) return;
          rows.push(h('div.r', h('i.sw', { style: { background: s._color } }), s.label, h('b', raw == null ? '—' : (s.fmt || opts.fmt || fmt.num)(raw))));
        });
        if (opts.total) {
          const sum = opts.series.reduce((a, s, k) => a + ((s.raw ? s.raw[i] : u.data[k + 1][i]) || 0), 0);
          rows.push(h('div.r', h('span.muted', 'Всего'), h('b', (opts.fmt || fmt.num)(sum))));
        }
        tip.replaceChildren(h('div.t', tipTime(u.data[0][i], opts.daily)), ...rows);
        tip.hidden = false;
        const w = u.over.clientWidth, left = u.cursor.left, top = u.cursor.top;
        const tw = tip.offsetWidth;
        tip.style.left = `${left + 14 + tw > w ? Math.max(0, left - tw - 14) : left + 14}px`;
        tip.style.top = `${Math.max(0, Math.min(top - 10, u.over.clientHeight - tip.offsetHeight))}px`;
      },
    },
  };
}

/**
 * Линейный или столбчатый график по времени.
 * el — контейнер; opts: { t: [сек], series: [{label, data, slot?, fmt?}], fmt, height, yMin, yMax, bars, stacked, daily, area, total }
 * Возвращает { update(t, seriesData), destroy() }.
 */
export function timeChart(el, opts) {
  el.classList.add('chart');
  let u, data = buildData(opts);
  const height = opts.height || 200;

  function buildData(o) {
    if (!o.stacked) return [o.t, ...o.series.map(s => s.data)];
    // Столбцы с накоплением: рисуем от полной суммы к первому ряду, сырые значения — для подсказки.
    const acc = o.t.map(() => 0);
    const cum = o.series.map(s => s.data.map((v, i) => (acc[i] += v || 0)));
    o.series.forEach(s => (s.raw = s.data));
    return [o.t, ...cum];
  }

  function build() {
    u?.destroy();
    el.querySelector('.chart-empty')?.remove();
    const grid = css('--grid'), axis = css('--axis'), muted = css('--muted');
    const font = `11px ${css('--font')}`;
    const paths = opts.bars ? uPlot.paths.bars({ size: [0.7, 48], radius: 0.25, gap: 2 }) : undefined;
    const ser = opts.series.map((s, k) => {
      const c = (s._color = color(s.slot ?? k + 1));
      return {
        label: s.label, stroke: c, width: opts.bars ? 0 : 2, points: { show: false }, spanGaps: false, paths,
        fill: opts.bars ? c : opts.area ? `${c}22` : undefined, value: (uu, v) => (v == null ? '—' : (s.fmt || opts.fmt || fmt.num)(v)),
      };
    });
    const order = opts.stacked ? ser.map((s, i) => i).reverse() : null;
    const config = {
      width: Math.max(200, el.clientWidth), height,
      cursor: { points: { size: 7 }, drag: { x: false, y: false } },
      legend: { show: opts.series.length > 1, live: false },
      scales: { x: { time: true }, y: { range: (uu, min, max) => [opts.yMin ?? Math.min(0, min ?? 0), opts.yMax ?? (max == null || max <= 0 ? 1 : max * 1.12)] } },
      axes: [
        { stroke: muted, font, grid: { show: false }, ticks: { stroke: axis, size: 4 }, values: timeTicks, space: 64 },
        { stroke: muted, font, grid: { stroke: grid, width: 1 }, ticks: { show: false }, size: 52, space: 32, values: (uu, splits) => splits.map(v => (opts.axisFmt || opts.fmt || fmt.num)(v)) },
      ],
      series: [{}, ...(order ? order.map(i => ser[i]) : ser)],
      plugins: [tooltipPlugin(opts)],
    };
    const d = order ? [data[0], ...order.map(i => data[i + 1])] : data;
    u = new uPlot(config, d, el);
    const allEmpty = data.slice(1).every(s => s.every(v => v == null || v === 0)) || !data[0].length;
    if (allEmpty) el.append(h('div.chart-empty', opts.emptyText || 'Нет данных за этот период'));
  }

  const ro = new ResizeObserver(() => u && el.clientWidth && u.setSize({ width: el.clientWidth, height }));
  ro.observe(el);
  const handle = {
    rebuild: () => el.isConnected ? build() : handle.destroy(),
    update(next) { Object.assign(opts, next); data = buildData(opts); build(); },
    destroy() { ro.disconnect(); u?.destroy(); charts.delete(handle); },
  };
  if (typeof uPlot === 'undefined') { el.append(h('div.chart-empty', 'Библиотека графиков не загрузилась')); return handle; }
  build();
  charts.add(handle);
  return handle;
}

/** Мини-график без осей для плитки. */
export function sparkline(el, t, values, slot = 1) {
  el.classList.add('spark');
  if (typeof uPlot === 'undefined') return { destroy() {} };
  let u;
  const build = () => {
    u?.destroy();
    const c = color(slot);
    u = new uPlot({
      width: Math.max(60, el.clientWidth), height: 34, cursor: { show: false }, legend: { show: false }, select: { show: false },
      scales: { x: { time: false }, y: { range: (uu, min, max) => [min - (max - min) * 0.15 - 0.01, max + (max - min) * 0.15 + 0.01] } },
      axes: [{ show: false }, { show: false }],
      series: [{}, { stroke: c, width: 1.6, fill: `${c}1f`, points: { show: false } }],
    }, [t, values], el);
  };
  const ro = new ResizeObserver(() => u && el.clientWidth && u.setSize({ width: el.clientWidth, height: 34 }));
  ro.observe(el);
  build();
  const handle = { rebuild: () => (el.isConnected ? build() : handle.destroy()), destroy() { ro.disconnect(); u?.destroy(); charts.delete(handle); } };
  charts.add(handle);
  return handle;
}

/** Горизонтальные столбики-доли (без uPlot): rows [{label, value, hint?}]. */
export function barList(rows, { fmt: f = fmt.num, slot = 1 } = {}) {
  const max = Math.max(...rows.map(r => r.value), 0) || 1;
  return h('div.bars', rows.map((r, i) => h('div.bar-row',
    h('span.ellipsis', { title: r.label }, r.label),
    h('span.track', h('i', { style: { width: `${(r.value / max) * 100}%`, background: `var(--s${r.slot ?? slot})` } })),
    h('span.num.nowrap', f(r.value), r.hint && h('span.muted', ` · ${r.hint}`)))));
}

/** Гистограмма задержек по корзинам «до N мс». */
export function histogram(buckets, { p95 } = {}) {
  const max = Math.max(...buckets.map(b => b.n), 0) || 1;
  const label = le => (le == null ? '∞' : le >= 1000 ? `${le / 1000}с` : `${le}`);
  return h('div',
    h('div.hist', { role: 'img', 'aria-label': 'Гистограмма задержек' }, buckets.map(b => h(`div.b${p95 != null && (b.le == null || b.le > p95) ? '.p95' : ''}`, { title: `до ${b.le == null ? '∞' : fmt.ms(b.le)}: ${b.n}` },
      h('i', { style: { height: `${(b.n / max) * 100}%` } })))),
    h('div.hist-axis', buckets.map(b => h('span', label(b.le)))));
}
