// QA-13 · Đăng nhập như trình duyệt (OIDC Authorization Code + PKCE S256, ADR-08) để lấy token thật cho kiểm thử.
// Client mission-web là client công khai, tắt direct grant, nên phải đi qua biểu mẫu Keycloak; OTP tính bằng TOTP
// RFC 6238 từ bí mật trong biến môi trường. Token được giữ trong bộ nhớ của tiến trình, không ghi ra đĩa.
import { createHash, createHmac, randomBytes } from 'node:crypto';
import { cauHinh } from './cau-hinh.mjs';

const cache = new Map();

/**
 * Mã TOTP RFC 6238 (HmacSHA1, 6 chữ số, chu kỳ 30 giây). Keycloak lưu bí mật OTP dạng rõ (`secretData.value`) và dùng
 * chính các byte UTF-8 của chuỗi đó làm khoá HMAC; bản base32 chỉ xuất hiện trong mã QR.
 */
export function totp(secret, thoiDiem = Date.now(), buoc = 30, soChuSo = 6) {
  const dem = Buffer.alloc(8);
  dem.writeBigUInt64BE(BigInt(Math.floor(thoiDiem / 1000 / buoc)));
  const h = createHmac('sha1', Buffer.from(secret, 'utf8')).update(dem).digest();
  const o = h[h.length - 1] & 0xf;
  const so = (h.readUInt32BE(o) & 0x7fffffff) % 10 ** soChuSo;
  return String(so).padStart(soChuSo, '0');
}

class KhoCookie {
  #c = new Map();
  nhan(res) {
    for (const sc of res.headers.getSetCookie?.() ?? []) {
      const [nv] = sc.split(';');
      const i = nv.indexOf('=');
      const ten = nv.slice(0, i).trim();
      const giaTri = nv.slice(i + 1).trim();
      if (giaTri === '' || /max-age=0/i.test(sc)) this.#c.delete(ten);
      else this.#c.set(ten, giaTri);
    }
  }
  tieuDe() {
    return [...this.#c].map(([k, v]) => `${k}=${v}`).join('; ');
  }
}

const giaiHtml = (s) => s.replace(/&amp;/g, '&').replace(/&#x3d;/gi, '=').replace(/&quot;/g, '"');

function actionCuaForm(html) {
  const m = html.match(/<form[^>]*action="([^"]+)"/);
  return m ? giaiHtml(m[1]) : null;
}

export function pkce() {
  const verifier = randomBytes(32).toString('base64url');
  const challenge = createHash('sha256').update(verifier).digest('base64url');
  return { verifier, challenge };
}

export function urlUyQuyen({ challenge, method = 'S256', responseType = 'code', state, nonce } = {}) {
  const u = new URL(`${cauHinh.issuer}/protocol/openid-connect/auth`);
  u.searchParams.set('client_id', cauHinh.clientId);
  u.searchParams.set('redirect_uri', cauHinh.redirectUri);
  u.searchParams.set('response_type', responseType);
  u.searchParams.set('scope', 'openid');
  u.searchParams.set('state', state ?? randomBytes(8).toString('hex'));
  u.searchParams.set('nonce', nonce ?? randomBytes(8).toString('hex'));
  if (challenge) {
    u.searchParams.set('code_challenge', challenge);
    u.searchParams.set('code_challenge_method', method);
  }
  return u.toString();
}

/** Đi theo chuỗi chuyển hướng tới khi gặp trang HTML hoặc redirect_uri (trả `code`). */
async function di(url, kho, init = {}) {
  let res;
  let dich = url;
  for (let i = 0; i < 10; i++) {
    res = await fetch(dich, { ...init, redirect: 'manual', headers: { ...(init.headers ?? {}), cookie: kho.tieuDe() } });
    kho.nhan(res);
    const loc = res.headers.get('location');
    if (res.status >= 300 && res.status < 400 && loc) {
      const tiep = new URL(loc, dich).toString();
      if (tiep.startsWith(cauHinh.redirectUri)) return { code: new URL(tiep).searchParams.get('code'), loi: new URL(tiep).searchParams.get('error') };
      dich = tiep;
      init = {};
      continue;
    }
    return { html: await res.text(), status: res.status };
  }
  throw new Error('Quá nhiều lần chuyển hướng khi đăng nhập');
}

export async function doiCode(code, verifier) {
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: cauHinh.clientId,
    redirect_uri: cauHinh.redirectUri,
    code,
  });
  if (verifier) body.set('code_verifier', verifier);
  return fetch(`${cauHinh.issuer}/protocol/openid-connect/token`, { method: 'POST', body });
}

/** Đăng nhập bằng tên và mật khẩu (kèm OTP khi luồng hỏi), trả token truy cập. */
export async function dangNhap(tenDangNhap) {
  if (cache.has(tenDangNhap)) {
    const t = cache.get(tenDangNhap);
    if (t.exp * 1000 - Date.now() > 30_000) return t.access;
  }
  return (await dangNhapDayDu(tenDangNhap)).access_token;
}

/** Như {@link dangNhap} nhưng trả cả phản hồi token (gồm refresh_token, dùng cho ZAP). */
export async function dangNhapDayDu(tenDangNhap) {
  if (!cauHinh.matKhau) throw new Error('Thiếu KT_MAT_KHAU hoặc KC_TEST_USER_PASSWORD');
  const kho = new KhoCookie();
  const p = pkce();
  let buoc = await di(urlUyQuyen({ challenge: p.challenge }), kho);
  let daGuiOtp = false;
  let daThuLaiOtp = false;
  for (let i = 0; i < 5 && !buoc.code; i++) {
    const action = buoc.html && actionCuaForm(buoc.html);
    if (!action) throw new Error(`Không thấy biểu mẫu đăng nhập (HTTP ${buoc.status})`);
    let form;
    if (/name="otp"/.test(buoc.html)) {
      if (!cauHinh.otpSecret) throw new Error('Luồng đăng nhập hỏi OTP nhưng thiếu KT_OTP_SECRET');
      // Keycloak không nhận lại một mã OTP đã dùng: lần thử lại chờ sang chu kỳ 30 giây kế tiếp.
      if (daGuiOtp) await new Promise((ok) => setTimeout(ok, 30_000 - (Date.now() % 30_000) + 500));
      daGuiOtp = true;
      form = new URLSearchParams({ otp: totp(cauHinh.otpSecret) });
    } else if (/name="password"/.test(buoc.html)) {
      form = new URLSearchParams({ username: tenDangNhap, password: cauHinh.matKhau, credentialId: '' });
    } else {
      throw new Error('Trang đăng nhập yêu cầu thao tác ngoài phạm vi kiểm thử (cập nhật hồ sơ, đổi mật khẩu…)');
    }
    buoc = await di(action, kho, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: form,
    });
    const loi = buoc.html && /id="input-error-(?!container)/.test(buoc.html);
    if (loi && !(daGuiOtp && /name="otp"/.test(buoc.html) && !daThuLaiOtp)) throw new Error(`Đăng nhập ${tenDangNhap} bị từ chối`);
    if (loi) daThuLaiOtp = true;
  }
  if (!buoc.code) throw new Error(`Không nhận được code sau đăng nhập ${tenDangNhap}`);
  const r = await doiCode(buoc.code, p.verifier);
  if (r.status !== 200) throw new Error(`Đổi code lấy token thất bại: HTTP ${r.status}`);
  const t = await r.json();
  const exp = JSON.parse(Buffer.from(t.access_token.split('.')[1], 'base64url').toString()).exp;
  cache.set(tenDangNhap, { access: t.access_token, exp });
  return t;
}

/** Token theo vai: ưu tiên token cấp sẵn trong biến môi trường, sau đó đăng nhập bằng biểu mẫu. */
export async function tokenCua(vai) {
  const san = cauHinh.token[vai];
  if (san) return san;
  const ten = cauHinh[vai];
  if (!ten) throw new Error(`Chưa cấu hình tài khoản cho vai ${vai}`);
  return dangNhap(ten);
}
