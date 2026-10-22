// Сейчас: живые диалоги по SSE (шаг, таймер, отмена) и чем заняты игроки онлайн.
import { h, icon, replace, avatar } from '../lib/dom.js';
import { api } from '../lib/api.js';
import { Stream } from '../lib/sse.js';
import * as fmt from '../lib/fmt.js';
import { confirm, busy, toast, empty, skeleton } from '../lib/ui.js';
import { STEPS, stepTitle, pname, speciesTitle } from '../lib/record.js';

const ACTIVITY = { talking: ['говорит', 'accent'], trading: ['торгуется', 'good'], praying: ['молится', 'warn'], idle: ['свободен', ''] };

export default function live(root) {
  const list = h('div.grid.three', skeleton(2, 120));
  const people = h('div.list', skeleton(3));
  const state = h('span.badge.warn', 'подключение…');
  replace(root,
    h('div.page-head', h('h1', 'Сейчас'), state),
    h('div.stack.lg',
      h('section', h('h2.section-title', 'Диалоги'), h('div', { style: { marginTop: '12px' } }, list)),
      h('section.card.flush', h('div.card-head', { style: { padding: '14px 16px 0' } }, h('h2', 'Игроки онлайн')), people)));

  let dialogues = [];
  const timers = new Map();
  function card(d) {
    const idx = STEPS.indexOf(d.step);
    const total = h('span.timer'), step = h('span.timer');
    timers.set(d.id, { d, total, step });
    const cancel = h('button.btn.sm.danger', { type: 'button' }, icon('close', 'sm'), 'Отменить');
    cancel.addEventListener('click', async () => {
      const ok = await confirm({ title: 'Отменить диалог?', text: `${pname(d.player)} → ${pname(d.mob)}`, consequence: 'Моб не ответит, игрок увидит «разговор прерван». Уже потраченное на запрос не вернётся.', ok: 'Отменить диалог', danger: true });
      if (ok) busy(cancel, async () => { await api.post(`live/dialogues/${encodeURIComponent(d.id)}/cancel`); toast('Диалог отменён'); });
    });
    return h('article.card.live-card', { 'aria-label': `Диалог ${pname(d.player)} с ${pname(d.mob)}` },
      h('div.row.nowrap', avatar(pname(d.player)),
        h('div.grow', h('div.ellipsis', { style: { fontWeight: 600 } }, `${pname(d.player)} → ${pname(d.mob)}`), h('div.muted', { style: { fontSize: '13px' } }, speciesTitle(d.mob?.species))),
        total),
      d.question && h('p.text-2', `«${d.question}»`),
      h('div.steps', { role: 'progressbar', 'aria-valuemin': 0, 'aria-valuemax': STEPS.length, 'aria-valuenow': idx + 1, 'aria-valuetext': stepTitle(d.step) },
        STEPS.map((s, i) => h(`i${i < idx ? '.done' : i === idx ? '.now' : ''}`, { title: stepTitle(s) }))),
      h('div.row', h('span.badge.accent', stepTitle(d.step)), h('span.muted', { style: { fontSize: '13px' } }, 'на шаге'), step, h('span.spacer'), cancel));
  }
  function tick() {
    const now = Date.now();
    for (const { d, total, step } of timers.values()) {
      const a = now - d.startedAt, b = now - d.stepSince;
      total.textContent = fmt.ms(a);
      step.textContent = fmt.ms(b);
      step.className = `timer${b > 15000 ? ' stuck' : b > 5000 ? ' slow' : ''}`;
    }
  }
  function draw(snap) {
    dialogues = snap.dialogues || [];
    timers.clear();
    replace(list, dialogues.length ? dialogues.map(card) : h('div.card', empty('Сейчас никто не разговаривает с мобами.')));
    const acts = snap.activities || [];
    replace(people, acts.length ? acts.map(a => {
      const [title, tone] = ACTIVITY[a.activity] || [a.activity, ''];
      return h('a.list-item', { href: `#/players/${encodeURIComponent(a.player?.uuid || '')}` }, avatar(pname(a.player)),
        h('div.body', h('div', { style: { fontWeight: 600 } }, pname(a.player))), h(`span.badge${tone ? '.' + tone : ''}`, title));
    }) : empty('Онлайн никого нет.'));
    tick();
  }
  const timer = setInterval(tick, 250);
  const stream = new Stream('live/dialogues', { snapshot: draw }, {
    onState: s => { state.className = `badge ${s === 'ok' ? 'good' : s === 'wait' ? 'warn' : 'bad'}`; state.textContent = s === 'ok' ? 'вживую' : s === 'wait' ? 'подключение…' : 'нет связи, повторяю'; },
  });
  return { destroy() { clearInterval(timer); stream.close(); } };
}
