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
    const mine = await firstValueFrom(api.myProfiles());
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
    expect(done.items).toEqual([]);
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
});
