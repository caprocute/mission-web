import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import {
  ConflictField,
  ConflictNoticeComponent,
  HomeLayoutNode,
  HomeLayoutOverlay,
  PreferenceApi,
  ProblemPresenter,
  UserPreferenceItem,
} from '@vt/api-client';
import { SessionService } from '@vt/auth';
import { SduiNodeComponent, SduiOutletComponent, UiPackLoader } from '@vt/sdui-renderer';
import { ButtonModule } from 'primeng/button';
import {
  EditorItem,
  buildEditorItems,
  moveItem,
  previewLayout,
  toOverlay,
  toggleHidden,
  widgetId,
} from './home-layout-editor';

const HOME_KEY = 'ui.home.layout';

/**
 * SCR-0002 Trang chủ "Việc của tôi hôm nay" (QT-00). Bố cục lấy từ máy chủ (`getHomeLayout`, đã lọc
 * theo vai trò và ghép lớp phủ cá nhân CNH-02); lỗi thì dựng bố cục UI Pack ở trình duyệt. CNH-03:
 * chế độ "Tuỳ chỉnh trang chủ" sắp xếp, ẩn hoặc hiện từng khối, lưu một lần với `If-Match`.
 */
@Component({
  selector: 'vt-home-page',
  imports: [SduiOutletComponent, SduiNodeComponent, ButtonModule, ConflictNoticeComponent],
  template: `
    <header class="vt-page-head">
      <div>
        <h1>Xin chào, đồng chí {{ fullName() }}</h1>
        <p>{{ today }}</p>
      </div>
      @if (!editing() && layout.hasValue()) {
        <p-button
          label="Tuỳ chỉnh trang chủ"
          icon="pi pi-th-large"
          severity="secondary"
          [text]="true"
          (onClick)="startEdit()"
          data-testid="home-customize"
        />
      }
    </header>

    <section class="home-widgets" aria-labelledby="home-widgets-title">
      <h2 id="home-widgets-title" class="sr-only">Thông tin của tôi</h2>
      @if (notice(); as n) {
        <p class="home-notice" role="status">{{ n }}</p>
      }

      @if (editing()) {
        <section class="home-editor" aria-labelledby="home-editor-title" data-testid="home-editor">
          <h3 id="home-editor-title">Sắp xếp các khối trên trang chủ</h3>
          <p class="muted">
            Dùng nút mũi tên để đổi thứ tự, nút con mắt để ẩn hoặc hiện. Phần xem trước bên dưới
            thay đổi ngay; bấm "Lưu" để áp dụng trên mọi thiết bị.
          </p>
          @if (conflict()) {
            <vt-conflict-notice
              [mine]="draftOverlay()"
              [theirs]="theirs()"
              [fields]="conflictFields"
              message="Trang chủ của đồng chí vừa được thay đổi trên thiết bị khác."
              (reload)="reloadAfterConflict()"
              (overwrite)="save(true)"
            />
          }
          <ol class="editor-list">
            @for (it of items(); track it.id; let i = $index; let last = $last) {
              <li class="editor-item" [class.is-hidden]="it.hidden" [attr.data-widget]="it.id">
                <span class="editor-label">
                  {{ it.label }}
                  @if (it.mandatory) {
                    <small class="editor-tag">Bắt buộc của đơn vị</small>
                  } @else if (it.hidden) {
                    <small class="editor-tag">Đang ẩn</small>
                  }
                </span>
                <span class="editor-actions">
                  <p-button
                    icon="pi pi-arrow-up"
                    [text]="true"
                    [disabled]="i === 0"
                    [ariaLabel]="'Đưa lên trên: ' + it.label"
                    (onClick)="move(i, -1)"
                  />
                  <p-button
                    icon="pi pi-arrow-down"
                    [text]="true"
                    [disabled]="last"
                    [ariaLabel]="'Đưa xuống dưới: ' + it.label"
                    (onClick)="move(i, 1)"
                  />
                  <p-button
                    [icon]="it.hidden ? 'pi pi-eye' : 'pi pi-eye-slash'"
                    [text]="true"
                    [disabled]="it.mandatory"
                    [ariaLabel]="(it.hidden ? 'Hiện lại: ' : 'Ẩn: ') + it.label"
                    (onClick)="toggle(i)"
                  />
                </span>
              </li>
            }
          </ol>
          <div class="editor-footer">
            <p-button
              label="Lưu"
              icon="pi pi-check"
              [loading]="saving()"
              (onClick)="save(false)"
              data-testid="home-save"
            />
            <p-button label="Huỷ" severity="secondary" [text]="true" (onClick)="cancel()" />
            @if (pref()) {
              <p-button
                label="Khôi phục mặc định"
                icon="pi pi-replay"
                severity="secondary"
                [outlined]="true"
                [disabled]="saving()"
                (onClick)="restoreDefault()"
                data-testid="home-reset"
              />
            }
          </div>
        </section>
      }

      @if (shown(); as n) {
        <vt-sdui-node [node]="n" />
      } @else if (layout.error()) {
        <vt-sdui-outlet layout="home">
          <p class="muted">Chưa tải được trang chủ của đơn vị. Đồng chí vui lòng tải lại trang.</p>
        </vt-sdui-outlet>
      } @else {
        <p class="muted" role="status">Đang tải trang chủ…</p>
      }
    </section>
  `,
  styles: `
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
    .home-notice {
      margin: 0 0 12px;
      color: #047857;
    }
    .home-editor {
      border: 1px solid #cbd5e1;
      border-radius: 12px;
      padding: 16px;
      margin-bottom: 16px;
      background: #f8fafc;
    }
    .home-editor h3 {
      margin: 0 0 4px;
      font-size: 1rem;
    }
    .editor-list {
      list-style: none;
      margin: 12px 0;
      padding: 0;
      display: grid;
      gap: 8px;
    }
    .editor-item {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      padding: 6px 10px;
      background: #ffffff;
      border: 1px solid #e2e8f0;
      border-radius: 10px;
    }
    .editor-item.is-hidden .editor-label {
      color: #64748b;
    }
    .editor-label {
      display: flex;
      flex-direction: column;
      font-weight: 600;
    }
    .editor-tag {
      font-weight: 400;
      color: #64748b;
    }
    .editor-actions {
      display: inline-flex;
      gap: 2px;
    }
    .editor-footer {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }
  `,
})
export class HomePage {
  private readonly session = inject(SessionService);
  private readonly api = inject(PreferenceApi);
  private readonly loader = inject(UiPackLoader);
  private readonly presenter = inject(ProblemPresenter);
  protected readonly fullName = computed(() => this.session.user()?.name ?? '');
  /** Ngày hôm nay theo cách viết hành chính, ví dụ "Thứ Bảy, ngày 24 tháng 10 năm 2026". */
  protected readonly today = (() => {
    const d = new Date();
    const thu =
      d.getDay() === 0
        ? 'Chủ nhật'
        : `Thứ ${['', 'Hai', 'Ba', 'Tư', 'Năm', 'Sáu', 'Bảy'][d.getDay()]}`;
    return `${thu}, ngày ${d.getDate()} tháng ${d.getMonth() + 1} năm ${d.getFullYear()}`;
  })();
  protected readonly layout = rxResource({ stream: () => this.api.homeLayout() });
  protected readonly editing = signal(false);
  protected readonly items = signal<EditorItem[]>([]);
  /** Bản `ui.home.layout` đã đọc khi mở chế độ tuỳ chỉnh (`null` khi chưa từng lưu). */
  protected readonly pref = signal<UserPreferenceItem | null>(null);
  protected readonly saving = signal(false);
  protected readonly conflict = signal(false);
  protected readonly theirs = signal<HomeLayoutOverlay | null>(null);
  protected readonly notice = signal<string | null>(null);

  /** Nút của mọi widget biết được: bố cục máy chủ trả và bố cục gốc của UI Pack. */
  private readonly nodes = computed(() => {
    const map = new Map<string, HomeLayoutNode>();
    const base = (this.loader.pack()?.layouts['home']?.children ?? []) as HomeLayoutNode[];
    for (const n of base) map.set(widgetId(n), n);
    for (const n of this.layout.value()?.layout.children ?? []) map.set(widgetId(n), n);
    return map;
  });

  protected readonly draftOverlay = computed(() =>
    toOverlay(this.items(), this.overlayOf(this.pref())),
  );

  protected readonly shown = computed(() => {
    const root = this.layout.value()?.layout;
    if (!root) return null;
    return this.editing() ? previewLayout(root, this.items(), this.nodes()) : root;
  });

  protected readonly conflictFields: ConflictField<HomeLayoutOverlay>[] = [
    { label: 'Thứ tự các khối', value: (o) => this.names((o.widgetOrder ?? []).map((p) => p.id)) },
    { label: 'Các khối đã ẩn', value: (o) => this.names(o.hiddenWidgets ?? []) || 'Không có' },
  ];

  protected startEdit(): void {
    this.notice.set(null);
    this.conflict.set(false);
    this.api.all().subscribe({
      next: (all) => this.openEditor(all.items[HOME_KEY] ?? null),
      error: (err) => this.presenter.present(err),
    });
  }

  protected move(index: number, delta: -1 | 1): void {
    this.items.update((list) => moveItem(list, index, delta));
  }

  protected toggle(index: number): void {
    this.items.update((list) => toggleHidden(list, index));
  }

  protected cancel(): void {
    this.editing.set(false);
    this.conflict.set(false);
  }

  /** Lưu một lần; `overwrite` ghi đè bản mới trên máy chủ sau xung đột. */
  protected save(overwrite: boolean): void {
    const rowVersion = overwrite ? this.theirsVersion : (this.pref()?.rowVersion ?? null);
    this.saving.set(true);
    this.api.put(HOME_KEY, this.draftOverlay(), rowVersion).subscribe({
      next: () => this.done('Đã lưu cách sắp xếp trang chủ.'),
      error: (err) => this.fail(err),
    });
  }

  protected restoreDefault(): void {
    const current = this.pref();
    if (!current) return;
    this.saving.set(true);
    this.api.remove(HOME_KEY, current.rowVersion).subscribe({
      next: () => this.done('Đã khôi phục trang chủ mặc định của đơn vị.'),
      error: (err) => this.fail(err),
    });
  }

  protected reloadAfterConflict(): void {
    this.conflict.set(false);
    this.layout.reload();
    this.startEdit();
  }

  private theirsVersion: number | null = null;

  private openEditor(item: UserPreferenceItem | null): void {
    const value = this.layout.value();
    if (!value) return;
    const base = (this.loader.pack()?.layouts['home']?.children ?? []) as HomeLayoutNode[];
    this.pref.set(item);
    this.items.set(
      buildEditorItems(
        value.layout.children ?? [],
        base,
        this.overlayOf(item)?.hiddenWidgets ?? [],
        value.personalizationMeta?.mandatoryWidgetIds ?? [],
      ),
    );
    this.editing.set(true);
  }

  private done(message: string): void {
    this.saving.set(false);
    this.editing.set(false);
    this.conflict.set(false);
    this.notice.set(message);
    this.layout.reload();
  }

  private fail(err: unknown): void {
    this.saving.set(false);
    const shown = this.presenter.present(err);
    if (!shown.conflict) return;
    this.conflict.set(true);
    this.theirs.set(null);
    this.api.all().subscribe({
      next: (all) => {
        const latest = all.items[HOME_KEY] ?? null;
        this.theirsVersion = latest?.rowVersion ?? null;
        this.theirs.set(this.overlayOf(latest) ?? {});
      },
      error: () => this.theirs.set({}),
    });
  }

  private overlayOf(item: UserPreferenceItem | null): HomeLayoutOverlay | null {
    return (item?.value as HomeLayoutOverlay | undefined) ?? null;
  }

  private names(ids: string[]): string {
    const byId = new Map(this.items().map((it) => [it.id, it.label]));
    return ids.map((id) => byId.get(id) ?? 'Khối khác').join(', ');
  }
}
