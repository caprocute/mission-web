import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, lastValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { JOB_TRACKER_OPTIONS, JobTracker } from '../../lib/job-tracker';
import { OrgTreeNode } from '../../lib/models/org-tree';
import { OrgTreeApi, orgImportErrors } from '../../lib/org-tree-api';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';

/** WEB-018, WEB-072: giả lập cây tổ chức có lịch sử SCR-0103 theo hợp đồng `org.yaml`. */
function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      {
        provide: JOB_TRACKER_OPTIONS,
        useValue: { initialDelayMs: 5, maxDelayMs: 5, maxRetries: 0 },
      },
    ],
  });
  return TestBed.inject(OrgTreeApi);
}

const day = (offset: number) =>
  new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);

const find = (nodes: OrgTreeNode[] | undefined, id: string): OrgTreeNode | undefined => {
  for (const n of nodes ?? []) {
    if (n.id === id) return n;
    const hit = find(n.children, id);
    if (hit) return hit;
  }
  return undefined;
};

describe('mock org-tree – SCR-0103 (WEB-018, WEB-072)', () => {
  beforeEach(() => resetMockData());

  it('cây theo độ sâu, nhánh sâu hơn nạp bằng subtree; xem tại ngày ra cơ cấu cũ', async () => {
    const api = setup();
    const tree = await firstValueFrom(api.tree());
    expect(tree).toMatchObject({ asOf: day(0), readOnly: false });
    expect(tree.roots.map((n) => n.name)).toEqual(['Đảng bộ tỉnh Thái Nguyên']);
    // ba tầng: gốc, UBND tỉnh, các sở; phòng của Sở Nội vụ chưa nạp
    const snv = find(tree.roots, 'u-snv')!;
    expect(snv).toMatchObject({ hasChildren: true, children: [] });
    // đơn vị đã kết thúc không có trên cây hiện tại
    expect(find(tree.roots, 'u-pb')).toBeUndefined();
    expect(find(tree.roots, 'u-ubnd')!.children!.length).toBeGreaterThan(90);

    const sub = await firstValueFrom(api.subtree('u-snv'));
    expect(sub.roots[0].id).toBe('u-snv');
    expect(sub.roots[0].children!.map((n) => n.name)).toContain('Phòng Công chức, viên chức');

    const past = await firstValueFrom(api.tree({ asOf: '2025-06-30' }));
    expect(past.readOnly).toBe(true);
    expect(find(past.roots, 'u-pb')).toMatchObject({ name: 'Huyện Phú Bình', hasChildren: true });
    expect(find(past.roots, 'u-dux-pb')).toBeUndefined();
    await expect(firstValueFrom(api.tree({ asOf: day(3) }))).rejects.toMatchObject({
      status: 422,
    });
  });

  it('trục ghi nhận: đổi tên ghi muộn chưa thấy khi xem đã ghi nhận đến trước ngày ghi', async () => {
    const api = setup();
    expect((await firstValueFrom(api.unit('u-snv-ccvc'))).name).toBe('Phòng Công chức, viên chức');
    const before = await firstValueFrom(api.unit('u-snv-ccvc', { recordedAsOf: day(-34) }));
    expect(before.name).toBe('Phòng Công chức');
    const old = await firstValueFrom(api.history('u-snv-ccvc', { recordedAsOf: day(-34) }));
    expect(old.items.map((h) => h.name)).toEqual(['Phòng Công chức']);
    const now = await firstValueFrom(api.history('u-snv-ccvc'));
    expect(now.items[0]).toMatchObject({
      name: 'Phòng Công chức, viên chức',
      changeType: 'DOI_TEN',
      changeOrder: { orderNo: '96/QĐ-SNV' },
    });
    expect(now.items[1]).toMatchObject({ validTo: day(-34), recordedFrom: expect.any(String) });
    expect(now.nextCursor).toBeNull();
  });

  it('chi tiết, tìm kiếm kèm tổ tiên, phân công gồm kiêm nhiệm, điều động và biệt phái', async () => {
    const api = setup();
    const snv = await firstValueFrom(api.unit('u-snv'));
    expect(snv).toMatchObject({
      nationalCode: '000.20.21.H56',
      parentId: 'u-ubnd',
      parentName: 'UBND tỉnh',
      subjectGroupName: 'Khối 4 · cơ quan hành chính',
      predecessorIds: ['u-snv-cu', 'u-sldtbxh'],
      rowVersion: 0,
    });
    const pred = await firstValueFrom(api.unit('u-snv-cu', { asOf: '2025-02-28' }));
    expect(pred.name).toBe('Sở Nội vụ (cũ)');
    const found = await firstValueFrom(api.search('so noi vu'));
    expect(found.find((m) => m.id === 'u-snv')?.ancestors.map((a) => a.id)).toEqual([
      'u-tn',
      'u-ubnd',
    ]);
    const page = await firstValueFrom(api.assignments('u-snv', {}, { limit: 5 }));
    expect(page.items.map((a) => a.kind)).toEqual([
      'CHINH',
      'KIEM_NHIEM',
      'CHINH',
      'DIEU_DONG',
      'BIET_PHAI',
    ]);
    expect(page.items[0]).toMatchObject({ jobTitleName: 'Giám đốc', personId: 'p-as-1' });
    expect(page.nextCursor).toBe('5');
  });

  it('sửa nhận OrgUnitInput kèm If-Match, tạo phiên bản mới; đơn vị đồng bộ không sửa được', async () => {
    const api = setup();
    const cur = await firstValueFrom(api.unit('so-tp'));
    const body = {
      code: cur.code,
      name: 'Sở Tư pháp tỉnh',
      level: cur.level,
      unitType: cur.unitType,
      subjectGroupId: cur.subjectGroupId,
      flows: cur.flows,
      validFrom: day(-1),
    };
    const after = await firstValueFrom(api.update(cur, body));
    expect(after).toMatchObject({ name: 'Sở Tư pháp tỉnh', rowVersion: 1, validFrom: day(-1) });
    expect((await firstValueFrom(api.unit('so-tp', { asOf: day(-2) }))).name).toBe('Sở Tư pháp');
    const hist = await firstValueFrom(api.history('so-tp'));
    expect(hist.items[0].changedFields).toEqual(['name']);
    await expect(firstValueFrom(api.update(cur, body))).rejects.toMatchObject({
      status: 409,
      error: { code: 'ORG-409-VERSION-CONFLICT' },
    });
    await expect(firstValueFrom(api.update(after, { ...body, code: '' }))).rejects.toMatchObject({
      status: 422,
      error: { errors: [{ field: 'code' }] },
    });
    const thpt = await firstValueFrom(api.unit('u-thpt-1'));
    await expect(
      firstValueFrom(
        api.update(thpt, { ...body, code: thpt.code, subjectGroupId: thpt.subjectGroupId }),
      ),
    ).rejects.toMatchObject({ error: { code: 'ORG-409-SYNCED' } });
  });

  it('thay đổi tổ chức: thiếu tiền thân MSG-01-09, sáp nhập trả OrgChangeOrder, tương lai ở NHAP', async () => {
    const api = setup();
    const base = { orderNo: '1/QĐ', orderDate: day(-1), effectiveOn: day(-1) };
    await expect(
      firstValueFrom(api.change('so-tc', { ...base, changeType: 'SAP_NHAP' })),
    ).rejects.toMatchObject({
      error: {
        errors: [
          {
            field: 'relatedUnitIds',
            message: 'Sáp nhập cần khai báo ít nhất một đơn vị tiền thân.',
          },
        ],
      },
    });
    await expect(
      firstValueFrom(
        api.change('so-tc', {
          changeType: 'DOI_TEN',
          effectiveOn: day(-1),
          orderNo: '1/QĐ',
        } as never),
      ),
    ).rejects.toMatchObject({ error: { errors: [{ field: 'orderDate' }] } });
    const res = await firstValueFrom(
      api.change('so-tc', {
        changeType: 'SAP_NHAP',
        effectiveOn: '2026-06-01',
        orderNo: '2001/QĐ-UBND',
        orderDate: '2026-05-20',
        relatedUnitIds: ['so-ct'],
        moveAssignmentsTo: 'so-tc',
      }),
    );
    expect(res).toMatchObject({
      changeType: 'SAP_NHAP',
      orderNo: '2001/QĐ-UBND',
      effectiveOn: '2026-06-01',
      status: 'DA_AP_DUNG',
    });
    expect(res.affectedUnitIds).toEqual(['so-tc', 'so-ct']);
    const ubnd = (await firstValueFrom(api.subtree('u-ubnd'))).roots[0].children!;
    expect(ubnd.some((n) => n.id === 'so-ct')).toBe(false);
    expect((await firstValueFrom(api.unit('so-tc'))).predecessorIds).toEqual(['so-ct']);

    const future = await firstValueFrom(
      api.change('so-yt', {
        changeType: 'DOI_TEN',
        effectiveOn: day(30),
        orderNo: '5/QĐ',
        orderDate: day(0),
        newName: 'Sở Y tế tỉnh',
      }),
    );
    expect(future.status).toBe('NHAP');
  });

  it('thành lập, hợp nhất tạo đơn vị mới từ newUnits; giải thể bị chặn khi còn đơn vị trực thuộc', async () => {
    const api = setup();
    const unit = {
      level: 'TINH',
      unitType: 'PHONG_BAN' as const,
      subjectGroupId: 'sg-k4',
      flows: ['NHA_NUOC' as const],
      validFrom: day(-1),
    };
    const est = await firstValueFrom(
      api.change('so-xd', {
        changeType: 'THANH_LAP',
        effectiveOn: day(-1),
        orderNo: '7/QĐ',
        orderDate: day(-2),
        newUnits: [{ ...unit, code: 'TN-UB-SXD-QH', name: 'Phòng Quy hoạch' }],
      }),
    );
    const child = (await firstValueFrom(api.subtree('so-xd'))).roots[0].children!;
    expect(child.map((n) => n.name)).toEqual(['Phòng Quy hoạch']);
    expect(est.affectedUnitIds).toContain(child[0].id);

    await expect(
      firstValueFrom(
        api.change('so-gd', {
          changeType: 'GIAI_THE',
          effectiveOn: day(-1),
          orderNo: '3/QĐ',
          orderDate: day(-1),
        }),
      ),
    ).rejects.toMatchObject({ status: 422, error: { code: 'ORG-422-HAS-ACTIVE-CHILDREN' } });
  });

  it('nhập Excel: 202 + Location /api/v1/jobs/{id}, lỗi từng dòng ở problem.errors', async () => {
    const api = setup();
    const jobs = TestBed.inject(JobTracker);
    const ok = await firstValueFrom(api.import(new File(['x'], 'cay-to-chuc.xlsx')));
    expect(ok.location).toBe(`/api/v1/jobs/${ok.job.id}`);
    expect(ok.job).toMatchObject({ type: 'IMPORT', status: 'CHO' });
    expect(await lastValueFrom(jobs.track(ok.location))).toMatchObject({
      status: 'HOAN_TAT',
      message: 'Đã nhập 37 đơn vị.',
    });

    const bad = await firstValueFrom(api.import(new File(['x'], 'cay-to-chuc-loi.xlsx')));
    const job = await lastValueFrom(jobs.track(bad.location));
    expect(job).toMatchObject({ status: 'THAT_BAI', problem: { code: 'ORG-422-IMPORT-INVALID' } });
    expect(orgImportErrors(job)).toEqual([
      { row: 12, message: expect.stringContaining('Phụ lục 2A') },
      { row: 27, message: expect.stringContaining('tiền thân') },
    ]);
  });
});
