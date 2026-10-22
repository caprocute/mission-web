import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
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
  imports: [SduiOutletComponent, SduiNodeComponent, RouterLink, ButtonModule, ConflictNoticeComponent],
  template: `
    <header class="vt-hero-banner">
      <div>
        <span class="vt-hero-kicker">Bàn làm việc điều hành · Kỳ đánh giá 2026</span>
        <h1>Xin chào, {{ fullName() }}</h1>
        <p class="muted">
          Dưới đây là những việc đồng chí cần quan tâm hôm nay và lối tắt tới khung cấu hình nền tảng.
        </p>
      </div>
      <div class="hero-actions">
        <a routerLink="/config-studio" class="hero-btn hero-btn--primary">
          <i class="pi pi-sliders-h" aria-hidden="true"></i>
          <span>Khung cấu hình &amp; Luồng động</span>
        </a>
        <a routerLink="/admin" class="hero-btn hero-btn--ghost">
          <i class="pi pi-cog" aria-hidden="true"></i>
          <span>Quản trị nền tảng</span>
        </a>
      </div>
    </header>

    <section class="platform-strip" aria-label="Khung nền tảng cấu hình và quản trị">
      <div class="strip-head">
        <span class="strip-badge">KHUNG NỀN TẢNG LÕI</span>
        <strong>Các phân hệ nền tảng vận hành hệ thống</strong>
      </div>
      <div class="platform-cards">
        <a routerLink="/config-studio" class="p-card">
          <span class="p-icon p-icon--rose" aria-hidden="true"><i class="pi pi-sitemap"></i></span>
          <span class="p-copy">
            <strong>Chính sách &amp; Luồng quy trình động</strong>
            <small>Thiết kế quy trình phê duyệt, công thức điểm và bảng xếp loại QĐ 366-QĐ/TW</small>
          </span>
        </a>
        <a routerLink="/admin/org" class="p-card">
          <span class="p-icon p-icon--blue" aria-hidden="true"><i class="pi pi-building"></i></span>
          <span class="p-copy">
            <strong>Cây tổ chức &amp; Đồng bộ nhân sự</strong>
            <small>Quản lý cơ cấu đơn vị đa cấp và đối soát dữ liệu cán bộ</small>
          </span>
        </a>
        <a routerLink="/admin/access" class="p-card">
          <span class="p-icon p-icon--amber" aria-hidden="true"><i class="pi pi-shield"></i></span>
          <span class="p-copy">
            <strong>Phân quyền &amp; Ủy quyền xử lý</strong>
            <small>Cấp vai trò theo đơn vị và thiết lập ủy quyền duyệt thay có thời hạn</small>
          </span>
        </a>
        <a routerLink="/tasks" class="p-card">
          <span class="p-icon p-icon--emerald" aria-hidden="true"><i class="pi pi-check-square"></i></span>
          <span class="p-copy">
            <strong>Không gian công việc &amp; Mục tiêu OKR</strong>
            <small>Xử lý nhiệm vụ hằng ngày trong ≤ 3 chạm và cập nhật tiến độ kết quả</small>
          </span>
        </a>
      </div>
    </section>

    <section class="home-widgets" aria-labelledby="home-widgets-title">
      <div class="home-tools">
        <h2 id="home-widgets-title" class="home-widgets-title">Thông tin của tôi</h2>
        @if (!editing() && layout.hasValue()) {
          <p-button
            label="Tuỳ chỉnh trang chủ"
            icon="pi pi-th-large"
            severity="secondary"
            [outlined]="true"
            (onClick)="startEdit()"
            data-testid="home-customize"
          />
        }
      </div>
      @if (notice(); as n) {
        <p class="home-notice" role="status">{{ n }}</p>
      }

      @if (editing()) {
        <section class="home-editor" aria-labelledby="home-editor-title" data-testid="home-editor">
          <h3 id="home-editor-title">Sắp xếp các khối trên trang chủ</h3>
          <p class="muted">
            Dùng nút mũi tên để đổi thứ tự, nút con mắt để ẩn hoặc hiện. Phần xem trước bên dưới thay đổi
            ngay; bấm "Lưu" để áp dụng trên mọi thiết bị.
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
            <p-button label="Lưu" icon="pi pi-check" [loading]="saving()" (onClick)="save(false)" data-testid="home-save" />
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
    .hero-actions {
      display: flex;
      gap: 10px;
      flex-wrap: wrap;
    }
    .hero-btn {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 10px 16px;
      border-radius: 10px;
      font-weight: 700;
      font-size: 0.9rem;
      text-decoration: none;
      transition: all 0.15s ease;
    }
    .hero-btn--primary {
      background: #e11d48;
      color: #ffffff;
      box-shadow: 0 8px 16px -4px rgba(225, 29, 72, 0.5);
    }
    .hero-btn--primary:hover {
      background: #be123c;
    }
    .hero-btn--ghost {
      background: rgba(255, 255, 255, 0.1);
      color: #ffffff;
      border: 1px solid rgba(255, 255, 255, 0.24);
    }
    .hero-btn--ghost:hover {
      background: rgba(255, 255, 255, 0.18);
    }
    .platform-strip {
      margin-bottom: 20px;
    }
    .strip-head {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 10px;
    }
    .strip-badge {
      font-size: 0.7rem;
      font-weight: 800;
      letter-spacing: 0.06em;
      color: #9f1239;
      background: #ffe4e6;
      padding: 2px 8px;
      border-radius: 6px;
    }
    .platform-cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 12px;
    }
    .p-card {
      display: flex;
      align-items: flex-start;
      gap: 12px;
      padding: 14px 16px;
      background: #ffffff;
      border: 1px solid #e2e8f0;
      border-radius: 12px;
      text-decoration: none;
      color: inherit;
      box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
      transition: transform 0.15s ease, box-shadow 0.15s ease, border-color 0.15s ease;
    }
    .p-card:hover {
      transform: translateY(-2px);
      border-color: #fda4af;
      box-shadow: 0 10px 15px -3px rgba(15, 23, 42, 0.08);
    }
    .p-icon {
      width: 40px;
      height: 40px;
      border-radius: 10px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      font-size: 1.1rem;
      flex-shrink: 0;
    }
    .p-icon--rose { background: #ffe4e6; color: #e11d48; }
    .p-icon--blue { background: #dbeafe; color: #2563eb; }
    .p-icon--amber { background: #fef3c7; color: #d97706; }
    .p-icon--emerald { background: #d1fae5; color: #059669; }
    .p-copy {
      display: flex;
      flex-direction: column;
      gap: 3px;
    }
    .p-copy strong {
      font-size: 0.94rem;
      color: #0f172a;
    }
    .home-tools {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      margin-bottom: 12px;
    }
    .home-widgets-title {
      font-size: 1.05rem;
      margin: 0;
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
    .p-copy small {
      font-size: 0.8rem;
      color: #64748b;
      line-height: 1.4;
    }
  `,
})
export class HomePage {
  private readonly session = inject(SessionService);
  private readonly api = inject(PreferenceApi);
  private readonly loader = inject(UiPackLoader);
  private readonly presenter = inject(ProblemPresenter);
  protected readonly fullName = computed(() => this.session.user()?.name ?? '');

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

  protected readonly draftOverlay = computed(() => toOverlay(this.items(), this.overlayOf(this.pref())));

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
