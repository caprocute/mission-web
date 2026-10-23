import { expect, test } from '@playwright/test';
import { donTheoTienTo, giaoNhiemVu, kyDangMo } from '../ho-tro/du-lieu';
import {
  HE_THONG,
  PhienQt,
  choTrangThai,
  ghiCho,
  kiemSuKien,
  kiemTruyCap,
  main,
  seed,
  thongBao,
  trenHeThong,
} from './chung';

/**
 * HT-17, phần QA-05 bổ sung cho bản mẫu QA-03 (`ht17-cap-nhat-tien-do.spec.ts` vào từ "Việc của tôi",
 * chỉ chạy trên hệ thống thật): cán bộ ở chế độ Đảng viên cao tuổi vào nhiệm vụ từ khối "Việc của tôi
 * hôm nay" trên trang chủ và cập nhật tiến độ trong không quá ba chạm (NFR-11, BR-18-09).
 * Cách đếm: chạm vào nhiệm vụ, chạm "Cập nhật tiến độ", chạm "Lưu N%"; chạm trên thanh tiến độ để chọn
 * phần trăm là bước nhập giá trị, đếm riêng và ghi vào báo cáo.
 * Truy vết: QT-06 → SCR-0002, SCR-0601 → `listMyTasks`, `getTask`, `updateTaskProgress` → MOD-02 (SVC-05)
 * → `task.task_progress`, EVT-05. Ca phủ định: gửi lại cùng `Idempotency-Key` không tạo bản ghi tiến độ
 * thứ hai (baseline mục 13).
 */
const TD = seed<{ ghiChu: string }>('tienDo');

interface NhiemVu {
  id: string;
  title: string;
  progress: number;
  rowVersion: number;
  status: string;
}

test.describe('Tiến độ từ trang chủ', { tag: '@qa-05' }, () => {
  let s: PhienQt;
  let kyId: string | null = null;

  test.afterEach(async () => {
    if (HE_THONG && kyId) {
      try {
        await donTheoTienTo(await s.apiCua('quanly'), kyId);
      } catch {
        // dọn là phần phụ
      }
    }
    const loi = [...s.loi];
    await s.dong();
    expect(loi, 'không có lỗi console trên trang của ứng dụng').toEqual([]);
  });

  test('HT-17 cán bộ cao tuổi cập nhật tiến độ từ trang chủ trong ba chạm', async ({ browser }) => {
    test.setTimeout(HE_THONG ? 300_000 : 120_000);
    PhienQt.batDau();
    s = new PhienQt(browser, ['canbo', 'quanly', 'quantri'], true);
    let ten: string | null = null;
    if (HE_THONG) {
      const ky = await kyDangMo(await s.apiCua('quantri'));
      kyId = ky.id;
      const nv = await giaoNhiemVu(
        await s.apiCua('quanly'),
        await s.danhTinh('quanly'),
        await s.danhTinh('canbo'),
        ky,
      );
      ten = nv.title;
    }

    // SCR-0002 ở chế độ cao tuổi: khối "Việc của tôi hôm nay"
    const page = await s.as('canbo', '/');
    await expect(page.locator('html')).toHaveAttribute('data-mode', 'elderly');
    const khoi = main(page).locator('li').filter({ has: page.getByRole('link') });
    await expect(khoi.first()).toBeVisible();
    await kiemTruyCap(page, 'trang chủ chế độ cao tuổi');
    let link = ten ? page.getByRole('link', { name: ten }) : khoi.first().getByRole('link');
    if (ten && !(await link.isVisible())) {
      // khối chỉ hiện bảy việc gần hạn nhất; việc vừa dựng có thể nằm ngoài, khi đó lấy việc đầu
      ghiCho('nhiệm vụ vừa dựng không nằm trong bảy việc gần hạn nhất của trang chủ; dùng việc đầu khối');
      link = khoi.first().getByRole('link');
    }

    s.batDauDem();
    await s.cham(link); // chạm 1
    const m = main(page);
    const nut = m.getByRole('button', { name: 'Cập nhật tiến độ' });
    await expect(nut, 'người chủ trì thấy nút cập nhật tiến độ').toBeVisible();
    const taskId = new URL(page.url()).pathname.split('/').pop()!;
    await s.cham(nut); // chạm 2
    const hop = page.getByRole('dialog', { name: 'Cập nhật tiến độ' });
    const thanh = hop.getByRole('slider', { name: 'Tiến độ' });
    const truoc = Number(await thanh.getAttribute('aria-valuenow'));
    expect(truoc, 'việc chọn để cập nhật chưa xong').toBeLessThan(90);
    // nhập giá trị: một chạm gần cuối thanh, xa tay nắm hiện tại (đếm riêng, không thuộc ba chạm lệnh)
    const ray = hop.locator('.p-slider');
    await expect(thanh).toBeVisible();
    const o = (await ray.boundingBox())!;
    await ray.click({ position: { x: o.width * 0.95, y: o.height / 2 } });
    const luu = hop.getByRole('button', { name: /^Lưu \d+%$/ });
    await expect(luu).not.toHaveText(`Lưu ${truoc}%`);
    const moi = Number((await luu.innerText()).match(/(\d+)%/)![1]);
    expect(moi, 'phần trăm mới phải lớn hơn trước').toBeGreaterThan(truoc);
    await kiemTruyCap(page, 'hộp thoại cập nhật tiến độ chế độ cao tuổi');
    const tu = new Date(Date.now() - 5_000);
    await s.cham(luu); // chạm 3
    await expect(hop).toBeHidden();
    await expect(thongBao(page, 'Đã lưu tiến độ.')).toBeVisible();
    expect(s.daCham, 'cập nhật tiến độ từ trang chủ không quá ba chạm').toBeLessThanOrEqual(3);
    test.info().annotations.push({
      type: 'so-cham',
      description: `${s.daCham} chạm lệnh (vào nhiệm vụ, mở hộp thoại, lưu) và 1 chạm chọn phần trăm trên thanh tiến độ`,
    });
    await expect(m.getByRole('region', { name: 'Chỉ số chính' }).getByText(`${moi}%`, { exact: true })).toBeVisible();

    // Máy chủ là nguồn sự thật: đọc lại, trạng thái DANG_THUC_HIEN, EVT-05
    await trenHeThong('đọc lại tiến độ và trạng thái nhiệm vụ', async () => {
      const api = await s.apiCua('canbo');
      await choTrangThai(api, `tasks/${taskId}`, 'DANG_THUC_HIEN');
      expect((await api.can<NhiemVu>('GET', `tasks/${taskId}`)).body.progress).toBe(moi);
    });
    await kiemSuKien('EVT-05', taskId, tu);

    // Ca phủ định: gửi lại cùng Idempotency-Key trả kết quả cũ, không ghi thêm lần cập nhật
    await trenHeThong('cùng Idempotency-Key không tạo bản ghi tiến độ trùng', async () => {
      const api = await s.apiCua('canbo');
      const ct = await api.can<NhiemVu>('GET', `tasks/${taskId}`);
      const khoa = crypto.randomUUID();
      const than = { percent: Math.min(100, moi + 5), note: TD.ghiChu };
      const mot = await s.goiTho('canbo', 'POST', `tasks/${taskId}/progress`, {
        body: than,
        ifMatch: ct.etag ?? `"${ct.body.rowVersion}"`,
        khoa,
      });
      expect(mot.status).toBeLessThan(300);
      const hai = await s.goiTho('canbo', 'POST', `tasks/${taskId}/progress`, {
        body: than,
        ifMatch: ct.etag ?? `"${ct.body.rowVersion}"`,
        khoa,
      });
      expect(hai.status, 'lần gửi lại trả kết quả đã lưu, không phải xung đột phiên bản').toBeLessThan(300);
      const sau = await api.can<NhiemVu>('GET', `tasks/${taskId}`);
      expect(sau.body.rowVersion, 'chỉ một lần ghi').toBe(
        (mot.body as NhiemVu).rowVersion ?? ct.body.rowVersion + 1,
      );
    });
  });
});
