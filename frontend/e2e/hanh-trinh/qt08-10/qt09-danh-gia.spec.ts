import { Page, expect, test } from '@playwright/test';
import { batCaoTuoi, chiHeThong, dau } from '../ho-tro/kiem-tra';
import { HE_THONG, cho, ghiCho } from '../ho-tro/moi-truong';
import { Phien, TenTaiKhoan, main } from '../ho-tro/phien';
import { choSuKien, coKafkaUi } from '../ho-tro/su-kien';
import {
  HO_SO_GIA_LAP,
  api,
  bamCoXacThuc,
  canhBao,
  hoSoCuaToi,
  hoSoTheoTrangThai,
  kiemMoiTruong,
  thongBao,
  topic,
  trangThaiHoSo,
  kiemAxe,
} from './chung';

/**
 * QT-09 Tự đánh giá, thẩm định, đề xuất và quyết định xếp loại (SCR-0901..SCR-0904) →
 * `/api/v1/appraisals/**` → MOD-04 trên SVC-05, ký số qua esign-service → EVT-11 mỗi bước hồ sơ,
 * EVT-18 khi ký xong, EVT-12 khi quyết định. Hành trình HT-25, HT-26, HT-27 của danh mục QA-01.
 *
 * Trạng thái hồ sơ theo baseline 9.1: `TU_DANH_GIA` → `CHO_THAM_DINH` → `DA_THAM_DINH` →
 * `CHO_QUYET_DINH` → `DA_KY_SO`; trả lại bổ sung đưa hồ sơ về `TU_DANH_GIA`.
 */

const trangThai = (page: Page) => main(page).getByText(/^Trạng thái:/);

/** Mã hồ sơ đi qua hành trình: giả lập dùng hồ sơ có sẵn, hệ thống thật đọc qua API của `user`. */
async function hoSo(
  s: Phien,
  user: TenTaiKhoan,
  cuaToi: boolean,
  status: string,
  giaLap: string,
): Promise<string> {
  if (!HE_THONG) return giaLap;
  const c = await api(s, user);
  const id = cuaToi ? await hoSoCuaToi(c, status) : await hoSoTheoTrangThai(c, status);
  await c.dong();
  if (!id) cho(`tài khoản ${user} chưa có hồ sơ đánh giá ở trạng thái ${status} trên dev`);
  return id;
}

/** Kiểm trạng thái hồ sơ qua API và EVT-11 của bước vừa xong (chỉ hệ thống thật). */
async function kiemHoSo(s: Phien, user: TenTaiKhoan, id: string, status: string, tu: Date) {
  await chiHeThong(`hồ sơ ${status} qua getAppraisalProfile và EVT-11`, async () => {
    const c = await api(s, user);
    expect(await trangThaiHoSo(c, id)).toBe(status);
    await c.dong();
    if (coKafkaUi()) await choSuKien(topic('EVT-11', 'appraisal.profile.stepcompleted.v1'), id, tu);
    else ghiCho('kiểm EVT-11: chưa đặt E2E_KAFKA_UI_URL');
  });
}

test.describe('QT-09 Tự đánh giá, thẩm định và quyết định xếp loại', { tag: '@qa-06' }, () => {
  let s: Phien;

  test.beforeEach(({ browser }) => {
    s = new Phien(browser, ['canbo', 'thamdinh', 'quanly']);
    kiemMoiTruong();
  });

  test.afterEach(async () => {
    const loi = [...s.loi];
    await s.dong();
    expect(loi, 'không có lỗi console hay phản hồi 5xx').toEqual([]);
  });

  test('HT-25 QT-09 tự đánh giá, bị trả lại, sửa và gửi lại', async () => {
    const tu = new Date();
    const id = await hoSo(s, 'canbo', true, 'TU_DANH_GIA', HO_SO_GIA_LAP.cuaToi);

    // AC-06 lập phiếu tự đánh giá (SCR-0901) và lưu nháp
    let page = await s.trang(
      'canbo',
      HE_THONG ? `/appraisal/tu-danh-gia/${id}` : '/appraisal/tu-danh-gia',
    );
    let m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: /^Phiếu tự đánh giá/ })).toBeVisible();
    await expect(trangThai(page)).toHaveText(/Đang tự đánh giá/);
    const nhanXet = m.getByRole('textbox', { name: /^Ưu điểm, hạn chế, hướng khắc phục/ });
    await nhanXet.fill(`Hoàn thành đúng hạn các nhiệm vụ then chốt (lần thử ${dau()}).`);
    await m.getByRole('button', { name: 'Lưu nháp' }).click();
    await expect(thongBao(page, 'Đã lưu nháp phiếu tự đánh giá')).toBeVisible();

    // Chế độ Đảng viên cao tuổi (NFR-11) rút gọn bước: phiếu tự lưu, không còn nút Lưu nháp
    await batCaoTuoi(page);
    await expect(m.getByRole('button', { name: 'Lưu nháp' })).toHaveCount(0);
    await kiemAxe(page, 'phiếu tự đánh giá, chế độ cao tuổi');

    // Ký số và nộp: mốc ký số đòi xác thực nhiều lớp, hồ sơ sang Chờ thẩm định
    await bamCoXacThuc(
      page,
      'canbo',
      m.getByRole('button', { name: 'Ký số và nộp' }),
      thongBao(page, 'Đã nộp phiếu tự đánh giá và ký số thành công'),
    );
    await expect(trangThai(page)).toHaveText(/Chờ thẩm định/);
    await kiemHoSo(s, 'canbo', id, 'CHO_THAM_DINH', tu);

    // AC-07 trả lại bổ sung (SCR-0902), hồ sơ về Đang tự đánh giá
    page = await s.trang('thamdinh', `/appraisal/tham-dinh/${id}`);
    m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: /^Thẩm định hồ sơ đánh giá/ }),
    ).toBeVisible();
    await m.getByRole('button', { name: 'Trả lại bổ sung' }).click();
    await m
      .getByRole('textbox', { name: /^Lý do trả lại bổ sung/ })
      .fill('Đề nghị bổ sung minh chứng cho nhiệm vụ đề án chuyển đổi số.');
    await m.getByRole('button', { name: 'Xác nhận trả lại' }).click();
    await expect(thongBao(page, /Đã trả lại hồ sơ cho đồng chí .+ bổ sung/)).toBeVisible();
    await kiemAxe(page, 'thẩm định sau khi trả lại');
    await kiemHoSo(s, 'thamdinh', id, 'TU_DANH_GIA', tu);

    // AC-06 sửa phiếu và gửi lại
    page = await s.trang(
      'canbo',
      HE_THONG ? `/appraisal/tu-danh-gia/${id}` : '/appraisal/tu-danh-gia',
    );
    m = main(page);
    await expect(trangThai(page)).toHaveText(/Đang tự đánh giá/);
    await m
      .getByRole('textbox', { name: /^Ưu điểm, hạn chế, hướng khắc phục/ })
      .fill('Đã bổ sung minh chứng theo yêu cầu thẩm định.');
    await bamCoXacThuc(
      page,
      'canbo',
      m.getByRole('button', { name: 'Ký số và nộp' }),
      thongBao(page, 'Đã nộp phiếu tự đánh giá và ký số thành công'),
    );
    await expect(trangThai(page)).toHaveText(/Chờ thẩm định/);
    await kiemAxe(page, 'phiếu tự đánh giá đã gửi lại, chế độ cao tuổi');
    await kiemHoSo(s, 'canbo', id, 'CHO_THAM_DINH', tu);
  });

  test('HT-26 QT-09 thẩm định và đề xuất mức xếp loại', async () => {
    const tu = new Date();
    const id = await hoSo(s, 'thamdinh', false, 'CHO_THAM_DINH', HO_SO_GIA_LAP.choThamDinh);

    // AC-07 kết luận thẩm định và ký số (SCR-0902), hồ sơ sang Đã thẩm định
    let page = await s.trang('thamdinh', `/appraisal/tham-dinh/${id}`);
    let m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: /^Thẩm định hồ sơ đánh giá/ }),
    ).toBeVisible();
    await kiemAxe(page, 'thẩm định hồ sơ');
    await m.getByRole('combobox', { name: /^Mức thẩm định/ }).selectOption('HTTNV');
    await m
      .getByRole('textbox', { name: /^Nhận xét thẩm định/ })
      .fill('Hoàn thành tốt các nhiệm vụ được giao, minh chứng đầy đủ.');
    const lyDo = m.getByRole('textbox', { name: /^Lý do khác mức tự đề xuất/ });
    if (await lyDo.isVisible())
      await lyDo.fill('Mức thẩm định căn cứ kết quả nghiệm thu các nhiệm vụ trong kỳ.');
    await bamCoXacThuc(
      page,
      'thamdinh',
      m.getByRole('button', { name: 'Ký số và chuyển đề xuất' }),
      thongBao(page, /Đã ký số kết quả thẩm định hồ sơ của đồng chí/),
    );
    await kiemHoSo(s, 'thamdinh', id, 'DA_THAM_DINH', tu);

    // Ghi mức đề xuất (SCR-0903). Ma trận 15a giao SCR-0903.de-xuat cho AC-05 quản lý trực tiếp,
    // không phải AC-07 như danh mục ghi; hành trình theo ma trận.
    page = await s.trang('quanly', `/appraisal/ket-qua/${id}`);
    m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: /^Phiếu xếp loại cá nhân/ }),
    ).toBeVisible();
    await m.getByRole('combobox', { name: /^Mức đề xuất/ }).selectOption('HTTNV');
    await m.getByRole('button', { name: 'Ghi mức đề xuất' }).click();
    await expect(
      thongBao(page, /Đã ghi mức đề xuất .+ chuyển tới cấp có thẩm quyền quyết định/),
    ).toBeVisible();
    await kiemAxe(page, 'phiếu xếp loại sau khi ghi mức đề xuất');
    await kiemHoSo(s, 'quanly', id, 'CHO_QUYET_DINH', tu);
  });

  test('HT-27 QT-09 quyết định xếp loại hàng loạt có ký số và xem phiếu có giải thích', async ({
    browser,
  }) => {
    test.setTimeout(HE_THONG ? 10 * 60_000 : 120_000);
    const tu = new Date();
    // Người quyết định, cán bộ và người thẩm định mở trang riêng, mỗi trang chỉ mang vai trò của mình
    await s.dong();
    s = new Phien(browser);

    // Ca phủ định: người thẩm định không có chức năng quyết định (SCR-0904 chỉ AC-08); trên hệ thống
    // thật máy chủ còn từ chối người đã thẩm định quyết định cùng hồ sơ (tách vai trò Phụ lục 4)
    let page = await s.trang('thamdinh', '/appraisal/quyet-dinh');
    await expect(
      main(page).getByRole('heading', { level: 1, name: 'Không có quyền truy cập' }),
    ).toBeVisible();
    await chiHeThong('createAppraisalBatchDecision bởi người thẩm định trả 403', async () => {
      const c = await api(s, 'thamdinh');
      const id = await hoSoTheoTrangThai(c, 'CHO_QUYET_DINH');
      if (!id) {
        await c.dong();
        ghiCho('ca phủ định tách vai trò: chưa có hồ sơ chờ quyết định trên dev');
        return;
      }
      const kq = await c.goi('POST', 'appraisals/batch-decisions', {
        body: {
          decisionNo: `E2E-${dau()}/QĐ`,
          decisionDate: new Date().toISOString().slice(0, 10),
          items: [{ profileId: id, ratingCode: 'HTTNV' }],
        },
      });
      expect(kq.status).toBe(403);
      await c.dong();
    });

    // AC-08 quyết định và ký lô một lần cho cả danh sách (SCR-0904, MFA bước lên)
    page = await s.trang('quyetdinh', '/appraisal/quyet-dinh');
    let m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: /^Quyết định xếp loại hàng loạt/ }),
    ).toBeVisible();
    const kyLo = m.getByRole('button', { name: /^Quyết định và ký lô \(\d+\)/ });
    if (HE_THONG && (await kyLo.isDisabled()))
      cho('chưa có hồ sơ chờ quyết định trên dev (HT-26 đưa hồ sơ tới bước này)');
    await kiemAxe(page, 'quyết định xếp loại hàng loạt');
    const soQd = `E2E-${dau()}/QĐ`;
    await m.getByRole('textbox', { name: 'Số quyết định' }).fill(soQd);
    await bamCoXacThuc(page, 'quyetdinh', kyLo, thongBao(page, /Đã gửi yêu cầu ký lô \d+ hồ sơ/));
    await expect(
      thongBao(page, new RegExp(`Đã hoàn tất quyết định ${soQd}: \\d+ hồ sơ đã ký số`)),
    ).toBeVisible({
      timeout: HE_THONG ? 5 * 60_000 : 60_000,
    });
    await expect(canhBao(page, /không ký được|lỗi/i)).toHaveCount(0);

    // AC-06 xem phiếu xếp loại đã quyết định, có vết tính giải thích điểm (SCR-0903)
    page = await s.trang('canbo', '/appraisal/ket-qua');
    m = main(page);
    await m
      .getByRole('link', { name: /Đã có quyết định/ })
      .first()
      .click();
    await expect(
      m.getByRole('heading', { level: 1, name: /^Phiếu xếp loại cá nhân/ }),
    ).toBeVisible();
    await expect(thongBao(page, /đã được quyết định: /)).toBeVisible();
    await expect(m.getByRole('button', { name: /^Tải Mẫu 1B/ })).toBeVisible();
    await m.getByRole('tab', { name: 'Vết tính' }).click();
    await expect(m.getByRole('tab', { name: 'Vết tính' })).toHaveAttribute('aria-selected', 'true');
    await kiemAxe(page, 'phiếu xếp loại đã quyết định');

    await chiHeThong('hồ sơ DA_KY_SO qua getAppraisalProfile, EVT-12 và EVT-18', async () => {
      const c = await api(s, 'canbo');
      const id = await hoSoCuaToi(c, 'DA_KY_SO');
      await c.dong();
      if (!id) {
        ghiCho('cán bộ thử chưa có hồ sơ đã ký số trên dev');
        return;
      }
      if (!coKafkaUi()) {
        ghiCho('kiểm EVT-12, EVT-18: chưa đặt E2E_KAFKA_UI_URL');
        return;
      }
      await choSuKien(topic('EVT-12', 'appraisal.rating.decided.v1'), id, tu);
      await choSuKien(topic('EVT-18', 'esign.signature.completed.v1'), '', tu);
    });
  });
});
