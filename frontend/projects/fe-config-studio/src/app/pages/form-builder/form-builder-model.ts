import { UiFormTestCaseInput, UiFormVersion, UiFormVersionUpdate } from '@vt/api-client';
import { FormRule, JSONSchema7, evaluateRuleOutcomes } from '@vt/form-engine';

/**
 * Logic thuần của SCR-1802 (QT-18): chuyển giữa danh sách trường của trình dựng và cặp
 * `schemaJson` (JSON Schema có `x-cel`, `x-classification`) với `uiJson` (nhãn, điều kiện hiển thị,
 * thông báo lỗi), dựng quy tắc cho bản xem trước và chạy bộ test trên trình duyệt (cel-js). Máy chủ
 * chạy lại bằng cel-java và là nguồn sự thật (BR-18-04, BR-18-05).
 */

export type FieldKind =
  | 'text'
  | 'textarea'
  | 'number'
  | 'date'
  | 'select'
  | 'multi'
  | 'file'
  | 'table'
  | 'group'
  | 'signature';

/** Mười loại trường được phép (TL-02b SCR-1802 vùng 1); không có trường nội dung tự do dạng mã. */
export const FIELD_KINDS: { kind: FieldKind; label: string; preview: boolean }[] = [
  { kind: 'text', label: 'Văn bản ngắn', preview: true },
  { kind: 'textarea', label: 'Văn bản dài', preview: true },
  { kind: 'number', label: 'Số', preview: true },
  { kind: 'date', label: 'Ngày', preview: true },
  { kind: 'select', label: 'Chọn một', preview: true },
  { kind: 'multi', label: 'Chọn nhiều', preview: false },
  { kind: 'file', label: 'Tệp minh chứng', preview: false },
  { kind: 'table', label: 'Bảng lặp', preview: false },
  { kind: 'group', label: 'Nhóm trường', preview: false },
  { kind: 'signature', label: 'Chữ ký số', preview: false },
];

export const kindLabel = (k: FieldKind) => FIELD_KINDS.find((x) => x.kind === k)?.label ?? k;

export interface FieldOption {
  value: string;
  label: string;
}

export interface BuilderField {
  key: string;
  kind: FieldKind;
  label: string;
  hint: string;
  required: boolean;
  /** Quy tắc kiểm tra CEL, biến `self` (giá trị trường) và `form` (cả biểu mẫu). */
  cel: string;
  message: string;
  /** Điều kiện hiển thị CEL; trống là luôn hiện. */
  showWhen: string;
  classification: 'C1' | 'C2';
  options: FieldOption[];
  /** Trường được bộ chính sách đánh giá dùng để tính điểm: không đổi kiểu, không xoá (BR-18-06). */
  scored: boolean;
}

export interface BuilderTest {
  caseId: string;
  input: Record<string, unknown>;
}

type Prop = Record<string, unknown>;

const JSON_TYPE: Record<FieldKind, string> = {
  text: 'string',
  textarea: 'string',
  number: 'number',
  date: 'string',
  select: 'string',
  multi: 'array',
  file: 'string',
  table: 'array',
  group: 'object',
  signature: 'string',
};

function kindOf(p: Prop): FieldKind {
  const k = p['x-kind'];
  if (typeof k === 'string' && FIELD_KINDS.some((x) => x.kind === k)) return k as FieldKind;
  if (p['type'] === 'number' || p['type'] === 'integer') return 'number';
  if (p['type'] === 'object') return 'group';
  if (p['type'] === 'array') return 'multi';
  if (p['oneOf'] || p['enum']) return 'select';
  if (p['format'] === 'date') return 'date';
  return Number(p['maxLength'] ?? 0) > 255 ? 'textarea' : 'text';
}

/** `hideExpression` lưu điều kiện ẩn; trình dựng hiện điều kiện hiển thị (phủ định của nó). */
export function showFromHide(hide: string): string {
  const m = /^!\((.*)\)$/s.exec(hide.trim());
  return m ? m[1] : hide.trim() ? `!(${hide.trim()})` : '';
}

export const hideFromShow = (show: string) => (show.trim() ? `!(${show.trim()})` : '');

export function fromVersion(f: UiFormVersion): { fields: BuilderField[]; tests: BuilderTest[] } {
  const schema = f.schemaJson as { properties?: Record<string, Prop>; required?: string[] };
  const ui = (f.uiJson ?? {}) as Record<string, Prop | undefined>;
  const required = new Set(schema.required ?? []);
  const fields = Object.entries(schema.properties ?? {}).map(([key, p]): BuilderField => {
    const u = ui[key] ?? {};
    const rule = f.celRules?.find((r) => r.field === key);
    const one = (p['oneOf'] as { const: string; title?: string }[] | undefined) ?? [];
    return {
      key,
      kind: kindOf(p),
      label: String(p['title'] ?? key),
      hint: String(p['description'] ?? ''),
      required: required.has(key),
      cel: String(p['x-cel'] ?? rule?.expr ?? ''),
      message: String(u['message'] ?? rule?.message ?? ''),
      showWhen: showFromHide(String(u['hideExpression'] ?? '')),
      classification: p['x-classification'] === 'C2' ? 'C2' : 'C1',
      options: one.map((o) => ({ value: o.const, label: o.title ?? o.const })),
      scored: p['x-scored'] === true,
    };
  });
  const tests = ((ui['x-test-cases'] as unknown as BuilderTest[] | undefined) ?? []).map((t) => ({
    caseId: t.caseId,
    input: { ...t.input },
  }));
  return { fields, tests };
}

function prop(f: BuilderField): Prop {
  const p: Prop = { type: JSON_TYPE[f.kind], title: f.label, 'x-kind': f.kind };
  if (f.hint) p['description'] = f.hint;
  if (f.kind === 'text') p['maxLength'] = 255;
  if (f.kind === 'textarea') p['maxLength'] = 2000;
  if (f.kind === 'date') p['format'] = 'date';
  if (f.kind === 'select') p['oneOf'] = f.options.map((o) => ({ const: o.value, title: o.label }));
  if (f.kind === 'multi') {
    p['items'] = { type: 'string', enum: f.options.map((o) => o.value) };
  }
  if (f.cel.trim()) p['x-cel'] = f.cel.trim();
  p['x-classification'] = f.classification;
  if (f.scored) p['x-scored'] = true;
  return p;
}

/** Thân `PUT /ui/forms/{key}/versions/{v}` từ danh sách trường và bộ test. */
export function toUpdate(
  base: UiFormVersion,
  fields: readonly BuilderField[],
  tests: readonly BuilderTest[],
): UiFormVersionUpdate {
  const ui: Record<string, unknown> = {};
  for (const f of fields) {
    const u: Prop = {};
    if (f.showWhen.trim()) u['hideExpression'] = hideFromShow(f.showWhen);
    if (f.message.trim()) u['message'] = f.message.trim();
    if (Object.keys(u).length) ui[f.key] = u;
  }
  ui['x-test-cases'] = tests;
  const required = fields.filter((f) => f.required).map((f) => f.key);
  return {
    schemaJson: {
      ...(base.schemaJson as Prop),
      type: 'object',
      required,
      properties: Object.fromEntries(fields.map((f) => [f.key, prop(f)])),
    },
    uiJson: ui,
    celRules: fields
      .filter((f) => f.cel.trim())
      .map((f) => ({
        field: f.key,
        expr: f.cel.trim(),
        message: f.message.trim() || 'Giá trị chưa hợp lệ',
        serverOnly: false,
      })),
  };
}

/** Khoá dữ liệu từ nhãn: chữ thường không dấu, nối bằng gạch dưới, ≤ 64 ký tự, không trùng. */
export function keyFromLabel(label: string, taken: readonly string[]): string {
  const base =
    label
      .normalize('NFD')
      .replace(/[̀-ͯ]/g, '')
      .replace(/đ/gi, 'd')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')
      .replace(/^_+|_+$/g, '')
      .slice(0, 60) || 'truong';
  let key = /^[a-z]/.test(base) ? base : `t_${base}`;
  for (let i = 2; taken.includes(key); i++) key = `${base}_${i}`;
  return key;
}

export const isValidKey = (k: string) => /^[a-z][a-z0-9_]{0,63}$/.test(k);

/** Đổi `self` thành khoá trường và bỏ tiền tố `form.` để quy tắc chạy trên mô hình phẳng của biểu mẫu. */
export function rewriteForModel(expr: string, key: string): string {
  return expr
    .split(/("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')/)
    .map((part, i) => (i % 2 ? part : part.replace(/\bform\./g, '').replace(/\bself\b/g, key)))
    .join('');
}

function check(expr: string, vars: Record<string, unknown>): 'PASS' | 'VIOLATED' | 'ERROR' {
  const r = evaluateRuleOutcomes([{ id: 'x', when: 'true', assert: expr, message: '' }], vars).get(
    'x',
  );
  return r === 'PASS' ? 'PASS' : r === 'VIOLATED' ? 'VIOLATED' : 'ERROR';
}

/** Trường đang hiện với dữ liệu `model` (điều kiện hiển thị đúng hoặc để trống). */
export function visibleKeys(
  fields: readonly BuilderField[],
  model: Record<string, unknown>,
): Set<string> {
  const form = Object.fromEntries(fields.map((f) => [f.key, model[f.key] ?? null]));
  return new Set(
    fields
      .filter((f) => !f.showWhen.trim() || check(f.showWhen, { form }) === 'PASS')
      .map((f) => f.key),
  );
}

const empty = (v: unknown) => v === undefined || v === null || v === '';

/** Lỗi trên trình duyệt của một bộ dữ liệu: bắt buộc chưa nhập, quy tắc CEL không đạt. */
export function browserErrors(
  fields: readonly BuilderField[],
  input: Record<string, unknown>,
): { field: string; message: string }[] {
  const shown = visibleKeys(fields, input);
  const form = Object.fromEntries(fields.map((f) => [f.key, input[f.key] ?? null]));
  const out: { field: string; message: string }[] = [];
  for (const f of fields) {
    if (!shown.has(f.key)) continue;
    const v = input[f.key];
    if (empty(v)) {
      if (f.required) out.push({ field: f.key, message: `${f.label}: chưa nhập` });
      continue;
    }
    if (f.cel.trim() && check(f.cel, { self: v, form }) !== 'PASS') {
      out.push({ field: f.key, message: f.message || `${f.label}: giá trị chưa hợp lệ` });
    }
  }
  return out;
}

/** Ca test gửi máy chủ kèm kết quả cel-js (BR-18-05). */
export function testInputs(
  fields: readonly BuilderField[],
  tests: readonly BuilderTest[],
): UiFormTestCaseInput[] {
  return tests.map((t) => {
    const errors = browserErrors(fields, t.input);
    return {
      caseId: t.caseId,
      input: t.input,
      browserValid: !errors.length,
      browserErrors: errors,
    };
  });
}

/** JSON Schema cho bản xem trước: trường hiện được trên trình duyệt, theo thứ tự của trình dựng. */
export function previewSchema(fields: readonly BuilderField[], shown: Set<string>): JSONSchema7 {
  const list = fields.filter(
    (f) => shown.has(f.key) && FIELD_KINDS.find((k) => k.kind === f.kind)?.preview,
  );
  return {
    type: 'object',
    required: list.filter((f) => f.required).map((f) => f.key),
    properties: Object.fromEntries(list.map((f) => [f.key, prop(f) as JSONSchema7])),
  } as JSONSchema7;
}

/** Quy tắc cho `<vt-form>` của bản xem trước (kiểm tra tức thời khi nhập). */
export function previewRules(fields: readonly BuilderField[], shown: Set<string>): FormRule[] {
  return fields
    .filter((f) => shown.has(f.key) && f.cel.trim())
    .map((f) => ({
      id: f.key,
      when: 'true',
      assert: rewriteForModel(f.cel, f.key),
      message: f.message || `${f.label}: giá trị chưa hợp lệ`,
      field: f.key,
    }));
}

/** Đo thời gian dựng lược đồ 50 trường (BR-18-08, ngân sách 50 ms). */
export function measure50(fields: readonly BuilderField[]): number {
  const many: BuilderField[] = Array.from({ length: 50 }, (_, i) => ({
    ...(fields[i % Math.max(fields.length, 1)] ?? FIELD_SAMPLE),
    key: `f_${i}`,
  }));
  const t0 = performance.now();
  const all = new Set(many.map((f) => f.key));
  previewSchema(many, all);
  previewRules(many, all);
  visibleKeys(many, {});
  return Math.round((performance.now() - t0) * 10) / 10;
}

const FIELD_SAMPLE: BuilderField = {
  key: 'x',
  kind: 'text',
  label: 'Trường',
  hint: '',
  required: false,
  cel: '',
  message: '',
  showWhen: '',
  classification: 'C1',
  options: [],
  scored: false,
};

export function newField(kind: FieldKind, taken: readonly string[]): BuilderField {
  const label = kindLabel(kind);
  return {
    ...FIELD_SAMPLE,
    key: keyFromLabel(label, taken),
    kind,
    label,
    classification: kind === 'textarea' ? 'C2' : 'C1',
    options:
      kind === 'select' || kind === 'multi'
        ? [
            { value: 'CO', label: 'Có' },
            { value: 'KHONG', label: 'Không' },
          ]
        : [],
  };
}
