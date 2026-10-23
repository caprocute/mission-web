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
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import {
  BLOCKER_STATUS_LABEL,
  BlockerStatus,
  OrgApi,
  TaskApi,
  TaskBlocker,
  TaskDetail,
  toProblem,
} from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';
import { map } from 'rxjs';

/** Xử lý tại chỗ, hay báo cáo lên cấp trên. */
export type BlockerHandleMode = 'xu-ly' | 'cap-tren';

/** Bảng chuyển điểm nghẽn của MOD-02 (`BlockerRules`): trạng thái được chọn tiếp theo. */
const NEXT: Record<BlockerStatus, BlockerStatus[]> = {
  MO: ['DANG_XU_LY', 'DONG'],
  DANG_XU_LY: ['DA_GIAI_QUYET', 'DONG'],
  DA_GIAI_QUYET: ['DONG'],
  DONG: [],
};

interface Draft {
  handlerPersonId: string | null;
  resolveBy: string;
  status: BlockerStatus | null;
  note: string;
}

/**
 * SCR-0601 "Xử lý điểm nghẽn" của người giao (QT-06 bước 4, `SCR-0601.xu-ly-nghen`):
 * - xử lý: giao người xử lý, đặt hạn, ghi kết quả và chuyển trạng thái → `handleTaskBlocker`
 *   (PATCH JSON Merge Patch, If-Match theo phiên bản điểm nghẽn); giải quyết hoặc đóng khi còn mở bắt buộc
 *   ghi kết quả;
 * - báo cáo lên cấp trên: chọn người tiếp nhận, ghi nội dung từ 5 ký tự → `escalateTaskBlocker`.
 * Danh sách người lấy từ `/api/v1/org/persons` trong phạm vi đơn vị, chỉ tải khi hộp thoại mở.
 */
@Component({
  selector: 'vt-blocker-handle-dialog',
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
    <p-dialog
      [header]="mode() === 'cap-tren' ? 'Báo cáo điểm nghẽn lên cấp trên' : 'Xử lý điểm nghẽn'"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(600px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      @if (blocker(); as b) {
        <p class="muted">{{ b.code }} · {{ b.content }}</p>
        @if (mode() === 'cap-tren') {
          <label for="bh-to">Người tiếp nhận</label>
          <p-select
            inputId="bh-to"
            [options]="people()"
            optionLabel="label"
            optionValue="value"
            placeholder="Chọn người tiếp nhận"
            [filter]="true"
            filterBy="label"
            [ngModel]="draft().handlerPersonId"
            (ngModelChange)="set('handlerPersonId', $event)"
            styleClass="full"
            appendTo="body"
          />
          <label for="bh-note">Nội dung báo cáo</label>
          <textarea
            id="bh-note"
            pTextarea
            rows="3"
            maxlength="2000"
            class="full"
            [ngModel]="draft().note"
            (ngModelChange)="set('note', $event)"
          ></textarea>
          <small class="muted">Tối thiểu 5 ký tự · {{ draft().note.length }}/2.000</small>
        } @else {
          <div class="grid">
            <div>
              <label for="bh-handler">Người xử lý</label>
              <p-select
                inputId="bh-handler"
                [options]="people()"
                optionLabel="label"
                optionValue="value"
                [placeholder]="b.handlerName ?? 'Chọn người xử lý'"
                [filter]="true"
                filterBy="label"
                [showClear]="true"
                [ngModel]="draft().handlerPersonId"
                (ngModelChange)="set('handlerPersonId', $event)"
                styleClass="full"
                appendTo="body"
              />
            </div>
            <div>
              <label for="bh-due">Hạn xử lý</label>
              <input
                id="bh-due"
                pInputText
                type="date"
                class="full"
                [min]="today"
                [ngModel]="draft().resolveBy"
                (ngModelChange)="set('resolveBy', $event)"
              />
            </div>
          </div>
          <label for="bh-status">Kết quả xử lý</label>
          <p-select
            inputId="bh-status"
            [options]="statusOptions()"
            optionLabel="label"
            optionValue="value"
            placeholder="Giữ trạng thái hiện tại"
            [showClear]="true"
            [ngModel]="draft().status"
            (ngModelChange)="set('status', $event)"
            styleClass="full"
            appendTo="body"
          />
          <label for="bh-result">Ghi chú kết quả{{ noteRequired() ? ' *' : '' }}</label>
          <textarea
            id="bh-result"
            pTextarea
            rows="3"
            maxlength="2000"
            class="full"
            [ngModel]="draft().note"
            (ngModelChange)="set('note', $event)"
          ></textarea>
        }
      }
      @if (error(); as e) {
        <p-message severity="error" [text]="e" styleClass="msg" />
      }
      <ng-template #footer>
        <p-button
          label="Huỷ"
          severity="secondary"
          [outlined]="true"
          (onClick)="visible.set(false)"
        />
        <p-button
          [label]="mode() === 'cap-tren' ? 'Gửi báo cáo' : 'Lưu xử lý'"
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
    }
    .full,
    :host ::ng-deep .full {
      width: 100%;
    }
    .grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0 12px;
    }
    @media (max-width: 560px) {
      .grid {
        grid-template-columns: 1fr;
      }
    }
    .muted {
      color: #4b5563;
    }
    :host ::ng-deep .msg {
      display: block;
      margin-top: 12px;
    }
  `,
})
export class BlockerHandleDialog {
  readonly task = input.required<TaskDetail>();
  readonly blocker = input<TaskBlocker | null>(null);
  readonly mode = input<BlockerHandleMode>('xu-ly');
  readonly visible = model(false);
  /** Điểm nghẽn sau khi lưu và tên người tiếp nhận (khi báo cáo lên cấp trên). */
  readonly saved = output<{ blocker: TaskBlocker; toName: string | null }>();

  private readonly api = inject(TaskApi);
  private readonly org = inject(OrgApi);
  protected readonly today = new Date().toISOString().slice(0, 10);

  private readonly persons = rxResource({
    params: () => this.visible() || undefined,
    stream: () => this.org.persons().pipe(map((p) => p.items)),
    defaultValue: [],
  });
  protected readonly people = computed(() =>
    (this.persons.hasValue() ? this.persons.value() : []).map((p) => ({
      value: p.id,
      label: `${p.name} · ${p.title}`,
    })),
  );
  protected readonly statusOptions = computed(() =>
    (NEXT[this.blocker()?.status ?? 'DONG'] ?? []).map((value) => ({
      value,
      label: BLOCKER_STATUS_LABEL[value],
    })),
  );

  protected readonly draft = linkedSignal<boolean, Draft>({
    source: this.visible,
    computation: () => ({
      handlerPersonId: null,
      resolveBy: this.blocker()?.resolveBy ?? '',
      status: null,
      note: '',
    }),
  });
  protected readonly error = linkedSignal<boolean, string | null>({
    source: this.visible,
    computation: () => null,
  });
  protected readonly busy = signal(false);

  /** Giải quyết, hoặc đóng khi điểm nghẽn còn mở, phải ghi kết quả (BlockerRules). */
  protected readonly noteRequired = computed(() => {
    const s = this.draft().status;
    const from = this.blocker()?.status;
    return s === 'DA_GIAI_QUYET' || (s === 'DONG' && from !== 'DA_GIAI_QUYET');
  });
  protected readonly valid = computed(() => {
    const d = this.draft();
    const b = this.blocker();
    if (!b) return false;
    if (this.mode() === 'cap-tren') return !!d.handlerPersonId && d.note.trim().length >= 5;
    if (d.status === 'DANG_XU_LY' && !d.handlerPersonId && !b.handlerName) return false;
    if (this.noteRequired() && !d.note.trim()) return false;
    return (
      !!d.handlerPersonId ||
      !!d.status ||
      (!!d.resolveBy && d.resolveBy !== b.resolveBy) ||
      !!d.note.trim()
    );
  });

  protected set<K extends keyof Draft>(k: K, v: Draft[K]) {
    this.draft.update((d) => ({ ...d, [k]: v }));
  }

  protected save(): void {
    const d = this.draft();
    const b = this.blocker()!;
    const toName = this.persons.value().find((p) => p.id === d.handlerPersonId)?.name ?? null;
    const call =
      this.mode() === 'cap-tren'
        ? this.api.escalateBlocker(this.task(), b, {
            note: d.note.trim(),
            targetPersonId: d.handlerPersonId!,
          })
        : this.api.handleBlocker(this.task(), b, {
            ...(d.handlerPersonId ? { handlerPersonId: d.handlerPersonId } : {}),
            ...(d.resolveBy && d.resolveBy !== b.resolveBy ? { resolveBy: d.resolveBy } : {}),
            ...(d.status ? { status: d.status } : {}),
            ...(d.note.trim() ? { resolutionNote: d.note.trim() } : {}),
          });
    this.busy.set(true);
    this.error.set(null);
    call.subscribe({
      next: (u) => {
        this.busy.set(false);
        this.saved.emit({ blocker: u, toName });
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
