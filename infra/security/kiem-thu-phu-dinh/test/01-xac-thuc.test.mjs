// QA-13 · ASVS V2/V3 (mức 3): API chỉ chấp nhận token truy cập hợp lệ do realm cấp. SEC-01, SEC-03 (TL-04 mục 9.3).
// Mỗi ca gọi một thao tác đọc thật (`getMyOrgProfile`) để chắc rằng từ chối là do token, không do đường dẫn.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { setTimeout as cho } from 'node:timers/promises';
import { cauHinh, thieu } from '../lib/cau-hinh.mjs';
import { goi, laTuChoiXacThuc } from '../lib/http.mjs';
import { giaiMa, khongKy, kyBangKhoaLa, kyHs256, suaThan } from '../lib/jwt.mjs';
import { tokenCua } from '../lib/oidc.mjs';

const DICH = '/org/me';
const skip = thieu('api', 'issuer');

describe('Xác thực token truy cập', { skip }, () => {
  test('token hợp lệ được chấp nhận (đối chứng)', async () => {
    const r = await goi(DICH, { token: await tokenCua('nguoiDung') });
    assert.equal(r.status, 200, r.text);
  });

  test('thiếu token: 401 kèm WWW-Authenticate Bearer', async () => {
    laTuChoiXacThuc(await goi(DICH));
  });

  test('sai lược đồ xác thực (Basic) bị từ chối', async () => {
    const r = await goi(DICH, { headers: { authorization: 'Basic YWRtaW46YWRtaW4=' } });
    laTuChoiXacThuc(r);
  });

  test('chuỗi rác không phải JWT bị từ chối', async () => {
    laTuChoiXacThuc(await goi(DICH, { token: 'khong-phai-jwt' }));
  });

  test('alg none (không chữ ký) bị từ chối', async () => {
    const { payload } = giaiMa(await tokenCua('nguoiDung'));
    laTuChoiXacThuc(await goi(DICH, { token: khongKy(payload) }));
  });

  test('ký bằng khoá lạ nhưng giữ kid của realm bị từ chối', async () => {
    const { header, payload } = giaiMa(await tokenCua('nguoiDung'));
    laTuChoiXacThuc(await goi(DICH, { token: kyBangKhoaLa(header, payload) }));
  });

  test('nhầm thuật toán HS256 với khoá công khai làm bí mật bị từ chối', async () => {
    const { header, payload } = giaiMa(await tokenCua('nguoiDung'));
    const jwks = await (await fetch(`${cauHinh.issuer}/protocol/openid-connect/certs`)).text();
    laTuChoiXacThuc(await goi(DICH, { token: kyHs256(header, payload, jwks) }));
  });

  test('sửa thân token (đổi tenant, nâng vai trò) mà giữ chữ ký gốc bị từ chối', async () => {
    const t = await tokenCua('nguoiDung');
    const doiTenant = suaThan(t, (p) => ({ ...p, tenant: '00000000-0000-7000-8000-0000000000ff', tenant_id: undefined }));
    laTuChoiXacThuc(await goi(DICH, { token: doiTenant }));
    const nangQuyen = suaThan(t, (p) => ({ ...p, realm_access: { roles: ['platform_admin', 'tenant_admin'] } }));
    laTuChoiXacThuc(await goi(DICH, { token: nangQuyen }));
  });

  test('token có exp trong quá khứ (giả mạo) bị từ chối', async () => {
    const { header, payload } = giaiMa(await tokenCua('nguoiDung'));
    const qua = Math.floor(Date.now() / 1000) - 3600;
    const t = kyBangKhoaLa(header, { ...payload, iat: qua - 300, exp: qua });
    laTuChoiXacThuc(await goi(DICH, { token: t }));
  });

  test('token do issuer khác cấp bị từ chối', async () => {
    const { header, payload } = giaiMa(await tokenCua('nguoiDung'));
    const t = kyBangKhoaLa(header, { ...payload, iss: 'https://idp-gia.example/realms/mission' });
    laTuChoiXacThuc(await goi(DICH, { token: t }));
  });

  test('token thật đã hết hạn bị từ chối', async (t) => {
    if (cauHinh.token.hetHan) {
      laTuChoiXacThuc(await goi(DICH, { token: cauHinh.token.hetHan }));
      return;
    }
    const token = await tokenCua('nguoiDung');
    // Spring Security chấp nhận lệch đồng hồ 60 giây (JwtTimestampValidator), nên chờ thêm 61 giây sau exp.
    const conLai = giaiMa(token).payload.exp * 1000 + 61_000 - Date.now();
    if (conLai > cauHinh.choHetHanToiDa * 1000) {
      t.skip(`cần chờ ${Math.ceil(conLai / 1000)} giây; đặt KT_TOKEN_HET_HAN hoặc KT_CHO_HET_HAN_GIAY`);
      return;
    }
    await cho(Math.max(conLai, 0));
    laTuChoiXacThuc(await goi(DICH, { token }));
  });
});
