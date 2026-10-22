// Тема ставится до первой отрисовки, чтобы страница не мигала светлой. Выбор пользователя: auto, light или dark.
(function () {
  var theme = 'auto';
  try { theme = localStorage.getItem('mt-theme') || 'auto'; } catch (e) { /* хранилище закрыто — по системе */ }
  if (theme === 'light' || theme === 'dark') document.documentElement.setAttribute('data-theme', theme);
})();
