import { Component, computed, input, linkedSignal, model, output, untracked } from '@angular/core';
import {
  AppraisalRatingExplanation,
  Evidence,
  RATING_LEVEL_LABEL,
  RatingLevel,
  UiFormVersion,
} from '@vt/api-client';
import { FormUi, VtFormComponent } from '@vt/form-engine';
import type { JSONSchema7 } from 'json-schema';
import { SelfForm, compareRating, formRules } from './self-assessment-rules';

/**
 * SCR-0901 vùng 4: mức hệ thống tham khảo kèm giải thích (trường 8, BR-09-10) và biểu mẫu động Mẫu 1B
 * (`<vt-form>`, lược đồ và quy tắc CEL từ `GET /ui/forms/mau-1b/current`) gồm mức tự đề xuất (trường 9),
 * giải pháp đột phá (trường 10), giải trình khi khác mức tham khảo (trường 11, BR-09-12) và ưu điểm,
 * hạn chế, hướng khắc phục (trường 12, C2). Minh chứng đã xác nhận cho mức xuất sắc chọn ở danh sách
 * riêng vì biểu mẫu động chưa có ô chọn minh chứng.
 */
@Component({
  selector: 'vt-self-rating',
  imports: [VtFormComponent],
  template: `
    <section class="box" aria-labelledby="sa-h">
      <h2 id="sa-h" class="sec">Tự đề xuất mức xếp loại</h2>
      <p class="ref">
        Mức hệ thống tham khảo: <b>{{ label[refRating()] }}</b>
      </p>
      @if (explanation()?.text) {
        <p class="why">{{ explanation()!.text }}</p>
      }
      @if (direction()) {
        <p class="warn" aria-live="polite">
          Mức tự đề xuất {{ direction() }} mức hệ thống tham khảo, đồng chí cần ghi giải trình (Điều
          16 Quy định 366).
        </p>
      }
      <vt-form
        [schema]="schema()"
        [ui]="ui()"
        [rules]="rules()"
        [context]="context()"
        [value]="initial()"
        [readonly]="!editable()"
        (valueChange)="changed($event)"
      />
      @if (current().selfRatingCode === 'HTXSNV') {
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
            <p class="hint">Chưa có minh chứng nào được xác nhận.</p>
          }
        </fieldset>
      }
      @if (editable()) {
        <p class="hint">Phiếu được tự động lưu nháp sau mỗi 30 giây.</p>
      }
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
  `,
})
export class SelfRating {
  /** Biểu mẫu Mẫu 1B đang áp dụng. */
  readonly form = input.required<UiFormVersion>();
  /** Hồ sơ đang mở; đổi hồ sơ thì dựng lại biểu mẫu. */
  readonly profileId = input.required<string>();
  readonly refRating = input.required<RatingLevel>();
  readonly explanation = input<AppraisalRatingExplanation | undefined>();
  readonly editable = input(false);
  readonly evidences = input<Evidence[]>([]);
  /** Nội dung phiếu đã lưu (giá trị ban đầu của biểu mẫu). */
  readonly value = input.required<SelfForm>();
  readonly evidenceIds = model<string[]>([]);
  /** Phát nội dung phiếu mỗi lần người dùng sửa. */
  readonly edited = output<SelfForm>();

  protected readonly label = RATING_LEVEL_LABEL;
  protected readonly schema = computed(() => this.form().schemaJson as JSONSchema7);
  protected readonly ui = computed(() => this.form().uiJson as FormUi);
  protected readonly rules = computed(() => formRules(this.form()));
  protected readonly context = computed(() => ({
    mucThamKhao: this.refRating(),
    soMinhChung: this.evidenceIds().length,
  }));

  /** Nội dung đang soạn. */
  protected readonly current = linkedSignal(() => this.value());
  /**
   * Giá trị ban đầu của biểu mẫu chỉ đổi khi đổi hồ sơ, phiên bản biểu mẫu hoặc quyền sửa (lúc đó biểu
   * mẫu dựng lại), và lấy nội dung đang soạn để không mất chữ đã gõ khi lưu nháp tự động.
   */
  protected readonly formKey = computed(
    () => `${this.profileId()}|${this.form().version}|${this.editable()}`,
  );
  protected readonly initial = computed(() => {
    this.formKey();
    return untracked(() => ({ ...this.current() }));
  });

  protected readonly direction = computed(() => {
    const c = compareRating(this.current().selfRatingCode, this.refRating());
    return c < 0 ? 'cao hơn' : c > 0 ? 'thấp hơn' : '';
  });

  protected changed(model: Record<string, unknown>) {
    const v = this.current();
    const next: SelfForm = {
      selfRatingCode: (model['selfRatingCode'] as RatingLevel | null) ?? v.selfRatingCode,
      breakthrough: (model['breakthrough'] as string | undefined) ?? '',
      reason: (model['reason'] as string | undefined) ?? '',
      content: (model['content'] as string | undefined) ?? '',
    };
    this.current.set(next);
    this.edited.emit(next);
  }

  protected pick(id: string, on: boolean) {
    this.evidenceIds.update((ids) => (on ? [...ids, id] : ids.filter((x) => x !== id)));
    this.edited.emit(this.current());
  }
}
