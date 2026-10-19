import { provideHttpClient, withInterceptors } from '@angular/common/http';
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
import { firstValueFrom } from 'rxjs';
import { resetMockData } from '../../../../../api-client/src/mock/mock-registry';
import { OKR_CAN, OkrPermissionKey } from '../../okr-permissions';
import { OkrReviewPage } from './okr-review.page';

/** WEB-023: SCR-0203 trên dữ liệu giả lập: hàng chờ, duyệt, trả lại có ý kiến, tách người soạn, duyệt lô. */
async function render(
  url = '/phe-duyet',
  can: (k: OkrPermissionKey) => boolean = () => true,
  elderly = false,
) {
  resetMockData();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'phe-duyet', component: OkrReviewPage },
        { path: '**', children: [] },
      ]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
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
  await harness.navigateByUrl(url, OkrReviewPage);
  const el = harness.routeNativeElement as HTMLElement;
  harness.fixture.autoDetectChanges();
  await until(el, 'vt-review-history li');
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
const rows = (el: HTMLElement) =>
  [...el.querySelectorAll('vt-review-queue .row')] as HTMLButtonElement[];

describe('OkrReviewPage (SCR-0203)', () => {
  it('hàng chờ theo đơn vị, bộ đầu tiên được chọn với chi tiết, kiểm tra và lịch sử', async () => {
    const el = await render();
    expect(text(el.querySelector('h1'))).toBe('Phê duyệt mục tiêu, OKR');
    expect(text(el.querySelector('.head'))).toContain('Quý IV/2026 · 5 bộ OKR chờ duyệt');
    expect(rows(el).map((r) => text(r.querySelector('strong')))).toEqual([
      'Ban Tuyên giáo',
      'Uỷ ban Kiểm tra',
      'Văn phòng Đảng uỷ',
      'Đảng uỷ Cơ quan A',
      'Đảng uỷ Cơ quan B',
    ]);
    expect(text(rows(el)[2])).toContain('4 / 10');
    expect(text(rows(el)[2])).toContain('Vượt 3 O');
    expect(rows(el)[0].getAttribute('aria-pressed')).toBe('true');
    const detail = text(el.querySelector('vt-review-detail'));
    expect(detail).toContain('Ban Tuyên giáo · Quý IV/2026');
    expect(detail).toContain('Chờ duyệt · phiên bản 1');
    expect(detail).toContain('KR 1.1 · 100% chi bộ tổ chức học tập nghị quyết');
    expect(detail).toContain('Đo được');
    const history = await until(el, 'vt-review-history li', (e) => text(e).includes('Phạm Thu Hà'));
    expect(text(history)).toContain('lưu nháp');
    expect(text(el.querySelector('vt-review-history'))).toContain(
      'Đang chờ: Đinh Văn Sơn (Phó Bí thư Thường trực)',
    );
    expect(buttons(el)).toEqual(expect.arrayContaining(['Trả lại để sửa', 'Phê duyệt']));
  });

  it('phê duyệt: MSG-02-09 và bộ rời hàng chờ', async () => {
    const el = await render();
    button(el, 'Phê duyệt')!.click();
    const msg = await until(el, '[role="status"]', (e) => text(e).startsWith('Đã phê duyệt'));
    expect(text(msg)).toBe('Đã phê duyệt OKR của Ban Tuyên giáo. Check-in KR đã được mở.');
    await until(el, 'vt-review-queue .row', () => rows(el).length === 4);
    expect(rows(el).map((r) => text(r.querySelector('strong')))).not.toContain('Ban Tuyên giáo');
  });

  it('trả lại không có ý kiến: MSG-02-10 cạnh ô ý kiến; có ý kiến thì trả lại được', async () => {
    const el = await render();
    button(el, 'Trả lại để sửa')!.click();
    await until(el, '#r-comment-err');
    expect(text(el.querySelector('#r-comment-err'))).toBe('Vui lòng nhập ý kiến khi trả lại.');
    expect(el.querySelector('#r-comment')!.getAttribute('aria-invalid')).toBe('true');
    const ta = el.querySelector('#r-comment') as HTMLTextAreaElement;
    ta.value = 'Đề nghị bổ sung căn cứ cho O2';
    ta.dispatchEvent(new Event('input'));
    button(el, 'Trả lại để sửa')!.click();
    const msg = await until(el, '[role="status"]', (e) => text(e).startsWith('Đã trả lại'));
    expect(text(msg)).toBe('Đã trả lại bộ OKR của Ban Tuyên giáo để sửa.');
    const h = await firstValueFrom(TestBed.inject(OkrApi).history('o-btg-1'));
    expect(h.items[0]).toMatchObject({
      action: 'TRA_LAI',
      comment: 'Đề nghị bổ sung căn cứ cho O2',
    });
  });

  it('bộ do chính người xem soạn: ẩn "Phê duyệt" và hiện MSG-02-11 (BR-02-08)', async () => {
    const el = await render('/phe-duyet?set=rs-vp');
    await until(el, 'vt-review-detail', (e) => text(e).includes('Văn phòng Đảng uỷ'));
    expect(text(el)).toContain('Đồng chí là người soạn bộ OKR này nên không thể phê duyệt.');
    expect(buttons(el)).not.toContain('Phê duyệt');
    expect(buttons(el)).toContain('Trả lại để sửa');
  });

  it('duyệt hàng loạt chỉ gồm bộ đủ điều kiện, xong thì báo kết quả job', async () => {
    const el = await render();
    button(el, 'Duyệt hàng loạt (đủ điều kiện)')!.click();
    await until(el, '.batch li');
    expect([...el.querySelectorAll('.batch li')].map(text)).toEqual([
      'Ban Tuyên giáo (2 mục tiêu, 6 KR)',
      'Uỷ ban Kiểm tra (2 mục tiêu, 5 KR)',
      'Đảng uỷ Cơ quan A (3 mục tiêu, 6 KR)',
    ]);
    const box = el.querySelector('.batch') as HTMLElement;
    expect(document.activeElement).toBe(box);
    box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await until(el, 'button', () => !el.querySelector('.batch'));
    expect(el.querySelector('.batch')).toBeNull();
    button(el, 'Duyệt hàng loạt (đủ điều kiện)')!.click();
    await until(el, '.batch li');
    button(el, 'Phê duyệt 3 bộ')!.click();
    const msg = await until(el, '[role="status"]', (e) => text(e).includes('Đã phê duyệt 7/7'));
    expect(text(msg)).toBe('Đã phê duyệt 7/7 mục tiêu của 3 bộ OKR.');
    await until(el, 'vt-review-queue .row', () => rows(el).length === 2);
  });

  it('ẩn chức năng theo quyền; chế độ cao tuổi ẩn duyệt hàng loạt', async () => {
    let el = await render('/phe-duyet', (k) => k === 'SCR-0203.xem');
    expect(buttons(el)).not.toContain('Phê duyệt');
    expect(buttons(el)).not.toContain('Trả lại để sửa');
    expect(buttons(el)).not.toContain('Duyệt hàng loạt (đủ điều kiện)');
    TestBed.resetTestingModule();
    el = await render('/phe-duyet', () => true, true);
    expect(buttons(el)).not.toContain('Duyệt hàng loạt (đủ điều kiện)');
    expect(buttons(el)).toContain('Phê duyệt');
  });

  it('kỳ không có bộ chờ duyệt: thông báo hàng chờ trống', async () => {
    const el = await render('/phe-duyet?period=p-2027-q1');
    await until(el, 'vt-review-queue p');
    expect(text(el.querySelector('vt-review-queue'))).toContain(
      'Không còn bộ OKR nào chờ đồng chí duyệt trong kỳ này.',
    );
  });
});
