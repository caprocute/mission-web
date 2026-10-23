import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { afterAll, describe, expect, it } from 'vitest';
import { OkrApi, TaskApi } from '@vt/api-client';
import { dung } from '../khop';
import { TUONG_TAC, TuongTac } from '../tuong-tac/mission-core';

/**
 * Bên tiêu dùng (QA-02): gọi hàm thật của `@vt/api-client`, so yêu cầu bắt được với hợp đồng rồi
 * trả phản hồi mẫu của hợp đồng và kiểm màn hình đọc được các trường cần. Đạt hết thì ghi tệp
 * `pact/pacts/mission-web-mission-core.json` (đặc tả 3.0.0); `npm run pact:check` (chế độ `kiem`)
 * chỉ so với tệp đã commit, lệch thì đỏ (như `api:check`).
 */
// jsdom thay URL toàn cục, nên dựng đường dẫn từ thư mục của tệp kiểm thử.
const TEP = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '../pacts/mission-web-mission-core.json',
);
const daQua = new Set<string>();

function chay(t: TuongTac) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [provideZonelessChangeDetection(), provideHttpClient(), provideHttpClientTesting()],
  });
  const http = TestBed.inject(HttpTestingController);
  const ket_qua = firstValueFrom(
    t.goi({ task: TestBed.inject(TaskApi), okr: TestBed.inject(OkrApi) }),
  );
  const req = http.expectOne((r) => r.url === t.yeu_cau.path);
  expect(req.request.method).toBe(t.yeu_cau.method);
  const query: Record<string, string[]> = {};
  for (const k of req.request.params.keys()) query[k] = req.request.params.getAll(k)!.map(String);
  expect(query).toEqual(t.yeu_cau.query ?? {});
  for (const [k, v] of Object.entries(t.yeu_cau.headers ?? {})) {
    if (k.toLowerCase() === 'content-type') continue; // HttpClient tự đặt khi gửi JSON.
    expect(req.request.headers.get(k), k).toBe(v);
  }
  expect(req.request.body ?? undefined).toEqual(t.yeu_cau.body);
  req.flush(dung(t.phan_hoi.body).mau as object, { status: t.phan_hoi.status, statusText: 'OK' });
  http.verify();
  return ket_qua;
}

function pact() {
  return {
    consumer: { name: 'mission-web' },
    provider: { name: 'mission-core' },
    interactions: TUONG_TAC.map((t) => {
      const body = dung(t.phan_hoi.body);
      return {
        description: t.mo_ta,
        providerStates: t.trang_thai,
        request: {
          method: t.yeu_cau.method,
          path: t.yeu_cau.path,
          ...(t.yeu_cau.query ? { query: t.yeu_cau.query } : {}),
          ...(t.yeu_cau.headers ? { headers: t.yeu_cau.headers } : {}),
          ...(t.yeu_cau.body !== undefined ? { body: t.yeu_cau.body } : {}),
        },
        response: {
          status: t.phan_hoi.status,
          headers: { 'Content-Type': 'application/json' },
          body: body.mau,
          matchingRules: { body: body.luat },
        },
      };
    }),
    metadata: { pactSpecification: { version: '3.0.0' } },
  };
}

describe('hợp đồng mission-web với mission-core', () => {
  for (const t of TUONG_TAC) {
    it(t.mo_ta, async () => {
      const kq = await chay(t);
      const truong = t.doc(kq as never);
      expect(
        truong.every((v) => v !== undefined),
        JSON.stringify(truong),
      ).toBe(true);
      daQua.add(t.mo_ta);
    });
  }

  it('mô tả tương tác không trùng', () => {
    expect(new Set(TUONG_TAC.map((t) => t.mo_ta)).size).toBe(TUONG_TAC.length);
  });

  afterAll(() => {
    if (daQua.size !== TUONG_TAC.length) return;
    const moi = JSON.stringify(pact(), null, 2) + '\n';
    if (import.meta.env.MODE === 'kiem') {
      const cu = readFileSync(TEP, 'utf8');
      if (cu !== moi) {
        throw new Error(
          'Tệp pact đã commit lệch với tương tác hiện tại; chạy `npm run pact` rồi commit lại.',
        );
      }
    } else {
      writeFileSync(TEP, moi);
    }
  });
});
