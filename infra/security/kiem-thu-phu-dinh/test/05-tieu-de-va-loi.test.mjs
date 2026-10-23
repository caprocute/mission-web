// QA-13 · ASVS V7.4 (xử lý lỗi), V8.1.1 (không đệm dữ liệu nhạy cảm), V14.4 (tiêu đề HTTP), V14.5 (CORS, phương thức).
// SEC-19 (CSP và tiêu đề bảo mật, TL-04 mục 9.6) áp cho trang web; với API chỉ kiểm các tiêu đề có nghĩa cho JSON.
import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import { describe, test } from 'node:test';
import { cauHinh, thieu } from '../lib/cau-hinh.mjs';
import { goi, laProblem } from '../lib/http.mjs';
import { tokenCua } from '../lib/oidc.mjs';

const skip = thieu('api', 'issuer');

describe('Tiêu đề phản hồi và thông báo lỗi', { skip }, () => {
  test('phản hồi có dữ liệu cá nhân không được đệm và chống đoán kiểu', async () => {
    const r = await goi('/org/me', { token: await tokenCua('nguoiDung') });
    assert.equal(r.status, 200);
    assert.equal(r.headers.get('x-content-type-options'), 'nosniff');
    assert.match(r.headers.get('cache-control') ?? '', /no-store/);
    assert.match(r.headers.get('content-type') ?? '', /^application\/json/);
  });

  test('không quảng bá công nghệ máy chủ', async () => {
    const r = await goi('/org/me', { token: await tokenCua('nguoiDung') });
    assert.equal(r.headers.get('x-powered-by'), null);
    assert.doesNotMatch(r.headers.get('server') ?? '', /\d+\.\d+/, 'tiêu đề Server không được kèm số phiên bản');
  });

  test('404 cho đường dẫn không có: Problem Details, không lộ nội bộ', async () => {
    const r = await goi('/khong-ton-tai-qa13', { token: await tokenCua('nguoiDung') });
    laProblem(r, [403, 404]);
  });

  test('phương thức không khai trong hợp đồng: 405', async () => {
    const r = await goi(`/org/me`, { method: 'DELETE', token: await tokenCua('nguoiDung'), headers: { 'idempotency-key': 'qa13' } });
    laProblem(r, 405);
  });

  test('Accept không hỗ trợ: 406, không đổi sang định dạng khác', async () => {
    const r = await goi('/org/me', { token: await tokenCua('nguoiDung'), headers: { accept: 'application/xml' } });
    laProblem(r, 406);
  });

  test('phương thức TRACE không phản hồi lại yêu cầu', async () => {
    // fetch không gửi được TRACE nên dùng node:http trực tiếp.
    const token = await tokenCua('nguoiDung');
    const u = new URL(`${cauHinh.api}/org/me`);
    const lib = u.protocol === 'https:' ? https : http;
    const { status, text } = await new Promise((ok, loi) => {
      const req = lib.request(u, { method: 'TRACE', headers: { authorization: `Bearer ${token}` } }, (res) => {
        let b = '';
        res.on('data', (c) => (b += c));
        res.on('end', () => ok({ status: res.statusCode, text: b }));
      });
      req.on('error', loi);
      req.end();
    });
    assert.ok(status >= 400, `TRACE trả ${status}`);
    assert.doesNotMatch(text, /authorization:/i, 'TRACE không được phản hồi lại tiêu đề Authorization');
  });

  test('CORS không cấp quyền cho origin lạ', async () => {
    const pre = await goi('/org/me', {
      method: 'OPTIONS',
      headers: { origin: cauHinh.originLa, 'access-control-request-method': 'GET', 'access-control-request-headers': 'authorization' },
    });
    const acao = pre.headers.get('access-control-allow-origin');
    assert.ok(acao !== '*' && acao !== cauHinh.originLa, `preflight cho phép origin lạ: ${acao}`);
    const r = await goi('/org/me', { token: await tokenCua('nguoiDung'), headers: { origin: cauHinh.originLa } });
    const acao2 = r.headers.get('access-control-allow-origin');
    assert.ok(acao2 !== '*' && acao2 !== cauHinh.originLa, `phản hồi cho phép origin lạ: ${acao2}`);
    assert.notEqual(r.headers.get('access-control-allow-credentials'), 'true');
  });

  test('401 không phân biệt "token sai" với "người dùng không tồn tại"', async () => {
    const a = await goi('/org/me', { token: 'a.b.c' });
    const b = await goi('/org/me');
    assert.equal(a.status, 401);
    assert.equal(b.status, 401);
    assert.equal(a.data?.code, b.data?.code, 'mã lỗi 401 phải như nhau để không gợi ý cho kẻ tấn công');
  });
});
