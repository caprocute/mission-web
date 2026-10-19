import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
  resetMockApi,
} from '@vt/api-client';
import { OVERSIGHT_CAN } from '../../oversight-permissions';
import { VERIFY_POLL_MS } from '../../step-up';
import { LedgerPage } from './ledger.page';

/** WEB-043: SCR-1302 trên dữ liệu giả lập: tra cứu, kết quả chuỗi băm, niêm phong, chạy kiểm tra. */
async function render(can: (k: string) => boolean = () => true) {
  await resetMockApi();
  TestBed.configureTestingModule({
    imports: [LedgerPage],
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      { provide: OVERSIGHT_CAN, useValue: can },
      { provide: VERIFY_POLL_MS, useValue: 10 },
    ],
  });
  const fixture = TestBed.createComponent(LedgerPage);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  await until(el, '.tbl tbody tr', (e) => text(e).includes('·'));
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

describe('LedgerPage (SCR-1302)', () => {
  it('90 ngày gần nhất, bản ghi 1042 tô đỏ, MSG-13-06, niêm phong và bản neo của bản ghi sai', async () => {
    const el = await render();
    const broken = el.querySelector('tr.broken');
    expect(text(broken)).toContain('Số 1042 Phân vùng 17');
    expect(text(broken)).toContain('Nghiệm thu minh chứng');
    expect(text(broken)).toContain('Tiến độ (C): 0,85');
    expect(text(broken)).toContain('Tiến độ (C): 0,95');
    const chain = await until(el, 'section', has('Kết quả kiểm tra toàn vẹn'));
    expect(text(chain)).toContain('Mã kiểm tra: 4b07…e7f8 ≠ tính lại 9d3c…c6d7');
    expect(text(chain)).toContain(
      'Phát hiện sổ cái mất toàn vẹn tại bản ghi số 1042 của phân vùng 17. Hệ thống đã tạo cảnh báo mức Cao',
    );
    expect(text(el.querySelector('#r-h')!.parentElement)).toContain('63/64 phân vùng khớp');
    const seal = await until(
      el,
      'section',
      (e) => !!e.querySelector('#s-h') && has('Mã niêm phong')(e),
    );
    expect(text(seal)).toContain('Hợp lệ');
    expect(text(seal)).toContain('Kho lưu trữ VPTW');
  });

  it('lọc theo đối tượng từ một dòng; chọn bản ghi xem chi tiết', async () => {
    const el = await render();
    const link = (await until(
      el,
      'button.link',
      has('Yêu cầu mở lại kỳ: MLK-2026-007'),
    )) as HTMLElement;
    link.click();
    await until(el, '.tbl tbody', (e) => e.querySelectorAll('tr').length === 2);
    (el.querySelector('.tbl tbody button.link') as HTMLElement).click();
    const seal = await until(
      el,
      'section',
      (e) => !!e.querySelector('#s-h') && has('Số 1046, phân vùng 17 ·')(e),
    );
    expect(text(seal)).toContain('Mã kiểm tra');
  });

  it('chạy kiểm tra một phân vùng khớp hết: MSG-13-05; người chỉ tra cứu không thấy nút chạy, xuất', async () => {
    const el = await render();
    const part = el.querySelector('#lg-vpart') as HTMLInputElement;
    part.value = '5';
    part.dispatchEvent(new Event('input'));
    ((await until(el, 'button', has('Chạy kiểm tra toàn vẹn'))) as HTMLButtonElement).click();
    const ok = await until(el, 'p-message', has('Kiểm tra toàn vẹn hoàn tất: 1/1 phân vùng khớp'));
    expect(text(ok)).toContain('20.068 bản ghi');
    TestBed.resetTestingModule();
    const ro = await render((k) => k === 'SCR-1302.tra-cuu');
    expect(text(ro)).not.toContain('Chạy kiểm tra toàn vẹn');
    expect(text(ro)).not.toContain('Xuất tệp bằng chứng');
  });
});
