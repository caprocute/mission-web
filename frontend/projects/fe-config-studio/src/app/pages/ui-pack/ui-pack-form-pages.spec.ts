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
import { breakMockFormParity } from '../../../../../api-client/src/mock/ui-packs/ui-packs.mock';
import { STUDIO_ACCESS } from '../../studio-permissions';
import { STUDIO_STEP_UP } from '../../studio-step-up';
import { FormBuilderPage } from '../form-builder/form-builder.page';
import {
  browserErrors,
  fromVersion,
  hideFromShow,
  keyFromLabel,
  rewriteForModel,
  showFromHide,
} from '../form-builder/form-builder-model';
import { UiPackPage } from './ui-pack.page';

/** WEB-050: SCR-1801, SCR-1802 trên dữ liệu giả lập; quyền theo ma trận wiki/15a. */
const ADMIN: PermissionKey[] = [
  'SCR-1801.soan',
  'SCR-1801.gui-duyet',
  'SCR-1801.duyet',
  'SCR-1801.quay-lui',
  'SCR-1802.dung',
  'SCR-1802.dua-vao-pack',
];

async function render<T>(cmp: Type<T>, keys: PermissionKey[], ready: string, inputs = {}) {
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
    ],
  });
  const fixture = TestBed.createComponent(cmp);
  for (const [k, v] of Object.entries(inputs)) fixture.componentRef.setInput(k, v);
  fixture.autoDetectChanges();
  await until(fixture.nativeElement, ready);
  return fixture.nativeElement as HTMLElement;
}

async function until(
  el: ParentNode,
  sel: string,
  test: (e: Element) => boolean = () => true,
  tries = 400,
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
const type = (el: HTMLInputElement | HTMLTextAreaElement, v: string, ev = 'input') => {
  el.value = v;
  el.dispatchEvent(new Event(ev));
};

describe('Trình dựng biểu mẫu · logic', () => {
  it('khoá sinh từ nhãn, đổi self và form cho mô hình phẳng, điều kiện hiển thị hai chiều', () => {
    expect(keyFromLabel('Tỷ lệ hồ sơ đạt chuẩn (%)', [])).toBe('ty_le_ho_so_dat_chuan');
    expect(keyFromLabel('Số', ['so'])).toBe('so_2');
    expect(rewriteForModel('self >= 0.0 && form.a == "self"', 'b')).toBe('b >= 0.0 && a == "self"');
    expect(showFromHide(hideFromShow('form.x == "CO"'))).toBe('form.x == "CO"');
  });

  it('lỗi trên trình duyệt: bỏ trường đang ẩn, kiểm quy tắc của trường có giá trị', () => {
    const { fields } = fromVersion({
      formKey: 'k',
      version: '1',
      status: 'NHAP',
      rowVersion: 1,
      schemaJson: {
        type: 'object',
        required: ['a', 'b'],
        properties: {
          a: { type: 'number', title: 'A', 'x-cel': 'self <= 100.0' },
          b: { type: 'string', title: 'B' },
        },
      },
      uiJson: { b: { hideExpression: '!(form.a > 50.0)' } },
    });
    expect(browserErrors(fields, { a: 104 }).map((e) => e.field)).toEqual(['a', 'b']);
    expect(browserErrors(fields, { a: 10 })).toEqual([]);
  });
});

describe('Config Studio · Giao diện đơn vị (SCR-1801)', () => {
  beforeEach(() => resetMockData());

  it('màu chữ không đạt AA báo MSG-18-01 và chặn lưu; sửa lại thì lưu, gửi duyệt, phát hành', async () => {
    const el = await render(UiPackPage, ADMIN, '[data-testid=tokens]');
    expect(text(el.querySelector('[data-testid=pack-bar]'))).toContain('Bản 1.8 · Bản nháp');
    const hex = el.querySelector('[data-token="color.onPrimary"] input.hex') as HTMLInputElement;
    type(hex, '#9FD8DF', 'change');
    await until(el, '[data-testid=contrast] li.bad');
    expect(text(el.querySelector('[data-testid=contrast] li.bad'))).toContain(
      'chưa đạt mức AA (4,5 : 1)',
    );
    expect(button(el, 'save').disabled).toBe(true);

    type(hex, '#FFFFFF', 'change');
    const radius = el.querySelector('[data-token="radius.card"] input') as HTMLInputElement;
    type(radius, '10', 'change');
    await until(el, '[data-testid=save] button', (b) => !(b as HTMLButtonElement).disabled);
    button(el, 'save').click();
    await until(el, '[data-testid=notice]', (e) => text(e) === 'Đã lưu bản nháp.');

    await until(el, '[data-testid=submit] button', (b) => !(b as HTMLButtonElement).disabled);
    button(el, 'submit').click();
    await until(el, '[data-testid=publish] button', undefined, 600);
    button(el, 'publish').click();
    const notice = await until(
      el,
      '[data-testid=notice]',
      (e) => text(e).includes('đã phát hành'),
      600,
    );
    expect(text(notice)).toBe(
      'Giao diện bản 1.8 đã phát hành. Người dùng sẽ nhận giao diện mới ở lần tải trang kế tiếp.',
    );
  }, 20000);

  it('quay lui về bản dùng bộ chính sách đã ngừng hiện MSG-18-04', async () => {
    const el = await render(UiPackPage, ADMIN, '[data-testid=tokens]');
    (el.querySelector('#tab-versions') as HTMLButtonElement).click();
    const row = await until(el, '[data-version="1.5.0"] button');
    (row as HTMLButtonElement).click();
    await until(document, '[data-testid=rollback-confirm] button');
    button(document, 'rollback-confirm').click();
    const err = await until(el, '[data-testid=error]');
    expect(text(err)).toContain('Không thể quay lui về bản 1.5');
  });

  it('chỉ có quyền gửi duyệt và duyệt thì không sửa được bản nháp', async () => {
    const el = await render(UiPackPage, ['SCR-1801.duyet'], '[data-testid=readonly]');
    expect(el.querySelector('[data-testid=save]')).toBeNull();
    expect(
      (el.querySelector('[data-token="color.primary"] input.hex') as HTMLInputElement).disabled,
    ).toBe(true);
  });
});

describe('Config Studio · Trình dựng biểu mẫu (SCR-1802)', () => {
  beforeEach(() => resetMockData());
  const inputs = { formKey: 'phieu-vc-yte', formVersion: '12' };

  it('CEL sai cú pháp báo MSG-18-06; trường tính điểm không xoá được; chạy bộ test và đưa vào giao diện', async () => {
    const el = await render(FormBuilderPage, ADMIN, '[data-testid=props]', inputs);
    (el.querySelector('[data-field=ty_le_hsba_dat] .pick') as HTMLButtonElement).click();
    await until(el, '[data-testid=scored]');
    expect(
      (el.querySelector('[data-field=ty_le_hsba_dat] [aria-label^="Xoá"]') as HTMLButtonElement)
        .disabled,
    ).toBe(true);

    (el.querySelector('[data-kind=text]') as HTMLButtonElement).click();
    const cel = (await until(el, '[data-testid=p-cel]')) as HTMLTextAreaElement;
    type(cel, 'size(self) >= ');
    const ce = await until(el, '[data-testid=cel-error]');
    expect(text(ce)).toContain('Biểu thức kiểm tra không hợp lệ');
    expect(button(el, 'save').disabled).toBe(true);
    type(cel, 'size(self) >= 3');
    await until(el, '[data-testid=save] button', (b) => !(b as HTMLButtonElement).disabled);
    button(el, 'save').click();
    await until(el, '[data-testid=notice]', (e) => text(e).startsWith('Đã lưu bản nháp'));

    button(el, 'run-tests').click();
    const sum = await until(el, '[data-testid=test-summary] .chip');
    expect(text(sum)).toBe('Bộ test 6/6 · trình duyệt và máy chủ cùng kết quả');
    await until(el, '[data-testid=attach] button', (b) => !(b as HTMLButtonElement).disabled);
    button(el, 'attach').click();
    const n = await until(el, '[data-testid=notice]', (e) => text(e).startsWith('Đã đưa'));
    expect(text(n)).toBe('Đã đưa biểu mẫu bản 12 vào giao diện bản 1.8.');
  }, 20000);

  it('ca test cho kết quả khác giữa trình duyệt và máy chủ hiện MSG-18-08', async () => {
    breakMockFormParity('TC-03');
    const el = await render(FormBuilderPage, ADMIN, '[data-testid=props]', inputs);
    button(el, 'run-tests').click();
    const bad = await until(el, '[data-case=TC-03]');
    expect(text(bad)).toBe('Ca test TC-03 cho kết quả khác nhau giữa trình duyệt và máy chủ.');
    expect(el.querySelector('[data-testid=attach] button')?.hasAttribute('disabled')).toBe(true);
  });
});
