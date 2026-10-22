import {
  ChangeDetectionStrategy,
  Component,
  forwardRef,
  Injector,
  afterNextRender,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  output,
  PendingTasks,
  signal,
  untracked,
  ViewEncapsulation,
  DestroyRef,
} from '@angular/core';
import { AbstractControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { FormlyFieldConfig, FormlyForm, FormlyFormOptions } from '@ngx-formly/core';
import { FormlyJsonschema } from '@ngx-formly/core/json-schema';
import { JSONSchema7 } from './json-schema';
import { CelFunctions, evaluateRules } from './cel-rule-evaluator';
import { FormRule, FormUi, RuleViolation, ServerFieldError } from './form-rule';
import { WIDGET_TYPES, formEngineProviders } from './provide-form-engine';
import { SectionReveal, VT_SECTION_REVEAL } from './fields/field-section';

type SchemaExt = JSONSchema7 & { 'x-catalog'?: string };

/**
 * Biểu mẫu từ chừng này trường trở lên thì dựng dần theo nhóm (TL-01 ngân sách dựng 50 trường
 * ≤ 50 ms, wiki/09): lượt vẽ đầu chỉ có nhóm đầu tiên, mỗi nhóm sau một tác vụ riêng.
 */
export const PROGRESSIVE_MIN_FIELDS = 20;

/** Đường dẫn khoá của trường tính từ gốc biểu mẫu, ví dụ `tieuChiChung.tc1`. */
export function fieldPath(field: FormlyFieldConfig): string {
  const keys: string[] = [];
  for (let f: FormlyFieldConfig | undefined = field; f; f = f.parent) {
    if (f.key !== undefined && f.key !== null) keys.unshift(String(f.key));
  }
  return keys.join('.');
}

/** Gán `defaultValue` của trường cho những chỗ mô hình chưa có giá trị (kể cả trường lồng nhau). */
function applyDefaults(
  fields: FormlyFieldConfig[] | undefined,
  model: Record<string, unknown>,
): void {
  for (const f of fields ?? []) {
    if (f.key === undefined || f.key === null) {
      applyDefaults(f.fieldGroup, model);
      continue;
    }
    const key = String(f.key);
    if (f.fieldGroup?.length) {
      const child = (model[key] ??= {}) as Record<string, unknown>;
      applyDefaults(f.fieldGroup, child);
    } else if (model[key] === undefined && f.defaultValue !== undefined) {
      model[key] = structuredClone(f.defaultValue);
    }
  }
}

/** Kiểu trường mặc định khi UI Pack không chỉ định widget. */
function defaultType(field: FormlyFieldConfig, schema: SchemaExt): string | undefined {
  if (field.type === 'enum') return 'vt-catalog';
  switch (field.type) {
    case 'string':
      if (schema.format === 'date') return 'vt-date';
      return (schema.maxLength ?? 0) > 255 ? 'vt-textarea' : 'vt-text';
    case 'number':
    case 'integer':
      return 'vt-score';
    case 'boolean':
      return 'vt-checkbox';
    case 'object':
      // nhóm trường lồng nhau: để Formly dựng formly-group
      return undefined;
    default:
      return field.type as string | undefined;
  }
}

/**
 * Biểu mẫu động `@vt/form-engine` (ADR-05 lớp 3, TL-01 "Biểu mẫu động"): JSON Schema của
 * Policy Pack, phần `ui` của UI Pack (nhóm trường, widget) và quy tắc CEL kiểm tra tức thì.
 * Vi phạm gắn vào trường qua `FormRule.field`; lỗi máy chủ (`serverErrors`) hiện ở đúng trường
 * cho tới khi người dùng sửa giá trị. Máy chủ luôn kiểm tra lại (AGENTS.md quy tắc 6).
 */
@Component({
  selector: 'vt-form',
  imports: [ReactiveFormsModule, FormlyForm],
  // Formly chỉ được đăng ký khi có <vt-form>, shell không phải tải sẵn (WEB-052)
  providers: [
    formEngineProviders(),
    { provide: VT_SECTION_REVEAL, useExisting: forwardRef(() => VtFormComponent) },
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" class="vt-form" novalidate>
      @if (attempted() && summary().length) {
        <div class="vt-form__summary" role="alert" tabindex="-1">
          <p>Biểu mẫu còn {{ summary().length }} lỗi cần sửa:</p>
          <ul>
            @for (v of summary(); track v.ruleId) {
              <li>
                @if (v.field) {
                  <a [href]="'#' + v.anchor" (click)="focusField($event, v.field)">{{
                    v.message
                  }}</a>
                } @else {
                  {{ v.message }}
                }
              </li>
            }
          </ul>
        </div>
      }
      <formly-form
        [form]="form"
        [fields]="fields()"
        [model]="modelRef()"
        [options]="options"
        (modelChange)="onChange($event)"
      />
      <ng-content />
    </form>
  `,
  styleUrl: './vt-form.component.css',
  // một bảng kiểu chung cho mọi widget và khung trường bên trong vt-form (lớp đều có tiền tố vt-)
  encapsulation: ViewEncapsulation.None,
})
export class VtFormComponent implements SectionReveal {
  readonly schema = input.required<JSONSchema7>();
  readonly ui = input<FormUi>({});
  readonly rules = input<readonly FormRule[]>([]);
  /** Ngữ cảnh chỉ đọc cho quy tắc (`ctx.*`), ví dụ mức hệ thống tham khảo. */
  readonly context = input<Record<string, unknown>>({});
  readonly functions = input<CelFunctions>({});
  /** Giá trị ban đầu (bản nháp đã lưu). */
  readonly value = input<Record<string, unknown>>({});
  /** Lỗi theo trường máy chủ trả (Problem Details `errors[]`). */
  readonly serverErrors = input<readonly ServerFieldError[]>([]);
  readonly readonly = input(false);

  readonly submitted = output<Record<string, unknown>>();
  readonly valueChange = output<Record<string, unknown>>();

  private readonly jsonschema = inject(FormlyJsonschema);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly injector = inject(Injector);
  protected readonly form = new FormGroup({});
  /**
   * Mô hình Formly sửa tại chỗ. Tạo lại (kèm giá trị mặc định) chỉ khi `value` hoặc schema đổi, và
   * có sẵn ngay ở lần vẽ đầu để Formly không phải dựng biểu mẫu hai lần.
   */
  protected readonly modelRef = computed(() => {
    const model = structuredClone(this.value());
    applyDefaults(this.fields(), model);
    return model;
  });
  private get model(): Record<string, unknown> {
    return this.modelRef();
  }
  protected readonly options: FormlyFormOptions = {};
  /** Vi phạm quy tắc CEL hiện tại (cập nhật mỗi lần nhập). */
  readonly violations = signal<RuleViolation[]>([]);
  protected readonly attempted = signal(false);
  private readonly ruleErrors = new Map<string, string>();
  private readonly serverErrorMap = new Map<string, { message: string; value: unknown }>();

  protected readonly fields = computed<FormlyFieldConfig[]>(() => {
    const widgets = this.ui().widgets ?? {};
    const validator = (control: AbstractControl, field: FormlyFieldConfig) =>
      this.validateField(control, field);
    // Chỉ đọc: khoá từng trường (gốc của toFieldConfig không có điều khiển nên khoá ở gốc không có tác
    // dụng); đổi `readonly` thì dựng lại các trường với giá trị `value` hiện tại
    const readonly = this.readonly();
    const root = this.jsonschema.toFieldConfig(this.schema(), {
      map: (field: FormlyFieldConfig, s: JSONSchema7) => {
        const schema = s as SchemaExt;
        const key = field.key !== undefined ? String(field.key) : '';
        const widget = key ? widgets[key] : undefined;
        field.type = (widget && WIDGET_TYPES[widget]) || defaultType(field, schema);
        // Chuỗi và hộp đánh dấu luôn có giá trị (rỗng, false) để quy tắc CEL thấy trường chưa nhập
        // và bản gửi máy chủ đủ trường, hai engine đánh giá trên cùng dữ liệu (wiki/03)
        if (key && field.defaultValue === undefined) {
          if (
            field.type === 'vt-text' ||
            field.type === 'vt-textarea' ||
            field.type === 'vt-date'
          ) {
            field.defaultValue = '';
            // FormlyJsonschema đổi '' thành undefined cho trường không bắt buộc: bỏ để mô hình giữ ''
            field.parsers = [];
          }
          if (field.type === 'vt-checkbox') field.defaultValue = false;
        }
        if (schema.multipleOf) field.props = { ...field.props, step: schema.multipleOf };
        if (schema['x-catalog']) field.props = { ...field.props, catalog: schema['x-catalog'] };
        if (key) {
          if (readonly) field.props = { ...field.props, disabled: true };
          field.id = `vt-f-${key}`;
          field.validators = { ...field.validators, validation: [validator] };
        }
        return field;
      },
    });
    const sections = this.ui().sections;
    if (!sections?.length) return [root];
    const children = root.fieldGroup ?? [];
    const byKey = new Map(children.map((f) => [String(f.key), f]));
    const used = new Set(sections.flatMap((s) => s.fields));
    const groups: FormlyFieldConfig[] = sections.map((s, sectionIndex) => ({
      type: 'vt-section-group',
      props: { label: s.title, sectionIndex },
      fieldGroup: s.fields.map((k) => byKey.get(k)).filter((f): f is FormlyFieldConfig => !!f),
    }));
    // Trường có trong schema mà UI Pack không xếp vào nhóm nào vẫn phải hiện (không giấu trường bắt buộc)
    const rest = children.filter((f) => !used.has(String(f.key)));
    if (rest.length) groups.push({ fieldGroup: rest });
    return groups;
  });

  /**
   * Số nhóm trường đã dựng. Biểu mẫu nhỏ hoặc không chia nhóm dựng hết ngay; biểu mẫu lớn bắt đầu
   * với nhóm đầu, `revealNext` thêm từng nhóm sau mỗi lượt vẽ. Đổi schema, ui thì bắt đầu lại.
   */
  private readonly shownSections = linkedSignal(() => {
    const groups = this.fields();
    const sectioned = groups.filter((g) => g.type === 'vt-section-group').length;
    return sectioned > 1 && countLeaves(groups) >= PROGRESSIVE_MIN_FIELDS ? 1 : Infinity;
  });
  private readonly pendingTasks = inject(PendingTasks);
  private revealPending: (() => void) | null = null;
  private revealTimer: ReturnType<typeof setTimeout> | undefined;

  sectionVisible(index: number): boolean {
    return index < this.shownSections();
  }

  /** Dựng ngay mọi nhóm còn lại (trước khi gửi, khi cần đưa focus tới một trường). */
  private revealAll(): void {
    this.shownSections.set(Infinity);
  }

  /** Trường sai theo schema (bắt buộc, độ dài, khoảng giá trị) ở lần bấm gửi gần nhất. */
  private readonly invalidFields = signal<{ path: string; message: string }[]>([]);

  /** Danh sách lỗi đầu biểu mẫu khi bấm gửi: lỗi schema, vi phạm quy tắc và lỗi máy chủ. */
  protected readonly summary = computed(() => {
    const items = this.invalidFields().map((f) => ({
      ruleId: `schema:${f.path}`,
      message: f.message,
      field: f.path as string | undefined,
      anchor: this.anchorOf(f.path),
    }));
    items.push(
      ...this.violations().map((v) => ({
        ruleId: v.ruleId,
        message: v.message,
        field: v.field,
        anchor: v.field ? this.anchorOf(v.field) : '',
      })),
    );
    for (const e of this.serverErrorsSignal()) {
      items.push({
        ruleId: `server:${e.field}`,
        message: e.message,
        field: e.field,
        anchor: this.anchorOf(e.field),
      });
    }
    return items;
  });
  private readonly serverErrorsSignal = signal<readonly ServerFieldError[]>([]);

  constructor() {
    effect(() => {
      const model = this.modelRef();
      // ngữ cảnh (`ctx.*`) hay bộ quy tắc đổi thì quy tắc cũng phải đánh giá lại
      this.context();
      this.rules();
      untracked(() => this.recheck(model));
    });
    effect(() => {
      const errors = this.serverErrors();
      untracked(() => this.applyServerErrors(errors));
    });
    // Dựng dần: sau mỗi lượt vẽ, thêm một nhóm ở tác vụ kế tiếp; giữ PendingTasks để `whenStable`
    // (kiểm thử, chụp ảnh) chờ tới khi đủ mọi nhóm
    effect(() => {
      const shown = this.shownSections();
      const total = this.fields().length;
      clearTimeout(this.revealTimer);
      if (shown >= total) {
        this.revealPending?.();
        this.revealPending = null;
        return;
      }
      this.revealPending ??= this.pendingTasks.add();
      afterNextRender(
        () => {
          this.revealTimer = setTimeout(() =>
            this.shownSections.update((n) => (n === shown ? n + 1 : n)),
          );
        },
        { injector: this.injector },
      );
    });
    inject(DestroyRef).onDestroy(() => {
      clearTimeout(this.revealTimer);
      this.revealPending?.();
    });
  }

  protected onChange(model: Record<string, unknown>): void {
    this.recheck(model);
    if (this.attempted()) this.invalidFields.set(this.collectInvalid(this.fields()));
    this.valueChange.emit(model);
  }

  protected submit(): void {
    this.revealAll();
    this.attempted.set(true);
    this.recheck();
    this.form.markAllAsTouched();
    this.invalidFields.set(this.collectInvalid(this.fields()));
    if (this.form.valid && this.violations().length === 0) {
      const out = structuredClone(this.model);
      applyDefaults(this.fields(), out);
      this.submitted.emit(out);
      return;
    }
    // đưa focus tới danh sách lỗi sau khi nó được vẽ (WCAG 3.3.1)
    afterNextRender(
      () =>
        (this.host.nativeElement.querySelector('.vt-form__summary') as HTMLElement | null)?.focus(),
      { injector: this.injector },
    );
  }

  protected focusField(ev: Event, path: string): void {
    ev.preventDefault();
    const focus = () =>
      (
        this.host.nativeElement.querySelector(
          `#${CSS.escape(this.anchorOf(path))}`,
        ) as HTMLElement | null
      )?.focus();
    if (this.shownSections() >= this.fields().length) {
      focus();
      return;
    }
    this.revealAll();
    afterNextRender(focus, { injector: this.injector });
  }

  /** Trường lá có lỗi schema (không tính lỗi quy tắc, lỗi máy chủ đã có trong danh sách). */
  private collectInvalid(fields: FormlyFieldConfig[]): { path: string; message: string }[] {
    const out: { path: string; message: string }[] = [];
    const walk = (list: FormlyFieldConfig[] | undefined) => {
      for (const f of list ?? []) {
        if (f.fieldGroup?.length) {
          walk(f.fieldGroup);
          continue;
        }
        const errors = f.formControl?.errors;
        if (!errors || f.key === undefined) continue;
        const keys = Object.keys(errors).filter((k) => k !== 'rule' && k !== 'server');
        if (!keys.length) continue;
        const label = f.props?.label ?? String(f.key);
        out.push({
          path: fieldPath(f),
          message: keys.includes('required')
            ? `${label}: chưa nhập`
            : `${label}: giá trị chưa hợp lệ`,
        });
      }
    };
    walk(fields);
    return out;
  }

  private anchorOf(path: string): string {
    return `vt-f-${path.split('.').pop()}`;
  }

  /** Đánh giá lại quy tắc CEL và cập nhật lỗi của những trường có trạng thái thay đổi. */
  private recheck(model = this.model): void {
    const violations = evaluateRules(this.rules(), model, {
      context: this.context(),
      functions: this.functions(),
    });
    this.violations.set(violations);
    const next = new Map<string, string>();
    for (const v of violations) if (v.field && !next.has(v.field)) next.set(v.field, v.message);
    const changed = new Set([...this.ruleErrors.keys(), ...next.keys()]);
    this.ruleErrors.clear();
    next.forEach((m, k) => this.ruleErrors.set(k, m));
    for (const path of changed) this.form.get(path)?.updateValueAndValidity({ emitEvent: false });
    if (changed.size) this.refreshView();
  }

  /** Vẽ lại các trường (OnPush) sau khi đổi lỗi mà không phát sự kiện giá trị. */
  private refreshView(): void {
    for (const f of this.fields()) if (f.options) this.options.detectChanges?.(f);
  }

  private applyServerErrors(errors: readonly ServerFieldError[]): void {
    const touched = new Set(this.serverErrorMap.keys());
    this.serverErrorMap.clear();
    const unmapped: ServerFieldError[] = [];
    for (const e of errors) {
      const control = this.form.get(e.field);
      if (control) {
        this.serverErrorMap.set(e.field, { message: e.message, value: control.value });
        touched.add(e.field);
      } else {
        unmapped.push(e);
      }
    }
    this.serverErrorsSignal.set(errors);
    if (errors.length) this.attempted.set(true);
    for (const path of touched) {
      const c = this.form.get(path);
      c?.markAsTouched();
      c?.updateValueAndValidity({ emitEvent: false });
    }
    if (touched.size) this.refreshView();
    if (unmapped.length) console.warn('[form-engine] Lỗi máy chủ không khớp trường', unmapped);
  }

  private validateField(control: AbstractControl, field: FormlyFieldConfig) {
    const path = fieldPath(field);
    const server = this.serverErrorMap.get(path);
    if (server && server.value !== control.value) {
      // người dùng đã sửa: bỏ lỗi máy chủ của trường này
      this.serverErrorMap.delete(path);
      this.serverErrorsSignal.update((list) => list.filter((e) => e.field !== path));
    }
    const s = this.serverErrorMap.get(path);
    if (s) return { server: { message: s.message } };
    const rule = this.ruleErrors.get(path);
    return rule ? { rule: { message: rule } } : null;
  }
}

/** Số trường lá (có điều khiển nhập) trong cây trường. */
function countLeaves(fields: FormlyFieldConfig[] | undefined): number {
  let n = 0;
  for (const f of fields ?? []) n += f.fieldGroup?.length ? countLeaves(f.fieldGroup) : 1;
  return n;
}
