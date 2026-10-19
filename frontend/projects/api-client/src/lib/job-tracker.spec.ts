import { HttpHeaders, HttpResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Subject, lastValueFrom, toArray } from 'rxjs';
import { JOB_TRACKER_OPTIONS, JobTracker, jobLocation, retryAfterMs } from './job-tracker';
import { Job } from './models/job';

/** WEB-007: theo dõi 202 + Location: /api/v1/jobs/{id}. */
function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      {
        provide: JOB_TRACKER_OPTIONS,
        useValue: { initialDelayMs: 1, maxDelayMs: 4, maxRetries: 2 },
      },
    ],
  });
  return { tracker: TestBed.inject(JobTracker), ctrl: TestBed.inject(HttpTestingController) };
}

const job = (status: Job['status'], progress?: number): Job => ({
  id: 'J1',
  type: 'PERIOD_LOCK',
  status,
  progress,
});
const tick = (ms = 15) => new Promise((r) => setTimeout(r, ms));

describe('JobTracker', () => {
  it('thăm dò có giãn cách tới khi HOAN_TAT, bỏ trạng thái trùng', async () => {
    const { tracker, ctrl } = setup();
    const all = lastValueFrom(tracker.track('/api/v1/jobs/J1').pipe(toArray()));
    ctrl.expectOne('/api/v1/jobs/J1').flush(job('CHO'));
    await tick();
    ctrl.expectOne('/api/v1/jobs/J1').flush(job('DANG_CHAY', 40));
    await tick();
    ctrl.expectOne('/api/v1/jobs/J1').flush(job('DANG_CHAY', 40));
    await tick();
    ctrl.expectOne('/api/v1/jobs/J1').flush(job('HOAN_TAT', 100));
    const seen = await all;
    expect(seen.map((j) => `${j.status}:${j.progress ?? ''}`)).toEqual([
      'CHO:',
      'DANG_CHAY:40',
      'HOAN_TAT:100',
    ]);
    await tick();
    ctrl.verify();
  });

  it('Location tuyệt đối được rút về đường dẫn tương đối', async () => {
    const { tracker, ctrl } = setup();
    const done = lastValueFrom(tracker.track('https://api.g4.example/api/v1/jobs/J1'));
    ctrl.expectOne('/api/v1/jobs/J1').flush(job('THAT_BAI'));
    expect((await done).status).toBe('THAT_BAI');
  });

  it('thử lại khi 503, báo lỗi ngay khi 404', async () => {
    const { tracker, ctrl } = setup();
    const ok = lastValueFrom(tracker.track('/api/v1/jobs/J1'));
    ctrl
      .expectOne('/api/v1/jobs/J1')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await tick();
    ctrl.expectOne('/api/v1/jobs/J1').flush(job('HOAN_TAT'));
    expect((await ok).status).toBe('HOAN_TAT');

    const nf = lastValueFrom(tracker.track('/api/v1/jobs/J2'));
    ctrl.expectOne('/api/v1/jobs/J2').flush(null, { status: 404, statusText: 'Not Found' });
    await expect(nf).rejects.toMatchObject({ status: 404 });
  });

  it('trộn cập nhật đẩy (SSE) với thăm dò và dừng khi kết thúc', async () => {
    const { tracker, ctrl } = setup();
    const events$ = new Subject<Job>();
    const all = lastValueFrom(tracker.track('/api/v1/jobs/J1', { events$ }).pipe(toArray()));
    ctrl.expectOne('/api/v1/jobs/J1').flush(job('DANG_CHAY', 10));
    events$.next(job('DANG_CHAY', 70));
    events$.next(job('HOAN_TAT', 100));
    expect((await all).map((j) => j.progress)).toEqual([10, 70, 100]);
    await tick();
    ctrl.match('/api/v1/jobs/J1').forEach((r) => r.flush(job('HOAN_TAT', 100)));
  });
});

describe('jobLocation, retryAfterMs', () => {
  it('đọc Location của 202 và Retry-After', () => {
    const res = new HttpResponse({
      status: 202,
      headers: new HttpHeaders({ Location: '/api/v1/jobs/J9' }),
    });
    expect(jobLocation(res)).toBe('/api/v1/jobs/J9');
    expect(jobLocation(new HttpResponse({ status: 200 }))).toBeNull();
    expect(retryAfterMs('3')).toBe(3000);
    const now = Date.parse('2026-10-19T00:00:00Z');
    expect(retryAfterMs('Mon, 19 Oct 2026 00:00:05 GMT', now)).toBe(5000);
    expect(retryAfterMs(null)).toBeNull();
  });
});
