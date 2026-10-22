import { JSONSchema7 } from '../lib/json-schema';
import { FormRule, FormUi } from '../lib/form-rule';

/**
 * Biểu mẫu thử 50 trường (5 nhóm, đủ năm loại widget, mỗi trường một quy tắc CEL) dùng cho
 * benchmark ngân sách TL-01 (WEB-010): `benchmark.spec.ts` trong jsdom và story đo trên trình duyệt.
 */
/** Số trường của biểu mẫu đo. */
export const BIG_FORM_FIELDS = 50;
const N = BIG_FORM_FIELDS;
const KINDS = ['text', 'score', 'textarea', 'date', 'catalog'] as const;

export function bigForm(): {
  schema: JSONSchema7;
  ui: FormUi;
  rules: FormRule[];
  model: Record<string, unknown>;
} {
  const properties: Record<string, JSONSchema7> = {};
  const widgets: Record<string, string> = {};
  const model: Record<string, unknown> = {};
  const rules: FormRule[] = [];
  for (let i = 0; i < N; i++) {
    const k = `f${i}`;
    const kind = KINDS[i % KINDS.length];
    if (kind === 'score') {
      properties[k] = { type: 'number', title: `Điểm ${i}`, minimum: 0, maximum: 100 };
      model[k] = 50;
      rules.push({
        id: `R${i}`,
        when: 'true',
        assert: `${k} >= 0.0 && ${k} <= 100.0`,
        message: 'x',
        field: k,
      });
    } else if (kind === 'catalog') {
      properties[k] = { type: 'string', title: `Mức ${i}`, enum: ['A', 'B', 'C', 'D'] };
      model[k] = 'A';
      rules.push({
        id: `R${i}`,
        when: `${k} == 'A'`,
        assert: `size(f${i - 2}) <= 2000`,
        message: 'x',
        field: k,
      });
    } else {
      properties[k] = {
        type: 'string',
        title: `Trường ${i}`,
        maxLength: kind === 'textarea' ? 2000 : 200,
      };
      if (kind === 'date') properties[k].format = 'date';
      model[k] = kind === 'date' ? '2027-01-04' : `Giá trị ${i}`;
      rules.push({
        id: `R${i}`,
        when: 'true',
        assert: `size(${k}) <= 2000`,
        message: 'x',
        field: k,
      });
    }
    if (kind === 'textarea') widgets[k] = 'vt.input.textarea';
  }
  const sections = Array.from({ length: 5 }, (_, s) => ({
    title: `Nhóm ${s + 1}`,
    fields: Object.keys(properties).slice(s * 10, s * 10 + 10),
  }));
  return { schema: { type: 'object', properties }, ui: { sections, widgets }, rules, model };
}
