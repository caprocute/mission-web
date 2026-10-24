import { Component, computed, input, output } from '@angular/core';
import { GENERAL_CRITERIA_LEVEL_LABEL, GeneralCriteriaItem } from '@vt/api-client/domains';
import { criteriaLevel } from './self-assessment-rules';

/**
 * SCR-0901 vùng 2: 9 tiêu chí chung dạng hộp đánh dấu (trường 2), tỷ lệ đáp ứng (trường 3) và mức
 * quy đổi tức thời theo BR-09-05 (trường 4). Tiêu chí chung không chấm điểm.
 */
@Component({
  selector: 'vt-general-criteria',
  template: `
    <section class="box" aria-labelledby="gc-h">
      <h2 id="gc-h" class="sec">Tiêu chí chung <span class="chip">không chấm điểm</span></h2>
      <fieldset [disabled]="!editable()">
        <legend class="sr-only">Đánh dấu tiêu chí đồng chí đáp ứng</legend>
        @for (c of items(); track c.criteriaCode; let i = $index) {
          <label class="row">
            <input
              type="checkbox"
              [checked]="c.met"
              (change)="toggled.emit({ code: c.criteriaCode, met: $any($event.target).checked })"
            />
            <span class="lbl">{{ i + 1 }}. {{ c.label ?? c.criteriaCode }}</span>
            <span class="st" [class.no]="!c.met">{{ c.met ? 'Đáp ứng' : 'Chưa đáp ứng' }}</span>
          </label>
        }
      </fieldset>
      <dl class="sum" aria-live="polite">
        <div>
          <dt>Tỷ lệ đáp ứng</dt>
          <dd>{{ met() }}/{{ items().length }} · {{ pct() }}%</dd>
        </div>
        <div>
          <dt>Mức tiêu chí chung</dt>
          <dd>
            <span class="tag" [class]="level()">{{ levelLabel() }}</span>
          </dd>
        </div>
      </dl>
      <p class="note">
        Quy đổi: 100% Thực hiện tốt · 90–&lt;100% Đảm bảo · 60–&lt;90% Cơ bản đảm bảo · &lt;60%
        Không đảm bảo
      </p>
    </section>
  `,
  styles: `
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
    }
    .sec {
      font-size: 1.05rem;
      margin: 0 0 8px;
      display: flex;
      gap: 8px;
      align-items: center;
    }
    .chip {
      font-size: 0.75rem;
      font-weight: 500;
      padding: 2px 8px;
      border-radius: 10px;
      background: #f3f4f6;
      color: #374151;
    }
    fieldset {
      border: 0;
      margin: 0;
      padding: 0;
    }
    .row {
      display: grid;
      grid-template-columns: 24px 1fr auto;
      gap: 8px;
      align-items: center;
      padding: 8px 0;
      border-bottom: 1px solid #f3f4f6;
      cursor: pointer;
    }
    .row input {
      width: 20px;
      height: 20px;
      accent-color: #15803d;
    }
    .st {
      font-size: 0.8rem;
      color: #4b5563;
    }
    .st.no {
      color: #b91c1c;
      font-weight: 600;
    }
    .sum div {
      display: flex;
      justify-content: space-between;
      margin-top: 10px;
    }
    dd {
      margin: 0;
      font-weight: 700;
    }
    .tag {
      padding: 2px 10px;
      border-radius: 10px;
      font-weight: 600;
      background: #dcfce7;
      color: #14532d;
    }
    .tag.DAM_BAO,
    .tag.CO_BAN_DAM_BAO {
      background: #fef6e4;
      color: #7c4a03;
    }
    .tag.KHONG_DAM_BAO {
      background: #fee2e2;
      color: #991b1b;
    }
    .note {
      font-size: 0.8rem;
      color: #4b5563;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
    }
  `,
})
export class GeneralCriteria {
  readonly items = input.required<GeneralCriteriaItem[]>();
  readonly editable = input(false);
  readonly toggled = output<{ code: string; met: boolean }>();

  protected readonly met = computed(() => this.items().filter((c) => c.met).length);
  protected readonly pct = computed(() =>
    this.items().length ? Math.round((this.met() / this.items().length) * 100) : 0,
  );
  protected readonly level = computed(() => criteriaLevel(this.met(), this.items().length));
  protected readonly levelLabel = computed(() => GENERAL_CRITERIA_LEVEL_LABEL[this.level()]);
}
