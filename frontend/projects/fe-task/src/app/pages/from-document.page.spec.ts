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
} from '@vt/api-client';
import { TASK_CAN } from '../task-permissions';
import { FromDocumentPage } from './from-document.page';
import { SourceDocsPage } from './source-docs.page';

/** WEB-029: SCR-0502 và danh sách văn bản đến trên dữ liệu giả lập, ẩn/hiện theo ma trận quyền. */
async function render<T>(
  cmp: Type<T>,
  ready: string,
  can: (k: string) => boolean = () => true,
  id?: string,
) {
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
      { provide: TASK_CAN, useValue: can },
    ],
  });
  const fixture = TestBed.createComponent(cmp);
  if (id) fixture.componentRef.setInput('id', id);
  fixture.autoDetectChanges();
  await until(fixture.nativeElement, ready);
  return fixture.nativeElement as HTMLElement;
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 80; i++) {
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

describe('SourceDocsPage (văn bản đến)', () => {
  it('liệt kê văn bản chờ xử lý kèm MSG-05-06', async () => {
    const el = await render(SourceDocsPage, '.tbl tbody tr');
    expect(el.querySelectorAll('.tbl tbody tr')).toHaveLength(3);
    expect(text(el.querySelector('.tbl'))).toContain(
      'Văn bản 512-CV/ĐUK đã sinh 2 nhiệm vụ chờ xác nhận.',
    );
    expect(text(el.querySelector('.tbl'))).toContain('Không trích được');
  });
});

describe('FromDocumentPage (SCR-0502)', () => {
  it('hiện toàn văn, ba nhiệm vụ AI trích với trạng thái như Hình 8.4 và MSG-05-07', async () => {
    const el = await render(FromDocumentPage, 'vt-suggestion-card', () => true, 'sd-512');
    expect(el.querySelectorAll('.para')).toHaveLength(4);
    expect(el.querySelectorAll('.para-link')).toHaveLength(3);
    const tags = [...el.querySelectorAll('vt-suggestion-card p-tag')].map(text);
    expect(tags).toEqual(['Đã xác nhận', 'Chờ xác nhận', 'Có thể trùng']);
    expect(text(el.querySelector('.msg07'))).toBe(
      'Ô viền tím do AI điền; đồng chí kiểm tra và sửa nếu cần.',
    );
    expect(buttons(el, '.actions')).toEqual(['Bỏ qua văn bản', 'Xác nhận và giao 1 nhiệm vụ']);
    expect(el.querySelector('.card.selected .pick')?.textContent?.trim()).toBe('Nhiệm vụ 2');
    expect(el.querySelectorAll('.card.selected input.ai').length).toBeGreaterThan(3);
  });

  it('không có quyền xác nhận: ẩn mọi nút xử lý', async () => {
    const el = await render(
      FromDocumentPage,
      'vt-suggestion-card',
      (k) => k === 'SCR-0502.nhan-van-ban',
      'sd-512',
    );
    expect(el.querySelector('.actions button')).toBeNull();
    expect(el.querySelector('.bar')).toBeNull();
  });

  it('không có quyền xem văn bản đến: không tải văn bản', async () => {
    const el = await render(FromDocumentPage, 'p-message', () => false, 'sd-512');
    expect(text(el)).toContain('dành cho lãnh đạo đơn vị nhận văn bản');
    expect(el.querySelector('.paper')).toBeNull();
  });

  it('không trích được nhiệm vụ: hiện MSG-05-10', async () => {
    const el = await render(FromDocumentPage, '.manual', () => true, 'sd-33');
    expect(text(el)).toContain(
      'Không trích được nhiệm vụ từ văn bản này. Vui lòng tạo nhiệm vụ thủ công.',
    );
  });

  it('sửa ô thì ô mất viền tím; xác nhận tạo nhiệm vụ nháp; trùng thì bắt buộc lý do', async () => {
    const el = await render(FromDocumentPage, 'vt-suggestion-card', () => true, 'sd-512');
    click(el, 'Sửa');
    await until(el, '#sg-sg-512-3-prod:not([readonly])');
    const prod = el.querySelector('#sg-sg-512-3-prod:not([readonly])') as HTMLInputElement;
    expect(prod.classList).toContain('ai');
    prod.value = 'Báo cáo';
    prod.dispatchEvent(new Event('input'));
    await until(el, '#sg-sg-512-3-prod', (e) => !e.classList.contains('ai'));
    expect(el.querySelector('#sg-sg-512-3-prod')?.classList).not.toContain('ai');
    click(el, 'Xác nhận nhiệm vụ 2');
    await until(el, '.actions', (e) => text(e).includes('giao 2 nhiệm vụ'));
    expect(buttons(el, '.actions')).toContain('Xác nhận và giao 2 nhiệm vụ');
    // nhiệm vụ 3 được chọn tiếp, có nhiệm vụ gần giống
    expect(text(el.querySelector('.dup'))).toContain('NV-2026-Q4-0098');
    click(el, 'Xác nhận nhiệm vụ 3');
    await until(el, '.msg.err');
    expect(text(el.querySelector('.msg.err'))).toContain('Vui lòng gộp hoặc ghi lý do');
  });
});
