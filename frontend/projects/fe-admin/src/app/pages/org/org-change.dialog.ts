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
  ORG_CHANGE_TYPE_LABEL,
  OrgChangeOrder,
  OrgChangeRequest,
  OrgChangeType,
  OrgTreeApi,
  OrgUnitDetail,
  OrgUnitInput,
  OrgUnitSearchHit,
  fieldErrors,
  toProblem,
} from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { Subject, debounceTime, of, switchMap } from 'rxjs';

/** MSG-01-09 (BR-01-07). */
export const MSG_01_09 = 'Sáp nhập cần khai báo ít nhất một đơn vị tiền thân.';

interface NewUnitDraft {
  code: string;
  name: string;
}

interface Draft {
  kind: OrgChangeType;
  effectiveOn: string;
  orderNo: string;
  orderDate: string;
  newName: string;
  units: NewUnitDraft[];
  predecessors: { id: string; name: string }[];
}

/** Trường có chỗ hiện lỗi riêng trong hộp; lỗi trường khác gộp vào thông báo chung. */
const SHOWN = new Set(['effectiveOn', 'orderNo', 'orderDate', 'newName', 'relatedUnitIds']);

const today = () => new Date().toISOString().slice(0, 10);
const blankUnits = (kind: OrgChangeType): NewUnitDraft[] =>
  Array.from({ length: kind === 'CHIA_TACH' ? 2 : 1 }, () => ({ code: '', name: '' }));

const KINDS = Object.keys(ORG_CHANGE_TYPE_LABEL) as OrgChangeType[];
const HINT: Record<OrgChangeType, string> = {
  THANH_LAP: 'Thành lập đơn vị mới trực thuộc đơn vị đang chọn.',
  DOI_TEN: 'Đơn vị giữ mã, lịch sử; tên mới có hiệu lực từ ngày quyết định.',
  SAP_NHAP:
    'Đơn vị tiền thân kết thúc hiệu lực, phân công và đơn vị trực thuộc chuyển về đơn vị đang chọn.',
  HOP_NHAT: 'Đơn vị đang chọn và đơn vị tiền thân kết thúc, hình thành đơn vị mới cùng cấp.',
  CHIA_TACH: 'Đơn vị đang chọn kết thúc, hình thành các đơn vị mới cùng cấp.',
  GIAI_THE: 'Đơn vị kết thúc hiệu lực; chỉ được khi không còn đơn vị trực thuộc.',
};

/**
 * Hộp "Thay đổi tổ chức" của SCR-0103: thành lập, đổi tên, sáp nhập, hợp nhất, chia tách, giải thể
 * kèm ngày hiệu lực và quyết định căn cứ (số, ngày ban hành) → `POST /api/v1/org/units/{id}/changes`
 * thân `OrgChangeRequest` (EVT-14, EVT-15). Đơn vị mới (`newUnits`) nhận cấp, loại, khối đối tượng,
 * luồng đánh giá của đơn vị đang chọn (suy luận), người dùng nhập mã và tên; sửa chi tiết sau bằng
 * "Sửa". Mỗi thay đổi tạo phiên bản mới, không ghi đè lịch sử (BR-01-06); BR-01-07, MSG-01-09 kiểm
 * lại ở máy chủ.
 */
@Component({
  selector: 'vt-org-change-dialog',
  imports: [FormsModule, ButtonModule, DialogModule, InputTextModule, MessageModule],
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
        <div>
          <label for="oc-order-date">Ngày ban hành</label>
          <input
            id="oc-order-date"
            pInputText
            type="date"
            class="full"
            [max]="maxDay"
            [ngModel]="d().orderDate"
            (ngModelChange)="set('orderDate', $event)"
          />
          @if (fe()['orderDate']) {
            <small class="err">{{ fe()['orderDate'] }}</small>
          }
        </div>
      </div>
      @if (d().kind === 'DOI_TEN') {
        <label for="oc-names">Tên mới</label>
        <input
          id="oc-names"
          pInputText
          class="full"
          maxlength="255"
          [ngModel]="d().newName"
          (ngModelChange)="set('newName', $event)"
        />
        @if (fe()['newName']) {
          <small class="err">{{ fe()['newName'] }}</small>
        }
      }
      @if (needsUnits()) {
        <fieldset>
          <legend>{{ unitsLabel() }}</legend>
          @for (nu of d().units; track $index; let i = $index) {
            <div class="unit-row">
              <div>
                <label class="sub" [for]="'oc-code-' + i">Mã đơn vị</label>
                <input
                  pInputText
                  class="full"
                  maxlength="30"
                  [id]="'oc-code-' + i"
                  [ngModel]="nu.code"
                  (ngModelChange)="setUnit(i, 'code', $event)"
                />
              </div>
              <div>
                <label class="sub" [for]="'oc-name-' + i">Tên đơn vị</label>
                <input
                  pInputText
                  class="full"
                  maxlength="255"
                  [id]="'oc-name-' + i"
                  [ngModel]="nu.name"
                  (ngModelChange)="setUnit(i, 'name', $event)"
                />
              </div>
              @if (d().kind === 'CHIA_TACH' && d().units.length > 2) {
                <p-button
                  icon="pi pi-times"
                  severity="secondary"
                  [text]="true"
                  [ariaLabel]="'Bỏ đơn vị thứ ' + (i + 1)"
                  (onClick)="removeUnit(i)"
                />
              }
            </div>
          }
          @if (d().kind === 'CHIA_TACH') {
            <p-button
              label="Thêm đơn vị"
              icon="pi pi-plus"
              severity="secondary"
              [text]="true"
              (onClick)="addUnit()"
            />
          }
          <p class="muted small">
            Cấp, loại, khối đối tượng và luồng đánh giá lấy theo {{ unit().name }}; có thể sửa sau
            khi ghi.
          </p>
          @if (unitsError()) {
            <small class="err" role="alert">{{ unitsError() }}</small>
          }
        </fieldset>
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
                  {{ m.name }} <span class="muted small">{{ path(m) }}</span>
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
            <!-- BR-01-07 -->
            <li class="muted small">Chưa chọn đơn vị tiền thân.</li>
          }
        </ul>
        @if (fe()['relatedUnitIds']) {
          <small class="err" role="alert">{{ fe()['relatedUnitIds'] }}</small>
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
      grid-template-columns: 1fr 1fr 1fr;
      gap: 0 12px;
    }
    .unit-row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 2fr) auto;
      gap: 0 12px;
      align-items: end;
    }
    label.sub {
      font-weight: 400;
      margin: 6px 0 4px;
    }
    @media (max-width: 560px) {
      .grid,
      .unit-row {
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
  readonly saved = output<OrgChangeOrder>();

  private readonly api = inject(OrgTreeApi);
  protected readonly kinds = KINDS;
  protected readonly kindLabel = ORG_CHANGE_TYPE_LABEL;
  protected readonly hint = HINT;
  protected readonly maxDay = today();

  protected readonly d = linkedSignal<boolean, Draft>({
    source: this.visible,
    computation: () => ({
      kind: 'DOI_TEN',
      effectiveOn: today(),
      orderNo: '',
      orderDate: today(),
      newName: '',
      units: blankUnits('DOI_TEN'),
      predecessors: [],
    }),
  });
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly fe = signal<Record<string, string>>({});
  protected readonly pq = signal('');
  protected readonly predMatches = signal<OrgUnitSearchHit[]>([]);
  private readonly pred$ = new Subject<string>();

  protected readonly needsUnits = computed(() =>
    ['THANH_LAP', 'HOP_NHAT', 'CHIA_TACH'].includes(this.d().kind),
  );
  protected readonly needsPredecessors = computed(() =>
    ['SAP_NHAP', 'HOP_NHAT'].includes(this.d().kind),
  );
  protected readonly unitsLabel = computed(
    () =>
      ({
        THANH_LAP: 'Đơn vị thành lập',
        HOP_NHAT: 'Đơn vị hợp nhất',
        CHIA_TACH: 'Các đơn vị mới',
      })[this.d().kind as 'THANH_LAP'] ?? '',
  );
  /** Lỗi máy chủ cho `newUnits` hoặc `newUnits.<i>.<trường>`. */
  protected readonly unitsError = computed(() =>
    Object.entries(this.fe())
      .filter(([k, m]) => k.startsWith('newUnits') && m)
      .map(([, m]) => m)
      .join(' '),
  );
  /** Kiểm tra nhanh phía trình duyệt; máy chủ kiểm lại (MSG-01-09 khi thiếu tiền thân). */
  protected readonly valid = computed(() => {
    const d = this.d();
    if (!d.effectiveOn || !d.orderNo.trim() || !d.orderDate) return false;
    if (d.kind === 'DOI_TEN') return !!d.newName.trim();
    if (this.needsUnits()) {
      const filled = d.units.every((u) => u.code.trim() && u.name.trim());
      return filled && d.units.length >= (d.kind === 'CHIA_TACH' ? 2 : 1);
    }
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
            (x) => x.id !== this.unit().id && !this.d().predecessors.some((p) => p.id === x.id),
          ),
        ),
      );
  }

  protected set<K extends keyof Draft>(key: K, value: Draft[K]) {
    this.d.update((d) => {
      const next = { ...d, [key]: value };
      // đổi loại thay đổi: số dòng đơn vị mới theo loại (chia tách ít nhất hai)
      if (key === 'kind') next.units = blankUnits(value as OrgChangeType);
      return next;
    });
    this.fe.update((f) => ({ ...f, [key]: '' }));
  }

  protected setUnit(i: number, key: keyof NewUnitDraft, value: string) {
    this.set(
      'units',
      this.d().units.map((u, j) => (j === i ? { ...u, [key]: value } : u)),
    );
    this.fe.update((f) =>
      Object.fromEntries(Object.entries(f).filter(([k]) => !k.startsWith('newUnits'))),
    );
  }

  protected addUnit() {
    this.set('units', [...this.d().units, { code: '', name: '' }]);
  }

  protected removeUnit(i: number) {
    this.set(
      'units',
      this.d().units.filter((_, j) => j !== i),
    );
  }

  protected path(m: OrgUnitSearchHit) {
    return m.ancestors.map((a) => a.name).join(' / ');
  }

  protected findPred(q: string) {
    this.pq.set(q);
    this.pred$.next(q);
  }

  protected addPred(m: OrgUnitSearchHit) {
    this.set('predecessors', [...this.d().predecessors, { id: m.id, name: m.name }]);
    this.fe.update((f) => ({ ...f, relatedUnitIds: '' }));
    this.pq.set('');
    this.predMatches.set([]);
  }

  protected removePred(id: string) {
    this.set(
      'predecessors',
      this.d().predecessors.filter((p) => p.id !== id),
    );
  }

  /** Thân `OrgChangeRequest` theo loại thay đổi (khớp cách org-service đọc từng trường). */
  private request(d: Draft): OrgChangeRequest {
    const u = this.unit();
    const parentId = d.kind === 'THANH_LAP' ? u.id : u.parentId;
    const newUnit = (n: NewUnitDraft): OrgUnitInput => ({
      code: n.code.trim(),
      name: n.name.trim(),
      level: u.level,
      unitType: u.unitType,
      subjectGroupId: u.subjectGroupId,
      flows: [...u.flows],
      validFrom: d.effectiveOn,
      ...(parentId ? { parentId } : {}),
    });
    return {
      changeType: d.kind,
      effectiveOn: d.effectiveOn,
      orderNo: d.orderNo.trim(),
      orderDate: d.orderDate,
      ...(d.kind === 'DOI_TEN' ? { newName: d.newName.trim() } : {}),
      ...(this.needsUnits() ? { newUnits: d.units.map(newUnit) } : {}),
      ...(this.needsPredecessors()
        ? {
            relatedUnitIds: d.predecessors.map((p) => p.id),
            // phân công của đơn vị tiền thân chuyển về đơn vị đang chọn (hợp nhất: về đơn vị mới)
            moveAssignmentsTo: u.id,
          }
        : {}),
    };
  }

  protected save() {
    const d = this.d();
    if (this.needsPredecessors() && !d.predecessors.length) {
      this.fe.set({ relatedUnitIds: MSG_01_09 });
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.change(this.unit().id, this.request(d)).subscribe({
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
        const other = Object.entries(f)
          .filter(([k]) => !SHOWN.has(k) && !k.startsWith('newUnits'))
          .map(([, m]) => m);
        this.error.set(other.length ? other.join(' ') : Object.keys(f).length ? null : p.title);
      },
    });
  }
}
