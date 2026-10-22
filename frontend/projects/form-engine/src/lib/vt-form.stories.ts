import { ApplicationRef, Component, ElementRef, inject, signal } from '@angular/core';
import type { Meta, StoryObj } from '@storybook/angular';
import { BIG_FORM_FIELDS, bigForm } from '../samples/bieu-mau-50-truong';
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

/**
 * Khung đo ngân sách dựng biểu mẫu 50 trường (TL-01, WEB-010) trên trình duyệt thật: `__doBieuMau()`
 * dựng lại biểu mẫu, trả thời gian của lượt vẽ đầu (đồng bộ), thời gian tới khi đủ 50 ô và tác vụ
 * dài nhất trong lúc dựng. Không gắn thẻ hồi quy giao diện.
 */
@Component({
  selector: 'vt-do-bieu-mau',
  imports: [VtFormComponent],
  template: `@if (show()) {
    <vt-form [schema]="f.schema" [ui]="f.ui" [rules]="f.rules" [value]="f.model" />
  }`,
})
class DoBieuMauHost {
  protected readonly f = bigForm();
  protected readonly show = signal(true);
  private readonly app = inject(ApplicationRef);
  private readonly host = inject(ElementRef<HTMLElement>);

  constructor() {
    (globalThis as Record<string, unknown>)['__doBieuMau'] = () => this.measure();
  }

  private async measure() {
    this.show.set(false);
    this.app.tick();
    await new Promise((r) => setTimeout(r, 50));
    const tasks: number[] = [];
    const obs = new PerformanceObserver((l) =>
      l.getEntries().forEach((e) => tasks.push(e.duration)),
    );
    obs.observe({ type: 'longtask' });
    const t0 = performance.now();
    this.show.set(true);
    this.app.tick();
    const first = performance.now() - t0;
    const count = () =>
      (this.host.nativeElement as HTMLElement).querySelectorAll('input, textarea, select').length;
    const firstCount = count();
    while (count() < BIG_FORM_FIELDS) await new Promise((r) => setTimeout(r, 1));
    const all = performance.now() - t0;
    await new Promise((r) => setTimeout(r, 50));
    obs.disconnect();
    return { first, firstCount, all, maxTask: Math.max(0, ...tasks) };
  }
}

export const DoNganSach: StoryObj<DoBieuMauHost> = {
  name: 'Đo ngân sách 50 trường',
  tags: ['!hoi-quy'],
  render: () => ({ moduleMetadata: { imports: [DoBieuMauHost] }, template: `<vt-do-bieu-mau />` }),
};
