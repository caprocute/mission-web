import { test } from '@playwright/test';

/**
 * Cấu hình của chế độ `E2E_MODE=he-thong` (QA-03): mọi địa chỉ, tài khoản và bí mật chỉ đọc từ biến
 * môi trường của máy chạy (Remote Control trên máy có đường tới server dev), không ghi vào repo.
 *
 * | Biến | Ý nghĩa |
 * |---|---|
 * | `E2E_APISIX_URL` | Gốc APISIX dev (SVC-01), ví dụ `http://localhost:<cổng>`; trình duyệt gọi qua `serve.mjs` |
 * | `E2E_KC_URL` | Gốc Keycloak dev (realm `mission`) |
 * | `KC_TEST_USER_PASSWORD`, `KC_TEST_OTP_SECRET` | Mật khẩu và khoá OTP chung của tài khoản thử (WEB-056) |
 * | `E2E_KAFKA_UI_URL`, `E2E_KAFKA_CLUSTER` | API của kafka-ui để đọc topic EVT (tuỳ chọn; thiếu thì ca kiểm sự kiện chờ) |
 * | `E2E_RUN_ID` | Tiền tố dữ liệu của lần chạy; mặc định sinh theo thời gian |
 */
export const HE_THONG = process.env.E2E_MODE === 'he-thong';

const bo = (s: string | undefined) => (s ?? '').trim().replace(/\/$/, '');

export const MOI_TRUONG = {
  apisix: bo(process.env.E2E_APISIX_URL),
  keycloak: bo(process.env.E2E_KC_URL),
  matKhau: process.env.KC_TEST_USER_PASSWORD ?? '',
  otp: process.env.KC_TEST_OTP_SECRET ?? '',
  kafkaUi: bo(process.env.E2E_KAFKA_UI_URL),
  kafkaCluster: (process.env.E2E_KAFKA_CLUSTER ?? 'mission-dev').trim(),
};

/** Tiền tố riêng của lần chạy, gắn vào tên dữ liệu để dọn và để hai lần chạy không đụng nhau. */
export const MA_LAN_CHAY =
  process.env.E2E_RUN_ID?.trim() ||
  `E2E-${new Date().toISOString().replace(/\D/g, '').slice(2, 14)}-${Math.random()
    .toString(36)
    .slice(2, 6)}`;

/** Các biến bắt buộc còn thiếu (không in giá trị). */
export function bienConThieu(): string[] {
  const out: string[] = [];
  if (!MOI_TRUONG.apisix) out.push('E2E_APISIX_URL');
  if (!MOI_TRUONG.keycloak) out.push('E2E_KC_URL');
  if (!MOI_TRUONG.matKhau) out.push('KC_TEST_USER_PASSWORD');
  if (!MOI_TRUONG.otp) out.push('KC_TEST_OTP_SECRET');
  return out;
}

/**
 * Đánh dấu ca hoặc một bước đang chờ việc khác (ví dụ JWT resource server của mission-core, seed hồ
 * sơ cán bộ): ghi chú `cho` để reporter đưa vào báo cáo, rồi bỏ qua ca. Không coi là xanh.
 */
export function cho(lyDo: string): never {
  test.info().annotations.push({ type: 'cho', description: lyDo });
  test.skip(true, `Chờ: ${lyDo}`);
  throw new Error('unreachable');
}

/** Ghi chú một bước đã bỏ qua vì chờ việc khác nhưng ca vẫn chạy tiếp các bước còn lại. */
export function ghiCho(lyDo: string) {
  test.info().annotations.push({ type: 'cho', description: lyDo });
}
