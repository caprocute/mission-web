import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { PermissionKey } from '@vt/auth';
import { resetMockData } from '../../../../../api-client/src/mock/mock-registry';
import { STUDIO_ACCESS } from '../../studio-permissions';
import { WfMonitorPage } from './wf-monitor.page';

/** WEB-062: SCR-1506 trên dữ liệu giả lập; quyền theo ma trận wiki/15a. */
const ADMIN: PermissionKey[] = [
  'SCR-1506.xem',
  'SCR-1506.chuyen-nguoi-xu-ly',
  'SCR-1506.gia-han-buoc',
  'SCR-1506.so-do',
];

async function render(keys: PermissionKey[]) {
  resetMockData();
  TestBed.configureTestingModule({
    imports: [WfMonitorPage],
    providers: [
      provideRouter([]),
      provideNoopAnimations(),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      {
        provide: STUDIO_ACCESS,
        useValue: {
          can: (k: PermissionKey) => keys.includes(k),
          author: (k: PermissionKey) => keys.includes(k),
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(WfMonitorPage);
  fixture.autoDetectChanges();
  await until(document, '[data-testid=instances] tbody tr');
  return fixture.nativeElement as HTMLElement;
}

async function until(
  el: ParentNode,
  sel: string,
  test: (e: Element) => boolean = () => true,
  tries = 500,
) {
  for (let i = 0; i < tries; i++) {
    const e = el.querySelector(sel);
    if (e && test(e)) return e;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw new Error(`Không thấy ${sel}`);
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const rowOf = (el: HTMLElement, title: string) =>
  [...el.querySelectorAll('[data-testid=instances] tbody tr')].find((r) =>
    text(r).includes(title),
  ) as HTMLElement;
const btn = (root: ParentNode, label: string) =>
  [...root.querySelectorAll('button')].find((b) => text(b) === label) as HTMLButtonElement;
const set = (sel: string, v: string, ev = 'input') => {
  const e = document.querySelector(sel) as HTMLInputElement;
  e.value = v;
  e.dispatchEvent(new Event(ev));
};

describe('SCR-1506 Giám sát tiến trình theo mẫu', () => {
  it('lọc tiến trình kẹt, tổng hợp theo cụm, chuyển người xử lý ghi sổ cái', async () => {
    const el = await render(ADMIN);
    expect(text(el.querySelector('[data-testid=cells]'))).toContain('Hà Nội');
    const box = el.querySelector('.filters input[type=checkbox]') as HTMLInputElement;
    box.checked = true;
    box.dispatchEvent(new Event('change'));
    await until(el, '[data-testid=instances] tbody', (b) => b.querySelectorAll('tr').length === 2);
    const row = rowOf(el, 'Trần Minh Hoàng');
    expect(text(row)).toContain('Chưa có người xử lý');
    expect(text(row)).toContain('Kẹt');
    btn(row, 'Chuyển người xử lý').click();
    await until(document, '[data-testid=assignee] option[value=p-binh]');
    set('[data-testid=assignee]', 'p-binh', 'change');
    set('#r-why', 'Đồng chí thẩm định nghỉ phép, giao người cùng nhóm xử lý.');
    await until(
      document,
      '[data-testid=confirm-reassign] button',
      (b) => !(b as HTMLButtonElement).disabled,
    );
    (document.querySelector('[data-testid=confirm-reassign] button') as HTMLButtonElement).click();
    await until(el, '[role=status]', (e) => text(e).includes('ghi vào sổ cái'));
    expect(el.querySelector('input[id^=vars], [data-testid=edit-vars]')).toBeNull();
  }, 30000);

  it('gia hạn và xem sơ đồ với bước hiện tại; không có quyền thì không thấy nút thao tác', async () => {
    const el = await render(ADMIN);
    const row = rowOf(el, 'Phạm Quốc Khánh');
    btn(row, 'Xem sơ đồ').click();
    await until(document, 'vt-wf-diagram g.active');
    expect(document.querySelector('vt-wf-diagram g.active')?.getAttribute('data-el')).toBe(
      'xetDuyet',
    );
    expect(text(document.querySelector('[data-testid=history]'))).toContain('Bắt đầu');

    TestBed.resetTestingModule();
    const ro = await render(['SCR-1506.xem']);
    expect(btn(ro, 'Chuyển người xử lý')).toBeUndefined();
    expect(btn(ro, 'Gia hạn')).toBeUndefined();
  }, 30000);
});
