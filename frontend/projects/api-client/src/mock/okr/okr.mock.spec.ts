import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { JobTracker } from '../../lib/job-tracker';
import { jobLocation } from '../../lib/job-tracker';
import { OkrApi } from '../../lib/okr-api';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { OKR_LARGE_UNIT_ID } from './okr.mock';

/** WEB-021: giả lập cây mục tiêu SCR-0201, lịch sử check-in và xuất mẫu. */
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
  return TestBed.inject(OkrApi);
}

describe('mock okr – SCR-0201 (WEB-021)', () => {
  beforeEach(() => resetMockData());

  it('mặc định trả kỳ đang mở với cây ba tầng, tiến độ tính từ KR như mockup', async () => {
    const t = await firstValueFrom(setup().tree());
    expect(t.period.label).toBe('Quý IV/2026');
    expect(t.parent?.code).toBe('O-ĐUK-1');
    const o1 = t.objectives.find((o) => o.code === 'O1')!;
    const o2 = t.objectives.find((o) => o.code === 'O2')!;
    expect(o1.progressPct).toBe(64);
    expect(o2.progressPct).toBe(51);
    expect(new Set(t.objectives.map((o) => o.tier))).toEqual(new Set([1, 2, 3]));
    expect(t.peerLinks).toHaveLength(1);
  });

  it('kỳ chưa có mục tiêu trả cây rỗng; đơn vị ngoài phạm vi trả 403', async () => {
    const api = setup();
    const t = await firstValueFrom(api.tree({ period: 'p-2027-q1' }));
    expect(t.objectives).toEqual([]);
    const err = await firstValueFrom(api.tree({ unit: 'u-khac' })).catch((e) => e);
    expect((err as HttpErrorResponse).status).toBe(403);
  });

  it('đơn vị lớn sinh hàng trăm nút để thử ảo hoá', async () => {
    const t = await firstValueFrom(setup().tree({ unit: OKR_LARGE_UNIT_ID }));
    expect(t.objectives.length).toBeGreaterThan(300);
  });

  it('lịch sử check-in phân trang con trỏ, mới nhất trước', async () => {
    const api = setup();
    const p1 = await firstValueFrom(api.checkins('kr-1-3', undefined, 2));
    expect(p1.items.map((c) => c.weekLabel)).toEqual(['Tuần 4', 'Tuần 3']);
    expect(p1.nextCursor).not.toBeNull();
    const p2 = await firstValueFrom(api.checkins('kr-1-3', p1.nextCursor!, 2));
    expect(p2.items.map((c) => c.weekLabel)).toEqual(['Tuần 2']);
    expect(p2.nextCursor).toBeNull();
  });

  it('xuất mẫu trả 202 + Location và job hoàn tất có đường dẫn kết quả', async () => {
    const api = setup();
    const res = await firstValueFrom(
      api.exportObjectives({ unitId: 'u-btc', periodId: 'p-2026-q4', view: 'tree' }),
    );
    expect(res.status).toBe(202);
    const loc = jobLocation(res)!;
    const job = await firstValueFrom(TestBed.inject(JobTracker).track(loc));
    expect(job.status).toBe('HOAN_TAT');
    expect(job.resultLocation).toMatch(/\/file$/);
  });
});

describe('mock okr – SCR-0202 (WEB-022)', () => {
  beforeEach(() => resetMockData());

  const err = (p: Promise<unknown>) =>
    p.then(
      () => {
        throw new Error('cần lỗi');
      },
      (e: HttpErrorResponse) => e,
    );

  it('ngữ cảnh soạn: kỳ, căn cứ, mục tiêu cấp trên, số mục tiêu đã có và Policy Pack', async () => {
    const api = setup();
    const c = await firstValueFrom(api.composeContext());
    expect(c.period.id).toBe('p-2026-q4');
    expect(c.existingCount).toBe(3);
    expect(c.policy.rules.some((r) => r.br === 'BR-02-07')).toBe(true);
    const self = await firstValueFrom(api.composeContext({ objectiveId: 'o-3' }));
    expect(self.existingCount).toBe(2);
  });

  it('sửa bản nháp với If-Match: 428 khi thiếu, 409 khi cũ, tăng rowVersion khi khớp', async () => {
    const api = setup();
    const d = await firstValueFrom(api.objective('o-3'));
    expect(d.rowVersion).toBe(3);
    const stale = await err(firstValueFrom(api.saveObjective('o-3', 2, d)));
    expect(stale.status).toBe(409);
    const saved = await firstValueFrom(api.saveObjective('o-3', 3, { ...d, title: 'Tên mới' }));
    expect(saved.rowVersion).toBe(4);
    expect(saved.draftNo).toBe(4);
    const tree = await firstValueFrom(api.tree());
    expect(tree.objectives.find((o) => o.id === 'o-3')?.title).toBe('Tên mới');
  });

  it('trình: máy chủ kiểm lại quy tắc (422), sửa xong thì chuyển CHO_DUYET và báo người duyệt', async () => {
    const api = setup();
    const d = await firstValueFrom(api.objective('o-3'));
    const bad = await err(firstValueFrom(api.submitObjective('o-3')));
    expect(bad.status).toBe(422);
    expect(JSON.stringify(bad.error)).toContain('KPI mơ hồ');
    const fixed = {
      ...d,
      keyResults: d.keyResults.map((k) =>
        k.code === 'KR 3.3'
          ? {
              ...k,
              title: 'Kiểm tra 100% chi bộ trực thuộc',
              unit: '%',
              baselineValue: 60,
              targetValue: 100,
              dueDate: '2026-12-31',
              verifierId: 'p-dung',
            }
          : k,
      ),
    };
    await firstValueFrom(api.saveObjective('o-3', d.rowVersion, fixed));
    const res = await firstValueFrom(api.submitObjective('o-3'));
    expect(res.status).toBe('CHO_DUYET');
    expect(res.reviewerName).toContain('Đinh Văn Sơn');
    const again = await err(firstValueFrom(api.saveObjective('o-3', d.rowVersion + 2, fixed)));
    expect(again.status).toBe(409);
  });

  it('tạo mục tiêu mới cấp mã O{n} và mã KR; kỳ đã khoá thì từ chối', async () => {
    const api = setup();
    const created = await firstValueFrom(
      api.createObjective({
        periodId: 'p-2027-q1',
        ownerType: 'TAP_THE',
        basisIds: ['b-kh-05'],
        parentObjectiveId: 'o-duk-1',
        title: 'Mục tiêu thử',
        overLimitReason: null,
        keyResults: [],
      }),
    );
    expect(created.code).toBe('O1');
    expect(created.status).toBe('NHAP');
    const locked = await err(
      firstValueFrom(api.createObjective({ ...created, id: undefined, periodId: 'p-2026-q3' })),
    );
    expect(locked.status).toBe(409);
  });

  it('trợ lý AI trả gợi ý cho KR mơ hồ và KR giống nhiệm vụ', async () => {
    const api = setup();
    const d = await firstValueFrom(api.objective('o-3'));
    const r = await firstValueFrom(api.aiReview(d));
    expect(r.suggestions.map((s) => s.krCode)).toEqual(['KR 3.3', 'KR 3.4']);
    expect(r.suggestions[0].sources.length).toBeGreaterThan(0);
  });
});

describe('mock okr – SCR-0203 (WEB-023)', () => {
  beforeEach(() => resetMockData());

  const err = (p: Promise<unknown>) =>
    p.then(
      () => {
        throw new Error('cần lỗi');
      },
      (e: HttpErrorResponse) => e,
    );

  it('hàng chờ: bộ OKR theo đơn vị với số O/KR và nhãn kiểm tra tổng hợp', async () => {
    const q = await firstValueFrom(setup().reviewQueue());
    expect(q.period.id).toBe('p-2026-q4');
    expect(q.reviewer.name).toBe('Đinh Văn Sơn');
    const byUnit = Object.fromEntries(q.items.map((s) => [s.unit.name, s]));
    expect(byUnit['Ban Tuyên giáo'].summary).toEqual({ tone: 'DAT', label: 'Đạt' });
    expect(byUnit['Văn phòng Đảng uỷ'].summary.label).toBe('Vượt 3 O');
    expect(byUnit['Văn phòng Đảng uỷ'].viewerIsAuthor).toBe(true);
    expect(byUnit['Đảng uỷ Cơ quan B'].summary.label).toBe('KR mơ hồ');
    expect(byUnit['Uỷ ban Kiểm tra'].krCount).toBe(5);
    expect(byUnit['Ban Tổ chức Đảng uỷ khối']).toBeUndefined();
  });

  it('bộ của Ban Tổ chức xuất hiện sau khi trình ở SCR-0202 và duyệt được, cây chuyển DA_DUYET', async () => {
    const api = setup();
    const d = await firstValueFrom(api.objective('o-3'));
    const krs = d.keyResults.filter((k) => k.code !== 'KR 3.3');
    krs[0] = { ...krs[0], weight: 0.7 };
    await firstValueFrom(api.saveObjective('o-3', d.rowVersion, { ...d, keyResults: krs }));
    await firstValueFrom(api.submitObjective('o-3'));
    const q = await firstValueFrom(api.reviewQueue());
    const own = q.items.find((s) => s.unit.name === 'Ban Tổ chức Đảng uỷ khối')!;
    expect(own.objectives.map((o) => o.code)).toEqual(['O3']);
    expect(own.objectives[0].keyResults.at(-1)?.check.label).toBe('Giống nhiệm vụ');
    const o = own.objectives[0];
    await firstValueFrom(api.approveObjective(o.id, o.rowVersion, { notifyPeers: true }));
    const tree = await firstValueFrom(api.tree());
    expect(tree.objectives.find((x) => x.id === 'o-3')?.status).toBe('DA_DUYET');
    const h = await firstValueFrom(api.history('o-3'));
    expect(h.items.map((x) => x.action)).toEqual(['DUYET', 'TRINH', 'LUU_NHAP']);
  });

  it('tự duyệt bị chặn 403 (BR-02-08); trả lại thiếu ý kiến 422 (BR-02-09); phiên bản cũ 409', async () => {
    const api = setup();
    const q = await firstValueFrom(api.reviewQueue());
    const vp = q.items.find((s) => s.viewerIsAuthor)!.objectives[0];
    const self = await err(
      firstValueFrom(api.approveObjective(vp.id, vp.rowVersion, { notifyPeers: true })),
    );
    expect(self.status).toBe(403);
    expect(self.error.code).toBe('OKR-403-SELF-APPROVAL');
    const btg = q.items.find((s) => s.unit.name === 'Ban Tuyên giáo')!.objectives[0];
    const noComment = await err(
      firstValueFrom(api.returnObjective(btg.id, btg.rowVersion, { notifyPeers: true })),
    );
    expect(noComment.status).toBe(422);
    const stale = await err(
      firstValueFrom(api.approveObjective(btg.id, 99, { notifyPeers: true })),
    );
    expect(stale.status).toBe(409);
    await firstValueFrom(
      api.returnObjective(btg.id, btg.rowVersion, {
        comment: 'Bổ sung căn cứ',
        notifyPeers: false,
      }),
    );
    const h = await firstValueFrom(api.history(btg.id));
    expect(h.items[0]).toMatchObject({ action: 'TRA_LAI', comment: 'Bổ sung căn cứ' });
  });

  it('duyệt hàng loạt trả 202 và job ghi số mục đã duyệt; bộ hết mục tiêu rời hàng chờ', async () => {
    const api = setup();
    const q = await firstValueFrom(api.reviewQueue());
    const set = q.items.find((s) => s.unit.name === 'Uỷ ban Kiểm tra')!;
    const res = await firstValueFrom(
      api.approveBatch(
        set.objectives.map((o) => ({ id: o.id, rowVersion: o.rowVersion })),
        { notifyPeers: true },
      ),
    );
    expect(res.status).toBe(202);
    const job = await firstValueFrom(TestBed.inject(JobTracker).track(jobLocation(res)!));
    expect(job.message).toBe('Đã phê duyệt 2/2 mục tiêu');
    const after = await firstValueFrom(api.reviewQueue());
    expect(after.items.some((s) => s.unit.name === 'Uỷ ban Kiểm tra')).toBe(false);
  });
});
