import { Browser, BrowserContext, Locator, Page, expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { OTP_SECRET, PASSWORD, completeKeycloak, completePopup } from './dang-nhap-keycloak';

/**
 * Ba luồng quản trị, cấu hình chạy với máy chủ thật trên môi trường QA-03 (WEB-072), cùng khuôn với
 * `that.luong-chinh.spec.ts` (WEB-070) và `that.danh-gia-ho-so.spec.ts` (WEB-071):
 * 1. phát hành UI Pack rồi thấy token mới (fe-config-studio; QT-18 → SCR-1801 → `/api/v1/ui/**`):
 *    người soạn đổi màu chủ đạo, lưu, gửi duyệt (202 + kiểm tra tự động); người khác duyệt và phát
 *    hành có xác thực nhiều lớp (202, job `RENDER`); người soạn không tự duyệt được; giao diện không
 *    đổi giữa lúc đang soạn, chỉ đổi ở lần tải trang kế tiếp sau EVT-23;
 * 2. phát hành Policy Pack qua kiểm thử vàng (fe-config-studio; QT-15 → SCR-1501, SCR-1503 →
 *    `/api/v1/policies/**`): sửa tham số bản nháp, chạy bộ ca vàng (202 + theo dõi), thấy kết quả đạt,
 *    gửi phê duyệt; người khác ký phê duyệt có xác thực nhiều lớp; người soạn không tự duyệt được;
 * 3. tạo kỳ và lệnh khoá kỳ (fe-admin; QT-08 → SCR-0801 → `/api/v1/periods/**`): tạo kỳ thử, lập lệnh,
 *    người lập không tự duyệt được, lãnh đạo phê duyệt có xác thực nhiều lớp, quản trị khởi chạy (202)
 *    và theo dõi tiến trình khoá kỳ (saga) trên màn hình tiến độ.
 *
 * Chạy khi chọn rõ: `E2E_MODE=that` (nhãn `@qa-03`, đăng nhập Keycloak bằng tài khoản thử của realm
 * dev; mật khẩu, khoá OTP đọc từ `KC_TEST_USER_PASSWORD`, `KC_TEST_OTP_SECRET`), hoặc
 * `E2E_MODE=gia-lap E2E_THAT_GIA_LAP=1` để chạy cùng các bước trên dữ liệu giả lập (một trang mang hợp
 * các vai trò của luồng qua sessionStorage `vt.devRoles`, chuyển tuyến trong SPA).
 *
 * Giả lập khác máy chủ thật ở ba chỗ (ghi chú `gia-lap` trong kết quả): mọi bước dùng chung một người
 * dùng giả lập nên máy chủ giả lập từ chối "người khác duyệt" với bản do chính trang tạo ra; vì vậy
 * bước duyệt chạy trên bản mẫu do người khác soạn, còn phép thử "người soạn không tự duyệt" chạy trên
 * bản trang vừa tạo. UI Pack của shell ở giả lập là tệp tĩnh `/ui-packs` nên không kiểm được token mới
 * sau phát hành; chỉ kiểm giao diện không đổi trong lúc soạn. API giả lập chạy trong trang nên không
 * thấy được phản hồi 202 trên mạng; chế độ `that` kiểm 202 kèm `Location`.
 *
 * Mỗi luồng kiểm không có lỗi console và màn hình không hiện mã trạng thái thô trong lúc chờ.
 */
const THAT = process.env.E2E_MODE === 'that';
/** Chờ tác vụ dài (kiểm tra tự động, dựng UI Pack, kiểm thử vàng, saga phát hành). */
const CHO_TAC_VU = THAT ? 180_000 : 15_000;
/** Chờ saga khoá kỳ thử hoàn tất trên máy chủ thật (kỳ thử không có hồ sơ nên nhanh). */
const CHO_KHOA_KY = Number(process.env.E2E_THAT_CHO_KHOA_KY ?? 300_000);
/** Chờ shell nhận UI Pack mới (CDN `current.json` max-age=60, bản lưu IndexedDB đổi ở lần sau). */
const CHO_TOKEN_MOI = Number(process.env.E2E_THAT_CHO_TOKEN ?? 240_000);

const REALM = JSON.parse(
  readFileSync(join(__dirname, '..', '..', 'infra/dev/keycloak/realm-mission.json'), 'utf8'),
) as { users: { username: string; realmRoles: string[] }[] };

/**
 * Tài khoản thử theo vai trò cần cho từng bước (docs/phan-quyen/ma-tran.json, wiki/15a). Tên đăng nhập
 * ghi đè được bằng `E2E_THAT_TK_<KHOA>` (ví dụ `E2E_THAT_TK_QUANTRI2=quantri-b`).
 */
const TAI_KHOAN = {
  /** AC-02 tenant_admin: soạn, gửi duyệt UI Pack (SCR-1801), tạo kỳ, lập lệnh, khởi chạy (SCR-0801). */
  quantri: ['tenant_admin'],
  /**
   * AC-02 tenant_admin thứ hai: duyệt và phát hành UI Pack (SCR-1801 `duyet`, khác người soạn). Realm
   * dev chưa có tài khoản này: chế độ `that` bỏ qua luồng UI Pack nếu không ghi đè
   * `E2E_THAT_TK_QUANTRI2` bằng một tài khoản tenant_admin khác `quantri`.
   */
  quantri2: ['tenant_admin'],
  /** AC-03 policy_author: soạn công thức, chạy test case vàng, gửi phê duyệt (SCR-1501, SCR-1503). */
  chinhsach: ['policy_author'],
  /** AC-08 decider: ký số phê duyệt phát hành Policy Pack (SCR-1503 `ky-duyet`). */
  quyetdinh: ['decider'],
  /** AC-04 leader: phê duyệt lệnh khoá kỳ (SCR-0801 `duyet-lenh`, khác người lập). */
  lanhdao: ['member', 'leader'],
} as const;
type TenTaiKhoan = keyof typeof TAI_KHOAN;

const tenDangNhap = (user: TenTaiKhoan) => process.env[`E2E_THAT_TK_${user.toUpperCase()}`] || user;

/** Tài khoản cần cho luồng còn thiếu trong realm dev mà không được ghi đè (chỉ chế độ `that`). */
function thieuTaiKhoan(users: TenTaiKhoan[]): string[] {
  return users
    .filter((u) => tenDangNhap(u) === u && !REALM.users.some((x) => x.username === u))
    .map((u) => `${u} (${TAI_KHOAN[u].join(', ')}; ghi đè bằng E2E_THAT_TK_${u.toUpperCase()})`);
}

function vaiTro(user: TenTaiKhoan): string[] {
  const name = tenDangNhap(user);
  const u = REALM.users.find((x) => x.username === name);
  if (!u) {
    test.info().annotations.push({
      type: 'canh-bao',
      description:
        name !== user
          ? `Tài khoản ${name} (ghi đè ${user}) không có trong realm dev, không kiểm được vai trò`
          : `Realm dev chưa có tài khoản ${user}; giả lập dùng vai trò khai báo ${TAI_KHOAN[user].join(', ')}`,
    });
    return [...TAI_KHOAN[user]];
  }
  expect(u.realmRoles, `vai trò của ${name}`).toEqual(expect.arrayContaining([...TAI_KHOAN[user]]));
  return u.realmRoles;
}

/** Ghi chú khác biệt giữa giả lập và máy chủ thật vào kết quả kiểm thử. */
const ghiChu = (description: string) =>
  test.info().annotations.push({ type: 'gia-lap', description });

/**
 * Phiên chạy một luồng (như WEB-070, WEB-071). Chế độ `that`: mỗi tài khoản một ngữ cảnh trình duyệt,
 * đăng nhập Keycloak ở lần đầu. Chế độ giả lập: một trang duy nhất mang hợp các vai trò, chuyển tuyến
 * trong SPA để kho giả lập (giữ trong bộ nhớ trang) không mất dữ liệu vừa tạo.
 */
class Phien {
  private readonly contexts = new Map<string, { ctx: BrowserContext; page: Page }>();
  private readonly extra: BrowserContext[] = [];
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

  private async giaLap(roles: string[], url: string): Promise<Page> {
    const ctx = await this.browser.newContext();
    await ctx.addInitScript((r) => sessionStorage.setItem('vt.devRoles', r), roles.join(','));
    const page = await ctx.newPage();
    this.watch(page);
    await page.goto(url);
    return page;
  }

  /** Trang của `user` đang mở `url`; đăng nhập (chế độ `that`) nếu là lần đầu. */
  async as(user: TenTaiKhoan, url: string): Promise<Page> {
    if (!THAT) {
      if (!this.shared) {
        this.shared = await this.giaLap([...new Set(this.users.flatMap(vaiTro))], url);
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

  /**
   * Trang chỉ mang vai trò của `user`. Chế độ `that` là trang của tài khoản đó; giả lập mở một ngữ
   * cảnh riêng chỉ có vai trò của `user` (dữ liệu giả lập của ngữ cảnh này là dữ liệu mẫu ban đầu).
   */
  async rieng(user: TenTaiKhoan, url: string): Promise<Page> {
    if (THAT) return this.as(user, url);
    const page = await this.giaLap(vaiTro(user), url);
    this.extra.push(page.context());
    return page;
  }

  async close() {
    for (const { ctx } of this.contexts.values()) await ctx.close();
    for (const ctx of this.extra) await ctx.close();
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
const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const duongDan = (page: Page) => new URL(page.url()).pathname;

/** Chuỗi trông như mã trạng thái thô (`DA_PHAT_HANH`, `CHO_DUYET`…), không được hiện lên màn hình. */
const MA_THO = /\b[A-Z]{2,}(?:_[A-Z0-9]+)+\b/;

async function khongMaTho(m: Locator) {
  expect(await m.innerText(), 'mã trạng thái thô trên màn hình').not.toMatch(MA_THO);
}

/**
 * Hộp "Xác thực nhiều lớp" (step-up RFC 9470): bấm Xác thực; chế độ `that` hoàn tất mật khẩu, OTP
 * trong cửa sổ Keycloak. Phiên đã đạt `aal2` trong 15 phút thì hộp không hiện.
 */
async function xacThucBuocLen(page: Page, user: TenTaiKhoan, xong: Locator) {
  const dialog = page.getByRole('dialog', { name: 'Xác thực nhiều lớp' });
  await expect(dialog.or(xong).first()).toBeVisible({ timeout: CHO_TAC_VU });
  if (await dialog.isVisible()) {
    const popup = THAT ? page.waitForEvent('popup') : null;
    await dialog.getByRole('button', { name: 'Xác thực' }).click();
    if (popup) await completePopup(await popup, tenDangNhap(user));
  }
  await expect(xong).toBeVisible({ timeout: CHO_TAC_VU });
}

/**
 * Theo dõi tác vụ dài (202 + đọc lại trạng thái) tới khi `xong` hiện: mỗi lần nhìn màn hình đều không
 * có mã trạng thái thô; trả các nhãn chờ `cho` đã thấy.
 */
async function theoDoi(m: Locator, cho: RegExp, xong: Locator, timeout = CHO_TAC_VU) {
  const seen = new Set<string>();
  await expect(async () => {
    const text = await m.innerText();
    expect(text, 'mã trạng thái thô khi chờ tác vụ').not.toMatch(MA_THO);
    const hit = cho.exec(text);
    if (hit) seen.add(hit[0]);
    await expect(xong).toBeVisible({ timeout: 500 });
  }).toPass({ timeout, intervals: [250] });
  await khongMaTho(m);
  return [...seen];
}

/**
 * Bấm `act` và (chế độ `that`) kiểm máy chủ nhận tác vụ dài: POST khớp `url` trả 202 kèm `Location`
 * (wiki/06). Giả lập trả 202 trong trang, không đi qua mạng nên không kiểm được.
 */
async function nhan202(page: Page, url: RegExp, act: () => Promise<void>) {
  const res = THAT
    ? page.waitForResponse((r) => r.request().method() === 'POST' && url.test(r.url()), {
        timeout: CHO_TAC_VU,
      })
    : null;
  await act();
  if (!res) return;
  const r = await res;
  expect(r.status(), `${r.url()} trả tác vụ dài`).toBe(202);
  expect(await r.headerValue('location'), 'tác vụ dài có Location').toBeTruthy();
}

/** Giá trị biến CSS của token trên `document.documentElement` (viết hoa để so màu hex). */
async function bienCss(page: Page, name: string): Promise<string> {
  return page.evaluate(
    (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim().toUpperCase(),
    name,
  );
}

test.describe('Quản trị, cấu hình với máy chủ thật', { tag: '@qa-03' }, () => {
  let s: Phien | undefined;

  test.beforeEach(() => {
    s = undefined;
    test.skip(
      THAT && (!PASSWORD || !OTP_SECRET),
      'Thiếu KC_TEST_USER_PASSWORD hoặc KC_TEST_OTP_SECRET',
    );
    test.setTimeout(THAT ? 900_000 : 120_000);
  });

  test.afterEach(async () => {
    const errors = [...(s?.errors ?? [])];
    await s?.close();
    s = undefined;
    expect(errors, 'lỗi console trên trang ứng dụng').toEqual([]);
  });

  test('UI Pack: soạn token → gửi duyệt → người khác phát hành → thấy token mới', async ({
    browser,
  }) => {
    const users: TenTaiKhoan[] = ['quantri', 'quantri2'];
    const thieu = THAT ? thieuTaiKhoan(users) : [];
    test.skip(thieu.length > 0, `Realm dev thiếu tài khoản: ${thieu.join('; ')}`);
    s = new Phien(browser, users);
    const TOKEN = '--vt-color-primary';
    const url = '/config-studio/giao-dien';

    // Người soạn mở Cấu hình giao diện (SCR-1801); chưa có bản nháp thì tạo từ bản đang dùng
    let page = await s.as('quantri', url);
    let m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: 'Giao diện đơn vị' })).toBeVisible();
    const dangApDung = await bienCss(page, TOKEN);
    const chip = m.getByTestId('pack-bar').locator('.chip[data-status]');
    const taoNhap = m.getByRole('button', { name: 'Tạo bản nháp từ bản đang dùng' });
    await expect(chip.or(taoNhap).first()).toBeVisible();
    // Danh sách phiên bản tải sau gói: nút tạo bản nháp có thể hiện thoáng qua trước khi bản nháp có
    // sẵn hiện ra, nên chờ thêm một nhịp trước khi tạo
    if (await taoNhap.isVisible()) await chip.waitFor({ timeout: 3_000 }).catch(() => undefined);
    if (await taoNhap.isVisible()) {
      await taoNhap.click();
      await expect(
        m.getByRole('status').filter({ hasText: /^Đã tạo bản nháp .+ từ bản đang dùng\.$/ }),
      ).toBeVisible();
    }
    await expect(chip, 'phải có bản nháp để soạn').toHaveText(/ · Bản nháp\s*$/);

    // Đổi màu chủ đạo sang một màu đạt tương phản AA (BR-18-01), khác màu hiện tại
    const mau = m.getByRole('textbox', { name: 'Màu chủ đạo', exact: true });
    const cu = (await mau.inputValue()).toUpperCase();
    let moi = '';
    for (const c of ['#1D4ED8', '#0F766E', '#7E22CE', '#9A3412', '#1E3A8A']) {
      if (c === cu) continue;
      await mau.fill(c);
      await mau.press('Tab');
      await expect(m.getByTestId('contrast').locator('li').first()).toBeVisible();
      if ((await m.getByTestId('contrast').locator('li.bad').count()) === 0) {
        moi = c;
        break;
      }
    }
    expect(moi, 'không có màu nào đạt tương phản với màu chữ trên nền chủ đạo').not.toBe('');
    // Đang soạn: shell giữ nguyên giao diện đang áp dụng (chỉ đổi ở lần tải trang kế tiếp)
    expect(await bienCss(page, TOKEN), 'giao diện đổi trong lúc đang soạn').toBe(dangApDung);

    await m.getByRole('button', { name: 'Lưu nháp' }).click();
    await expect(m.getByRole('status').filter({ hasText: /^Đã lưu (bản )?nháp\.$/ })).toBeVisible();
    await expect(mau).toHaveValue(new RegExp(`^${moi}$`, 'i'));
    const banSoan = /^\s*Bản (\S+) · /.exec(await chip.innerText())![1];

    // Gửi duyệt: 202, theo dõi kiểm tra tự động (lược đồ, tương phản, 30 KB) tới khi chờ duyệt
    const guiDuyet = m.getByRole('button', { name: `Gửi duyệt bản ${banSoan}` });
    await expect(guiDuyet).toBeEnabled();
    await nhan202(page, /\/api\/v1\/ui\/packs\/[^/]+\/versions\/[^/]+\/submit$/, () =>
      guiDuyet.click(),
    );
    await theoDoi(
      m,
      /Đang kiểm tra tự động bản [^…]+…/,
      m.getByRole('status').filter({
        hasText: `Bản ${banSoan} đạt kiểm tra tự động và đã được gửi duyệt.`,
      }),
    );
    await expect(chip).toHaveText(`Bản ${banSoan} · Chờ duyệt`);
    expect(await bienCss(page, TOKEN), 'giao diện đổi trước khi phát hành').toBe(dangApDung);

    /** Người soạn bấm duyệt bản của chính mình: nút không có, hoặc máy chủ từ chối (bốn mắt). */
    const khongTuDuyet = async (p: Page) => {
      const nut = main(p).getByRole('button', { name: 'Duyệt và phát hành' });
      await expect(main(p).getByTestId('pack-bar')).toBeVisible();
      if (!(await nut.isVisible())) return;
      await nut.click();
      await xacThucBuocLen(
        p,
        'quantri',
        main(p)
          .getByRole('alert')
          .filter({ hasText: 'Người duyệt phải khác người soạn phiên bản này.' }),
      );
      await expect(main(p).getByTestId('pack-bar').locator('.chip[data-status]')).toHaveText(
        / · Chờ duyệt\s*$/,
      );
    };
    if (THAT) await khongTuDuyet(page);

    // Người duyệt (tenant_admin khác người soạn) duyệt và phát hành: AAL2, 202 job RENDER, theo dõi
    page = await s.as('quantri2', url);
    m = main(page);
    await expect(m.getByTestId('pack-bar').locator('.chip[data-status]')).toHaveText(
      `Bản ${banSoan} · Chờ duyệt`,
    );
    const phatHanh = m
      .getByRole('status')
      .filter({ hasText: `Giao diện bản ${banSoan} đã phát hành.` });
    await nhan202(page, /\/api\/v1\/ui\/packs\/[^/]+\/versions\/[^/]+\/publish$/, () =>
      m.getByRole('button', { name: 'Duyệt và phát hành' }).click(),
    );
    await xacThucBuocLen(page, 'quantri2', phatHanh.or(m.getByRole('alert')));
    await theoDoi(m, /Đang kiểm tra tự động[^…]*…/, phatHanh);
    await expect(phatHanh).toContainText('ở lần tải trang kế tiếp');
    // Vừa phát hành, chưa điều hướng: trang đang mở vẫn giữ giao diện cũ
    expect(await bienCss(page, TOKEN), 'giao diện đổi khi chưa tải lại trang').toBe(dangApDung);
    // Thẻ "đơn vị đang dùng" của trang chỉ cập nhật khi mở lại màn hình (gói không được đọc lại sau
    // khi phát hành), nên chuyển sang phần khác rồi quay lại trong SPA
    await spaGoto(page, '/config-studio');
    await expect(m.getByRole('heading', { level: 1, name: /^Chính sách đánh giá/ })).toBeVisible();
    await spaGoto(page, url);
    await expect(
      m
        .getByText(`Đang áp dụng: bản ${banSoan}`)
        .or(m.getByText(`Đơn vị đang dùng giao diện bản ${banSoan}.`)),
    ).toBeVisible();

    if (THAT) {
      // Lần tải trang kế tiếp (sau EVT-23): shell đọc `current.json` mới, lưu gói vào IndexedDB và
      // áp ở lần điều hướng sau đó nếu đã có bản lưu cũ, nên tải lại tới khi token đổi
      const soan = await s.as('quantri', '/');
      await expect(async () => {
        await soan.reload();
        await expect(soan.getByRole('navigation', { name: 'Menu chính' })).toBeVisible();
        expect(await bienCss(soan, TOKEN)).toBe(moi);
      }).toPass({ timeout: CHO_TOKEN_MOI, intervals: [5_000, 10_000, 15_000] });
    } else {
      ghiChu(
        'UI Pack của shell ở giả lập là tệp tĩnh /ui-packs nên không kiểm token mới sau phát hành; ' +
          'bước duyệt chạy trên bản nháp mẫu do người khác soạn.',
      );
      // Bốn mắt trên giả lập: bản do chính người dùng giả lập tạo thì máy chủ giả lập từ chối duyệt
      await m.getByRole('button', { name: 'Tạo bản nháp từ bản đang dùng' }).click();
      await expect(chip).toHaveText(/ · Bản nháp\s*$/);
      const banMoi = /^\s*Bản (\S+) · /.exec(await chip.innerText())![1];
      await m.getByRole('button', { name: `Gửi duyệt bản ${banMoi}` }).click();
      await theoDoi(
        m,
        /Đang kiểm tra tự động[^…]*…/,
        m.getByRole('status').filter({ hasText: `Bản ${banMoi} đạt kiểm tra tự động` }),
      );
      await khongTuDuyet(page);
    }
    await khongMaTho(m);
  });

  test('Policy Pack: sửa tham số → kiểm thử vàng → gửi phê duyệt → người khác ký phát hành', async ({
    browser,
  }) => {
    const users: TenTaiKhoan[] = ['chinhsach', 'quyetdinh'];
    const thieu = THAT ? thieuTaiKhoan(users) : [];
    test.skip(thieu.length > 0, `Realm dev thiếu tài khoản: ${thieu.join('; ')}`);
    s = new Phien(browser, users);
    const goi = process.env.E2E_THAT_GOI_CHINH_SACH;

    // Người soạn mở danh sách chính sách đánh giá, chọn phiên bản nháp đầu tiên (hoặc của gói ghi đè)
    let page = await s.as('chinhsach', '/config-studio');
    let m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: /^Chính sách đánh giá/ })).toBeVisible();
    const vung = goi ? m.locator(`section[data-pack="${goi}"]`) : m;
    const moSoan = vung.getByRole('link', { name: /^Mở để soạn/ }).first();
    await expect(moSoan, 'cần một phiên bản nháp để soạn').toBeVisible();
    await moSoan.click();
    await expect(page).toHaveURL(/\/config-studio\/goi\/[^/]+\/[^/]+\/cong-thuc$/);
    const goc = duongDan(page).replace(/\/cong-thuc$/, '');
    const chip = m.getByTestId('pack-bar').locator('.chip[data-status]');
    await expect(chip).toHaveText(/ · (Bản nháp|Đạt kiểm thử)\s*$/);

    // Sửa tham số "Mức trừ cho mỗi ngày chậm" (SCR-1501) và lưu nháp. Mặc định đổi rồi trả về giá
    // trị cũ (hai lần lưu) để bộ ca vàng đã ký vẫn đúng; `E2E_THAT_TRU_TIEN_DO` giữ giá trị mới
    const truTienDo = m.getByRole('spinbutton', { name: 'Mức trừ cho mỗi ngày chậm' });
    await expect(truTienDo).toBeEditable();
    const cu = await truTienDo.inputValue();
    const giuMoi = process.env.E2E_THAT_TRU_TIEN_DO;
    const moi = giuMoi ?? (Number(cu) === 0.05 ? '0.04' : '0.05');
    const luu = m.getByRole('button', { name: 'Lưu nháp' });
    const daLuu = m
      .getByRole('status')
      .filter({ hasText: /^(Đã lưu nháp\.|Phiên bản đã thay đổi sau lần kiểm thử gần nhất)/ });
    for (const v of giuMoi ? [moi] : [moi, cu]) {
      await truTienDo.fill(v);
      await expect(luu).toBeEnabled();
      await luu.click();
      await expect(daLuu).toBeVisible();
      await expect(luu).toBeDisabled();
    }
    await expect(chip).toHaveText(/ · Bản nháp\s*$/);

    // Chạy bộ ca vàng: 202, theo dõi lần chạy tới khi có kết quả, phải đạt toàn bộ (BR-15-07)
    const lanChay = m.getByTestId('last-run');
    await nhan202(page, /\/api\/v1\/policies\/packs\/[^/]+\/versions\/[^/]+\/test-runs$/, () =>
      m.getByRole('button', { name: 'Chạy kiểm thử' }).click(),
    );
    await theoDoi(
      m,
      /Đang chạy kiểm thử…|Đang kiểm thử/,
      lanChay.filter({ hasText: /Kiểm thử gần nhất:/ }),
    );
    await expect(lanChay).toHaveText(/Kiểm thử gần nhất:\s*Đạt (\d+)\/\1 ca/);
    await expect(chip).toHaveText(/ · Đạt kiểm thử\s*$/);

    // Thẻ Test case: kết quả từng ca đều đạt, hai nơi tính cùng kết quả
    await m
      .getByRole('navigation', { name: 'Nội dung phiên bản' })
      .getByRole('link', {
        name: 'Test case',
      })
      .click();
    await expect(page).toHaveURL(new RegExp(`${esc(goc)}/test-case$`));
    await expect(m.getByTestId('card-run')).toContainText(/(\d+)\/\1/);
    await expect(m.getByTestId('card-engine')).toContainText('Hai nơi tính cho cùng kết quả');
    const ca = m.getByTestId('cases').locator('tbody tr[data-case]');
    await expect(ca.first()).toBeVisible();
    await expect(ca.filter({ hasText: 'Không đạt' })).toHaveCount(0);
    await khongMaTho(m);

    // Gửi phê duyệt phát hành: chọn kỳ hiệu lực sắp tới (BR-15-10)
    await m.getByRole('button', { name: 'Gửi phê duyệt phát hành' }).click();
    const gui = page.getByRole('dialog', { name: 'Gửi phê duyệt phát hành' });
    const ky = gui.getByRole('combobox', { name: 'Kỳ đánh giá bắt đầu áp dụng' });
    await expect(ky.locator('option').nth(1), 'không có kỳ sắp tới để áp dụng').toBeAttached();
    await ky.selectOption({ index: 1 });
    await gui
      .getByRole('textbox', { name: 'Ghi chú gửi người phê duyệt' })
      .fill(`Kiểm thử đầu cuối ${Date.now()}: đổi mức trừ tiến độ`);
    await gui.getByRole('button', { name: 'Gửi phê duyệt', exact: true }).click();
    await expect(
      m.getByRole('status').filter({
        hasText: 'Đã gửi phê duyệt phát hành. Phiên bản chờ cấp có thẩm quyền phê duyệt.',
      }),
    ).toBeVisible();
    await expect(chip).toHaveText(/ · Chờ phê duyệt\s*$/);

    // Bốn mắt (BR-15-09): người soạn không thấy nút ký phê duyệt bản đang chờ duyệt
    if (THAT) {
      page = await s.as('chinhsach', `${goc}/test-case`);
    } else {
      ghiChu(
        'Máy chủ giả lập không kiểm người ký khác người soạn Policy Pack; kiểm người soạn không ' +
          'có nút ký trên bản chờ phê duyệt mẫu, ở ngữ cảnh chỉ có vai trò người soạn.',
      );
      page = await s.rieng('chinhsach', '/config-studio');
      await main(page)
        .getByRole('row')
        .filter({ has: page.locator('[data-status="CHO_PHE_DUYET"]') })
        .getByRole('link', { name: /^Xem/ })
        .first()
        .click();
      await main(page)
        .getByRole('navigation', { name: 'Nội dung phiên bản' })
        .getByRole('link', { name: 'Test case' })
        .click();
    }
    await expect(main(page).getByTestId('pack-bar').locator('.chip[data-status]')).toHaveText(
      / · Chờ phê duyệt\s*$/,
    );
    await expect(main(page).getByTestId('card-run')).toBeVisible();
    await expect(main(page).getByRole('button', { name: 'Ký số phê duyệt' })).toHaveCount(0);

    // Người có thẩm quyền xem kết quả kiểm thử, ký số phê duyệt có step-up; saga phát hành 202
    page = await s.as('quyetdinh', `${goc}/test-case`);
    m = main(page);
    // Giả lập mang cả vai trò người soạn nên trang hiện gợi ý của người soạn
    if (THAT) {
      await expect(
        m.getByText('Đồng chí xem kết quả kiểm thử trước khi ký phê duyệt hoặc từ chối phát hành.'),
      ).toBeVisible();
    }
    await expect(m.getByTestId('card-run')).toContainText(/(\d+)\/\1/);
    await m.getByRole('button', { name: 'Ký số phê duyệt' }).click();
    const duyet = page.getByRole('dialog', { name: 'Ký số phê duyệt phát hành' });
    await duyet
      .getByRole('textbox', { name: 'Ý kiến phê duyệt' })
      .fill('Đồng ý phát hành theo kết quả kiểm thử vàng.');
    const daPhatHanh = m
      .getByRole('status')
      .filter({ hasText: /^Phiên bản .+ đã được phát hành, áp dụng từ kỳ / });
    await nhan202(page, /\/api\/v1\/policies\/packs\/[^/]+\/versions\/[^/]+\/approve$/, () =>
      duyet.getByRole('button', { name: 'Ký số phê duyệt' }).click(),
    );
    await xacThucBuocLen(
      page,
      'quyetdinh',
      m
        .getByRole('status')
        .filter({ hasText: /Đang phát hành phiên bản|đã được phát hành/ })
        .first(),
    );
    await theoDoi(m, /Đang phát hành phiên bản/, daPhatHanh);
    await expect(m.getByTestId('pack-bar').locator('.chip[data-status]')).toHaveText(
      / · Đã phát hành\s*$/,
    );
    await khongMaTho(m);
  });

  test('Kỳ đánh giá: tạo kỳ thử → lập lệnh khoá kỳ → người khác duyệt → theo dõi khoá kỳ', async ({
    browser,
  }) => {
    const users: TenTaiKhoan[] = ['quantri', 'lanhdao'];
    const thieu = THAT ? thieuTaiKhoan(users) : [];
    test.skip(thieu.length > 0, `Realm dev thiếu tài khoản: ${thieu.join('; ')}`);
    s = new Phien(browser, users);
    const stamp = String(Date.now());
    // Kỳ thử là một quý đã qua (lệnh khoá kỳ chỉ lập được khi kỳ đã bắt đầu), chọn ngẫu nhiên để
    // không trùng thời gian với kỳ thử của lần chạy trước (409 PERIOD-409-OVERLAP); ghi đè được
    const nam = 1990 + (Number(stamp.slice(-5)) % 30);
    const quy = 1 + (Number(stamp.slice(-7, -5)) % 4);
    const tuNgay =
      process.env.E2E_THAT_KY_TU ?? `${nam}-${String(quy * 3 - 2).padStart(2, '0')}-01`;
    const denNgay =
      process.env.E2E_THAT_KY_DEN ??
      `${nam}-${String(quy * 3).padStart(2, '0')}-${quy === 1 || quy === 4 ? 31 : 30}`;
    const maKy = `KT${stamp.slice(-8)}`;
    const tenKy = `Kỳ thử khoá kỳ ${stamp}`;
    const url = '/admin/periods';

    // Quản trị tenant tạo kỳ thử (SCR-0801 `tao-ky`)
    let page = await s.as('quantri', url);
    let m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Kỳ đánh giá và khoá kỳ' }),
    ).toBeVisible();
    await m.getByRole('button', { name: 'Tạo kỳ' }).click();
    const tao = page.getByRole('dialog', { name: 'Tạo kỳ đánh giá' });
    await tao.getByRole('textbox', { name: 'Mã kỳ' }).fill(maKy);
    await tao.getByRole('textbox', { name: 'Tên kỳ' }).fill(tenKy);
    await tao.getByLabel('Từ ngày').fill(tuNgay);
    await tao.getByLabel('Đến ngày').fill(denNgay);
    await tao.getByRole('button', { name: 'Tạo kỳ' }).click();
    await expect(tao).toBeHidden();
    await expect(
      m.getByRole('status').filter({ hasText: new RegExp(`^Đã tạo kỳ ${esc(tenKy)}\\.`) }),
    ).toBeVisible();
    const chonKy = (p: Page, ten: string) =>
      main(p).getByRole('button', { name: new RegExp(`^${esc(ten)} `) });
    await expect(chonKy(page, tenKy)).toHaveAttribute('aria-current', 'true');
    const lenh = m.getByRole('region', { name: `${tenKy}: lệnh khoá kỳ` });
    await expect(lenh.getByText('Chưa lập lệnh')).toBeVisible();

    // Lập lệnh khoá kỳ (SCR-0801 `lap-lenh`, EVT-21)
    await lenh.getByRole('button', { name: 'Lập lệnh khoá kỳ' }).click();
    const lap = page.getByRole('dialog', { name: 'Lập lệnh khoá kỳ' });
    await lap
      .getByRole('textbox', { name: 'Văn bản căn cứ' })
      .fill(`Thông báo ${stamp.slice(-4)}-TB/ĐUK về khoá kỳ thử`);
    await lap.getByRole('button', { name: 'Lập lệnh' }).click();
    await expect(
      m.getByRole('status').filter({
        hasText: `Đã lập lệnh khoá kỳ ${tenKy}, chờ lãnh đạo phê duyệt.`,
      }),
    ).toBeVisible();
    await expect(lenh.getByText('Chờ lãnh đạo phê duyệt')).toBeVisible();
    await khongMaTho(m);

    // Bốn mắt (BR-08-01): người lập không có nút phê duyệt, hoặc máy chủ từ chối
    const pheDuyet = lenh.getByRole('button', { name: 'Phê duyệt lệnh' });
    if (await pheDuyet.isVisible()) {
      await pheDuyet.click();
      await xacThucBuocLen(
        page,
        'quantri',
        m.getByRole('alert').filter({ hasText: 'Người phê duyệt lệnh phải khác người lập lệnh.' }),
      );
      await expect(lenh.getByText('Chờ lãnh đạo phê duyệt')).toBeVisible();
    }

    // Lãnh đạo (khác người lập) phê duyệt lệnh có xác thực nhiều lớp (SCR-0801 `duyet-lenh`)
    const kyDuyet = THAT ? tenKy : 'Quý IV/2026';
    const kyChay = THAT ? tenKy : 'Tháng 9/2026';
    if (!THAT) {
      ghiChu(
        'Giả lập dùng một người dùng cho mọi bước nên phê duyệt chạy trên lệnh mẫu "Quý IV/2026" ' +
          'và khởi chạy trên lệnh mẫu đã duyệt "Tháng 9/2026"; tiến trình giả lập không chờ hoàn tất.',
      );
    }
    page = await s.as('lanhdao', url);
    m = main(page);
    await chonKy(page, kyDuyet).click();
    const lenhDuyet = m.getByRole('region', { name: `${kyDuyet}: lệnh khoá kỳ` });
    await lenhDuyet.getByRole('button', { name: 'Phê duyệt lệnh' }).click();
    await xacThucBuocLen(
      page,
      'lanhdao',
      m.getByRole('status').filter({
        hasText: new RegExp(`^Lệnh khoá kỳ ${esc(kyDuyet)} đã được phê duyệt bởi đồng chí`),
      }),
    );
    await expect(lenhDuyet.getByText('Chờ lãnh đạo phê duyệt')).toBeHidden();
    await khongMaTho(m);

    // Quản trị tenant (khác người phê duyệt) khởi chạy khoá kỳ: AAL2, 202 + job (SCR-0801 `khoi-chay`)
    page = await s.as('quantri', url);
    m = main(page);
    await chonKy(page, kyChay).click();
    const lenhChay = m.getByRole('region', { name: `${kyChay}: lệnh khoá kỳ` });
    await nhan202(page, /\/api\/v1\/periods\/[^/]+\/lock$/, () =>
      lenhChay.getByRole('button', { name: 'Khởi chạy' }).click(),
    );
    await xacThucBuocLen(
      page,
      'quantri',
      m.getByRole('status').filter({
        hasText: `Đã khởi chạy tiến trình khoá kỳ ${kyChay}. Tiến độ được cập nhật tự động.`,
      }),
    );

    // Màn hình tiến độ: sáu bước, chỉ số, nhật ký; đọc lại định kỳ tới khi khoá xong (thật) hoặc
    // tới khi bước tính điểm tiến lên (giả lập)
    const tienTrinh = m.getByRole('region', { name: /^Tiến trình khoá kỳ/ });
    await expect(tienTrinh).toBeVisible({ timeout: CHO_TAC_VU });
    await expect(
      tienTrinh.getByRole('table', { name: 'Sáu bước của tiến trình khoá kỳ và việc hoàn tác' }),
    ).toBeVisible();
    await expect(lenhChay.getByText('Đã khởi chạy')).toBeVisible();
    if (THAT) {
      const nhan = await theoDoi(
        m,
        /Đang chạy|Đang khoá/,
        tienTrinh.getByText(new RegExp(`^Kỳ ${esc(kyChay)} đã khoá lúc`)),
        CHO_KHOA_KY,
      );
      expect(nhan.length, 'nhãn đang chạy phải hiện trong lúc chờ').toBeGreaterThan(0);
      await expect(tienTrinh.getByRole('status')).toHaveText('Hoàn tất');
      await expect(chonKy(page, kyChay)).toContainText('Đã khoá');
    } else {
      const daTinh = tienTrinh.locator('.card').filter({ hasText: 'Đã tính điểm' }).locator('.big');
      await theoDoi(m, /Đang chạy/, daTinh.filter({ hasText: /^[1-9]/ }));
      await expect(tienTrinh.getByRole('progressbar', { name: 'Tiến độ tính điểm' })).toBeVisible();
    }
    await expect(
      m.getByRole('region', { name: /^Tiến trình khoá kỳ/ }).getByRole('table'),
    ).not.toContainText(MA_THO);
    await khongMaTho(m);
  });
});
