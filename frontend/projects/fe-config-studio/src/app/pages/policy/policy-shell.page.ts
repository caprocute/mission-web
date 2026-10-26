import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import {
  POLICY_VERSION_STATUS_LABEL,
  PolicyPackVersion,
  isPolicyVersionEditable,
  policyVersionLabel,
} from '@vt/api-client/domains';
import { VtSkeletonComponent } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { filter, map } from 'rxjs';
import { STUDIO_ACCESS } from '../../studio-permissions';
import { fmtDate } from '../../studio-ui';
import { PolicyNewVersionDialog } from './policy-dialogs';
import { POLICY_TABS } from './policy-tabs';
import { PolicyWorkspace } from './policy-workspace';

/** Trạng thái đã khoá hẳn nội dung, chỉ đổi được bằng cách nhân bản thành bản nháp mới. */
const CLONABLE = new Set(['DA_PHAT_HANH', 'NGUNG']);

/**
 * Khung một phiên bản Policy Pack (QT-15): tên gói, phiên bản đang soạn và bản đang áp dụng, kết quả
 * kiểm thử gần nhất, các thẻ "Công thức" (SCR-1501), "Bảng quyết định" (SCR-1502), "Luồng duyệt"
 * (SCR-1504) và "Test case" (SCR-1503) hiện theo quyền. Phiên bản đã khoá nội dung thì người soạn được
 * dẫn tới bản nháp đang có của gói, hoặc tạo bản nháp mới từ phiên bản này (`createPolicyPackVersion`),
 * để không bị kẹt ở chế độ chỉ xem.
 */
@Component({
  selector: 'vt-policy-shell-page',
  imports: [
    ButtonModule,
    PolicyNewVersionDialog,
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
    VtSkeletonComponent,
  ],
  providers: [PolicyWorkspace],
  template: `
    <nav class="crumb" aria-label="Đường dẫn">
      <a routerLink="../..">Chính sách đánh giá</a>
      <span aria-hidden="true">/</span>
      <span>{{ ws.pack.value()?.name ?? '' }}</span>
    </nav>
    <header class="head">
      <h1>{{ ws.pack.value()?.name ?? 'Chính sách đánh giá' }}</h1>
      @if (ws.pack.value()?.legalBasis; as lb) {
        <p class="sub">Căn cứ: {{ lb }}</p>
      }
    </header>
    @if (ws.version.error()) {
      <p class="err" role="alert">Không tải được phiên bản này. Vui lòng thử lại.</p>
    }
    @if (ws.version.value()?.value; as v) {
      <div class="bar" data-testid="pack-bar">
        <span class="chip" [attr.data-status]="v.status">
          Phiên bản {{ vlabel(v.version) }} · {{ statusLabel[v.status] }}
        </span>
        @if (ws.published(); as p) {
          <span class="chip ok">
            Đang áp dụng: bản {{ vlabel(p.version) }}
            @if (p.effectiveFrom) {
              từ {{ date(p.effectiveFrom) }}
            }
          </span>
        }
        @if (ws.lastRun.isLoading() && !ws.running()) {
          <vt-skeleton
            class="run-sk"
            variant="text"
            [rows]="1"
            label="Đang tải kết quả kiểm thử gần nhất"
          />
        } @else {
          <span class="run" data-testid="last-run" aria-live="polite">
            @if (ws.running()) {
              Đang chạy kiểm thử…
            } @else if (ws.lastRun.value(); as r) {
              Kiểm thử gần nhất:
              <strong [class.bad]="r.result !== 'DAT'">
                {{ r.result === 'DAT' ? 'Đạt' : 'Chưa đạt' }} {{ r.passed }}/{{ r.total }} ca
              </strong>
            } @else {
              Chưa chạy kiểm thử
            }
          </span>
        }
        @if (locked(); as l) {
          @if (l.draft; as d) {
            <a class="go-draft" data-testid="open-draft" [routerLink]="sameTab(d)">
              Mở bản nháp {{ vlabel(d) }} để chỉnh sửa
            </a>
          } @else {
            <p-button
              label="Tạo bản nháp mới"
              size="small"
              data-testid="new-version"
              (onClick)="showNew.set(true)"
            />
          }
        }
      </div>
      @if (locked()) {
        <vt-policy-new-version-dialog
          [base]="v.version"
          [(visible)]="showNew"
          (created)="onCreated($event)"
        />
      }
      @if (ws.runError(); as e) {
        <p class="err" role="alert">{{ e }}</p>
      }
      <nav class="tabs" aria-label="Nội dung phiên bản">
        @for (t of tabs(); track t.path) {
          <a [routerLink]="t.path" routerLinkActive="on" ariaCurrentWhenActive="page">
            {{ t.label }}
          </a>
        }
      </nav>
      <router-outlet />
    } @else if (!ws.version.error()) {
      <!-- Chờ phiên bản về rồi mới dựng thanh trạng thái, các thẻ nội dung và trang con -->
      <vt-skeleton variant="page" label="Đang tải phiên bản chính sách" />
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .crumb {
      display: flex;
      gap: 6px;
      color: #4b5563;
      font-size: 0.875rem;
      margin-bottom: 8px;
    }
    .crumb a {
      color: #1d4ed8;
    }
    h1 {
      font-size: 1.5rem;
      margin: 0;
    }
    .sub {
      color: #4b5563;
      margin: 4px 0 12px;
    }
    .bar {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 12px;
      align-items: center;
      margin-bottom: 12px;
    }
    .chip {
      padding: 4px 10px;
      border-radius: 999px;
      background: #fef3c7;
      color: #78350f;
      font-weight: 500;
    }
    .chip.ok {
      background: #dcfce7;
      color: #14532d;
    }
    .run {
      color: #374151;
    }
    .go-draft {
      color: #1d4ed8;
      font-weight: 600;
    }
    .run-sk {
      flex: 0 1 240px;
    }
    .run .bad {
      color: #b4001f;
    }
    .tabs {
      display: flex;
      gap: 4px;
      border-bottom: 1px solid #e5e7eb;
      margin-bottom: 16px;
      overflow-x: auto;
    }
    .tabs a {
      padding: 10px 16px;
      color: #374151;
      text-decoration: none;
      border-bottom: 2px solid transparent;
      white-space: nowrap;
    }
    .tabs a.on {
      color: #b4001f;
      border-bottom-color: #b4001f;
      font-weight: 600;
    }
    .err {
      color: #b4001f;
    }
  `,
})
export class PolicyShellPage {
  /** Tham số tuyến `goi/:packId/:version`. */
  readonly packId = input.required<string>();
  readonly version = input.required<string>();

  protected readonly ws = inject(PolicyWorkspace);
  protected readonly access = inject(STUDIO_ACCESS);
  private readonly router = inject(Router);
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );
  protected readonly showNew = signal(false);

  /**
   * Phiên bản đang mở đã khoá nội dung và người dùng soạn được chính sách: dẫn tới bản nháp còn sửa được
   * của gói (`draft`), không có thì cho tạo bản nháp mới khi bản này đã phát hành hoặc ngừng áp dụng.
   */
  protected readonly locked = computed<{ draft: string | null } | null>(() => {
    const s = this.ws.status();
    if (!s || isPolicyVersionEditable(s) || !this.access.author('SCR-1501.soan')) return null;
    const cur = this.version();
    const drafts = (this.ws.pack.value()?.versions ?? [])
      .filter((v) => v.version !== cur && isPolicyVersionEditable(v.status))
      .map((v) => v.version)
      .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
    if (drafts.length) return { draft: drafts[0] };
    return CLONABLE.has(s) ? { draft: null } : null;
  });
  protected readonly tabs = computed(() =>
    POLICY_TABS.filter((t) => t.keys.some((k) => this.access.can(k))),
  );
  protected readonly statusLabel = POLICY_VERSION_STATUS_LABEL;
  protected readonly vlabel = policyVersionLabel;
  protected readonly date = fmtDate;

  constructor() {
    effect(() => this.ws.ref.set({ id: this.packId(), v: this.version() }));
  }

  /** Đường dẫn cùng thẻ đang mở nhưng ở phiên bản `v` của gói. */
  protected sameTab(v: string): string[] {
    const segs =
      this.router.parseUrl(this.url()).root.children['primary']?.segments.map((x) => x.path) ?? [];
    const i = segs.findIndex((x, k) => x === this.packId() && segs[k + 1] === this.version());
    if (i < 0) return ['/'];
    return ['/', ...segs.slice(0, i + 1), v, ...segs.slice(i + 2)];
  }

  protected onCreated(v: PolicyPackVersion) {
    this.ws.pack.reload();
    void this.router.navigate(this.sameTab(v.version));
  }
}
