import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { PeriodApi } from './period-api';
import type { PeriodLockOrder } from './models/period';

/** QA-06 HT-23: khởi chạy khoá kỳ gửi đúng khoá chống lặp `CK-{năm}{kỳ}` của lệnh (SCR-0801). */
describe('PeriodApi', () => {
  it('khởi chạy gửi Idempotency-Key là khoá của lệnh và If-Match theo phiên bản lệnh', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const api = TestBed.inject(PeriodApi);
    const http = TestBed.inject(HttpTestingController);
    const order = { id: 'o1', rowVersion: 1, idempotencyKey: 'CK-2026Q4' } as PeriodLockOrder;
    const p = firstValueFrom(api.start('p1', order));
    const req = http.expectOne('/api/v1/periods/p1/lock');
    expect(req.request.headers.get('Idempotency-Key')).toBe('CK-2026Q4');
    expect(req.request.headers.get('If-Match')).toBe('"1"');
    expect(req.request.body).toEqual({ lockOrderId: 'o1' });
    req.flush(
      { id: 'j1', status: 'DANG_CHAY' },
      { status: 202, statusText: 'Accepted', headers: { Location: '/api/v1/jobs/j1' } },
    );
    await p;
    http.verify();
  });
});
