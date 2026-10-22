// Живые потоки (SSE) с переподключением и индикатором. EventSource сам повторяет при обрыве сети, но если сервер закрыл поток
// (перезапуск, 401), он сдаётся — тогда пробуем сами с нарастающей паузой и проверкой сессии.
import { api } from './api.js';

export const bus = new EventTarget();
export const emit = (type, detail) => bus.dispatchEvent(new CustomEvent(type, { detail }));
export const on = (type, fn) => { const l = e => fn(e.detail); bus.addEventListener(type, l); return () => bus.removeEventListener(type, l); };

export class Stream {
  constructor(path, events, { onState } = {}) {
    this.path = path;
    this.events = events;
    this.onState = onState || (() => {});
    this.delay = 1000;
    this.closed = false;
    this.open();
  }
  open() {
    if (this.closed) return;
    this.onState('wait');
    const es = (this.es = new EventSource(`/api/${this.path}`, { withCredentials: true }));
    es.onopen = () => { this.delay = 1000; this.onState('ok'); };
    for (const [name, fn] of Object.entries(this.events)) {
      es.addEventListener(name, e => {
        let data = null;
        try { data = e.data ? JSON.parse(e.data) : null; } catch { return; }
        fn(data);
      });
    }
    es.onerror = () => {
      if (this.closed) return;
      if (es.readyState === EventSource.CONNECTING) { this.onState('wait'); return; }
      es.close();
      this.onState('off');
      clearTimeout(this.timer);
      this.timer = setTimeout(async () => {
        try { await api.get('session'); } catch (e) { if (e.status === 401) return; }
        this.open();
      }, this.delay);
      this.delay = Math.min(this.delay * 2, 30_000);
    };
  }
  close() {
    this.closed = true;
    clearTimeout(this.timer);
    this.es?.close();
  }
}
