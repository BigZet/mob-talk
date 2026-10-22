// Отладка: сгруппированные ошибки со стеком, хвост лога с фильтрами и автообновлением, гистограммы задержек, очередь LLM, версии модов.
import { h, icon, replace, debounce } from '../lib/dom.js';
import { api } from '../lib/api.js';
import { on } from '../lib/sse.js';
import * as fmt from '../lib/fmt.js';
import { seg, tabs, load, confirm, busy, toast, empty, errorBox, skeleton, stat, table, copyText, switchInput } from '../lib/ui.js';
import { histogram } from '../lib/chart.js';
import { setParams } from '../app.js';

const LAT = [['stt', 'Распознавание'], ['llm_first', 'LLM: первый токен'], ['llm', 'LLM целиком'], ['tts', 'Озвучка'], ['total', 'Диалог целиком']];
const LOGGERS = ['mobtalk', 'divineblock', 'rusflora', 'minecraft', 'fabric'];
const LAT_RANGES = [{ value: '1h', title: '1 ч', ms: 3600e3 }, { value: '24h', title: '24 ч', ms: 86400e3 }, { value: '7d', title: '7 д', ms: 7 * 86400e3 }];

export default function debug(root, route) {
  let tab = route.params.tab || 'errors';
  const cleanup = [];
  let inner = null;
  const tabsBox = h('div'), body = h('div');
  replace(root, h('div.page-head', h('h1', 'Отладка'),
    h('a.btn', { href: api.href('diagnostics'), download: '', title: 'Один архив: лог, ошибки, версии, настройки без секретов и история за сутки — чтобы переслать разработчику' }, icon('download', 'sm'), 'Отчёт для разработчика')), tabsBox, body);
  const drawTabs = () => replace(tabsBox, tabs([{ value: 'errors', title: 'Ошибки' }, { value: 'log', title: 'Лог' }, { value: 'latency', title: 'Задержки' }, { value: 'system', title: 'Система' }], tab, v => { tab = v; setParams({ tab: v }); drawTabs(); draw(); }));
  function draw() {
    inner?.();
    inner = { errors: errorsTab, log: logTab, latency: latencyTab, system: systemTab }[tab](body, route) || null;
  }
  drawTabs();
  draw();
  return { destroy() { inner?.(); cleanup.forEach(f => f()); } };
}

function errorsTab(body) {
  const list = h('div.stack');
  const clearBtn = h('button.btn.sm.danger', { type: 'button' }, 'Очистить всё');
  replace(body, h('div.toolbar', h('span.grow.text-2', 'Одинаковые исключения сгруппированы; показан последний стек.'), clearBtn), list);
  const reload = () => load(list, () => api.get('errors'), r => {
    const groups = (r.groups || []).sort((a, b) => b.last - a.last);
    if (!groups.length) return h('div.card', empty('Ошибок нет. Хорошо.'));
    return groups.map(g => h('article.card',
      h('div.row.nowrap', { style: { alignItems: 'flex-start' } },
        h('span.kind-ico.k8', icon('warn')),
        h('div.grow', { style: { minWidth: 0 } },
          h('div', { style: { fontWeight: 600, overflowWrap: 'anywhere' } }, g.message || g.type),
          h('div.meta.muted', { style: { fontSize: '12.5px', marginTop: '2px', display: 'flex', gap: '8px', flexWrap: 'wrap' } }, h('span.mono', g.type), g.where && h('span.mono', g.where), h('span', `впервые ${fmt.ago(g.first)}`), h('span', `последний ${fmt.ago(g.last)}`))),
        h(`span.badge${g.count > 10 ? '.bad' : '.warn'}`, { title: 'Сколько раз' }, `×${fmt.num(g.count)}`)),
      g.context && h('p.text-2', { style: { marginTop: '8px', fontSize: '13.5px' } }, g.context),
      g.stack && h('details', { style: { marginTop: '10px' } }, h('summary', 'Стек'),
        h('div.row', { style: { margin: '8px 0' } }, h('button.btn.sm', { type: 'button', onclick: () => copyText(`${g.type}: ${g.message}\n${g.stack}`) }, icon('copy', 'sm'), 'Скопировать')),
        h('pre.stack', g.stack))));
  }, { lines: 6 });
  clearBtn.addEventListener('click', async () => {
    if (await confirm({ title: 'Очистить ошибки?', consequence: 'Счётчики и стеки сбросятся. Записи «error» в истории останутся.', ok: 'Очистить', danger: true }))
      busy(clearBtn, async () => { await api.post('errors/clear'); toast('Ошибки очищены'); reload(); });
  });
  reload();
  const off = on('error-group', () => reload());
  return off;
}

function logTab(body, route) {
  const sel = new Set((route.params.loggers || '').split(',').filter(Boolean));
  let level = route.params.level || 'INFO', text = '', auto = true;
  const pre = h('div.log', { role: 'log', 'aria-label': 'Хвост лога сервера', tabindex: '0' });
  const chips = h('div.chips');
  const drawChips = () => replace(chips, LOGGERS.map(l => h('button.chip', { type: 'button', 'aria-pressed': String(sel.has(l)), onclick: () => { sel.has(l) ? sel.delete(l) : sel.add(l); setParams({ loggers: [...sel].join(',') }); drawChips(); fetchLog(true); } }, l)));
  const lvl = h('select.select', { 'aria-label': 'Уровень', style: { width: 'auto' } }, ['DEBUG', 'INFO', 'WARN', 'ERROR'].map(l => h('option', { value: l, selected: l === level }, `от ${l}`)));
  lvl.addEventListener('change', () => { level = lvl.value; setParams({ level }); fetchLog(true); });
  const find = h('input.input', { type: 'search', placeholder: 'Текст в строке', 'aria-label': 'Фильтр по тексту' });
  find.addEventListener('input', debounce(() => { text = find.value.trim().toLowerCase(); paint(); }, 150));
  const autoSw = switchInput({ checked: true, label: 'Автообновление', onChange: v => (auto = v) });
  const status = h('span.muted', { style: { fontSize: '12.5px' } });
  replace(body, h('div.card',
    h('div.toolbar', h('div.search', icon('search'), find), lvl, h('label.row', { style: { gap: '8px' } }, autoSw, h('span', 'обновлять')), status,
      h('a.btn.sm', { href: '#', download: '', onclick: e => { e.currentTarget.href = api.href('logs/export', { filter: [...sel], level }); } }, icon('download', 'sm'), 'Скачать лог')),
    h('div', { style: { marginBottom: '10px' } }, chips),
    pre));
  let lines = [];
  function paint() {
    const atBottom = pre.scrollHeight - pre.scrollTop - pre.clientHeight < 40;
    const shown = text ? lines.filter(l => `${l.logger} ${l.text}`.toLowerCase().includes(text)) : lines;
    replace(pre, shown.length ? shown.map(l => h('div', h('span.muted', l.t, ' '), h(`span.${l.level}`, l.level.padEnd(5), ' '), h('span.lg', `[${l.logger}] `), l.text)) : h('span.muted', 'Строк нет.'));
    if (atBottom) pre.scrollTop = pre.scrollHeight;
  }
  let busyFetch = false;
  async function fetchLog(first) {
    if (busyFetch) return;
    busyFetch = true;
    try {
      const r = await api.get('logs', { lines: 500, filter: [...sel], level });
      lines = r.lines || [];
      paint();
      if (first) pre.scrollTop = pre.scrollHeight;
      status.textContent = `обновлено ${fmt.timeSec(Date.now())}`;
    } catch (e) { if (first) replace(pre, errorBox(e, () => fetchLog(true))); else status.textContent = e.message; } finally { busyFetch = false; }
  }
  drawChips();
  replace(pre, skeleton(6));
  fetchLog(true);
  const timer = setInterval(() => { if (auto && document.visibilityState === 'visible') fetchLog(false); }, 3000);
  return () => clearInterval(timer);
}

function latencyTab(body, route) {
  let range = route.params.lrange || '24h';
  const grid = h('div.grid.two');
  replace(body, h('div.toolbar', h('span.grow.text-2', 'Распределение задержек; оранжевым — хвост дольше p95.'), seg(LAT_RANGES, range, v => { range = v; setParams({ lrange: v }); draw(); }, 'Период')), grid);
  function draw() {
    const to = Date.now(), from = to - LAT_RANGES.find(r => r.value === range).ms;
    replace(grid, LAT.map(([kind, title]) => {
      const box = h('div');
      load(box, () => api.get('latency', { kind, from, to }), r => r.count ? [
        h('div.row', { style: { gap: '14px', fontSize: '13px', marginBottom: '6px' } }, h('span', 'p50 ', h('b', fmt.ms(r.p50))), h('span', 'p95 ', h('b', fmt.ms(r.p95))), h('span', 'p99 ', h('b', fmt.ms(r.p99))), h('span.muted', `макс ${fmt.ms(r.max)}`)),
        histogram(r.buckets || [], { p95: r.p95 }),
      ] : empty('Замеров за период нет.'), { lines: 3 });
      return h('section.card', h('div.card-head', h('h2', title), h('span.sub', 'мс')), box);
    }));
  }
  draw();
}

function systemTab(body) {
  const tiles = h('div.grid.stats'), mods = h('div');
  replace(body, h('div.stack.lg', tiles, h('section.card.flush', h('div.card-head', { style: { padding: '14px 16px 0' } }, h('h2', 'Моды и версии')), mods)));
  const loadTiles = () => Promise.all([api.get('overview'), api.get('server')]).then(([o, s]) => replace(tiles,
    stat({ label: 'Очередь LLM', value: fmt.num(o.llmQueue), hint: 'ждут ответа', tone: o.llmQueue > 5 ? 'warn' : '' }),
    stat({ label: 'В полёте', value: fmt.num(o.llmInFlight), hint: 'запросов к LLM' }),
    stat({ label: 'Диалогов идёт', value: fmt.num(o.dialoguesActive) }),
    stat({ label: 'Аптайм', value: fmt.duration(s.uptimeSeconds) }),
    stat({ label: 'Mob Talk', value: s.version, hint: `Minecraft ${s.minecraft} · Fabric ${s.loader}` }),
    stat({ label: 'Ошибок за час', value: fmt.num(o.errorsLastHour), tone: o.errorsLastHour ? 'warn' : '' }))).catch(e => replace(tiles, errorBox(e, loadTiles)));
  loadTiles();
  load(mods, () => api.get('server'), s => table([
    { key: 'name', title: 'Мод', value: m => m.name.toLowerCase(), render: m => h('b', m.name) },
    { key: 'id', title: 'ID', render: m => h('span.mono', m.id) },
    { key: 'version', title: 'Версия', render: m => h('span.mono', m.version) },
  ], s.mods || [], { sort: { key: 'name', dir: 1 } }), { lines: 6 });
  const timer = setInterval(() => document.visibilityState === 'visible' && loadTiles(), 10_000);
  return () => clearInterval(timer);
}
