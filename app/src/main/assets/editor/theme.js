(function () {
  'use strict';
  const media = window.matchMedia('(prefers-color-scheme: dark)');
  const native = window.InkstoneNative;
  let preference = 'system';
  let nativeResolved;
  const valid = value => ['system', 'light', 'dark'].includes(value);
  try {
    if (native && typeof native.themeState === 'function') {
      const state = JSON.parse(native.themeState());
      if (valid(state.preference)) preference = state.preference;
      if (state.resolved === 'light' || state.resolved === 'dark') nativeResolved = state.resolved;
    } else {
      const stored = localStorage.getItem('inkstone-theme');
      if (valid(stored)) preference = stored;
    }
  } catch (_) { }
  function apply() {
    const resolved = nativeResolved || (preference === 'system' ? (media.matches ? 'dark' : 'light') : preference);
    document.documentElement.dataset.theme = resolved;
    document.documentElement.style.colorScheme = resolved;
    return { preference, resolved };
  }
  window.InkstoneTheme = {
    set: function (value, resolved) {
      if (!valid(value)) return false;
      preference = value;
      nativeResolved = resolved === 'light' || resolved === 'dark' ? resolved : undefined;
      try { localStorage.setItem('inkstone-theme', value); } catch (_) { }
      return apply();
    },
    state: apply
  };
  const changed = function () { if (!nativeResolved && preference === 'system') apply(); };
  if (media.addEventListener) media.addEventListener('change', changed);
  else media.addListener(changed);
  // Runs before the stylesheets and editor construction to avoid a light first frame.
  apply();
})();
