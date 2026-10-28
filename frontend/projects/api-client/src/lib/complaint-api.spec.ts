import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { lastValueFrom } from 'rxjs';
import { ComplaintApi } from './complaint-api';
import { JOB_TRACKER_OPTIONS } from './job-tracker';
import { Job } from './models/job';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      {
        provide: JOB_TRACKER_OPTIONS,
        useValue: { initialDelayMs: 1, maxDelayMs: 4, maxRetries: 0 },
      },
    ],
  });
  return { api: TestBed.inject(ComplaintApi), ctrl: TestBed.inject(HttpTestingController) };
}

const job = (status: Job['status']): Job => ({ id: 'J1', type: 'COMPLAINT_SIGN', status });
const tick = (ms = 15) => new Promise((r) => setTimeout(r, ms));

describe('ComplaintApi.settle', () => {
  it('202 chưa xong thì theo dõi job tới khi ký xong', async () => {
    const { api, ctrl } = setup();
    const done = lastValueFrom(api.settle({ location: '/api/v1/jobs/J1', job: job('CHO') }));
    ctrl.expectOne('/api/v1/jobs/J1').flush(job('DANG_CHAY'));
    await tick();
    ctrl.expectOne('/api/v1/jobs/J1').flush(job('HOAN_TAT'));
    expect((await done).status).toBe('HOAN_TAT');
    ctrl.verify();
  });

  it('job đã kết thúc trong phản hồi 202 thì không gọi thêm', async () => {
    const { api, ctrl } = setup();
    const done = await lastValueFrom(
      api.settle({ location: '/api/v1/jobs/J1', job: job('THAT_BAI') }),
    );
    expect(done.status).toBe('THAT_BAI');
    ctrl.verify();
  });
});
