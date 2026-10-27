import { expect, test } from '@playwright/test';
import { batCaoTuoi, chiHeThong, dau, kiemAxe, xacThucBuocLen } from '../ho-tro/kiem-tra';
import { HE_THONG, cho, ghiCho } from '../ho-tro/moi-truong';
import { Phien, main } from '../ho-tro/phien';
import { choSuKien, coKafkaUi } from '../ho-tro/su-kien';
import { api, kiemMoiTruong } from './chung';

/**
 * QT-01 Khởi tạo tenant và tổ chức (SCR-0101..SCR-0104) → `/api/v1/config/tenants*`,
 * `/api/v1/org/*` → MOD-08, SVC-02, SVC-04 → EVT-14, EVT-15, EVT-16. Hành trình HT-05..HT-07.
 */
interface DonVi {
  id: string;
  name: string;
  children?: DonVi[];
}

/** Tìm đơn vị theo tên trong cây `roots` trả về của `getOrgTree` (`OrgTree`). */
function timDonVi(ds: DonVi[], ten: string): DonVi | undefined {
  for (const d of ds) {
    if (d.name === ten) return d;
    const con = d.children ? timDonVi(d.children, ten) : undefined;
    if (con) return con;
  }
  return undefined;
}

test.describe('QT-01 Khởi tạo tenant và tổ chức', { tag: '@qa-04' }, () => {
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

  test('HT-05 QT-01 quản trị nền tảng khởi tạo, cấp phát và kích hoạt tenant mới', async () => {
    test.setTimeout(HE_THONG ? 600_000 : 120_000);
    // Tạo tenant trên dev là thay đổi khó gỡ (không gian đăng nhập, tên miền), nên chỉ chạy khi cho phép rõ
    if (HE_THONG && process.env.E2E_CHO_PHEP_TAO_TENANT !== '1') {
      cho('tạo tenant thật trên dev cần đặt E2E_CHO_PHEP_TAO_TENANT=1');
    }
    const ma = `e2e_${dau()}`;
    const ten = `Đơn vị thử nghiệm ${ma}`;

    // AC-01 ở chế độ Đảng viên cao tuổi: danh sách tenant (SCR-0101) → khởi tạo (SCR-0102)
    const page = await s.trang('nentang', '/admin/tenants');
    const m = main(page);
    await batCaoTuoi(page);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Danh sách đơn vị triển khai' }),
    ).toBeVisible();
    await kiemAxe(page, 'danh sách tenant');
    await m.getByRole('link', { name: 'Khởi tạo đơn vị triển khai' }).click();
    await expect(
      m.getByRole('heading', { level: 1, name: 'Khởi tạo đơn vị triển khai' }),
    ).toBeVisible();

    // Bước 1: thông tin đơn vị (`createConfigTenant`)
    await m.getByRole('textbox', { name: 'Tên đơn vị triển khai' }).fill(ten);
    await m.getByRole('textbox', { name: /^Mã đơn vị/ }).fill(ma);
    await m.getByRole('textbox', { name: 'Số hợp đồng' }).fill('HD-E2E/2026');
    await m.getByRole('textbox', { name: 'Hợp đồng hiệu lực đến' }).fill('2027-12-31');
    await m.getByRole('spinbutton', { name: 'Số tài khoản dự kiến' }).fill('500');
    await m.getByRole('spinbutton', { name: 'Số đơn vị dự kiến' }).fill('10');
    await m
      .getByRole('textbox', { name: 'Đầu mối quản trị của đơn vị' })
      .fill('Quản trị thử nghiệm');
    await m.getByRole('textbox', { name: 'Thư điện tử đầu mối' }).fill('quantri.thu@example.vn');
    await kiemAxe(page, 'khởi tạo tenant bước 1');
    const tiep = m.getByRole('button', { name: /Tiếp tục/ });
    await tiep.click();

    // Bước 2: mức cô lập và cụm máy chủ theo gợi ý của hệ thống
    await expect(m.getByRole('heading', { name: '2. Mức cô lập dữ liệu' })).toBeVisible();
    await expect(m.getByRole('radio', { name: /^Dùng chung/ })).toBeChecked();
    await tiep.click();

    // Bước 3: bộ quy định đánh giá, bộ giao diện rồi cấp phát (`provisionConfigTenant` 202 + `getJob`)
    await expect(
      m.getByRole('heading', { name: '3. Bộ quy định đánh giá và bộ giao diện' }),
    ).toBeVisible();
    await m
      .getByRole('combobox', { name: 'Bộ quy định đánh giá luồng Đảng' })
      .selectOption({ index: 1 });
    await m
      .getByRole('combobox', { name: 'Bộ quy định đánh giá luồng Nhà nước' })
      .selectOption({ index: 1 });
    await tiep.click();
    await expect(m.getByRole('region', { name: 'Tiến trình cấp phát hạ tầng' })).toBeVisible();
    await kiemAxe(page, 'tiến trình cấp phát');
    await expect(
      m.getByRole('status').filter({ hasText: /Cấp phát hạ tầng cho đơn vị thành công/ }),
    ).toBeVisible({
      timeout: HE_THONG ? 300_000 : 60_000,
    });

    // Bước 4: hệ thống đăng nhập của đơn vị
    await expect(m.getByRole('heading', { name: '4. Đăng nhập' })).toBeVisible();
    await m.getByRole('button', { name: 'Thêm hệ thống đăng nhập' }).click();
    await m
      .getByRole('textbox', { name: 'Tên định danh hệ thống đăng nhập 1' })
      .fill(`sso-${ma.replace('_', '-')}`);
    await m
      .getByRole('textbox', { name: 'Địa chỉ hệ thống đăng nhập 1' })
      .fill('https://sso.example.vn/realms/thu');
    await m.getByRole('button', { name: 'Kiểm tra kết nối' }).click();
    await expect(tiep).toBeEnabled();
    await tiep.click();

    // Bước 5: nguồn nhân sự
    await expect(m.getByRole('heading', { name: '5. Tổ chức, nhân sự' })).toBeVisible();
    await m.getByRole('checkbox', { name: 'CSDL CBCCVC qua NDXP' }).check();
    await tiep.click();

    // Bước 6: kiểm tra tổng thể và kích hoạt (`activateConfigTenant`); trạng thái hiện bằng nhãn nghiệp vụ
    await expect(
      m.getByRole('heading', { name: '6. Kiểm tra tổng thể và kích hoạt' }),
    ).toBeVisible();
    await kiemAxe(page, 'kiểm tra và kích hoạt');
    await m.getByRole('button', { name: 'Kích hoạt' }).click();
    await expect(
      m.getByRole('status').filter({ hasText: `Đã kích hoạt đơn vị ${ten}` }),
    ).toBeVisible();
    await expect(m.getByText(/Đang vận hành/).first()).toBeVisible();
    await expect(m.getByText(/\b(DANG_VAN_HANH|ACTIVE|PROVISIONING)\b/)).toHaveCount(0);

    await chiHeThong('tenant đọc lại qua listConfigTenants', async () => {
      const c = await api(s, 'nentang');
      const ds = await c.can<{ items: { code: string; status: string }[] }>(
        'GET',
        'config/tenants',
        {
          query: { q: ma, limit: '20' },
        },
      );
      expect(ds.body.items.some((t) => t.code === ma)).toBe(true);
      await c.dong();
    });
  });

  test('HT-05 QT-01 ca phủ định: quản trị tenant không mở được danh sách tenant', async () => {
    const page = await s.trang('quantri', '/admin/tenants');
    const m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Không có quyền truy cập' }),
    ).toBeVisible();
    await chiHeThong('listConfigTenants trả 403 cho quản trị tenant', async () => {
      const c = await api(s, 'quantri');
      expect((await c.goi('GET', 'config/tenants')).status).toBe(403);
      await c.dong();
    });
  });

  test('HT-06 QT-01 quản trị tenant ghi thay đổi đơn vị có hiệu lực từ ngày', async () => {
    const page = await s.trang('quantri', '/admin/org');
    const m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: 'Cây tổ chức' })).toBeVisible();
    const cay = m.getByRole('tree', { name: 'Cơ cấu tổ chức' });
    const nut = cay.getByRole('treeitem', { level: 2 }).first();
    if (HE_THONG && (await nut.count()) === 0)
      cho('cây tổ chức trên dev chưa có đơn vị cấp dưới (seed tổ chức)');
    const hienTai = (await nut.innerText()).trim().split('\n')[0];
    // Lần chạy trước trả tên cũ từ ngày mai, nên trong ngày tên còn hậu tố E2E: bỏ hậu tố để tên không dài thêm
    const goc = hienTai.replace(/( E2E \d+)+$/, '');
    const moi = `${goc} E2E ${dau()}`;
    await nut.click();
    await kiemAxe(page, 'cây tổ chức');

    // Đổi tên theo quyết định có ngày hiệu lực (`changeOrgUnit`, SCR-0103 "Thay đổi tổ chức")
    const doiTen = async (tu: string, sang: string, so: string, hieuLuc?: string) => {
      await cay.getByRole('treeitem', { name: tu }).click();
      await m.getByRole('button', { name: 'Thay đổi tổ chức' }).click();
      const d = page.getByRole('dialog', { name: /^Thay đổi tổ chức/ });
      await expect(d.getByRole('radio', { name: 'Đổi tên' })).toBeChecked();
      if (hieuLuc) await d.getByRole('textbox', { name: 'Ngày hiệu lực' }).fill(hieuLuc);
      await d.getByRole('textbox', { name: 'Văn bản căn cứ' }).fill(so);
      await d.getByRole('textbox', { name: 'Tên mới' }).fill(sang);
      await d.getByRole('button', { name: 'Ghi thay đổi' }).click();
      await xacThucBuocLen(
        page,
        'quantri',
        m.getByRole('alert').filter({ hasText: `Đã ghi quyết định ${so}` }),
        false,
      );
    };
    const tu = new Date(Date.now() - 5_000);
    await doiTen(hienTai, moi, `E2E-${dau()}/QĐ`);
    await expect(cay.getByRole('treeitem', { name: moi })).toBeVisible();
    const lichSu = m.getByRole('region', { name: 'Lịch sử thay đổi' });
    await expect(lichSu.getByText(/· Đổi tên/).first()).toBeVisible();

    await chiHeThong('EVT-14 org.unit.changed.v1 và lịch sử qua listOrgUnitHistory', async () => {
      const c = await api(s, 'quantri');
      const cayApi = await c.can<{ roots: DonVi[] }>('GET', 'org/units/tree');
      const dv = timDonVi(cayApi.body.roots, moi);
      expect(dv, 'đơn vị sau đổi tên có trong getOrgTree').toBeTruthy();
      const ls = await c.can<{ items: unknown[] } | unknown[]>(
        'GET',
        `org/units/${dv!.id}/history`,
      );
      expect((Array.isArray(ls.body) ? ls.body : ls.body.items).length).toBeGreaterThan(1);
      if (coKafkaUi())
        await choSuKien(process.env.E2E_TOPIC_EVT14?.trim() || 'org.unit.changed.v1', dv!.id, tu);
      else ghiCho('kiểm EVT-14: chưa đặt E2E_KAFKA_UI_URL');
      await c.dong();
    });
    ghiCho(
      'phân công cán bộ (createOrgAssignment, EVT-15): giao diện SCR-0103 chưa có thao tác tạo phân công',
    );

    // Ca phủ định: lưu bản sửa khi đơn vị đã được người khác cập nhật → 409 và mời tải lại
    await chiHeThong('sửa đơn vị với phiên bản cũ nhận 409 và được mời tải lại', async () => {
      // Vùng chi tiết đơn vị đang chọn; tiêu đề vùng là tên viết tắt khi có, nên không lọc theo tên đơn vị
      const chiTiet = m
        .getByRole('region')
        .filter({ has: page.getByRole('button', { name: 'Sửa', exact: true }) })
        .filter({ has: page.getByRole('term').filter({ hasText: 'Mã đơn vị' }) });
      const tieuDe = (await chiTiet.getByRole('heading', { level: 2 }).innerText()).trim();
      const vung = m.getByRole('region', { name: tieuDe, exact: true });
      await vung.getByRole('button', { name: 'Sửa' }).click();
      const c = await api(s, 'quantri');
      const cayApi = await c.can<{ roots: DonVi[] }>('GET', 'org/units/tree');
      const dv = timDonVi(cayApi.body.roots, moi)!;
      const hien = await c.can<Record<string, unknown>>('GET', `org/units/${dv.id}`);
      await c.can('PUT', `org/units/${dv.id}`, {
        ifMatch: hien.etag,
        body: { ...hien.body, shortName: `E2E${dau()}` },
      });
      await c.dong();
      await vung.getByRole('textbox', { name: 'Tên viết tắt' }).fill('E2E');
      await vung.getByRole('button', { name: 'Lưu thay đổi' }).click();
      const bao = vung.getByRole('alert');
      await expect(bao.getByRole('button', { name: 'Tải lại' })).toBeVisible();
      await bao.getByRole('button', { name: 'Tải lại' }).click();
      // Phản hồi 409 của bước này là chủ ý; trình duyệt vẫn ghi lỗi tải tài nguyên vào console
      const i = s.loi.findIndex((l) => /status of 409/.test(l));
      if (i >= 0) s.loi.splice(i, 1);
    });

    // Dọn: trả lại tên cũ bằng quyết định đổi tên có hiệu lực từ ngày mai (một đơn vị không đổi tên hai
    // lần trong cùng ngày hiệu lực); lịch sử vẫn giữ dấu vết của lần chạy
    // Ngày mai theo giờ Việt Nam (ngày hiệu lực của máy chủ), không theo UTC của máy chạy kiểm thử
    const maiDate = new Date(Date.now() + 7 * 3_600_000 + 86_400_000).toISOString().slice(0, 10);
    await doiTen(moi, goc, `E2E-${dau()}/QĐ-HOAN`, maiDate);
  });

  test('HT-07 QT-01 chạy đồng bộ nhân sự và xử lý sai lệch đối soát', async () => {
    if (HE_THONG) {
      // Đồng bộ từ hệ thống ngoài là tính năng tuỳ chọn, mặc định tắt (ADR-34): tenant chưa bật cờ thì
      // màn hình báo chưa bật, hành trình chờ tới khi tenant thử nghiệm bật cờ
      const c = await api(s, 'quantri');
      const co = await c.can<{ flags?: Record<string, boolean> }>('GET', 'ui/feature-flags');
      await c.dong();
      if (co.body.flags?.['org.externalSync'] !== true) {
        cho('tenant chưa bật đồng bộ nhân sự từ hệ thống ngoài (cờ org.externalSync, ADR-34)');
      }
    }
    const page = await s.trang('quantri', '/admin/org/sync');
    const m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Đồng bộ và đối soát nhân sự' }),
    ).toBeVisible();
    await kiemAxe(page, 'đồng bộ và đối soát nhân sự');

    // Đồng bộ ngay (`startOrgSyncRun` 202): trang báo kết quả bằng nhãn nghiệp vụ
    await m.getByRole('button', { name: 'Đồng bộ ngay' }).click();
    if (HE_THONG) {
      // "Đồng bộ ngay" mở một lần chạy cho mỗi nguồn (cùng thời điểm bắt đầu). Mọi nguồn dừng ở LOI khi org-service
      // chưa nối được integration-gateway hay nguồn nhân sự: ghi chờ kèm lý do của máy chủ thay cho chờ hết hạn nhãn
      // "Đã đồng bộ"; nguồn chưa cấu hình kết nối (SCIM, HRM trên dev) chỉ làm lần đồng bộ thành công một phần
      interface LanChay { status: string; startedAt: string; reason?: string }
      const c = await api(s, 'quantri');
      let lan: LanChay[] = [];
      await expect
        .poll(
          async () => {
            const ds = await c.can<{ items: LanChay[] }>('GET', 'org/sync-runs?limit=10');
            lan = ds.body.items.filter((r) => r.startedAt === ds.body.items[0]?.startedAt);
            return lan.some((r) => r.status === 'DANG_CHAY');
          },
          { timeout: 120_000 },
        )
        .toBe(false);
      await c.dong();
      if (lan.every((r) => r.status === 'LOI'))
        cho(
          `đồng bộ nhân sự trên hệ thống thật lỗi: ${[...new Set(lan.map((r) => r.reason ?? ''))].join('; ')}`,
        );
    }
    await expect(m.getByRole('status').filter({ hasText: /Đã đồng bộ/ })).toBeVisible({
      timeout: HE_THONG ? 120_000 : 10_000,
    });

    // Chọn một dòng lệch rồi lấy theo nguồn (`resolveOrgDiscrepancies`)
    const bang = m.getByRole('table', { name: /^Danh sách lệch dữ liệu/ });
    const chonDong = bang.getByRole('checkbox', { name: /^Chọn (?!tất cả)/ }).first();
    if (HE_THONG && (await chonDong.count()) === 0)
      cho('dev chưa có mục lệch đối soát sau đồng bộ (nguồn nhân sự giả lập)');
    const tong = m
      .getByRole('group', { name: 'Lọc theo trường lệch' })
      .getByRole('button', { name: /^Tất cả/ });
    await expect(tong).toHaveText(/\d+/);
    const truoc = Number((await tong.innerText()).match(/\d+/)![0]);
    const tu = new Date(Date.now() - 5_000);
    await chonDong.check();
    await m.getByRole('button', { name: /^Lấy theo nguồn/ }).click();
    await expect(
      m.getByRole('status').filter({ hasText: /Đã áp dụng \d+ quyết định/ }),
    ).toBeVisible();
    await expect(tong).toHaveText(new RegExp(`${truoc - 1}`));

    await chiHeThong('EVT-16 trên Kafka sau khi xử lý sai lệch', async () => {
      // Không biết trước mã người của dòng lệch, nên kiểm có bản tin EVT-16 sau thời điểm xử lý
      if (coKafkaUi())
        await choSuKien(process.env.E2E_TOPIC_EVT16?.trim() || 'org.person.changed.v1', '', tu);
      else ghiCho('kiểm EVT-16: chưa đặt E2E_KAFKA_UI_URL');
    });
    ghiCho('ánh xạ thủ công một người không khớp (mapOrgDiscrepancy) chưa có trong bản này');
  });
});
