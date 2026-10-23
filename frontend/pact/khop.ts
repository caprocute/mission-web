/**
 * Bộ dựng luật so khớp Pact đặc tả v3 (QA-02): giá trị mẫu kèm luật theo đường dẫn `$...`, cùng
 * khuôn với tệp hợp đồng của integration-gateway (DV-13-11) để bên cung cấp xác minh bằng chung một
 * bộ so khớp. Chỉ khai báo trường bên tiêu dùng thực sự đọc; máy chủ trả thêm trường không làm hỏng.
 */
export interface Khop {
  readonly __khop: true;
  readonly mau: unknown;
  readonly luat: Record<string, unknown>;
  readonly con?: unknown;
}

const khop = (mau: unknown, luat: Record<string, unknown>, con?: unknown): Khop => ({
  __khop: true,
  mau,
  luat,
  con,
});

const laKhop = (v: unknown): v is Khop =>
  typeof v === 'object' && v !== null && (v as Khop).__khop === true;

/** Cùng kiểu với mẫu (lan xuống nút con). */
export const giongKieu = (mau: unknown) => khop(mau, { match: 'type' });
/** Mảng có ít nhất `min` phần tử, mỗi phần tử theo khuôn `phanTu`. */
export const moiPhanTu = (phanTu: unknown, min = 1) =>
  khop(undefined, { match: 'type', min }, phanTu);
export const soNguyen = (mau: number) => khop(mau, { match: 'integer' });
export const khopMau = (mau: string, regex: string) => khop(mau, { match: 'regex', regex });
export const uuid = (mau: string) =>
  khopMau(mau, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
export const ngay = (mau: string) => khop(mau, { match: 'date', format: 'yyyy-MM-dd' });

/** Tách khuôn thành giá trị mẫu và bảng luật `matchingRules.body`. */
export function dung(
  khuon: unknown,
  duong = '$',
  luat: Record<string, unknown> = {},
): {
  mau: unknown;
  luat: Record<string, unknown>;
} {
  if (laKhop(khuon)) {
    luat[duong] = { matchers: [khuon.luat] };
    if (khuon.con !== undefined) {
      const phanTu = dung(khuon.con, `${duong}[*]`, luat).mau;
      return { mau: [phanTu], luat };
    }
    return { mau: dung(khuon.mau, duong, luat).mau, luat };
  }
  if (Array.isArray(khuon)) {
    return { mau: khuon.map((v, i) => dung(v, `${duong}[${i}]`, luat).mau), luat };
  }
  if (typeof khuon === 'object' && khuon !== null) {
    const mau: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(khuon)) mau[k] = dung(v, `${duong}.${k}`, luat).mau;
    return { mau, luat };
  }
  return { mau: khuon, luat };
}
