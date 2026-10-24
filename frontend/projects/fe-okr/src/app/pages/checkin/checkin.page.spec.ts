import {
  HttpInterceptorFn,
  HttpResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  OkrApi,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { firstValueFrom, map } from 'rxjs';
import { resetMockData } from '../../../../../api-client/src/mock/mock-registry';
import { OKR_CAN, OkrPermissionKey } from '../../okr-permissions';
import { OkrCheckinPage } from './checkin.page';

/** WEB-024: SCR-0301 trên dữ liệu giả lập: chọn KR, số liệu nguồn, ghi đè, chênh lớn, gửi, nháp. */
async function render(
  url = '/checkin',
  can: (k: OkrPermissionKey) => boolean = () => true,
  elderly = false,
  extra: HttpInterceptorFn[] = [],
) {
  resetMockData();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'checkin', component: OkrCheckinPage },
        { path: '**', children: [] },
      ]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, ...extra, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      { provide: OKR_CAN, useValue: can },
    ],
  });
  if (elderly) {
    const theme = TestBed.inject(ThemeService);
    theme.mode.set('elderly');
    theme.reducedSteps.set(true);
  }
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(url, OkrCheckinPage);
  const el = harness.routeNativeElement as HTMLElement;
  harness.fixture.autoDetectChanges();
  await until(el, 'form h2');
  return el;
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 100; i++) {
    const e = [...el.querySelectorAll(sel)].find(test);
    if (e) return e;
    await new Promise((r) => setTimeout(r, 20));
  }
  return null;
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const buttons = (el: HTMLElement) => [...el.querySelectorAll('button')].map(text);
const button = (el: HTMLElement, label: string) =>
  [...el.querySelectorAll('button')].find((b) => text(b) === label) as
    HTMLButtonElement | undefined;
const input = (el: HTMLElement, id: string) =>
  el.querySelector(`#${id}`) as HTMLInputElement | HTMLTextAreaElement;
function type(i: HTMLInputElement | HTMLTextAreaElement, v: string) {
  i.value = v;
  i.dispatchEvent(new Event('input'));
}
const radio = (el: HTMLElement, label: string) =>
  [...el.querySelectorAll('.level')].find((l) => text(l) === label)!.querySelector('input')!;
const tick = () => new Promise((r) => setTimeout(r, 40));

describe('OkrCheckinPage (SCR-0301)', () => {
  afterEach(() => window.history.replaceState(null, '', '/'));

  it('mở KR đầu tiên chưa check-in với công thức, lần trước, số liệu nguồn và mức tự tin lần trước', async () => {
    const el = await render();
    expect(text(el.querySelector('h1'))).toBe('Check-in kết quả then chốt');
    expect(text(el.querySelector('.head'))).toContain(
      'Tuần 4/13 · Quý IV/2026 · hạn check-in thứ Sáu 23/10/2026',
    );
    const rows = [...el.querySelectorAll('vt-checkin-list .row')].map(text);
    expect(rows[0]).toContain('KR 1.1');
    expect(rows[2]).toContain('KR 2.1');
    expect(rows[2]).toContain('Xong');
    expect(text(el.querySelector('form h2'))).toContain('KR 1.1 · Kết nạp đảng viên mới');
    expect(text(el.querySelector('.info'))).toContain('/ 500 đảng viên đầu kỳ');
    expect(input(el, 'c-prev').value).toBe('2,4%');
    await until(el, 'small', (s) => text(s).includes('cập nhật lúc'));
    expect(input(el, 'c-value').value).toBe('2,6');
    expect(text(el.querySelector('form'))).toContain('Tiến độ KR: 87% chỉ tiêu');
    expect(radio(el, 'Cao').checked).toBe(true);
    expect(text(el.querySelector('vt-checkin-trend'))).toContain('Diễn biến KR 1.1');
  });

  it('gửi check-in: MSG-03-01, KR chuyển "Xong", sang KR kế tiếp', async () => {
    const el = await render();
    await until(el, 'small', (s) => text(s).includes('cập nhật lúc'));
    type(input(el, 'c-highlights'), 'Chi bộ 5 kết nạp 01 đồng chí');
    button(el, 'Gửi check-in')!.click();
    const msg = await until(el, '[role="status"]', (e) => text(e).startsWith('Đã gửi'));
    expect(text(msg)).toBe('Đã gửi check-in KR 1.1. Tiến độ mục tiêu O1 hiện là 67%.');
    await until(el, 'form h2', (h) => text(h).includes('KR 1.3'));
    expect(text(el.querySelector('form h2'))).toContain('KR 1.3');
    const h = await firstValueFrom(TestBed.inject(OkrApi).checkins('kr-1-1'));
    expect(h.items[0]).toMatchObject({ value: 2.6, highlights: 'Chi bộ 5 kết nạp 01 đồng chí' });
  });

  it('ghi đè số liệu nguồn phải có lý do (MSG-03-04); thiếu giá trị báo cạnh ô', async () => {
    const el = await render();
    await until(el, 'small', (s) => text(s).includes('cập nhật lúc'));
    type(input(el, 'c-value'), '2,5');
    await tick();
    expect(input(el, 'c-overrideReason')).toBeTruthy();
    button(el, 'Gửi check-in')!.click();
    await tick();
    expect(text(el.querySelector('#c-reason-err'))).toBe(
      'Vui lòng ghi lý do khi thay đổi số liệu lấy từ CSDL đảng viên.',
    );
    expect(document.activeElement?.id).toBe('c-overrideReason');
    type(input(el, 'c-value'), '');
    await tick();
    expect(text(el.querySelector('#c-value-err'))).toBe('Vui lòng nhập giá trị hiện tại.');
  });

  it('chênh quá 50% khoảng chỉ tiêu: MSG-03-02, "Vẫn gửi" thì gửi được', async () => {
    const el = await render('/checkin?kr=kr-1-3');
    type(input(el, 'c-value'), '80');
    await tick();
    button(el, 'Gửi check-in')!.click();
    const warn = await until(el, '.confirm');
    expect(text(warn)).toContain(
      'Giá trị mới chênh 53% so với lần trước. Đồng chí có chắc chắn không?',
    );
    expect(document.activeElement).toBe(warn);
    button(el, 'Vẫn gửi')!.click();
    const msg = await until(el, '[role="status"]', (e) => text(e).startsWith('Đã gửi'));
    expect(text(msg)).toContain('Đã gửi check-in KR 1.3.');
  });

  it('nguồn số liệu lỗi: MSG-03-03, vẫn nhập tay được', async () => {
    window.history.replaceState(null, '', '/?mock-source=loi');
    const el = await render();
    const warn = await until(el, '[role="alert"]', (e) => text(e).startsWith('Không lấy được'));
    expect(text(warn)).toBe('Không lấy được số liệu từ CSDL đảng viên. Đồng chí có thể nhập tay.');
    expect(input(el, 'c-value').value).toBe('2,4');
    expect(text(el.querySelector('form'))).toContain('CSDL đảng viên (nhập tay)');
  });

  it('lưu nháp rồi mở lại vẫn thấy nháp; lịch sử mở được và đóng bằng Esc', async () => {
    const el = await render('/checkin?kr=kr-1-3');
    type(input(el, 'c-value'), '34');
    radio(el, 'Trung bình').dispatchEvent(new Event('change'));
    await tick();
    button(el, 'Lưu nháp')!.click();
    const msg = await until(el, '[role="status"]', (e) => text(e).startsWith('Đã lưu nháp'));
    expect(text(msg)).toBe('Đã lưu nháp check-in KR 1.3.');
    button(el, 'Lịch sử')!.click();
    const hist = (await until(el, 'vt-checkin-history section')) as HTMLElement;
    expect(document.activeElement).toBe(hist);
    expect(text(hist)).toContain('Lịch sử check-in KR 1.3');
    hist.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await tick();
    expect(el.querySelector('vt-checkin-history')).toBeNull();
    const kr = await firstValueFrom(TestBed.inject(OkrApi).keyResult('kr-1-3'));
    expect(kr.myDraftCheckin).toMatchObject({ value: 34, confidence: 'TRUNG_BINH' });
    expect(kr.currentValue).toBe(32.6);
  });

  it('không có quyền check-in: ẩn nút gửi; chế độ cao tuổi một bước, ẩn nháp, lịch sử, biểu đồ', async () => {
    let el = await render('/checkin', (k) => k !== 'SCR-0301.check-in');
    expect(buttons(el)).not.toContain('Gửi check-in');
    TestBed.resetTestingModule();
    el = await render('/checkin', () => true, true);
    expect(buttons(el)).toContain('Gửi check-in');
    expect(buttons(el)).not.toContain('Lưu nháp');
    expect(buttons(el)).not.toContain('Lịch sử');
    expect(el.querySelector('vt-checkin-trend')).toBeNull();
  });

  it('chế độ cao tuổi: KR chưa từng check-in được chọn sẵn mức Trung bình, gửi ngay (NFR-11)', async () => {
    // giả như KR chưa có lần check-in nào và chưa có nháp
    const chuaCheckin: HttpInterceptorFn = (req, next) =>
      next(req).pipe(
        map((e) =>
          e instanceof HttpResponse && /\/okr\/key-results\/[^/]+$/.test(req.url)
            ? e.clone({
                body: { ...(e.body as object), lastCheckin: undefined, myDraftCheckin: undefined },
              })
            : e,
        ),
      );
    let el = await render('/checkin', () => true, false, [chuaCheckin]);
    expect([...el.querySelectorAll<HTMLInputElement>('.level input')].some((i) => i.checked)).toBe(
      false,
    );
    TestBed.resetTestingModule();
    el = await render('/checkin', () => true, true, [chuaCheckin]);
    await until(el, 'small', (s) => text(s).includes('cập nhật lúc'));
    expect(radio(el, 'Trung bình').checked).toBe(true);
    button(el, 'Gửi check-in')!.click();
    const msg = await until(el, '[role="status"]', (e) => text(e).startsWith('Đã gửi'));
    expect(text(msg)).toContain('Đã gửi check-in KR 1.1');
  });
});
