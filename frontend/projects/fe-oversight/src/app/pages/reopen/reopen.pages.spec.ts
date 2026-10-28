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
import { ComplaintApi, ReopenApi } from '@vt/api-client/domains';

let instance: unknown;

/** Hồ sơ Quý I/2026 (đã khoá) của Nguyễn Văn Bình trong giả lập `locked-profiles.ts`. */
const BINH_Q1 = '01928f3e-7b1a-7c3e-9a51-3f2d9c1d1311';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

interface CreatePageView {
  profileOptions(): { value: string; label: string }[];
  profilesRes: { isLoading(): boolean };
  setPeriod(id: string | null): void;
  addTarget(id: string | null): void;
  targets(): string[];
  basisOptions(): { value: string; label: string }[];
  basisRes: { isLoading(): boolean };
  setBasisKind(k: string): void;
  pickBasis(id: string | null): void;
  ready(): boolean;
}

async function poll(test: () => boolean) {
  for (let i = 0; i < 100 && !test(); i++) await new Promise((r) => setTimeout(r, 20));
}

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
  instance = fixture.componentInstance;
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
    expect(text(el.querySelector('#scope-h')!.parentElement)).toContain('Tiến độ (C)');
    expect(text(el.querySelector('.slots'))).toContain('Ngô Thanh Tâm');
    expect(text(el.querySelector('.slots'))).toContain('Đang chờ duyệt');
    expect(text(el.querySelector('.decide'))).toContain('Duyệt lượt thứ hai');
    type(el, '#dec-comment', 'Đồng ý mở lại một hồ sơ.');
    await press(el, 'Xác thực nhiều lớp và duyệt');
    const ok = await until(el, 'p-message', has('đủ hai phê duyệt'));
    expect(text(ok)).toContain('Kỳ Quý II/2026 được mở lại trong phạm vi 1 đối tượng');
    expect(text(el.querySelector('h1'))).toContain('Đã đủ hai phê duyệt');
    expect(el.querySelector('.decide')).toBeNull();
  });

  it('BR-13-02: người tạo không duyệt được yêu cầu của mình, hiện MSG-13-01', async () => {
    const el = await render(ReopenDetailPage, () => true, 'rr-2026-009');
    await until(el, '.decide');
    type(el, '#dec-comment', 'Đồng ý mở lại phiếu tự đánh giá.');
    await press(el, 'Xác thực nhiều lớp và duyệt');
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
  const withQuery = (params: Record<string, string>) => {
    const q = convertToParamMap(params);
    return [
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: q }, queryParamMap: of(q) },
      },
    ];
  };
  const REASON =
    'Thi hành kết luận khiếu nại KN-2026-0040: điều chỉnh hệ số C của nhiệm vụ thường xuyên 4.';

  async function submitAndCapture(el: HTMLElement) {
    const create = vi.spyOn(TestBed.inject(ReopenApi), 'create');
    const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    type(el, '#rq-reason', REASON);
    await press(el, 'Tạo yêu cầu');
    for (let i = 0; i < 100 && !nav.mock.calls.length; i++)
      await new Promise((r) => setTimeout(r, 20));
    return { input: create.mock.calls[0]?.[0], nav };
  }

  it('tạo yêu cầu từ căn cứ khiếu nại: hồ sơ chọn sẵn hiện họ tên, gửi mã hồ sơ UUID, sang chi tiết', async () => {
    // nơi gọi (SCR-1202) truyền sẵn kỳ, căn cứ và hồ sơ qua tham số truy vấn
    const el = await render(
      ReopenCreatePage,
      () => true,
      undefined,
      withQuery({
        periodId: 'p-2026q1',
        basisKind: 'KHIEU_NAI',
        basisCode: 'KN-2026-0040',
        basisId: 'kn-2026-0040',
        profileId: BINH_Q1,
      }),
    );
    await until(el, '#rq-profile');
    await until(el, '.chips li', has('Nguyễn Văn Bình'));
    expect(text(el.querySelector('.chips'))).toContain('Nguyễn Văn Bình · Phòng Tổng hợp');
    expect(text(el)).not.toContain(BINH_Q1);
    // căn cứ truyền sẵn hiện bằng mã khiếu nại trong ô chọn căn cứ, không có ô tự gõ
    expect(el.querySelector('#rq-basis-code')).toBeNull();
    expect(text(await until(el, 'p-select[name="basisId"]'))).toContain('KN-2026-0040');
    const { input, nav } = await submitAndCapture(el);
    expect(input?.basis).toEqual({ kind: 'KHIEU_NAI', id: 'kn-2026-0040', code: 'KN-2026-0040' });
    expect(input?.scopeIds).toEqual([BINH_Q1]);
    expect(input?.scopeDetail.items.map((i) => i.profileId)).toEqual([BINH_Q1]);
    expect(nav).toHaveBeenCalled();
    expect(nav.mock.calls[0][0][0]).toBe('..');
  });

  it('HT-34: hồ sơ chọn từ danh sách hồ sơ của kỳ đã khoá (không còn ô tự gõ mã hồ sơ)', async () => {
    // mở thẳng trang: căn cứ mặc định là quyết định hậu kiểm, nhập số quyết định
    const el = await render(ReopenCreatePage);
    const page = instance as CreatePageView;
    await until(el, '#rq-profile');
    // chưa chọn kỳ: ô chọn hồ sơ bị khoá, có hướng dẫn chọn kỳ trước, không gọi danh sách hồ sơ
    expect(el.querySelector('input[name="profile"], input#rq-profile[pinputtext]')).toBeNull();
    expect(
      el.querySelector('p-select[name="profile"] .p-disabled, p-select[name="profile"].p-disabled'),
    ).not.toBeNull();
    expect(text(el.querySelector('#h-profile'))).toContain('Chọn kỳ đã khoá trước');
    expect(page.profileOptions()).toEqual([]);

    page.setPeriod('p-2026q1');
    for (let i = 0; i < 100 && !page.profileOptions().length; i++)
      await new Promise((r) => setTimeout(r, 20));
    const opts = page.profileOptions();
    expect(opts.map((o) => o.label)).toEqual(
      expect.arrayContaining([
        'Nguyễn Văn Bình · Phòng Tổng hợp',
        'Vũ Quốc Khánh · Văn phòng Đảng uỷ',
      ]),
    );
    expect(opts.every((o) => UUID.test(o.value) && !o.label.includes(o.value))).toBe(true);
    expect(opts.some((o) => o.label.includes('Trần Minh Hoàng'))).toBe(false); // hồ sơ Quý II

    page.addTarget(BINH_Q1);
    await until(el, '.chips li', has('Nguyễn Văn Bình'));
    expect(page.profileOptions().map((o) => o.value)).not.toContain(BINH_Q1);
    // đổi kỳ bỏ hồ sơ của kỳ cũ
    page.setPeriod('p-2026q2');
    expect(page.targets()).toEqual([]);
    page.setPeriod('p-2026q1');
    page.addTarget(BINH_Q1);
    expect(text(el.querySelector('label[for="rq-basis-code"]'))).toBe('Số quyết định hậu kiểm');
    type(el, '#rq-basis-code', '15-QĐ/UBKT');
    const { input, nav } = await submitAndCapture(el);
    expect(input).toMatchObject({
      periodId: 'p-2026q1',
      scope: 'HO_SO',
      scopeIds: [BINH_Q1],
      basis: { kind: 'QUYET_DINH', code: '15-QĐ/UBKT' },
    });
    expect(nav).toHaveBeenCalled();
  });

  it('YC-PT-26: trùng phạm vi với yêu cầu đang xử lý (409) hiện lời giải thích của máy chủ', async () => {
    const el = await render(ReopenCreatePage);
    const page = instance as CreatePageView;
    page.setPeriod('p-2026q1');
    page.addTarget(BINH_Q1);
    type(el, '#rq-basis-code', '15-QĐ/UBKT');
    await submitAndCapture(el);
    // lần hai cùng hồ sơ: máy chủ (giả lập) trả 409 OVERSIGHT-409-INVALID-STATE
    await press(el, 'Tạo yêu cầu');
    const err = await until(el, 'p-message', has('Đã có yêu cầu mở lại đang xử lý'));
    expect(text(err)).toBe('Đã có yêu cầu mở lại đang xử lý cho cùng phạm vi của kỳ này.');
    expect(text(el)).not.toContain('Xung đột dữ liệu');
  });

  it('mở thẳng trang, căn cứ khiếu nại: chọn khiếu nại đã có kết luận của kỳ, gửi mã khiếu nại', async () => {
    const el = await render(ReopenCreatePage);
    const page = instance as CreatePageView;
    page.setBasisKind('KHIEU_NAI');
    const pick = await until(el, 'p-select[name="basisId"]');
    expect(text(el.querySelector('#rq-basis-l'))).toBe('Khiếu nại đã có kết luận');
    expect(
      pick.querySelector('.p-disabled, [aria-disabled="true"]') ?? pick.matches('.p-disabled'),
    ).toBeTruthy();
    expect(text(el.querySelector('#h-basis'))).toContain('Chọn kỳ đã khoá trước');
    page.setPeriod('p-2026q1');
    await poll(() => page.basisOptions().length > 0);
    // khiếu nại đã trả lời hoặc đang thi hành của Quý I; nhãn là mã nghiệp vụ, người khiếu nại, trạng thái
    expect(page.basisOptions()).toEqual([
      {
        value: 'kn-2026-0038',
        code: 'KN-2026-0038',
        label: expect.stringMatching(/^KN-2026-0038 · .+ · Đã trả lời$/),
      },
    ]);
    page.addTarget(BINH_Q1);
    type(el, '#rq-reason', REASON);
    await new Promise((r) => setTimeout(r, 20));
    expect(page.ready()).toBe(false);
    page.pickBasis('kn-2026-0038');
    const { input } = await submitAndCapture(el);
    expect(input?.basis).toEqual({ kind: 'KHIEU_NAI', id: 'kn-2026-0038', code: 'KN-2026-0038' });
  });

  it('căn cứ đợt hậu kiểm: chọn từ danh sách đợt hậu kiểm của kỳ (listPostAudits)', async () => {
    await render(ReopenCreatePage);
    const page = instance as CreatePageView;
    page.setBasisKind('HAU_KIEM');
    page.setPeriod('p-2026q2');
    await poll(() => page.basisOptions().length > 0);
    expect(page.basisOptions().map((o) => o.label)).toEqual([
      'QĐ 12-QĐ/UBKT ngày 05/08/2026 · Hậu kiểm đột xuất · Đánh giá lại',
    ]);
  });

  it('cơ quan kiểm tra không đọc được danh sách khiếu nại: nêu nơi bắt đầu, khoá nút tạo', async () => {
    const list = vi.spyOn(ComplaintApi.prototype, 'list');
    const el = await render(ReopenCreatePage, only('SCR-1301.tao', 'SCR-1401.xem'));
    const page = instance as CreatePageView;
    page.setPeriod('p-2026q1');
    page.addTarget(BINH_Q1);
    page.setBasisKind('KHIEU_NAI');
    type(el, '#rq-reason', REASON);
    const start = await until(el, '#h-basis-start');
    expect(text(start)).toContain('mở từ trang khiếu nại đã có kết luận');
    expect(el.querySelector('p-select[name="basisId"]')).toBeNull();
    expect(text(el.querySelector('#h-submit'))).toContain('chọn loại căn cứ khác');
    const submit = [...el.querySelectorAll('button')].find((b) => text(b) === 'Tạo yêu cầu');
    expect((submit as HTMLButtonElement).disabled).toBe(true);
    expect(list).not.toHaveBeenCalled();
    // cảnh báo thì đọc được: ô chọn hiện, nhưng Quý I chưa có cảnh báo kết luận có vi phạm
    page.setBasisKind('CANH_BAO');
    await until(el, 'p-select[name="basisId"]');
    await poll(() => !page.basisRes.isLoading());
    expect(page.basisOptions()).toEqual([]);
    expect(el.querySelector('#h-submit')).toBeNull();
    list.mockRestore();
  });
});
