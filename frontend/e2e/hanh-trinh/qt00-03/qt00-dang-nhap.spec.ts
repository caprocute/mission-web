import { expect, test } from '@playwright/test';
import { batCaoTuoi, chiHeThong, kiemAxe, xacThucBuocLen } from '../ho-tro/kiem-tra';
import { HE_THONG, cho, ghiCho } from '../ho-tro/moi-truong';
import { Phien, main } from '../ho-tro/phien';
import { api, kiemMoiTruong } from './chung';

/**
 * QT-00 Đăng nhập và chọn ngữ cảnh (SCR-0001, SCR-0002) → `/api/v1/org/me`, `/api/v1/org/me/context`
 * → SVC-04 (org-service), Keycloak realm `mission`. Hành trình HT-01..HT-04 của danh mục QA-01.
 */
interface Ho {
  assignments: { id: string; orgUnitName: string; roles: string[] }[];
}

test.describe('QT-00 Đăng nhập và chọn ngữ cảnh', { tag: '@qa-04' }, () => {
  let s: Phien;

  test.beforeEach(({ browser }) => {
    s = new Phien(browser);
    kiemMoiTruong();
  });

  test.afterEach(async () => {
    const loi = [...s.loi];
    await s.dong();
    expect(loi, 'không có lỗi console hay phản hồi 5xx').toEqual([]);
  });

  test('HT-01 QT-00 cán bộ đăng nhập và mở trang Việc của tôi hôm nay', async () => {
    // AC-06 đăng nhập (Keycloak thật có OTP trên he-thong), vào trang chủ SCR-0002
    const page = await s.trang('canbo', '/');
    const m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: /^Xin chào/ })).toBeVisible();
    const thongTin = m.getByRole('region', { name: 'Thông tin của tôi' });
    await expect(thongTin.getByText('Việc của tôi hôm nay')).toBeVisible();
    await expect(thongTin.getByRole('link', { name: /Xem tất cả việc của tôi/ })).toBeVisible();
    await kiemAxe(page, 'trang chủ');

    // Chế độ Đảng viên cao tuổi (NFR-11): trang chủ vẫn dùng được, không lỗi truy cập
    await batCaoTuoi(page);
    await expect(thongTin.getByText('Việc của tôi hôm nay')).toBeVisible();
    await kiemAxe(page, 'trang chủ, chế độ cao tuổi');

    // Từ trang chủ tới "Việc của tôi" trong một chạm
    await thongTin.getByRole('link', { name: /Xem tất cả việc của tôi/ }).click();
    await expect(m.getByRole('heading', { level: 1, name: 'Việc của tôi' })).toBeVisible();

    await chiHeThong('hồ sơ và phân công đọc qua getMyOrgProfile', async () => {
      const c = await api(s, 'canbo');
      const ho = await c.can<Ho>('GET', 'org/me');
      expect(ho.body.assignments.length).toBeGreaterThan(0);
      await c.dong();
    });
    // Ca phủ định "sai mật khẩu năm lần thì khoá tạm" làm khoá tài khoản thử dùng chung của mọi hành
    // trình, và realm dev đang để ngưỡng mặc định của Keycloak, nên chưa chạy tự động
    ghiCho(
      'ca khoá tạm sau năm lần sai mật khẩu: cần tài khoản thử riêng và ngưỡng khoá năm lần trong realm dev',
    );
  });

  test('HT-02 QT-00 người có nhiều phân công chọn đơn vị và vai trò', async () => {
    // Giả lập: biến thể `kiem-nhiem` cho ba phân công; hệ thống thật: phân công seed của `quanly`
    const page = await s.trang('quanly', HE_THONG ? '/' : '/?mock-org=kiem-nhiem');
    const hop = page.getByRole('dialog', { name: 'Chọn phân công làm việc' });
    const doi = page.getByRole('button', { name: 'Đổi phân công' });
    await expect(hop.or(doi).first()).toBeVisible();
    if (HE_THONG && !(await hop.isVisible()) && !(await doi.isVisible())) {
      cho('tài khoản quanly trên dev chưa có từ hai phân công (seed hồ sơ cán bộ)');
    }
    if (!(await hop.isVisible())) await doi.click();
    await expect(hop).toBeVisible();
    const lua = hop.getByRole('radio');
    expect(await lua.count()).toBeGreaterThan(1);
    // Chọn phân công thứ hai không đòi xác thực nhiều lớp
    const chonDuoc = lua.filter({ hasNotText: 'yêu cầu xác thực nhiều lớp' });
    const thuHai = chonDuoc.nth(1);
    const donVi = await thuHai.evaluate(
      (el) => el.closest('label')?.querySelector('strong')?.textContent?.trim() ?? '',
    );
    await thuHai.check();
    await hop.getByRole('button', { name: 'Vào làm việc' }).click();
    await expect(hop).toBeHidden();
    await expect(page.getByRole('banner').getByText(donVi, { exact: true })).toBeVisible();
    await kiemAxe(page, 'trang chủ sau khi đổi phân công');

    await chiHeThong('setMyContext từ chối phân công không thuộc mình', async () => {
      const c = await api(s, 'quanly');
      const kq = await c.goi('PUT', 'org/me/context', {
        body: { assignmentId: '00000000-0000-7000-8000-000000000000' },
      });
      expect([403, 404, 422]).toContain(kq.status);
      await c.dong();
    });
  });

  test('HT-03 QT-00 vai trò thẩm định bị hỏi OTP, thao tác phê duyệt đòi xác thực lại', async () => {
    if (HE_THONG) {
      // Tài khoản AAL2 (AC-07) đăng nhập: Keycloak phải hỏi mã OTP
      await s.trang('thamdinh', '/');
      expect(s.daHoiOtp.has('thamdinh'), 'Keycloak hỏi OTP khi đăng nhập vai trò thẩm định').toBe(
        true,
      );
    } else {
      // Giả lập: chọn phân công thẩm định (AAL2) thì hiện hộp xác thực nhiều lớp
      const page = await s.trang('quanly', '/?mock-org=kiem-nhiem');
      const hop = page.getByRole('dialog', { name: 'Chọn phân công làm việc' });
      await expect(hop).toBeVisible();
      await hop.getByRole('radio', { name: /yêu cầu xác thực nhiều lớp/ }).check();
      await hop.getByRole('button', { name: 'Vào làm việc' }).click();
      await xacThucBuocLen(
        page,
        'quanly',
        page.getByRole('banner').getByText(/Hội đồng thẩm định/),
        true,
      );
    }

    // Phê duyệt OKR là thao tác AAL2 (SCR-0203): lãnh đạo bấm Phê duyệt thì được mời xác thực lại.
    // Ma trận phân quyền giao phê duyệt cho AC-04 (lãnh đạo), không phải AC-05 như danh mục ghi.
    const page = await s.trang('lanhdao', '/okr/phe-duyet');
    const m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Phê duyệt mục tiêu, OKR' }),
    ).toBeVisible();
    const cho1 = m.getByRole('region', { name: 'Hàng chờ' }).getByRole('button');
    if (HE_THONG && (await cho1.count()) === 0)
      cho('hàng chờ phê duyệt trống trên dev (HT-08 tạo bộ OKR chờ duyệt)');
    await cho1.first().click();
    await kiemAxe(page, 'phê duyệt OKR');
    await m.getByRole('button', { name: 'Phê duyệt', exact: true }).click();
    await xacThucBuocLen(
      page,
      'lanhdao',
      m.getByRole('status').filter({ hasText: /Đã phê duyệt OKR/ }),
      false,
    );
  });

  test('HT-04 QT-00 đăng nhập bằng VNeID mức 2 và liên kết hồ sơ cán bộ', async () => {
    cho(
      'realm dev chưa có nhà cung cấp định danh VNeID giả lập (YC-PT-13), giao diện chưa có lối đăng nhập VNeID',
    );
  });
});
