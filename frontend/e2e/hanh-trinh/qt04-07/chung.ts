import AxeBuilder from '@axe-core/playwright';
import { Browser, BrowserContext, Locator, Page, expect, request, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { completePopup } from '../../dang-nhap-keycloak';
import { ApiHeThong } from '../ho-tro/api';
import { HE_THONG, MA_LAN_CHAY, MOI_TRUONG, bienConThieu, cho, ghiCho } from '../ho-tro/moi-truong';
import { DanhTinh, Phien, TAI_KHOAN, TenTaiKhoan, main, spaGoto } from '../ho-tro/phien';
import { choSuKien, coKafkaUi } from '../ho-tro/su-kien';

/**
 * Phần dùng chung của các hành trình QT-04..QT-07 (QA-05) trên khung QA-03. Mỗi hành trình chạy được ở
 * hai chế độ:
 * - giả lập (mặc định): tắt đăng nhập, API giả lập trong trình duyệt. Một trang duy nhất mang hợp các
 *   vai trò của hành trình (sessionStorage `vt.devRoles`), chuyển tuyến trong SPA để kho giả lập không
 *   mất dữ liệu vừa tạo. Bước đọc lại qua API, kiểm sự kiện Kafka và tách vai trò chỉ máy chủ thật mới
 *   kiểm được nên ghi "chờ" vào báo cáo;
 * - `E2E_MODE=he-thong`: mỗi tài khoản thử một ngữ cảnh, đăng nhập Keycloak thật (`Phien` của QA-03),
 *   dựng dữ liệu và đọc lại trạng thái qua APISIX, kiểm EVT qua kafka-ui. Thiếu biến môi trường thì cả
 *   hành trình ghi "Chờ" thay vì đỏ.
 */
export { HE_THONG, MA_LAN_CHAY, cho, ghiCho, main };
export const GIA_LAP = !HE_THONG;

const SEED = JSON.parse(
  readFileSync(join(__dirname, '..', '..', '..', '..', 'data/seed/e2e/qt04-07.json'), 'utf8'),
) as Record<string, Record<string, string | number> & { $comment?: string }>;

/** Dữ liệu đầu vào của hành trình QT-04..QT-07; `{MA}` thay bằng tiền tố của lần chạy. */
export function seed<T = Record<string, string | number>>(khoa: string): T {
  const v = SEED[khoa];
  expect(v, `data/seed/e2e/qt04-07.json thiếu khoá ${khoa}`).toBeTruthy();
  return JSON.parse(JSON.stringify(v).replaceAll('{MA}', MA_LAN_CHAY)) as T;
}

/** Chuẩn EICAR (tệp thử chống mã độc, không phải mã độc) ghép lúc chạy để kho mã không chứa nguyên chuỗi. */
export function eicar(): Buffer {
  const p = SEED['eicar'] as unknown as { phan: string[] };
  return Buffer.from(p.phan.join(''), 'latin1');
}

/** Topic của EVT theo baseline (wiki 18); ghi đè được bằng `E2E_TOPIC_EVT<số>` nếu hạ tầng đặt tên khác. */
export const TOPIC: Record<string, string> = {
  'EVT-03': 'task.plan.approved.v1',
  'EVT-04': 'task.task.assigned.v1',
  'EVT-05': 'task.task.progressed.v1',
  'EVT-06': 'task.task.statuschanged.v1',
  'EVT-07': 'product.evidence.submitted.v1',
  'EVT-08': 'product.evidence.accepted.v1',
  'EVT-21': 'notify.message.requested.v1',
  'EVT-24': 'integ.document.received.v1',
};
const topic = (evt: string) =>
  process.env[`E2E_TOPIC_${evt.replace('-', '')}`]?.trim() || TOPIC[evt];

interface Mo {
  ctx: BrowserContext;
  page: Page;
}

/**
 * Phiên của một hành trình. Chế độ hệ thống thật dùng `Phien` của QA-03 (đăng nhập, bắt token); giả
 * lập dùng một trang chung. `cham` đếm số chạm để kiểm thao tác thường ngày ≤ 3 chạm (BR-18-09).
 */
export class PhienQt {
  private readonly that: Phien | null;
  private chung: Mo | null = null;
  private readonly api = new Map<TenTaiKhoan, ApiHeThong>();
  private readonly loiGiaLap: string[] = [];
  private soCham = 0;

  constructor(
    private readonly browser: Browser,
    private readonly users: TenTaiKhoan[],
    private readonly caoTuoi = false,
  ) {
    this.that = HE_THONG ? new Phien(browser) : null;
  }

  /** Đầu mỗi hành trình: ở chế độ hệ thống thật, thiếu biến môi trường thì cả ca chờ. */
  static batDau() {
    if (!HE_THONG) return;
    const thieu = bienConThieu();
    if (thieu.length) cho(`môi trường chưa đặt ${thieu.join(', ')}`);
  }

  get loi(): string[] {
    return this.that ? this.that.loi : this.loiGiaLap;
  }

  /** Trang của `user` đang mở `url` (đăng nhập ở lần đầu khi chạy với hệ thống thật). */
  async as(user: TenTaiKhoan, url: string): Promise<Page> {
    let page: Page;
    if (this.that) {
      page = await this.that.trang(user, url);
    } else if (this.chung) {
      page = this.chung.page;
      await spaGoto(page, url);
    } else {
      const roles = [...new Set(this.users.flatMap((u) => TAI_KHOAN[u]))].join(',');
      const ctx = await this.browser.newContext();
      await ctx.addInitScript((r) => sessionStorage.setItem('vt.devRoles', r), roles);
      page = await ctx.newPage();
      this.theoDoi(page);
      this.chung = { ctx, page };
      await page.goto(url);
    }
    await this.datCheDo(page);
    return page;
  }

  private theoDoi(page: Page) {
    const origin = new URL(test.info().project.use.baseURL ?? 'http://localhost:4200').origin;
    page.on('console', (m) => {
      if (m.type() === 'error' && page.url().startsWith(origin)) this.loiGiaLap.push(m.text());
    });
    page.on('pageerror', (e) => this.loiGiaLap.push(e.message));
  }

  /**
   * Chế độ Đảng viên cao tuổi bật bằng nút "Chữ lớn" trên thanh trên, như người dùng làm (NFR-11);
   * lựa chọn được ghi lên máy chủ (CNH-05) nên `dong()` trả về chế độ thường cho tài khoản thử.
   */
  private async datCheDo(page: Page) {
    const html = page.locator('html');
    const muon = this.caoTuoi ? 'elderly' : 'default';
    await page.getByRole('banner').waitFor();
    const dang = (await html.getAttribute('data-mode')) === 'elderly' ? 'elderly' : 'default';
    if (dang === muon) return;
    await page.getByRole('button', { name: this.caoTuoi ? 'Chữ lớn' : 'Chữ thường' }).click();
    if (this.caoTuoi) await expect(html).toHaveAttribute('data-mode', 'elderly');
    else await expect(html).not.toHaveAttribute('data-mode', 'elderly');
  }

  /** Danh tính (sub, tên, đơn vị) của `user` đọc từ access token (chỉ chế độ hệ thống thật). */
  async danhTinh(user: TenTaiKhoan): Promise<DanhTinh> {
    expect(this.that, 'danh tính chỉ có ở chế độ hệ thống thật').not.toBeNull();
    return this.that!.danhTinh(user);
  }

  /** Client API của `user` (chỉ chế độ hệ thống thật). */
  async apiCua(user: TenTaiKhoan): Promise<ApiHeThong> {
    expect(this.that, 'client API chỉ có ở chế độ hệ thống thật').not.toBeNull();
    let a = this.api.get(user);
    if (!a) {
      a = await ApiHeThong.tao(await this.that!.token(user), user);
      this.api.set(user, a);
    }
    return a;
  }

  /**
   * Lời gọi API tự đặt tiêu đề, cho hai việc `ApiHeThong` của QA-03 không làm: PATCH
   * `application/merge-patch+json` (xử lý điểm nghẽn) và gửi lại cùng một `Idempotency-Key` (ca phủ định
   * chống tạo trùng). Mặc định vẫn theo quy ước mục 13: khoá idempotency mới, `If-Match` khi có.
   */
  async goiTho(
    user: TenTaiKhoan,
    method: 'POST' | 'PATCH',
    path: string,
    opts: { body?: unknown; ifMatch?: string; khoa?: string; kieu?: string } = {},
  ): Promise<{ status: number; body: unknown }> {
    expect(this.that, 'gọi API chỉ có ở chế độ hệ thống thật').not.toBeNull();
    const ctx = await request.newContext({ baseURL: `${MOI_TRUONG.apisix}/api/v1/` });
    try {
      const headers: Record<string, string> = {
        authorization: `Bearer ${await this.that!.token(user)}`,
        accept: 'application/json',
        'content-type': opts.kieu ?? 'application/json',
        'idempotency-key': opts.khoa ?? randomUUID(),
      };
      if (opts.ifMatch) headers['if-match'] = opts.ifMatch;
      const r = await ctx.fetch(path.replace(/^\//, ''), {
        method,
        headers,
        data: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      });
      if (r.status() >= 400) {
        test.info().annotations.push({
          type: 'x-request-id',
          description: `${user}: ${method} /api/v1/${path} → ${r.status()} · ${r.headers()['x-request-id'] ?? ''}`,
        });
      }
      const text = await r.text();
      let body: unknown = text;
      try {
        body = text ? JSON.parse(text) : null;
      } catch {
        // giữ nguyên văn bản
      }
      return { status: r.status(), body };
    } finally {
      await ctx.dispose();
    }
  }

  /** Một chạm của người dùng (bấm, chọn) trên phần tử; đếm để kiểm số chạm của thao tác thường ngày. */
  async cham(loc: Locator) {
    this.soCham++;
    await loc.click();
  }

  batDauDem() {
    this.soCham = 0;
  }

  get daCham() {
    return this.soCham;
  }

  async dong() {
    if (this.caoTuoi && this.that) {
      // trả tài khoản thử về chế độ thường để hành trình khác không chạy nhầm chế độ
      for (const u of this.users) {
        try {
          const p = await this.that.trang(u, '/');
          const nut = p.getByRole('button', { name: 'Chữ thường' });
          if (await nut.isVisible()) await nut.click();
        } catch {
          // bỏ qua: dọn là phần phụ
        }
      }
    }
    for (const a of this.api.values()) await a.dong();
    await this.that?.dong();
    await this.chung?.ctx.close();
  }
}

/**
 * Bước chỉ kiểm được trên hệ thống thật (đọc lại trạng thái qua API, tách vai trò của máy chủ):
 * giả lập ghi "chờ" kèm lý do.
 */
export async function trenHeThong(moTa: string, f: () => Promise<void>) {
  if (!HE_THONG) {
    ghiCho(`${moTa}: chỉ kiểm được với máy chủ thật`);
    return;
  }
  await test.step(moTa, f);
}

/** Kiểm EVT trên Kafka theo khoá tổng hợp `loc` từ thời điểm `tu`; thiếu kafka-ui thì ghi chờ. */
export async function kiemSuKien(evt: string, loc: string, tu: Date) {
  if (!HE_THONG) {
    ghiCho(`kiểm ${evt}: chỉ kiểm được với Kafka của hệ thống thật`);
    return;
  }
  if (!coKafkaUi()) {
    ghiCho(`kiểm ${evt}: chưa đặt E2E_KAFKA_UI_URL (kafka-ui của hạ tầng dev)`);
    return;
  }
  await test.step(`${evt} trên ${topic(evt)}`, async () => {
    await choSuKien(topic(evt), loc, tu);
  });
}

/** Chờ máy chủ chuyển trạng thái (đọc lại bằng GET tới khi khớp, chịu được xử lý bất đồng bộ). */
export async function choTrangThai(
  api: ApiHeThong,
  path: string,
  trangThai: string,
  truong = 'status',
  timeout = 30_000,
) {
  await expect
    .poll(
      async () => {
        const kq = await api.goi<Record<string, unknown>>('GET', path);
        return kq.status === 200 ? String(kq.body[truong]) : `HTTP ${kq.status}`;
      },
      { message: `${path} chưa sang ${trangThai}`, timeout, intervals: [1_000, 2_000, 3_000] },
    )
    .toBe(trangThai);
}

/**
 * Lỗi truy cập đã biết, đã báo để mở task sửa riêng (QA-05 mục 4: không sửa lỗi phát hiện được trong
 * task kiểm thử). Hành trình vẫn chạy tiếp nhưng ghi chú vào báo cáo; gỡ mục khi lỗi được sửa.
 */
const LOI_DA_BIET: { id: string; vungChon: RegExp; ghiChu: string }[] = [
  {
    id: 'color-contrast',
    vungChon: /\.p-message-text\[data-pc-section="text"\]\[data-p="success"\]/,
    ghiChu: 'chữ của thông báo thành công (p-message mức success) chưa đủ tương phản 4,5:1',
  },
  {
    id: 'color-contrast',
    vungChon: /\.p-message-text\[data-pc-section="text"\]\[data-p="error"\]/,
    ghiChu: 'chữ của cảnh báo lỗi (p-message mức error, ví dụ báo quá hạn) chưa đủ tương phản 4,5:1',
  },
];

/** axe-core theo WCAG 2.1 AA trên vùng nội dung chính: không có lỗi mức serious hay critical. */
export async function kiemTruyCap(page: Page, ten: string) {
  await test.step(`khả năng truy cập: ${ten}`, async () => {
    const kq = await new AxeBuilder({ page })
      .include('main#main')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();
    const loi: string[] = [];
    for (const v of kq.violations.filter((x) => x.impact === 'serious' || x.impact === 'critical')) {
      for (const n of v.nodes) {
        const chon = n.target.join(' ');
        const biet = LOI_DA_BIET.find((b) => b.id === v.id && b.vungChon.test(chon));
        if (biet) {
          test.info().annotations.push({
            type: 'loi-da-biet',
            description: `${ten}: ${biet.ghiChu} (${v.id})`,
          });
        } else {
          loi.push(`${v.id}: ${chon} ${n.any.map((a) => a.message).join('; ')}`.trim());
        }
      }
    }
    expect(loi, `axe-core: lỗi mức serious ở ${ten}`).toEqual([]);
  });
}

/** Chọn một mục của p-select/p-multiselect PrimeNG (danh sách hiện ở `body`). */
export async function chon(page: Page, box: Locator, option?: string | RegExp) {
  const list = page.getByRole('listbox');
  // Bấm sớm lúc danh sách chọn chưa nạp xong thì PrimeNG không mở; bấm lại tới khi mở
  await expect(async () => {
    if (!(await list.isVisible())) await box.click();
    await expect(list).toBeVisible({ timeout: 1_000 });
  }).toPass();
  // bỏ qua tiêu đề nhóm (PrimeNG vẽ nhóm như một option không chọn được)
  const chonDuoc = list.locator(
    '[role=option]:not(.p-select-option-group):not(.p-multiselect-option-group):not([aria-disabled=true])',
  );
  await (option ? chonDuoc.filter({ hasText: option }) : chonDuoc).first().click();
  await expect(list).toBeHidden();
}

/**
 * Hộp "Xác thực nhiều lớp" (step-up RFC 9470): bấm Xác thực; hệ thống thật hoàn tất mật khẩu, OTP
 * trong cửa sổ Keycloak. `batBuoc` kiểm hộp phải hiện (thao tác AAL2 như nghiệm thu, duyệt ký số).
 */
export async function xacThucBuocLen(
  page: Page,
  user: TenTaiKhoan,
  xong: Locator,
  batBuoc: boolean,
) {
  const dialog = page.getByRole('dialog', { name: 'Xác thực nhiều lớp' });
  if (batBuoc) await expect(dialog).toBeVisible();
  else await expect(dialog.or(xong).first()).toBeVisible();
  if (await dialog.isVisible()) {
    const popup = HE_THONG ? page.waitForEvent('popup') : null;
    await dialog.getByRole('button', { name: 'Xác thực' }).click();
    if (popup) await completePopup(await popup, user);
  }
  await expect(xong).toBeVisible({ timeout: 30_000 });
}

/** Ngày `yyyy-mm-dd` sau `min` vài ngày, không vượt `max` (thuộc tính của ô ngày). */
export function ngayTrongKhoang(min: string | null, max: string | null, sau = 7): string {
  const d = min ? new Date(min + 'T00:00:00Z') : new Date();
  if (!min || d < new Date()) d.setTime(Date.now());
  d.setUTCDate(d.getUTCDate() + sau);
  const v = d.toISOString().slice(0, 10);
  return max && v > max ? max : v;
}

/** Thông báo trạng thái (vùng `status`) của trang có nội dung khớp. */
export const thongBao = (page: Page, noiDung: string | RegExp) =>
  main(page).getByRole('status').filter({ hasText: noiDung });

/**
 * `canbo` mở chi tiết nhiệm vụ mình chủ trì (SCR-0503 → SCR-0601): trên hệ thống thật tìm theo tên của
 * lần chạy; giả lập lấy việc chủ trì đang làm dở trong dữ liệu giả lập.
 */
export async function moNhiemVu(s: PhienQt, ten: string | null): Promise<Page> {
  const page = await s.as('canbo', '/tasks');
  const m = main(page);
  await expect(m.getByRole('heading', { level: 1, name: 'Việc của tôi' })).toBeVisible();
  const ds = m.getByRole('region', { name: 'Danh sách nhiệm vụ' });
  if (ten) {
    await m.getByRole('searchbox', { name: 'Tìm nhiệm vụ' }).fill(MA_LAN_CHAY);
    await ds.getByRole('link', { name: ten }).click();
  } else {
    // việc chủ trì đang thực hiện, chưa xong: tiến độ lớn hơn 0 và nhỏ hơn 100
    await ds
      .getByRole('row')
      .filter({ hasText: 'Chủ trì' })
      .filter({ hasNot: page.locator('[role=progressbar][aria-valuenow="0"], [role=progressbar][aria-valuenow="100"]') })
      .last()
      .getByRole('link')
      .first()
      .click();
  }
  await expect(m.getByRole('button', { name: 'Cập nhật tiến độ' })).toBeVisible();
  return page;
}
