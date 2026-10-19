import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { Provider, Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
  mockStepUp,
  resetMockApi,
} from '@vt/api-client';
import { OVERSIGHT_CAN } from '../../oversight-permissions';
import { OVERSIGHT_STEP_UP } from '../../step-up';
import { ReopenCreatePage } from './reopen-create.page';
import { ReopenDetailPage } from './reopen-detail.page';
import { ReopenListPage } from './reopen-list.page';

/** WEB-043: SCR-1301 trên dữ liệu giả lập: danh sách, chi tiết hai người duyệt, tạo yêu cầu. */
async function render<T>(
  cmp: Type<T>,
  can: (k: string) => boolean = () => true,
  id?: string,
  extra: Provider[] = [],
): Promise<HTMLElement> {
  await resetMockApi();
  TestBed.configureTestingModule({
    imports: [cmp],
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      { provide: OVERSIGHT_CAN, useValue: can },
      {
        provide: OVERSIGHT_STEP_UP,
        useValue: () => {
          mockStepUp();
          return Promise.resolve(true);
        },
      },
      ...extra,
    ],
  });
  const fixture = TestBed.createComponent(cmp);
  if (id) fixture.componentRef.setInput('id', id);
  fixture.autoDetectChanges();
  return fixture.nativeElement as HTMLElement;
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 200; i++) {
    const e = [...el.querySelectorAll(sel)].find(test);
    if (e) return e;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw new Error(`Không thấy ${sel}`);
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const has = (s: string) => (e: Element) => text(e).includes(s);

function type(el: HTMLElement, sel: string, value: string) {
  const f = el.querySelector(sel) as HTMLInputElement | HTMLTextAreaElement;
  f.value = value;
  f.dispatchEvent(new Event('input'));
}

async function press(el: HTMLElement, label: string) {
  const b = (await until(
    el,
    'button',
    (e) => text(e) === label && !(e as HTMLButtonElement).disabled,
  )) as HTMLButtonElement;
  b.click();
}

const only =
  (...keys: string[]) =>
  (k: string) =>
    keys.includes(k);

describe('ReopenListPage (SCR-1301)', () => {
  it('người duyệt mở thẳng "Chờ tôi duyệt"; có nút tạo khi có quyền tạo', async () => {
    const el = await render(ReopenListPage);
    await until(el, '.tbl tbody tr', has('MLK-'));
    expect(
      [...el.querySelectorAll('.tbl tbody tr')].map((r) => text(r.querySelector('td'))),
    ).toEqual(['MLK-2026-008', 'MLK-2026-007']);
    expect(text(el)).toContain('Tạo yêu cầu mở lại kỳ');
  });

  it('cơ quan kiểm tra chỉ xem: không có "Chờ tôi duyệt" và nút tạo', async () => {
    const el = await render(ReopenListPage, only('SCR-1301.huy', 'SCR-1302.tra-cuu'));
    await until(el, '.tbl tbody tr', has('MLK-'));
    expect(text(el)).not.toContain('Chờ tôi duyệt');
    expect(text(el)).not.toContain('Tạo yêu cầu mở lại kỳ');
    expect(el.querySelectorAll('.tbl tbody tr').length).toBe(3);
  });
});

describe('ReopenDetailPage (SCR-1301)', () => {
  it('MLK-2026-007 như Hình 11.7: phạm vi, ảnh hưởng 91,2 → 92,1, duyệt lượt 2 mở lại kỳ (MSG-13-03)', async () => {
    const el = await render(ReopenDetailPage, () => true, 'rr-2026-007');
    await until(el, '.tbl tbody tr', has('91,2'));
    expect(text(el.querySelector('h1'))).toContain('MLK-2026-007 Chờ người duyệt thứ hai');
    expect(text(el.querySelector('#scope-h')!.parentElement)).toContain('Hệ số C');
    expect(text(el.querySelector('.slots'))).toContain('Ngô Thanh Tâm');
    expect(text(el.querySelector('.slots'))).toContain('Đang chờ duyệt');
    expect(text(el.querySelector('.decide'))).toContain('Duyệt lượt thứ hai');
    type(el, '#dec-comment', 'Đồng ý mở lại một hồ sơ.');
    await press(el, 'Xác thực MFA và duyệt');
    const ok = await until(el, 'p-message', has('đủ hai phê duyệt'));
    expect(text(ok)).toContain('Kỳ Quý II/2026 được mở lại trong phạm vi 1 đối tượng');
    expect(text(el.querySelector('h1'))).toContain('Đã đủ hai phê duyệt');
    expect(el.querySelector('.decide')).toBeNull();
  });

  it('BR-13-02: người tạo không duyệt được yêu cầu của mình, hiện MSG-13-01', async () => {
    const el = await render(ReopenDetailPage, () => true, 'rr-2026-009');
    await until(el, '.decide');
    type(el, '#dec-comment', 'Đồng ý mở lại phiếu tự đánh giá.');
    await press(el, 'Xác thực MFA và duyệt');
    const err = await until(el, 'p-message', has('Hai người duyệt phải là hai người khác nhau'));
    expect(text(err)).toContain('khác người tạo yêu cầu');
  });

  it('người duyệt lượt 2 không thấy thẻ duyệt ở lượt 1; người tạo huỷ được có lý do', async () => {
    const el = await render(
      ReopenDetailPage,
      only('SCR-1301.duyet-2', 'SCR-1301.huy'),
      'rr-2026-009',
    );
    await until(el, '.slots');
    expect(el.querySelector('.decide')).toBeNull();
    await press(el, 'Huỷ yêu cầu');
    await until(document.body, '#cc-reason');
    type(document.body, '#cc-reason', 'Đã xử lý bằng phiếu bổ sung khác.');
    await new Promise((r) => setTimeout(r, 20));
    const dialog = document.body.querySelector('.p-dialog') as HTMLElement;
    await press(dialog, 'Huỷ yêu cầu');
    await until(el, 'p-message', has('Đã huỷ yêu cầu MLK-2026-009'));
    expect(text(el.querySelector('h1'))).toContain('Đã đóng');
  });
});

describe('ReopenCreatePage (SCR-1301)', () => {
  it('tạo yêu cầu từ căn cứ khiếu nại rồi sang trang chi tiết', async () => {
    // nơi gọi (SCR-1202) truyền sẵn kỳ, căn cứ và hồ sơ qua tham số truy vấn
    const q = convertToParamMap({
      periodId: 'p-2026q1',
      basisKind: 'KHIEU_NAI',
      basisCode: 'KN-2026-0040',
      profileId: 'ap-2026q1-nguyen-van-binh',
    });
    const el = await render(ReopenCreatePage, () => true, undefined, [
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: q }, queryParamMap: of(q) },
      },
    ]);
    const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    await until(el, '#rq-profile');
    expect(text(el.querySelector('.chips'))).toContain('ap-2026q1-nguyen-van-binh');
    expect((el.querySelector('#rq-basis-code') as HTMLInputElement).value).toBe('KN-2026-0040');
    type(
      el,
      '#rq-reason',
      'Thi hành kết luận khiếu nại KN-2026-0040: điều chỉnh hệ số C của nhiệm vụ thường xuyên 4.',
    );
    await press(el, 'Tạo yêu cầu');
    for (let i = 0; i < 100 && !nav.mock.calls.length; i++)
      await new Promise((r) => setTimeout(r, 20));
    expect(nav).toHaveBeenCalled();
    expect(nav.mock.calls[0][0][0]).toBe('..');
  });
});
