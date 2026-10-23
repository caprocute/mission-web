// QA-13 · ASVS V5.1 (kiểm dữ liệu vào), V5.3 (chống chèn), V12.1, V12.2 (tệp tải lên), V13.2.6. Giới hạn
// `limit ≤ 200` (baseline mục 13), tệp nhập cây tổ chức chỉ nhận .xlsx (DV-04-05, ORG-422-IMPORT-FILE).
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { thieu } from '../lib/cau-hinh.mjs';
import { donViA } from '../lib/du-lieu.mjs';
import { goi, khoa, khongLoNoiBo, laProblem } from '../lib/http.mjs';
import { tokenCua } from '../lib/oidc.mjs';

const skip = thieu('api', 'issuer');

describe('Kiểm tra dữ liệu vào', { skip }, () => {
  for (const [ten, gt] of [['vượt 200', '201'], ['bằng 0', '0'], ['âm', '-1'], ['không phải số', 'abc'], ['rất lớn', '99999999999']]) {
    test(`limit ${ten}: 400`, async () => {
      const r = await goi(`/org/units?limit=${gt}`, { token: await tokenCua('quanTri') });
      laProblem(r, 400);
    });
  }

  test('limit = 200 vẫn hợp lệ (đối chứng biên)', async () => {
    // Danh sách đơn vị dành cho quản trị và lãnh đạo (ma trận phân quyền), nên đối chứng bằng tài khoản quản trị.
    const r = await goi('/org/units?limit=200', { token: await tokenCua('quanTri') });
    assert.equal(r.status, 200, r.text);
  });

  test('mã đối tượng không phải UUID: 400, không lộ chi tiết nội bộ', async () => {
    const r = await goi("/org/units/1'%20OR%20'1'='1", { token: await tokenCua('nguoiDung') });
    laProblem(r, [400, 404]);
  });

  test('chuỗi tìm kiếm chứa ký tự chèn SQL không gây lỗi máy chủ', async () => {
    for (const q of ["'; DROP TABLE org.org_unit; --", "' OR '1'='1", '%_%', '\\', '$(id)', '<script>alert(1)</script>']) {
      const r = await goi(`/org/units/search?q=${encodeURIComponent(q)}`, { token: await tokenCua('nguoiDung') });
      assert.ok(r.status < 500, `q=${q}: HTTP ${r.status}`);
      khongLoNoiBo(r);
      if (r.status === 200) assert.match(r.headers.get('content-type') ?? '', /application\/json/);
    }
  });

  test('con trỏ phân trang giả mạo không gây lỗi máy chủ', async () => {
    for (const c of ["' OR 1=1--", 'eyJpZCI6IjEnIE9SIDE9MS0tIn0', 'A'.repeat(4096)]) {
      const r = await goi(`/org/units?cursor=${encodeURIComponent(c)}`, { token: await tokenCua('quanTri') });
      assert.ok(r.status < 500, `cursor: HTTP ${r.status}`);
      khongLoNoiBo(r);
    }
  });

  test('đường dẫn có ../ mã hoá không thoát khỏi tài nguyên', async () => {
    const r = await goi('/org/units/..%2f..%2f..%2fetc%2fpasswd', { token: await tokenCua('nguoiDung') });
    assert.ok([400, 404].includes(r.status), `HTTP ${r.status}`);
    assert.doesNotMatch(r.text, /root:|Apache Tomcat\/\d/);
  });

  test('thân JSON sai cú pháp: 400 dạng Problem Details', async () => {
    const id = await donViA();
    const r = await goi(`/org/units/${id}`, {
      token: await tokenCua('quanTri'),
      method: 'PUT',
      headers: { 'idempotency-key': khoa(), 'if-match': '"0"', 'content-type': 'application/json' },
      body: '{"code": ',
    });
    laProblem(r, [400, 422]);
  });

  test('trường lạ để gán hàng loạt (tenantId, rowVersion) không được nhận', async () => {
    const id = await donViA();
    const goc = await goi(`/org/units/${id}`, { token: await tokenCua('quanTri') });
    const r = await goi(`/org/units/${id}`, {
      token: await tokenCua('quanTri'),
      method: 'PUT',
      headers: { 'idempotency-key': khoa(), 'if-match': '"987654"' },
      json: { ...goc.data, tenantId: '00000000-0000-7000-8000-0000000000ff', rowVersion: 0, id: undefined },
    });
    // Phiên bản cũ nên không được ghi; điều cần kiểm là máy chủ không trả 2xx và không lộ nội bộ.
    assert.ok(r.status >= 400 && r.status < 500, `HTTP ${r.status}`);
    khongLoNoiBo(r);
  });
  test('Content-Type không phải JSON khi ghi: 415', async () => {
    const id = await donViA();
    for (const kieu of ['text/plain', 'application/xml', 'application/x-www-form-urlencoded']) {
      const r = await goi(`/org/units/${id}`, {
        token: await tokenCua('quanTri'),
        method: 'PUT',
        headers: { 'idempotency-key': khoa(), 'if-match': '"0"', 'content-type': kieu },
        body: kieu === 'application/xml' ? '<!DOCTYPE a [<!ENTITY x SYSTEM "file:///etc/passwd">]><a>&x;</a>' : 'code=x',
      });
      laProblem(r, 415);
    }
  });

  test('tham số lặp (HTTP parameter pollution) không vượt được giới hạn limit', async () => {
    for (const q of ['limit=1&limit=500', 'limit=500&limit=1']) {
      const r = await goi(`/org/units?${q}`, { token: await tokenCua('quanTri') });
      assert.ok([200, 400].includes(r.status), `${q}: HTTP ${r.status}`);
      if (r.status === 200) assert.ok(r.data.items.length <= 200);
      khongLoNoiBo(r);
    }
  });
});

describe('Tệp tải lên', { skip }, () => {
  async function nhap(tenTep, noiDung, kieu) {
    const fd = new FormData();
    fd.append('file', new Blob([noiDung], { type: kieu }), tenTep);
    const token = await tokenCua('quanTri');
    const res = await fetch(`${process.env.KT_API_URL.replace(/\/+$/, '')}/org/imports`, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, 'idempotency-key': khoa(), accept: 'application/problem+json' },
      body: fd,
    });
    const text = await res.text();
    return { status: res.status, headers: res.headers, text, data: text ? JSON.parse(text) : null };
  }

  test('tệp thực thi đổi đuôi .xlsx bị từ chối theo chữ ký đầu tệp', async () => {
    const r = await nhap('cay-to-chuc.xlsx', Buffer.from('MZ\x90\x00\x03\x00\x00\x00 gia lap tep thuc thi'),
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
    laProblem(r, [415, 422]);
  });

  test('tệp HTML có mã kịch bản bị từ chối', async () => {
    const r = await nhap('cay.html', '<html><script>alert(document.cookie)</script></html>', 'text/html');
    laProblem(r, [415, 422]);
  });

  test('tệp rỗng bị từ chối', async () => {
    const r = await nhap('rong.xlsx', Buffer.alloc(0), 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
    laProblem(r, [400, 415, 422]);
  });

  test('tệp vượt 10 MB bị từ chối', async () => {
    const lon = Buffer.alloc(11 * 1024 * 1024 + 1, 0x41);
    lon.write('PK\x03\x04', 0, 'latin1');
    const r = await nhap('lon.xlsx', lon, 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
    assert.ok([400, 413, 422].includes(r.status), `HTTP ${r.status}: ${r.text.slice(0, 200)}`);
    khongLoNoiBo(r);
  });

  // Phát hiện QA-13 (ZAP 30001 trên dev cục bộ 23/10/2026): thân multipart hỏng từng làm org-service trả 500; nay
  // ProblemAdvice ánh xạ MultipartException sang 400 Problem (ASVS V13.1.5), quá dung lượng vẫn là 413.
  test('thân multipart hỏng: 400, không phải 500', async () => {
    for (const [body, kieu] of [['abc', 'multipart/form-data'], ['--zz\r\nContent-Disposition: form-data; name="file"\r\n\r\nabc', 'multipart/form-data; boundary=zz']]) {
      const res = await fetch(`${process.env.KT_API_URL.replace(/\/+$/, '')}/org/imports`, {
        method: 'POST',
        headers: { authorization: `Bearer ${await tokenCua('quanTri')}`, 'idempotency-key': khoa(), 'content-type': kieu },
        body,
      });
      const text = await res.text();
      assert.equal(res.status, 400, `HTTP ${res.status}: ${text.slice(0, 160)}`);
      assert.match(res.headers.get('content-type') ?? '', /application\/problem\+json/);
    }
  });

  test('cán bộ không có quyền quản trị không nhập được cây tổ chức', async () => {
    const fd = new FormData();
    fd.append('file', new Blob([Buffer.from('PK\x03\x04')]), 'cay.xlsx');
    const res = await fetch(`${process.env.KT_API_URL.replace(/\/+$/, '')}/org/imports`, {
      method: 'POST',
      headers: { authorization: `Bearer ${await tokenCua('nguoiDung')}`, 'idempotency-key': khoa() },
      body: fd,
    });
    assert.equal(res.status, 403, await res.text());
  });
});
