import { Component, computed, input } from '@angular/core';
import { KIND_LABEL, readDiagram } from './wf-model';

/**
 * Sơ đồ mẫu quy trình vẽ bằng SVG từ hình vẽ BPMNDI (SCR-1505): phần tử vi phạm luật tô đỏ. Danh sách
 * bước ẩn bên dưới cho trình đọc màn hình.
 */
@Component({
  selector: 'vt-wf-diagram',
  template: `
    @if (d().shapes.length) {
      <div class="wrap" tabindex="0" role="region" aria-label="Vùng sơ đồ, cuộn ngang để xem hết">
        <svg
          [attr.viewBox]="'0 0 ' + d().width + ' ' + d().height"
          [attr.width]="d().width"
          [attr.height]="d().height"
          role="img"
          [attr.aria-label]="label()"
        >
          <defs>
            <marker
              id="vt-wf-arrow"
              viewBox="0 0 10 10"
              refX="10"
              refY="5"
              markerWidth="7"
              markerHeight="7"
              orient="auto"
            >
              <path d="M0,0 L10,5 L0,10 z" fill="#4b5563" />
            </marker>
          </defs>
          @for (e of d().edges; track e.id) {
            <polyline
              [attr.points]="e.points"
              class="edge"
              [class.bad]="bad().has(e.id)"
              marker-end="url(#vt-wf-arrow)"
            />
          }
          @for (s of d().shapes; track s.id) {
            <g
              [class.bad]="bad().has(s.id)"
              [class.active]="activeSet().has(s.id)"
              [class.done]="doneSet().has(s.id)"
              [attr.data-el]="s.id"
            >
              <title>{{ kind(s.kind) }}: {{ s.name || s.id }}</title>
              @switch (shapeOf(s.kind)) {
                @case ('circle') {
                  <circle
                    [attr.cx]="s.x + s.w / 2"
                    [attr.cy]="s.y + s.h / 2"
                    [attr.r]="s.w / 2"
                    [class.end]="s.kind === 'endEvent'"
                  />
                  <text [attr.x]="s.x + s.w / 2" [attr.y]="s.y + s.h + 16" text-anchor="middle">
                    {{ short(s.name, 14) }}
                  </text>
                }
                @case ('diamond') {
                  <polygon [attr.points]="diamond(s)" />
                  <text [attr.x]="s.x + s.w / 2" [attr.y]="s.y - 8" text-anchor="middle">
                    {{ short(s.name, 18) }}
                  </text>
                }
                @default {
                  <rect
                    [attr.x]="s.x"
                    [attr.y]="s.y"
                    [attr.width]="s.w"
                    [attr.height]="s.h"
                    rx="10"
                    [class.machine]="s.kind === 'serviceTask'"
                  />
                  <text [attr.x]="s.x + s.w / 2" [attr.y]="s.y + s.h / 2 + 4" text-anchor="middle">
                    {{ short(s.name, 16) }}
                  </text>
                }
              }
            </g>
          }
        </svg>
      </div>
      <ol class="sr-only">
        @for (s of d().shapes; track s.id) {
          <li>
            {{ kind(s.kind) }}: {{ s.name || s.id }}
            @if (bad().has(s.id)) {
              (có lỗi)
            }
            @if (activeSet().has(s.id)) {
              (bước hiện tại)
            }
            @if (doneSet().has(s.id)) {
              (đã qua)
            }
          </li>
        }
      </ol>
    } @else {
      <p class="empty">Tệp sơ đồ chưa có hình vẽ để hiển thị.</p>
    }
  `,
  styles: `
    .wrap {
      overflow-x: auto;
      background: #fff;
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      padding: 8px;
    }
    rect,
    circle,
    polygon {
      fill: #fff;
      stroke: #374151;
      stroke-width: 1.5;
    }
    rect.machine {
      fill: #eef2ff;
    }
    circle.end {
      stroke-width: 3;
    }
    text {
      font-size: 12px;
      fill: #111827;
    }
    .edge {
      fill: none;
      stroke: #4b5563;
      stroke-width: 1.5;
    }
    .done rect,
    .done circle,
    .done polygon {
      fill: #ecfdf3;
      stroke: #166534;
    }
    .active rect,
    .active circle,
    .active polygon {
      fill: #fff7e6;
      stroke: #b45309;
      stroke-width: 3;
    }
    .bad rect,
    .bad circle,
    .bad polygon,
    .edge.bad {
      stroke: #b4001f;
      stroke-width: 3;
    }
    .bad rect,
    .bad polygon {
      fill: #fdecee;
    }
    .empty {
      color: #4b5563;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
  `,
})
export class WfDiagram {
  readonly xml = input.required<string>();
  readonly issues = input<string[]>([]);
  readonly name = input('');
  /** Phần tử đang chờ và đã qua của một tiến trình (SCR-1506). */
  readonly active = input<string[]>([]);
  readonly done = input<string[]>([]);
  protected readonly activeSet = computed(() => new Set(this.active()));
  protected readonly doneSet = computed(() => new Set(this.done()));
  protected readonly d = computed(() => readDiagram(this.xml()));
  protected readonly bad = computed(() => new Set(this.issues()));
  protected readonly label = computed(() => {
    const n = this.issues().filter(Boolean).length;
    return `Sơ đồ ${this.name()}, ${this.d().shapes.length} phần tử${n ? `, ${n} phần tử có lỗi` : ''}`;
  });

  protected kind = (k: string) => KIND_LABEL[k] ?? 'Phần tử khác';
  protected shapeOf = (k: string) =>
    /Event$/.test(k) ? 'circle' : /Gateway$/.test(k) ? 'diamond' : 'box';
  protected short = (s: string, n: number) => (s.length > n ? s.slice(0, n - 1) + '…' : s);
  protected diamond = (s: { x: number; y: number; w: number; h: number }) =>
    `${s.x + s.w / 2},${s.y} ${s.x + s.w},${s.y + s.h / 2} ${s.x + s.w / 2},${s.y + s.h} ${s.x},${s.y + s.h / 2}`;
}
