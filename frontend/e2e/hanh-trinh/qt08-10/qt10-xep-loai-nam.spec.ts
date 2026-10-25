import { expect, test } from '@playwright/test';
import { batCaoTuoi, chiHeThong, dau } from '../ho-tro/kiem-tra';
import { HE_THONG, cho } from '../ho-tro/moi-truong';
import { Phien, main } from '../ho-tro/phien';
import { api, kiemAxe, kiemMoiTruong, thongBao } from './chung';

/**
 * QT-10 Xếp loại năm và phân nhóm triển vọng (SCR-1001, SCR-1002) → `/api/v1/appraisals/annual`,
 * `/api/v1/appraisals/prospect-groups/**` → MOD-04 trên SVC-05. Hành trình HT-28, HT-29 của danh mục
 * QA-01; hai hành trình không phát sự kiện riêng và không có trạng thái cuối theo baseline 9.1.
 */
test.describe('QT-10 Xếp loại năm và phân nhóm triển vọng', { tag: '@qa-06' }, () => {
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

  test('HT-28 QT-10 xếp loại năm từ bốn quý và giải thích kết quả', async () => {
    // AC-07 xem bảng tổng hợp bốn quý ở chế độ Đảng viên cao tuổi (SCR-1001)
    const page = await s.trang('thamdinh', '/appraisal/xep-loai-nam');
    const m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: /^Xếp loại năm/ })).toBeVisible();
    await batCaoTuoi(page);
    const bang = m.getByRole('table');
    const nguoi = bang.getByRole('rowheader').getByRole('button');
    // Máy chủ thật trả chậm hơn bản giả lập: chờ dòng đầu trước khi kết luận danh sách rỗng
    if (HE_THONG)
      await nguoi
        .first()
        .waitFor({ timeout: 15_000 })
        .catch(() => undefined);
    if (HE_THONG && (await nguoi.count()) === 0)
      cho('chưa có kết quả xếp loại năm trên dev (bốn kỳ quý dựng bằng dữ liệu khởi tạo)');
    await expect(nguoi.first()).toBeVisible();
    await kiemAxe(page, 'xếp loại năm, chế độ cao tuổi');

    // Lọc hồ sơ có cảnh báo liên thông, mở giải thích: bốn quý đã khoá, chỉ đọc
    const loc = m.getByRole('button', { name: 'Chỉ hiện hồ sơ có cảnh báo liên thông' });
    await loc.click();
    await expect(loc).toHaveAttribute('aria-pressed', 'true');
    let chon = nguoi.filter({ hasText: 'Bùi Văn Khánh' });
    if (HE_THONG) {
      // Trên dev chỉ hồ sơ năm đã có điểm năm mới mở được giải thích (luồng Nhà nước chưa có điểm năm)
      const taiLai = page.waitForResponse(
        (r) => r.url().includes('/appraisals/annual?') && !r.url().includes('flag='),
      );
      await loc.click();
      await expect(loc).toHaveAttribute('aria-pressed', 'false');
      await taiLai;
      await nguoi.first().waitFor();
      const dong = bang.getByRole('row').filter({ has: page.getByRole('rowheader') });
      const coDiem: number[] = [];
      for (let i = 0; i < (await dong.count()); i++)
        if ((await dong.nth(i).getByRole('cell').nth(4).innerText()).trim() !== '—') coDiem.push(i);
      if (coDiem.length === 0) cho('chưa có hồ sơ năm nào đã tính điểm năm trên dev');
      chon = dong.nth(coDiem[0]).getByRole('rowheader').getByRole('button');
    }
    const ten = (await chon.innerText()).trim();
    await chon.click();
    const giaiThich = m.getByRole('complementary').filter({
      has: page.getByRole('heading', { name: `Giải thích liên thông · ${ten}` }),
    });
    await expect(giaiThich).toBeVisible();
    await expect(
      giaiThich.getByRole('heading', { name: 'Kết quả bốn quý (đã khoá, chỉ đọc)' }),
    ).toBeVisible();
    await expect(giaiThich.getByRole('listitem').filter({ hasText: /^Quý|Q[1-4]/ })).toHaveCount(4);
    if (!HE_THONG) {
      // Hai quý liên tiếp không hoàn thành nhiệm vụ: quy tắc liên thông QĐ khoản 15.6
      await expect(
        bang
          .getByRole('row', { name: new RegExp(ten) })
          .getByText(/Hai quý liên tiếp không hoàn thành/),
      ).toBeVisible();
    }
    await kiemAxe(page, 'giải thích liên thông xếp loại năm');

    // AC-07 lập đề xuất bố trí lại khi có hai quý liên tiếp không hoàn thành (SCR-1001.bo-tri-lai)
    const boTri = giaiThich.getByRole('button', { name: 'Lập đề xuất bố trí lại' });
    if (await boTri.isVisible()) {
      await boTri.click();
      const hop = page.getByRole('dialog', { name: 'Lập đề xuất bố trí lại công việc' });
      await hop
        .getByRole('textbox', { name: /Ghi chú gửi cơ quan tổ chức/ })
        .fill(`Kiểm thử hành trình ${dau()}: đề nghị xem xét bố trí công việc phù hợp.`);
      await hop.getByRole('button', { name: 'Lập đề xuất' }).click();
      await expect(
        thongBao(page, `Đã lập đề xuất bố trí lại công việc cho đồng chí ${ten}`),
      ).toBeVisible();
      await expect(
        giaiThich.getByText('Đã lập đề xuất bố trí lại công việc cho hồ sơ này.'),
      ).toBeVisible();
    } else if (!HE_THONG) {
      throw new Error(
        'hồ sơ có hai quý liên tiếp không hoàn thành phải có lối lập đề xuất bố trí lại',
      );
    }

    await chiHeThong('listAnnualAppraisalRatings trả bảng tổng hợp có bốn quý', async () => {
      const c = await api(s, 'thamdinh');
      // Kỳ năm mới nhất, như màn hình chọn khi không truyền ?period=
      const ky = await c.can<{ items: { id: string }[] }>('GET', 'periods?kind=NAM');
      const r = await c.can<{ items: { quarterRatings: unknown[] }[] }>(
        'GET',
        `appraisals/annual?period=${ky.body.items[0].id}`,
      );
      expect(r.body.items.length).toBeGreaterThan(0);
      expect(r.body.items[0].quarterRatings).toHaveLength(4);
      await c.dong();
    });
  });

  test('HT-29 QT-10 phân nhóm cán bộ triển vọng', async () => {
    // AC-04 xếp nhóm, ghi lý do khi khác gợi ý, lưu nháp và trình tập thể lãnh đạo (SCR-1002)
    const page = await s.trang('lanhdao', '/appraisal/phan-nhom');
    const m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: /^Phân nhóm triển vọng/ })).toBeVisible();
    const doi = m.getByRole('button', { name: /^Đổi nhóm, thứ tự của / });
    if (HE_THONG)
      await doi
        .first()
        .waitFor({ timeout: 15_000 })
        .catch(() => undefined);
    if (HE_THONG && (await doi.count()) === 0)
      cho('danh sách phân nhóm trên dev chưa có cán bộ hoặc đã trình (cần kỳ năm đã xếp loại)');
    await expect(m.getByRole('heading', { level: 2, name: /^Nhóm 1 – / })).toBeVisible();
    await kiemAxe(page, 'phân nhóm triển vọng');

    // Đổi nhóm bằng menu bàn phím (thay cho kéo thả): người đầu tiên của Nhóm 1 sang Nhóm 2
    const nut = doi.first();
    const ten = ((await nut.getAttribute('aria-label')) ?? '').replace('Đổi nhóm, thứ tự của ', '');
    await nut.click();
    const menu = m.getByRole('group', { name: `Thao tác với ${ten}` });
    // Lần chạy trước trên dev có thể đã đổi nhóm người này: chọn nhóm đích đầu tiên menu đưa ra
    const chuyen = menu.getByRole('button', { name: /^Chuyển sang Nhóm \d/ }).first();
    const nhom = ((await chuyen.textContent()) ?? '').trim().replace('Chuyển sang ', '');
    await chuyen.click();
    await expect(thongBao(page, `Đã đưa ${ten} vào ${nhom}`)).toBeVisible();
    // Ô lý do chỉ hiện khi khác nhóm gợi ý; máy chủ chưa sinh gợi ý (wiki 14) nên trên dev có thể không có
    const lyDo = m.getByRole('textbox', { name: 'Lý do điều chỉnh' });
    if (!HE_THONG || (await lyDo.count()) > 0)
      await lyDo.fill('Kiểm thử hành trình: điều chỉnh nhóm theo nhận xét của tập thể lãnh đạo.');
    await m.getByRole('button', { name: 'Lưu nháp' }).click();
    await expect(thongBao(page, 'Đã lưu nháp danh sách phân nhóm.')).toBeVisible();

    await m.getByRole('button', { name: 'Trình tập thể lãnh đạo' }).click();
    const hop = page.getByRole('dialog', { name: 'Trình tập thể lãnh đạo' });
    await expect(hop).toBeVisible();
    await hop.getByRole('button', { name: 'Trình', exact: true }).click();
    await expect(
      thongBao(page, /Đã trình tập thể lãnh đạo danh sách phân nhóm triển vọng/),
    ).toBeVisible();
    // Sau khi trình, danh sách bị khoá chỉnh sửa
    await expect(m.getByRole('button', { name: 'Lưu nháp' })).toHaveCount(0);
    await expect(doi).toHaveCount(0);
    await kiemAxe(page, 'phân nhóm triển vọng đã trình');
  });
});
