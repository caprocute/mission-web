import { Page, Response, expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { donTheoTienTo, kyDangMo } from '../ho-tro/du-lieu';
import {
  HE_THONG,
  PhienQt,
  chon,
  choTrangThai,
  ghiCho,
  kiemSuKien,
  kiemTruyCap,
  main,
  ngayTrongKhoang,
  seed,
  thongBao,
  trenHeThong,
} from './chung';

/**
 * HT-13, HT-14, HT-15 (QA-05, `danh-muc.json`): giao nhiệm vụ từ kế hoạch và cán bộ nhận việc; tạo
 * nhiệm vụ từ văn bản chỉ đạo đến; cán bộ đăng ký nhiệm vụ và quản lý quyết định.
 * Truy vết: QT-05 → SCR-0501 (giao việc), SCR-0502 (văn bản đến), SCR-0503 (việc của tôi) →
 * `createTaskDraft`, `checkTaskAssignment`, `assignTask`, `listMyTasks`, `acceptTask`,
 * `createTaskFromDocument`, `registerTask`, `decideTaskRegistration` (SCR-0601) → MOD-02 (SVC-05) → `task.task`
 * (`NHAP` → `DA_GIAO` → `DANG_THUC_HIEN`), EVT-04, EVT-06, EVT-24.
 * Giả lập: kho giao việc tách khỏi kho "Việc của tôi" nên bước nhận việc dùng một nhiệm vụ "Đã giao"
 * có sẵn của dữ liệu giả lập; hệ thống thật dùng đúng nhiệm vụ vừa giao.
 */
const GV = seed<{
  ten: string;
  sanPham: string;
  soLuong: number;
  donViTinh: string;
  minhChung: string;
  tieuChuan: string;
}>('giaoViec');
const VB = seed<{ trichYeu: string; tenNhiemVu: string }>('vanBan');

/** Điền phần sản phẩm và tiêu chuẩn còn trống của SCR-0501. */
async function dienSanPham(page: Page) {
  const sp = main(page).getByRole('region', { name: 'Sản phẩm và tiêu chuẩn' });
  const o: [string, string][] = [
    ['Sản phẩm *', GV.sanPham],
    ['Đơn vị tính *', GV.donViTinh],
    ['Minh chứng yêu cầu *', GV.minhChung],
    ['Tiêu chuẩn chất lượng *', GV.tieuChuan],
  ];
  for (const [nhan, giaTri] of o) {
    const box = sp.getByRole('textbox', { name: nhan });
    if (!(await box.inputValue())) await box.fill(giaTri);
  }
  const sl = sp.getByRole('spinbutton', { name: 'Số lượng *' });
  if (!Number(await sl.inputValue())) await sl.fill(String(GV.soLuong));
}

const bat = (page: Page, phuongThuc: string, duongDan: RegExp) =>
  page.waitForResponse(
    (r: Response) =>
      r.request().method() === phuongThuc && duongDan.test(new URL(r.url()).pathname),
  );

test.describe('Giao việc', { tag: '@qa-05' }, () => {
  test.describe.configure({ mode: 'serial' });
  let s: PhienQt;
  let kyId: string | null = null;

  test.afterEach(async () => {
    if (HE_THONG) {
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

  test('HT-13 giao nhiệm vụ từ kế hoạch và cán bộ nhận việc', async ({ browser }) => {
    test.setTimeout(HE_THONG ? 300_000 : 120_000);
    PhienQt.batDau();
    // Chế độ cao tuổi cho QT-05 (NFR-11)
    s = new PhienQt(browser, ['quanly', 'canbo', 'quantri'], true);
    if (HE_THONG) kyId = (await kyDangMo(await s.apiCua('quantri'))).id;

    // AC-05 soạn nhiệm vụ từ dòng kế hoạch đã duyệt (SCR-0501)
    let page = await s.as('quanly', '/tasks/assign');
    let m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: 'Giao nhiệm vụ' })).toBeVisible();
    await expect(m.getByRole('button', { name: 'Từ kế hoạch quý' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await kiemTruyCap(page, 'giao nhiệm vụ');
    await chon(page, m.getByRole('combobox', { name: 'Chọn dòng kế hoạch đã duyệt' }));
    await m.getByRole('textbox', { name: 'Tên nhiệm vụ *' }).fill(GV.ten);
    await chon(
      page,
      m.getByRole('combobox', { name: 'Chọn người chủ trì' }),
      HE_THONG ? (await s.danhTinh('canbo')).ten : undefined,
    );
    const han = m.getByRole('textbox', { name: 'Hạn hoàn thành *' });
    await han.fill(ngayTrongKhoang(await han.getAttribute('min'), await han.getAttribute('max')));
    await dienSanPham(page);

    // Ca phủ định trên màn hình: người nghiệm thu trùng người chủ trì thì không giao được (BR-05-02)
    const kiem = m.getByRole('region', { name: 'Kiểm tra trước khi giao' });
    const nghiemThu = m.getByRole('combobox', { name: /·/ }).last();
    const nguoiNghiemThu = (await nghiemThu.innerText()).trim();
    const tenChuTri = (
      await m
        .getByRole('combobox', { name: 'Chọn người chủ trì' })
        .or(m.getByRole('region', { name: 'Phân công' }).getByRole('combobox').first())
        .first()
        .innerText()
    )
      .split('·')[0]
      .trim();
    if (tenChuTri) {
      await chon(page, nghiemThu, new RegExp(`^${tenChuTri}`));
      await expect(
        kiem
          .getByRole('listitem')
          .filter({ hasText: 'Người nghiệm thu phải khác người thực hiện nhiệm vụ.' }),
      ).toBeVisible();
      await expect(m.getByRole('button', { name: 'Giao việc' })).toBeDisabled();
      await chon(page, nghiemThu, new RegExp(`^${nguoiNghiemThu.split('·')[0].trim()}`));
    }
    await expect(kiem.getByRole('listitem').filter({ hasText: /^Chưa đạt/ })).toHaveCount(0);

    // Giao việc (`checkTaskAssignment`, `assignTask`); máy chủ phát EVT-04
    const tu = new Date(Date.now() - 5_000);
    const giao = HE_THONG ? bat(page, 'POST', /\/api\/v1\/tasks\/[^/]+\/assign$/) : null;
    await m.getByRole('button', { name: 'Giao việc' }).click();
    await expect(m.getByRole('heading', { level: 1, name: 'Đã giao nhiệm vụ' })).toBeVisible();
    await expect(m.getByText(/Đã giao nhiệm vụ .+ cho đồng chí/)).toBeVisible();
    let taskId = '';
    if (giao) taskId = new URL((await giao).url()).pathname.split('/').at(-2)!;
    await trenHeThong('nhiệm vụ sang Đã giao', async () => {
      await choTrangThai(await s.apiCua('quanly'), `tasks/${taskId}`, 'DA_GIAO');
    });
    await kiemSuKien('EVT-04', taskId, tu);

    // Ca phủ định: giao việc cho người ngoài phạm vi đơn vị quản lý bị máy chủ từ chối
    await trenHeThong('giao cho người ngoài đơn vị quản lý bị từ chối', async () => {
      const api = await s.apiCua('quanly');
      const nhap = await api.goi<{ id: string; rowVersion: number }>('POST', 'tasks', {
        body: {
          status: 'NHAP',
          periodId: kyId,
          unitName: '',
          assignerId: (await s.danhTinh('quanly')).sub,
          assignerName: (await s.danhTinh('quanly')).ten,
          source: 'DOT_XUAT',
          basis: 'Kiểm thử đầu cuối tự động',
          title: `${GV.ten} (ngoài đơn vị)`,
          taskGroup: 'THUONG_XUYEN',
          priority: 'TRUNG_BINH',
          leadPersonId: randomUUID(),
          coAssignees: [],
          milestones: [],
          dueDate: ngayTrongKhoang(null, null),
          productName: GV.sanPham,
          quantity: 1,
          unit: GV.donViTinh,
          evidenceRequired: GV.minhChung,
          qualityCriteria: GV.tieuChuan,
        },
      });
      if (nhap.status >= 400) {
        expect([400, 403, 404, 422], `tạo nháp giao người ngoài đơn vị: ${nhap.status}`).toContain(
          nhap.status,
        );
        return;
      }
      const gui = await api.goi('POST', `tasks/${nhap.body.id}/assign`, {
        ifMatch: nhap.etag ?? `"${nhap.body.rowVersion}"`,
        body: {},
      });
      expect([400, 403, 404, 409, 422], `giao người ngoài đơn vị: ${gui.status}`).toContain(
        gui.status,
      );
    });

    // AC-06 thấy nhiệm vụ trong "Việc của tôi" và nhận việc (SCR-0503 → SCR-0601)
    page = await s.as('canbo', '/tasks');
    m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: 'Việc của tôi' })).toBeVisible();
    const ds = m.getByRole('region', { name: 'Danh sách nhiệm vụ' });
    if (HE_THONG) {
      await m.getByRole('searchbox', { name: 'Tìm nhiệm vụ' }).fill(GV.ten);
      await ds.getByRole('link', { name: GV.ten }).click();
    } else {
      await ds
        .getByRole('row')
        .filter({ hasText: 'Chủ trì' })
        // chế độ cao tuổi rút gọn cột (không có cột trạng thái): việc chủ trì chưa có tiến độ là việc mới giao
        .filter({ has: page.locator('[role=progressbar][aria-valuenow="0"]') })
        .first()
        .getByRole('link')
        .click();
    }
    await expect(m.getByText('Đã giao', { exact: true })).toBeVisible();
    const tu2 = new Date(Date.now() - 5_000);
    await m.getByRole('button', { name: 'Nhận việc' }).click();
    await expect(thongBao(page, 'Đã nhận việc.')).toBeVisible();
    await expect(m.getByText('Đang thực hiện', { exact: true })).toBeVisible();
    await trenHeThong('nhiệm vụ sang Đang thực hiện', async () => {
      await choTrangThai(await s.apiCua('canbo'), `tasks/${taskId}`, 'DANG_THUC_HIEN');
    });
    await kiemSuKien('EVT-06', taskId, tu2);
  });

  test('HT-14 tạo nhiệm vụ từ văn bản chỉ đạo đến', async ({ browser }) => {
    test.setTimeout(HE_THONG ? 300_000 : 120_000);
    PhienQt.batDau();
    s = new PhienQt(browser, ['quanly']);

    // Văn bản đến đang chờ xử lý (SCR-0502); hệ thống thật dùng văn bản thử nạp sẵn (EVT-24)
    const page = await s.as('quanly', '/tasks/documents');
    const m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: 'Văn bản đến' })).toBeVisible();
    const bang = m.getByRole('table', { name: 'Danh sách văn bản đến' });
    const dong = bang
      .getByRole('row')
      .filter({ hasText: HE_THONG ? VB.trichYeu : 'Chờ xác nhận' })
      .first();
    if (HE_THONG && !(await dong.isVisible())) {
      ghiCho(
        `chưa có văn bản thử "${VB.trichYeu}" trong hộp văn bản đến (dữ liệu khởi tạo của EVT-24)`,
      );
      test.skip(true, 'Chờ: chưa nạp văn bản thử');
    }
    await kiemSuKien('EVT-24', VB.trichYeu, new Date(Date.now() - 30 * 86_400_000));
    await dong.getByRole('link').first().click();
    await page.waitForURL(/\/tasks\/documents\/[^/]+$/);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Tạo nhiệm vụ từ văn bản chỉ đạo' }),
    ).toBeVisible();
    await kiemTruyCap(page, 'tạo nhiệm vụ từ văn bản');

    // Xác nhận một nhiệm vụ AI trích không trùng nhiệm vụ cũ, đổi tên theo tiền tố của lần chạy
    const nv = m.getByRole('article').filter({ hasText: 'Chờ xác nhận' }).first();
    const so = Number(
      (await nv.getAttribute('aria-label'))?.match(/\d+/)?.[0] ??
        (await nv.getByRole('button').first().innerText()).match(/\d+/)![0],
    );
    await nv.getByRole('button', { name: `Nhiệm vụ ${so}`, exact: true }).click();
    const xuLy = m.getByRole('group', { name: `Xử lý nhiệm vụ ${so}` });
    // ô do AI điền chỉ sửa được sau khi bấm Sửa; ô đã sửa thành dữ liệu do người nhập
    await xuLy.getByRole('button', { name: 'Sửa' }).click();
    await nv.getByRole('textbox', { name: /^Tên nhiệm vụ/ }).fill(VB.tenNhiemVu);
    // chọn lại nhiệm vụ đang sửa để hiện nhóm thao tác của nó
    const chonNv = nv.getByRole('button', { name: `Nhiệm vụ ${so}`, exact: true });
    if ((await chonNv.getAttribute('aria-pressed')) !== 'true') await chonNv.click();
    const tao = HE_THONG ? bat(page, 'POST', /\/api\/v1\/tasks\/from-document$/) : null;
    await xuLy.getByRole('button', { name: `Xác nhận nhiệm vụ ${so}` }).click();
    await expect(
      thongBao(page, new RegExp(`^Đã xác nhận nhiệm vụ ${so}, tạo .+ ở trạng thái Nháp\\.`)),
    ).toBeVisible();
    let taskId = '';
    if (tao) taskId = ((await (await tao).json()) as { taskId?: string }).taskId ?? '';

    // Giao các nhiệm vụ đã xác nhận (`assignTask`); máy chủ phát EVT-04
    const tu = new Date(Date.now() - 5_000);
    await m.getByRole('button', { name: /^Xác nhận và giao [1-9]\d* nhiệm vụ$/ }).click();
    await expect(thongBao(page, /^Đã giao \d+ nhiệm vụ: /)).toBeVisible();
    await trenHeThong('nhiệm vụ từ văn bản sang Đã giao', async () => {
      expect(taskId, 'phản hồi tạo nhiệm vụ từ văn bản có mã nhiệm vụ').not.toBe('');
      await choTrangThai(await s.apiCua('quanly'), `tasks/${taskId}`, 'DA_GIAO');
    });
    await kiemSuKien('EVT-04', taskId, tu);
  });

  test('HT-15 cán bộ đăng ký nhiệm vụ và quản lý quyết định', async ({ browser }) => {
    test.setTimeout(HE_THONG ? 300_000 : 120_000);
    PhienQt.batDau();
    s = new PhienQt(browser, ['canbo', 'quanly']);

    // AC-06 đăng ký nhiệm vụ từ danh mục trên "Việc của tôi" (SCR-0503)
    const page = await s.as('canbo', '/tasks');
    const m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: 'Việc của tôi' })).toBeVisible();
    await m.getByRole('button', { name: 'Đăng ký nhiệm vụ' }).click();
    const dk = m.getByRole('region', { name: 'Đăng ký nhiệm vụ' });
    await chon(page, dk.getByRole('combobox', { name: 'Chọn mục danh mục' }));
    await dk.getByRole('spinbutton', { name: 'Số lượng' }).fill('1');
    const han = dk.getByRole('textbox', { name: 'Hạn đề xuất' });
    await han.fill(ngayTrongKhoang(await han.getAttribute('min'), await han.getAttribute('max')));
    const gui = HE_THONG ? bat(page, 'POST', /\/api\/v1\/tasks\/registrations$/) : null;
    await dk.getByRole('button', { name: 'Gửi đăng ký' }).click();
    await expect(
      dk.getByText('Đã gửi đăng ký nhiệm vụ. Người quản lý trực tiếp sẽ xem xét.'),
    ).toBeVisible();
    let taskId: string;
    if (gui) {
      taskId = ((await (await gui).json()) as { id: string }).id;
    } else {
      // giả lập chạy API trong trình duyệt: lấy mã từ dòng "Chờ duyệt đăng ký" vừa thêm vào danh sách
      await m
        .getByRole('region', { name: 'Danh sách nhiệm vụ' })
        .getByRole('row')
        .filter({ hasText: 'Chờ duyệt đăng ký' })
        .first()
        .getByRole('link')
        .first()
        .click();
      await page.waitForURL(/\/tasks\/[0-9a-f-]{36}$/);
      taskId = new URL(page.url()).pathname.split('/').pop()!;
    }

    // AC-05 xem xét đăng ký trên chi tiết nhiệm vụ (lối vào từ thông báo đăng ký): chốt hạn, số lượng, duyệt
    const tu = new Date(Date.now() - 5_000);
    const ql = await s.as('quanly', `/tasks/${taskId}`);
    const qm = main(ql);
    const hop = qm.getByRole('region', { name: 'Đăng ký nhiệm vụ chờ duyệt' });
    await expect(hop).toBeVisible();
    await hop.getByRole('button', { name: 'Duyệt đăng ký' }).click();
    const dlg = ql.getByRole('dialog', { name: 'Duyệt đăng ký nhiệm vụ' });
    await expect(dlg.getByRole('textbox', { name: 'Hạn hoàn thành' })).not.toHaveValue('');
    await dlg.getByRole('spinbutton', { name: /^Số lượng/ }).fill('2');
    await kiemTruyCap(ql, 'duyệt đăng ký nhiệm vụ');
    await dlg.getByRole('button', { name: 'Duyệt và giao việc' }).click();
    await expect(dlg).toBeHidden();
    await expect(thongBao(ql, 'Đã duyệt đăng ký và giao nhiệm vụ.')).toBeVisible();
    await expect(hop).toBeHidden();
    await trenHeThong('nhiệm vụ đăng ký sang Đã giao', async () => {
      await choTrangThai(await s.apiCua('quanly'), `tasks/${taskId}`, 'DA_GIAO');
    });
    await kiemSuKien('EVT-04', taskId, tu);
  });
});
