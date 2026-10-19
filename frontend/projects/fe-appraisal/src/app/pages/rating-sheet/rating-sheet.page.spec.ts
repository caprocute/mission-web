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
import { ThemeService } from '@vt/design-system';
import { resetMockData } from '../../../../../api-client/src/mock/mock-registry';
import { mockSession } from '../../../../../api-client/src/mock/mock-session';
import { APPRAISAL_CAN, AppraisalPermissionKey } from '../../appraisal-permissions';
import { RatingSheetPage } from './rating-sheet.page';
import { ResultsPage } from './results.page';

/** WEB-037: SCR-0903 trên dữ liệu giả lập: điểm, điều kiện, giải thích, vết, đề xuất, tải, khiếu nại. */
const MANAGER: AppraisalPermissionKey[] = ['SCR-0903.xem', 'SCR-0903.de-xuat'];
const MEMBER: AppraisalPermissionKey[] = [
  'SCR-0903.xem',
  'SCR-0903.tai-mau-1b',
  'SCR-0903.khieu-nai',
];

async function render(
  url: string,
  keys: AppraisalPermissionKey[],
  opts: { elderly?: boolean } = {},
) {
  TestBed.resetTestingModule();
  resetMockData();
  mockSession.reset();
  TestBed.configureTestingModule({
    providers: [
      provideRouter(
        [
          { path: 'ket-qua', component: ResultsPage },
          { path: 'ket-qua/:id', component: RatingSheetPage },
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
    ],
  });
  if (opts.elderly) {
    const theme = TestBed.inject(ThemeService);
    theme.mode.set('elderly');
    theme.reducedSteps.set(true);
  }
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(url);
  const el = harness.routeNativeElement as HTMLElement;
  harness.fixture.autoDetectChanges();
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
  [...el.querySelectorAll('button, a')].find((b) => text(b) === label) as
    HTMLButtonElement | undefined;
function type(el: HTMLElement, sel: string, value: string) {
  const box = el.querySelector(sel) as HTMLTextAreaElement | HTMLSelectElement;
  box.value = value;
  box.dispatchEvent(new Event(box.tagName === 'SELECT' ? 'change' : 'input'));
}

const P = '01928f3e-7b1a-7c3e-9a51-3f2d9c1b';
const SHEET = `/ket-qua/${P}0a41`;

describe('RatingSheetPage (SCR-0903, WEB-037)', () => {
  it('điểm 91,2, cấu phần, điều kiện hai mức, mức hệ thống kèm giải thích và vết các mức', async () => {
    const { el } = await render(SHEET, MANAGER);
    await until(el, 'p.level');
    expect(text(el.querySelector('h1'))).toBe('Phiếu xếp loại cá nhân · Quý IV/2026');
    expect(text(el.querySelector('.sub'))).toContain('Tiêu chí chung: Thực hiện tốt');
    expect(text(el.querySelector('[data-field="total-score"]'))).toBe('91,2');
    const groups = [...el.querySelectorAll('tr[data-group]')].map((r) =>
      [...r.children].slice(0, 4).map(text),
    );
    expect(groups).toEqual([
      ['Nhiệm vụ thường xuyên (5)', '0,920', '30', '27,6'],
      ['Nhiệm vụ then chốt (4)', '0,908', '70', '63,6'],
    ]);
    expect(text(el.querySelector('[data-field="exceed-ratio"]'))).toBe('22%');
    const heads = [...el.querySelectorAll('h3')].map(text);
    expect(heads).toEqual([
      'Điều kiện mức Hoàn thành xuất sắc nhiệm vụ',
      'Điều kiện mức Hoàn thành tốt nhiệm vụ',
    ]);
    const miss = el.querySelector('.conds li.miss')!;
    expect([...miss.children].map(text)).toEqual([
      '✗ Chưa đạt',
      'Từ 30% nhiệm vụ vượt mục tiêu trở lên (thực tế 22%)',
    ]);
    expect(text(el.querySelector('.level'))).toBe('Hoàn thành tốt nhiệm vụ');
    expect(text(el.querySelector('.explain'))).toContain(
      'chưa đạt điều kiện 30% nhiệm vụ vượt mục tiêu',
    );
    expect(text(await until(el, '[data-field="other-flow"]'))).toContain('91,8 điểm');
    expect([...el.querySelectorAll('.trail li')].map(text)).toEqual([
      'Tự đề xuất: Hoàn thành xuất sắc nhiệm vụ · 04/01',
      'Thẩm định: Hoàn thành tốt nhiệm vụ · 07/01',
      'Giải trình bắt buộc · đã ghi',
      'Chờ đề xuất',
    ]);
    expect(el.querySelector('.pack')?.getAttribute('title')).toBe(
      'Kết quả này được tính theo bộ tiêu chí Đảng v2.0, áp dụng cho kỳ Quý IV/2026.',
    );
    // YC-PT-10: không hiện mã kỹ thuật
    expect(el.textContent).not.toMatch(/HTXSNV|HTTNV|DA_THAM_DINH|Policy Pack|SCR-/);
  });

  it('xem nhiệm vụ của một nhóm, Esc đóng và trả tiêu điểm; thẻ Vết tính dẫn tới minh chứng', async () => {
    const { el } = await render(SHEET, MANAGER);
    await until(el, 'p.level');
    const toggle = el.querySelector('#grp-THEN_CHOT') as HTMLButtonElement;
    toggle.click();
    await until(el, '#tasks-THEN_CHOT');
    expect(el.querySelectorAll('#tasks-THEN_CHOT tbody tr').length).toBe(4);
    expect(
      el.querySelector('#tasks-THEN_CHOT a[data-evidence-id]')?.getAttribute('href'),
    ).toContain('/evidence?task=');
    el.querySelector('#tasks-THEN_CHOT')!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    );
    await until(el, '#grp-THEN_CHOT', (b) => b.getAttribute('aria-expanded') === 'false');
    expect(el.querySelector('#tasks-THEN_CHOT')).toBeNull();
    expect(document.activeElement?.id).toBe('grp-THEN_CHOT');

    (el.querySelector('#tab-TRACE') as HTMLButtonElement).click();
    const step = await until(el, '.steps li[data-rule="kpi_nhiem_vu"]');
    expect(text(step)).toContain('Bước 1. KPI nhiệm vụ');
    expect(step?.querySelector('a')?.textContent).toContain('Xem minh chứng đã nghiệm thu');
    expect(el.textContent).not.toMatch(/DA_XAC_NHAN|kpi_nhiem_vu|BR-09/);
  });

  it('chuyển thẻ bằng phím mũi tên sang luồng Nhà nước', async () => {
    const { el } = await render(SHEET, MANAGER);
    await until(el, 'p.level');
    const first = el.querySelector('#tab-DANG') as HTMLButtonElement;
    first.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await until(el, '[data-field="total-score"]', (e) => text(e) === '91,8');
    expect(document.activeElement?.id).toBe('tab-NHA_NUOC');
    expect(text(el.querySelector('.level'))).toBe('Hoàn thành xuất sắc nhiệm vụ');
    expect(text(el.querySelector('tbody tr'))).toContain('Tiêu chí chung');
  });

  it('quản lý ghi mức đề xuất: khác mức thẩm định thì MSG-09-12 và bắt buộc lý do; ghi xong chờ quyết định', async () => {
    const { el } = await render(SHEET, MANAGER);
    await until(el, '#f-rating');
    expect((el.querySelector('#f-rating') as HTMLSelectElement).value).toBe('HTTNV');
    type(el, '#f-rating', 'HTXSNV');
    expect(text(await until(el, '#w-rating'))).toBe(
      'Mức đề xuất khác mức thẩm định. Vui lòng ghi lý do (QĐ khoản 16.3).',
    );
    button(el, 'Ghi mức đề xuất')!.click();
    expect(text(await until(el, '#e-reason'))).toContain('từ 20 đến 2.000 ký tự');
    type(el, '#f-rating', 'HTTNV');
    await until(el, '#w-rating', () => false);
    button(el, 'Ghi mức đề xuất')!.click();
    const ok = await until(el, '.msg.ok');
    expect(text(ok)).toBe(
      'Đã ghi mức đề xuất "Hoàn thành tốt nhiệm vụ" cho hồ sơ của đồng chí Trần Minh Hoàng. Hồ sơ đã chuyển tới cấp có thẩm quyền quyết định.',
    );
    expect(document.activeElement).toBe(ok);
    expect(el.querySelector('#f-rating')).toBeNull();
    expect([...el.querySelectorAll('.trail li')].map(text).slice(-2)[1]).toBe('Chờ quyết định');
  });

  it('máy chủ chặn đề xuất do tách vai trò (403) thì hiện thông báo; vai trò không có quyền thì ẩn khối đề xuất', async () => {
    const { el } = await render(`/ket-qua/${P}0a43`, MANAGER);
    await until(el, '#f-rating');
    button(el, 'Ghi mức đề xuất')!.click();
    expect(text(await until(el, '.msg.err'))).toContain('đã tham gia nghiệm thu một nhiệm vụ');

    const view = await render(SHEET, ['SCR-0903.xem']);
    await until(view.el, 'p.level');
    expect(view.el.querySelector('#f-rating')).toBeNull();
    expect(button(view.el, 'Gửi khiếu nại')).toBeUndefined();
  });

  it('cán bộ xem kết quả đã quyết định: thông báo hạn khiếu nại, tải Mẫu 1B, gửi khiếu nại', async () => {
    const list = await render('/ket-qua', MEMBER);
    const row = await until(list.el, 'a.row');
    expect(text(row)).toContain('Quý III/2026');
    expect(text(row)).toContain('Đã có quyết định');
    expect(list.el.querySelector('#unit-h')).toBeNull();

    const { el } = await render(`/ket-qua/${P}0a45`, MEMBER);
    const info = await until(el, '.msg.info');
    expect(text(info)).toMatch(
      /^Kết quả xếp loại Quý III\/2026 của đồng chí Trần Minh Hoàng đã được quyết định: Hoàn thành tốt nhiệm vụ\. Thời hạn khiếu nại đến hết ngày \d\d\/\d\d\/\d{4}\.$/,
    );
    expect(button(el, 'Gửi khiếu nại')?.getAttribute('href')).toContain(
      '/oversight/complaints/new',
    );
    button(el, 'Tải Mẫu 1B (PDF/A)')!.click();
    const link = await until(el, 'a[download]');
    expect(link?.getAttribute('href')).toMatch(/^blob:/);
    expect(document.activeElement).toBe(link);
    expect(el.querySelector('#tab-NHA_NUOC')).toBeNull();
  });

  it('quản lý thấy danh sách hồ sơ chờ đề xuất; chế độ cao tuổi ẩn vết tính và chi tiết nhiệm vụ', async () => {
    const list = await render('/ket-qua', MANAGER);
    await until(list.el, '#unit-h');
    const rows = await until(list.el, 'section:last-of-type a.row');
    expect(text(rows)).toContain('Trần Minh Hoàng');
    expect(text(list.el.querySelector('#unit-h'))).toBe('Hồ sơ chờ đồng chí đề xuất mức');

    const { el } = await render(SHEET, MANAGER, { elderly: true });
    await until(el, 'p.level');
    expect(el.querySelector('#tab-TRACE')).toBeNull();
    expect(el.querySelector('#grp-THEN_CHOT')).toBeNull();
    expect(el.querySelector('details.verify')).toBeNull();
    expect(el.querySelector('.grid.simple')).not.toBeNull();
  });
});
