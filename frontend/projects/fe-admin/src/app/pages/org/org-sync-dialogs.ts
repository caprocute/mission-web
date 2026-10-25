import { Component, inject, input, linkedSignal, model, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { OrgApi, fieldErrors, toProblem } from '@vt/api-client';
import { OrgDiscrepancy, OrgSyncApi } from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { VtSkeletonComponent } from '@vt/design-system';
import { errorText } from '../../admin-ui';

const STYLES = `
  fieldset {
    border: 0;
    margin: 0;
    padding: 0;
  }
  legend,
  .f > span {
    font-weight: 500;
    margin-bottom: 6px;
  }
  .opt {
    display: flex;
    gap: 8px;
    align-items: flex-start;
    padding: 8px 10px;
    border: 1px solid #e5e7eb;
    border-radius: 6px;
    margin-bottom: 8px;
  }
  .f {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin-bottom: 12px;
  }
  .row {
    display: flex;
    gap: 8px;
    margin-bottom: 12px;
  }
  .row input {
    flex: 1;
  }
  .native {
    padding: 8px 10px;
    border: 1px solid #cbd5e1;
    border-radius: 6px;
    font: inherit;
    min-height: 40px;
    background: #fff;
  }
  .muted {
    color: #4b5563;
  }
  .err {
    color: #b4001f;
  }
`;

/**
 * "Ánh xạ thủ công" (SCR-0104 vùng 3) cho một dòng không khớp người: tìm cán bộ trong phạm vi
 * (`GET /org/persons`), chọn hồ sơ đúng → `POST /org/discrepancies/{id}/map` ghi `integ.mapping_xref`
 * (EVT-16). Máy chủ trả 409 `ORG-409-XREF-EXISTS` khi mã ngoài đã gắn với người khác.
 */
@Component({
  selector: 'vt-org-sync-map-dialog',
  imports: [FormsModule, ButtonModule, DialogModule, InputTextModule, VtSkeletonComponent],
  template: `
    <p-dialog
      header="Ánh xạ thủ công"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(560px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <p class="muted">
        {{ row().sourceValueMasked }} · {{ row().externalId }}. Chọn hồ sơ của đúng người; mỗi mã
        tại nguồn chỉ gắn với một hồ sơ trên hệ thống.
      </p>
      <form class="row" role="search" (ngSubmit)="q.set(text().trim())">
        <label class="sr-only" for="map-q">Tìm cán bộ</label>
        <input
          id="map-q"
          pInputText
          name="q"
          [ngModel]="text()"
          (ngModelChange)="text.set($event)"
        />
        <p-button type="submit" label="Tìm" severity="contrast" [outlined]="true" />
      </form>
      <fieldset>
        <legend>Hồ sơ trên hệ thống</legend>
        @for (p of people.value()?.items ?? []; track p.id) {
          <label class="opt">
            <input type="radio" name="cand" [checked]="pick() === p.id" (change)="pick.set(p.id)" />
            <span>
              <strong>{{ p.name }}</strong> · {{ p.title }} · {{ p.unitName }}
            </span>
          </label>
        } @empty {
          @if (people.isLoading()) {
            <vt-skeleton variant="list" [rows]="3" label="Đang tìm cán bộ" />
          } @else {
            <p class="muted">Không thấy cán bộ phù hợp. Thử tìm tên khác.</p>
          }
        }
      </fieldset>
      @if (error(); as e) {
        <p class="err" role="alert">{{ e }}</p>
      }
      <ng-template #footer>
        <p-button
          label="Huỷ"
          severity="contrast"
          [outlined]="true"
          (onClick)="visible.set(false)"
        />
        <p-button label="Ghi ánh xạ" [loading]="busy()" [disabled]="!pick()" (onClick)="save()" />
      </ng-template>
    </p-dialog>
  `,
  styles:
    STYLES + '.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0)}',
})
export class OrgSyncMapDialog {
  readonly row = input.required<OrgDiscrepancy>();
  readonly visible = model(false);
  readonly saved = output<void>();

  private readonly api = inject(OrgSyncApi);
  private readonly org = inject(OrgApi);
  protected readonly text = linkedSignal(() => this.row().personName ?? '');
  protected readonly q = linkedSignal(() => this.row().personName ?? '');
  protected readonly people = rxResource({
    params: () => (this.visible() ? this.q() : undefined),
    stream: ({ params }) => this.org.persons(params || undefined),
  });
  protected readonly pick = linkedSignal<string | null>(() => (this.row(), null));
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected save() {
    const id = this.pick();
    if (!id) return;
    this.busy.set(true);
    this.error.set(null);
    this.api.map(this.row().id, id).subscribe({
      next: () => {
        this.busy.set(false);
        this.visible.set(false);
        this.saved.emit();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(errorText(e, 'Không ghi được ánh xạ.'));
      },
    });
  }
}

/**
 * "Ghi chú, giao xử lý" (SCR-0104 vùng 3): giao cán bộ tổ chức đơn vị kiểm tra một dòng lệch →
 * `POST /org/discrepancies/{id}/assign` (EVT-21). Người nhận lấy từ `GET /org/persons`; người nhận
 * là AC-07 theo suy luận của wiki/15a.
 */
@Component({
  selector: 'vt-org-sync-assign-dialog',
  imports: [FormsModule, ButtonModule, DialogModule, TextareaModule, VtSkeletonComponent],
  template: `
    <p-dialog
      header="Ghi chú, giao xử lý"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(560px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <p class="muted">{{ row().personName }} · {{ row().sourceValueMasked }}</p>
      <label class="f" for="vt-osd-who">
        <span>Cán bộ nhận kiểm tra</span>
        @if (people.isLoading()) {
          <vt-skeleton variant="text" [rows]="1" label="Đang tải danh sách cán bộ" />
        } @else {
          <select
            id="vt-osd-who"
            class="native"
            name="who"
            [ngModel]="who()"
            (ngModelChange)="who.set($event)"
          >
            <option value="">Chọn cán bộ</option>
            @for (p of people.value()?.items ?? []; track p.id) {
              <option [value]="p.id">{{ p.name }} · {{ p.unitName }}</option>
            }
          </select>
        }
        @if (fe()['assigneeId']) {
          <small class="err">{{ fe()['assigneeId'] }}</small>
        }
      </label>
      <label class="f">
        <span>Ghi chú</span>
        <textarea
          pTextarea
          name="note"
          rows="3"
          maxlength="1000"
          [ngModel]="note()"
          (ngModelChange)="note.set($event)"
        ></textarea>
      </label>
      @if (error(); as e) {
        <p class="err" role="alert">{{ e }}</p>
      }
      <ng-template #footer>
        <p-button
          label="Huỷ"
          severity="contrast"
          [outlined]="true"
          (onClick)="visible.set(false)"
        />
        <p-button label="Giao xử lý" [loading]="busy()" [disabled]="!who()" (onClick)="save()" />
      </ng-template>
    </p-dialog>
  `,
  styles: STYLES,
})
export class OrgSyncAssignDialog {
  readonly row = input.required<OrgDiscrepancy>();
  readonly visible = model(false);
  readonly saved = output<OrgDiscrepancy>();

  private readonly api = inject(OrgSyncApi);
  private readonly org = inject(OrgApi);
  protected readonly people = rxResource({
    params: () => this.visible() || undefined,
    stream: () => this.org.persons(),
  });
  protected readonly who = linkedSignal(() => (this.row(), ''));
  protected readonly note = linkedSignal(() => (this.row(), ''));
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly fe = signal<Record<string, string>>({});

  protected save() {
    this.busy.set(true);
    this.error.set(null);
    this.fe.set({});
    this.api.assign(this.row().id, this.who(), this.note().trim() || undefined).subscribe({
      next: (d) => {
        this.busy.set(false);
        this.visible.set(false);
        this.saved.emit(d);
      },
      error: (e) => {
        this.busy.set(false);
        const fe = fieldErrors(toProblem(e));
        this.fe.set(fe);
        this.error.set(Object.keys(fe).length ? null : errorText(e, 'Không giao xử lý được.'));
      },
    });
  }
}
