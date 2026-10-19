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
import { AssignTaskPage } from './assign-task.page';

const DRAFT = '0192a0f0-0002-7000-8000-000000000201';

/** WEB-028: SCR-0501 dựng trên dữ liệu giả lập, danh sách kiểm tra của máy chủ, ẩn nút theo quyền. */
async function render(id: string | undefined, can: (k: string) => boolean = () => true) {
  TestBed.configureTestingModule({
    imports: [AssignTaskPage],
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
  const fixture = TestBed.createComponent(AssignTaskPage);
  if (id) fixture.componentRef.setInput('id', id);
  fixture.autoDetectChanges();
  // chờ khung soạn và kết quả kiểm tra (trễ 400 ms)
  for (let i = 0; i < 60 && !fixture.nativeElement.querySelector('.checks li'); i++) {
    await new Promise((r) => setTimeout(r, 20));
    await fixture.whenStable();
  }
  return fixture;
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const buttons = (el: HTMLElement) => [...el.querySelectorAll('.head .actions button')].map(text);

describe('AssignTaskPage (SCR-0501)', () => {
  it('mở nhiệm vụ nháp sinh từ kế hoạch: điền sẵn, kiểm tra chặn khi chưa có chủ trì', async () => {
    const el = (await render(DRAFT)).nativeElement as HTMLElement;
    expect(text(el.querySelector('.head'))).toContain('NV-2026-Q4-0201');
    expect((el.querySelector('#as-title') as HTMLInputElement).value).toContain(
      'Rà soát quy hoạch cấp uỷ',
    );
    const checks = text(el.querySelector('.checks'));
    expect(checks).toContain('Chưa chọn người chủ trì');
    expect(checks).toContain('Thuộc kế hoạch đã duyệt (phiên bản 2)');
    expect(checks).toContain('Nhiệm vụ gần giống NV-2026-Q3-0211');
    expect(el.querySelector('#as-reason')).not.toBeNull();
    const assign = [...el.querySelectorAll<HTMLButtonElement>('.head button')].find(
      (b) => text(b) === 'Giao việc',
    )!;
    expect(assign.disabled).toBe(true);
  });

  it('soạn mới: khung trống với người giao và người nghiệm thu mặc định', async () => {
    const el = (await render(undefined)).nativeElement as HTMLElement;
    expect(text(el.querySelector('.head'))).toContain('người giao Lê Minh Châu');
    expect(text(el.querySelector('.checks'))).toContain('Chọn dòng của kế hoạch đã duyệt');
  });

  it('ẩn nút theo quyền SCR-0501', async () => {
    let el = (await render(DRAFT, (k) => k !== 'SCR-0501.giao')).nativeElement as HTMLElement;
    expect(buttons(el)).toEqual(['Huỷ', 'Lưu nháp']);
    TestBed.resetTestingModule();
    el = (await render(DRAFT, (k) => k !== 'SCR-0501.soan' && k !== 'SCR-0501.trung'))
      .nativeElement as HTMLElement;
    expect(buttons(el)).toEqual(['Giao việc']);
    expect(text(el.querySelector('.similar'))).not.toContain('Xem nhiệm vụ gần giống');
  });
});
