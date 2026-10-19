import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { Type, WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
  mockStepUp,
  resetMockApi,
} from '@vt/api-client';
import { of } from 'rxjs';
import { ADMIN_CAN } from '../../admin-permissions';
import { ACCESS_ME, ACCESS_STEP_UP } from './access-ui';

/** Dựng trang quản trị quyền trên dữ liệu giả lập; `me` là người dùng mà giao diện tin là mình. */
export async function renderAccess<T>(
  page: Type<T>,
  opts: { can?: (k: string) => boolean; me?: string; params?: Record<string, string> } = {},
) {
  await resetMockApi();
  TestBed.configureTestingModule({
    imports: [page],
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      { provide: ADMIN_CAN, useValue: opts.can ?? (() => true) },
      { provide: ACCESS_ME, useValue: () => opts.me ?? 'dev-user' },
      {
        provide: ACCESS_STEP_UP,
        useValue: () => {
          mockStepUp();
          return Promise.resolve(true);
        },
      },
      ...(opts.params
        ? [{ provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap(opts.params)) } }]
        : []),
    ],
  });
  const fixture = TestBed.createComponent(page);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  /** Chọn đơn vị như khi bấm trên cây (cây có kiểm thử riêng ở SCR-0103). */
  const selectUnit = (id: string) =>
    (
      fixture.componentInstance as unknown as { selected: WritableSignal<string | null> }
    ).selected.set(id);
  return { el, selectUnit };
}

export async function until(
  el: HTMLElement,
  sel: string,
  test: (e: Element) => boolean = () => true,
) {
  for (let i = 0; i < 200; i++) {
    const e = [...el.querySelectorAll(sel)].find(test);
    if (e) return e;
    await new Promise((r) => setTimeout(r, 20));
  }
  throw new Error(`Không thấy ${sel}`);
}

export const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
export const has = (s: string) => (e: Element) => text(e).includes(s);
export const row = (el: HTMLElement, s: string) =>
  [...el.querySelectorAll('tbody tr')].find(has(s)) ?? null;
export const buttons = (scope: Element | null) =>
  [...(scope?.querySelectorAll('button') ?? [])].map(text).filter(Boolean);
