import { Page, Route, expect, test } from '@playwright/test';

/**
 * Lỗi phân quyền từ máy chủ (wiki/06, wiki/15): nút hiện theo ma trận vẫn có thể bị OPA từ chối, và
 * giao diện phải hiện đúng MSG của máy chủ, không nuốt lỗi. Tắt mock API trong `config.json` để
 * request đi ra mạng rồi trả lời bằng `page.route` như máy chủ thật.
 */
const MSG_02_11 = 'Đồng chí là người soạn bộ OKR này nên không thể phê duyệt.';

/** Chỉ API danh sách "Việc của tôi" trả theo `respond`; API khác (ví dụ `/org/me`) trả 404. */
async function realApi(page: Page, respond: (route: Route) => Promise<void>) {
  await page.context().addInitScript(() => sessionStorage.setItem('vt.devRoles', 'member'));
  await page.route('**/config.json', async (route) => {
    const res = await route.fetch();
    await route.fulfill({ response: res, json: { ...(await res.json()), useMockApi: false } });
  });
  await page.route('**/api/v1/**', (route) =>
    route.request().url().includes('/tasks/mine')
      ? respond(route)
      : route.fulfill({
          status: 404,
          json: { type: 'about:blank', title: 'Không có', status: 404 },
        }),
  );
}

test('403 có code: hiện đúng thông điệp MSG của máy chủ', async ({ page }) => {
  await realApi(page, (route) =>
    route.fulfill({
      status: 403,
      contentType: 'application/problem+json',
      json: {
        type: 'https://mission.gov.vn/problems/forbidden',
        title: MSG_02_11,
        status: 403,
        code: 'MSG-02-11',
        detail: 'BR-02-08',
      },
    }),
  );
  await page.goto('/tasks');
  await expect(page.getByText(MSG_02_11)).toBeVisible();
});

test('403 không có body: hiện thông báo không có quyền chung', async ({ page }) => {
  await realApi(page, (route) => route.fulfill({ status: 403, body: '' }));
  await page.goto('/tasks');
  // Màn hình hiện lỗi của danh sách, `authInterceptor` (WEB-006) hiện thêm thông báo chung
  await expect(
    page.getByText('Đồng chí không có quyền thực hiện thao tác này').first(),
  ).toBeVisible();
});

test('401 step-up: hiện hộp xác thực nhiều lớp, xác thực xong thì gửi lại request', async ({
  page,
}) => {
  let calls = 0;
  await realApi(page, (route) => {
    calls++;
    if (calls === 1) {
      return route.fulfill({
        status: 401,
        headers: {
          'WWW-Authenticate':
            'Bearer error="insufficient_user_authentication", acr_values="aal2", max_age=300',
        },
        body: '',
      });
    }
    // Lần gửi lại: trả 403 có MSG để thấy rõ request đã được gửi lại sau bước lên
    return route.fulfill({
      status: 403,
      contentType: 'application/problem+json',
      json: { type: 'about:blank', title: MSG_02_11, status: 403, code: 'MSG-02-11' },
    });
  });
  await page.goto('/tasks');
  const dialog = page.getByRole('dialog', { name: 'Xác thực nhiều lớp' });
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: 'Xác thực' }).click();
  await expect(page.getByText(MSG_02_11)).toBeVisible();
  expect(calls).toBe(2);
});
