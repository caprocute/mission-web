import { Browser, Page, expect, test } from '@playwright/test';
import { mkdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { completeKeycloak } from '../dang-nhap-keycloak';

/**
 * Chụp ảnh màn hình cho bộ hướng dẫn sử dụng theo vai trò (VH-05, YC-PT-11, NFR-11). Mỗi vai trò
 * AC-01..AC-10 một ca, dùng tài khoản thử của realm dev mang vai trò đó; ảnh chụp ở chế độ thường và,
 * với các bước của cán bộ (AC-06), cả ở chế độ Đảng viên cao tuổi. Chạy lại sau mỗi lần giao diện đổi
 * để ảnh trong `docs/huong-dan-su-dung/anh/` khớp giao diện mới.
 *
 * Giả lập (mặc định): tắt đăng nhập, vai trò đặt vào sessionStorage `vt.devRoles`, dữ liệu là dữ liệu
 * mẫu không có thông tin cá nhân thật. Hệ thống thật (`E2E_MODE=he-thong`): đăng nhập Keycloak thật;
 * ảnh cần mã bản ghi của dữ liệu giả lập được bỏ qua và ghi chú lại.
 */
const HE_THONG = process.env.E2E_MODE === 'he-thong';
const ANH = join(__dirname, '..', '..', '..', 'docs', 'huong-dan-su-dung', 'anh');
const REALM = JSON.parse(
  readFileSync(join(__dirname, '..', '..', '..', 'infra/dev/keycloak/realm-mission.json'), 'utf8'),
) as { users: { username: string; realmRoles: string[] }[] };

interface Anh {
  /** Tên tệp ảnh, không phần mở rộng; tài liệu Markdown dẫn đúng tên này. */
  ten: string;
  url: string;
  /** Tiêu đề trang chờ hiện trước khi chụp. */
  tieuDe: RegExp;
  /** Thao tác thêm trước khi chụp (mở hộp thoại, chọn bản ghi). */
  buoc?: (page: Page) => Promise<void>;
  /** Chụp thêm bản chế độ Đảng viên cao tuổi, tên tệp thêm hậu tố `-cao-tuoi`. */
  caoTuoi?: boolean;
  /** Đường dẫn dùng mã bản ghi của dữ liệu giả lập; trên hệ thống thật bỏ qua. */
  chiGiaLap?: boolean;
}

interface VaiTro {
  ac: string;
  taiKhoan: string;
  anh: Anh[];
}

const m = (page: Page) => page.locator('main#main');

const chonKy = (ten: string) => async (page: Page) => {
  await m(page)
    .getByRole('button', { name: new RegExp(`^${ten.replace('/', '\\/')} `) })
    .click();
  await expect(m(page).getByRole('region', { name: `${ten}: lệnh khoá kỳ` })).toBeVisible();
};

const DANH_SACH: VaiTro[] = [
  {
    ac: 'ac-01',
    taiKhoan: 'nentang',
    anh: [
      { ten: 'ac-01-01-trang-chu', url: '/', tieuDe: /^Xin chào/ },
      {
        ten: 'ac-01-02-danh-sach-don-vi',
        url: '/admin/tenants',
        tieuDe: /^Danh sách đơn vị triển khai/,
      },
      {
        ten: 'ac-01-03-khoi-tao-don-vi',
        url: '/admin/tenants/new',
        tieuDe: /^Khởi tạo đơn vị triển khai/,
      },
      {
        ten: 'ac-01-04-mau-quy-trinh',
        url: '/config-studio/mau-luong',
        tieuDe: /^Mẫu quy trình dùng chung/,
      },
      {
        ten: 'ac-01-05-giam-sat-tien-trinh',
        url: '/config-studio/tien-trinh',
        tieuDe: /^Giám sát tiến trình/,
      },
    ],
  },
  {
    ac: 'ac-02',
    taiKhoan: 'quantri',
    anh: [
      { ten: 'ac-02-01-quan-tri', url: '/admin', tieuDe: /^Quản trị$/ },
      { ten: 'ac-02-02-cay-to-chuc', url: '/admin/org', tieuDe: /^Cây tổ chức/ },
      {
        ten: 'ac-02-03-dong-bo-nhan-su',
        url: '/admin/org/sync',
        tieuDe: /^Đồng bộ và đối soát nhân sự/,
      },
      { ten: 'ac-02-04-vai-tro-theo-don-vi', url: '/admin/access', tieuDe: /^Vai trò theo đơn vị/ },
      { ten: 'ac-02-05-ky-danh-gia', url: '/admin/periods', tieuDe: /^Kỳ đánh giá và khoá kỳ/ },
      {
        ten: 'ac-02-06-tien-trinh-khoa-ky',
        url: '/admin/periods',
        tieuDe: /^Kỳ đánh giá và khoá kỳ/,
        buoc: chonKy('Quý III/2026'),
        chiGiaLap: true,
      },
      {
        ten: 'ac-02-07-giao-dien-don-vi',
        url: '/config-studio/giao-dien',
        tieuDe: /^Giao diện đơn vị/,
      },
      { ten: 'ac-02-08-bieu-mau-dong', url: '/config-studio/bieu-mau', tieuDe: /^Biểu mẫu động/ },
    ],
  },
  {
    ac: 'ac-03',
    taiKhoan: 'chinhsach',
    anh: [
      {
        ten: 'ac-03-01-chinh-sach-danh-gia',
        url: '/config-studio',
        tieuDe: /^Chính sách đánh giá/,
      },
      {
        ten: 'ac-03-02-cong-thuc',
        url: '/config-studio/goi/0192a1b0-3c4d-7e5f-8a9b-000000004001/2.1.0/cong-thuc',
        tieuDe: /^Quy định đánh giá/,
        chiGiaLap: true,
      },
      {
        ten: 'ac-03-03-bang-quyet-dinh',
        url: '/config-studio/goi/0192a1b0-3c4d-7e5f-8a9b-000000004001/2.1.0/bang-quyet-dinh',
        tieuDe: /^Quy định đánh giá/,
        chiGiaLap: true,
      },
      {
        ten: 'ac-03-04-test-case',
        url: '/config-studio/goi/0192a1b0-3c4d-7e5f-8a9b-000000004001/2.1.0/test-case',
        tieuDe: /^Quy định đánh giá/,
        chiGiaLap: true,
      },
      {
        ten: 'ac-03-05-luong-duyet',
        url: '/config-studio/goi/0192a1b0-3c4d-7e5f-8a9b-000000004001/2.1.0/luong-duyet',
        tieuDe: /^Quy định đánh giá/,
        chiGiaLap: true,
      },
      {
        ten: 'ac-03-06-nguong-bat-thuong',
        url: '/oversight/alert-thresholds',
        tieuDe: /^Ngưỡng phát hiện/,
      },
      {
        ten: 'ac-03-07-danh-muc-cong-viec',
        url: '/tasks/catalog',
        tieuDe: /^Thư viện danh mục công việc/,
      },
    ],
  },
  {
    ac: 'ac-04',
    taiKhoan: 'lanhdao',
    anh: [
      { ten: 'ac-04-01-trang-chu', url: '/', tieuDe: /^Xin chào/ },
      {
        ten: 'ac-04-02-phe-duyet-okr',
        url: '/okr/phe-duyet',
        tieuDe: /^Phê duyệt mục tiêu, OKR/,
        buoc: async (page) => {
          const cho = m(page).getByRole('region', { name: 'Hàng chờ' }).getByRole('button');
          if (await cho.count()) await cho.first().click();
        },
      },
      { ten: 'ac-04-03-ke-hoach-quy', url: '/plans', tieuDe: /^Kế hoạch công tác/ },
      {
        ten: 'ac-04-04-duyet-lenh-khoa-ky',
        url: '/admin/periods',
        tieuDe: /^Kỳ đánh giá và khoá kỳ/,
        buoc: chonKy('Quý IV/2026'),
        chiGiaLap: true,
      },
      { ten: 'ac-04-05-xep-loai-nam', url: '/appraisal/xep-loai-nam', tieuDe: /^Xếp loại năm/ },
      {
        ten: 'ac-04-06-phan-nhom-trien-vong',
        url: '/appraisal/phan-nhom',
        tieuDe: /^Phân nhóm triển vọng/,
      },
      { ten: 'ac-04-07-bang-dieu-hanh', url: '/dashboard', tieuDe: /^Bảng điều hành/ },
      {
        ten: 'ac-04-08-uy-quyen',
        url: '/admin/access/delegations',
        tieuDe: /^Uỷ quyền khi vắng mặt/,
      },
    ],
  },
  {
    ac: 'ac-05',
    taiKhoan: 'quanly',
    anh: [
      { ten: 'ac-05-01-trang-chu', url: '/', tieuDe: /^Xin chào/ },
      { ten: 'ac-05-02-cay-muc-tieu', url: '/okr', tieuDe: /^Cây mục tiêu OKR/ },
      { ten: 'ac-05-03-soan-muc-tieu', url: '/okr/objectives/new', tieuDe: /^Soạn mục tiêu/ },
      { ten: 'ac-05-04-ke-hoach-quy', url: '/plans', tieuDe: /^Kế hoạch công tác/ },
      { ten: 'ac-05-05-giao-viec', url: '/tasks/assign', tieuDe: /^Giao nhiệm vụ/ },
      { ten: 'ac-05-06-van-ban-den', url: '/tasks/documents', tieuDe: /^Văn bản đến/ },
      {
        ten: 'ac-05-07-nghiem-thu',
        url: '/evidence/0192a1b2-0000-7000-8000-000000000138/review',
        tieuDe: /./,
        chiGiaLap: true,
      },
      { ten: 'ac-05-08-ket-qua-xep-loai', url: '/appraisal/ket-qua', tieuDe: /^Kết quả xếp loại/ },
      { ten: 'ac-05-09-bang-dieu-hanh', url: '/dashboard', tieuDe: /^Bảng điều hành/ },
    ],
  },
  {
    ac: 'ac-06',
    taiKhoan: 'canbo',
    anh: [
      { ten: 'ac-06-01-trang-chu', url: '/', tieuDe: /^Xin chào/, caoTuoi: true },
      { ten: 'ac-06-02-viec-cua-toi', url: '/tasks', tieuDe: /^Việc của tôi$/, caoTuoi: true },
      {
        ten: 'ac-06-03-chi-tiet-nhiem-vu',
        url: '/tasks/0192a0f0-0001-7000-8000-000000000001',
        tieuDe: /./,
        chiGiaLap: true,
      },
      {
        ten: 'ac-06-04-cap-nhat-tien-do',
        url: '/tasks/0192a0f0-0001-7000-8000-000000000001',
        tieuDe: /./,
        chiGiaLap: true,
        caoTuoi: true,
        buoc: async (page) => {
          await m(page).getByRole('button', { name: 'Cập nhật tiến độ' }).click();
          await expect(page.getByRole('dialog', { name: 'Cập nhật tiến độ' })).toBeVisible();
        },
      },
      { ten: 'ac-06-05-check-in', url: '/okr/checkin', tieuDe: /^Check-in kết quả then chốt/ },
      {
        ten: 'ac-06-06-nop-minh-chung',
        url: '/evidence/0192a1b2-0000-7000-8000-000000000142',
        tieuDe: /^Nộp sản phẩm, minh chứng/,
        chiGiaLap: true,
      },
      {
        ten: 'ac-06-07-tu-danh-gia',
        url: '/appraisal/tu-danh-gia',
        tieuDe: /^Phiếu tự đánh giá/,
        caoTuoi: true,
      },
      { ten: 'ac-06-08-ket-qua-xep-loai', url: '/appraisal/ket-qua', tieuDe: /^Kết quả xếp loại/ },
      {
        ten: 'ac-06-09-gui-khieu-nai',
        url: '/oversight/my-complaints/new',
        tieuDe: /^Gửi khiếu nại/,
      },
    ],
  },
  {
    ac: 'ac-07',
    taiKhoan: 'thamdinh',
    anh: [
      {
        ten: 'ac-07-01-tham-dinh',
        url: '/appraisal/tham-dinh',
        tieuDe: /^Thẩm định hồ sơ đánh giá/,
      },
      { ten: 'ac-07-02-xep-loai-nam', url: '/appraisal/xep-loai-nam', tieuDe: /^Xếp loại năm/ },
      {
        ten: 'ac-07-03-hoi-nghi-kiem-diem',
        url: '/dossier/kiem-diem',
        tieuDe: /^Hội nghị kiểm điểm/,
      },
      { ten: 'ac-07-04-goi-ho-so', url: '/dossier/goi-ho-so', tieuDe: /^Gói hồ sơ số/ },
      { ten: 'ac-07-05-xu-ly-khieu-nai', url: '/oversight/complaints', tieuDe: /^Xử lý khiếu nại/ },
      { ten: 'ac-07-06-canh-bao', url: '/oversight/alerts', tieuDe: /^Trung tâm cảnh báo/ },
    ],
  },
  {
    ac: 'ac-08',
    taiKhoan: 'quyetdinh',
    anh: [
      {
        ten: 'ac-08-01-quyet-dinh-hang-loat',
        url: '/appraisal/quyet-dinh',
        tieuDe: /^Quyết định xếp loại hàng loạt/,
      },
      { ten: 'ac-08-02-ket-qua-xep-loai', url: '/appraisal/ket-qua', tieuDe: /^Kết quả xếp loại/ },
      { ten: 'ac-08-03-goi-ho-so', url: '/dossier/goi-ho-so', tieuDe: /^Gói hồ sơ số/ },
      {
        ten: 'ac-08-04-mo-lai-ky',
        url: '/oversight/reopen-requests',
        tieuDe: /^Hậu kiểm, mở lại kỳ/,
      },
      { ten: 'ac-08-05-chinh-sach', url: '/config-studio', tieuDe: /^Chính sách đánh giá/ },
    ],
  },
  {
    ac: 'ac-09',
    taiKhoan: 'duyet2',
    anh: [
      {
        ten: 'ac-09-01-duyet-huy-khoa-ky',
        url: '/admin/periods',
        tieuDe: /^Kỳ đánh giá và khoá kỳ/,
        buoc: chonKy('Tháng 8/2026'),
        chiGiaLap: true,
      },
      {
        ten: 'ac-09-02-mo-lai-ky',
        url: '/oversight/reopen-requests',
        tieuDe: /^Hậu kiểm, mở lại kỳ/,
      },
      {
        ten: 'ac-09-03-mau-quy-trinh',
        url: '/config-studio/mau-luong',
        tieuDe: /^Mẫu quy trình dùng chung/,
      },
    ],
  },
  {
    ac: 'ac-10',
    taiKhoan: 'kiemtra',
    anh: [
      { ten: 'ac-10-01-canh-bao', url: '/oversight/alerts', tieuDe: /^Trung tâm cảnh báo/ },
      { ten: 'ac-10-02-so-cai', url: '/oversight/ledger', tieuDe: /^Sổ cái và kiểm tra toàn vẹn/ },
      {
        ten: 'ac-10-03-tao-yeu-cau-mo-lai',
        url: '/oversight/reopen-requests/new',
        tieuDe: /^Tạo yêu cầu mở lại kỳ/,
      },
      { ten: 'ac-10-04-vai-tro-theo-don-vi', url: '/admin/access', tieuDe: /^Vai trò theo đơn vị/ },
      { ten: 'ac-10-05-bang-dieu-hanh', url: '/dashboard', tieuDe: /^Bảng điều hành/ },
    ],
  },
];

/** Mở trang cho tài khoản thử: đăng nhập Keycloak thật hoặc đặt vai trò giả lập. */
async function moTrang(browser: Browser, taiKhoan: string): Promise<Page> {
  const u = REALM.users.find((x) => x.username === taiKhoan);
  expect(u, `realm dev thiếu tài khoản ${taiKhoan}`).toBeTruthy();
  const ctx = await browser.newContext();
  if (!HE_THONG)
    await ctx.addInitScript(
      (r) => sessionStorage.setItem('vt.devRoles', r),
      u!.realmRoles.join(','),
    );
  const page = await ctx.newPage();
  await page.goto('/');
  if (HE_THONG) await completeKeycloak(page, taiKhoan);
  return page;
}

async function den(page: Page, url: string) {
  await page.evaluate((u) => {
    history.pushState(null, '', u);
    dispatchEvent(new PopStateEvent('popstate', { state: null }));
  }, url);
}

/** Chờ trang yên: tiêu đề hiện, không còn chữ "Đang tải", hiệu ứng chuyển cảnh đã dừng. */
async function choYen(page: Page, tieuDe: RegExp) {
  await expect(m(page).getByRole('heading', { level: 1, name: tieuDe }).first()).toBeVisible();
  await expect(m(page).getByText(/^Đang tải/)).toHaveCount(0, { timeout: 15_000 });
  await page.waitForFunction(() =>
    document
      .getAnimations()
      .every((a) => a.playState !== 'running' || a.effect?.getTiming().iterations === Infinity),
  );
}

async function chup(page: Page, ten: string) {
  await page.screenshot({
    path: join(ANH, `${ten}.jpg`),
    type: 'jpeg',
    quality: 80,
    animations: 'disabled',
  });
}

test.describe('Ảnh cho hướng dẫn sử dụng', () => {
  test.beforeAll(() => mkdirSync(ANH, { recursive: true }));

  for (const vt of DANH_SACH) {
    test(`${vt.ac} ${vt.taiKhoan}`, async ({ browser }) => {
      const page = await moTrang(browser, vt.taiKhoan);
      const loi: string[] = [];
      page.on('pageerror', (e) => loi.push(e.message));
      const caoTuoi: Anh[] = [];
      for (const a of vt.anh) {
        if (HE_THONG && a.chiGiaLap) {
          test
            .info()
            .annotations.push({ type: 'bo-qua', description: `${a.ten}: cần dữ liệu giả lập` });
          continue;
        }
        await den(page, a.url);
        await choYen(page, a.tieuDe);
        if (a.buoc) await a.buoc(page);
        await choYen(page, a.tieuDe);
        await chup(page, a.ten);
        if (a.buoc) await page.keyboard.press('Escape');
        if (a.caoTuoi) caoTuoi.push(a);
      }
      // Chế độ Đảng viên cao tuổi (NFR-11): bật nút "Chữ lớn" rồi chụp lại các bước đã đánh dấu
      if (caoTuoi.length) {
        await page.getByRole('button', { name: 'Chữ lớn' }).click();
        await expect(page.locator('html')).toHaveAttribute('data-mode', 'elderly');
        for (const a of caoTuoi) {
          await den(page, a.url);
          await choYen(page, a.tieuDe);
          if (a.buoc) await a.buoc(page);
          await choYen(page, a.tieuDe);
          await chup(page, `${a.ten}-cao-tuoi`);
          if (a.buoc) await page.keyboard.press('Escape');
        }
      }
      expect(loi, 'không có lỗi trang khi chụp').toEqual([]);
      await page.context().close();
    });
  }
});
