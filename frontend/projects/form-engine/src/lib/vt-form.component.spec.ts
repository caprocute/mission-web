import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { JSONSchema7 } from './json-schema';
import { of } from 'rxjs';
import { MAU_1B_CONTEXT, MAU_1B_RULES, MAU_1B_SCHEMA, MAU_1B_UI } from '../samples/mau-1b';
import { VT_FORM_CATALOGS } from './fields/field-types';
import { FormRule, FormUi, ServerFieldError } from './form-rule';
import { provideFormEngine } from './provide-form-engine';
import { VtFormComponent } from './vt-form.component';

/** WEB-010: dựng biểu mẫu từ schema + ui + quy tắc, widget vt.input.*, lỗi theo trường. */
@Component({
  imports: [VtFormComponent],
  template: `<vt-form
    [schema]="schema()"
    [ui]="ui()"
    [rules]="rules()"
    [context]="context()"
    [value]="value()"
    [serverErrors]="serverErrors()"
    (submitted)="sent.push($event)"
  >
    <button type="submit">Nộp</button>
  </vt-form>`,
})
class HostComponent {
  schema = signal<JSONSchema7>(MAU_1B_SCHEMA);
  ui = signal<FormUi>(MAU_1B_UI);
  rules = signal<readonly FormRule[]>(MAU_1B_RULES);
  context = signal<Record<string, unknown>>(MAU_1B_CONTEXT);
  value = signal<Record<string, unknown>>({ mucTuDeXuat: 'HTTNV' });
  serverErrors = signal<readonly ServerFieldError[]>([]);
  sent: Record<string, unknown>[] = [];
}

async function setup(
  patch: Partial<Record<keyof HostComponent, unknown>> = {},
  providers: unknown[] = [],
) {
  TestBed.configureTestingModule({ providers: [provideFormEngine(), ...(providers as never[])] });
  const fixture = TestBed.createComponent(HostComponent);
  for (const [k, v] of Object.entries(patch))
    (fixture.componentInstance as never as Record<string, { set: (x: unknown) => void }>)[k].set(v);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { fixture, host: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
}

const q = <T extends Element>(el: HTMLElement, s: string) => el.querySelector(s) as T;
async function type(
  fixture: ComponentFixture<HostComponent>,
  el: HTMLElement,
  sel: string,
  value: string,
) {
  const input = q<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(el, sel);
  if (input instanceof HTMLSelectElement) {
    input.selectedIndex = [...input.options].findIndex((o) => o.textContent?.trim() === value);
  } else {
    input.value = value;
  }
  input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input'));
  input.dispatchEvent(new Event('blur'));
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
}
async function submit(fixture: ComponentFixture<HostComponent>, el: HTMLElement) {
  q<HTMLButtonElement>(el, 'button[type=submit]').click();
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
}

async function fillValid(fixture: ComponentFixture<HostComponent>, el: HTMLElement) {
  await type(fixture, el, '#vt-f-ketQuaNhiemVu', 'Hoàn thành 12/12 nhiệm vụ.');
  await type(fixture, el, '#vt-f-diemTuCham', '92.5');
  await type(fixture, el, '#vt-f-uuDiem', 'Chủ động.');
  await type(fixture, el, '#vt-f-hanChe', 'Còn chậm.');
  await type(fixture, el, '#vt-f-huongKhacPhuc', 'Lập lịch nhắc việc.');
}

describe('VtFormComponent với Mẫu 1B', () => {
  it('dựng 4 nhóm theo ui.sections, legend đúng tên', async () => {
    const { el } = await setup();
    const legends = [...el.querySelectorAll('fieldset.vt-section > legend')].map((l) =>
      l.textContent?.trim(),
    );
    expect(legends).toEqual([
      'Tiêu chí chung',
      'Kết quả thực hiện nhiệm vụ',
      'Tự đề xuất mức',
      'Ưu điểm, hạn chế và hướng khắc phục',
    ]);
  });

  it('widget: score là ô số có min/max/step, textarea có đếm ký tự, catalog là select có nhãn danh mục', async () => {
    const { el } = await setup();
    const score = q<HTMLInputElement>(el, '#vt-f-diemTuCham');
    expect([score.type, score.min, score.max, score.step]).toEqual(['number', '0', '100', '0.1']);
    expect(q(el, '#vt-f-hanChe').tagName).toBe('TEXTAREA');
    expect(el.textContent).toContain('0/2000 ký tự');
    const opts = [...q<HTMLSelectElement>(el, '#vt-f-mucTuDeXuat').options].map((o) =>
      o.textContent?.trim(),
    );
    expect(opts).toEqual([
      '-- Chọn --',
      'Hoàn thành xuất sắc nhiệm vụ',
      'Hoàn thành tốt nhiệm vụ',
      'Hoàn thành nhiệm vụ',
      'Không hoàn thành nhiệm vụ',
    ]);
    expect(q<HTMLSelectElement>(el, '#vt-f-mucTuDeXuat').selectedIndex).toBe(2);
    const checks = el.querySelectorAll<HTMLInputElement>('input[type=checkbox]');
    expect(checks.length).toBe(9);
    expect([...checks].every((c) => c.checked)).toBe(true);
  });

  it('nhãn gắn với ô nhập, trường bắt buộc có chữ cho trình đọc màn hình, mô tả gắn aria-describedby', async () => {
    const { el } = await setup();
    const label = q<HTMLLabelElement>(el, 'label[for="vt-f-hanChe"]');
    expect(label.textContent).toContain('Hạn chế');
    expect(label.textContent).toContain('(bắt buộc)');
    const giai = q<HTMLTextAreaElement>(el, '#vt-f-giaiPhapDotPha');
    expect(giai.getAttribute('aria-describedby')).toBe('vt-f-giaiPhapDotPha-desc');
    expect(q(el, '#vt-f-giaiPhapDotPha-desc').textContent).toContain('Hoàn thành xuất sắc');
  });

  it('chọn HTXSNV: lỗi CEL R-1B-02 và R-1B-03 hiện ở đúng trường, aria-invalid, sửa thì hết lỗi', async () => {
    const { fixture, el } = await setup();
    await type(fixture, el, '#vt-f-mucTuDeXuat', 'Hoàn thành xuất sắc nhiệm vụ');
    await type(fixture, el, '#vt-f-giaiPhapDotPha', 'ngắn');
    await type(fixture, el, '#vt-f-giaiTrinh', 'ngắn');
    expect(q(el, '#vt-f-giaiTrinh-error').textContent).toContain(
      'MSG-09-02'.length ? 'khác mức hệ thống tham khảo' : '',
    );
    const giai = q<HTMLTextAreaElement>(el, '#vt-f-giaiPhapDotPha');
    expect(giai.getAttribute('aria-invalid')).toBe('true');
    expect(giai.getAttribute('aria-describedby')).toContain('vt-f-giaiPhapDotPha-error');
    expect(q(el, '#vt-f-giaiPhapDotPha-error').textContent).toContain('ít nhất 50 ký tự');
    await type(fixture, el, '#vt-f-giaiPhapDotPha', 'x'.repeat(50));
    expect(el.querySelector('#vt-f-giaiPhapDotPha-error')).toBeNull();
    expect(giai.getAttribute('aria-invalid')).toBe('false');
  });

  it('chọn mức khác tham khảo rồi gửi ngay: R-1B-03 (MSG-09-02) có trong danh sách dù chưa chạm ô giải trình', async () => {
    const { fixture, el } = await setup();
    await type(fixture, el, '#vt-f-mucTuDeXuat', 'Hoàn thành nhiệm vụ');
    await submit(fixture, el);
    expect(q(el, '.vt-form__summary').textContent).toContain('khác mức hệ thống tham khảo');
    expect(q(el, '#vt-f-giaiTrinh').getAttribute('aria-invalid')).toBe('true');
  });

  it('gửi khi còn lỗi: danh sách lỗi đầu biểu mẫu (role=alert) có liên kết tới trường, không phát submitted', async () => {
    const { fixture, el, host } = await setup();
    await submit(fixture, el);
    const summary = q<HTMLElement>(el, '.vt-form__summary');
    expect(summary.getAttribute('role')).toBe('alert');
    expect(host.sent).toEqual([]);
    expect(el.querySelectorAll('[aria-invalid=true]').length).toBeGreaterThanOrEqual(5);
    expect(q(el, '#vt-f-hanChe-error').textContent).toContain('Trường này bắt buộc nhập.');
    expect(summary.textContent).toContain('Hạn chế: chưa nhập');
    await new Promise((r) => setTimeout(r));
    expect(document.activeElement).toBe(summary);
  });

  it('đủ dữ liệu hợp lệ thì phát submitted với mô hình đầy đủ trường (chuỗi rỗng, không bỏ trường)', async () => {
    const { fixture, el, host } = await setup();
    await fillValid(fixture, el);
    await submit(fixture, el);
    expect(host.sent.length).toBe(1);
    expect(host.sent[0]).toEqual(
      expect.objectContaining({
        diemTuCham: 92.5,
        mucTuDeXuat: 'HTTNV',
        giaiPhapDotPha: '',
        giaiTrinh: '',
      }),
    );
    expect((host.sent[0]['tieuChiChung'] as Record<string, boolean>)['tc9']).toBe(true);
  });

  it('quy tắc serverOnly (R-1B-04) không chặn ở trình duyệt', async () => {
    const { fixture, el, host } = await setup({
      context: { ...MAU_1B_CONTEXT, ngayNop: '2027-03-01' },
    });
    await fillValid(fixture, el);
    await submit(fixture, el);
    expect(host.sent.length).toBe(1);
  });

  it('lỗi máy chủ theo trường hiện ở trường, lỗi không khớp trường vào danh sách chung; sửa giá trị thì mất', async () => {
    const { fixture, el, host } = await setup();
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    await type(fixture, el, '#vt-f-hanChe', 'Như quý trước');
    host.serverErrors.set([
      { field: 'hanChe', message: 'Nội dung trùng phiếu quý trước' },
      { field: 'khongCo', message: 'Lỗi chung từ máy chủ' },
    ]);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(q(el, '#vt-f-hanChe-error').textContent).toContain('Nội dung trùng phiếu quý trước');
    expect(q(el, '.vt-form__summary').textContent).toContain('Lỗi chung từ máy chủ');
    await type(fixture, el, '#vt-f-hanChe', 'Đã viết lại');
    expect(el.querySelector('#vt-f-hanChe-error')).toBeNull();
    warn.mockRestore();
  });
});

describe('VtFormComponent: widget ngày, trường ngoài nhóm, nguồn danh mục', () => {
  const schema: JSONSchema7 = {
    type: 'object',
    required: ['ngay'],
    properties: {
      ngay: { type: 'string', format: 'date', title: 'Ngày hoàn thành' },
      loai: {
        type: 'string',
        title: 'Loại',
        'x-catalog': 'loai-viec',
        enum: ['A', 'B'],
      } as JSONSchema7,
      ghiChu: { type: 'string', title: 'Ghi chú' },
    },
  };

  it('format date dùng ô ngày gốc; trường không có trong ui.sections vẫn hiện; danh mục lấy từ VT_FORM_CATALOGS', async () => {
    const catalogs = vi.fn(() =>
      of([
        { value: 'A', label: 'Việc thường xuyên' },
        { value: 'B', label: 'Việc đột xuất' },
      ]),
    );
    const { el } = await setup(
      { schema, ui: { sections: [{ title: 'Thời hạn', fields: ['ngay'] }] }, rules: [], value: {} },
      [{ provide: VT_FORM_CATALOGS, useValue: catalogs }],
    );
    expect(q<HTMLInputElement>(el, '#vt-f-ngay').type).toBe('date');
    expect(el.querySelector('#vt-f-ghiChu')).not.toBeNull();
    expect(catalogs).toHaveBeenCalledWith('loai-viec');
    expect(
      [...q<HTMLSelectElement>(el, '#vt-f-loai').options].map((o) => o.textContent?.trim()),
    ).toContain('Việc đột xuất');
  });
});
