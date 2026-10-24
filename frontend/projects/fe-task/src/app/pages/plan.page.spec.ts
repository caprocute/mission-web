import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { DEFAULT_RUNTIME_CONFIG, MOCK_API_OPTIONS, RUNTIME_CONFIG, idempotencyInterceptor, mockApiInterceptor } from '@vt/api-client';
import { PlanApi } from '@vt/api-client/domains';
import { firstValueFrom, of } from 'rxjs';
import { TASK_CAN } from '../task-permissions';
import { PlanPage } from './plan.page';

/**
 * WEB-026, WEB-065: SCR-0402 trên dữ liệu giả lập theo hợp đồng, kiểm tra BR-04-02..07, BR-04-10, ẩn/hiện
 * theo ma trận quyền.
 */
async function render(
  ready: string,
  can: (k: string) => boolean = () => true,
  query: Record<string, string> = {},
  before?: (api: PlanApi) => Promise<void>,
) {
  TestBed.configureTestingModule({
    imports: [PlanPage],
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      { provide: TASK_CAN, useValue: can },
      { provide: ActivatedRoute, useValue: { queryParamMap: of(convertToParamMap(query)) } },
    ],
  });
  if (before) await before(TestBed.inject(PlanApi));
  const fixture = TestBed.createComponent(PlanPage);
  fixture.autoDetectChanges();
  await until(fixture.nativeElement, ready);
  return fixture.nativeElement as HTMLElement;
}

async function until(el: HTMLElement, sel: string, test: (e: Element) => boolean = () => true) {
  for (let i = 0; i < 100; i++) {
    const e = el.querySelector(sel);
    if (e && test(e)) return;
    await new Promise((r) => setTimeout(r, 20));
  }
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const buttons = (el: HTMLElement, scope = '') =>
  [...el.querySelectorAll(`${scope} button`)].map(text).filter(Boolean);

const click = (el: HTMLElement, label: string, scope = '') =>
  (
    [...el.querySelectorAll(`${scope} button`)].find((b) => text(b) === label) as HTMLButtonElement
  ).click();

describe('PlanPage (SCR-0402)', () => {
  beforeEach(async () => (await import('@vt/api-client')).resetMockApi());

  it('mở kế hoạch đang soạn, đánh dấu dòng có lỗi chặn và khoá Gửi duyệt', async () => {
    const el = await render('.checks li');
    await until(el, '.checks li', () => el.querySelectorAll('.checks li').length > 3);
    expect(text(el.querySelector('h1'))).toContain('Quý I/2027');
    expect(el.querySelectorAll('.tbl tbody tr')).toHaveLength(6);
    expect(el.querySelectorAll('.tbl tr.bad')).toHaveLength(1);
    const checks = [...el.querySelectorAll('.checks li')].map(text);
    expect(checks.some((c) => c.includes('KPI mơ hồ ở dòng 6'))).toBe(true);
    expect(checks.some((c) => c.includes('KR 2.1 chưa có nhiệm vụ'))).toBe(true);
    // luật không có vi phạm hiện "Đạt" theo danh sách luật phía client; BR-04-10 chỉ ở bản điều chỉnh
    expect(checks).toContain('ĐạtMỗi việc có đúng 1 chủ trì');
    expect(checks.some((c) => c.includes('lý do điều chỉnh'))).toBe(false);
    expect(text(el.querySelector('.tbl tbody tr'))).toContain('Văn phòng Đảng uỷ');
    const submit = [...el.querySelectorAll('.actions button')].find(
      (b) => text(b) === 'Gửi duyệt',
    ) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    expect(text(el.querySelector('.banner'))).toContain('Hạn phê duyệt kế hoạch');
  });

  it('không có quyền xem thì không gọi dữ liệu, chỉ báo thiếu quyền', async () => {
    const el = await render('.msg', (k) => k !== 'SCR-0402.xem');
    expect(text(el.querySelector('.msg'))).toContain('không có quyền xem');
    expect(el.querySelector('.tbl')).toBeNull();
  });

  it('chỉ có quyền xem thì không thấy nút lập, lưu, gửi duyệt', async () => {
    const el = await render('.tbl tbody tr', (k) => k === 'SCR-0402.xem');
    const all = buttons(el);
    expect(all).not.toContain('Lưu nháp');
    expect(all).not.toContain('Gửi duyệt');
    expect(all).not.toContain('Thêm từ danh mục');
  });

  it('kỳ chờ duyệt chỉ đọc, có lối sang màn phê duyệt khi được phép', async () => {
    const el = await render('.tbl tbody tr', () => true, { plan: 'plan-2026-q4' });
    expect(text(el.querySelector('.sub'))).toContain('Chờ duyệt');
    expect(buttons(el, '.actions')).toEqual(['Xem xét và duyệt']);
    TestBed.resetTestingModule();
    const ro = await render('.tbl tbody tr', (k) => !k.startsWith('SCR-0403'), {
      plan: 'plan-2026-q4',
    });
    expect(buttons(ro, '.actions')).toEqual([]);
  });

  it('bản điều chỉnh: dòng sửa phải ghi lý do thay đổi (BR-04-10) mới hết lỗi chặn', async () => {
    // phiên bản 2 Quý IV được duyệt và ký trên giả lập, rồi lập điều chỉnh lần 2 trên màn hình
    const approveQ4 = async (api: PlanApi) => {
      (await import('@vt/api-client')).mockStepUp();
      const v = await firstValueFrom(api.version('plan-2026-q4', 2));
      await firstValueFrom(api.approve('plan-2026-q4', 2, v.rowVersion, { signMethod: 'MYSIGN' }));
      const id = (await firstValueFrom(api.version('plan-2026-q4', 2))).signRequestId!;
      await firstValueFrom(api.signRequest(id));
      await firstValueFrom(api.signRequest(id));
    };
    const el = await render('.tbl tbody tr', () => true, { plan: 'plan-2026-q4' }, approveQ4);
    expect(text(el.querySelector('.msg.ok'))).toContain('đã duyệt và ký số');
    click(el, 'Tạo điều chỉnh', '.actions');
    await until(el.ownerDocument.body, '.p-dialog');
    const dialog = el.ownerDocument.body.querySelector('.p-dialog') as HTMLElement;
    expect(text(dialog)).toContain('Lần điều chỉnh 2/2');
    const adj = dialog.querySelector('#adj-reason') as HTMLTextAreaElement;
    adj.value = 'Bổ sung theo chỉ đạo mới';
    adj.dispatchEvent(new Event('input'));
    await new Promise((r) => setTimeout(r, 20));
    click(dialog, 'Tạo điều chỉnh');
    await until(el, '.sub', (e) => text(e).includes('phiên bản 3'));
    expect(text(el.querySelector('.sub'))).toContain('Nháp');
    (el.querySelector('[aria-label="Sửa dòng 1"]') as HTMLElement).click();
    await until(el, '#pi-qty');
    const qty = el.querySelector('#pi-qty') as HTMLInputElement;
    qty.value = '20';
    qty.dispatchEvent(new Event('input'));
    qty.dispatchEvent(new Event('blur'));
    await until(el, '.checks', (e) => text(e).includes('cần ghi lý do'));
    expect(text(el.querySelector('.checks'))).toContain(
      'Dòng 1 thay đổi so với phiên bản đã duyệt',
    );
    const reason = el.querySelector('#pi-reason') as HTMLInputElement;
    reason.value = 'Bổ sung hồ sơ theo chỉ đạo';
    reason.dispatchEvent(new Event('input'));
    await until(el, '.checks', (e) => text(e).includes('ĐạtMọi dòng thêm, sửa có lý do'));
    expect(text(el.querySelector('.checks'))).toContain('ĐạtMọi dòng thêm, sửa có lý do');
    expect(text(el)).toContain('Lý do điều chỉnh: Bổ sung theo chỉ đạo mới');
    // bỏ dòng gốc: hỏi lý do (BR-04-10), dòng bỏ hiện kèm lý do và khôi phục được
    (el.querySelector('[aria-label="Sửa dòng 2"]') as HTMLElement).click();
    await until(el, 'vt-plan-item-editor', (e) => text(e).includes('Bỏ dòng này'));
    click(el.querySelector('vt-plan-item-editor') as HTMLElement, 'Bỏ dòng này');
    const body = el.ownerDocument.body;
    await until(body, '#rm-reason');
    const rm = body.querySelector('#rm-reason') as HTMLTextAreaElement;
    rm.value = 'Không còn phù hợp';
    rm.dispatchEvent(new Event('input'));
    await new Promise((r) => setTimeout(r, 20));
    const rmDialog = rm.closest('.p-dialog') as HTMLElement;
    click(rmDialog, 'Bỏ dòng');
    await until(el, '.removed');
    expect(text(el.querySelector('.removed'))).toContain('Không còn phù hợp');
  });
});
