// Общие элементы интерфейса: тосты, подтверждения, шторки, загрузка с ошибкой, сегменты, таблицы, сердечки.
import { h, icon, replace } from './dom.js';

// ---------- тосты ----------
export function toast(text, kind = 'ok') {
  const box = document.getElementById('toasts');
  const el = h(`div.toast${kind === 'bad' ? '.bad' : ''}`, icon(kind === 'bad' ? 'warn' : 'check'), h('span', text));
  box.append(el);
  setTimeout(() => el.remove(), kind === 'bad' ? 7000 : 3500);
}
export const toastError = e => toast(e?.message || String(e), 'bad');

// ---------- окна ----------
export function openDialog(dlg) {
  document.body.append(dlg);
  dlg.addEventListener('close', () => dlg.remove(), { once: true });
  dlg.addEventListener('click', e => { if (e.target === dlg) dlg.close('cancel'); });
  dlg.showModal();
  return dlg;
}

/**
 * Подтверждение опасного действия: заголовок, текст последствий, кнопка с глаголом.
 * Возвращает Promise<boolean>.
 */
export function confirm({ title, text, consequence, ok = 'Подтвердить', danger = false }) {
  return new Promise(resolve => {
    let result = false;
    const dlg = h('dialog', { 'aria-labelledby': 'dlg-t' },
      h('form.dlg', { method: 'dialog' },
        h('h2#dlg-t', title),
        text && h('p.text-2', text),
        consequence && h(`div.conseq${danger ? '.bad' : ''}`, icon('warn'), h('span', consequence)),
        h('div.actions',
          h('button.btn', { value: 'cancel', type: 'submit' }, 'Отмена'),
          h(`button.btn${danger ? '.danger.solid' : '.primary'}`, { value: 'ok', type: 'submit', onclick: () => (result = true) }, ok))));
    dlg.addEventListener('close', () => resolve(result));
    openDialog(dlg);
    dlg.querySelector('button[value="ok"]').focus();
  });
}

/** Окно с полями ввода: fields [{key, title, type?, value?, placeholder?, options?}] → Promise<объект|null>. */
export function ask({ title, text, fields, ok = 'Готово', danger = false, consequence }) {
  return new Promise(resolve => {
    let result = null;
    const inputs = {};
    const form = h('form.dlg', { method: 'dialog' },
      h('h2', title), text && h('p.text-2', text),
      fields.map(f => {
        const id = `ask-${f.key}`;
        const input = f.options
          ? h('select.select', { id }, f.options.map(o => h('option', { value: o.value ?? o, selected: (o.value ?? o) === f.value }, o.title ?? o)))
          : f.type === 'textarea' ? h('textarea.textarea', { id, placeholder: f.placeholder || '' }, f.value ?? '')
            : h('input.input', { id, type: f.type || 'text', value: f.value ?? '', placeholder: f.placeholder || '', autocomplete: 'off' });
        inputs[f.key] = input;
        return h('div', h('label.lbl', { for: id }, f.title), input);
      }),
      consequence && h(`div.conseq${danger ? '.bad' : ''}`, icon('warn'), h('span', consequence)),
      h('div.actions',
        h('button.btn', { value: 'cancel', type: 'submit' }, 'Отмена'),
        h(`button.btn${danger ? '.danger.solid' : '.primary'}`, { value: 'ok', type: 'submit', onclick: () => {
          result = Object.fromEntries(Object.entries(inputs).map(([k, el]) => [k, el.value]));
        } }, ok)));
    const dlg = h('dialog', form);
    dlg.addEventListener('close', () => resolve(result));
    openDialog(dlg);
    Object.values(inputs)[0]?.focus();
  });
}

/** Боковая шторка (на телефоне — во весь экран). Возвращает {body, close, setTitle}. */
export function drawer(title, { onClose } = {}) {
  const t = h('h2', title);
  const body = h('div.drawer-body');
  const dlg = h('dialog.drawer', { 'aria-label': title },
    h('div.drawer-head', h('button.btn.icon.ghost', { type: 'button', 'aria-label': 'Закрыть', onclick: () => dlg.close() }, icon('close')), t),
    body);
  dlg.addEventListener('close', () => onClose?.());
  openDialog(dlg);
  return { body, dlg, close: () => dlg.open && dlg.close(), setTitle: s => (t.textContent = s) };
}

/** Нижняя шторка с содержимым (меню «Ещё» на телефоне). */
export function sheet(content, label) {
  const dlg = h('dialog.sheet', { 'aria-label': label }, h('div.drawer-body', content));
  openDialog(dlg);
  return dlg;
}

// ---------- загрузка ----------
export function skeleton(lines = 3, height = 18) {
  return h('div.stack', Array.from({ length: lines }, (_, i) => h('div.skeleton', { style: { height: `${height}px`, width: `${100 - (i % 3) * 18}%` } })));
}

export function errorBox(err, retry) {
  const unavailable = err?.status === 503;
  return h(`div.error-box${unavailable ? '.unavailable' : ''}`, { role: 'alert' },
    icon(unavailable ? 'info' : 'warn'),
    h('div.body', h('b', unavailable ? 'Недоступно' : 'Не получилось загрузить'), h('p.text-2', err?.message || String(err))),
    retry && !unavailable && h('button.btn.sm', { type: 'button', onclick: retry }, icon('refresh', 'sm'), 'Ещё раз'));
}

export const empty = text => h('div.empty', text);

/**
 * Загрузить и нарисовать: пока грузится — скелет, при ошибке — понятная плашка с «Ещё раз».
 * render(data) возвращает узлы. Возвращает функцию перезагрузки.
 */
export function load(el, fetcher, render, { lines = 3, quiet = false } = {}) {
  let seq = 0;
  const run = async (soft = false) => {
    const my = ++seq;
    if (!soft) replace(el, skeleton(lines));
    try {
      const data = await fetcher();
      if (my !== seq || !el.isConnected && soft) return;
      replace(el, render(data));
    } catch (e) {
      if (my !== seq || e.name === 'AbortError') return;
      if (soft && quiet) return;
      replace(el, errorBox(e, () => run()));
    }
  };
  run();
  return run;
}

/** Кнопка с состоянием «занято» на время действия; ошибки — тостом. */
export async function busy(btn, action) {
  if (btn.getAttribute('aria-busy') === 'true') return;
  btn.setAttribute('aria-busy', 'true');
  btn.disabled = true;
  try { return await action(); } catch (e) { toastError(e); return undefined; } finally { btn.removeAttribute('aria-busy'); btn.disabled = false; }
}

// ---------- управление ----------
/** Сегменты: options [{value,title}], выбранное value, onChange(value). */
export function seg(options, value, onChange, label) {
  const box = h('div.seg', { role: 'group', 'aria-label': label });
  const render = v => box.querySelectorAll('button').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.v === v)));
  for (const o of options) box.append(h('button', { type: 'button', dataset: { v: o.value }, onclick: () => { render(o.value); onChange(o.value); } }, o.title));
  render(value);
  return box;
}

/** Вкладки с ролями и стрелками. */
export function tabs(options, value, onChange) {
  const box = h('div.tabs', { role: 'tablist' });
  for (const o of options) {
    box.append(h('button', { type: 'button', role: 'tab', 'aria-selected': String(o.value === value), tabindex: o.value === value ? '0' : '-1', dataset: { v: o.value }, onclick: () => onChange(o.value) }, o.title));
  }
  box.addEventListener('keydown', e => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
    const all = [...box.children], i = all.indexOf(document.activeElement);
    const next = all[(i + (e.key === 'ArrowRight' ? 1 : all.length - 1)) % all.length];
    next?.focus(); next?.click();
  });
  return box;
}

export const RANGES = [
  { value: '1h', title: '1 ч', ms: 3600e3 }, { value: '6h', title: '6 ч', ms: 6 * 3600e3 }, { value: '24h', title: '24 ч', ms: 24 * 3600e3 },
  { value: '7d', title: '7 д', ms: 7 * 86400e3 }, { value: '30d', title: '30 д', ms: 30 * 86400e3 },
];
export const rangeMs = v => (RANGES.find(r => r.value === v) || RANGES[0]).ms;

export function switchInput({ checked, label, onChange, id }) {
  const input = h('input', { type: 'checkbox', role: 'switch', checked, id, 'aria-label': label, onchange: e => onChange?.(e.target.checked) });
  return h('label.switch', input, h('span'));
}

export function hearts(n, max = 5) {
  const box = h('span.hearts', { title: `${n} из ${max}`, 'aria-label': `${n} из ${max} сердечек`, role: 'img' });
  for (let i = 0; i < max; i++) {
    const cls = n >= i + 1 ? '' : n >= i + 0.5 ? 'half' : 'empty';
    box.append(icon('heart', cls));
  }
  return box;
}

/**
 * Таблица с сортировкой. columns [{key, title, value?(row) для сортировки, render?(row), r?: true}], rows, onRow(row).
 * На телефоне превращается в карточки (подписи из title).
 */
export function table(columns, rows, { onRow, sort, rowLabel } = {}) {
  let state = sort || { key: null, dir: 1 };
  const tbody = h('tbody');
  const ths = columns.map(c => h(`th${c.r ? '.r' : ''}`, { scope: 'col' },
    c.sortable === false ? c.title : h('button', { type: 'button', onclick: () => { state = { key: c.key, dir: state.key === c.key ? -state.dir : (c.desc ? -1 : 1) }; draw(); } }, c.title)));
  const draw = () => {
    ths.forEach((th, i) => { const c = columns[i]; if (state.key === c.key) th.setAttribute('aria-sort', state.dir > 0 ? 'ascending' : 'descending'); else th.removeAttribute('aria-sort'); });
    const col = columns.find(c => c.key === state.key);
    const sorted = col ? [...rows].sort((a, b) => {
      const va = col.value ? col.value(a) : a[col.key], vb = col.value ? col.value(b) : b[col.key];
      return (typeof va === 'string' ? va.localeCompare(vb, 'ru') : (va ?? -Infinity) - (vb ?? -Infinity)) * state.dir;
    }) : rows;
    tbody.replaceChildren(...sorted.map(row => {
      const tr = h(`tr${onRow ? '.click' : ''}`, onRow && { tabindex: '0', 'aria-label': rowLabel?.(row), onclick: () => onRow(row), onkeydown: e => { if (e.key === 'Enter') onRow(row); } });
      columns.forEach((c, i) => tr.append(h(`td${c.r ? '.r' : ''}${i === 0 ? '.main-cell' : ''}`, { 'data-label': c.title }, c.render ? c.render(row) : row[c.key] ?? '—')));
      return tr;
    }));
  };
  draw();
  return h('div.table-wrap', h('table.table.cards', h('thead', h('tr', ths)), tbody));
}

export function stat({ label, value, unit, hint, tone, iconName, extra }) {
  return h(`div.card.stat${tone ? '.' + tone : ''}`,
    h('div.label', iconName && icon(iconName, 'sm'), label),
    h('div.value.num', value, unit && h('small', unit)),
    h('div.hint', hint || ''),
    extra);
}

export function kv(pairs) {
  return h('dl.kv', pairs.filter(Boolean).flatMap(([k, v]) => [h('dt', k), h('dd', v ?? '—')]));
}

export function copyText(text) {
  return navigator.clipboard?.writeText(text).then(() => toast('Скопировано'), () => toast('Не удалось скопировать', 'bad'));
}
