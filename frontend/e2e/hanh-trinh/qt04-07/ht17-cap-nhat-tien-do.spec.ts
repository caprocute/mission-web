import { expect, test } from '@playwright/test';
import { ApiHeThong } from '../ho-tro/api';
import {
  NhiemVuTom,
  TIEN_DO,
  choNeuPhuThuoc,
  docNhiemVu,
  donTheoTienTo,
  giaoNhiemVu,
  kyDangMo,
} from '../ho-tro/du-lieu';
import { HE_THONG, MA_LAN_CHAY, bienConThieu, cho, ghiCho } from '../ho-tro/moi-truong';
import { Phien, main } from '../ho-tro/phien';
import { choSuKien, coKafkaUi } from '../ho-tro/su-kien';

/**
 * HT-17 (hành trình mẫu của QA-03, `danh-muc.json`) trên hệ thống thật (backend thật qua APISIX, Keycloak realm `mission`):
 * cán bộ (AC-06, tài khoản `canbo`) mở "Việc của tôi", cập nhật tiến độ một nhiệm vụ mình chủ trì,
 * máy chủ phát EVT-05 và giao diện hiện phần trăm mới.
 * Truy vết: QT-05, QT-06 → SCR-0503, SCR-0601 → `listMyTasks`, `updateTaskProgress` → MOD-02 (SVC-05)
 * → `task.task_progress`, EVT-05 `task.task.progressed.v1`.
 * Dữ liệu đầu vào dựng qua API (`data/seed/e2e/he-thong.json`): kỳ đang mở và một nhiệm vụ đột xuất do
 * `quanly` giao cho `canbo`, tên mang tiền tố của lần chạy.
 * Khác danh mục: bản mẫu vào nhiệm vụ từ "Việc của tôi" theo QA-03 thay cho lối tắt trang chủ và chưa
 * chạy ở chế độ cao tuổi; QA-05 bổ sung hai phần đó trước khi đổi `trangThai` sang `dat`.
 */
const TOPIC_EVT05 = process.env.E2E_TOPIC_EVT05?.trim() || 'task.task.progressed.v1';

test.describe('Nhiệm vụ', { tag: '@qa-03' }, () => {
  test.describe.configure({ mode: 'serial' });
  test.skip(!HE_THONG, 'Chỉ chạy với E2E_MODE=he-thong');

  let s: Phien;
  let quanLy: ApiHeThong | null = null;
  let kyId: string | null = null;

  test.beforeAll(({ browser }) => {
    s = new Phien(browser);
  });

  test.afterAll(async () => {
    if (quanLy) {
      await donTheoTienTo(quanLy, kyId);
      await quanLy.dong();
    }
    await s.dong();
  });

  test('HT-17 cán bộ cập nhật tiến độ việc của mình, máy chủ phát sự kiện và màn hình hiện tiến độ mới', async () => {
    const thieu = bienConThieu();
    if (thieu.length) cho(`môi trường chưa đặt ${thieu.join(', ')}`);

    // Dựng dữ liệu bằng danh tính thật của từng vai trò
    let nv: NhiemVuTom & { code: string };
    try {
      const quanTri = await ApiHeThong.tao(await s.token('quantri'), 'quantri');
      quanLy = await ApiHeThong.tao(await s.token('quanly'), 'quanly');
      const ky = await kyDangMo(quanTri);
      kyId = ky.id;
      await quanTri.dong();
      nv = await giaoNhiemVu(quanLy, await s.danhTinh('quanly'), await s.danhTinh('canbo'), ky);
    } catch (e) {
      choNeuPhuThuoc(e, 'dựng dữ liệu');
    }
    const truoc = nv.progress ?? 0;

    // AC-06 mở "Việc của tôi" (SCR-0503), tìm nhiệm vụ theo tên và mở chi tiết (SCR-0601)
    const page = await s.trang('canbo', '/tasks');
    const m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: 'Việc của tôi' })).toBeVisible();
    await m.getByRole('searchbox', { name: 'Tìm nhiệm vụ' }).fill(MA_LAN_CHAY);
    const list = m.getByRole('region', { name: 'Danh sách nhiệm vụ' });
    await list.getByRole('link', { name: nv.title }).click();
    await expect(m.getByRole('heading', { level: 1, name: nv.title })).toBeVisible();

    // Cập nhật tiến độ qua hộp thoại (`updateTaskProgress`, If-Match do ứng dụng gửi)
    const tu = new Date(Date.now() - 5_000);
    await m.getByRole('button', { name: 'Cập nhật tiến độ' }).click();
    const dialog = page.getByRole('dialog', { name: 'Cập nhật tiến độ' });
    await dialog.getByRole('slider', { name: 'Tiến độ' }).focus();
    for (let i = 0; i < TIEN_DO.buocTang; i++) await page.keyboard.press('ArrowRight');
    await dialog.getByRole('textbox', { name: 'Kết quả đã làm' }).fill(TIEN_DO.ghiChu);
    const luu = dialog.getByRole('button', { name: /^Lưu \d+%$/ });
    const moi = Number((await luu.innerText()).match(/(\d+)%/)![1]);
    expect(moi, 'phần trăm mới phải lớn hơn trước').toBeGreaterThan(truoc);
    await luu.click();
    await expect(dialog).toBeHidden();
    await expect(m.getByRole('status').filter({ hasText: 'Đã lưu tiến độ.' })).toBeVisible();

    // Máy chủ là nguồn sự thật: đọc lại qua API, trạng thái cuối `DANG_THUC_HIEN` (baseline 9.1)
    const sau = await docNhiemVu(quanLy!, nv.id);
    expect(sau.progress).toBe(moi);
    expect(sau.status).toBe('DANG_THUC_HIEN');

    // Ca phủ định: cập nhật với phiên bản cũ nhận xung đột 409
    const canBo = await ApiHeThong.tao(await s.token('canbo'), 'canbo');
    try {
      const cu = await canBo.goi('POST', `tasks/${nv.id}/progress`, {
        ifMatch: `"${nv.rowVersion}"`,
        body: { percent: Math.min(100, moi + 5), note: TIEN_DO.ghiChu },
      });
      expect(cu.status, 'phiên bản cũ phải bị từ chối').toBe(409);
    } finally {
      await canBo.dong();
    }

    // EVT-05 trên Kafka, lọc theo mã nhiệm vụ (khoá `tenant:taskId`)
    if (coKafkaUi()) {
      await choSuKien(TOPIC_EVT05, nv.id, tu);
    } else {
      ghiCho('kiểm EVT-05: chưa đặt E2E_KAFKA_UI_URL (kafka-ui của hạ tầng dev, INF-22)');
    }

    // Giao diện hiện tiến độ mới sau khi tải lại danh sách
    await s.trang('canbo', '/tasks');
    await m.getByRole('searchbox', { name: 'Tìm nhiệm vụ' }).fill(MA_LAN_CHAY);
    await expect(
      list.getByRole('row').filter({ hasText: nv.title }).getByRole('progressbar'),
    ).toHaveAttribute('aria-valuenow', String(moi));

    expect(s.loi, 'không có lỗi console trên trang của ứng dụng').toEqual([]);
  });
});
