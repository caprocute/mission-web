import { lintRules } from '@vt/form-engine';

const BUILTINS = new Set([
  'true',
  'false',
  'null',
  'in',
  'int',
  'uint',
  'double',
  'string',
  'bool',
  'bytes',
  'list',
  'map',
  'dyn',
  'type',
  'size',
  'has',
  'math',
  'duration',
  'timestamp',
]);

/** Cú pháp CEL: rỗng khi hợp lệ. Hàm riêng của máy chủ (cel-java) không kiểm ở trình duyệt. */
export function celSyntaxError(expr: string): string | null {
  if (!expr.trim()) return 'biểu thức đang để trống';
  const res = lintRules([{ id: 'x', when: 'true', assert: expr, message: '', serverOnly: true }]);
  return res.length ? 'sai cú pháp' : null;
}

/**
 * Biến dùng trong biểu thức mà phiên bản không khai báo (`manifest.variables`), không phải tên
 * quy tắc khác và không phải biến lặp của `map`/`filter`/`all`/`exists`. Chỉ để nhắc sớm; máy chủ
 * kiểm kiểu đầy đủ khi lưu.
 */
export function unknownVariables(
  expr: string,
  declared: readonly string[],
  ruleKeys: readonly string[],
): string[] {
  const code = expr.replace(/"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/g, '""');
  const bound = new Set(
    [...code.matchAll(/\.\s*(?:map|filter|all|exists|exists_one)\s*\(\s*([A-Za-z_]\w*)\s*,/g)].map(
      (m) => m[1],
    ),
  );
  const names = new Set(declared);
  const roots = new Set([...declared.map((d) => d.split('.')[0]), ...ruleKeys]);
  const out = new Set<string>();
  for (const m of code.matchAll(/(?<![.\w])([A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*)(\s*\()?/g)) {
    let path = m[1].replace(/\s+/g, '').split('.');
    if (m[2]) {
      if (path.length === 1) continue;
      path = path.slice(0, -1);
    }
    const root = path[0];
    if (BUILTINS.has(root) || bound.has(root)) continue;
    if (!roots.has(root)) {
      out.add(path.join('.'));
      continue;
    }
    const full = path.join('.');
    const namespaced = declared.some((d) => d.startsWith(root + '.'));
    if (path.length > 1 && namespaced && !names.has(full)) out.add(full);
  }
  return [...out];
}
