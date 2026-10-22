import type { Meta, StoryObj } from '@storybook/angular';
import { Component, input } from '@angular/core';
import { applicationConfig } from '@storybook/angular';
import { provideSduiComponents } from '../component-registry';
import { SduiNode } from '../ui-pack';
import { GridComponent } from './grid.component';

/** Khối minh hoạ có chiều cao cố định để ảnh chụp lưới không phụ thuộc dữ liệu. */
@Component({
  selector: 'vt-story-o',
  template: `<div class="o">{{ nhan() }}</div>`,
  styles: `
    .o {
      min-height: 96px;
      padding: 16px;
      border-radius: var(--vt-radius-card);
      background: var(--vt-color-surface);
      border: 1px solid var(--vt-color-border);
      color: var(--vt-color-text);
    }
  `,
})
class StoryBlock {
  readonly nhan = input('');
}

const children: SduiNode[] = ['Ô thứ nhất', 'Ô thứ hai', 'Ô thứ ba', 'Ô thứ tư', 'Ô thứ năm'].map(
  (nhan) => ({ component: 'story.o', props: { nhan } }),
);

/** Story của `vt.layout.grid`: lưới đáp ứng của trang chủ SDUI. */
const meta: Meta<GridComponent> = {
  title: 'Bố cục/Lưới',
  component: GridComponent,
  tags: ['hoi-quy'],
  decorators: [
    applicationConfig({
      providers: [
        provideSduiComponents([
          {
            name: 'story.o',
            since: '2.0.0',
            load: () => Promise.resolve(StoryBlock),
            propsSchema: {
              type: 'object',
              additionalProperties: false,
              properties: { nhan: { type: 'string' } },
            },
          },
        ]),
      ],
    }),
  ],
};
export default meta;

export const BaCot: StoryObj<GridComponent> = {
  name: 'vt.layout.grid',
  args: { columns: { xs: 1, md: 2, xl: 3 }, children },
};
