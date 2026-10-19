import { evaluateRules, lintRules } from './cel-rule-evaluator';
import { FormRule } from './form-rule';

/**
 * WEB-001: test mẫu cho luật CEL phía trình duyệt (TL-01 "Biểu mẫu động").
 * Kiểm tra ở trình duyệt chỉ để phản hồi nhanh; máy chủ quyết định cuối cùng (AGENTS.md quy tắc 6).
 */
const rules: FormRule[] = [
  {
    id: 'R1',
    when: 'true',
    assert: 'progress >= 0 && progress <= 100',
    message: 'Tiến độ phải trong khoảng 0–100%.',
  },
  {
    id: 'R2',
    when: 'progress == 100',
    assert: 'size(note) > 0',
    message: 'Cần ghi chú khi báo hoàn thành.',
  },
  { id: 'R3', when: 'true', assert: 'false', message: 'Chỉ máy chủ kiểm tra.', serverOnly: true },
];

describe('evaluateRules', () => {
  it('không có vi phạm khi dữ liệu hợp lệ', () => {
    expect(evaluateRules(rules, { progress: 50, note: '' })).toEqual([]);
  });

  it('trả vi phạm khi assert sai', () => {
    expect(evaluateRules(rules, { progress: 120, note: '' })).toEqual([
      { ruleId: 'R1', message: 'Tiến độ phải trong khoảng 0–100%.' },
    ]);
  });

  it('chỉ kiểm assert khi điều kiện when đúng', () => {
    expect(evaluateRules(rules, { progress: 100, note: '' }).map((v) => v.ruleId)).toEqual(['R2']);
    expect(evaluateRules(rules, { progress: 100, note: 'Đã xong' })).toEqual([]);
  });

  it('bỏ qua luật serverOnly', () => {
    expect(evaluateRules([rules[2]], {})).toEqual([]);
  });

  it('thiếu biến (trường chưa nhập) không coi là vi phạm', () => {
    expect(evaluateRules([rules[0]], {})).toEqual([]);
  });
});

describe('lintRules', () => {
  it('báo lỗi cú pháp theo mã luật', () => {
    const res = lintRules([
      ...rules,
      { id: 'BAD', when: 'true', assert: 'progress >=', message: 'x' },
    ]);
    expect(res.map((r) => r.ruleId)).toEqual(['BAD']);
    expect(res[0].errors.length).toBeGreaterThan(0);
  });
});
