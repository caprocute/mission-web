import { Page, expect, test } from '@playwright/test';
import { ApiHeThong } from '../ho-tro/api';
import { batCaoTuoi, chiHeThong, xacThucBuocLen } from '../ho-tro/kiem-tra';
import { HE_THONG } from '../ho-tro/moi-truong';
import { Phien, TenTaiKhoan, main } from '../ho-tro/phien';
import { kiemAxeOnDinh, SEED, api, kiemMoiTruong, kiemSuKien, timHoacCho } from './chung';

/**
 * QT-11 Kiểm điểm và hồ sơ số: SCR-1101 Hội nghị kiểm điểm, SCR-1102 Gói hồ sơ số và ký số →
 * `/api/v1/dossiers/*` → MOD-04 (SVC-05), SVC-14 docgen, SVC-15 esign → `dossier.meeting`,
 * `dossier.package`, EVT-17 `dossier.form.generated.v1`, EVT-18 `esign.signature.completed.v1`.
 * Hành trình HT-30, HT-31.
 *
 * Kho giả lập gán vai trò hội nghị theo bản ghi khởi tạo cho một người dùng duy nhất (thư ký ở hội nghị
 * M1, chủ trì ở M3, thành viên ở M4, người được kiểm điểm ở M2), còn hội nghị mới lập chưa có vai trò
 * nào; nên hành trình lập hội nghị mới rồi đi tiếp các bước trên hội nghị khởi tạo đúng vai trò. Trên
 * hệ thống thật, hội nghị của từng bước tìm qua `listDossierMeetings` theo vai trò của tài khoản thử.
 */
const HN = (n: number) => `0192a3f4-1101-7000-8000-00000000000${n}`;
const GOI_DU_DIEU_KIEN = '0192a3f4-1102-7000-8000-000000000002';
const GOI_CON_LOI = '0192a3f4-1102-7000-8000-000000000001';

interface HoiNghi {
  id: string;
  status: string;
  myRole?: string;
}
interface Goi {
  id: string;
  code: string;
  status: string;
}

/** Hội nghị mà `user` giữ vai trò `vaiTro` ở trạng thái `trangThai` (he-thong) hoặc bản ghi khởi tạo. */
async function hoiNghi(
  s: Phien,
  user: TenTaiKhoan,
  vaiTro: string,
  trangThai: string[],
  giaLap: number,
): Promise<string> {
  if (!HE_THONG) return HN(giaLap);
  const a = await api(s, user);
  try {
    const h = await timHoacCho<HoiNghi>(
      a,
      'dossiers/meetings',
      {},
      (x) => x.myRole === vaiTro && trangThai.includes(x.status),
      `hội nghị có ${user} làm ${vaiTro}`,
    );
    return h.id;
  } finally {
    await a.dong();
  }
}

async function moHoiNghi(page: Page, s: Phien, user: TenTaiKhoan, id: string) {
  await s.trang(user, `/dossier/kiem-diem/${id}`);
  await expect(main(page).getByRole('heading', { level: 1 })).toBeVisible();
}

test.describe('QT-11 Hội nghị kiểm điểm và hồ sơ số', { tag: '@qa-07' }, () => {
  let s: Phien;

  test.beforeEach(({ browser }) => {
    s = new Phien(browser, ['thamdinh', 'canbo', 'lanhdao', 'quyetdinh']);
    kiemMoiTruong();
  });

  test.afterEach(async () => {
    const loi = [...s.loi];
    await s.dong();
    expect(loi, 'không có lỗi console hay phản hồi 5xx').toEqual([]);
  });

  test('HT-30 QT-11 lập hội nghị, góp ý dự thảo, lập biên bản và kết thúc hội nghị', async () => {
    test.setTimeout(HE_THONG ? 600_000 : 60_000);

    // Người chuẩn bị hồ sơ (thẩm định) lập hội nghị ở chế độ Đảng viên cao tuổi (`createDossierMeeting`)
    let page = await s.trang('thamdinh', '/dossier/kiem-diem');
    await batCaoTuoi(page);
    let m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: 'Hội nghị kiểm điểm' })).toBeVisible();
    await expect(m.getByRole('table', { name: 'Danh sách hội nghị kiểm điểm' })).toBeVisible();
    await kiemAxeOnDinh(page, 'danh sách hội nghị kiểm điểm, chế độ cao tuổi');
    await m.getByRole('button', { name: 'Lập hội nghị' }).click();
    const lap = page.getByRole('dialog', { name: 'Lập hội nghị kiểm điểm' });
    await expect(lap).toBeVisible();
    for (const ten of [
      'Loại hội nghị',
      'Kỳ kiểm điểm',
      'Người được kiểm điểm',
      'Chủ trì',
      'Thư ký',
    ])
      await expect(lap.getByLabel(ten, { exact: true }).first()).toBeVisible();
    await lap.getByRole('button', { name: 'Huỷ' }).click();
    await expect(lap).toBeHidden();

    // Người được kiểm điểm gửi dự thảo (`sendDossierMeetingDraft`): máy chủ kiểm thời hạn 03 ngày làm việc
    const hnGui = await hoiNghi(s, 'canbo', 'NGUOI_DUOC_KIEM_DIEM', ['DU_KIEN'], 2);
    page = await s.trang('canbo', `/dossier/kiem-diem/${hnGui}`);
    m = main(page);
    await expect(m.getByText('Người được kiểm điểm', { exact: true }).first()).toBeVisible();
    await m.getByRole('button', { name: 'Gửi dự thảo' }).click();
    await expect(
      m
        .getByRole('status')
        .filter({ hasText: 'Đã gửi dự thảo tới các thành viên hội nghị.' })
        .or(m.getByRole('alert').filter({ hasText: /chưa đủ 03 ngày/ })),
    ).toBeVisible();

    // Thành viên góp ý dự thảo (`createDossierMeetingComment`)
    const hnGopY = await hoiNghi(s, 'canbo', 'THANH_VIEN', ['DA_GUI_DU_THAO'], 4);
    await moHoiNghi(page, s, 'canbo', hnGopY);
    const gopY = m.getByRole('group', { name: 'Góp ý của đồng chí' });
    await gopY.getByRole('radio', { name: 'Hạn chế' }).check();
    await m.getByRole('textbox', { name: 'Nội dung' }).fill(SEED.kiemDiem.gopY);
    await m.getByRole('button', { name: 'Gửi góp ý' }).click();
    await expect(m.getByRole('status').filter({ hasText: 'Đã gửi góp ý.' })).toBeVisible();
    await expect(m.getByText(SEED.kiemDiem.gopY)).toBeVisible();

    // Thư ký ghi ý kiến tại hội nghị, ghi kết quả biểu quyết và sinh biên bản (`createDossierMeetingMinutes`,
    // xác thực nhiều lớp)
    const hnThuKy = await hoiNghi(s, 'canbo', 'THU_KY', ['DA_GUI_DU_THAO', 'DA_HOP'], 1);
    await moHoiNghi(page, s, 'canbo', hnThuKy);
    const yKien = m.getByRole('group', { name: 'Thư ký ghi ý kiến tại hội nghị' });
    await yKien.getByRole('radio', { name: 'Ưu điểm' }).check();
    await m.getByRole('textbox', { name: 'Nội dung' }).fill(SEED.kiemDiem.yKienHoiNghi);
    await m.getByRole('button', { name: 'Ghi ý kiến' }).click();
    await expect(
      m.getByRole('status').filter({ hasText: 'Đã ghi ý kiến tại hội nghị.' }),
    ).toBeVisible();
    for (const [muc, so] of Object.entries(SEED.kiemDiem.bieuQuyet))
      await m.getByRole('spinbutton', { name: muc, exact: true }).fill(String(so));
    await m.getByRole('button', { name: 'Sinh biên bản' }).click();
    await xacThucBuocLen(
      page,
      'canbo',
      m.getByRole('status').filter({ hasText: /Đã ghi kết quả biểu quyết và sinh biên bản/ }),
      !HE_THONG,
    );
    await expect(m.getByText('Đã lập biên bản', { exact: true }).first()).toBeVisible();

    // Chủ trì kết thúc hội nghị (`closeDossierMeeting`)
    const hnChuTri = await hoiNghi(s, 'lanhdao', 'CHU_TRI', ['DA_LAP_BIEN_BAN'], 3);
    page = await s.trang('lanhdao', `/dossier/kiem-diem/${hnChuTri}`);
    m = main(page);
    await m.getByRole('button', { name: 'Kết thúc hội nghị' }).click();
    await expect(
      m.getByRole('status').filter({ hasText: /^Đã kết thúc hội nghị\./ }),
    ).toBeVisible();
    await kiemAxeOnDinh(page, 'hội nghị kiểm điểm đã kết thúc, chế độ cao tuổi');
  });

  test('HT-31 QT-11 kiểm danh mục, ký số và xuất gói hồ sơ số', async () => {
    test.setTimeout(HE_THONG ? 600_000 : 60_000);
    let goiDu = GOI_DU_DIEU_KIEN;
    let goiLoi: string | null = GOI_CON_LOI;
    if (HE_THONG) {
      const a = await api(s, 'thamdinh');
      try {
        goiDu = (
          await timHoacCho<Goi>(
            a,
            'dossiers/packages',
            { status: 'CHO_KY' },
            (x) => x.status === 'CHO_KY',
            'gói hồ sơ số chờ ký',
          )
        ).id;
        goiLoi = null;
      } finally {
        await a.dong();
      }
    }

    // Người chuẩn bị (thẩm định) mở danh sách gói, rồi gói chờ ký và đọc danh mục kiểm tra
    let page = await s.trang('thamdinh', '/dossier/goi-ho-so');
    let m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: 'Gói hồ sơ số' })).toBeVisible();
    await expect(m.getByRole('table', { name: 'Danh sách gói hồ sơ số' })).toBeVisible();
    await kiemAxeOnDinh(page, 'danh sách gói hồ sơ số');

    // Ca phủ định ở trình duyệt: gói còn mục kiểm tra chưa đạt thì chưa ký được (máy chủ cũng chặn)
    if (goiLoi) {
      page = await s.trang('quyetdinh', `/dossier/goi-ho-so/${goiLoi}`);
      m = main(page);
      await expect(m.getByText(/Còn \d+ mục cần xử lý trước khi ký gói\./)).toBeVisible();
      await m.getByRole('button', { name: 'Ký số gói và nộp' }).click();
      await expect(m.getByRole('alert').filter({ hasText: /^Chưa thể ký gói:/ })).toBeVisible();
    }

    page = await s.trang('thamdinh', `/dossier/goi-ho-so/${goiDu}`);
    m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: /^Gói hồ sơ số/ })).toBeVisible();
    await expect(m.getByRole('region', { name: 'Kiểm tra trước khi ký gói' })).toBeVisible();
    await expect(m.getByText('Đạt đủ điều kiện ký gói.')).toBeVisible();
    await expect(m.getByRole('table', { name: 'Biểu mẫu của gói' })).toBeVisible();

    // Tải gói ZIP kèm tệp kê khai (`exportDossierPackage` → tác vụ dài `getJob`)
    const tai = page.waitForEvent('download');
    await m.getByRole('button', { name: 'Tải gói (ZIP và manifest)' }).click();
    await expect(m.getByRole('status').filter({ hasText: /^Đã tải gói DP-/ })).toBeVisible({
      timeout: 30_000,
    });
    const tep = await tai;
    expect(tep.suggestedFilename()).toMatch(/\.(zip|json)$/);

    // Người có thẩm quyền ký số gói và nộp (`signDossierPackage`, xác thực nhiều lớp, bộ ký giả lập ở dev)
    const tu = new Date(Date.now() - 5_000);
    page = await s.trang('quyetdinh', `/dossier/goi-ho-so/${goiDu}`);
    m = main(page);
    await m.getByRole('button', { name: 'Ký số gói và nộp' }).click();
    await xacThucBuocLen(
      page,
      'quyetdinh',
      m.getByRole('status').filter({ hasText: /đã được ký số và nộp lúc/ }),
      !HE_THONG,
    );
    await expect(m.getByText('Đã nộp', { exact: true }).first()).toBeVisible();
    await kiemAxeOnDinh(page, 'gói hồ sơ số đã ký');

    await chiHeThong('đọc lại gói đã khoá qua API', async () => {
      const a: ApiHeThong = await api(s, 'quyetdinh');
      try {
        const g = await a.can<Goi>('GET', `dossiers/packages/${goiDu}`);
        expect(g.body.status).toBe('DA_NOP');
      } finally {
        await a.dong();
      }
    });
    await kiemSuKien('EVT-18', 'esign.signature.completed.v1', goiDu, tu);
    await kiemSuKien('EVT-17', 'dossier.form.generated.v1', goiDu, tu);
  });
});
