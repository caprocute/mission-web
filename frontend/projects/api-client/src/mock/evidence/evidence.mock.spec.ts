import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { EvidenceApi } from '../../lib/evidence-api';
import { FileApi, FileRejectedError, UploadProgress } from '../../lib/file-api';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { failMockIntegrityOnce, failMockUploadPartOnce, mockStepUp } from '../mock-session';

/** WEB-032: hành vi giả lập SCR-0701 (tải lên nhiều phần, quét, tra trùng, lưu nháp, nộp BR-07-01…04). */
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
  return { ev: TestBed.inject(EvidenceApi), files: TestBed.inject(FileApi) };
}

async function errorOf(p: Promise<unknown>): Promise<HttpErrorResponse> {
  try {
    await p;
  } catch (e) {
    return e as HttpErrorResponse;
  }
  throw new Error('không có lỗi');
}

const TASK = '0192a0f0-0001-7000-8000-000000000142';
const EV = '0192a1b2-0000-7000-8000-000000000142';
const DUP_CONTENT = 'So lieu tong hop cac chi bo quy III/2026\n';
const XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const file = (name: string, content: string | Uint8Array, type = 'application/pdf') =>
  new File([content as BlobPart], name, { type });

async function scanUntilDone(files: FileApi, objectId: string) {
  for (let i = 0; i < 5; i++) {
    const r = await firstValueFrom(files.scan(objectId));
    if (r.scanStatus !== 'CHO_QUET') return r.scanStatus;
  }
  return 'CHO_QUET';
}

describe('mock evidence – SCR-0701 (WEB-032)', () => {
  beforeEach(() => resetMockData());

  it('minh chứng của nhiệm vụ 0142: v1 yêu cầu bổ sung, v2 nháp kèm văn bản 47-BC và phụ lục', async () => {
    const { ev } = setup();
    const page = await firstValueFrom(ev.list({ task: TASK }));
    expect(page.items.map((e) => [e.id, e.currentVersionNo, e.currentStatus])).toEqual([
      [EV, 2, 'NHAP'],
    ]);
    const e = await firstValueFrom(ev.get(EV));
    expect(e.task.code).toBe('NV-2026-Q4-0142');
    expect(e.versions?.map((v) => v.status)).toEqual(['YEU_CAU_BO_SUNG', 'NHAP']);
    const v2 = await firstValueFrom(ev.version(EV, 2));
    expect(v2).toMatchObject({ source: 'VAN_BAN_HE_THONG', docNumber: '47-BC/BTCĐUK' });
    expect(v2.fileName).toBe('phu-luc-so-lieu.xlsx');
    const docs = await firstValueFrom(ev.externalDocs('47'));
    expect(docs.map((d) => d.docNumber)).toEqual(['47-BC/BTCĐUK']);
    expect(await firstValueFrom(ev.deliverablesOfTask(TASK))).toHaveLength(1);
  });

  it('tải lên: băm, nối tiếp sau khi mất kết nối, quét xong thì sạch', async () => {
    const { files } = setup();
    failMockUploadPartOnce(1);
    const steps: UploadProgress[] = [];
    const o = await files.upload(
      file('bao-cao.pdf', 'noi dung bao cao'),
      { ownerModule: 'product', ownerRefId: EV },
      (p) => steps.push(p),
      0,
    );
    expect(steps.some((p) => p.retrying)).toBe(true);
    expect(steps.at(-1)?.phase).toBe('HOAN_TAT');
    expect(o.sha256).toMatch(/^[0-9a-f]{64}$/);
    expect(o).toMatchObject({ scanStatus: 'CHO_QUET', dedup: { duplicate: false } });
    expect(await scanUntilDone(files, o.objectId)).toBe('SACH');
  });

  it('chặn kiểu và dung lượng trước khi gửi; tệp nhiễm và băm sai do máy chủ báo', async () => {
    const { files } = setup();
    await expect(
      files.upload(file('a.exe', 'x', 'application/octet-stream'), { ownerModule: 'product' }),
    ).rejects.toBeInstanceOf(FileRejectedError);
    const bad = await errorOf(
      files.upload(file('sai-bam.pdf', 'x'), { ownerModule: 'product' }, undefined, 0),
    );
    expect(bad.status).toBe(422);
    expect(bad.error.code).toBe('FILE-422-HASH-MISMATCH');
    const o = await files.upload(file('eicar.pdf', 'x'), { ownerModule: 'product' }, undefined, 0);
    expect(await scanUntilDone(files, o.objectId)).toBe('NHIEM');
  });

  it('nộp: bắt cam kết quét sạch và giải trình khi trùng (BR-07-03, 04), rồi nhiệm vụ chờ nghiệm thu', async () => {
    const { ev, files } = setup();
    const o = await files.upload(
      file('so-lieu.xlsx', DUP_CONTENT, XLSX),
      { ownerModule: 'product', ownerRefId: EV },
      undefined,
      0,
    );
    expect(o.dedup?.matches?.[0]).toMatchObject({ taskCode: 'NV-2026-Q3-0088' });
    let v = await firstValueFrom(ev.version(EV, 2));
    v = await firstValueFrom(
      ev.saveVersion(EV, v, {
        source: 'VAN_BAN_HE_THONG',
        sourceDocRef: v.sourceDocRef!,
        objectId: o.objectId,
      }),
    );
    expect(v).toMatchObject({ isDuplicateSuspect: true, scanStatus: 'CHO_QUET' });

    const notScanned = await errorOf(firstValueFrom(ev.submit(EV, v)));
    expect(notScanned.error.code).toBe('FILE-422-NOT-SCANNED');
    await scanUntilDone(files, o.objectId);
    const noReason = await errorOf(firstValueFrom(ev.submit(EV, v)));
    expect(noReason.error.errors[0].field).toBe('reuseJustification');

    v = await firstValueFrom(
      ev.saveVersion(EV, v, {
        source: 'VAN_BAN_HE_THONG',
        sourceDocRef: v.sourceDocRef!,
        objectId: o.objectId,
        reuseJustification: 'Số liệu tổng hợp dùng chung với báo cáo quý III đã nộp.',
      }),
    );
    const res = await firstValueFrom(ev.submit(EV, v));
    expect(res.version.status).toBe('DA_NOP');
    expect(res.reviewer?.name).toBe('Lê Minh Châu');
    expect(res.taskStatusChanged).toBe('CHO_NGHIEM_THU');

    // BR-07-01: đã nộp thì không sửa được
    const frozen = await errorOf(
      firstValueFrom(ev.saveVersion(EV, res.version, { source: 'TAI_LEN' })),
    );
    expect(frozen.status).toBe(409);
  });

  it('If-Match cũ bị 409; chỉ tạo phiên bản mới khi phiên bản trước đã kết thúc', async () => {
    const { ev } = setup();
    const v = await firstValueFrom(ev.version(EV, 2));
    await firstValueFrom(
      ev.saveVersion(EV, v, { source: 'VAN_BAN_HE_THONG', sourceDocRef: v.sourceDocRef! }),
    );
    const stale = await errorOf(firstValueFrom(ev.saveVersion(EV, v, { source: 'TAI_LEN' })));
    expect(stale.status).toBe(409);
    const open = await errorOf(firstValueFrom(ev.createVersion(EV, { source: 'TAI_LEN' })));
    expect(open.error.code).toBe('PRODUCT-409-OPEN-VERSION-EXISTS');

    const [d] = await firstValueFrom(ev.deliverablesOfTask('0192a0f0-0001-7000-8000-000000000157'));
    const e = await firstValueFrom(
      ev.create({ deliverableId: d.id, title: 'Phiếu thẩm định', sourceType: 'TAI_LEN' }),
    );
    const v1 = await firstValueFrom(ev.createVersion(e.id, { source: 'TAI_LEN' }));
    expect(v1).toMatchObject({ versionNo: 1, status: 'NHAP' });
  });
});

describe('mock evidence – SCR-0703 (WEB-033)', () => {
  beforeEach(() => resetMockData());
  const R = '0192a1b2-0000-7000-8000-000000000138';
  const criteria = ['TC-DV-03-1', 'TC-DV-03-2', 'TC-DV-03-3'].map((c) => ({
    criterionCode: c,
    passed: true,
  }));

  it('hàng chờ của tôi; mở nghiệm thu chuyển DANG_NGHIEM_THU và kiểm toàn vẹn; người nộp không tự nghiệm thu', async () => {
    const { ev } = setup();
    const q = await firstValueFrom(
      ev.list({ reviewer: 'me', status: ['DA_NOP', 'DANG_NGHIEM_THU'] }),
    );
    expect(q.items.map((e) => [e.task.code, e.currentStatus])).toEqual([
      ['NV-2026-Q4-0138', 'DA_NOP'],
    ]);
    const v = await firstValueFrom(ev.version(R, 2));
    expect(v).toMatchObject({ isDuplicateSuspect: true, hasPriorRevisionRequest: true });
    const opened = await firstValueFrom(ev.openReview(R, v));
    expect(opened).toMatchObject({ status: 'DANG_NGHIEM_THU', integrity: { verified: true } });
    const own = await firstValueFrom(ev.version(EV, 2));
    expect((await errorOf(firstValueFrom(ev.openReview(EV, own)))).error.code).toBe(
      'PRODUCT-403-SELF-REVIEW',
    );
    failMockIntegrityOnce();
    expect((await errorOf(firstValueFrom(ev.openReview(R, opened)))).error.code).toBe(
      'PRODUCT-409-INTEGRITY-FAILED',
    );
  });

  it('xác nhận: cần MFA, đủ tiêu chuẩn, nhận xét khi B < 1, nguyên nhân chỉnh sửa; xem trước điểm như Hình 8.15', async () => {
    const { ev } = setup();
    const v = await firstValueFrom(ev.openReview(R, await firstValueFrom(ev.version(R, 2))));
    const p = await firstValueFrom(
      ev.scorePreview({
        taskId: v.taskId,
        qualityLevel: 0.75,
        revisionCause: 'NOI_DUNG_KHONG_COT_LOI',
        isObjectiveRevision: false,
      }),
    );
    expect(p).toMatchObject({ a: 1, b: 0.75, c: 0.9, kpi: 0.883 });
    const body = { qualityLevel: 0.75 as const, criteriaResults: criteria };
    expect((await errorOf(firstValueFrom(ev.accept(R, v, body)))).status).toBe(401);
    mockStepUp();
    const err = async (b: object) =>
      (await errorOf(firstValueFrom(ev.accept(R, v, b as never)))).error.code;
    expect(await err({ ...body, criteriaResults: criteria.slice(0, 2) })).toBe(
      'PRODUCT-422-CRITERIA-INCOMPLETE',
    );
    expect(await err(body)).toBe('PRODUCT-422-COMMENT-REQUIRED');
    expect(await err({ ...body, comment: 'Thiếu số liệu ở phiên bản 1' })).toBe(
      'PRODUCT-422-REVISION-CAUSE-REQUIRED',
    );
    const res = await firstValueFrom(
      ev.accept(R, v, {
        ...body,
        comment: 'Thiếu số liệu ở phiên bản 1',
        revisionCause: 'NOI_DUNG_KHONG_COT_LOI',
        isObjectiveRevision: false,
      }),
    );
    expect(res).toMatchObject({ versionStatus: 'DA_XAC_NHAN', taskStatusChanged: 'HOAN_THANH' });
    expect(res.acceptance).toMatchObject({ qualityLevel: 0.75, revisionCount: 1 });
  });

  it('yêu cầu bổ sung bắt buộc nhận xét, phiên bản kết thúc và người nộp tạo phiên bản mới', async () => {
    const { ev } = setup();
    const v = await firstValueFrom(ev.openReview(R, await firstValueFrom(ev.version(R, 2))));
    expect((await errorOf(firstValueFrom(ev.requestRevision(R, v, { comment: ' ' })))).status).toBe(
      422,
    );
    const res = await firstValueFrom(
      ev.requestRevision(R, v, { comment: 'Bổ sung biên bản kiểm tra chi bộ 5' }),
    );
    expect(res.versionStatus).toBe('YEU_CAU_BO_SUNG');
    const e = await firstValueFrom(ev.get(R));
    expect(e.versions?.at(-1)).toMatchObject({
      status: 'YEU_CAU_BO_SUNG',
      decisionNote: 'Bổ sung biên bản kiểm tra chi bộ 5',
    });
  });
});
