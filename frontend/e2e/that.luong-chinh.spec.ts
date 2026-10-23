import { Browser, BrowserContext, Locator, Page, expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { OTP_SECRET, PASSWORD, completeKeycloak, completePopup } from './dang-nhap-keycloak';

/**
 * Ba luồng nghiệp vụ chính chạy với máy chủ thật trên môi trường kiểm thử đầu cuối QA-03 (WEB-070):
 * 1. lập OKR → phê duyệt → check-in KR (fe-okr; QT-02, QT-03 → SCR-0202, SCR-0203, SCR-0301);
 * 2. lập kế hoạch → giao việc → cập nhật tiến độ (fe-task; QT-04..QT-06 → SCR-0402, SCR-0501, SCR-0601);
 * 3. nộp minh chứng → nghiệm thu có xác thực bước lên (fe-evidence; QT-07 → SCR-0701, SCR-0703).
 *
 * Chỉ chạy khi chọn rõ: `E2E_MODE=that` (nhãn `@qa-03`, máy chủ thật, đăng nhập Keycloak bằng tài
 * khoản thử của realm dev; mật khẩu và khoá OTP đọc từ `KC_TEST_USER_PASSWORD`, `KC_TEST_OTP_SECRET`),
 * hoặc `E2E_MODE=gia-lap E2E_THAT_GIA_LAP=1` để chạy cùng các bước trên dữ liệu giả lập (tắt đăng
 * nhập, người dùng giả lập mang hợp các vai trò của luồng qua sessionStorage `vt.devRoles`).
 *
 * Dữ liệu tạo ra mang dấu thời gian để không trùng giữa các lần chạy. Mỗi luồng kiểm không có lỗi
 * console trên trang của ứng dụng.
 */
const THAT = process.env.E2E_MODE === 'that';
/** Tên người chủ trì khi giao việc (khớp một phần nhãn trong danh sách chọn). */
const CHU_TRI = process.env.E2E_THAT_CHU_TRI ?? (THAT ? 'Nguyễn Văn Cán' : 'Nguyễn Văn An');

const REALM = JSON.parse(
  readFileSync(join(__dirname, '..', '..', 'infra/dev/keycloak/realm-mission.json'), 'utf8'),
) as { users: { username: string; realmRoles: string[] }[] };

/** Tài khoản thử của realm dev theo vai trò cần cho từng bước (docs/phan-quyen/ma-tran.json). */
const TAI_KHOAN = {
  /** member + manager: soạn OKR đơn vị, check-in, lập kế hoạch, giao việc, nghiệm thu (SCR-0703). */
  quanly: ['member', 'manager'],
  /** member + leader: phê duyệt OKR (SCR-0203). */
  lanhdao: ['member', 'leader'],
  /** member: cập nhật tiến độ, nộp minh chứng việc mình chủ trì (SCR-0601, SCR-0701). */
  canbo: ['member'],
} as const;
type TenTaiKhoan = keyof typeof TAI_KHOAN;

function vaiTro(user: TenTaiKhoan): string[] {
  const u = REALM.users.find((x) => x.username === user);
  expect(u, `realm dev thiếu tài khoản ${user}`).toBeTruthy();
  expect(u!.realmRoles).toEqual(expect.arrayContaining([...TAI_KHOAN[user]]));
  return u!.realmRoles;
}

/**
 * Phiên chạy một luồng. Chế độ `that`: mỗi tài khoản một ngữ cảnh trình duyệt, đăng nhập Keycloak ở
 * lần đầu. Chế độ giả lập: một trang duy nhất mang hợp các vai trò, chuyển tuyến trong SPA để kho giả
 * lập (giữ trong bộ nhớ trang) không mất dữ liệu vừa tạo.
 */
class Phien {
  private readonly contexts = new Map<string, { ctx: BrowserContext; page: Page }>();
  private shared: Page | null = null;
  readonly errors: string[] = [];

  constructor(
    private readonly browser: Browser,
    private readonly users: TenTaiKhoan[],
  ) {}

  private watch(page: Page) {
    const origin = new URL(test.info().project.use.baseURL ?? 'http://localhost:4200').origin;
    page.on('console', (m) => {
      if (m.type() === 'error' && page.url().startsWith(origin)) this.errors.push(m.text());
    });
    page.on('pageerror', (e) => this.errors.push(e.message));
  }

  /** Trang của `user` đang mở `url`; đăng nhập (chế độ `that`) nếu là lần đầu. */
  async as(user: TenTaiKhoan, url: string): Promise<Page> {
    if (!THAT) {
      if (!this.shared) {
        const roles = [...new Set(this.users.flatMap(vaiTro))].join(',');
        const ctx = await this.browser.newContext();
        await ctx.addInitScript((r) => sessionStorage.setItem('vt.devRoles', r), roles);
        this.shared = await ctx.newPage();
        this.watch(this.shared);
        await this.shared.goto(url);
        return this.shared;
      }
      await spaGoto(this.shared, url);
      return this.shared;
    }
    vaiTro(user);
    const open = this.contexts.get(user);
    if (open) {
      await spaGoto(open.page, url);
      return open.page;
    }
    const ctx = await this.browser.newContext();
    const page = await ctx.newPage();
    this.watch(page);
    this.contexts.set(user, { ctx, page });
    await page.goto(url);
    await completeKeycloak(page, user);
    const here = new URL(page.url());
    if (here.pathname + here.search !== url) await spaGoto(page, url);
    return page;
  }

  async close() {
    for (const { ctx } of this.contexts.values()) await ctx.close();
    await this.shared?.context().close();
  }
}

/** Chuyển tuyến trong SPA (router Angular nghe `popstate`), không tải lại trang. */
async function spaGoto(page: Page, url: string) {
  await page.evaluate((u) => {
    history.pushState(null, '', u);
    dispatchEvent(new PopStateEvent('popstate', { state: null }));
  }, url);
}

const main = (page: Page) => page.locator('main#main');

/** Chọn một mục của p-select/p-multiselect PrimeNG (danh sách hiện ở `body`). */
async function chon(page: Page, box: Locator, option?: string | RegExp) {
  const list = page.getByRole('listbox');
  // Bấm sớm lúc danh sách chọn chưa nạp xong thì PrimeNG không mở; bấm lại tới khi mở
  await expect(async () => {
    if (!(await list.isVisible())) await box.click();
    await expect(list).toBeVisible({ timeout: 1_000 });
  }).toPass();
  await (option ? list.getByRole('option', { name: option }) : list.getByRole('option'))
    .first()
    .click();
  await expect(list).toBeHidden();
}

/**
 * Hộp "Xác thực nhiều lớp" (step-up RFC 9470): bấm Xác thực; chế độ `that` hoàn tất mật khẩu, OTP
 * trong cửa sổ Keycloak. `batBuoc` kiểm hộp phải hiện (thao tác AAL2 như nghiệm thu).
 */
async function xacThucBuocLen(page: Page, user: TenTaiKhoan, xong: Locator, batBuoc: boolean) {
  const dialog = page.getByRole('dialog', { name: 'Xác thực nhiều lớp' });
  if (batBuoc) await expect(dialog).toBeVisible();
  else await expect(dialog.or(xong).first()).toBeVisible();
  if (await dialog.isVisible()) {
    const popup = THAT ? page.waitForEvent('popup') : null;
    await dialog.getByRole('button', { name: 'Xác thực' }).click();
    if (popup) await completePopup(await popup, user);
  }
  await expect(xong).toBeVisible();
}

/** Ngày `yyyy-mm-dd` sau `min` vài ngày, không vượt `max` (thuộc tính của ô ngày). */
function ngayTrongKy(min: string | null, max: string | null): string {
  const d = min ? new Date(min + 'T00:00:00Z') : new Date();
  d.setUTCDate(d.getUTCDate() + 7);
  const v = d.toISOString().slice(0, 10);
  return max && v > max ? max : v;
}

/** Giao việc từ dòng kế hoạch đã duyệt cho người chủ trì `CHU_TRI` (SCR-0501); trả tên nhiệm vụ. */
async function giaoViec(s: Phien, stamp: string): Promise<string> {
  const page = await s.as('quanly', '/tasks/assign');
  const m = main(page);
  await expect(m.getByRole('heading', { level: 1, name: 'Giao nhiệm vụ' })).toBeVisible();
  await expect(m.getByRole('button', { name: 'Từ kế hoạch quý' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await chon(page, m.getByRole('combobox', { name: 'Chọn dòng kế hoạch đã duyệt' }));
  const name = `Nhiệm vụ kiểm thử ${stamp}`;
  await m.getByRole('textbox', { name: 'Tên nhiệm vụ *' }).fill(name);
  await chon(page, m.getByRole('combobox', { name: 'Chọn người chủ trì' }), CHU_TRI);
  const due = m.getByRole('textbox', { name: 'Hạn hoàn thành *' });
  await due.fill(ngayTrongKy(await due.getAttribute('min'), await due.getAttribute('max')));
  // Dòng kế hoạch thường điền sẵn sản phẩm và tiêu chuẩn; ô nào còn trống thì điền
  const product = m.getByRole('region', { name: 'Sản phẩm và tiêu chuẩn' });
  const fill: [string, string][] = [
    ['Sản phẩm *', 'Báo cáo kết quả'],
    ['Đơn vị tính *', 'báo cáo'],
    ['Minh chứng yêu cầu *', 'Báo cáo đã ký'],
    ['Tiêu chuẩn chất lượng *', 'Đúng thể thức, đủ số liệu'],
  ];
  for (const [label, value] of fill) {
    const box = product.getByRole('textbox', { name: label });
    if (!(await box.inputValue())) await box.fill(value);
  }
  const qty = product.getByRole('spinbutton', { name: 'Số lượng *' });
  if (!Number(await qty.inputValue())) await qty.fill('1');
  await m.getByRole('button', { name: 'Giao việc' }).click();
  await expect(m.getByRole('heading', { level: 1, name: 'Đã giao nhiệm vụ' })).toBeVisible();
  await expect(m.getByText(/Đã giao nhiệm vụ .+ cho đồng chí/)).toBeVisible();
  return name;
}

/**
 * Người chủ trì mở nhiệm vụ trong "Việc của tôi" (SCR-0503 → SCR-0601). Kho giả lập giao việc tách
 * khỏi kho nhiệm vụ nên ở chế độ giả lập nhiệm vụ vừa giao không có trong danh sách: mở nhiệm vụ đầu
 * tiên đồng chí đang chủ trì và đang thực hiện.
 */
async function moViecCuaToi(s: Phien, name: string): Promise<Page> {
  const page = await s.as('canbo', '/tasks');
  const m = main(page);
  await expect(m.getByRole('heading', { level: 1, name: 'Việc của tôi' })).toBeVisible();
  const list = m.getByRole('region', { name: 'Danh sách nhiệm vụ' });
  if (THAT) {
    await m.getByRole('searchbox', { name: 'Tìm nhiệm vụ' }).fill(name);
    await list.getByRole('link', { name }).click();
  } else {
    await list
      .getByRole('row')
      .filter({ hasText: 'Chủ trì' })
      .filter({ hasText: 'Đang thực hiện' })
      .first()
      .getByRole('link')
      .click();
  }
  await expect(m.getByRole('button', { name: 'Cập nhật tiến độ' })).toBeVisible();
  if (THAT) await expect(m.getByRole('heading', { level: 1, name })).toBeVisible();
  return page;
}

test.describe('Luồng chính với máy chủ thật', { tag: '@qa-03' }, () => {
  let s: Phien;

  test.beforeEach(({ browser }) => {
    test.skip(
      THAT && (!PASSWORD || !OTP_SECRET),
      'Thiếu KC_TEST_USER_PASSWORD hoặc KC_TEST_OTP_SECRET',
    );
    test.setTimeout(THAT ? 300_000 : 120_000);
    s = new Phien(browser, Object.keys(TAI_KHOAN) as TenTaiKhoan[]);
  });

  test.afterEach(async () => {
    const errors = [...s.errors];
    await s.close();
    expect(errors, 'lỗi console trên trang ứng dụng').toEqual([]);
  });

  test('lập OKR → phê duyệt → check-in KR', async () => {
    const stamp = String(Date.now());
    const title = `Mục tiêu kiểm thử ${stamp}`;
    const kr = (i: number) => `KR kiểm thử ${stamp} số ${i + 1}`;

    // Soạn mục tiêu đơn vị và trình phê duyệt (SCR-0202)
    let page = await s.as('quanly', '/okr/objectives/new');
    let m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: /^Soạn mục tiêu/ })).toBeVisible();
    // Chờ ngữ cảnh soạn (kỳ, căn cứ, mục tiêu cấp trên) nạp xong rồi mới điền
    await expect(m.getByRole('region', { name: 'Kiểm tra trước khi trình' })).toBeVisible();
    const basis = m.getByRole('combobox', { name: 'Căn cứ, bắt buộc' });
    const basisList = page.getByRole('listbox');
    await expect(async () => {
      if (!(await basisList.isVisible())) {
        await basis.focus();
        await page.keyboard.press('ArrowDown');
      }
      await expect(basisList.getByRole('option').first()).toBeVisible({ timeout: 1_000 });
    }).toPass();
    await basisList.getByRole('option').first().click();
    await page.keyboard.press('Escape');
    await expect(basisList).toBeHidden();
    const parent = m.getByRole('combobox', { name: 'Chọn mục tiêu cấp trên' });
    if (await parent.isVisible()) await chon(page, parent);
    await m.getByRole('textbox', { name: 'Tên mục tiêu' }).fill(title);
    const reason = m.getByRole('textbox', { name: /^Lý do vượt/ });
    if (await reason.isVisible()) await reason.fill('Kiểm thử đầu cuối luồng lập OKR');
    const krTitles = m.getByRole('textbox', { name: /^Nội dung KR/ });
    const krCount = await krTitles.count();
    expect(krCount).toBeGreaterThan(0);
    for (let i = 0; i < krCount; i++) {
      await krTitles.nth(i).fill(kr(i));
      await m
        .getByRole('combobox', { name: /^Đơn vị đo KR/ })
        .nth(i)
        .fill('%');
      await m
        .getByRole('spinbutton', { name: /^Chỉ tiêu KR/ })
        .nth(i)
        .fill('100');
    }
    const unit = await m.getByRole('textbox', { name: 'Đơn vị sở hữu' }).inputValue();
    const submit = m.getByRole('button', { name: 'Trình phê duyệt' });
    await expect(submit).toBeEnabled();
    await submit.click();
    await expect(m.getByRole('status').filter({ hasText: /Đã trình phê duyệt/ })).toBeVisible();

    // Lãnh đạo phê duyệt bộ OKR của đơn vị (SCR-0203)
    page = await s.as('lanhdao', '/okr/phe-duyet');
    m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Phê duyệt mục tiêu, OKR' }),
    ).toBeVisible();
    await m.getByRole('region', { name: 'Hàng chờ' }).getByRole('button', { name: unit }).click();
    await expect(m.getByRole('rowheader', { name: title })).toBeVisible();
    await m.getByRole('button', { name: 'Phê duyệt', exact: true }).click();
    await xacThucBuocLen(
      page,
      'lanhdao',
      m.getByRole('status').filter({ hasText: /Đã phê duyệt OKR/ }),
      false,
    );

    // Chủ sở hữu check-in KR vừa được duyệt (SCR-0301)
    page = await s.as('quanly', '/okr/checkin');
    m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Check-in kết quả then chốt' }),
    ).toBeVisible();
    await m
      .getByRole('region', { name: 'KR của tôi cần check-in' })
      .getByRole('button', { name: kr(0) })
      .click();
    await expect(m.getByRole('heading', { level: 2, name: kr(0) })).toBeVisible();
    await m.getByRole('textbox', { name: /^Giá trị hiện tại/ }).fill('40');
    await m.getByRole('radio', { name: 'Trung bình' }).check();
    await m
      .getByRole('textbox', { name: 'Kết quả nổi bật trong tuần' })
      .fill('Đã triển khai kế hoạch');
    await m.getByRole('button', { name: 'Gửi check-in' }).click();
    await expect(m.getByRole('status').filter({ hasText: /Đã gửi check-in/ })).toBeVisible();
  });

  test('lập kế hoạch → giao việc → cập nhật tiến độ', async () => {
    const stamp = String(Date.now());
    const item = `Việc kế hoạch kiểm thử ${stamp}`;

    // Thêm một việc vào kế hoạch quý và lưu nháp (SCR-0402)
    const page = await s.as('quanly', '/plans');
    const m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: /^Kế hoạch công tác/ })).toBeVisible();
    await m.getByRole('button', { name: 'Thêm việc ngoài danh mục' }).click();
    const editor = m.getByRole('region', { name: /^Sửa dòng \d+/ });
    await editor.getByRole('textbox', { name: 'Nội dung công việc *' }).fill(item);
    await editor.getByRole('textbox', { name: 'Sản phẩm *' }).fill('Báo cáo kết quả');
    await editor.getByRole('spinbutton', { name: 'Số lượng *' }).fill('1');
    await editor.getByRole('textbox', { name: 'Đơn vị tính *' }).fill('báo cáo');
    await editor.getByRole('button', { name: 'Xong' }).click();
    await m.getByRole('button', { name: 'Lưu nháp' }).click();
    await expect(m.getByText(/Đã lưu nháp lúc/)).toBeVisible();
    await expect(m.getByRole('cell', { name: item })).toBeVisible();

    // Giao nhiệm vụ từ dòng kế hoạch đã duyệt (SCR-0501)
    const name = await giaoViec(s, stamp);

    // Người chủ trì cập nhật tiến độ (SCR-0601)
    const own = await moViecCuaToi(s, name);
    const om = main(own);
    await om.getByRole('button', { name: 'Cập nhật tiến độ' }).click();
    const dialog = own.getByRole('dialog', { name: 'Cập nhật tiến độ' });
    await dialog.getByRole('slider', { name: 'Tiến độ' }).focus();
    await own.keyboard.press('ArrowRight');
    await own.keyboard.press('ArrowRight');
    await dialog.getByRole('textbox', { name: 'Kết quả đã làm' }).fill('Đã hoàn thành dự thảo');
    await dialog.getByRole('button', { name: /^Lưu \d+%$/ }).click();
    await expect(dialog).toBeHidden();
    await expect(om.getByRole('status').filter({ hasText: 'Đã lưu tiến độ.' })).toBeVisible();
  });

  test('nộp minh chứng → nghiệm thu có xác thực nhiều lớp', async () => {
    const stamp = String(Date.now());
    const name = await giaoViec(s, stamp);

    // Người chủ trì nộp minh chứng (SCR-0701)
    const page = await moViecCuaToi(s, name);
    const m = main(page);
    await m.getByRole('button', { name: 'Nộp sản phẩm' }).click();
    await expect(
      m.getByRole('heading', { level: 1, name: 'Sản phẩm, minh chứng của nhiệm vụ' }),
    ).toBeVisible();
    // Sản phẩm chưa nộp có nút "Nộp minh chứng"; sản phẩm đang có bản nháp có liên kết "Tiếp tục nộp"
    await m
      .getByRole('button', { name: 'Nộp minh chứng' })
      .or(m.getByRole('link', { name: 'Tiếp tục nộp' }))
      .first()
      .click();
    await expect(
      m.getByRole('heading', { level: 1, name: 'Nộp sản phẩm, minh chứng' }),
    ).toBeVisible();
    const file = `minh-chung-${stamp}.pdf`;
    await m.getByLabel('chọn tệp').setInputFiles({
      name: file,
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n'),
    });
    const row = m.getByRole('row').filter({ hasText: file });
    await expect(row.getByRole('cell', { name: 'Sạch' })).toBeVisible({ timeout: 60_000 });
    await m.getByRole('checkbox', { name: /^Tôi xác nhận minh chứng/ }).check();
    await m.getByRole('button', { name: 'Nộp để nghiệm thu' }).click();
    await expect(m.getByRole('status').filter({ hasText: /Đã nộp phiên bản/ })).toBeVisible();

    // Người nghiệm thu xác nhận nghiệm thu năm mức, bắt buộc bước lên AAL2 (SCR-0703, BR-07-11).
    // Giả lập chặn tự nghiệm thu minh chứng do chính mình nộp, nên chọn mục đầu của hàng chờ.
    const rp = await s.as('quanly', '/evidence?reviewer=me');
    const rm = main(rp);
    await expect(
      rm.getByRole('heading', { level: 1, name: 'Minh chứng chờ tôi nghiệm thu' }),
    ).toBeVisible();
    const queue = rm.getByRole('listitem');
    await (THAT ? queue.filter({ hasText: name }) : queue)
      .first()
      .getByRole('link', { name: 'Nghiệm thu' })
      .click();
    await expect(rm.getByRole('heading', { level: 1, name: 'Nghiệm thu sản phẩm' })).toBeVisible();
    if (THAT) await expect(rm.getByText(name).first()).toBeVisible();
    const criteria = rm.getByRole('radio', { name: 'Đạt', exact: true });
    await expect(rm.getByRole('region', { name: 'Đối chiếu tiêu chuẩn chất lượng' })).toBeVisible();
    for (let i = 0; i < (await criteria.count()); i++) await criteria.nth(i).check();
    await rm.getByRole('radio', { name: /^1,00/ }).check();
    const cause = rm.getByRole('radio', { name: 'Khách quan' });
    if (await cause.isVisible()) await cause.check();
    await rm.getByRole('textbox', { name: 'Nhận xét' }).fill('Sản phẩm đạt yêu cầu');
    await rm.getByRole('button', { name: 'Xác nhận nghiệm thu' }).click();
    await xacThucBuocLen(
      rp,
      'quanly',
      rm.getByRole('status').filter({ hasText: /Đã xác nhận nghiệm thu/ }),
      true,
    );
  });
});
