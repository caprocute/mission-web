import { Component, input, output, signal } from '@angular/core';
import { AiKrSuggestion } from '@vt/api-client';

/**
 * Vùng 4 của SCR-0202: gợi ý của trợ lý AI (SVC-14, AC-12) viền tím, ghi rõ "Chỉ đọc · không ghi".
 * Người soạn chọn một cách viết lại rồi "Dùng gợi ý vào ô"; màn hình điền ô KR với viền tím và chỉ
 * ghi khi người soạn lưu, kèm nguồn `AI_ACCEPTED` (BR-02-11).
 */
@Component({
  selector: 'vt-ai-review-panel',
  template: `
    <section class="box" aria-labelledby="ai-h">
      <h2 id="ai-h" class="sec">
        <span class="num" aria-hidden="true">4</span> Trợ lý AI
        <span class="pill">Chỉ đọc · không ghi</span>
      </h2>
      @if (error(); as e) {
        <p class="err" role="alert">{{ e }}</p>
      }
      @if (!reviewed()) {
        <p class="muted">
          Bấm "Trợ lý AI soát KR" để nhận gợi ý viết lại KR có nguồn trích dẫn. Trợ lý chỉ đề xuất;
          nội dung chỉ được ghi khi đồng chí lưu.
        </p>
      } @else if (!suggestions().length) {
        <p class="muted" role="status">Trợ lý không còn gợi ý nào cho các KR hiện tại.</p>
      }
      @for (s of suggestions(); track s.id) {
        <div class="ai">
          <p>
            <strong>{{ s.krCode }} {{ s.issue }}.</strong> Gợi ý viết lại:
          </p>
          <div role="radiogroup" [attr.aria-label]="'Chọn gợi ý cho ' + s.krCode">
            @for (w of s.rewrites; track w; let j = $index) {
              <label class="opt">
                <input
                  type="radio"
                  [name]="'ai-' + s.id"
                  [checked]="choice(s) === j"
                  (change)="setChoice(s, j)"
                />
                "{{ w }}"
              </label>
            }
          </div>
          <p class="src">Nguồn: {{ s.sources.join('; ') }}</p>
        </div>
        <div class="acts">
          <button type="button" class="btn" (click)="dismissed.emit(s)">Bỏ qua</button>
          <button
            type="button"
            class="btn use"
            (click)="applied.emit({ suggestion: s, choice: choice(s) })"
          >
            Dùng gợi ý vào ô {{ s.krCode }}
          </button>
        </div>
      }
      @if (reviewed()) {
        <p class="note">◆ Nội dung do AI tạo · cần người soạn xác nhận</p>
      }
    </section>
  `,
  styles: `
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
      margin: 0 0 12px;
      font-size: 0.875rem;
    }
    .sec {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 1.05rem;
      margin: 0 0 12px;
    }
    .num {
      display: inline-grid;
      place-items: center;
      width: 22px;
      height: 22px;
      border-radius: 50%;
      background: #111827;
      color: #fff;
      font-size: 0.75rem;
    }
    .pill {
      font-size: 0.75rem;
      font-weight: 600;
      color: #6d28d9;
      background: #f5f3ff;
      border-radius: 10px;
      padding: 2px 8px;
    }
    .muted {
      color: #4b5563;
    }
    .err {
      color: #b91c1c;
    }
    .ai {
      border: 1.5px dashed #6d28d9;
      background: #f5f3ff;
      border-radius: 6px;
      padding: 8px 12px;
    }
    .ai p {
      margin: 0 0 6px;
    }
    .opt {
      display: flex;
      gap: 6px;
      align-items: flex-start;
      margin-bottom: 4px;
    }
    .src {
      color: #4b5563;
      font-size: 0.8125rem;
    }
    .acts {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin: 8px 0 12px;
    }
    .btn {
      font: inherit;
      font-weight: 600;
      padding: 6px 12px;
      border-radius: 6px;
      border: 1px solid #9ca3af;
      background: #fff;
      cursor: pointer;
    }
    .btn.use {
      border-color: #c4002a;
      color: #c4002a;
    }
    .btn:focus-visible {
      outline: 2px solid #c4002a;
      outline-offset: 2px;
    }
    .note {
      color: #6d28d9;
      font-weight: 600;
      margin: 4px 0 0;
    }
  `,
})
export class AiReviewPanel {
  readonly suggestions = input.required<AiKrSuggestion[]>();
  /** Đã soát ít nhất một lần (để phân biệt "chưa soát" với "không còn gợi ý"). */
  readonly reviewed = input(false);
  readonly error = input<string | null>(null);
  readonly dismissed = output<AiKrSuggestion>();
  readonly applied = output<{ suggestion: AiKrSuggestion; choice: number }>();

  private readonly choices = signal<Readonly<Record<string, number>>>({});

  protected choice(s: AiKrSuggestion) {
    return this.choices()[s.id] ?? 0;
  }

  protected setChoice(s: AiKrSuggestion, j: number) {
    this.choices.update((c) => ({ ...c, [s.id]: j }));
  }
}
