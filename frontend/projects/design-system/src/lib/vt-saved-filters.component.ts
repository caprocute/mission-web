import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';

/** Bộ lọc đã lưu hiển thị trên thanh bộ lọc. */
export interface VtSavedFilterOption {
  id: string;
  name: string;
  isDefault: boolean;
}

let seq = 0;

/**
 * Thanh "Bộ lọc đã lưu" của danh sách (CNH-04): mỗi bộ lọc là một nút, bấm một lần là áp dụng
 * (≤ 3 chạm); "Lưu bộ lọc hiện tại" mở ô nhập tên; "Quản lý" cho đổi tên, đặt mặc định, xoá.
 * Thành phần chỉ trình bày và phát sự kiện; màn hình lưu qua `ListPersonalization`.
 */
@Component({
  selector: 'vt-saved-filters',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'vt-saved-filters', '(keydown.escape)': 'closeAll()' },
  template: `
    <div class="vt-sf__row" role="group" aria-label="Bộ lọc đã lưu">
      @for (f of filters(); track f.id) {
        <button
          type="button"
          class="vt-sf__chip"
          [class.is-active]="f.id === activeId()"
          [attr.aria-pressed]="f.id === activeId()"
          (click)="apply.emit(f.id === activeId() ? null : f.id)"
        >
          @if (f.isDefault) {
            <i class="pi pi-star-fill" aria-hidden="true"></i>
            <span class="vt-sf__sr">Mặc định: </span>
          }
          {{ f.name }}
        </button>
      }
      @if (canSave()) {
        <button type="button" class="vt-sf__link" [disabled]="busy()" (click)="toggleForm()">
          <i class="pi pi-bookmark" aria-hidden="true"></i> Lưu bộ lọc hiện tại
        </button>
      }
      @if (filters().length) {
        <button
          type="button"
          class="vt-sf__link"
          [attr.aria-expanded]="managing()"
          [attr.aria-controls]="manageId"
          (click)="managing.set(!managing())"
        >
          Quản lý bộ lọc
        </button>
      }
    </div>

    @if (formOpen()) {
      <form class="vt-sf__form" (submit)="submitSave($event)">
        <label [for]="nameId">Tên bộ lọc</label>
        <input
          [id]="nameId"
          type="text"
          maxlength="100"
          autocomplete="off"
          [value]="draftName()"
          (input)="draftName.set($any($event.target).value)"
        />
        <label class="vt-sf__check">
          <input
            type="checkbox"
            [checked]="draftDefault()"
            (change)="draftDefault.set($any($event.target).checked)"
          />
          Áp dụng khi mở danh sách
        </label>
        <button type="submit" class="vt-sf__primary" [disabled]="busy() || !draftName().trim()">
          Lưu
        </button>
        <button type="button" class="vt-sf__link" (click)="formOpen.set(false)">Huỷ</button>
      </form>
    }

    @if (managing()) {
      <ul [id]="manageId" class="vt-sf__manage" aria-label="Quản lý bộ lọc đã lưu">
        @for (f of filters(); track f.id) {
          <li>
            @if (renaming() === f.id) {
              <form class="vt-sf__inline" (submit)="submitRename($event, f.id)">
                <label class="vt-sf__sr" [for]="nameId + '-' + f.id"
                  >Tên mới của bộ lọc {{ f.name }}</label
                >
                <input
                  [id]="nameId + '-' + f.id"
                  type="text"
                  maxlength="100"
                  [value]="draftName()"
                  (input)="draftName.set($any($event.target).value)"
                />
                <button
                  type="submit"
                  class="vt-sf__primary"
                  [disabled]="busy() || !draftName().trim()"
                >
                  Lưu tên
                </button>
                <button type="button" class="vt-sf__link" (click)="renaming.set(null)">Huỷ</button>
              </form>
            } @else {
              <span class="vt-sf__name"
                >{{ f.name }}
                @if (f.isDefault) {
                  <small>(mặc định)</small>
                }
              </span>
              <span class="vt-sf__acts">
                <button
                  type="button"
                  class="vt-sf__link"
                  [disabled]="busy()"
                  (click)="startRename(f)"
                >
                  Đổi tên
                </button>
                <button
                  type="button"
                  class="vt-sf__link"
                  [disabled]="busy()"
                  (click)="toggleDefault.emit(f.id)"
                >
                  {{ f.isDefault ? 'Bỏ mặc định' : 'Đặt mặc định' }}
                </button>
                @if (confirming() === f.id) {
                  <button
                    type="button"
                    class="vt-sf__danger"
                    [disabled]="busy()"
                    (click)="remove.emit(f.id); confirming.set(null)"
                  >
                    Xác nhận xoá
                  </button>
                  <button type="button" class="vt-sf__link" (click)="confirming.set(null)">
                    Không xoá
                  </button>
                } @else {
                  <button
                    type="button"
                    class="vt-sf__link"
                    [disabled]="busy()"
                    (click)="confirming.set(f.id)"
                  >
                    Xoá
                  </button>
                }
              </span>
            }
          </li>
        }
      </ul>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .vt-sf__row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
    }
    .vt-sf__chip {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      min-height: 2.25rem;
      padding: 0 12px;
      border: 1px solid #94a3b8;
      border-radius: 999px;
      background: #ffffff;
      color: #1f2937;
      font: inherit;
      cursor: pointer;
    }
    .vt-sf__chip.is-active {
      background: #1e3a8a;
      border-color: #1e3a8a;
      color: #ffffff;
    }
    .vt-sf__link,
    .vt-sf__danger {
      border: 0;
      background: none;
      min-height: 2.25rem;
      padding: 0 6px;
      color: #1d4ed8;
      font: inherit;
      text-decoration: underline;
      cursor: pointer;
    }
    .vt-sf__danger {
      color: #b91c1c;
    }
    .vt-sf__primary {
      min-height: 2.25rem;
      padding: 0 14px;
      border: 0;
      border-radius: 8px;
      background: #1e3a8a;
      color: #ffffff;
      font: inherit;
      cursor: pointer;
    }
    button:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }
    button:focus-visible,
    input:focus-visible {
      outline: 3px solid #1d4ed8;
      outline-offset: 2px;
    }
    .vt-sf__form,
    .vt-sf__inline {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
      margin-top: 8px;
    }
    .vt-sf__form input[type='text'],
    .vt-sf__inline input[type='text'] {
      min-height: 2.25rem;
      padding: 0 8px;
      border: 1px solid #94a3b8;
      border-radius: 8px;
      font: inherit;
    }
    .vt-sf__check {
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    .vt-sf__manage {
      list-style: none;
      margin: 8px 0 0;
      padding: 8px 12px;
      border: 1px solid #cbd5e1;
      border-radius: 10px;
      display: grid;
      gap: 4px;
    }
    .vt-sf__manage li {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
    }
    .vt-sf__name small {
      color: #4b5563;
      margin-left: 4px;
    }
    .vt-sf__sr {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
  `,
})
export class VtSavedFiltersComponent {
  readonly filters = input.required<VtSavedFilterOption[]>();
  readonly activeId = input<string | null>(null);
  readonly busy = input(false);
  readonly canSave = input(true);

  /** Áp dụng bộ lọc (`null`: bỏ bộ lọc đang áp dụng). */
  readonly apply = output<string | null>();
  readonly save = output<{ name: string; isDefault: boolean }>();
  readonly rename = output<{ id: string; name: string }>();
  readonly toggleDefault = output<string>();
  readonly remove = output<string>();

  protected readonly formOpen = signal(false);
  protected readonly managing = signal(false);
  protected readonly renaming = signal<string | null>(null);
  protected readonly confirming = signal<string | null>(null);
  protected readonly draftName = signal('');
  protected readonly draftDefault = signal(false);
  private readonly n = ++seq;
  protected readonly nameId = `vt-saved-filter-name-${this.n}`;
  protected readonly manageId = `vt-saved-filter-manage-${this.n}`;

  protected toggleForm(): void {
    this.draftName.set('');
    this.draftDefault.set(false);
    this.formOpen.set(!this.formOpen());
  }

  protected submitSave(e: Event): void {
    e.preventDefault();
    const name = this.draftName().trim();
    if (!name) return;
    this.save.emit({ name, isDefault: this.draftDefault() });
    this.formOpen.set(false);
  }

  protected startRename(f: VtSavedFilterOption): void {
    this.draftName.set(f.name);
    this.renaming.set(f.id);
  }

  protected submitRename(e: Event, id: string): void {
    e.preventDefault();
    const name = this.draftName().trim();
    if (!name) return;
    this.rename.emit({ id, name });
    this.renaming.set(null);
  }

  protected closeAll(): void {
    this.formOpen.set(false);
    this.renaming.set(null);
    this.confirming.set(null);
    this.managing.set(false);
  }
}
