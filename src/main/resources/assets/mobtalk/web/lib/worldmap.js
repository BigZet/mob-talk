// Карта мира сверху: тайлы /api/map/tiles/{мир}/{zoom}/{tx}/{tz}.png (1 пиксель = 2^zoom блоков, тайл 256 пикселей),
// поверх — следы игроков, метки и сами игроки. Перетаскивание, колесо и щипок для масштаба, стрелки и +/− с клавиатуры.
import { h, icon } from './dom.js';

const TILE = 256, MAX_Z = 4, MIN_SCALE = 1 / 16, MAX_SCALE = 8;
const SVGNS = 'http://www.w3.org/2000/svg';
const svg = (tag, attrs = {}) => { const el = document.createElementNS(SVGNS, tag); for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v); return el; };
const colorOf = i => `var(--s${(i % 8) + 1})`;

export class WorldMap {
  /** opts: { world, center:{x,z}, scale, onView(view), onPick(uuid) } */
  constructor(el, opts = {}) {
    this.world = opts.world || 'minecraft:overworld';
    this.cx = opts.center?.x ?? 0; this.cz = opts.center?.z ?? 0;
    this.scale = opts.scale ?? 1;
    this.opts = opts;
    this.players = []; this.tracks = []; this.markers = []; this.showTracks = true; this.follow = null;
    this.tiles = new Map(); this.empty = new Set();
    this.tileLayer = h('div.wmap-tiles');
    this.overlay = svg('svg', { class: 'wmap-overlay' });
    this.readout = h('div.wmap-readout', { 'aria-hidden': 'true' });
    const btn = (label, ic, fn) => h('button.btn.icon.sm', { type: 'button', 'aria-label': label, title: label, onclick: fn }, ic);
    this.root = h('div.wmap', { tabindex: '0', role: 'application', 'aria-label': 'Карта мира. Стрелки — сдвиг, плюс и минус — масштаб.' },
      this.tileLayer, this.overlay, this.readout,
      h('div.wmap-zoom', btn('Приблизить', h('span', '+'), () => this.zoomBy(2)), btn('Отдалить', h('span', '−'), () => this.zoomBy(0.5)),
        btn('Ко всем игрокам', icon('players', 'sm'), () => this.fit())));
    el.replaceChildren(this.root);
    this.bind();
    this.ro = new ResizeObserver(() => this.draw());
    this.ro.observe(this.root);
  }

  // ---------- координаты ----------
  get w() { return this.root.clientWidth; }
  get hgt() { return this.root.clientHeight; }
  toScreen(x, z) { return [(x - this.cx) * this.scale + this.w / 2, (z - this.cz) * this.scale + this.hgt / 2]; }
  toWorld(px, py) { return [(px - this.w / 2) / this.scale + this.cx, (py - this.hgt / 2) / this.scale + this.cz]; }

  // ---------- управление ----------
  bind() {
    const pts = new Map();
    let last = null, pinch = null;
    this.root.addEventListener('pointerdown', e => {
      if (e.target.closest('button')) return;
      this.root.setPointerCapture(e.pointerId);
      pts.set(e.pointerId, [e.clientX, e.clientY]);
      last = [e.clientX, e.clientY];
      if (pts.size === 2) { const [a, b] = [...pts.values()]; pinch = { d: Math.hypot(a[0] - b[0], a[1] - b[1]), s: this.scale }; }
    });
    this.root.addEventListener('pointermove', e => {
      const r = this.root.getBoundingClientRect();
      const [wx, wz] = this.toWorld(e.clientX - r.left, e.clientY - r.top);
      this.readout.textContent = `X ${Math.round(wx)}  Z ${Math.round(wz)}`;
      if (!pts.has(e.pointerId)) return;
      pts.set(e.pointerId, [e.clientX, e.clientY]);
      if (pts.size === 2 && pinch) {
        const [a, b] = [...pts.values()];
        const mid = [(a[0] + b[0]) / 2 - r.left, (a[1] + b[1]) / 2 - r.top];
        this.zoomAt(pinch.s * Math.hypot(a[0] - b[0], a[1] - b[1]) / pinch.d / this.scale, mid[0], mid[1]);
        return;
      }
      if (!last) return;
      this.cx -= (e.clientX - last[0]) / this.scale; this.cz -= (e.clientY - last[1]) / this.scale;
      last = [e.clientX, e.clientY];
      this.follow = null;
      this.draw();
    });
    const up = e => { pts.delete(e.pointerId); if (pts.size < 2) pinch = null; last = pts.size ? [...pts.values()][0] : null; if (!pts.size) this.changed(); };
    this.root.addEventListener('pointerup', up);
    this.root.addEventListener('pointercancel', up);
    this.root.addEventListener('pointerleave', () => (this.readout.textContent = ''));
    this.root.addEventListener('wheel', e => {
      e.preventDefault();
      const r = this.root.getBoundingClientRect();
      this.zoomAt(Math.pow(2, -e.deltaY / 300), e.clientX - r.left, e.clientY - r.top);
      this.changed();
    }, { passive: false });
    this.root.addEventListener('dblclick', e => { const r = this.root.getBoundingClientRect(); this.zoomAt(2, e.clientX - r.left, e.clientY - r.top); this.changed(); });
    this.root.addEventListener('keydown', e => {
      const step = 80 / this.scale;
      const moves = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
      if (moves[e.key]) { e.preventDefault(); this.cx += moves[e.key][0]; this.cz += moves[e.key][1]; this.follow = null; this.draw(); this.changed(); }
      else if (e.key === '+' || e.key === '=') { this.zoomBy(2); } else if (e.key === '-') { this.zoomBy(0.5); }
    });
  }
  zoomAt(factor, px, py) {
    const [wx, wz] = this.toWorld(px, py);
    this.scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, this.scale * factor));
    this.cx = wx - (px - this.w / 2) / this.scale; this.cz = wz - (py - this.hgt / 2) / this.scale;
    this.draw();
  }
  zoomBy(f) { this.zoomAt(f, this.w / 2, this.hgt / 2); this.changed(); }
  center(x, z, scale) { this.cx = x; this.cz = z; if (scale) this.scale = scale; this.draw(); this.changed(); }
  changed() { clearTimeout(this.tm); this.tm = setTimeout(() => this.opts.onView?.({ x: Math.round(this.cx), z: Math.round(this.cz), scale: +this.scale.toFixed(3) }), 300); }
  /** Показать всех игроков (и следы) целиком. */
  fit() {
    const pts = [...this.players.map(p => [p.x, p.z]), ...(this.showTracks ? this.tracks.flatMap(t => t.points.map(q => [q[1], q[3]])) : [])];
    if (!pts.length) return;
    const xs = pts.map(p => p[0]), zs = pts.map(p => p[1]);
    const spanX = Math.max(64, Math.max(...xs) - Math.min(...xs)), spanZ = Math.max(64, Math.max(...zs) - Math.min(...zs));
    this.center((Math.max(...xs) + Math.min(...xs)) / 2, (Math.max(...zs) + Math.min(...zs)) / 2, Math.min(MAX_SCALE, Math.max(MIN_SCALE, Math.min(this.w / spanX, this.hgt / spanZ) * 0.8)));
  }

  // ---------- данные ----------
  setWorld(world) { if (world === this.world) return; this.world = world; for (const img of this.tiles.values()) img.remove(); this.tiles.clear(); this.empty.clear(); this.draw(); }
  setPlayers(list) {
    this.players = list;
    if (this.follow) { const p = list.find(x => x.player.uuid === this.follow); if (p) { this.cx = p.x; this.cz = p.z; } }
    this.drawOverlay();
  }
  setTracks(tracks) { this.tracks = tracks; this.drawOverlay(); }
  setMarkers(markers) { this.markers = markers; this.drawOverlay(); }
  followPlayer(uuid) { this.follow = uuid; const p = this.players.find(x => x.player.uuid === uuid); if (p) this.center(p.x, p.z, Math.max(this.scale, 2)); }
  /** Тайл перерисован на сервере (событие map): загрузить его заново, минуя кэш. */
  refreshTile(world, z, tx, tz) {
    if (world !== this.world) return;
    const key = `${z}/${tx}/${tz}`;
    this.empty.delete(key);
    const img = this.tiles.get(key);
    if (img) img.src = `/api/map/tiles/${encodeURIComponent(this.world)}/${z}/${tx}/${tz}.png?v=${Date.now()}`; else this.drawTiles();
  }
  refreshTiles() { for (const img of this.tiles.values()) img.remove(); this.tiles.clear(); this.empty.clear(); this.draw(); }

  // ---------- рисование ----------
  draw() {
    if (!this.w) return;
    this.drawTiles();
    this.drawOverlay();
  }
  drawTiles() {
    const z = Math.min(MAX_Z, Math.max(0, Math.floor(Math.log2(1 / this.scale))));
    const blocks = TILE * 2 ** z, px = blocks * this.scale;
    const [x0, z0] = this.toWorld(0, 0), [x1, z1] = this.toWorld(this.w, this.hgt);
    const want = new Set();
    for (let tx = Math.floor(x0 / blocks); tx <= Math.floor(x1 / blocks); tx++) {
      for (let tz = Math.floor(z0 / blocks); tz <= Math.floor(z1 / blocks); tz++) {
        const key = `${z}/${tx}/${tz}`;
        if (this.empty.has(key)) continue;
        want.add(key);
        let img = this.tiles.get(key);
        if (!img) {
          img = h('img', { alt: '', draggable: 'false', decoding: 'async', src: `/api/map/tiles/${encodeURIComponent(this.world)}/${z}/${tx}/${tz}.png` });
          img.onerror = () => { this.empty.add(key); img.remove(); this.tiles.delete(key); };
          img.onload = () => { if (!img.naturalWidth) img.onerror(); };
          this.tiles.set(key, img);
          this.tileLayer.append(img);
        }
        const [sx, sy] = this.toScreen(tx * blocks, tz * blocks);
        Object.assign(img.style, { left: `${Math.floor(sx)}px`, top: `${Math.floor(sy)}px`, width: `${Math.ceil(px) + 1}px`, height: `${Math.ceil(px) + 1}px` });
      }
    }
    for (const [key, img] of this.tiles) if (!want.has(key)) { img.remove(); this.tiles.delete(key); }
    this.tileLayer.classList.toggle('pixelated', this.scale > 1 / 2 ** z);
    // Сетка фона — по чанкам или их степеням двойки, движется вместе с картой.
    const grid = 16 * 2 ** Math.max(0, Math.round(Math.log2(64 / (16 * this.scale)))), gpx = grid * this.scale;
    const [ox, oy] = this.toScreen(0, 0);
    this.root.style.backgroundSize = `${gpx}px ${gpx}px`;
    this.root.style.backgroundPosition = `${((ox % gpx) + gpx) % gpx}px ${((oy % gpx) + gpx) % gpx}px`;
  }
  drawOverlay() {
    if (!this.w) return;
    const o = this.overlay;
    o.setAttribute('viewBox', `0 0 ${this.w} ${this.hgt}`);
    o.replaceChildren();
    const index = new Map(this.players.map((p, i) => [p.player.uuid, i]));
    if (this.showTracks) for (const t of this.tracks) {
      const i = index.get(t.player.uuid) ?? [...index.keys()].length + this.tracks.indexOf(t);
      const pts = t.points.map(q => this.toScreen(q[1], q[3]).map(v => v.toFixed(1)).join(',')).join(' ');
      if (t.points.length > 1) o.append(svg('polyline', { points: pts, class: 'track', style: `stroke: ${colorOf(i)}` }));
    }
    for (const m of this.markers) {
      const [sx, sy] = this.toScreen(m.x, m.z);
      if (sx < -50 || sy < -50 || sx > this.w + 50 || sy > this.hgt + 50) continue;
      const g = svg('g', { class: `marker ${m.kind}` });
      const title = svg('title'); title.textContent = m.name; g.append(title);
      g.append(svg('rect', { x: sx - 5, y: sy - 5, width: 10, height: 10, transform: `rotate(45 ${sx} ${sy})` }));
      if (this.scale >= 1 / 8) { const label = svg('text', { x: sx + 9, y: sy + 4 }); label.textContent = m.name; g.append(label); }
      o.append(g);
    }
    this.players.forEach((p, i) => {
      const [sx, sy] = this.toScreen(p.x, p.z);
      const g = svg('g', { class: 'player', tabindex: '-1' });
      const title = svg('title'); title.textContent = `${p.player.name}: ${Math.round(p.x)} ${Math.round(p.y)} ${Math.round(p.z)}`; g.append(title);
      if (p.yaw != null) {
        const a = (p.yaw * Math.PI) / 180;
        g.append(svg('line', { x1: sx, y1: sy, x2: sx - Math.sin(a) * 18, y2: sy + Math.cos(a) * 18, class: 'dir', style: `stroke: ${colorOf(i)}` }));
      }
      g.append(svg('circle', { cx: sx, cy: sy, r: 7, style: `fill: ${colorOf(i)}` }));
      const name = svg('text', { x: sx + 11, y: sy - 9, class: 'name' }); name.textContent = p.player.name; g.append(name);
      g.addEventListener('click', () => this.opts.onPick?.(p.player.uuid));
      o.append(g);
    });
  }
  destroy() { this.ro.disconnect(); clearTimeout(this.tm); }
}
