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
let cmp: { newPartition: { set(v: number | null): void } };

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
  cmp = fixture.componentInstance as unknown as typeof cmp;
  const el = fixture.nativeElement as HTMLElement;
  await until(el, '.tbl tbody tr', (e) => text(e).includes('Số '));
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
  it('90 ngày gần nhất, bản ghi 1042 tô đỏ, MSG-13-06, niêm phong và đối chứng của bản ghi sai', async () => {
    const el = await render();
    const broken = el.querySelector('tr.broken');
    expect(broken?.getAttribute('data-entry')).toBe('17·1042');
    expect(broken?.getAttribute('data-event-type')).toBe('product.evidence.accepted.v1');
    expect(text(broken)).toContain('Số 1042');
    expect(text(broken)).toContain('Nghiệm thu sản phẩm');
    expect(text(broken)).toContain('Hệ số C: 0,85');
    expect(text(broken)).toContain('Hệ số C: 0,95');
    const chain = await until(el, 'section', has('Kết quả kiểm tra toàn vẹn'));
    const bad = chain.querySelector('li.bad-box');
    expect(bad?.getAttribute('data-stored-hash')).toMatch(/^4b07/);
    expect(bad?.getAttribute('data-recomputed-hash')).toMatch(/^9d3c/);
    expect(text(bad)).toContain('Bản ghi số 1042');
    expect(text(bad)).toContain('Đã bị thay đổi');
    expect(text(chain)).toContain(
      'Phát hiện dữ liệu bị thay đổi trái phép tại bản ghi số 1042. Hệ thống đã tạo cảnh báo mức Cao',
    );
    expect(text(el.querySelector('#r-h')!.parentElement)).toContain(
      'Phát hiện dữ liệu bị thay đổi',
    );
    const seal = await until(
      el,
      'section',
      (e) => !!e.querySelector('#s-h') && has('Chữ ký số')(e),
    );
    expect(text(seal)).toContain('Hợp lệ');
    expect(text(seal)).toContain('Kho lưu trữ VPTW');
    // không hiện mã kỹ thuật trên giao diện (YC-PT-10)
    expect(text(el)).not.toMatch(/Merkle|RFC 3161|phân vùng|\.v1\b|băm/);
  });

  it('lọc theo đối tượng từ một dòng; chọn bản ghi xem chi tiết', async () => {
    const el = await render();
    const link = (await until(
      el,
      'button.link',
      (e) => e.getAttribute('data-aggregate-id') === 'rr-2026-007',
    )) as HTMLElement;
    link.click();
    await until(el, '.tbl tbody', (e) => e.querySelectorAll('tr').length === 2);
    (el.querySelector('.tbl tbody button.link') as HTMLElement).click();
    const seal = await until(
      el,
      'section',
      (e) => !!e.querySelector('#s-h') && has('Số 1046 ·')(e),
    );
    expect(seal.querySelector('dl')?.getAttribute('data-entry')).toBe('17·1046');
    expect(text(seal)).toContain('Phê duyệt yêu cầu mở lại kỳ');
  });

  it('chạy kiểm tra một phân vùng khớp hết: MSG-13-05; người chỉ tra cứu không thấy nút chạy, xuất', async () => {
    const el = await render();
    // giao diện không còn ô chọn phân vùng (YC-PT-10); kiểm tra một phần dữ liệu đặt thẳng vào trang
    cmp.newPartition.set(5);
    ((await until(el, 'button', has('Chạy kiểm tra toàn vẹn'))) as HTMLButtonElement).click();
    const ok = await until(el, 'p-message', has('Kiểm tra toàn vẹn hoàn tất'));
    expect(text(ok)).toContain('20.068 bản ghi đều nguyên vẹn');
    TestBed.resetTestingModule();
    const ro = await render((k) => k === 'SCR-1302.tra-cuu');
    expect(text(ro)).not.toContain('Chạy kiểm tra toàn vẹn');
    expect(text(ro)).not.toContain('Xuất bằng chứng');
  });
});
