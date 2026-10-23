import { Page, Response, expect, test } from '@playwright/test';
import { donTheoTienTo, giaoNhiemVu, kyDangMo } from '../ho-tro/du-lieu';
import {
  HE_THONG,
  PhienQt,
  chon,
  choTrangThai,
  ghiCho,
  kiemSuKien,
  kiemTruyCap,
  main,
  moNhiemVu,
  seed,
  thongBao,
  trenHeThong,
} from './chung';

/**
 * HT-18, HT-19 (QA-05, `danh-muc.json`): báo điểm nghẽn, quản lý xử lý và báo cáo lên cấp trên; đề nghị
 * gia hạn nhiệm vụ và quản lý phê duyệt, nhánh vượt ngưỡng đi qua luồng duyệt nhiều bước.
 * Truy vết: QT-06 → SCR-0601 (chi tiết nhiệm vụ), SCR-0602 (đề nghị điều chỉnh) → `reportTaskBlocker`,
 * `handleTaskBlocker`, `escalateTaskBlocker`, `previewTaskChangeRequest`, `submitTaskChangeRequest`,
 * `decideTaskChangeRequest` → MOD-02 (SVC-05), workflow-service (SVC-07) cho nhánh vượt ngưỡng →
 * `task.task`, `task.task_blocker`, `task.task_change_request`, EVT-06, EVT-21.
 * Phía người quản lý (xử lý điểm nghẽn, quyết định đề nghị) web chưa có màn hình, nên trên hệ thống
 * thật bước đó gọi API bằng danh tính của quản lý; giả lập ghi "chờ".
 * Nhiệm vụ: hệ thống thật dùng nhiệm vụ đột xuất `quanly` giao cho `canbo` (`he-thong.json`); giả lập dùng
 * nhiệm vụ đang thực hiện đồng chí chủ trì trong dữ liệu giả lập.
 */
const TD = seed<{
  diemNghen: string;
  xuLyDiemNghen: string;
  lyDoGiaHan: string;
  giaHanThemNgay: number;
  ngayGiaHanVuotNguong: number;
}>('tienDo');

interface NhiemVu {
  id: string;
  title: string;
  dueDate: string;
  rowVersion: number;
  blockers: { id: string; status: string; rowVersion: number }[];
  changeRequests: { id: string; status: string; rowVersion?: number }[];
}

/** Nhiệm vụ đầu vào trên hệ thống thật: `quanly` giao cho `canbo`, `canbo` nhận việc. */
async function nhiemVuHeThong(s: PhienQt): Promise<{ id: string; title: string; kyId: string }> {
  const quanLy = await s.apiCua('quanly');
  const ky = await kyDangMo(await s.apiCua('quantri'));
  const nv = await giaoNhiemVu(quanLy, await s.danhTinh('quanly'), await s.danhTinh('canbo'), ky);
  const canBo = await s.apiCua('canbo');
  const ct = await canBo.can<NhiemVu>('GET', `tasks/${nv.id}`);
  await canBo.can('POST', `tasks/${nv.id}/accept`, { ifMatch: ct.etag ?? `"${ct.body.rowVersion}"` });
  return { id: nv.id, title: nv.title, kyId: ky.id };
}

const bat = (page: Page, duongDan: RegExp) =>
  page.waitForResponse(
    (r: Response) => r.request().method() === 'POST' && duongDan.test(new URL(r.url()).pathname),
  );

/** `yyyy-mm-dd` cộng `n` ngày. */
const congNgay = (ngay: string, n: number) => {
  const d = new Date(ngay + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};

test.describe('Tiến độ và điều chỉnh', { tag: '@qa-05' }, () => {
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
    const loi = [...s.loi];
    await s.dong();
    expect(loi, 'không có lỗi console trên trang của ứng dụng').toEqual([]);
  });

  test('HT-18 báo điểm nghẽn, quản lý xử lý và báo cáo lên cấp trên', async ({ browser }) => {
    test.setTimeout(HE_THONG ? 300_000 : 120_000);
    PhienQt.batDau();
    s = new PhienQt(browser, ['canbo', 'quanly', 'quantri']);
    let nv: { id: string; title: string } | null = null;
    if (HE_THONG) ({ kyId, ...nv } = await nhiemVuHeThong(s));

    // AC-06 báo điểm nghẽn trên SCR-0601
    const page = await moNhiemVu(s, nv?.title ?? null);
    const m = main(page);
    await m.getByRole('button', { name: 'Báo điểm nghẽn' }).click();
    const hop = page.getByRole('dialog', { name: 'Báo điểm nghẽn' });
    await hop.getByRole('textbox', { name: 'Nội dung điểm nghẽn' }).fill(TD.diemNghen);
    const chonO = hop.getByRole('combobox', { name: 'Chọn' });
    await chon(page, chonO.nth(0), /Phối hợp/);
    await chon(page, chonO.nth(0), /Khách quan/);
    await chon(page, chonO.nth(0));
    const tu = new Date(Date.now() - 5_000);
    const gui = HE_THONG ? bat(page, /\/api\/v1\/tasks\/[^/]+\/blockers$/) : null;
    await hop.getByRole('button', { name: 'Gửi điểm nghẽn' }).click();
    await expect(hop).toBeHidden();
    await expect(thongBao(page, /^Đã ghi điểm nghẽn và thông báo cho đồng chí /)).toBeVisible();
    await m.getByRole('tab', { name: /^Điểm nghẽn \(\d+\)$/ }).click();
    await expect(m.getByRole('tabpanel').getByText(TD.diemNghen)).toBeVisible();
    await kiemTruyCap(page, 'điểm nghẽn của nhiệm vụ');
    let blockerId = '';
    if (gui) {
      const ct = (await (await gui).json()) as NhiemVu;
      blockerId = ct.blockers.find((b) => b.status !== 'DA_XU_LY')?.id ?? ct.blockers.at(-1)!.id;
    }
    // EVT-21: người giao nhận thông báo điểm nghẽn
    await kiemSuKien('EVT-21', nv?.id ?? '', tu);

    // AC-05 xử lý điểm nghẽn và báo cáo lên cấp trên (web chưa có màn hình cho người quản lý)
    ghiCho('quản lý xử lý, báo cáo điểm nghẽn lên cấp trên: web chưa có màn hình, kiểm qua API trên hệ thống thật');
    await trenHeThong('quản lý xử lý điểm nghẽn rồi báo cáo lên cấp trên', async () => {
      const api = await s.apiCua('quanly');
      const ct = await api.can<NhiemVu>('GET', `tasks/${nv!.id}`);
      const b = ct.body.blockers.find((x) => x.id === blockerId)!;
      const { status: st } = await s.goiTho('quanly', 'PATCH', `tasks/${nv!.id}/blockers/${blockerId}`, {
        body: { resolutionNote: TD.xuLyDiemNghen },
        ifMatch: `"${b.rowVersion}"`,
        kieu: 'application/merge-patch+json',
      });
      expect(st, 'xử lý điểm nghẽn').toBeLessThan(300);
      const len = await api.can<{ status: string }>(
        'POST',
        `tasks/${nv!.id}/blockers/${blockerId}/escalate`,
        { body: { note: TD.xuLyDiemNghen } },
      );
      expect(len.status).toBeLessThan(300);
    });
  });

  test('HT-19 đề nghị gia hạn nhiệm vụ và quản lý phê duyệt', async ({ browser }) => {
    test.setTimeout(HE_THONG ? 300_000 : 120_000);
    PhienQt.batDau();
    s = new PhienQt(browser, ['canbo', 'quanly', 'quantri']);
    let nv: { id: string; title: string } | null = null;
    if (HE_THONG) ({ kyId, ...nv } = await nhiemVuHeThong(s));

    // AC-06 lập đề nghị gia hạn (SCR-0602); hộp thoại xem trước người duyệt và số lần đã dùng
    const page = await moNhiemVu(s, nv?.title ?? null);
    const m = main(page);
    await m.getByRole('button', { name: 'Đề nghị gia hạn' }).click();
    const hop = page.getByRole('dialog', { name: 'Đề nghị gia hạn, điều chỉnh nhiệm vụ' });
    await expect(hop.getByRole('button', { name: 'Gia hạn' })).toHaveAttribute('aria-pressed', 'true');
    const hanCu = (await hop.getByText(/hạn hiện tại \d{2}\/\d{2}\/\d{4}/).innerText()).match(
      /(\d{2})\/(\d{2})\/(\d{4})/,
    )!;
    const iso = `${hanCu[3]}-${hanCu[2]}-${hanCu[1]}`;
    await hop.getByRole('textbox', { name: 'Hạn mới đề nghị *' }).fill(congNgay(iso, TD.giaHanThemNgay));
    await chon(page, hop.getByRole('combobox', { name: 'Chọn', exact: true }), /Khách quan/);
    await hop.getByRole('textbox', { name: 'Lý do chi tiết *' }).fill(TD.lyDoGiaHan);
    await expect(hop.getByText(/^Người duyệt: .+ · đã dùng \d+\/\d+ lần gia hạn trong quý$/)).toBeVisible();
    await kiemTruyCap(page, 'đề nghị gia hạn');
    const gui = HE_THONG ? bat(page, /\/api\/v1\/tasks\/[^/]+\/change-requests$/) : null;
    await hop.getByRole('button', { name: 'Gửi đề nghị' }).click();
    await expect(hop).toBeHidden();
    await expect(thongBao(page, /^Đã gửi đề nghị tới đồng chí /)).toBeVisible();
    await m.getByRole('tab', { name: /^Đề nghị \(\d+\)$/ }).click();
    await expect(m.getByRole('tabpanel').getByText(TD.lyDoGiaHan)).toBeVisible();
    let crId = '';
    if (gui) crId = ((await (await gui).json()) as NhiemVu).changeRequests.at(-1)!.id;

    // AC-05 quyết định đề nghị (web chưa có màn hình cho người quản lý): duyệt, nhiệm vụ vẫn đang thực hiện
    ghiCho('quản lý quyết định đề nghị gia hạn: web chưa có màn hình, kiểm qua API trên hệ thống thật');
    const tu = new Date(Date.now() - 5_000);
    await trenHeThong('quản lý duyệt đề nghị gia hạn', async () => {
      const api = await s.apiCua('quanly');
      const ct = await api.can<NhiemVu>('GET', `tasks/${nv!.id}`);
      await api.can('POST', `tasks/${nv!.id}/change-requests/${crId}/decision`, {
        ifMatch: ct.etag ?? `"${ct.body.rowVersion}"`,
        body: { approve: true, causeDecided: 'KHACH_QUAN', note: 'Đồng ý gia hạn.' },
      });
      await choTrangThai(api, `tasks/${nv!.id}`, 'DANG_THUC_HIEN');
      const sau = await api.can<NhiemVu>('GET', `tasks/${nv!.id}`);
      expect(sau.body.dueDate, 'hạn mới sau khi duyệt gia hạn').toBe(congNgay(iso, TD.giaHanThemNgay));
    });
    await kiemSuKien('EVT-06', nv?.id ?? '', tu);

    // Ca phủ định: đề nghị vượt ngưỡng gia hạn đi qua luồng duyệt nhiều bước, không duyệt thẳng được
    await trenHeThong('đề nghị vượt ngưỡng đi qua luồng duyệt nhiều bước', async () => {
      const canBo = await s.apiCua('canbo');
      const ct = await canBo.can<NhiemVu>('GET', `tasks/${nv!.id}`);
      const xem = await canBo.can<{ requiresWorkflow?: boolean; approvalSteps?: unknown[] }>(
        'POST',
        `tasks/${nv!.id}/change-requests/preview`,
        {
          body: {
            type: 'GIA_HAN',
            newDueDate: congNgay(ct.body.dueDate, TD.ngayGiaHanVuotNguong),
            causeClaimed: 'KHACH_QUAN',
            reason: TD.lyDoGiaHan,
          },
        },
      );
      const nhieuBuoc =
        xem.body.requiresWorkflow === true || (xem.body.approvalSteps?.length ?? 0) > 1;
      expect(nhieuBuoc, 'xem trước đề nghị vượt ngưỡng phải báo luồng duyệt nhiều bước').toBe(true);
    });
  });
});
