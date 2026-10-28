import { Page, expect, test } from '@playwright/test';
import { batCaoTuoi, chiHeThong, chon, xacThucBuocLen, kiemAxeOnDinh } from '../ho-tro/kiem-tra';
import { HE_THONG, cho } from '../ho-tro/moi-truong';
import { Phien, main } from '../ho-tro/phien';
import { SEED, api, kiemMoiTruong, kiemSuKien, maTuDuongDan, timHoacCho } from './chung';

/**
 * QT-12 Khiếu nại và giải trình: SCR-1201 (người khiếu nại), SCR-1202 (người xử lý, bên giải trình,
 * người ký trả lời) → `/api/v1/oversight/complaints*` → MOD-05 (SVC-05) → `oversight.complaint`,
 * EVT-25 `oversight.complaint.filed.v1`, EVT-21 `notify.message.requested.v1`. Hành trình HT-32, HT-33.
 *
 * Theo ma trận phân quyền (`wiki/15a`): tiếp nhận, yêu cầu giải trình và kết luận là `appraiser`
 * (AC-07, `thamdinh`), bên giải trình là đơn vị liên quan (`manager`, `quanly`), ký văn bản trả lời là
 * `decider` (AC-08, `quyetdinh`); danh mục QA-01 ghi AC-10 tiếp nhận, lệch ma trận nên hành trình theo
 * ma trận và ghi lệch trong báo cáo. Kho giả lập chọn người xử lý theo bản ghi: KN-2026-0045 giao cho
 * người dùng hiện tại, KN-2026-0044 có yêu cầu giải trình gửi người dùng hiện tại, KN-2026-0040 chờ kết
 * luận, KN-2026-0042 do chính người dùng hiện tại đã thẩm định (ca tách vai trò).
 */
const HO_SO_CON_HAN = '01928f3e-7b1a-7c3e-9a51-3f2d9c1d1304';
const HO_SO_QUA_HAN = '01928f3e-7b1a-7c3e-9a51-3f2d9c1d1313';

interface HoSo {
  profileId: string;
  workingDaysLeft: number;
  allowedKinds: string[];
}
interface KhieuNai {
  id: string;
  code: string;
  status: string;
}

const moKhieuNai = async (page: Page, ma: string) => {
  const m = main(page);
  const ds = m.getByRole('region', { name: 'Khiếu nại', exact: true });
  await ds.getByRole('button', { name: 'Trong phạm vi' }).click();
  await ds.getByRole('link', { name: new RegExp(`^${ma} ·`) }).click();
  await expect(m.getByRole('heading', { level: 2, name: new RegExp(`^${ma} `) })).toBeVisible();
};

test.describe('QT-12 Khiếu nại và giải trình', { tag: '@qa-07' }, () => {
  let s: Phien;

  test.beforeEach(({ browser }) => {
    s = new Phien(
      browser,
      HE_THONG
        ? ['canbo', 'thamdinh', 'thamdinh2', 'quanly', 'quyetdinh', 'quyetdinh2']
        : ['canbo', 'thamdinh', 'quanly', 'quyetdinh'],
    );
    kiemMoiTruong();
  });

  test.afterEach(async () => {
    const loi = [...s.loi];
    await s.dong();
    expect(loi, 'không có lỗi console hay phản hồi 5xx').toEqual([]);
  });

  test('HT-32 QT-12 cán bộ ký số gửi khiếu nại kết quả xếp loại, quá hạn thì không gửi được', async () => {
    test.setTimeout(HE_THONG ? 600_000 : 60_000);
    let conHan = HO_SO_CON_HAN;
    let quaHan: string | null = HO_SO_QUA_HAN;
    if (HE_THONG) {
      const a = await api(s, 'canbo');
      try {
        conHan = (
          await timHoacCho<HoSo>(
            a,
            'oversight/complaints/eligible-profiles',
            {},
            (x) => x.allowedKinds.includes('KHIEU_NAI'),
            'hồ sơ của canbo còn hạn khiếu nại',
          )
        ).profileId;
        const r = await a.goi<{ items: HoSo[] }>('GET', 'oversight/complaints/eligible-profiles');
        quaHan = r.body.items.find((x) => !x.allowedKinds.includes('KHIEU_NAI'))?.profileId ?? null;
      } finally {
        await a.dong();
      }
    }

    // AC-06 mở "Khiếu nại, giải trình của tôi" ở chế độ Đảng viên cao tuổi (`listComplainableProfiles`)
    const page = await s.trang('canbo', '/oversight/my-complaints');
    await batCaoTuoi(page);
    const m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Khiếu nại, giải trình của tôi' }),
    ).toBeVisible();
    const kq = m.getByRole('region', { name: 'Kết quả có thể khiếu nại' });
    await expect(kq).toBeVisible();
    await kiemAxeOnDinh(page, 'khiếu nại của tôi, chế độ cao tuổi');

    // Ca phủ định: hồ sơ quá hạn chỉ còn gửi ý kiến bảo lưu, không còn lối soạn khiếu nại
    if (quaHan) {
      await expect(kq.getByText(/Đã quá thời hạn khiếu nại/).first()).toBeVisible();
      await expect(kq.getByRole('link', { name: 'Gửi ý kiến bảo lưu' }).first()).toBeVisible();
    }

    // Soạn khiếu nại (`createComplaint`), chọn cấu phần, ký số và gửi (`submitComplaint`, xác thực nhiều lớp)
    await s.trang('canbo', `/oversight/my-complaints/new?profileId=${conHan}`);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Gửi khiếu nại kết quả đánh giá' }),
    ).toBeVisible();
    await m
      .getByRole('group', { name: 'Hình thức (bắt buộc)' })
      .getByRole('button', { name: 'Khiếu nại', exact: true })
      .click();
    await m
      .getByRole('list', { name: /^Cấu phần bị khiếu nại/ })
      .getByRole('button')
      .nth(1)
      .click();
    await m.getByRole('textbox', { name: /^Lý do, căn cứ/ }).fill(SEED.khieuNai.lyDo);
    await m.getByRole('textbox', { name: /^Đề nghị cụ thể/ }).fill(SEED.khieuNai.deNghi);
    await kiemAxeOnDinh(page, 'soạn khiếu nại, chế độ cao tuổi');
    const tu = new Date(Date.now() - 5_000);
    await m.getByRole('button', { name: 'Ký số và gửi' }).click();
    const daGui = m.getByRole('status').filter({ hasText: /^Đã gửi khiếu nại KN-\d{4}-\d{4}\./ });
    await xacThucBuocLen(page, 'canbo', daGui, !HE_THONG);
    await expect(daGui).toContainText('Kết quả hiện hành vẫn có hiệu lực');
    await expect(m.getByRole('heading', { level: 1, name: /^Khiếu nại KN-/ })).toBeVisible();
    await expect(m.getByText('Mới, chờ tiếp nhận').first()).toBeVisible();
    const id = maTuDuongDan(page.url());

    await chiHeThong(
      'đọc lại khiếu nại và hồ sơ đánh giá qua API, gửi quá hạn bị từ chối',
      async () => {
        const a = await api(s, 'canbo');
        try {
          const kn = await a.can<KhieuNai>('GET', `oversight/complaints/${id}`);
          expect(kn.body.status).not.toBe('NHAP');
          const hs = await a.goi<{ status?: string }>('GET', `appraisals/profiles/${conHan}`);
          if (hs.status === 200) expect(hs.body.status).toBe('KHIEU_NAI');
          if (quaHan) {
            const tre = await a.goi('POST', 'oversight/complaints', {
              body: {
                profileId: quaHan,
                kind: 'KHIEU_NAI',
                targetStage: 'QUYET_DINH',
                content: SEED.khieuNai.lyDo,
                requestText: SEED.khieuNai.deNghi,
              },
            });
            expect(tre.status, 'khiếu nại quá hạn phải bị từ chối').toBe(422);
          }
        } finally {
          await a.dong();
        }
      },
    );
    await kiemSuKien('EVT-25', 'oversight.complaint.filed.v1', id, tu);
  });

  test('HT-33 QT-12 tiếp nhận, yêu cầu giải trình, kết luận và ký văn bản trả lời', async () => {
    test.setTimeout(HE_THONG ? 600_000 : 60_000);
    // Giả lập: mỗi bước một bản ghi dựng sẵn (KN-2026-0042 tách vai trò, 0045 tiếp nhận, 0044 giải trình, 0040 kết
    // luận). Hệ thống thật: cả chuỗi chạy trên khiếu nại MOI về hồ sơ của canbo (HT-32 tạo); người xử lý là
    // `thamdinh2` vì `thamdinh` đã thẩm định chính hồ sơ đó (tách vai trò), người ký trả lời là `quyetdinh2` vì
    // `quyetdinh` đã quyết định hồ sơ đó (bốn mắt)
    const xuLy = HE_THONG ? 'thamdinh2' : 'thamdinh';
    const kyTraLoi = HE_THONG ? 'quyetdinh2' : 'quyetdinh';
    let maTachVaiTro = 'KN-2026-0042';
    let maTiepNhan = 'KN-2026-0045';
    let maGiaiTrinh = 'KN-2026-0044';
    let maKetLuan = 'KN-2026-0040';
    if (HE_THONG) {
      const a = await api(s, xuLy);
      try {
        const kn = await timHoacCho<KhieuNai>(
          a,
          'oversight/complaints',
          { status: 'MOI' },
          (x) => x.status === 'MOI',
          `khiếu nại mới trong phạm vi ${xuLy} (HT-32 tạo)`,
        );
        maTachVaiTro = maTiepNhan = maGiaiTrinh = maKetLuan = kn.code;
      } finally {
        await a.dong();
      }
    }

    // Ca phủ định tách vai trò: người đã thẩm định hồ sơ không được xử lý khiếu nại về hồ sơ đó
    let page = await s.trang('thamdinh', '/oversight/complaints');
    let m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: 'Xử lý khiếu nại' })).toBeVisible();
    await kiemAxeOnDinh(page, 'xử lý khiếu nại');
    if (HE_THONG) {
      await chiHeThong(
        'người đã thẩm định hồ sơ không tiếp nhận được khiếu nại về hồ sơ đó',
        async () => {
          const a = await api(s, 'thamdinh');
          try {
            const ds = await a.can<{ items: KhieuNai[] }>('GET', 'oversight/complaints', {
              query: { status: 'MOI', limit: '200' },
            });
            const kn = ds.body.items.find((x) => x.code === maTachVaiTro);
            if (kn) {
              const r = await a.goi('POST', `oversight/complaints/${kn.id}/accept`, { body: {} });
              expect([403, 409, 422], 'người đã thẩm định không được tiếp nhận').toContain(
                r.status,
              );
            }
          } finally {
            await a.dong();
          }
        },
      );
    } else {
      await moKhieuNai(page, maTachVaiTro);
      await m.getByRole('button', { name: 'Tiếp nhận' }).click();
      await expect(
        m
          .getByRole('alert')
          .filter({ hasText: /không thể xử lý khiếu nại này vì đã tham gia thẩm định/ }),
      ).toBeVisible();
    }

    // AC-07 tiếp nhận khiếu nại được giao (`acceptComplaint`)
    if (HE_THONG) {
      page = await s.trang(xuLy, '/oversight/complaints');
      m = main(page);
    }
    await moKhieuNai(page, maTiepNhan);
    await m.getByRole('button', { name: 'Tiếp nhận' }).click();
    await expect(
      m.getByRole('status').filter({ hasText: `Đã tiếp nhận khiếu nại ${maTiepNhan}.` }),
    ).toBeVisible();

    // Yêu cầu đơn vị liên quan giải trình (`requestComplaintExplanations`); trên hệ thống thật chọn quản lý
    // đơn vị của cán bộ (`quanly`) nếu có trong danh sách để bước sau đăng nhập đúng bên giải trình
    await expect(m.getByRole('combobox', { name: /^Bên được yêu cầu giải trình/ })).toBeAttached();
    await m.getByText('Chọn người hoặc đơn vị').click();
    const benGiaiTrinh = page.getByRole('listbox').getByRole('option');
    const quanLy = benGiaiTrinh.filter({ hasText: /Quản Lý/ });
    await ((await quanLy.count()) ? quanLy : benGiaiTrinh).first().click();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('listbox')).toBeHidden();
    await m.getByRole('textbox', { name: 'Nội dung yêu cầu' }).fill(SEED.khieuNai.yeuCauGiaiTrinh);
    await m.getByRole('button', { name: 'Gửi yêu cầu giải trình' }).click();
    await expect(
      m.getByRole('status').filter({ hasText: /^Đã gửi yêu cầu giải trình cho \d+ bên/ }),
    ).toBeVisible();

    // Bên giải trình gửi nội dung giải trình (`submitComplaintExplanation`)
    page = await s.trang('quanly', '/oversight/complaints');
    m = main(page);
    await moKhieuNai(page, maGiaiTrinh);
    await m
      .getByRole('textbox', { name: 'Nội dung giải trình của đồng chí' })
      .fill(SEED.khieuNai.giaiTrinh);
    await m.getByRole('button', { name: 'Gửi giải trình' }).click();
    await expect(
      m.getByRole('status').filter({ hasText: 'Đã gửi giải trình; người xử lý được thông báo.' }),
    ).toBeVisible();

    // Người xử lý ghi kết luận cho khiếu nại đã đủ giải trình (`saveComplaintConclusion`)
    page = await s.trang(xuLy, '/oversight/complaints');
    m = main(page);
    await moKhieuNai(page, maKetLuan);
    await chon(
      page,
      m.getByRole('combobox', { name: 'Kết quả xử lý (bắt buộc)' }),
      'Giữ nguyên kết quả',
    );
    await m.getByRole('textbox', { name: 'Nội dung văn bản trả lời' }).fill(SEED.ketLuan.noiDung);
    await m.getByRole('textbox', { name: 'Số văn bản trả lời' }).fill(SEED.ketLuan.soVanBan);
    await m.getByRole('button', { name: /^(Ghi|Cập nhật) kết luận$/ }).click();
    await expect(
      m
        .getByRole('status')
        .filter({ hasText: new RegExp(`^Đã ghi kết luận cho khiếu nại ${maKetLuan}`) }),
    ).toBeVisible();

    // Người có thẩm quyền ký số văn bản trả lời (`respondComplaint`, xác thực nhiều lớp)
    page = await s.trang(kyTraLoi, '/oversight/complaints');
    m = main(page);
    await moKhieuNai(page, maKetLuan);
    await m.getByRole('button', { name: 'Ký số và trả lời' }).click();
    await xacThucBuocLen(
      page,
      kyTraLoi,
      m
        .getByRole('status')
        .filter({ hasText: /^Đã ký số văn bản trả lời .* và gửi người khiếu nại\./ }),
      !HE_THONG,
    );
    await expect(m.getByText('Đã trả lời').first()).toBeVisible({
      timeout: HE_THONG ? 30_000 : 5_000,
    });
    await kiemSuKien('EVT-21', 'notify.message.requested.v1', maKetLuan, new Date());
  });
});
