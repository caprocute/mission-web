import {
  HttpErrorResponse,
  HttpInterceptorFn,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { throwError } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { EVIDENCE_CAN } from '../evidence-permissions';
import { REVIEW_STEP_UP } from '../review-step-up';
import { EvidenceReviewPage } from './evidence-review.page';

/** WEB-033: SCR-0703 trên dữ liệu giả lập: mở và kiểm toàn vẹn, đối chiếu, chọn mức, xem trước điểm, quyết định. */
const R = '0192a1b2-0000-7000-8000-000000000138';

/** Máy chủ chưa có lần tính điểm nào chứa nhiệm vụ: `score-preview` trả 422. */
const previewUnavailable: HttpInterceptorFn = (req, next) =>
  req.url.endsWith('/appraisals/score-preview')
    ? throwError(() => new HttpErrorResponse({ status: 422, url: req.url }))
    : next(req);

async function render(
  id = R,
  can: (k: string) => boolean = () => true,
  extra: HttpInterceptorFn[] = [],
) {
  const stepUp = vi.fn(async () => {
    (await import('@vt/api-client')).mockStepUp();
    return true;
  });
  TestBed.configureTestingModule({
    imports: [EvidenceReviewPage],
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([...extra, idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      { provide: EVIDENCE_CAN, useValue: can },
      { provide: REVIEW_STEP_UP, useValue: stepUp },
    ],
  });
  const fixture = TestBed.createComponent(EvidenceReviewPage);
  fixture.componentRef.setInput('id', id);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  await until(() => el.querySelector('.layout') || el.querySelector('.msg.err'));
  return { el, stepUp };
}

async function until(test: () => unknown) {
  for (let i = 0; i < 250; i++) {
    if (test()) return;
    await new Promise((r) => setTimeout(r, 20));
  }
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const button = (el: HTMLElement, label: string) =>
  [...el.querySelectorAll('button')].find((b) => text(b) === label) as HTMLButtonElement;
const radios = (el: HTMLElement, name: string) =>
  [...el.querySelectorAll(`input[type=radio][name="${name}"]`)] as HTMLInputElement[];

function passAll(el: HTMLElement) {
  for (const i of [0, 1, 2]) radios(el, 'c' + i)[0].click();
}

function type(el: HTMLElement, sel: string, value: string) {
  const i = el.querySelector(sel) as HTMLTextAreaElement;
  i.value = value;
  i.dispatchEvent(new Event('input'));
}

describe('EvidenceReviewPage (SCR-0703)', () => {
  beforeEach(async () => (await import('@vt/api-client')).resetMockApi());

  it('mở nghiệm thu như Hình 8.15: đang nghiệm thu, đã kiểm toàn vẹn, MSG-07-11, ba tiêu chuẩn', async () => {
    const { el } = await render();
    expect(text(el.querySelector('.top .chip'))).toBe('Đang nghiệm thu');
    expect(text(el.querySelector('.sub'))).toContain('người thực hiện Nguyễn Văn An');
    expect(text(el.querySelector('.files'))).toContain('đã kiểm tra toàn vẹn');
    expect(text(el.querySelector('.msg.warn'))).toContain('NV-2026-Q3-0088');
    expect(el.querySelectorAll('.tbl tbody tr')).toHaveLength(3);
    // MSG-07-09: chưa đánh giá đủ tiêu chuẩn thì chưa chọn được mức
    expect((el.querySelector('.levels') as HTMLFieldSetElement).disabled).toBe(true);
    expect(text(el.querySelector('#lv-hint'))).toContain('đánh giá tất cả tiêu chuẩn');
  });

  it('chọn mức 0,75 xem trước KPI 0,883; xác nhận cần nhận xét và nguyên nhân, qua MFA', async () => {
    const { el, stepUp } = await render();
    passAll(el);
    await until(() => !(el.querySelector('.levels') as HTMLFieldSetElement).disabled);
    radios(el, 'level')[1].click();
    radios(el, 'cause')[0].click();
    await until(() => el.querySelector('.kpi'));
    expect(text(el.querySelector('.kpi'))).toBe('0,883');
    expect(button(el, 'Xác nhận nghiệm thu').disabled).toBe(true);
    expect(text(el.querySelector('#acc-why'))).toContain('ghi nhận xét');
    type(el, '#cmt', 'Phiên bản 1 thiếu số liệu chi bộ 3 và 6; đã bổ sung đủ.');
    await until(() => !button(el, 'Xác nhận nghiệm thu').disabled);
    button(el, 'Xác nhận nghiệm thu').click();
    await until(() => el.querySelector('.msg.ok'));
    expect(stepUp).toHaveBeenCalled();
    expect(text(el.querySelector('.msg.ok'))).toBe(
      'Đã xác nhận nghiệm thu mức 0,75. Điểm nhiệm vụ đang được tính.',
    );
    await until(() => el.querySelector('#dec-h'));
    expect(text(el.querySelector('.meta'))).toContain('Đã xác nhận');
  });

  it('xem trước điểm chưa có dữ liệu: báo rõ, vẫn xác nhận được (QA-05 HT-21)', async () => {
    const { el } = await render(R, () => true, [previewUnavailable]);
    passAll(el);
    await until(() => !(el.querySelector('.levels') as HTMLFieldSetElement).disabled);
    radios(el, 'level')[0].click();
    await until(() => text(el).includes('Chưa xem trước được điểm'));
    expect(el.querySelector('.kpi')).toBeNull();
    radios(el, 'cause')[0]?.click();
    await until(() => !button(el, 'Xác nhận nghiệm thu').disabled);
    expect(button(el, 'Xác nhận nghiệm thu').disabled).toBe(false);
  });

  it('yêu cầu bổ sung bắt buộc nhận xét (BR-07-08)', async () => {
    const { el } = await render();
    button(el, 'Yêu cầu bổ sung').click();
    await until(() => el.querySelector('.msg.err'));
    expect(text(el.querySelector('.msg.err'))).toContain('Ghi nội dung cần bổ sung');
    type(el, '#cmt', 'Bổ sung biên bản kiểm tra chi bộ 5');
    button(el, 'Yêu cầu bổ sung').click();
    await until(() => el.querySelector('.msg.ok'));
    expect(text(el.querySelector('.top .chip'))).toBe('Yêu cầu bổ sung');
  });

  it('toàn vẹn thất bại chặn nghiệm thu (MSG-07-13); ', async () => {
    (await import('@vt/api-client')).failMockIntegrityOnce();
    const { el } = await render();
    await until(() => el.querySelector('.msg.err'));
    expect(text(el.querySelector('.msg.err'))).toContain('Toàn vẹn minh chứng không được bảo đảm');
    expect(button(el, 'Xác nhận nghiệm thu')).toBeUndefined();
  });

  it('phiên bản chưa nộp không nghiệm thu được: không có nút quyết định', async () => {
    const { el } = await render('0192a1b2-0000-7000-8000-000000000142');
    await until(() => el.querySelector('.top .chip'));
    expect(text(el.querySelector('.top .chip'))).toBe('Nháp');
    expect(button(el, 'Xác nhận nghiệm thu')).toBeUndefined();
  });
});
