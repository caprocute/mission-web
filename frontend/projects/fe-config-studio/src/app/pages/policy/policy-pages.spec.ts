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
import { resetMockData } from '../../../../../api-client/src/mock/mock-registry';
import { STUDIO_ACCESS } from '../../studio-permissions';
import { DecisionTablePage } from './decision-table.page';
import { FormulaPage } from './formula.page';
import { PolicyWorkspace } from './policy-workspace';

/** WEB-045: SCR-1501, SCR-1502 trên dữ liệu giả lập; quyền soạn theo ma trận wiki/15a. */
const PACK = '0192a1b0-3c4d-7e5f-8a9b-000000004001';

async function render<T>(cmp: Type<T>, author = true, ready = '[data-testid=preview-score]') {
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
      { provide: STUDIO_ACCESS, useValue: { can: () => true, author: () => author } },
      PolicyWorkspace,
    ],
  });
  TestBed.inject(PolicyWorkspace).ref.set({ id: PACK, v: '2.1.0' });
  const fixture = TestBed.createComponent(cmp);
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
const type = (el: HTMLInputElement | HTMLTextAreaElement, v: string) => {
  el.value = v;
  el.dispatchEvent(new Event('input'));
};
const button = (el: HTMLElement, id: string) =>
  el.querySelector(`[data-testid=${id}] button`) as HTMLButtonElement;

describe('Config Studio · Công thức (SCR-1501)', () => {
  beforeEach(() => resetMockData());

  it('khối KPI theo nhãn nghiệp vụ, xem trước ca TC-017', async () => {
    const el = await render(FormulaPage);
    expect(text(el.querySelector('[data-testid=kpi-strip]')).replace(/\s/g, '')).toContain(
      'ĐiểmKPI=(Sốlượng+Chấtlượng+Tiếnđộ)',
    );
    const preview = text(el.querySelector('[data-testid=preview]'));
    expect(preview).toContain('0,783');
    expect(preview).toContain('63,6');
    expect(text(el.querySelector('[data-testid=preview-score]'))).toBe('91,2');
    expect(preview).toContain('Hoàn thành tốt nhiệm vụ');
    expect(el.textContent).not.toMatch(/SCR-|NHAP|DA_PHAT_HANH/);
  });

  it('tổng điểm nhóm khác 100 báo MSG-15-02 và chặn lưu; sai cú pháp tô đỏ khối', async () => {
    const el = await render(FormulaPage);
    type(el.querySelector('[data-group=then_chot]') as HTMLInputElement, '60');
    await until(el, '[data-testid=group-error]');
    expect(text(el.querySelector('[data-testid=group-error]'))).toBe(
      'Tổng điểm tối đa các nhóm là 90, phải bằng 100.',
    );
    expect(button(el, 'save').disabled).toBe(true);
    type(el.querySelector('[data-group=then_chot]') as HTMLInputElement, '70');
    type(el.querySelector('#expr-A') as HTMLTextAreaElement, 'math.least(1.0');
    await until(el, '[data-block-card=A].bad');
    expect(text(el.querySelector('[data-block-card=A] .small'))).toBe(
      'Biểu thức không hợp lệ: sai cú pháp tại khối Số lượng.',
    );
    expect(button(el, 'save').disabled).toBe(true);
  });

  it('lưu nháp tham số rồi xem trước tính lại theo bản đã lưu', async () => {
    const el = await render(FormulaPage);
    type(el.querySelector('[data-group=then_chot]') as HTMLInputElement, '60');
    type(el.querySelector('[data-group=thuong_xuyen]') as HTMLInputElement, '40');
    await until(el, '[data-testid=save] button', (b) => !(b as HTMLButtonElement).disabled);
    button(el, 'save').click();
    await until(el, '.note');
    expect(text(el.querySelector('.note'))).toBe('Đã lưu nháp.');
    await until(el, '[data-testid=preview-score]', (e) => text(e) === '91,3');
    expect(text(el.querySelector('[data-testid=preview-score]'))).toBe('91,3');
  });

  it('người chỉ có quyền xem không thấy nút soạn', async () => {
    const el = await render(FormulaPage, false);
    expect(el.querySelector('[data-testid=save]')).toBeNull();
    expect(el.querySelector('[data-testid=submit]')).toBeNull();
    expect(text(el.querySelector('[data-testid=readonly]'))).toContain('không chỉnh sửa');
    expect((el.querySelector('#expr-A') as HTMLTextAreaElement).readOnly).toBe(true);
  });
});

describe('Config Studio · Bảng quyết định (SCR-1502)', () => {
  beforeEach(() => resetMockData());

  it('bảng 6 dòng, dòng bị che báo MSG-15-04, biến mẫu câu lạ báo MSG-15-05', async () => {
    const el = await render(DecisionTablePage, true, '[data-row="6"]');
    expect(el.querySelectorAll('[data-testid=decision-table] tbody tr')).toHaveLength(6);
    (el.querySelector('[data-testid=add-row] button') as HTMLButtonElement).click();
    await until(el, '[data-row="7"]');
    type(el.querySelector('[data-row="7"] input') as HTMLInputElement, '< 60');
    await until(el, '[data-testid=shadow-warning]');
    expect(text(el.querySelector('[data-testid=shadow-warning]'))).toBe(
      'Dòng 7 không bao giờ được chọn vì dòng 3 phía trên luôn thoả mãn trước.',
    );
    type(el.querySelector('[data-testid=template]') as HTMLTextAreaElement, 'Thiếu {bien_la}');
    await until(el, '[data-testid=template-error]');
    expect(text(el.querySelector('[data-testid=template-error]'))).toBe(
      'Mẫu câu giải thích dùng biến bien_la không có trong ngữ cảnh xếp loại.',
    );
    expect(button(el, 'save').disabled).toBe(true);
  });

  it('chỉ xem: hiện nhãn mức, không có ô nhập', async () => {
    const el = await render(DecisionTablePage, false, '[data-row="6"]');
    expect(el.querySelector('[data-testid=decision-table] input')).toBeNull();
    expect(text(el.querySelector('[data-row="1"]'))).toContain('Không hoàn thành nhiệm vụ');
    expect(text(el.querySelector('[data-row="1"]'))).toContain('Không đảm bảo');
  });
});
