import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { DEFAULT_RUNTIME_CONFIG, JOB_TRACKER_OPTIONS, MOCK_API_OPTIONS, RUNTIME_CONFIG, idempotencyInterceptor, mockApiInterceptor, mockStepUp, setMockEsignOutage } from '@vt/api-client';
import { AppraisalApi } from '@vt/api-client/domains';
import { ThemeService } from '@vt/design-system';
import { firstValueFrom } from 'rxjs';
import { resetMockData } from '../../../../../api-client/src/mock/mock-registry';
import { mockSession } from '../../../../../api-client/src/mock/mock-session';
import { APPRAISAL_CAN, AppraisalPermissionKey } from '../../appraisal-permissions';
import { APPRAISAL_STEP_UP } from '../self-assessment/step-up';
import { ReviewPage } from './review.page';

/** WEB-036: SCR-0902 trên dữ liệu giả lập: hàng đợi, so sánh B, C, giải trình, ký, trả lại, xuất. */
async function render(
  url = '/tham-dinh',
  opts: { can?: (k: AppraisalPermissionKey) => boolean; elderly?: boolean } = {},
) {
  TestBed.resetTestingModule();
  resetMockData();
  mockSession.reset();
  TestBed.configureTestingModule({
    providers: [
      provideRouter(
        [
          { path: 'tham-dinh', component: ReviewPage },
          { path: 'tham-dinh/:id', component: ReviewPage },
          { path: '**', children: [] },
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
        useValue: { initialDelayMs: 5, maxDelayMs: 5, maxRetries: 0 },
      },
      { provide: APPRAISAL_CAN, useValue: opts.can ?? (() => true) },
      {
        provide: APPRAISAL_STEP_UP,
        useValue: async () => {
          mockStepUp();
          return true;
        },
      },
    ],
  });
  if (opts.elderly) {
    const theme = TestBed.inject(ThemeService);
    theme.mode.set('elderly');
    theme.reducedSteps.set(true);
  }
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(url, ReviewPage);
  const el = harness.routeNativeElement as HTMLElement;
  harness.fixture.autoDetectChanges();
  await until(el, '.stat .mid', (e) => /HT/.test(e.textContent ?? ''));
  return { el, harness };
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 150; i++) {
    const e = [...el.querySelectorAll(sel)].find(test);
    if (e) return e;
    await new Promise((r) => setTimeout(r, 20));
  }
  return null;
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const button = (el: HTMLElement, label: string) =>
  [...el.querySelectorAll('button')].find((b) => text(b) === label) as HTMLButtonElement;
function type(el: HTMLElement, sel: string, value: string) {
  const box = el.querySelector(sel) as HTMLTextAreaElement | HTMLSelectElement;
  box.value = value;
  box.dispatchEvent(new Event(box.tagName === 'SELECT' ? 'change' : 'input'));
  box.dispatchEvent(new Event('blur'));
}

const P = '01928f3e-7b1a-7c3e-9a51-3f2d9c1b';
const COMMENT = 'Hoàn thành tốt các nhiệm vụ then chốt; tỷ lệ việc vượt 22% chưa đạt ngưỡng 30%.';

describe('ReviewPage (SCR-0902, WEB-036)', () => {
  afterEach(() => setMockEsignOutage(false));

  it('hàng đợi sắp theo hạn; mở hồ sơ đầu tiên với bốn thẻ số liệu và bảng so sánh B, C', async () => {
    const { el } = await render(`/tham-dinh/${P}0a31`);
    const rows = [...el.querySelectorAll('.qrow')];
    expect(rows.length).toBe(7);
    expect(text(el.querySelector('.qrow.on'))).toContain('Trần Minh Hoàng');
    expect(text(el.querySelector('.qrow.on'))).toContain('Tự chấm cao 3 quý');
    expect(text(el.querySelector('h1'))).toBe('Thẩm định hồ sơ đánh giá · Quý IV/2026');
    const stats = [...el.querySelectorAll('.stat')].map(text);
    expect(stats[0]).toContain('93,4');
    expect(stats[1]).toContain('91,2');
    expect(stats[1]).toContain('−2,2 do điều chỉnh chất lượng, tiến độ');
    expect(stats[2]).toContain('Thực hiện tốt');
    expect(stats[3]).toContain('HTTNV');
    expect(text(el.querySelector('.who-head'))).toContain('đã ký số MySign');
    const cmp = [...el.querySelectorAll('tbody tr')].map(text);
    expect(cmp).toHaveLength(4);
    expect(cmp[1]).toContain('Chất lượng: phải sửa nội dung cơ bản');
    expect(el.querySelectorAll('tbody td.bad').length).toBeGreaterThan(0);
    expect(el.querySelector('a.ev')?.getAttribute('href')).toContain('/evidence?task=');
  });

  it('mức mặc định bằng mức hệ thống; khác mức tự đề xuất thì hiện MSG-09-06 và chặn ký tới khi đủ lý do', async () => {
    const { el } = await render(`/tham-dinh/${P}0a31`);
    expect((el.querySelector('#f-rating') as HTMLSelectElement).value).toBe('HTTNV');
    expect(text(el.querySelector('.warn'))).toContain('Mức thẩm định khác mức tự đề xuất');
    type(el, '#f-comment', COMMENT);
    button(el, 'Ký số và chuyển đề xuất').click();
    await until(el, '.field-err');
    expect(text(el.querySelector('#e-reason'))).toContain('từ 20 đến 2.000 ký tự');
    type(el, '#f-rating', 'HTXSNV');
    await until(el, '#act-title', (e) => text(e) === 'Ký thẩm định');
    expect(el.querySelector('.warn')).toBeNull();
  });

  it('ký số: MFA, job ký, hồ sơ DA_THAM_DINH và rời hàng đợi', async () => {
    const { el } = await render(`/tham-dinh/${P}0a31`);
    type(el, '#f-comment', COMMENT);
    type(el, '#f-reason', 'Tỷ lệ nhiệm vụ vượt mục tiêu 22%, thấp hơn ngưỡng 30% (QĐ Điều 11).');
    button(el, 'Ký số và chuyển đề xuất').click();
    const ok = await until(el, '.msg.ok', (e) => /Đã ký số kết quả thẩm định/.test(text(e)));
    expect(text(ok)).toContain('Trần Minh Hoàng');
    expect(text(await until(el, '#done-title'))).toBe('Kết luận thẩm định');
    expect(text(el.querySelector('.state'))).toBe('Đã thẩm định');
    await until(el, '.queue', (e) => e.querySelectorAll('.qrow').length === 6);
    expect(el.querySelectorAll('.qrow').length).toBe(6);
    expect(text(el.querySelector('.queue'))).not.toContain('Trần Minh Hoàng');
  });

  it('tách vai trò: máy chủ chặn 403 thì hiện MSG-09-08', async () => {
    const { el } = await render(`/tham-dinh/${P}0a35`);
    type(el, '#f-comment', COMMENT);
    button(el, 'Ký số và chuyển đề xuất').click();
    const err = await until(el, '.msg.err');
    expect(text(err)).toContain(
      'đã tham gia nghiệm thu một nhiệm vụ của hồ sơ (Phụ lục 4, mục V.1)',
    );
  });

  it('trả lại bổ sung: bắt buộc lý do, MSG-09-09; hồ sơ đã trả đủ số lần thì báo lỗi 409', async () => {
    const { el } = await render(`/tham-dinh/${P}0a33`);
    button(el, 'Trả lại bổ sung').click();
    await until(el, '#f-return');
    expect(document.activeElement?.id).toBe('f-return');
    button(el, 'Xác nhận trả lại').click();
    expect(text(await until(el, '#e-return'))).toContain('ít nhất 20 ký tự');
    type(el, '#f-return', 'Đề nghị bổ sung biên bản nghiệm thu nhiệm vụ then chốt số 2.');
    button(el, 'Xác nhận trả lại').click();
    const ok = await until(el, '.msg.ok');
    expect(text(ok)).toBe(
      'Đã trả lại hồ sơ cho đồng chí Nguyễn Hải Yến bổ sung. Lần trả lại thứ 1/2.',
    );

    const again = await render(`/tham-dinh/${P}0a36`);
    button(again.el, 'Trả lại bổ sung').click();
    await until(again.el, '#f-return');
    type(again.el, '#f-return', 'Đề nghị bổ sung giải trình chậm hạn nhiệm vụ then chốt.');
    button(again.el, 'Xác nhận trả lại').click();
    expect(text(await until(again.el, '.msg.err'))).toContain('2/2 lần');
  });

  it('ẩn ký và trả lại khi máy chủ không cho phép trên hồ sơ (permittedActions, YC-PT-21)', async () => {
    const { el, harness } = await render(`/tham-dinh/${P}0a31`);
    expect(button(el, 'Ký số và chuyển đề xuất')).toBeDefined();
    const page = harness.routeDebugElement!.componentInstance as {
      profile: { update(f: (p: object | undefined) => object | undefined): void };
    };
    page.profile.update((p) => (p ? { ...p, permittedActions: ['SCR-0902.tra-lai'] } : p));
    harness.fixture.detectChanges();
    expect(button(el, 'Ký số và chuyển đề xuất')).toBeUndefined();
    expect(button(el, 'Trả lại bổ sung')).toBeDefined();
    page.profile.update((p) => (p ? { ...p, permittedActions: [] } : p));
    harness.fixture.detectChanges();
    expect(button(el, 'Trả lại bổ sung')).toBeUndefined();
  });

  it('ẩn nút theo ma trận quyền; chế độ cao tuổi ẩn bảng so sánh và xuất; xuất danh sách sinh liên kết tải', async () => {
    const view = await render(`/tham-dinh/${P}0a31`, {
      can: (k) => k === 'SCR-0902.xem',
    });
    expect(button(view.el, 'Ký số và chuyển đề xuất')).toBeUndefined();
    expect(button(view.el, 'Trả lại bổ sung')).toBeUndefined();
    expect(button(view.el, 'Xuất danh sách')).toBeUndefined();

    const old = await render(`/tham-dinh/${P}0a31`, { elderly: true });
    expect(old.el.querySelector('#cmp-title')).toBeNull();
    expect(button(old.el, 'Xuất danh sách')).toBeUndefined();
    expect(old.el.querySelector('.layout.simple')).not.toBeNull();

    const full = await render();
    button(full.el, 'Xuất danh sách').click();
    const link = await until(full.el, 'a[download]');
    expect(link?.getAttribute('href')).toMatch(/^blob:/);
    expect(document.activeElement).toBe(link);
    const api = TestBed.inject(AppraisalApi);
    expect((await firstValueFrom(api.profiles())).items.length).toBe(7);
  });
});
