import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import {
  DEFAULT_RUNTIME_CONFIG,
  JOB_TRACKER_OPTIONS,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
  mockStepUp,
} from '@vt/api-client';
import { resetMockData } from '../../../../../api-client/src/mock/mock-registry';
import { DOSSIER_CAN, DossierPermissionKey } from '../../dossier-permissions';
import { DOSSIER_STEP_UP } from '../../step-up';
import { MeetingsPage } from '../meetings/meetings.page';
import { MeetingPage } from './meeting.page';

/** WEB-040: SCR-1101 trên dữ liệu giả lập theo từng vai trong hội nghị. */
const M = (n: number) => `0192a3f4-1101-7000-8000-${String(n).padStart(12, '0')}`;
const ALL: DossierPermissionKey[] = [
  'SCR-1101.tao',
  'SCR-1101.gui-du-thao',
  'SCR-1101.gop-y',
  'SCR-1101.thu-ky',
  'SCR-1101.ket-thuc',
];

async function render(url: string, keys: DossierPermissionKey[] = ALL) {
  TestBed.resetTestingModule();
  resetMockData();
  TestBed.configureTestingModule({
    providers: [
      provideRouter(
        [
          { path: 'kiem-diem', component: MeetingsPage },
          { path: 'kiem-diem/:id', component: MeetingPage },
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
        useValue: { initialDelayMs: 1, maxDelayMs: 1, maxRetries: 0 },
      },
      { provide: DOSSIER_CAN, useValue: (k: DossierPermissionKey) => keys.includes(k) },
      {
        provide: DOSSIER_STEP_UP,
        useValue: async () => {
          mockStepUp();
          return true;
        },
      },
    ],
  });
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(url);
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
const text = (e: Element | null | undefined) => (e?.textContent ?? '').replace(/\s+/g, ' ').trim();
const type = (e: HTMLElement, v: string) => {
  (e as HTMLInputElement).value = v;
  e.dispatchEvent(new Event('input'));
};

describe('SCR-1101 Hội nghị kiểm điểm (WEB-040)', () => {
  it('thư ký: thời hạn đạt, thành phần 6/7, ghi ý kiến, sinh biên bản', async () => {
    const { el } = await render(`/kiem-diem/${M(1)}`);
    await until(el, '.comments li');
    expect(text(el.querySelector('.pass'))).toContain('Đạt yêu cầu ≥ 03 ngày làm việc');
    expect(text(el.querySelector('#mt-pp'))).toContain('6/7 có mặt');
    expect(el.querySelector('[data-act="gop-y"]')).toBeNull();
    expect(el.querySelector('[data-act="ket-thuc"]')).toBeNull();
    // ý kiến tại hội nghị
    (el.querySelector('[data-act="ghi-y-kien"]') as HTMLElement).click();
    await until(el, '#mt-content-err');
    type(el.querySelector('#mt-content')!, 'Đề nghị nêu rõ thời hạn khắc phục.');
    (el.querySelector('[data-act="ghi-y-kien"]') as HTMLElement).click();
    await until(el, '.comments li', (e) => text(e).includes('thời hạn khắc phục'));
    // biểu quyết vượt số có mặt bị chặn ngay ở trình duyệt
    type(el.querySelector('#sp-vote-HTTNV')!, '7');
    (el.querySelector('[data-act="sinh-bien-ban"]') as HTMLElement).click();
    expect(text(await until(el, '#sp-vote-err'))).toBe(
      'Tổng số phiếu (7) vượt số người có mặt (6).',
    );
    type(el.querySelector('#sp-vote-HTTNV')!, '5');
    type(el.querySelector('#sp-vote-HTXSNV')!, '1');
    (el.querySelector('[data-act="sinh-bien-ban"]') as HTMLElement).click();
    await until(el, '[data-status]', (e) => text(e) === 'Đã lập biên bản');
    expect(text(el.querySelector('[data-vote]'))).toContain('Hoàn thành tốt nhiệm vụ: 5');
    expect(el.textContent).not.toMatch(/HTTNV|HTXSNV|MSG-|DOSSIER-/);
  }, 15000);

  it('người được kiểm điểm: gửi dự thảo muộn thì cảnh báo thời hạn', async () => {
    const { el } = await render(`/kiem-diem/${M(2)}`);
    const send = await until(el, '[data-act="gui-du-thao"]');
    send.click();
    const w = await until(el, '[data-msg="MSG-11-01"]');
    expect(text(w)).toContain('chưa đủ 03 ngày theo QĐ Điều 6');
    expect(el.querySelector('[data-act="gui-du-thao"]')).toBeNull();
  }, 15000);

  it('chủ trì: ghi lý do giữ ngày, kết thúc hội nghị tạo hạn chế cần khắc phục', async () => {
    const { el } = await render(`/kiem-diem/${M(7)}`);
    (await until(el, '[data-act="giu-ngay"]')).click();
    expect(text(await until(el, '.err'))).toContain('ít nhất 10 ký tự');
    type(el.querySelector('#mt-reason')!, 'Cấp uỷ thống nhất giữ ngày họp do lịch công tác.');
    (el.querySelector('[data-act="giu-ngay"]') as HTMLElement).click();
    await until(el, '.note', (e) => text(e).includes('Cấp uỷ thống nhất'));

    const r3 = await render(`/kiem-diem/${M(3)}`);
    (await until(r3.el, '[data-act="ket-thuc"]')).click();
    expect(text(await until(r3.el, '.ok'))).toBe(
      'Đã kết thúc hội nghị. Biên bản đang được sinh và sẽ gửi chủ trì, thư ký ký số.',
    );
    await until(r3.el, '[data-created] li');
    expect(r3.el.querySelectorAll('[data-created] li').length).toBe(2);
  }, 15000);

  it('người được kiểm điểm thấy góp ý ẩn danh; thành viên gửi góp ý', async () => {
    const { el } = await render(`/kiem-diem/${M(5)}`);
    await until(el, '.comments li');
    expect(text(el.querySelector('.comments li'))).toContain('Thành viên hội nghị');
    expect(text(el)).toContain('không thấy tên người góp ý');
    expect(el.querySelector('#mt-content')).toBeNull();

    const r4 = await render(`/kiem-diem/${M(4)}`, ['SCR-1101.gop-y']);
    await until(r4.el, '[data-act="gop-y"]');
    (r4.el.querySelectorAll('input[name="mt-kind"]')[1] as HTMLInputElement).click();
    type(r4.el.querySelector('#mt-content')!, 'Cần chủ động hơn khi phối hợp.');
    (r4.el.querySelector('[data-act="gop-y"]') as HTMLElement).click();
    await until(
      r4.el,
      '.comments li',
      (e) => text(e).includes('Hạn chế') && text(e).includes('phối hợp'),
    );
    expect(r4.el.querySelector('[data-act="tai-du-thao"]')).not.toBeNull();
    expect(r4.el.querySelector('[data-act="doi-ngay"]')).toBeNull();
  }, 15000);

  it('danh sách và lập hội nghị, hiện trường hợp đặc thù của người được kiểm điểm', async () => {
    const { el, harness } = await render('/kiem-diem');
    await until(el, 'tbody tr');
    expect(el.querySelectorAll('tbody tr').length).toBe(7);
    (el.querySelector('[data-act="lap-hoi-nghi"]') as HTMLElement).click();
    const dlg = await until(el, '[role="dialog"]');
    await until(
      dlg,
      '#mc-chairPersonId option',
      (o) => (o as HTMLOptionElement).value === 'p-dung',
    );
    const pick = (id: string, v: string) => {
      const s = dlg.querySelector(`#${id}`) as HTMLSelectElement;
      s.value = v;
      s.dispatchEvent(new Event('change'));
    };
    await until(dlg, '#mc-periodId option', (o) => (o as HTMLOptionElement).value === 'p-2026');
    pick('mc-periodId', 'p-2026');
    pick('mc-subjectPersonId', 'p-tuan');
    expect(text(await until(dlg, '[data-special]'))).toContain(
      'Điều động, luân chuyển dưới 2 tháng',
    );
    pick('mc-chairPersonId', 'p-dung');
    pick('mc-secretaryPersonId', 'p-dung');
    (dlg.querySelector('[data-act="tao"]') as HTMLElement).click();
    expect(await until(dlg, '.ferr', (e) => text(e) === 'Thư ký phải khác chủ trì.')).toBeTruthy();
    pick('mc-secretaryPersonId', 'p-chau');
    type(dlg.querySelector('#mc-scheduledAt')!, '2026-12-28T08:00');
    (dlg.querySelector('[data-act="tao"]') as HTMLElement).click();
    const h1 = await until(harness.fixture.nativeElement as HTMLElement, 'h1', (e) =>
      text(e).includes('Hội nghị kiểm điểm cuối năm 2026'),
    );
    expect(h1).toBeTruthy();
  }, 15000);
});
