import type { Meta, StoryObj } from '@storybook/angular';
import { MAU_1B_CONTEXT, MAU_1B_RULES, MAU_1B_SCHEMA, MAU_1B_UI } from '../samples/mau-1b';
import { VtFormComponent } from './vt-form.component';

/** Story của `vt-form` với mẫu 1B (phiếu tự đánh giá), dựng từ JSON Schema, ui và quy tắc CEL. */
const meta: Meta<VtFormComponent> = {
  title: 'Biểu mẫu/vt-form',
  component: VtFormComponent,
  tags: ['hoi-quy'],
};
export default meta;

export const Mau1B: StoryObj<VtFormComponent> = {
  name: 'vt-form mẫu 1B',
  render: (args) => ({
    props: args,
    template: `<vt-form [schema]="schema" [ui]="ui" [rules]="rules" [context]="context" [value]="value" />`,
  }),
  args: {
    schema: MAU_1B_SCHEMA,
    ui: MAU_1B_UI,
    rules: MAU_1B_RULES,
    context: MAU_1B_CONTEXT,
    value: { mucTuDeXuat: 'HTTNV' },
  },
};
