import { contrastRatio, isOpaqueColor } from './contrast';
import { tokensToCss, type DesignToken, type TokenGroup } from './design-tokens';

/**
 * Biên dịch và kiểm tra tĩnh bộ token của UI Pack (WEB-004, TL-01 "Pipeline token sang CSS
 * variables và preset PrimeNG"). Quy tắc trùng bộ kiểm của máy chủ (`UiPackStaticChecks`,
 * CORE-08-10) để người soạn thấy cùng kết quả trước khi gửi duyệt; máy chủ vẫn là nơi kiểm cuối.
 * Mã này không phụ thuộc Angular nên công cụ dòng lệnh `tools/token-compiler` dùng lại được.
 */

/** Ngưỡng tương phản AA cho chữ thường (BR-18-01, MSG-18-01). */
export const CONTRAST_AA = 4.5;
/** Ngưỡng tương phản ở chế độ Đảng viên cao tuổi (BR-18-09). */
export const CONTRAST_ELDERLY = 7;
/** Ngân sách UI Pack nén gzip (BR-18-08, MSG-18-05). */
export const UI_PACK_MAX_GZIP_BYTES = 30 * 1024;

export type TokenIssueCode = 'CONTRAST' | 'ELDER_MODE' | 'LIMIT' | 'SIZE';

/** Một lỗi kiểm tra; `path` là đường dẫn trong UI Pack, `ratio` có khi lỗi tương phản. */
export interface TokenIssue {
  code: TokenIssueCode;
  path: string;
  message: string;
  ratio?: number;
}

/** Một cặp chữ, nền đã kiểm. */
export interface ContrastResult {
  text: string;
  background: string;
  ratio: number;
  need: number;
  ok: boolean;
  mode: 'default' | 'elderly';
}

/** Phần UI Pack mà bộ biên dịch cần. */
export interface CompilableUiPack {
  tokens: TokenGroup;
  modes?: Record<string, TokenGroup & { flags?: unknown }>;
}

export interface CompileResult {
  /** CSS custom properties, cùng định dạng với `tokensToCss` phía trình duyệt. */
  css: string;
  contrast: ContrastResult[];
  issues: TokenIssue[];
}

const ALIAS = /^\{([A-Za-z0-9_.-]+)\}$/;
const DIMENSION = /^(\d+(?:\.\d+)?)(px|rem)?$/;

const isToken = (node: unknown): node is DesignToken =>
  !!node && typeof node === 'object' && Object.prototype.hasOwnProperty.call(node, '$value');

/** Giải bí danh `{color.white}` (tối đa 8 tầng), trả chuỗi giá trị. */
export function resolveTokenValue(value: unknown, root: TokenGroup, depth = 0): string {
  const s = typeof value === 'string' ? value : JSON.stringify(value);
  const m = ALIAS.exec(s.trim());
  if (!m || depth > 8) return s;
  let node: unknown = root;
  for (const part of m[1].split('.')) node = (node as Record<string, unknown> | undefined)?.[part];
  return isToken(node) ? resolveTokenValue(node.$value, root, depth + 1) : s;
}

/** Màu lá của nhóm `color` theo đường dẫn tương đối (`primary`, `brand.base`), bí danh đã giải. */
export function colorTokens(tokens: TokenGroup): Map<string, string> {
  const out = new Map<string, string>();
  const walk = (group: unknown, path: string) => {
    if (!group || typeof group !== 'object') return;
    for (const [key, node] of Object.entries(group as Record<string, unknown>)) {
      if (key.startsWith('$') || !node || typeof node !== 'object') continue;
      const p = path ? `${path}.${key}` : key;
      if (isToken(node)) out.set(p, resolveTokenValue(node.$value, tokens));
      else walk(node, p);
    }
  };
  walk(tokens['color'], '');
  return out;
}

const camel = (name: string, prefix: string) =>
  name.length > prefix.length &&
  name.startsWith(prefix) &&
  name[prefix.length] === name[prefix.length].toUpperCase() &&
  name[prefix.length] !== name[prefix.length].toLowerCase();

function onTarget(name: string): string | null {
  if (name.startsWith('on-') && name.length > 3) return name.slice(3);
  if (camel(name, 'on')) return name[2].toLowerCase() + name.slice(3);
  return null;
}

/**
 * Cặp (chữ, nền) suy ra từ tên token trong cùng nhóm: `on-X`/`onX` là chữ trên nền `X`;
 * `text`, `text-…`, `textX` là chữ trên mọi nền `surface*`, `background*` (wiki trang 03).
 */
export function contrastPairs(paths: Iterable<string>): [string, string][] {
  const groups = new Map<string, string[]>();
  for (const p of paths) {
    const dot = p.lastIndexOf('.');
    const g = dot < 0 ? '' : p.slice(0, dot);
    groups.set(g, [...(groups.get(g) ?? []), p]);
  }
  const pairs: [string, string][] = [];
  for (const members of groups.values()) {
    for (const text of members) {
      const name = text.slice(text.lastIndexOf('.') + 1);
      const prefix = text.slice(0, text.length - name.length);
      const bg = onTarget(name);
      if (bg !== null && members.includes(prefix + bg)) {
        pairs.push([text, prefix + bg]);
      } else if (name === 'text' || name.startsWith('text-') || camel(name, 'text')) {
        for (const b of members) {
          const bn = b.slice(b.lastIndexOf('.') + 1);
          if (bn.startsWith('surface') || bn.startsWith('background')) pairs.push([text, b]);
        }
      }
    }
  }
  return pairs;
}

/** Gộp sâu ghi đè vào bản sao token (ghi đè thay cả token, giữ token không bị nhắc tới). */
export function mergeTokens(base: TokenGroup, override: TokenGroup): TokenGroup {
  const out: Record<string, unknown> = { ...base };
  for (const [key, node] of Object.entries(override)) {
    const cur = out[key];
    out[key] =
      !isToken(node) &&
      node &&
      typeof node === 'object' &&
      cur &&
      typeof cur === 'object' &&
      !isToken(cur)
        ? mergeTokens(cur as TokenGroup, node as TokenGroup)
        : isToken(node) && isToken(cur)
          ? { ...cur, ...node }
          : node;
  }
  return out as TokenGroup;
}

/** Kích thước theo px (`rem` tính 16 px), `null` khi không đọc được. */
export function tokenPx(token: unknown, root: TokenGroup): number | null {
  const value = isToken(token) ? token.$value : token;
  const s = resolveTokenValue(value, root).trim().toLowerCase();
  const m = DIMENSION.exec(s);
  if (!m) return null;
  const n = Number(m[1]);
  if (!m[2] && n !== 0) return null;
  return m[2] === 'rem' ? n * 16 : n;
}

/** Tỉ lệ làm tròn xuống hai chữ số để thông điệp không báo "4,5 : 1" cho 4,478 : 1. */
export const formatRatio = (r: number) =>
  (Math.floor(r * 100) / 100).toLocaleString('vi-VN', { maximumFractionDigits: 2 });

function checkContrast(
  tokens: TokenGroup,
  need: number,
  mode: 'default' | 'elderly',
  issues: TokenIssue[],
): ContrastResult[] {
  const colors = colorTokens(tokens);
  const code: TokenIssueCode = mode === 'elderly' ? 'ELDER_MODE' : 'CONTRAST';
  const level = mode === 'elderly' ? 'dành cho chế độ cao tuổi (7 : 1)' : 'AA (4,5 : 1)';
  const out: ContrastResult[] = [];
  for (const [t, b] of contrastPairs(colors.keys())) {
    const [fg, bg] = [colors.get(t)!, colors.get(b)!];
    const path = `tokens.color.${t}`;
    if (!isOpaqueColor(fg) || !isOpaqueColor(bg)) {
      issues.push({
        code,
        path,
        message: `Không kiểm được tương phản của màu ${t} trên nền ${b}, hãy dùng mã màu dạng #RRGGBB.`,
      });
      continue;
    }
    const ratio = contrastRatio(fg, bg);
    const ok = ratio >= need;
    out.push({ text: t, background: b, ratio, need, ok, mode });
    if (!ok) {
      issues.push({
        code,
        path,
        ratio,
        message: `Màu chữ ${t} trên nền ${b} có tương phản ${formatRatio(ratio)} : 1, chưa đạt mức ${level}.`,
      });
    }
  }
  return out;
}

/** Ghi đè của chế độ cao tuổi: `modes.elderly` của gói web, hoặc `tokens.$extensions.modes.elderly`. */
export function elderlyOverride(pack: CompilableUiPack): (TokenGroup & { flags?: unknown }) | null {
  const fromModes = pack.modes?.['elderly'];
  if (fromModes) return fromModes;
  const ext = (pack.tokens['$extensions'] as { modes?: Record<string, TokenGroup> } | undefined)
    ?.modes?.['elderly'];
  return ext ?? null;
}

/** Kiểm tra tĩnh bộ token (không gồm kích thước gói; xem `checkUiPackSize`). */
export function checkTokens(pack: CompilableUiPack): {
  contrast: ContrastResult[];
  issues: TokenIssue[];
} {
  const issues: TokenIssue[] = [];
  const tokens = pack.tokens;
  const contrast = checkContrast(tokens, CONTRAST_AA, 'default', issues);

  const body = (tokens['font'] as TokenGroup | undefined)?.['size'] as TokenGroup | undefined;
  if (body?.['body'] !== undefined) {
    const v = tokenPx(body['body'], tokens);
    if (v === null || v < 12 || v > 16) {
      issues.push({
        code: 'LIMIT',
        path: 'tokens.font.size.body',
        message: 'Cỡ chữ cơ sở phải từ 12 px đến 16 px.',
      });
    }
  }
  const radius = tokens['radius'];
  if (radius && typeof radius === 'object') {
    for (const [key, node] of Object.entries(radius as TokenGroup)) {
      if (key.startsWith('$') || !isToken(node)) continue;
      const v = tokenPx(node, tokens);
      if (v === null || v > 16) {
        issues.push({
          code: 'LIMIT',
          path: `tokens.radius.${key}`,
          message: 'Bo góc phải từ 0 px đến 16 px.',
        });
      }
    }
  }

  const elderly = elderlyOverride(pack);
  if (elderly) {
    const { flags, ...override } = elderly as TokenGroup & { flags?: { minTouchTarget?: unknown } };
    const merged = mergeTokens(tokens, override as TokenGroup);
    contrast.push(...checkContrast(merged, CONTRAST_ELDERLY, 'elderly', issues));
    const size = (merged['font'] as TokenGroup | undefined)?.['size'] as TokenGroup | undefined;
    const ebody = size?.['body'] === undefined ? null : tokenPx(size['body'], merged);
    if (ebody === null || ebody < 17) {
      issues.push({
        code: 'ELDER_MODE',
        path: 'modes.elderly.font.size.body',
        message: 'Chế độ cao tuổi cần cỡ chữ cơ sở từ 17 px.',
      });
    }
    let touch: unknown = flags?.minTouchTarget;
    const sz = merged['size'] as TokenGroup | undefined;
    for (const key of ['minTouchTarget', 'min-touch-target'])
      if (sz?.[key] !== undefined) touch = sz[key];
    if (touch !== undefined) {
      const t = tokenPx(touch, merged);
      if (t === null || t < 48) {
        issues.push({
          code: 'ELDER_MODE',
          path: 'modes.elderly.flags.minTouchTarget',
          message: 'Chế độ cao tuổi cần vùng chạm từ 48 px.',
        });
      }
    }
  }
  return { contrast, issues };
}

/** Lỗi kích thước khi gói nén gzip vượt ngân sách 30 KB. */
export function checkUiPackSize(
  gzipBytes: number,
  max = UI_PACK_MAX_GZIP_BYTES,
): TokenIssue | null {
  if (gzipBytes <= max) return null;
  const kb = Math.ceil(gzipBytes / 1024);
  return {
    code: 'SIZE',
    path: '$',
    message: `Kích thước bộ giao diện là ${kb} KB, vượt ngân sách ${max / 1024} KB.`,
  };
}

/** Biên dịch token thành CSS và chạy kiểm tra tĩnh; CSS trùng `tokensToCss` của trình duyệt. */
export function compileUiPack(pack: CompilableUiPack): CompileResult {
  const { contrast, issues } = checkTokens(pack);
  const modes: Record<string, TokenGroup> = {};
  for (const [name, group] of Object.entries(pack.modes ?? {})) modes[name] = group;
  const elderly = elderlyOverride(pack);
  if (!modes['elderly'] && elderly) modes['elderly'] = elderly;
  return { css: tokensToCss(pack.tokens, modes), contrast, issues };
}
