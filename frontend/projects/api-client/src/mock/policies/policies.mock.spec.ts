import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { shadowedDecisionRows, stepsForLevels } from '../../lib/models/policy';
import { PolicyApi } from '../../lib/policy-api';
import { toProblem } from '../../lib/problem-details';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { mockSession, mockStepUp } from '../mock-session';

/** WEB-045: giả lập Policy Pack SCR-1501, SCR-1502 theo `policies.yaml`. */
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
  return TestBed.inject(PolicyApi);
}

const fail = async (p: Promise<unknown>) => {
  try {
    await p;
  } catch (e) {
    return toProblem(e);
  }
  throw new Error('Mong đợi lỗi');
};

describe('mock policies – SCR-1501, SCR-1502 (WEB-045)', () => {
  beforeEach(() => resetMockData());

  it('danh sách gói, phiên bản kèm ETag, quy tắc theo thứ tự tính', async () => {
    const api = setup();
    const { items } = await firstValueFrom(api.packs());
    expect(items.map((p) => p.code)).toEqual(['khoi-dang/quy-dinh-moi', 'nn-335', 'vc-su-nghiep']);
    const ref = { id: items[0].id, v: '2.1.0' };
    const v = await firstValueFrom(api.version(ref));
    expect(v.value.status).toBe('NHAP');
    expect(v.etag).toBe('"1"');
    const rules = await firstValueFrom(api.rules(ref));
    expect(rules.map((r) => r.ruleKey).slice(0, 4)).toEqual(['A', 'B', 'C', 'kpi_nhiem_vu']);
  });

  it('lưu quy tắc: 422 cú pháp, 422 tổng nhóm, 409 ETag cũ, thành công tăng rowVersion', async () => {
    const api = setup();
    const ref = { id: (await firstValueFrom(api.packs())).items[0].id, v: '2.1.0' };
    const rules = await firstValueFrom(api.rules(ref));
    const c = rules.find((r) => r.ruleKey === 'C')!;
    const bad = await fail(
      firstValueFrom(api.saveRule(ref, c, { expression: '(1 + 2', resultType: 'DECIMAL' })),
    );
    expect(bad.status).toBe(422);
    expect(bad['code']).toBe('POLICY-422-CEL-TYPE');
    const nhom = rules.find((r) => r.ruleKey === 'nhom')!;
    const sum = await fail(
      firstValueFrom(
        api.saveRule(ref, nhom, {
          expression: nhom.expression,
          resultType: 'DECIMAL',
          params: { diem_toi_da: { thuong_xuyen: 30, then_chot: 60 } },
        }),
      ),
    );
    expect(sum['code']).toBe('POLICY-422-GROUP-SUM');
    expect(sum.detail).toContain('90');
    const saved = await firstValueFrom(
      api.saveRule(ref, c, {
        expression: c.expression,
        resultType: 'DECIMAL',
        params: { ...c.params, tru_tien_do: 0.1 },
      }),
    );
    expect(saved.rowVersion).toBe(c.rowVersion + 1);
    expect(saved.statusReverted).toBe(false);
    const stale = await fail(
      firstValueFrom(api.saveRule(ref, c, { expression: c.expression, resultType: 'DECIMAL' })),
    );
    expect(stale.status).toBe(409);
  });

  it('sửa phiên bản đạt kiểm thử thì về nháp; phiên bản đã phát hành không sửa được', async () => {
    const api = setup();
    const packs = (await firstValueFrom(api.packs())).items;
    const nn = { id: packs[1].id, v: '1.4.0' };
    const b = (await firstValueFrom(api.rules(nn))).find((r) => r.ruleKey === 'B')!;
    const saved = await firstValueFrom(
      api.saveRule(nn, b, { expression: b.expression, resultType: 'DECIMAL' }),
    );
    expect(saved.statusReverted).toBe(true);
    expect(saved.versionStatus).toBe('NHAP');
    const pub = { id: packs[0].id, v: '2.0.0' };
    const a = (await firstValueFrom(api.rules(pub)))[0];
    const e = await fail(
      firstValueFrom(api.saveRule(pub, a, { expression: a.expression, resultType: 'DECIMAL' })),
    );
    expect(e['code']).toBe('POLICY-409-VERSION-FROZEN');
  });

  it('bảng xếp loại: cảnh báo dòng bị che, biến mẫu câu lạ bị từ chối, xuất DMN', async () => {
    const api = setup();
    const ref = { id: (await firstValueFrom(api.packs())).items[0].id, v: '2.1.0' };
    const t = await firstValueFrom(api.decisionTable(ref, 'xep_loai'));
    expect(t.rules.length).toBe(6);
    const rules = [...t.rules, { ...t.rules[2] }];
    const saved = await firstValueFrom(
      api.saveDecisionTable(ref, t, {
        hitPolicy: 'FIRST',
        inputs: t.inputs,
        rules,
        explanationTemplates: t.explanationTemplates,
      }),
    );
    expect(saved.warnings?.[0]).toMatchObject({ row: 7, shadowedBy: 3 });
    const bad = await fail(
      firstValueFrom(
        api.saveDecisionTable(ref, saved, {
          hitPolicy: 'FIRST',
          inputs: t.inputs,
          rules: t.rules,
          explanationTemplates: { ha_muc: 'Thiếu {bien_la}' },
        }),
      ),
    );
    expect(bad.detail).toContain('bien_la');
    const xml = await (await firstValueFrom(api.exportDmn(ref, 'xep_loai'))).text();
    expect(xml).toContain('hitPolicy="FIRST"');
  });

  it('shadowedDecisionRows: dòng điều kiện rộng hơn ở trên che dòng dưới', () => {
    expect(
      shadowedDecisionRows([
        { inputs: { diem: '< 60' }, ratingLevel: 'KHTNV', clause: 'x', explanation: 'x' },
        {
          inputs: { diem: '< 60', loai_tru: 'true' },
          ratingLevel: 'KHTNV',
          clause: 'x',
          explanation: 'x',
        },
        { inputs: { diem: '>= 80' }, ratingLevel: 'HTTNV', clause: 'x', explanation: 'x' },
      ]),
    ).toEqual([{ row: 2, by: 1 }]);
  });

  it('xem trước ca vàng, chạy test đạt rồi gửi phê duyệt', async () => {
    const api = setup();
    const id = (await firstValueFrom(api.packs())).items[0].id;
    const ref = { id, v: '2.1.0' };
    const cases = await firstValueFrom(api.goldenCases(id));
    expect(cases.items.map((c) => c.caseCode)).toContain('TC-017');
    const ev = await firstValueFrom(api.evaluate(ref, 'TC-017'));
    expect(ev.outputs['diem']).toBe(91.2);
    expect(ev.ratingLevel).toBe('HTTNV');
    let v = await firstValueFrom(api.version(ref));
    const early = await fail(
      firstValueFrom(
        api.submit(ref, v.etag, { effectivePeriodId: 'k', scopes: [{ flow: 'DANG' }] }),
      ),
    );
    expect(early['code']).toBe('POLICY-409-NOT-TESTED');
    const job = await firstValueFrom(api.runTests(ref, v.etag));
    expect(job.id).toBeTruthy();
    expect((await firstValueFrom(api.testRuns(ref))).items[0].result).toBe('DANG_CHAY');
    expect((await firstValueFrom(api.testRuns(ref))).items[0].result).toBe('DAT');
    v = await firstValueFrom(api.version(ref));
    expect(v.value.status).toBe('DAT_KIEM_THU');
    const sent = await firstValueFrom(
      api.submit(ref, v.etag, { effectivePeriodId: 'k', scopes: [{ flow: 'DANG' }] }),
    );
    expect(sent.status).toBe('CHO_PHE_DUYET');
  });
});

describe('mock policies – SCR-1503, SCR-1504 (WEB-046)', () => {
  beforeEach(() => {
    resetMockData();
    mockSession.reset();
  });

  const packId = async (api: PolicyApi, code: string) =>
    (await firstValueFrom(api.packs())).items.find((p) => p.code === code)!.id;

  it('lịch sử chạy có lần lỗi kèm khác biệt cấu hình; chạy lại cần If-Match và không chồng lần chạy', async () => {
    const api = setup();
    const ref = { id: await packId(api, 'khoi-dang/quy-dinh-moi'), v: '2.1.0' };
    const runs = await firstValueFrom(api.testRuns(ref, 10));
    expect(runs.items.map((r) => [r.runNo, r.result])).toEqual([
      [13, 'KHONG_DAT'],
      [12, 'DAT'],
    ]);
    const bad = await firstValueFrom(api.testRun(ref, runs.items[0].id));
    const failed = bad.cases!.filter((c) => !c.passed);
    expect(failed.map((c) => c.caseCode)).toEqual(['TC-077']);
    expect(bad.configDiff?.changes[0].path).toBe('params.loai_ngay');
    const v = await firstValueFrom(api.version(ref));
    expect((await fail(firstValueFrom(api.runTests(ref, '"99"'))))['code']).toBe(
      'POLICY-409-VERSION-CONFLICT',
    );
    await firstValueFrom(api.runTests(ref, v.etag, { propertyDatasets: 10000 }));
    expect((await fail(firstValueFrom(api.runTests(ref, v.etag))))['code']).toBe(
      'POLICY-409-TEST-RUNNING',
    );
    await firstValueFrom(api.testRuns(ref));
    const [latest] = (await firstValueFrom(api.testRuns(ref))).items;
    expect(latest.runNo).toBe(14);
    expect(latest.result).toBe('DAT');
    const full = await firstValueFrom(api.testRun(ref, latest.id));
    expect(full.engineMatchedCount).toBe(full.total);
    expect(full.propertyResult?.violations).toBe(0);
  });

  it('thêm ca kiểm thử: chờ ký duyệt, không trùng mã, chưa tính vào bộ', async () => {
    const api = setup();
    const id = await packId(api, 'khoi-dang/quy-dinh-moi');
    const before = await firstValueFrom(api.testCases(id));
    const created = await firstValueFrom(
      api.createTestCase({
        policyPackId: id,
        caseCode: 'TC-149',
        description: 'Cán bộ biệt phái giữa kỳ',
        input: {},
        expected: { muc: 'HTNV' },
        source: 'BA',
      }),
    );
    expect(created.status).toBe('NHAP');
    const after = await firstValueFrom(api.testCases(id));
    expect(after.suite?.total).toBe(before.suite?.total);
    expect(after.suite?.pending).toBe(1);
    const dup = await fail(
      firstValueFrom(
        api.createTestCase({
          policyPackId: id,
          caseCode: 'TC-017',
          description: 'Trùng mã',
          input: {},
          expected: { muc: 'HTNV' },
          source: 'BA',
        }),
      ),
    );
    expect(dup.status).toBe(422);
  });

  it('ký phê duyệt: cần MFA, 202 rồi phiên bản phát hành; từ chối cần ý kiến', async () => {
    const api = setup();
    const ref = { id: await packId(api, 'vc-su-nghiep'), v: '1.1.0' };
    let v = await firstValueFrom(api.version(ref));
    expect(v.value.status).toBe('CHO_PHE_DUYET');
    expect((await fail(firstValueFrom(api.approve(ref, v.etag)))).status).toBe(401);
    expect((await fail(firstValueFrom(api.reject(ref, v.etag, { reason: 'ngắn' })))).status).toBe(
      422,
    );
    mockStepUp();
    const job = await firstValueFrom(api.approve(ref, v.etag, { comment: 'Đồng ý' }));
    expect(job.type).toBe('POLICY_PUBLISH');
    v = await firstValueFrom(api.version(ref));
    v = await firstValueFrom(api.version(ref));
    expect(v.value.status).toBe('DA_PHAT_HANH');
    expect(v.value.effectiveFrom).toBe('2027-01-01');
  });

  it('luồng duyệt: danh mục mẫu, khai báo tham số, mô phỏng và lưu có kiểm tra', async () => {
    const api = setup();
    const ref = { id: await packId(api, 'khoi-dang/quy-dinh-moi'), v: '2.1.0' };
    const list = await firstValueFrom(api.workflowTemplates());
    expect(list.items.length).toBe(5);
    const t = await firstValueFrom(api.workflowTemplate('duyet-ho-so-danh-gia', 3));
    expect((t.paramSchema['properties'] as Record<string, unknown>)['so_cap']).toMatchObject({
      minimum: 2,
      maximum: 4,
    });
    const [b] = await firstValueFrom(api.workflowBindings(ref));
    const sim = await firstValueFrom(
      api.simulate({ templateKey: b.templateKey, templateVersionNo: 3, params: b.params }),
    );
    expect(sim.lint.passed).toBe(true);
    expect(sim.steps.find((s) => s.stepKey === 'DE_XUAT')?.skippable).toBe(true);
    const params = structuredClone(b.params) as { cap: { buoc: string; ky_so?: boolean }[] };
    params.cap.find((c) => c.buoc === 'THAM_DINH')!.ky_so = false;
    const bad = await fail(
      firstValueFrom(
        api.saveWorkflowBinding(ref, 'DANH_GIA', b.rowVersion, {
          templateKey: b.templateKey,
          templateVersionNo: 3,
          scope: b.scope,
          params,
        }),
      ),
    );
    expect(bad['code']).toBe('POLICY-422-WF-PARAMS');
    expect(bad.detail).toContain('Thẩm định');
    const saved = await firstValueFrom(
      api.saveWorkflowBinding(ref, 'DANH_GIA', b.rowVersion, {
        templateKey: b.templateKey,
        templateVersionNo: 3,
        scope: b.scope,
        params: { ...b.params, so_cap: 3 },
      }),
    );
    expect(saved.rowVersion).toBe(b.rowVersion + 1);
  });

  it('stepsForLevels: giữ bước đầu, bước cuối', () => {
    expect(stepsForLevels(['a', 'b', 'c', 'd'], 3)).toEqual(['a', 'b', 'd']);
    expect(stepsForLevels(['a', 'b', 'c', 'd'], 2)).toEqual(['a', 'd']);
    expect(stepsForLevels(['a', 'b', 'c'], 1)).toEqual(['c']);
  });
});
