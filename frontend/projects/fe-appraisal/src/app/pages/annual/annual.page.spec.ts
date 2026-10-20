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
import { APPRAISAL_CAN, AppraisalPermissionKey } from '../../appraisal-permissions';
import { AnnualPage } from './annual.page';

/** WEB-039: SCR-1001 trên dữ liệu giả lập: thẻ số liệu, lọc liên thông, giải thích, bố trí lại. */
const APPRAISER: AppraisalPermissionKey[] = [
  'SCR-1001.xem',
  'SCR-1001.tham-dinh-nam',
  'SCR-1001.bo-tri-lai',
  'SCR-1002.xem',
];

async function render(opts: { keys?: AppraisalPermissionKey[]; elderly?: boolean } = {}) {
  TestBed.resetTestingModule();
  resetMockData();
  const keys = opts.keys ?? APPRAISER;
  TestBed.configureTestingModule({
    providers: [
      provideRouter(
        [
          { path: 'xep-loai-nam', component: AnnualPage },
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
  await harness.navigateByUrl('/xep-loai-nam', AnnualPage);
  const el = harness.routeNativeElement as HTMLElement;
  harness.fixture.autoDetectChanges();
  await until(el, '.conds li');
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
const button = (el: HTMLElement, label: string) =>
  [...el.querySelectorAll('button')].find((b) => text(b) === label) as HTMLButtonElement;

describe('AnnualPage (WEB-039, SCR-1001)', { timeout: 15000 }, () => {
  it('bốn thẻ số liệu, bảng 48 hồ sơ chia trang, giải thích hồ sơ đầu, không hiện mã mức', async () => {
    const { el } = await render();
    expect(text(el.querySelector('h1'))).toBe('Xếp loại năm 2026');
    expect([...el.querySelectorAll('.stat strong')].map(text)).toEqual(['48/48', '3', '1', '48']);
    expect(el.querySelectorAll('tbody tr').length).toBe(20);
    expect(text(el.querySelector('.pager'))).toContain('Trang 1/3');
    expect(text(el.querySelector('#an-ex'))).toBe('Giải thích liên thông · Đỗ Quang Vinh');
    expect(text(el.querySelector('.side'))).toContain('trung bình điểm bốn quý đã quyết định');
    const link = el.querySelector<HTMLAnchorElement>('[data-act="tham-dinh-nam"]')!;
    expect(link.getAttribute('href')).toBe('/tham-dinh?periodId=p-2026');
    expect(text(el)).not.toMatch(/HTXSNV|HTTNV|KHTNV|\bHTNV\b|CO_QUY|TRUNG_BINH_QUY|Policy Pack/);
  });

  it('lọc liên thông, chọn hồ sơ có cờ, lập đề xuất bố trí lại qua hộp thoại', async () => {
    const { el } = await render();
    el.querySelector<HTMLButtonElement>('[data-act="loc-lien-thong"]')!.click();
    await until(el, 'tbody tr', () => el.querySelectorAll('tbody tr').length === 3);
    expect(el.querySelector('[data-act="loc-lien-thong"]')!.getAttribute('aria-pressed')).toBe(
      'true',
    );
    const name = button(el, 'Bùi Văn Khánh');
    name.click();
    await until(el, '#an-ex', (e) => text(e).includes('Bùi Văn Khánh'));
    const open = await until(el, '[data-act="bo-tri-lai"]');
    open.click();
    const box = await until(el, '#rd-note');
    await new Promise((r) => setTimeout(r, 30));
    expect(document.activeElement).toBe(box);
    box.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await new Promise((r) => setTimeout(r, 30));
    expect(el.querySelector('[role="dialog"]')).toBeNull();
    expect(document.activeElement).toBe(open);

    open.click();
    await until(el, '#rd-note');
    button(el, 'Lập đề xuất').click();
    const ok = await until(el, '.msg.ok[role="status"]');
    expect(text(ok)).toContain('Đã lập đề xuất bố trí lại công việc cho đồng chí Bùi Văn Khánh');
    await until(el, '.reassign .msg.ok');
    expect(el.querySelector('[data-act="bo-tri-lai"]')).toBeNull();
  });

  it('lãnh đạo chỉ xem; chế độ cao tuổi bỏ cột tự đề xuất năm', async () => {
    const { el } = await render({ keys: ['SCR-1001.xem'], elderly: true });
    expect(el.querySelector('[data-act="tham-dinh-nam"]')).toBeNull();
    expect([...el.querySelectorAll('thead th')].map(text)).not.toContain('Tự đề xuất năm');
    expect(el.querySelector('.layout.one')).not.toBeNull();
    button(el, 'Bùi Văn Khánh')?.click();
    el.querySelector<HTMLButtonElement>('[data-act="loc-lien-thong"]')!.click();
    await until(el, 'tbody tr', () => el.querySelectorAll('tbody tr').length === 3);
    button(el, 'Bùi Văn Khánh').click();
    await until(el, '.reassign');
    expect(el.querySelector('[data-act="bo-tri-lai"]')).toBeNull();
  });
});
