import { Browser, BrowserContext, Page, Response, expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { completeKeycloak } from '../../dang-nhap-keycloak';
import { HE_THONG } from './moi-truong';

/**
 * Phiên đăng nhập theo vai trò cho hành trình trên hệ thống thật (QA-03): mỗi tài khoản thử một ngữ
 * cảnh trình duyệt, đăng nhập Keycloak thật có OTP (dùng lại `completeKeycloak`, `totp.ts`). Token
 * của ứng dụng chỉ nằm trong bộ nhớ trang (SEC-07), nên phiên bắt access token từ phản hồi của
 * endpoint token Keycloak để client API dựng dữ liệu bằng đúng danh tính đó; token không ghi ra
 * log, ảnh hay báo cáo.
 *
 * Chế độ giả lập (`npm run e2e:hanh-trinh`): tắt đăng nhập, vai trò của tài khoản thử lấy từ realm dev
 * đặt vào sessionStorage `vt.devRoles`. Kho giả lập nằm trong bộ nhớ trang, nên các tài khoản cùng
 * tham gia một luồng dữ liệu (tham số `chung`) dùng chung một trang mang hợp vai trò của họ và chuyển
 * tuyến trong SPA; tài khoản khác mở ngữ cảnh riêng chỉ mang vai trò của mình (dùng cho ca phủ định).
 */
const REALM = JSON.parse(
  readFileSync(
    join(__dirname, '..', '..', '..', '..', 'infra/dev/keycloak/realm-mission.json'),
    'utf8',
  ),
) as { users: { username: string; realmRoles: string[] }[] };

/** Tài khoản thử của realm dev và vai trò tối thiểu mỗi hành trình cần (docs/phan-quyen/ma-tran.json). */
export const TAI_KHOAN = {
  /** platform_admin: khởi tạo, cấp phát, kích hoạt tenant (SCR-0101, SCR-0102). */
  nentang: ['platform_admin'],
  /** tenant_admin: dựng kỳ và dữ liệu cấu hình; cây tổ chức, đồng bộ nhân sự (SCR-0103, SCR-0104). */
  quantri: ['tenant_admin'],
  /** member + manager: giao việc (SCR-0501). */
  quanly: ['member', 'manager'],
  /** member + leader: phê duyệt, trả lại OKR (SCR-0203). */
  lanhdao: ['member', 'leader'],
  /** member + appraiser: vai trò AAL2, bị hỏi OTP khi đăng nhập. */
  thamdinh: ['member', 'appraiser'],
  /** member: AC-06 "Việc của tôi", cập nhật tiến độ (SCR-0503, SCR-0601). */
  canbo: ['member'],
  /** member + inspector: AC-10 cảnh báo, sổ cái, yêu cầu mở lại kỳ (SCR-1301, SCR-1302, SCR-1401). */
  kiemtra: ['member', 'inspector'],
  /** decider: AC-08 ký gói hồ sơ, ký trả lời khiếu nại, duyệt lượt một, phát hành Policy Pack. */
  quyetdinh: ['decider'],
  /** second_approver: AC-09 duyệt lượt hai mở lại kỳ (SCR-1301). */
  duyet2: ['second_approver'],
  /** policy_author: AC-03 soạn Policy Pack, ca vàng, luồng duyệt (SCR-1501..SCR-1504). */
  chinhsach: ['policy_author'],
  /** tenant_admin thứ hai: duyệt phát hành UI Pack do `quantri` soạn (SCR-1801, bốn mắt). */
  quantri2: ['tenant_admin'],
} as const;
export type TenTaiKhoan = keyof typeof TAI_KHOAN;

/** Kiểm realm dev còn tài khoản với đủ vai trò hành trình cần. */
export function kiemTaiKhoan(user: TenTaiKhoan): string[] {
  const u = REALM.users.find((x) => x.username === user);
  expect(u, `realm dev thiếu tài khoản ${user}`).toBeTruthy();
  expect(u!.realmRoles).toEqual(expect.arrayContaining([...TAI_KHOAN[user]]));
  return u!.realmRoles;
}

/** Thông tin danh tính đọc từ access token (không giữ chữ ký, không in token). */
export interface DanhTinh {
  sub: string;
  ten: string;
  donVi: string | null;
  tenant: string | null;
}

function giaiMa(token: string): DanhTinh {
  const claims = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
  return {
    sub: claims.sub,
    ten: claims.name ?? claims.preferred_username ?? '',
    donVi: claims.unit_id ?? null,
    tenant: claims.tenant ?? null,
  };
}

interface Mo {
  ctx: BrowserContext;
  page: Page;
  token: string | null;
}

export class Phien {
  private readonly mo = new Map<TenTaiKhoan, Mo>();
  /** Trang dùng chung của nhóm `chung` ở chế độ giả lập. */
  private chungMo: Mo | null = null;
  /** Lỗi console, lỗi trang và phản hồi 5xx của ứng dụng (không tính trang Keycloak). */
  readonly loi: string[] = [];
  /** Tài khoản đã được Keycloak hỏi mã OTP khi đăng nhập (he-thong). */
  readonly daHoiOtp = new Set<TenTaiKhoan>();

  constructor(
    private readonly browser: Browser,
    private readonly chung: TenTaiKhoan[] = [],
  ) {}

  private origin() {
    return new URL(test.info().project.use.baseURL ?? 'http://localhost:4200').origin;
  }

  private theoDoi(m: Mo, user?: TenTaiKhoan) {
    const origin = this.origin();
    m.page.on('request', (r) => {
      if (
        user &&
        r.method() === 'POST' &&
        r.url().includes('/login-actions/') &&
        /(^|&)otp=/.test(r.postData() ?? '')
      )
        this.daHoiOtp.add(user);
    });
    m.page.on('console', (msg) => {
      if (msg.type() === 'error' && m.page.url().startsWith(origin)) this.loi.push(msg.text());
    });
    m.page.on('pageerror', (e) => this.loi.push(e.message));
    m.page.on('response', (r: Response) => {
      if (r.status() >= 500 && r.url().startsWith(origin)) {
        const id = r.headers()['x-request-id'];
        this.loi.push(
          `${r.request().method()} ${new URL(r.url()).pathname} → ${r.status()}${id ? ` · ${id}` : ''}`,
        );
      }
    });
    if (!HE_THONG) return;
    // Bắt token qua route thay vì đọc thân phản hồi: Chrome bỏ thân phản hồi khi trang chuyển tuyến ngay sau
    // khi đổi mã lấy token (getResponseBody "No data found"), nên cách đọc sau làm mất token của lần đăng nhập.
    void m.page.route(/\/protocol\/openid-connect\/token$/, async (route) => {
      const res = await route.fetch();
      const body = await res.text();
      if (res.status() === 200) {
        try {
          const b = JSON.parse(body) as { access_token?: string };
          if (b.access_token) m.token = b.access_token;
        } catch {
          // thân không phải JSON: để nguyên cho ứng dụng tự xử lý
        }
      }
      await route.fulfill({ response: res, body });
    });
  }

  /** Trang của `user` đang mở `url`; đăng nhập Keycloak ở lần đầu (giả lập: đặt vai trò). */
  async trang(user: TenTaiKhoan, url: string): Promise<Page> {
    kiemTaiKhoan(user);
    if (!HE_THONG) return this.trangGiaLap(user, url);
    const dang = this.mo.get(user);
    if (dang) {
      await spaGoto(dang.page, url);
      return dang.page;
    }
    const ctx = await this.browser.newContext();
    const m: Mo = { ctx, page: await ctx.newPage(), token: null };
    this.theoDoi(m, user);
    this.mo.set(user, m);
    await m.page.goto(url);
    await completeKeycloak(m.page, user);
    const here = new URL(m.page.url());
    if (here.pathname + here.search !== url) await spaGoto(m.page, url);
    return m.page;
  }

  private async trangGiaLap(user: TenTaiKhoan, url: string): Promise<Page> {
    const trongNhom = this.chung.includes(user);
    const dang = trongNhom ? this.chungMo : (this.mo.get(user) ?? null);
    if (dang) {
      await spaGoto(dang.page, url);
      return dang.page;
    }
    const roles = [...new Set((trongNhom ? this.chung : [user]).flatMap(kiemTaiKhoan))].join(',');
    const ctx = await this.browser.newContext();
    await ctx.addInitScript((r) => sessionStorage.setItem('vt.devRoles', r), roles);
    const m: Mo = { ctx, page: await ctx.newPage(), token: null };
    this.theoDoi(m);
    if (trongNhom) this.chungMo = m;
    else this.mo.set(user, m);
    await m.page.goto(url);
    return m.page;
  }

  /** Access token hiện hành của `user` (đăng nhập ở trang chủ nếu chưa); chỉ có ở chế độ he-thong. */
  async token(user: TenTaiKhoan): Promise<string> {
    if (!HE_THONG) throw new Error('Chế độ giả lập không có access token');
    if (!this.mo.has(user)) await this.trang(user, '/');
    const m = this.mo.get(user)!;
    await expect
      .poll(() => m.token, { message: `chưa bắt được access token của ${user}`, timeout: 15_000 })
      .not.toBeNull();
    return m.token!;
  }

  async danhTinh(user: TenTaiKhoan): Promise<DanhTinh> {
    return giaiMa(await this.token(user));
  }

  async dong() {
    for (const { ctx } of this.mo.values()) await ctx.close();
    await this.chungMo?.ctx.close();
    this.mo.clear();
    this.chungMo = null;
  }
}

/** Chuyển tuyến trong SPA (router Angular nghe `popstate`), không tải lại trang nên token còn. */
export async function spaGoto(page: Page, url: string) {
  await page.evaluate((u) => {
    history.pushState(null, '', u);
    dispatchEvent(new PopStateEvent('popstate', { state: null }));
  }, url);
}

export const main = (page: Page) => page.locator('main#main');
