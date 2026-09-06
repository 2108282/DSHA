(function () {
  'use strict';
  var root = document.documentElement, preference = null;
  try { preference = localStorage.getItem('dsha-theme'); } catch (e) {}
  if (preference !== 'dark' && preference !== 'light') preference = null;
  var dark = preference ? preference === 'dark' : !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  root.setAttribute('data-theme', dark ? 'dark' : 'light');
  var meta = document.querySelector('meta[name=theme-color]');
  if (meta) meta.setAttribute('content', dark ? '#070c14' : '#edf3fc');
})();
