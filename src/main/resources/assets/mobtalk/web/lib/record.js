// Записи истории: названия видов, строка ленты и карточка записи с трассой диалога.
import { h, icon } from './dom.js';
import { api } from './api.js';
import * as fmt from './fmt.js';
import { drawer, load, kv, copyText } from './ui.js';

export const KINDS = {
  dialogue: { title: 'Диалог', icon: 'mobs', k: 1 },
  witness: { title: 'Очевидец', icon: 'search', k: 5 },
  village_talk: { title: 'Разговор в деревне', icon: 'players', k: 3 },
  gift: { title: 'Подарок', icon: 'heart', k: 8 },
  trade: { title: 'Торг', icon: 'costs', k: 3 },
  relation_change: { title: 'Отношения', icon: 'heart', k: 5 },
  rumor: { title: 'Слух', icon: 'info', k: 7 },
  verdict: { title: 'Вердикт бога', icon: 'divine', k: 7 },
  confession: { title: 'Исповедь', icon: 'divine', k: 5 },
  ban: { title: 'Бан', icon: 'warn', k: 8 },
  boss: { title: 'Босс', icon: 'divine', k: 2 },
  scene: { title: 'Сцена с жителем', icon: 'mobs', k: 1 },
  wedding: { title: 'Свадьба', icon: 'heart', k: 5 },
  moment: { title: 'Момент', icon: 'moments', k: 4 },
  moment_edit: { title: 'Правка момента', icon: 'moments' },
  rec_session: { title: 'Сессия записи', icon: 'play' },
  player_join: { title: 'Вход игрока', icon: 'players' },
  player_leave: { title: 'Выход игрока', icon: 'players' },
  death: { title: 'Смерть игрока', icon: 'warn', k: 8 },
  parrot_jailed: { title: 'Попугай в клетке', icon: 'warn', k: 5 },
  parrot_released: { title: 'Попугай на свободе', icon: 'check', k: 3 },
  config_change: { title: 'Настройка', icon: 'settings', k: 1 },
  error: { title: 'Ошибка', icon: 'warn', k: 8 },
  command: { title: 'Команда', icon: 'server' },
  admin_login: { title: 'Вход в админку', icon: 'logout' },
  cost: { title: 'Расход', icon: 'costs', k: 2 },
};
export const kindTitle = k => KINDS[k]?.title || k;

export const SPECIES = {
  villager: 'житель', cat: 'кошка', wolf: 'волк', cow: 'корова', pig: 'свинья', sheep: 'овца', chicken: 'курица', parrot: 'попугай',
  horse: 'лошадь', donkey: 'осёл', llama: 'лама', fox: 'лиса', iron_golem: 'железный голем', wandering_trader: 'странствующий торговец',
  allay: 'тихоня', bee: 'пчела', goat: 'коза', rabbit: 'кролик', axolotl: 'аксолотль', frog: 'лягушка', camel: 'верблюд', mca_villager: 'житель MCA',
};
export const speciesTitle = s => SPECIES[String(s || '').replace(/^minecraft:/, '')] || String(s || '').replace(/^minecraft:/, '').replaceAll('_', ' ');
export const pname = p => (p == null ? '' : typeof p === 'string' ? p : p.name || p.uuid || '');

export function kindIcon(kind) {
  const k = KINDS[kind] || {};
  return h(`span.kind-ico${k.k ? '.k' + k.k : ''}`, icon(k.icon || 'info'));
}

/** Строка ленты: значок вида, текст, кто/с кем, время. */
export function recordRow(rec, { onOpen } = {}) {
  const usd = rec.data?.usd;
  return h('button.list-item', { type: 'button', onclick: () => (onOpen || openRecord)(rec) },
    kindIcon(rec.kind),
    h('div.body',
      h('div.line', rec.text || kindTitle(rec.kind)),
      h('div.meta', h('span', kindTitle(rec.kind)), rec.player && h('span', pname(rec.player)), rec.mob && h('span', `${pname(rec.mob)}${rec.mob.species ? ' · ' + speciesTitle(rec.mob.species) : ''}`),
        usd ? h('span.num', fmt.usd(usd)) : null, rec.data?.error ? h('span.badge.bad', 'ошибка') : null)),
    h('time.time', { datetime: new Date(rec.t).toISOString(), title: fmt.dateTimeFull(rec.t) }, fmt.ago(rec.t)));
}

const STEP_TITLE = {
  accepted: 'принят', budget: 'бюджет', context: 'контекст', prompt: 'промпт собран', llm_first: 'LLM: первый токен', llm_done: 'LLM: конец',
  parsed: 'разбор', actions: 'действия', tts: 'озвучка', delivered: 'доставлено',
};
export const stepTitle = s => STEP_TITLE[s] || s;
export const STEPS = Object.keys(STEP_TITLE);

/** Трасса: полоска каждого шага от конца предыдущего до своего времени. */
export function traceView(trace) {
  const steps = trace?.steps || [];
  if (!steps.length) return h('p.muted', 'Трассы нет.');
  const total = Math.max(...steps.map(s => s.ms), 1);
  let prev = 0;
  const rows = steps.map(s => {
    const from = prev, to = s.ms; prev = s.ms;
    const cls = trace.error?.step === s.step ? '.err' : s.step.startsWith('llm') ? '.llm' : s.step === 'tts' ? '.tts' : '';
    return h(`div.trace-row${cls}`,
      h('span.ellipsis', stepTitle(s.step)),
      h('span.track', { title: `${fmt.ms(to - from)} (с ${fmt.ms(from)} по ${fmt.ms(to)})` }, h('i', { style: { left: `${(from / total) * 100}%`, width: `${Math.max(0.6, ((to - from) / total) * 100)}%` } })),
      h('span.ms', `+${fmt.ms(to - from)}`));
  });
  const sz = trace.sizes || {};
  return h('div.stack',
    h('div.trace', { role: 'list', 'aria-label': 'Шаги диалога' }, rows),
    h('div.row.muted', { style: { fontSize: '13px' } },
      h('span', `Всего ${fmt.ms(total)}`),
      sz.promptChars != null && h('span', `· промпт ${fmt.num(sz.promptChars)} симв.`),
      sz.tokensIn != null && h('span', `· токены ${fmt.num(sz.tokensIn)} → ${fmt.num(sz.tokensOut)}`),
      sz.audioBytes ? h('span', `· звук ${fmt.bytes(sz.audioBytes)}`) : null),
    trace.error && h('div.notice', icon('warn'), h('span', typeof trace.error === 'object' ? `${stepTitle(trace.error.step)}: ${trace.error.message}` : trace.error)));
}

const STT = { local: 'Whisper у игрока', server: 'OpenRouter через сервер', 'server-local': 'Whisper на сервере', text: 'текстом' };

function dataView(rec) {
  const d = rec.data || {};
  const known = {
    dialogue: () => [
      h('div.stack', h('div', h('h4', 'Вопрос'), h('p', d.question || '—')), h('div', h('h4', 'Ответ'), h('p', d.answer || '—'))),
      kv([['Модель', d.model], ['Распознавание', STT[d.stt] || d.stt], ['Действия', (d.actions || []).join(', ') || 'нет'], ['Стоимость', fmt.usd(d.usd)],
        ['Токены', d.tokensIn != null ? `${fmt.num(d.tokensIn)} → ${fmt.num(d.tokensOut)}` : null], ['Всего', fmt.ms(d.ms)], d.error && ['Ошибка', d.error]]),
      h('div', h('h3', { style: { marginBottom: '10px' } }, 'Трасса'), traceView(d.trace)),
      d.prompt != null ? h('details', h('summary', 'Промпт целиком'), h('div.row', { style: { margin: '8px 0' } }, h('button.btn.sm', { type: 'button', onclick: () => copyText(d.prompt) }, icon('copy', 'sm'), 'Скопировать')), h('pre.block', d.prompt))
        : h('p.muted', { style: { fontSize: '13px' } }, 'Промпт и сырой ответ не сохраняются: включите «debug.keepPrompts» в настройках, чтобы видеть их у новых диалогов.'),
      d.raw != null && h('details', h('summary', 'Сырой ответ модели'), h('pre.block', d.raw)),
    ],
    error: () => [kv([['Тип', d.type], ['Где', d.where], ['Повторов', d.repeat], ['Контекст', d.context]]), d.message && h('p', d.message),
      d.stack && h('div.stack', h('div.row', h('h4.grow', 'Стек'), h('button.btn.sm', { type: 'button', onclick: () => copyText(d.stack) }, icon('copy', 'sm'), 'Скопировать')), h('pre.stack', d.stack))],
    command: () => [kv([['Команда', h('code', d.command)], ['Кто', d.by], ['Результат', d.result]]), d.output?.length ? h('pre.block', d.output.join('\n')) : null],
    config_change: () => [kv([['Источник', d.source], ['Ключ', h('code', d.key)], ['Было', fmt.value(d.before)], ['Стало', fmt.value(d.after)], ['Кто', d.by], d.rollbackOf && ['Откат записи', d.rollbackOf]])],
    confession: () => [kv([['Священник', d.priest], ['Где', d.place === 'temple' ? 'храм' : 'село'], ['Возвращение после епитимьи', d.returning ? 'да' : null],
      ['Неотпущенных дел', d.deeds], ['Названо', d.named], ['Отпущено', d.absolved], ['До епитимьи', d.deferred], ['Скрыто', d.concealed], ['Искренность', d.sincerity],
      ['Разрешение', d.refused ? 'нет (глумление)' : 'дано'], ['Епитимья', d.penance], ['Снято проклятий', d.curses], ['Милость', d.mercyBefore != null ? `${d.mercyBefore} → ${d.mercyAfter}` : null],
      ['Бог ответил', d.god === false ? 'нет (числа не применены)' : null], ['Слова', d.words], ['Наставление', d.counsel]].filter(([, v]) => v !== null && v !== undefined && v !== ''))],
    verdict: () => [kv([['Молитва', d.prayer], ['Вердикт', d.verdict], ['Итог', { wrath: 'кара', miracle: 'благо', warning: 'знамение', jest: 'шутка', none: 'молчание' }[d.act] || d.act], ['Тон', d.tone], ['Деяние', d.deed], ['Сила', d.power], ['Ярость', d.fury]])],
  };
  if (known[rec.kind]) return known[rec.kind]();
  const pairs = Object.entries(d).filter(([k]) => k !== 'trace').map(([k, v]) => [k, fmt.value(v)]);
  return pairs.length ? [kv(pairs)] : [];
}

/** Карточка записи истории в боковой шторке; полные данные подгружаются по id. */
export function openRecord(rec) {
  const d = drawer(kindTitle(rec.kind));
  load(d.body, () => api.get(`history/${encodeURIComponent(rec.id)}`), full => (d.setTitle(kindTitle(full.kind)), [
    h('div.row', kindIcon(full.kind), h('div.grow', h('div', { style: { fontWeight: 600 } }, full.text), h('div.muted', { style: { fontSize: '13px' } }, fmt.dateTimeFull(full.t)))),
    kv([full.player && ['Игрок', h('a', { href: `#/players/${encodeURIComponent(full.player.uuid || '')}`, onclick: () => d.close() }, pname(full.player))],
      full.mob && ['Моб', h('a', { href: `#/mobs/${encodeURIComponent(full.mob.uuid || '')}`, onclick: () => d.close() }, `${pname(full.mob)} · ${speciesTitle(full.mob.species)}`)]]),
    ...dataView(full),
    h('details', h('summary.muted', 'JSON записи'), h('pre.block', JSON.stringify(full, null, 2))),
  ]), { lines: 6 });
  return d;
}
