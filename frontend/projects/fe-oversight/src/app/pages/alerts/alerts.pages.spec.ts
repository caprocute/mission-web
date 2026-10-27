import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
  resetMockApi,
} from '@vt/api-client';
import { OVERSIGHT_CAN } from '../../oversight-permissions';
import { AlertCenterPage } from './alert-center.page';
import { AlertThresholdsPage } from './alert-thresholds.page';

let instance: unknown;

/** WEB-044: SCR-1401 và ngưỡng phát hiện trên dữ liệu giả lập. */
async function render<T>(
  cmp: Type<T>,
  id?: string,
  can: (k: string) => boolean = () => true,
): Promise<HTMLElement> {
  await resetMockApi();
  TestBed.configureTestingModule({
    imports: [cmp],
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      { provide: OVERSIGHT_CAN, useValue: can },
    ],
  });
  const fixture = TestBed.createComponent(cmp);
  if (id) fixture.componentRef.setInput('id', id);
  instance = fixture.componentInstance;
  fixture.autoDetectChanges();
  return fixture.nativeElement as HTMLElement;
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 200; i++) {
    const e = [...el.querySelectorAll(sel)].find(test);
    if (e) return e;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw new Error(`Không thấy ${sel}`);
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const has = (s: string) => (e: Element) => text(e).includes(s);

function type(el: HTMLElement, sel: string, value: string) {
  const f = el.querySelector(sel) as HTMLInputElement | HTMLTextAreaElement;
  f.value = value;
  f.dispatchEvent(new Event('input'));
}

async function press(el: HTMLElement, label: string) {
  const b = (await until(
    el,
    'button',
    (e) => text(e) === label && !(e as HTMLButtonElement).disabled,
  )) as HTMLButtonElement;
  b.click();
}

/** Không hiện mã dấu hiệu, mã quy tắc, mã băm hay thuật ngữ kỹ thuật (YC-PT-10). */
const TECH =
  /\b(BR|MSG|QT|SCR|TL)-\d|MINH_CHUNG_TRUNG|SUA_SAU_KHOA|DANG_XEM|SHA|5e88|ClickHouse|le-17-|cb-2026/;
const NOTE = 'Ba báo cáo tháng dùng cùng mẫu nhưng số liệu khác nhau, xác nhận hợp lệ.';

describe('AlertCenterPage (SCR-1401)', () => {
  it('danh sách đang mở có đủ tám dấu hiệu, sắp mức Cao trước; thẻ phân bố 90 ngày', async () => {
    const el = await render(AlertCenterPage);
    await until(el, '.items li', has('Minh chứng trùng'));
    const items = [...el.querySelectorAll('.items li')];
    expect(items.length).toBe(8);
    expect(text(items[0])).toContain('Mức cao');
    expect(new Set(items.map((i) => i.getAttribute('data-signal'))).size).toBe(8);
    expect(text(el.querySelector('.stats'))).toMatch(/Có vi phạm sau kiểm tra\s*1/);
    expect(text(el)).toContain('Cảnh báo chỉ chỉ ra chỗ cần xem, không phải kết luận vi phạm');
    expect(text(el)).not.toMatch(TECH);
  });

  it('minh chứng trùng: chuỗi truy vết, nguồn không lộ mã băm, xác nhận hợp lệ gỡ cờ chặn', async () => {
    const el = await render(AlertCenterPage, 'cb-2026-0118');
    await until(el, '#d-h', has('Minh chứng trùng'));
    expect(text(el.querySelector('#d-h'))).toContain('Đang xem xét');
    const chain = el.querySelector('.chain')!;
    expect(text(chain)).toContain('Tệp trùng nội dung: Ba minh chứng có cùng một tệp nội dung');
    expect(chain.querySelector('[data-kind="BAM_TRUNG"]')?.getAttribute('data-id')).toMatch(
      /^5e88/,
    );
    expect(text(el.querySelector('.tbl tbody'))).toContain('Trùng với 2 tệp khác');
    expect(text(el)).toContain('Tạm chưa tính điểm 3 minh chứng liên quan');
    expect(text(el)).not.toMatch(TECH);
    await press(el, 'Xác nhận hợp lệ');
    await until(el, '.err', has('Ghi chú xử lý cần từ 20'));
    type(el, '#cb-note', NOTE);
    await press(el, 'Xác nhận hợp lệ');
    const ok = await until(el, 'p-message', has('Đã xác nhận hợp lệ'));
    expect(text(ok)).toContain('3 minh chứng được tính điểm trở lại');
    expect(text(el.querySelector('#d-h'))).toContain('Đã đóng');
    expect(text(el)).toContain('đã gỡ');
  });

  it('kết luận có vi phạm với kỳ đã khoá: thông báo thi hành và nút tạo yêu cầu mở lại kỳ', async () => {
    const el = await render(AlertCenterPage, 'cb-2026-0117');
    await until(el, '#cb-note');
    const labels = [...el.querySelectorAll('button')].map((b) => text(b));
    expect(labels).toContain('Ghi kết luận');
    expect(labels).not.toContain('Xác nhận hợp lệ');
    (instance as { verdict: { set(v: string): void } }).verdict.set('CO_VI_PHAM');
    type(el, '#cb-note', 'Thay đổi hệ số C sau khi khoá kỳ không có căn cứ, có vi phạm quy trình.');
    await press(el, 'Ghi kết luận');
    await until(el, 'p-message', has('Đã ghi kết luận: có vi phạm'));
    const warn = await until(el, 'p-message', has('đã khoá'));
    expect(text(warn)).toContain('Kỳ Quý II/2026 đã khoá');
    const link = (await until(el, 'a', has('Tạo yêu cầu mở lại kỳ'))) as HTMLAnchorElement;
    expect(link.getAttribute('href')).toContain('basisKind=CANH_BAO');
    expect(link.getAttribute('href')).toContain('profileId=01928f3e-7b1a-7c3e-9a51-3f2d9c1d1301');
  });

  it('tách vai trò: cảnh báo về đơn vị của người xử lý bị máy chủ từ chối', async () => {
    const el = await render(AlertCenterPage, 'cb-2026-0113');
    await until(el, '#cb-note');
    type(el, '#cb-note', NOTE);
    await press(el, 'Xác nhận hợp lệ');
    const e = await until(el, 'p-message', has('không thể xử lý cảnh báo này'));
    expect(text(e)).not.toMatch(TECH);
  });

  it('người chỉ có quyền xem không thấy nút xử lý', async () => {
    const el = await render(AlertCenterPage, 'cb-2026-0116', (k) => k === 'SCR-1401.xem');
    await until(el, '#d-h', has('cào bằng'));
    expect(text(el)).toContain('chỉ có quyền xem');
    expect(el.querySelector('#cb-note')).toBeNull();
    expect(text(el)).not.toContain('Ngưỡng phát hiện');
  });
});

describe('AlertThresholdsPage (SCR-1401 ngưỡng phát hiện)', () => {
  it('đổi ngưỡng cào bằng, bắt buộc lý do, lưu phiên bản mới', async () => {
    const el = await render(AlertThresholdsPage);
    await until(el, 'tr[data-signal="CAO_BANG"] input[type="number"]');
    expect(text(el)).toContain('Phiên bản hiện hành 3');
    expect(text(el)).not.toMatch(TECH);
    const row = el.querySelector('tr[data-signal="CAO_BANG"]')!;
    expect(text(row)).toContain('Tỷ lệ cán bộ cùng một mức');
    const input = row.querySelector('input[type="number"]') as HTMLInputElement;
    input.value = '85';
    input.dispatchEvent(new Event('input'));
    await press(el, 'Lưu phiên bản mới');
    await until(el, '.err', has('Lý do thay đổi cần ít nhất 20 ký tự'));
    type(el, '#th-reason', 'Hạ ngưỡng cào bằng xuống 85% theo chỉ đạo của Ban Thường vụ.');
    await press(el, 'Lưu phiên bản mới');
    await until(el, 'p-message', has('Đã lưu phiên bản ngưỡng 4'));
  });
});
