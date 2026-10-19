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
  mockStepUp,
  resetMockApi,
} from '@vt/api-client';
import { OVERSIGHT_CAN, OVERSIGHT_USER_ID } from '../../oversight-permissions';
import { OVERSIGHT_STEP_UP } from '../../step-up';
import { ComplaintFormPage } from './complaint-form.page';
import { ComplaintHandlePage } from './complaint-handle.page';
import { MyComplaintsPage } from './my-complaints.page';

let instance: unknown;

/** WEB-042: SCR-1201, SCR-1202 trên dữ liệu giả lập. */
async function render<T>(
  cmp: Type<T>,
  inputs: Record<string, string> = {},
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
      { provide: OVERSIGHT_USER_ID, useValue: () => 'dev-user' },
      {
        provide: OVERSIGHT_STEP_UP,
        useValue: () => {
          mockStepUp();
          return Promise.resolve(true);
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(cmp);
  for (const [k, v] of Object.entries(inputs)) fixture.componentRef.setInput(k, v);
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

async function press(el: HTMLElement, label: string) {
  const b = (await until(
    el,
    'button',
    (e) => text(e) === label && !(e as HTMLButtonElement).disabled,
  )) as HTMLButtonElement;
  b.click();
}

/** Giao diện cho cán bộ không hiện mã quy tắc, thông báo, mức mật hay thuật ngữ kỹ thuật (YC-PT-10). */
const TECH = /\b(BR|MSG|QT|SCR|TL)-\d|\bC[23]\b|Policy|OPA|MFA|SHA-256|người dùng hiện tại/;

describe('MyComplaintsPage (SCR-1201)', () => {
  it('kết quả còn hạn và quá hạn (chỉ bảo lưu); bảng chỉ có khiếu nại của tôi', async () => {
    const el = await render(MyComplaintsPage);
    await until(el, '.tbl tbody tr', has('KN-2026-0038'));
    const cards = [...el.querySelectorAll('.results li')];
    expect(cards.length).toBe(2);
    expect(text(cards[0])).toContain('Soạn khiếu nại');
    expect(text(cards[1])).toContain('Đã quá thời hạn khiếu nại');
    expect(text(cards[1])).toContain('Gửi ý kiến bảo lưu');
    const codes = [...el.querySelectorAll('.tbl tbody tr')].map((r) => text(r.querySelector('td')));
    expect(codes.sort()).toEqual(['KN-2026-0038', 'KN-2026-0050']);
    expect(text(el)).not.toMatch(TECH);
  });
});

describe('ComplaintFormPage (SCR-1201)', () => {
  it('ký số và gửi bản nháp: báo đã gửi kèm hạn trả lời', async () => {
    const el = await render(ComplaintFormPage, { id: 'kn-2026-0050' });
    await until(el, '#kn-content');
    expect(text(el)).not.toMatch(TECH);
    await press(el, 'Ký số và gửi');
    await until(el, '.err', has('Nêu đề nghị cụ thể'));
    const req = el.querySelector('#kn-request') as HTMLTextAreaElement;
    req.value = 'Đề nghị xem xét lại hệ số B của nhiệm vụ then chốt 2.';
    req.dispatchEvent(new Event('input'));
    await press(el, 'Ký số và gửi');
    const ok = await until(el, 'p-message', has('Đã gửi khiếu nại KN-2026-0050'));
    expect(text(ok)).toContain('Hạn trả lời');
  });

  it('văn bản trả lời: hiện điều chỉnh theo tên cấu phần, không hiện khoá kỹ thuật', async () => {
    const el = await render(ComplaintFormPage, { id: 'kn-2026-0038' });
    await until(el, 'p-message', has('đã có văn bản trả lời'));
    expect(text(el)).not.toMatch(/NV-THEN-CHOT|\.C:/);
    expect(text(el)).not.toMatch(TECH);
  });
});

describe('ComplaintHandlePage (SCR-1202)', () => {
  it('tách vai trò: tiếp nhận khiếu nại về hồ sơ mình đã tham gia bị từ chối', async () => {
    const el = await render(ComplaintHandlePage, { id: 'kn-2026-0042' });
    await until(el, 'h2', has('KN-2026-0042'));
    expect(text(el)).not.toMatch(TECH);
    await press(el, 'Tiếp nhận');
    const err = await until(el, 'p-message', has('không thể xử lý khiếu nại này'));
    expect(text(err)).not.toMatch(TECH);
  });

  it('tính thử điều chỉnh: điểm và xếp loại theo tên đầy đủ, chưa ghi nhận', async () => {
    const el = await render(ComplaintHandlePage, { id: 'kn-2026-0040' });
    await until(el, '#kl-outcome');
    // chọn "Chấp nhận một phần" như khi chọn trong danh sách thả xuống
    (instance as { pickOutcome(o: string): void }).pickOutcome('CHAP_NHAN_MOT_PHAN');
    await until(el, '#adj-on-0');
    (el.querySelector('#adj-on-0') as HTMLInputElement).click();
    await until(el, '#adj-new-0');
    const v = el.querySelector('#adj-new-0') as HTMLInputElement;
    v.value = '1';
    v.dispatchEvent(new Event('input'));
    await press(el, 'Tính thử');
    const sim = await until(el, '.sim');
    expect(text(sim)).toContain('bộ tiêu chí đánh giá đã áp dụng cho kỳ');
    expect(text(sim)).toMatch(/Hoàn thành/);
    expect(text(sim)).toContain('chưa được ghi nhận');
    expect(text(el)).not.toMatch(TECH);
  });
});
