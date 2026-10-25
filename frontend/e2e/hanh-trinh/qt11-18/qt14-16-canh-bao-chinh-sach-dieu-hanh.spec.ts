import { Page, expect, test } from '@playwright/test';
import { batCaoTuoi, chiHeThong, chon, xacThucBuocLen, kiemAxeOnDinh } from '../ho-tro/kiem-tra';
import { HE_THONG } from '../ho-tro/moi-truong';
import { Phien, main, spaGoto } from '../ho-tro/phien';
import { SEED, api, kiemMoiTruong, kiemSuKien, timHoacCho } from './chung';

/**
 * QT-14 Cảnh báo bất thường (SCR-1401), QT-15 Policy Pack và luồng duyệt động (SCR-1501..SCR-1504),
 * QT-16 Bảng điều hành và đi xuống tới minh chứng (SCR-1601..SCR-1603) → `/api/v1/oversight/alerts*`,
 * `/api/v1/policies/*`, `/api/v1/analytics/*` → MOD-05, MOD-06 (SVC-05), SVC-12 analytics →
 * `oversight.anomaly_alert`, `policy.policy_pack_version`, EVT-20 `analytics.anomaly.detected.v1`,
 * EVT-13 `policy.pack.published.v1`. Hành trình HT-36 đến HT-39.
 *
 * Giao diện chưa có lối tạo phiên bản Policy Pack mới (`createPolicyPackVersion` chỉ có trong hợp đồng),
 * nên HT-37 soạn trên phiên bản nháp đang có; ở chế độ giả lập là bản nháp 2.1 của gói khối Đảng.
 */
const GOI_DANG = '0192a1b0-3c4d-7e5f-8a9b-000000004001';
const CANH_BAO_MOI = 'cb-2026-0118';

interface CanhBao {
  id: string;
  status: string;
  signalCode: string;
}
interface Goi {
  id: string;
  name: string;
  versions?: { version: string; status: string }[];
}

/** Gói và phiên bản nháp để soạn: giả lập dùng bản khởi tạo, hệ thống thật tìm qua `listPolicyPacks`. */
async function banNhap(s: Phien): Promise<{ goi: string; ban: string; nhan: string }> {
  if (!HE_THONG) return { goi: GOI_DANG, ban: '2.1.0', nhan: '2.1' };
  const a = await api(s, 'chinhsach');
  try {
    const g = await timHoacCho<Goi>(
      a,
      'policies/packs',
      {},
      (x) => (x.versions ?? []).some((v) => v.status === 'NHAP'),
      'gói chính sách có phiên bản nháp',
    );
    const v = g.versions!.find((x) => x.status === 'NHAP')!.version;
    return { goi: g.id, ban: v, nhan: v.replace(/\.0$/, '') };
  } finally {
    await a.dong();
  }
}

/** Kết quả kiểm thử gần nhất ở đầu trang phiên bản ("Đạt 10/10 ca", "Chưa đạt 9/10 ca"). */
const ketQuaKiemThu = (page: Page) =>
  page.locator('strong').filter({ hasText: /(Đạt|Chưa đạt) \d+\/\d+ ca/ });

/** Chạy toàn bộ ca vàng (`runPolicyTests`) và chờ lần chạy kết thúc với kết quả `mong`. */
async function chayKiemThu(page: Page, mong: RegExp) {
  const m = main(page);
  await m.getByRole('button', { name: 'Chạy kiểm thử' }).click();
  await expect(ketQuaKiemThu(page)).toHaveText(mong, { timeout: 30_000 });
}

test.describe('QT-14 QT-15 QT-16 Cảnh báo, chính sách và bảng điều hành', { tag: '@qa-07' }, () => {
  let s: Phien;

  test.beforeEach(({ browser }) => {
    s = new Phien(browser, ['kiemtra', 'chinhsach', 'quyetdinh', 'lanhdao', 'quanly']);
    kiemMoiTruong();
  });

  test.afterEach(async () => {
    const loi = [...s.loi];
    await s.dong();
    expect(loi, 'không có lỗi console hay phản hồi 5xx').toEqual([]);
  });

  test('HT-36 QT-14 xem cảnh báo bất thường, giao kiểm tra và ghi kết luận', async () => {
    test.setTimeout(HE_THONG ? 600_000 : 60_000);
    let id = CANH_BAO_MOI;
    if (HE_THONG) {
      const a = await api(s, 'kiemtra');
      try {
        id = (
          await timHoacCho<CanhBao>(
            a,
            'oversight/alerts',
            {},
            (x) => ['MOI', 'DANG_XEM'].includes(x.status),
            'cảnh báo bất thường đang mở (EVT-20 từ dữ liệu khởi tạo)',
          )
        ).id;
      } finally {
        await a.dong();
      }
    }

    // AC-10 mở trung tâm cảnh báo ở chế độ Đảng viên cao tuổi (`listAnomalyAlerts`)
    const page = await s.trang('kiemtra', '/oversight/alerts');
    await batCaoTuoi(page);
    const m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Trung tâm cảnh báo bất thường' }),
    ).toBeVisible();
    await expect(m.getByRole('region', { name: 'Cảnh báo' })).toBeVisible();
    await kiemAxeOnDinh(page, 'trung tâm cảnh báo, chế độ cao tuổi');

    // Mở chi tiết (`getAnomalyAlert`): chuỗi truy vết bằng chứng của cảnh báo
    await s.trang('kiemtra', `/oversight/alerts/${id}`);
    await expect(m.getByRole('list', { name: 'Chuỗi truy vết bằng chứng' })).toBeVisible();
    const xuLy = m.getByRole('region', { name: 'Xử lý' });

    // Giao cơ quan kiểm tra xác minh (`createAlertAction` loại giao kiểm tra)
    await xuLy
      .getByRole('textbox', { name: 'Ghi chú xử lý (bắt buộc)' })
      .fill(SEED.canhBao.ghiChuGiao);
    await chon(
      page,
      xuLy.getByRole('combobox', { name: /^(Cán bộ cơ quan kiểm tra|Chọn cán bộ)/ }),
    );
    await xuLy.getByRole('button', { name: 'Giao kiểm tra' }).click();
    await expect(
      m.getByRole('status').filter({ hasText: /^Đã giao kiểm tra cho đồng chí/ }),
    ).toBeVisible();

    // Ghi kết luận kiểm tra (`createAlertAction` loại kết luận)
    await xuLy
      .getByRole('textbox', { name: 'Ghi chú xử lý (bắt buộc)' })
      .fill(SEED.canhBao.ghiChuKetLuan);
    await xuLy
      .getByRole('group', { name: 'Kết luận kiểm tra (bắt buộc)' })
      .getByText('Không vi phạm')
      .click();
    await xuLy.getByRole('button', { name: 'Ghi kết luận' }).click();
    await expect(
      m.getByRole('status').filter({ hasText: 'Đã ghi kết luận: không vi phạm.' }),
    ).toBeVisible();
    await kiemAxeOnDinh(page, 'cảnh báo đã kết luận, chế độ cao tuổi');

    await chiHeThong('đọc lại trạng thái cảnh báo qua API', async () => {
      const a = await api(s, 'kiemtra');
      try {
        expect((await a.can<CanhBao>('GET', `oversight/alerts/${id}`)).body.status).toBe(
          'KHONG_VI_PHAM',
        );
      } finally {
        await a.dong();
      }
    });
    // EVT-20 do job ClickHouse phát khi tạo cảnh báo từ dữ liệu khởi tạo; kiểm có bản tin cho cảnh báo
    await kiemSuKien(
      'EVT-20',
      'analytics.anomaly.detected.v1',
      id,
      new Date(Date.now() - 86_400_000),
    );
  });

  test('HT-37 QT-15 soạn phiên bản Policy Pack, chạy ca vàng và phát hành', async () => {
    test.setTimeout(HE_THONG ? 600_000 : 90_000);
    const { goi, ban, nhan } = await banNhap(s);
    const goc = `/config-studio/goi/${goi}/${ban}`;

    // AC-03 mở danh mục ở chế độ Đảng viên cao tuổi, vào phiên bản nháp
    let page = await s.trang('chinhsach', '/config-studio');
    await batCaoTuoi(page);
    let m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Chính sách đánh giá', exact: true }),
    ).toBeVisible();
    await kiemAxeOnDinh(page, 'danh mục chính sách, chế độ cao tuổi');
    await m
      .getByRole('link', { name: `Mở để soạn phiên bản ${nhan}` })
      .first()
      .click();
    await expect(
      m.getByText(new RegExp(`Phiên bản ${nhan.replace('.', '\\.')} · Bản nháp`)),
    ).toBeVisible();

    // Sửa bảng quyết định (`saveDecisionTable`)
    await m.getByRole('link', { name: 'Bảng quyết định' }).click();
    await m
      .getByRole('textbox', { name: 'Dòng 1, lời giải thích' })
      .fill('Đạt mức cao nhất khi tổng điểm và các điều kiện đều đạt');
    await m.getByRole('button', { name: 'Lưu nháp' }).click();
    await expect(m.getByText('Đã lưu nháp.')).toBeVisible();

    // Sửa quy tắc tính chậm (`savePolicyRule`) làm một ca vàng sai: chưa đạt 100% thì không gửi duyệt được
    await m.getByRole('link', { name: 'Công thức' }).click();
    const loaiNgay = m.getByRole('combobox', { name: 'Loại ngày tính chậm' });
    await loaiNgay.selectOption({ label: 'ngày lịch' });
    await m.getByRole('button', { name: 'Lưu nháp' }).click();
    await expect(
      m.getByText(/(Đã lưu nháp\.|Phiên bản đã thay đổi sau lần kiểm thử)/),
    ).toBeVisible();
    await chayKiemThu(page, /Chưa đạt \d+\/\d+ ca/);
    await expect(m.getByRole('button', { name: 'Gửi phê duyệt phát hành' })).toBeDisabled();
    await chiHeThong('gửi duyệt khi ca vàng chưa đạt bị máy chủ từ chối', async () => {
      const a = await api(s, 'chinhsach');
      try {
        const r = await a.goi('POST', `policies/packs/${goi}/versions/${ban}/submit`, {
          body: { effectiveFromPeriodId: 'khong-ap-dung' },
        });
        expect([409, 422]).toContain(r.status);
      } finally {
        await a.dong();
      }
    });

    // Trả quy tắc về đúng, chạy lại ca vàng (`runPolicyTests`) đạt toàn bộ. Mở lại thẻ Công thức để màn
    // hình đọc lại quy tắc cùng phiên bản dữ liệu mới sau lần kiểm thử, như người dùng chuyển thẻ
    await m.getByRole('link', { name: 'Test case' }).click();
    await m.getByRole('link', { name: 'Công thức' }).click();
    await expect(loaiNgay).toHaveValue(/.+/);
    await loaiNgay.selectOption({ label: 'ngày làm việc theo lịch của đơn vị' });
    await m.getByRole('button', { name: 'Lưu nháp' }).click();
    await expect(
      m.getByText(/(Đã lưu nháp\.|Phiên bản đã thay đổi sau lần kiểm thử)/),
    ).toBeVisible();
    await chayKiemThu(page, /^\s*Đạt (\d+)\/\1 ca/);

    // Gửi phê duyệt phát hành (`submitPolicyPackVersion`)
    await m.getByRole('button', { name: 'Gửi phê duyệt phát hành' }).click();
    const gui = page.getByRole('dialog', { name: 'Gửi phê duyệt phát hành' });
    await gui
      .getByRole('combobox', { name: 'Kỳ đánh giá bắt đầu áp dụng' })
      .selectOption({ index: 1 });
    await gui.getByRole('button', { name: 'Gửi phê duyệt' }).click();
    await expect(m.getByText(/Đã gửi phê duyệt phát hành\./)).toBeVisible();

    // AC-08 ký số phê duyệt phát hành (`approvePolicyPackVersion`, xác thực nhiều lớp) → EVT-13
    const tu = new Date(Date.now() - 5_000);
    page = await s.trang('quyetdinh', `${goc}/test-case`);
    m = main(page);
    await expect(m.getByTestId('cases')).toBeVisible();
    await kiemAxeOnDinh(page, 'thẻ Test case của phiên bản chờ phê duyệt');
    await m.getByRole('button', { name: 'Ký số phê duyệt' }).click();
    const ky = page.getByRole('dialog', { name: 'Ký số phê duyệt phát hành' });
    await ky.getByRole('textbox', { name: 'Ý kiến phê duyệt' }).fill(SEED.chinhSach.yKienPheDuyet);
    await ky.getByRole('button', { name: 'Ký số phê duyệt' }).click();
    await xacThucBuocLen(
      page,
      'quyetdinh',
      m.getByText(/đã được phát hành, áp dụng từ kỳ/),
      !HE_THONG,
    );

    await chiHeThong('đọc lại phiên bản ở trạng thái đã phát hành', async () => {
      const a = await api(s, 'chinhsach');
      try {
        const v = await a.can<{ status: string }>('GET', `policies/packs/${goi}/versions/${ban}`);
        expect(v.body.status).toBe('DA_PHAT_HANH');
      } finally {
        await a.dong();
      }
    });
    await kiemSuKien('EVT-13', 'policy.pack.published.v1', goi, tu);
  });

  test('HT-38 QT-15 cấu hình luồng duyệt từ mẫu và mô phỏng ràng buộc', async () => {
    test.setTimeout(HE_THONG ? 600_000 : 60_000);
    const { goi, ban } = await banNhap(s);

    // AC-03 chọn mẫu luồng (`listPolicyWorkflowTemplates`), mô phỏng (`simulateWorkflowBinding`) rồi lưu
    const page = await s.trang('chinhsach', `/config-studio/goi/${goi}/${ban}/luong-duyet`);
    const m = main(page);
    const mau = m.getByRole('radiogroup', { name: 'Chọn mẫu luồng' });
    await expect(mau).toBeVisible();
    await mau.getByRole('radio').first().check();
    await kiemAxeOnDinh(page, 'cấu hình luồng duyệt');
    await m.getByRole('button', { name: 'Mô phỏng luồng' }).click();
    await expect(m.getByText(/^Kiểm tra cấu trúc luồng: đạt/)).toBeVisible({ timeout: 30_000 });
    await m.getByRole('button', { name: 'Lưu tham số' }).click();
    await expect(m.getByText(/^Đã lưu tham số luồng duyệt\./)).toBeVisible();
  });

  test('HT-39 QT-16 bảng điều hành, dựng dashboard và đi xuống tới minh chứng', async () => {
    test.setTimeout(HE_THONG ? 600_000 : 60_000);

    // AC-04 xem bảng điều hành ở chế độ Đảng viên cao tuổi (`queryAnalytics`)
    let page = await s.trang('lanhdao', '/dashboard');
    await batCaoTuoi(page);
    let m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: 'Bảng điều hành' })).toBeVisible();
    await expect(m.getByRole('region', { name: 'Bốn chỉ số chính' })).toBeVisible();
    await kiemAxeOnDinh(page, 'bảng điều hành, chế độ cao tuổi');

    // Đi xuống đơn vị, cán bộ rồi tới minh chứng (`getTraceChain`): không quá ba lần chạm từ bảng điều hành
    const bang = m
      .getByRole('table')
      .filter({ has: page.getByRole('link') })
      .first();
    await bang.getByRole('link').first().click();
    await expect(m.getByRole('heading', { level: 1 })).toBeVisible();
    await m
      .getByRole('link', { name: /^Xem chuỗi truy vết của / })
      .first()
      .click();
    await expect(
      m.getByRole('heading', { level: 1, name: 'Truy vết kết quả đánh giá' }),
    ).toBeVisible();
    const chuoi = m.getByRole('list', { name: 'Chuỗi truy vết' });
    await expect(chuoi.getByRole('listitem').first()).toBeVisible();
    await expect(chuoi.getByRole('link').first()).toBeVisible();
    await kiemAxeOnDinh(page, 'chuỗi truy vết tới minh chứng, chế độ cao tuổi');

    // AC-05 dựng dashboard riêng (`createDashboard`) rồi mở lại (`getDashboard`)
    page = await s.trang('quanly', '/dashboard/dashboards');
    m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: 'Dashboard của tôi' })).toBeVisible();
    await m.getByRole('link', { name: 'Tạo mới' }).click();
    await expect(m.getByRole('heading', { level: 1, name: 'Tạo dashboard' })).toBeVisible();
    await m.getByRole('textbox', { name: 'Tên dashboard' }).fill(SEED.dashboard.ten);
    await m
      .getByRole('button', { name: /^Thêm biểu đồ / })
      .first()
      .click();
    await m.getByRole('button', { name: 'Lưu dashboard' }).click();
    await expect(m.getByRole('heading', { level: 1, name: 'Sửa dashboard' })).toBeVisible();
    const id = new URL(page.url()).pathname.split('/').at(-2)!;
    await spaGoto(page, `/dashboard/dashboards/${id}`);
    await expect(m.getByRole('heading', { level: 1, name: SEED.dashboard.ten })).toBeVisible();

    await chiHeThong('đọc lại dashboard qua API', async () => {
      const a = await api(s, 'quanly');
      try {
        const d = await a.can<{ title?: string; name?: string }>(
          'GET',
          `analytics/dashboards/${id}`,
        );
        expect(d.body.title ?? d.body.name).toBe(SEED.dashboard.ten);
      } finally {
        await a.dong();
      }
    });
  });
});
