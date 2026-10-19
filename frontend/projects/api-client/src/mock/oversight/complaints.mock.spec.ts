import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { ComplaintApi } from '../../lib/complaint-api';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { ReopenApi } from '../../lib/reopen-api';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { MOCK_ME, mockSession, mockStepUp } from '../mock-session';

/** WEB-042: hành vi giả lập SCR-1201, SCR-1202 (QT-12) theo hợp đồng `oversight.yaml`. */
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
  return TestBed.inject(ComplaintApi);
}

async function errorOf(p: Promise<unknown>): Promise<HttpErrorResponse> {
  try {
    await p;
  } catch (e) {
    return e as HttpErrorResponse;
  }
  throw new Error('không có lỗi');
}

const LONG =
  'Báo cáo của nhiệm vụ then chốt 2 được Thường trực Đảng uỷ thông qua không phải sửa, nên mức B = 0,50 chưa phản ánh đúng chất lượng sản phẩm.';

describe('mock oversight – QT-12 khiếu nại (WEB-042)', () => {
  beforeEach(() => {
    resetMockData();
    mockSession.reset();
  });

  it('kết quả còn hạn: quý I quá hạn chỉ còn bảo lưu (BR-12-01), lưu nháp hình thức khác trả MSG-12-01', async () => {
    const api = setup();
    const el = await firstValueFrom(api.eligibleProfiles());
    expect(el.map((e) => e.allowedKinds.length)).toEqual([3, 1]);
    expect(el[1].workingDaysLeft).toBeLessThan(0);
    const e = await errorOf(
      firstValueFrom(
        api.create({
          profileId: el[1].profileId,
          kind: 'KHIEU_NAI',
          targetStage: 'QUYET_DINH',
          reserveOpinion: false,
        }),
      ),
    );
    expect(e.status).toBe(422);
    expect(e.error.msg).toBe('MSG-12-01');
  });

  it('soạn, lưu nháp rồi ký gửi: thiếu cấu phần MSG-12-03, cần MFA, sang MOI có hạn trả lời', async () => {
    const api = setup();
    const el = (await firstValueFrom(api.eligibleProfiles()))[0];
    let c = await firstValueFrom(
      api.create({
        profileId: el.profileId,
        kind: 'KHIEU_NAI',
        targetStage: 'THAM_DINH',
        content: LONG,
        reserveOpinion: false,
      }),
    );
    expect(c.status).toBeNull();
    expect(c.code).toMatch(/^KN-\d{4}-\d{4}$/);
    expect((await errorOf(firstValueFrom(api.submit(c)))).status).toBe(401);
    mockStepUp();
    const e = await errorOf(firstValueFrom(api.submit(c)));
    expect(e.status).toBe(422);
    expect(e.error.code).toBe('OVERSIGHT-422-NO-TARGET');
    c = await firstValueFrom(
      api.update(c, {
        profileId: el.profileId,
        kind: 'KHIEU_NAI',
        targetStage: 'THAM_DINH',
        targets: [el.targets[1]],
        content: LONG,
        requestText: 'Đề nghị nâng mức B lên 0,75.',
        reserveOpinion: true,
      }),
    );
    const job = await firstValueFrom(api.submit(c));
    expect(job.location).toMatch(/^\/api\/v1\/jobs\//);
    c = await firstValueFrom(api.get(c.id));
    expect(c.status).toBe('MOI');
    expect(c.responseDeadline).toBeTruthy();
    expect(
      (
        await errorOf(
          firstValueFrom(
            api.update(c, {
              profileId: el.profileId,
              kind: 'KHIEU_NAI',
              targetStage: 'THAM_DINH',
              reserveOpinion: false,
            }),
          ),
        )
      ).status,
    ).toBe(409);
    c = await firstValueFrom(api.withdraw(c, 'Đã trao đổi và thống nhất kết quả.'));
    expect(c.status).toBe('RUT');
  });

  it('tách vai trò: tiếp nhận hồ sơ mình đã thẩm định trả 403 MSG-12-05 (BR-12-04)', async () => {
    const api = setup();
    const c = await firstValueFrom(api.get('kn-2026-0042'));
    const e = await errorOf(firstValueFrom(api.accept(c)));
    expect(e.status).toBe(403);
    expect(e.error.msg).toBe('MSG-12-05');
    const ok = await firstValueFrom(api.accept(await firstValueFrom(api.get('kn-2026-0045'))));
    expect(ok.status).toBe('DA_TIEP_NHAN');
    const r = await firstValueFrom(
      api.requestExplanations(ok, {
        parties: [{ kind: 'NGUOI', id: 'p-an' }],
        content: 'Đề nghị xác nhận thời gian học tập trung.',
      }),
    );
    expect(r.status).toBe('DANG_GIAI_TRINH');
    const recs = await firstValueFrom(api.responses(r.id, 'YEU_CAU'));
    expect(recs).toHaveLength(1);
    expect(recs[0].dueDate).toBeTruthy();
  });

  it('bên liên quan giải trình: đủ giải trình thì sang CHO_KET_LUAN', async () => {
    const api = setup();
    const c = await firstValueFrom(api.get('kn-2026-0044'));
    const ask = (await firstValueFrom(api.responses(c.id, 'YEU_CAU'))).find(
      (r) => r.party?.id === MOCK_ME.id,
    )!;
    const rec = await firstValueFrom(
      api.submitExplanation(c, { requestId: ask.id, content: 'Đề cương ghi số liệu cả năm.' }),
    );
    expect(rec.recordKind).toBe('GIAI_TRINH');
    expect((await firstValueFrom(api.get(c.id))).status).toBe('CHO_KET_LUAN');
  });

  it('kết luận có điều chỉnh, tính thử, ký trả lời rồi tạo yêu cầu mở lại kỳ (BR-12-07)', async () => {
    const api = setup();
    let c = await firstValueFrom(api.get('kn-2026-0040'));
    const sim = await firstValueFrom(
      api.simulate(c.profileId, {
        adjustments: [{ component: 'C', oldValue: 0.8, newValue: 1, reason: 'Hạn đã dời.' }],
      }),
    );
    expect(sim.before.totalScore).toBe(84.6);
    expect(sim.after.totalScore).toBeCloseTo(85.8);
    const e = await errorOf(
      firstValueFrom(api.saveConclusion(c, { outcome: 'CHAP_NHAN_MOT_PHAN', responseText: 'x' })),
    );
    expect(e.status).toBe(422);
    c = await firstValueFrom(
      api.saveConclusion(c, {
        outcome: 'CHAP_NHAN_MOT_PHAN',
        adjustments: [
          {
            targetKey: 'NV-THUONG-XUYEN-4.C',
            oldValue: 0.8,
            newValue: 1,
            reason: 'Hạn đã dời theo 27-TB/ĐUK.',
          },
        ],
        responseText: 'Chấp nhận một phần.',
        responseDocRef: '11-CV/BTC',
      }),
    );
    expect(c.conclusion?.periodLocked).toBe(true);
    mockStepUp();
    await firstValueFrom(api.respond(c));
    c = await firstValueFrom(api.get(c.id));
    expect(c.status).toBe('DA_TRA_LOI');
    const reopen = TestBed.inject(ReopenApi);
    const r = await firstValueFrom(
      reopen.create({
        periodId: c.period.id,
        auditKind: 'PHAN_ANH',
        scope: 'HO_SO',
        scopeIds: [c.profileId],
        scopeDetail: {
          items: [{ profileId: c.profileId, table: 'product.acceptance', fields: ['c_factor'] }],
        },
        openWorkingDays: 3,
        reason:
          'Thi hành kết luận khiếu nại KN-2026-0040 chấp nhận một phần: điều chỉnh hệ số C của nhiệm vụ thường xuyên 4.',
        basis: { kind: 'KHIEU_NAI', id: c.id, code: c.code },
      }),
    );
    c = await firstValueFrom(api.get(c.id));
    expect(c.reopenRequestId).toBe(r.id);
    expect(c.status).toBe('DANG_THI_HANH');
  });

  it('người khiếu nại xác nhận bảo lưu sau khi có trả lời (BR-12-09)', async () => {
    const api = setup();
    const c = await firstValueFrom(
      api.reserve(await firstValueFrom(api.get('kn-2026-0038')), 'Tôi vẫn giữ ý kiến.'),
    );
    expect(c.reservedOpinion).toBe('Tôi vẫn giữ ý kiến.');
    expect((await firstValueFrom(api.responses(c.id, 'BAO_LUU'))).length).toBe(1);
  });
});
