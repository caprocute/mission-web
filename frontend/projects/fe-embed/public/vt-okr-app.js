/* global document, window, URL, console */
/*
 * Bộ nạp phần tử <vt-okr-app> cho cổng văn phòng số (WEB-013, TL-01 SVC-17 "Phục vụ và nhúng").
 *
 * Cổng chỉ cần một thẻ:
 *   <script src="https://<máy chủ web>/embed/vt-okr-app.js" nonce="..."></script>
 *   <vt-okr-app tenant="<mã tenant>" route="tasks" ngCspNonce="..."></vt-okr-app>
 *
 * Bộ nạp lấy mọi tệp theo địa chỉ của chính nó (không theo trang cổng), chép nonce CSP của thẻ
 * script sang các thẻ nó thêm, rồi nạp es-module-shims (chế độ shim của Native Federation) và
 * main.js. Tệp này không qua bước build nên viết ES5, không dùng innerHTML hay eval.
 */
(function () {
  'use strict';
  var me = document.currentScript;
  if (!me || window.__vtOkrAppLoader) return;
  window.__vtOkrAppLoader = true;
  var base = new URL('./', me.src).href;
  var nonce = me.nonce || me.getAttribute('nonce') || '';

  function add(el) {
    if (nonce) el.setAttribute('nonce', nonce);
    document.head.appendChild(el);
  }

  var css = document.createElement('link');
  css.rel = 'stylesheet';
  css.href = base + 'styles.css';
  add(css);

  // Native Federation dùng import map dạng shim; đặt trước khi es-module-shims chạy
  var opts = window.esmsInitOptions || {};
  opts.shimMode = true;
  if (nonce && !opts.nonce) opts.nonce = nonce;
  window.esmsInitOptions = opts;

  var shims = document.createElement('script');
  shims.type = 'module';
  shims.src = base + 'polyfills.js';
  shims.onload = function () {
    window.importShim(base + 'main.js').catch(function (err) {
      console.error('[vt-okr-app] Không nạp được ứng dụng', err);
    });
  };
  shims.onerror = function () {
    console.error('[vt-okr-app] Không tải được ' + shims.src);
  };
  add(shims);
})();
