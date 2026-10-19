import {
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  model,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  CATALOG_ITEM_STATUS_LABEL,
  CatalogApi,
  CatalogItem,
  CatalogItemInput,
  CatalogItemSaveResult,
  CatalogItemStatus,
  Position,
  TASK_GROUP_LABEL,
  TaskGroup,
  WorkCatalog,
  toProblem,
} from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';

const opts = <K extends string>(m: Record<K, string>) =>
  (Object.keys(m) as K[]).map((value) => ({ value, label: m[value] }));

const EMPTY: CatalogItemInput = {
  code: '',
  positionId: undefined,
  name: '',
  productName: '',
  unit: '',
  difficulty: 3,
  baseScore: 20,
  qualityCriteria: '',
  evidenceRequired: '',
  defaultGroup: 'THUONG_XUYEN',
  standardDays: undefined,
  status: 'AP_DUNG',
};

/**
 * SCR-0401 "Thêm công việc" và "Sửa" (trường 3–14): POST `/catalog/{catalogId}/items` hoặc PUT `/items/{id}` kèm If-Match.
 * Mức khó 1–5, điểm chuẩn 0–100 (BR-04-14); điểm gợi ý theo mức khó lấy từ tham số Policy Pack của danh mục
 * và chỉ tự điền khi người sửa chưa đổi điểm. Mục đã dùng ở kỳ khoá hiện MSG-04-01 trước khi lưu (BR-04-13).
 */
@Component({
  selector: 'vt-catalog-item-dialog',
  imports: [
    FormsModule,
    ButtonModule,
    DialogModule,
    InputTextModule,
    MessageModule,
    SelectModule,
    TextareaModule,
  ],
  template: `
    @let d = draft();
    <p-dialog
      [header]="item() ? 'Sửa công việc ' + item()!.code : 'Thêm công việc'"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(760px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      @if (item()?.usedInLockedPeriod) {
        <p class="note" role="note">
          Công việc {{ item()!.code }} đã được dùng ở kỳ đã khoá. Thay đổi sẽ được lưu ở phiên bản
          danh mục mới, kỳ cũ giữ nguyên.
        </p>
      } @else if (!item() && catalog().status === 'AP_DUNG') {
        <p class="note" role="note">
          Phiên bản {{ catalog().versionNo }} đang áp dụng: công việc mới được thêm vào phiên bản
          nháp kế tiếp.
        </p>
      }
      <div class="grid">
        <div>
          <label for="ci-pos">Vị trí việc làm <span class="req">*</span></label>
          <p-select
            inputId="ci-pos"
            [options]="positionOptions()"
            optionLabel="label"
            optionValue="value"
            placeholder="Chọn vị trí"
            [ngModel]="d.positionId"
            (ngModelChange)="set('positionId', $event)"
            styleClass="full"
            appendTo="body"
          />
          @if (err('positionId'); as e) {
            <small class="err">{{ e }}</small>
          }
        </div>
        <div>
          <label for="ci-code">Mã công việc</label>
          <input
            id="ci-code"
            pInputText
            class="full"
            maxlength="20"
            placeholder="Để trống để tự sinh"
            [ngModel]="d.code"
            (ngModelChange)="set('code', $event)"
          />
          @if (err('code'); as e) {
            <small class="err">{{ e }}</small>
          }
        </div>
      </div>
      <label for="ci-name">Tên công việc <span class="req">*</span></label>
      <input
        id="ci-name"
        pInputText
        class="full"
        maxlength="255"
        [ngModel]="d.name"
        (ngModelChange)="set('name', $event)"
      />
      @if (err('name'); as e) {
        <small class="err">{{ e }}</small>
      } @else {
        <small class="muted"
          >Ghi rõ việc và kết quả, tránh từ mơ hồ như "kịp thời", "đẩy mạnh".</small
        >
      }
      <div class="grid3">
        <div>
          <label for="ci-prod">Sản phẩm <span class="req">*</span></label>
          <input
            id="ci-prod"
            pInputText
            class="full"
            maxlength="255"
            [ngModel]="d.productName"
            (ngModelChange)="set('productName', $event)"
          />
          @if (err('productName'); as e) {
            <small class="err">{{ e }}</small>
          }
        </div>
        <div>
          <label for="ci-unit">Đơn vị tính <span class="req">*</span></label>
          <input
            id="ci-unit"
            pInputText
            class="full"
            maxlength="30"
            [ngModel]="d.unit"
            (ngModelChange)="set('unit', $event)"
          />
          @if (err('unit'); as e) {
            <small class="err">{{ e }}</small>
          }
        </div>
        <div>
          <label for="ci-group">Nhóm mặc định <span class="req">*</span></label>
          <p-select
            inputId="ci-group"
            [options]="groupOptions"
            optionLabel="label"
            optionValue="value"
            [ngModel]="d.defaultGroup"
            (ngModelChange)="set('defaultGroup', $event)"
            styleClass="full"
            appendTo="body"
          />
        </div>
        <div>
          <label for="ci-diff">Mức khó (1–5) <span class="req">*</span></label>
          <p-select
            inputId="ci-diff"
            [options]="difficultyOptions"
            optionLabel="label"
            optionValue="value"
            [ngModel]="d.difficulty"
            (ngModelChange)="setDifficulty($event)"
            styleClass="full"
            appendTo="body"
          />
          <small class="muted">Hệ số {{ factor(d.difficulty) }}</small>
        </div>
        <div>
          <label for="ci-score">Điểm chuẩn (0–100) <span class="req">*</span></label>
          <input
            id="ci-score"
            pInputText
            type="number"
            min="0"
            max="100"
            class="full"
            [ngModel]="d.baseScore"
            (ngModelChange)="setScore($event)"
          />
          @if (err('baseScore'); as e) {
            <small class="err">{{ e }}</small>
          } @else {
            <small class="muted">Gợi ý {{ suggestion(d.difficulty) }} điểm theo mức khó</small>
          }
        </div>
        <div>
          <label for="ci-days">Thời hạn chuẩn (ngày làm việc)</label>
          <input
            id="ci-days"
            pInputText
            type="number"
            min="1"
            class="full"
            [ngModel]="d.standardDays"
            (ngModelChange)="set('standardDays', toNum($event) ?? undefined)"
          />
          @if (err('standardDays'); as e) {
            <small class="err">{{ e }}</small>
          }
        </div>
      </div>
      <label for="ci-qc">Tiêu chuẩn chất lượng <span class="req">*</span></label>
      <textarea
        id="ci-qc"
        pTextarea
        rows="2"
        maxlength="1000"
        class="full"
        [ngModel]="d.qualityCriteria"
        (ngModelChange)="set('qualityCriteria', $event)"
      ></textarea>
      <small class="muted">Dùng làm danh sách đối chiếu khi nghiệm thu chất lượng.</small>
      @if (err('qualityCriteria'); as e) {
        <small class="err block">{{ e }}</small>
      }
      <label for="ci-ev">Minh chứng yêu cầu <span class="req">*</span></label>
      <input
        id="ci-ev"
        pInputText
        class="full"
        maxlength="500"
        [ngModel]="d.evidenceRequired"
        (ngModelChange)="set('evidenceRequired', $event)"
      />
      @if (err('evidenceRequired'); as e) {
        <small class="err">{{ e }}</small>
      }
      @if (item()) {
        <label for="ci-status">Trạng thái <span class="req">*</span></label>
        <p-select
          inputId="ci-status"
          [options]="statusOptions"
          optionLabel="label"
          optionValue="value"
          [ngModel]="d.status"
          (ngModelChange)="set('status', $event)"
          appendTo="body"
        />
      }
      @if (error(); as e) {
        <p-message severity="error" [text]="e" styleClass="msg" />
      }
      <ng-template #footer>
        <p-button
          label="Huỷ"
          severity="contrast"
          [outlined]="true"
          (onClick)="visible.set(false)"
        />
        <p-button
          [label]="item()?.usedInLockedPeriod ? 'Lưu vào phiên bản mới' : 'Lưu'"
          [loading]="busy()"
          [disabled]="!valid()"
          (onClick)="save()"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    label {
      display: block;
      margin: 12px 0 6px;
      font-weight: 500;
    }
    .req {
      color: #b91c1c;
    }
    .full,
    :host ::ng-deep .full {
      width: 100%;
    }
    .grid {
      display: grid;
      grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
      gap: 0 12px;
    }
    .grid3 {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0 12px;
    }
    @media (max-width: 640px) {
      .grid,
      .grid3 {
        grid-template-columns: 1fr;
      }
    }
    .muted {
      color: #4b5563;
    }
    .err {
      color: #b91c1c;
    }
    .block {
      display: block;
    }
    .note {
      margin: 0 0 8px;
      padding: 10px 14px;
      border-radius: 6px;
      background: #eff6ff;
      border: 1px solid #93c5fd;
      color: #1e3a8a;
    }
    :host ::ng-deep .msg {
      display: block;
      margin-top: 12px;
    }
  `,
})
export class CatalogItemDialog {
  readonly catalog = input.required<WorkCatalog>();
  readonly positions = input<Position[]>([]);
  /** Mục đang sửa; trống khi thêm mới. */
  readonly item = input<CatalogItem | null>(null);
  readonly visible = model(false);
  readonly saved = output<CatalogItemSaveResult>();

  private readonly api = inject(CatalogApi);
  protected readonly groupOptions = opts<TaskGroup>(TASK_GROUP_LABEL);
  protected readonly statusOptions = opts<CatalogItemStatus>(CATALOG_ITEM_STATUS_LABEL);
  protected readonly difficultyOptions = [1, 2, 3, 4, 5].map((v) => ({
    value: v,
    label: `Mức ${v}`,
  }));
  protected readonly positionOptions = computed(() =>
    this.positions().map((p) => ({ value: p.id, label: p.name })),
  );

  protected readonly draft = linkedSignal<boolean, CatalogItemInput>({
    source: this.visible,
    computation: () => {
      const i = this.item();
      if (!i) return { ...EMPTY, baseScore: this.suggestion(EMPTY.difficulty) };
      const {
        code,
        positionId,
        name,
        productName,
        unit,
        difficulty,
        baseScore,
        qualityCriteria,
        evidenceRequired,
        defaultGroup,
        standardDays,
        status,
      } = i;
      return {
        code,
        positionId,
        name,
        productName,
        unit,
        difficulty,
        baseScore,
        qualityCriteria,
        evidenceRequired,
        defaultGroup,
        standardDays,
        status,
      };
    },
  });
  private readonly fieldErrors = linkedSignal<boolean, { field: string; message: string }[]>({
    source: this.visible,
    computation: () => [],
  });
  protected readonly error = linkedSignal<boolean, string | null>({
    source: this.visible,
    computation: () => null,
  });
  protected readonly busy = signal(false);

  protected readonly valid = computed(() => {
    const d = this.draft();
    const filled = [
      d.name,
      d.productName,
      d.unit,
      d.qualityCriteria,
      d.evidenceRequired ?? '',
    ].every((s) => !!s.trim());
    return (
      !this.busy() &&
      !!d.positionId &&
      filled &&
      d.difficulty >= 1 &&
      d.difficulty <= 5 &&
      d.baseScore >= 0 &&
      d.baseScore <= 100
    );
  });

  protected set<K extends keyof CatalogItemInput>(k: K, v: CatalogItemInput[K]) {
    this.draft.update((d) => ({ ...d, [k]: v }));
    this.fieldErrors.update((e) => e.filter((x) => x.field !== k));
  }
  /** Điểm đang bằng điểm gợi ý của mức khó cũ (người sửa chưa tự đổi) thì đổi theo gợi ý của mức khó mới. */
  protected setDifficulty(v: number) {
    const d = this.draft();
    if (d.baseScore === this.suggestion(d.difficulty)) this.set('baseScore', this.suggestion(v));
    this.set('difficulty', v);
  }
  protected setScore(v: unknown) {
    this.set('baseScore', this.toNum(v) ?? -1);
  }
  protected suggestion(diff: number): number {
    return this.catalog().scoreSuggestion[String(diff)] ?? 0;
  }
  protected factor(diff: number): string {
    return String(this.catalog().difficultyFactors[String(diff)] ?? 1).replace('.', ',');
  }
  protected err(field: string): string | undefined {
    return this.fieldErrors().find((e) => e.field === field)?.message;
  }
  protected toNum(v: unknown): number | null {
    const n = Number(v);
    return v === '' || v === null || !Number.isFinite(n) ? null : n;
  }

  protected save(): void {
    const d = this.draft();
    const body: CatalogItemInput = { ...d, code: d.code?.trim() || undefined, name: d.name.trim() };
    const i = this.item();
    this.busy.set(true);
    this.error.set(null);
    (i
      ? this.api.updateItem(this.catalog().id, i, body)
      : this.api.addItem(this.catalog().id, body)
    ).subscribe({
      next: (r) => {
        this.busy.set(false);
        this.saved.emit(r);
        this.visible.set(false);
      },
      error: (e) => {
        this.busy.set(false);
        const p = toProblem(e);
        this.fieldErrors.set(p.errors ?? []);
        this.error.set(p.errors?.length ? 'Vui lòng sửa các ô được đánh dấu.' : p.title);
      },
    });
  }
}
