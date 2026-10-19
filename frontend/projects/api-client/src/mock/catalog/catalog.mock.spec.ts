import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { CatalogApi } from '../../lib/catalog-api';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { CatalogItemInput } from '../../lib/models/catalog';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';

/** WEB-025: hành vi giả lập SCR-0401 (lọc, BR-04-13, BR-04-14, nhân bản phiên bản, nhập Excel). */
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
  return TestBed.inject(CatalogApi);
}

const V3 = 'cat-cvdv-v3';
const input = (over: Partial<CatalogItemInput> = {}): CatalogItemInput => ({
  positionId: 'vt-cvdv',
  name: 'Tổng hợp ý kiến góp ý dự thảo văn kiện',
  productName: 'Báo cáo tổng hợp',
  unit: 'báo cáo',
  difficulty: 3,
  baseScore: 20,
  qualityCriteria: 'Đủ ý kiến của các chi bộ',
  evidenceRequired: 'Báo cáo có chữ ký',
  defaultGroup: 'THUONG_XUYEN',
  ...over,
});

describe('mock catalog – SCR-0401 (WEB-025)', () => {
  beforeEach(async () => resetMockData());

  it('phiên bản đang áp dụng đứng đầu, lọc theo vị trí, mức khó, trạng thái, từ khoá', async () => {
    const api = setup();
    const cats = await firstValueFrom(api.catalogs());
    expect(cats.items[0]).toMatchObject({
      id: V3,
      versionNo: 3,
      status: 'AP_DUNG',
      itemCount: 16,
      positionCount: 3,
    });
    const all = await firstValueFrom(api.versionItems(V3, { status: 'AP_DUNG', limit: 6 }));
    expect(all.total).toBe(15);
    expect(all.items).toHaveLength(6);
    expect(all.nextCursor).toBe('6');
    const tc = await firstValueFrom(api.versionItems(V3, { positionId: 'vt-cvtc', difficulty: 5 }));
    expect(tc.items.map((i) => i.code)).toEqual(['TC-TC-02']);
    expect((await firstValueFrom(api.versionItems(V3, { q: 'quy hoạch' }))).items[0].code).toBe(
      'TC-DV-06',
    );
  });

  it('BR-04-14 và từ mơ hồ trả 422; thêm vào phiên bản đang áp dụng tạo phiên bản nháp, tự sinh mã', async () => {
    const api = setup();
    await expect(
      firstValueFrom(api.addItem(V3, input({ difficulty: 6, baseScore: 120 }))),
    ).rejects.toMatchObject({ status: 422 });
    await expect(
      firstValueFrom(api.addItem(V3, input({ name: 'Đẩy mạnh công tác phát triển đảng viên' }))),
    ).rejects.toMatchObject({
      error: { errors: [{ field: 'name' }] },
    });
    const r = await firstValueFrom(api.addItem(V3, input()));
    expect(r.movedToNewVersion).toBe(true);
    expect(r.catalog).toMatchObject({ versionNo: 4, status: 'NHAP' });
    expect(r.item.code).toBe('TC-DV-11');
  });

  it('BR-04-13: sửa mục đã dùng ở kỳ khoá lưu ở phiên bản mới; mục chưa dùng sửa tại chỗ', async () => {
    const api = setup();
    const { items } = await firstValueFrom(api.versionItems(V3, { q: 'TC-DV-0', limit: 50 }));
    const used = items.find((i) => i.code === 'TC-DV-01')!;
    const fresh = items.find((i) => i.code === 'TC-DV-06')!;
    const a = await firstValueFrom(
      api.updateItem(V3, used, input({ name: 'Thẩm định hồ sơ kết nạp đảng viên', baseScore: 22 })),
    );
    expect(a.movedToNewVersion).toBe(true);
    expect(a.item.baseScore).toBe(22);
    const still = (await firstValueFrom(api.versionItems(V3, { q: 'TC-DV-01' }))).items[0];
    expect(still.baseScore).toBe(20);
    const b = await firstValueFrom(
      api.updateItem(
        V3,
        fresh,
        input({ name: 'Rà soát quy hoạch cấp uỷ', difficulty: 5, baseScore: 34 }),
      ),
    );
    expect(b.movedToNewVersion).toBe(false);
    await expect(firstValueFrom(api.updateItem(V3, fresh, input()))).rejects.toMatchObject({
      status: 409,
    });
    await expect(firstValueFrom(api.cloneVersion(V3))).rejects.toMatchObject({ status: 409 });
  });

  it('nhập Excel: 202, hỏi tác vụ tới khi xong, kết quả gần nhất và tệp lỗi', async () => {
    const api = setup();
    const before = await firstValueFrom(api.latestImport(V3));
    expect(before).toMatchObject({ validRows: 1221, totalRows: 1240 });
    const { location } = await firstValueFrom(
      api.importExcel(V3, new File(['x'], 'danh-muc-moi.xlsx')),
    );
    expect((await firstValueFrom(api.importJob(location))).status).toBe('DANG_XU_LY');
    const done = await firstValueFrom(api.importJob(location));
    expect(done).toMatchObject({ status: 'XONG', validRows: 47 });
    expect((await firstValueFrom(api.latestImport(V3)))!.fileName).toBe('danh-muc-moi.xlsx');
    const blob = await firstValueFrom(api.importErrors(done.id));
    expect(blob.size).toBeGreaterThan(10);
  });
});
