import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import {
  DEFAULT_RUNTIME_CONFIG,
  JOB_TRACKER_OPTIONS,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
  mockStepUp,
} from '@vt/api-client';
import { resetMockData } from '../../../../../api-client/src/mock/mock-registry';
import { DOSSIER_CAN, DossierPermissionKey } from '../../dossier-permissions';
import { DOSSIER_STEP_UP } from '../../step-up';
import { PackagesPage } from '../packages/packages.page';
import { PackagePage } from './package.page';
import { msg1104, msg1105, msg1106, problemMessage, shortHash } from './package-rules';

/** WEB-041: SCR-1102 trên dữ liệu giả lập theo vai chuẩn bị (AC-07) và ký gói (AC-08). */
const G = (n: number) => `0192a3f4-1102-7000-8000-${String(n).padStart(12, '0')}`;
const PREPARER: DossierPermissionKey[] = [
  'SCR-1102.chuan-bi',
  'SCR-1102.tai-goi',
  'SCR-1102.goi-con',
];
const SIGNER: DossierPermissionKey[] = ['SCR-1102.chuan-bi', 'SCR-1102.tai-goi', 'SCR-1102.ky-goi'];

async function render(url: string, keys: DossierPermissionKey[] = SIGNER) {
  TestBed.resetTestingModule();
  resetMockData();
  TestBed.configureTestingModule({
    providers: [
      provideRouter(
        [
          { path: 'goi-ho-so', component: PackagesPage },
          { path: 'goi-ho-so/:id', component: PackagePage },
        ],
        withComponentInputBinding(),
      ),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      {
        provide: JOB_TRACKER_OPTIONS,
        useValue: { initialDelayMs: 1, maxDelayMs: 1, maxRetries: 0 },
      },
      { provide: DOSSIER_CAN, useValue: (k: DossierPermissionKey) => keys.includes(k) },
      {
        provide: DOSSIER_STEP_UP,
        useValue: async () => {
          mockStepUp();
          return true;
        },
      },
    ],
  });
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(url);
  const el = harness.routeNativeElement as HTMLElement;
  harness.fixture.autoDetectChanges();
  return { el, harness };
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 250; i++) {
    const e = [...el.querySelectorAll(sel)].find(test);
    if (e) return e as HTMLElement;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw new Error(`Không thấy ${sel}`);
}
const text = (e: Element | null | undefined) => (e?.textContent ?? '').replace(/\s+/g, ' ').trim();

describe('SCR-1102 quy tắc (WEB-041)', () => {
  it('thông báo theo TL-02b và lỗi không lộ mã', () => {
    expect(msg1104(2)).toBe(
      'Chưa thể ký gói: 2 mục kiểm tra chưa đạt. Xem chi tiết ở danh sách kiểm tra.',
    );
    expect(msg1105('DP-2026-N-0142', '2027-01-30T15:12:00')).toBe(
      'Gói DP-2026-N-0142 đã được ký số và nộp lúc 15:12 ngày 30/01/2027. Gói đã bị khoá; mọi thay đổi phải qua mở lại kỳ.',
    );
    expect(msg1106('Ban Tổ chức Trung ương', 'bên nhận chưa phản hồi', null)).toContain(
      'Hệ thống sẽ tự thử lại theo lịch.',
    );
    expect(shortHash('9f2c4e7a1b3d5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcd')).toBe(
      '9f2c4e7a…6789abcd',
    );
    const m = problemMessage({
      type: 'x',
      title: 'x',
      status: 409,
      code: 'DOSSIER-409-PACKAGE-LOCKED',
    });
    expect(m).not.toMatch(/DOSSIER|409/);
  });
});

describe('SCR-1102 Gói hồ sơ số và ký số (WEB-041)', () => {
  it('người ký: danh sách kiểm tra chưa đạt thì báo MSG-11-04, không gọi ký', async () => {
    const { el } = await render(`/goi-ho-so/${G(1)}`);
    await until(el, '[data-check]');
    expect(el.querySelectorAll('[data-check="CANH_BAO"]').length).toBe(2);
    expect(text(el.querySelector('#pk-forms'))).toContain('tệp xong');
    expect(text(await until(el, '[data-special]'))).toContain('Kiêm nhiệm nhiều chức vụ');
    expect(text(await until(el, '[aria-labelledby="pk-sign"] strong'))).toBe('Ngô Thanh Tâm');
    (el.querySelector('[data-act="ky-goi"]') as HTMLElement).click();
    expect(text(await until(el, '.err'))).toBe(msg1104(2));
    expect(el.querySelector('[data-act="goi-con"]')).toBeNull();
    expect(el.textContent).not.toMatch(/MSG-|DOSSIER-|CANH_BAO|DA_KY/);
  }, 15000);

  it('người ký: ký gói đạt kiểm tra, gói chuyển Đã nộp và báo MSG-11-05', async () => {
    const { el } = await render(`/goi-ho-so/${G(2)}`);
    await until(el, '[data-check]');
    (await until(el, '[data-act="ky-goi"]')).click();
    await until(el, '[data-status]', (e) => text(e) === 'Đã nộp');
    expect(text(await until(el, '.ok', (e) => text(e).includes('đã được ký số')))).toMatch(
      /^Gói DP-2026-Q-0131 đã được ký số và nộp lúc \d{2}:\d{2} ngày/,
    );
    expect(el.querySelector('[data-act="ky-goi"]')).toBeNull();
  }, 15000);

  it('người chuẩn bị: tệp lỗi (MSG-11-07), sinh lại mẫu lỗi; không có nút ký', async () => {
    const { el } = await render(`/goi-ho-so/${G(3)}`, PREPARER);
    const w = await until(el, '[data-msg="MSG-11-07"]');
    expect(text(w)).toBe(
      'Có 2 tệp sinh lỗi. Đồng chí có thể bấm "Sinh lại mẫu lỗi" sau khi bổ sung dữ liệu.',
    );
    expect(el.querySelector('[data-act="ky-goi"]')).toBeNull();
    expect(el.querySelector('[data-act="tai-goi"]')).toBeNull();
    (el.querySelector('[data-act="sinh-lai"]') as HTMLElement).click();
    await until(el, '[data-status]', (e) => text(e) === 'Chờ ký');
    expect(el.querySelector('[data-msg="MSG-11-07"]')).toBeNull();
    expect(el.querySelector('[data-act="tai-goi"]')).not.toBeNull();
  }, 15000);

  it('người chuẩn bị: gửi gói con của gói đã nộp; nơi nhận chưa kết nối thì báo lỗi', async () => {
    const { el } = await render(`/goi-ho-so/${G(2)}`, [...PREPARER, 'SCR-1102.ky-goi']);
    (await until(el, '[data-act="ky-goi"]')).click();
    const send = await until(el, '[data-act="goi-con"]');
    const sel = el.querySelector('#pk-dest') as HTMLSelectElement;
    sel.value = 'VPTW';
    sel.dispatchEvent(new Event('change'));
    send.click();
    expect(text(await until(el, '.err'))).toContain('chưa được cấu hình kết nối');
    sel.value = 'BTC_TW';
    sel.dispatchEvent(new Event('change'));
    (el.querySelector('[data-act="goi-con"]') as HTMLElement).click();
    await until(el, '[data-transfer]', (e) => text(e) === 'Đã gửi');
  }, 15000);

  it('danh sách gói', async () => {
    const { el } = await render('/goi-ho-so', PREPARER);
    await until(el, 'tbody tr');
    expect(el.querySelectorAll('tbody tr').length).toBe(4);
    expect(text(el.querySelector('tbody'))).toContain('2 tệp lỗi');
  }, 15000);
});
