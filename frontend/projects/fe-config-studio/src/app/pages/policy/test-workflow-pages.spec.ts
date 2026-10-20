import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
  mockStepUp,
} from '@vt/api-client';
import { PermissionKey } from '@vt/auth';
import { resetMockData } from '../../../../../api-client/src/mock/mock-registry';
import { STUDIO_ACCESS } from '../../studio-permissions';
import { STUDIO_STEP_UP } from '../../studio-step-up';
import { PolicyWorkspace } from './policy-workspace';
import { TestCasePage } from './test-case.page';
import { WorkflowPage } from './workflow.page';

/** WEB-046: SCR-1503, SCR-1504 trên dữ liệu giả lập; quyền theo ma trận wiki/15a. */
const P_DANG = '0192a1b0-3c4d-7e5f-8a9b-000000004001';
const P_VC = '0192a1b0-3c4d-7e5f-8a9b-000000004003';

const AUTHOR: PermissionKey[] = [
  'SCR-1501.soan',
  'SCR-1501.gui-duyet',
  'SCR-1502.soan',
  'SCR-1503.chay',
  'SCR-1504.cau-hinh',
];
const DECIDER: PermissionKey[] = ['SCR-1503.xem-duyet', 'SCR-1503.ky-duyet'];

async function render<T>(
  cmp: Type<T>,
  keys: PermissionKey[],
  pack: string,
  v: string,
  ready: string,
) {
  TestBed.configureTestingModule({
    imports: [cmp],
    providers: [
      provideRouter([]),
      provideNoopAnimations(),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      {
        provide: STUDIO_ACCESS,
        useValue: {
          can: (k: PermissionKey) => keys.includes(k),
          author: (k: PermissionKey) => keys.includes(k),
        },
      },
      {
        provide: STUDIO_STEP_UP,
        useValue: async () => {
          mockStepUp();
          return true;
        },
      },
      PolicyWorkspace,
    ],
  });
  TestBed.inject(PolicyWorkspace).ref.set({ id: pack, v });
  const fixture = TestBed.createComponent(cmp);
  fixture.autoDetectChanges();
  await until(fixture.nativeElement, ready);
  return fixture.nativeElement as HTMLElement;
}

async function until(
  el: ParentNode,
  sel: string,
  test: (e: Element) => boolean = () => true,
  tries = 300,
) {
  for (let i = 0; i < tries; i++) {
    const e = el.querySelector(sel);
    if (e && test(e)) return e;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw new Error(`Không thấy ${sel}`);
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const button = (el: ParentNode, id: string) =>
  el.querySelector(`[data-testid=${id}] button`) as HTMLButtonElement;

describe('Config Studio · Test case (SCR-1503)', () => {
  beforeEach(() => resetMockData());

  it('người soạn: lần lỗi #13 có MSG-15-06, chi tiết lỗi kèm thay đổi cấu hình, lọc ca, chạy lại đạt', async () => {
    const el = await render(TestCasePage, AUTHOR, P_DANG, '2.1.0', '[data-testid=cases]');
    expect(text(el.querySelector('[data-testid=suite]'))).toContain('Bộ ca mẫu gồm 10 ca');
    expect(text(el.querySelector('[data-testid=msg-15-06]'))).toBe(
      'Lần chạy #13: 9/10 ca đạt. Phiên bản chưa đạt kiểm thử nên chưa thể gửi phê duyệt.',
    );
    const fail = await until(el, '[data-testid=fail-detail]', (e) => text(e).includes('TC-077'));
    expect(text(fail)).toContain('Tiến độ = 0,9');
    expect(text(fail)).toContain('Loại ngày tính chậm: ngày làm việc theo lịch của đơn vị');
    (el.querySelector('[data-filter=FAIL]') as HTMLButtonElement).click();
    await until(el, '[data-testid=cases] tbody', (e) => e.querySelectorAll('tr').length === 1);
    expect(el.querySelector('[data-case=TC-077]')).not.toBeNull();
    expect(button(el, 'submit').disabled).toBe(true);
    expect(el.querySelector('[data-testid=approve]')).toBeNull();

    button(el, 'rerun').click();
    await until(el, '[data-testid=card-run]', (e) => text(e).includes('#14'), 400);
    expect(text(el.querySelector('[data-testid=card-run]'))).toContain('10/10');
    expect(el.querySelector('[data-testid=msg-15-06]')).toBeNull();
    await until(el, '[data-testid=fail-detail] h2', (e) => text(e) === 'Lỗi ở lần #13 (đã sửa)');
    await until(el, '[data-testid=card-impact]', (e) => text(e).includes('So với bản 2'));
    await until(el, '[data-testid=submit] button', (b) => !(b as HTMLButtonElement).disabled);
  });

  it('người soạn: thêm ca kiểm thử chờ ký duyệt', async () => {
    const el = await render(TestCasePage, AUTHOR, P_DANG, '2.1.0', '[data-testid=cases]');
    button(el, 'add-case').click();
    const code = (await until(document, '[data-testid=case-code]')) as HTMLInputElement;
    code.value = 'TC-149';
    code.dispatchEvent(new Event('input'));
    const desc = document.querySelector('[data-testid=case-desc]') as HTMLTextAreaElement;
    desc.value = 'Cán bộ biệt phái giữa kỳ';
    desc.dispatchEvent(new Event('input'));
    const level = document.querySelector('[data-testid=case-level]') as HTMLSelectElement;
    level.value = 'HTNV';
    level.dispatchEvent(new Event('change'));
    await until(
      document,
      '[data-testid=case-save] button',
      (b) => !(b as HTMLButtonElement).disabled,
    );
    button(document, 'case-save').click();
    const notice = await until(el, '[data-testid=notice]');
    expect(text(notice)).toContain('Đã thêm ca TC-149');
    await until(el, '[data-testid=suite]', (e) => text(e).includes('1 ca mới đang chờ ký duyệt'));
  });

  it('người phê duyệt: chỉ đọc, ký số phê duyệt rồi nhận MSG-15-08', async () => {
    const el = await render(TestCasePage, DECIDER, P_VC, '1.1.0', '[data-testid=cases]');
    expect(el.querySelector('[data-testid=rerun]')).toBeNull();
    expect(el.querySelector('[data-testid=add-case]')).toBeNull();
    expect(text(el.querySelector('[data-testid=readonly]'))).toContain('trước khi ký phê duyệt');
    expect(text(el.querySelector('[data-testid=card-impact]'))).toContain('Không áp dụng');
    button(el, 'approve').click();
    await until(document, '[data-testid=approve-confirm] button');
    button(document, 'approve-confirm').click();
    const notice = await until(
      el,
      '[data-testid=notice]',
      (e) => text(e).includes('đã được phát hành'),
      400,
    );
    expect(text(notice)).toBe(
      'Phiên bản 1.1 đã được phát hành, áp dụng từ kỳ Quý I/2027. Các kỳ đã khoá tiếp tục dùng phiên bản cũ.',
    );
    expect(el.querySelector('[data-testid=approve]')).toBeNull();
  });
});

describe('Config Studio · Luồng duyệt (SCR-1504)', () => {
  beforeEach(() => resetMockData());

  const select = (el: ParentNode, sel: string, v: string) => {
    const s = el.querySelector(sel) as HTMLSelectElement;
    s.value = s.options[[...s.options].findIndex((o) => o.value.endsWith(v))].value;
    s.dispatchEvent(new Event('change'));
  };

  it('nạp mẫu đang dùng, mô phỏng rồi lưu; bước ký bắt buộc không bỏ được', async () => {
    const el = await render(WorkflowPage, AUTHOR, P_DANG, '2.1.0', '[data-testid=wf-levels-table]');
    await until(
      el,
      '[data-template=duyet-ho-so-danh-gia]',
      (e) => e.getAttribute('aria-checked') === 'true',
    );
    await until(
      el,
      '[data-testid=wf-period]',
      (e) => (e as HTMLSelectElement).value === 'p-2027q1',
    );
    expect(el.querySelectorAll('[data-testid=wf-levels-table] tbody tr').length).toBe(4);
    const sign = el.querySelector('[data-step=THAM_DINH] input[type=checkbox]') as HTMLInputElement;
    expect(sign.disabled).toBe(true);
    expect(sign.checked).toBe(true);
    expect(text(el.querySelector('[data-testid=msg-15-10]'))).toContain(
      'chỉ được cấu hình bằng mẫu',
    );
    expect(button(el, 'save-binding').disabled).toBe(true);

    select(el, '[data-testid=wf-levels]', '3');
    await until(
      el,
      '[data-testid=wf-levels-table] tbody',
      (e) => e.querySelectorAll('tr').length === 3,
    );
    expect(el.querySelector('[data-testid=skip-DE_XUAT]')).toBeNull();

    button(el, 'simulate').click();
    const lint = await until(el, '[data-testid=wf-lint]');
    expect(text(lint)).toContain('Kiểm tra cấu trúc luồng: đạt');
    expect(text(lint)).toContain('42/42 kiểm thử của mẫu đạt');
    await until(el, '[data-testid=save-binding] button', (b) => !(b as HTMLButtonElement).disabled);
    button(el, 'save-binding').click();
    const notice = await until(el, '[data-testid=notice]');
    expect(text(notice)).toContain('Đã lưu tham số luồng duyệt');
  });

  it('kiểm sơ bộ: thời hạn ngoài 1–15 ngày và điều kiện bỏ qua sai cú pháp chặn mô phỏng', async () => {
    const el = await render(WorkflowPage, AUTHOR, P_DANG, '2.1.0', '[data-testid=skip-DE_XUAT]');
    await until(el, '[data-testid=simulate] button', (b) => !(b as HTMLButtonElement).disabled);
    const due = el.querySelector('[data-testid=wf-due]') as HTMLInputElement;
    due.value = '20';
    due.dispatchEvent(new Event('input'));
    await until(el, '[data-testid=simulate] button', (b) => (b as HTMLButtonElement).disabled);
    expect(text(el)).toContain('Thời hạn mỗi bước phải từ 1 đến 15 ngày làm việc.');
    due.value = '3';
    due.dispatchEvent(new Event('input'));
    const skip = el.querySelector('[data-testid=skip-DE_XUAT]') as HTMLTextAreaElement;
    skip.value = "nguoi_duoc_danh_gia.chuc_danh in ['CHUYEN_VIEN'";
    skip.dispatchEvent(new Event('input'));
    await until(el, '[data-testid=skip-DE_XUAT]', (e) => e.getAttribute('aria-invalid') === 'true');
    expect(button(el, 'simulate').disabled).toBe(true);
  });

  it('khác người soạn: chỉ xem', async () => {
    const el = await render(
      WorkflowPage,
      ['SCR-1504.cau-hinh'],
      P_VC,
      '1.1.0',
      '[data-testid=wf-hint]',
    );
    expect(el.querySelector('[data-testid=simulate]')).toBeNull();
    expect(text(el.querySelector('[data-testid=wf-hint]'))).toContain('chỉ xem');
  });
});
