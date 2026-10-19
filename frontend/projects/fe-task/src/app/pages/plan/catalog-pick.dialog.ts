import { Component, computed, input, linkedSignal, model, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CatalogItem } from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';

/**
 * "Thêm từ danh mục" (SCR-0402): chọn mục của danh mục đang áp dụng cho kỳ. Danh sách hộp kiểm
 * hiện thẳng trong hộp thoại (không dùng lớp phủ của multiselect) để đọc màn hình và bàn phím dễ dùng.
 */
@Component({
  selector: 'vt-catalog-pick-dialog',
  imports: [FormsModule, ButtonModule, CheckboxModule, DialogModule, InputTextModule],
  template: `
    <p-dialog
      header="Thêm từ danh mục"
      [modal]="true"
      [(visible)]="visible"
      closeAriaLabel="Đóng"
      [style]="{ width: 'min(640px, 95vw)' }"
    >
      <label for="cat-q">Tìm theo mã hoặc tên</label>
      <input
        pInputText
        id="cat-q"
        type="search"
        class="w"
        [ngModel]="q()"
        (ngModelChange)="q.set($event)"
      />
      <fieldset>
        <legend class="sr-only">Công việc trong danh mục đang áp dụng</legend>
        <ul class="list">
          @for (c of shown(); track c.id) {
            <li>
              <p-checkbox
                [inputId]="'cp-' + c.id"
                [binary]="true"
                [disabled]="c.used"
                [ngModel]="c.used || picked().includes(c.id)"
                (ngModelChange)="toggle(c.id)"
              />
              <label [for]="'cp-' + c.id">
                <strong>{{ c.code }}</strong> · {{ c.name }}
                <span class="muted"
                  >({{ c.baseScore }} điểm{{ c.used ? ', đã có trong kế hoạch' : '' }})</span
                >
              </label>
            </li>
          } @empty {
            <li class="muted">Không có công việc phù hợp.</li>
          }
        </ul>
      </fieldset>
      <p class="muted small">
        Nội dung, nhóm, sản phẩm, đơn vị tính và điểm lấy theo danh mục; chủ trì mặc định là đơn vị
        lập.
      </p>
      <ng-template #footer>
        <p-button
          label="Đóng"
          severity="contrast"
          [outlined]="true"
          (onClick)="visible.set(false)"
        />
        <p-button
          [label]="'Thêm ' + picked().length + ' công việc'"
          [disabled]="!picked().length"
          (onClick)="add()"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    label[for='cat-q'] {
      display: block;
      font-weight: 500;
      margin-bottom: 6px;
    }
    .w {
      width: 100%;
    }
    fieldset {
      border: 0;
      padding: 0;
      margin: 8px 0 0;
    }
    .list {
      list-style: none;
      margin: 0;
      padding: 0;
      max-height: 320px;
      overflow-y: auto;
    }
    .list li {
      display: flex;
      gap: 8px;
      align-items: center;
      padding: 6px 0;
      border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.9em;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
  `,
})
export class CatalogPickDialog {
  readonly visible = model(false);
  readonly items = input<CatalogItem[]>([]);
  /** Mục đã có trong kế hoạch, không cho chọn lại. */
  readonly used = input<string[]>([]);
  readonly added = output<CatalogItem[]>();
  protected readonly q = signal('');
  protected readonly picked = linkedSignal<boolean, string[]>({
    source: this.visible,
    computation: () => [],
  });
  protected readonly shown = computed(() => {
    const q = this.q().trim().toLowerCase();
    return this.items()
      .filter((c) => c.status !== 'NGUNG')
      .filter((c) => !q || `${c.code} ${c.name}`.toLowerCase().includes(q))
      .map((c) => ({ ...c, used: this.used().includes(c.id) }));
  });

  protected toggle(id: string) {
    this.picked.update((p) => (p.includes(id) ? p.filter((x) => x !== id) : [...p, id]));
  }

  protected add() {
    const ids = new Set(this.picked());
    this.added.emit(this.items().filter((c) => ids.has(c.id)));
    this.visible.set(false);
  }
}
