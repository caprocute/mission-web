import { Page, expect, test } from '@playwright/test';
import { batCaoTuoi, chiHeThong, chon, xacThucBuocLen, kiemAxeOnDinh } from '../ho-tro/kiem-tra';
import { HE_THONG, cho } from '../ho-tro/moi-truong';
import { Phien, main, TenTaiKhoan } from '../ho-tro/phien';
import { SEED, api, kiemMoiTruong, kiemSuKien, maTuDuongDan, timHoacCho } from './chung';

/**
 * QT-13 Hậu kiểm, mở lại kỳ và sổ cái: SCR-1301 (yêu cầu mở lại kỳ, hai người duyệt), SCR-1302 (sổ
 * cái, kiểm tra toàn vẹn) → `/api/v1/oversight/reopen-requests*`, `/api/v1/ledger/*` → MOD-05 (SVC-05),
 * SVC-09 ledger → `oversight.reopen_request`, `ledger.entry`, EVT-22 `oversight.period.reopened.v1`,
 * kỳ `MO_LAI_HAU_KIEM`. Hành trình HT-34, HT-35.
 *
 * Kho giả lập áp đúng luật bốn mắt của máy chủ cho một người dùng duy nhất: người tạo yêu cầu không
 * duyệt được, người đã duyệt lượt một không duyệt được lượt hai. Vì vậy hành trình tạo yêu cầu mới
 * rồi kiểm ca phủ định trên chính yêu cầu đó, còn hai lượt duyệt đi trên yêu cầu khởi tạo của người
 * khác (MLK-2026-008 chờ lượt một, MLK-2026-007 chờ lượt hai).
 */
const CHO_LUOT_1 = 'rr-2026-008';
const CHO_LUOT_2 = 'rr-2026-007';

interface YeuCau {
  id: string;
  code: string;
  status: string;
  period: { id: string };
}

async function duyet(page: Page, user: 'quyetdinh' | 'duyet2', luot: string, yKien: string) {
  const m = main(page);
  const vung = m.getByRole('region', { name: `Duyệt lượt thứ ${luot}` });
  await vung.getByRole('textbox', { name: /^Ý kiến người duyệt/ }).fill(yKien);
  await vung.getByRole('button', { name: 'Xác thực nhiều lớp và duyệt' }).click();
  return { m, user };
}

/**
 * Người lập yêu cầu mở lại kỳ (AC-10 kiểm tra, giám sát). Đặt `E2E_HT34_NGUOI_TAO=thamdinh` để chạy trên dev trong lúc
 * vai trò inspector chưa được đọc danh sách hồ sơ (`listAppraisalProfiles`) mà ô chọn hồ sơ của kỳ đã khoá cần.
 */
const NGUOI_TAO = (process.env.E2E_HT34_NGUOI_TAO?.trim() || 'kiemtra') as TenTaiKhoan;
test.describe('QT-13 Mở lại kỳ và sổ cái', { tag: '@qa-07' }, () => {
  let s: Phien;

  test.beforeEach(({ browser }) => {
    s = new Phien(browser, [NGUOI_TAO, 'kiemtra', 'quyetdinh', 'duyet2']);
    kiemMoiTruong();
  });

  test.afterEach(async () => {
    const loi = [...s.loi];
    await s.dong();
    expect(loi, 'không có lỗi console hay phản hồi 5xx').toEqual([]);
  });

  test('HT-34 QT-13 tạo yêu cầu mở lại kỳ, tính thử tác động và hai người khác nhau duyệt', async () => {
    test.setTimeout(HE_THONG ? 600_000 : 60_000);

    // AC-10 tạo yêu cầu ở chế độ Đảng viên cao tuổi (`createReopenRequest`, xác thực nhiều lớp)
    let page = await s.trang(NGUOI_TAO, '/oversight/reopen-requests');
    await batCaoTuoi(page);
    let m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: 'Hậu kiểm, mở lại kỳ' })).toBeVisible();
    await kiemAxeOnDinh(page, 'hậu kiểm, mở lại kỳ, chế độ cao tuổi');
    await m.getByRole('link', { name: 'Tạo yêu cầu mở lại kỳ' }).click();
    await expect(m.getByRole('heading', { level: 1, name: 'Tạo yêu cầu mở lại kỳ' })).toBeVisible();
    const ky = m.getByRole('group', { name: 'Kỳ và căn cứ' });
    // Hệ thống thật: chọn kỳ đã khoá có hồ sơ đã chốt trong phạm vi người lập (ô hồ sơ chỉ liệt kê hồ sơ đã
    // quyết định, đã ký, đã khoá hoặc đang khiếu nại); kỳ khoá đầu danh sách có thể chỉ còn hồ sơ đang tự đánh giá
    let tenKy: string | undefined;
    if (HE_THONG) {
      const a = await api(s, NGUOI_TAO);
      try {
        for (const st of ['DA_KY_SO', 'DA_KHOA', 'DA_QUYET_DINH', 'KHIEU_NAI']) {
          const r = await a.can<{ items: { period?: { id?: string; label?: string } }[] }>(
            'GET',
            'appraisals/profiles',
            { query: { status: st, limit: '20' } },
          );
          const p = r.body.items.find((x) => x.period?.label)?.period;
          tenKy = p?.label;
          if (p?.id) {
            // Yêu cầu do lần chạy trước tạo (lý do mang tiền tố E2E-) còn đang xử lý thì máy chủ từ chối yêu cầu
            // mới cùng phạm vi (OVERSIGHT-409-INVALID-STATE): huỷ các yêu cầu đó trước
            const ds = await a.can<{
              items: { id: string; reason?: string; rowVersion?: number }[];
            }>('GET', 'oversight/reopen-requests', { query: { period: p.id, limit: '200' } });
            for (const y of ds.body.items.filter((x) => x.reason?.startsWith('E2E-'))) {
              await a.goi('POST', `oversight/reopen-requests/${y.id}/cancel`, {
                ifMatch: `"${y.rowVersion ?? 0}"`,
                body: { reason: 'Huỷ yêu cầu thử của lần chạy trước.' },
              });
            }
          }
          if (tenKy) break;
        }
      } finally {
        await a.dong();
      }
      if (!tenKy)
        cho(
          `kỳ đã khoá có hồ sơ đã chốt trong phạm vi ${NGUOI_TAO}: chạy HT-25..27 (seed-e2e-hanh-trinh.py ho-so) trước`,
        );
    }
    await chon(page, ky.getByRole('combobox').first(), tenKy);
    // Căn cứ nhập tay bằng số chỉ hợp lệ với "Quyết định hậu kiểm" (BasisRef QUYET_DINH cần `code`), loại mặc
    // định khi mở thẳng trang; các loại khác cần mã đối tượng trong MOD-07 (`id`), chọn từ danh sách của kỳ
    await expect(ky.locator('#rq-basis-kind')).toContainText('Quyết định hậu kiểm');
    await chon(page, ky.locator('#rq-basis-kind'), 'Quyết định hậu kiểm');
    await ky.getByRole('textbox', { name: 'Số quyết định hậu kiểm' }).fill(SEED.moLai.canCu);
    // HT-34: hồ sơ chọn từ danh sách hồ sơ của kỳ vừa chọn (`listAppraisalProfiles`), gửi mã hồ sơ
    // UUID; không còn ô tự gõ mã. Giả lập: kỳ khoá đầu tiên là Quý II/2026, chọn hồ sơ Phạm Thị Lan (chưa
    // có yêu cầu đang chờ). Hệ thống thật: hồ sơ đầu tiên của kỳ khoá trong phạm vi người kiểm tra.
    const phamVi = m.getByRole('group', { name: 'Phạm vi mở lại' });
    const hoSo = phamVi.getByRole('combobox', { name: 'Hồ sơ đánh giá' });
    await expect(hoSo).toBeEnabled();
    await chon(page, hoSo, HE_THONG ? undefined : /^Phạm Thị Lan/);
    const doiTuong = phamVi
      .getByRole('list', { name: 'Đối tượng trong phạm vi' })
      .getByRole('listitem');
    await expect(doiTuong).toHaveCount(1);
    await expect(doiTuong).not.toHaveText(['Chưa có đối tượng nào.']);
    await expect(doiTuong.first()).not.toHaveText(/[0-9a-f]{8}-[0-9a-f]{4}-/i);
    if (!HE_THONG) await expect(doiTuong.first()).toContainText('Phạm Thị Lan');
    await m.getByRole('textbox', { name: /^Lý do mở lại/ }).fill(SEED.moLai.lyDo);
    await kiemAxeOnDinh(page, 'tạo yêu cầu mở lại kỳ, chế độ cao tuổi');
    await m.getByRole('button', { name: 'Tạo yêu cầu' }).click();
    const tieuDe = m.getByRole('heading', { level: 1, name: /^Yêu cầu mở lại kỳ MLK-/ });
    await xacThucBuocLen(page, NGUOI_TAO, tieuDe, !HE_THONG);
    await expect(tieuDe).toContainText('Chờ người duyệt thứ nhất');
    const moi = maTuDuongDan(page.url());

    // Tính thử tác động (`simulateReopenRequest`): bảng ảnh hưởng hiện, chưa áp dụng
    await expect(
      m.getByRole('table', { name: 'Ảnh hưởng dự kiến (tính thử, chưa áp dụng)' }),
    ).toBeVisible();
    await m.getByRole('button', { name: 'Tính thử lại' }).click();
    await expect(
      m.getByRole('table', { name: 'Ảnh hưởng dự kiến (tính thử, chưa áp dụng)' }),
    ).toBeVisible();

    // Ca phủ định bốn mắt: người tạo yêu cầu (giả lập: cùng danh tính) không duyệt được
    if (!HE_THONG) {
      page = await s.trang('quyetdinh', `/oversight/reopen-requests/${moi}`);
      await duyet(page, 'quyetdinh', 'nhất', SEED.moLai.yKienDuyet1);
      await expect(
        main(page)
          .getByRole('alert')
          .filter({ hasText: /^Hai người duyệt phải là hai người khác nhau/ }),
      ).toBeVisible();
    }

    let luot1 = CHO_LUOT_1;
    let luot2 = CHO_LUOT_2;
    let kyMoLai: string | null = null;
    if (HE_THONG) {
      luot1 = luot2 = moi;
      // Trên hệ thống thật người duyệt chưa có phiên aal2 thì giao diện mời xác thực lại ở bước dưới
      // (xacThucBuocLen), máy chủ trả 401 `insufficient_user_authentication` nếu bỏ qua bước này
      const a = await api(s, 'quyetdinh');
      try {
        kyMoLai = (await a.can<YeuCau>('GET', `oversight/reopen-requests/${moi}`)).body.period.id;
      } finally {
        await a.dong();
      }
    }

    // AC-08 duyệt lượt thứ nhất (`approveReopenRequest`, xác thực nhiều lớp)
    page = await s.trang('quyetdinh', `/oversight/reopen-requests/${luot1}`);
    m = main(page);
    await duyet(page, 'quyetdinh', 'nhất', SEED.moLai.yKienDuyet1);
    await xacThucBuocLen(
      page,
      'quyetdinh',
      m.getByRole('status').filter({ hasText: /^Đã ghi nhận phê duyệt thứ nhất/ }),
      false,
    );

    // Ca phủ định: cùng một người duyệt lượt hai bị từ chối
    if (!HE_THONG) {
      await s.trang('quyetdinh', `/oversight/reopen-requests/${luot1}`);
      await duyet(page, 'quyetdinh', 'hai', SEED.moLai.yKienDuyet2);
      await expect(
        m.getByRole('alert').filter({ hasText: /^Hai người duyệt phải là hai người khác nhau/ }),
      ).toBeVisible();
    } else {
      await chiHeThong('cùng người duyệt hai lượt', async () => {
        const a = await api(s, 'quyetdinh');
        try {
          const r = await a.goi('POST', `oversight/reopen-requests/${luot1}/approvals`, {
            body: { comment: SEED.moLai.yKienDuyet2 },
          });
          expect([401, 403], 'người đã duyệt lượt một không được duyệt lượt hai').toContain(
            r.status,
          );
        } finally {
          await a.dong();
        }
      });
    }

    // AC-09 duyệt lượt thứ hai: đủ hai phê duyệt thì kỳ mở lại trong phạm vi (EVT-22)
    const tu = new Date(Date.now() - 5_000);
    page = await s.trang('duyet2', `/oversight/reopen-requests/${luot2}`);
    m = main(page);
    await duyet(page, 'duyet2', 'hai', SEED.moLai.yKienDuyet2);
    await xacThucBuocLen(
      page,
      'duyet2',
      m.getByRole('status').filter({ hasText: /đã đủ hai phê duyệt/ }),
      false,
    );
    await kiemAxeOnDinh(page, 'yêu cầu mở lại kỳ đã đủ hai phê duyệt');

    await chiHeThong('đọc lại kỳ ở trạng thái mở lại hậu kiểm', async () => {
      const a = await api(s, 'kiemtra');
      try {
        const yc = await a.can<YeuCau>('GET', `oversight/reopen-requests/${luot2}`);
        expect(['DA_DUYET', 'DA_THUC_HIEN']).toContain(yc.body.status);
        const p = await a.can<{ status: string }>('GET', `periods/${kyMoLai ?? yc.body.period.id}`);
        expect(p.body.status).toBe('MO_LAI_HAU_KIEM');
      } finally {
        await a.dong();
      }
    });
    await kiemSuKien('EVT-22', 'oversight.period.reopened.v1', luot2, tu);
  });

  test('HT-35 QT-13 tra cứu sổ cái, chạy kiểm tra toàn vẹn và xem niêm phong', async () => {
    test.setTimeout(HE_THONG ? 600_000 : 60_000);
    if (HE_THONG) {
      const a = await api(s, 'kiemtra');
      try {
        await timHoacCho(a, 'ledger/entries', {}, () => true, 'bản ghi sổ cái');
      } finally {
        await a.dong();
      }
    }

    // AC-10 tra cứu sổ cái (`listLedgerEntries`)
    const page = await s.trang('kiemtra', '/oversight/ledger');
    const m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Sổ cái và kiểm tra toàn vẹn' }),
    ).toBeVisible();
    await m.getByRole('button', { name: /^\W*Tìm$/ }).click();
    const banGhi = m.getByRole('button', { name: /^Xem bản ghi số \d+/ });
    await expect(banGhi.first()).toBeVisible();
    await kiemAxeOnDinh(page, 'sổ cái');

    // Xem một bản ghi: niêm phong, dấu thời gian và nơi lưu bản đối chứng (bằng chứng bao hàm)
    await banGhi.nth(1).click();
    await expect(m.getByRole('region', { name: /Niêm phong và đối chứng/ })).toBeVisible();

    // Chạy kiểm tra toàn vẹn (`createLedgerVerifyRun` → `getLedgerVerifyRun`): lần chạy kết thúc với kết luận
    await m.getByRole('button', { name: 'Chạy kiểm tra toàn vẹn' }).click();
    await expect(
      m
        .getByRole('status')
        .filter({ hasText: /Kiểm tra toàn vẹn hoàn tất/ })
        .or(m.getByRole('alert').filter({ hasText: /Phát hiện dữ liệu bị thay đổi trái phép/ })),
    ).toBeVisible({ timeout: 30_000 });
    await expect(m.getByRole('region', { name: /Lần kiểm tra/ })).toBeVisible();

    await chiHeThong('lần kiểm tra và bằng chứng bao hàm qua API', async () => {
      const a = await api(s, 'kiemtra');
      try {
        const ds = await a.can<{ items: { id: string; finishedAt: string | null }[] }>(
          'GET',
          'ledger/verify-runs',
          { query: { limit: '1' } },
        );
        expect(ds.body.items.length).toBeGreaterThan(0);
        const e = await a.can<{ items: { eventId: string }[] }>('GET', 'ledger/entries', {
          query: { limit: '1' },
        });
        // Hợp đồng getLedgerInclusionProof: tham số `event` (event_id), chưa niêm phong trả 409
        const proof = await a.goi('GET', 'oversight/ledger/proofs', {
          query: { event: e.body.items[0].eventId },
        });
        expect([200, 409], 'bằng chứng bao hàm trả về hoặc bản ghi chưa niêm phong').toContain(
          proof.status,
        );
      } finally {
        await a.dong();
      }
    });
  });
});
