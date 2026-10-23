// QA-13 · ASVS V4.1, V4.2 (mức 2): cô lập tenant và chống IDOR. SEC-05 (RLS theo tenant_id, TL-03 "RLS"); wiki 14
// mục 42 (chỉ client dịch vụ được uỷ quyền mới dùng X-Tenant-Id).
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { thieu } from '../lib/cau-hinh.mjs';
import { dauVaoDonVi, donViA } from '../lib/du-lieu.mjs';
import { goi, khoa, laProblem } from '../lib/http.mjs';
import { giaiMa } from '../lib/jwt.mjs';
import { tokenCua } from '../lib/oidc.mjs';

const coBanKhac = thieu('api', 'issuer') || (thieu('quanTriKhac') && thieu('token.quanTriKhac'));

describe('Cô lập dữ liệu giữa các tenant', { skip: coBanKhac }, () => {
  test('người tenant B không đọc được đơn vị của tenant A theo mã (404, không phải 403)', async () => {
    const id = await donViA();
    const a = await goi(`/org/units/${id}`, { token: await tokenCua('nguoiDung') });
    assert.equal(a.status, 200, `đối chứng tenant A: ${a.text}`);
    const b = await goi(`/org/units/${id}`, { token: await tokenCua('quanTriKhac') });
    // 404 để không xác nhận sự tồn tại của bản ghi tenant khác (ASVS V4.2.1).
    laProblem(b, 404);
  });

  test('X-Tenant-Id do người dùng tự gửi không đổi được tenant', async () => {
    const id = await donViA();
    const { payload } = giaiMa(await tokenCua('nguoiDung'));
    const tenantCuaA = payload.tenant_id ?? payload.tenant;
    const r = await goi(`/org/units/${id}`, {
      token: await tokenCua('quanTriKhac'),
      headers: { 'x-tenant-id': tenantCuaA },
    });
    laProblem(r, 404);
  });

  test('người tenant B không sửa được đơn vị tenant A', async () => {
    const id = await donViA();
    const goc = await goi(`/org/units/${id}`, { token: await tokenCua('nguoiDung') });
    const r = await goi(`/org/units/${id}`, {
      token: await tokenCua('quanTriKhac'),
      method: 'PUT',
      headers: { 'idempotency-key': khoa(), 'if-match': goc.headers.get('etag') ?? '"0"' },
      json: dauVaoDonVi(goc.data),
    });
    laProblem(r, [403, 404]);
    const sau = await goi(`/org/units/${id}`, { token: await tokenCua('nguoiDung') });
    assert.equal(sau.headers.get('etag'), goc.headers.get('etag'), 'đơn vị tenant A không được đổi phiên bản');
  });

  test('cán bộ không có quyền quản trị không sửa được đơn vị của chính tenant mình (403)', async () => {
    const id = await donViA();
    const goc = await goi(`/org/units/${id}`, { token: await tokenCua('nguoiDung') });
    const r = await goi(`/org/units/${id}`, {
      token: await tokenCua('nguoiDung'),
      method: 'PUT',
      headers: { 'idempotency-key': khoa(), 'if-match': goc.headers.get('etag') },
      json: dauVaoDonVi(goc.data),
    });
    laProblem(r, 403);
  });
});
