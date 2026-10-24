import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { DEFAULT_RUNTIME_CONFIG, JOB_TRACKER_OPTIONS, MOCK_API_OPTIONS, RUNTIME_CONFIG, idempotencyInterceptor, mockApiInterceptor, mockStepUp, setMockEsignOutage } from '@vt/api-client';
import { AppraisalApi } from '@vt/api-client/domains';
import { ThemeService } from '@vt/design-system';
import { firstValueFrom } from 'rxjs';
import { resetMockData } from '../../../../../api-client/src/mock/mock-registry';
import { mockSession } from '../../../../../api-client/src/mock/mock-session';
import { APPRAISAL_CAN, AppraisalPermissionKey } from '../../appraisal-permissions';
import { APPRAISAL_STEP_UP } from '../self-assessment/step-up';
import { BatchDecisionPage } from './batch-decision.page';

/** WEB-038: SCR-0904 trên dữ liệu giả lập: chọn hồ sơ, đổi mức, phân bố, ký lô, huỷ lô, Mẫu 3. */
const DECIDER: AppraisalPermissionKey[] = [
  'SCR-0904.xem',
  'SCR-0904.doi-muc',
  'SCR-0904.ky-lo',
  'SCR-0904.theo-doi-huy',
];

async function render(
  opts: { keys?: AppraisalPermissionKey[]; elderly?: boolean; pollMs?: number } = {},
) {
  TestBed.resetTestingModule();
  resetMockData();
  mockSession.reset();
  const keys = opts.keys ?? DECIDER;
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'quyet-dinh', component: BatchDecisionPage },
        { path: '**', children: [] },
      ]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      {
        provide: JOB_TRACKER_OPTIONS,
        useValue: {
          initialDelayMs: opts.pollMs ?? 5,
          maxDelayMs: opts.pollMs ?? 5,
          maxRetries: 0,
        },
      },
      { provide: APPRAISAL_CAN, useValue: (k: AppraisalPermissionKey) => keys.includes(k) },
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
  await harness.navigateByUrl('/quyet-dinh', BatchDecisionPage);
  const el = harness.routeNativeElement as HTMLElement;
  harness.fixture.autoDetectChanges();
  await until(el, 'tbody tr');
  return { el, harness };
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 250; i++) {
    const e = [...el.querySelectorAll(sel)].find(test);
    if (e) return e;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw new Error(`Không thấy ${sel}`);
}

const text = (e: Element | null | undefined) => (e?.textContent ?? '').replace(/\s+/g, ' ').trim();

function type(el: HTMLElement, sel: string, value: string) {
  const i = el.querySelector(sel) as HTMLInputElement | HTMLTextAreaElement;
  i.value = value;
  i.dispatchEvent(new Event('input'));
}

function choose(sel: HTMLSelectElement, value: string) {
  sel.value = value;
  sel.dispatchEvent(new Event('change'));
}

const levelSelect = (el: HTMLElement, i: number) =>
  el.querySelectorAll<HTMLSelectElement>('tbody select')[i];

describe('SCR-0904 Quyết định xếp loại hàng loạt (WEB-038)', () => {
  afterEach(() => setMockEsignOutage(false));

  it('mặc định chọn tất cả 48 hồ sơ, mức quyết định bằng mức đề xuất, phân bố 19% trong giới hạn', async () => {
    const { el } = await render();
    expect(text(el.querySelector('h1'))).toBe('Quyết định xếp loại hàng loạt · Quý IV/2026');
    expect(text(el.querySelector('#bd-list-h'))).toBe('Đã chọn 48/48 hồ sơ');
    expect(el.querySelectorAll('tbody tr').length).toBe(10);
    expect(text(el.querySelector('.pager'))).toContain('Trang 1/5');
    expect(levelSelect(el, 0).value).toBe('HTXSNV');
    expect([...el.querySelectorAll('.dv')].map(text)).toEqual([
      '9 · 19%',
      '30 · 63%',
      '7 · 15%',
      '2 · 4%',
    ]);
    expect(text(el.querySelector('.msg.info'))).toContain('trong giới hạn 20%');
    // chứng thư ký số nạp bất đồng bộ (bộ giả lập nạp lười theo module từ WEB-052 pha 2)
    await until(el, '#bd-certificate', (e) => text(e).includes('Ngô Thanh Tâm · MySign'));
    expect(text(el.querySelector('.btn.primary'))).toBe('Quyết định và ký lô (48)');
    const notes = [...el.querySelectorAll('.note')].map(text);
    expect(notes).toContain('Khác mức · đã giải trình');
    expect(notes).toContain('Dưới 60 điểm');
    expect(notes).toContain('Quy tắc người đứng đầu: đạt');
    // không hiện mã mức, mã trạng thái (YC-PT-10)
    expect(text(el)).not.toMatch(/HTXSNV|HTTNV|KHTNV|CHO_QUYET_DINH|SCR-/);
  });

  it('đổi mức mở hộp thoại bắt buộc lý do; Esc huỷ và trả lại mức cũ; vượt ngưỡng hiện cảnh báo', async () => {
    const { el } = await render();
    const sel = levelSelect(el, 1);
    choose(sel, 'HTXSNV');
    await until(el, '[role=dialog]');
    expect(document.activeElement?.id).toBe('bd-reason');
    el.querySelector('[role=dialog]')!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    );
    await new Promise((r) => setTimeout(r, 20));
    expect(el.querySelector('[role=dialog]')).toBeNull();
    expect(sel.value).toBe('HTTNV');

    choose(sel, 'HTXSNV');
    await until(el, '[role=dialog]');
    (el.querySelector('.dialog .btn.primary') as HTMLButtonElement).click();
    expect(text(await until(el, '#bd-reason-err'))).toContain('từ 20 đến 2.000 ký tự');
    type(el, '#bd-reason', 'Có giải pháp đột phá được Ban Thường vụ ghi nhận trong quý.');
    (el.querySelector('.dialog .btn.primary') as HTMLButtonElement).click();
    await new Promise((r) => setTimeout(r, 20));
    expect(el.querySelector('[role=dialog]')).toBeNull();
    expect(text(el.querySelectorAll('.note')[1])).toBe('Đổi mức · đã ghi lý do');
    const warn = await until(el, '.side .msg.warn');
    expect(text(warn)).toContain('là 21%, vượt giới hạn 20% của khối');
    // chưa xác nhận phiên hiệu chỉnh thì không gửi
    type(el, '#bd-decisionNo', '215-QĐ/ĐUK');
    (el.querySelector('.btn.primary') as HTMLButtonElement).click();
    expect(text(await until(el, '.errs'))).toContain('phiên hiệu chỉnh');
  });

  it('bỏ chọn một hồ sơ cấp dưới thì chặn hồ sơ người đứng đầu (MSG-09-14)', async () => {
    const { el } = await render();
    const boxes = el.querySelectorAll<HTMLInputElement>('tbody input[type=checkbox]');
    boxes[1].click();
    await until(el, '.note.bad');
    type(el, '#bd-decisionNo', '215-QĐ/ĐUK');
    (el.querySelector('.btn.primary') as HTMLButtonElement).click();
    expect(text(await until(el, '.errs'))).toContain(
      'Hồ sơ của người đứng đầu chỉ được quyết định sau khi các đơn vị trực thuộc đã có mức',
    );
    expect(text(el.querySelector('#bd-list-h'))).toBe('Đã chọn 47/48 hồ sơ');
  });

  it('danh sách nạp lại có thêm hồ sơ không làm mất lựa chọn của người dùng (WEB-071)', async () => {
    const { el, harness } = await render();
    el.querySelectorAll<HTMLInputElement>('tbody input[type=checkbox]')[0].click();
    await until(el, '#bd-list-h', (e) => text(e) === 'Đã chọn 47/48 hồ sơ');
    const queue = (
      harness.routeDebugElement!.componentInstance as {
        queue: { value(): { id: string }[]; set(v: { id: string }[]): void };
      }
    ).queue;
    const rows = queue.value();
    queue.set([...rows, { ...rows[rows.length - 1], id: 'ho-so-moi' }]);
    await until(el, '#bd-list-h', (e) => /\/49 hồ sơ/.test(text(e)));
    expect(text(el.querySelector('#bd-list-h'))).toBe('Đã chọn 47/49 hồ sơ');
  });

  it('ký lô: số quyết định trùng báo lỗi; ký xong hiện MSG-09-16 và hồ sơ rời danh sách', async () => {
    const { el } = await render();
    type(el, '#bd-decisionNo', '198-QĐ/ĐUK');
    (el.querySelector('.btn.primary') as HTMLButtonElement).click();
    expect(text(await until(el, '.msg.err'))).toContain('Số quyết định này đã được dùng trong năm');
    type(el, '#bd-decisionNo', '215-QĐ/ĐUK');
    (el.querySelector('.btn.primary') as HTMLButtonElement).click();
    await until(el, '[role=progressbar]');
    const ok = await until(el, '.msg.ok', (e) => /Đã hoàn tất quyết định/.test(text(e)));
    expect(text(ok)).toBe(
      'Đã hoàn tất quyết định 215-QĐ/ĐUK: 48 hồ sơ đã ký số và thông báo tới cán bộ.',
    );
    await until(el, '.list p.muted', (e) => /Không có hồ sơ nào chờ quyết định/.test(text(e)));
    const api = TestBed.inject(AppraisalApi);
    const p = await firstValueFrom(api.profile('01928f3e-7b1a-7c3e-9a51-3f2d9c1b0d01'));
    expect(p.status).toBe('DA_KY_SO');
    expect(p.ratingTrail.find((r) => r.levelKind === 'QUYET_DINH')?.isBulkDecision).toBe(true);
  });

  it('lỗi ký một phần thì hiện MSG-09-15, hồ sơ lỗi vẫn chờ quyết định', async () => {
    const { el } = await render();
    setMockEsignOutage(true);
    type(el, '#bd-decisionNo', '216-QĐ/ĐUK');
    (el.querySelector('.btn.primary') as HTMLButtonElement).click();
    const warn = await until(el, '.msg.warn', (e) => /Đã ký/.test(text(e)));
    expect(text(warn)).toBe(
      'Đã ký 46/48 hồ sơ. 2 hồ sơ lỗi sẽ được tự thử lại; đồng chí có thể đóng màn hình.',
    );
    await until(el, '#bd-list-h', (e) => text(e) === 'Đã chọn 2/2 hồ sơ');
  });

  it('huỷ lô khi đang ký: hồ sơ đã ký giữ nguyên, phần còn lại vẫn chờ quyết định', async () => {
    // giãn nhịp hỏi job để kịp huỷ khi lô mới ký một phần
    const { el, harness } = await render({ pollMs: 400 });
    type(el, '#bd-decisionNo', '217-QĐ/ĐUK');
    (el.querySelector('.btn.primary') as HTMLButtonElement).click();
    const cancel = (await until(
      el,
      '.row-acts .btn:not(.primary)',
      (e) => text(e) === 'Huỷ lô',
    )) as HTMLButtonElement;
    cancel.click();
    const ok = await until(el, '.msg.ok', (e) => /Đã huỷ lô quyết định 217-QĐ\/ĐUK/.test(text(e)));
    expect(text(ok)).toMatch(
      /hồ sơ đã ký giữ nguyên quyết định; \d+ hồ sơ chưa ký vẫn chờ quyết định/,
    );
    harness.fixture.destroy();
  });

  it('lọc theo khối; ngưỡng lấy theo gói của hồ sơ; mở lại lô đang ký sau khi rời màn hình (WEB-063)', async () => {
    const { el, harness } = await render({ pollMs: 400 });
    const khoi = [...el.querySelectorAll<HTMLSelectElement>('.filter select')].find((s) =>
      [...s.options].some((o) => o.text === 'Khối các cơ quan Đảng'),
    )!;
    choose(khoi, khoi.options[1].value);
    await until(el, '#bd-list-h', (e) => text(e) === 'Đã chọn 48/48 hồ sơ');
    type(el, '#bd-decisionNo', '218-QĐ/ĐUK');
    (el.querySelector('.btn.primary') as HTMLButtonElement).click();
    await until(el, '[role=progressbar]');
    await harness.navigateByUrl('/khac');
    await harness.navigateByUrl('/quyet-dinh', BatchDecisionPage);
    const page = harness.routeNativeElement as HTMLElement;
    const item = await until(page, '.pending li', (e) => /218-QĐ\/ĐUK/.test(text(e)));
    (item.querySelector('button') as HTMLButtonElement).click();
    const cancel = (await until(
      page,
      '.row-acts .btn:not(.primary)',
      (e) => text(e) === 'Huỷ lô',
    )) as HTMLButtonElement;
    cancel.click();
    await until(page, '.msg.ok', (e) => /Đã huỷ lô quyết định 218-QĐ\/ĐUK/.test(text(e)));
    harness.fixture.destroy();
  });

  it('ẩn chức năng theo ma trận quyền; chế độ cao tuổi bỏ hai cột mức đầu và nút xuất', async () => {
    const view = await render({ keys: ['SCR-0904.xem'] });
    expect(view.el.querySelector('tbody select')).toBeNull();
    expect(view.el.querySelector('.btn.primary')).toBeNull();
    const old = await render({ elderly: true });
    const heads = [...old.el.querySelectorAll('thead th')].map(text);
    expect(heads).not.toContain('Tự đề xuất');
    expect(heads).not.toContain('Thẩm định');
    expect([...old.el.querySelectorAll('.btn')].map(text)).not.toContain('Xuất Mẫu 3');
    expect(old.el.querySelector('.layout.one')).not.toBeNull();
  });

  it('xuất Mẫu 3 từ các hồ sơ đang chọn sinh liên kết tải bản nháp', async () => {
    const { el } = await render();
    const btn = [...el.querySelectorAll<HTMLButtonElement>('.btn')].find(
      (b) => text(b) === 'Xuất Mẫu 3',
    )!;
    btn.click();
    const link = (await until(el, 'a[download]')) as HTMLAnchorElement;
    expect(text(link)).toBe('Lưu tệp Mẫu 3');
    expect(link.href).toMatch(/^blob:/);
  });
});
