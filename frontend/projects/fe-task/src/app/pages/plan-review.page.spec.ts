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
import { PlanReviewPage } from './plan-review.page';
import { PLAN_STEP_UP } from './plan/step-up';

/** WEB-027: SCR-0403 trên dữ liệu giả lập: so sánh, trả lại, MFA bước lên, ký số, ẩn/hiện theo quyền. */
async function render(
  ready: string,
  can: (k: string) => boolean = () => true,
  query: Record<string, string> = { v: '2' },
) {
  // lần đầu giả như phiên đã đạt MFA nhưng máy chủ vẫn đòi (401), lần sau mới đánh dấu đã MFA
  let calls = 0;
  const stepUp = vi.fn(async () => {
    if (calls++ > 0) (await import('@vt/api-client')).mockStepUp();
    return true;
  });
  TestBed.configureTestingModule({
    imports: [PlanReviewPage],
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      { provide: TASK_CAN, useValue: can },
      { provide: PLAN_STEP_UP, useValue: stepUp },
      { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap(query)) } },
    ],
  });
  const fixture = TestBed.createComponent(PlanReviewPage);
  fixture.componentRef.setInput('id', 'plan-2026-q4');
  fixture.autoDetectChanges();
  await until(fixture.nativeElement, ready);
  return { el: fixture.nativeElement as HTMLElement, stepUp };
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 250; i++) {
    const e = el.querySelector(sel);
    if (e && test(e)) return;
    await new Promise((r) => setTimeout(r, 20));
  }
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const buttons = (el: HTMLElement, scope = '') =>
  [...el.querySelectorAll(`${scope} button`)].map(text).filter(Boolean);
const click = (el: HTMLElement, label: string) =>
  ([...el.querySelectorAll('button')].find((b) => text(b) === label) as HTMLButtonElement).click();

describe('PlanReviewPage (SCR-0403)', () => {
  beforeEach(async () => (await import('@vt/api-client')).resetMockApi());

  it('tóm tắt và bảng khác biệt như Hình 7.14, chỉ hiện thay đổi hoặc toàn bộ', async () => {
    const { el } = await render('.tbl tbody tr');
    expect(text(el.querySelector('h1'))).toBe('Phê duyệt điều chỉnh kế hoạch Quý IV/2026');
    expect([...el.querySelectorAll('.card .big')].map(text)).toEqual([
      '1',
      '1',
      '1',
      '70 →thành 70',
    ]);
    const rows = [...el.querySelectorAll('.tbl tbody tr')];
    expect(rows.map((r) => r.className)).toEqual(['THEM', 'SUA', 'BO']);
    expect(text(rows[1])).toContain('15 hồ sơ');
    expect(text(rows[1])).toContain('18 hồ sơ');
    expect(text(rows[1])).toContain('Chi bộ 2, 7 bổ sung hồ sơ');
    expect(text(el.querySelector('.meta'))).toContain('1 / tối đa 2 trong quý');
    (
      [
        ...el.querySelectorAll(
          'p-selectbutton [role=radio], p-selectbutton button, p-togglebutton',
        ),
      ].find((b) => text(b) === 'Toàn bộ') as HTMLElement
    ).click();
    await until(el, '.tbl tbody tr', () => el.querySelectorAll('.tbl tbody tr').length === 9);
    expect(el.querySelectorAll('.tbl tbody tr')).toHaveLength(9);
  });

  it('trả lại bắt buộc ý kiến, sau đó kế hoạch thành TRA_LAI', async () => {
    const { el } = await render('.dec-actions');
    click(el, 'Trả lại');
    await until(el, '#rv-comment-err');
    expect(text(el.querySelector('#rv-comment-err'))).toContain('Ghi ý kiến');
    const ta = el.querySelector('#rv-comment') as HTMLTextAreaElement;
    ta.value = 'Làm rõ sản phẩm của việc thêm mới';
    ta.dispatchEvent(new Event('input'));
    click(el, 'Trả lại');
    await until(el, '.msg.warn', (e) => text(e).includes('trả lại'));
    expect(text(el.querySelector('.msg.ok'))).toContain('Đã trả lại kế hoạch');
    expect(el.querySelector('.dec-actions')).toBeNull();
  });

  it(
    'duyệt: yêu cầu MFA bước lên, chờ MySign (MSG-04-09) rồi báo MSG-04-10',
    { timeout: 15_000 },
    async () => {
      const { el, stepUp } = await render('.dec-actions');
      click(el, 'Duyệt và ký số');
      await until(el, '.msg.warn[role=alert]');
      expect(text(el.querySelector('.msg.warn[role=alert]'))).toContain('xác thực bước lên');
      click(el, 'Xác thực MFA');
      await until(el, '.msg.info[role=status]');
      expect(stepUp).toHaveBeenCalledTimes(2);
      expect(text(el.querySelector('.msg.info[role=status]'))).toContain(
        'Vui lòng xác nhận ký trên ứng dụng MySign trong 120 giây.',
      );
      await until(el, '.msg.ok[role=status]');
      expect(text(el.querySelector('.msg.ok[role=status]'))).toBe(
        'Đã duyệt và ký số kế hoạch phiên bản 2. Hệ thống đang sinh 1 nhiệm vụ mới.',
      );
      await until(el, '.msg.ok:not([role])');
      expect(el.querySelector('.dec-actions')).toBeNull();
    },
  );

  it('ẩn nút theo ma trận quyền; không có quyền xem thì chỉ báo thiếu quyền', async () => {
    const { el } = await render('.dec-actions', (k) => k !== 'SCR-0403.duyet-ky');
    expect(buttons(el, '.dec-actions')).toEqual(['Trả lại']);
    expect(el.querySelector('.methods')).toBeNull();
    TestBed.resetTestingModule();
    const { el: none } = await render('.msg', (k) => k !== 'SCR-0403.so-sanh');
    expect(text(none.querySelector('.msg'))).toContain('không có quyền');
    expect(none.querySelector('.tbl')).toBeNull();
  });
});
