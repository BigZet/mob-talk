// Форматирование чисел, денег, времени и размеров по-русски.

const nf = new Intl.NumberFormat('ru-RU');
const nf1 = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 1 });
const nf2 = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 });

export const num = (v, digits = 0) => v == null || Number.isNaN(v) ? '—' : digits === 0 ? nf.format(Math.round(v)) : digits === 1 ? nf1.format(v) : nf2.format(v);

export function usd(v) {
  if (v == null || Number.isNaN(v)) return '—';
  const a = Math.abs(v);
  const digits = a === 0 ? 2 : a < 0.01 ? 4 : a < 1 ? 3 : 2;
  return '$' + v.toLocaleString('ru-RU', { minimumFractionDigits: Math.min(2, digits), maximumFractionDigits: digits });
}

export function ms(v) {
  if (v == null || Number.isNaN(v)) return '—';
  if (v < 1000) return `${Math.round(v)} мс`;
  if (v < 60_000) return `${nf1.format(v / 1000)} с`;
  return duration(v / 1000);
}

/** Длительность в секундах: «2 ч 05 мин», «3 д 4 ч», «45 с». */
export function duration(sec) {
  if (sec == null || Number.isNaN(sec)) return '—';
  sec = Math.max(0, Math.round(sec));
  const d = Math.floor(sec / 86400), hh = Math.floor((sec % 86400) / 3600), mm = Math.floor((sec % 3600) / 60), ss = sec % 60;
  if (d) return `${d} д ${hh} ч`;
  if (hh) return `${hh} ч ${String(mm).padStart(2, '0')} мин`;
  if (mm) return `${mm} мин ${String(ss).padStart(2, '0')} с`;
  return `${ss} с`;
}

/** Обратный отсчёт до момента t: «14:03» или «1 ч 12 мин». */
export function countdown(t) {
  const left = Math.max(0, Math.round((t - Date.now()) / 1000));
  if (left >= 3600) return duration(left);
  return `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`;
}

export function bytes(b) {
  if (b == null) return '—';
  const units = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
  let i = 0;
  while (b >= 1024 && i < units.length - 1) { b /= 1024; i++; }
  return `${i ? nf1.format(b) : Math.round(b)} ${units[i]}`;
}

const timeF = new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' });
const timeS = new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
const dayF = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short' });
const fullF = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
const fullS = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });

export const time = t => (t ? timeF.format(t) : '—');
export const timeSec = t => (t ? timeS.format(t) : '—');
export const day = t => (t ? dayF.format(t) : '—');
export const dateTime = t => (t ? fullF.format(t) : '—');
export const dateTimeFull = t => (t ? fullS.format(t) : '—');

/** «только что», «5 мин назад», «вчера в 18:40», «3 окт». */
export function ago(t) {
  if (!t) return '—';
  const s = (Date.now() - t) / 1000;
  if (s < 0) return time(t);
  if (s < 45) return 'только что';
  if (s < 3600) return `${Math.max(1, Math.round(s / 60))} мин назад`;
  const today = new Date(); today.setHours(0, 0, 0, 0);
  if (t >= today.getTime()) return `сегодня в ${time(t)}`;
  if (t >= today.getTime() - 86400_000) return `вчера в ${time(t)}`;
  return dateTime(t);
}

/** Ключ дня «20261022» из API → «22 окт». */
export function dayKey(key) {
  const m = /^(\d{4})-?(\d{2})-?(\d{2})$/.exec(String(key));
  return m ? dayF.format(new Date(+m[1], +m[2] - 1, +m[3])) : String(key);
}

export function plural(n, one, few, many) {
  const a = Math.abs(n) % 100, b = a % 10;
  if (a > 10 && a < 20) return many;
  if (b > 1 && b < 5) return few;
  if (b === 1) return one;
  return many;
}

export function value(v) {
  if (v === true) return 'да';
  if (v === false) return 'нет';
  if (v == null || v === '') return '—';
  if (typeof v === 'number') return num(v, 2);
  if (typeof v === 'object') return JSON.stringify(v);
  return String(v);
}
