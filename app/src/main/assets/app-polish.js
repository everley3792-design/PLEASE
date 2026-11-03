/* Measured performance fixes and UI integration for 3.2.2. */
(() => {
  'use strict';
  const html = document.documentElement;
  const app = window.OCPolish = { version: '3.3.0', ready: false };
  let nativeForeground = true;
  const pausedAnimations = new Set();
  let groundTexture;
  let photoTimer;
  app.photoDone = () => {
    clearTimeout(photoTimer);
    document.body.classList.remove('photo-capturing');
    const button = document.querySelector('.l31-photo-bar [data-shot]');
    if (button) { button.disabled = false; button.textContent = '拍照'; }
  };

  // The same static flowers were hundreds of separate HTML / SVG surfaces.
  // Pack them into one standalone SVG image. Keep source nodes off-document
  // for resizing, palette changes and the optional full-motion mode.
  class GroundTexture {
    constructor(ground) {
      this.ground = ground;
      this.sources = [...ground.querySelectorAll('.isle-tuft,.isle-fl:not(.l30-fl),.isle-stone,.isle-lily,.isle-glint')];
      this.fragment = document.createDocumentFragment();
      this.image = null;
      this.url = null;
      this.generation = 0;
      this.timer = 0;
      this.size = '';
      this.specs = [];
      this.signature = '';
      this.observer = new ResizeObserver(() => {
        const key = `${ground.clientWidth}:${ground.clientHeight}`;
        if (key !== this.size && ground.clientWidth) this.schedule();
      });
      this.observer.observe(ground);
      new MutationObserver(() => this.schedule()).observe(Home.stage.root, {
        attributes: true, attributeFilter: ['data-phase', 'data-wx']
      });
      this.schedule();
    }
    schedule() { clearTimeout(this.timer); this.timer = setTimeout(() => this.rebuild(), 180); }
    restore() {
      if (this.fragment.childNodes.length) this.ground.append(this.fragment);
      if (this.image) this.image.hidden = true;
    }
    async rebuild() {
      if (html.dataset.quality === 'full') { this.generation++; this.restore(); return; }
      const width = this.ground.clientWidth, height = this.ground.clientHeight;
      if (!width || !height || Nav.cur !== 0) return;
      const generation = ++this.generation;
      const variables = getComputedStyle(this.ground);
      const palette = ['--isle-tuft', '--isle-tint', '--floor-a', '--floor-b', '--sky-c']
        .map(key => `${key}:${variables.getPropertyValue(key)};`).join('');
      const signature = `${width}:${height}:${palette}`;
      if (signature === this.signature && this.image && !this.image.hidden) return;
      const length = (value, total) => value.endsWith('%') ? parseFloat(value) * total / 100 : parseFloat(value) || 0;
      // Read original styles once. Detached source nodes do not have layout;
      // store their geometry and keep the decoded old image visible during refresh.
      this.sources.forEach((node, index) => {
        if (!node.isConnected && this.specs[index]) return;
        const style = getComputedStyle(node);
        const w = parseFloat(style.width) || 16, h = parseFloat(style.height) || 16;
        const translation = style.translate === 'none' ? ['0', '0'] : style.translate.split(' ');
        const tx = length(translation[0], w), ty = length(translation[1] || '0', h);
        const origin = style.transformOrigin.split(' ').map(parseFloat);
        const scale = style.scale === 'none' ? [1, 1] : style.scale.split(' ').map(Number);
        const rotation = parseFloat(style.rotate) || 0;
        let picture;
        const source = node.querySelector('svg');
        if (source) {
          const clone = source.cloneNode(true);
          clone.setAttribute('width', w); clone.setAttribute('height', h);
          picture = new XMLSerializer().serializeToString(clone);
        } else if (node.classList.contains('isle-stone')) {
          picture = `<ellipse cx="${w / 2}" cy="${h / 2}" rx="${w / 2}" ry="${h / 2}" fill="#C7BAA0"/><ellipse cx="${w * .44}" cy="${h * .38}" rx="${w * .36}" ry="${h * .30}" fill="#E3DAC4" opacity=".8"/>`;
        } else {
          picture = `<ellipse cx="${w / 2}" cy="${h / 2}" rx="${w / 2}" ry="${Math.min(1, h / 2)}" fill="#FFF8DD" opacity=".35"/>`;
        }
        this.specs[index] = { left: node.style.left || style.left, top: node.style.top || style.top,
          origin, tx, ty, rotation, scale, picture };
      });
      const parts = this.specs.map(({left, top, origin, tx, ty, rotation, scale, picture}) => {
        const x = length(left, width), y = length(top, height);
        return `<g transform="translate(${x + origin[0] + tx} ${y + origin[1] + ty}) rotate(${rotation}) scale(${scale[0]} ${scale[1] ?? scale[0]}) translate(${-origin[0]} ${-origin[1]})">${picture}</g>`;
      });
      const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" style="${palette}">${parts.join('')}</svg>`;
      const url = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }));
      const image = new Image(); image.className = 'ground-texture'; image.alt = ''; image.draggable = false;
      image.src = url;
      try {
        await image.decode();
        if (generation !== this.generation || html.dataset.quality === 'full') { URL.revokeObjectURL(url); return; }
        // Swap only a decoded image. No blank frame during a palette or size change.
        this.sources.forEach(node => this.fragment.append(node));
        if (this.image) this.image.replaceWith(image); else this.ground.append(image);
        const old = this.url;
        this.image = image; this.url = url; this.size = `${width}:${height}`;
        this.signature = signature;
        if (old) URL.revokeObjectURL(old);
      } catch (error) {
        URL.revokeObjectURL(url);
        // Keep the original decoration if an older WebView cannot decode it.
        if (!this.image) this.restore();
        console.warn('[ground texture]', error);
      }
    }
  }

  function installDialogue() {
    const home = document.getElementById('p-home');
    const layer = document.createElement('div'); layer.id = 'isleTalk';
    home.append(layer); html.classList.add('isle-talk-on');
    const entries = new Map();
    let serial = 0;
    let lastFrame = 0;
    let safeTop = 128;
    const hide = entry => { if (entry.on) { entry.on = false; entry.element.className = 'isle-tb off'; } };
    const intersect = (a, b) => a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
    const renderer = {
      update() {
        const C = window.OCSCommunity;
        if (!C?.ready || Nav.cur !== 0 || document.hidden || Ov.stack.length || document.body.classList.contains('oc-edit')) {
          entries.forEach(hide); return;
        }
        const camera = window.OCSCamera;
        const width = Home.stage.W, height = Home.stage.H;
        const now = performance.now();
        if (now - lastFrame < 30) return;
        lastFrame = now;
        const active = [];
        for (const node of C.nodes.values()) {
          const source = node.s.bubble;
          if (!source.classList.contains('show') || Cfg.v.bubbles === false || !source.textContent.trim()) continue;
          let entry = entries.get(node.id);
          if (!entry) {
            const element = document.createElement('div'); element.className = 'isle-tb off';
            const body = document.createElement('div'); body.className = 'tb-in'; element.append(body); layer.append(element);
            entry = { element, body, on: false, text: '', serial: 0, width: 180, height: 70, measured: false };
            entries.set(node.id, entry);
          }
          const text = source.textContent.trim();
          if (entry.text !== text) {
            entry.text = text; entry.body.textContent = text;
            entry.element.dataset.name = source.dataset.name || node.s.ch.name;
            entry.body.dataset.name = source.dataset.name || node.s.ch.name;
            entry.body.style.setProperty('--namec', source.style.getPropertyValue('--namec') || 'var(--accent)');
            entry.serial = ++serial; entry.measured = false;
          }
          entry.node = node; active.push(entry);
        }
        // Prefer the controlled character and the most recent line on small screens.
        active.sort((a, b) => Number(b.node.id === Cfg.v.active) - Number(a.node.id === Cfg.v.active) || b.serial - a.serial);
        const shown = active.slice(0, width < 600 ? 2 : 3);
        for (const [id, entry] of entries) {
          if (!C.nodes.has(id)) { entry.element.remove(); entries.delete(id); }
          else if (!shown.includes(entry)) hide(entry);
        }
        if (!shown.length) return;
        // HUD has a fixed footprint; its safe zone needs no DOM measurement.
        safeTop = Math.min(height * .36, 132);
        // Read sizes once when a line changes; never read actor geometry.
        for (const entry of shown) {
          if (entry.measured) continue;
          entry.element.className = 'isle-tb on';
          entry.width = entry.element.offsetWidth || 180;
          entry.height = entry.element.offsetHeight || 70;
          entry.measured = true;
        }
        const placed = [];
        for (const entry of shown) {
          const s = entry.node.s, z = camera?.z || 1;
          const ax = (s.x + s.size / 2) * z + (camera?.x || 0);
          const ay = (s.feetY - s.h - (s._seatLift || 0) - s.hitTop) * z + (camera?.y || 0);
          if (ax < -20 || ax > width + 20 || ay < -80 || ay > height + 40) { hide(entry); continue; }
          const w = entry.width, h = entry.height;
          const x = Math.min(Math.max(12, ax - w / 2), Math.max(12, width - w - 12));
          const initialY = Math.max(safeTop, Math.min(ay - h - 12, height - 154 - h));
          const candidates = [initialY, ...placed.flatMap(other => [other.y - h - 10, other.y + other.h + 10])];
          const y = candidates.find(candidate => candidate >= safeTop && candidate + h <= height - 144
            && !placed.some(other => intersect({ x, y: candidate, w, h }, other)));
          if (y === undefined) { hide(entry); continue; }
          placed.push({ x, y, w, h });
          const transform = `translate3d(${Math.round(x * 2) / 2}px,${Math.round(y * 2) / 2}px,0)`;
          if (entry.element.style.transform !== transform) entry.element.style.transform = transform;
          const tail = `${Math.round(Math.min(Math.max(18, ax - x), w - 18))}px`;
          if (entry.body.style.getPropertyValue('--tx') !== tail) entry.body.style.setProperty('--tx', tail);
          if (y > ay) entry.element.dataset.flip = '1';
          else if (entry.element.hasAttribute('data-flip')) delete entry.element.dataset.flip;
          if (!entry.on) { entry.on = true; entry.element.className = 'isle-tb on'; }
        }
      }
    };
    Ticker.add(renderer);
  }

  function installSettings() {
    app.setQuality = value => {
      if (!['balanced', 'battery', 'full'].includes(value)) return;
      Cfg.set('renderQuality', value); html.dataset.quality = value;
      groundTexture?.schedule();
    };
    const make = Settings.make;
    Settings.make = function () {
      const saved = ['balanced', 'battery', 'full'].includes(Cfg.v.renderQuality) ? Cfg.v.renderQuality : 'balanced';
      if (html.dataset.quality !== saved) { html.dataset.quality = saved; groundTexture?.schedule(); }
      const result = make.apply(this, arguments);
      if (this.body.querySelector('.settings-quality')) return result;
      const section = el('<div class="sec">畫面與效能</div>');
      const group = el('<div class="group settings-quality"><div class="row"><span class="r-ic">' + ic('monitor') + '</span><div class="r-t"><b>畫面效果</b><small>流暢保留角色互動；省電減少背景動態</small></div></div></div>');
      const control = mkSeg([{v:'balanced',t:'流暢'},{v:'battery',t:'省電'},{v:'full',t:'完整動態'}], html.dataset.quality, app.setQuality);
      control.style.margin = '0 12px'; group.append(control);
      this.body.prepend(section, group);
      this.body.append(el('<div class="app-build">OC Shimeji 3.3.0 · 本機自動保存</div>'));
      return result;
    };
    Settings.make();
  }

  function installNavigation() {
    const nav = document.getElementById('nav');
    nav.querySelectorAll('.nav-btn').forEach(button => {
      if (!button.querySelector('.nav-label')) {
        const label = document.createElement('span'); label.className = 'nav-label';
        label.textContent = button.getAttribute('aria-label') || ''; button.append(label);
      }
    });
    const toggle = document.querySelector('#hudRail>[data-go="toggle"]');
    const label = document.createElement('span'); label.className = 'hud-label'; label.textContent = '日常'; toggle.append(label);
    const back = Back.handle;
    Back.handle = function () {
      if (Ov.stack.length) return back.apply(this, arguments);
      const photo = document.querySelector('.l31-photo-bar [data-exit]');
      if (photo) { photo.click(); return true; }
      const ring = document.querySelector('#isleRing .ring-scrim');
      if (ring) { ring.click(); return true; }
      const quick = document.querySelector('#l31Quick [data-close2]');
      if (quick) { quick.click(); return true; }
      const cap = document.getElementById('hudCap');
      if (cap?.classList.contains('open')) { cap.classList.remove('open'); cap.setAttribute('aria-expanded', 'false'); return true; }
      for (const [container, selector] of [['hudRail', '[data-go="toggle"]'], ['fab', '#qaToggle'], ['qbag', '#qbX']]) {
        const node = document.getElementById(container);
        if (node?.classList.contains('open')) { node.querySelector(selector)?.click(); return true; }
      }
      return back.apply(this, arguments);
    };
    const go = Nav.go;
    Nav.go = function (index) {
      app.photoDone();
      if (index !== 0 && document.body.classList.contains('l31-photo')) document.querySelector('.l31-photo-bar [data-exit]')?.click();
      const result = go.apply(this, arguments);
      if (index === 0) groundTexture?.schedule();
      return result;
    };
    document.addEventListener('pointerdown', event => {
      if (event.target.closest('#hudRail')) return;
      const rail = document.getElementById('hudRail');
      if (rail?.classList.contains('open')) toggle.click();
    }, { passive: true });
    document.addEventListener('click', event => {
      const button = event.target.closest('.l31-photo-bar [data-shot]');
      if (!button || typeof window.OCNative?.captureScene !== 'function') return;
      event.preventDefault(); event.stopImmediatePropagation();
      if (button.disabled) return;
      button.disabled = true; button.textContent = '拍攝中…';
      document.body.classList.add('photo-capturing');
      photoTimer = setTimeout(app.photoDone, 6000);
      requestAnimationFrame(() => requestAnimationFrame(() => {
        const name = `OC-Shimeji-${new Date().toISOString().replace(/[:.]/g, '-')}.png`;
        if (!OCNative.captureScene(name)) { app.photoDone(); toast('請先完成目前的匯出操作', 'info'); }
      }));
    }, true);
  }

  function installSuspension() {
    app.syncSuspension = () => {
      const paused = !nativeForeground || document.hidden;
      Ticker.paused = paused;
      if (paused) {
        cancelAnimationFrame(Ticker.raf); Ticker.raf = 0;
        for (const animation of document.getAnimations()) {
          if (animation.playState === 'running') { pausedAnimations.add(animation); animation.pause(); }
        }
      } else {
        for (const animation of pausedAnimations) {
          if (animation.playState === 'paused' && animation.effect?.target?.isConnected) animation.play();
        }
        pausedAnimations.clear(); Ticker.kick();
      }
      const modal = Ov.stack.length > 0 && !window.OCS24D?.Ed?.on;
      html.classList.toggle('scene-paused', paused || modal);
      for (const environment of Env.rt) {
        environment.suspended = paused || (environment.r === Home.stage.root && modal);
        if (environment.suspended) for (const cloud of environment.clouds) cloud.motion?.pause();
      }
    };
    app.setForeground = active => { nativeForeground = active; app.syncSuspension(); };
    const sync = Ov.sync;
    Ov.sync = function () { const result = sync.apply(this, arguments); app.syncSuspension(); return result; };
    document.addEventListener('visibilitychange', app.syncSuspension);
    app.syncSuspension();
  }

  // The fog overlay is a full-screen surface; skip painting it when clear.
  function installAtmosphere() {
    const layer = document.getElementById('sceneAtmosphere');
    if (!layer || typeof Env === 'undefined') return;
    const paint = Env.paint;
    Env.paint = function () {
      const result = paint.apply(this, arguments);
      const fog = parseFloat(layer.style.getPropertyValue('--fog')) || 0;
      if (layer.classList.contains('is-clear') !== fog < .01) layer.classList.toggle('is-clear', fog < .01);
      return result;
    };
    Env.dirty = 1;
  }

  const ready = setInterval(() => {
    if (!window.P32Ready || !window.L31 || !Home.stage) return;
    clearInterval(ready);
    const ground = window.IsleGround?.g;
    if (ground) groundTexture = new GroundTexture(ground);
    installDialogue(); installSettings(); installNavigation(); installSuspension(); installAtmosphere();
    app.ready = true;
  }, 100);
})();
