import { expect, test } from '@playwright/test';
import { ApiHeThong } from '../ho-tro/api';
import { batCaoTuoi, chiHeThong } from '../ho-tro/kiem-tra';
import { HE_THONG, cho, ghiCho } from '../ho-tro/moi-truong';
import { Phien, main } from '../ho-tro/phien';
import { choSuKien, coKafkaUi } from '../ho-tro/su-kien';
import {
  KY_GIA_LAP,
  KyThu,
  api,
  bamCoXacThuc,
  canhBao,
  ghiDoDac,
  kiemMoiTruong,
  lapLenh,
  moKy,
  taoKy,
  thongBao,
  topic,
  kiemAxe,
} from './chung';

/**
 * QT-08 Khoá kỳ đánh giá (SCR-0801) → `/api/v1/periods/**` → MOD-08 trên SVC-05, saga `chot-ky` do
 * SVC-06 điều phối → EVT-09, EVT-10, EVT-17, EVT-18, EVT-19 khi khoá xong, EVT-26 khi hoàn tác.
 * Hành trình HT-23, HT-24 của danh mục QA-01.
 */
interface Ky {
  id: string;
  code: string;
  status: string;
  currentLockOrder?: { id: string; rowVersion: number } | null;
}

/** Tìm kỳ theo mã qua `listPeriods` (chỉ chế độ he-thong). */
async function kyTheoMa(c: ApiHeThong, ma: string): Promise<Ky> {
  const r = await c.can<{ items: Ky[] }>('GET', 'periods', { query: { limit: '200' } });
  const ky = r.body.items.find((x) => x.code === ma);
  expect(ky, `kỳ ${ma} đọc lại được qua listPeriods`).toBeTruthy();
  return ky!;
}

/** Thời gian chờ saga khoá kỳ: giả lập tiến một nhịp mỗi lần đọc; hệ thống thật tính điểm thật. */
const CHO_SAGA = HE_THONG ? 15 * 60_000 : 120_000;

test.describe('QT-08 Khoá kỳ đánh giá', { tag: '@qa-06' }, () => {
  let s: Phien;

  test.beforeEach(({ browser }) => {
    // Giả lập: quản trị và lãnh đạo dùng chung một kho dữ liệu trang (chuyển tuyến trong SPA)
    s = new Phien(browser, ['quantri', 'lanhdao']);
    kiemMoiTruong();
  });

  test.afterEach(async () => {
    const loi = [...s.loi];
    await s.dong();
    expect(loi, 'không có lỗi console hay phản hồi 5xx').toEqual([]);
  });

  test('HT-23 QT-08 lập lệnh khoá kỳ, duyệt và theo dõi tiến trình tới khi khoá xong', async () => {
    test.setTimeout(CHO_SAGA + 180_000);
    const tu = new Date();
    // AC-02 tạo kỳ riêng của lần chạy và lập lệnh khoá kỳ, chế độ Đảng viên cao tuổi (NFR-11)
    let page = await s.trang('quantri', '/admin/periods');
    let m = main(page);
    await expect(
      m.getByRole('heading', { level: 1, name: 'Kỳ đánh giá và khoá kỳ' }),
    ).toBeVisible();
    await batCaoTuoi(page);
    await kiemAxe(page, 'kỳ đánh giá và khoá kỳ, chế độ cao tuổi');
    const ky: KyThu = await taoKy(page);
    await lapLenh(page, ky);
    const vungKy = await moKy(page, ky.ten);
    await expect(vungKy.getByText(/Chờ lãnh đạo phê duyệt/)).toBeVisible();
    await kiemAxe(page, 'lệnh khoá kỳ chờ phê duyệt');

    // Ca phủ định BR-08-01: người lập lệnh không tự duyệt lệnh của mình
    if (HE_THONG) {
      await chiHeThong('approvePeriodLockOrder bởi chính người lập trả 403', async () => {
        const c = await api(s, 'quantri');
        const k = await kyTheoMa(c, ky.ma);
        const o = k.currentLockOrder!;
        const kq = await c.goi('POST', `periods/${k.id}/lock-orders/${o.id}/approve`, {
          ifMatch: `"${o.rowVersion}"`,
        });
        expect(kq.status).toBe(403);
        await c.dong();
      });
    } else {
      // Kho giả lập coi mọi thao tác là của một người, nên bấm duyệt chính lệnh vừa lập bị chặn
      await bamCoXacThuc(
        page,
        'quantri',
        vungKy.getByRole('button', { name: 'Phê duyệt lệnh' }),
        canhBao(page, 'Người phê duyệt lệnh phải khác người lập lệnh.'),
      );
    }

    // AC-04 phê duyệt lệnh (MFA bước lên). Giả lập: lệnh chờ duyệt có sẵn do người khác lập.
    const kyDuyet = HE_THONG ? ky.ten : KY_GIA_LAP.lenhChoDuyet;
    page = await s.trang('lanhdao', '/admin/periods');
    let vung = await moKy(page, kyDuyet);
    await bamCoXacThuc(
      page,
      'lanhdao',
      vung.getByRole('button', { name: 'Phê duyệt lệnh' }),
      thongBao(page, `Lệnh khoá kỳ ${kyDuyet} đã được phê duyệt`),
    );

    // AC-02 khởi chạy (khác người phê duyệt, BR-08-01). Giả lập: lệnh đã duyệt có sẵn do người khác duyệt.
    const kyChay = HE_THONG ? ky.ten : KY_GIA_LAP.lenhDaDuyet;
    page = await s.trang('quantri', '/admin/periods');
    m = main(page);
    vung = await moKy(page, kyChay);
    const batDau = Date.now();
    await bamCoXacThuc(
      page,
      'quantri',
      vung.getByRole('button', { name: 'Khởi chạy' }),
      thongBao(page, `Đã khởi chạy tiến trình khoá kỳ ${kyChay}`),
    );

    // Theo dõi sáu bước tới khi hoàn tất; ghi thời gian saga vào báo cáo
    const tienTrinh = m.getByRole('region', { name: /^Tiến trình khoá kỳ/ });
    await expect(tienTrinh.getByRole('table', { name: /Sáu bước/ })).toBeVisible();
    await expect(
      tienTrinh.getByRole('alert').filter({ hasText: `Kỳ ${kyChay} đã khoá lúc` }),
    ).toBeVisible({ timeout: CHO_SAGA });
    const giay = Math.round((Date.now() - batDau) / 1000);
    ghiDoDac(
      `Thời gian saga khoá kỳ (${HE_THONG ? 'hệ thống thật' : 'giả lập'}): ${giay} giây từ lúc khởi chạy tới khi hoàn tất`,
    );
    await expect(
      tienTrinh.getByRole('status').filter({ hasText: /^\s*Hoàn tất\s*$/ }),
    ).toBeVisible();
    const hang = tienTrinh.getByRole('table', { name: /Sáu bước/ }).getByRole('row');
    for (const buoc of ['Kiểm tra tiền điều kiện', 'Khoá kỳ', 'Tính điểm hàng loạt', 'Hoàn tất'])
      await expect(hang.filter({ hasText: buoc }).getByRole('cell').first()).toHaveText(/^\s*Đạt/);
    await expect(
      m.getByRole('button', { name: new RegExp(`^${kyChay.replace('/', '\\/')} Đã khoá`) }),
    ).toBeVisible();
    await kiemAxe(page, 'tiến trình khoá kỳ đã hoàn tất, chế độ cao tuổi');

    await chiHeThong('kỳ DA_KHOA qua getPeriod và EVT-09, 10, 17, 18, 19 trên Kafka', async () => {
      const c = await api(s, 'quantri');
      const k = await kyTheoMa(c, ky.ma);
      const lai = await c.can<Ky>('GET', `periods/${k.id}`);
      expect(lai.body.status).toBe('DA_KHOA');
      await c.dong();
      if (!coKafkaUi()) {
        ghiCho('kiểm EVT-09, 10, 17, 18, 19: chưa đặt E2E_KAFKA_UI_URL');
        return;
      }
      await choSuKien(topic('EVT-09', 'config.period.locked.v1'), k.id, tu);
      // Các sự kiện sau mang khoá hồ sơ, biểu mẫu, yêu cầu ký, niêm phong: kiểm có bản tin từ lúc chạy
      await choSuKien(topic('EVT-10', 'scoring.score.computed.v1'), '', tu);
      await choSuKien(topic('EVT-17', 'dossier.form.generated.v1'), '', tu);
      await choSuKien(topic('EVT-18', 'esign.signature.completed.v1'), '', tu);
      await choSuKien(topic('EVT-19', 'ledger.seal.created.v1'), '', tu);
    });
  });

  test('HT-24 QT-08 huỷ khoá kỳ trước bước ký lô và trở về kỳ mở', async ({ browser }) => {
    test.setTimeout(HE_THONG ? 10 * 60_000 : 120_000);
    const tu = new Date();
    // Giả lập: quản trị và người duyệt thứ hai mở trang riêng, mỗi trang chỉ mang vai trò của mình
    await s.dong();
    s = new Phien(browser);
    {
      let ky: KyThu | null = null;
      let page = await s.trang('quantri', '/admin/periods');
      let m = main(page);
      await expect(
        m.getByRole('heading', { level: 1, name: 'Kỳ đánh giá và khoá kỳ' }),
      ).toBeVisible();
      if (HE_THONG) {
        // Hệ thống thật: kỳ riêng, lệnh do AC-02 lập và AC-04 duyệt trước khi khởi chạy
        ky = await taoKy(page);
        await lapLenh(page, ky);
        const ld = await s.trang('lanhdao', '/admin/periods');
        const v = await moKy(ld, ky.ten);
        await bamCoXacThuc(
          ld,
          'lanhdao',
          v.getByRole('button', { name: 'Phê duyệt lệnh' }),
          thongBao(ld, `Lệnh khoá kỳ ${ky.ten} đã được phê duyệt`),
        );
        page = await s.trang('quantri', '/admin/periods');
        m = main(page);
      }
      const kyChay = ky?.ten ?? KY_GIA_LAP.lenhDaDuyet;
      const vung = await moKy(page, kyChay);
      await bamCoXacThuc(
        page,
        'quantri',
        vung.getByRole('button', { name: 'Khởi chạy' }),
        thongBao(page, `Đã khởi chạy tiến trình khoá kỳ ${kyChay}`),
      );

      // AC-02 đề nghị huỷ và hoàn tác toàn bộ khi bước 4 chưa hoàn tất (BR-08-09)
      const dk = m.getByRole('group', { name: 'Điều khiển tiến trình khoá kỳ' });
      const huy = dk.getByRole('button', { name: 'Huỷ và hoàn tác toàn bộ' });
      if (HE_THONG && !(await huy.isVisible()))
        cho('saga trên dev đã qua bước 4 trước khi kịp đề nghị huỷ (kỳ thử không có hồ sơ)');
      await huy.click();
      const hop = page.getByRole('dialog', { name: 'Huỷ và hoàn tác toàn bộ' });
      const lyDo = 'Kiểm thử hành trình: huỷ khoá kỳ trước bước ký số lô';
      await hop.getByRole('textbox', { name: 'Lý do huỷ' }).fill(lyDo);
      await bamCoXacThuc(
        page,
        'quantri',
        hop.getByRole('button', { name: 'Gửi đề nghị huỷ' }),
        thongBao(page, 'Đề nghị huỷ và hoàn tác cần người duyệt thứ hai'),
      );
      const deNghi = m.getByRole('region', { name: 'Đề nghị huỷ và hoàn tác' });
      await expect(deNghi.getByText(`Lý do: ${lyDo}`)).toBeVisible();
      await kiemAxe(page, 'đề nghị huỷ và hoàn tác');

      // AC-09 duyệt thứ hai (MFA). Giả lập: đề nghị huỷ có sẵn do người khác gửi.
      const kyHuy = ky?.ten ?? KY_GIA_LAP.deNghiHuy;
      page = await s.trang('duyet2', '/admin/periods');
      m = main(page);
      await moKy(page, kyHuy);
      await bamCoXacThuc(
        page,
        'duyet2',
        m
          .getByRole('region', { name: 'Đề nghị huỷ và hoàn tác' })
          .getByRole('button', { name: 'Duyệt huỷ và hoàn tác' }),
        thongBao(page, 'Đã duyệt huỷ; hệ thống đang hoàn tác theo thứ tự ngược.'),
      );
      await expect(
        canhBao(page, `Đã hoàn tác toàn bộ. Kỳ ${kyHuy} trở về trạng thái Đang mở.`),
      ).toBeVisible({
        timeout: HE_THONG ? 5 * 60_000 : 60_000,
      });
      await expect(
        m.getByRole('button', { name: new RegExp(`^${kyHuy.replace('/', '\\/')} Đang mở`) }),
      ).toBeVisible();
      await kiemAxe(page, 'kỳ đã hoàn tác về đang mở');

      await chiHeThong('kỳ về MO qua getPeriod và EVT-26', async () => {
        const c = await api(s, 'quantri');
        const k = await kyTheoMa(c, ky!.ma);
        const lai = await c.can<Ky>('GET', `periods/${k.id}`);
        expect(lai.body.status).toBe('MO');
        await c.dong();
        if (coKafkaUi()) await choSuKien(topic('EVT-26', 'config.period.unlocked.v1'), k.id, tu);
        else ghiCho('kiểm EVT-26: chưa đặt E2E_KAFKA_UI_URL');
      });

      // Ca phủ định: kỳ đã qua bước ký số lô thì không còn lối huỷ và hoàn tác, chỉ còn mở lại hậu kiểm
      let kyDaKhoa: string = KY_GIA_LAP.daKhoa;
      if (HE_THONG) {
        const c = await api(s, 'quantri');
        const r = await c.can<{ items: { id: string; name: string; status: string }[] }>(
          'GET',
          'periods',
          { query: { limit: '200' } },
        );
        const khoa = r.body.items.find((x) => x.status === 'DA_KHOA');
        if (!khoa) {
          await c.dong();
          ghiCho(
            'ca phủ định sau bước ký lô: tenant dev chưa có kỳ đã khoá (HT-23 tạo kỳ đã khoá)',
          );
          return;
        }
        const kq = await c.goi('POST', `periods/${khoa.id}/lock/cancel`, {
          body: { reason: lyDo },
        });
        expect([409, 412, 428]).toContain(kq.status);
        await c.dong();
        kyDaKhoa = khoa.name;
      }
      page = await s.trang('quantri', '/admin/periods');
      m = main(page);
      await moKy(page, kyDaKhoa);
      await expect(m.getByRole('button', { name: 'Huỷ và hoàn tác toàn bộ' })).toHaveCount(0);
    }
  });
});
