// 3D-карта (BlueMap) во вкладке «Игроки → 3D». BlueMap рендерит только пока вкладка открыта: страница раз в минуту шлёт
// keepalive, сервер ставит рендер на паузу через 3 минуты тишины. Сама карта — страница BlueMap из /map3d/ в iframe.
import { h, icon, replace } from '../lib/dom.js';
import { api } from '../lib/api.js';
import * as fmt from '../lib/fmt.js';
import { errorBox, skeleton } from '../lib/ui.js';

const NOT_YET = { status: 503, message: '3D-карта не подключена: на сервере нет BlueMap или админка ещё не умеет её показывать.' };

export function map3dTab(box) {
  let stopped = false;
  const state = h('div.row', { style: { gap: '8px', marginBottom: '12px', fontSize: '13.5px' } });
  const frameBox = h('section.card.flush.map3d-card', skeleton(1, 400));
  replace(box, state, frameBox);

  function paint(s) {
    replace(state,
      s.loading ? h('span.badge.warn', 'BlueMap загружается…') : h(`span.badge${s.rendering ? '.good' : ''}`, s.rendering ? 'рендер идёт' : 'рендер на паузе'),
      s.progress != null && s.progress < 1 ? h('span.text-2', `готово ${fmt.num(s.progress * 100)}%`) : null,
      h('span.muted', s.lastRender ? `карта на ${fmt.ago(s.lastRender)}` : 'карта ещё не отрисована'),
      h('span.spacer'),
      h('span.muted', 'Нагрузка только пока вкладка открыта.'),
      h('a.btn.sm.ghost', { href: '/map3d/', target: '_blank', rel: 'noopener' }, icon('chevron', 'sm'), 'Во весь экран'));
  }
  async function keepalive() {
    if (stopped || document.visibilityState !== 'visible') return;
    try { const r = await api.post('map3d/keepalive'); last = { ...last, rendering: r.rendering ?? true, loading: r.loading ?? last.loading }; paint(last); } catch { /* статус покажет */ }
  }
  let last = {};
  async function status(first) {
    try {
      const s = await api.get('map3d/status');
      if (stopped) return;
      if (!s.available) throw NOT_YET;
      last = s;
      paint(s);
      if (first) {
        replace(frameBox, h('iframe.map3d', { src: '/map3d/', title: '3D-карта мира', loading: 'lazy', referrerpolicy: 'no-referrer', allow: 'fullscreen' }));
        keepalive();
      }
    } catch (e) {
      if (stopped) return;
      if (!first) return;
      const off = e === NOT_YET || e.status === 404 || e.status === 503;
      replace(state);
      replace(frameBox, errorBox(off ? NOT_YET : e, () => status(true)));
      if (off) stopped = true;
    }
  }
  status(true);
  const ka = setInterval(keepalive, 60_000);
  const st = setInterval(() => !stopped && document.visibilityState === 'visible' && status(false), 15_000);
  const onVis = () => { if (document.visibilityState === 'visible') keepalive(); };
  document.addEventListener('visibilitychange', onVis);
  return () => { stopped = true; clearInterval(ka); clearInterval(st); document.removeEventListener('visibilitychange', onVis); };
}
