/*
 * Hành vi phía trình duyệt của theme đăng nhập Mission. Nạp đồng bộ trong <head> để bật chế độ
 * chữ lớn trước khi vẽ trang (tránh nháy cỡ chữ). Không dùng script nội tuyến hay innerHTML.
 * Chế độ chữ lớn (NFR-11, data-mode="elderly") chỉ là tuỳ chọn hiển thị của người dùng nên được
 * lưu ở localStorage của trang đăng nhập; không có dữ liệu cá nhân nào được lưu.
 */
(function () {
  'use strict';
  var KEY = 'mission.elderly';
  var root = document.documentElement;

  function readMode() {
    try {
      return localStorage.getItem(KEY) === '1';
    } catch (e) {
      return false;
    }
  }
  function writeMode(on) {
    try {
      localStorage.setItem(KEY, on ? '1' : '0');
    } catch (e) {
      /* trình duyệt chặn lưu trữ: vẫn đổi chế độ cho trang hiện tại */
    }
  }
  function apply(on) {
    if (on) root.setAttribute('data-mode', 'elderly');
    else root.removeAttribute('data-mode');
    var btn = document.getElementById('mission-elderly-toggle');
    if (btn) {
      btn.setAttribute('aria-pressed', on ? 'true' : 'false');
      btn.textContent = on ? btn.getAttribute('data-label-off') : btn.getAttribute('data-label-on');
    }
  }

  apply(readMode());

  document.addEventListener('DOMContentLoaded', function () {
    apply(readMode());
    var domain = document.getElementById('mission-access-domain');
    var host = domain ? domain.querySelector('[data-access-host]') : null;
    if (host && location.host) {
      host.textContent = location.host;
      domain.hidden = false;
    }
    var btn = document.getElementById('mission-elderly-toggle');
    if (btn) {
      btn.addEventListener('click', function () {
        var on = root.getAttribute('data-mode') !== 'elderly';
        writeMode(on);
        apply(on);
      });
    }

    // Chặn gửi biểu mẫu hai lần: khoá nút sau khi trình duyệt đã lấy dữ liệu biểu mẫu
    document.querySelectorAll('form[data-once-submit]').forEach(function (form) {
      form.addEventListener('submit', function () {
        var buttons = form.querySelectorAll('button[type=submit], input[type=submit]');
        setTimeout(function () {
          buttons.forEach(function (b) {
            b.disabled = true;
          });
        }, 0);
      });
    });
  });

  // Liên kết chỉ được bấm một lần (nút nguồn định danh, gửi lại thư), thay script nội tuyến của base
  document.addEventListener('click', function (event) {
    var link = event.target.closest ? event.target.closest('a[data-once-link]') : null;
    if (!link) return;
    if (link.getAttribute('aria-disabled') === 'true') {
      event.preventDefault();
      return;
    }
    var cls = link.getAttribute('data-disabled-class');
    if (cls) cls.trim().split(/\s+/).forEach(function (c) { link.classList.add(c); });
    link.setAttribute('aria-disabled', 'true');
  });
})();
