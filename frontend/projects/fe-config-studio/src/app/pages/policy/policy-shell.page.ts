import { Component, computed, effect, inject, input } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { POLICY_VERSION_STATUS_LABEL, policyVersionLabel } from '@vt/api-client/domains';
import { STUDIO_ACCESS } from '../../studio-permissions';
import { fmtDate } from '../../studio-ui';
import { POLICY_TABS } from './policy-tabs';
import { PolicyWorkspace } from './policy-workspace';

/**
 * Khung một phiên bản Policy Pack (QT-15): tên gói, phiên bản đang soạn và bản đang áp dụng, kết quả
 * kiểm thử gần nhất, các thẻ "Công thức" (SCR-1501), "Bảng quyết định" (SCR-1502), "Luồng duyệt"
 * (SCR-1504) và "Test case" (SCR-1503) hiện theo quyền.
 */
@Component({
  selector: 'vt-policy-shell-page',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
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
      </div>
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
  protected readonly tabs = computed(() =>
    POLICY_TABS.filter((t) => t.keys.some((k) => this.access.can(k))),
  );
  protected readonly statusLabel = POLICY_VERSION_STATUS_LABEL;
  protected readonly vlabel = policyVersionLabel;
  protected readonly date = fmtDate;

  constructor() {
    effect(() => this.ws.ref.set({ id: this.packId(), v: this.version() }));
  }
}
