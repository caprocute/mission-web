// Kiểm tra theme Keycloak tiếng Việt của Mission (YC-TK-13) trên một Keycloak đang chạy.
// Mở trang đăng nhập, đăng nhập (mật khẩu + OTP), trang tài khoản và trang quản trị realm,
// rồi báo chuỗi tiếng Anh còn sót và nút/nhãn bị tràn khung. Chụp ảnh từng màn hình.
//
// Biến môi trường (không ghi giá trị thật vào Git):
//   KC_URL         địa chỉ Keycloak, vd http://127.0.0.1:35080 (qua hầm SSH tới server dev)
//   KC_REALM       realm, mặc định mission
//   KC_USER, KC_PASSWORD, KC_OTP_SECRET   tài khoản thử (OTP_SECRET là chuỗi bí mật thô như Keycloak lưu
//                  trong secretData.value, không phải dạng base32 của mã QR; để trống nếu không có OTP)
//   KC_ADMIN_USER, KC_ADMIN_PASSWORD      quản trị realm master (tuỳ chọn, để kiểm trang quản trị)
//   KC_SHOTS       thư mục lưu ảnh, mặc định ./anh-kiem-thu
// Chạy: node kiem-tra-tieng-viet.mjs  (cần gói playwright)
import { chromium } from 'playwright';
import { createHmac } from 'node:crypto';
import { mkdirSync } from 'node:fs';

const KC = process.env.KC_URL ?? 'http://127.0.0.1:35080';
const REALM = process.env.KC_REALM ?? 'mission';
const SHOTS = process.env.KC_SHOTS ?? './anh-kiem-thu';
mkdirSync(SHOTS, { recursive: true });

// Cụm tiếng Anh thường gặp trên giao diện Keycloak; xuất hiện nguyên cụm là chưa dịch.
const TU_TIENG_ANH = [
  'Sign in', 'Sign out', 'Log in', 'Username', 'Password', 'Forgot Password', 'Remember me',
  'Personal info', 'Account security', 'Signing in', 'Device activity', 'Linked accounts', 'Applications',
  'Save', 'Cancel', 'Delete', 'Create', 'Search', 'Actions', 'Required', 'Enabled', 'Disabled',
  'Users', 'Groups', 'Clients', 'Client scopes', 'Realm roles', 'Realm settings', 'Authentication',
  'Identity providers', 'User federation', 'Sessions', 'Events', 'Manage', 'Configure', 'Organizations',
  'First name', 'Last name', 'Email', 'Welcome', 'Back to', 'Submit', 'Try another way',
];
const CHO_PHEP = ['Email', 'OTP', 'Keycloak', 'English', 'OpenID', 'SAML', 'LDAP', 'Kerberos', 'client', 'realm', 'token'];

function totp(secret) {
  const key = Buffer.from(secret, 'utf8');
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const h = createHmac('sha1', key).update(counter).digest();
  const o = h[h.length - 1] & 0xf;
  return String((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}

const loi = [];
async function kiemTra(page, ten) {
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${SHOTS}/${ten}.png`, fullPage: true });
  const kq = await page.evaluate(([tu, choPhep]) => {
    const text = document.body.innerText;
    const sot = tu.filter((t) => !choPhep.includes(t) && new RegExp(`(^|[^\\p{L}])${t}([^\\p{L}]|$)`, 'u').test(text));
    const tran = [];
    for (const el of document.querySelectorAll('button, a, th, label, [role=tab], .pf-v5-c-nav__link, .pf-v5-c-label, .pf-v5-c-badge')) {
      const r = el.getBoundingClientRect();
      if (!r.width || !r.height) continue;
      const st = getComputedStyle(el);
      if (el.scrollWidth > el.clientWidth + 2 && st.overflow !== 'visible' && st.textOverflow === 'ellipsis') {
        tran.push((el.innerText || '').trim().slice(0, 40));
      }
    }
    return { sot, tran, lang: document.documentElement.lang };
  }, [TU_TIENG_ANH, CHO_PHEP]);
  console.log(`[${ten}] lang=${kq.lang} tiếng Anh sót: ${kq.sot.join(', ') || 'không'}; tràn khung: ${kq.tran.join(' | ') || 'không'}`);
  if (kq.sot.length) loi.push(`${ten}: còn chuỗi tiếng Anh ${kq.sot.join(', ')}`);
  if (kq.tran.length) loi.push(`${ten}: tràn khung ${kq.tran.join(' | ')}`);
}

async function dangNhap(page, user, pass, otpSecret) {
  await page.fill('#username', user);
  await page.fill('#password', pass);
  await page.click('[type=submit]');
  await page.waitForLoadState('domcontentloaded');
  // Luồng đăng nhập có thể không đòi OTP (OTP có điều kiện theo vai trò, DV-02-03).
  const coOtp = otpSecret && (await page.locator('#otp').count()) > 0;
  if (!coOtp && (await page.locator('#username').count()) > 0) {
    await kiemTra(page, '02-dang-nhap-loi');
    throw new Error('Đăng nhập không qua bước mật khẩu, xem ảnh 02-dang-nhap-loi.png');
  }
  if (coOtp) {
    await kiemTra(page, '02-otp');
    await page.fill('#otp', totp(otpSecret));
    await page.click('[type=submit]');
  }
}

const browser = await chromium.launch();
const consoleLoi = [];
try {
  const ctx = await browser.newContext({ locale: 'vi-VN', viewport: { width: 1280, height: 800 } });
  const page = await ctx.newPage();
  page.on('console', (m) => m.type() === 'error' && consoleLoi.push(m.text()));
  page.on('response', (r) => r.status() >= 400 && consoleLoi.push(`HTTP ${r.status()} ${r.request().method()} ${r.url()}`));

  await page.goto(`${KC}/realms/${REALM}/account`);
  await page.waitForSelector('#username', { timeout: 30000 });
  await kiemTra(page, '01-dang-nhap');

  if (process.env.KC_USER) {
    await dangNhap(page, process.env.KC_USER, process.env.KC_PASSWORD, process.env.KC_OTP_SECRET);
    await page.waitForURL(/\/account/, { timeout: 30000 });
    await kiemTra(page, '03-tai-khoan');
    for (const [duong, ten] of [['account-security/signing-in', '04-cach-dang-nhap'], ['account-security/device-activity', '05-thiet-bi'], ['applications', '06-ung-dung']]) {
      await page.goto(`${KC}/realms/${REALM}/account/${duong}`);
      await kiemTra(page, ten);
    }
    // Điện thoại: chế độ hẹp, kiểm tra tràn khung.
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(`${KC}/realms/${REALM}/account`);
    await kiemTra(page, '07-tai-khoan-dien-thoai');
  }

  if (process.env.KC_ADMIN_USER) {
    const ctxA = await browser.newContext({ locale: 'vi-VN', viewport: { width: 1440, height: 900 } });
    const pa = await ctxA.newPage();
    pa.on('console', (m) => m.type() === 'error' && consoleLoi.push(m.text()));
    pa.on('response', (r) => r.status() >= 400 && consoleLoi.push(`HTTP ${r.status()} ${r.request().method()} ${r.url()}`));
    await pa.goto(`${KC}/admin/master/console/#/${REALM}`);
    await pa.waitForSelector('#username', { timeout: 30000 });
    await dangNhap(pa, process.env.KC_ADMIN_USER, process.env.KC_ADMIN_PASSWORD);
    await pa.waitForSelector('nav', { timeout: 60000 });
    for (const [duong, ten] of [['', '10-quan-tri-trang-chu'], ['/users', '11-quan-tri-nguoi-dung'], ['/clients', '12-quan-tri-client'], ['/realm-settings', '13-quan-tri-cai-dat'], ['/authentication', '14-quan-tri-xac-thuc'], ['/organizations', '15-quan-tri-to-chuc']]) {
      await pa.goto(`${KC}/admin/master/console/#/${REALM}${duong}`);
      await kiemTra(pa, ten);
    }
  }
} finally {
  await browser.close();
}
if (consoleLoi.length) console.log('Lỗi console:', [...new Set(consoleLoi)].slice(0, 10).join('\n  '));
console.log(loi.length ? `KẾT QUẢ: ${loi.length} vấn đề\n- ${loi.join('\n- ')}` : 'KẾT QUẢ: ĐẠT');
process.exit(loi.length ? 1 : 0);
