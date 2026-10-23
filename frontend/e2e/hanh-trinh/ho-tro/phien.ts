import { Browser, BrowserContext, Page, Response, expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { completeKeycloak } from '../../dang-nhap-keycloak';

/**
 * Phiên đăng nhập theo vai trò cho hành trình trên hệ thống thật (QA-03): mỗi tài khoản thử một ngữ
 * cảnh trình duyệt, đăng nhập Keycloak thật có OTP (dùng lại `completeKeycloak`, `totp.ts`). Token
 * của ứng dụng chỉ nằm trong bộ nhớ trang (SEC-07), nên phiên bắt access token từ phản hồi của
 * endpoint token Keycloak để client API dựng dữ liệu bằng đúng danh tính đó; token không ghi ra
 * log, ảnh hay báo cáo.
 */
const REALM = JSON.parse(
  readFileSync(
    join(__dirname, '..', '..', '..', '..', 'infra/dev/keycloak/realm-mission.json'),
    'utf8',
  ),
) as { users: { username: string; realmRoles: string[] }[] };

/** Tài khoản thử của realm dev và vai trò tối thiểu mỗi hành trình cần (docs/phan-quyen/ma-tran.json). */
export const TAI_KHOAN = {
  /** tenant_admin: dựng kỳ và dữ liệu cấu hình. */
  quantri: ['tenant_admin'],
  /** member + manager: giao việc (SCR-0501). */
  quanly: ['member', 'manager'],
  /** member + leader. */
  lanhdao: ['member', 'leader'],
  /** member: AC-06 "Việc của tôi", cập nhật tiến độ (SCR-0503, SCR-0601). */
  canbo: ['member'],
} as const;
export type TenTaiKhoan = keyof typeof TAI_KHOAN;

/** Kiểm realm dev còn tài khoản với đủ vai trò hành trình cần. */
export function kiemTaiKhoan(user: TenTaiKhoan) {
  const u = REALM.users.find((x) => x.username === user);
  expect(u, `realm dev thiếu tài khoản ${user}`).toBeTruthy();
  expect(u!.realmRoles).toEqual(expect.arrayContaining([...TAI_KHOAN[user]]));
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
  /** Lỗi console, lỗi trang và phản hồi 5xx của ứng dụng (không tính trang Keycloak). */
  readonly loi: string[] = [];

  constructor(private readonly browser: Browser) {}

  private origin() {
    return new URL(test.info().project.use.baseURL ?? 'http://localhost:4200').origin;
  }

  private theoDoi(m: Mo) {
    const origin = this.origin();
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
      if (!/\/protocol\/openid-connect\/token$/.test(new URL(r.url()).pathname)) return;
      if (r.status() !== 200) return;
      r.json()
        .then((b: { access_token?: string }) => {
          if (b.access_token) m.token = b.access_token;
        })
        .catch(() => undefined);
    });
  }

  /** Trang của `user` đang mở `url`; đăng nhập Keycloak ở lần đầu. */
  async trang(user: TenTaiKhoan, url: string): Promise<Page> {
    kiemTaiKhoan(user);
    const dang = this.mo.get(user);
    if (dang) {
      await spaGoto(dang.page, url);
      return dang.page;
    }
    const ctx = await this.browser.newContext();
    const m: Mo = { ctx, page: await ctx.newPage(), token: null };
    this.theoDoi(m);
    this.mo.set(user, m);
    await m.page.goto(url);
    await completeKeycloak(m.page, user);
    const here = new URL(m.page.url());
    if (here.pathname + here.search !== url) await spaGoto(m.page, url);
    return m.page;
  }

  /** Access token hiện hành của `user` (đăng nhập ở trang chủ nếu chưa). */
  async token(user: TenTaiKhoan): Promise<string> {
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
    this.mo.clear();
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
