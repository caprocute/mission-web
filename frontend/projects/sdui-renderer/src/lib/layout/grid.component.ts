import { Component, computed, input } from '@angular/core';
import { SduiNodeComponent } from '../sdui-node.component';
import { SduiNode } from '../ui-pack';

/** `vt.layout.grid`: lưới đáp ứng theo điểm ngắt xs/md/xl. */
@Component({
  selector: 'vt-layout-grid',
  imports: [SduiNodeComponent],
  template: `
    <div class="vt-grid" [style.--cols-xs]="cols().xs" [style.--cols-md]="cols().md" [style.--cols-xl]="cols().xl">
      @for (child of children(); track $index) {
        <div class="vt-grid__cell"><vt-sdui-node [node]="child" /></div>
      }
    </div>
  `,
  styles: `
    .vt-grid { display: grid; gap: var(--vt-space-lg, 16px); grid-template-columns: repeat(var(--cols-xs), minmax(0, 1fr)); }
    @media (min-width: 768px) { .vt-grid { grid-template-columns: repeat(var(--cols-md), minmax(0, 1fr)); } }
    @media (min-width: 1280px) { .vt-grid { grid-template-columns: repeat(var(--cols-xl), minmax(0, 1fr)); } }
    .vt-grid__cell { min-width: 0; }
  `,
})
export class GridComponent {
  readonly columns = input<{ xs?: number; md?: number; xl?: number }>({});
  readonly children = input<SduiNode[]>([]);
  protected readonly cols = computed(() => ({ xs: 1, md: 2, xl: 3, ...this.columns() }));
}
