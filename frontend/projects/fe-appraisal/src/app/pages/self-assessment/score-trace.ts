import { Component, ElementRef, afterNextRender, input, output, viewChild } from '@angular/core';
import { AppraisalScoreTrace, AppraisalScoreTraceStep } from '@vt/api-client';

const RULE: Record<string, string> = {
  kpi_nhiem_vu: 'KPI nhiệm vụ',
  kpi_nhom: 'KPI nhóm',
  diem_ket_qua: 'Điểm kết quả',
};

/**
 * SCR-0901 "Vết tính": ngăn bên liệt kê các bước tính của SVC-07 (`scoring.score_trace`): A, B, C và
 * nguồn dữ liệu của từng nhiệm vụ, KPI nhóm, tổng điểm, kèm điều khoản và phiên bản Policy Pack.
 * Esc hoặc nút "Đóng" đóng ngăn; tiêu điểm vào ngăn khi mở.
 */
@Component({
  selector: 'vt-score-trace',
  template: `
    @let t = trace();
    <div
      #panel
      class="panel"
      role="dialog"
      aria-modal="false"
      aria-labelledby="tr-h"
      tabindex="-1"
      (keydown.escape)="closed.emit()"
    >
      <div class="top">
        <h2 id="tr-h">Vết tính</h2>
        <button type="button" class="close" (click)="closed.emit()">Đóng</button>
      </div>
      <p class="meta">
        Policy Pack {{ t.policyVersion }} · {{ t.engineVersion }} · {{ t.steps.length }} bước
      </p>
      <ol>
        @for (s of t.steps; track s.stepNo) {
          <li>
            <p class="rule">
              <b>{{ rule(s) }}</b>
              @if (s.taskId && titles()[s.taskId]) {
                · {{ titles()[s.taskId] }}
              }
            </p>
            @if (s.explanation) {
              <p>{{ s.explanation }}</p>
            }
            @if (s.inputs['nguon']) {
              <p class="src">Nguồn: {{ s.inputs['nguon'] }}</p>
            }
            @if (s.legalRef) {
              <p class="src">Căn cứ: {{ s.legalRef }}</p>
            }
          </li>
        }
      </ol>
    </div>
  `,
  styles: `
    .panel {
      position: fixed;
      top: 0;
      right: 0;
      bottom: 0;
      width: min(440px, 100vw);
      overflow: auto;
      z-index: 20;
      padding: 20px;
      background: var(--p-content-background, #fff);
      border-left: 1px solid #d1d5db;
      box-shadow: -8px 0 24px rgb(0 0 0 / 12%);
    }
    .top {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    h2 {
      font-size: 1.15rem;
      margin: 0;
    }
    .close {
      padding: 6px 14px;
      border: 1px solid #9ca3af;
      border-radius: 6px;
      background: #fff;
      cursor: pointer;
    }
    .meta,
    .src {
      color: #4b5563;
      font-size: 0.85rem;
    }
    ol {
      padding-left: 20px;
    }
    li {
      margin-bottom: 12px;
      border-bottom: 1px solid #f3f4f6;
    }
    li p {
      margin: 2px 0;
    }
  `,
})
export class ScoreTrace {
  readonly trace = input.required<AppraisalScoreTrace>();
  /** Tên nhiệm vụ theo `taskId`, lấy từ ảnh chụp điểm. */
  readonly titles = input<Record<string, string>>({});
  readonly closed = output<void>();
  private readonly panel = viewChild.required<ElementRef<HTMLElement>>('panel');

  constructor() {
    afterNextRender(() => this.panel().nativeElement.focus());
  }

  protected rule(s: AppraisalScoreTraceStep) {
    return `Bước ${s.stepNo}. ${RULE[s.ruleCode] ?? s.ruleCode}`;
  }
}
