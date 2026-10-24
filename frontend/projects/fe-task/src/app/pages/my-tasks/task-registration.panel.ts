import { Component, computed, inject, input, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { PeriodRef, TaskApi, TaskSummary, toProblem } from '@vt/api-client';
import { CatalogApi } from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';

/**
 * SCR-0503 vùng 5: đăng ký nhiệm vụ từ danh mục (QT-05 bước 1c).
 * - Trường 8 "mục danh mục" (`task.task.catalog_item_id`), trường 9 "số lượng, hạn đề xuất" (hạn trong kỳ).
 * - Gửi đăng ký → `/api/v1/tasks/registrations` (POST, Idempotency-Key), nhiệm vụ `NHAP` có cờ đăng ký, EVT-21.
 * - Thành công hiện MSG-05-11; lỗi 422 hiện theo trường từ `errors[]` của máy chủ.
 */
@Component({
  selector: 'vt-task-registration-panel',
  imports: [FormsModule, ButtonModule, InputTextModule, MessageModule, SelectModule],
  template: `
    <section class="card" aria-labelledby="reg-title">
      <h2 id="reg-title" tabindex="-1">Đăng ký nhiệm vụ</h2>
      <label for="reg-item">Từ danh mục</label>
      <p-select
        inputId="reg-item"
        [options]="catalogOptions()"
        optionLabel="label"
        optionValue="value"
        [ngModel]="itemId()"
        (ngModelChange)="itemId.set($event)"
        placeholder="Chọn mục danh mục"
        [filter]="true"
        filterBy="label"
        styleClass="full"
        [loading]="catalog.isLoading()"
        appendTo="body"
      />
      @if (fieldError('catalogItemId'); as e) {
        <small class="err">{{ e }}</small>
      }
      <div class="row">
        <div>
          <label for="reg-qty">Số lượng{{ unit() ? ' (' + unit() + ')' : '' }}</label>
          <input
            id="reg-qty"
            pInputText
            type="number"
            min="1"
            max="999"
            inputmode="numeric"
            class="full"
            [ngModel]="quantity()"
            (ngModelChange)="setQuantity($event)"
          />
          @if (fieldError('quantity'); as e) {
            <small class="err">{{ e }}</small>
          }
        </div>
        <div>
          <label for="reg-due">Hạn đề xuất</label>
          <input
            id="reg-due"
            pInputText
            type="date"
            class="full"
            [min]="today"
            [max]="period()?.endDate ?? ''"
            [ngModel]="due()"
            (ngModelChange)="due.set($event)"
          />
          @if (fieldError('proposedDueDate'); as e) {
            <small class="err">{{ e }}</small>
          }
        </div>
      </div>
      <p class="muted small">
        Nhiệm vụ đăng ký cần người quản lý trực tiếp duyệt, sau đó được đưa vào bản điều chỉnh kế
        hoạch.
      </p>
      @if (error(); as e) {
        <p-message severity="error" [text]="e" styleClass="msg" />
      }
      @if (done()) {
        <p-message
          severity="success"
          text="Đã gửi đăng ký nhiệm vụ. Người quản lý trực tiếp sẽ xem xét."
          styleClass="msg"
        />
      }
      <p-button
        label="Gửi đăng ký"
        [loading]="busy()"
        [disabled]="!valid()"
        (onClick)="submit()"
        styleClass="mt"
      />
    </section>
  `,
  styles: `
    .card {
      background: var(--vt-color-surface, #fff);
      border-radius: var(--vt-radius-card, 8px);
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
    }
    h2 {
      margin: 0 0 8px;
      font-size: 1.1rem;
    }
    label {
      display: block;
      margin: 12px 0 6px;
    }
    .full,
    :host ::ng-deep .full {
      width: 100%;
    }
    .row {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 12px;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.9em;
    }
    .err {
      color: var(--p-red-700, #b91c1c);
    }
    :host ::ng-deep .msg {
      margin: 8px 0;
      display: block;
    }
    :host ::ng-deep .mt {
      margin-top: 8px;
    }
  `,
})
export class TaskRegistrationPanel {
  readonly period = input<PeriodRef | null>(null);
  readonly registered = output<TaskSummary>();

  private readonly api = inject(TaskApi);
  private readonly catalogApi = inject(CatalogApi);
  protected readonly catalog = rxResource({
    stream: () => this.catalogApi.items({ applicableToMe: true, limit: 200 }),
  });
  protected readonly catalogOptions = computed(() =>
    (this.catalog.hasValue() ? this.catalog.value().items : []).map((i) => ({
      value: i.id,
      label: `${i.code} · ${i.name}`,
      unit: i.unit,
    })),
  );
  protected readonly today = new Date().toISOString().slice(0, 10);

  protected readonly itemId = signal<string | null>(null);
  protected readonly quantity = signal<number | null>(1);
  protected readonly due = signal('');
  protected readonly busy = signal(false);
  protected readonly done = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fieldErrors = signal<Record<string, string>>({});

  protected readonly unit = computed(
    () => this.catalogOptions().find((o) => o.value === this.itemId())?.unit ?? '',
  );
  protected readonly valid = computed(
    () => !!this.itemId() && (this.quantity() ?? 0) > 0 && !!this.due(),
  );

  protected setQuantity(v: number | string | null) {
    const n = Number(v);
    this.quantity.set(v === '' || v === null || !Number.isFinite(n) ? null : n);
  }

  protected fieldError(f: string) {
    return this.fieldErrors()[f] ?? null;
  }

  protected submit(): void {
    this.busy.set(true);
    this.done.set(false);
    this.error.set(null);
    this.fieldErrors.set({});
    this.api
      .register({
        catalogItemId: this.itemId()!,
        quantity: this.quantity()!,
        proposedDueDate: this.due(),
      })
      .subscribe({
        next: (t) => {
          this.busy.set(false);
          this.done.set(true);
          this.itemId.set(null);
          this.quantity.set(1);
          this.due.set('');
          this.registered.emit(t);
        },
        error: (e) => {
          this.busy.set(false);
          const p = toProblem(e);
          this.fieldErrors.set(
            Object.fromEntries((p.errors ?? []).map((x) => [x.field, x.message])),
          );
          this.error.set(p.errors?.length ? null : p.title);
        },
      });
  }
}
