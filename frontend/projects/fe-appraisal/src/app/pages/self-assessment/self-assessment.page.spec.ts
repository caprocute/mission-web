import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import {
  AppraisalApi,
  DEFAULT_RUNTIME_CONFIG,
  JOB_TRACKER_OPTIONS,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
  mockStepUp,
  setMockEsignOutage,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { firstValueFrom } from 'rxjs';
import { resetMockData } from '../../../../../api-client/src/mock/mock-registry';
import { mockSession } from '../../../../../api-client/src/mock/mock-session';
import { APPRAISAL_CAN, AppraisalPermissionKey } from '../../appraisal-permissions';
import { SELF_AUTOSAVE_MS, SelfAssessmentPage } from './self-assessment.page';
import { APPRAISAL_STEP_UP } from './step-up';

/** WEB-035: SCR-0901 trên dữ liệu giả lập: tiêu chí chung, tự đề xuất, giải trình, lưu nháp, ký số. */
async function render(
  url = '/tu-danh-gia',
  opts: {
    can?: (k: AppraisalPermissionKey) => boolean;
    elderly?: boolean;
    autosaveMs?: number;
  } = {},
) {
  resetMockData();
  mockSession.reset();
  TestBed.configureTestingModule({
    providers: [
      provideRouter(
        [
          { path: 'tu-danh-gia', component: SelfAssessmentPage },
          { path: 'tu-danh-gia/:id', component: SelfAssessmentPage },
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
      { provide: SELF_AUTOSAVE_MS, useValue: opts.autosaveMs ?? 600_000 },
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
  await harness.navigateByUrl(url, SelfAssessmentPage);
  const el = harness.routeNativeElement as HTMLElement;
  harness.fixture.autoDetectChanges();
  await until(el, 'vt-self-rating select');
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
  [...el.querySelectorAll('button')].find((b) => text(b) === label) as
    HTMLButtonElement | undefined;
const field = (el: HTMLElement, id: string) =>
  el.querySelector(`#${id}`) as HTMLTextAreaElement | HTMLSelectElement;
function type(i: HTMLTextAreaElement | HTMLSelectElement, v: string) {
  i.value = v;
  i.dispatchEvent(new Event(i instanceof HTMLSelectElement ? 'change' : 'input'));
}
const tick = (ms = 40) => new Promise((r) => setTimeout(r, ms));
const DANG = '01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a01';
const CONTENT =
  'Ưu điểm: hoàn thành đúng hạn; hạn chế: chậm một báo cáo chuyên đề; khắc phục: lập lịch.';

describe('SelfAssessmentPage (SCR-0901)', () => {
  afterEach(() => setMockEsignOutage(false));

  it('mở hồ sơ luồng Đảng: tiêu chí chung, bảng kết quả, mức tham khảo, nút nộp chờ đủ trường', async () => {
    const { el } = await render();
    expect(text(el.querySelector('h1'))).toBe('Phiếu tự đánh giá · Quý IV/2026');
    expect(text(el.querySelector('.sub'))).toContain('Hạn nộp 05/01/2027 (còn 3 ngày làm việc)');
    expect(el.querySelectorAll('vt-general-criteria input[type=checkbox]').length).toBe(9);
    expect(text(el.querySelector('vt-general-criteria dl'))).toContain('9/9 · 100%');
    expect(text(el.querySelector('vt-score-result .total'))).toContain('91,2');
    expect(text(el.querySelector('[role=tab][aria-selected=true]'))).toBe(
      'Luồng Đảng · thang 100 điểm · Bộ quy định đánh giá khối Đảng, phiên bản 2.0',
    );
    expect(field(el, 'sa-rating').value).toBe('HTTNV');
    expect(text(el.querySelector('.why'))).toContain(
      'chưa đạt điều kiện 30% nhiệm vụ vượt mục tiêu',
    );
    expect(button(el, 'Ký số và nộp')!.disabled).toBe(true);
    expect(text(el.querySelector('#missing'))).toContain('ưu điểm, hạn chế');
    el.querySelectorAll<HTMLInputElement>('vt-general-criteria input')[2].click();
    await tick();
    const sum = text(el.querySelector('vt-general-criteria dl'));
    expect(sum).toContain('8/9 · 89%');
    expect(sum).toContain('Cơ bản đảm bảo');
  });

  it('chọn HTXSNV: MSG-09-02 cao hơn mức tham khảo, MSG-09-03, gắn minh chứng rồi ký số và nộp (MSG-09-04)', async () => {
    const { el } = await render();
    type(field(el, 'sa-rating'), 'HTXSNV');
    await tick();
    expect(text(el.querySelector('.warn'))).toBe(
      'Mức hệ thống tham khảo: Hoàn thành tốt nhiệm vụ. Mức tự đề xuất cao hơn nên phải ghi giải trình (Điều 16 Quy định 366).',
    );
    type(field(el, 'sa-content'), CONTENT);
    await tick();
    expect(text(el.querySelector('#missing'))).toContain('giải pháp đột phá');
    type(field(el, 'sa-breakthrough'), 'Đưa phần mềm thẩm định hồ sơ quy hoạch vào sử dụng.');
    type(field(el, 'sa-reason'), 'Giải pháp số hoá được đơn vị ghi nhận bằng quyết định.');
    const ev = (await until(el, '.ev input')) as HTMLInputElement;
    expect(text(ev.parentElement)).toContain('QĐ 112-QĐ/ĐUK');
    ev.click();
    await tick();
    const sign = button(el, 'Ký số và nộp')!;
    expect(sign.disabled).toBe(false);
    sign.click();
    const ok = await until(el, '[role=status]', (e) => text(e).startsWith('Đã nộp phiếu'));
    expect(text(ok)).toMatch(
      /^Đã nộp phiếu tự đánh giá và ký số thành công lúc \d\d:\d\d .*Phiếu đã chuyển sang bước thẩm định\.$/,
    );
    expect(text(el.querySelector('.state'))).toBe('Trạng thái: Chờ thẩm định');
    expect(button(el, 'Ký số và nộp')).toBeUndefined();
    expect((field(el, 'sa-content') as HTMLTextAreaElement).readOnly).toBe(true);
  });

  it('ký lỗi: MSG-09-05, hồ sơ giữ TU_DANH_GIA và dữ liệu đã nhập', async () => {
    const { el } = await render();
    type(field(el, 'sa-content'), CONTENT);
    await tick();
    setMockEsignOutage(true);
    button(el, 'Ký số và nộp')!.click();
    const err = await until(el, '[role=alert]', (e) =>
      text(e).startsWith('Ký số không thành công'),
    );
    expect(text(err)).toBe(
      'Ký số không thành công: dịch vụ ký số từ xa không phản hồi. Phiếu chưa được nộp, dữ liệu đã nhập vẫn được giữ.',
    );
    expect(text(el.querySelector('.state'))).toBe('Trạng thái: Đang tự đánh giá');
    expect(field(el, 'sa-content').value).toBe(CONTENT);
  });

  it('lưu nháp tay và tự lưu theo chu kỳ (trường 12)', async () => {
    const { el } = await render('/tu-danh-gia', { autosaveMs: 60 });
    type(field(el, 'sa-content'), 'Bản nháp đầu');
    const auto = await until(el, '.hint', (e) => text(e).startsWith('Đã tự lưu nháp lúc'));
    expect(auto).toBeTruthy();
    let p = await firstValueFrom(TestBed.inject(AppraisalApi).profile(DANG));
    expect(p.selfAssessment?.content).toBe('Bản nháp đầu');
    type(field(el, 'sa-content'), 'Bản nháp hai');
    await tick(5);
    button(el, 'Lưu nháp')?.click();
    await until(el, '[role=status]', (e) => text(e).startsWith('Đã lưu nháp'));
    p = await firstValueFrom(TestBed.inject(AppraisalApi).profile(DANG));
    expect(p.selfAssessment?.content).toBe('Bản nháp hai');
  });

  it('chuyển thẻ luồng Nhà nước, mở vết tính và đóng bằng Esc, xem lịch sử', async () => {
    const { el } = await render();
    const tabs = [...el.querySelectorAll<HTMLButtonElement>('[role=tab]')];
    expect(tabs.map(text)).toEqual([
      'Luồng Đảng · thang 100 điểm · Bộ quy định đánh giá khối Đảng, phiên bản 2.0',
      'Luồng Nhà nước · thang 30/70 điểm',
      'Lịch sử',
    ]);
    tabs[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await until(el, 'vt-score-result .total', (e) => text(e).includes('91,8'));
    expect(text(el.querySelector('vt-score-result .extra'))).toContain('28,0');
    expect(text(el.querySelector('[role=tab][aria-selected=true]'))).toBe(
      'Luồng Nhà nước · thang 30/70 điểm · Bộ quy định đánh giá NĐ 335/2025, phiên bản 1.3',
    );
    button(el, 'Vết tính')!.click();
    const panel = (await until(el, 'vt-score-trace .panel')) as HTMLElement;
    await tick();
    expect(document.activeElement).toBe(panel);
    expect(text(panel)).toContain('KPI = (A + B + C) / 3');
    panel.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await tick();
    expect(el.querySelector('vt-score-trace')).toBeNull();
    [...el.querySelectorAll<HTMLButtonElement>('[role=tab]')][2].click();
    await tick();
    expect(text(el.querySelector('vt-rating-trail'))).toContain('Chưa có mức nào được ghi');
  });

  it('quản lý xem hồ sơ cấp dưới chỉ đọc; chế độ cao tuổi ẩn lưu nháp, vết tính, lịch sử', async () => {
    let { el } = await render('/tu-danh-gia/01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a21', {
      can: (k) => k === 'SCR-0901.xem',
    });
    expect(text(el.querySelector('.sub'))).toContain('Lê Thu Hà');
    expect(button(el, 'Ký số và nộp')).toBeUndefined();
    expect(button(el, 'Lưu nháp')).toBeUndefined();
    expect((el.querySelector('vt-general-criteria fieldset') as HTMLFieldSetElement).disabled).toBe(
      true,
    );
    expect((field(el, 'sa-content') as HTMLTextAreaElement).readOnly).toBe(true);
    TestBed.resetTestingModule();
    ({ el } = await render('/tu-danh-gia', { elderly: true }));
    expect(button(el, 'Ký số và nộp')).toBeTruthy();
    expect(button(el, 'Lưu nháp')).toBeUndefined();
    expect(button(el, 'Vết tính')).toBeUndefined();
    expect([...el.querySelectorAll('[role=tab]')].map(text)).not.toContain('Lịch sử');
  });
});
