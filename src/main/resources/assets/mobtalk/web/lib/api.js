// Обращения к /api/* по контракту docs/admin-web.md. Меняющие запросы несут X-MobTalk: 1 (защита от CSRF).
// Ошибка — ApiError с понятным текстом; 401 сообщает приложению, что нужно войти.

export class ApiError extends Error {
  constructor(status, code, message, extra = {}) {
    super(message);
    this.status = status;
    this.code = code;
    Object.assign(this, extra);
  }
}

const FALLBACK = {
  0: 'Сервер не отвечает. Проверьте, что он запущен и сеть доступна.',
  400: 'Сервер не принял запрос.',
  401: 'Сессия закончилась, нужно войти заново.',
  403: 'Нет прав на это действие.',
  404: 'Не найдено.',
  409: 'Конфликт: данные изменились, обновите страницу.',
  429: 'Слишком много попыток, подождите.',
  500: 'Ошибка на сервере. Подробности — в разделе «Отладка».',
  503: 'Сервис ещё не подключён.',
};

const listeners = new Set();
/** Подписка на 401 (показать вход). */
export function onUnauthorized(fn) { listeners.add(fn); return () => listeners.delete(fn); }

export function query(params = {}) {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params || {})) {
    if (v == null || v === '' || (Array.isArray(v) && !v.length)) continue;
    q.set(k, Array.isArray(v) ? v.join(',') : String(v));
  }
  const s = q.toString();
  return s ? `?${s}` : '';
}

async function request(method, path, body, { params, signal, quiet401 } = {}) {
  const headers = { Accept: 'application/json' };
  if (method !== 'GET') headers['X-MobTalk'] = '1';
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let res;
  try {
    res = await fetch(`/api/${path}${query(params)}`, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: 'same-origin', signal, cache: 'no-store',
    });
  } catch (e) {
    if (e.name === 'AbortError') throw e;
    throw new ApiError(0, 'network', FALLBACK[0]);
  }
  let data = null;
  const text = await res.text();
  if (text) { try { data = JSON.parse(text); } catch { data = null; } }
  if (!res.ok) {
    const retryAfter = data?.retryAfter ?? Number(res.headers.get('Retry-After')) ?? null;
    const err = new ApiError(res.status, data?.error || String(res.status), data?.message || FALLBACK[res.status] || `Ошибка ${res.status}`, { retryAfter, data });
    if (res.status === 401 && !quiet401) listeners.forEach(fn => fn(err));
    throw err;
  }
  return data;
}

export const api = {
  get: (path, params, opts) => request('GET', path, undefined, { ...opts, params }),
  post: (path, body = {}, opts) => request('POST', path, body, opts),
  patch: (path, body = {}, opts) => request('PATCH', path, body, opts),
  del: (path, opts) => request('DELETE', path, undefined, opts),
  /** Адрес для скачивания (экспорт истории) — тот же origin, cookie сессии идёт сама. */
  href: (path, params) => `/api/${path}${query(params)}`,
};
