// Бог (Божественный блок): сводка за сутки, как устроен бог, живые боссы и проклятия, души (милость, грехи, кары подряд), молитвы
// с ответами, графики ответов и частых деяний (из записей «verdict» истории), справочник деяний, действия источника divineblock.
// Без мода в сборке API отвечает 503 — показываем это спокойно.
import { h, icon, replace, avatar } from '../lib/dom.js';
import { api } from '../lib/api.js';
import { on } from '../lib/sse.js';
import * as fmt from '../lib/fmt.js';
import { RANGES, rangeMs, seg, load, confirm, ask, busy, toast, empty, errorBox, skeleton, table, stat } from '../lib/ui.js';
import { timeChart } from '../lib/chart.js';
import { recordRow, pname } from '../lib/record.js';
import { drawActions } from './settings.js';
import { setParams } from '../app.js';

/** Ответы бога: подпись и цвет графика (слот палитры). */
const ACT = { wrath: ['Кары', 8], miracle: ['Блага', 3], warning: ['Знамения', 5], jest: ['Шутки', 6], none: ['Молчание', 7] };
/** Действия, у которых своё место на вкладке (у боссов, у души), а не общий список действий. */
const OWN_PLACE = new Set(['boss_remove', 'boss_clear', 'clear', 'god_recall', 'court_end', 'mercy', 'act']);
/** Своя кнопка у души, а не общая кнопка действия по игроку. */
const SOUL_OWN = new Set(['mercy', 'act']);
const CHOICE = { miracle: ['благо', '.good'], wrath: ['кара', '.bad'], warning: ['знамение', '.warn'], jest: ['шутка', ''], none: ['без ответа', ''] };
const TONE = { profane: '.bad', rude: '.bad', urgent: '.warn', penitent: '.good' };
/** Проклятия, которые держатся до двадцати минут (Curses.Type в моде), простыми словами. */
const CURSES = {
  'порча земли': 'Земля вокруг дома игрока (у кровати, иначе у алтаря) гниёт: трава становится сухой землёй, посевы откатываются в ростки и гибнут, листва осыпается, скот рядом болеет и теряет сердца.',
  'мор скота': 'Животные рядом с игроком кашляют дымом и теряют по сердцу, пока не погибнут.',
  'жители боятся': 'Жители и торговцы разбегаются от игрока.',
  'печать каина': 'Звери злы на игрока, големы и волки (даже ручные) охотятся на него, жители убегают.',
  'тьма египетская': 'В обычном мире стоит полночь, что бы ни показывали часы.',
  'вода в кровь': 'Над водой рядом с игроком красный туман, рыба в ней гибнет, а кто стоит в воде — болеет.',
  'потоп': 'Вода поднялась и уходит, только когда проклятие кончится.',
};
const curseHelp = title => CURSES[String(title || '').toLowerCase()] || '';
/** Пороги милости — те же, что у Righteousness.label() в моде. */
const MERCY = ['святой (60 и выше)', 'праведник (20…60)', 'обычный смертный (−20…20)', 'грешник (−60…−20)', 'проклятый (ниже −60)'];
const OPEN_KEY = 'mobtalk.divine.howto';
const readOpen = () => { try { return localStorage.getItem(OPEN_KEY) !== 'closed'; } catch { return true; } };
const saveOpen = open => { try { localStorage.setItem(OPEN_KEY, open ? 'open' : 'closed'); } catch { /* без хранилища — просто открыто */ } };
const note = text => h('p.muted', { style: { margin: '4px 0 10px', fontSize: '13px' } }, text);

export default function divine(root, route) {
  let range = route.params.range || '7d';
  const cleanup = [], charts = [];
  let actions = [], deeds = new Map(), disabled = new Set();
  const tiles = h('div.grid.stats'), soulsBox = h('div.card.flush'), activeBox = h('div'), verdicts = h('div.list'), statBox = h('div.chart'),
    topBox = h('div'), actionsBox = h('div'), prayersBox = h('div.list'), catalogBox = h('div');
  let prayerPlayer = route.params.player || '';
  const playerSel = h('select.select', { 'aria-label': 'Игрок', style: { width: 'auto' }, onchange: e => { prayerPlayer = e.target.value; setParams({ player: prayerPlayer || null }); drawPrayers(); } }, h('option', { value: '' }, 'все игроки'));
  replace(root, h('div.page-head', h('h1', 'Бог')), h('div', skeleton(4)));

  // Сначала узнаём, есть ли мод: один запрос, 503 — понятная плашка вместо раздела. Справочник деяний — из описания настроек источника.
  Promise.all([api.get('divine/souls'), api.get('sources/divineblock/actions').catch(() => ({ actions: [] })), api.get('sources').catch(() => ({ sources: [] })),
    api.get('sources/divineblock/values').catch(() => ({ values: {} }))]).then(([first, act, src, vals]) => {
    actions = act.actions || [];
    const field = (src.sources || []).find(s => s.id === 'divineblock')?.groups?.flatMap(g => g.fields || []).find(f => f.key === 'disabled');
    deeds = new Map((field?.options || []).map(o => [o.id, o]));
    const off = vals.values?.disabled;
    disabled = new Set(Array.isArray(off) ? off : String(off || '').split(',').map(x => x.trim()).filter(Boolean));
    replace(root,
      h('div.page-head', h('h1', 'Бог'), h('span.muted', 'что бог слышит и как отвечает')),
      h('div.stack.lg',
        tiles,
        howTo(),
        activeBox,
        h('section', h('h2.section-title', 'Души'),
          note('Каждый, кто хоть раз молился. Милость — сколько добра бог помнит за игроком, грехи — сколько дурного, кары подряд — насколько тяжелее будет следующая кара.'),
          soulsBox),
        h('section.card.flush', h('div.card-head', { style: { padding: '14px 16px 0' } }, h('h2', 'Молитвы и ответы'), playerSel),
          h('div', { style: { padding: '0 16px' } }, note('Слова игрока, тон, подношение, ответ бога и почему он так решил. Наведите на деяние — появится описание.')), prayersBox),
        h('div.grid.two',
          h('section.card', h('div.card-head', h('h2', 'Ответы бога'), seg(RANGES.slice(2), range, v => { range = v; setParams({ range: v }); drawStats(); }, 'Период')),
            note('Сколько раз бог ответил каждым способом, по дням или часам.'), statBox),
          h('section.card', h('div.card-head', h('h2', 'Частые деяния'), h('span.sub', 'за тот же период')), note('Что именно бог делал чаще всего.'), topBox)),
        h('section.card.flush', h('div.card-head', { style: { padding: '14px 16px 0' } }, h('h2', 'Последние вердикты'), h('a.btn.sm.ghost', { href: '#/history?kind=verdict' }, 'Все', icon('chevron', 'sm'))), verdicts),
        catalogBox,
        actionsBox));
    drawSouls(first);
    drawPrayers();
    drawActive();
    drawStats();
    drawCatalog();
    loadVerdicts();
    drawActions({ id: 'divineblock', title: 'Божественный блок' }, actions.filter(a => !OWN_PLACE.has(a.id)), actionsBox);
  }).catch(e => replace(root, h('div.page-head', h('h1', 'Бог')), errorBox(e.status === 503 ? { status: 503, message: 'Божественного блока нет в этой сборке или он ещё не подключён к админке.' } : e, () => divine(root, route))));

  /** Описание деяния из справочника, без технической приставки группы силы («METEOR «…»: »). */
  const deedHelp = id => String(deeds.get(id)?.help || '').replace(/^[A-Z_]+ «[^»]*»: /, '');
  const loadVerdicts = () => load(verdicts, () => api.get('history', { kind: 'verdict', limit: 10 }),
    r => (r.items.length ? r.items.map(x => recordRow(x)) : empty('Вердиктов пока нет: с запуска сервера никто не молился.')), { lines: 5 });

  // ---------- как устроен бог ----------
  function howTo() {
    const li = (b, text) => h('li', { style: { margin: '6px 0' } }, h('b', b), ' — ', text);
    return h('details.group', { open: readOpen(), ontoggle: e => saveOpen(e.target.open) },
      h('summary', icon('chevron', 'chev'), h('span.grow', 'Как устроен бог'), h('span.count', 'коротко, простыми словами')),
      h('div', { style: { padding: '0 16px 14px', borderTop: '1px solid var(--border)' } },
        h('ul', { style: { margin: '10px 0 0', paddingLeft: '18px', lineHeight: 1.5 } },
          li('Молитва', 'игрок говорит у алтаря голосом или в чат. Бог слышит слова, тон (смиренно, грубо, кощунственно) и подношение на алтаре, вспоминает поступки игрока и решает, как ответить.'),
          li('Ответ', 'благо (помощь), кара (наказание), знамение (знак без последствий), шутка (безобидный розыгрыш) или молчание. Сила от 1 до 4: чем выше, тем тяжелее кара или щедрее благо.'),
          li('Милость', `счёт поступков от −100 до +100. Сажал деревья, разводил скот, торговал — растёт; убивал жителей, ломал церковь, богохульствовал — падает. Каждый день понемногу тянется к нулю. Сама молитва милость не меняет (кроме хулы, она её роняет): «+5 к благу» у причин молитвы склоняет только этот ответ. Метки: ${MERCY.join(', ')}.`),
          li('Грехи', 'сколько дурных поступков бог помнит за игроком. Грех во время идущей кары продлевает её.'),
          li('Кары подряд', 'каждая новая кара без прощения делает следующую на ступень тяжелее, до четырёх ступеней. Неделя игровых дней без кар или искреннее покаяние сбрасывают счёт.'),
          li('Проклятия', 'кары, которые держатся до двадцати минут: порча земли, мор скота, печать Каина и другие. Искреннее покаяние снимает их все сразу.'),
          li('Изгнание', 'за худшее бог изгоняет навечно: игрок не войдёт на сервер, пока админ его не простит (кнопка у души).'),
          li('Боссы', 'за тяжкие грехи бог посылает священных боссов, они охотятся на грешника. Убрать их можно в «Живых боссах».'))));
  }

  // ---------- сводка ----------
  async function drawTiles(a) {
    let day = null;
    try { const to = Date.now(); day = await api.get('history/stats', { kind: 'verdict', by: 'data.act', bucket: 'hour', from: to - 86400e3, to }); } catch { /* сводка без чисел молитв */ }
    const sum = k => (day?.series?.[k] || []).reduce((x, y) => x + y, 0);
    const total = Object.keys(day?.series || {}).reduce((x, k) => x + sum(k), 0);
    const bosses = (a.bosses || []).length, cursed = new Set((a.curses || []).map(c => pname(c.player))).size;
    replace(tiles,
      stat({ label: 'Молитв за сутки', iconName: 'divine', value: day ? fmt.num(total) : '—', hint: total ? `благ ${fmt.num(sum('miracle'))} · шуток ${fmt.num(sum('jest'))} · молчал ${fmt.num(sum('none'))}` : 'никто не молился' }),
      stat({ label: 'Кар за сутки', iconName: 'divine', value: day ? fmt.num(sum('wrath')) : '—', tone: sum('wrath') ? 'bad' : '', hint: 'наказаний по молитвам' }),
      stat({ label: 'Кары идут', iconName: 'live', value: fmt.num((a.punishments || []).length), tone: (a.punishments || []).length ? 'warn' : '', hint: (a.punishments || []).length ? (a.punishments || []).map(p => pname(p.player)).join(', ') : 'никого не карают' }),
      stat({ label: 'Под проклятием', iconName: 'players', value: fmt.num(cursed), tone: cursed ? 'warn' : '', hint: cursed ? `проклятий всего ${fmt.num(a.curses.length)}` : 'никого' }),
      stat({ label: 'Живых боссов', iconName: 'mobs', value: fmt.num(bosses), tone: bosses ? 'bad' : '', hint: a.godAvatar?.active ? 'бог сошёл на землю' : a.skyCourt?.active ? 'идёт небесный суд' : 'бог спокоен' }));
  }

  // ---------- души ----------
  const mercyBadge = m => h(`span.badge${m < -20 ? '.bad' : m < 0 ? '.warn' : m >= 20 ? '.good' : ''}`, `${m > 0 ? '+' : ''}${fmt.num(Math.round(m))}`);
  function ladderCell(n) {
    const text = n ? `${n} ${n === 1 ? 'кара' : n < 5 ? 'кары' : 'кар'} подряд` : 'нет';
    const next = n ? `следующая на ${Math.min(4, n)} ступ. тяжелее` : 'следующая кара обычной силы';
    return h('div', { title: `${next}. Сбрасывается неделей без кар или покаянием.` },
      h('div.row.nowrap', h('div.steps', { style: { width: '72px' } }, Array.from({ length: 4 }, (_, i) => h(`i${i < n ? '.done' : ''}`))), h('span', text)),
      h('div.muted', { style: { fontSize: '12px' } }, next));
  }
  function drawSouls(r) {
    const souls = r.souls || [];
    replace(playerSel, h('option', { value: '' }, 'все игроки'), souls.map(s => h('option', { value: pname(s.player), selected: pname(s.player) === prayerPlayer }, pname(s.player))));
    replace(soulsBox, souls.length ? table([
      { key: 'player', title: 'Игрок', value: s => pname(s.player).toLowerCase(), render: s => h('div.row.nowrap', avatar(pname(s.player)), h('div', h('div', { style: { fontWeight: 600 } }, pname(s.player)), s.banned && h('span.badge.bad', 'изгнан навечно'))) },
      { key: 'mercy', title: 'Милость', r: true, desc: true, render: s => h('div', mercyBadge(s.mercy ?? 0), s.label && h('div.muted', { style: { fontSize: '12px' } }, s.label)) },
      { key: 'sins', title: 'Грехи', r: true, desc: true, render: s => h('div', { title: (s.deeds || []).map(d => d.text).join('\n') || 'поступков не было' }, h(`span.badge${s.sins > 5 ? '.bad' : s.sins ? '.warn' : ''}`, fmt.num(s.sins))) },
      { key: 'ladder', title: 'Кары подряд', desc: true, render: s => ladderCell(s.ladder || 0) },
      { key: 'deeds', title: 'Что бог помнит', sortable: false, render: s => (s.deeds?.length ? h('div.muted', { style: { fontSize: '12.5px', maxWidth: '280px' } }, s.deeds.slice(0, 3).map(d => d.text).join('; ')) : h('span.muted', 'ничего')) },
      { key: 'curses', title: 'Проклятия', sortable: false, render: s => (s.curses?.length ? h('div.chips', s.curses.map(c => h('span.badge.warn', { title: curseHelp(c.title) }, c.title, ' ', h('span.num.countdown', { dataset: { until: c.until } }, fmt.countdown(c.until))))) : h('span.muted', 'нет')) },
      { key: 'act', title: 'Управление', sortable: false, render: s => soulActions(s) },
    ], souls, { sort: { key: 'sins', dir: -1 } }) : empty('Душ пока нет: никто не молился.'));
  }
  function soulActions(s) {
    const perPlayer = actions.filter(a => a.args?.some(x => x.key === 'player') && !SOUL_OWN.has(a.id) && a.id !== 'streamer');
    const own = [has('mercy') && ownButton('Милость…', () => mercyDialog(s)), has('act') && ownButton('Кара…', () => actDialog(s, 'wrath'), '.danger'),
      has('act') && ownButton('Благо…', () => actDialog(s, 'good'))].filter(Boolean);
    if (!perPlayer.length && !own.length) return null;
    return h('div.row', { style: { flexWrap: 'wrap', gap: '6px' } }, own, perPlayer.filter(a => a.id !== 'unban' || s.banned).map(a => {
      const btn = h(`button.btn.sm${a.danger ? '.danger' : ''}`, { type: 'button', title: a.help || '', onclick: async e => {
        e.stopPropagation();
        if (!(await confirm({ title: `${a.title}: ${pname(s.player)}?`, consequence: a.danger || 'Подействует сразу.', ok: a.title, danger: !!a.danger }))) return;
        busy(btn, async () => { const r = await api.post(`sources/divineblock/actions/${encodeURIComponent(a.id)}`, { args: { player: pname(s.player) } }); toast(r.message || 'Готово'); reloadSouls(); drawActive(); });
      } }, a.title);
      return btn;
    }));
  }
  function ownButton(title, run, cls = '') {
    const btn = h(`button.btn.sm${cls}`, { type: 'button' }, title);
    btn.addEventListener('click', e => { e.stopPropagation(); run(btn); });
    return btn;
  }
  const runAct = (args, done) => api.post('sources/divineblock/actions/' + args.id, { args: args.args }).then(r => { toast(r.message || 'Готово'); done?.(); })
    .catch(e => toast(e.message || String(e), 'bad'));
  async function mercyDialog(s) {
    const r = await ask({ title: `Милость: ${pname(s.player)}`, text: `Сейчас ${Math.round(s.mercy ?? 0)} (${s.label || '—'}). Шкала от −100 до +100; от неё зависит, карает бог или милует.`,
      fields: [{ key: 'mode', title: 'Что сделать', options: [{ value: '+', title: 'Прибавить' }, { value: '-', title: 'Отнять' }, { value: '=', title: 'Установить ровно' }], value: '+' },
        { key: 'n', title: 'Сколько', type: 'number', value: '10' }], ok: 'Применить' });
    if (!r) return;
    const n = Math.abs(Number(r.n));
    if (!Number.isFinite(n)) return toast('Нужно число', 'bad');
    runAct({ id: 'mercy', args: { player: pname(s.player), amount: r.mode === '=' ? `=${Number(r.n)}` : `${r.mode}${n}` } }, () => { reloadSouls(); });
  }
  async function actDialog(s, side) {
    const pick = [...deeds.values()].filter(d => (side === 'wrath' ? d.group === 'wrath' : d.group !== 'wrath'));
    const fields = [pick.length
      ? { key: 'deed', title: side === 'wrath' ? 'Кара' : 'Благо, шутка или знамение', options: pick.map(d => ({ value: d.id, title: `${side === 'wrath' ? '' : d.groupTitle + ': '}${d.title}${disabled.has(d.id) ? ' (выключено в настройках)' : ''}` })) }
      : { key: 'deed', title: 'Деяние (id)', placeholder: side === 'wrath' ? 'lightning' : 'heal' },
      { key: 'power', title: 'Сила', options: [1, 2, 3, 4].map(p => ({ value: String(p), title: `${p}${p === 1 ? ' — слабо' : p === 4 ? ' — сильнее некуда' : ''}` })), value: '2' }];
    const r = await ask({ title: `${side === 'wrath' ? 'Покарать' : 'Послать благо'}: ${pname(s.player)}`, fields, ok: side === 'wrath' ? 'Покарать' : 'Послать', danger: side === 'wrath',
      text: 'Падёт сразу, если игрок сейчас на сервере. Сила обрезается до того, что деяние умеет. Описание деяния — в справочнике «Что может бог» ниже.',
      consequence: side === 'wrath' ? 'Кара бьёт по-настоящему: может разрушить постройки и убить игрока.' : undefined });
    if (!r) return;
    runAct({ id: 'act', args: { player: pname(s.player), deed: r.deed, power: r.power } }, () => { drawActive(); drawPrayers(); });
  }
  const reloadSouls = () => api.get('divine/souls').then(drawSouls).catch(e => replace(soulsBox, errorBox(e, reloadSouls)));

  // ---------- активное ----------
  /** Действие источника divineblock с подтверждением; после — перерисовать активное и души. */
  async function godAction(btn, id, args, title, consequence) {
    if (!(await confirm({ title, consequence, ok: 'Да', danger: true }))) return;
    busy(btn, async () => { const r = await api.post(`sources/divineblock/actions/${encodeURIComponent(id)}`, { args }); toast(r.message || 'Готово'); drawActive(); reloadSouls(); });
  }
  const has = id => actions.some(a => a.id === id);
  function bossRow(b) {
    const pct = b.maxHealth ? b.health / b.maxHealth : 0;
    const remove = has('boss_remove') && b.uuid ? h('button.btn.sm', { type: 'button' }, 'Убрать') : null;
    remove?.addEventListener('click', () => godAction(remove, 'boss_remove', { uuid: b.uuid }, `Убрать «${b.title}»?`, 'Босс исчезнет без добычи и без вердикта.'));
    const where = b.dimension ? `${String(b.dimension).replace('minecraft:', '')} ${Math.round(b.x)} ${Math.round(b.y)} ${Math.round(b.z)}` : '';
    return h('div', h('div.row', h('b.grow', b.title, b.phase ? h('span.muted', ` · фаза ${b.phase}`) : null), remove),
      h('div.muted', { style: { fontSize: '13px' } }, [b.target && `охотится на ${pname(b.target)}`, where, `здоровье ${fmt.num(b.health)} из ${fmt.num(b.maxHealth)}`, `пришёл ${fmt.ago(b.since)}`].filter(Boolean).join(' · ')),
      h(`div.meter${pct < 0.3 ? '.bad' : ''}`, { style: { marginTop: '6px', height: '10px' }, role: 'meter', 'aria-label': `Здоровье ${b.title}`, 'aria-valuenow': Math.round(pct * 100), 'aria-valuemin': 0, 'aria-valuemax': 100 }, h('i', { style: { width: `${pct * 100}%`, background: 'var(--s8)' } })));
  }
  function godRow(state, title, id, button, consequence) {
    if (!state?.active) return null;
    const btn = has(id) ? h('button.btn.sm.danger', { type: 'button' }, button) : null;
    btn?.addEventListener('click', () => godAction(btn, id, {}, `${button}?`, consequence));
    return h('div.row', h('b.grow', title, state.player ? h('span.muted', ` · ${pname(state.player)}`) : null), btn);
  }
  const timed = (list, emptyText, help) => (list.length ? h('div.list', list.map(c => h('div.list-item', { style: { padding: '8px 0', alignItems: 'flex-start' } }, avatar(pname(c.player)),
    h('div.body', h('div', { style: { fontWeight: 600 } }, c.title, h('span.muted', { style: { fontWeight: 400 } }, ` · ${pname(c.player)}`)), help(c.title) && h('div.meta', help(c.title))),
    h('span.badge.warn.num.countdown', { dataset: { until: c.until }, title: 'осталось' }, fmt.countdown(c.until))))) : h('p.muted', emptyText));
  function drawActive() {
    load(activeBox, () => api.get('divine/active'), a => {
      drawTiles(a);
      const bosses = a.bosses || [], curses = a.curses || [], blessings = a.blessings || [];
      const clearAll = bosses.length && (has('boss_clear') || has('clear')) ? h('button.btn.sm.danger', { type: 'button' }, 'Убрать всех') : null;
      clearAll?.addEventListener('click', () => godAction(clearAll, has('boss_clear') ? 'boss_clear' : 'clear', {}, `Убрать всех боссов (${bosses.length})?`, 'Все священные боссы исчезнут сразу.'));
      const god = [godRow(a.godAvatar, 'Бог с булавой', 'god_recall', 'Отозвать бога', 'Аватар бога уйдёт, кара закончится.'),
        godRow(a.skyCourt, 'Небесный суд', 'court_end', 'Закончить суд', 'Суд закончится, игрок вернётся туда, откуда его забрали.')].filter(Boolean);
      const punishments = a.punishments || [];
      const running = h('section.card', h('div.card-head', h('h2', 'Кары идут сейчас'), h('span.badge', punishments.length)),
        note('Кара от вердикта до удара и ещё 10 секунд после (не дольше минуты). Пока идёт, новый грех не шлёт вторую кару, а утяжеляет эту.'),
        punishments.length ? h('div.list', punishments.map(p => h('div.list-item', { style: { padding: '8px 0', alignItems: 'flex-start' } }, avatar(pname(p.player)),
          h('div.body', h('div', { style: { fontWeight: 600 } }, p.title, h('span.muted', { style: { fontWeight: 400 } }, ` · ${pname(p.player)}`)),
            h('div.meta', `началась ${fmt.ago(p.since)}`, p.strengthened ? h('span.badge.bad', `утяжелена ×${p.strengthened}`) : null, p.moved ? h('span', 'игрока уже переносили') : null,
              p.deed && deedHelp(p.deed) ? h('span', deedHelp(p.deed)) : null)),
          h('span.badge.warn.num.countdown', { dataset: { until: p.until }, title: 'до конца' }, fmt.countdown(p.until))))) : h('p.muted', 'Сейчас никого не карают.'));
      return h('div.stack', running, h('div.grid.two',
        h('section.card', h('div.card-head', h('h2', 'Живые боссы'), h('span.badge', bosses.length), clearAll),
          note('Священные боссы и сам бог, посланные за грехи. Убранный босс исчезает без добычи.'),
          god.length ? h('div.stack', { style: { marginBottom: '12px' } }, god) : null,
          bosses.length ? h('div.stack', bosses.map(bossRow)) : h('p.muted', 'Боссов нет — бог спокоен.')),
        h('div.stack',
          h('section.card', h('div.card-head', h('h2', 'Проклятия сейчас'), h('span.badge', curses.length)),
            note('Кары, которые ещё действуют. Справа — сколько осталось; покаяние снимает их раньше.'), timed(curses, 'Никто не проклят.', curseHelp)),
          h('section.card', h('div.card-head', h('h2', 'Блага сейчас'), h('span.badge', blessings.length)),
            note('Долгие милости бога: сила, скорость, благословение.'), timed(blessings, 'Благ на время нет.', () => '')))));
    }, { lines: 4 });
  }

  // ---------- молитвы ----------
  function prayerRow(p) {
    const [kind, cls] = CHOICE[p.choice?.kind] || [p.choice?.kind || '', ''];
    const reasons = (p.reasons || []).map(r => `${r.title}${r.mercy ? ` (${r.mercy > 0 ? '+' : ''}${r.mercy} ${r.mercy > 0 ? 'к благу' : 'к каре'})` : ''}${r.power ? ` (сила ${r.power > 0 ? '+' : ''}${r.power})` : ''}`).join('; ');
    const help = p.choice?.deed && deedHelp(p.choice.deed);
    return h('div.list-item', { style: { alignItems: 'flex-start' } }, avatar(pname(p.player)),
      h('div.body',
        h('div.row', h('b', pname(p.player)), p.toneTitle && h(`span.badge${TONE[p.tone] || ''}`, { title: 'тон молитвы' }, p.toneTitle),
          p.offering && h('span.badge', { title: 'что лежало на алтаре' }, `подношение: ${p.offering.replace('minecraft:', '')}${p.offeringValue ? ` (ценность ${p.offeringValue})` : ''}`)),
        h('div', `«${p.text}»`),
        p.answer && h('div.muted', `Бог: «${p.answer}»`),
        h('div.meta', kind && h(`span.badge${cls}`, kind), p.choice?.deedTitle && h('span', { title: help || '', style: help ? { textDecoration: 'underline dotted', cursor: 'help' } : {} }, p.choice.deedTitle),
          p.choice?.power ? h('span', `сила ${p.choice.power} из 4`) : null,
          p.choice?.decidedBy && h('span', { jev: 'решила нейросеть Jev', admin: 'послал админ' }[p.choice.decidedBy] || 'решили правила'), p.choice?.note && h('span', p.choice.note)),
        help && h('div.muted', { style: { fontSize: '12px' } }, help),
        reasons && h('div.muted', { style: { fontSize: '12px' } }, 'Почему (склоняет этот ответ, милость души не меняет): ', reasons),
        p.decision && decisionBox(p.decision, p.choice)),
      h('time.time', { datetime: new Date(p.t).toISOString(), title: fmt.dateTimeFull(p.t) }, fmt.ago(p.t)));
  }
  /** «Как решал бог»: итог причин, склонность и ступень, бюджет силы и вероятности групп — Jev, лестница и итог. */
  function decisionBox(d, choice) {
    const ids = Object.keys(d.odds || {}).length ? Object.keys(d.odds) : Object.keys(d.ladder || {});
    const pct = v => (v == null ? '—' : `${Math.round(v * 100)}%`);
    const bar = (v, color) => h('div.meter', { style: { height: '6px', minWidth: '60px' } }, h('i', { style: { width: `${Math.round((v || 0) * 100)}%`, background: color } }));
    const jev = d.jev && Object.keys(d.jev).length;
    const facts = [
      ['Итог причин', `${d.favor > 0 ? '+' : ''}${d.favor} ${d.favor >= 0 ? 'к благу' : 'к каре'}, сила ${d.force}`],
      ['Склонность', `${d.inclination || '—'}${d.fury ? ' (ярость)' : ''}${d.forgiveness ? ' (прощение)' : ''}`],
      ['Ступень', `${d.level} из 10`], ['Бюджет силы', `${d.budget} из 4`], ['Кар подряд', d.escalation ? `+${d.escalation} ступ.` : 'нет']];
    return h('details', { style: { marginTop: '6px' } },
      h('summary', { style: { cursor: 'pointer', fontSize: '12.5px', color: 'var(--text-2)' } }, 'Как решал бог'),
      h('div', { style: { fontSize: '12.5px', marginTop: '6px' } },
        h('div.chips', facts.map(([k, v]) => h('span.badge', { title: k }, `${k}: ${v}`))),
        ids.length ? h('table', { style: { marginTop: '8px', borderCollapse: 'collapse', width: '100%', maxWidth: '560px' } },
          h('thead', h('tr', ['Группа', jev ? 'Jev' : null, 'Лестница', 'Итог'].filter(Boolean).map(t => h('th', { style: { textAlign: 'left', fontWeight: 600, padding: '2px 6px' } }, t)))),
          h('tbody', ids.map(id => h('tr', { style: id === d.group || (id === 'silence' && choice?.kind === 'none') ? { background: 'var(--accent-soft)' } : {} },
            h('td', { style: { padding: '2px 6px' } }, d.labels?.[id] || id, id === d.group ? ' ✓' : ''),
            jev ? h('td', { style: { padding: '2px 6px' } }, h('div.row.nowrap', bar(d.jev[id], 'var(--s5)'), pct(d.jev[id]))) : null,
            h('td', { style: { padding: '2px 6px' } }, h('div.row.nowrap', bar(d.ladder?.[id], 'var(--s6)'), pct(d.ladder?.[id]))),
            h('td', { style: { padding: '2px 6px' } }, h('div.row.nowrap', bar(d.odds?.[id], 'var(--s8)'), h('b', pct(d.odds?.[id])))))))) : h('div.muted', { style: { marginTop: '6px' } }, 'Выбора группы не было: знамение, прощение или молчание по правилам.'),
        h('div.muted', { style: { marginTop: '6px' } }, jev
          ? 'Итог = половина мнения Jev + половина лестницы (колокол вокруг ступени); из итога бог тянет жребий. ✓ — что выпало.'
          : 'Jev не участвовал: решала лестница (колокол вокруг ступени), из неё бог тянет жребий. ✓ — что выпало.')));
  }
  function drawPrayers() {
    load(prayersBox, () => api.get('divine/prayers', { player: prayerPlayer, limit: 50 }), r => (r.items?.length ? r.items.map(prayerRow) : empty(prayerPlayer ? 'Этот игрок ещё не молился.' : 'Молитв ещё не было.')), { lines: 4 });
  }

  const timer = setInterval(() => root.querySelectorAll('.countdown').forEach(el => (el.textContent = fmt.countdown(+el.dataset.until))), 1000);
  cleanup.push(() => clearInterval(timer));

  // ---------- статистика ----------
  async function drawStats() {
    charts.splice(0).forEach(c => c.destroy());
    replace(statBox, skeleton(1, 180));
    replace(topBox, skeleton(4));
    const to = Date.now(), from = to - rangeMs(range), bucket = rangeMs(range) > 2 * 86400e3 ? 'day' : 'hour';
    try {
      const r = await api.get('history/stats', { kind: 'verdict', by: 'data.act', bucket, from, to });
      replace(statBox);
      const keys = Object.keys(r.series || {}).sort((a, b) => Object.keys(ACT).indexOf(a) - Object.keys(ACT).indexOf(b));
      charts.push(timeChart(statBox, { t: r.t || [], series: keys.map(k => ({ label: ACT[k]?.[0] || k, data: r.series[k], slot: ACT[k]?.[1] })), bars: true, stacked: true, total: true,
        daily: bucket === 'day', yMin: 0, emptyText: 'За период бог никому не отвечал' }));
    } catch (e) { replace(statBox, errorBox(e, drawStats)); }
    try {
      const r = await api.get('history/stats', { kind: 'verdict', by: 'data.deed', bucket: 'day', from, to });
      const rows = Object.entries(r.series || {}).map(([deed, data]) => [deed, data.reduce((x, y) => x + y, 0)]).filter(([deed, n]) => n && deed !== '—' && deed !== 'null').sort((a, b) => b[1] - a[1]).slice(0, 8);
      const max = rows[0]?.[1] || 1;
      const byTitle = new Map([...deeds.values()].map(d => [d.title, d]));
      replace(topBox, rows.length ? h('div.stack', rows.map(([deed, n]) => {
        const d = byTitle.get(deed), tone = d?.group === 'wrath' ? 'var(--s8)' : d?.group === 'miracle' ? 'var(--s3)' : 'var(--s6)';
        return h('div', { title: d ? deedHelp(d.id) : '' }, h('div.row', h('span.grow', deed, d && h('span.muted', ` · ${d.groupTitle.toLowerCase()}`)), h('b.num', fmt.num(n))),
          h('div.meter', { style: { height: '8px', marginTop: '4px' }, role: 'meter', 'aria-label': deed, 'aria-valuenow': n, 'aria-valuemin': 0, 'aria-valuemax': max }, h('i', { style: { width: `${(n / max) * 100}%`, background: tone } })));
      })) : empty('За период деяний не было.'));
    } catch (e) { replace(topBox, errorBox(e, drawStats)); }
  }

  // ---------- справочник ----------
  function drawCatalog() {
    if (!deeds.size) { replace(catalogBox); return; }
    const groups = new Map();
    for (const d of deeds.values()) { if (!groups.has(d.group)) groups.set(d.group, { title: d.groupTitle, list: [] }); groups.get(d.group).list.push(d); }
    const intro = { wrath: 'Наказания. Те, что «у дома», бьют по дому игрока, «мировые» — по всем.', miracle: 'Помощь тем, кто просил смиренно и жил праведно.', jest: 'Безобидные розыгрыши: смешно, но не смертельно.', omen: 'Знаки без последствий: бог отвечает, но ничего не меняет.' };
    replace(catalogBox, h('details.group',
      h('summary', icon('chevron', 'chev'), h('span.grow', 'Что может бог'), h('span.count', `${deeds.size} деяний${disabled.size ? `, выключено ${disabled.size}` : ''}`)),
      h('div', { style: { borderTop: '1px solid var(--border)', padding: '6px 16px 14px' } },
        note('Полный список кар, благ, шуток и знамений. Выключить деяние можно в «Настройках» → «Божественный блок» → «Деяния бога».'),
        [...groups].map(([id, g]) => h('div', { style: { marginTop: '12px' } }, h('h3', { style: { margin: '0 0 2px', fontSize: '14px' } }, `${g.title} (${g.list.length})`),
          intro[id] && h('div.muted', { style: { fontSize: '12.5px', marginBottom: '6px' } }, intro[id]),
          h('div.list', g.list.map(d => h('div.list-item', { style: { padding: '6px 0', alignItems: 'flex-start' } },
            h('div.body', h('div', { style: { fontWeight: 600 } }, d.title, disabled.has(d.id) && h('span.badge', { style: { marginLeft: '6px' } }, 'выключено')),
              deedHelp(d.id) && h('div.meta', deedHelp(d.id)))))))))));
  }

  cleanup.push(on('history', rec => { if (rec?.kind === 'verdict' || rec?.kind === 'boss' || rec?.kind === 'ban') { if (soulsBox.isConnected) { reloadSouls(); drawActive(); drawPrayers(); if (rec.kind === 'verdict') loadVerdicts(); } } }));
  return { destroy() { cleanup.forEach(f => f()); charts.forEach(c => c.destroy()); } };
}
