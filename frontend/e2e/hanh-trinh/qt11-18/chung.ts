import { Page, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ApiHeThong, LoiApi } from '../ho-tro/api';
import { kiemAxe } from '../ho-tro/kiem-tra';
import { HE_THONG, MA_LAN_CHAY, bienConThieu, cho, ghiCho } from '../ho-tro/moi-truong';
import { Phien, TenTaiKhoan } from '../ho-tro/phien';
import { choSuKien, coKafkaUi } from '../ho-tro/su-kien';

/**
 * Phần dùng chung của nhóm hành trình QT-11..QT-18 (QA-07). Mỗi hành trình chạy được ở hai chế độ:
 * giả lập (`npm run e2e:hanh-trinh`, dữ liệu khởi tạo của kho giả lập trong `api-client/src/mock`) và
 * hệ thống thật (`npm run e2e:he-thong`, dữ liệu tìm hoặc dựng qua API thật, kiểm thêm sự kiện Kafka).
 *
 * Kho giả lập chỉ có một danh tính người dùng và nạp lại khi tải lại trang, nên ở chế độ giả lập các
 * tài khoản cùng tham gia một luồng dùng chung một trang (tham số `chung` của `Phien`) và các bước
 * dựa vào bản ghi khởi tạo có vai trò sẵn của người dùng đó (chủ trì, thư ký, người xử lý...).
 * Trên hệ thống thật, mỗi tài khoản một phiên Keycloak riêng; bản ghi cần có sẵn (hồ sơ có thể khiếu
 * nại, kỳ đã khoá, cảnh báo) lấy qua API, thiếu thì ca ghi "Chờ" kèm lý do thay vì báo đỏ.
 */

/** Trên hệ thống thật, thiếu biến môi trường bắt buộc thì ca được ghi "Chờ". */
export function kiemMoiTruong() {
  if (!HE_THONG) return;
  const thieu = bienConThieu();
  if (thieu.length) cho(`môi trường chưa đặt ${thieu.join(', ')}`);
}

/** Client API của `user` bằng access token thật (chỉ gọi ở chế độ he-thong). */
export async function api(s: Phien, user: TenTaiKhoan): Promise<ApiHeThong> {
  return ApiHeThong.tao(await s.token(user), user);
}

/** Dữ liệu nhập của hành trình (`data/seed/e2e/qt11-18.json`), `{MA}` thay bằng tiền tố lần chạy. */
const SEED_THO = readFileSync(
  join(__dirname, '..', '..', '..', '..', 'data/seed/e2e/qt11-18.json'),
  'utf8',
).replaceAll('{MA}', MA_LAN_CHAY);
export const SEED = JSON.parse(SEED_THO) as {
  kiemDiem: { gopY: string; yKienHoiNghi: string; bieuQuyet: Record<string, number> };
  khieuNai: { lyDo: string; deNghi: string; yeuCauGiaiTrinh: string; giaiTrinh: string };
  ketLuan: { noiDung: string; soVanBan: string; ghiChuKhongGiaiTrinh: string };
  moLai: { canCu: string; lyDo: string; yKienDuyet1: string; yKienDuyet2: string };
  canhBao: { ghiChuGiao: string; ghiChuKetLuan: string };
  chinhSach: { yKienPheDuyet: string };
  dashboard: { ten: string };
  troLy: { cauHoi: string };
};

export interface Trang<T> {
  items: T[];
  nextCursor?: string | null;
}

/**
 * Lỗi do dữ liệu khởi tạo hoặc dịch vụ chưa sẵn sàng trên server dev thì đánh dấu chờ thay vì báo đỏ:
 * 401 là dịch vụ chưa nhận JWT Keycloak, 404 trên API danh sách là dịch vụ chưa định tuyến qua APISIX,
 * 403 là tài khoản thử chưa có phạm vi (đơn vị, hồ sơ cán bộ) trong seed.
 */
export function choNeuChuaSan(e: unknown, buoc: string): never {
  if (e instanceof LoiApi) {
    if (e.kq.status === 401) cho(`${buoc}: dịch vụ chưa nhận JWT Keycloak (${e.message})`);
    if (e.kq.status === 404 || e.kq.status === 502 || e.kq.status === 503)
      cho(`${buoc}: dịch vụ chưa chạy hoặc chưa định tuyến qua APISIX (${e.message})`);
    if (e.kq.status === 403)
      cho(`${buoc}: tài khoản thử chưa có phạm vi dữ liệu trong seed (${e.message})`);
  }
  throw e;
}

/** Tìm phần tử đầu tiên thoả `dk` trong danh sách API; không có thì ghi "Chờ" với `lyDo`. */
export async function timHoacCho<T>(
  a: ApiHeThong,
  path: string,
  query: Record<string, string>,
  dk: (x: T) => boolean,
  lyDo: string,
): Promise<T> {
  let kq;
  try {
    kq = await a.can<Trang<T>>('GET', path, { query: { limit: '200', ...query } });
  } catch (e) {
    choNeuChuaSan(e, lyDo);
  }
  const x = kq.body.items.find(dk);
  if (!x)
    cho(`${lyDo}: chưa có dữ liệu khởi tạo phù hợp trên server dev (data/seed/e2e/README.md)`);
  return x;
}

/** Kiểm sự kiện trên Kafka khi có kafka-ui; không có thì ghi chú bước chờ. */
export async function kiemSuKien(ma: string, topic: string, loc: string, tu: Date) {
  if (!HE_THONG) {
    test.info().annotations.push({
      type: 'khong-ap-dung',
      description: `kiểm ${ma} ${topic} (chỉ kiểm trên hệ thống thật)`,
    });
    return;
  }
  if (!coKafkaUi()) {
    ghiCho(`kiểm ${ma}: chưa đặt E2E_KAFKA_UI_URL (kafka-ui của hạ tầng dev, INF-22)`);
    return;
  }
  await choSuKien(process.env[`E2E_TOPIC_${ma.replace('-', '')}`]?.trim() || topic, loc, tu);
}

/** Mã bản ghi ở đoạn cuối đường dẫn chi tiết (`/.../<id>`). */
export const maTuDuongDan = (url: string) =>
  new URL(url).pathname.split('/').filter(Boolean).pop()!;

async function choHetHieuUng(page: Page) {
  // Đợi hai khung hình để trình duyệt kịp tính kiểu và khởi động transition, rồi đợi mọi hiệu ứng dừng
  await page.evaluate(
    () => new Promise((xong) => requestAnimationFrame(() => requestAnimationFrame(xong))),
  );
  await page
    .waitForFunction(() => document.getAnimations().every((a) => a.playState !== 'running'), null, {
      polling: 100,
      timeout: 5_000,
    })
    .catch(() => undefined);
}

/**
 * Quét axe sau khi các hiệu ứng hiện dần (thông báo PrimeNG, hộp thoại) đã chạy xong: quét giữa lúc mờ dần
 * cho kết quả tương phản màu sai (đã gặp ở thông báo cảnh báo màn hình khiếu nại, khoảng 1/20 lần chạy).
 * Nếu lần quét đầu vẫn báo lỗi thì đợi thêm rồi quét lại một lần: lỗi thật vẫn còn ở lần quét thứ hai.
 */
export async function kiemAxeOnDinh(page: Page, ten: string) {
  await choHetHieuUng(page);
  try {
    await kiemAxe(page, ten);
  } catch {
    await page.waitForTimeout(500);
    await choHetHieuUng(page);
    await kiemAxe(page, ten);
  }
}
