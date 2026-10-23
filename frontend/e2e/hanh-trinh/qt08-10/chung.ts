import { Locator, Page, expect, test } from '@playwright/test';
import { ApiHeThong } from '../ho-tro/api';
import { dau, kiemAxe as kiemAxeGoc, xacThucBuocLen } from '../ho-tro/kiem-tra';
import { HE_THONG, cho } from '../ho-tro/moi-truong';
import { main } from '../ho-tro/phien';

export { api, kiemMoiTruong } from '../qt00-03/chung';

/**
 * Phần dùng chung của nhóm hành trình QT-08..QT-10 (QA-06): khoá kỳ, tự đánh giá, thẩm định, quyết
 * định ký số, xếp loại năm. Giống QA-04, mỗi hành trình chạy ở hai chế độ: giả lập
 * (`npm run e2e:hanh-trinh`) và hệ thống thật (`npm run e2e:he-thong`, đọc lại qua API và Kafka).
 *
 * Kho giả lập nhận mọi thao tác là của một người dùng cố định, nên ở chế độ giả lập các bước cần
 * "người khác" (duyệt lệnh khác người lập, khởi chạy khác người duyệt, duyệt huỷ khác người đề nghị)
 * chạy trên kỳ có sẵn trong dữ liệu giả lập mà người lập là người khác; trên hệ thống thật các bước đó
 * chạy trên chính kỳ hành trình vừa tạo.
 */

/** Hồ sơ giả lập: phiếu tự đánh giá của tôi (SCR-0901) và một hồ sơ chờ thẩm định (SCR-0902). */
export const HO_SO_GIA_LAP = {
  cuaToi: '01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a01',
  choThamDinh: '01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a21',
} as const;

/** Kỳ giả lập có lệnh đang chờ duyệt, lệnh đã duyệt, đề nghị huỷ chờ duyệt, kỳ đã khoá xong. */
export const KY_GIA_LAP = {
  lenhChoDuyet: 'Quý IV/2026',
  lenhDaDuyet: 'Tháng 9/2026',
  deNghiHuy: 'Tháng 8/2026',
  daKhoa: 'Quý II/2026',
} as const;

export interface KyThu {
  ma: string;
  ten: string;
}

/**
 * Tạo một kỳ tháng riêng cho lần chạy qua giao diện (SCR-0801 "Tạo kỳ"). Tháng chọn ngẫu nhiên trong
 * quá khứ xa để không đụng kỳ thật của tenant dev và không trùng giữa các lần chạy.
 */
export async function taoKy(page: Page): Promise<KyThu> {
  const so = Number(dau());
  const nam = 1990 + (so % 30);
  const thang = 1 + (Math.floor(so / 30) % 12);
  const mm = String(thang).padStart(2, '0');
  const cuoi = new Date(Date.UTC(nam, thang, 0)).getUTCDate();
  const ky = { ma: `E2E-${nam}${mm}`, ten: `Kỳ thử E2E ${mm}/${nam}` };
  const m = main(page);
  await m.getByRole('button', { name: 'Tạo kỳ' }).click();
  const hop = page.getByRole('dialog', { name: 'Tạo kỳ đánh giá' });
  await expect(hop).toBeVisible();
  await hop.getByRole('textbox', { name: 'Mã kỳ' }).fill(ky.ma);
  await hop.getByRole('textbox', { name: 'Tên kỳ' }).fill(ky.ten);
  await chonLoaiThang(page, hop);
  await hop.getByRole('textbox', { name: 'Từ ngày' }).fill(`${nam}-${mm}-01`);
  await hop.getByRole('textbox', { name: 'Đến ngày' }).fill(`${nam}-${mm}-${cuoi}`);
  await hop.getByRole('button', { name: 'Tạo kỳ' }).click();
  if (HE_THONG) {
    const loi = page.getByRole('alert').filter({ hasText: /đã tồn tại|trùng/i });
    if (await loi.isVisible().catch(() => false))
      cho(`mã kỳ ${ky.ma} đã có trên dev, chạy lại để lấy tháng khác`);
  }
  await expect(m.getByRole('status').filter({ hasText: `Đã tạo kỳ ${ky.ten}` })).toBeVisible();
  return ky;
}

async function chonLoaiThang(page: Page, hop: Locator) {
  const loai = hop.getByRole('combobox', { name: 'Quý' });
  const list = page.getByRole('listbox');
  await expect(async () => {
    if (!(await list.isVisible())) await loai.click();
    await expect(list).toBeVisible({ timeout: 1_000 });
  }).toPass();
  await list.getByRole('option', { name: 'Tháng', exact: true }).click();
  await expect(list).toBeHidden();
}

/** Chọn kỳ trong "Danh sách kỳ" để mở vùng lệnh khoá kỳ và tiến trình của kỳ đó. */
export async function moKy(page: Page, ten: string) {
  const m = main(page);
  await m.getByRole('button', { name: new RegExp(`^${ten.replace(/[/.]/g, '\\$&')} `) }).click();
  const vung = m.getByRole('region', { name: `${ten}: lệnh khoá kỳ` });
  await expect(vung).toBeVisible();
  return vung;
}

/** Lập lệnh khoá kỳ (SCR-0801.lap-lenh) cho kỳ đang mở. */
export async function lapLenh(page: Page, ky: KyThu) {
  const vung = await moKy(page, ky.ten);
  await vung.getByRole('button', { name: 'Lập lệnh khoá kỳ' }).click();
  const hop = page.getByRole('dialog', { name: 'Lập lệnh khoá kỳ' });
  await hop.getByRole('textbox', { name: 'Văn bản căn cứ' }).fill(`Thông báo ${ky.ma}/TB`);
  await hop.getByRole('button', { name: 'Lập lệnh' }).click();
  await expect(
    main(page)
      .getByRole('status')
      .filter({ hasText: `Đã lập lệnh khoá kỳ ${ky.ten}` }),
  ).toBeVisible();
}

/** Bấm một thao tác cần xác thực nhiều lớp và chờ dấu hiệu thành công `xong`. */
export async function bamCoXacThuc(
  page: Page,
  user: string,
  nut: Locator,
  xong: Locator,
): Promise<void> {
  await nut.click();
  await xacThucBuocLen(page, user, xong, false);
}

/**
 * axe-core sau khi hiệu ứng chuyển cảnh dừng: thông báo PrimeNG mờ dần lúc xuất hiện hoặc biến mất,
 * nếu đo giữa chừng thì độ tương phản của chữ bị tính sai.
 */
export async function kiemAxe(page: Page, ten: string) {
  // Bỏ qua hiệu ứng lặp vô hạn (biểu tượng đang chạy của bước saga)
  await page.waitForFunction(() =>
    document
      .getAnimations()
      .every((a) => a.playState !== 'running' || a.effect?.getTiming().iterations === Infinity),
  );
  await kiemAxeGoc(page, ten);
}

/** Thông báo trạng thái trong vùng nội dung chính có chứa `text`. */
export const thongBao = (page: Page, text: string | RegExp) =>
  main(page).getByRole('status').filter({ hasText: text }).first();

/** Thông báo lỗi (role=alert) có chứa `text`. */
export const canhBao = (page: Page, text: string | RegExp) =>
  page.getByRole('alert').filter({ hasText: text }).first();

/** Ghi số đo vào báo cáo hành trình (cột Ghi chú). */
export function ghiDoDac(noiDung: string) {
  test.info().annotations.push({ type: 'do-dac', description: noiDung });
}

/** Trạng thái hồ sơ đánh giá đọc lại qua `getAppraisalProfile`. */
export async function trangThaiHoSo(c: ApiHeThong, id: string): Promise<string> {
  const r = await c.can<{ status: string }>('GET', `appraisals/profiles/${id}`);
  return r.body.status;
}

/** Hồ sơ đầu tiên của tôi ở trạng thái `status` (`listMyAppraisalProfiles`), hoặc null. */
export async function hoSoCuaToi(c: ApiHeThong, status: string): Promise<string | null> {
  const r = await c.can<{ items: { id: string; status: string }[] }>(
    'GET',
    'appraisals/profiles/mine',
  );
  return r.body.items.find((x) => x.status === status)?.id ?? null;
}

/** Hồ sơ đầu tiên trong phạm vi ở trạng thái `status` (`listAppraisalProfiles`), hoặc null. */
export async function hoSoTheoTrangThai(c: ApiHeThong, status: string): Promise<string | null> {
  const r = await c.can<{ items: { id: string }[] }>('GET', 'appraisals/profiles', {
    query: { status },
  });
  return r.body.items[0]?.id ?? null;
}

/** Topic của một sự kiện, cho phép đặt lại bằng biến `E2E_TOPIC_EVTxx`. */
export const topic = (evt: string, macDinh: string) =>
  process.env[`E2E_TOPIC_${evt.replace('-', '')}`]?.trim() || macDinh;
