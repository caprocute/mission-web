import { evaluate, parse } from 'cel-js';
import { FormRule, RuleViolation } from './form-rule';

/**
 * Chạy bộ quy tắc CEL của biểu mẫu trên trình duyệt để phản hồi tức thì.
 * Cùng bộ test case được chạy trên cel-java ở CI để bảo đảm hai engine cho cùng kết quả.
 */
export function evaluateRules(rules: FormRule[], model: Record<string, unknown>): RuleViolation[] {
  const violations: RuleViolation[] = [];
  for (const rule of rules) {
    if (rule.serverOnly) continue;
    try {
      if (evaluate(rule.when, model) !== true) continue;
      if (evaluate(rule.assert, model) !== true) violations.push({ ruleId: rule.id, message: rule.message });
    } catch {
      // trường chưa nhập (thiếu biến) không coi là vi phạm; máy chủ kiểm tra lại khi gửi
    }
  }
  return violations;
}

/** Kiểm tra cú pháp toàn bộ quy tắc, dùng ở Config Studio (SCR-1802) trước khi lưu. */
export function lintRules(rules: FormRule[]): { ruleId: string; errors: string[] }[] {
  return rules
    .flatMap((r) => [r.when, r.assert].map((e) => ({ id: r.id, res: parse(e) })))
    .filter((x) => !x.res.isSuccess)
    .map((x) => ({ ruleId: x.id, errors: (x.res as { errors: string[] }).errors }));
}
