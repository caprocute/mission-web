import { Component, computed, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AssigneeRef, TASK_GROUP_LABEL, TaskGroup } from '@vt/api-client';
import { PLAN_RECURRENCE_LABEL, PlanItem, PlanRecurrence } from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { MultiSelectModule } from 'primeng/multiselect';
import { SelectModule } from 'primeng/select';
import { SelectButtonModule } from 'primeng/selectbutton';

export interface Option {
  value: string;
  label: string;
}

const GROUPS = (Object.keys(TASK_GROUP_LABEL) as TaskGroup[]).map((value) => ({
  value,
  label: TASK_GROUP_LABEL[value],
}));

/**
 * Sửa một dòng của Bảng 1 (SCR-0402 trường 5–13). Mọi thay đổi phát ra ngay để trang kiểm tra tức thì
 * và lưu nháp tự động; điểm chỉ đọc vì lấy từ danh mục (BR-04-05).
 */
@Component({
  selector: 'vt-plan-item-editor',
  imports: [
    FormsModule,
    ButtonModule,
    InputNumberModule,
    InputTextModule,
    MultiSelectModule,
    SelectModule,
    SelectButtonModule,
  ],
  host: { '(keydown.escape)': 'done.emit()' },
  template: `
    @let i = item();
    <section class="editor" [attr.aria-labelledby]="'ed-h'">
      <h3 id="ed-h">
        Sửa dòng {{ i.lineNo }}
        <span class="muted small">
          @if (i.catalogItemCode) {
            · từ danh mục {{ i.catalogItemCode }}
          } @else {
            · việc ngoài danh mục, chờ cấp duyệt chấm điểm
          }
        </span>
      </h3>
      @if (messages().length) {
        <ul class="errs" role="list">
          @for (m of messages(); track $index) {
            <li>{{ m }}</li>
          }
        </ul>
      }
      <div class="grid">
        <div class="f wide">
          <label for="pi-title">Nội dung công việc <span class="req">*</span></label>
          <input
            pInputText
            id="pi-title"
            maxlength="500"
            [ngModel]="i.title"
            (ngModelChange)="set({ title: $event })"
          />
        </div>
        <div class="f">
          <span class="lbl" id="pi-group-l">Nhóm</span>
          <p-selectbutton
            [options]="groups"
            optionLabel="label"
            optionValue="value"
            [allowEmpty]="false"
            ariaLabelledBy="pi-group-l"
            [ngModel]="i.taskGroup"
            (ngModelChange)="set({ taskGroup: $event })"
          />
        </div>
        @if (!simple()) {
          <div class="f">
            <label for="pi-kr">KR liên kết</label>
            <p-select
              inputId="pi-kr"
              [options]="keyResults()"
              optionLabel="label"
              optionValue="value"
              [showClear]="true"
              placeholder="Không liên kết"
              [ngModel]="i.keyResultId ?? null"
              (ngModelChange)="set({ keyResultId: $event ?? undefined })"
              styleClass="w"
            />
          </div>
        }
        <div class="f">
          <label for="pi-lead">Chủ trì <span class="req">*</span></label>
          <p-select
            inputId="pi-lead"
            [options]="units()"
            optionLabel="label"
            optionValue="value"
            placeholder="Chọn đơn vị"
            [ngModel]="i.leadAssignee.id || null"
            (ngModelChange)="setLead($event)"
            styleClass="w"
          />
        </div>
        @if (!simple()) {
          <div class="f">
            <label for="pi-co">Phối hợp</label>
            <p-multiselect
              inputId="pi-co"
              [options]="coOptions()"
              optionLabel="label"
              optionValue="value"
              display="chip"
              placeholder="Không có"
              [ngModel]="coIds()"
              (ngModelChange)="setCo($event)"
              styleClass="w"
            />
          </div>
        }
        <div class="f">
          <label for="pi-prod">Sản phẩm <span class="req">*</span></label>
          <input
            pInputText
            id="pi-prod"
            maxlength="255"
            [ngModel]="i.productName"
            (ngModelChange)="set({ productName: $event })"
          />
        </div>
        <div class="f narrow">
          <label for="pi-qty">Số lượng <span class="req">*</span></label>
          <p-inputnumber
            inputId="pi-qty"
            [min]="0"
            [maxFractionDigits]="2"
            [ngModel]="i.quantity"
            (ngModelChange)="set({ quantity: $event ?? 0 })"
            styleClass="w"
          />
        </div>
        <div class="f narrow">
          <label for="pi-unit">Đơn vị tính <span class="req">*</span></label>
          <input
            pInputText
            id="pi-unit"
            maxlength="32"
            [ngModel]="i.unit ?? ''"
            (ngModelChange)="set({ unit: $event })"
          />
        </div>
        <div class="f narrow">
          <label for="pi-rec">Lặp lại</label>
          <p-select
            inputId="pi-rec"
            [options]="recurrences"
            optionLabel="label"
            optionValue="value"
            [ngModel]="i.recurrence ?? ''"
            (ngModelChange)="setRecurrence($event)"
            styleClass="w"
          />
        </div>
        @if (!i.recurrence) {
          <div class="f narrow">
            <label for="pi-due">Hạn <span class="req">*</span></label>
            <input
              pInputText
              type="date"
              id="pi-due"
              [min]="periodStart()"
              [max]="periodEnd()"
              [ngModel]="i.dueDate"
              (ngModelChange)="set({ dueDate: $event || periodEnd() })"
            />
          </div>
        }
        @if (adjusting()) {
          <div class="f wide">
            <label for="pi-reason">Lý do thay đổi (bắt buộc với dòng thêm, sửa)</label>
            <input
              pInputText
              id="pi-reason"
              maxlength="500"
              [ngModel]="i.changeReason ?? ''"
              (ngModelChange)="set({ changeReason: $event || undefined })"
            />
          </div>
        }
        <div class="f narrow">
          <span class="lbl">Điểm</span>
          <span class="score">{{ i.standardScore ?? 'Chờ chấm' }}</span>
        </div>
      </div>
      <div class="acts">
        <p-button
          label="Bỏ dòng này"
          severity="contrast"
          [outlined]="true"
          icon="pi pi-trash"
          (onClick)="remove.emit()"
        />
        <p-button label="Xong" (onClick)="done.emit()" />
      </div>
    </section>
  `,
  styles: `
    .editor {
      border: 1px solid #6d28d9;
      border-radius: 8px;
      padding: 12px 16px;
      margin-top: 12px;
      background: #faf5ff;
    }
    h3 {
      margin: 0 0 10px;
      font-size: 1.02rem;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 10px 14px;
    }
    .f {
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 0;
    }
    .wide {
      grid-column: 1 / -1;
    }
    label,
    .lbl {
      font-weight: 500;
    }
    .req,
    .errs {
      color: #b91c1c;
    }
    .errs {
      margin: 0 0 10px;
      padding-left: 20px;
    }
    .score {
      padding: 8px 0;
      font-weight: 600;
    }
    :host ::ng-deep .w {
      width: 100%;
    }
    .acts {
      display: flex;
      justify-content: flex-end;
      gap: 8px;
      margin-top: 12px;
    }
    .muted {
      color: #4b5563;
      font-weight: 400;
    }
    .small {
      font-size: 0.9em;
    }
    @media (max-width: 900px) {
      .grid {
        grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      }
    }
  `,
})
export class PlanItemEditor {
  readonly item = input.required<PlanItem>();
  readonly units = input<Option[]>([]);
  readonly keyResults = input<Option[]>([]);
  readonly periodStart = input<string>('');
  readonly periodEnd = input<string>('');
  readonly simple = input(false);
  /** Phiên bản điều chỉnh: mỗi dòng thêm, sửa ghi lý do (BR-04-10). */
  readonly adjusting = input(false);
  /** Lỗi, cảnh báo của dòng từ "Kiểm tra kế hoạch". */
  readonly messages = input<string[]>([]);
  readonly changed = output<PlanItem>();
  readonly remove = output<void>();
  readonly done = output<void>();

  protected readonly groups = GROUPS;
  protected readonly recurrences = [
    { value: '', label: 'Không lặp, theo hạn' },
    ...(Object.keys(PLAN_RECURRENCE_LABEL) as PlanRecurrence[]).map((value) => ({
      value,
      label: PLAN_RECURRENCE_LABEL[value],
    })),
  ];
  protected readonly coOptions = computed(() =>
    this.units().filter((u) => u.value !== this.item().leadAssignee.id),
  );
  protected readonly coIds = computed(() => (this.item().coAssignees ?? []).map((c) => c.id));

  protected set(patch: Partial<PlanItem>) {
    this.changed.emit({ ...this.item(), ...patch });
  }
  private unitRef(id: string): AssigneeRef {
    return { kind: 'UNIT', id, name: this.units().find((u) => u.value === id)?.label ?? '' };
  }
  protected setLead(id: string) {
    this.set({
      leadAssignee: this.unitRef(id),
      coAssignees: (this.item().coAssignees ?? []).filter((c) => c.id !== id),
    });
  }
  protected setCo(ids: string[]) {
    this.set({ coAssignees: ids.map((id) => this.unitRef(id)) });
  }
  /** Dòng "Hằng tháng" vẫn mang hạn cuối kỳ vì hợp đồng bắt buộc `dueDate`. */
  protected setRecurrence(v: string) {
    this.set(
      v ? { recurrence: v as PlanRecurrence, dueDate: this.periodEnd() } : { recurrence: null },
    );
  }
}
