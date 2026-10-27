import { Page, expect, test } from '@playwright/test';
import { donTheoTienTo, giaoNhiemVu, kyDangMo } from '../ho-tro/du-lieu';
import {
  HE_THONG,
  MA_LAN_CHAY,
  PhienQt,
  choTrangThai,
  eicarDocx,
  ghiCho,
  kiemSuKien,
  kiemTruyCap,
  main,
  moNhiemVu,
  seed,
  thongBao,
  trenHeThong,
  xacThucBuocLen,
} from './chung';

/**
 * HT-20, HT-21 (QA-05, `danh-muc.json`): cán bộ tải lên, chờ quét mã độc rồi nộp minh chứng; người
 * nghiệm thu xem trước điểm, yêu cầu bổ sung, cán bộ nộp phiên bản mới, người nghiệm thu xác nhận năm
 * mức có xác thực nhiều lớp.
 * Truy vết: QT-07 → SCR-0701 (nộp), SCR-0703 (nghiệm thu) → `createFileUpload`, `uploadFilePart`,
 * `completeFileUpload`, `createEvidence`, `submitEvidenceVersion`, `openEvidenceReview`,
 * `previewAppraisalTaskScore`, `requestEvidenceRevision`, `createEvidenceVersion`,
 * `acceptEvidenceVersion` → MOD-03 (SVC-05), file-service (SVC-11) → `product.evidence_version`,
 * `task.task`, EVT-07, EVT-08, EVT-06.
 * Giả lập: tệp có "eicar" trong tên được báo nhiễm; người dùng giả lập là người chủ trì của nhiệm vụ
 * mình nộp, còn hàng chờ nghiệm thu là minh chứng của người khác, nên bước nộp lại sau yêu cầu bổ sung
 * chỉ kiểm được trên hệ thống thật.
 */
/** Nhiệm vụ giả lập có sản phẩm chưa nộp và bản nháp minh chứng của người dùng giả lập. */
const NV_GIA_LAP = 'Báo cáo chuyên đề công tác kiểm tra quý III';
const MC = seed<{ tenTep: string; yKienBoSung: string; nhanXet: string }>('minhChung');
// Mỗi tệp có nội dung riêng: trên dev, tệp trùng mã băm với lượt chạy trước bị đòi giải trình dùng lại (BR-07-03)
let soTep = 0;
const pdf = () =>
  Buffer.from(
    `%PDF-1.4\n% ${Date.now()}-${process.pid}-${++soTep}\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n`,
  );

interface MinhChung {
  currentVersionNo: number;
  currentStatus: string;
}

/** Phiên bản mới nhất của minh chứng và ETag của nó (`getEvidence`, `getEvidenceVersion`). */
async function phienBanMoi(api: Awaited<ReturnType<PhienQt['apiCua']>>, id: string) {
  const ev = await api.can<MinhChung>('GET', `evidences/${id}`);
  const v = await api.can<{ rowVersion: number }>(
    'GET',
    `evidences/${id}/versions/${ev.body.currentVersionNo}`,
  );
  return {
    no: ev.body.currentVersionNo,
    status: ev.body.currentStatus,
    etag: v.etag ?? `"${v.body.rowVersion}"`,
  };
}

/**
 * Sang trang nộp minh chứng (SCR-0701): từ chi tiết nhiệm vụ bấm "Nộp sản phẩm" rồi chọn sản phẩm chưa
 * nộp. Chế độ cao tuổi vẫn giữ nút này khi nhiệm vụ còn sản phẩm chưa nộp.
 */
async function moNop(page: Page) {
  const m = main(page);
  await m.getByRole('button', { name: 'Nộp sản phẩm' }).click();
  if (!(await m.getByRole('heading', { level: 1, name: 'Nộp sản phẩm, minh chứng' }).isVisible())) {
    await expect(
      m.getByRole('heading', { level: 1, name: 'Sản phẩm, minh chứng của nhiệm vụ' }),
    ).toBeVisible();
    await m
      .getByRole('button', { name: 'Nộp minh chứng' })
      .or(m.getByRole('link', { name: 'Tiếp tục nộp' }))
      .first()
      .click();
    await expect(
      m.getByRole('heading', { level: 1, name: 'Nộp sản phẩm, minh chứng' }),
    ).toBeVisible();
  }
  const taiLen = m.getByRole('radio', { name: /^Tải tệp lên/ });
  if (!(await taiLen.isChecked())) await taiLen.check();
}

/** Chọn tệp, chờ quét xong và trả dòng của tệp trong bảng. */
async function taiTep(
  page: Page,
  ten: string,
  noiDung: Buffer,
  ketQua: 'Sạch' | RegExp,
  mimeType = 'application/pdf',
) {
  const m = main(page);
  await m.getByLabel('chọn tệp').setInputFiles({ name: ten, mimeType, buffer: noiDung });
  const dong = m.getByRole('row').filter({ hasText: ten });
  await expect(dong.getByRole('cell', { name: ketQua })).toBeVisible({ timeout: 90_000 });
  return dong;
}

/** Nộp phiên bản đang soạn: cam kết rồi "Nộp để nghiệm thu"; trả mã minh chứng trong đường dẫn. */
async function nop(page: Page): Promise<string> {
  const m = main(page);
  await m.getByRole('checkbox', { name: /^Tôi xác nhận minh chứng/ }).check();
  await m.getByRole('button', { name: 'Nộp để nghiệm thu' }).click();
  await expect(thongBao(page, /Đã nộp phiên bản/)).toBeVisible();
  return new URL(page.url()).pathname.split('/')[2];
}

/** Nhiệm vụ đầu vào trên hệ thống thật: `quanly` giao (là người nghiệm thu), `canbo` nhận việc. */
async function nhiemVuHeThong(s: PhienQt) {
  const ky = await kyDangMo(await s.apiCua('quantri'));
  const nv = await giaoNhiemVu(
    await s.apiCua('quanly'),
    await s.danhTinh('quanly'),
    await s.danhTinh('canbo'),
    ky,
  );
  const canBo = await s.apiCua('canbo');
  const ct = await canBo.can<{ rowVersion: number }>('GET', `tasks/${nv.id}`);
  await canBo.can('POST', `tasks/${nv.id}/accept`, {
    ifMatch: ct.etag ?? `"${ct.body.rowVersion}"`,
  });
  return { id: nv.id, title: nv.title, code: nv.code, kyId: ky.id };
}

/** Đánh giá đủ tiêu chuẩn, chọn mức và nguyên nhân trên SCR-0703; kiểm vùng xem trước điểm. */
async function danhGia(page: Page, muc: RegExp) {
  const m = main(page);
  await expect(m.getByRole('region', { name: 'Đối chiếu tiêu chuẩn chất lượng' })).toBeVisible();
  const dat = m.getByRole('radio', { name: 'Đạt', exact: true });
  for (let i = 0; i < (await dat.count()); i++) await dat.nth(i).check();
  await m.getByRole('radio', { name: muc }).check();
  const nn = m.getByRole('radio', { name: 'Khách quan' });
  if (await nn.isVisible()) await nn.check();
  // `previewAppraisalTaskScore`: vùng xem trước hiện điểm khi đã chọn mức
  const xem = m.getByRole('region', { name: 'Xem trước điểm' });
  await expect(xem.getByText('Chọn mức chất lượng để xem trước điểm nhiệm vụ.')).toBeHidden();
  const chuaCo = xem.getByText(/Chưa xem trước được điểm/);
  await expect(
    xem
      .getByText(/\d+,\d+/)
      .first()
      .or(chuaCo),
  ).toBeVisible();
  if (HE_THONG && (await chuaCo.isVisible())) {
    // SVC-07 chỉ xem trước khi kỳ đã có lần tính điểm chứa nhiệm vụ; kỳ đang mở chưa tính lần nào (wiki 14)
    ghiCho('xem trước điểm: kỳ chưa có lần tính điểm nào chứa nhiệm vụ (score-preview 422)');
    xemTruoc422 = true;
  }
}

/**
 * `score-preview` đã trả 422 và được ghi chờ; trình duyệt tự ghi một lỗi tải tài nguyên cho mỗi lần gọi
 * (đổi mức, đổi nguyên nhân), không kèm đường dẫn, nên bỏ các dòng 422 đó khỏi kiểm lỗi console.
 */
let xemTruoc422 = false;

test.describe('Minh chứng và nghiệm thu', { tag: '@qa-05' }, () => {
  test.describe.configure({ mode: 'serial' });
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
    const boQua = xemTruoc422;
    xemTruoc422 = false;
    const loi = s.loi.filter((l) => !(boQua && l.includes('status of 422')));
    await s.dong();
    expect(loi, 'không có lỗi console trên trang của ứng dụng').toEqual([]);
  });

  test('HT-20 cán bộ cao tuổi tải lên và nộp minh chứng sau khi quét mã độc', async ({
    browser,
  }) => {
    test.setTimeout(HE_THONG ? 300_000 : 120_000);
    PhienQt.batDau();
    s = new PhienQt(browser, ['canbo', 'quanly', 'quantri'], true);
    let nv: { id: string; title: string; code: string } | null = null;
    if (HE_THONG) ({ kyId, ...nv } = await nhiemVuHeThong(s));

    const page = nv ? await moNhiemVu(s, nv.title) : await moNhiemVu(s, NV_GIA_LAP, NV_GIA_LAP);
    await moNop(page);
    const m = main(page);

    // Ca phủ định: tệp thử EICAR bị đánh dấu nhiễm, bị cách ly và không nộp được
    const tenNhiem = `eicar-${MA_LAN_CHAY}.docx`;
    await taiTep(
      page,
      tenNhiem,
      eicarDocx(),
      /Nhiễm|Không an toàn|cách ly/i,
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    );
    await expect(
      m.getByRole('alert').filter({ hasText: `Tệp "${tenNhiem}" không an toàn` }),
    ).toBeVisible();
    await m.getByRole('checkbox', { name: /^Tôi xác nhận minh chứng/ }).check();
    await expect(m.getByRole('button', { name: 'Nộp để nghiệm thu' })).toBeDisabled();
    await expect(m.getByText(/gỡ tệp không an toàn và tải tệp khác/)).toBeVisible();
    await m.getByRole('button', { name: `Gỡ tệp ${tenNhiem}` }).click();
    await expect(m.getByRole('row').filter({ hasText: tenNhiem })).toHaveCount(0);

    // Tệp sạch: tải lên nhiều phần (`createFileUpload` → `uploadFilePart` → `completeFileUpload`), quét xong
    const tu = new Date(Date.now() - 5_000);
    await taiTep(page, MC.tenTep, pdf(), 'Sạch');
    await kiemTruyCap(page, 'nộp minh chứng chế độ cao tuổi');
    const evId = await nop(page);
    await kiemSuKien('EVT-07', evId, tu);

    // Máy chủ là nguồn sự thật: phiên bản DA_NOP, nhiệm vụ CHO_NGHIEM_THU
    await trenHeThong('phiên bản đã nộp và nhiệm vụ chờ nghiệm thu', async () => {
      const api = await s.apiCua('canbo');
      expect((await phienBanMoi(api, evId)).status).toBe('DA_NOP');
      await choTrangThai(api, `tasks/${nv!.id}`, 'CHO_NGHIEM_THU');
    });
  });

  test('HT-21 nghiệm thu năm mức, yêu cầu bổ sung rồi xác nhận minh chứng', async ({ browser }) => {
    test.setTimeout(HE_THONG ? 360_000 : 120_000);
    PhienQt.batDau();
    s = new PhienQt(browser, ['canbo', 'quanly', 'quantri']);
    let nv: { id: string; title: string; code: string } | null = null;
    if (HE_THONG) ({ kyId, ...nv } = await nhiemVuHeThong(s));

    // Cán bộ nộp minh chứng của mình (như HT-20, chế độ thường)
    const page = nv ? await moNhiemVu(s, nv.title) : await moNhiemVu(s, NV_GIA_LAP, NV_GIA_LAP);
    await moNop(page);
    await taiTep(page, MC.tenTep, pdf(), 'Sạch');
    const evMinh = await nop(page);

    // Ca phủ định: người nộp không tự nghiệm thu được minh chứng của mình (BR-07-06, 403)
    if (HE_THONG) {
      const canBo = await s.apiCua('canbo');
      const v = await phienBanMoi(canBo, evMinh);
      const tu = await canBo.goi('POST', `evidences/${evMinh}/versions/${v.no}/open-review`, {
        ifMatch: v.etag,
      });
      expect(tu.status, 'người nộp tự mở nghiệm thu').toBe(403);
    } else {
      const p = await s.as('canbo', `/evidence/${evMinh}/review`);
      await expect(
        main(p)
          .getByText(
            /Người nghiệm thu phải khác người nộp|không phải người nghiệm thu được chỉ định/,
          )
          .first(),
      ).toBeVisible();
      await expect(main(p).getByRole('button', { name: 'Xác nhận nghiệm thu' })).toHaveCount(0);
    }

    // AC-05 mở hàng chờ, xem trước điểm và yêu cầu bổ sung (SCR-0703)
    const moHangCho = async () => {
      const rp = await s.as('quanly', '/evidence?reviewer=me');
      const rm = main(rp);
      await expect(
        rm.getByRole('heading', { level: 1, name: 'Minh chứng chờ tôi nghiệm thu' }),
      ).toBeVisible();
      const hang = rm.getByRole('listitem');
      // hàng chờ hiện mã nhiệm vụ và tên minh chứng, không hiện tên nhiệm vụ
      await (nv?.code ? hang.filter({ hasText: nv.code }) : hang)
        .first()
        .getByRole('link', { name: 'Nghiệm thu' })
        .click();
      await expect(
        rm.getByRole('heading', { level: 1, name: 'Nghiệm thu sản phẩm' }),
      ).toBeVisible();
      return rp;
    };
    let rp = await moHangCho();
    let rm = main(rp);
    const evReview = new URL(rp.url()).pathname.split('/')[2];
    await danhGia(rp, /^0,75/);
    await kiemTruyCap(rp, 'nghiệm thu sản phẩm');
    await rm.getByRole('textbox', { name: 'Nhận xét' }).fill(MC.yKienBoSung);
    await rm.getByRole('button', { name: 'Yêu cầu bổ sung' }).click();
    await expect(thongBao(rp, /^Đã yêu cầu bổ sung/)).toBeVisible();
    await expect(
      rm.getByRole('region', { name: 'Kết quả nghiệm thu' }).getByText(MC.yKienBoSung),
    ).toBeVisible();

    // AC-06 nộp phiên bản mới (`createEvidenceVersion`, `submitEvidenceVersion`)
    if (HE_THONG) {
      const p = await s.as('canbo', `/evidence/${evReview}`);
      await expect(main(p).getByText(MC.yKienBoSung).first()).toBeVisible();
      await taiTep(p, MC.tenTep.replace('.pdf', '-bo-sung.pdf'), pdf(), 'Sạch');
      await nop(p);
      rp = await moHangCho();
      rm = main(rp);
    } else {
      ghiCho(
        'nộp phiên bản mới sau yêu cầu bổ sung: người dùng giả lập không chủ trì minh chứng trong hàng chờ, kiểm trên hệ thống thật',
      );
      // tải lại toàn trang để kho giả lập về trạng thái đầu, minh chứng trong hàng chờ lại ở "Đã nộp"
      await rp.waitForLoadState('networkidle');
      await rp.goto('/evidence?reviewer=me');
      rp = await moHangCho();
      rm = main(rp);
    }

    // Xác nhận nghiệm thu: thiếu xác thực nhiều lớp thì không ghi nhận (huỷ hộp xác thực). Dev tắt OTP cấp sẵn phiên
    // aal2 lúc đăng nhập (tat-otp-dev.sh) nên hộp không hiện và lệnh ghi nhận ngay; khi đó bỏ ca phủ định này
    await danhGia(rp, /^1,00/);
    await rm.getByRole('textbox', { name: 'Nhận xét' }).fill(MC.nhanXet);
    const xong = thongBao(rp, /Đã xác nhận nghiệm thu/);
    const tu = new Date(Date.now() - 5_000);
    await rm.getByRole('button', { name: 'Xác nhận nghiệm thu' }).click();
    const hop = rp.getByRole('dialog', { name: 'Xác thực nhiều lớp' });
    await expect(hop.or(xong).first()).toBeVisible({ timeout: HE_THONG ? 60_000 : 5_000 });
    if (await hop.isVisible()) {
      await hop.getByRole('button', { name: 'Huỷ' }).click();
      await expect(hop).toBeHidden();
      await expect(xong).toHaveCount(0);
      await trenHeThong('xác nhận nghiệm thu không có xác thực bậc hai bị chặn', async () => {
        const api = await s.apiCua('quanly');
        // token đăng nhập thường (hộp xác thực vừa hiện chứng tỏ chưa đủ mức); kiểm xác thực đứng trước kiểm thân
        const v = await phienBanMoi(api, evReview);
        const r = await api.goi('POST', `evidences/${evReview}/versions/${v.no}/accept`, {
          ifMatch: v.etag,
          body: { qualityLevel: 1, criteriaResults: [], comment: MC.nhanXet },
        });
        expect([401, 403], 'thiếu step-up phải bị chặn').toContain(r.status);
      });
      await rm.getByRole('button', { name: 'Xác nhận nghiệm thu' }).click();
      await xacThucBuocLen(rp, 'quanly', xong, true);
    } else {
      ghiCho(
        'ca thiếu xác thực bậc hai bị chặn: dev cấp phiên aal2 khi đăng nhập (tat-otp-dev.sh), hộp không hiện',
      );
    }
    await kiemSuKien('EVT-08', evReview, tu);
    await kiemSuKien('EVT-06', nv?.id ?? '', tu);
    await trenHeThong('phiên bản đã xác nhận và nhiệm vụ hoàn thành', async () => {
      const api = await s.apiCua('quanly');
      expect((await phienBanMoi(api, evReview)).status).toBe('DA_XAC_NHAN');
      await choTrangThai(api, `tasks/${nv!.id}`, 'HOAN_THANH');
    });
  });
});
