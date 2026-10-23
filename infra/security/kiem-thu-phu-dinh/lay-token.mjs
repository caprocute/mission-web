// QA-13 · Lấy token của người dùng thử cho công cụ khác (ZAP) bằng cùng luồng Code + PKCE của bộ kiểm thử.
//   node lay-token.mjs [access|refresh] [tên đăng nhập]
// In đúng một giá trị ra stdout để gán vào biến (ZAP_REFRESH_TOKEN=$(node lay-token.mjs refresh)); không ghi tệp.
// Không chạy lệnh này trong bước có `set -x` hay in biến ra nhật ký.
import { cauHinh } from './lib/cau-hinh.mjs';
import { dangNhapDayDu } from './lib/oidc.mjs';

const [loai = 'refresh', ten = process.env.KT_ZAP_NGUOI_DUNG ?? cauHinh.quanTri] = process.argv.slice(2);
if (!cauHinh.issuer) {
  console.error('Thiếu KT_OIDC_ISSUER');
  process.exit(2);
}
const t = await dangNhapDayDu(ten);
const v = loai === 'access' ? t.access_token : t.refresh_token;
if (!v) {
  console.error(`Keycloak không trả ${loai} token`);
  process.exit(1);
}
process.stdout.write(v);
