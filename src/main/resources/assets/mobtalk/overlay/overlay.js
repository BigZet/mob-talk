// Оверлей Mob Talk для OBS (идея З3, docs/overlay.md). Данные — только по SSE с /overlay/events того же адреса;
// весь текст вставляется через textContent, разметка из данных не исполняется.
(() => {
  'use strict';
  const WIDGETS = ['subtitles', 'god', 'mercy', 'now'];
  const CHATTER = new Set(['witness', 'village', 'initiative', 'notice']);
  const path = location.pathname.replace(/\/+$/, '').split('/').pop();
  const only = WIDGETS.includes(path) ? path : null;
  // Вид: маленькая карточка (по умолчанию, /overlay/card) или весь экран (/overlay/full); один виджет — по его адресу.
  const forced = path === 'full' || path === 'card' ? path : null;
  const layout = () => only ? 'single' : forced || (config.layout === 'full' ? 'full' : 'card');
  const params = new URLSearchParams(location.search);
  const $ = id => document.getElementById(id);
  const el = (tag, cls, text) => { const node = document.createElement(tag); if (cls) node.className = cls; if (text != null) node.textContent = text; return node; };

  let config = { layout: 'card', subtitles: true, god: true, mercy: true, now: true, heard: true, chatter: true, holdSeconds: 6, scale: 100, style: 'cages', reduceMotion: false };
  let state = {};
  let lastGodLine = 0;
  const seenActive = new Map(); // ключ кары → {full: мс в момент первого показа, node}
  let lastSins = new Map(), lastBanned = null, lastVerdictT = 0;


  function applyConfig() {
    const style = params.get('theme') || config.style || 'cages';
    document.body.classList.remove('style-cages', 'style-parchment', 'style-minimal');
    document.body.classList.add('style-' + (['cages', 'parchment', 'minimal'].includes(style) ? style : 'cages'));
    document.body.classList.toggle('calm', !!config.reduceMotion || params.get('calm') === '1');
    const scale = parseFloat(params.get('scale')) || (config.scale || 100) / 100;
    $('stage').style.zoom = String(Math.max(0.3, Math.min(3, scale)));
    const view = layout();
    document.body.classList.remove('all', 'single', 'compact');
    document.body.classList.add(view === 'full' ? 'all' : view === 'card' ? 'compact' : view);
    for (const name of WIDGETS) $(name).hidden = view === 'card' || !(config[name] !== false && (!only || only === name)) || !hasContent(name);
    $('card').hidden = view !== 'card';
  }
  function hasContent(name) {
    if (name === 'subtitles') return true;
    if (name === 'god') return !!state.god;
    if (name === 'mercy') return Array.isArray(state.souls) && state.souls.length > 0;
    return (state.active || []).length > 0 || (state.events || []).length > 0 || $('flash').children.length > 0;
  }

  // ---- Субтитры ----
  function subtitle(line, god) {
    if (config.subtitles === false || (only && only !== 'subtitles')) return;
    if (!god && config.chatter === false && CHATTER.has(line.kind)) return;
    if (layout() === 'card') { cardLine(line, god); return; }
    const box = $('lines');
    const node = el('div', 'line' + (god ? ' god' : '') + (!god && (CHATTER.has(line.kind) || line.toYou === false) ? ' background' : ''));
    if (!god && config.heard !== false && line.heard) node.append(el('div', 'heard', (line.asker ? line.asker + ': ' : '— ') + line.heard));
    const speaker = el('span', 'speaker', god ? 'Глас Божий' : line.speaker);
    if (!god) speaker.style.color = colour(line.speaker);
    const text = el('span', 'text', '');
    node.append(speaker, text);
    box.append(node);
    while (box.children.length > 2) box.firstElementChild.remove();
    if (box.children.length === 2) box.firstElementChild.classList.add('old');
    const millis = Number(line.millis) > 0 ? Number(line.millis) : Math.max(1500, String(line.text || '').length * 65);
    type(text, line.text || '', millis);
    setTimeout(() => { node.classList.add('out'); setTimeout(() => node.remove(), 700); }, millis + (config.holdSeconds || 6) * 1000);
  }
  function type(node, full, millis) {
    if (document.body.classList.contains('calm')) { node.textContent = full; return; }
    const started = performance.now(), span = Math.min(millis, full.length * 70 + 200);
    const step = now => {
      const shown = Math.min(full.length, Math.ceil(full.length * (now - started) / span));
      node.textContent = full.slice(0, shown);
      if (shown < full.length) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }
  function colour(name) {
    let hash = 0;
    for (const ch of String(name || '')) hash = (hash * 31 + ch.codePointAt(0)) | 0;
    return 'hsl(' + (Math.abs(hash) % 360) + ', 70%, ' + (document.body.classList.contains('style-parchment') ? '32%' : '72%') + ')';
  }

  // ---- Карточка: бог одним словом, своя милость, одно главное «сейчас» и последняя реплика ----
  let cardToken = 0;
  function cardLine(line, god) {
    const token = ++cardToken;
    const speaker = $('ic-speaker');
    speaker.textContent = (god ? 'Глас Божий' : line.speaker || '') + ': ';
    speaker.style.color = god ? '' : colour(line.speaker);
    $('ic-line').classList.toggle('god', !!god);
    $('ic-line').hidden = false;
    const millis = Number(line.millis) > 0 ? Number(line.millis) : Math.max(1500, String(line.text || '').length * 65);
    type($('ic-text'), line.text || '', millis);
    setTimeout(() => { if (token === cardToken) $('ic-line').hidden = true; }, millis + (config.holdSeconds || 6) * 1000);
  }
  function moodClass(mood) { return mood <= -15 ? 'wrath' : mood >= 15 ? 'grace' : 'calm'; }
  function renderCard() {
    const god = config.god !== false ? state.god : null;
    $('ic-dot').className = 'ic-dot ' + (god ? moodClass(god.mood || 0) : 'calm');
    $('ic-god').textContent = god ? 'Бог ' + (god.word || 'молчит') : 'Mob Talk';
    const me = config.mercy !== false && (state.souls || []).find(s => s.player && (s.player.uuid === state.you || s.player.uuid === state.streamer));
    const mercy = $('ic-mercy');
    mercy.replaceChildren();
    if (me && me.mercy != null) {
      const lit = Math.round(me.mercy / 20);
      for (let i = 0; i < 5; i++) mercy.append(el('span', 'candle' + (i < lit ? ' lit' : '')));
      mercy.title = me.label || '';
    } else if (me) mercy.append(el('span', 'label', me.label || ''));
    const items = config.now === false ? [] : nowItems();
    $('ic-now').hidden = items.length === 0;
    if (!items.length) { cardTop = null; return; }
    const top = items[0];
    $('ic-now').className = 'ic-now ' + top.kind;
    $('ic-title').textContent = top.title;
    $('ic-who').textContent = top.who || '';
    $('ic-more').textContent = items.length > 1 ? 'ещё ' + (items.length - 1) : '';
    $('ic-bar').hidden = top.share == null;
    if (top.share != null) $('ic-fill').style.width = top.share * 100 + '%';
    cardTop = top;
    tickCard();
  }
  let cardTop = null;
  function nowItems() {
    // Порядок важности: суд, изгнание и объявления, потом босс, потом кары и блага (ближайшая к концу первой), свадьба последней.
    const now = Date.now(), out = [];
    const events = (state.events || []).filter(e => !e.until || e.until > now);
    const event = e => ({ kind: e.kind === 'wedding' ? 'blessing' : 'curse', title: e.title, who: e.player ? e.player.name : '', until: e.until || 0 });
    for (const e of events) if (e.kind !== 'wedding') out.push(event(e));
    for (const a of state.active || []) if (a.kind === 'boss')
      out.push({ kind: 'curse', title: a.title, who: a.target ? 'цель: ' + a.target.name : '', until: 0,
        share: a.maxHealth > 0 ? Math.max(0, Math.min(1, a.health / a.maxHealth)) : 1 });
    const cards = (state.active || []).filter(a => a.kind !== 'boss')
      .sort((a, b) => (a.until || Infinity) - (b.until || Infinity));
    for (const a of cards) out.push({ kind: a.kind === 'blessing' ? 'blessing' : 'curse', title: a.title,
      who: a.target ? (a.kind === 'blessing' ? 'кому: ' : 'цель: ') + a.target.name : 'на всех', until: a.until || 0 });
    for (const e of events) if (e.kind === 'wedding') out.push(event(e));
    const verdict = state.verdict;
    if (!out.length && verdict && now - (verdict.t || 0) < 60 * 1000 && verdict.title)
      out.push({ kind: verdict.act === 'miracle' ? 'blessing' : 'curse', title: (ACTS[verdict.act] || '') + ': ' + verdict.title,
        who: verdict.player ? 'молился: ' + verdict.player.name : '', until: 0 });
    return out;
  }
  function tickCard() {
    if (!cardTop) return;
    const left = cardTop.until ? Math.max(0, cardTop.until - Date.now()) : 0, s = Math.ceil(left / 1000);
    $('ic-time').textContent = cardTop.until ? Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0') : '';
    $('ic-now').classList.toggle('urgent', !!cardTop.until && left < 60000);
  }

  // ---- Бог ----
  const ACTS = { wrath: 'кара', miracle: 'благо', warning: 'знамение', jest: 'шутка', none: 'без ответа' };
  function renderGod() {
    const god = state.god;
    if (!god) return;
    $('mood-mark').style.left = ((Math.max(-100, Math.min(100, god.mood || 0)) + 100) / 2) + '%';
    $('mood-word').textContent = god.word || '';
    const verdict = state.verdict;
    const fresh = verdict && Date.now() - (verdict.t || 0) < 5 * 60 * 1000;
    $('verdict').hidden = !fresh;
    if (!fresh) return;
    const act = $('verdict-act');
    act.className = 'act ' + (ACTS[verdict.act] ? verdict.act : 'none');
    act.textContent = ACTS[verdict.act] || verdict.act || '';
    $('verdict-title').textContent = verdict.title || '';
    $('verdict-who').textContent = verdict.player ? 'молился: ' + verdict.player.name : '';
    const reasons = $('verdict-reasons');
    reasons.replaceChildren(...(verdict.reasons || []).map(r => el('li', null, r)));
    if (verdict.t > lastVerdictT && lastVerdictT !== 0) effect(verdict.act === 'wrath' ? 'vignette' : verdict.act === 'miracle' ? 'rays' : null);
    lastVerdictT = Math.max(lastVerdictT, verdict.t || 0) || 1;
  }

  // ---- Милость ----
  function renderMercy() {
    const souls = (state.souls || []).slice();
    souls.sort((a, b) => (b.mercy ?? -(b.sins || 0)) - (a.mercy ?? -(a.sins || 0)));
    const list = $('souls');
    const banned = new Set();
    list.replaceChildren(...souls.map(soul => {
      const id = soul.player && soul.player.uuid;
      const row = el('li', 'soul' + (id && (id === state.streamer || id === state.you) ? ' me' : '') + (soul.banned ? ' banned' : ''));
      const name = el('span', 'name', soul.player ? soul.player.name : '');
      const sins = soul.sins;
      if (sins != null && lastSins.has(id) && sins > lastSins.get(id)) name.append(el('span', 'plus', '+' + (sins - lastSins.get(id))));
      if (sins != null) lastSins.set(id, sins);
      let right;
      if (soul.mercy != null) {
        right = el('span', 'candles');
        const lit = Math.round(soul.mercy / 20);
        for (let i = 0; i < 5; i++) right.append(el('span', 'candle' + (i < lit ? ' lit' : '')));
        right.title = soul.label || '';
      } else right = el('span', 'label', soul.label || '');
      const meta = el('div', 'meta');
      if (soul.mercy != null && soul.label) meta.append(el('span', null, soul.label));
      if (sins != null) meta.append(el('span', null, 'грехов: ' + sins));
      if (soul.ladder) meta.append(el('span', null, 'ступень кар: ' + soul.ladder));
      if (soul.cursed) meta.append(el('span', 'cursed', 'под карой'));
      if (soul.banned) { meta.append(el('span', 'cursed', 'изгнан')); banned.add(id); }
      row.append(name, right, meta);
      return row;
    }));
    if (lastBanned) for (const id of banned) if (!lastBanned.has(id)) {
      const soul = souls.find(s => s.player && s.player.uuid === id);
      flashEvent('ban', (soul ? soul.player.name : 'Игрок') + ' изгнан из мира', 5000);
    }
    lastBanned = banned;
  }

  // ---- Сейчас ----
  function renderNow() {
    const bosses = (state.active || []).filter(a => a.kind === 'boss');
    $('bosses').replaceChildren(...bosses.map(boss => {
      const box = el('div', 'boss');
      const head = el('div', 'boss-name');
      head.append(el('b', null, boss.title), el('span', null, boss.target ? 'цель: ' + boss.target.name : ''));
      const bar = el('div', 'boss-bar');
      const share = boss.maxHealth > 0 ? Math.max(0, Math.min(1, boss.health / boss.maxHealth)) * 100 + '%' : '100%';
      const trail = el('div', 'boss-trail'), fill = el('div', 'boss-fill');
      trail.style.width = fill.style.width = share;
      bar.append(trail, fill);
      box.append(head, bar);
      return box;
    }));
    const events = (state.events || []).filter(e => !e.until || e.until > Date.now());
    $('events').replaceChildren(...events.map(e => el('div', 'event ' + (e.kind || ''), e.title + (e.player ? ' — ' + e.player.name : ''))));
    const cards = (state.active || []).filter(a => a.kind !== 'boss');
    const keep = new Set();
    for (const item of cards) {
      const key = item.kind + '|' + item.title + '|' + (item.target ? item.target.uuid : '');
      keep.add(key);
      let seen = seenActive.get(key);
      if (!seen) {
        seen = { full: Math.max(1, (item.until || 0) - Date.now()), node: card(item) };
        seenActive.set(key, seen);
        $('active').append(seen.node);
        effect(item.kind === 'blessing' ? 'rays' : 'vignette');
      }
      seen.item = item;
    }
    for (const [key, seen] of seenActive) if (!keep.has(key)) {
      seenActive.delete(key);
      seen.node.classList.add('gone');
      setTimeout(() => seen.node.remove(), 1200);
    }
    tickCards();
  }
  function card(item) {
    const node = el('div', 'card ' + (item.kind === 'blessing' ? 'blessing' : 'curse'));
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 44 44'); svg.setAttribute('class', 'ring');
    for (const cls of ['track', 'left']) {
      const c = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      c.setAttribute('cx', '22'); c.setAttribute('cy', '22'); c.setAttribute('r', '18'); c.setAttribute('class', cls);
      c.setAttribute('stroke-dasharray', String(2 * Math.PI * 18));
      svg.append(c);
    }
    const body = el('div');
    body.append(el('div', 'title', item.title), el('div', 'who', item.target ? (item.kind === 'blessing' ? 'кому: ' : 'цель: ') + item.target.name : 'на всех'), el('div', 'time', ''));
    node.append(svg, body);
    return node;
  }
  function tickCards() {
    for (const seen of seenActive.values()) {
      const item = seen.item || {};
      const left = item.until ? Math.max(0, item.until - Date.now()) : 0;
      const ring = seen.node.querySelector('.left');
      const length = 2 * Math.PI * 18;
      ring.setAttribute('stroke-dashoffset', String(item.until ? length * (1 - left / seen.full) : 0));
      const s = Math.ceil(left / 1000);
      seen.node.querySelector('.time').textContent = item.until ? Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0') : '';
      seen.node.classList.toggle('urgent', !!item.until && left < 60000);
    }
  }
  function flashEvent(kind, title, millis) {
    const node = el('div', 'event ' + kind, title);
    $('flash').append(node);
    $('now').hidden = config.now === false || (only && only !== 'now');
    setTimeout(() => node.remove(), millis);
  }
  function effect(kind) {
    if (!kind || layout() === 'card' || document.body.classList.contains('calm') || (only && only !== 'now' && only !== 'god')) return;
    const node = $(kind);
    node.classList.remove('on'); void node.offsetWidth; node.classList.add('on');
  }

  // ---- Поток ----
  function onState(next) {
    state = next || {};
    for (const line of state.lines || []) if ((line.t || 0) > lastGodLine) { lastGodLine = line.t; subtitle(line, true); }
    renderGod(); renderMercy(); renderNow(); renderCard(); applyConfig();
  }
  let silentSince = 0;
  function connect() {
    const source = new EventSource('/overlay/events');
    source.addEventListener('config', e => { config = Object.assign(config, JSON.parse(e.data)); applyConfig(); renderCard(); });
    source.addEventListener('state', e => onState(JSON.parse(e.data)));
    source.addEventListener('line', e => subtitle(JSON.parse(e.data), false));
    source.onopen = () => { silentSince = 0; document.body.classList.remove('disconnected'); };
    source.onerror = () => { if (!silentSince) silentSince = Date.now(); };
  }
  setInterval(() => {
    tickCards(); tickCard();
    if (layout() === 'card' && cardTop && cardTop.until && cardTop.until <= Date.now()) renderCard();
    if (silentSince && Date.now() - silentSince > 10000) document.body.classList.add('disconnected');
    const events = $('events');
    if (events && (state.events || []).some(e => e.until && e.until <= Date.now())) renderNow();
  }, 250);
  applyConfig();
  connect();
})();
