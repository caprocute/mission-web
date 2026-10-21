import { evaluate, parse } from 'cel-js';
import { FormRule, RuleViolation } from './form-rule';

export * from './mission-cel';

type Cst = Extract<ReturnType<typeof parse>, { isSuccess: true }>['cst'];

/** Hàm mở rộng dùng trong biểu thức (ví dụ `workdays`), hiện thực song song với cel-java. */
export type CelFunctions = Record<string, (...args: never[]) => unknown>;

export interface EvaluateOptions {
  /** Ngữ cảnh chỉ đọc, truy cập bằng `ctx.<tên>` (ví dụ `ctx.mucThamKhao`). */
  context?: Record<string, unknown>;
  functions?: CelFunctions;
  /** Kiểm cả quy tắc `serverOnly` (chỉ dùng khi đã cung cấp đủ hàm, ví dụ trong test vàng). */
  includeServerOnly?: boolean;
}

/** Kết quả của một quy tắc: không áp dụng, đạt, vi phạm, hoặc chưa đánh giá được. */
export type RuleOutcome = 'NOT_APPLICABLE' | 'PASS' | 'VIOLATED' | 'MISSING_INPUT' | 'SKIPPED';

/** Hàm có sẵn trong cel-js 0.8 dạng gọi tự do (không phải macro trên danh sách). */
const BUILTIN_FUNCTIONS = new Set(['size', 'has']);
/** Macro trên danh sách hoặc map, gọi dạng `ds.exists(x, ...)`. */
const COLLECTION_MACROS = new Set(['filter', 'map', 'all', 'exists', 'exists_one']);

const compiled = new Map<string, Cst | Error>();

/** Phân tích một lần rồi giữ cây cú pháp, các lần đánh giá sau không phân tích lại. */
function compile(expr: string): Cst {
  let c = compiled.get(expr);
  if (!c) {
    const res = parse(expr);
    c = res.isSuccess ? res.cst : new Error(res.errors.join('; '));
    compiled.set(expr, c);
  }
  if (c instanceof Error) throw c;
  return c;
}

/**
 * Thiếu biến (trường chưa nhập) thì cel-js báo `Identifier "x" not found`: không coi là vi phạm vì
 * máy chủ kiểm tra lại khi gửi. Lỗi khác (hàm không có, sai kiểu) là lỗi cấu hình quy tắc.
 */
function isMissingInput(err: unknown): boolean {
  return err instanceof Error && /Identifier ".*" not found/.test(err.message);
}

function run(expr: string, vars: Record<string, unknown>, fns?: CelFunctions): unknown {
  return evaluate(compile(expr), vars, fns as Record<string, CallableFunction> | undefined);
}

/** Đánh giá từng quy tắc, trả kết quả chi tiết (dùng cho test vàng và Config Studio). */
export function evaluateRuleOutcomes(
  rules: readonly FormRule[],
  model: Record<string, unknown>,
  options: EvaluateOptions = {},
): Map<string, RuleOutcome> {
  const vars = options.context ? { ...model, ctx: options.context } : model;
  const out = new Map<string, RuleOutcome>();
  for (const rule of rules) {
    if (rule.serverOnly && !options.includeServerOnly) {
      out.set(rule.id, 'SKIPPED');
      continue;
    }
    try {
      if (run(rule.when, vars, options.functions) !== true) {
        out.set(rule.id, 'NOT_APPLICABLE');
        continue;
      }
      out.set(rule.id, run(rule.assert, vars, options.functions) === true ? 'PASS' : 'VIOLATED');
    } catch (err) {
      if (!isMissingInput(err)) {
        console.warn(`[form-engine] Quy tắc ${rule.id} lỗi khi đánh giá:`, (err as Error).message);
      }
      out.set(rule.id, 'MISSING_INPUT');
    }
  }
  return out;
}

/**
 * Chạy bộ quy tắc CEL của biểu mẫu trên trình duyệt để phản hồi tức thì.
 * Cùng bộ test vàng được chạy trên cel-java ở CI để bảo đảm hai engine cho cùng kết quả;
 * máy chủ luôn kiểm tra lại và là nguồn sự thật (AGENTS.md quy tắc 6).
 */
export function evaluateRules(
  rules: readonly FormRule[],
  model: Record<string, unknown>,
  options: EvaluateOptions = {},
): RuleViolation[] {
  const outcomes = evaluateRuleOutcomes(rules, model, options);
  return rules
    .filter((r) => outcomes.get(r.id) === 'VIOLATED')
    .map((r) =>
      r.field
        ? { ruleId: r.id, message: r.message, field: r.field }
        : { ruleId: r.id, message: r.message },
    );
}

/** Tên hàm được gọi trong biểu thức (bỏ chuỗi ký tự để không bắt nhầm). */
export function calledFunctions(expr: string): { name: string; method: boolean }[] {
  const code = expr.replace(/"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/g, '""');
  return [...code.matchAll(/(\.)?\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(/g)].map((m) => ({
    name: m[2],
    method: !!m[1],
  }));
}

/**
 * Kiểm tra bộ quy tắc trước khi lưu (Config Studio SCR-1802) và trong test: cú pháp, mã trùng,
 * và hàm chưa có trên trình duyệt mà quy tắc không đánh dấu `serverOnly`.
 */
export function lintRules(
  rules: readonly FormRule[],
  functions: CelFunctions = {},
): { ruleId: string; errors: string[] }[] {
  const seen = new Set<string>();
  const result: { ruleId: string; errors: string[] }[] = [];
  for (const r of rules) {
    const errors: string[] = [];
    if (seen.has(r.id)) errors.push(`Mã quy tắc ${r.id} bị trùng`);
    seen.add(r.id);
    for (const expr of [r.when, r.assert]) {
      const res = parse(expr);
      if (!res.isSuccess) {
        errors.push(...res.errors);
        continue;
      }
      if (r.serverOnly) continue;
      for (const f of calledFunctions(expr)) {
        const known = f.method
          ? COLLECTION_MACROS.has(f.name)
          : BUILTIN_FUNCTIONS.has(f.name) || f.name in functions;
        if (!known) {
          errors.push(
            `Hàm ${f.name}() chưa có trên trình duyệt (cel-js); đánh dấu serverOnly hoặc bổ sung hàm`,
          );
        }
      }
    }
    if (errors.length) result.push({ ruleId: r.id, errors });
  }
  return result;
}
