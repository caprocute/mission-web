import { Locator, Page, expect, test } from '@playwright/test';
import { ApiHeThong } from '../ho-tro/api';
import { kyDangMo } from '../ho-tro/du-lieu';
import {
  HE_THONG,
  ghiCho,
  PhienQt,
  choTrangThai,
  kiemSuKien,
  kiemTruyCap,
  main,
  seed,
  thongBao,
  trenHeThong,
  xacThucBuocLen,
} from './chung';

/**
 * HT-11, HT-12 (QA-05, `danh-muc.json`): lập kế hoạch quý, bị trả lại rồi được phê duyệt; điều chỉnh kế
 * hoạch đã duyệt thành phiên bản mới.
 * Truy vết: QT-04 → SCR-0401 (danh mục), SCR-0402 (lập kế hoạch), SCR-0403 (phê duyệt) →
 * `createPlan`, `savePlanVersion`, `checkPlanVersion`, `submitPlanVersion`, `returnPlanVersion`,
 * `approvePlanVersion`, `getPlanVersion`, `createPlanAdjustment` → MOD-02 (SVC-05) →
 * `task.plan_version` (`NHAP` → `CHO_DUYET` → `TRA_LAI` → `CHO_DUYET` → `DA_DUYET`), EVT-03.
 * Người lập là AC-05 (`quanly`), người duyệt là AC-04 (`lanhdao`). Ở chế độ giả lập dùng kế hoạch quý
 * nháp của dữ liệu giả lập, vốn có một dòng KPI mơ hồ làm ca phủ định "gửi duyệt khi chưa qua kiểm tra".
 */
const KH = seed<{
  noiDung: string;
  sanPham: string;
  soLuong: number;
  donViTinh: string;
  noiDungSua: string;
  soLuongSua: number;
  yKienTraLai: string;
  yKienDuyet: string;
  lyDoDieuChinh: string;
}>('keHoach');

interface KeHoach {
  id: string;
  currentVersion: { versionNo: number; status: string };
}

/**
 * Hệ thống thật: kế hoạch của đơn vị người lập trong kỳ đang mở, tạo mới nếu chưa có (`createPlan`,
 * đơn vị lấy theo ngữ cảnh phân công). Trả đường dẫn mở đúng kế hoạch đó trên SCR-0402.
 */
async function keHoachHeThong(s: PhienQt): Promise<{ api: ApiHeThong; id: string; url: string }> {
  const api = await s.apiCua('quanly');
  const ky = await kyDangMo(await s.apiCua('quantri'));
  const ds = await api.can<{ items: KeHoach[] }>('GET', 'plans', {
    query: { period: ky.id, limit: '200' },
  });
  let kh = ds.body.items.find((p) => ['NHAP', 'TRA_LAI'].includes(p.currentVersion.status));
  if (!kh) kh = (await api.can<KeHoach>('POST', 'plans', { body: { periodId: ky.id } })).body;
  return { api, id: kh.id, url: `/plans?plan=${kh.id}&v=${kh.currentVersion.versionNo}` };
}

/** Sửa dòng `i` của bảng công việc bằng trình soạn dòng (SCR-0402 bước 3). */
async function suaDong(page: Page, i: number, noiDung: string, soLuong: number) {
  const m = main(page);
  await m.getByRole('button', { name: `Sửa dòng ${i}` }).click();
  const ed = m.getByRole('region', { name: new RegExp(`^Sửa dòng ${i}`) });
  await ed.getByRole('textbox', { name: 'Nội dung công việc *' }).fill(noiDung);
  await ed.getByRole('textbox', { name: 'Sản phẩm *' }).fill(KH.sanPham);
  await ed.getByRole('spinbutton', { name: 'Số lượng *' }).fill(String(soLuong));
  await ed.getByRole('textbox', { name: 'Đơn vị tính *' }).fill(KH.donViTinh);
  await ed.getByRole('button', { name: 'Xong' }).click();
  await expect(ed).toBeHidden();
}

/** Điền hạn của dòng đang sửa nếu còn trống (hạn cuối kỳ, thuộc tính `max` của ô ngày). */
async function dienHan(ed: Locator) {
  const han = ed.getByRole('textbox', { name: 'Hạn *' });
  if (await han.inputValue()) return;
  const max = await han.getAttribute('max');
  await han.fill(max ?? new Date(Date.now() + 30 * 86_400_000).toISOString().slice(0, 10));
}

const soDongKeHoach = (page: Page) =>
  main(page)
    .getByRole('region', { name: 'Bảng công việc trong kế hoạch' })
    .getByRole('button', { name: /^Sửa dòng \d+$/ })
    .count();

/** Thêm một việc ngoài danh mục vào cuối bảng và trả số thứ tự dòng mới. */
async function themDong(page: Page): Promise<number> {
  const m = main(page);
  await m.getByRole('button', { name: 'Thêm việc ngoài danh mục' }).click();
  const ed = m.getByRole('region', { name: /^Sửa dòng \d+/ });
  const ten = (await ed.getByRole('heading', { level: 3 }).innerText()).match(/Sửa dòng (\d+)/);
  const so = Number(ten![1]);
  await ed.getByRole('textbox', { name: 'Nội dung công việc *' }).fill(KH.noiDung);
  await ed.getByRole('textbox', { name: 'Sản phẩm *' }).fill(KH.sanPham);
  await ed.getByRole('spinbutton', { name: 'Số lượng *' }).fill(String(KH.soLuong));
  await ed.getByRole('textbox', { name: 'Đơn vị tính *' }).fill(KH.donViTinh);
  await dienHan(ed);
  // phiên bản điều chỉnh: dòng thêm, sửa phải ghi lý do (BR-04-10)
  const lyDo = ed.getByRole('textbox', { name: /^Lý do thay đổi/ });
  if (await lyDo.isVisible()) await lyDo.fill(KH.lyDoDieuChinh);
  await ed.getByRole('button', { name: 'Xong' }).click();
  await expect(ed).toBeHidden();
  return so;
}

/**
 * Phiên bản điều chỉnh vượt tỷ trọng nhóm then chốt (dữ liệu giả lập có sẵn): bỏ dòng then chốt cuối
 * cùng kèm lý do cho tới khi hết lỗi tỷ trọng (BR-04-04, BR-04-10).
 */
async function canTyTrong(page: Page) {
  const m = main(page);
  const kiem = m.getByRole('region', { name: 'Kiểm tra kế hoạch' });
  const vuot = kiem.getByRole('listitem').filter({ hasText: /Tổng điểm nhóm .* vượt điểm tối đa/ });
  for (let lan = 0; lan < 5 && (await vuot.count()); lan++) {
    const dong = m
      .getByRole('region', { name: 'Bảng công việc trong kế hoạch' })
      .getByRole('row')
      .filter({ hasText: 'Then chốt' })
      .last();
    await dong.getByRole('button', { name: /^Sửa dòng \d+$/ }).click();
    await m
      .getByRole('region', { name: /^Sửa dòng \d+/ })
      .getByRole('button', { name: 'Bỏ dòng này' })
      .click();
    // dòng có trong phiên bản đã duyệt thì phải ghi lý do bỏ
    const hop = page.getByRole('dialog', { name: 'Bỏ dòng khỏi kế hoạch điều chỉnh' });
    if (
      await hop.waitFor({ timeout: 3_000 }).then(
        () => true,
        () => false,
      )
    ) {
      await hop.getByRole('textbox', { name: 'Lý do bỏ dòng (bắt buộc)' }).fill(KH.lyDoDieuChinh);
      await hop.getByRole('button', { name: 'Bỏ dòng' }).click();
      await expect(hop).toBeHidden();
    }
  }
}

/** Bảng kiểm tra kế hoạch không còn mục "Lỗi" thì nút Gửi duyệt mới bật (BR-04-03). */
async function guiDuyet(page: Page) {
  const m = main(page);
  await m.getByRole('button', { name: 'Lưu nháp' }).click();
  await expect(m.getByText(/Đã lưu nháp lúc/)).toBeVisible();
  const kiem = m.getByRole('region', { name: 'Kiểm tra kế hoạch' });
  await expect(kiem.getByRole('listitem').filter({ hasText: /^Lỗi/ })).toHaveCount(0);
  await m.getByRole('button', { name: 'Gửi duyệt' }).click();
  await expect(
    thongBao(page, 'Đã gửi duyệt kế hoạch; lãnh đạo được thông báo để phê duyệt.'),
  ).toBeVisible();
  await expect(m.getByText(/phiên bản \d+ · Chờ duyệt/).first()).toBeVisible();
}

/**
 * Mở SCR-0403 của phiên bản đang chờ duyệt bằng tài khoản người duyệt. Hệ thống thật dựng đường dẫn từ
 * kế hoạch ở `planUrl`; giả lập bấm "Xem xét và duyệt" trên trang kế hoạch đang mở (`page`).
 */
async function moPheDuyet(
  s: PhienQt,
  page: Page,
  planUrl: string,
): Promise<{ page: Page; id: string; v: number }> {
  let reviewUrl: string;
  if (HE_THONG) {
    const u = new URL(planUrl, 'http://x');
    reviewUrl = `/plans/${u.searchParams.get('plan')}/review?v=${u.searchParams.get('v') ?? '1'}`;
  } else {
    await main(page).getByRole('button', { name: 'Xem xét và duyệt' }).click();
    await page.waitForURL(/\/plans\/[^/]+\/review/);
    reviewUrl = new URL(page.url()).pathname + new URL(page.url()).search;
  }
  page = await s.as('lanhdao', reviewUrl);
  const m = main(page);
  await expect(
    m.getByRole('heading', { level: 1, name: /^Phê duyệt (điều chỉnh )?kế hoạch/ }),
  ).toBeVisible();
  const [, id] = reviewUrl.match(/\/plans\/([^/]+)\/review/)!;
  const v = Number(new URL(reviewUrl, 'http://x').searchParams.get('v') ?? '1');
  return { page, id, v };
}

/**
 * AC-04 duyệt và ký số (xác thực nhiều lớp khi quá 15 phút từ lần gần nhất). Giả lập chỉ có một người
 * dùng nên người duyệt trùng người lập: kiểm màn hình chặn tự duyệt (tách vai trò) thay cho bước duyệt,
 * trừ kế hoạch giả lập do người khác lập (`nguoiKhacLap`). Trả `true` khi đã duyệt.
 */
async function duyetKySo(page: Page, nguoiKhacLap = HE_THONG): Promise<boolean> {
  const m = main(page);
  await m.getByRole('button', { name: 'Duyệt và ký số' }).click();
  if (!nguoiKhacLap) {
    await xacThucBuocLen(
      page,
      'lanhdao',
      m.getByRole('alert').filter({ hasText: 'Người duyệt không được là người lập kế hoạch.' }),
      false,
    );
    ghiCho(
      'duyệt và ký số: giả lập chỉ có một người dùng, đã kiểm màn hình chặn người lập tự duyệt',
    );
    return false;
  }
  await xacThucBuocLen(
    page,
    'lanhdao',
    thongBao(page, /^Đã duyệt và ký số kế hoạch phiên bản \d+/),
    false,
  );
  return true;
}

test.describe('Kế hoạch quý', { tag: '@qa-05' }, () => {
  test.describe.configure({ mode: 'serial' });
  let s: PhienQt;

  test.afterEach(async () => {
    const loi = [...s.loi];
    await s.dong();
    expect(loi, 'không có lỗi console trên trang của ứng dụng').toEqual([]);
  });

  test('HT-11 lập kế hoạch quý từ danh mục công việc, bị trả lại và được phê duyệt', async ({
    browser,
  }) => {
    test.setTimeout(HE_THONG ? 300_000 : 120_000);
    PhienQt.batDau();
    // Chế độ cao tuổi: danh mục yêu cầu QT-04 có ít nhất một hành trình chạy ở chế độ này (NFR-11)
    s = new PhienQt(browser, ['quanly', 'lanhdao', 'quantri'], true);

    // AC-05 mở kế hoạch quý nháp của đơn vị (SCR-0402)
    let planUrl = '/plans';
    let api: ApiHeThong | null = null;
    if (HE_THONG) ({ api, url: planUrl } = await keHoachHeThong(s));
    let page = await s.as('quanly', planUrl);
    let m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: /^Kế hoạch công tác/ })).toBeVisible();
    await kiemTruyCap(page, 'lập kế hoạch quý');

    // Thêm việc từ danh mục công việc (SCR-0401) nếu đơn vị có danh mục, và một việc ngoài danh mục
    await m.getByRole('button', { name: 'Thêm từ danh mục' }).click();
    const dm = page.getByRole('dialog');
    await expect(dm).toBeVisible();
    // việc nhỏ điểm nhất để không vượt tỷ trọng nhóm; dòng từ danh mục cần chọn hạn (BR-04-02)
    const muc = dm.getByRole('checkbox', { checked: false, disabled: false });
    const truocKhiThem = await soDongKeHoach(page);
    if (await muc.count()) {
      const nho = dm.getByRole('checkbox', {
        name: /\(5 điểm\)$/,
        checked: false,
        disabled: false,
      });
      await ((await nho.count()) ? nho : muc).first().check();
      await dm.getByRole('button', { name: /^Thêm/ }).last().click();
      await expect(dm).toBeHidden();
      for (let i = truocKhiThem + 1; i <= (await soDongKeHoach(page)); i++) {
        await m.getByRole('button', { name: `Sửa dòng ${i}` }).click();
        const ed = m.getByRole('region', { name: new RegExp(`^Sửa dòng ${i}`) });
        await dienHan(ed);
        await ed.getByRole('button', { name: 'Xong' }).click();
      }
    } else {
      await page.keyboard.press('Escape');
      await expect(dm).toBeHidden();
    }
    const dongMoi = await themDong(page);

    // Ca phủ định: còn dòng "KPI mơ hồ" (dữ liệu giả lập) thì không gửi duyệt được (BR-04-03)
    const kiem = m.getByRole('region', { name: 'Kiểm tra kế hoạch' });
    const loiKiem = kiem.getByRole('listitem').filter({ hasText: /^Lỗi/ });
    if (await loiKiem.count()) {
      await expect(m.getByRole('button', { name: 'Gửi duyệt' })).toBeDisabled();
      await expect(kiem.getByText('Gửi duyệt bị chặn cho tới khi sửa hết lỗi.')).toBeVisible();
      // sửa các dòng bị báo lỗi: nội dung rõ sản phẩm, số lượng, đơn vị tính
      const dongLoi = m.getByRole('row').filter({ hasText: /KPI mơ hồ ở dòng/ });
      const soDong: number[] = [];
      for (const r of await dongLoi.all())
        soDong.push(Number(await r.getByRole('cell').first().innerText()));
      for (const so of soDong) await suaDong(page, so, `${KH.noiDung} (dòng ${so})`, KH.soLuong);
    } else {
      await trenHeThong('gửi duyệt bản chưa qua kiểm tra bị máy chủ chặn', async () => {
        const kq = await api!.goi(
          'POST',
          `plans/${new URL(page.url()).searchParams.get('plan')}/versions/1/submit`,
          {
            ifMatch: '"0"',
          },
        );
        expect(
          [409, 412, 422],
          `gửi duyệt sai phiên bản hoặc chưa kiểm tra: ${kq.status}`,
        ).toContain(kq.status);
      });
    }
    await guiDuyet(page);

    // AC-04 xem so sánh phiên bản và trả lại kèm ý kiến (SCR-0403)
    let duyet = await moPheDuyet(s, page, planUrl);
    page = duyet.page;
    m = main(page);
    await expect(m.getByRole('region', { name: 'Nội dung kế hoạch' })).toContainText(KH.noiDung);
    await kiemTruyCap(page, 'phê duyệt kế hoạch');
    await m.getByRole('textbox', { name: 'Ý kiến của người duyệt' }).fill(KH.yKienTraLai);
    await m.getByRole('button', { name: 'Trả lại' }).click();
    await expect(thongBao(page, 'Đã trả lại kế hoạch cho người lập kèm ý kiến.')).toBeVisible();
    if (HE_THONG) {
      await choTrangThai(api!, `plans/${duyet.id}/versions/${duyet.v}`, 'TRA_LAI');
      planUrl = `/plans?plan=${duyet.id}&v=${duyet.v}`;
    }

    // AC-05 thấy ý kiến, sửa dòng và gửi lại
    page = await s.as('quanly', HE_THONG ? planUrl : '/plans');
    m = main(page);
    await expect(m.getByText(/phiên bản \d+ · Trả lại/).first()).toBeVisible();
    await expect(m.getByText(KH.yKienTraLai)).toBeVisible();
    await suaDong(page, dongMoi, KH.noiDungSua, KH.soLuongSua);
    await guiDuyet(page);

    // AC-04 phê duyệt và ký số, bắt buộc xác thực nhiều lớp (AAL2) khi quá 15 phút từ lần gần nhất
    const tu = new Date(Date.now() - 5_000);
    duyet = await moPheDuyet(s, page, planUrl);
    page = duyet.page;
    m = main(page);
    await expect(m.getByRole('region', { name: 'Nội dung kế hoạch' })).toContainText(KH.noiDungSua);
    await m.getByRole('textbox', { name: 'Ý kiến của người duyệt' }).fill(KH.yKienDuyet);
    await duyetKySo(page);

    // Máy chủ là nguồn sự thật: phiên bản sang DA_DUYET (sau khi ký số xong) và phát EVT-03
    await trenHeThong('kế hoạch sang Đã duyệt', async () => {
      await choTrangThai(
        api!,
        `plans/${duyet.id}/versions/${duyet.v}`,
        'DA_DUYET',
        'status',
        90_000,
      );
    });
    await kiemSuKien('EVT-03', duyet.id, tu);
  });

  test('HT-12 điều chỉnh kế hoạch đã duyệt thành phiên bản mới', async ({ browser }) => {
    test.setTimeout(HE_THONG ? 300_000 : 120_000);
    PhienQt.batDau();
    s = new PhienQt(browser, ['quanly', 'lanhdao', 'quantri']);

    // Kế hoạch đã duyệt: giả lập có sẵn kế hoạch quý trước; hệ thống thật dùng kế hoạch HT-11 vừa duyệt
    let api: ApiHeThong | null = null;
    let planUrl = '/plans';
    let planId = '';
    if (HE_THONG) {
      api = await s.apiCua('quanly');
      const ky = await kyDangMo(await s.apiCua('quantri'));
      const ds = await api.can<{ items: KeHoach[] }>('GET', 'plans', {
        query: { period: ky.id, status: 'DA_DUYET', limit: '200' },
      });
      const kh = ds.body.items[0];
      if (!kh) test.skip(true, 'Chờ: chưa có kế hoạch đã duyệt trong kỳ (chạy HT-11 trước)');
      planId = kh.id;
      planUrl = `/plans?plan=${kh.id}&v=${kh.currentVersion.versionNo}`;
    }
    let page = await s.as('quanly', planUrl);
    let m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: /^Kế hoạch công tác/ })).toBeVisible();
    if (!HE_THONG) {
      // Giả lập: phiên bản 2 của Quý IV/2026 do người khác lập đang chờ duyệt; duyệt để có bản đã duyệt
      await m.getByRole('combobox', { name: /^Quý I\/2027/ }).click();
      await page.getByRole('option', { name: /^Quý IV\/2026 .*Chờ duyệt/ }).click();
      await m.getByRole('button', { name: 'Xem xét và duyệt' }).click();
      await expect(
        main(page).getByRole('heading', { level: 1, name: /^Phê duyệt (điều chỉnh )?kế hoạch/ }),
      ).toBeVisible();
      expect(await duyetKySo(page, true)).toBe(true);
      await main(page)
        .getByRole('link', { name: /^Mở kế hoạch phiên bản/ })
        .click();
    }
    await expect(m.getByText(/phiên bản \d+ · Đã duyệt/).first()).toBeVisible();

    // AC-05 tạo phiên bản điều chỉnh có lý do (BR-04-10)
    await m.getByRole('button', { name: 'Tạo điều chỉnh' }).click();
    const hop = page.getByRole('dialog');
    await hop.getByRole('textbox', { name: 'Lý do điều chỉnh (bắt buộc)' }).fill(KH.lyDoDieuChinh);
    await hop.getByRole('button', { name: 'Tạo điều chỉnh' }).click();
    await expect(thongBao(page, /^Đã tạo phiên bản \d+ để điều chỉnh/)).toBeVisible();
    await expect(m.getByText(/phiên bản \d+ · Nháp/).first()).toBeVisible();
    await themDong(page);
    await canTyTrong(page);
    await guiDuyet(page);

    // AC-04 thấy phần thay đổi so với phiên bản đang hiệu lực rồi phê duyệt
    const tu = new Date(Date.now() - 5_000);
    if (HE_THONG) {
      // phiên bản điều chỉnh là phiên bản hiện hành mới của kế hoạch
      const v = await api!.can<KeHoach>(
        'GET',
        `plans/${planId}/versions/${Number(new URL(planUrl, 'http://x').searchParams.get('v')) + 1}`,
      );
      planUrl = `/plans?plan=${planId}&v=${(v.body as unknown as { versionNo: number }).versionNo}`;
    }
    const duyet = await moPheDuyet(s, page, planUrl);
    page = duyet.page;
    m = main(page);
    await expect(m.getByRole('region', { name: 'Tóm tắt thay đổi' })).toBeVisible();
    // màn hình điều chỉnh so sánh với phiên bản đang hiệu lực, mặc định chỉ hiện dòng thay đổi
    await expect(m.getByRole('button', { name: 'Chỉ hiện thay đổi' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await expect(m.getByText(KH.noiDung).first()).toBeVisible();
    await kiemTruyCap(page, 'phê duyệt điều chỉnh kế hoạch');
    await duyetKySo(page);
    await trenHeThong('phiên bản điều chỉnh sang Đã duyệt', async () => {
      await choTrangThai(
        api!,
        `plans/${duyet.id}/versions/${duyet.v}`,
        'DA_DUYET',
        'status',
        90_000,
      );
    });
    await kiemSuKien('EVT-03', duyet.id, tu);
  });
});
