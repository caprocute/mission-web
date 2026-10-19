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
import { MyTasksPage, readSavedFilters } from './my-tasks.page';

/** WEB-030: SCR-0503 dựng trên dữ liệu giả lập, ẩn/hiện theo quyền, lưu bộ lọc. */
async function render(can: (k: string) => boolean = () => true) {
  localStorage.clear();
  TestBed.configureTestingModule({
    imports: [MyTasksPage],
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
  // trang có khối @defer (hộp cập nhật hàng loạt, WEB-052) nên phải biên dịch trước khi dựng
  await TestBed.compileComponents();
  const fixture = TestBed.createComponent(MyTasksPage);
  fixture.autoDetectChanges();
  // chờ chunk mock-registry nạp lười và phản hồi giả lập
  for (let i = 0; i < 20 && !fixture.nativeElement.querySelector('tbody tr .task-link'); i++) {
    await new Promise((r) => setTimeout(r, 20));
    await fixture.whenStable();
  }
  return fixture;
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('MyTasksPage (SCR-0503)', () => {
  it('hiện danh sách sắp theo hạn, số đếm bộ lọc nhanh và bảng xem nhanh', async () => {
    const f = await render();
    const el = f.nativeElement as HTMLElement;
    const rows = el.querySelectorAll('tbody tr');
    expect(rows.length).toBe(10);
    const codes = [...rows].map((r) => /NV-2026-Q4-\d{4}/.exec(text(r))?.[0]);
    expect(codes.slice(0, 3)).toEqual(['NV-2026-Q4-0005', 'NV-2026-Q4-0142', 'NV-2026-Q4-0003']);
    expect(text(el.querySelector('.quick'))).toContain('Chậm 2');
    expect(el.querySelector('vt-task-preview-panel')).not.toBeNull();
    expect(text(el.querySelector('.list-foot'))).toContain('1–10 / 10');
  });

  it('bấm "Chậm" lọc còn nhiệm vụ trễ hạn và lưu bộ lọc', async () => {
    const f = await render();
    const el = f.nativeElement as HTMLElement;
    const chip = [...el.querySelectorAll<HTMLButtonElement>('.chip')].find((b) =>
      text(b).startsWith('Chậm'),
    )!;
    chip.click();
    for (let i = 0; i < 20 && el.querySelectorAll('tbody tr').length !== 2; i++) {
      await new Promise((r) => setTimeout(r, 20));
      await f.whenStable();
    }
    expect(el.querySelectorAll('tbody tr').length).toBe(2);
    expect(chip.getAttribute('aria-pressed')).toBe('true');
    expect(readSavedFilters().filter).toBe('CHAM');
  });

  it('không có quyền đăng ký thì ẩn nút và khối đăng ký', async () => {
    const f = await render((k) => k !== 'SCR-0503.dang-ky');
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('vt-task-registration-panel')).toBeNull();
    expect(text(el.querySelector('.head-actions'))).not.toContain('Đăng ký nhiệm vụ');
  });
});

describe('readSavedFilters', () => {
  it('bỏ giá trị lạ, trả mặc định khi hỏng', () => {
    const s = (v: string) => ({ getItem: () => v });
    expect(readSavedFilters(s('{"filter":"CHAM","role":"X","kr":"kr-1"}'))).toEqual({
      filter: 'CHAM',
      role: null,
      group: null,
      kr: 'kr-1',
    });
    expect(readSavedFilters(s('{hỏng')).filter).toBe('TAT_CA');
  });
});
