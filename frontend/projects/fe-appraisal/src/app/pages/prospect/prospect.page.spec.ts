import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { resetMockData } from '../../../../../api-client/src/mock/mock-registry';
import { APPRAISAL_CAN, AppraisalPermissionKey } from '../../appraisal-permissions';
import { APPRAISAL_CURRENT_UNIT } from './current-unit';
import { ProspectPage } from './prospect.page';

/** WEB-039: SCR-1002 trên dữ liệu giả lập: bốn nhóm, bàn phím, BR-10-08, BR-10-09, lưu và trình. */
const LEADER: AppraisalPermissionKey[] = ['SCR-1002.xem', 'SCR-1002.xep-nhom', 'SCR-1002.trinh'];

async function render(url = '/phan-nhom', keys = LEADER) {
  TestBed.resetTestingModule();
  resetMockData();
  TestBed.configureTestingModule({
    providers: [
      provideRouter(
        [
          { path: 'phan-nhom', component: ProspectPage },
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
      { provide: APPRAISAL_CAN, useValue: (k: AppraisalPermissionKey) => keys.includes(k) },
      { provide: APPRAISAL_CURRENT_UNIT, useValue: () => null },
    ],
  });
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(url, ProspectPage);
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
const tick = () => new Promise((r) => setTimeout(r, 30));
const text = (e: Element | null | undefined) => (e?.textContent ?? '').replace(/\s+/g, ' ').trim();
const button = (root: Element, label: string) =>
  [...root.querySelectorAll('button')].find((b) => text(b) === label) as HTMLButtonElement;
const cardOf = (el: HTMLElement, name: string) =>
  [...el.querySelectorAll('.pc')].find(
    (c) => text(c.querySelector('.pname')) === name,
  ) as HTMLElement;
const groupOf = (el: HTMLElement, name: string) =>
  cardOf(el, name).closest('section')!.getAttribute('data-group');

describe('ProspectPage (WEB-039, SCR-1002)', { timeout: 15000 }, () => {
  it('bốn nhóm theo tiêu chí của khối; thẻ khác gợi ý được chọn sẵn với lý do', async () => {
    const { el } = await render();
    await until(el, '.pc');
    await until(el, '.gname', (e) => text(e) === 'Nhóm 1 – Phát triển vượt trội');
    expect([...el.querySelectorAll('.gname')].map(text)).toEqual([
      'Nhóm 1 – Phát triển vượt trội',
      'Nhóm 2 – Có triển vọng',
      'Nhóm 3 – Cần bồi dưỡng thêm',
      'Nhóm 4 – Cần xem xét bố trí',
    ]);
    expect(el.querySelectorAll('.pc').length).toBe(48);
    expect(groupOf(el, 'Vũ Ngọc Sơn')).toBe('NHOM_1');
    expect(text(cardOf(el, 'Vũ Ngọc Sơn').querySelector('.rank'))).toBe('#2');
    expect(el.querySelector<HTMLTextAreaElement>('#pg-reason-box')!.value).toContain('Vũ Ngọc Sơn');
    expect(text(el)).not.toMatch(/NHOM_|HTXSNV|KHTNV|C2|leader|appraiser|Policy Pack/);
  });

  it('bàn phím: BR-10-09 chặn đưa người có cờ vào Nhóm 2; Esc đóng menu và trả tiêu điểm', async () => {
    const { el } = await render();
    await until(el, '.pc');
    const dots = cardOf(el, 'Bùi Văn Khánh').querySelector<HTMLButtonElement>('.dots')!;
    dots.click();
    const menu = await until(el, '.menu');
    button(menu, 'Chuyển sang Nhóm 2').click();
    const err = await until(el, '[role="alert"]');
    expect(text(err)).toBe(
      'Cán bộ Bùi Văn Khánh xếp loại năm Không hoàn thành nhiệm vụ nên không thể đưa vào Nhóm 2.',
    );
    expect(groupOf(el, 'Bùi Văn Khánh')).toBe('NHOM_4');
    dots.click();
    const m2 = await until(el, '.menu');
    m2.querySelector('button')!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    );
    await tick();
    expect(el.querySelector('.menu')).toBeNull();
    expect(document.activeElement).toBe(dots);
  });

  it('đổi nhóm cần lý do (MSG-10-07), lưu nháp rồi trình tập thể lãnh đạo', async () => {
    const { el } = await render();
    await until(el, '.pc');
    cardOf(el, 'Lê Thu Hà').querySelector<HTMLButtonElement>('.dots')!.click();
    button(await until(el, '.menu'), 'Chuyển sang Nhóm 1').click();
    await tick();
    expect(groupOf(el, 'Lê Thu Hà')).toBe('NHOM_1');
    expect(document.activeElement).toBe(cardOf(el, 'Lê Thu Hà').querySelector('.dots'));
    button(el, 'Lưu nháp').click();
    const err = await until(el, '[role="alert"]');
    expect(text(err)).toBe(
      'Đồng chí đã xếp Lê Thu Hà khác nhóm gợi ý. Vui lòng ghi lý do điều chỉnh.',
    );
    await tick();
    const box = el.querySelector<HTMLTextAreaElement>('#pg-reason-box')!;
    expect(document.activeElement).toBe(box);
    box.value = 'Đồng chí được quy hoạch chức danh Trưởng phòng giai đoạn 2026 – 2031.';
    box.dispatchEvent(new Event('input'));
    button(el, 'Lưu nháp').click();
    expect(text(await until(el, '.msg.ok'))).toBe('Đã lưu nháp danh sách phân nhóm.');

    button(el, 'Trình tập thể lãnh đạo').click();
    const dlg = await until(el, '[role="dialog"]');
    button(dlg, 'Trình').click();
    await until(el, '.msg.ok', (e) => text(e).startsWith('Đã trình tập thể lãnh đạo'));
    expect(button(el, 'Lưu nháp')).toBeUndefined();
    expect(el.querySelector('.dots')).toBeNull();
  });

  it('kỳ quý: chức năng bị khoá (MSG-10-05); thẩm định viên không có nút trình', async () => {
    const q = await render('/phan-nhom?period=p-2026q4');
    expect(text(await until(q.el, '[data-msg="MSG-10-05"]'))).toBe(
      'Phân nhóm triển vọng chỉ thực hiện ở kỳ năm (QĐ khoản 12.1.5).',
    );
    const a = await render('/phan-nhom', ['SCR-1002.xem', 'SCR-1002.xep-nhom']);
    await until(a.el, '.pc');
    expect(button(a.el, 'Trình tập thể lãnh đạo')).toBeUndefined();
    expect(button(a.el, 'Lưu nháp')).toBeDefined();
  });
});
