import { Page, expect, test } from '@playwright/test';
import { totp } from './totp';

/**
 * Đăng nhập Keycloak dev dùng chung cho các kịch bản chạy với máy chủ thật (`keycloak.*`, `that.*`).
 * Mật khẩu và khoá OTP của tài khoản thử (WEB-056) chỉ đọc từ biến môi trường, không ghi ra log hay
 * ảnh chụp.
 */
export const PASSWORD = process.env.KC_TEST_USER_PASSWORD ?? '';
export const OTP_SECRET = process.env.KC_TEST_OTP_SECRET ?? '';

/** Bước 30 giây của mã OTP đã dùng gần nhất theo tài khoản. */
const buocOtpDaDung = new Map<string, number>();

/**
 * Mã OTP chưa dùng của tài khoản: Keycloak từ chối mã đã dùng trong cùng bước 30 giây (chống phát lại), nên
 * hai lần đăng nhập liền nhau của một tài khoản thì lần sau chờ sang bước mới thay vì nhập lại mã cũ.
 */
async function maOtpMoi(username: string): Promise<string> {
  const buoc = () => Math.floor(Date.now() / 30_000);
  const truoc = buocOtpDaDung.get(username);
  if (truoc !== undefined && buoc() <= truoc) {
    await new Promise((r) => setTimeout(r, (truoc + 1) * 30_000 - Date.now() + 250));
  }
  buocOtpDaDung.set(username, buoc());
  return totp(OTP_SECRET);
}

/** Hoàn tất trang đăng nhập Keycloak (mật khẩu, rồi OTP nếu luồng yêu cầu) và chờ quay về ứng dụng. */
export async function completeKeycloak(page: Page, username: string) {
  const origin = new URL(test.info().project.use.baseURL ?? 'http://localhost:4200').origin;
  // ứng dụng nạp xong mới chuyển sang Keycloak, nên chờ rời khỏi ứng dụng trước
  await page.waitForURL((u) => u.origin !== origin);
  for (let step = 0; step < 4 && !page.url().startsWith(origin); step++) {
    const user = page.locator('#username');
    const otp = page.locator('#otp');
    await expect(user.or(page.locator('#password')).or(otp).first()).toBeVisible();
    if (await otp.isVisible()) {
      await otp.fill(await maOtpMoi(username));
    } else {
      if (await user.isVisible()) await user.fill(username);
      await page.locator('#password').fill(PASSWORD);
    }
    await page.locator('#kc-login').click();
    await page.waitForLoadState('load');
  }
  await page.waitForURL((u) => u.origin === origin);
  await expect(page.getByRole('navigation', { name: 'Menu chính' })).toBeVisible();
}

/** Nhập mật khẩu và OTP trong cửa sổ bước lên cho tới khi cửa sổ tự đóng. */
export async function completePopup(popup: Page, username: string) {
  for (let step = 0; step < 4 && !popup.isClosed(); step++) {
    const otp = popup.locator('#otp');
    const pwd = popup.locator('#password');
    await expect(pwd.or(otp).first()).toBeVisible();
    if (await otp.isVisible()) await otp.fill(await maOtpMoi(username));
    else {
      if (await popup.locator('#username').isVisible())
        await popup.locator('#username').fill(username);
      await pwd.fill(PASSWORD);
    }
    await popup.locator('#kc-login').click();
    await popup.waitForEvent('close', { timeout: 5_000 }).catch(() => undefined);
  }
  expect(popup.isClosed()).toBe(true);
}
