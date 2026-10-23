// QA-13 · SEC-40 (TL-04 mục 9.8): kịch bản xác thực của ZAP (Graal.js) lấy token truy cập từ Keycloak cho ngữ cảnh
// okr-api. Bí mật không nằm trong kế hoạch ZAP hay repo: kịch bản đọc biến môi trường của tiến trình ZAP.
//
//   ZAP_TOKEN_URL      <issuer>/protocol/openid-connect/token (bắt buộc)
//   ZAP_CLIENT_ID      client của người dùng thử
//   ZAP_CLIENT_SECRET  có giá trị thì dùng grant client_credentials (staging, client riêng cho ZAP như TL-04)
//   ZAP_REFRESH_TOKEN  dùng khi không có client secret: grant refresh_token cho client công khai mission-web với
//                      refresh token của người dùng thử (lấy bằng kiem-thu-phu-dinh/lay-token.mjs). Keycloak xoay vòng
//                      refresh token nên kịch bản giữ bản mới nhất trong biến toàn cục của ZAP.
//
// Phản hồi token là JSON; phần quản lý phiên của ngữ cảnh đọc `{%json:access_token%}` để gắn Authorization: Bearer.

var HttpRequestHeader = Java.type('org.parosproxy.paros.network.HttpRequestHeader');
var HttpHeader = Java.type('org.parosproxy.paros.network.HttpHeader');
var URI = Java.type('org.apache.commons.httpclient.URI');
var ScriptVars = Java.type('org.zaproxy.zap.extension.script.ScriptVars');
var JSystem = Java.type('java.lang.System');
var URLEncoder = Java.type('java.net.URLEncoder');

var KHOA_REFRESH = 'mission.qa13.refresh_token';

function bien(ten) {
  var v = JSystem.getenv(ten);
  return v === null || v === undefined ? '' : String(v);
}

function ma(v) {
  return URLEncoder.encode(v, 'UTF-8');
}

function authenticate(helper, paramsValues, credentials) {
  var tokenUrl = bien('ZAP_TOKEN_URL');
  var clientId = bien('ZAP_CLIENT_ID') || 'mission-web';
  var secret = bien('ZAP_CLIENT_SECRET');
  var body;
  if (secret) {
    body = 'grant_type=client_credentials&client_id=' + ma(clientId) + '&client_secret=' + ma(secret);
  } else {
    var refresh = ScriptVars.getGlobalVar(KHOA_REFRESH) || bien('ZAP_REFRESH_TOKEN');
    if (!refresh) {
      throw 'Thiếu ZAP_CLIENT_SECRET hoặc ZAP_REFRESH_TOKEN để lấy token cho ZAP';
    }
    body = 'grant_type=refresh_token&client_id=' + ma(clientId) + '&refresh_token=' + ma(refresh);
  }
  var msg = helper.prepareMessage();
  var header = new HttpRequestHeader(HttpRequestHeader.POST, new URI(tokenUrl, false), HttpHeader.HTTP11);
  header.setHeader(HttpHeader.CONTENT_TYPE, 'application/x-www-form-urlencoded');
  msg.setRequestHeader(header);
  msg.setRequestBody(body);
  msg.getRequestHeader().setContentLength(msg.getRequestBody().length());
  helper.sendAndReceive(msg, false);
  if (!secret && msg.getResponseHeader().getStatusCode() === 200) {
    var moi = JSON.parse(String(msg.getResponseBody().toString())).refresh_token;
    if (moi) {
      ScriptVars.setGlobalVar(KHOA_REFRESH, moi);
    }
  }
  return msg;
}

function getRequiredParamsNames() {
  return [];
}

function getOptionalParamsNames() {
  return [];
}

function getCredentialsParamsNames() {
  return [];
}
