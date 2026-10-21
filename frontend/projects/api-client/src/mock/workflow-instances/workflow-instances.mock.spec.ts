import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { WorkflowInstanceApi } from '../../lib/workflow-instance-api';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';

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
  resetMockData();
  return TestBed.inject(WorkflowInstanceApi);
}

const fail = <T>(p: Promise<T>) =>
  p.catch((e: HttpErrorResponse) => e) as Promise<HttpErrorResponse>;

describe('mock workflow-instances – SCR-1506 (QT-15, BS-01)', () => {
  it('chuyển người xử lý chỉ cho người cùng nhóm ứng viên, gia hạn phải sau hạn cũ, ghi lịch sử', async () => {
    const api = setup();
    const stuck = (await firstValueFrom(api.list({ stuck: true }))).items;
    expect(stuck.length).toBe(2);
    const t = stuck.find((i) => !i.assigneeId)!;
    const bad = await fail(
      firstValueFrom(
        api.reassign(t.currentTaskId!, { assigneeId: 'p-dung', reason: 'Nghỉ phép dài ngày' }),
      ),
    );
    expect(bad.error.code).toBe('WORKFLOW-422-ASSIGNEE-NOT-ELIGIBLE');
    await firstValueFrom(
      api.reassign(t.currentTaskId!, { assigneeId: 'p-binh', reason: 'Nghỉ phép dài ngày' }),
    );
    const early = await fail(
      firstValueFrom(
        api.extend(t.currentTaskId!, {
          dueAt: '2020-01-01T00:00:00Z',
          reason: 'Chờ bổ sung minh chứng',
        }),
      ),
    );
    expect(early.status).toBe(422);
    const later = new Date(Date.now() + 5 * 86_400_000).toISOString();
    await firstValueFrom(
      api.extend(t.currentTaskId!, { dueAt: later, reason: 'Chờ bổ sung minh chứng' }),
    );
    expect((await firstValueFrom(api.list({ stuck: true }))).items.length).toBe(1);
    const h = await firstValueFrom(api.history(t.businessKey));
    expect(h.entries.map((e) => e.action)).toEqual([
      'TAO_TIEN_TRINH',
      'CHUYEN_NGUOI_XU_LY',
      'GIA_HAN',
    ]);
    const g = await firstValueFrom(api.diagram(t.businessKey));
    expect(g.activeActivityIds).toEqual(['capTrenNhanXet']);
    expect(g.bpmnXml).toContain('capTrenNhanXet');
  });
});
