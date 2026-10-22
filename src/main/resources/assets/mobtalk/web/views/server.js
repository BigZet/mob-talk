// Сервер: консоль команд (история, автодополнение корней, подтверждение), резервные копии, перечитать конфиг, сведения о сборке.
import { h, icon, replace } from '../lib/dom.js';
import { api } from '../lib/api.js';
import { on } from '../lib/sse.js';
import * as fmt from '../lib/fmt.js';
import { load, confirm, busy, toast, toastError, empty, kv, switchInput, copyText } from '../lib/ui.js';

const HIST_KEY = 'mt-console-history';
const store = {
  get(k, d) { try { return JSON.parse(localStorage.getItem(k)) ?? d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* без хранилища история живёт до перезагрузки */ } },
};

export default function server(root) {
  const cleanup = [];
  const consoleBox = h('section.card'), backups = h('div'), info = h('div');
  replace(root,
    h('div.page-head', h('h1', 'Сервер')),
    h('div.grid.side', consoleBox, h('div.stack.lg',
      h('section.card', h('div.card-head', h('h2', 'Резервные копии')), backups),
      h('section.card', h('div.card-head', h('h2', 'Сборка')), info))));
  drawConsole(consoleBox);
  drawBackups(backups);
  drawInfo(info);
  cleanup.push(on('backup', () => drawBackups(backups)));
  return { destroy() { cleanup.forEach(f => f()); } };
}

function drawConsole(box) {
  let roots = [];
  api.get('server/commands').then(r => (roots = r.roots || [])).catch(() => {});
  const hist = store.get(HIST_KEY, []);
  let hi = hist.length;
  let ask = true;
  try { ask = sessionStorage.getItem('mt-console-ask') !== 'no'; } catch { /* по умолчанию спрашиваем */ }
  const out = h('div.console-out', { role: 'log', 'aria-label': 'Результаты команд' }, empty('Команды выполняются от имени консоли сервера. Слэш в начале не нужен.'));
  const input = h('input.input', { type: 'text', placeholder: 'say Привет всем', autocomplete: 'off', spellcheck: 'false', 'aria-label': 'Команда', 'aria-autocomplete': 'list', 'aria-controls': 'cmd-suggest' });
  const suggest = h('div.suggest#cmd-suggest', { role: 'listbox', hidden: true });
  const run = h('button.btn.primary', { type: 'submit' }, icon('play', 'sm'), 'Выполнить');
  let sIdx = -1, sItems = [];
  const paintSuggest = () => {
    const word = input.value.replace(/^\//, '');
    sItems = word && !word.includes(' ') ? roots.filter(r => r.startsWith(word) && r !== word).slice(0, 8) : [];
    suggest.hidden = !sItems.length;
    sIdx = Math.min(sIdx, sItems.length - 1);
    replace(suggest, sItems.map((r, i) => h('button', { type: 'button', role: 'option', 'aria-selected': String(i === sIdx), tabindex: '-1', onmousedown: e => { e.preventDefault(); pickSuggest(r); } }, r)));
  };
  const pickSuggest = r => { input.value = `${r} `; sIdx = -1; paintSuggest(); input.focus(); };
  input.addEventListener('input', () => { sIdx = -1; paintSuggest(); });
  input.addEventListener('blur', () => setTimeout(() => (suggest.hidden = true), 100));
  input.addEventListener('keydown', e => {
    if (!suggest.hidden && sItems.length) {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); sIdx = (sIdx + (e.key === 'ArrowDown' ? 1 : sItems.length - 1) + (sIdx < 0 && e.key === 'ArrowUp' ? 1 : 0)) % sItems.length; paintSuggest(); return; }
      if ((e.key === 'Tab' || e.key === 'Enter') && sIdx >= 0) { e.preventDefault(); pickSuggest(sItems[sIdx]); return; }
      if (e.key === 'Tab') { e.preventDefault(); pickSuggest(sItems[0]); return; }
      if (e.key === 'Escape') { suggest.hidden = true; return; }
    }
    if (e.key === 'ArrowUp' && hist.length) { e.preventDefault(); hi = Math.max(0, hi - 1); input.value = hist[hi] || ''; }
    if (e.key === 'ArrowDown' && hist.length) { e.preventDefault(); hi = Math.min(hist.length, hi + 1); input.value = hist[hi] || ''; }
  });
  const askSw = switchInput({ checked: ask, label: 'Спрашивать подтверждение', onChange: v => { ask = v; try { sessionStorage.setItem('mt-console-ask', v ? 'yes' : 'no'); } catch { /* только на эту вкладку */ } } });
  const form = h('form.console-form', {
    onsubmit: async e => {
      e.preventDefault();
      const cmd = input.value.trim().replace(/^\//, '');
      if (!cmd) return;
      if (ask && !(await confirm({ title: 'Выполнить команду?', text: `/${cmd}`, consequence: 'Команда выполнится от имени консоли сервера с полными правами.', ok: 'Выполнить' }))) return;
      busy(run, async () => {
        const entry = h('div.console-entry', h('div.cmd', h('span.muted', fmt.timeSec(Date.now())), h('span.grow', `/${cmd}`)), h('pre.muted', '…'));
        out.querySelector('.empty')?.remove();
        out.append(entry);
        out.scrollTop = out.scrollHeight;
        if (hist[hist.length - 1] !== cmd) { hist.push(cmd); if (hist.length > 100) hist.shift(); store.set(HIST_KEY, hist); }
        hi = hist.length;
        input.value = '';
        try {
          const r = await api.post('server/command', { command: cmd });
          const text = (r.output || []).join('\n') || (r.ok ? 'Готово, без вывода.' : 'Команда не выполнена.');
          entry.querySelector('.cmd').append(h(`span.badge${r.ok ? '.good' : '.bad'}`, r.ok ? `ок${r.result != null ? ' · ' + r.result : ''}` : 'ошибка'),
            h('button.btn.icon.sm.ghost', { type: 'button', 'aria-label': 'Скопировать вывод', onclick: () => copyText(text) }, icon('copy', 'sm')));
          replace(entry.querySelector('pre'), text);
          entry.querySelector('pre').classList.remove('muted');
        } catch (ex) {
          entry.querySelector('.cmd').append(h('span.badge.bad', 'ошибка'));
          replace(entry.querySelector('pre'), ex.message);
          throw ex;
        } finally { out.scrollTop = out.scrollHeight; }
      });
    },
  }, suggest, h('div.grow', { style: { position: 'relative' } }, input), run);
  replace(box,
    h('div.card-head', h('h2', 'Консоль'), h('label.row', { style: { gap: '8px', fontSize: '13px' } }, askSw, h('span.text-2', 'спрашивать'))),
    h('div.stack', out, form,
      h('div.row', { style: { gap: '6px' } }, h('span.muted', { style: { fontSize: '12.5px' } }, 'Быстро:'),
        ['list', 'mobtalk budget report', 'divineblock list', 'save-all'].map(c => h('button.chip', { type: 'button', onclick: () => { input.value = c; input.focus(); } }, c)))));
}

function drawBackups(box) {
  const create = h('button.btn.primary', { type: 'button' }, 'Создать копию');
  create.addEventListener('click', async () => {
    if (!(await confirm({ title: 'Создать резервную копию мира?', consequence: 'Сервер сохранит мир и запишет архив, не останавливаясь. Во время записи автосохранение выключено, возможна короткая задержка.', ok: 'Создать' }))) return;
    busy(create, async () => { const r = await api.post('server/backup'); toast(`Копия готова: ${r.backup?.name || ''}`); drawBackups(box); });
  });
  const reloadCfg = h('button.btn', { type: 'button' }, icon('refresh', 'sm'), 'Перечитать конфиг');
  reloadCfg.addEventListener('click', async () => {
    if (await confirm({ title: 'Перечитать mobtalk-server.json?', consequence: 'Правки, сделанные в файле вручную, применятся сразу. Несохранённые изменения в админке не потеряются — они уже в файле.', ok: 'Перечитать' }))
      busy(reloadCfg, async () => { await api.post('server/reload-config'); toast('Конфиг перечитан'); });
  });
  const list = h('div');
  replace(box, h('div.row', { style: { marginBottom: '12px' } }, create, reloadCfg), list);
  load(list, () => api.get('backups'), r => {
    const items = (r.backups || []).sort((a, b) => b.t - a.t);
    if (!items.length) return empty('Резервных копий ещё нет.');
    const total = items.reduce((a, b) => a + (b.size || 0), 0);
    return [h('div.list', items.map(b => h('div.list-item', { style: { padding: '10px 0' } }, icon('download'), h('div.body', h('div.mono', b.name), h('div.meta', fmt.dateTime(b.t), h('span', fmt.ago(b.t)))), h('span.num.text-2', fmt.bytes(b.size))))),
      h('p.muted', { style: { fontSize: '12.5px', marginTop: '8px' } }, `${items.length} ${fmt.plural(items.length, 'копия', 'копии', 'копий')}, всего ${fmt.bytes(total)}. Лежат в папке backups/ сервера.`)];
  }, { lines: 3 });
}

function drawInfo(box) {
  load(box, () => api.get('server'), s => [
    kv([['Mob Talk', s.version], ['Minecraft', s.minecraft], ['Fabric Loader', s.loader], ['Мир', s.world ? `${s.world.name}${s.world.difficulty ? ' · ' + s.world.difficulty : ''}` : '—'],
      ['Игроки', s.players ? `${s.players.online} из ${s.players.max}` : '—'], ['Аптайм', fmt.duration(s.uptimeSeconds)],
      s.admin && ['Админка', `${s.admin.bind}:${s.admin.port}`], s.admin?.fingerprint && ['Отпечаток', h('span.mono', { style: { fontSize: '11.5px' } }, s.admin.fingerprint)]]),
    s.pack?.length ? h('details', { style: { marginTop: '12px' } }, h('summary', 'pack-manifest.txt'), h('pre.block', { style: { marginTop: '8px' } }, s.pack.join('\n'))) : null,
  ], { lines: 6 });
}
