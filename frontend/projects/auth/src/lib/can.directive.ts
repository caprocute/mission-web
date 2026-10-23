import {
  Directive,
  TemplateRef,
  ViewContainerRef,
  computed,
  effect,
  inject,
  input,
} from '@angular/core';
import { PermissionKey, PermissionService, WithPermittedActions } from './permission.service';

/**
 * Hiện nội dung khi vai trò hiện tại có quyền với chức năng của ma trận (wiki/15a).
 * Truyền một khoá hoặc mảng khoá (đủ một khoá là hiện), có thể kèm nhánh `else`:
 *
 * ```html
 * <button *vtCan="'SCR-0203.duyet'">Phê duyệt</button>
 * <section *vtCan="['SCR-0103.xem', 'SCR-0801.xem']; else khongCoQuyen">…</section>
 * <!-- theo từng đối tượng: thêm điều kiện `permittedActions` máy chủ trả cho đối tượng -->
 * <p-button *vtCan="'SCR-1601.giao-lai'; on: task" label="Giao lại" />
 * ```
 *
 * Không có quyền thì phần tử không được dựng (YC-PT-21), không hiện dạng mờ kèm lời giải thích.
 *
 * Chỉ ẩn thao tác chắc chắn không làm được; máy chủ vẫn là nơi quyết định (AGENTS.md quy tắc 6).
 */
@Directive({ selector: '[vtCan]' })
export class VtCanDirective {
  readonly vtCan = input.required<PermissionKey | readonly PermissionKey[]>();
  readonly vtCanElse = input<TemplateRef<unknown> | null>(null);
  readonly vtCanOn = input<WithPermittedActions | null | undefined>(undefined);

  private readonly template = inject(TemplateRef);
  private readonly container = inject(ViewContainerRef);
  private readonly permissions = inject(PermissionService);

  private readonly allowed = computed(() => {
    const keys = this.vtCan();
    const on = this.vtCanOn();
    const list = typeof keys === 'string' ? [keys as PermissionKey] : (keys as PermissionKey[]);
    return list.some((k) => this.permissions.allows(k, on));
  });

  constructor() {
    effect(() => {
      const view = this.allowed() ? this.template : this.vtCanElse();
      this.container.clear();
      if (view) this.container.createEmbeddedView(view);
    });
  }
}
