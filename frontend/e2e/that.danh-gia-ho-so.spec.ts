import { Browser, BrowserContext, Locator, Page, expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { OTP_SECRET, PASSWORD, completeKeycloak, completePopup } from './dang-nhap-keycloak';

/**
 * Ba luồng đánh giá, hồ sơ số, khiếu nại chạy với máy chủ thật trên môi trường QA-03 (WEB-071), cùng
 * khuôn với `that.luong-chinh.spec.ts` (WEB-070):
 * 1. đánh giá quý: tự đánh giá và ký nộp → thẩm định có ký số → đề xuất mức → quyết định ký lô
 *    (fe-appraisal; QT-09 → SCR-0901, SCR-0902, SCR-0903, SCR-0904; ký số 202 + theo dõi tác vụ);
 * 2. gói hồ sơ số: sinh lại biểu mẫu lỗi → tải gói ZIP và manifest → ký số gói và nộp cấp trên
 *    (fe-dossier; QT-11 → SCR-1102; mọi bước là tác vụ dài 202 + theo dõi);
 * 3. khiếu nại: cán bộ ký số và gửi → người xử lý tiếp nhận, ghi kết luận → người có thẩm quyền ký
 *    văn bản trả lời (fe-oversight; QT-12 → SCR-1201, SCR-1202).
 *
 * Chạy khi chọn rõ: `E2E_MODE=that` (nhãn `@qa-03`, đăng nhập Keycloak bằng tài khoản thử của realm
 * dev; mật khẩu, khoá OTP đọc từ `KC_TEST_USER_PASSWORD`, `KC_TEST_OTP_SECRET`), hoặc
 * `E2E_MODE=gia-lap E2E_THAT_GIA_LAP=1` để chạy cùng các bước trên dữ liệu giả lập (một trang mang hợp
 * các vai trò của luồng qua sessionStorage `vt.devRoles`, chuyển tuyến trong SPA).
 *
 * Sau mỗi luồng: kiểm không có lỗi console trên trang ứng dụng và quét localStorage, sessionStorage,
 * IndexedDB của mọi trang đã mở để chắc nội dung nhận xét, khiếu nại (dữ liệu C2) vừa nhập không nằm ở
 * dạng rõ trong bộ nhớ trình duyệt (wiki/08). Trạng thái hiển thị trong lúc chờ ký phải là nhãn nghiệp
 * vụ, không lộ mã trạng thái thô (wiki/16).
 */
const THAT = process.env.E2E_MODE === 'that';
/** Chờ ký số từ xa (MySign) và tác vụ dài: máy chủ thật có thể mất tới vài phút. */
const CHO_TAC_VU = THAT ? 180_000 : 15_000;

const REALM = JSON.parse(
  readFileSync(join(__dirname, '..', '..', 'infra/dev/keycloak/realm-mission.json'), 'utf8'),
) as { users: { username: string; realmRoles: string[] }[] };

/**
 * Tài khoản thử của realm dev theo vai trò cần cho từng bước (docs/phan-quyen/ma-tran.json). Tên đăng
 * nhập ghi đè được bằng `E2E_THAT_TK_<KHOA>` (ví dụ `E2E_THAT_TK_THAMDINH`) khi môi trường QA-03 dùng
 * tài khoản khác; tài khoản ghi đè không có trong realm dev thì không kiểm được vai trò.
 */
const TAI_KHOAN = {
  /** member: tự đánh giá, ký nộp (SCR-0901), soạn, ký gửi khiếu nại (SCR-1201). */
  canbo: ['member'],
  /** member + appraiser: thẩm định (SCR-0902), chuẩn bị, tải gói (SCR-1102), xử lý khiếu nại (SCR-1202). */
  thamdinh: ['member', 'appraiser'],
  /** member + manager: ghi mức đề xuất xếp loại (SCR-0903 `de-xuat`). */
  quanly: ['member', 'manager'],
  /** decider: quyết định, ký lô (SCR-0904), ký gói (SCR-1102), ký văn bản trả lời (SCR-1202). */
  quyetdinh: ['decider'],
} as const;
type TenTaiKhoan = keyof typeof TAI_KHOAN;

const tenDangNhap = (user: TenTaiKhoan) => process.env[`E2E_THAT_TK_${user.toUpperCase()}`] || user;

function vaiTro(user: TenTaiKhoan): string[] {
  const name = tenDangNhap(user);
  const u = REALM.users.find((x) => x.username === name);
  if (!u && name !== user) {
    test.info().annotations.push({
      type: 'canh-bao',
      description: `Tài khoản ${name} (ghi đè ${user}) không có trong realm dev, không kiểm được vai trò`,
    });
    return [...TAI_KHOAN[user]];
  }
  expect(u, `realm dev thiếu tài khoản ${name}`).toBeTruthy();
  expect(u!.realmRoles).toEqual(expect.arrayContaining([...TAI_KHOAN[user]]));
  return u!.realmRoles;
}

/**
 * Phiên chạy một luồng (như WEB-070). Chế độ `that`: mỗi tài khoản một ngữ cảnh trình duyệt, đăng nhập
 * Keycloak ở lần đầu. Chế độ giả lập: một trang duy nhất mang hợp các vai trò, chuyển tuyến trong SPA để
 * kho giả lập (giữ trong bộ nhớ trang) không mất dữ liệu vừa tạo.
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
    await completeKeycloak(page, tenDangNhap(user));
    const here = new URL(page.url());
    if (here.pathname + here.search !== url) await spaGoto(page, url);
    return page;
  }

  /** Mọi trang ứng dụng đã mở trong luồng (để quét bộ nhớ trình duyệt). */
  pages(): Page[] {
    return this.shared ? [this.shared] : [...this.contexts.values()].map((x) => x.page);
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

/** Chuỗi trông như mã trạng thái thô (`CHO_KY`, `DA_THAM_DINH`…), không được hiện lên màn hình. */
const MA_THO = /\b[A-Z]{2,}(?:_[A-Z0-9]+)+\b/;

async function khongMaTho(m: Locator) {
  expect(await m.innerText(), 'mã trạng thái thô trên màn hình').not.toMatch(MA_THO);
}

/**
 * Hộp "Xác thực nhiều lớp" (step-up RFC 9470) trước khi ký số: bấm Xác thực; chế độ `that` hoàn tất
 * mật khẩu, OTP trong cửa sổ Keycloak. Phiên đã đạt `aal2` trong 15 phút thì hộp không hiện.
 */
async function xacThucBuocLen(page: Page, user: TenTaiKhoan, xong: Locator) {
  const dialog = page.getByRole('dialog', { name: 'Xác thực nhiều lớp' });
  await expect(dialog.or(xong).first()).toBeVisible();
  if (await dialog.isVisible()) {
    const popup = THAT ? page.waitForEvent('popup') : null;
    await dialog.getByRole('button', { name: 'Xác thực' }).click();
    if (popup) await completePopup(await popup, tenDangNhap(user));
  }
  await expect(xong).toBeVisible();
}

/**
 * Theo dõi bước ký số (202 + JobTracker) tới khi thông báo `xong` hiện: trong lúc chờ, mỗi lần nhìn
 * màn hình đều không có mã trạng thái thô. Chế độ `that` (ký số từ xa chờ người ký xác nhận) còn bắt
 * buộc thấy nhãn chờ `cho` (trừ khi `batBuocCho` tắt); giả lập ký xong rất nhanh nên không bắt buộc.
 */
async function theoDoiKy(m: Locator, cho: RegExp, xong: Locator, batBuocCho = true) {
  const seen: string[] = [];
  await expect(async () => {
    const text = await m.innerText();
    expect(text, 'mã trạng thái thô khi chờ ký').not.toMatch(MA_THO);
    if (cho.test(text)) seen.push(cho.exec(text)![0]);
    await expect(xong).toBeVisible({ timeout: 500 });
  }).toPass({ timeout: CHO_TAC_VU, intervals: [250] });
  if (THAT && batBuocCho)
    expect(seen.length, 'nhãn chờ phải hiện trong lúc chờ').toBeGreaterThan(0);
  await khongMaTho(m);
}

/**
 * Quét localStorage, sessionStorage và IndexedDB (mọi cơ sở, mọi kho) của các trang trong phiên: không
 * được chứa `secrets` (nội dung C2 vừa nhập) ở dạng rõ. Giá trị nhị phân (Blob) không đọc được nội dung.
 */
async function quetBoNho(s: Phien, secrets: string[]) {
  for (const page of s.pages()) {
    const dump = await page.evaluate(async () => {
      const out: string[] = [];
      for (const store of [localStorage, sessionStorage]) {
        for (let i = 0; i < store.length; i++) {
          const k = store.key(i)!;
          out.push(k, store.getItem(k) ?? '');
        }
      }
      const req = <T>(r: IDBRequest<T>) =>
        new Promise<T>((ok, ko) => {
          r.onsuccess = () => ok(r.result);
          r.onerror = () => ko(r.error);
        });
      const dbs = indexedDB.databases ? await indexedDB.databases() : [];
      for (const { name } of dbs) {
        if (!name) continue;
        const db = await req(indexedDB.open(name));
        for (const st of Array.from(db.objectStoreNames)) {
          const rows = await req(db.transaction(st, 'readonly').objectStore(st).getAll());
          for (const v of rows) {
            try {
              out.push(typeof v === 'string' ? v : JSON.stringify(v));
            } catch {
              out.push(String(v));
            }
          }
        }
        db.close();
      }
      return out.join('\n');
    });
    for (const secret of secrets) {
      expect(dump.includes(secret), `bộ nhớ trình duyệt chứa dạng rõ: ${secret}`).toBe(false);
    }
  }
}

/** Tên cán bộ ở dòng phụ của phiếu tự đánh giá: "Họ tên · Chức vụ, Đơn vị". */
async function tenCanBo(m: Locator): Promise<string> {
  const sub = (await m.locator('header p.sub').first().innerText()).trim();
  return sub.split(/ · |, /)[0].trim();
}

test.describe('Đánh giá, hồ sơ số, khiếu nại với máy chủ thật', { tag: '@qa-03' }, () => {
  let s: Phien;
  let secrets: string[];

  test.beforeEach(({ browser }) => {
    test.skip(
      THAT && (!PASSWORD || !OTP_SECRET),
      'Thiếu KC_TEST_USER_PASSWORD hoặc KC_TEST_OTP_SECRET',
    );
    test.setTimeout(THAT ? 600_000 : 120_000);
    s = new Phien(browser, Object.keys(TAI_KHOAN) as TenTaiKhoan[]);
    secrets = [];
  });

  test.afterEach(async () => {
    const errors = [...s.errors];
    try {
      if (s.pages().length) await quetBoNho(s, secrets);
    } finally {
      await s.close();
    }
    expect(errors, 'lỗi console trên trang ứng dụng').toEqual([]);
  });

  test('đánh giá quý: tự đánh giá → thẩm định → đề xuất → quyết định có ký số', async () => {
    const stamp = String(Date.now());
    const uuDiem = `Ưu điểm tự đánh giá kiểm thử ${stamp}: hoàn thành đúng hạn các nhiệm vụ được giao`;
    const nhanXet = `Nhận xét thẩm định kiểm thử ${stamp}: hồ sơ đầy đủ minh chứng`;
    const lyDo = `Lý do khác mức kiểm thử ${stamp}: chưa đạt điều kiện nhiệm vụ vượt mục tiêu`;
    const deXuat = `Nhận xét đề xuất kiểm thử ${stamp}`;
    secrets.push(uuDiem, nhanXet, lyDo, deXuat);

    // Cán bộ tự đánh giá, ký số và nộp (SCR-0901): 202, theo dõi yêu cầu ký tới khi hồ sơ đổi trạng thái
    let page = await s.as('canbo', '/appraisal/tu-danh-gia');
    let m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: /^Phiếu tự đánh giá · / })).toBeVisible();
    await expect(m.getByText('Trạng thái: Đang tự đánh giá')).toBeVisible();
    const person = await tenCanBo(m);
    await m.getByRole('textbox', { name: /^Ưu điểm, hạn chế, hướng khắc phục/ }).fill(uuDiem);
    const giaiTrinh = m.getByRole('textbox', { name: 'Giải trình khi khác mức tham khảo' });
    // Giải trình chỉ bắt buộc khi mức tự đề xuất khác mức tham khảo; điền luôn cũng hợp lệ
    if (await giaiTrinh.isEditable()) await giaiTrinh.fill(lyDo);
    const kyNop = m.getByRole('button', { name: 'Ký số và nộp' });
    await expect(kyNop).toBeEnabled();
    await kyNop.click();
    const tuDanhGiaXong = m
      .getByRole('status')
      .filter({ hasText: /Đã nộp phiếu tự đánh giá và ký số thành công/ });
    await xacThucBuocLen(
      page,
      'canbo',
      m.getByRole('status').filter({ hasText: /Đã gửi yêu cầu ký|Đã nộp phiếu tự đánh giá/ }),
    );
    await theoDoiKy(m, /Đã gửi yêu cầu ký|Đang chờ ký/, tuDanhGiaXong);
    await expect(m.getByText('Trạng thái: Chờ thẩm định')).toBeVisible();

    // Người thẩm định mở hồ sơ trong hàng đợi, ghi nhận xét (C2) và ký số chuyển đề xuất (SCR-0902)
    page = await s.as('thamdinh', '/appraisal/tham-dinh');
    m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: /^Thẩm định hồ sơ đánh giá/ }),
    ).toBeVisible();
    await m
      .getByRole('region', { name: 'Hàng đợi thẩm định' })
      .getByRole('link', { name: new RegExp(`^${person} `) })
      .first()
      .click();
    const hoSo = m.getByRole('region', { name: new RegExp(`^${person} · `) });
    await expect(hoSo.getByText('Chờ thẩm định', { exact: false }).first()).toBeVisible();
    const profileId = new URL(page.url()).pathname.split('/').pop()!;
    await m.getByRole('textbox', { name: 'Nhận xét thẩm định *' }).fill(nhanXet);
    const lyDoKhac = m.getByRole('textbox', { name: 'Lý do khác mức tự đề xuất *' });
    if (await lyDoKhac.isVisible()) await lyDoKhac.fill(lyDo);
    await m.getByRole('button', { name: 'Ký số và chuyển đề xuất' }).click();
    const thamDinhXong = m
      .getByRole('status')
      .filter({ hasText: `Đã ký số kết quả thẩm định hồ sơ của đồng chí ${person}` });
    await xacThucBuocLen(
      page,
      'thamdinh',
      m.getByRole('status').filter({ hasText: /Đã gửi yêu cầu ký|Đã ký số kết quả thẩm định/ }),
    );
    await theoDoiKy(m, /Đã gửi yêu cầu ký|Đang chờ ký/, thamDinhXong);

    // Quản lý trực tiếp ghi mức đề xuất xếp loại trên phiếu xếp loại (SCR-0903)
    page = await s.as('quanly', `/appraisal/ket-qua/${profileId}`);
    m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: /^Phiếu xếp loại cá nhân/ }),
    ).toBeVisible();
    await expect(m.locator(`header[data-profile-id="${profileId}"]`)).toHaveAttribute(
      'data-status',
      'DA_THAM_DINH',
    );
    await m.getByRole('textbox', { name: 'Nhận xét (không bắt buộc)' }).fill(deXuat);
    await m.getByRole('button', { name: 'Ghi mức đề xuất' }).click();
    await expect(
      m
        .getByRole('status')
        .filter({ hasText: /^Đã ghi mức đề xuất .+ chuyển tới cấp có thẩm quyền/ }),
    ).toBeVisible();
    await khongMaTho(m);

    // Người có thẩm quyền chọn đúng hồ sơ, quyết định và ký lô (SCR-0904): 202, theo dõi tới khi xong
    page = await s.as('quyetdinh', '/appraisal/quyet-dinh');
    m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: /^Quyết định xếp loại hàng loạt/ }),
    ).toBeVisible();
    const chonHoSo = m.getByRole('checkbox', { name: `Chọn hồ sơ của đồng chí ${person}` });
    const kyLo = m.getByRole('button', { name: /^Quyết định và ký lô/ });
    // Danh sách nạp lại thì lựa chọn trở về mặc định (chọn tất cả), nên chọn lại tới khi giữ được
    await expect(async () => {
      await m.getByRole('checkbox', { name: 'Chọn tất cả hồ sơ trong danh sách' }).uncheck();
      const next = m.getByRole('button', { name: 'Trang sau' });
      while (!(await chonHoSo.isVisible()) && (await next.isEnabled())) await next.click();
      await chonHoSo.check();
      await expect(kyLo).toHaveText(/\(1\)/, { timeout: 1_000 });
      await expect(kyLo).toBeEnabled({ timeout: 1_000 });
    }).toPass({ timeout: 20_000 });
    await m.getByRole('textbox', { name: 'Số quyết định' }).fill(`${stamp.slice(-6)}-QĐ/ĐUK`);
    await kyLo.click();
    const quyetDinhXong = m
      .getByRole('status')
      .filter({ hasText: /^Đã hoàn tất quyết định .+: 1 hồ sơ đã ký số/ });
    await xacThucBuocLen(
      page,
      'quyetdinh',
      m.getByRole('status').filter({ hasText: /Đã gửi yêu cầu ký lô|Đã hoàn tất quyết định/ }),
    );
    await theoDoiKy(m, /Đã gửi yêu cầu ký lô|Đang ký lô|Tiến độ ký lô/, quyetDinhXong);

    // Hồ sơ đã đổi trạng thái sau khi ký (EVT-18): phiếu xếp loại không còn chờ quyết định
    page = await s.as('quyetdinh', `/appraisal/ket-qua/${profileId}`);
    m = main(page);
    await expect(m.locator(`header[data-profile-id="${profileId}"]`)).toHaveAttribute(
      'data-status',
      /^(DA_QUYET_DINH|DA_KY_SO|DA_KHOA)$/,
    );
    await khongMaTho(m);
  });

  test('gói hồ sơ số: sinh biểu mẫu → tải gói → ký số gói và nộp', async () => {
    // Gói do hệ thống lập khi khoá kỳ (QT-11), giao diện không có nút tạo gói: chọn gói đang lập có
    // biểu mẫu lỗi để sinh lại, rồi gói chờ ký đạt đủ điều kiện để tải và ký (ghi đè bằng mã gói)
    const goiLap = process.env.E2E_THAT_GOI_LAP;
    const goiKy = process.env.E2E_THAT_GOI_KY;
    let page = await s.as('thamdinh', '/dossier/goi-ho-so');
    let m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: 'Gói hồ sơ số' })).toBeVisible();
    const rows = m.getByRole('table', { name: 'Danh sách gói hồ sơ số' }).getByRole('row');

    // Sinh lại biểu mẫu lỗi (202 + theo dõi tác vụ sinh, EVT-17)
    await m.getByRole('combobox', { name: 'Trạng thái' }).selectOption({ label: 'Đang lập' });
    const lap = goiLap ? rows.filter({ hasText: goiLap }) : rows.filter({ hasText: 'tệp lỗi' });
    await expect(lap.first()).toBeVisible();
    await lap.first().getByRole('link').click();
    await expect(m.getByRole('heading', { level: 1, name: /^Gói hồ sơ số / })).toBeVisible();
    await expect(m.locator('[data-status]')).toHaveText('Đang lập');
    await m.getByRole('button', { name: 'Sinh lại mẫu lỗi' }).click();
    await theoDoiKy(
      m,
      /Đang sinh biểu mẫu/,
      m.getByRole('status').filter({ hasText: 'Đã sinh lại các biểu mẫu lỗi.' }),
      false,
    );
    await expect(m.locator('[data-status]')).not.toHaveText('Đang lập');
    await expect(
      m.getByRole('heading', { level: 2, name: /^Biểu mẫu sinh từ dữ liệu/ }),
    ).toHaveText(/(\d+)\/\1 tệp xong/);

    // Chọn gói chờ ký đã đạt danh sách kiểm tra
    await spaGoto(page, '/dossier/goi-ho-so');
    await m.getByRole('combobox', { name: 'Trạng thái' }).selectOption({ label: 'Chờ ký' });
    const ky = goiKy ? rows.filter({ hasText: goiKy }) : rows.filter({ hasText: 'Chờ ký' });
    await expect(ky.first()).toBeVisible();
    let url = '';
    const count = await ky.count();
    for (let i = 0; i < count && !url; i++) {
      const link = ky.nth(i).getByRole('link');
      const href = (await link.getAttribute('href'))!;
      await link.click();
      const check = m.getByRole('region', { name: /^Kiểm tra trước khi ký gói/ });
      await expect(check.getByText(/^(Đạt đủ điều kiện ký gói|Còn \d+ mục)/)).toBeVisible();
      if (await check.getByText('Đạt đủ điều kiện ký gói.').isVisible()) url = href;
      else await spaGoto(page, '/dossier/goi-ho-so');
    }
    expect(url, 'không có gói chờ ký nào đạt danh sách kiểm tra').not.toBe('');
    const code = (await m.locator('header strong').first().innerText()).trim();

    // Tải gói ZIP và manifest (202 + theo dõi tác vụ đóng gói)
    const download = page.waitForEvent('download', { timeout: CHO_TAC_VU });
    await m.getByRole('button', { name: 'Tải gói (ZIP và manifest)' }).click();
    await theoDoiKy(
      m,
      /Đang đóng gói tệp/,
      m.getByRole('status').filter({ hasText: `Đã tải gói ${code} kèm manifest.` }),
      false,
    );
    expect((await download).suggestedFilename()).toBe(`${code}.zip`);

    // Người có thẩm quyền ký số gói và nộp cấp trên (202 + theo dõi yêu cầu ký, EVT-18)
    page = await s.as('quyetdinh', url);
    m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: /^Gói hồ sơ số / })).toBeVisible();
    await expect(m.locator('[data-status]')).toHaveText('Chờ ký');
    await m.getByRole('button', { name: 'Ký số gói và nộp' }).click();
    const daNop = m.getByRole('status').filter({ hasText: `Gói ${code} đã được ký số và nộp lúc` });
    await xacThucBuocLen(
      page,
      'quyetdinh',
      m
        .getByRole('status')
        .filter({ hasText: /Đang chờ đồng chí xác nhận ký|đã được ký số và nộp/ }),
    );
    await theoDoiKy(m, /Đang chờ đồng chí xác nhận ký/, daNop);
    await expect(m.locator('[data-status]')).toHaveText(/^(Đã ký|Đã nộp)$/);
    await expect(m.getByText(/^Gói đã ký và nộp nên bị khoá/)).toBeVisible();
    await khongMaTho(m);
  });

  test('khiếu nại: gửi khiếu nại → tiếp nhận, kết luận → ký văn bản trả lời', async () => {
    const stamp = String(Date.now());
    const lyDo =
      `Lý do khiếu nại kiểm thử ${stamp}: sản phẩm của nhiệm vụ đã được nghiệm thu đạt yêu cầu ` +
      'nhưng mức chất lượng ghi thấp hơn biên bản nghiệm thu.';
    const deNghi = `Đề nghị kiểm thử ${stamp}: xem xét lại mức chất lượng`;
    const ghiChu = `Ghi chú kết luận kiểm thử ${stamp}: hồ sơ đủ căn cứ, không cần giải trình`;
    const traLoi = `Văn bản trả lời kiểm thử ${stamp}: giữ nguyên kết quả đánh giá`;
    secrets.push(lyDo, deNghi, ghiChu, traLoi);

    // Cán bộ soạn, ký số và gửi khiếu nại về kết quả đã công bố (SCR-1201)
    let page = await s.as('canbo', '/oversight/my-complaints');
    let m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Khiếu nại, giải trình của tôi' }),
    ).toBeVisible();
    await m
      .getByRole('region', { name: 'Kết quả có thể khiếu nại' })
      .getByRole('link', { name: 'Soạn khiếu nại' })
      .first()
      .click();
    await expect(
      m.getByRole('heading', { level: 1, name: 'Gửi khiếu nại kết quả đánh giá' }),
    ).toBeVisible();
    await expect(m.getByRole('button', { name: 'Khiếu nại', exact: true })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await m
      .getByRole('list', { name: /^Cấu phần bị khiếu nại/ })
      .getByRole('button')
      .first()
      .click();
    await m.getByRole('textbox', { name: /^Lý do, căn cứ/ }).fill(lyDo);
    await m.getByRole('textbox', { name: /^Đề nghị cụ thể/ }).fill(deNghi);
    await m.getByRole('button', { name: 'Ký số và gửi' }).click();
    const daGui = m.getByRole('status').filter({ hasText: /^Đã gửi khiếu nại KN-/ });
    await xacThucBuocLen(page, 'canbo', daGui);
    const code = /KN-\d+-\d+/.exec(await daGui.innerText())![0];
    await expect(
      m.getByRole('heading', { level: 1, name: new RegExp(`^Khiếu nại ${code}`) }),
    ).toBeVisible();
    await khongMaTho(m);

    // Người xử lý tìm đúng khiếu nại vừa gửi theo mã, tiếp nhận và ghi kết luận (SCR-1202)
    page = await s.as('thamdinh', '/oversight/complaints');
    m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: 'Xử lý khiếu nại' })).toBeVisible();
    const list = m.getByRole('region', { name: 'Khiếu nại', exact: true });
    await list.getByRole('button', { name: 'Trong phạm vi' }).click();
    await list.getByRole('link', { name: new RegExp(`^${code} · `) }).click();
    const detail = m.getByRole('region', { name: new RegExp(`^${code} `) });
    await expect(detail.getByText(lyDo)).toBeVisible();
    await detail.getByRole('button', { name: 'Tiếp nhận' }).click();
    await expect(
      m.getByRole('status').filter({ hasText: `Đã tiếp nhận khiếu nại ${code}.` }),
    ).toBeVisible();
    const ketLuan = m.getByRole('region', { name: 'Kết luận', exact: true });
    // Ô "Kết quả xử lý" (p-select) mang tên theo chữ gợi ý, nên lấy ô chọn đầu tiên của vùng
    const outcome = ketLuan.getByRole('combobox').first();
    const options = page.getByRole('listbox');
    await expect(async () => {
      if (!(await options.isVisible())) await outcome.click();
      await expect(options).toBeVisible({ timeout: 1_000 });
    }).toPass();
    await options.getByRole('option', { name: 'Giữ nguyên kết quả' }).click();
    const note = ketLuan.getByRole('textbox', { name: /^Ghi chú kết luận không qua giải trình/ });
    if (await note.isVisible()) await note.fill(ghiChu);
    await ketLuan.getByRole('textbox', { name: 'Nội dung văn bản trả lời' }).fill(traLoi);
    const soVanBan = `${stamp.slice(-6)}-CV/BTC`;
    await ketLuan.getByRole('textbox', { name: 'Số văn bản trả lời' }).fill(soVanBan);
    await ketLuan.getByRole('button', { name: 'Ghi kết luận' }).click();
    await expect(
      m.getByRole('status').filter({ hasText: `Đã ghi kết luận cho khiếu nại ${code}` }),
    ).toBeVisible();
    await khongMaTho(m);

    // Người có thẩm quyền ký số văn bản trả lời (SCR-1202 `ky-tra-loi`)
    page = await s.as('quyetdinh', '/oversight/complaints');
    m = main(page);
    const list2 = m.getByRole('region', { name: 'Khiếu nại', exact: true });
    await list2.getByRole('button', { name: 'Trong phạm vi' }).click();
    await list2.getByRole('link', { name: new RegExp(`^${code} · `) }).click();
    const ketLuan2 = m.getByRole('region', { name: 'Kết luận', exact: true });
    await ketLuan2.getByRole('button', { name: 'Ký số và trả lời' }).click();
    await xacThucBuocLen(
      page,
      'quyetdinh',
      m
        .getByRole('status')
        .filter({ hasText: `Đã ký số văn bản trả lời ${soVanBan} và gửi người khiếu nại.` }),
    );
    await expect(
      m.getByRole('heading', { level: 2, name: new RegExp(`^${code} .*Đã trả lời`) }),
    ).toBeVisible();
    await khongMaTho(m);

    // Người khiếu nại thấy khiếu nại đã được trả lời
    page = await s.as('canbo', '/oversight/my-complaints');
    m = main(page);
    await expect(
      m.getByRole('table', { name: 'Khiếu nại của tôi' }).getByRole('row', { name: code }),
    ).toContainText('Đã trả lời');
  });
});
