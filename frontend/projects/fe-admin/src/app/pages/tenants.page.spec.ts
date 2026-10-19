import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { ADMIN_CAN } from '../admin-permissions';
import { TenantsPage } from './tenants.page';

/** WEB-016: SCR-0101 trên dữ liệu giả lập, ẩn hiện theo quyền, lọc, phân trang con trỏ, MSG. */
async function render(can: (k: string) => boolean = () => true, pageQuery = '') {
  TestBed.configureTestingModule({
    imports: [TenantsPage],
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => pageQuery },
      },
      { provide: ADMIN_CAN, useValue: can },
    ],
  });
  const fixture = TestBed.createComponent(TenantsPage);
  fixture.autoDetectChanges();
  await until(fixture.nativeElement, pageQuery ? 'p-message' : '.tbl tbody .name');
  await until(fixture.nativeElement, pageQuery ? 'p-message' : '.cells tbody tr');
  return fixture;
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 80; i++) {
    const e = el.querySelector(sel);
    if (e && test(e)) return;
    await new Promise((r) => setTimeout(r, 20));
  }
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const names = (el: HTMLElement) => [...el.querySelectorAll('.tbl tbody .name')].map(text);

describe('TenantsPage (SCR-0101)', () => {
  it('thẻ tổng quan, bảng 8 dòng, trạng thái khởi tạo n/6, MSG-01-01 và MSG-01-02', async () => {
    const el = (await render()).nativeElement as HTMLElement;
    expect(text(el.querySelector('.cards'))).toContain('tối đa 5.000 đơn vị');
    expect(names(el)).toHaveLength(8);
    expect(text(el.querySelector('.tbl tbody'))).toContain('Đang khởi tạo 4/6');
    expect(text(el.querySelector('.notices'))).toContain(
      'Đơn vị Tỉnh Thái Nguyên đang khởi tạo, đến bước 4/6.',
    );
    expect(text(el.querySelector('aside'))).toContain(
      'Cụm máy chủ CELL-HCM đã sử dụng 91% dung lượng thiết kế. Đề nghị lập kế hoạch mở cụm máy chủ mới.',
    );
    expect(text(el.querySelector('.pager'))).toContain('Hiển thị 1–8 trong 35');
  });

  it('trang sau và trang trước theo con trỏ', async () => {
    const f = await render();
    const el = f.nativeElement as HTMLElement;
    const first = names(el)[0];
    (
      el.querySelector(
        '[aria-label="Trang sau"] button, button[aria-label="Trang sau"]',
      ) as HTMLElement
    ).click();
    await until(el, '.pager', (e) => text(e).includes('9–16'));
    expect(names(el)[0]).not.toBe(first);
    (
      el.querySelector(
        '[aria-label="Trang trước"] button, button[aria-label="Trang trước"]',
      ) as HTMLElement
    ).click();
    await until(el, '.pager', (e) => text(e).includes('1–8'));
    expect(names(el)[0]).toBe(first);
  });

  it('ẩn Xuất danh sách và Khởi tạo tenant khi không có quyền', async () => {
    const el = (await render((k) => k === 'SCR-0101.xem')).nativeElement as HTMLElement;
    expect(text(el.querySelector('.actions'))).toBe('');
  });

  it('hiện đủ nút khi là quản trị nền tảng', async () => {
    const el = (await render()).nativeElement as HTMLElement;
    expect(text(el.querySelector('.actions'))).toContain('Xuất danh sách');
    expect(text(el.querySelector('.actions'))).toContain('Khởi tạo đơn vị triển khai');
  });

  it('máy chủ trả 403 thì hiện thông báo không có quyền', async () => {
    const el = (await render(() => true, '?mock-error=403')).nativeElement as HTMLElement;
    expect(text(el)).toContain('Đồng chí không có quyền thực hiện thao tác này.');
  });
});
