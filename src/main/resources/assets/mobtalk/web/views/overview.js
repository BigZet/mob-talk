// Обзор: плитки «сейчас», графики за период, расходы по видам, лента последних событий.
import { h, icon, replace } from '../lib/dom.js';
import { api } from '../lib/api.js';
import { on } from '../lib/sse.js';
import * as fmt from '../lib/fmt.js';
import { RANGES, rangeMs, seg, load, stat, errorBox, skeleton, empty } from '../lib/ui.js';
import { timeChart, sparkline, barList } from '../lib/chart.js';
import { recordRow } from '../lib/record.js';
import { setParams } from '../app.js';

const BAR_SERIES = ['dialogues', 'cost_usd', 'llm_queue'];
const SERIES = ['tps', 'mspt', 'mspt_p95', 'mem_used', 'mem_max', 'online',
  'lat_stt_p50', 'lat_stt_p95', 'lat_llm_p50', 'lat_llm_p95', 'lat_tts_p50', 'lat_tts_p95'];
const COST_KIND = { stt: 'Распознавание', llm: 'Диалоги (LLM)', tts: 'Озвучка', jev: 'Решения Jev', witness: 'Очевидцы', village_talk: 'Разговоры в деревне' };
const int = v => (Number.isInteger(v) ? fmt.num(v) : '');
const mb = v => (v >= 1024 ? `${fmt.num(v / 1024, 1)} ГБ` : `${fmt.num(v)} МБ`);

export default function overview(root, route) {
  let range = route.params.range || '6h';
  let latKind = route.params.lat || 'llm';
  const cleanup = [];
  const charts = [], sparks = [];
  const tiles = h('div.grid.stats');
  const chartsBox = h('div.grid.two');
  const costsBox = h('div');
  const feed = h('div.list');

  replace(root,
    h('div.page-head', h('h1', 'Обзор'),
      seg(RANGES, range, v => { range = v; setParams({ range: v }); drawCharts(); }, 'Период графиков')),
    h('div.stack.lg',
      tiles,
      chartsBox,
      h('div.grid.side',
        h('section.card.flush', h('div.card-head', { style: { padding: '14px 16px 0' } }, h('h2', 'Последние события'), h('a.btn.sm.ghost', { href: '#/history' }, 'Вся история', icon('chevron', 'sm'))), feed),
        h('section.card', h('div.card-head', h('h2', 'Расходы по видам'), h('span.sub', 'за период')), costsBox))));

  // ---------- плитки ----------
  const spark = { t: [], tps: [], mspt: [] };
  let last = null;
  function drawTiles(o) {
    last = o;
    const memPct = o.memMaxMb ? o.memUsedMb / o.memMaxMb : 0;
    const tpsTone = o.tps == null ? '' : o.tps >= 19 ? 'good' : o.tps >= 15 ? 'warn' : 'bad';
    const tpsSpark = h('div'), msptSpark = h('div');
    replace(tiles,
      stat({ label: 'Онлайн', iconName: 'players', value: fmt.num(o.online), unit: o.maxPlayers ? `/ ${o.maxPlayers}` : '', hint: o.streamer ? `стример ${o.streamer.name}` : 'стример не назначен' }),
      stat({ label: 'TPS', iconName: 'live', value: fmt.num(o.tps, 1), tone: tpsTone, hint: tpsTone === 'good' ? 'норма' : tpsTone === 'warn' ? 'подтормаживает' : tpsTone ? 'сильно тормозит' : '', extra: tpsSpark }),
      stat({ label: 'MSPT', iconName: 'overview', value: fmt.num(o.mspt, 1), unit: 'мс', hint: `p95 ${fmt.num(o.msptP95, 1)} мс`, tone: o.mspt > 50 ? 'bad' : o.mspt > 40 ? 'warn' : '', extra: msptSpark }),
      stat({ label: 'Память', iconName: 'server', value: mb(o.memUsedMb), hint: `из ${mb(o.memMaxMb)}`, tone: memPct > 0.9 ? 'bad' : '',
        extra: h(`div.meter${memPct > 0.9 ? '.bad' : memPct > 0.75 ? '.warn' : ''}`, { role: 'meter', 'aria-valuenow': Math.round(memPct * 100), 'aria-valuemin': 0, 'aria-valuemax': 100, 'aria-label': 'Занято памяти' }, h('i', { style: { width: `${Math.min(100, memPct * 100)}%` } })) }),
      stat({ label: 'Диалоги', iconName: 'mobs', value: fmt.num(o.dialoguesActive), hint: `очередь ${fmt.num(o.llmQueue)} · в полёте ${fmt.num(o.llmInFlight)}` }),
      stat({ label: 'Расходы сегодня', iconName: 'costs', value: fmt.usd(o.costTodayUsd), hint: o.errorsLastHour ? `ошибок за час: ${o.errorsLastHour}` : 'ошибок за час нет' }));
    sparks.splice(0).forEach(c => c.destroy());
    if (spark.t.length > 1) { sparks.push(sparkline(tpsSpark, spark.t, spark.tps, 3), sparkline(msptSpark, spark.t, spark.mspt, 1)); }
  }
  async function loadTiles() {
    try {
      const [o, m] = await Promise.all([api.get('overview'), api.get('metrics', { series: 'tps,mspt', points: 60, from: Date.now() - 1800e3 })]);
      spark.t = m.t; spark.tps = m.series.tps; spark.mspt = m.series.mspt;
      drawTiles(o);
    } catch (e) { replace(tiles, errorBox(e, loadTiles)); }
  }
  replace(tiles, Array.from({ length: 6 }, () => h('div.card.stat', skeleton(3))));
  loadTiles();
  const tilesTimer = setInterval(() => document.visibilityState === 'visible' && api.get('overview').then(drawTiles).catch(() => {}), 30_000);
  cleanup.push(() => clearInterval(tilesTimer));
  cleanup.push(on('metrics', p => {
    if (!last || !p?.values) return;
    const v = p.values;
    spark.t = [...spark.t, p.t / 1000].slice(-180); spark.tps = [...spark.tps, v.tps].slice(-180); spark.mspt = [...spark.mspt, v.mspt].slice(-180);
    drawTiles({ ...last, tps: v.tps ?? last.tps, mspt: v.mspt ?? last.mspt, msptP95: v.mspt_p95 ?? last.msptP95, memUsedMb: v.mem_used ?? last.memUsedMb, memMaxMb: v.mem_max ?? last.memMaxMb,
      online: v.online ?? last.online, dialoguesActive: v.dialogues_active ?? last.dialoguesActive, llmQueue: v.llm_queue ?? last.llmQueue, llmInFlight: v.llm_inflight ?? last.llmInFlight });
  }));

  // ---------- графики ----------
  const cards = {};
  const chartCard = (key, title, sub) => {
    const body = h('div.chart');
    const card = h('section.card', h('div.card-head', h('h2', title), sub && h('span.sub', sub)), body);
    cards[key] = { card, body };
    return card;
  };
  const latSeg = seg([{ value: 'stt', title: 'STT' }, { value: 'llm', title: 'LLM' }, { value: 'tts', title: 'TTS' }], latKind, v => { latKind = v; setParams({ lat: v }); drawCharts(true); }, 'Вид задержки');
  replace(chartsBox,
    chartCard('tps', 'TPS', 'норма — 20'), chartCard('mspt', 'MSPT', 'мс на тик, бюджет 50'), chartCard('mem', 'Память JVM'), chartCard('online', 'Игроки онлайн'),
    chartCard('dialogues', 'Диалоги', 'начато за интервал'), chartCard('cost', 'Расходы', 'за интервал'));
  const latCard = chartCard('lat', 'Задержки');
  latCard.querySelector('.card-head').append(latSeg);
  chartsBox.append(latCard, chartCard('queue', 'Очередь LLM', 'ждут ответа'));
  let lastData = null, seq = 0;
  async function drawCharts(soft = false) {
    const my = ++seq;
    if (!soft) for (const c of Object.values(cards)) replace(c.body, skeleton(1, 160));
    try {
      const to = Date.now();
      const cached = soft && lastData && lastData.range === range;
      const [m, b] = cached ? [lastData.m, lastData.b] : await Promise.all([
        api.get('metrics', { from: to - rangeMs(range), to, points: 300, series: SERIES.join(',') }),
        api.get('metrics', { from: to - rangeMs(range), to, points: 72, series: BAR_SERIES.join(',') })]);
      if (my !== seq) return;
      lastData = { range, m, b };
      if (!root.isConnected) return;
      const s = m.series;
      charts.splice(0).forEach(c => c.destroy?.());
      const put = (key, o, src = m) => { replace(cards[key].body); charts.push(timeChart(cards[key].body, { t: src.t, ...o })); };
      put('tps', { series: [{ label: 'TPS', data: s.tps, slot: 3 }], yMin: 0, yMax: 21, fmt: v => fmt.num(v, 1), area: true });
      put('mspt', { series: [{ label: 'Среднее', data: s.mspt }, { label: 'p95', data: s.mspt_p95, slot: 2 }], yMin: 0, fmt: v => `${fmt.num(v, 1)} мс`, axisFmt: v => fmt.num(v) });
      put('mem', { series: [{ label: 'Занято', data: s.mem_used }, { label: 'Предел', data: s.mem_max, slot: 7 }], fmt: mb, axisFmt: v => (v >= 1024 ? `${fmt.num(v / 1024, 1)}Г` : fmt.num(v)) });
      put('online', { series: [{ label: 'Онлайн', data: s.online, slot: 1 }], yMin: 0, area: true, axisFmt: int });
      put('dialogues', { series: [{ label: 'Диалоги', data: b.series.dialogues, slot: 1 }], bars: true, axisFmt: int }, b);
      put('cost', { series: [{ label: 'Расходы', data: b.series.cost_usd, slot: 2 }], bars: true, fmt: fmt.usd }, b);
      put('lat', { series: [{ label: 'p50', data: s[`lat_${latKind}_p50`] }, { label: 'p95', data: s[`lat_${latKind}_p95`], slot: 2 }], yMin: 0, fmt: fmt.ms, axisFmt: v => (v >= 1000 ? `${fmt.num(v / 1000, 1)}с` : fmt.num(v)) });
      put('queue', { series: [{ label: 'В очереди', data: b.series.llm_queue, slot: 7 }], yMin: 0, bars: true, axisFmt: int }, b);
    } catch (e) {
      if (my !== seq) return;
      for (const c of Object.values(cards)) replace(c.body, errorBox(e, () => drawCharts()));
    }
    loadCosts();
  }
  function loadCosts() {
    const to = Date.now();
    load(costsBox, () => api.get('costs', { group: 'kind', from: to - rangeMs(range), to }), c => c.rows?.length
      ? [h('div.stat', { style: { padding: '0 0 12px' } }, h('div.value.num', fmt.usd(c.totalUsd)), h('div.hint', `всего за ${RANGES.find(r => r.value === range).title}`)),
        barList(c.rows.map((r, i) => ({ label: COST_KIND[r.key] || r.label || r.key, value: r.usd, slot: i + 1, hint: `${fmt.num(r.count)} шт.` })), { fmt: fmt.usd }),
        h('a.btn.sm.ghost', { href: '#/costs', style: { marginTop: '12px' } }, 'Подробнее', icon('chevron', 'sm'))]
      : empty('Расходов за период нет'));
  }
  drawCharts();
  const chartTimer = setInterval(() => { if (document.visibilityState === 'visible' && rangeMs(range) <= 6 * 3600e3) { lastData = null; drawCharts(true); } }, 60_000);
  cleanup.push(() => clearInterval(chartTimer));

  // ---------- лента ----------
  load(feed, () => api.get('history', { limit: 12 }), r => (r.items.length ? r.items.map(rec => recordRow(rec)) : empty('Событий пока нет')), { lines: 6 });
  cleanup.push(on('history', rec => {
    if (!rec || feed.querySelector('.skeleton, .error-box')) return;
    feed.querySelector('.empty')?.remove();
    feed.prepend(recordRow(rec));
    while (feed.children.length > 12) feed.lastChild.remove();
  }));

  return { destroy() { cleanup.forEach(f => f()); charts.forEach(c => c.destroy?.()); sparks.forEach(c => c.destroy()); } };
}
