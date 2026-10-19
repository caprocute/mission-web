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
import { TaskDetailPage, coefficient } from './task-detail.page';

const ID = (n: string) => `0192a0f0-0001-7000-8000-00000000${n.padStart(4, '0')}`;

/** WEB-031: SCR-0601 dựng trên dữ liệu giả lập, ẩn/hiện nút theo quyền và vai trò, thông báo MSG-06-03. */
async function render(id: string, can: (k: string) => boolean = () => true) {
  TestBed.configureTestingModule({
    imports: [TaskDetailPage],
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
  const fixture = TestBed.createComponent(TaskDetailPage);
  fixture.componentRef.setInput('id', id);
  fixture.autoDetectChanges();
  for (let i = 0; i < 20 && !fixture.nativeElement.querySelector('.kpis'); i++) {
    await new Promise((r) => setTimeout(r, 20));
    await fixture.whenStable();
  }
  return fixture;
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const buttons = (el: HTMLElement) => [...el.querySelectorAll('.actions button')].map(text);

describe('TaskDetailPage (SCR-0601)', () => {
  // jsdom không có ResizeObserver mà p-tablist dùng để cuộn thẻ
  beforeAll(() => {
    const noop = () => undefined;
    globalThis.ResizeObserver ??= class {
      observe = noop;
      unobserve = noop;
      disconnect = noop;
    } as unknown as typeof ResizeObserver;
  });

  it('hiện đầu trang, bốn thẻ chỉ số và số đếm trên các thẻ', async () => {
    const el = (await render(ID('157'))).nativeElement as HTMLElement;
    expect(text(el.querySelector('.head'))).toContain('NV-2026-Q4-0157');
    expect(text(el.querySelector('.head'))).toContain('Danh mục TC-DV-01');
    const kpis = [...el.querySelectorAll('.kpi')].map(text);
    expect(kpis).toHaveLength(4);
    expect(kpis[0]).toContain('45%');
    expect(kpis[2]).toContain('8/18');
    expect(kpis[2]).toContain('3 chờ nghiệm thu');
    expect(text(el.querySelector('p-tablist'))).toContain('Tiến độ (4)');
    expect(text(el.querySelector('p-tablist'))).toContain('Điểm nghẽn (1)');
    expect(buttons(el)).toEqual([
      'Báo điểm nghẽn',
      'Đề nghị gia hạn',
      'Nộp sản phẩm',
      'Cập nhật tiến độ',
    ]);
  });

  it('ẩn nút theo quyền và vai trò: người phối hợp không gửi đề nghị, chỉ ghi nhật ký', async () => {
    let el = (await render(ID('157'), (k) => k !== 'SCR-0602.gui' && k !== 'SCR-0601.nop-sp'))
      .nativeElement as HTMLElement;
    expect(buttons(el)).toEqual(['Báo điểm nghẽn', 'Cập nhật tiến độ']);
    TestBed.resetTestingModule();
    el = (await render(ID('3'))).nativeElement as HTMLElement;
    expect(buttons(el)).not.toContain('Đề nghị gia hạn');
    expect(buttons(el)).toContain('Ghi nhật ký');
  });

  it('nhiệm vụ quá hạn hiện MSG-06-03 với hệ số C', async () => {
    const el = (await render(ID('142'))).nativeElement as HTMLElement;
    expect(text(el)).toMatch(
      /Nhiệm vụ đã quá hạn \d+ ngày làm việc\. Hệ số tiến độ dự kiến C = \d,\d\d\./,
    );
  });

  it('nhiệm vụ đã hoàn thành không còn nút thao tác', async () => {
    const el = (await render(ID('5'))).nativeElement as HTMLElement;
    expect(buttons(el)).toEqual([]);
  });

  it('mã không tồn tại báo không tìm thấy', async () => {
    const f = await render(ID('9999'));
    for (let i = 0; i < 10 && !f.nativeElement.querySelector('p-message'); i++)
      await new Promise((r) => setTimeout(r, 20));
    expect(text(f.nativeElement)).toContain('Không tìm thấy nhiệm vụ.');
  });

  it('hệ số C theo BR-06-01', () => {
    expect(coefficient(0)).toBe(1);
    expect(coefficient(3)).toBe(0.85);
    expect(coefficient(30)).toBe(0);
  });
});
