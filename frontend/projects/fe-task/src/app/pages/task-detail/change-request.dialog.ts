/*
 * SCR-0602 "Đề nghị gia hạn, điều chỉnh" (QT-06 bước 5, TL-02 Hình 8.10) — đối chiếu đặc tả:
 * Trường: 1 loại đề nghị (GIA_HAN, DOI_SO_LUONG, DOI_SAN_PHAM, TAM_DUNG, HUY; mặc định GIA_HAN); 2 hạn mới (sau hạn hiện tại,
 *   trong kỳ); 3 số ngày làm việc tăng thêm (máy chủ tính theo lịch nghỉ); 4 số lượng, sản phẩm mới; 5 nguyên nhân theo người
 *   gửi; 6 điểm nghẽn liên quan; 7 lý do chi tiết 20–2.000 ký tự; 8 tài liệu kèm (chưa làm: chờ `/api/v1/files` của WEB-032);
 *   9 người duyệt (chỉ đọc); 10 số lần gia hạn đã dùng; 12 trạng thái đề nghị (hiện ở tab Đề nghị của SCR-0601).
 * Chức năng: đổi loại; xem trước hệ quả → POST /tasks/{id}/change-requests/preview; gửi → POST /tasks/{id}/change-requests
 *   (Idempotency-Key), EVT-21; huỷ đóng hộp thoại. Rút lại nằm ở SCR-0601.
 * Quy tắc, thông báo: BR-06-01 (C), BR-06-06/MSG-06-07 (vượt ngưỡng), BR-06-07/MSG-06-08 (gửi sau hạn),
 *   BR-06-09/MSG-06-09 (không huỷ khi đã có sản phẩm nghiệm thu), MSG-06-06 (xem trước), MSG-06-10 (gửi thành công).
 */
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
import { rxResource, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { debounceTime, of } from 'rxjs';
import {
  CAUSE_KIND_LABEL,
  CHANGE_REQUEST_TYPE_LABEL,
  ChangeRequestDraft,
  ChangeRequestType,
  CauseKind,
  TaskApi,
  TaskDetail,
  toProblem,
} from '@vt/api-client';
import { VtSkeletonComponent } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TextareaModule } from 'primeng/textarea';
import { formatDate } from '../../task-ui';

interface Draft {
  type: ChangeRequestType;
  newDueDate: string;
  newQuantity: number | null;
  newProduct: string;
  causeClaimed: CauseKind | null;
  blockerId: string | null;
  reason: string;
}

const fmtC = (c: number) => c.toFixed(2).replace('.', ',');

@Component({
  selector: 'vt-change-request-dialog',
  imports: [
    VtSkeletonComponent,
    FormsModule,
    ButtonModule,
    DialogModule,
    InputTextModule,
    MessageModule,
    SelectModule,
    SelectButtonModule,
    TextareaModule,
  ],
  template: `
    @let t = task();
    @let d = draft();
    <p-dialog
      header="Đề nghị gia hạn, điều chỉnh nhiệm vụ"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(820px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <p class="muted">{{ t.code }} · {{ t.title }} · hạn hiện tại {{ date(t.dueDate) }}</p>

      <span class="lbl" id="cr-type-lbl">Loại đề nghị <span class="req">*</span></span>
      <p-selectbutton
        [options]="typeOptions"
        optionLabel="label"
        optionValue="value"
        [allowEmpty]="false"
        [ngModel]="d.type"
        (ngModelChange)="set('type', $event)"
        ariaLabelledBy="cr-type-lbl"
        styleClass="types"
      />

      <div class="grid">
        @switch (d.type) {
          @case ('GIA_HAN') {
            <div>
              <label for="cr-due">Hạn mới đề nghị <span class="req">*</span></label>
              <input
                id="cr-due"
                pInputText
                type="date"
                class="full"
                [min]="minDue()"
                [max]="periodEnd() ?? ''"
                [ngModel]="d.newDueDate"
                (ngModelChange)="set('newDueDate', $event)"
              />
              @if (preview.value(); as p) {
                <small class="muted">+{{ p.extraWorkingDays }} ngày làm việc</small>
              }
            </div>
          }
          @case ('DOI_SO_LUONG') {
            <div>
              <label for="cr-qty">Số lượng mới ({{ t.unit }}) <span class="req">*</span></label>
              <input
                id="cr-qty"
                pInputText
                type="number"
                min="1"
                class="full"
                [ngModel]="d.newQuantity"
                (ngModelChange)="set('newQuantity', toNum($event))"
              />
              <small class="muted">Hiện tại {{ t.plannedQuantity }} {{ t.unit }}</small>
            </div>
          }
          @case ('DOI_SAN_PHAM') {
            <div>
              <label for="cr-prod">Sản phẩm mới <span class="req">*</span></label>
              <input
                id="cr-prod"
                pInputText
                class="full"
                maxlength="500"
                [ngModel]="d.newProduct"
                (ngModelChange)="set('newProduct', $event)"
              />
            </div>
          }
          @default {
            <div></div>
          }
        }
        <div>
          <label for="cr-cause">Nguyên nhân <span class="req">*</span></label>
          <p-select
            inputId="cr-cause"
            [options]="causeOptions"
            optionLabel="label"
            optionValue="value"
            placeholder="Chọn"
            [ngModel]="d.causeClaimed"
            (ngModelChange)="set('causeClaimed', $event)"
            styleClass="full"
            appendTo="body"
          />
        </div>
        <div>
          <label for="cr-blocker">Điểm nghẽn liên quan</label>
          <p-select
            inputId="cr-blocker"
            [options]="blockerOptions()"
            optionLabel="label"
            optionValue="value"
            placeholder="Không chọn"
            [showClear]="true"
            [ngModel]="d.blockerId"
            (ngModelChange)="set('blockerId', $event)"
            styleClass="full"
            appendTo="body"
          />
        </div>
      </div>

      <label for="cr-reason">Lý do chi tiết <span class="req">*</span></label>
      <textarea
        id="cr-reason"
        pTextarea
        rows="3"
        maxlength="2000"
        class="full"
        [ngModel]="d.reason"
        (ngModelChange)="set('reason', $event)"
      ></textarea>
      <small class="muted" [class.err]="d.reason.trim().length > 0 && d.reason.trim().length < 20"
        >{{ d.reason.trim().length }}/2.000 ký tự, tối thiểu 20</small
      >

      <span class="lbl">Tài liệu kèm theo</span>
      <p class="muted small">
        Nếu có văn bản liên quan, đồng chí ghi số, ký hiệu văn bản vào phần lý do chi tiết.
      </p>

      @if (preview.value(); as p) {
        @if (p.cancelBlocked) {
          <p-message
            severity="error"
            text="Không thể huỷ nhiệm vụ đã có sản phẩm được nghiệm thu."
            styleClass="msg"
          />
        }
        @if (d.type === 'GIA_HAN' && d.newDueDate) {
          <div class="consequence" aria-live="polite">
            <div class="obj">
              <strong>Nếu được chấp nhận là khách quan:</strong> hạn tính điểm đổi thành
              {{ date(p.objective.scoreDueDate) }}; hoàn thành đúng hạn mới thì hệ số tiến độ (C) =
              {{ c(p.objective.c) }}.
            </div>
            <div class="subj">
              <strong>Nếu bị đánh giá là chủ quan:</strong> hạn tính điểm giữ
              {{ date(p.subjective.scoreDueDate) }}; hoàn thành {{ date(d.newDueDate) }} là chậm
              {{ p.subjective.delayDays }} ngày làm việc, hệ số tiến độ (C) = 1 − 0,05 ×
              {{ p.subjective.delayDays }} = {{ c(p.subjective.c) }}.
            </div>
          </div>
        }
        @if (p.overThreshold) {
          <p-message
            severity="info"
            styleClass="msg"
            [text]="
              'Nhiệm vụ đã gia hạn ' +
              t.extensionsUsed +
              ' lần trong quý. Đề nghị này sẽ được chuyển lên ' +
              (p.escalateToName ?? 'cấp trên') +
              ' duyệt.'
            "
          />
        }
        @if (p.lateSubmission) {
          <p-message
            severity="info"
            text="Đề nghị gửi sau hạn thực hiện. Các ngày chậm trước khi gửi được tính là chủ quan."
            styleClass="msg"
          />
        }
      } @else if (preview.isLoading()) {
        <vt-skeleton variant="text" [rows]="2" label="Đang tính hệ quả của đề nghị" />
      }
      @if (error(); as e) {
        <p-message severity="error" [text]="e" styleClass="msg" />
      }

      <ng-template #footer>
        <span class="muted small approver"
          >Người duyệt: {{ approver() }} · đã dùng {{ t.extensionsUsed }}/{{ t.extensionsMax }} lần
          gia hạn trong quý</span
        >
        <p-button
          label="Huỷ"
          severity="secondary"
          [outlined]="true"
          (onClick)="visible.set(false)"
        />
        <p-button
          label="Gửi đề nghị"
          [loading]="busy()"
          [disabled]="!valid()"
          (onClick)="submit()"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    label,
    .lbl {
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
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0 12px;
    }
    @media (max-width: 700px) {
      .grid {
        grid-template-columns: 1fr;
      }
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.9em;
    }
    .err {
      color: #b91c1c;
    }
    :host ::ng-deep .types {
      --p-togglebutton-color: #374151;
    }
    :host ::ng-deep .types .p-togglebutton {
      min-height: var(--vt-min-touch, 40px);
    }
    .consequence {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
      margin-top: 12px;
    }
    @media (max-width: 700px) {
      .consequence {
        grid-template-columns: 1fr;
      }
    }
    .obj,
    .subj {
      padding: 12px;
      border-radius: 6px;
      line-height: 1.5;
    }
    .obj {
      background: #eff6ff;
      border-left: 4px solid #1d4ed8;
    }
    .subj {
      background: #fffbeb;
      border-left: 4px solid #b45309;
    }
    .approver {
      flex: 1;
      margin-right: 8px;
      text-align: left;
    }
    :host ::ng-deep .msg {
      display: block;
      margin-top: 12px;
    }
  `,
})
export class ChangeRequestDialog {
  readonly task = input.required<TaskDetail>();
  readonly periodEnd = input<string | null>(null);
  readonly visible = model(false);
  readonly saved = output<{ task: TaskDetail; approverName: string }>();

  private readonly api = inject(TaskApi);
  protected readonly typeOptions = (
    Object.keys(CHANGE_REQUEST_TYPE_LABEL) as ChangeRequestType[]
  ).map((value) => ({
    value,
    label: CHANGE_REQUEST_TYPE_LABEL[value],
  }));
  protected readonly causeOptions = (Object.keys(CAUSE_KIND_LABEL) as CauseKind[]).map((value) => ({
    value,
    label: CAUSE_KIND_LABEL[value],
  }));
  protected readonly blockerOptions = computed(() =>
    this.task()
      .blockers.filter((b) => b.status === 'MO' || b.status === 'DANG_XU_LY')
      .map((b) => ({ value: b.id, label: `${b.code} · ${b.content}` })),
  );

  /** Mỗi lần mở: loại mặc định Gia hạn, gợi ý điểm nghẽn đang mở đầu tiên (SCR-0602 trường 6). */
  protected readonly draft = linkedSignal<boolean, Draft>({
    source: this.visible,
    computation: () => ({
      type: 'GIA_HAN',
      newDueDate: '',
      newQuantity: this.task().plannedQuantity,
      newProduct: '',
      causeClaimed: null,
      blockerId: this.blockerOptions()[0]?.value ?? null,
      reason: '',
    }),
  });
  protected readonly error = linkedSignal<boolean, string | null>({
    source: this.visible,
    computation: () => null,
  });
  protected readonly busy = signal(false);
  protected readonly minDue = computed(() => {
    const d = new Date(Date.parse(this.task().dueDate) + 86_400_000);
    return d.toISOString().slice(0, 10);
  });

  private readonly body = computed<ChangeRequestDraft>(() => {
    const d = this.draft();
    return {
      type: d.type,
      newDueDate: d.type === 'GIA_HAN' ? d.newDueDate || undefined : undefined,
      newQuantity: d.type === 'DOI_SO_LUONG' ? (d.newQuantity ?? undefined) : undefined,
      newProduct: d.type === 'DOI_SAN_PHAM' ? d.newProduct.trim() : undefined,
      causeClaimed: d.causeClaimed ?? 'CHU_QUAN',
      blockerId: d.blockerId ?? undefined,
      reason: d.reason.trim(),
    };
  });
  /** Xem trước chỉ khi có điều kiện tính (gia hạn có ngày, hoặc huỷ); trễ 300 ms để không gọi theo từng phím. */
  private readonly previewKey = toSignal(
    toObservable(
      computed(() =>
        this.visible() &&
        ((this.body().type === 'GIA_HAN' && this.body().newDueDate) || this.body().type === 'HUY')
          ? this.body()
          : null,
      ),
    ).pipe(debounceTime(300)),
    { initialValue: null },
  );
  protected readonly preview = rxResource({
    params: () => this.previewKey() ?? undefined,
    stream: ({ params }) =>
      params ? this.api.previewChangeRequest(this.task(), params) : of(undefined),
  });

  protected readonly approver = computed(() => {
    const p = this.preview.hasValue() ? this.preview.value() : undefined;
    return p?.overThreshold
      ? `${p.escalateToName ?? 'cấp trên'} (do vượt số lần gia hạn)`
      : `${this.task().assignerName} (người giao việc)`;
  });

  protected readonly valid = computed(() => {
    const d = this.draft();
    const reason = d.reason.trim().length;
    if (!d.causeClaimed || reason < 20 || reason > 2000) return false;
    if (d.type === 'GIA_HAN' && (!d.newDueDate || d.newDueDate <= this.task().dueDate))
      return false;
    if (d.type === 'DOI_SO_LUONG' && !((d.newQuantity ?? 0) > 0)) return false;
    if (d.type === 'DOI_SAN_PHAM' && !d.newProduct.trim()) return false;
    const p = this.preview.hasValue() ? this.preview.value() : undefined;
    return !(d.type === 'HUY' && p?.cancelBlocked);
  });

  protected set<K extends keyof Draft>(k: K, v: Draft[K]) {
    this.draft.update((d) => ({ ...d, [k]: v }));
  }
  protected toNum(v: unknown): number | null {
    const n = Number(v);
    return v === '' || v === null || !Number.isFinite(n) ? null : n;
  }
  protected date(d: string) {
    return formatDate(d, true);
  }
  protected c(v: number) {
    return fmtC(v);
  }

  protected submit(): void {
    this.busy.set(true);
    this.error.set(null);
    const approverName = this.approver().replace(/ \(.*\)$/, '');
    this.api.submitChangeRequest(this.task(), this.body()).subscribe({
      next: (u) => {
        this.busy.set(false);
        this.saved.emit({
          task: u,
          approverName: u.changeRequests[0]?.approverName ?? approverName,
        });
        this.visible.set(false);
      },
      error: (e) => {
        this.busy.set(false);
        const p = toProblem(e);
        this.error.set(p.errors?.length ? p.errors.map((x) => x.message).join('; ') : p.title);
      },
    });
  }
}
