// QA-13 · ASVS V11.1 (logic nghiệp vụ), V13.2 (API REST): ghi phải kèm Idempotency-Key, sửa phải kèm If-Match
// (baseline mục 13; wiki 06). Ca kiểm thử không làm đổi dữ liệu: mọi lệnh sửa đều bị từ chối trước khi ghi, trừ
// `setMyContext` ghi lại đúng phân công hiện có của tài khoản thử.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { thieu } from '../lib/cau-hinh.mjs';
import { dauVaoDonVi, donViA, hoSoNguoiDung } from '../lib/du-lieu.mjs';
import { goi, khoa, laProblem } from '../lib/http.mjs';
import { tokenCua } from '../lib/oidc.mjs';

const skip = thieu('api', 'issuer');

async function donViVaPhienBan() {
  const id = await donViA();
  const r = await goi(`/org/units/${id}`, { token: await tokenCua('quanTri') });
  assert.equal(r.status, 200, r.text);
  assert.ok(r.headers.get('etag'), 'đọc đối tượng sửa được phải trả ETag');
  return { id, etag: r.headers.get('etag'), body: dauVaoDonVi(r.data) };
}

describe('Phiên bản dữ liệu và chống ghi trùng', { skip }, () => {
  test('sửa thiếu If-Match: 428 và đơn vị không đổi', async () => {
    const u = await donViVaPhienBan();
    const r = await goi(`/org/units/${u.id}`, {
      token: await tokenCua('quanTri'),
      method: 'PUT',
      headers: { 'idempotency-key': khoa() },
      json: u.body,
    });
    laProblem(r, 428);
    assert.equal(r.data.code, 'COMMON-428-PRECONDITION-REQUIRED');
  });

  test('sửa với If-Match cũ: 409 xung đột', async () => {
    const u = await donViVaPhienBan();
    const r = await goi(`/org/units/${u.id}`, {
      token: await tokenCua('quanTri'),
      method: 'PUT',
      headers: { 'idempotency-key': khoa(), 'if-match': '"987654"' },
      json: u.body,
    });
    laProblem(r, [409, 412]);
  });

  test('If-Match không phải ETag do máy chủ cấp: 400', async () => {
    const u = await donViVaPhienBan();
    const r = await goi(`/org/units/${u.id}`, {
      token: await tokenCua('quanTri'),
      method: 'PUT',
      headers: { 'idempotency-key': khoa(), 'if-match': '"1 OR 1=1"' },
      json: u.body,
    });
    laProblem(r, [400, 409, 412]);
  });

  test('ghi thiếu Idempotency-Key: 400', async () => {
    const u = await donViVaPhienBan();
    const r = await goi(`/org/units/${u.id}`, {
      token: await tokenCua('quanTri'),
      method: 'PUT',
      headers: { 'if-match': u.etag },
      json: u.body,
    });
    laProblem(r, 400);
  });

  test('dùng lại Idempotency-Key với thân khác: 422, cùng thân: trả lại kết quả cũ', async () => {
    const h = await hoSoNguoiDung();
    const phanCong = h.assignments[0].id;
    const k = khoa();
    const token = await tokenCua('nguoiDung');
    const lan1 = await goi('/org/me/context', {
      token, method: 'PUT', headers: { 'idempotency-key': k }, json: { assignmentId: phanCong },
    });
    assert.equal(lan1.status, 200, lan1.text);
    const khac = await goi('/org/me/context', {
      token, method: 'PUT', headers: { 'idempotency-key': k },
      json: { assignmentId: '00000000-0000-7000-8000-0000000000ff' },
    });
    laProblem(khac, 422);
    assert.equal(khac.data.code, 'COMMON-422-IDEMPOTENCY-MISMATCH');
    const lai = await goi('/org/me/context', {
      token, method: 'PUT', headers: { 'idempotency-key': k }, json: { assignmentId: phanCong },
    });
    assert.equal(lai.status, 200, lai.text);
    assert.deepEqual(lai.data, lan1.data, 'gửi lại cùng khoá và cùng thân phải trả đúng kết quả lần đầu');
  });

  test('Idempotency-Key của người này không dùng được để đọc kết quả của người khác', async () => {
    const h = await hoSoNguoiDung();
    const k = khoa();
    const lan1 = await goi('/org/me/context', {
      token: await tokenCua('nguoiDung'), method: 'PUT', headers: { 'idempotency-key': k },
      json: { assignmentId: h.assignments[0].id },
    });
    assert.equal(lan1.status, 200, lan1.text);
    const nguoiKhac = await goi('/org/me/context', {
      token: await tokenCua('quanTri'), method: 'PUT', headers: { 'idempotency-key': k },
      json: { assignmentId: h.assignments[0].id },
    });
    // Khoá gắn với người gọi: người khác không nhận lại phân công của cán bộ (thường là 404 vì không phải phân công của họ).
    assert.notEqual(nguoiKhac.status, 200, `không được trả lại kết quả của người khác: ${nguoiKhac.text}`);
    laProblem(nguoiKhac, [403, 404, 422]);
  });
});
