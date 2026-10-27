import { Request, expect, test } from '@playwright/test';
import { batCaoTuoi, chiHeThong, xacThucBuocLen, kiemAxeOnDinh } from '../ho-tro/kiem-tra';
import { HE_THONG, cho } from '../ho-tro/moi-truong';
import { Phien, main, spaGoto } from '../ho-tro/phien';
import { SEED, Trang, api, kiemMoiTruong, kiemSuKien } from './chung';

/**
 * QT-17 Trợ lý AI chỉ đọc (SCR-1701), QT-18 UI Pack và biểu mẫu động (SCR-1801, SCR-1802) →
 * `/api/v1/ai/*`, `/api/v1/ui/*` → SVC-16 ai-gateway, MOD-08 (SVC-05) → `config.ui_pack_version`,
 * EVT-23 `config.uipack.published.v1`. Hành trình HT-40, HT-41.
 *
 * Trợ lý AI (AC-12) chỉ đọc và gợi ý: hành trình đếm mọi lệnh ghi vào mục tiêu, KR trong lúc hỏi và chỉ
 * chấp nhận lệnh ghi xuất hiện sau khi người dùng bấm "Dùng gợi ý". Trên hệ thống thật, ai-gateway chưa
 * chạy ở dev thì ca HT-40 ghi "Chờ" (tiêu chí hoàn thành của QA-07).
 *
 * UI Pack (QT-18): bản nháp 1.8 trong kho giả lập do quản trị khác soạn, nên `quantri2` duyệt phát hành
 * được; bản nháp mới do chính người dùng tạo thì bị từ chối tự phát hành (ca bốn mắt). Kho giả lập nạp
 * lại khi tải trang, nên bước "tải lại trang thấy giao diện mới" chỉ kiểm trên hệ thống thật; ở chế độ
 * giả lập kiểm phiên bản đang áp dụng đổi sang 1.8 trong cùng phiên.
 */
const GHI_OKR = /\/api\/v1\/okr\/objectives/;
/** Trạng thái của bản đang trong vòng soạn, duyệt (`UiPackVersionStatus`, `config.yaml`). */
const DANG_SOAN = ['NHAP', 'DANG_KIEM_TRA', 'CHO_DUYET'];

interface PhienBanUi {
  version: string;
  status: string;
  createdBy?: { id: string };
}

test.describe('QT-17 QT-18 Trợ lý AI và giao diện đơn vị', { tag: '@qa-07' }, () => {
  let s: Phien;

  test.beforeEach(({ browser }) => {
    s = new Phien(browser, ['quantri', 'quantri2']);
    kiemMoiTruong();
  });

  test.afterEach(async () => {
    const loi = [...s.loi];
    await s.dong();
    expect(loi, 'không có lỗi console hay phản hồi 5xx').toEqual([]);
  });

  test('HT-40 QT-17 trợ lý AI gợi ý KR, người dùng chấp nhận hoặc bỏ qua, AI không tự ghi', async () => {
    test.setTimeout(HE_THONG ? 600_000 : 60_000);
    if (HE_THONG) {
      const a = await api(s, 'canbo');
      try {
        const r = await a.goi('POST', 'ai/assist/okr', { body: { question: SEED.troLy.cauHoi } });
        // Dev chưa triển khai ai-gateway: APISIX không có tuyến AI nên lệnh rơi về nginx của web (405) hoặc báo
        // 404, 502, 503. Ca này không áp dụng trên dev cho tới khi có ai-gateway
        if ([404, 405, 502, 503].includes(r.status))
          cho('không áp dụng trên dev: ai-gateway (SVC-16) chưa được triển khai ở server dev');
      } finally {
        await a.dong();
      }
    }

    // AC-06 mở trợ lý soạn OKR ở chế độ Đảng viên cao tuổi
    const page = await s.trang('canbo', '/okr/tro-ly');
    await batCaoTuoi(page);
    const m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Soạn OKR cùng trợ lý AI' }),
    ).toBeVisible();
    const ai = m.getByRole('region', { name: /^Trợ lý AI/ });
    await expect(ai.getByText('Chỉ đọc · không ghi')).toBeVisible();
    await kiemAxeOnDinh(page, 'trợ lý AI, chế độ cao tuổi');

    // Theo dõi bản nháp: số KR "Chưa đo được", số nội dung "AI hỗ trợ"; trên hệ thống thật theo dõi thêm
    // mọi lệnh ghi mục tiêu, KR đi ra máy chủ (ở chế độ giả lập kho chạy trong trang, không qua mạng)
    const nhap = m.getByRole('region', { name: /^Bản nháp OKR/ });
    const chuaDo = nhap.getByText('Chưa đo được', { exact: true });
    const aiHoTro = nhap.getByText('AI hỗ trợ', { exact: true });
    const ghi: Request[] = [];
    page.on('request', (r) => {
      if (r.method() !== 'GET' && GHI_OKR.test(r.url())) ghi.push(r);
    });

    // Hỏi trợ lý (`assistOkrDraft`, kèm soát KR của bản nháp): chỉ hiện gợi ý, không ghi gì
    await ai.getByRole('textbox', { name: 'Câu hỏi cho trợ lý' }).fill(SEED.troLy.cauHoi);
    await ai.getByRole('button', { name: 'Gửi câu hỏi' }).click();
    await expect(ai.getByText('Nội dung do AI tạo').first()).toBeVisible({ timeout: 30_000 });
    const goiY = ai.locator('article');
    await expect(goiY.first()).toBeVisible();
    expect(await goiY.count()).toBeGreaterThan(1);
    expect(ghi, 'trợ lý AI không tự ghi mục tiêu, KR khi người dùng chưa quyết định').toEqual([]);
    // Trợ lý chỉ đánh dấu KR chưa đo được trên bản nháp, nội dung KR chưa đổi
    await expect(chuaDo.first()).toBeVisible();
    const n0 = await chuaDo.count();
    const a0 = await aiHoTro.count();
    await kiemAxeOnDinh(page, 'gợi ý của trợ lý AI, chế độ cao tuổi');

    // Bỏ qua một gợi ý (`decideAiSuggestion` BO_QUA): vẫn không ghi gì vào bản nháp
    const boQua = ai.locator('article[data-kind="LIEN_KET_NGANG"]').first();
    await boQua.getByRole('button', { name: 'Bỏ qua' }).click();
    await expect(boQua.getByText('Đã bỏ qua gợi ý.')).toBeVisible();
    expect(ghi, 'bỏ qua gợi ý không ghi bản nháp').toEqual([]);
    await expect(chuaDo).toHaveCount(n0);

    // Dùng một gợi ý (`decideAiSuggestion` CHAP_NHAN): lúc này mới có lệnh ghi do người dùng quyết định
    const dung = ai.locator('article[data-kind="SOAT_KR"]').first();
    await dung.getByRole('button', { name: 'Dùng gợi ý' }).click();
    await expect(
      m.getByRole('status').filter({ hasText: /^Đã chèn gợi ý vào bản nháp\./ }),
    ).toBeVisible();
    await expect(dung.getByText('Đã dùng gợi ý.')).toBeVisible();
    if (HE_THONG)
      expect(ghi.length, 'chỉ ghi sau khi người dùng bấm "Dùng gợi ý"').toBeGreaterThan(0);
    await expect(chuaDo).toHaveCount(n0 - 1);
    // Nội dung chèn từ gợi ý mang cờ "AI hỗ trợ" (BR-02-11), có thể gắn cả mục tiêu lẫn KR
    await expect.poll(() => aiHoTro.count()).toBeGreaterThan(a0);

    // Soát KR ở màn hình soạn (`reviewOkrKeyResults`) cũng chỉ gợi ý
    const truoc = ghi.length;
    await spaGoto(page, '/okr/objectives/new');
    const soat = m.getByRole('button', { name: 'Trợ lý AI soát KR' });
    if (await soat.isVisible()) {
      await soat.click();
      await expect(m.getByText(/AI|gợi ý/i).first()).toBeVisible();
      expect(ghi.length, 'soát KR không ghi dữ liệu').toBe(truoc);
    }
  });

  test('HT-41 QT-18 soạn, xem trước và phát hành UI Pack cùng biểu mẫu động', async () => {
    test.setTimeout(HE_THONG ? 600_000 : 90_000);
    if (HE_THONG) {
      // Trên server dev không có sẵn bản nháp như kho giả lập: seed `data/seed/ui-pack/nap_ui_pack.py`
      // để bản 1.0 ở chờ duyệt hoặc đã phát hành, mỗi lần chạy trọn vẹn phát hành bản nháp của mình, lần
      // chạy dừng giữa chừng để lại bản chờ duyệt. Đọc bản đang trong vòng soạn, duyệt để đưa về nháp.
      const a = await api(s, 'quantri');
      let dang: PhienBanUi | undefined;
      try {
        const r = await a.goi<Trang<{ id: string }>>('GET', 'ui/packs');
        if (r.status !== 200 || !r.body.items?.length)
          cho('tenant thử chưa có UI Pack trong seed cấu hình (DL-07)');
        const goiId = r.body.items[0].id;
        const dangSoan = async () =>
          (
            await a.can<Trang<PhienBanUi>>('GET', `ui/packs/${goiId}/versions`, {
              query: { limit: '50' },
            })
          ).body.items.find((v) => DANG_SOAN.includes(v.status));
        dang = await dangSoan();
        if (dang?.status === 'DANG_KIEM_TRA') {
          // Kiểm tra tự động của lần chạy trước có thể còn đang chạy; chờ nó kết thúc
          await expect
            .poll(async () => (dang = await dangSoan())?.status, {
              timeout: 60_000,
              message: 'bản giao diện của lần chạy trước kẹt ở trạng thái đang kiểm tra tự động',
            })
            .not.toBe('DANG_KIEM_TRA');
        }
      } finally {
        await a.dong();
      }
      if (dang && dang.createdBy?.id === (await s.danhTinh('quantri2')).sub)
        cho(
          `bản ${dang.version} do quantri2 soạn nên quantri2 không duyệt được (bốn mắt); cần quản trị khác trả về hoặc phát hành`,
        );
      if (dang?.status === 'CHO_DUYET') {
        // Bản chờ duyệt còn lại của lần chạy trước: quản trị thứ hai trả về nháp (xác thực nhiều lớp)
        const p2 = await s.trang('quantri2', '/config-studio/giao-dien');
        const m2 = main(p2);
        await m2.getByRole('button', { name: 'Từ chối' }).click();
        const hop = p2.getByRole('dialog', { name: 'Từ chối phiên bản giao diện' });
        await hop
          .getByRole('textbox', { name: /^Ý kiến/ })
          .fill('Trả về để chạy lại hành trình HT-41.');
        await hop.getByRole('button', { name: 'Từ chối' }).click();
        await xacThucBuocLen(
          p2,
          'quantri2',
          m2.getByRole('status').filter({ hasText: /^Đã trả bản .+ về cho người soạn\.$/ }),
          false,
        );
      }
    }

    // AC-02 mở "Giao diện đơn vị" ở chế độ Đảng viên cao tuổi, sửa bản nháp và lưu
    let page = await s.trang('quantri', '/config-studio/giao-dien');
    await batCaoTuoi(page);
    let m = main(page);
    await expect(m.getByRole('heading', { level: 1, name: 'Giao diện đơn vị' })).toBeVisible();
    // Chưa có bản nháp (bản trước đã phát hành) thì tạo từ bản đang dùng, như người dùng thật
    const chip = m.getByTestId('pack-bar').locator('.chip[data-status]');
    const taoNhap = m.getByRole('button', { name: 'Tạo bản nháp từ bản đang dùng' });
    await expect(chip.or(taoNhap).first()).toBeVisible();
    if (await taoNhap.isVisible()) await chip.waitFor({ timeout: 3_000 }).catch(() => undefined);
    if (await taoNhap.isVisible()) {
      await taoNhap.click();
      await expect(
        m.getByRole('status').filter({ hasText: /^Đã tạo bản nháp .+ từ bản đang dùng\.$/ }),
      ).toBeVisible();
    }
    await expect(chip, 'phải có bản nháp để soạn').toHaveText(/ · Bản nháp\s*$/);
    await kiemAxeOnDinh(page, 'giao diện đơn vị, chế độ cao tuổi');
    const nhap = m.getByRole('button', { name: /^Gửi duyệt bản / });
    const ban = (await nhap.innerText()).replace('Gửi duyệt bản', '').trim();
    // Bản nháp nhân bản từ bản lần trước đã phát hành có thể đã bo góc 10: đổi sang giá trị khác để có
    // thay đổi cần lưu (không đổi thì nút "Lưu nháp" không bật)
    const boGoc = m.getByRole('spinbutton', { name: 'Bo góc thẻ' });
    await boGoc.fill((await boGoc.inputValue()) === '10' ? '12' : '10');
    await boGoc.press('Tab');
    await m.getByRole('button', { name: 'Lưu nháp' }).click();
    await expect(m.getByRole('status').filter({ hasText: 'Đã lưu bản nháp.' })).toBeVisible();

    // Xem trước (`previewUiPackVersion`) và kiểm tra tự động, trong đó kích thước trong ngân sách 30 KB.
    // Máy chủ nêu số KB ("… KB, trong ngân sách 30 KB."); giả lập theo YC-PT-24 không nêu số KB
    await expect(
      m.getByRole('figure', { name: new RegExp(`^Xem trước Bản ${ban}`) }),
    ).toBeVisible();
    await expect(
      m.getByText(/Đạt: (.*ngân sách 30 KB|Dung lượng giao diện trong giới hạn cho phép)/),
    ).toBeVisible();
    await m.getByRole('checkbox', { name: 'Xem ở chế độ người cao tuổi' }).check();

    // Biểu mẫu động: tạo bản nháp mới (`createUiFormVersion`), chạy bộ test (`runUiFormTests`) và đưa
    // biểu mẫu đang dựng vào bản nháp giao diện (`attachUiPackForm`)
    await spaGoto(page, '/config-studio/bieu-mau');
    await expect(m.getByRole('heading', { level: 1, name: 'Biểu mẫu động' })).toBeVisible();
    // Lần chạy trước có thể đã tạo bản nháp: khi đó trang chỉ còn "Mở để dựng" (chưa có bản nháp mới cho tới khi
    // phát hành), dùng lại bản nháp đó
    const taoMau = m.getByRole('button', { name: 'Tạo bản nháp mới' });
    await expect(taoMau.or(m.getByRole('link', { name: /^Mở để dựng/ })).first()).toBeVisible();
    if (await taoMau.count()) {
      await taoMau.first().click();
      await expect(
        m.getByRole('heading', { level: 1, name: /^Trình dựng biểu mẫu/ }),
      ).toBeVisible();
      await spaGoto(page, '/config-studio/bieu-mau');
    }
    await m.getByRole('link', { name: 'Mở để dựng' }).first().click();
    await expect(m.getByRole('heading', { level: 1, name: /^Trình dựng biểu mẫu/ })).toBeVisible();
    // Biểu mẫu chưa có ca test (bản dựng mới trên hệ thống thật) thì nút chạy khoá: thêm dữ liệu mẫu đang
    // hiện làm một ca, như người dùng làm theo hướng dẫn trên màn hình
    const khongCoCa = m.getByTestId('no-tests');
    const daDat = m.getByText(/Bộ test (\d+)\/\1 · trình duyệt và máy chủ cùng kết quả/);
    // Bản nháp dùng lại từ lần chạy trước có thể đã chạy đạt bộ test cho đúng nội dung này (nút chạy khoá)
    await expect(
      khongCoCa
        .or(m.getByText(/^Chưa chạy bộ test cho nội dung hiện tại/))
        .or(daDat)
        .first(),
    ).toBeVisible();
    if (await khongCoCa.isVisible()) {
      await m.getByRole('button', { name: 'Thêm dữ liệu đang nhập làm ca test' }).click();
      await expect(m.getByText(/^Đã thêm ca /)).toBeVisible();
      // Ca mới chỉ giữ khi lưu nháp; chưa lưu thì nút "Đưa vào giao diện" khoá vì còn thay đổi chưa lưu
      await m.getByRole('button', { name: 'Lưu nháp' }).click();
      await expect(m.getByText(/^Đã lưu bản nháp\./)).toBeVisible();
    }
    if (!(await daDat.isVisible()) || (await khongCoCa.count()) > 0) {
      await m.getByRole('button', { name: 'Chạy bộ test biểu mẫu' }).click();
    }
    await expect(daDat).toBeVisible({ timeout: 30_000 });
    await m.getByRole('button', { name: `Đưa vào giao diện bản ${ban}` }).click();
    await expect(
      m.getByRole('status').filter({ hasText: new RegExp(`vào giao diện bản ${ban}\\.`) }),
    ).toBeVisible();

    // Gửi duyệt (`submitUiPackVersion`): đạt kiểm tra tự động thì chờ duyệt
    await spaGoto(page, '/config-studio/giao-dien');
    await m.getByRole('button', { name: `Gửi duyệt bản ${ban}` }).click();
    await expect(
      m.getByRole('status').filter({ hasText: /đạt kiểm tra tự động và đã được gửi duyệt/ }),
    ).toBeVisible({ timeout: 30_000 });

    // Quản trị thứ hai duyệt và phát hành (`publishUiPackVersion`, xác thực nhiều lớp) → EVT-23
    const tu = new Date(Date.now() - 5_000);
    page = await s.trang('quantri2', '/config-studio/giao-dien');
    m = main(page);
    await m.getByRole('button', { name: 'Duyệt và phát hành' }).click();
    await xacThucBuocLen(
      page,
      'quantri2',
      m.getByRole('status').filter({ hasText: new RegExp(`Giao diện bản ${ban} đã phát hành`) }),
      !HE_THONG,
    );
    await expect(m.getByText(`Đơn vị đang dùng giao diện bản ${ban}.`)).toBeVisible();

    // Ca phủ định bốn mắt: bản nháp do chính người dùng tạo thì không tự phát hành được
    if (!HE_THONG) {
      await m.getByRole('button', { name: 'Tạo bản nháp từ bản đang dùng' }).click();
      await expect(m.getByRole('status').filter({ hasText: /^Đã tạo bản nháp/ })).toBeVisible();
      await m.getByRole('button', { name: /^Gửi duyệt bản / }).click();
      await expect(m.getByRole('status').filter({ hasText: /đã được gửi duyệt/ })).toBeVisible({
        timeout: 30_000,
      });
      await m.getByRole('button', { name: 'Duyệt và phát hành' }).click();
      await expect(m.getByText('Người duyệt phải khác người soạn phiên bản này.')).toBeVisible();
    }

    await chiHeThong(
      'tải lại trang nhận giao diện mới; thân tạo phiên bản sai bị từ chối',
      async () => {
        const a = await api(s, 'quantri');
        try {
          const hien = await a.can<{ version?: string }>('GET', 'ui/packs/current');
          expect(hien.body.version?.replace(/\.0$/, '')).toBe(ban);
          await page.reload();
          await expect(page.locator('html')).toHaveAttribute('data-tenant', /.+/);
          const goi = await a.can<{ items: { id: string }[] }>('GET', 'ui/packs');
          // `createUiPackVersion` chỉ nhận `version`, `cloneFromVersion` (UiPackVersionCreate): thân sai hợp đồng phải bị
          // từ chối 4xx, không được thành lỗi máy chủ. Ngân sách 30 KB (gzip) đã kiểm ở bước kiểm tra tự động phía trên
          const lon = await a.goi('POST', `ui/packs/${goi.body.items[0].id}/versions`, {
            body: { tokens: { padding: 'x'.repeat(31 * 1024) } },
          });
          expect([409, 413, 422], 'thân tạo phiên bản sai hợp đồng phải bị từ chối').toContain(
            lon.status,
          );
        } finally {
          await a.dong();
        }
      },
    );
    await kiemSuKien('EVT-23', 'config.uipack.published.v1', ban, tu);
  });
});
