// QA-13 · Tìm dữ liệu thật để kiểm thử mà không cần nạp dữ liệu riêng: đơn vị và phân công của tài khoản cán bộ.
import { cauHinh } from './cau-hinh.mjs';
import { goi } from './http.mjs';
import { tokenCua } from './oidc.mjs';

let hoSo;

/** Hồ sơ `GET /org/me` của tài khoản cán bộ (AC-06): mã người, các phân công còn hiệu lực. */
export async function hoSoNguoiDung() {
  if (!hoSo) {
    const r = await goi('/org/me', { token: await tokenCua('nguoiDung') });
    if (r.status !== 200) throw new Error(`Không đọc được hồ sơ người dùng thử: HTTP ${r.status}`);
    hoSo = r.data;
  }
  return hoSo;
}

/** Đơn vị thuộc tenant A: lấy từ cấu hình hoặc từ phân công đầu tiên của cán bộ. */
export async function donViA() {
  if (cauHinh.donViA) return cauHinh.donViA;
  const h = await hoSoNguoiDung();
  const id = h.assignments?.[0]?.orgUnitId;
  if (!id) throw new Error('Tài khoản cán bộ thử chưa có phân công; đặt KT_DON_VI_A');
  return id;
}

/** Thân `OrgUnitInput` từ bản đọc `OrgUnit`, giữ nguyên giá trị để ca kiểm thử không làm đổi dữ liệu. */
export function dauVaoDonVi(u) {
  const keys = ['code', 'nationalCode', 'name', 'shortName', 'level', 'unitType', 'subjectGroupId', 'flows', 'parentId',
    'validFrom', 'validTo'];
  return Object.fromEntries(keys.filter((k) => u?.[k] != null).map((k) => [k, u[k]]));
}
