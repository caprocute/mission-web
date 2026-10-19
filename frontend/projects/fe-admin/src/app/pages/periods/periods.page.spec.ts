import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
  mockStepUp,
  resetMockApi,
} from '@vt/api-client';
import { ADMIN_CAN } from '../../admin-permissions';
import { PeriodsPage } from './periods.page';
import { PERIOD_POLL_MS, PERIOD_STEP_UP } from './step-up';

/** WEB-034: SCR-0801 trên dữ liệu giả lập: danh sách kỳ, lệnh khoá kỳ, saga, điều khiển, bù trừ. */
async function render(can: (k: string) => boolean = () => true) {
  await resetMockApi();
  TestBed.configureTestingModule({
    imports: [PeriodsPage],
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      { provide: ADMIN_CAN, useValue: can },
      {
        provide: PERIOD_STEP_UP,
        useValue: () => {
          mockStepUp();
          return Promise.resolve(true);
        },
      },
      // không tự đọc lại trong kiểm thử để mỗi lần đọc là do thao tác của kiểm thử
      { provide: PERIOD_POLL_MS, useValue: 3_600_000 },
    ],
  });
  const fixture = TestBed.createComponent(PeriodsPage);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  await until(el, '.steps tbody tr');
  return el;
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

async function pick(el: HTMLElement, name: string) {
  ((await until(el, '.pitem', has(name))) as HTMLButtonElement).click();
}

async function press(el: HTMLElement, label: string) {
  const b = (await until(el, 'button', (e) => text(e) === label)) as HTMLButtonElement;
  b.click();
}

describe('PeriodsPage (SCR-0801)', () => {
  it('mặc định chọn kỳ đang khoá: lệnh, bốn chỉ số, sáu bước, MSG-08-03, hồ sơ lỗi, nhật ký', async () => {
    const el = await render();
    expect([...el.querySelectorAll('.pitem .pname')].map(text)).toEqual([
      'Quý I/2027',
      'Quý IV/2026',
      'Năm 2026',
      'Tháng 9/2026',
      'Quý III/2026',
      'Tháng 8/2026',
      'Quý II/2026',
      'Quý I/2026',
    ]);
    expect(text(el.querySelector('.pitem.sel'))).toContain('Quý III/2026');
    expect(text(el.querySelector('.pitem'))).toContain('Đã tạo');
    const info = text(el.querySelector('.info'));
    expect(info).toContain('Thông báo 21-TB/ĐUK');
    expect(info).toContain('Phạm Quang Huy');
    expect(info).toContain('Đã khởi chạy');
    expect(text(el.querySelector('.cards'))).toContain('298.760');
    expect(el.querySelectorAll('.steps tbody tr')).toHaveLength(6);
    expect(text(el.querySelector('.steps tbody tr.cur'))).toContain('2. Tính điểm hàng loạt');
    expect(text(el)).toContain('Có 214 minh chứng chưa được nghiệm thu sẽ không được tính điểm');
    await until(el, 'h2', has('Hồ sơ lỗi (17)'));
    await until(el, '.events li', has('chuyển sang trạng thái Đang khoá'));
    expect(text(el.querySelector('.controls'))).toContain('Tạm dừng sau bước hiện tại');
    expect(text(el.querySelector('.controls'))).toContain('Chạy lại hồ sơ lỗi');
  });

  it('vai trò chỉ xem không thấy nút tạo kỳ và nút điều khiển', async () => {
    const el = await render((k) => k === 'SCR-0801.xem');
    expect(text(el.querySelector('.head'))).not.toContain('Tạo kỳ');
    expect(text(el.querySelector('.controls'))).not.toContain('Tạm dừng');
    expect(text(el.querySelector('.controls'))).not.toContain('Huỷ và hoàn tác');
  });

  it('phê duyệt lệnh Quý IV: MSG-08-01; khởi chạy bị chặn vì người khởi chạy trùng người duyệt', async () => {
    const el = await render();
    await pick(el, 'Quý IV/2026');
    await press(el, 'Phê duyệt lệnh');
    const ok = await until(el, 'p-message', has('đã được phê duyệt bởi đồng chí'));
    expect(text(ok)).toContain('Lệnh khoá kỳ Quý IV/2026 đã được phê duyệt bởi đồng chí Nguyễn Văn Minh');
    await press(el, 'Khởi chạy');
    const err = await until(el, 'p-message', has('Người khởi chạy phải khác'));
    expect(text(err)).toContain('Người khởi chạy phải khác người phê duyệt');
  });

  it('khởi chạy Năm 2026 khi chưa qua hạn nghiệm thu tồn đọng: MSG-08-02', async () => {
    const el = await render();
    await pick(el, 'Năm 2026');
    await press(el, 'Khởi chạy');
    const err = await until(el, 'p-message', has('Chưa thể khởi chạy'));
    expect(text(err)).toContain('chưa qua hạn nghiệm thu tồn đọng (08/01/2027)');
  });

  it('khởi chạy Tháng 9: kỳ sang Đang khoá, saga hiện bước 2 đang chạy', async () => {
    const el = await render();
    await pick(el, 'Tháng 9/2026');
    await press(el, 'Khởi chạy');
    await until(el, 'p-message', has('Đã khởi chạy tiến trình khoá kỳ Tháng 9/2026'));
    await until(el, '.pitem.sel', has('Đang khoá'));
    const cur = await until(el, '.steps tbody tr.cur');
    expect(text(cur)).toContain('Tính điểm hàng loạt');
    expect(text(el.querySelector('.cards'))).toContain('4.812');
  });

  it('duyệt thứ hai huỷ Tháng 8: saga bù trừ', async () => {
    const el = await render();
    await pick(el, 'Tháng 8/2026');
    const box = await until(el, '.cancelbox');
    expect(text(box)).toContain('Hoàng Đức Dũng');
    expect(text(box)).toContain('sai sót cấu hình trọng số khối 3');
    await press(el, 'Duyệt huỷ và hoàn tác');
    await until(el, 'p-message', has('hệ thống đang hoàn tác theo thứ tự ngược'));
    await until(el, '.row-head .status', has('Đang hoàn tác'));
    await until(el, '.steps tbody tr', has('Đã hoàn tác'));
  });

  it('đề nghị huỷ Quý III: lý do ngắn báo lỗi trường, đủ dài thì MSG-08-05', async () => {
    const el = await render();
    await press(el, 'Huỷ và hoàn tác toàn bộ');
    const area = (await until(el, '#cc-reason')) as HTMLTextAreaElement;
    area.value = 'sai';
    area.dispatchEvent(new Event('input'));
    await press(el, 'Gửi đề nghị huỷ');
    await until(el, '.err', has('ít nhất 10 ký tự'));
    area.value = 'Sai trọng số khối 3 trong Policy Pack';
    area.dispatchEvent(new Event('input'));
    await press(el, 'Gửi đề nghị huỷ');
    await until(el, 'p-message', has('Đề nghị huỷ và hoàn tác cần người duyệt thứ hai'));
    expect(text(await until(el, '.cancelbox'))).toContain('Nguyễn Văn Minh');
  });
});
