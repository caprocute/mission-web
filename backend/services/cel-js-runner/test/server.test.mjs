// Kiểm thử bộ chạy cel-js (CORE-05-07): biểu thức của gói khối Đảng cho cùng kết quả với cel-java.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createServer, evaluateBatch, parseBody } from '../dist/server.mjs';

const nv = { ke_hoach: 10n, so_luong_duyet: 12n, muc_chat_luong: 1.0, so_lan_chinh_sua: 0n, ngay_lam_viec_cham_chu_quan: 2n };
const thamSo = { tru_tien_do: 0.05, cach_tinh_chat_luong: 'NAM_MUC_PL3', tru_moi_lan_chinh_sua: 0.25 };

function run(expression, inputs, returnType = 'DECIMAL') {
  return evaluateBatch({ items: [{ expression, inputs, returnType, variableTypes: {} }] }).results[0];
}

test('KPI nhiệm vụ: math.least, double(), toán tử ba ngôi', () => {
  const a = run('nv.ke_hoach == 0 ? 1.0 : math.least(double(nv.so_luong_duyet) / double(nv.ke_hoach), 1.0)', { nv });
  assert.deepEqual(a, { ok: true, value: 1, errorCode: null, message: null });
  const c = run('math.greatest(0.0, 1.0 - tham_so.tru_tien_do * double(ngay_cham))', { tham_so: thamSo, ngay_cham: 2n });
  assert.equal(c.ok, true);
  assert.ok(Math.abs(c.value - 0.9) < 1e-12);
});

test('macro map, filter, exists và hàm sum cộng chính xác thập phân', () => {
  const kq = [{ diem_nhom: 0.1 }, { diem_nhom: 0.2 }];
  assert.equal(run('sum(ket_qua_nhom.map(k, k.diem_nhom))', { ket_qua_nhom: kq }).value, 0.3);
  const quy = [
    { quy: 1n, xep_loai: 'KHTNV', co_xep_loai: true },
    { quy: 2n, xep_loai: 'KHTNV', co_xep_loai: true },
  ];
  assert.equal(run("ket_qua_quy.filter(q, q.xep_loai == 'KHTNV').size()", { ket_qua_quy: quy }).value, 2);
  const bo = run(
    "[1, 2, 3].exists(i, ket_qua_quy.exists(a, a.quy == i && a.xep_loai == 'KHTNV') && ket_qua_quy.exists(b, b.quy == i + 1 && b.xep_loai == 'KHTNV'))",
    { ket_qua_quy: quy },
    'BOOL',
  );
  assert.equal(bo.value, true);
});

test('min_muc, tra bảng và chuỗi rỗng', () => {
  assert.equal(run("min_muc('HTXSNV', 'HTNV')", {}, 'STRING').value, 'HTNV');
  assert.equal(
    run("{'THUC_HIEN_TOT': 'HTXSNV', 'DAM_BAO': 'HTTNV'}[m]", { m: 'DAM_BAO' }, 'STRING').value,
    'HTTNV',
  );
});

test('lỗi trả mã chuẩn', () => {
  assert.equal(run('1 +', {}).errorCode, 'POLICY-422-CEL-TYPE');
  assert.equal(run("'a'", {}, 'DECIMAL').errorCode, 'POLICY-422-CEL-TYPE');
  assert.equal(run('x + 1.0', {}).ok, false);
});

test('đọc JSON giữ kiểu int', () => {
  const body = parseBody('{"a": 10, "b": 10.0, "c": [1, 2.5]}');
  assert.equal(body.a, 10n);
  assert.equal(body.b, 10);
  assert.deepEqual(body.c, [1n, 2.5]);
});

test('máy chủ HTTP trả kết quả cùng thứ tự', async () => {
  const server = createServer();
  await new Promise((r) => server.listen(0, r));
  const { port } = server.address();
  try {
    const res = await fetch(`http://127.0.0.1:${port}/v1/cel/evaluate`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        items: [
          { expression: 'x * 2', inputs: { x: 3 }, returnType: 'DECIMAL' },
          { expression: 'y > 0', inputs: { y: 1 }, returnType: 'BOOL' },
        ],
      }),
    });
    assert.equal(res.status, 200);
    const out = await res.json();
    assert.deepEqual(out.results.map((r) => r.value), [6, true]);
    assert.equal((await fetch(`http://127.0.0.1:${port}/healthz`)).status, 200);
  } finally {
    server.close();
  }
});
