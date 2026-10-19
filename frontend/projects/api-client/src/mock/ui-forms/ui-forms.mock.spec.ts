import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { UiFormApi } from '../../lib/ui-form-api';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
    ],
  });
  return TestBed.inject(UiFormApi);
}

describe('mock ui-forms – Mẫu 1B cho SCR-0901', () => {
  it('trả phiên bản đã phát hành của mau-1b; khoá lạ trả 404', async () => {
    const api = setup();
    const f = await firstValueFrom(api.current('mau-1b'));
    expect(f.status).toBe('DA_PHAT_HANH');
    expect(Object.keys((f.schemaJson as { properties: object }).properties)).toEqual([
      'selfRatingCode',
      'breakthrough',
      'reason',
      'content',
    ]);
    expect(f.celRules?.map((r) => r.field)).toEqual(['content', 'reason', 'breakthrough']);
    const err = await firstValueFrom(api.current('khong-co')).catch((e) => e);
    expect((err as HttpErrorResponse).status).toBe(404);
  });
});
