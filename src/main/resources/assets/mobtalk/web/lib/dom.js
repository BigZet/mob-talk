// Маленький построитель DOM: h('div.card#id', {onclick, aria-label…}, детей…). Текст всегда textContent — HTML из строк не разбираем.

export function h(spec, attrs, ...children) {
  if (attrs == null || typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs)) {
    if (attrs !== undefined) children.unshift(attrs);
    attrs = {};
  }
  const m = /^([a-z0-9-]+)?((?:[.#][\w-]+)*)$/i.exec(spec);
  const el = document.createElement(m?.[1] || 'div');
  for (const part of (m?.[2] || '').match(/[.#][\w-]+/g) || []) {
    if (part[0] === '.') el.classList.add(part.slice(1)); else el.id = part.slice(1);
  }
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'class') el.classList.add(...String(v).split(/\s+/).filter(Boolean));
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else if (k === 'value' || k === 'checked' || k === 'selected' || k === 'indeterminate') el[k] = v;
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  append(el, children);
  return el;
}

export function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c == null || c === false || c === true) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

/** Иконка из спрайта index.html. */
export function icon(name, cls = '') {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('class', `ico ${cls}`.trim());
  svg.setAttribute('aria-hidden', 'true');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', `#i-${name}`);
  svg.append(use);
  return svg;
}

export function clear(el) { el.replaceChildren(); return el; }

export function replace(el, ...children) { el.replaceChildren(); return append(el, children); }

/** Цвет и буквы аватара по нику — стабильно, без внешних картинок. */
export function avatar(name, cls = '') {
  const s = String(name || '?');
  let hash = 0;
  for (const ch of s) hash = (hash * 31 + ch.codePointAt(0)) | 0;
  const hues = ['var(--s1)', 'var(--s2)', 'var(--s3)', 'var(--s7)', 'var(--s5)', 'var(--s6)', 'var(--s8)', 'var(--s4)'];
  return h(`span.avatar${cls ? '.' + cls : ''}`, { style: { background: hues[Math.abs(hash) % hues.length] }, 'aria-hidden': 'true' }, [...s][0].toUpperCase());
}

export function debounce(fn, ms = 250) {
  let t;
  return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}
