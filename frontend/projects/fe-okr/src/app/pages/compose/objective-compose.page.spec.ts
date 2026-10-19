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
import { resetMockData } from '../../../../../api-client/src/mock/mock-registry';
import { ThemeService } from '@vt/design-system';
import { firstValueFrom } from 'rxjs';
import { OKR_CAN, OkrPermissionKey } from '../../okr-permissions';
import { ObjectiveComposePage } from './objective-compose.page';

/** WEB-022: SCR-0202 trên dữ liệu giả lập: soát CEL tức thì, gợi ý AI, lưu với If-Match, trình duyệt. */
async function render(
  url: string,
  can: (k: OkrPermissionKey) => boolean = () => true,
  elderly = false,
  before?: (api: OkrApi) => Promise<unknown>,
) {
  resetMockData();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'objectives/new', component: ObjectiveComposePage },
        { path: 'objectives/:id/edit', component: ObjectiveComposePage },
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
  if (before) await before(TestBed.inject(OkrApi));
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(url, ObjectiveComposePage);
  const el = harness.routeNativeElement as HTMLElement;
  harness.fixture.autoDetectChanges();
  await until(el, 'h1');
  return { harness, el };
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
const field = (el: HTMLElement, aria: string) =>
  el.querySelector(`[aria-label="${aria}"]`) as HTMLInputElement | HTMLTextAreaElement;
function type(input: HTMLInputElement | HTMLTextAreaElement, value: string) {
  input.value = value;
  input.dispatchEvent(new Event('input'));
}
function choose(select: HTMLSelectElement, index: number) {
  select.selectedIndex = index;
  select.dispatchEvent(new Event('change'));
}
const tick = () => new Promise((r) => setTimeout(r, 30));

describe('ObjectiveComposePage (SCR-0202)', () => {
  it('mở bản nháp O3: KR 3.3 mơ hồ chặn trình, KR 3.4 cảnh báo giống nhiệm vụ', async () => {
    const { el } = await render('/objectives/o-3/edit');
    expect(text(el.querySelector('h1'))).toBe('Sửa mục tiêu O3 quý IV/2026');
    expect(text(el.querySelector('.head'))).toContain('bản nháp 3');
    expect(field(el, 'Nội dung KR 3.1').value).toContain('100% chi bộ');
    expect(text(el.querySelector('.foot'))).toContain('Tổng trọng số: 100%');
    expect(text(el.querySelector('.foot'))).toContain('2 lỗi cần sửa trước khi trình');
    expect(text(el.querySelector('.foot'))).toContain('1 cảnh báo');
    expect(text(el.querySelector('table.kr'))).toContain('KPI mơ hồ: "tăng cường" không đo được.');
    expect(text(el.querySelector('table.kr'))).toContain('KR 3.4 giống một nhiệm vụ');
    expect(field(el, 'Nội dung KR 3.3').getAttribute('aria-invalid')).toBe('true');
    const check = text(el.querySelector('.check'));
    expect(check).toContain('Đạt: Có căn cứ');
    expect(check).toContain('Đạt: Tổng trọng số KR = 100%');
    expect(button(el, 'Trình phê duyệt')!.disabled).toBe(true);
  });

  it('trọng số lệch 100% hiện MSG-02-07 ngay khi nhập', async () => {
    const { el } = await render('/objectives/o-3/edit');
    type(field(el, 'Trọng số phần trăm KR 3.1'), '20');
    await tick();
    expect(text(el.querySelector('.foot'))).toContain('Tổng trọng số: 80%');
    expect(text(el.querySelector('.check'))).toContain('Tổng trọng số KR là 80%, cần bằng 100%.');
  });

  it('dùng gợi ý AI: điền ô với viền tím, sửa đủ KR rồi trình được (MSG-02-08)', async () => {
    const { el } = await render('/objectives/o-3/edit');
    button(el, 'Trợ lý AI soát KR')!.click();
    await until(el, 'vt-ai-review-panel .ai');
    expect(text(el.querySelector('vt-ai-review-panel .ai'))).toContain('KR 3.3 chưa đo được');
    button(el, 'Dùng gợi ý vào ô KR 3.3')!.click();
    await tick();
    const title = field(el, 'Nội dung KR 3.3');
    expect(title.value).toBe('Kiểm tra 100% chi bộ trực thuộc trước 31/12/2026');
    expect(title.classList).toContain('ai');
    type(field(el, 'Đơn vị đo KR 3.3'), '%');
    type(field(el, 'Giá trị đầu KR 3.3'), '60');
    type(field(el, 'Chỉ tiêu KR 3.3'), '100');
    type(field(el, 'Hạn KR 3.3'), '2026-12-31');
    choose(field(el, 'Người xác nhận KR 3.3') as unknown as HTMLSelectElement, 1);
    await tick();
    expect(text(el.querySelector('.foot'))).not.toContain('lỗi cần sửa');
    const submit = button(el, 'Trình phê duyệt')!;
    expect(submit.disabled).toBe(false);
    submit.click();
    const ok = await until(el, '[role="status"]', (e) => text(e).startsWith('Đã trình'));
    expect(text(ok)).toBe(
      'Đã trình phê duyệt. Người duyệt: Phó Bí thư Thường trực Đinh Văn Sơn, hạn 10/10/2026.',
    );
    const saved = await firstValueFrom(TestBed.inject(OkrApi).objective('o-3'));
    expect(saved.status).toBe('CHO_DUYET');
    expect(saved.keyResults[2].contentSource).toBe('AI_ACCEPTED');
    expect(buttons(el)).not.toContain('Lưu nháp');
  });

  it('người khác vừa lưu: hiện so sánh xung đột thay vì ghi đè (409)', async () => {
    const { el } = await render('/objectives/o-3/edit');
    const api = TestBed.inject(OkrApi);
    const d = await firstValueFrom(api.objective('o-3'));
    await firstValueFrom(
      api.saveObjective('o-3', d.rowVersion, { ...d, title: 'Bản của người khác' }),
    );
    type(field(el, 'Nội dung KR 3.1') as HTMLTextAreaElement, '100% chi bộ sinh hoạt chuyên đề');
    button(el, 'Lưu nháp')!.click();
    const notice = await until(el, 'vt-conflict-notice table');
    expect(text(notice)).toContain('Bản của người khác');
  });

  it('mục tiêu mới khi kỳ đã đủ 3: MSG-02-04 và bắt buộc lý do vượt', async () => {
    const { el } = await render('/objectives/new?period=p-2026-q4');
    expect(text(el.querySelector('h1'))).toBe('Soạn mục tiêu quý IV/2026');
    expect(text(el.querySelector('.banner'))).toContain('Kỳ Quý IV/2026 đã có 3 mục tiêu');
    expect(el.querySelector('#f-reason')).toBeTruthy();
    expect(text(el.querySelector('.check'))).toContain('Vui lòng ghi lý do vượt 3 mục tiêu.');
    // Biểu mẫu trống chưa nhập: không dồn lỗi đỏ cạnh ô, chỉ danh sách kiểm tra
    expect(el.querySelector('table.kr .findings')).toBeNull();
    type(field(el, 'Nội dung KR 4.1'), 'Tăng cường kiểm tra');
    await tick();
    expect(text(el.querySelector('table.kr'))).toContain('KPI mơ hồ');
    expect(el.querySelectorAll('table.kr tbody tr')).toHaveLength(2);
    expect(field(el, 'Trọng số phần trăm KR 4.1').value).toBe('50');
  });

  it('ẩn trợ lý AI và nút trình khi không có quyền; chế độ cao tuổi ẩn trợ lý AI', async () => {
    let { el } = await render(
      '/objectives/o-3/edit',
      (k) => k !== 'SCR-0202.ai-soat' && k !== 'SCR-0202.trinh',
    );
    expect(buttons(el)).not.toContain('Trợ lý AI soát KR');
    expect(buttons(el)).not.toContain('Trình phê duyệt');
    expect(buttons(el)).toContain('Lưu nháp');
    TestBed.resetTestingModule();
    ({ el } = await render('/objectives/o-3/edit', () => true, true));
    expect(buttons(el)).not.toContain('Trợ lý AI soát KR');
    expect(buttons(el)).toContain('Trình phê duyệt');
  });

  it('mục tiêu đã trình thì chỉ xem, không sửa tại chỗ (BR-02-10)', async () => {
    const { el } = await render(
      '/objectives/o-3/edit',
      () => true,
      false,
      async (api) => {
        const d = await firstValueFrom(api.objective('o-3'));
        const krs = d.keyResults.filter((k) => k.code !== 'KR 3.3');
        krs[0] = { ...krs[0], weight: 0.7 };
        await firstValueFrom(api.saveObjective('o-3', d.rowVersion, { ...d, keyResults: krs }));
        return firstValueFrom(api.submitObjective('o-3'));
      },
    );
    expect(text(el)).toContain('nên không sửa trực tiếp được');
    expect(buttons(el)).not.toContain('Lưu nháp');
    expect(field(el, 'Nội dung KR 3.1').closest('fieldset')!.disabled).toBe(true);
  });

  it('máy chủ chặn 403 (mục tiêu của đơn vị khác): hiện đúng thông báo, không hiện biểu mẫu', async () => {
    const { el } = await render('/objectives/o-duk-1/edit');
    await until(el, 'p-message');
    expect(text(el)).toContain('Đồng chí không có quyền sửa mục tiêu của đơn vị khác.');
    expect(el.querySelector('table.kr')).toBeNull();
  });

  it('mục tiêu không tồn tại: báo lỗi và đường về cây', async () => {
    const { el } = await render('/objectives/khong-co/edit');
    await until(el, 'p-message');
    expect(text(el)).toContain('Không tìm thấy mục tiêu');
  });
});
