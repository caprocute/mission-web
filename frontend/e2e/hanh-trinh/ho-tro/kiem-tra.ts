import AxeBuilder from '@axe-core/playwright';
import { Locator, Page, expect, test } from '@playwright/test';
import { completePopup } from '../../dang-nhap-keycloak';
import { HE_THONG } from './moi-truong';

/**
 * Kiểm tra dùng chung của các hành trình (tiêu chí "hành trình đạt", wiki 11): khả năng truy cập theo
 * axe-core trên màn hình đi qua, chế độ Đảng viên cao tuổi (NFR-11) và các bước chỉ có nghĩa trên hệ
 * thống thật (đọc lại dữ liệu qua API, sự kiện Kafka).
 */

/** Không có vi phạm WCAG 2.1 AA mức serious hoặc critical trong vùng nội dung chính. */
export async function kiemAxe(page: Page, ten: string) {
  const kq = await new AxeBuilder({ page })
    .include('main#main')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  const nang = kq.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
  expect(nang, `axe-core trên màn hình ${ten}`).toEqual([]);
}

async function choHetHieuUng(page: Page) {
  // Đợi hai khung hình để trình duyệt kịp tính kiểu và khởi động transition, rồi đợi mọi hiệu ứng dừng
  await page.evaluate(
    () => new Promise((xong) => requestAnimationFrame(() => requestAnimationFrame(xong))),
  );
  await page
    .waitForFunction(() => document.getAnimations().every((a) => a.playState !== 'running'), null, {
      polling: 100,
      timeout: 5_000,
    })
    .catch(() => undefined);
}

/**
 * Quét axe sau khi các hiệu ứng hiện dần (thông báo PrimeNG, hộp thoại) đã chạy xong: quét giữa lúc mờ dần
 * cho kết quả tương phản màu sai (QA-07 gặp ở thông báo cảnh báo màn hình khiếu nại, khoảng 1/20 lần chạy).
 * Nếu lần quét đầu vẫn báo lỗi thì đợi thêm rồi quét lại một lần: lỗi thật vẫn còn ở lần quét thứ hai.
 */
export async function kiemAxeOnDinh(page: Page, ten: string) {
  await choHetHieuUng(page);
  try {
    await kiemAxe(page, ten);
  } catch {
    await page.waitForTimeout(500);
    await choHetHieuUng(page);
    await kiemAxe(page, ten);
  }
}

/** Bật chế độ Đảng viên cao tuổi bằng nút "Chữ lớn" trên thanh trên. */
export async function batCaoTuoi(page: Page) {
  const nut = page.getByRole('button', { name: 'Chữ lớn' });
  // Trên hệ thống thật chế độ hiển thị lưu theo người dùng ở máy chủ (CNH-02), nên lần chạy trước có thể đã
  // bật sẵn: khi đó nút mang nhãn "Chữ thường" và không bấm lại
  const daBat = page.getByRole('button', { name: 'Chữ thường', pressed: true });
  await expect(nut.or(daBat).first()).toBeVisible();
  if (await nut.isVisible()) await nut.click();
  await expect(page.locator('html')).toHaveAttribute('data-mode', 'elderly');
}

/** Về chế độ chữ thường nếu lần chạy trước để lại chế độ cao tuổi (lưu ở máy chủ, CNH-02). */
export async function tatCaoTuoi(page: Page) {
  const daBat = page.getByRole('button', { name: 'Chữ thường', pressed: true });
  const nut = page.getByRole('button', { name: 'Chữ lớn' });
  await expect(nut.or(daBat).first()).toBeVisible();
  if (await daBat.isVisible()) await daBat.click();
  await expect(page.locator('html')).not.toHaveAttribute('data-mode', 'elderly');
}

/**
 * Bước chỉ chạy trên hệ thống thật (đọc lại qua API, kiểm sự kiện); ở chế độ giả lập ghi chú
 * `khong-ap-dung` để báo cáo nói rõ bước nào chưa được kiểm.
 */
export async function chiHeThong(buoc: string, fn: () => Promise<void>) {
  if (HE_THONG) return fn();
  test.info().annotations.push({
    type: 'khong-ap-dung',
    description: `${buoc} (chỉ kiểm trên hệ thống thật)`,
  });
}

/** Chuỗi phân biệt lần chạy, gắn vào tên dữ liệu tạo qua giao diện. */
export const dau = () => String(Date.now()).slice(-8);

/**
 * Hộp "Xác thực nhiều lớp" (bước lên RFC 9470): bấm Xác thực; trên hệ thống thật hoàn tất mật khẩu,
 * OTP trong cửa sổ Keycloak. `batBuoc` kiểm hộp phải hiện; `xong` là dấu hiệu thao tác đã thành.
 */
export async function xacThucBuocLen(page: Page, user: string, xong: Locator, batBuoc: boolean) {
  const dialog = page.getByRole('dialog', { name: 'Xác thực nhiều lớp' });
  // Phiên vừa bước lên thì không hỏi lại; khi đó chờ thẳng kết quả ký số bất đồng bộ.
  if (batBuoc) await expect(dialog).toBeVisible();
  else await expect(dialog.or(xong).first()).toBeVisible({ timeout: HE_THONG ? 60_000 : 5_000 });
  if (await dialog.isVisible()) {
    const popup = HE_THONG ? page.waitForEvent('popup') : null;
    await dialog.getByRole('button', { name: 'Xác thực' }).click();
    if (popup) await completePopup(await popup, user);
  }
  // Hệ thống thật ký số bất đồng bộ (MySign giả lập, TSA) rồi mới báo xong qua tác vụ 202.
  await expect(xong).toBeVisible({ timeout: HE_THONG ? 60_000 : 5_000 });
}

/** Chọn một mục của p-select PrimeNG (danh sách hiện ở `body`); bấm lại tới khi danh sách mở. */
export async function chon(page: Page, box: Locator, option?: string | RegExp) {
  const list = page.getByRole('listbox');
  await expect(async () => {
    if (!(await list.isVisible())) await box.click();
    await expect(list).toBeVisible({ timeout: 1_000 });
  }).toPass();
  await (option ? list.getByRole('option', { name: option }) : list.getByRole('option'))
    .first()
    .click();
  await expect(list).toBeHidden();
}
