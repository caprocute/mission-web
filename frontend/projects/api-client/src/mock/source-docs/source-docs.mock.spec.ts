import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AiApi } from '../../lib/ai-api';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { SourceDocApi } from '../../lib/source-doc-api';
import { TaskSuggestion } from '../../lib/models/source-doc';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';

/** WEB-029: hành vi giả lập SCR-0502 (BR-05-05, BR-05-06, BR-05-07, gộp, bỏ, giao theo lô). */
function setup() {
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
  return { docs: TestBed.inject(SourceDocApi), ai: TestBed.inject(AiApi) };
}

const confirm = (docs: SourceDocApi, s: TaskSuggestion, similarReason?: string) =>
  firstValueFrom(
    docs.fromDocument({
      sourceDocId: 'sd-512',
      suggestionId: s.id,
      paragraphNo: s.paragraphNo,
      fields: s.fields,
      editedFields: [],
      similarReason,
    }),
  );

async function errorOf(p: Promise<unknown>): Promise<HttpErrorResponse> {
  try {
    await p;
  } catch (e) {
    return e as HttpErrorResponse;
  }
  throw new Error('không có lỗi');
}

describe('mock source-docs – SCR-0502 (WEB-029)', () => {
  beforeEach(() => resetMockData());

  it('liệt kê văn bản đến kèm số nhiệm vụ chờ xác nhận và trả kết quả trích', async () => {
    const { docs, ai } = setup();
    const page = await firstValueFrom(docs.list({ status: 'CHO_XU_LY' }));
    const d = page.items.find((x) => x.id === 'sd-512')!;
    expect(d.pendingCount).toBe(2);
    const ex = await firstValueFrom(ai.extraction('sd-512'));
    expect(ex.suggestions.map((s) => s.decision)).toEqual([
      'DA_XAC_NHAN',
      'CHO_XAC_NHAN',
      'CHO_XAC_NHAN',
    ]);
    expect(ex.suggestions[0].taskCode).toMatch(/^NV-/);
    expect(ex.suggestions[2].similar[0].code).toBe('NV-2026-Q4-0098');
    const full = await firstValueFrom(docs.get('sd-512'));
    expect(full.paragraphs[0].text).not.toContain('{{');
  });

  it('xác nhận ghi ô đã sửa; có nhiệm vụ gần giống thì bắt buộc lý do (BR-05-06)', async () => {
    const { docs, ai } = setup();
    const ex = await firstValueFrom(ai.extraction('sd-512'));
    const s2 = ex.suggestions[1];
    const done = await confirm(docs, { ...s2, fields: { ...s2.fields, productName: 'Báo cáo' } });
    expect(done.decision).toBe('DA_XAC_NHAN');
    expect(done.editedFields).toEqual(['productName']);
    const err = await errorOf(confirm(docs, ex.suggestions[2]));
    expect(err.status).toBe(422);
    expect(err.error.errors[0].field).toBe('similarReason');
    expect((await confirm(docs, ex.suggestions[2], 'Phạm vi khác')).decision).toBe('DA_XAC_NHAN');
  });

  it('chặn tạo nhiệm vụ lần hai cho cùng đoạn văn bản (BR-05-07, MSG-05-09)', async () => {
    const { docs, ai } = setup();
    const ex = await firstValueFrom(ai.extraction('sd-87'));
    const err = await errorOf(
      firstValueFrom(
        docs.fromDocument({
          sourceDocId: 'sd-87',
          suggestionId: ex.suggestions[0].id,
          paragraphNo: 1,
          fields: ex.suggestions[0].fields,
          editedFields: [],
          similarReason: 'x',
        }),
      ),
    );
    expect(err.status).toBe(409);
    expect(err.error.title).toBe('Đoạn 1 của văn bản đã được tạo nhiệm vụ NV-2026-Q4-0120.');
  });

  it('gộp, bỏ, giao theo lô rồi đánh dấu văn bản đã xử lý', async () => {
    const { docs, ai } = setup();
    const ex = await firstValueFrom(ai.extraction('sd-512'));
    const [s1, s2, s3] = ex.suggestions;
    const merged = await firstValueFrom(
      docs.mergeInto(s3.similar[0].id, {
        sourceDocId: 'sd-512',
        suggestionId: s3.id,
        paragraphNo: 4,
      }),
    );
    expect(merged.decision).toBe('DA_GOP');
    expect((await errorOf(firstValueFrom(ai.reject(s2.id, ' ')))).status).toBe(422);
    expect((await firstValueFrom(ai.reject(s2.id, 'Đã có trong kế hoạch'))).decision).toBe('DA_BO');
    const results = await firstValueFrom(docs.assignBatch([s1.taskId!]));
    expect(results[0]).toMatchObject({ assigned: true, status: 'DA_GIAO' });
    const page = await firstValueFrom(docs.list({ status: 'DA_XU_LY' }));
    expect(page.items.map((d) => d.id)).toContain('sd-512');
  });

  it('bỏ qua văn bản phải có lý do', async () => {
    const { docs } = setup();
    expect((await errorOf(firstValueFrom(docs.dismiss('sd-33', '')))).status).toBe(422);
    expect((await firstValueFrom(docs.dismiss('sd-33', 'Chỉ là lịch làm việc'))).status).toBe(
      'DA_BO_QUA',
    );
  });
});
