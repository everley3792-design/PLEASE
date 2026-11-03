/* Android-only integration. The original application stays in index.html. */
(() => {
  'use strict';
  const bridge = window.OCNative;
  if (!bridge) return;
  const MAX_BYTES = 32 * 1024 * 1024;
  const requests = new Map();
  let sequence = 0;
  let foreground = true;

  const notify = text => {
    if (typeof toast === 'function') toast(text, 'info');
  };

  function encode(blob) {
    if (blob.size > MAX_BYTES) return Promise.reject(new Error('檔案超過 32 MB，請分批匯出'));
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = () => reject(new Error('無法讀取檔案'));
      reader.onload = () => resolve(String(reader.result).split(',')[1]);
      reader.readAsDataURL(blob);
    });
  }

  async function download(anchor) {
    try {
      const response = await fetch(anchor.href);
      if (!response.ok) throw new Error('無法讀取匯出檔案');
      const blob = await response.blob();
      const content = await encode(blob);
      bridge.saveBase64(anchor.download || 'oc-shimeji-export.json', blob.type, content);
    } catch (error) { notify(error.message); }
  }

  // Old workshop exports call anchor.click() directly, including detached anchors.
  // Capture object URLs immediately, before the original code revokes them.
  const originalClick = HTMLAnchorElement.prototype.click;
  const isExport = anchor => anchor && /^(blob:|data:)/.test(anchor.href);
  HTMLAnchorElement.prototype.click = function () {
    if (isExport(this)) { void download(this); return; }
    return originalClick.call(this);
  };
  document.addEventListener('click', event => {
    const anchor = event.target.closest?.('a');
    if (isExport(anchor)) { event.preventDefault(); void download(anchor); }
  }, true);

  // Android WebView has no Web Share file implementation. Supply the file-only
  // variant used by Backup and AnimUI, with a real system share sheet.
  const canShare = data => Array.isArray(data?.files) && data.files.length === 1
    && data.files[0] instanceof Blob && data.files[0].size <= MAX_BYTES;
  Object.defineProperty(navigator, 'canShare', { configurable: true, value: canShare });
  Object.defineProperty(navigator, 'share', {
    configurable: true,
    value: async data => {
      if (!canShare(data)) throw new TypeError('一次可分享一個不超過 32 MB 的檔案');
      const file = data.files[0];
      const content = await encode(file);
      const id = `share-${++sequence}`;
      return new Promise((resolve, reject) => {
        requests.set(id, { resolve, reject });
        if (!bridge.shareBase64(file.name || 'oc-shimeji-export.json', file.type, content, id)) {
          requests.delete(id);
          reject(new Error('目前無法分享，請先完成其他匯出操作'));
        }
      });
    }
  });

  // Existing buzz() calls use navigator.vibrate; route short feedback through
  // the declared Android permission, respecting the app's haptics preference.
  Object.defineProperty(navigator, 'vibrate', {
    configurable: true,
    value: pattern => {
      if (typeof Cfg !== 'undefined' && Cfg.v.haptics === false) return false;
      const duration = Array.isArray(pattern) ? pattern[0] : pattern;
      if (Number(duration) > 0) bridge.vibrate(Math.min(200, Number(duration)));
      return true;
    }
  });

  function flush() {
    // Each store owns its schema. Call its existing persistence method.
    const jobs = [
      () => Cfg.flush(), () => Chars.flush(),
      () => { if (AnimUI.api) return AnimUI.flush(); },
      () => Items.save(true), () => Eco.save(),
      () => { if (window.OCSCommunity?.ready) OCSCommunity.persist(); },
      () => window.OCSCamera?.persist()
    ];
    for (const job of jobs) {
      try { Promise.resolve(job()).catch(console.warn); } catch (error) { console.warn(error); }
    }
  }

  window.OCAndroid = {
    finishRequest(id, success, error) {
      const request = requests.get(id);
      if (!request) return;
      requests.delete(id);
      if (success) request.resolve();
      else request.reject(new Error(error || '分享失敗'));
    },
    resume(active) {
      foreground = active;
      window.OCPolish?.setForeground?.(active);
      if (!active) { flush(); window.OCApp?.onPause(); }
      else window.OCApp?.onResume();
    },
    back() {
      // Workshop's drawing overlay is outside the original Ov stack.
      const closeDrawing = document.querySelector('.c4-ov #tTop [data-x="close"]');
      if (closeDrawing && !Ov.stack.length) { closeDrawing.click(); return true; }
      return !!window.OCApp?.back();
    }
  };
  document.addEventListener('visibilitychange', () => { if (document.hidden) flush(); });

  function syncTheme() {
    const meta = document.querySelector('meta[name="theme-color"]');
    bridge.setTheme(meta?.content || '#F4F2EC', document.documentElement.dataset.theme === 'dark');
  }
  new MutationObserver(syncTheme).observe(document.documentElement, {
    attributes: true, attributeFilter: ['data-theme']
  });
  const themeMeta = document.querySelector('meta[name="theme-color"]');
  if (themeMeta) new MutationObserver(syncTheme).observe(themeMeta, { attributes: true });
  syncTheme();

  // A bridge for file export does not implement the original planned overlay,
  // notification or widget features. Keep their UI honest and non-interactive.
  const settingsMake = Settings.make;
  Settings.make = function () {
    const result = settingsMake.apply(this, arguments);
    const label = [...this.body.querySelectorAll('.r-t b')].find(node => node.textContent === '桌面懸浮窗');
    if (label) {
      const row = label.closest('.row');
      const control = row.querySelector('[role="switch"]');
      control.disabled = true;
      control.setAttribute('aria-checked', 'false');
      row.querySelector('small').textContent = '此版本尚未加入跨 App 懸浮桌寵';
    }
    const status = [...this.body.querySelectorAll('.r-t b')].find(node => node.textContent === '裝置連線');
    if (status) status.parentElement.querySelector('small').textContent = '已連接檔案匯入、另存與分享';
    return result;
  };

  const ready = setInterval(() => {
    if (!Home.stage || !Settings.body) return;
    clearInterval(ready);
    Settings.make();
    document.querySelectorAll('#exNative .tag').forEach(tag => { tag.textContent = '尚未實作'; });
    if (!foreground) window.OCApp?.onPause();
  }, 200);
})();
