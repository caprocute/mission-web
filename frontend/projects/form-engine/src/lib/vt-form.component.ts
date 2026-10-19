import { Component, computed, input, output, signal } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { FormlyFieldConfig, FormlyForm } from '@ngx-formly/core';
import { FormlyJsonschema } from '@ngx-formly/core/json-schema';
import { inject } from '@angular/core';
import { JSONSchema7 } from 'json-schema';
import { evaluateRules } from './cel-rule-evaluator';
import { FormRule, FormUi, RuleViolation } from './form-rule';

/**
 * Biểu mẫu động `@vt/form-engine` (ADR-05, lớp 3): JSON Schema từ Policy Pack,
 * phần trình bày từ UI Pack, kiểm tra CEL tức thì trên trình duyệt.
 */
@Component({
  selector: 'vt-form',
  imports: [ReactiveFormsModule, FormlyForm],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" class="vt-form">
      <formly-form [form]="form" [fields]="fields()" [model]="model" (modelChange)="onChange($event)" />
      @if (violations().length) {
        <ul class="vt-form__violations" role="alert">
          @for (v of violations(); track v.ruleId) {
            <li>{{ v.message }}</li>
          }
        </ul>
      }
      <ng-content />
    </form>
  `,
  styles: `.vt-form__violations { color: var(--p-red-600, #b91c1c); margin: 8px 0; padding-left: 20px; }`,
})
export class VtFormComponent {
  readonly schema = input.required<JSONSchema7>();
  readonly ui = input<FormUi>({});
  readonly rules = input<FormRule[]>([]);
  readonly submitted = output<Record<string, unknown>>();

  private readonly jsonschema = inject(FormlyJsonschema);
  protected readonly form = new FormGroup({});
  protected model: Record<string, unknown> = {};
  protected readonly violations = signal<RuleViolation[]>([]);

  protected readonly fields = computed<FormlyFieldConfig[]>(() => {
    const root = this.jsonschema.toFieldConfig(this.schema());
    const widgets = this.ui().widgets ?? {};
    for (const f of root.fieldGroup ?? []) {
      const w = widgets[String(f.key)];
      if (w === 'vt.input.textarea') f.type = 'textarea';
    }
    const sections = this.ui().sections;
    if (!sections?.length) return [root];
    const byKey = new Map((root.fieldGroup ?? []).map((f) => [String(f.key), f]));
    return sections.map((s) => ({
      wrappers: ['panel'],
      props: { label: s.title },
      fieldGroup: s.fields.map((k) => byKey.get(k)).filter((f): f is FormlyFieldConfig => !!f),
    }));
  });

  protected onChange(model: Record<string, unknown>): void {
    this.violations.set(evaluateRules(this.rules(), model));
  }

  protected submit(): void {
    const v = evaluateRules(this.rules(), this.model);
    this.violations.set(v);
    if (this.form.valid && v.length === 0) this.submitted.emit(this.model);
  }
}
