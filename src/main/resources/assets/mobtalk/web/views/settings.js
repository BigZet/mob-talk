// Настройки: источники (Mob Talk, Божественный блок, другие моды), группы как в F7/F9, поиск, правка с «Применить»/«Сбросить»
// на группу, подтверждение опасных; действия источника; вкладка «История изменений» с откатом.
import { h, icon, replace, debounce } from '../lib/dom.js';
import { api } from '../lib/api.js';
import { on } from '../lib/sse.js';
import * as fmt from '../lib/fmt.js';
import { tabs, load, confirm, ask, busy, toast, toastError, empty, switchInput, errorBox, skeleton } from '../lib/ui.js';
import { setParams } from '../app.js';

const same = (a, b) => (typeof a === 'number' && typeof b === 'number' ? Math.abs(a - b) < 1e-9 : a === b);
const decimals = step => (String(step).split('.')[1] || '').length;

/** Элемент управления полем по типу. onInput(value) на каждое изменение. Возвращает {el, set(value)}. */
export function fieldControl(f, value, onInput) {
  const id = `f-${f.key.replace(/[^\w-]/g, '_')}`;
  if (f.type === 'bool') {
    const sw = switchInput({ checked: !!value, label: f.title, id, onChange: onInput });
    return { el: sw, set: v => (sw.querySelector('input').checked = !!v) };
  }
  if (f.type === 'enum') {
    const opts = (f.options || []).map(o => (typeof o === 'object' ? o : { value: o, title: o }));
    const sel = h('select.select', { id, 'aria-label': f.title, onchange: e => onInput(opts.find(o => String(o.value) === e.target.value)?.value ?? e.target.value) },
      opts.map(o => h('option', { value: String(o.value), selected: same(o.value, value) }, o.title ?? String(o.value))));
    return { el: sel, set: v => (sel.value = String(v)) };
  }
  if (f.type === 'int' || f.type === 'decimal') {
    const step = f.step || (f.type === 'int' ? 1 : 0.1), d = decimals(step);
    const clamp = v => Math.min(f.max ?? Infinity, Math.max(f.min ?? -Infinity, v));
    const round = v => +(Math.round(v / step) * step).toFixed(d);
    const hasRange = f.min != null && f.max != null;
    const num = h('input.input.num', { id, type: 'number', inputmode: 'decimal', step, min: f.min, max: f.max, value, 'aria-label': `${f.title}${f.unit ? ', ' + f.unit : ''}` });
    const range = hasRange ? h('input', { type: 'range', min: f.min, max: f.max, step, value, tabindex: '-1', 'aria-hidden': 'true' }) : null;
    const set = v => { num.value = v; if (range) range.value = v; };
    const commit = v => { if (!Number.isFinite(v)) return; v = round(clamp(v)); set(v); onInput(v); };
    num.addEventListener('change', () => commit(parseFloat(String(num.value).replace(',', '.'))));
    num.addEventListener('input', () => { const v = parseFloat(num.value); if (Number.isFinite(v) && range) range.value = v; });
    range?.addEventListener('input', () => commit(parseFloat(range.value)));
    const minus = h('button.btn', { type: 'button', 'aria-label': `Меньше: ${f.title}`, onclick: () => commit(parseFloat(num.value) - step) }, '−');
    const plus = h('button.btn', { type: 'button', 'aria-label': `Больше: ${f.title}`, onclick: () => commit(parseFloat(num.value) + step) }, '+');
    return { el: h('div.range-row', minus, range, num, plus), set };
  }
  const input = h('input.input', { id, type: 'text', value: value ?? '', 'aria-label': f.title, oninput: e => onInput(e.target.value) });
  return { el: input, set: v => (input.value = v ?? '') };
}

/** Кнопки действий источника (снять бан, снять проклятия…) с подтверждением и вводом аргументов. */
export function drawActions(src, actions, box) {
  if (!actions.length) return replace(box);
  replace(box, h('section.card', h('div.card-head', h('h2', 'Действия'), h('span.sub', src.title)),
    h('div.row', actions.map(a => {
      const btn = h(`button.btn${a.danger ? '.danger' : ''}`, { type: 'button' }, a.title);
      btn.addEventListener('click', async () => {
        let args = {};
        if (a.args?.length) {
          const r = await ask({ title: a.title, fields: a.args.map(x => ({ key: x.key, title: x.title || x.key })), ok: a.title, danger: !!a.danger, consequence: a.danger });
          if (!r) return; args = r;
        } else if (!(await confirm({ title: `${a.title}?`, consequence: a.danger || 'Действие выполнится сразу.', ok: a.title, danger: !!a.danger }))) return;
        busy(btn, async () => { const r = await api.post(`sources/${encodeURIComponent(src.id)}/actions/${encodeURIComponent(a.id)}`, { args }); toast(r.message || 'Готово'); });
      });
      return btn;
    }))));
}

export default function settings(root, route) {
  let sources = [];
  let sourceId = route.params.source || 'mobtalk';
  let tab = route.params.tab || 'fields';
  let q = route.params.q || '';
  const cleanup = [];
  const head = h('div.page-head', h('h1', 'Настройки'));
  const tabsBox = h('div');
  const body = h('div');
  replace(root, head, tabsBox, body);

  function drawTabs() {
    replace(tabsBox, tabs([{ value: 'fields', title: 'Значения' }, { value: 'changes', title: 'История изменений' }], tab, v => { tab = v; setParams({ tab: v }); drawTabs(); drawBody(); }));
  }

  // ---------- значения ----------
  let saved = {}, groupsState = [];
  function drawBody() {
    if (tab === 'changes') return drawChanges();
    const srcList = h('div.src-list', { role: 'group', 'aria-label': 'Источник настроек' }, sources.map(s => h('button', {
      type: 'button', 'aria-pressed': String(s.id === sourceId), onclick: () => { if (dirtyCount() && !window.confirm('Есть непримененные изменения. Уйти без них?')) return; sourceId = s.id; setParams({ source: s.id }); drawBody(); },
    }, s.title)));
    const panel = h('div.stack');
    replace(body, h('div.settings-layout', srcList, panel));
    const src = sources.find(s => s.id === sourceId) || sources[0];
    if (!src) return replace(panel, empty('Источников настроек нет.'));
    sourceId = src.id;
    const search = h('input.input', { type: 'search', placeholder: 'Поиск настройки', value: q, 'aria-label': 'Поиск настройки' });
    const groupsBox = h('div');
    const actionsBox = h('div');
    replace(panel, h('div.toolbar', h('div.search', icon('search'), search), h('span.badge.dirty-count', { hidden: true })), groupsBox, actionsBox);
    search.addEventListener('input', debounce(() => { q = search.value.trim(); setParams({ q }); filter(); }, 200));
    load(groupsBox, () => api.get(`sources/${encodeURIComponent(src.id)}/values`), r => { saved = { ...r.values }; return drawGroups(src); }, { lines: 8 });
    api.get(`sources/${encodeURIComponent(src.id)}/actions`).then(r => drawActions(src, r.actions || [], actionsBox)).catch(e => { if (e.status !== 404) replace(actionsBox, errorBox(e)); });
  }

  function dirtyCount() { return groupsState.reduce((a, g) => a + Object.keys(g.draft).length, 0); }
  function paintDirty() {
    const n = dirtyCount(), b = body.querySelector('.dirty-count');
    if (b) { b.hidden = !n; b.className = 'badge accent dirty-count'; b.textContent = `не применено: ${n}`; }
  }

  function drawGroups(src) {
    groupsState = [];
    const out = [];
    let section = null;
    src.groups.forEach((g, gi) => {
      if (g.section && g.section !== section) { section = g.section; out.push(h('h2.section-title', { style: { margin: gi ? '20px 0 10px' : '0 0 10px' } }, section)); }
      const st = { g, draft: {}, rows: {}, ctl: {} };
      groupsState.push(st);
      const countEl = h('span.count');
      const apply = h('button.btn.primary.sm', { type: 'button' }, 'Применить');
      const reset = h('button.btn.sm', { type: 'button' }, 'Сбросить');
      const note = h('span.note');
      const actions = h('div.group-actions', { hidden: true }, note, reset, apply);
      const repaint = () => {
        const n = Object.keys(st.draft).length;
        actions.hidden = !n;
        note.textContent = n ? `Изменено: ${n}` : '';
        const nonDefault = g.fields.filter(f => f.default !== undefined && !same(saved[f.key], f.default)).length;
        countEl.textContent = `${g.fields.length} ${fmt.plural(g.fields.length, 'настройка', 'настройки', 'настроек')}${nonDefault ? ` · не по умолчанию ${nonDefault}` : ''}`;
        for (const f of g.fields) st.rows[f.key].classList.toggle('dirty', f.key in st.draft);
        paintDirty();
      };
      const rows = g.fields.map(f => {
        const errEl = h('div.err', { role: 'alert' });
        const ctl = fieldControl(f, saved[f.key], v => {
          if (same(v, saved[f.key])) delete st.draft[f.key]; else st.draft[f.key] = v;
          row.classList.remove('invalid'); errEl.textContent = '';
          repaint();
        });
        st.ctl[f.key] = ctl;
        const def = f.default !== undefined && !same(saved[f.key], f.default)
          ? h('button.btn.ghost.sm', { type: 'button', title: `Вернуть значение по умолчанию: ${fmt.value(f.default)}`, onclick: () => { ctl.set(f.default); if (same(f.default, saved[f.key])) delete st.draft[f.key]; else st.draft[f.key] = f.default; repaint(); } }, icon('undo', 'sm'), h('span.sr-only', 'По умолчанию'))
          : null;
        const row = h('div.field', { dataset: { key: f.key, search: `${f.title} ${f.key} ${f.help || ''}`.toLowerCase() } },
          h('div',
            h('label.name', { for: `f-${f.key.replace(/[^\w-]/g, '_')}` }, f.title, f.unit && h('span.muted', `(${f.unit})`), f.danger && h('span.badge.warn', { title: f.danger }, icon('warn'), 'опасно')),
            f.help && h('div.help', f.help),
            h('div.help', h('span.key', f.key), f.default !== undefined && ` · по умолчанию ${fmt.value(f.default)}`),
            errEl),
          h('div.ctl', ctl.el, def || h('span', { style: { width: '32px', flex: 'none' }, 'aria-hidden': 'true' })));
        row._err = errEl;
        st.rows[f.key] = row;
        return row;
      });
      reset.addEventListener('click', () => { for (const k of Object.keys(st.draft)) { st.ctl[k].set(saved[k]); st.rows[k].classList.remove('invalid'); st.rows[k]._err.textContent = ''; } st.draft = {}; repaint(); });
      apply.addEventListener('click', async () => {
        const changed = Object.keys(st.draft);
        const dangers = g.fields.filter(f => changed.includes(f.key) && f.danger);
        if (dangers.length) {
          const ok = await confirm({ title: `Применить «${g.title}»?`, text: changed.map(k => `${g.fields.find(f => f.key === k).title}: ${fmt.value(saved[k])} → ${fmt.value(st.draft[k])}`).join('; '),
            consequence: dangers.map(f => f.danger).join(' '), ok: 'Применить', danger: true });
          if (!ok) return;
        }
        await busy(apply, async () => {
          const r = await api.patch(`sources/${encodeURIComponent(src.id)}/values`, { values: st.draft });
          for (const k of r.applied || []) { saved[k] = st.draft[k]; delete st.draft[k]; }
          for (const [k, why] of Object.entries(r.errors || {})) if (st.rows[k]) { st.rows[k].classList.add('invalid'); st.rows[k]._err.textContent = why; }
          repaint();
          if (r.applied?.length) toast(`Применено: ${r.applied.length}`);
          if (Object.keys(r.errors || {}).length) toast('Часть значений сервер не принял — причины у полей', 'bad');
        });
      });
      const details = h('details.group', { open: gi < 2 || !!q, dataset: { group: g.id } },
        h('summary', icon('chevron', 'chev'), h('span.grow', g.title), countEl), h('div.fields', rows), actions);
      st.details = details;
      repaint();
      out.push(details);
    });
    requestAnimationFrame(filter);
    return out.length ? out : empty('В этом источнике нет настроек.');
  }

  function filter() {
    const s = q.toLowerCase();
    for (const st of groupsState) {
      let any = false;
      for (const row of Object.values(st.rows)) { const hit = !s || row.dataset.search.includes(s) || st.g.title.toLowerCase().includes(s); row.hidden = !hit; any ||= hit; }
      st.details.hidden = !any;
      if (s && any) st.details.open = true;
    }
  }

  // ---------- история изменений ----------
  function drawChanges() {
    const list = h('div.list');
    const more = h('div.more');
    const srcSel = h('select.select', { 'aria-label': 'Источник', onchange: () => reload() }, h('option', { value: '' }, 'Все источники'), sources.map(s => h('option', { value: s.id }, s.title)));
    replace(body, h('div.toolbar', srcSel), h('section.card.flush', list, more));
    let cursor = null;
    const title = (src, key) => sources.find(s => s.id === src)?.groups.flatMap(g => g.fields).find(f => f.key === key)?.title || key;
    const row = rec => {
      const d = rec.data || {};
      const btn = h('button.btn.sm', { type: 'button', title: 'Вернуть значение «было»' }, icon('undo', 'sm'), 'Откатить');
      btn.addEventListener('click', async () => {
        if (!(await confirm({ title: 'Откатить изменение?', text: `${title(d.source, d.key)}: ${fmt.value(d.after)} → ${fmt.value(d.before)}`, consequence: 'Значение изменится сразу у всех игроков, без перезапуска.', ok: 'Откатить' }))) return;
        busy(btn, async () => { await api.post(`config/rollback/${encodeURIComponent(rec.id)}`); toast('Откатано'); reload(); });
      });
      return h('div.list-item',
        h('div.body',
          h('div.line', h('b', title(d.source, d.key)), ' ', h('span.badge', fmt.value(d.before)), ' → ', h('span.badge.accent', fmt.value(d.after))),
          h('div.meta', h('span', sources.find(s => s.id === d.source)?.title || d.source), h('span.key.mono', d.key), h('span', d.by || ''), d.rollbackOf && h('span.badge', 'откат'))),
        h('div.stack', { style: { alignItems: 'flex-end', gap: '6px' } }, h('time.time', { title: fmt.dateTimeFull(rec.t) }, fmt.ago(rec.t)), btn));
    };
    async function page(first) {
      if (first) { cursor = null; replace(list, skeleton(5)); replace(more); }
      try {
        const r = await api.get('config/history', { source: srcSel.value, cursor, limit: 50 });
        if (first) replace(list);
        list.append(...r.items.map(row));
        if (first && !r.items.length) list.append(empty('Изменений пока не было.'));
        cursor = r.next;
        replace(more, cursor ? h('button.btn', { type: 'button', onclick: e => busy(e.currentTarget, () => page(false)) }, 'Показать ещё') : null);
      } catch (e) { if (first) replace(list, errorBox(e, () => page(true))); else toastError(e); }
    }
    const reload = () => page(true);
    reload();
  }

  // ---------- старт ----------
  drawTabs();
  load(body, () => api.get('sources'), r => { sources = r.sources || []; queueMicrotask(drawBody); return skeleton(4); }, { lines: 6 });
  cleanup.push(on('config', rec => {
    const d = rec?.data;
    if (!d || d.source !== sourceId || tab !== 'fields') return;
    saved[d.key] = d.after;
    for (const st of groupsState) if (st.ctl[d.key] && !(d.key in st.draft)) st.ctl[d.key].set(d.after);
  }));
  const beforeUnload = e => { if (dirtyCount()) { e.preventDefault(); e.returnValue = ''; } };
  addEventListener('beforeunload', beforeUnload);
  cleanup.push(() => removeEventListener('beforeunload', beforeUnload));
  return { destroy() { cleanup.forEach(f => f()); } };
}
