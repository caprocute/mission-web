import { evaluateRuleOutcomes, evaluateRules, lintRules } from './cel-rule-evaluator';
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

describe('evaluateRules với ngữ cảnh, hàm mở rộng và trường đích (WEB-010)', () => {
  const r: FormRule[] = [
    {
      id: 'C1',
      when: 'muc != ctx.mucThamKhao',
      assert: 'size(giaiTrinh) >= 20',
      message: 'Cần giải trình',
      field: 'giaiTrinh',
    },
    { id: 'F1', when: 'true', assert: 'workdays(a, b) <= 3', message: 'Quá hạn' },
  ];

  it('đọc ngữ cảnh qua ctx.* và gắn trường đích vào vi phạm', () => {
    expect(
      evaluateRules([r[0]], { muc: 'A', giaiTrinh: '' }, { context: { mucThamKhao: 'B' } }),
    ).toEqual([{ ruleId: 'C1', message: 'Cần giải trình', field: 'giaiTrinh' }]);
    expect(
      evaluateRules([r[0]], { muc: 'B', giaiTrinh: '' }, { context: { mucThamKhao: 'B' } }),
    ).toEqual([]);
  });

  it('hàm mở rộng truyền qua functions; thiếu hàm thì không báo vi phạm và ghi cảnh báo', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const workdays = (a: number, b: number) => b - a;
    expect(
      evaluateRules([r[1]], { a: 1, b: 9 }, { functions: { workdays } }).map((v) => v.ruleId),
    ).toEqual(['F1']);
    expect(evaluateRules([r[1]], { a: 1, b: 9 })).toEqual([]);
    expect(warn).toHaveBeenCalledWith(
      expect.stringContaining('F1'),
      expect.stringContaining('workdays'),
    );
    warn.mockRestore();
  });

  it('kết quả chi tiết từng quy tắc', () => {
    const out = evaluateRuleOutcomes([...rules, r[0]], { progress: 100, note: 'x' });
    expect(Object.fromEntries(out)).toEqual({
      R1: 'PASS',
      R2: 'PASS',
      R3: 'SKIPPED',
      C1: 'MISSING_INPUT',
    });
  });
});

describe('lintRules: hàm chưa có trên trình duyệt (WEB-010)', () => {
  it('báo hàm lạ khi quy tắc không serverOnly; macro danh sách và size() hợp lệ', () => {
    const res = lintRules([
      { id: 'A', when: 'true', assert: 'workdays(a, b) <= 3', message: '' },
      { id: 'B', when: 'true', assert: 'workdays(a, b) <= 3', message: '', serverOnly: true },
      { id: 'C', when: 'true', assert: 'ds.exists(x, x > 1) && size(s) > 0', message: '' },
      { id: 'D', when: 'true', assert: 's.matches("^[0-9]+$")', message: '' },
      { id: 'E', when: 'true', assert: 'size("f(x)") > 0', message: '' },
    ]);
    expect(res.map((x) => x.ruleId)).toEqual(['A', 'D']);
    expect(res[0].errors[0]).toContain('workdays');
    expect(
      lintRules([{ id: 'A', when: 'true', assert: 'workdays(a, b) <= 3', message: '' }], {
        workdays: () => 0,
      }),
    ).toEqual([]);
  });

  it('báo mã quy tắc trùng', () => {
    expect(lintRules([rules[0], rules[0]])[0].errors).toContain('Mã quy tắc R1 bị trùng');
  });
});

describe('giới hạn của cel-js 0.8 cần biết khi viết quy tắc (wiki/03)', () => {
  it('phép chia số nguyên cho số thực (CEL chuẩn và cel-java cho 3): dùng 7.0 / 2.0 khi cần', () => {
    expect(
      evaluateRuleOutcomes(
        [{ id: 'X', when: 'true', assert: '7 / 2 == 3.5', message: '' }],
        {},
      ).get('X'),
    ).toBe('PASS');
  });

  it('không có cú pháp phương thức trên chuỗi (s.size(), startsWith, matches)', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(
      evaluateRuleOutcomes([{ id: 'X', when: 'true', assert: 's.size() > 0', message: '' }], {
        s: 'a',
      }).get('X'),
    ).toBe('MISSING_INPUT');
    warn.mockRestore();
  });
});
