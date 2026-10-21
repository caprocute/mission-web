import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { DossierApi } from '../../lib/dossier-api';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { mockSession, mockStepUp } from '../mock-session';
import { HttpClient } from '@angular/common/http';
import { Job } from '../../lib/models/job';

/** WEB-041: giả lập gói hồ sơ số của MOD-06 (SCR-1102). */
function setup() {
  resetMockData();
  mockSession.reset();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
    ],
  });
  return TestBed.inject(DossierApi);
}

async function errorOf(p: Promise<unknown>): Promise<HttpErrorResponse> {
  try {
    await p;
  } catch (e) {
    return e as HttpErrorResponse;
  }
  throw new Error('không có lỗi');
}

const G = (n: number) => `0192a3f4-1102-7000-8000-${String(n).padStart(12, '0')}`;

async function finish(location: string) {
  const http = TestBed.inject(HttpClient);
  for (let i = 0; i < 5; i++) {
    const job = await firstValueFrom(http.get<Job>(location));
    if (job.status !== 'DANG_CHAY' && job.status !== 'CHO') return job;
  }
  throw new Error('job không kết thúc');
}

describe('dossier packages mock (WEB-041)', () => {
  it('danh sách, biểu mẫu, danh sách kiểm tra theo mockup', async () => {
    const api = setup();
    const page = await firstValueFrom(api.packages());
    expect(page.items.map((p) => p.code)).toContain('DP-2026-N-0142');
    const g1 = await firstValueFrom(api.package(G(1)));
    expect(g1.status).toBe('CHO_KY');
    expect(g1.central?.profileCount).toBe(2);
    const forms = await firstValueFrom(api.packageForms(G(1)));
    expect(forms.items.length).toBe(g1.formCounts.total);
    const cl = await firstValueFrom(api.packageChecklist(G(1)));
    expect(cl.passed).toBe(false);
    expect(cl.items.filter((i) => i.status === 'CANH_BAO').length).toBe(2);
    const failed = await firstValueFrom(api.packageForms(G(3), 'LOI'));
    expect(failed.items.length).toBe(2);
    expect((await errorOf(firstValueFrom(api.formFile(failed.items[0].id)))).status).toBe(409);
  });

  it('ký gói: step-up, chặn khi danh sách kiểm tra chưa đạt (MSG-11-04), ký xong khoá gói', async () => {
    const api = setup();
    const g1 = await firstValueFrom(api.package(G(1)));
    expect((await errorOf(firstValueFrom(api.signPackage(g1)))).status).toBe(401);
    mockStepUp();
    const e = await errorOf(firstValueFrom(api.signPackage(g1)));
    expect(e.status).toBe(409);
    expect(e.error.msg).toBe('MSG-11-04');
    expect(e.error.detail).toContain('2 mục kiểm tra chưa đạt');
    const g2 = await firstValueFrom(api.package(G(2)));
    expect((await errorOf(firstValueFrom(api.signPackage({ ...g2, rowVersion: 9 })))).status).toBe(
      409,
    );
    const { location, signRequestId } = await firstValueFrom(api.signPackage(g2));
    expect(signRequestId).toBeTruthy();
    expect((await finish(location)).status).toBe('HOAN_TAT');
    const after = await firstValueFrom(api.package(G(2)));
    expect(after.status).toBe('DA_NOP');
    expect(after.submittedAt).toBeTruthy();
    expect((await errorOf(firstValueFrom(api.signPackage(after)))).error.code).toBe(
      'DOSSIER-409-PACKAGE-LOCKED',
    );
  });

  it('sinh lại mẫu lỗi đưa gói về chờ ký; gói chưa sẵn sàng không tải được', async () => {
    const api = setup();
    const g3 = await firstValueFrom(api.package(G(3)));
    expect((await errorOf(firstValueFrom(api.exportPackage(G(3))))).status).toBe(409);
    const { location } = await firstValueFrom(api.regenerate(g3));
    expect((await finish(location)).status).toBe('HOAN_TAT');
    const after = await firstValueFrom(api.package(G(3)));
    expect(after.status).toBe('CHO_KY');
    expect(after.formCounts.failed).toBe(0);
    expect((await errorOf(firstValueFrom(api.regenerate(after)))).status).toBe(409);
  });

  it('tải gói ZIP kèm manifest; gửi gói con chỉ khi gói đã ký', async () => {
    const api = setup();
    const job = await finish((await firstValueFrom(api.exportPackage(G(4)))).location);
    expect(job.resultLocation).toBeTruthy();
    const file = await firstValueFrom(api.download(job.resultLocation!));
    expect(file.body?.type).toBe('application/zip');
    expect(
      (await errorOf(firstValueFrom(api.transfer(G(1), { destination: 'BTC_TW' })))).status,
    ).toBe(409);
    expect(
      (await errorOf(firstValueFrom(api.transfer(G(4), { destination: 'VPTW' })))).status,
    ).toBe(422);
    const sent = await finish(
      (await firstValueFrom(api.transfer(G(4), { destination: 'BTC_TW' }))).location,
    );
    expect(sent.status).toBe('HOAN_TAT');
  });
});
