import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  DEFAULT_RUNTIME_CONFIG,
  JOB_TRACKER_OPTIONS,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { ADMIN_CAN } from '../../admin-permissions';
import { OrgSyncPage } from './org-sync.page';

/**
 * WEB-019: SCR-0104 trên dữ liệu giả lập: thẻ nguồn, MSG-01-12/13/14/15, lọc, chi tiết, quyền.
 * Dữ liệu giả lập dùng chung trong tệp, nên các ca thay đổi dữ liệu chạy sau ca chỉ đọc.
 */
async function render(can: (k: string) => boolean = () => true) {
  TestBed.configureTestingModule({
    imports: [OrgSyncPage],
    providers: [
      provideRouter([]),
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
      { provide: ADMIN_CAN, useValue: can },
    ],
  });
  const fixture = TestBed.createComponent(OrgSyncPage);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  await until(el, 'tbody th[scope=row]');
  return { fixture, el };
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 100; i++) {
    const e = el.querySelector(sel);
    if (e && test(e)) return;
    await new Promise((r) => setTimeout(r, 20));
  }
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const button = (el: HTMLElement, label: string) =>
  [...el.querySelectorAll('button')].find((b) => text(b).includes(label));
const rowBox = (el: HTMLElement, name: string) =>
  el.querySelector(`input[aria-label="Chọn ${name}"]`) as HTMLInputElement;
const page = (el: HTMLElement) => text(el);

describe('OrgSyncPage (SCR-0104)', () => {
  it('bốn thẻ nguồn, MSG-01-12, MSG-01-14 và chi tiết dòng đầu', async () => {
    const { el } = await render();
    const cards = [...el.querySelectorAll('.card')].map(text);
    expect(cards[0]).toContain('CSDL quốc gia CBCCVC (NDXP)');
    expect(cards[0]).toContain('Thành công');
    expect(cards[0]).toContain(' 02:00 ·');
    expect(cards[0]).toContain('61.204 bản ghi · 37 thay đổi');
    expect(cards[2]).toContain('Một phần');
    expect(cards[3]).toContain('412');
    expect(page(el)).toMatch(/Còn \d+ mục lệch mức Cao\. Đơn vị chưa thể được kích hoạt\./);
    expect(page(el)).toContain('Lần đồng bộ CBCCVC lúc');
    expect(page(el)).toContain(
      'NDXP không phản hồi sau 120 giây. Dữ liệu đã được lưu lại để chạy lại.',
    );
    const detail = text(el.querySelector('section[aria-labelledby=z4]'));
    expect(detail).toContain('CBCCVC: 000.20.21.H56-00412');
    expect(detail).toContain('12/10/2026 (theo quyết định)');
    expect(detail).toContain('nhận được thông báo thay đổi phân công');
  });

  it('lọc theo trường lệch và chọn cán bộ để xem chi tiết', async () => {
    const { el } = await render();
    button(el, 'Không khớp người')!.click();
    await until(el, 'tbody th[scope=row]', (e) => text(e).startsWith('Trần Văn Khải'));
    const rows = [...el.querySelectorAll('section[aria-labelledby=z2] tbody tr')].map(text);
    expect(rows.filter((r) => !r.includes('Không khớp người'))).toEqual([]);
    button(el, 'Trần Văn Khải')!.click();
    await until(el, 'section[aria-labelledby=z4]', (e) => text(e).includes('Trần Văn Khải'));
    expect(text(el.querySelector('section[aria-labelledby=z4]'))).toContain('cần ánh xạ thủ công');
  });

  it('chỉ đọc: không có nút đồng bộ, không có ô chọn dòng', async () => {
    const { el } = await render((k) => k === 'SCR-0104.xem');
    expect(button(el, 'Đồng bộ ngay')).toBeUndefined();
    expect(button(el, 'Chạy lại')).toBeUndefined();
    expect(el.querySelector('tbody input[type=checkbox]')).toBeNull();
    expect(button(el, 'Lấy theo nguồn')).toBeUndefined();
  });

  it('lấy theo nguồn hai dòng hiện MSG-01-13; dòng không khớp người bị chặn MSG-01-15', async () => {
    const { el } = await render();
    rowBox(el, 'Trần Văn Khải').click();
    await until(el, 'button', () => !!button(el, 'Lấy theo nguồn (1)'));
    button(el, 'Lấy theo nguồn (1)')!.click();
    await until(el, 'vt-org-sync-list .err', (e) => text(e).includes('đối soát thủ công'));
    expect(text(el.querySelector('vt-org-sync-list .err'))).toBe(
      'Trần Văn Khải: Hai hồ sơ trùng tên khác ngày sinh. Vui lòng đối soát thủ công.',
    );
    rowBox(el, 'Trần Văn Khải').click();
    rowBox(el, 'Nguyễn Thị Lan').click();
    rowBox(el, 'Vũ Văn Toàn').click();
    await until(el, 'button', () => !!button(el, 'Lấy theo nguồn (2)'));
    button(el, 'Lấy theo nguồn (2)')!.click();
    await until(el, '.note', () => page(el).includes('Đã áp dụng'));
    expect(page(el)).toContain(
      'Đã áp dụng 2 quyết định. 2 phân công được cập nhật với ngày hiệu lực theo quyết định gốc.',
    );
    await until(el, '.card', () => page(el).includes('410'));
    expect(rowBox(el, 'Nguyễn Thị Lan')).toBeNull();
  });

  it('chạy lại lần hết thời gian qua tác vụ 202, MSG-01-14 biến mất', async () => {
    const { el } = await render();
    button(el, 'Chạy lại')!.click();
    await until(el, '.note', () => page(el).includes('Đã chạy lại dữ liệu lỗi'));
    await until(el, '.tag', () => page(el).includes('đã chạy lại'));
    expect(page(el)).not.toContain('Dữ liệu đã được lưu lại để chạy lại.');
    expect(button(el, 'Chạy lại')).toBeUndefined();
  });
});
