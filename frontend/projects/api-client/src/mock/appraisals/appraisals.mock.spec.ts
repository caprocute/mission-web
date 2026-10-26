import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AppraisalApi } from '../../lib/appraisal-api';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { Job } from '../../lib/models/job';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { mockSession, mockStepUp, setMockEsignOutage } from '../mock-session';
import { criteriaLevel } from './appraisals.mock';

/** WEB-035: giả lập MOD-04 cho SCR-0901 (lưu nháp, quy tắc nộp, step-up, ký qua job, chuyển luồng). */
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
  return { api: TestBed.inject(AppraisalApi), http: TestBed.inject(HttpClient) };
}

async function errorOf(p: Promise<unknown>): Promise<HttpErrorResponse> {
  try {
    await p;
  } catch (e) {
    return e as HttpErrorResponse;
  }
  throw new Error('không có lỗi');
}

const P = '01928f3e-7b1a-7c3e-9a51-3f2d9c1b';
const DANG = P + '0a01';
const OTHER = P + '0a21';
const EVIDENCE = '0192a1b2-0000-7000-8000-000000000901';
const CONTENT = 'Ưu điểm: hoàn thành đúng hạn; hạn chế: chậm một báo cáo chuyên đề.';

describe('appraisals mock (WEB-035)', () => {
  it('BR-09-05: quy đổi tỷ lệ đáp ứng ra mức tiêu chí chung', () => {
    expect(criteriaLevel(1)).toBe('THUC_HIEN_TOT');
    expect(criteriaLevel(8 / 9)).toBe('CO_BAN_DAM_BAO');
    expect(criteriaLevel(0.9)).toBe('DAM_BAO');
    expect(criteriaLevel(5 / 9)).toBe('KHONG_DAM_BAO');
  });

  it('hồ sơ của tôi có hai luồng; chuyển luồng lấy ảnh chụp của hồ sơ luồng kia', async () => {
    const { api } = setup();
    const mine = await firstValueFrom(api.myProfiles({ period: P + '0001' }));
    expect(mine.items.map((r) => [r.flow, r.totalScore, r.autoRatingCode])).toEqual([
      ['DANG', 91.2, 'HTTNV'],
      ['NHA_NUOC', 91.8, 'HTXSNV'],
    ]);
    const nn = await firstValueFrom(api.scores(DANG, 'NHA_NUOC'));
    expect(nn.snapshot).toMatchObject({
      flow: 'NHA_NUOC',
      kpiScore: 63.8,
      generalCriteriaScore: 28,
    });
    const trace = await firstValueFrom(api.scoreTrace(DANG, { taskId: P + '0c02' }));
    expect(trace.steps[0].explanation).toBe(
      'KPI = (A + B + C) / 3 = (1 + 0,5 + 0,85) / 3 = 0,783.',
    );
  });

  it('lưu nháp cập nhật tiêu chí chung và ETag; If-Match cũ trả 409; hồ sơ người khác 403', async () => {
    const { api } = setup();
    const p = await firstValueFrom(api.profile(DANG));
    const criteria = [{ criteriaCode: 'TCC-03', met: false }];
    const saved = await firstValueFrom(api.saveSelfAssessment(p, { criteria, content: 'Nháp' }));
    expect(saved.rowVersion).toBe(p.rowVersion + 1);
    expect(saved.generalCriteria![0]).toMatchObject({ level: 'CO_BAN_DAM_BAO' });
    expect(saved.selfAssessment?.content).toBe('Nháp');
    const stale = await errorOf(firstValueFrom(api.saveSelfAssessment(p, { content: 'x' })));
    expect([stale.status, stale.error.code]).toEqual([409, 'APPRAISAL-409-VERSION-CONFLICT']);
    const other = await firstValueFrom(api.profile(OTHER));
    const denied = await errorOf(firstValueFrom(api.saveSelfAssessment(other, { content: 'x' })));
    expect(denied.status).toBe(403);
  });

  it('nộp: MSG-09-02 khi khác mức không giải trình, MSG-09-03 khi HTXSNV thiếu minh chứng, 401 khi chưa MFA', async () => {
    const { api } = setup();
    let p = await firstValueFrom(api.profile(DANG));
    p = await firstValueFrom(
      api.saveSelfAssessment(p, { selfRatingCode: 'HTNV', content: CONTENT, reason: 'ngắn' }),
    );
    let e = await errorOf(firstValueFrom(api.submit(p, { signProvider: 'MYSIGN' })));
    expect([e.status, e.error.code]).toEqual([422, 'APPRAISAL-422-REASON-REQUIRED']);
    p = await firstValueFrom(
      api.saveSelfAssessment(p, { selfRatingCode: 'HTXSNV', breakthrough: 'Giải pháp' }),
    );
    e = await errorOf(firstValueFrom(api.submit(p, { signProvider: 'MYSIGN' })));
    expect(e.error.code).toBe('APPRAISAL-422-BREAKTHROUGH-REQUIRED');
    p = await firstValueFrom(api.saveSelfAssessment(p, { selfRatingCode: 'HTTNV' }));
    e = await errorOf(firstValueFrom(api.submit(p, { signProvider: 'MYSIGN' })));
    expect(e.status).toBe(401);
    expect(e.headers.get('WWW-Authenticate')).toContain('insufficient_user_authentication');
  });

  it('ký số: 202 + job, lần hỏi thứ hai hồ sơ sang CHO_THAM_DINH với mức TU_DE_XUAT', async () => {
    const { api, http } = setup();
    let p = await firstValueFrom(api.profile(DANG));
    p = await firstValueFrom(
      api.saveSelfAssessment(p, {
        selfRatingCode: 'HTXSNV',
        breakthrough: 'Đưa phần mềm thẩm định hồ sơ quy hoạch vào sử dụng.',
        breakthroughEvidenceIds: [EVIDENCE],
        reason: 'Giải pháp số hoá được đơn vị ghi nhận bằng quyết định.',
        content: CONTENT,
      }),
    );
    mockStepUp();
    const { location, job } = await firstValueFrom(
      api.submit(p, { signProvider: 'MYSIGN', formCode: 'MAU_1B' }),
    );
    expect(job.status).toBe('CHO');
    expect((await firstValueFrom(http.get<Job>(location))).status).toBe('DANG_CHAY');
    expect((await firstValueFrom(http.get<Job>(location))).status).toBe('HOAN_TAT');
    const after = await firstValueFrom(api.profile(DANG));
    expect(after.status).toBe('CHO_THAM_DINH');
    expect(after.ratingTrail.at(-1)).toMatchObject({
      levelKind: 'TU_DE_XUAT',
      ratingCode: 'HTXSNV',
      differsFromPrev: true,
    });
    const again = await errorOf(firstValueFrom(api.saveSelfAssessment(after, { content: 'x' })));
    expect(again.error.code).toBe('APPRAISAL-409-INVALID-STATUS');
  });

  it('ký lỗi: job THAT_BAI, hồ sơ giữ TU_DANH_GIA (MSG-09-05); xem trước Mẫu 1B trả PDF', async () => {
    const { api, http } = setup();
    let p = await firstValueFrom(api.profile(DANG));
    p = await firstValueFrom(
      api.saveSelfAssessment(p, { selfRatingCode: 'HTTNV', content: CONTENT }),
    );
    mockStepUp();
    setMockEsignOutage(true);
    const { location } = await firstValueFrom(api.submit(p, { signProvider: 'MYSIGN' }));
    await firstValueFrom(http.get<Job>(location));
    const failed = await firstValueFrom(http.get<Job>(location));
    expect(failed.status).toBe('THAT_BAI');
    expect(failed.problem?.title).toBe('Dịch vụ ký số từ xa không phản hồi');
    expect((await firstValueFrom(api.profile(DANG))).status).toBe('TU_DANH_GIA');
    const pdf = await firstValueFrom(
      api.previewForm({ formCode: 'MAU_1B', sourceModule: 'appraisal', sourceRefId: DANG }),
    );
    expect(pdf.type).toBe('application/pdf');
    expect((await pdf.text()).startsWith('%PDF-1.4')).toBe(true);
  });
});

describe('appraisals mock, thẩm định (WEB-036)', () => {
  const HOANG = P + '0a31';
  const MAI = P + '0a35';
  const SON = P + '0a36';
  const COMMENT = 'Hoàn thành tốt các nhiệm vụ then chốt; tỷ lệ việc vượt chưa đạt ngưỡng 30%.';
  const REASON = 'Không đạt điều kiện ≥ 30% nhiệm vụ vượt mục tiêu (QĐ Điều 11).';

  it('hàng đợi CHO_THAM_DINH sắp theo hạn, có mức tự đề xuất, gợi ý bất thường và phân trang', async () => {
    const { api } = setup();
    const page = await firstValueFrom(api.profiles({ limit: 5 }));
    expect(page.items).toHaveLength(5);
    expect(page.nextCursor).toBe('5');
    const all = await firstValueFrom(api.profiles({ limit: 200 }));
    expect(all.items.map((r) => r.person.name)).toContain('Lê Thu Hà');
    const h = all.items.find((r) => r.id === HOANG)!;
    expect(h).toMatchObject({ totalScore: 91.2, levels: { selfProposed: 'HTXSNV' } });
    expect(h.anomalyHints?.[0].label).toBe('Tự chấm cao 3 quý');
    const done = await firstValueFrom(api.profiles({ status: ['DA_THAM_DINH'] }));
    // chỉ có các hồ sơ của phiếu xếp loại SCR-0903 (WEB-037)
    expect(done.items.map((r) => r.id.slice(-4))).toEqual(['0a41', '0a42', '0a43']);
  });

  it('ký thẩm định: 422 MSG-09-06 khi khác mức không lý do, 401 khi chưa MFA, job xong hồ sơ DA_THAM_DINH', async () => {
    const { api, http } = setup();
    const p = await firstValueFrom(api.profile(HOANG));
    const e = await errorOf(
      firstValueFrom(api.appraise(p, { ratingCode: 'HTTNV', comment: COMMENT })),
    );
    expect([e.status, e.error.code]).toEqual([422, 'APPRAISAL-422-REASON-REQUIRED']);
    const body = { ratingCode: 'HTTNV' as const, comment: COMMENT, reason: REASON };
    expect((await errorOf(firstValueFrom(api.appraise(p, body)))).status).toBe(401);
    mockStepUp();
    const { location } = await firstValueFrom(api.appraise(p, body));
    await firstValueFrom(http.get<Job>(location));
    const job = await firstValueFrom(http.get<Job>(location));
    expect(job.status).toBe('HOAN_TAT');
    const after = await firstValueFrom(api.profile(HOANG));
    expect(after.status).toBe('DA_THAM_DINH');
    expect(after.ratingTrail.at(-1)).toMatchObject({ levelKind: 'THAM_DINH', ratingCode: 'HTTNV' });
    const queue = await firstValueFrom(api.profiles());
    expect(queue.items.some((r) => r.id === HOANG)).toBe(false);
  });

  it('tách vai trò 403 MSG-09-08; trả lại MSG-09-09 rồi 409 khi vượt số lần tối đa', async () => {
    const { api } = setup();
    mockStepUp();
    const mai = await firstValueFrom(api.profile(MAI));
    const e = await errorOf(
      firstValueFrom(api.appraise(mai, { ratingCode: 'HTNV', comment: COMMENT })),
    );
    expect([e.status, e.error.code]).toEqual([403, 'APPRAISAL-403-SEPARATION-OF-DUTIES']);
    const reason = 'Đề nghị bổ sung biên bản nghiệm thu nhiệm vụ then chốt số 2.';
    const back = await firstValueFrom(api.returnProfile(mai, reason));
    expect([back.status, back.returnCount, back.returnLimit]).toEqual(['TU_DANH_GIA', 1, 2]);
    const son = await firstValueFrom(api.profile(SON));
    const limit = await errorOf(firstValueFrom(api.returnProfile(son, reason)));
    expect([limit.status, limit.error.code]).toEqual([409, 'APPRAISAL-409-RETURN-LIMIT']);
    const short = await errorOf(firstValueFrom(api.returnProfile(son, 'ngắn')));
    expect(short.status).toBe(422);
  });

  it('xuất danh sách: 202 + job HOAN_TAT, tải tệp kết quả', async () => {
    const { api, http } = setup();
    const { location } = await firstValueFrom(
      api.exportProfiles({ periodId: P + '0001', status: ['CHO_THAM_DINH'], format: 'XLSX' }),
    );
    const job = await firstValueFrom(http.get<Job>(location));
    expect(job.status).toBe('HOAN_TAT');
    const file = await firstValueFrom(api.download(job.resultLocation!));
    expect(await file.text()).toContain('Trần Minh Hoàng');
  });

  it('WEB-037 ghi mức đề xuất: lý do khi khác mức thẩm định, hồ sơ sang CHO_QUYET_DINH, 409 khi ghi lại', async () => {
    const { api } = setup();
    const p = await firstValueFrom(api.profile(P + '0a41'));
    expect(p.status).toBe('DA_THAM_DINH');
    const noReason = await errorOf(firstValueFrom(api.propose(p, { ratingCode: 'HTXSNV' })));
    expect([noReason.status, noReason.error.code]).toEqual([422, 'APPRAISAL-422-REASON-REQUIRED']);
    const after = await firstValueFrom(api.propose(p, { ratingCode: 'HTTNV' }));
    expect(after.status).toBe('CHO_QUYET_DINH');
    expect(after.ratingTrail.at(-1)).toMatchObject({
      levelKind: 'DE_XUAT',
      ratingCode: 'HTTNV',
      actorRole: 'manager',
      differsFromPrev: false,
    });
    const again = await errorOf(firstValueFrom(api.propose(after, { ratingCode: 'HTTNV' })));
    expect([again.status, again.error.code]).toEqual([409, 'APPRAISAL-409-INVALID-STATUS']);
    const stale = await errorOf(firstValueFrom(api.propose(p, { ratingCode: 'HTTNV' })));
    expect(stale.status).toBe(409);
  });

  it('WEB-037 tách vai trò khi đề xuất (403); hồ sơ đã quyết định của tôi có Mẫu 1B đã ký', async () => {
    const { api, http } = setup();
    const nam = await firstValueFrom(api.profile(P + '0a43'));
    const e = await errorOf(firstValueFrom(api.propose(nam, { ratingCode: 'HTTNV' })));
    expect([e.status, e.error.code]).toEqual([403, 'APPRAISAL-403-SEPARATION-OF-DUTIES']);
    const mine = await firstValueFrom(api.myProfiles());
    const q3 = mine.items.find((r) => r.status === 'DA_KY_SO')!;
    expect(q3.levels.decided).toBe('HTTNV');
    const full = (await firstValueFrom(api.profile(q3.id))) as { signedFormId?: string };
    const pdf = await firstValueFrom(
      http.get(`/api/v1/dossiers/forms/${full.signedFormId}/file`, { responseType: 'blob' }),
    );
    expect((await pdf.text()).startsWith('%PDF-1.4')).toBe(true);
    const view = await firstValueFrom(api.scores(q3.id));
    expect(view.explanation?.conditions.filter((c) => !c.met).map((c) => c.actual)).toEqual([
      '22%',
    ]);
  });

  it('WEB-038 ký lô: kiểm số quyết định, lý do, người đứng đầu, ngưỡng xuất sắc, MFA; job ký dần tới DA_KY', async () => {
    const { api, http } = setup();
    const page = await firstValueFrom(api.profiles({ status: ['CHO_QUYET_DINH'], limit: 200 }));
    const rows = page.items;
    expect(rows.length).toBe(48);
    const item = (r: (typeof rows)[number], ratingCode = r.levels.proposed!) => ({
      profileId: r.id,
      ratingCode,
      rowVersion: r.rowVersion,
    });
    const base = {
      periodId: P + '0001',
      flow: 'DANG' as const,
      decisionNo: '215-QĐ/ĐUK',
      decisionDate: '2027-01-12',
      calibrationAcknowledged: false,
      items: rows.map((r) => item(r)),
    };
    const code = async (body: typeof base & { calibrationAcknowledged?: boolean }) => {
      const e = await errorOf(firstValueFrom(api.batchDecide(body)));
      return [e.status, e.error?.code];
    };
    expect(await code({ ...base, decisionNo: '198-QĐ/ĐUK' })).toEqual([
      409,
      'APPRAISAL-409-DECISION-NO-DUPLICATE',
    ]);
    const changed = base.items.map((x, i) =>
      i === 1 ? { ...x, ratingCode: 'HTXSNV' as const } : x,
    );
    expect(await code({ ...base, items: changed })).toEqual([422, 'APPRAISAL-422-REASON-REQUIRED']);
    const head = rows.find((r) => r.isHead)!;
    expect(await code({ ...base, items: [item(head)] })).toEqual([
      422,
      'APPRAISAL-422-HEAD-TOO-EARLY',
    ]);
    const many = changed.map((x, i) =>
      i === 1 ? { ...x, reason: 'Có giải pháp đột phá được Ban Thường vụ ghi nhận.' } : x,
    );
    expect(await code({ ...base, items: many })).toEqual([
      422,
      'APPRAISAL-422-CALIBRATION-REQUIRED',
    ]);
    const stale = base.items.map((x, i) => (i === 0 ? { ...x, rowVersion: 1 } : x));
    expect(await code({ ...base, items: stale })).toEqual([409, 'APPRAISAL-409-VERSION-CONFLICT']);
    expect((await code(base))[0]).toBe(401);

    mockStepUp();
    const { location, job } = await firstValueFrom(api.batchDecide(base));
    expect(job?.resultLocation).toMatch(/\/batch-decisions\//);
    let j = job!;
    for (let i = 0; i < 5 && j.status !== 'HOAN_TAT'; i++)
      j = await firstValueFrom(http.get<Job>(location));
    expect(j.status).toBe('HOAN_TAT');
    const b = await firstValueFrom(api.batchDecision(job!.resultLocation!));
    expect([b.status, b.signedCount, b.profileCount]).toEqual(['DA_KY', 48, 48]);
    const again = await errorOf(
      firstValueFrom(api.batchDecide({ ...base, decisionNo: '216-QĐ/ĐUK' })),
    );
    expect(again.status).toBe(409);
    const cancel = await errorOf(firstValueFrom(api.cancelBatch(b)));
    expect([cancel.status, cancel.error.code]).toEqual([409, 'APPRAISAL-409-BATCH-COMPLETED']);
  });

  it('WEB-038 huỷ lô giữa chừng: hồ sơ đã ký giữ DA_KY_SO, còn lại vẫn chờ; lỗi ký một phần', async () => {
    const { api, http } = setup();
    mockStepUp();
    const rows = (await firstValueFrom(api.profiles({ status: ['CHO_QUYET_DINH'], limit: 200 })))
      .items;
    const body = {
      periodId: P + '0001',
      flow: 'DANG' as const,
      decisionNo: '220-QĐ/ĐUK',
      decisionDate: '2027-01-12',
      calibrationAcknowledged: false,
      items: rows.map((r) => ({
        profileId: r.id,
        ratingCode: r.levels.proposed!,
        rowVersion: r.rowVersion,
      })),
    };
    const { location, job } = await firstValueFrom(api.batchDecide(body));
    await firstValueFrom(http.get<Job>(location));
    const mid = await firstValueFrom(api.batchDecision(job!.resultLocation!));
    expect([mid.status, mid.signedCount]).toEqual(['CHO_KY', 12]);
    const after = await firstValueFrom(api.cancelBatch(mid));
    expect(after.status).toBe('LOI_MOT_PHAN');
    expect((await firstValueFrom(http.get<Job>(location))).status).toBe('DA_HUY');
    const left = await firstValueFrom(api.profiles({ status: ['CHO_QUYET_DINH'], limit: 200 }));
    expect(left.items.length).toBe(36);

    setMockEsignOutage(true);
    const rest = {
      ...body,
      decisionNo: '221-QĐ/ĐUK',
      items: body.items.filter((x) => left.items.some((r) => r.id === x.profileId)),
    };
    const second = await firstValueFrom(api.batchDecide(rest));
    let j = second.job!;
    for (let i = 0; i < 5 && j.status !== 'HOAN_TAT'; i++)
      j = await firstValueFrom(http.get<Job>(second.location));
    const b = await firstValueFrom(api.batchDecision(second.job!.resultLocation!));
    expect([b.status, b.signedCount, b.failedCount]).toEqual(['LOI_MOT_PHAN', 34, 2]);
  });
});

describe('appraisals mock: xếp loại năm và phân nhóm triển vọng (WEB-039)', () => {
  const YEAR = 'p-2026';

  it('bảng năm có bốn thẻ số liệu, lọc liên thông và đề xuất bố trí lại một lần', async () => {
    const { api } = setup();
    const page = await firstValueFrom(api.annual({ period: YEAR }));
    expect(page.items.length).toBe(48);
    expect(page.counts).toEqual({
      profilesCreated: 48,
      profilesTotal: 48,
      blockedExcellent: 3,
      reassignProposed: 1,
      awaitingProspectGrouping: 48,
    });
    const flagged = await firstValueFrom(api.annual({ period: YEAR, warningsOnly: true }));
    expect(flagged.items.map((r) => r.person.name)).toEqual([
      'Phạm Đức Long',
      'Bùi Văn Khánh',
      'Đặng Hữu Phúc',
    ]);
    const hoang = page.items.find((r) => r.person.name === 'Trần Minh Hoàng')!;
    const ex = await firstValueFrom(api.annualExplanation(hoang.id));
    expect(ex.formula).toBe('(91,8 + 88,6 + 90,2 + 91,0) / 4 = 90,4');
    expect(ex.conditions.filter((c) => !c.met).map((c) => c.actual)).toEqual(['24%']);

    const khanh = flagged.items.find((r) => r.reassignFlag)!;
    expect((await errorOf(firstValueFrom(api.proposeReassignment(hoang)))).error.code).toBe(
      'APPRAISAL-409-NO-REASSIGN-FLAG',
    );
    const stale = await errorOf(
      firstValueFrom(api.proposeReassignment({ ...khanh, rowVersion: 1 })),
    );
    expect(stale.error.code).toBe('APPRAISAL-409-VERSION-CONFLICT');
    const res = await firstValueFrom(api.proposeReassignment(khanh, 'Đề nghị Ban xem xét.'));
    expect(res.relocationProposed).toBe(true);
    const again = await errorOf(
      firstValueFrom(api.proposeReassignment({ ...khanh, rowVersion: 3 })),
    );
    expect(again.error.code).toBe('APPRAISAL-409-REASSIGN-EXISTS');
  });

  it('bảng phân nhóm: khoá ở kỳ quý, BR-10-09, lý do khi khác gợi ý, lưu rồi trình', async () => {
    const { api } = setup();
    const quarter = await errorOf(firstValueFrom(api.prospectBoard('p-2026q4', P + '0101')));
    expect([quarter.status, quarter.error.code]).toEqual([409, 'APPRAISAL-409-PROSPECT-QUARTER']);

    const board = await firstValueFrom(api.prospectBoard(YEAR, P + '0101'));
    expect(board.status).toBe('NHAP');
    const son = board.entries.find((e) => e.person.name === 'Vũ Ngọc Sơn')!;
    expect([son.groupCode, son.suggestedGroup, son.rankInGroup]).toEqual(['NHOM_1', 'NHOM_2', 2]);
    const input = (
      patch: (x: { personId: string; groupCode: string; reason?: string }) => void,
    ) => ({
      entries: board.entries.map((e) => {
        const x = {
          personId: e.person.id,
          groupCode: e.groupCode,
          rankInGroup: e.rankInGroup!,
          reason: e.reason,
          trainingPlanRef: e.trainingPlanRef,
        };
        patch(x);
        return x as never;
      }),
    });
    const khanh = board.entries.find((e) => e.reassignFlag)!;
    const bad = await errorOf(
      firstValueFrom(
        api.saveProspectBoard(
          board,
          input((x) => {
            if (x.personId === khanh.person.id) {
              x.groupCode = 'NHOM_2';
              x.reason = 'Đồng chí có nhiều đóng góp trong công tác chuyên môn của Ban.';
            }
          }),
        ),
      ),
    );
    expect(bad.error.code).toBe('APPRAISAL-422-PROSPECT-NOT-ELIGIBLE');
    // Lưu nháp không bắt buộc lý do (WEB-063); trình khi còn thiếu lý do thì 422 MSG-10-07
    const draft = await firstValueFrom(
      api.saveProspectBoard(
        board,
        input((x) => {
          if (x.personId === son.person.id) x.reason = '';
        }),
      ),
    );
    const noReason = await errorOf(firstValueFrom(api.submitProspectBoard(draft)));
    expect(noReason.error.errors[0].code).toBe('MSG-10-07');

    const saved = await firstValueFrom(
      api.saveProspectBoard(
        draft,
        input(() => undefined),
      ),
    );
    expect(saved.rowVersion).toBe(board.rowVersion + 2);
    expect((await errorOf(firstValueFrom(api.submitProspectBoard(board)))).status).toBe(409);
    const submitted = await firstValueFrom(api.submitProspectBoard(saved));
    expect(submitted.status).toBe('DA_TRINH');
    const locked = await errorOf(
      firstValueFrom(
        api.saveProspectBoard(
          submitted,
          input(() => undefined),
        ),
      ),
    );
    expect(locked.error.code).toBe('APPRAISAL-409-INVALID-STATUS');
  });
});

describe('appraisals mock: kết quả đánh giá theo đơn vị (AP-09, SCR-1603)', () => {
  it('liệt kê cán bộ của đơn vị với mã ảnh chụp điểm của chuỗi truy vết; thiếu kỳ trả 422', async () => {
    const { api } = setup();
    const q4 = await firstValueFrom(api.unitResults({ unitId: 'u-btc', period: 'p-2026q4' }));
    expect(q4.items.map((r) => [r.person.name, r.scoreSnapshotId, r.ratingCode])).toEqual([
      ['Trần Minh Hoàng', 'ss-2026q4-tran-minh-hoang', null],
      ['Nguyễn Thị Mai', 'ss-2026q4-nguyen-thi-mai', null],
      ['Phạm Văn Long', 'ss-2026q4-pham-van-long', null],
    ]);
    expect(q4.items[0]).toMatchObject({
      status: 'CHO_QUYET_DINH',
      totalScore: 91.2,
      autoRatingCode: 'HTTNV',
    });
    const q3 = await firstValueFrom(
      api.unitResults({ unitId: 'u-btc', period: 'p-2026q3', limit: 2 }),
    );
    expect(q3.items).toHaveLength(2);
    expect(q3.items[0]).toMatchObject({ status: 'DA_KHOA', ratingCode: 'HTTNV' });
    expect(q3.nextCursor).toBe('2');
    const rest = await firstValueFrom(
      api.unitResults({ unitId: 'u-btc', period: 'p-2026q3', cursor: q3.nextCursor }),
    );
    expect(rest.items.map((r) => r.person.name)).toEqual(['Phạm Văn Long']);
    expect(rest.nextCursor).toBeNull();
    const none = await firstValueFrom(api.unitResults({ unitId: 'u-la', period: 'p-2026q4' }));
    expect(none.items).toEqual([]);
    const err = await errorOf(firstValueFrom(api.unitResults({ unitId: 'u-btc', period: '' })));
    expect(err.status).toBe(422);
  });
});
