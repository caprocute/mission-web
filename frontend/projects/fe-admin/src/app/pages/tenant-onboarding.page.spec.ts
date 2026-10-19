import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { ADMIN_CAN } from '../admin-permissions';
import { TenantOnboardingPage } from './tenant-onboarding.page';

/** WEB-017: SCR-0102 trên dữ liệu giả lập: khai báo, mở lại nháp, kiểm tra tổng thể, kích hoạt, quyền. */
async function render(id?: string, step?: string, can: (k: string) => boolean = () => true) {
  TestBed.configureTestingModule({
    imports: [TenantOnboardingPage],
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      { provide: ADMIN_CAN, useValue: can },
    ],
  });
  const router = TestBed.inject(Router);
  const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(TenantOnboardingPage);
  if (id) fixture.componentRef.setInput('id', id);
  if (step) fixture.componentRef.setInput('step', step);
  fixture.autoDetectChanges();
  await until(fixture.nativeElement, '.stepper');
  return { fixture, el: fixture.nativeElement as HTMLElement, navigate };
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 100; i++) {
    const e = el.querySelector(sel);
    if (e && test(e)) return;
    await new Promise((r) => setTimeout(r, 20));
  }
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const button = (el: HTMLElement, label: string) =>
  [...el.querySelectorAll('button')].find((b) => text(b).includes(label)) as
    HTMLButtonElement | undefined;
function type(el: HTMLElement, name: string, value: string) {
  const i = el.querySelector(`[name="${name}"]`) as HTMLInputElement;
  i.value = value;
  i.dispatchEvent(new Event('input'));
}

describe('TenantOnboardingPage (SCR-0102)', () => {
  it('bước 1: chỉ cho tiếp tục khi đủ trường bắt buộc, lưu sinh hồ sơ rồi chuyển bước 2', async () => {
    const { el, fixture, navigate } = await render();
    expect(text(el.querySelector('h1'))).toBe('Khởi tạo đơn vị triển khai');
    expect(button(el, 'Tiếp tục')?.disabled).toBe(true);
    type(el, 'name', 'Tỉnh Hà Giang');
    type(el, 'code', 'hagiang');
    type(el, 'contractRef', '91/2026/HĐ-VTS');
    type(el, 'accounts', '60000');
    await fixture.whenStable();
    expect(button(el, 'Tiếp tục')?.disabled).toBe(false);
    button(el, 'Tiếp tục')!.click();
    for (let i = 0; i < 50 && !navigate.mock.calls.length; i++) {
      await new Promise((r) => setTimeout(r, 20));
    }
    expect(navigate).toHaveBeenCalledWith(
      ['/admin/tenants', expect.stringMatching(/^t-/), 'onboarding'],
      expect.objectContaining({ queryParams: { step: 2 } }),
    );
  });

  it('mở lại nháp: bước 1 sang bước 2, gợi ý pool và chọn sẵn cell tải thấp nhất', async () => {
    const { el } = await render('t-truong-thpt-hue');
    expect(text(el.querySelector('header'))).toContain('ONB-2026-0331');
    await until(el, 'button', () => button(el, 'Tiếp tục')?.disabled === false);
    button(el, 'Tiếp tục')!.click();
    await until(el, '.tbl tbody tr.sel');
    expect(text(el.querySelector('.hint'))).toContain('Gợi ý của hệ thống: Dùng chung');
    expect(text(el.querySelector('.tbl tbody tr.sel'))).toContain('CELL-SH');
    const silo = el.querySelector('input[name="model"][value="silo"]') as HTMLInputElement;
    expect(silo.disabled).toBe(false);
    expect((el.querySelector('[name="domain"]') as HTMLInputElement).value).toBe(
      'quochoc.okr.viettel.vn',
    );
  });

  it('bước 4: kiểm tra kết nối IdP cập nhật kết quả từng IdP', async () => {
    const { el } = await render('t-thainguyen');
    await until(el, 'h2', (e) => text(e).includes('Định danh'));
    expect(text(el.querySelector('tbody'))).toContain('Chưa kiểm tra');
    expect(button(el, 'Tiếp tục')?.disabled).toBe(true);
    button(el, 'Kiểm tra kết nối IdP')!.click();
    await until(el, 'tbody', (e) => !text(e).includes('Chưa kiểm tra'));
    expect(button(el, 'Tiếp tục')?.disabled).toBe(false);
  });

  it('bước 6: tenant sẵn sàng kích hoạt được, chuyển DANG_VAN_HANH', async () => {
    const { el } = await render('t-quangngai', '6');
    await until(el, '.checks li');
    await until(el, 'button', () => button(el, 'Kích hoạt')?.disabled === false);
    button(el, 'Kích hoạt')!.click();
    await until(el, '.okbox');
    expect(text(el.querySelector('.okbox'))).toContain('đang vận hành');
    expect(text(el)).toContain('Đã kích hoạt đơn vị Tỉnh Quảng Ngãi');
  });

  it('ẩn Huỷ và Kích hoạt theo quyền; Huỷ cần xác nhận tại chỗ', async () => {
    const r1 = await render('t-truong-thpt-hue', '1', (k) => k !== 'SCR-0102.huy');
    expect(button(r1.el, 'Huỷ')).toBeUndefined();
    TestBed.resetTestingModule();
    const { el, navigate } = await render('t-truong-thpt-hue', '1');
    await until(el, 'button', () => !!button(el, 'Huỷ'));
    button(el, 'Huỷ')!.click();
    await until(el, 'button', () => !!button(el, 'Xác nhận huỷ yêu cầu'));
    button(el, 'Xác nhận huỷ yêu cầu')!.click();
    for (let i = 0; i < 50 && !navigate.mock.calls.length; i++) {
      await new Promise((r) => setTimeout(r, 20));
    }
    expect(navigate).toHaveBeenCalledWith(['/admin/tenants']);
  });
});
