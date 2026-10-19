import { Component, computed, inject, input } from '@angular/core';
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
}
