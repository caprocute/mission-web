import { Component, ComponentRef, OnChanges, ViewContainerRef, inject, input } from '@angular/core';
import { ComponentRegistry } from './component-registry';
import { PropsValidator } from './props-validator';
import { CelContext } from './cel-context';
import { SduiNode } from './ui-pack';

/**
 * Component đệ quy dựng một nút bố cục SDUI. Không dùng innerHTML hay bypassSecurityTrust*:
 * chỉ tạo component đã đăng ký bằng ViewContainerRef.createComponent.
 */
@Component({
  selector: 'vt-sdui-node',
  template: '',
})
export class SduiNodeComponent implements OnChanges {
  readonly node = input.required<SduiNode>();

  private readonly vcr = inject(ViewContainerRef);
  private readonly registry = inject(ComponentRegistry);
  private readonly validator = inject(PropsValidator);
  private readonly cel = inject(CelContext);
  private ref: ComponentRef<unknown> | null = null;

  async ngOnChanges(): Promise<void> {
    this.vcr.clear();
    this.ref = null;
    const node = this.node();
    const entry = this.registry.get(node.component);
    if (!entry) {
      console.warn(`[sdui] Component "${node.component}" không có trong registry, bỏ qua`);
      return;
    }
    if (node.visibleWhen && !this.cel.evaluateBool(node.visibleWhen)) return;
    const props = this.validator.sanitize(entry, node.props ?? {});
    const type = await entry.load();
    this.ref = this.vcr.createComponent(type);
    for (const [k, v] of Object.entries(props)) this.ref.setInput(k, v);
    if (entry.container) this.ref.setInput('children', node.children ?? []);
  }
}
