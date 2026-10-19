import { Component, computed, effect, inject, input } from '@angular/core';
import { CelContext, layoutNeedsCel } from './cel-context';
import { ComponentRegistry } from './component-registry';
import { SduiNode } from './ui-pack';
import { SduiNodeComponent } from './sdui-node.component';
import { UiPackLoader } from './ui-pack-loader';

/** Dựng một bố cục có tên trong UI Pack (ví dụ `home` cho SCR-0002). */
@Component({
  selector: 'vt-sdui-outlet',
  imports: [SduiNodeComponent],
  template: `
    @if (root(); as n) {
      <vt-sdui-node [node]="n" />
    } @else {
      <ng-content />
    }
  `,
})
export class SduiOutletComponent {
  readonly layout = input.required<string>();
  private readonly loader = inject(UiPackLoader);
  protected readonly root = computed(() => this.loader.pack()?.layouts[this.layout()] ?? null);
  private readonly cel = inject(CelContext);
  private readonly registry = inject(ComponentRegistry);

  constructor() {
    // WEB-052: bố cục vừa có thì tải song song cel-js (nếu có visibleWhen) và mã của mọi nút
    // luôn hiện, thay vì chờ component cha dựng xong mới nạp component con
    effect(() => {
      const root = this.root();
      if (!root) return;
      // lỗi tải được xử lý lại ở evaluateBool (ẩn nút, ghi cảnh báo) và ở nút (bỏ qua nút lỗi)
      if (layoutNeedsCel(root)) this.cel.preload().catch(() => undefined);
      this.prefetch(root);
    });
  }

  /** Nút có `visibleWhen` chưa biết có hiện hay không nên không tải trước (kể cả nút con). */
  private prefetch(node: SduiNode): void {
    if (node.visibleWhen) return;
    const entry = this.registry.get(node.component);
    if (entry) {
      entry.load().catch(() => undefined);
      entry.prefetch?.();
    }
    for (const child of node.children ?? []) this.prefetch(child);
  }
}
