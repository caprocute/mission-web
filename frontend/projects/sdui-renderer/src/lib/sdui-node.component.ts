import { Component, ComponentRef, OnChanges, ViewContainerRef, inject, input } from '@angular/core';
import { ComponentRegistry } from './component-registry';
import { PropsValidator } from './props-validator';
import { CelContext } from './cel-context';
import { SduiTelemetry } from './sdui-telemetry';
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
  private readonly telemetry = inject(SduiTelemetry);
  private ref: ComponentRef<unknown> | null = null;
  /** Lượt dựng hiện tại; bỏ kết quả của lượt cũ nếu `node` đổi trong lúc chờ nạp lười. */
  private run = 0;

  async ngOnChanges(): Promise<void> {
    const run = ++this.run;
    this.vcr.clear();
    this.ref = null;
    const node = this.node();
    const entry = this.registry.get(node.component);
    if (!entry) {
      // component lạ không bao giờ được dựng (ADR-05 lớp 2)
      this.telemetry.record('sdui.component.unknown', { component: String(node.component) });
      return;
    }
    // cel-js nạp lười (WEB-052): chỉ chờ khi nút có visibleWhen, và chưa tải widget nếu nút bị ẩn
    if (node.visibleWhen && !(await this.cel.evaluateBool(node.visibleWhen))) return;
    const props = this.validator.sanitize(entry, node.props ?? {});
    const type = await entry.load();
    if (run !== this.run) return;
    this.ref = this.vcr.createComponent(type);
    for (const [k, v] of Object.entries(props)) this.ref.setInput(k, v);
    if (entry.container) this.ref.setInput('children', node.children ?? []);
  }
}
