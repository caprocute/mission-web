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
import { TASK_CAN } from '../task-permissions';
import { CatalogPage } from './catalog.page';

/** WEB-025: SCR-0401 dựng trên dữ liệu giả lập, ẩn/hiện chức năng theo quyền, phân trang con trỏ. */
async function render(can: (k: string) => boolean = () => true) {
  TestBed.configureTestingModule({
    imports: [CatalogPage],
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      { provide: TASK_CAN, useValue: can },
    ],
  });
  const fixture = TestBed.createComponent(CatalogPage);
  fixture.autoDetectChanges();
  await until(fixture.nativeElement, '.tbl tbody .code');
  return fixture;
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 60; i++) {
    const e = el.querySelector(sel);
    if (e && test(e)) return;
    await new Promise((r) => setTimeout(r, 20));
  }
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('CatalogPage (SCR-0401)', () => {
  it('hiện phiên bản đang áp dụng, tổng số, bảng theo Bảng 3 và thẻ chi tiết dòng đầu', async () => {
    const el = (await render()).nativeElement as HTMLElement;
    expect(text(el.querySelector('.head'))).toContain('phiên bản 3 (áp dụng từ Quý IV/2026)');
    expect(text(el.querySelector('.total'))).toBe('15 công việc · 3 vị trí việc làm');
    expect(el.querySelectorAll('.tbl tbody tr')).toHaveLength(10);
    expect(text(el.querySelector('aside .box'))).toContain('TC-DV-01');
    expect(text(el.querySelector('aside .box'))).toContain('hệ số 1');
    expect(text(el.querySelector('section[aria-labelledby=imp-title]'))).toContain(
      '1.221 dòng hợp lệ',
    );
  });

  it('trang sau dùng con trỏ của máy chủ', async () => {
    const f = await render();
    const el = f.nativeElement as HTMLElement;
    (el.querySelector('button[aria-label="Trang sau"]') as HTMLButtonElement).click();
    await until(el, '.pager', (e) => text(e).startsWith('11–15'));
    expect(text(el.querySelector('.pager'))).toContain('11–15 / 15');
  });

  it('chỉ xem: ẩn thêm, sửa, nhập Excel, nhân bản, đưa vào kế hoạch', async () => {
    const el = (await render((k) => k === 'SCR-0401.xem')).nativeElement as HTMLElement;
    expect(el.querySelector('.head .actions')).toBeNull();
    expect([...el.querySelectorAll('aside button')].map(text)).not.toContain('Sửa');
    expect(el.querySelector('section[aria-labelledby=imp-title]')).toBeNull();
    expect(el.querySelector('.tbl .chk')).toBeNull();
  });
});
