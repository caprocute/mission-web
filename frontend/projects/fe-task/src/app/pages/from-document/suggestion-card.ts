import { Component, computed, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  AssigneeRef,
  SUGGESTION_DECISION_LABEL,
  SuggestionField,
  TaskSuggestion,
  TaskSuggestionFields,
} from '@vt/api-client';
import { InputTextModule } from 'primeng/inputtext';
import { MultiSelectModule } from 'primeng/multiselect';
import { SelectModule } from 'primeng/select';
import { TagModule } from 'primeng/tag';
import { TagSeverity, formatDate } from '../../task-ui';

export interface Option {
  value: string;
  label: string;
}

/** Ô đã khác gợi ý gốc của AI: mất viền tím, thành dữ liệu do người nhập (MSG-05-07). */
export function editedFields(s: Pick<TaskSuggestion, 'fields' | 'aiFields'>): SuggestionField[] {
  const keys: SuggestionField[] = [
    'title',
    'leadUnitId',
    'coUnits',
    'dueDate',
    'productName',
    'catalogItemId',
    'keyResultId',
  ];
  const norm = (k: SuggestionField, f: TaskSuggestionFields) =>
    k === 'coUnits'
      ? f.coUnits
          .map((c) => c.id)
          .sort()
          .join(',')
      : (f[k] ?? '');
  return keys.filter((k) => norm(k, s.fields) !== norm(k, s.aiFields));
}

/** Thẻ trạng thái của nhiệm vụ trích (SCR-0502 trường 12), thêm "Có thể trùng" và "Đã giao" như Hình 8.4. */
export function suggestionTag(s: TaskSuggestion): { label: string; severity: TagSeverity } {
  if (s.decision === 'CHO_XAC_NHAN')
    return s.similar.length
      ? { label: 'Có thể trùng', severity: 'warn' }
      : { label: 'Chờ xác nhận', severity: 'info' };
  if (s.decision === 'DA_XAC_NHAN' && s.taskStatus && s.taskStatus !== 'NHAP')
    return { label: 'Đã giao', severity: 'success' };
  if (s.decision === 'DA_XAC_NHAN') return { label: 'Đã xác nhận', severity: 'success' };
  return { label: SUGGESTION_DECISION_LABEL[s.decision], severity: 'secondary' };
}

/**
 * Một nhiệm vụ do AI trích (SCR-0502 vùng 2): các ô AI điền có viền tím và nhãn "AI";
 * khi đang sửa thì ô là trường nhập, ô nào khác gợi ý thì mất viền tím.
 */
@Component({
  selector: 'vt-suggestion-card',
  imports: [FormsModule, InputTextModule, MultiSelectModule, SelectModule, TagModule],
  template: `
    @let s = suggestion();
    @let f = s.fields;
    <article
      class="card"
      [class.selected]="selected()"
      [class.closed]="s.decision === 'DA_BO' || s.decision === 'DA_GOP'"
      [attr.aria-labelledby]="'sg-h-' + s.id"
    >
      <header class="head">
        <button
          type="button"
          class="pick"
          [id]="'sg-h-' + s.id"
          [attr.aria-pressed]="selected()"
          (click)="pick.emit()"
        >
          Nhiệm vụ {{ index() }}
        </button>
        <p-tag [value]="tag().label" [severity]="tag().severity" />
        @if (s.taskCode) {
          <span class="muted small"
            >{{ s.decision === 'DA_GOP' ? 'vào' : '' }} {{ s.taskCode }}</span
          >
        }
        @if (!simple()) {
          <span class="conf muted small">độ tin cậy {{ confidence() }}</span>
        }
      </header>

      @if (s.reason) {
        <p class="muted small">Lý do bỏ: {{ s.reason }}</p>
      }

      <div class="field">
        <label [for]="id('title')">
          Tên nhiệm vụ
          @if (isAi('title')) {
            <span class="badge">AI</span>
          }
        </label>
        @if (editing()) {
          <input
            pInputText
            [id]="id('title')"
            class="box"
            [class.ai]="isAi('title')"
            maxlength="500"
            [ngModel]="f.title"
            (ngModelChange)="set({ title: $event })"
            [attr.aria-invalid]="!!errors()['title']"
            [attr.aria-describedby]="errors()['title'] ? id('title-err') : null"
          />
        } @else {
          <input
            pInputText
            readonly
            class="box ro"
            [class.ai]="isAi('title')"
            [id]="id('title')"
            [value]="f.title"
          />
        }
        @if (errors()['title']; as e) {
          <small class="err" [id]="id('title-err')">{{ e }}</small>
        }
      </div>

      <div class="row3">
        <div class="field">
          <label [for]="id('lead')">
            Chủ trì
            @if (isAi('leadUnitId')) {
              <span class="badge">AI</span>
            }
          </label>
          @if (editing()) {
            <p-select
              [inputId]="id('lead')"
              [options]="units()"
              optionLabel="label"
              optionValue="value"
              [ngModel]="f.leadUnitId"
              (ngModelChange)="setLead($event)"
              styleClass="box w"
              [class.ai-wrap]="isAi('leadUnitId')"
            />
          } @else {
            <input
              pInputText
              readonly
              class="box ro"
              [class.ai]="isAi('leadUnitId')"
              [id]="id('lead')"
              [value]="f.leadUnitName"
            />
          }
          @if (errors()['leadUnitId']; as e) {
            <small class="err">{{ e }}</small>
          }
        </div>
        <div class="field">
          <label [for]="id('co')">
            Phối hợp
            @if (isAi('coUnits')) {
              <span class="badge">AI</span>
            }
          </label>
          @if (editing()) {
            <p-multiselect
              [inputId]="id('co')"
              [options]="coOptions()"
              optionLabel="label"
              optionValue="value"
              display="chip"
              [ngModel]="coIds()"
              (ngModelChange)="setCo($event)"
              styleClass="box w"
              [class.ai-wrap]="isAi('coUnits')"
              placeholder="Không có"
            />
          } @else {
            <input
              pInputText
              readonly
              class="box ro"
              [class.ai]="isAi('coUnits')"
              [id]="id('co')"
              [value]="coNames() || '—'"
            />
          }
          @if (errors()['coUnits']; as e) {
            <small class="err">{{ e }}</small>
          }
        </div>
        <div class="field">
          <label [for]="id('due')">
            Hạn
            @if (isAi('dueDate')) {
              <span class="badge">AI</span>
            }
          </label>
          @if (editing()) {
            <input
              pInputText
              type="date"
              [id]="id('due')"
              class="box"
              [class.ai]="isAi('dueDate')"
              [max]="maxDate()"
              [ngModel]="f.dueDate"
              (ngModelChange)="set({ dueDate: $event })"
            />
          } @else {
            <input
              pInputText
              readonly
              class="box ro"
              [class.ai]="isAi('dueDate')"
              [id]="id('due')"
              [value]="date(f.dueDate)"
            />
          }
          @if (errors()['dueDate']; as e) {
            <small class="err">{{ e }}</small>
          }
        </div>
      </div>

      <div class="row3">
        <div class="field">
          <label [for]="id('prod')">Sản phẩm</label>
          @if (editing()) {
            <input
              pInputText
              [id]="id('prod')"
              class="box"
              [class.ai]="isAi('productName')"
              maxlength="255"
              [ngModel]="f.productName"
              (ngModelChange)="set({ productName: $event })"
            />
          } @else {
            <input
              pInputText
              readonly
              class="box ro"
              [class.ai]="isAi('productName')"
              [id]="id('prod')"
              [value]="f.productName"
            />
          }
          @if (errors()['productName']; as e) {
            <small class="err">{{ e }}</small>
          }
        </div>
        @if (!simple()) {
          <div class="field">
            <label [for]="id('cat')">Danh mục gợi ý</label>
            @if (editing()) {
              <p-select
                [inputId]="id('cat')"
                [options]="catalog()"
                optionLabel="label"
                optionValue="value"
                [showClear]="true"
                [filter]="true"
                placeholder="Không chọn"
                [ngModel]="f.catalogItemId ?? null"
                (ngModelChange)="setCatalog($event)"
                styleClass="box w"
                [class.ai-wrap]="isAi('catalogItemId')"
              />
            } @else {
              <input
                pInputText
                readonly
                class="box ro"
                [class.ai]="isAi('catalogItemId')"
                [id]="id('cat')"
                [value]="f.catalogItemCode || '—'"
              />
            }
          </div>
          <div class="field">
            <label [for]="id('kr')">KR gợi ý</label>
            @if (editing()) {
              <p-select
                [inputId]="id('kr')"
                [options]="keyResults()"
                optionLabel="label"
                optionValue="value"
                [showClear]="true"
                placeholder="Không chọn"
                [ngModel]="f.keyResultId ?? null"
                (ngModelChange)="setKr($event)"
                styleClass="box w"
                [class.ai-wrap]="isAi('keyResultId')"
              />
            } @else {
              <input
                pInputText
                readonly
                class="box ro"
                [class.ai]="isAi('keyResultId')"
                [id]="id('kr')"
                [value]="f.keyResultCode || '—'"
              />
            }
          </div>
        }
      </div>
    </article>
  `,
  styles: `
    .card {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      padding: 12px 16px;
      background: var(--p-content-background, #fff);
      margin-bottom: 12px;
    }
    .card.selected {
      border-color: #6d28d9;
      box-shadow: 0 0 0 2px rgb(109 40 217 / 0.2);
    }
    .card.closed {
      background: #f9fafb;
    }
    .head {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 8px;
      flex-wrap: wrap;
    }
    .pick {
      font: inherit;
      font-weight: 600;
      font-size: 1.02rem;
      border: 0;
      background: none;
      padding: 2px 0;
      cursor: pointer;
      color: inherit;
    }
    .pick:focus-visible {
      outline: 2px solid #6d28d9;
      outline-offset: 2px;
    }
    .conf {
      margin-left: auto;
    }
    .field {
      display: flex;
      flex-direction: column;
      gap: 4px;
      margin-bottom: 8px;
      min-width: 0;
    }
    label {
      font-weight: 500;
      display: flex;
      align-items: center;
      gap: 6px;
    }
    .badge {
      background: #6d28d9;
      color: #fff;
      font-size: 0.7rem;
      font-weight: 600;
      padding: 0 5px;
      border-radius: 3px;
      line-height: 1.4;
    }
    .row3 {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 10px;
    }
    .box {
      width: 100%;
    }
    .ro {
      text-overflow: ellipsis;
    }
    .ai,
    :host ::ng-deep p-select.ai-wrap,
    :host ::ng-deep p-multiselect.ai-wrap {
      border: 1.5px dashed #6d28d9 !important;
      background: #f5f3ff;
    }
    :host ::ng-deep .w {
      width: 100%;
    }
    .err {
      color: #b91c1c;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.9em;
    }
    @media (max-width: 720px) {
      .row3 {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
})
export class SuggestionCard {
  readonly suggestion = input.required<TaskSuggestion>();
  readonly index = input.required<number>();
  readonly selected = input(false);
  readonly editing = input(false);
  readonly simple = input(false);
  readonly units = input<Option[]>([]);
  readonly catalog = input<Option[]>([]);
  readonly keyResults = input<Option[]>([]);
  readonly maxDate = input<string | undefined>();
  readonly errors = input<Record<string, string>>({});
  readonly pick = output<void>();
  readonly fieldsChange = output<TaskSuggestionFields>();

  protected readonly tag = computed(() => suggestionTag(this.suggestion()));
  protected readonly edited = computed(() => new Set(editedFields(this.suggestion())));
  protected readonly confidence = computed(() =>
    this.suggestion().confidence.toFixed(2).replace('.', ','),
  );
  protected readonly coIds = computed(() => this.suggestion().fields.coUnits.map((c) => c.id));
  protected readonly coNames = computed(() =>
    this.suggestion()
      .fields.coUnits.map((c) => c.name)
      .join(', '),
  );
  protected readonly coOptions = computed(() =>
    this.units().filter((u) => u.value !== this.suggestion().fields.leadUnitId),
  );

  /** Viền tím tới khi xác nhận (SCR-0502 trường 5–8) và chỉ với ô AI điền mà người dùng chưa sửa. */
  protected isAi(k: SuggestionField): boolean {
    const s = this.suggestion();
    return s.decision === 'CHO_XAC_NHAN' && !this.edited().has(k) && this.hasValue(k);
  }

  private hasValue(k: SuggestionField): boolean {
    const v = this.suggestion().aiFields[k];
    return Array.isArray(v) ? v.length > 0 : !!v;
  }

  protected id(part: string) {
    return `sg-${this.suggestion().id}-${part}`;
  }
  protected date(d: string) {
    return d ? formatDate(d, true) : '—';
  }
  protected set(patch: Partial<TaskSuggestionFields>) {
    this.fieldsChange.emit({ ...this.suggestion().fields, ...patch });
  }
  protected setLead(id: string) {
    const name = this.units().find((u) => u.value === id)?.label ?? '';
    const coUnits = this.suggestion().fields.coUnits.filter((c) => c.id !== id);
    this.set({ leadUnitId: id, leadUnitName: name, coUnits });
  }
  protected setCo(ids: string[]) {
    const coUnits: AssigneeRef[] = ids.map((id) => ({
      kind: 'UNIT',
      id,
      name: this.units().find((u) => u.value === id)?.label ?? '',
    }));
    this.set({ coUnits });
  }
  protected setCatalog(id: string | null) {
    const label = this.catalog().find((c) => c.value === id)?.label ?? '';
    this.set({
      catalogItemId: id ?? undefined,
      catalogItemCode: id ? label.split(' ')[0] : undefined,
    });
  }
  protected setKr(id: string | null) {
    const label = this.keyResults().find((c) => c.value === id)?.label ?? '';
    this.set({
      keyResultId: id ?? undefined,
      keyResultCode: id ? label.split(' · ')[0] : undefined,
    });
  }
}
