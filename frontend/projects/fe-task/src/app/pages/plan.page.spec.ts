import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { of } from 'rxjs';
import { TASK_CAN } from '../task-permissions';
import { PlanPage } from './plan.page';

/** WEB-026: SCR-0402 trên dữ liệu giả lập, kiểm tra BR-04-02..07, ẩn/hiện theo ma trận quyền. */
async function render(
  ready: string,
  can: (k: string) => boolean = () => true,
  query: Record<string, string> = {},
) {
  TestBed.configureTestingModule({
    imports: [PlanPage],
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      { provide: TASK_CAN, useValue: can },
      { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap(query)) } },
    ],
  });
  const fixture = TestBed.createComponent(PlanPage);
  fixture.autoDetectChanges();
  await until(fixture.nativeElement, ready);
  return fixture.nativeElement as HTMLElement;
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 100; i++) {
    const e = el.querySelector(sel);
    if (e && test(e)) return;
    await new Promise((r) => setTimeout(r, 20));
  }
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const buttons = (el: HTMLElement, scope = '') =>
  [...el.querySelectorAll(`${scope} button`)].map(text).filter(Boolean);

describe('PlanPage (SCR-0402)', () => {
  it('mở kế hoạch đang soạn, đánh dấu dòng có lỗi chặn và khoá Gửi duyệt', async () => {
    const el = await render('.checks li');
    await until(el, '.checks li', () => el.querySelectorAll('.checks li').length > 3);
    expect(text(el.querySelector('h1'))).toContain('Quý I/2027');
    expect(el.querySelectorAll('.tbl tbody tr')).toHaveLength(6);
    expect(el.querySelectorAll('.tbl tr.bad')).toHaveLength(1);
    const checks = [...el.querySelectorAll('.checks li')].map(text);
    expect(checks.some((c) => c.includes('KPI mơ hồ ở dòng 6'))).toBe(true);
    expect(checks.some((c) => c.includes('KR 2.1 chưa có nhiệm vụ'))).toBe(true);
    const submit = [...el.querySelectorAll('.actions button')].find(
      (b) => text(b) === 'Gửi duyệt',
    ) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    expect(text(el.querySelector('.banner'))).toContain('Hạn phê duyệt kế hoạch');
  });

  it('không có quyền xem thì không gọi dữ liệu, chỉ báo thiếu quyền', async () => {
    const el = await render('.msg', (k) => k !== 'SCR-0402.xem');
    expect(text(el.querySelector('.msg'))).toContain('không có quyền xem');
    expect(el.querySelector('.tbl')).toBeNull();
  });

  it('chỉ có quyền xem thì không thấy nút lập, lưu, gửi duyệt', async () => {
    const el = await render('.tbl tbody tr', (k) => k === 'SCR-0402.xem');
    const all = buttons(el);
    expect(all).not.toContain('Lưu nháp');
    expect(all).not.toContain('Gửi duyệt');
    expect(all).not.toContain('Thêm từ danh mục');
  });

  it('kỳ chờ duyệt chỉ đọc, có lối sang màn phê duyệt khi được phép', async () => {
    const el = await render('.tbl tbody tr', () => true, { plan: 'plan-2026-q4' });
    expect(text(el.querySelector('.sub'))).toContain('CHO_DUYET');
    expect(buttons(el, '.actions')).toEqual(['Xem xét và duyệt']);
    TestBed.resetTestingModule();
    const ro = await render('.tbl tbody tr', (k) => !k.startsWith('SCR-0403'), {
      plan: 'plan-2026-q4',
    });
    expect(buttons(ro, '.actions')).toEqual([]);
  });
});
