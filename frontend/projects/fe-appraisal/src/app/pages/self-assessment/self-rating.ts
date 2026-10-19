import { Component, computed, input, model, output, signal } from '@angular/core';
import {
  AppraisalRatingExplanation,
  Evidence,
  RATING_LEVELS,
  RATING_LEVEL_LABEL,
  RatingLevel,
} from '@vt/api-client';
import { compareRating } from './self-assessment-rules';

/**
 * SCR-0901 vùng 4: mức hệ thống tham khảo kèm giải thích (trường 8, BR-09-10), mức tự đề xuất
 * (trường 9), giải pháp đột phá kèm minh chứng `DA_XAC_NHAN` khi HTXSNV (trường 10), giải trình khi
 * khác mức tham khảo (trường 11, BR-09-12) và ưu điểm, hạn chế, hướng khắc phục (trường 12, C2).
 */
@Component({
  selector: 'vt-self-rating',
  template: `
    <section class="box" aria-labelledby="sa-h">
      <h2 id="sa-h" class="sec">Tự đề xuất mức xếp loại</h2>
      <p class="ref">
        Mức hệ thống tham khảo: <b>{{ label[refRating()] }}</b>
      </p>
      @if (explanation()?.text) {
        <p class="why">{{ explanation()!.text }}</p>
      }
      <label for="sa-rating" class="lbl">Mức tự đề xuất <span aria-hidden="true">*</span></label>
      <select
        id="sa-rating"
        [disabled]="!editable()"
        [value]="selfRating()"
        (change)="set(selfRating, $any($event.target).value)"
        aria-required="true"
      >
        @for (r of levels; track r) {
          <option [value]="r" [selected]="r === selfRating()">{{ label[r] }} ({{ r }})</option>
        }
      </select>
      @if (direction()) {
        <p class="warn" aria-live="polite">
          Mức hệ thống tham khảo: <b>{{ label[refRating()] }}</b
          >. Mức tự đề xuất {{ direction() }} nên phải ghi giải trình (Điều 16 Quy định 366).
        </p>
      }
      @if (selfRating() === 'HTXSNV') {
        <label for="sa-breakthrough" class="lbl">
          Giải pháp đột phá <span aria-hidden="true">*</span>
        </label>
        <textarea
          id="sa-breakthrough"
          rows="4"
          maxlength="2000"
          [readOnly]="!editable()"
          [value]="breakthrough()"
          (input)="set(breakthrough, $any($event.target).value)"
          (blur)="touch('breakthrough')"
          [attr.aria-invalid]="!!shown('breakthrough')"
          [attr.aria-describedby]="shown('breakthrough') ? 'sa-breakthrough-err' : null"
        ></textarea>
        <fieldset class="ev">
          <legend>Gắn minh chứng đã xác nhận</legend>
          @for (e of evidences(); track e.id) {
            <label>
              <input
                type="checkbox"
                [disabled]="!editable()"
                [checked]="evidenceIds().includes(e.id)"
                (change)="pick(e.id, $any($event.target).checked)"
              />
              {{ e.title }}
            </label>
          } @empty {
            <p class="hint">Chưa có minh chứng nào ở trạng thái đã xác nhận.</p>
          }
        </fieldset>
        @if (shown('breakthrough'); as m) {
          <p id="sa-breakthrough-err" class="err">{{ m }}</p>
        }
      }
      @if (direction()) {
        <label for="sa-reason" class="lbl">
          Giải trình khác mức tham khảo <span aria-hidden="true">*</span>
        </label>
        <textarea
          id="sa-reason"
          rows="4"
          maxlength="2000"
          [readOnly]="!editable()"
          [value]="reason()"
          (input)="set(reason, $any($event.target).value)"
          (blur)="touch('reason')"
          [attr.aria-invalid]="!!shown('reason')"
          aria-describedby="sa-reason-hint"
        ></textarea>
        <p id="sa-reason-hint" class="hint">
          Từ 20 đến 2.000 ký tự, đã nhập {{ reason().trim().length }}.
          @if (shown('reason'); as m) {
            <span class="err">{{ m }}</span>
          }
        </p>
      }
      <label for="sa-content" class="lbl">
        Ưu điểm, hạn chế, hướng khắc phục <span aria-hidden="true">*</span>
      </label>
      <textarea
        id="sa-content"
        rows="5"
        maxlength="2000"
        placeholder="Nhập tối đa 2.000 ký tự..."
        [readOnly]="!editable()"
        [value]="content()"
        (input)="set(content, $any($event.target).value)"
        (blur)="touch('content')"
        aria-required="true"
        [attr.aria-invalid]="!!shown('content')"
        aria-describedby="sa-content-hint"
      ></textarea>
      <p id="sa-content-hint" class="hint">
        Nội dung được mã hoá khi lưu · tự động lưu nháp 30 giây một lần
        @if (shown('content'); as m) {
          <span class="err">{{ m }}</span>
        }
      </p>
    </section>
  `,
  styles: `
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
      display: grid;
      gap: 6px;
    }
    .sec {
      font-size: 1.05rem;
      margin: 0 0 4px;
    }
    .ref,
    .why {
      margin: 0;
    }
    .why,
    .hint {
      font-size: 0.85rem;
      color: #4b5563;
      margin: 0;
    }
    .lbl {
      font-weight: 600;
      margin-top: 8px;
    }
    select,
    textarea {
      font: inherit;
      padding: 8px;
      border: 1px solid #9ca3af;
      border-radius: 6px;
      width: 100%;
      box-sizing: border-box;
    }
    .warn {
      padding: 10px 12px;
      border-left: 4px solid #d97706;
      background: #fef6e4;
      color: #5c3a00;
      margin: 4px 0;
    }
    .ev {
      border: 0;
      padding: 0;
      margin: 4px 0;
      font-size: 0.9rem;
    }
    .ev label {
      display: flex;
      gap: 6px;
    }
    .err {
      color: #b91c1c;
      display: block;
      margin: 2px 0 0;
    }
  `,
})
export class SelfRating {
  readonly refRating = input.required<RatingLevel>();
  readonly explanation = input<AppraisalRatingExplanation | undefined>();
  readonly editable = input(false);
  readonly evidences = input<Evidence[]>([]);
  /** Lỗi theo quy tắc (`content`, `reason`, `breakthrough`) từ `checkSelfForm`. */
  readonly errors = input<Record<string, string>>({});
  /** Hiện mọi lỗi (sau khi người dùng bấm nộp), không chờ rời ô. */
  readonly showAll = input(false);
  readonly selfRating = model.required<RatingLevel>();
  readonly breakthrough = model('');
  readonly evidenceIds = model<string[]>([]);
  readonly reason = model('');
  readonly content = model('');
  readonly edited = output<void>();

  protected readonly levels = RATING_LEVELS;
  protected readonly label = RATING_LEVEL_LABEL;
  private readonly touched = signal<ReadonlySet<string>>(new Set());

  protected readonly direction = computed(() => {
    const c = compareRating(this.selfRating(), this.refRating());
    return c < 0 ? 'cao hơn' : c > 0 ? 'thấp hơn' : '';
  });

  protected shown(key: string): string | null {
    const m = this.errors()[key];
    return m && (this.showAll() || this.touched().has(key)) ? m : null;
  }

  protected touch(key: string) {
    this.touched.update((s) => new Set(s).add(key));
  }

  protected set<T>(m: { set(v: T): void }, v: T) {
    m.set(v);
    this.edited.emit();
  }

  protected pick(id: string, on: boolean) {
    this.evidenceIds.update((ids) => (on ? [...ids, id] : ids.filter((x) => x !== id)));
    this.edited.emit();
  }
}
