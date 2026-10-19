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
  ORG_CHANGE_KIND_LABEL,
  OrgChangeKind,
  OrgChangeResult,
  OrgTreeApi,
  OrgUnitDetail,
  OrgUnitMatch,
  OrgUnitRef,
  fieldErrors,
  toProblem,
} from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { TextareaModule } from 'primeng/textarea';
import { Subject, debounceTime, of, switchMap } from 'rxjs';

/** MSG-01-09 (BR-01-07). */
export const MSG_01_09 = 'Sáp nhập cần khai báo ít nhất một đơn vị tiền thân.';

interface Draft {
  kind: OrgChangeKind;
  effectiveOn: string;
  orderNo: string;
  names: string;
  predecessors: OrgUnitRef[];
}

const KINDS = Object.keys(ORG_CHANGE_KIND_LABEL) as OrgChangeKind[];
const HINT: Record<OrgChangeKind, string> = {
  THANH_LAP: 'Thành lập đơn vị mới trực thuộc đơn vị đang chọn.',
  DOI_TEN: 'Đơn vị giữ mã, lịch sử; tên mới có hiệu lực từ ngày quyết định.',
  SAP_NHAP:
    'Đơn vị tiền thân kết thúc hiệu lực, phân công và đơn vị trực thuộc chuyển về đơn vị đang chọn.',
  HOP_NHAT: 'Đơn vị đang chọn và đơn vị tiền thân kết thúc, hình thành đơn vị mới cùng cấp.',
  CHIA_TACH: 'Đơn vị đang chọn kết thúc, hình thành các đơn vị mới cùng cấp (mỗi dòng một tên).',
  GIAI_THE: 'Đơn vị kết thúc hiệu lực; chỉ được khi không còn đơn vị trực thuộc.',
};

/**
 * Hộp "Thay đổi tổ chức" của SCR-0103: thành lập, đổi tên, sáp nhập, hợp nhất, chia tách, giải thể
 * kèm ngày hiệu lực và văn bản căn cứ → `POST /api/v1/org/units/{id}/changes` (EVT-14, EVT-15).
 * Mỗi thay đổi tạo phiên bản mới, không ghi đè lịch sử (BR-01-06); BR-01-07, MSG-01-09 kiểm ở máy chủ.
 */
@Component({
  selector: 'vt-org-change-dialog',
  imports: [
    FormsModule,
    ButtonModule,
    DialogModule,
    InputTextModule,
    MessageModule,
    TextareaModule,
  ],
  template: `
    <p-dialog
      [header]="'Thay đổi tổ chức: ' + unit().name"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(640px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <fieldset>
        <legend>Loại thay đổi</legend>
        <div class="kinds">
          @for (k of kinds; track k) {
            <label class="chk">
              <input
                type="radio"
                name="kind"
                [value]="k"
                [checked]="d().kind === k"
                (change)="set('kind', k)"
              />
              {{ kindLabel[k] }}
            </label>
          }
        </div>
        <p class="muted small">{{ hint[d().kind] }}</p>
      </fieldset>
      <div class="grid">
        <div>
          <label for="oc-eff">Ngày hiệu lực</label>
          <input
            id="oc-eff"
            pInputText
            type="date"
            class="full"
            [ngModel]="d().effectiveOn"
            (ngModelChange)="set('effectiveOn', $event)"
          />
          @if (fe()['effectiveOn']) {
            <small class="err">{{ fe()['effectiveOn'] }}</small>
          }
        </div>
        <div>
          <label for="oc-order">Văn bản căn cứ</label>
          <input
            id="oc-order"
            pInputText
            class="full"
            maxlength="64"
            placeholder="Số quyết định"
            [ngModel]="d().orderNo"
            (ngModelChange)="set('orderNo', $event)"
          />
          @if (fe()['orderNo']) {
            <small class="err">{{ fe()['orderNo'] }}</small>
          }
        </div>
      </div>
      @if (needsNames()) {
        <label for="oc-names">{{ namesLabel() }}</label>
        @if (d().kind === 'CHIA_TACH') {
          <textarea
            id="oc-names"
            pTextarea
            rows="3"
            class="full"
            [ngModel]="d().names"
            (ngModelChange)="set('names', $event)"
          ></textarea>
        } @else {
          <input
            id="oc-names"
            pInputText
            class="full"
            maxlength="255"
            [ngModel]="d().names"
            (ngModelChange)="set('names', $event)"
          />
        }
        @if (fe()['names']) {
          <small class="err">{{ fe()['names'] }}</small>
        }
      }
      @if (needsPredecessors()) {
        <label for="oc-pred">Đơn vị tiền thân</label>
        <input
          id="oc-pred"
          pInputText
          type="search"
          class="full"
          placeholder="Gõ tên hoặc mã đơn vị"
          [ngModel]="pq()"
          (ngModelChange)="findPred($event)"
        />
        @if (predMatches().length) {
          <ul class="results" aria-label="Đơn vị tìm được">
            @for (m of predMatches(); track m.id) {
              <li>
                <button type="button" class="res" (click)="addPred(m)">
                  {{ m.name }} <span class="muted small">{{ m.path }}</span>
                </button>
              </li>
            }
          </ul>
        }
        <ul class="chips" aria-label="Đơn vị tiền thân đã chọn">
          @for (p of d().predecessors; track p.id) {
            <li>
              {{ p.name }}
              <button
                type="button"
                class="x"
                [attr.aria-label]="'Bỏ ' + p.name"
                (click)="removePred(p.id)"
              >
                ×
              </button>
            </li>
          } @empty {
            <li class="muted small">Chưa chọn đơn vị tiền thân (BR-01-07).</li>
          }
        </ul>
        @if (fe()['predecessorIds']) {
          <small class="err" role="alert">{{ fe()['predecessorIds'] }}</small>
        }
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
          label="Ghi thay đổi"
          [loading]="busy()"
          [disabled]="!valid()"
          (onClick)="save()"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    fieldset {
      border: 0;
      padding: 0;
      margin: 0;
    }
    legend,
    label {
      display: block;
      margin: 12px 0 6px;
      font-weight: 500;
    }
    .kinds {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 16px;
    }
    label.chk {
      display: inline-flex;
      gap: 6px;
      margin: 0;
      font-weight: 400;
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
    .small {
      font-size: 0.875em;
    }
    .err {
      color: #b4001f;
    }
    .results,
    .chips {
      list-style: none;
      padding: 0;
      margin: 6px 0;
    }
    .results {
      border: 1px solid #e5e7eb;
      border-radius: 6px;
      max-height: 180px;
      overflow: auto;
    }
    .res {
      width: 100%;
      text-align: left;
      background: none;
      border: 0;
      padding: 6px 8px;
      font: inherit;
      cursor: pointer;
    }
    .res:hover,
    .res:focus-visible {
      background: #f3f4f6;
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }
    .chips li:not(.muted) {
      background: #eef0f3;
      border-radius: 999px;
      padding: 2px 4px 2px 10px;
    }
    .x {
      border: 0;
      background: none;
      cursor: pointer;
      font-size: 1.1em;
      padding: 0 6px;
    }
    :host ::ng-deep .msg {
      display: block;
      margin-top: 12px;
    }
  `,
})
export class OrgChangeDialog {
  readonly unit = input.required<OrgUnitDetail>();
  readonly visible = model(false);
  readonly saved = output<OrgChangeResult>();

  private readonly api = inject(OrgTreeApi);
  protected readonly kinds = KINDS;
  protected readonly kindLabel = ORG_CHANGE_KIND_LABEL;
  protected readonly hint = HINT;

  protected readonly d = linkedSignal<boolean, Draft>({
    source: this.visible,
    computation: () => ({
      kind: 'DOI_TEN',
      effectiveOn: new Date().toISOString().slice(0, 10),
      orderNo: '',
      names: '',
      predecessors: [],
    }),
  });
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly fe = signal<Record<string, string>>({});
  protected readonly pq = signal('');
  protected readonly predMatches = signal<OrgUnitMatch[]>([]);
  private readonly pred$ = new Subject<string>();

  protected readonly needsNames = computed(() => !['SAP_NHAP', 'GIAI_THE'].includes(this.d().kind));
  protected readonly needsPredecessors = computed(() =>
    ['SAP_NHAP', 'HOP_NHAT'].includes(this.d().kind),
  );
  protected readonly namesLabel = computed(
    () =>
      ({
        THANH_LAP: 'Tên đơn vị thành lập',
        DOI_TEN: 'Tên mới',
        HOP_NHAT: 'Tên đơn vị hợp nhất',
        CHIA_TACH: 'Tên các đơn vị mới (mỗi dòng một tên)',
      })[this.d().kind as 'THANH_LAP'] ?? '',
  );
  private readonly names = computed(() =>
    this.d()
      .names.split('\n')
      .map((n) => n.trim())
      .filter(Boolean),
  );
  /** Kiểm tra nhanh phía trình duyệt; máy chủ kiểm lại (MSG-01-09 khi thiếu tiền thân). */
  protected readonly valid = computed(() => {
    const d = this.d();
    if (!d.effectiveOn || !d.orderNo.trim()) return false;
    if (d.kind === 'CHIA_TACH') return this.names().length >= 2;
    if (this.needsNames() && !this.names().length) return false;
    return true;
  });

  constructor() {
    this.pred$
      .pipe(
        debounceTime(250),
        switchMap((q) => (q.trim().length >= 2 ? this.api.search(q.trim()) : of([]))),
      )
      .subscribe((m) =>
        this.predMatches.set(
          m.filter(
            (x) =>
              !x.validTo &&
              x.id !== this.unit().id &&
              !this.d().predecessors.some((p) => p.id === x.id),
          ),
        ),
      );
  }

  protected set<K extends keyof Draft>(key: K, value: Draft[K]) {
    this.d.update((d) => ({ ...d, [key]: value }));
    this.fe.update((f) => ({ ...f, [key]: '' }));
  }

  protected findPred(q: string) {
    this.pq.set(q);
    this.pred$.next(q);
  }

  protected addPred(m: OrgUnitMatch) {
    this.set('predecessors', [...this.d().predecessors, { id: m.id, code: m.code, name: m.name }]);
    this.fe.update((f) => ({ ...f, predecessorIds: '' }));
    this.pq.set('');
    this.predMatches.set([]);
  }

  protected removePred(id: string) {
    this.set(
      'predecessors',
      this.d().predecessors.filter((p) => p.id !== id),
    );
  }

  protected save() {
    const d = this.d();
    if (this.needsPredecessors() && !d.predecessors.length) {
      this.fe.set({ predecessorIds: MSG_01_09 });
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api
      .change(this.unit().id, {
        kind: d.kind,
        effectiveOn: d.effectiveOn,
        orderNo: d.orderNo.trim(),
        names: this.needsNames()
          ? d.kind === 'CHIA_TACH'
            ? this.names()
            : [d.names.trim()]
          : undefined,
        predecessorIds: this.needsPredecessors() ? d.predecessors.map((p) => p.id) : undefined,
      })
      .subscribe({
        next: (r) => {
          this.busy.set(false);
          this.visible.set(false);
          this.saved.emit(r);
        },
        error: (e) => {
          this.busy.set(false);
          const p = toProblem(e);
          const f = fieldErrors(p);
          this.fe.set(f);
          this.error.set(Object.keys(f).length ? null : p.title);
        },
      });
  }
}
