import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { SearchApi, SearchResult, SearchResultType } from '@vt/api-client';
import { PermissionService } from '@vt/auth';
import { Subscription } from 'rxjs';
import { safeInternalLink } from './relative-time';

/** Nhãn loại kết quả hiển thị cho người dùng. */
export const SEARCH_TYPE_LABEL: Record<SearchResultType, string> = {
  TASK: 'Nhiệm vụ',
  SOURCE_DOC: 'Văn bản chỉ đạo',
  CATALOG_ITEM: 'Danh mục công việc',
};

export interface Segment {
  text: string;
  mark: boolean;
}

/** Tách đoạn trích thành phần thường và phần khớp theo vị trí máy chủ trả (không dùng HTML). */
export function highlightSegments(
  text: string,
  highlights: { start: number; length: number }[] = [],
): Segment[] {
  const out: Segment[] = [];
  let at = 0;
  for (const h of [...highlights].sort((a, b) => a.start - b.start)) {
    const start = Math.max(at, Math.min(text.length, h.start));
    const end = Math.min(text.length, h.start + h.length);
    if (end <= start) continue;
    if (start > at) out.push({ text: text.slice(at, start), mark: false });
    out.push({ text: text.slice(start, end), mark: true });
    at = end;
  }
  if (at < text.length) out.push({ text: text.slice(at), mark: false });
  return out;
}

const MIN_CHARS = 2;
const DEBOUNCE_MS = 300;

/**
 * Ô tìm kiếm toàn cục trên thanh trên (SVC-16 `GET /api/v1/search`): gõ từ 2 ký tự thì gợi ý, mũi tên
 * lên xuống chọn, Enter mở, Esc đóng. Theo mẫu combobox của WAI-ARIA. Máy chủ kiểm quyền và lọc kết
 * quả. Vai trò không có quyền gọi tìm kiếm theo ma trận API thì không hiện ô (YC-PT-21); máy chủ vẫn
 * trả 403 thì ô tự ẩn trong phiên.
 */
@Component({
  selector: 'vt-global-search',
  host: {
    '(document:click)': 'onDocumentClick($event)',
    '[hidden]': 'forbidden() || !allowed()',
    '[class.mobile-open]': 'mobileOpen()',
  },
  template: `
    <!-- Màn hình hẹp: ô tìm kiếm thu thành nút, bấm thì mở phủ thanh trên -->
    <button
      type="button"
      class="toggle"
      aria-label="Tìm kiếm"
      [attr.aria-expanded]="mobileOpen()"
      (click)="openMobile()"
    >
      <i class="pi pi-search" aria-hidden="true"></i>
    </button>
    <label for="vt-global-search" class="sr-only">Tìm nhiệm vụ, văn bản, danh mục công việc</label>
    <i class="pi pi-search icon" aria-hidden="true"></i>
    <input
      id="vt-global-search"
      type="search"
      role="combobox"
      autocomplete="off"
      maxlength="200"
      placeholder="Tìm nhiệm vụ, văn bản…"
      aria-autocomplete="list"
      aria-controls="vt-global-search-list"
      [attr.aria-expanded]="expanded()"
      [attr.aria-activedescendant]="active() >= 0 ? 'vt-search-opt-' + active() : null"
      [value]="query()"
      (input)="onInput($any($event.target).value)"
      (keydown)="onKey($event)"
      (focus)="open.set(results().length > 0)"
    />
    <div class="popup" [hidden]="!showPopup()">
      <ul id="vt-global-search-list" role="listbox" aria-label="Kết quả tìm kiếm">
        @for (r of results(); track r.type + r.id; let i = $index) {
          <li
            [id]="'vt-search-opt-' + i"
            role="option"
            [attr.aria-selected]="i === active()"
            [class.active]="i === active()"
            (mousedown)="$event.preventDefault()"
            tabindex="-1"
            (click)="go(r)"
            (keydown.enter)="go(r)"
          >
            <span class="meta">
              <span class="type">{{ typeLabel(r.type) }}</span>
              @if (r.code) {
                <span class="code">{{ r.code }}</span>
              }
            </span>
            <span class="title">{{ r.title }}</span>
            @if (r.snippet && r.snippet !== r.title) {
              <span class="snippet">
                @for (s of segments(r); track $index) {
                  @if (s.mark) {
                    <mark>{{ s.text }}</mark>
                  } @else {
                    {{ s.text }}
                  }
                }
              </span>
            }
            @if (r.unitName || r.periodLabel) {
              <span class="where">{{ where(r) }}</span>
            }
          </li>
        }
      </ul>
      <p class="status" role="status">{{ statusText() }}</p>
    </div>
  `,
  styles: `
    :host {
      position: relative;
      display: flex;
      align-items: center;
      flex: 0 1 22rem;
      min-width: 9rem;
    }
    :host([hidden]) {
      display: none;
    }
    .icon {
      position: absolute;
      left: 0.75rem;
      color: var(--p-text-muted-color, #475569);
      pointer-events: none;
    }
    input {
      width: 100%;
      min-height: var(--vt-min-touch, 40px);
      padding: 0.4rem 0.75rem 0.4rem 2.2rem;
      border: 1px solid #94a3b8;
      border-radius: 999px;
      background: var(--vt-color-surface, #fff);
      color: var(--vt-color-text, #0f172a);
      font: inherit;
    }
    input:focus-visible {
      outline: 3px solid var(--vt-color-primary-strong, #b91c1c);
      outline-offset: 1px;
    }
    .popup {
      position: absolute;
      top: calc(100% + 6px);
      left: 0;
      z-index: 50;
      width: min(30rem, calc(100vw - 2rem));
      max-height: min(28rem, 70vh);
      overflow: auto;
      background: var(--vt-color-surface, #fff);
      border: 1px solid #e2e8f0;
      border-radius: 12px;
      box-shadow: 0 12px 32px rgb(15 23 42 / 18%);
    }
    ul {
      list-style: none;
      margin: 0;
      padding: 0.25rem 0;
    }
    li {
      display: grid;
      gap: 0.15rem;
      padding: 0.6rem 1rem;
      cursor: pointer;
      min-height: var(--vt-min-touch, 44px);
    }
    li.active,
    li:hover {
      background: var(--p-content-hover-background, #f1f5f9);
    }
    li.active {
      box-shadow: inset 3px 0 0 var(--vt-color-primary-strong, #b91c1c);
    }
    .meta {
      display: flex;
      gap: 0.5rem;
      font-size: 0.78rem;
      color: var(--p-text-muted-color, #475569);
    }
    .type {
      font-weight: 600;
    }
    .title {
      font-weight: 600;
    }
    .snippet,
    .where {
      font-size: 0.86rem;
      color: var(--p-text-muted-color, #475569);
    }
    mark {
      background: #fef08a;
      color: inherit;
      padding: 0 1px;
    }
    .status {
      margin: 0;
      padding: 0.5rem 1rem;
      font-size: 0.86rem;
      color: var(--p-text-muted-color, #475569);
    }
    .status:empty {
      display: none;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
    .toggle {
      display: none;
      align-items: center;
      justify-content: center;
      border: 0;
      border-radius: 999px;
      min-width: var(--vt-min-touch, 40px);
      min-height: var(--vt-min-touch, 40px);
      background: transparent;
      color: var(--vt-color-text, #0f172a);
      font-size: 1.1rem;
      cursor: pointer;
    }
    .toggle:focus-visible {
      outline: 3px solid var(--vt-color-primary-strong, #b91c1c);
      outline-offset: 2px;
    }
    @media (max-width: 720px) {
      :host {
        flex: 0 0 auto;
        min-width: 0;
        position: static;
      }
      .toggle {
        display: inline-flex;
      }
      .icon,
      input,
      .popup {
        display: none;
      }
      :host(.mobile-open) .toggle {
        display: none;
      }
      :host(.mobile-open) input,
      :host(.mobile-open) .icon {
        display: block;
      }
      :host(.mobile-open) input {
        position: fixed;
        top: 8px;
        left: 8px;
        right: 8px;
        width: auto;
        z-index: 60;
        box-shadow: 0 4px 16px rgb(15 23 42 / 20%);
      }
      :host(.mobile-open) .icon {
        position: fixed;
        top: 22px;
        left: 20px;
        z-index: 61;
      }
      :host(.mobile-open) .popup:not([hidden]) {
        display: block;
        position: fixed;
        top: 64px;
        left: 8px;
        right: 8px;
        width: auto;
      }
    }
  `,
})
export class GlobalSearch {
  private readonly api = inject(SearchApi);
  private readonly permissions = inject(PermissionService);
  protected readonly allowed = computed(() => this.permissions.canApi('search', 'GET'));
  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly query = signal('');
  protected readonly results = signal<SearchResult[]>([]);
  protected readonly active = signal(-1);
  protected readonly open = signal(false);
  protected readonly state = signal<'idle' | 'loading' | 'done' | 'error'>('idle');
  protected readonly forbidden = signal(false);
  protected readonly mobileOpen = signal(false);
  protected readonly expanded = computed(() => this.open() && this.results().length > 0);
  protected readonly showPopup = computed(
    () => this.open() && this.query().trim().length >= MIN_CHARS && this.state() !== 'idle',
  );
  protected readonly statusText = computed(() => {
    switch (this.state()) {
      case 'loading':
        return 'Đang tìm…';
      case 'error':
        return 'Chưa tìm được, đồng chí vui lòng thử lại.';
      case 'done':
        return this.results().length === 0
          ? 'Không tìm thấy kết quả phù hợp.'
          : `${this.results().length} kết quả.`;
      default:
        return '';
    }
  });

  private timer: ReturnType<typeof setTimeout> | null = null;
  private sub: Subscription | null = null;

  protected openMobile(): void {
    this.mobileOpen.set(true);
    setTimeout(() => this.host.nativeElement.querySelector('input')?.focus());
  }

  private closeMobile(): void {
    if (!this.mobileOpen()) return;
    this.mobileOpen.set(false);
    setTimeout(() => this.host.nativeElement.querySelector<HTMLButtonElement>('.toggle')?.focus());
  }

  protected typeLabel(t: SearchResultType): string {
    return SEARCH_TYPE_LABEL[t] ?? 'Kết quả';
  }

  protected where(r: SearchResult): string {
    return [r.unitName, r.periodLabel].filter(Boolean).join(' · ');
  }

  protected segments(r: SearchResult): Segment[] {
    return highlightSegments(r.snippet ?? '', r.highlights);
  }

  protected onInput(value: string): void {
    this.query.set(value);
    this.active.set(-1);
    if (this.timer) clearTimeout(this.timer);
    this.sub?.unsubscribe();
    const q = value.trim();
    if (q.length < MIN_CHARS) {
      this.results.set([]);
      this.state.set('idle');
      return;
    }
    this.open.set(true);
    this.state.set('loading');
    this.timer = setTimeout(() => this.run(q), DEBOUNCE_MS);
  }

  private run(q: string): void {
    this.sub = this.api.search(q).subscribe({
      next: (page) => {
        this.results.set(page.items);
        this.state.set('done');
      },
      error: (err: unknown) => {
        if (err instanceof HttpErrorResponse && err.status === 403) this.forbidden.set(true);
        this.results.set([]);
        this.state.set('error');
      },
    });
  }

  protected onKey(e: KeyboardEvent): void {
    const n = this.results().length;
    if (e.key === 'ArrowDown' && n) {
      e.preventDefault();
      this.open.set(true);
      this.active.set((this.active() + 1) % n);
    } else if (e.key === 'ArrowUp' && n) {
      e.preventDefault();
      this.open.set(true);
      this.active.set(this.active() <= 0 ? n - 1 : this.active() - 1);
    } else if (e.key === 'Enter') {
      const r = this.results()[this.active() >= 0 ? this.active() : 0];
      if (r && this.showPopup()) {
        e.preventDefault();
        this.go(r);
      }
    } else if (e.key === 'Escape') {
      if (this.open()) {
        e.stopPropagation();
        this.open.set(false);
        this.active.set(-1);
      } else if (this.mobileOpen()) {
        this.closeMobile();
      } else {
        this.onInput('');
      }
    }
  }

  protected go(r: SearchResult): void {
    const link = safeInternalLink(r.deepLink);
    this.open.set(false);
    this.active.set(-1);
    this.mobileOpen.set(false);
    if (link) void this.router.navigateByUrl(link);
  }

  protected onDocumentClick(e: MouseEvent): void {
    if (this.host.nativeElement.contains(e.target as Node)) return;
    this.open.set(false);
    this.mobileOpen.set(false);
  }
}
