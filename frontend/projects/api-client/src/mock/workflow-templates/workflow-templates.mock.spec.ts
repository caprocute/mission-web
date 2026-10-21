import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { WorkflowTemplateApi } from '../../lib/workflow-template-api';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { mockStepUp } from '../mock-session';

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
  return TestBed.inject(WorkflowTemplateApi);
}

const fail = <T>(p: Promise<T>) =>
  p.catch((e: HttpErrorResponse) => e) as Promise<HttpErrorResponse>;

describe('mock workflow-templates – SCR-1505 (QT-15, BS-01)', () => {
  it('lưu bị linter máy chủ chặn; test đạt rồi đề nghị phát hành; người soạn không tự duyệt', async () => {
    const api = setup();
    const draft = (await firstValueFrom(api.list('NHAP'))).items[0];
    const t = await firstValueFrom(api.get(draft.id));
    const bad = t.bpmnXml!.replace('flowable:type="external-worker"', '');
    const err = await fail(
      firstValueFrom(api.save(t.id, t.rowVersion, { bpmnXml: bad, paramSchema: t.paramSchema! })),
    );
    expect(err.status).toBe(422);
    expect(err.error.errors[0].rule).toBe('chi-external-worker');

    await firstValueFrom(api.runTests(t.id));
    await firstValueFrom(api.get(t.id));
    const tested = await firstValueFrom(api.get(t.id));
    expect(tested.status).toBe('DAT_KIEM_THU');
    const sub = await firstValueFrom(api.submit(t.id, tested.rowVersion));
    expect(sub.status).toBe('CHO_PHE_DUYET');
    mockStepUp();
    const same = await fail(firstValueFrom(api.approve(t.id, sub.rowVersion)));
    expect(same.error.code).toBe('WORKFLOW-403-SAME-AUTHOR');
  });

  it('phê duyệt cần xác thực nhiều lớp, rồi triển khai cuốn chiếu từ CELL-NB', async () => {
    const api = setup();
    const p = (await firstValueFrom(api.list('CHO_PHE_DUYET'))).items[0];
    expect((await fail(firstValueFrom(api.approve(p.id, p.rowVersion)))).status).toBe(401);
    mockStepUp();
    await firstValueFrom(api.approve(p.id, p.rowVersion));
    await firstValueFrom(api.get(p.id));
    expect((await firstValueFrom(api.get(p.id))).status).toBe('HIEU_LUC');
    const r1 = (await firstValueFrom(api.rollouts(p.id))).items;
    expect(r1.map((x) => x.status)).toEqual(['DA_TRIEN_KHAI', ...Array(8).fill('CHO')]);
    expect(r1[0].cellCode).toBe('CELL-NB');
  });
});
