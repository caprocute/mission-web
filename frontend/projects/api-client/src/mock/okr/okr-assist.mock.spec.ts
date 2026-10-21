import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AiApi } from '../../lib/ai-api';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { MSG_17_02 } from './okr-assist';

/** WEB-049: giả lập trợ lý AI soạn OKR của SVC-14 (SCR-1701). */
function setup() {
  resetMockData();
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
  return TestBed.inject(AiApi);
}

const ask = { periodId: 'p-2026-q4', unitId: 'u-btc', draftObjectiveIds: ['o-3'] };

describe('okr assist mock (WEB-049)', () => {
  it('soạn OKR: hai mục tiêu có nguồn, soát KR mơ hồ và lưu ý liên kết ngang', async () => {
    const api = setup();
    const out = await firstValueFrom(api.assistOkr({ ...ask, question: 'Soạn OKR quý cho Ban' }));
    expect(out.kind).toBe('result');
    if (out.kind !== 'result') return;
    const r = out.result;
    expect(r.label).toBe('Nội dung do AI tạo');
    const kinds = r.suggestions.map((s) => s.kind);
    expect(kinds.filter((k) => k === 'MUC_TIEU').length).toBe(2);
    const vague = r.suggestions.find((s) => s.kind === 'SOAT_KR')!;
    expect(vague.krCode).toBe('KR 3.3');
    expect(vague.sources.length).toBeGreaterThan(0);
    expect(kinds).toContain('LIEN_KET_NGANG');
  });

  it('rào chắn chặn câu hỏi chấm điểm, xếp loại (MSG-17-02)', async () => {
    const api = setup();
    const out = await firstValueFrom(
      api.assistOkr({ ...ask, question: 'Đề xuất xếp loại cho tôi' }),
    );
    if (out.kind !== 'result') throw new Error('không mong 202');
    expect(out.result.blocked?.message).toBe(MSG_17_02);
    expect(out.result.suggestions.length).toBe(0);
  });

  it('ghi quyết định một lần; câu hỏi rỗng trả 422', async () => {
    const api = setup();
    const out = await firstValueFrom(api.assistOkr({ ...ask, question: 'Soát KR' }));
    if (out.kind !== 'result') throw new Error('không mong 202');
    const id = out.result.suggestions[0].id;
    const rec = await firstValueFrom(api.decide(id, { decision: 'BO_QUA' }));
    expect(rec.decision).toBe('BO_QUA');
    const again = await firstValueFrom(api.decide(id, { decision: 'CHAP_NHAN' })).catch(
      (e: HttpErrorResponse) => e,
    );
    expect((again as HttpErrorResponse).status).toBe(409);
    const bad = await firstValueFrom(api.assistOkr({ ...ask, question: ' ' })).catch(
      (e: HttpErrorResponse) => e,
    );
    expect((bad as HttpErrorResponse).status).toBe(422);
  });
});
