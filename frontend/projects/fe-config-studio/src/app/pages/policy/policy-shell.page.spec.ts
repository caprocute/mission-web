import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
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
import { PolicyShellPage } from './policy-shell.page';
import { WorkflowPage } from './workflow.page';

/**
 * Phiên bản đã khoá nội dung (QT-15): người soạn không bị kẹt ở chế độ chỉ xem mà được dẫn tới bản nháp
 * đang có, hoặc tạo bản nháp mới từ phiên bản đã phát hành (`createPolicyPackVersion`) rồi cấu hình tiếp
 * luồng duyệt (SCR-1504, hành trình HT-38).
 */
const P_DANG = '0192a1b0-3c4d-7e5f-8a9b-000000004001';
const P_VC = '0192a1b0-3c4d-7e5f-8a9b-000000004003';
const AUTHOR: PermissionKey[] = ['SCR-1501.soan', 'SCR-1502.soan', 'SCR-1504.cau-hinh'];

async function open(url: string, keys: PermissionKey[] = AUTHOR) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter(
        [
          {
            path: 'goi/:packId/:version',
            component: PolicyShellPage,
            children: [{ path: 'luong-duyet', component: WorkflowPage }],
          },
        ],
        withComponentInputBinding(),
      ),
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
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(url);
  harness.fixture.autoDetectChanges();
  return harness.routeNativeElement!.ownerDocument.body;
}

async function until(
  el: ParentNode,
  sel: string,
  test: (e: Element) => boolean = () => true,
  tries = 300,
) {
  for (let i = 0; i < tries; i++) {
    const e = el.querySelector(sel);
    if (e && test(e)) return e;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw new Error(`Không thấy ${sel}`);
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('Config Studio · Phiên bản đã khoá nội dung (QT-15)', () => {
  beforeEach(() => resetMockData());

  it('bản đã phát hành, gói chưa có bản nháp: tạo bản nháp mới rồi cấu hình luồng duyệt ở bản đó', async () => {
    const el = await open(`/goi/${P_VC}/1.0.0/luong-duyet`);
    await until(el, '[data-testid=wf-hint]');
    expect(el.querySelector('[data-testid=simulate]')).toBeNull();
    expect(el.querySelector('[data-testid=open-draft]')).toBeNull();

    ((await until(el, '[data-testid=new-version] button')) as HTMLButtonElement).click();
    const note = (await until(el, '[data-testid=new-version-note]')) as HTMLTextAreaElement;
    expect(text(el.querySelector('.p-dialog'))).toContain('của phiên bản 1');
    const create = el.querySelector('[data-testid=new-version-create] button') as HTMLButtonElement;
    expect(create.disabled).toBe(true);
    note.value = 'Rút ngắn thời hạn mỗi bước duyệt';
    note.dispatchEvent(new Event('input'));
    await until(
      el,
      '[data-testid=new-version-create] button',
      (b) => !(b as HTMLButtonElement).disabled,
    );
    (el.querySelector('[data-testid=new-version-create] button') as HTMLButtonElement).click();

    await until(el, '[data-testid=pack-bar]', (b) => text(b).includes('Phiên bản 1.2 · Bản nháp'));
    expect(TestBed.inject(Router).url).toBe(`/goi/${P_VC}/1.2.0/luong-duyet`);
    await until(el, '[data-testid=simulate]');
    expect(el.querySelector('[data-testid=wf-hint]')).toBeNull();
    expect(el.querySelector('[data-testid=new-version]')).toBeNull();
    expect(text(el)).not.toMatch(/NHAP|DA_PHAT_HANH|SCR-/);
  });

  it('bản đã phát hành, gói đang có bản nháp: dẫn tới bản nháp cùng thẻ thay vì tạo thêm', async () => {
    const el = await open(`/goi/${P_DANG}/2.0.0/luong-duyet`);
    const link = await until(el, '[data-testid=open-draft]');
    expect(text(link)).toBe('Mở bản nháp 2.1 để chỉnh sửa');
    expect(link.getAttribute('href')).toBe(`/goi/${P_DANG}/2.1.0/luong-duyet`);
    expect(el.querySelector('[data-testid=new-version]')).toBeNull();
  });

  it('người không soạn chính sách: chỉ xem, không có lối tạo bản nháp', async () => {
    const el = await open(`/goi/${P_VC}/1.0.0/luong-duyet`, ['SCR-1504.cau-hinh']);
    await until(el, '[data-testid=wf-hint]');
    expect(el.querySelector('[data-testid=new-version]')).toBeNull();
    expect(el.querySelector('[data-testid=open-draft]')).toBeNull();
  });
});
