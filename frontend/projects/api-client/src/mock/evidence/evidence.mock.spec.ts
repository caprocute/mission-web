import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { EvidenceApi } from '../../lib/evidence-api';
import { FileApi, FileRejectedError, UploadProgress } from '../../lib/file-api';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { failMockUploadPartOnce } from '../mock-session';

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
