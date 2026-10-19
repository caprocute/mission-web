import { CelFunctions, evaluateRuleOutcomes } from './cel-rule-evaluator';
import { FormRule } from './form-rule';

/**
 * Bộ test vàng của một biểu mẫu, dùng chung cho cel-js (trình duyệt) và cel-java (máy chủ)
 * theo TL-01 "Biểu mẫu động": mỗi ca gồm dữ liệu nhập, ngữ cảnh và danh sách quy tắc vi phạm
 * mong đợi khi chạy TOÀN BỘ quy tắc (kể cả `serverOnly`). Định dạng xem wiki/03.
 *
 * Quy ước chung cho hai engine: thiếu trường (chưa nhập) thì quy tắc không bị coi là vi phạm.
 */
export interface GoldenSuite {
  /** Mã biểu mẫu trong Policy Pack, ví dụ `mau-1b`. */
  form: string;
  /** Phiên bản biểu mẫu mà bộ test áp dụng. */
  version: string;
  cases: GoldenCase[];
}

export interface GoldenCase {
  id: string;
  description: string;
  model: Record<string, unknown>;
  /** Ngữ cảnh chỉ đọc (`ctx.*`), ví dụ mức hệ thống tham khảo. */
  context?: Record<string, unknown>;
  /** Mã quy tắc vi phạm mong đợi, thứ tự không quan trọng. */
  violated: string[];
}

export interface GoldenResult {
  caseId: string;
  ok: boolean;
  /** Mong đợi vi phạm nhưng trình duyệt không thấy. */
  missing: string[];
  /** Trình duyệt thấy vi phạm nhưng không mong đợi. */
  unexpected: string[];
  /** Quy tắc `serverOnly` không chạy được trên trình duyệt (chỉ cel-java kiểm). */
  skipped: string[];
}

/**
 * Chạy bộ test vàng trên cel-js. Quy tắc `serverOnly` bị bỏ qua (trừ khi truyền đủ hàm qua
 * `functions` và `includeServerOnly`), nên kỳ vọng về chúng không làm ca bị trượt ở trình duyệt.
 */
export function runGoldenSuite(
  rules: readonly FormRule[],
  suite: GoldenSuite,
  options: { functions?: CelFunctions; includeServerOnly?: boolean } = {},
): GoldenResult[] {
  return suite.cases.map((c) => {
    const outcomes = evaluateRuleOutcomes(rules, c.model, { ...options, context: c.context });
    const skipped = rules.filter((r) => outcomes.get(r.id) === 'SKIPPED').map((r) => r.id);
    const actual = rules.filter((r) => outcomes.get(r.id) === 'VIOLATED').map((r) => r.id);
    const expected = c.violated.filter((id) => !skipped.includes(id));
    // mã không có trong bộ quy tắc cũng rơi vào missing: test vàng lệch với Policy Pack
    const missing = expected.filter((id) => !actual.includes(id));
    const unexpected = actual.filter((id) => !c.violated.includes(id));
    return {
      caseId: c.id,
      ok: !missing.length && !unexpected.length,
      missing,
      unexpected,
      skipped,
    };
  });
}
