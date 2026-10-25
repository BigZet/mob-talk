// Мобы и деревни: поиск мобов, карточка моба (характер, состояние, отношения, память, события, правка, сброс);
// деревни — хроника, слухи, очки игроков и «простить».
import { h, icon, replace, avatar, debounce } from '../lib/dom.js';
import { api } from '../lib/api.js';
import * as fmt from '../lib/fmt.js';
import { tabs, load, drawer, confirm, busy, toast, toastError, empty, hearts, kv, errorBox, skeleton, table } from '../lib/ui.js';
import { speciesTitle, pname } from '../lib/record.js';
import { navigate, setParams } from '../app.js';

const STATE = [['mood', 'Настроение', 4], ['calm', 'Спокойствие', 3], ['energy', 'Бодрость', 2], ['trust', 'Доверие', 1], ['interest', 'Интерес', 7]];
const VILLAGE_MOOD = [['mood', 'Настроение', 4], ['calm', 'Спокойствие', 3], ['energy', 'Бодрость', 2]];
const signClass = s => (s > 0 ? '.good' : s < 0 ? '.bad' : '');

export default function mobs(root, route) {
  let tab = route.params.tab || 'mobs';
  const tabsBox = h('div');
  const body = h('div');
  replace(root, h('div.page-head', h('h1', 'Мобы и деревни')), tabsBox, body);
  const drawTabs = () => replace(tabsBox, tabs([{ value: 'mobs', title: 'Мобы' }, { value: 'villages', title: 'Деревни' }], tab, v => { tab = v; setParams({ tab: v }); drawTabs(); draw(); }));
  const draw = () => (tab === 'villages' ? drawVillages(body) : drawMobs(body, route));

  drawTabs();
  draw();

  let open = null;
  function update(r) {
    if ((r.params.tab || 'mobs') !== tab) { route = r; tab = r.params.tab || 'mobs'; drawTabs(); draw(); }
    if (open && open.uuid !== r.id) { open.d.close(); open = null; }
    if (r.id && !open) open = { uuid: r.id, d: mobCard(r.id, () => { open = null; setTimeout(() => { if (location.hash.includes(`/mobs/${encodeURIComponent(r.id)}`)) navigate('mobs', null, r.params); }); }) };
  }
  update(route);
  return { update, destroy() { open?.d.close(); } };
}

function drawMobs(body, route) {
  let q = route.params.q || '', village = route.params.village || '';
  const search = h('input.input', { type: 'search', placeholder: 'Имя, вид, характер', value: q, 'aria-label': 'Поиск моба' });
  const vSel = h('select.select', { 'aria-label': 'Деревня' }, h('option', { value: '' }, 'Все деревни'));
  const box = h('div.card.flush');
  const more = h('div.more');
  replace(body, h('div.toolbar', h('div.search', icon('search'), search), vSel), box, more);
  api.get('villages').then(r => { for (const v of r.villages || []) vSel.append(h('option', { value: v.id, selected: v.id === village }, v.name)); }).catch(() => {});
  let cursor = null, rows = [];
  const render = () => replace(box, rows.length ? table([
    { key: 'name', title: 'Моб', render: m => h('div.row.nowrap', avatar(m.name), h('div', h('div', { style: { fontWeight: 600 } }, m.name, !m.alive && h('span.badge.bad', { style: { marginLeft: '6px' } }, 'погиб')), h('div.muted', { style: { fontSize: '13px' } }, speciesTitle(m.species), m.trait ? ` · ${m.trait}` : ''))) },
    { key: 'village', title: 'Деревня', value: m => m.village?.name || '', render: m => m.village?.name || '—' },
    { key: 'relations', title: 'Знакомых', r: true, desc: true },
    { key: 'lastEvent', title: 'Последнее', value: m => m.lastEvent?.t || 0, desc: true, render: m => (m.lastEvent ? h('div', { style: { maxWidth: '360px' } }, h('div.ellipsis', m.lastEvent.text), h('div.muted', { style: { fontSize: '12px' } }, fmt.ago(m.lastEvent.t))) : '—') },
    { key: 'loaded', title: 'В мире', sortable: false, render: m => (m.loaded ? h('span.badge.good', 'загружен') : h('span.badge', 'выгружен')) },
  ], rows, { onRow: m => navigate('mobs', m.uuid, { ...route.params, q, village }), rowLabel: m => `Открыть ${m.name}` }) : empty(q || village ? 'Никого не нашлось.' : 'Мобов с профилем пока нет.'));
  async function page(first) {
    if (first) { cursor = null; rows = []; replace(box, skeleton(6)); replace(more); }
    try {
      const r = await api.get('mobs', { q, village, cursor, limit: 50 });
      rows = rows.concat(r.items || []);
      cursor = r.next;
      render();
      replace(more, cursor ? h('button.btn', { type: 'button', onclick: e => busy(e.currentTarget, () => page(false)) }, 'Показать ещё') : null);
    } catch (e) { if (first) replace(box, errorBox(e, () => page(true))); else toastError(e); }
  }
  search.addEventListener('input', debounce(() => { q = search.value.trim(); setParams({ q }); page(true); }, 250));
  vSel.addEventListener('change', () => { village = vSel.value; setParams({ village }); page(true); });
  page(true);
}

function stateBars(s, rows = STATE) {
  return h('div.stack', { style: { gap: '10px' } }, rows.filter(([k]) => s?.[k] !== undefined).map(([k, title, slot]) => h('div',
    h('div.row', { style: { fontSize: '13px', marginBottom: '4px' } }, h('span.grow.text-2', title), h('b.num', s?.[k] ?? '—')),
    h('div.meter', { role: 'meter', 'aria-label': title, 'aria-valuenow': s?.[k] ?? 0, 'aria-valuemin': 0, 'aria-valuemax': 100 }, h('i', { style: { width: `${s?.[k] ?? 0}%`, background: `var(--s${slot})` } })))));
}

export function mobCard(uuid, onClose) {
  const d = drawer('Моб', { onClose });
  const reload = () => load(d.body, () => api.get(`mobs/${encodeURIComponent(uuid)}`), r => {
    const m = r.mob || {}, c = r.card || {};
    d.setTitle(c.name || m.name);
    const memText = Array.isArray(c.memory) ? c.memory.join('\n') : c.memory || '';
    const name = h('input.input#mc-name', { value: c.name || m.name || '' });
    const character = h('textarea.textarea#mc-char', c.character || '');
    const voice = h('input.input#mc-voice', { value: c.voice || '' });
    const memory = h('textarea.textarea#mc-mem', { rows: 5 }, memText);
    const save = h('button.btn.primary', { type: 'submit' }, 'Сохранить карточку');
    const form = h('form.stack', {
      onsubmit: e => {
        e.preventDefault();
        const body = {};
        if (name.value !== (c.name || m.name || '')) body.name = name.value.trim();
        if (character.value !== (c.character || '')) body.character = character.value;
        if (voice.value !== (c.voice || '')) body.voice = voice.value.trim();
        if (memory.value !== memText) body.memory = Array.isArray(c.memory) ? memory.value.split('\n').map(x => x.trim()).filter(Boolean) : memory.value;
        if (!Object.keys(body).length) return toast('Ничего не изменилось');
        busy(save, async () => { await api.post(`mobs/${encodeURIComponent(uuid)}/card`, body); toast('Карточка сохранена'); reload(); });
      },
    },
      h('div', h('label.lbl', { for: 'mc-name' }, 'Имя'), name),
      h('div', h('label.lbl', { for: 'mc-char' }, 'Характер'), character),
      h('div', h('label.lbl', { for: 'mc-voice' }, 'Голос (ID)'), voice),
      h('div', h('label.lbl', { for: 'mc-mem' }, 'Память — что моб помнит, по строке на факт'), memory),
      h('div.row', save));
    const reset = h('button.btn.danger', { type: 'button' }, 'Сбросить состояние и память');
    reset.addEventListener('click', async () => {
      if (await confirm({ title: `Сбросить ${c.name || m.name}?`, consequence: 'Состояние, отношения и память вернутся к умолчанию. Имя и характер останутся. Отменить нельзя.', ok: 'Сбросить', danger: true }))
        busy(reset, async () => { await api.post(`mobs/${encodeURIComponent(uuid)}/reset`); toast('Сброшено'); reload(); });
    });
    const clearH = h('button.btn.danger', { type: 'button' }, 'Очистить историю реплик');
    clearH.addEventListener('click', async () => {
      if (await confirm({ title: 'Очистить историю реплик?', consequence: 'Моб забудет, о чём говорил. Записи в общей истории админки останутся.', ok: 'Очистить', danger: true }))
        busy(clearH, async () => { await api.post(`mobs/${encodeURIComponent(uuid)}/clear-history`); toast('История очищена'); reload(); });
    });
    const fam = r.family || {};
    const rivals = r.rivals;
    const rivalAction = (label, action, done, danger) => {
      const b = h(danger ? 'button.btn.sm.danger' : 'button.btn.sm', { type: 'button' }, label);
      b.addEventListener('click', () => busy(b, async () => { await api.post(`mobs/${encodeURIComponent(uuid)}/${action}`); toast(done); reload(); }));
      return b;
    };
    return [
      h('div.row.nowrap', avatar(c.name || m.name, 'lg'), h('div.grow', h('h2', c.name || m.name), h('div.muted', speciesTitle(m.species), m.village?.name ? ` · ${m.village.name}` : '', m.trait ? ` · ${m.trait}` : ''),
        h('div.row', { style: { gap: '4px', marginTop: '4px' } }, m.alive === false && h('span.badge.bad', 'погиб'), m.loaded ? h('span.badge.good', 'загружен') : h('span.badge', 'выгружен')))),
      h('section.card', h('div.card-head', h('h3', 'Состояние'), r.state?.spell ? h(`span.badge${signClass(r.state.spell.sign)}`, `${r.state.spell.word} · ${fmt.num(r.state.spell.minutes)} мин`) : r.state?.word && h('span.badge.accent', r.state.word)),
        stateBars(r.state),
        r.state?.spell?.help && h('p.muted', { style: { marginTop: '10px' } }, `Поможет: ${r.state.spell.help}`),
        r.state?.reasons?.length ? h('div.meta', { style: { marginTop: '10px', flexWrap: 'wrap' } }, r.state.reasons.map(x => h(`span.badge${signClass(x.sign)}`, x.text))) : null),
      (fam.spouse || fam.children?.length) && h('section', h('h3', { style: { marginBottom: '8px' } }, 'Семья'), kv([fam.spouse && ['Супруг(а)', pname(fam.spouse)], fam.children?.length && ['Дети', fam.children.map(pname).join(', ')]])),
      h('section.stack', h('h3', 'Отношения с игроками'),
        r.relations?.length ? h('div.list.card.flush', r.relations.map(x => h('a.list-item', { href: `#/players/${encodeURIComponent(x.player?.uuid || '')}`, onclick: () => d.close() }, avatar(pname(x.player)),
          h('div.body', h('div', { style: { fontWeight: 600 } }, pname(x.player)), h('div.meta', x.label && h('span.badge', x.label), h('span.num', `отношение ${x.relationship}`))), hearts(x.hearts ?? 0))))
          : h('p.muted', 'Ни с кем не знаком.')),
      rivals && h('section.stack', h('h3', 'Соперники'),
        rivals.suitors?.length ? h('div.list.card.flush', rivals.suitors.map(x => h('div.list-item', avatar(pname(x.player)),
          h('div.body', h('div', { style: { fontWeight: 600 } }, pname(x.player)), h('div.meta', h('span.badge', x.courtship), h('span.num', `сердечки ${x.hearts} · счёт выбора ${x.score}`))))))
          : h('p.muted', 'Сейчас никто не ухаживает.'),
        rivals.trial && kv([['Испытание', `${rivals.trial.kind === 'FAVORITE' ? 'первым подарить' : 'первым принести'} ${rivals.trial.item}${rivals.trial.count > 1 ? ' ×' + rivals.trial.count : ''}`],
          ['Принесли', rivals.trial.entries.map(e => `${pname(e.player)}: ${e.progress}`).join(', ') || '—'], ['Конец', fmt.ago(rivals.trial.endsAt)]]),
        rivals.sceneAt && kv([['Выбор у колокола', fmt.ago(rivals.sceneAt)]]),
        h('div.row', rivals.trial && rivalAction('Закончить испытание', 'rivals-end-trial', 'Испытание закончено'),
          !rivals.sceneAt && rivals.suitors?.length >= 2 && rivalAction('Позвать к колоколу', 'rivals-call', 'Ухажёры позваны'),
          rivalAction('Сбросить соперничество', 'rivals-reset', 'Сброшено', true))),
      h('section.stack', h('h3', 'Факты'), r.facts?.length ? h('div.list.card.flush', r.facts.map(f => h('div.list-item', h('div.body', h('div.line', f.text), h('div.meta', fmt.ago(f.t), f.importance != null && h('span', `важность ${f.importance}`)))))) : h('p.muted', 'Фактов нет.')),
      h('section.stack', h('div.row', h('h3.grow', 'События'), h('a.btn.sm.ghost', { href: `#/history?mob=${encodeURIComponent(uuid)}`, onclick: () => d.close() }, 'Вся история моба', icon('chevron', 'sm'))),
        r.events?.length ? h('div.list.card.flush', r.events.map(ev => h('div.list-item', h('div.body.line', ev.text), h('time.time', fmt.ago(ev.t))))) : h('p.muted', 'Событий нет.')),
      h('section.card', h('h3', { style: { marginBottom: '12px' } }, 'Карточка'), form),
      h('section.stack', h('h3', 'Опасное'), h('div.row', reset, clearH)),
    ];
  }, { lines: 8 });
  reload();
  return d;
}

// Настроение деревни: средние по жителям, сколько приуныли и сколько сияют, долгие состояния и общие причины.
function villageMood(m) {
  if (!m?.counted) return null;
  return h('div.grid.three', { style: { marginBottom: '16px' } },
    h('div.stack', h('h3', 'Настроение деревни'), stateBars(m, VILLAGE_MOOD)),
    h('div.stack', h('h3', 'Жители'),
      h('div.meta', { style: { flexWrap: 'wrap', gap: '6px', alignItems: 'center' } },
        h('span', `учтено ${fmt.num(m.counted)}`),
        h(`span.badge${m.low ? '.bad' : ''}`, `приуныли: ${fmt.num(m.low)}`),
        h(`span.badge${m.bright ? '.good' : ''}`, `сияют: ${fmt.num(m.bright)}`)),
      m.spells?.length ? h('div.meta', { style: { flexWrap: 'wrap', gap: '6px' } }, m.spells.map(x => h(`span.badge${signClass(x.sign)}`, `${x.word}: ${fmt.num(x.count)}`))) : h('p.muted', 'Долгих состояний нет.')),
    h('div.stack', h('h3', 'Почему'),
      m.reasons?.length ? h('div.list', m.reasons.map(x => h('div.list-item', { style: { padding: '6px 0' } },
        h('div.body.line', h(`span.badge${signClass(x.sign)}`, x.text)), h('span.num', `${fmt.num(x.count)} ${fmt.plural(x.count, 'житель', 'жителя', 'жителей')}`)))) : h('p.muted', 'Ничего особенного.')));
}

function drawVillages(body) {
  load(body, () => api.get('villages'), r => {
    const vs = r.villages || [];
    if (!vs.length) return empty('Деревень с жителями Mob Talk пока нет.');
    return h('div.stack.lg', vs.map(v => h('section.card',
      h('div.card-head', h('h2', v.name), h('span.sub', `${fmt.num(v.residents)} ${fmt.plural(v.residents, 'житель', 'жителя', 'жителей')}`), h('a.btn.sm.ghost', { href: `#/mobs?village=${encodeURIComponent(v.id)}` }, 'Жители', icon('chevron', 'sm'))),
      villageMood(v.mood),
      h('div.grid.three',
        h('div.stack', h('h3', 'Очки игроков'), v.standings?.length ? h('div.list', v.standings.map(s => {
          const btn = s.points < 0 ? h('button.btn.sm', { type: 'button' }, 'Простить') : null;
          btn?.addEventListener('click', async () => {
            if (await confirm({ title: `Простить ${pname(s.player)} в «${v.name}»?`, consequence: 'Очки игрока в деревне станут нулевыми: жители перестанут его сторониться. Слухи уже сказанные останутся.', ok: 'Простить' }))
              busy(btn, async () => { await api.post(`villages/${encodeURIComponent(v.id)}/forgive/${encodeURIComponent(s.player?.uuid || '')}`); toast('Прощён'); drawVillages(body); });
          });
          return h('div.list-item', { style: { padding: '8px 0' } }, avatar(pname(s.player)), h('div.body', h('div', { style: { fontWeight: 600 } }, pname(s.player)), h('div.meta', h(`span.badge${s.points < 0 ? '.bad' : s.points > 30 ? '.good' : ''}`, s.rank), h('span.num', `${s.points > 0 ? '+' : ''}${s.points}`))), btn);
        })) : h('p.muted', 'Нет.')),
        h('div.stack', h('h3', 'Слухи'), v.rumors?.length ? h('div.list', v.rumors.map(x => h('div.list-item', { style: { padding: '8px 0' } }, h('div.body', h('div.line', `«${x.text}»`), h('div.meta', x.about && h('span', `о ${pname(x.about)}`), h('span', fmt.ago(x.t))))))) : h('p.muted', 'Слухов нет.')),
        h('div.stack', h('h3', 'Хроника'), v.chronicle?.length ? h('div.list', v.chronicle.map(x => h('div.list-item', { style: { padding: '8px 0' } }, h('div.body.line', x.text), h('time.time', fmt.ago(x.t))))) : h('p.muted', 'Пусто.'))))));
  }, { lines: 8 });
}
