/* Shared, layout-independent graphics helpers. Loaded before the scene engine. */
(() => {
  'use strict';
  let quality = 'balanced';
  try {
    const saved = JSON.parse(localStorage.getItem('ocs.v1') || '{}').renderQuality;
    if (['balanced', 'battery', 'full'].includes(saved)) quality = saved;
  } catch (_) { /* A damaged preference must not prevent startup. */ }
  document.documentElement.dataset.quality = quality;

  const paths = new WeakMap();
  const canvas = document.createElement('canvas');
  const context = canvas.getContext('2d');
  window.OCGraphics = {
    contains(element, x, y) {
      let cached = paths.get(element);
      const data = element.getAttribute('d') || '';
      if (!cached || cached.data !== data) {
        cached = { data, path: new Path2D(data) };
        paths.set(element, cached);
      }
      // SVG isPointInFill forces style/layout when the scene was just changed.
      // Path2D uses the identical path and local coordinates without touching layout.
      return context.isPointInPath(cached.path, x, y, element.getAttribute('fill-rule') || 'nonzero');
    }
  };
})();
