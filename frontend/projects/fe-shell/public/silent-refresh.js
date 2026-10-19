// WEB-006: trang nhận kết quả của cửa sổ MFA bước lên (angular-oauth2-oidc initLoginFlowInPopup).
// Gửi mã ủy quyền (query của luồng code) về cửa sổ chính cùng nguồn; tệp riêng để hợp CSP (WEB-014).
/* global window, location */
(function () {
  var target = window.opener || window.parent;
  var payload = location.hash || '#' + location.search;
  if (target && target !== window) target.postMessage(payload, location.origin);
})();
