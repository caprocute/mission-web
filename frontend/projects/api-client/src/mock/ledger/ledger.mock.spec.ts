import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { LedgerApi, verifyRunIdOf } from '../../lib/ledger-api';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { LOCKED_PROFILE } from '../appraisals/locked-profiles';

/** WEB-043: hành vi giả lập SCR-1302 (QT-13) theo hợp đồng `oversight.yaml`. */
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
  return TestBed.inject(LedgerApi);
}

async function errorOf(p: Promise<unknown>): Promise<HttpErrorResponse> {
  try {
    await p;
  } catch (e) {
    return e as HttpErrorResponse;
  }
  throw new Error('không có lỗi');
}

const DAY = 86_400_000;
const iso = (daysAgo: number) => new Date(Date.now() - daysAgo * DAY).toISOString();

describe('mock ledger – SCR-1302 sổ cái (WEB-043)', () => {
  beforeEach(() => resetMockData());

  it('tra cứu theo đối tượng trong 90 ngày, mới nhất trước, chuỗi nối bằng prevHash', async () => {
    const api = setup();
    const page = await firstValueFrom(
      api.entries({
        aggregateType: 'appraisal_profile',
        aggregateId: LOCKED_PROFILE.hoangQ2,
        from: iso(90),
        to: iso(0),
      }),
    );
    expect(page.items.length).toBe(11);
    expect(page.items[0].eventType).toBe('oversight.complaint.concluded.v1');
    expect(page.items.every((e) => e.chainPartition === 17)).toBe(true);
    const accepted = await firstValueFrom(
      api.entries({ eventType: 'product.evidence.accepted.v1', from: iso(90), to: iso(0) }),
    );
    expect(accepted.items.map((e) => e.seqNo)).toEqual([1042, 1036, 1034]);
    const d = await firstValueFrom(api.entry('le-17-1043'));
    const prev = await firstValueFrom(api.entry('le-17-1042'));
    expect(d.prevHash).toBe(prev.entryHash);
    expect(prev.seal).toMatchObject({ signatureValid: true });
    expect(prev.anchors?.map((a) => a.anchorTarget)).toEqual(['KHO_VPTW', 'TSA_DOC_LAP']);
  });

  it('khoảng thời gian quá 2 năm trả 422 LEDGER-422-RANGE-TOO-LONG', async () => {
    const api = setup();
    const e = await errorOf(firstValueFrom(api.entries({ from: iso(800), to: iso(0) })));
    expect(e.status).toBe(422);
    expect(e.error.code).toBe('LEDGER-422-RANGE-TOO-LONG');
  });

  it('lần kiểm tra gần nhất phá vỡ tại bản ghi 1042 phân vùng 17, 63/64 phân vùng khớp', async () => {
    const api = setup();
    const [last, prev] = await firstValueFrom(api.verifyRuns());
    expect(last).toMatchObject({
      partitionsMatched: 63,
      partitionsTotal: 64,
      firstMismatch: { chainPartition: 17, seqNo: 1042 },
    });
    expect(last.chain?.map((c) => [c.seqNo, c.matches])).toEqual([
      [1041, true],
      [1042, false],
      [1043, true],
    ]);
    expect(prev.mismatches).toBe(0);
  });

  it('chạy kiểm tra (202): job trỏ tới lần kiểm tra, xong sau hai lần đọc', async () => {
    const api = setup();
    const { location, job } = await firstValueFrom(
      api.startVerify({ verifyKind: 'TOAN_BO', chainPartition: 5 }),
    );
    expect(location).toMatch(/^\/api\/v1\/jobs\//);
    const id = verifyRunIdOf(job)!;
    expect((await firstValueFrom(api.verifyRun(id))).finishedAt).toBeNull();
    const done = await firstValueFrom(api.verifyRun(id));
    expect(done).toMatchObject({ mismatches: 0, partitionsMatched: 1, partitionsTotal: 1 });
    expect(done.finishedAt).toBeTruthy();
    const bad = await errorOf(
      firstValueFrom(api.startVerify({ verifyKind: 'TOAN_BO', chainPartition: 64 })),
    );
    expect(bad.status).toBe(422);
  });

  it('xuất bằng chứng (202) hoàn tất kèm đường dẫn tệp', async () => {
    const api = setup();
    const { job } = await firstValueFrom(api.export({ from: iso(90), to: iso(0) }));
    expect(job).toMatchObject({ status: 'HOAN_TAT', type: 'EXPORT' });
    expect(job.resultLocation).toMatch(/^\/api\/v1\/files\//);
  });
});
