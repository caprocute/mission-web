import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { UiPackApi } from '../../lib/ui-pack-api';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { mockStepUp } from '../mock-session';
import { breakMockFormParity } from './ui-packs.mock';

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
  return TestBed.inject(UiPackApi);
}

const fail = <T>(p: Promise<T>) =>
  p.catch((e: HttpErrorResponse) => e) as Promise<HttpErrorResponse>;

describe('mock ui-packs – SCR-1801, SCR-1802 (QT-18)', () => {
  it('lưu nháp chặn tương phản dưới AA (MSG-18-01), gửi duyệt rồi duyệt cần xác thực nhiều lớp', async () => {
    const api = setup();
    const pack = (await firstValueFrom(api.packs())).items[0];
    expect(pack.draftVersion).toBe('1.8.0');
    const r = { id: pack.id, v: '1.8.0' };
    const v = await firstValueFrom(api.version(r));
    const tokens = structuredClone(v.tokens) as { color: { onPrimary: { $value: string } } };
    tokens.color.onPrimary.$value = '#9FD8DF';
    const bad = await fail(firstValueFrom(api.save(r, v.rowVersion, { ...v, tokens })));
    expect(bad.status).toBe(422);
    expect(bad.error.code).toBe('UI-422-CONTRAST');
    expect(bad.error.detail).toContain('chưa đạt mức AA (4,5 : 1)');

    await firstValueFrom(api.submit(r, v.rowVersion));
    await firstValueFrom(api.version(r));
    const checked = await firstValueFrom(api.version(r));
    expect(checked.status).toBe('CHO_DUYET');

    const noMfa = await fail(firstValueFrom(api.publish(r, checked.rowVersion)));
    expect(noMfa.status).toBe(401);
    mockStepUp();
    await firstValueFrom(api.publish(r, checked.rowVersion));
    await firstValueFrom(api.version(r));
    expect((await firstValueFrom(api.version(r))).status).toBe('DA_PHAT_HANH');
    const list = (await firstValueFrom(api.versions(pack.id))).items;
    expect(list.find((x) => x.version === '1.7.0')?.status).toBe('THAY_THE');
  });

  it('quay lui bản dùng bộ chính sách đã ngừng trả MSG-18-04; bản tương thích thì áp dụng lại', async () => {
    const api = setup();
    const pack = (await firstValueFrom(api.packs())).items[0];
    const list = (await firstValueFrom(api.versions(pack.id))).items;
    const v15 = list.find((x) => x.version === '1.5.0')!;
    const err = await fail(
      firstValueFrom(api.activate({ id: pack.id, v: '1.5.0' }, v15.rowVersion)),
    );
    expect(err.error.code).toBe('UI-409-ROLLBACK-INCOMPATIBLE');
    const v16 = list.find((x) => x.version === '1.6.0')!;
    const ok = await firstValueFrom(api.activate({ id: pack.id, v: '1.6.0' }, v16.rowVersion));
    expect(ok.status).toBe('DA_PHAT_HANH');
  });

  it('biểu mẫu: trường tính điểm không xoá được (MSG-18-07); bộ test so hai engine (MSG-18-08)', async () => {
    const api = setup();
    const r = { key: 'phieu-vc-yte', v: '12' };
    const f = await firstValueFrom(api.form(r));
    const props = { ...(f.schemaJson['properties'] as Record<string, unknown>) };
    delete props['ty_le_hsba_dat'];
    const err = await fail(
      firstValueFrom(
        api.saveForm(r, f.rowVersion, {
          schemaJson: { ...f.schemaJson, properties: props },
          uiJson: f.uiJson,
        }),
      ),
    );
    expect(err.error.code).toBe('UI-409-SCORED-FIELD');

    breakMockFormParity('TC-02');
    const run = await firstValueFrom(
      api.runFormTests(r, {
        cases: [
          { caseId: 'TC-01', input: { ty_le_hsba_dat: 104 }, browserValid: false },
          { caseId: 'TC-02', input: { ty_le_hsba_dat: 90 }, browserValid: true },
        ],
      }),
    );
    expect(run.parity).toBe(false);
    expect(run.cases.map((c) => c.match)).toEqual([true, false]);
  });
});
