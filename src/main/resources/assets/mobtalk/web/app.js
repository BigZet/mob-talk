// Веб-админка Mob Talk: вход, каркас, меню, маршруты в хэше (#/раздел/id?фильтры — ссылку можно отправить), общий поток событий.
import { h, icon, replace } from './lib/dom.js';
import { api, onUnauthorized } from './lib/api.js';
import { Stream, emit, on } from './lib/sse.js';
import { sheet, toast } from './lib/ui.js';

export const SECTIONS = [
  { id: 'overview', title: 'Обзор', icon: 'overview' },
  { id: 'live', title: 'Сейчас', icon: 'live' },
  { id: 'history', title: 'История', icon: 'history' },
  { id: 'players', title: 'Игроки', icon: 'players' },
  { id: 'mobs', title: 'Мобы и деревни', short: 'Мобы', icon: 'mobs' },
  { id: 'settings', title: 'Настройки', icon: 'settings' },
  { id: 'divine', title: 'Бог', icon: 'divine' },
  { id: 'costs', title: 'Расходы', icon: 'costs' },
  { id: 'debug', title: 'Отладка', icon: 'debug' },
  { id: 'server', title: 'Сервер', icon: 'server' },
];
const PHONE_NAV = ['overview', 'live', 'history', 'players'];

// ---------- маршрут ----------
export function parseRoute() {
  const raw = location.hash.replace(/^#\/?/, '');
  const [path, qs = ''] = raw.split('?');
  const [section, ...rest] = path.split('/').map(decodeURIComponent);
  return { section: SECTIONS.some(s => s.id === section) ? section : 'overview', id: rest.join('/') || null, params: Object.fromEntries(new URLSearchParams(qs)) };
}
function hashOf(section, id, params = {}) {
  const q = new URLSearchParams(Object.entries(params).filter(([, v]) => v != null && v !== ''));
  const s = q.toString();
  return `#/${section}${id ? '/' + encodeURIComponent(id) : ''}${s ? '?' + s : ''}`;
}
export function navigate(section, id = null, params = {}) { location.hash = hashOf(section, id, params); }
/** Записать фильтры раздела в хэш без перерисовки (ссылка остаётся точной). */
export function setParams(params, id = current.route?.id) {
  const r = parseRoute();
  const merged = { ...r.params, ...params };
  history.replaceState(null, '', hashOf(r.section, id, merged));
  if (current.route) current.route = parseRoute();
}

// ---------- тема ----------
const THEMES = ['auto', 'light', 'dark'];
const THEME_TITLE = { auto: 'Тема: как в системе', light: 'Тема: светлая', dark: 'Тема: тёмная' };
const THEME_ICON = { auto: 'auto', light: 'sun', dark: 'moon' };
function getTheme() { try { return localStorage.getItem('mt-theme') || 'auto'; } catch { return 'auto'; } }
function setTheme(t) {
  try { localStorage.setItem('mt-theme', t); } catch { /* без хранилища тема живёт до перезагрузки */ }
  if (t === 'auto') document.documentElement.removeAttribute('data-theme'); else document.documentElement.setAttribute('data-theme', t);
  emit('theme', t);
}

// ---------- приложение ----------
const app = document.getElementById('app');
const current = { route: null, section: null, view: null, el: null };
let events = null;
let errorsBadge = 0;

function navLink(s, cls = '') {
  const a = h(`a${cls}`, { href: `#/${s.id}`, dataset: { section: s.id } }, icon(s.icon), h('span', cls === '.sheet-link' ? s.title : s.short || s.title));
  if (s.id === 'debug') a.append(h('span.navbadge', { hidden: true, 'aria-label': 'новые ошибки' }));
  return a;
}

function shell() {
  const themeBtn = h('button.btn.icon.ghost', { type: 'button' });
  const paintTheme = () => { const t = getTheme(); replace(themeBtn, icon(THEME_ICON[t])); themeBtn.title = THEME_TITLE[t]; themeBtn.setAttribute('aria-label', `${THEME_TITLE[t]}. Сменить`); };
  themeBtn.addEventListener('click', () => { setTheme(THEMES[(THEMES.indexOf(getTheme()) + 1) % 3]); paintTheme(); });
  paintTheme();
  const conn = h('span.conn.wait', { role: 'status', 'aria-live': 'polite' }, 'подключение…');
  const more = h('button', { type: 'button', 'aria-haspopup': 'dialog', dataset: { section: 'more' }, onclick: openMore }, icon('more'), h('span', 'Ещё'), h('span.navbadge', { hidden: true }));
  const main = h('main.main#main', { tabindex: '-1' });
  replace(app,
    h('nav.sidebar', { 'aria-label': 'Разделы' },
      h('a.brand', { href: '#/overview' }, h('img', { src: 'icon.svg', alt: '' }), h('span', 'Mob Talk', h('br'), h('small', 'админка сервера'))),
      SECTIONS.map(s => navLink(s)),
      h('div.foot', h('span.server-line', ''))),
    h('div.col',
      h('header.topbar',
        h('a.brand.phone', { href: '#/overview', 'aria-label': 'Mob Talk, обзор' }, h('img', { src: 'icon.svg', alt: '' })),
        h('span.title.grow.ellipsis', ''),
        conn, themeBtn,
        h('button.btn.icon.ghost', { type: 'button', title: 'Выйти', 'aria-label': 'Выйти', onclick: logout }, icon('logout'))),
      main),
    h('nav.bottomnav', { 'aria-label': 'Разделы' }, PHONE_NAV.map(id => navLink(SECTIONS.find(s => s.id === id))), more));
  app.removeAttribute('aria-busy');
  return { main, conn };
}

function openMore() {
  const dlg = sheet(h('nav.sheet-nav', { 'aria-label': 'Все разделы' }, SECTIONS.map(s => {
    const a = navLink(s, '.sheet-link');
    a.addEventListener('click', () => dlg.close());
    return a;
  })), 'Все разделы');
  markNav();
}

function markNav() {
  const sec = current.route?.section;
  document.querySelectorAll('[data-section]').forEach(a => {
    const here = a.dataset.section === sec || (a.dataset.section === 'more' && !PHONE_NAV.includes(sec));
    if (here) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
  document.querySelectorAll('[data-section] .navbadge, [data-section="more"] .navbadge').forEach(b => {
    const owner = b.parentElement.dataset.section;
    const show = errorsBadge > 0 && (owner === 'debug' || owner === 'more');
    b.hidden = !show;
    b.textContent = errorsBadge > 99 ? '99+' : String(errorsBadge);
  });
}

async function show() {
  const route = parseRoute();
  const prev = current.route;
  current.route = route;
  const sec = SECTIONS.find(s => s.id === route.section);
  document.title = `${sec.title} · Mob Talk`;
  const titleEl = document.querySelector('.topbar .title');
  if (titleEl) titleEl.textContent = sec.title;
  if (route.section === 'debug') errorsBadge = 0;
  markNav();
  if (prev && prev.section === route.section && current.view?.update) { current.view.update(route); return; }
  current.view?.destroy?.();
  const main = document.getElementById('main');
  const el = h('div.view');
  replace(main, el);
  current.el = el;
  current.section = route.section;
  try {
    const mod = await import(`./views/${route.section}.js`);
    if (current.el !== el) return;
    current.view = mod.default(el, route) || null;
  } catch (e) {
    console.error(e);
    replace(el, h('div.error-box', icon('warn'), h('div.body', h('b', 'Раздел не загрузился'), h('p.text-2', String(e.message || e)))));
  }
  if (prev && prev.section !== route.section) { window.scrollTo(0, 0); main.focus({ preventScroll: true }); }
}

function startEvents(conn) {
  const label = { ok: 'в сети', wait: 'переподключение…', off: 'нет связи' };
  events?.close();
  events = new Stream('events', {
    hello: d => emit('hello', d),
    metrics: d => emit('metrics', d),
    history: d => emit('history', d),
    presence: d => emit('presence', d),
    config: d => emit('config', d),
    error: d => { emit('error-group', d); if (current.route?.section !== 'debug') { errorsBadge++; markNav(); } },
    backup: d => emit('backup', d),
    positions: d => emit('positions', d),
  }, { onState: s => { conn.className = `conn${s === 'ok' ? '' : '.' + s}`.replace('.', ' '); conn.textContent = label[s]; } });
}

// ---------- вход ----------
function loginScreen(message) {
  events?.close();
  current.view?.destroy?.();
  current.view = null; current.route = null; current.el = null;
  const pass = h('input.input#pw', { type: 'password', autocomplete: 'current-password', required: true, 'aria-describedby': 'login-err' });
  const err = h('p.err#login-err', { role: 'alert' }, message || '');
  const btn = h('button.btn.primary', { type: 'submit' }, 'Войти');
  let lockTimer;
  const form = h('form.card', {
    onsubmit: async e => {
      e.preventDefault();
      if (!pass.value) return;
      btn.setAttribute('aria-busy', 'true'); btn.disabled = true; err.textContent = '';
      try {
        await api.post('login', { password: pass.value }, { quiet401: true });
        start();
      } catch (ex) {
        btn.removeAttribute('aria-busy'); btn.disabled = false;
        if (ex.status === 429 && ex.retryAfter) {
          let left = ex.retryAfter;
          btn.disabled = true;
          const tick = () => { err.textContent = `Слишком много попыток. Повторить можно через ${left} с.`; if (left-- <= 0) { clearInterval(lockTimer); btn.disabled = false; err.textContent = ''; } };
          clearInterval(lockTimer); tick(); lockTimer = setInterval(tick, 1000);
        } else err.textContent = ex.status === 401 ? 'Неверный пароль.' : ex.message;
        pass.select();
      }
    },
  },
    h('div.brand', h('img', { src: 'icon.svg', alt: '' }), h('span', 'Mob Talk')),
    h('p', 'Админка сервера. Пароль задаётся командой', h('br'), h('code', '/mobtalk admin web password')),
    h('div', h('label.lbl', { for: 'pw' }, 'Пароль'), pass),
    btn, err);
  replace(app, h('div.login', form));
  app.removeAttribute('aria-busy');
  pass.focus();
}

async function logout() {
  try { await api.post('logout'); } catch { /* сессия и так могла истечь */ }
  loginScreen('Вы вышли.');
}

let started = false;
async function start() {
  try { await api.get('session', null, { quiet401: true }); } catch (e) {
    if (e.status === 401) return loginScreen();
    replace(app, h('div.login', h('div.card', h('div.brand', h('img', { src: 'icon.svg', alt: '' }), h('span', 'Mob Talk')), h('p', e.message), h('button.btn', { type: 'button', onclick: start }, 'Ещё раз'))));
    return;
  }
  const { conn } = shell();
  current.route = null;
  startEvents(conn);
  show();
  api.get('server').then(s => { const el = document.querySelector('.server-line'); if (el) el.textContent = `Mob Talk ${s.version} · MC ${s.minecraft}`; }).catch(() => {});
  if (!started) {
    started = true;
    addEventListener('hashchange', () => { if (current.el) show(); });
    onUnauthorized(() => { if (current.el) loginScreen('Сессия закончилась, войдите снова.'); });
    on('toast', t => toast(t));
  }
}

start();
