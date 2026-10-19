import { Page, expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { SECTIONS, CHILD_ROUTES, checkSection, checkChild } from './kiem-tra';
import { totp } from './totp';

/**
 * Chế độ Keycloak dev: đăng nhập thật bằng từng tài khoản thử của realm dev (WEB-056), kỳ vọng tính
 * từ đúng các realm role của tài khoản đó trong `infra/dev/keycloak/realm-mission.json`. Mật khẩu và
 * khoá OTP chỉ đọc từ biến môi trường, không ghi ra log hay ảnh chụp.
 */
const REALM = JSON.parse(
  readFileSync(join(__dirname, '..', '..', 'infra/dev/keycloak/realm-mission.json'), 'utf8'),
) as { users: { username: string; realmRoles: string[] }[] };
const PASSWORD = process.env.KC_TEST_USER_PASSWORD ?? '';
const OTP_SECRET = process.env.KC_TEST_OTP_SECRET ?? '';

/** Hoàn tất trang đăng nhập Keycloak (mật khẩu, rồi OTP nếu luồng yêu cầu) và chờ quay về ứng dụng. */
async function completeKeycloak(page: Page, username: string) {
  const origin = new URL(test.info().project.use.baseURL ?? 'http://localhost:4200').origin;
  // ứng dụng nạp xong mới chuyển sang Keycloak, nên chờ rời khỏi ứng dụng trước
  await page.waitForURL((u) => u.origin !== origin);
  for (let step = 0; step < 4 && !page.url().startsWith(origin); step++) {
    const user = page.locator('#username');
    const otp = page.locator('#otp');
    await expect(user.or(page.locator('#password')).or(otp).first()).toBeVisible();
    if (await otp.isVisible()) {
      await otp.fill(totp(OTP_SECRET));
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

/** Đọc claim của JWT (không kiểm chữ ký, chỉ để kiểm `acr` trong kiểm thử). */
function claims(jwt: string): Record<string, unknown> {
  const b = jwt.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
  return JSON.parse(Buffer.from(b, 'base64').toString('utf8'));
}

/** Nhập mật khẩu và OTP trong cửa sổ bước lên cho tới khi cửa sổ tự đóng. */
async function completePopup(popup: Page, username: string) {
  for (let step = 0; step < 4 && !popup.isClosed(); step++) {
    const otp = popup.locator('#otp');
    const pwd = popup.locator('#password');
    await expect(pwd.or(otp).first()).toBeVisible();
    if (await otp.isVisible()) await otp.fill(totp(OTP_SECRET));
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

test.beforeEach(() => {
  test.skip(!PASSWORD || !OTP_SECRET, 'Thiếu KC_TEST_USER_PASSWORD hoặc KC_TEST_OTP_SECRET');
});

for (const u of REALM.users) {
  const label = `${u.username} (${u.realmRoles.join(', ')})`;
  test(`tài khoản ${label}`, async ({ page }, info) => {
    await page.goto('/');
    await completeKeycloak(page, u.username);
    for (const s of SECTIONS) await checkSection(page, info, label, u.realmRoles, s);
    for (const c of CHILD_ROUTES) await checkChild(page, info, label, u.realmRoles, c);
  });
}

test('401 step-up: hộp xác thực nhiều lớp, Keycloak cấp acr=aal2 rồi gửi lại request', async ({
  page,
}) => {
  // Tắt mock API; request đầu tới API trả 401 insufficient_user_authentication như máy chủ thật,
  // request gửi lại được ghi lại để đọc `acr` của access token mới
  const tokens: string[] = [];
  await page.route('**/config.json', async (route) => {
    const res = await route.fetch();
    await route.fulfill({ response: res, json: { ...(await res.json()), useMockApi: false } });
  });
  await page.route('**/api/v1/**', (route) => {
    const auth = route.request().headers()['authorization'] ?? '';
    if (!route.request().url().includes('/tasks/mine')) {
      return route.fulfill({
        status: 404,
        json: { type: 'about:blank', title: 'Không có', status: 404 },
      });
    }
    tokens.push(auth.replace(/^Bearer /, ''));
    if (tokens.length === 1) {
      return route.fulfill({
        status: 401,
        headers: {
          'WWW-Authenticate':
            'Bearer error="insufficient_user_authentication", acr_values="aal2", max_age=300',
        },
        body: '',
      });
    }
    return route.fulfill({
      status: 403,
      json: { type: 'about:blank', title: 'Đã gửi lại', status: 403 },
    });
  });
  await page.goto('/tasks');
  await completeKeycloak(page, 'canbo');
  const dialog = page.getByRole('dialog', { name: 'Xác thực nhiều lớp' });
  await expect(dialog).toBeVisible();
  expect(claims(tokens[0])['acr']).toBe('aal1');
  const popupOpened = page.waitForEvent('popup');
  await dialog.getByRole('button', { name: 'Xác thực' }).click();
  const popup = await popupOpened;
  expect(new URL(popup.url()).searchParams.get('acr_values')).toBe('aal2');
  await completePopup(popup, 'canbo');
  await expect(page.getByText('Đã gửi lại').first()).toBeVisible();
  expect(tokens).toHaveLength(2);
  expect(claims(tokens[1])['acr']).toBe('aal2');
});
