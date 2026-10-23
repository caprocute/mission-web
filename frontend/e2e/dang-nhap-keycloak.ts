import { Page, expect, test } from '@playwright/test';
import { totp } from './totp';

/**
 * Đăng nhập Keycloak dev dùng chung cho các kịch bản chạy với máy chủ thật (`keycloak.*`, `that.*`).
 * Mật khẩu và khoá OTP của tài khoản thử (WEB-056) chỉ đọc từ biến môi trường, không ghi ra log hay
 * ảnh chụp.
 */
export const PASSWORD = process.env.KC_TEST_USER_PASSWORD ?? '';
export const OTP_SECRET = process.env.KC_TEST_OTP_SECRET ?? '';

/** Bước TOTP (30 giây) đã dùng gần nhất của từng tài khoản trong tiến trình kiểm thử này. */
const buocDaDung = new Map<string, number>();

/**
 * Mã OTP chưa dùng của tài khoản. Realm giữ mặc định không cho dùng lại một mã (otpPolicyCodeReusable): nhập lại mã
 * cũ trong cùng bước 30 giây bị coi là sai, và vài lần sai liền khoá tạm tài khoản (chống dò mật khẩu), làm mọi ca
 * sau của tài khoản đó hỏng. Hành trình đăng nhập cùng tài khoản nhiều lần liên tiếp nên chờ sang bước mới.
 */
async function otpMoi(page: Page, username: string): Promise<string> {
  const buoc = () => Math.floor(Date.now() / 30_000);
  const cu = buocDaDung.get(username);
  if (cu !== undefined && buoc() <= cu)
    await page.waitForTimeout((cu + 1) * 30_000 - Date.now() + 500);
  buocDaDung.set(username, buoc());
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
      await otp.fill(await otpMoi(page, username));
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
    if (await otp.isVisible()) await otp.fill(await otpMoi(popup, username));
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
