import { Component, computed, input, output, signal } from '@angular/core';
import { CONFIDENCE_LABEL, KeyResult, OBJECTIVE_STATUS_LABEL, Objective } from '@vt/api-client';
import { TagModule } from 'primeng/tag';
import {
  confidenceSeverity,
  descendantCount,
  formatDate,
  formatNumber,
  krTone,
  objectiveTone,
  statusSeverity,
  valueOverTarget,
} from '../../okr-ui';

/**
 * Một nút của cây mục tiêu SCR-0201 (Hình 7.3 vùng 2, 3): mục tiêu với tiến độ, trạng thái và các KR
 * (trọng số, người xác nhận, nguồn số liệu, giá trị trên chỉ tiêu, mức tự tin, lần check-in gần nhất).
 * Mục tiêu cấp dưới (tầng 2 của phòng, tầng 3 của cá nhân) thu gọn mặc định và chỉ dựng khi mở,
 * để cây hàng trăm nút không dựng hết DOM một lúc. Thành phần tự gọi đệ quy cho cấp dưới.
 */
@Component({
  selector: 'vt-objective-node',
  imports: [TagModule],
  template: `
    @let o = objective();
    <article class="obj" [class.sub]="o.tier > 1" [attr.aria-labelledby]="'o-' + o.id">
      <header class="hd">
        <span class="oid" [class.sub]="o.tier > 1">{{ o.code }}</span>
        <h2 class="title" [id]="'o-' + o.id">
          {{ o.title }}
          @if (o.tier > 1) {
            <span class="owner">· {{ o.ownerPersonName ?? o.ownerUnitName }}</span>
          }
        </h2>
        <p-tag [severity]="statusSev()" [value]="statusText()" />
        <div
          class="bar wide"
          role="progressbar"
          [attr.aria-label]="'Tiến độ ' + o.code"
          aria-valuemin="0"
          aria-valuemax="100"
          [attr.aria-valuenow]="o.progressPct"
        >
          <i [class]="tone()" [style.width.%]="o.progressPct"></i>
        </div>
        <strong class="pct">{{ o.progressPct }}%</strong>
        <span class="muted">{{ o.keyResults.length }} KR</span>
      </header>
      @if (o.keyResults.length === 0) {
        <p class="muted empty">Mục tiêu chưa có KR.</p>
      }
      @for (kr of o.keyResults; track kr.id) {
        <button
          type="button"
          class="kr"
          [class.on]="kr.id === selectedKrId()"
          [class.risk]="kr.riskFlag === 'RUI_RO'"
          [attr.aria-pressed]="kr.id === selectedKrId()"
          (click)="krSelected.emit(kr)"
        >
          <span class="code">{{ kr.code }}</span>
          <span class="txt">
            <span class="kt">{{ kr.title }}</span>
            <span class="meta">{{ meta(kr) }}</span>
          </span>
          <span class="val">
            <span class="bar" aria-hidden="true">
              <i [class]="krTone(kr)" [style.width.%]="kr.progress * 100"></i>
            </span>
            <span>{{ valueOverTarget(kr) }}</span>
          </span>
          <span class="conf">
            @if (kr.confidence; as c) {
              <p-tag [severity]="confSev(c)" [value]="confLabel[c]" />
            } @else {
              <span class="muted">Chưa check-in</span>
            }
          </span>
          <span class="muted date">{{ kr.lastCheckinAt ? formatDate(kr.lastCheckinAt) : '' }}</span>
        </button>
      }
      @if (childList().length > 0) {
        <button
          type="button"
          class="toggle"
          [attr.aria-expanded]="open()"
          [attr.aria-controls]="'c-' + o.id"
          (click)="open.set(!open())"
        >
          <i
            class="pi"
            [class.pi-chevron-down]="open()"
            [class.pi-chevron-right]="!open()"
            aria-hidden="true"
          ></i>
          {{ open() ? 'Thu gọn' : 'Xem' }} {{ descendants() }} mục tiêu cấp dưới ({{
            childTierText()
          }})
        </button>
      }
    </article>
    @if (open()) {
      <div
        class="children"
        [id]="'c-' + o.id"
        role="group"
        [attr.aria-label]="'Mục tiêu cấp dưới của ' + o.code"
      >
        @for (c of childList(); track c.id) {
          <vt-objective-node
            [objective]="c"
            [children]="children()"
            [selectedKrId]="selectedKrId()"
            (krSelected)="krSelected.emit($event)"
          />
        }
      </div>
    }
  `,
  styles: `
    /* Đỏ thương hiệu đậm hơn token chính (#ee0033 chỉ đạt 4,48:1 với nền trắng, xem WEB-059) để chữ và nền màu đạt WCAG AA */
    :host {
      --okr-accent: #c4002a;
    }
    :host {
      display: block;
      content-visibility: auto;
      contain-intrinsic-size: auto 220px;
    }
    .obj {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      padding: 12px;
      background: var(--p-content-background, #fff);
      margin-bottom: 10px;
    }
    .obj.sub {
      background: #fafafa;
    }
    .hd {
      display: flex;
      align-items: center;
      gap: 10px;
      flex-wrap: wrap;
      padding-bottom: 8px;
      border-bottom: 1px dashed var(--p-content-border-color, #e5e7eb);
    }
    .oid {
      background: var(--okr-accent);
      color: #fff;
      border-radius: 6px;
      padding: 4px 8px;
      font-weight: 800;
    }
    .oid.sub {
      background: #4b5563;
    }
    .title {
      font-size: 1.05rem;
      margin: 0;
      flex: 1 1 260px;
    }
    .owner {
      font-weight: 400;
      color: #4b5563;
      font-size: 0.9em;
    }
    .pct {
      min-width: 3ch;
    }
    .muted {
      color: #4b5563;
      font-size: 0.875rem;
    }
    .empty {
      margin: 8px 0 0;
    }
    .bar {
      display: block;
      height: 8px;
      background: #e5e7eb;
      border-radius: 4px;
      overflow: hidden;
      min-width: 80px;
    }
    .bar.wide {
      width: 160px;
    }
    .bar i {
      display: block;
      height: 100%;
      background: #9ca3af;
    }
    .bar i.ok {
      background: #15803d;
    }
    .bar i.warn {
      background: #d97706;
    }
    .bar i.danger {
      background: #c62828;
    }
    .kr {
      display: grid;
      grid-template-columns: 72px minmax(0, 1fr) 150px 130px 48px;
      gap: 10px;
      align-items: center;
      width: 100%;
      padding: 8px 4px 8px 8px;
      border: 0;
      border-bottom: 1px dashed var(--p-content-border-color, #e5e7eb);
      background: none;
      font: inherit;
      color: inherit;
      text-align: left;
      cursor: pointer;
    }
    .kr:hover {
      background: #f9fafb;
    }
    .kr.on {
      background: #fdf2f4;
      box-shadow: inset 3px 0 0 var(--okr-accent);
    }
    .kr:focus-visible,
    .toggle:focus-visible {
      outline: 2px solid var(--okr-accent);
      outline-offset: 2px;
    }
    .code {
      font-weight: 700;
    }
    .txt {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .meta {
      color: #4b5563;
      font-size: 0.85rem;
    }
    .val {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .toggle {
      margin-top: 8px;
      border: 0;
      background: none;
      color: var(--okr-accent);
      font: inherit;
      font-weight: 600;
      cursor: pointer;
      padding: 4px 0;
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    .children {
      margin-left: 24px;
      padding-left: 12px;
      border-left: 2px solid #e5e7eb;
    }
    @media (max-width: 900px) {
      .kr {
        grid-template-columns: 64px minmax(0, 1fr);
      }
      .val,
      .conf,
      .date {
        grid-column: 2;
      }
    }
  `,
})
export class ObjectiveNode {
  readonly objective = input.required<Objective>();
  readonly children = input.required<ReadonlyMap<string, Objective[]>>();
  readonly selectedKrId = input<string | null>(null);
  readonly krSelected = output<KeyResult>();

  protected readonly open = signal(false);
  protected readonly confLabel = CONFIDENCE_LABEL;
  protected readonly valueOverTarget = valueOverTarget;
  protected readonly formatDate = formatDate;
  protected readonly krTone = krTone;
  protected readonly confSev = confidenceSeverity;

  protected readonly childList = computed(() => this.children().get(this.objective().id) ?? []);
  protected readonly descendants = computed(() =>
    descendantCount(this.objective().id, this.children()),
  );
  protected readonly tone = computed(() => objectiveTone(this.objective()));
  protected readonly statusSev = computed(() => statusSeverity(this.objective().status));
  protected readonly statusText = computed(() => {
    const o = this.objective();
    const label = OBJECTIVE_STATUS_LABEL[o.status];
    return o.status === 'DA_DUYET' && o.approvedAt ? `${label} ${formatDate(o.approvedAt)}` : label;
  });
  protected readonly childTierText = computed(() => {
    const tiers = new Set<number>();
    const stack = [...this.childList()];
    while (stack.length) {
      const o = stack.pop()!;
      tiers.add(o.tier);
      stack.push(...(this.children().get(o.id) ?? []));
    }
    return [...tiers]
      .sort()
      .map((t) => `tầng ${t}`)
      .join(', ');
  });

  protected meta(kr: KeyResult): string {
    const parts = [`Trọng số ${formatNumber(Math.round(kr.weight * 100))}%`];
    parts.push(`người xác nhận: ${kr.verifierName}`);
    if (kr.dataSource) parts.push(`nguồn: ${kr.dataSource}`);
    if (kr.valueNote) parts.push(kr.valueNote);
    return parts.join(' · ');
  }
}
