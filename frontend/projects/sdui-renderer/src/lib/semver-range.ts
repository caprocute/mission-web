/**
 * Kiểm phiên bản theo dải semver tối giản cho `requires.registry` của UI Pack (WEB-009):
 * hỗ trợ `^`, `~`, `>=`, `>`, `<=`, `<`, `=`, phiên bản đầy đủ hoặc thiếu (`2`, `2.4`, `2.x`),
 * `*`, nhiều điều kiện cách nhau bằng dấu cách (và) và `||` (hoặc). Dải không đọc được là không khớp.
 */
type Ver = [number, number, number];

const PART = /^(\d+|[xX*])(?:\.(\d+|[xX*]))?(?:\.(\d+|[xX*]))?$/;

function parseVersion(v: string): Ver | null {
  const m = /^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$/.exec(v.trim());
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
}

function cmp(a: Ver, b: Ver): number {
  return a[0] - b[0] || a[1] - b[1] || a[2] - b[2];
}

/** Đổi một điều kiện thành cặp [cận dưới bao gồm, cận trên không bao gồm]; null là không đọc được. */
function bounds(
  comparator: string,
): { min?: Ver; minEx?: boolean; max?: Ver; maxIn?: boolean } | null {
  const m = /^(\^|~|>=|<=|>|<|=)?\s*v?(.+)$/.exec(comparator);
  if (!m) return null;
  const op = m[1] ?? '';
  const p = PART.exec(m[2].replace(/[-+].*$/, ''));
  if (!p) return null;
  const nums = [p[1], p[2], p[3]].map((s) =>
    s === undefined || /[xX*]/.test(s) ? null : Number(s),
  );
  // số đầu tiên bị thiếu hoặc là ký tự đại diện; mọi số sau đó coi như đại diện
  const known = nums.findIndex((n) => n === null);
  const len = known === -1 ? 3 : known;
  const base: Ver = [nums[0] ?? 0, len > 1 ? (nums[1] ?? 0) : 0, len > 2 ? (nums[2] ?? 0) : 0];
  const next = (i: number): Ver =>
    i === 0
      ? [base[0] + 1, 0, 0]
      : i === 1
        ? [base[0], base[1] + 1, 0]
        : [base[0], base[1], base[2] + 1];
  if (len === 0) return op === '' || op === '=' || op === '>=' || op === '<=' ? {} : null;
  switch (op) {
    case '^': {
      const i = base[0] > 0 || len === 1 ? 0 : base[1] > 0 || len === 2 ? 1 : 2;
      return { min: base, max: next(i) };
    }
    case '~':
      return { min: base, max: next(len === 1 ? 0 : 1) };
    case '>=':
      return { min: base };
    case '>':
      return len === 3 ? { min: base, minEx: true } : { min: next(len - 1) };
    case '<':
      return { max: base };
    case '<=':
      return len === 3 ? { max: base, maxIn: true } : { max: next(len - 1) };
    default:
      return len === 3 ? { min: base, max: base, maxIn: true } : { min: base, max: next(len - 1) };
  }
}

function within(v: Ver, comparator: string): boolean {
  const b = bounds(comparator);
  if (!b) return false;
  if (b.min && (b.minEx ? cmp(v, b.min) <= 0 : cmp(v, b.min) < 0)) return false;
  if (b.max && (b.maxIn ? cmp(v, b.max) > 0 : cmp(v, b.max) >= 0)) return false;
  return true;
}

export function satisfiesRange(version: string, range: string): boolean {
  const v = parseVersion(version);
  if (!v || typeof range !== 'string' || range.length > 128) return false;
  return range.split('||').some((alt) => {
    // gộp toán tử đứng tách khỏi số (">= 2.0.0") rồi tách theo dấu cách
    const parts = alt
      .trim()
      .replace(/(\^|~|>=|<=|>|<|=)\s+/g, '$1')
      .split(/\s+/)
      .filter(Boolean);
    return parts.length > 0 && parts.every((c) => within(v, c));
  });
}
