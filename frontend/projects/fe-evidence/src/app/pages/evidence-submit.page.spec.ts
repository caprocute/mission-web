import {
  HttpInterceptorFn,
  HttpResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { map } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { EVIDENCE_CAN } from '../evidence-permissions';
import { EVIDENCE_SCAN_POLL_MS, EvidenceSubmitPage } from './evidence-submit.page';

/** WEB-032: SCR-0701 trên dữ liệu giả lập: phiên bản 2 như Hình 8.13, quét, trùng mã băm, nộp, chỉ đọc. */
const EV = '0192a1b2-0000-7000-8000-000000000142';

async function render(
  can: (k: string) => boolean = () => true,
  elderly = false,
  extra: HttpInterceptorFn[] = [],
) {
  TestBed.configureTestingModule({
    imports: [EvidenceSubmitPage],
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([...extra, idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      { provide: EVIDENCE_CAN, useValue: can },
      { provide: EVIDENCE_SCAN_POLL_MS, useValue: 10 },
    ],
  });
  if (elderly) TestBed.inject(ThemeService).mode.set('elderly');
  const fixture = TestBed.createComponent(EvidenceSubmitPage);
  fixture.componentRef.setInput('id', EV);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  // chờ cả minh chứng lẫn nhiệm vụ (vai trò chủ trì, hạn): hai request có thể về theo thứ tự bất kỳ
  await until(
    () =>
      el.querySelector('.tbl tbody tr') &&
      el.querySelector('.sep, .meta .chip') &&
      el.querySelector('.sub')?.textContent?.includes('hạn'),
  );
  return el;
}

async function until(test: () => unknown) {
  for (let i = 0; i < 250; i++) {
    if (test()) return;
    await new Promise((r) => setTimeout(r, 20));
  }
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const button = (el: HTMLElement, label: string) =>
  [...el.querySelectorAll('button')].find((b) => text(b) === label) as HTMLButtonElement;
const rows = (el: HTMLElement) => [...el.querySelectorAll('.tbl tbody tr')].map(text);

function type(el: HTMLElement, sel: string, value: string) {
  const i = el.querySelector(sel) as HTMLInputElement | HTMLTextAreaElement;
  i.value = value;
  i.dispatchEvent(new Event('input'));
}

function check(el: HTMLElement, sel: string) {
  (el.querySelector(sel) as HTMLInputElement).click();
}

function pick(el: HTMLElement, file: File) {
  const input = el.querySelector('#file-in') as HTMLInputElement;
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  input.dispatchEvent(new Event('change'));
}

/** Phiên bản cuối vừa bị yêu cầu bổ sung, máy chủ trả kèm quyết định nghiệm thu gần nhất. */
const returned: HttpInterceptorFn = (req, next) =>
  next(req).pipe(
    map((ev) =>
      ev instanceof HttpResponse && req.method === 'GET' && /\/versions\/\d+$/.test(req.url)
        ? ev.clone({
            body: {
              ...(ev.body as object),
              status: 'YEU_CAU_BO_SUNG',
              acceptance: { decision: 'YEU_CAU_BO_SUNG', comment: 'Bổ sung bảng tổng hợp quý.' },
            },
          })
        : ev,
    ),
  );

describe('EvidenceSubmitPage (SCR-0701)', () => {
  beforeEach(async () => (await import('@vt/api-client')).resetMockApi());

  it('phiên bản 2 như Hình 8.13: văn bản vOffice, phụ lục đang quét rồi sạch, cảnh báo trùng', async () => {
    const el = await render();
    expect(text(el.querySelector('.sub'))).toContain('NV-2026-Q4-0142');
    expect(text(el.querySelector('#file-h'))).toContain('phiên bản 2');
    expect((el.querySelector('input[value=VAN_BAN_HE_THONG]') as HTMLInputElement).checked).toBe(
      true,
    );
    expect(text(el.querySelector('.doc'))).toContain('47-BC/BTCĐUK');
    await until(() => el.querySelector('.doc + .muted'));
    expect(text(el.querySelector('.doc + .muted'))).toContain('Trần Thị Bình');
    expect(rows(el)[1]).toContain('phu-luc-so-lieu.xlsx');
    expect(rows(el)[1]).toContain('96 KB');
    // mã kiểm tra toàn vẹn (SHA-256) không hiện cho người dùng (YC-PT-24)
    expect(rows(el)[1]).not.toMatch(/77be/);
    expect(text(el.querySelector('.dup'))).toContain('NV-2026-Q3-0088 (Phạm Thu Hà');
    expect([...el.querySelectorAll('.versions li')].map(text)).toEqual([
      expect.stringContaining('Phiên bản 1'),
      expect.stringContaining('Phiên bản 2'),
    ]);
    expect(text(el.querySelector('.versions'))).toContain('Bổ sung số liệu chi bộ 3, 6');
    await until(() => rows(el)[1].includes('Sạch'));
    expect(rows(el)[1]).toContain('Sạch');
  });

  it('chỉ nộp khi đủ giải trình và cam kết; nộp xong báo người nghiệm thu, chuyển chỉ đọc', async () => {
    const el = await render();
    await until(() => rows(el)[1].includes('Sạch'));
    const submit = () => button(el, 'Nộp để nghiệm thu');
    expect(submit().disabled).toBe(true);
    expect(text(el.querySelector('#submit-why'))).toContain('ghi giải trình');
    type(el, '#reuse', 'Phụ lục số liệu dùng chung cho hai báo cáo quý III');
    check(el, '.att input');
    await until(() => !submit().disabled);
    submit().click();
    await until(() => el.querySelector('.msg.ok'));
    const ok = [...el.querySelectorAll('.msg.ok')].map(text);
    expect(ok[0]).toBe('Đã nộp phiên bản 2. Người nghiệm thu: đồng chí Lê Minh Châu.');
    expect(ok[1]).toBe('Nhiệm vụ đã chuyển sang Chờ nghiệm thu.');
    await until(() => !button(el, 'Lưu nháp') && el.querySelector('.msg.info'));
    expect(text(el.querySelector('.msg.info'))).toContain('không sửa được');
  });

  it('tải tệp: tính băm, chặn kiểu không cho phép, báo trùng mã băm (MSG-07-01)', async () => {
    const el = await render();
    pick(el, new File(['x'], 'ban-thao.exe'));
    await until(() => el.querySelector('.msg.err[role=alert]:not(.dup)'));
    expect(text(el.querySelector('.msg.err:not(.dup)'))).toContain('không thuộc PDF');

    const content = 'So lieu tong hop cac chi bo quy III/2026\n';
    pick(el, new File([content], 'so-lieu-moi.xlsx'));
    await until(() => rows(el).some((r) => r.includes('so-lieu-moi.xlsx')));
    expect(text(el.querySelector('.dup'))).toContain('so-lieu-moi.xlsx');
    await until(() => rows(el)[1].includes('Sạch'));
  });

  it('không có quyền soạn: chỉ đọc; chế độ cao tuổi ẩn cột SHA-256', async () => {
    const el = await render((k) => k === 'SCR-0701.xem', true);
    expect(button(el, 'Lưu nháp')).toBeUndefined();
    expect(el.querySelector('#file-in')).toBeNull();
    expect([...el.querySelectorAll('.tbl th')].map(text)).not.toContain('Mã kiểm tra toàn vẹn');
  });

  it('phiên bản bị yêu cầu bổ sung: hiện ý kiến người nghiệm thu cho người nộp', async () => {
    const el = await render(() => true, false, [returned]);
    await until(() => el.querySelector('#ret-h'));
    expect(text(el.querySelector('#ret-h'))).toBe('Ý kiến người nghiệm thu đề nghị bổ sung');
    expect(text(el.querySelector('#ret-h + p'))).toBe('Bổ sung bảng tổng hợp quý.');
  });
});
