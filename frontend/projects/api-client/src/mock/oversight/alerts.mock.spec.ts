import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AlertApi } from '../../lib/alert-api';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';

/** WEB-044: hành vi giả lập SCR-1401 (QT-14) theo hợp đồng `oversight.yaml`, `config.yaml`. */
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
  return TestBed.inject(AlertApi);
}

async function errorOf(p: Promise<unknown>): Promise<HttpErrorResponse> {
  try {
    await p;
  } catch (e) {
    return e as HttpErrorResponse;
  }
  throw new Error('không có lỗi');
}

const NOTE = 'Ba báo cáo tháng dùng cùng mẫu nhưng số liệu khác nhau, xác nhận hợp lệ.';

describe('mock oversight – QT-14 cảnh báo bất thường (WEB-044)', () => {
  beforeEach(() => resetMockData());

  it('danh sách sắp theo mức rồi thời gian, lọc trạng thái, kèm thẻ phân bố 90 ngày', async () => {
    const api = setup();
    const page = await firstValueFrom(
      api.list({ status: ['MOI', 'DANG_XEM', 'DA_GIAO_KIEM_TRA'] }),
    );
    expect(page.items.length).toBe(8);
    expect(new Set(page.items.map((a) => a.signalCode)).size).toBe(8);
    expect(page.items.map((a) => a.severity)).toEqual([
      'CAO',
      'CAO',
      'VUA',
      'VUA',
      'VUA',
      'VUA',
      'THAP',
      'THAP',
    ]);
    expect(page.counts).toMatchObject({ windowDays: 90, validCount: 1, violationCount: 1 });
    const high = await firstValueFrom(
      api.list({ severity: ['CAO'], signal: ['MINH_CHUNG_TRUNG'] }),
    );
    expect(high.items.map((a) => a.id)).toEqual(['cb-2026-0118', 'cb-2026-0112']);
  });

  it('mở lần đầu chuyển Mới sang Đang xem; xác nhận hợp lệ gỡ cờ chặn và đóng', async () => {
    const api = setup();
    const a = await firstValueFrom(api.get('cb-2026-0118'));
    expect(a.status).toBe('DANG_XEM');
    expect(a.actions[0]).toMatchObject({ action: 'TU_DONG', active: true });
    const short = await errorOf(
      firstValueFrom(api.act(a, { type: 'XAC_NHAN_HOP_LE', note: 'ngắn' })),
    );
    expect(short.status).toBe(422);
    const done = await firstValueFrom(api.act(a, { type: 'XAC_NHAN_HOP_LE', note: NOTE }));
    expect(done.status).toBe('DONG');
    expect(done.actions[0].active).toBe(false);
    expect(done.actions.at(-1)).toMatchObject({ action: 'XAC_NHAN_HOP_LE', note: NOTE });
    const stale = await errorOf(firstValueFrom(api.act(a, { type: 'GHI_CHU', note: NOTE })));
    expect(stale.status).toBe(412);
  });

  it('giao kiểm tra bắt buộc người được giao; kết luận có vi phạm', async () => {
    const api = setup();
    const a = await firstValueFrom(api.get('cb-2026-0116'));
    const missing = await errorOf(
      firstValueFrom(api.act(a, { type: 'GIAO_KIEM_TRA', note: NOTE })),
    );
    expect(missing.status).toBe(422);
    const assigned = await firstValueFrom(
      api.act(a, { type: 'GIAO_KIEM_TRA', note: NOTE, assignedTo: 'p-dung' }),
    );
    expect(assigned.status).toBe('DA_GIAO_KIEM_TRA');
    expect(assigned.actions.at(-1)?.assignedTo?.name).toBe('Hoàng Văn Dũng');
    const again = await errorOf(
      firstValueFrom(api.act(assigned, { type: 'XAC_NHAN_HOP_LE', note: NOTE })),
    );
    expect(again.status).toBe(409);
    const v = await firstValueFrom(
      api.act(assigned, { type: 'KET_LUAN', note: NOTE, verdict: 'CO_VI_PHAM' }),
    );
    expect(v.status).toBe('CO_VI_PHAM');
  });

  it('tách vai trò: cảnh báo về đơn vị của người xử lý trả 403', async () => {
    const api = setup();
    const a = await firstValueFrom(api.get('cb-2026-0113'));
    const e = await errorOf(firstValueFrom(api.act(a, { type: 'XAC_NHAN_HOP_LE', note: NOTE })));
    expect(e.status).toBe(403);
    expect(e.error.code).toBe('OVERSIGHT-403-SEPARATION-OF-DUTIES');
  });

  it('ngưỡng phát hiện: lưu phiên bản mới có lý do', async () => {
    const api = setup();
    const t = await firstValueFrom(api.thresholds());
    expect(t.signals.length).toBe(8);
    const signals = t.signals.map((s) =>
      s.signalCode === 'CAO_BANG' ? { ...s, params: { sameLevelPercent: 85 } } : s,
    );
    const bad = await errorOf(firstValueFrom(api.saveThresholds(t, { signals, reason: 'ngắn' })));
    expect(bad.status).toBe(422);
    const saved = await firstValueFrom(
      api.saveThresholds(t, {
        signals,
        reason: 'Hạ ngưỡng cào bằng xuống 85% theo chỉ đạo của Ban Thường vụ.',
      }),
    );
    expect(saved.version).toBe(t.version + 1);
    expect(saved.signals.find((s) => s.signalCode === 'CAO_BANG')?.params).toEqual({
      sameLevelPercent: 85,
    });
  });
});
