import { Page, expect, test } from '@playwright/test';
import { batCaoTuoi, chiHeThong, chon, dau, kiemAxe, xacThucBuocLen } from '../ho-tro/kiem-tra';
import { HE_THONG, cho, ghiCho } from '../ho-tro/moi-truong';
import { Phien, main, spaGoto } from '../ho-tro/phien';
import { choSuKien, coKafkaUi } from '../ho-tro/su-kien';
import { api, kiemMoiTruong } from './chung';

/**
 * QT-02 Lập và phê duyệt OKR (SCR-0201..SCR-0203), QT-03 Check-in KR (SCR-0301) → `/api/v1/okr/*`
 * → MOD-01 → `okr.objective`, `okr.kr_checkin`, EVT-01 `okr.objective.approved.v1`, EVT-02
 * `okr.keyresult.checkedin.v1`. Hành trình HT-08, HT-09.
 *
 * Theo ma trận phân quyền (`wiki/15a`): soạn và trình OKR đơn vị là AC-05 (`quanly`), phê duyệt và
 * trả lại là AC-04 (`lanhdao`); danh mục QA-01 ghi AC-06 soạn và AC-05 duyệt, lệch với ma trận nên
 * hành trình theo ma trận và ghi lệch trong báo cáo. AC-06 chưa có lối soạn OKR mới trên giao diện.
 */

/** Soạn mục tiêu đơn vị với các KR mặc định của khung soạn rồi trình phê duyệt; trả tên đơn vị sở hữu. */
async function soanVaTrinh(page: Page, ten: string, kr: (i: number) => string): Promise<string> {
  const m = main(page);
  await expect(m.getByRole('heading', { level: 1, name: /^Soạn mục tiêu/ })).toBeVisible();
  await expect(m.getByRole('region', { name: 'Kiểm tra trước khi trình' })).toBeVisible();
  const canCu = m.getByRole('combobox', { name: 'Căn cứ, bắt buộc' });
  const ds = page.getByRole('listbox');
  await expect(async () => {
    if (!(await ds.isVisible())) {
      await canCu.focus();
      await page.keyboard.press('ArrowDown');
    }
    await expect(ds.getByRole('option').first()).toBeVisible({ timeout: 1_000 });
  }).toPass();
  await ds.getByRole('option').first().click();
  await page.keyboard.press('Escape');
  await expect(ds).toBeHidden();
  const capTren = m.getByRole('combobox', { name: 'Chọn mục tiêu cấp trên' });
  if (await capTren.isVisible()) {
    if (HE_THONG) {
      // Kỳ thử của hệ thống thật chưa chắc có mục tiêu đã duyệt của đơn vị cấp trên (dev không có tài khoản thử
      // ở Sở để lập sẵn), khi đó danh sách chỉ có dòng báo trống
      await capTren.click();
      const ds2 = page.getByRole('listbox');
      await expect(ds2.getByRole('option').first()).toBeVisible();
      if ((await ds2.getByRole('option', { name: /No results found|Không có/ }).count()) > 0)
        cho('kỳ thử chưa có mục tiêu đã duyệt của đơn vị cấp trên để chọn làm mục tiêu cấp trên');
      await page.keyboard.press('Escape');
    }
    await chon(page, capTren);
  }
  await m.getByRole('textbox', { name: 'Tên mục tiêu' }).fill(ten);
  const lyDo = m.getByRole('textbox', { name: /^Lý do vượt/ });
  if (await lyDo.isVisible()) await lyDo.fill('Kiểm thử đầu cuối luồng lập OKR');
  const noiDung = m.getByRole('textbox', { name: /^Nội dung KR/ });
  const so = await noiDung.count();
  expect(so).toBeGreaterThan(0);
  for (let i = 0; i < so; i++) {
    await noiDung.nth(i).fill(kr(i));
    await m
      .getByRole('combobox', { name: /^Đơn vị đo KR/ })
      .nth(i)
      .fill('%');
    await m
      .getByRole('spinbutton', { name: /^Chỉ tiêu KR/ })
      .nth(i)
      .fill('100');
  }
  const donVi = await m.getByRole('textbox', { name: 'Đơn vị sở hữu' }).inputValue();
  await kiemAxe(page, 'soạn mục tiêu');
  await m.getByRole('button', { name: 'Trình phê duyệt' }).click();
  await expect(m.getByRole('status').filter({ hasText: /Đã trình phê duyệt/ })).toBeVisible();
  return donVi;
}

/** Mã mục tiêu đọc từ đường dẫn soạn `/okr/objectives/<id>/edit`. */
const maMucTieu = (page: Page) => new URL(page.url()).pathname.split('/')[3] ?? '';

test.describe('QT-02 QT-03 Mục tiêu, OKR và check-in', { tag: '@qa-04' }, () => {
  let s: Phien;

  test.beforeEach(({ browser }) => {
    // Giả lập: người soạn và người duyệt cùng một kho dữ liệu nên dùng chung một trang
    s = new Phien(browser, ['quanly', 'lanhdao']);
    kiemMoiTruong();
  });

  test.afterEach(async () => {
    const loi = [...s.loi];
    await s.dong();
    expect(loi, 'không có lỗi console hay phản hồi 5xx').toEqual([]);
  });

  test('HT-08 QT-02 lập mục tiêu và KR, bị trả lại, sửa và được phê duyệt', async () => {
    test.setTimeout(HE_THONG ? 600_000 : 120_000);
    const d = dau();
    const ten = `Mục tiêu kiểm thử ${d}`;
    const tenSua = `${ten} đã sửa`;
    const kr = (i: number) => `KR kiểm thử ${d} số ${i + 1}`;

    // AC-05 soạn mục tiêu đơn vị ở chế độ Đảng viên cao tuổi và trình (SCR-0202)
    let page = await s.trang('quanly', '/okr/objectives/new');
    await batCaoTuoi(page);
    const donVi = await soanVaTrinh(page, ten, kr);
    const id = maMucTieu(page);

    // AC-04 trả lại kèm ý kiến (SCR-0203 `returnOkrObjective`)
    page = await s.trang('lanhdao', '/okr/phe-duyet');
    let m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Phê duyệt mục tiêu, OKR' }),
    ).toBeVisible();
    await m.getByRole('region', { name: 'Hàng chờ' }).getByRole('button', { name: donVi }).click();
    await expect(m.getByRole('rowheader', { name: new RegExp(ten) })).toBeVisible();
    await m
      .getByRole('textbox', { name: /^Ý kiến phê duyệt/ })
      .fill('Đề nghị làm rõ chỉ tiêu của KR');
    await m.getByRole('button', { name: 'Trả lại để sửa' }).click();
    await xacThucBuocLen(
      page,
      'lanhdao',
      m.getByRole('status').filter({ hasText: /Đã trả lại bộ OKR/ }),
      false,
    );

    // AC-05 sửa từ cây mục tiêu (`updateOkrObjective`) rồi trình lại
    page = await s.trang('quanly', '/okr');
    m = main(page);
    await m.getByRole('article', { name: ten }).getByRole('button', { name: /^Sửa/ }).click();
    await expect(m.getByRole('heading', { level: 1, name: /^Sửa mục tiêu/ })).toBeVisible();
    await m.getByRole('textbox', { name: 'Tên mục tiêu' }).fill(tenSua);
    const tu = new Date(Date.now() - 5_000);
    await m.getByRole('button', { name: 'Trình phê duyệt' }).click();
    await expect(m.getByRole('status').filter({ hasText: /Đã trình phê duyệt/ })).toBeVisible();

    // AC-04 phê duyệt (`approveOkrObjective`, AAL2: có thể được mời xác thực lại)
    page = await s.trang('lanhdao', '/okr/phe-duyet');
    m = main(page);
    await m.getByRole('region', { name: 'Hàng chờ' }).getByRole('button', { name: donVi }).click();
    await expect(m.getByRole('rowheader', { name: new RegExp(tenSua) })).toBeVisible();
    await m.getByRole('button', { name: 'Phê duyệt', exact: true }).click();
    await xacThucBuocLen(
      page,
      'lanhdao',
      m.getByRole('status').filter({ hasText: /Đã phê duyệt OKR/ }),
      false,
    );

    // Cây mục tiêu hiện mục tiêu đã duyệt cùng các KR liên kết (SCR-0201, `getOkrTree`)
    page = await s.trang('quanly', '/okr');
    m = main(page);
    const the = m.getByRole('article', { name: tenSua });
    await expect(the.getByText(/Đã duyệt/)).toBeVisible();
    await expect(the.getByRole('button', { name: new RegExp(kr(0)) })).toBeVisible();
    await kiemAxe(page, 'cây mục tiêu, chế độ cao tuổi');

    await chiHeThong('trạng thái DA_DUYET qua getOkrObjective và EVT-01', async () => {
      const c = await api(s, 'quanly');
      const o = await c.can<{ status: string }>('GET', `okr/objectives/${id}`);
      expect(o.body.status).toBe('DA_DUYET');
      await c.dong();
      if (coKafkaUi())
        await choSuKien(process.env.E2E_TOPIC_EVT01?.trim() || 'okr.objective.approved.v1', id, tu);
      else ghiCho('kiểm EVT-01: chưa đặt E2E_KAFKA_UI_URL');
    });
  });

  test('HT-08 QT-02 ca phủ định: người không có quyền phê duyệt bị từ chối', async ({
    browser,
  }) => {
    // AC-05 không có quyền phê duyệt (ma trận SCR-0203): giao diện chặn tuyến, máy chủ trả 403
    const rieng = new Phien(browser);
    try {
      const page = await rieng.trang('quanly', '/okr/phe-duyet');
      await expect(
        main(page).getByRole('heading', { level: 1, name: 'Không có quyền truy cập' }),
      ).toBeVisible();
      await kiemAxe(page, 'trang không có quyền');
      await chiHeThong('approveOkrObjective bằng tài khoản quanly trả 403', async () => {
        const c = await api(rieng, 'quanly');
        const ds = await c.can<{ items: { id: string }[] }>('GET', 'okr/objectives', {
          query: { limit: '1' },
        });
        if (!ds.body.items.length) cho('chưa có mục tiêu nào trên dev để thử phê duyệt trái quyền');
        const kq = await c.goi('POST', `okr/objectives/${ds.body.items[0].id}/approve`, {
          body: {},
        });
        expect(kq.status).toBe(403);
        await c.dong();
      });
      expect(rieng.loi).toEqual([]);
    } finally {
      await rieng.dong();
    }
  });

  test('HT-09 QT-03 check-in KR và gửi lại không tạo bản ghi trùng', async ({ browser }) => {
    const rieng = new Phien(browser);
    try {
      // AC-06 check-in KR của mình ở chế độ Đảng viên cao tuổi (SCR-0301)
      const page = await rieng.trang('canbo', '/okr/checkin');
      const m = main(page);
      await batCaoTuoi(page);
      await expect(
        m.getByRole('heading', { level: 1, name: 'Check-in kết quả then chốt' }),
      ).toBeVisible();
      const ds = m.getByRole('region', { name: 'KR của tôi cần check-in' }).getByRole('button');
      if (HE_THONG && (await ds.count()) === 0)
        cho('tài khoản canbo trên dev chưa sở hữu KR đã duyệt (seed OKR)');
      const mot = ds.filter({ hasNotText: /Xong$/ }).first();
      const tenKr = ((await mot.innerText()).split('\n')[0] ?? '')
        .split(' · ')
        .slice(0, 2)
        .join(' · ')
        .trim();
      await mot.click();
      await expect(
        m.getByRole('heading', { level: 2, name: new RegExp(`^${tenKr.split(' · ')[0]}`) }),
      ).toBeVisible();
      await kiemAxe(page, 'check-in KR, chế độ cao tuổi');
      const giaTri = m.getByRole('textbox', { name: /^Giá trị hiện tại/ });
      // Giá trị mới nhích nhẹ so với lần trước để không vướng cảnh báo chênh lệch bất thường
      const truocChu = await m.getByRole('textbox', { name: 'Giá trị lần trước' }).inputValue();
      const truoc = Number(truocChu.replace(/[^\d,.-]/g, '').replace(',', '.')) || 0;
      const moi = Math.round((truoc + Math.max(0.2, truoc * 0.05)) * 10) / 10;
      const moiChu = String(moi).replace('.', ',');
      await giaTri.fill(moiChu);
      // KR lấy số liệu từ nguồn (BR-03): đổi giá trị thì phải ghi lý do
      const lyDoNguon = m.getByRole('textbox', { name: /^Lý do khác số liệu nguồn/ });
      if (await lyDoNguon.isVisible())
        await lyDoNguon.fill('Số liệu nguồn chưa cập nhật đợt kết nạp mới');
      await m.getByRole('radio', { name: 'Trung bình' }).check();
      await m
        .getByRole('textbox', { name: 'Kết quả nổi bật trong tuần' })
        .fill(`Đã triển khai kế hoạch ${dau()}`);
      const tu = new Date(Date.now() - 5_000);
      const gui = HE_THONG
        ? page.waitForRequest(
            (r) => r.method() === 'POST' && /\/okr\/key-results\/[^/]+\/checkins$/.test(r.url()),
          )
        : null;
      await m.getByRole('button', { name: 'Gửi check-in' }).click();
      const vanGui = m.getByRole('button', { name: 'Vẫn gửi' });
      if (await vanGui.isVisible({ timeout: 1_000 }).catch(() => false)) await vanGui.click();
      await expect(m.getByRole('status').filter({ hasText: /Đã gửi check-in/ })).toBeVisible();

      await chiHeThong(
        'gửi lại cùng Idempotency-Key không tạo check-in thứ hai; EVT-02',
        async () => {
          const r = await gui!;
          const krId = new URL(r.url()).pathname.split('/').at(-2)!;
          const khoa = r.headers()['idempotency-key'];
          expect(khoa, 'ứng dụng gửi Idempotency-Key khi check-in').toBeTruthy();
          const c = await api(rieng, 'canbo');
          const truoc = await c.can<{ items: { id: string }[] }>(
            'GET',
            `okr/key-results/${krId}/checkins`,
            {
              query: { limit: '200' },
            },
          );
          const lai = await c.goi('POST', `okr/key-results/${krId}/checkins`, {
            body: JSON.parse(r.postData() ?? '{}'),
            idempotencyKey: khoa,
          });
          expect(lai.status).toBeLessThan(300);
          const sau = await c.can<{ items: { id: string }[] }>(
            'GET',
            `okr/key-results/${krId}/checkins`,
            {
              query: { limit: '200' },
            },
          );
          expect(sau.body.items.length, 'không có check-in thứ hai').toBe(truoc.body.items.length);
          await c.dong();
          if (coKafkaUi())
            await choSuKien(
              process.env.E2E_TOPIC_EVT02?.trim() || 'okr.keyresult.checkedin.v1',
              krId,
              tu,
            );
          else ghiCho('kiểm EVT-02: chưa đặt E2E_KAFKA_UI_URL');
        },
      );

      // Tiến độ KR trên danh sách cập nhật theo giá trị vừa gửi
      await spaGoto(page, '/okr/checkin');
      await expect(
        m
          .getByRole('region', { name: 'KR của tôi cần check-in' })
          .getByRole('button', { name: new RegExp(`${tenKr.split(' · ')[0]}.*${moiChu}`) }),
      ).toBeVisible();
      expect(rieng.loi).toEqual([]);
    } finally {
      await rieng.dong();
    }
  });
});
