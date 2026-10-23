// QA-13 · Cấu hình bộ kiểm thử phủ định. Mọi địa chỉ, tài khoản và bí mật đọc từ biến môi trường; repo không chứa
// giá trị thật (AGENTS.md quy tắc 4). Mặc định khớp realm dev `infra/dev/keycloak/realm-mission.json`.

const env = process.env;

function bat(ten) {
  const v = env[ten];
  return v === undefined || v === '' ? null : v;
}

function boDauGach(url) {
  return url ? url.replace(/\/+$/, '') : url;
}

export const cauHinh = Object.freeze({
  /** Gốc API, gồm cả `/api/v1`, ví dụ địa chỉ cổng APISIX hoặc org-service chạy riêng. */
  api: boDauGach(bat('KT_API_URL')),
  /** Issuer realm, ví dụ `<keycloak>/realms/mission`. */
  issuer: boDauGach(bat('KT_OIDC_ISSUER')),
  clientId: bat('KT_CLIENT_ID') ?? 'mission-web',
  /** Phải khớp một redirect URI đã đăng ký của client; bộ kiểm thử chỉ đọc `code` từ Location, không mở địa chỉ này. */
  redirectUri: bat('KT_REDIRECT_URI') ?? 'http://localhost:4200/',
  matKhau: bat('KT_MAT_KHAU') ?? bat('KC_TEST_USER_PASSWORD'),
  otpSecret: bat('KT_OTP_SECRET') ?? bat('KC_TEST_OTP_SECRET'),
  /** AC-06 cán bộ (vai trò member, không cần OTP). */
  nguoiDung: bat('KT_NGUOI_DUNG') ?? 'canbo',
  /** AC-02 quản trị tenant A (cần OTP theo luồng mission-browser). */
  quanTri: bat('KT_QUAN_TRI') ?? 'quantri',
  /** Tài khoản thuộc tenant khác tenant A; trống thì bỏ qua kiểm thử chéo tenant. */
  quanTriKhac: bat('KT_QUAN_TRI_KHAC'),
  /** Token đã cấp sẵn (khi không đăng nhập được bằng biểu mẫu, ví dụ realm có VNeID bắt buộc). */
  token: {
    nguoiDung: bat('KT_TOKEN_NGUOI_DUNG'),
    quanTri: bat('KT_TOKEN_QUAN_TRI'),
    quanTriKhac: bat('KT_TOKEN_QUAN_TRI_KHAC'),
    hetHan: bat('KT_TOKEN_HET_HAN'),
  },
  /** Số giây tối đa chịu chờ một token thật hết hạn (gồm độ lệch đồng hồ 60 giây của Spring); 0 là không chờ. */
  choHetHanToiDa: Number(bat('KT_CHO_HET_HAN_GIAY') ?? '0'),
  /** Mã đơn vị hợp lệ của tenant A; trống thì lấy đơn vị đầu tiên trong danh sách. */
  donViA: bat('KT_DON_VI_A'),
  /** Origin lạ dùng để kiểm CORS. */
  originLa: bat('KT_ORIGIN_LA') ?? 'https://ke-gian.example',
});

/** Lý do bỏ qua khi thiếu cấu hình bắt buộc, dùng với `test(..., { skip })`. */
export function thieu(...ten) {
  const vang = ten.filter((t) => {
    const v = t.split('.').reduce((o, k) => (o == null ? o : o[k]), cauHinh);
    return v == null || v === '';
  });
  return vang.length ? `thiếu cấu hình: ${vang.join(', ')}` : false;
}
