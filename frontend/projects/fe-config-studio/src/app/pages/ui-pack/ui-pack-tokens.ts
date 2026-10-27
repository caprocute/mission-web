import { UiSduiNode } from '@vt/api-client';
import { contrastRatio } from '@vt/design-system';

/**
 * Logic thuần của SCR-1801 (QT-18): đọc, ghi token theo đường dẫn, kiểm tra tương phản tức thời
 * (BR-18-01, BR-18-09) và sửa danh sách khối trang chủ (BR-18-02). Máy chủ kiểm lại khi lưu, gửi duyệt.
 */

export type TokenTree = Record<string, unknown>;

export type TokenKind = 'color' | 'px' | 'font';

export interface TokenField {
  path: string;
  label: string;
  kind: TokenKind;
  min?: number;
  max?: number;
}

/** Token tenant được chỉnh (TL-02b SCR-1801 trường 3, 4); giới hạn theo bảng trường. */
export const TOKEN_FIELDS: TokenField[] = [
  { path: 'color.primary', label: 'Màu chủ đạo', kind: 'color' },
  { path: 'color.onPrimary', label: 'Màu chữ trên nền chủ đạo', kind: 'color' },
  { path: 'color.danger', label: 'Màu cảnh báo', kind: 'color' },
  { path: 'color.text', label: 'Màu chữ', kind: 'color' },
  { path: 'color.background', label: 'Màu nền trang', kind: 'color' },
  { path: 'font.family', label: 'Phông chữ', kind: 'font' },
  { path: 'font.size.body', label: 'Cỡ chữ cơ sở', kind: 'px', min: 12, max: 16 },
  { path: 'radius.card', label: 'Bo góc thẻ', kind: 'px', min: 0, max: 16 },
  { path: 'space.unit', label: 'Đơn vị khoảng cách', kind: 'px', min: 2, max: 8 },
];

/** Bộ phông được phép (phông có sẵn trong bản dựng, không tải phông ngoài). */
export const FONT_OPTIONS: { label: string; value: string[] }[] = [
  { label: 'Be Vietnam Pro', value: ['Be Vietnam Pro', 'Roboto', 'sans-serif'] },
  { label: 'Inter', value: ['Inter', 'DejaVu Sans', 'sans-serif'] },
  { label: 'Roboto', value: ['Roboto', 'sans-serif'] },
  { label: 'DejaVu Sans', value: ['DejaVu Sans', 'sans-serif'] },
];

const HEX = /^#[0-9a-f]{6}$/i;

export const isHex = (v: string) => HEX.test(v);

function node(tree: TokenTree, path: string): { $value?: unknown } | undefined {
  let n: unknown = tree;
  for (const p of path.split('.')) n = (n as Record<string, unknown> | undefined)?.[p];
  return n as { $value?: unknown } | undefined;
}

export function tokenValue(tree: TokenTree, path: string): unknown {
  return node(tree, path)?.$value;
}

/** Giá trị chuỗi để hiển thị: màu hex, số px, tên phông đầu tiên. */
export function tokenText(tree: TokenTree, path: string): string {
  const v = tokenValue(tree, path);
  if (Array.isArray(v)) return String(v[0] ?? '');
  return v === undefined || v === null ? '' : String(v);
}

export const pxOf = (tree: TokenTree, path: string) =>
  parseFloat(tokenText(tree, path).replace('px', '')) || 0;

/** Bản sao cây token với một giá trị đổi; tạo nhóm và `$type` khi chưa có. */
export function setToken(tree: TokenTree, field: TokenField, value: unknown): TokenTree {
  const next = structuredClone(tree);
  const parts = field.path.split('.');
  let n = next as Record<string, unknown>;
  for (const p of parts.slice(0, -1)) n = (n[p] ??= {}) as Record<string, unknown>;
  const leaf = parts[parts.length - 1];
  const type =
    field.kind === 'color' ? 'color' : field.kind === 'font' ? 'fontFamily' : 'dimension';
  n[leaf] = { ...((n[leaf] as object) ?? {}), $type: type, $value: value };
  return next;
}

export interface ContrastCheck {
  fg: string;
  bg: string;
  label: string;
  ratio: number;
  need: number;
  ok: boolean;
}

const fmt = (n: number) => n.toLocaleString('vi-VN', { maximumFractionDigits: 1 });
export const ratioText = (n: number) => `${fmt(n)} : 1`;

/**
 * Màu chữ trên nền màu chủ đạo: `color.onPrimary` của gói; gói chỉ khai `onPrimaryStrong` hoặc chưa khai
 * thì lấy trắng hay đen, màu nào tương phản cao hơn với màu chủ đạo. Xem trước và kiểm tra tương phản
 * dùng chung một màu nên ô xem trước không hiện cặp màu mà kiểm tra chưa xét.
 */
export function onPrimaryOf(tree: TokenTree): string {
  const own = tokenText(tree, 'color.onPrimary') || tokenText(tree, 'color.onPrimaryStrong');
  if (own) return own;
  const p = tokenText(tree, 'color.primary');
  if (!isHex(p)) return '#FFFFFF';
  return contrastRatio('#FFFFFF', p) >= contrastRatio('#000000', p) ? '#FFFFFF' : '#000000';
}

/** Các cặp màu chữ, nền phải đạt AA (4,5 : 1); chế độ cao tuổi chữ trên nền trang ≥ 7 : 1. */
export function contrastChecks(tree: TokenTree, elderly = false): ContrastCheck[] {
  const pairs: [string, string, string, number][] = [
    ['color.onPrimary', 'color.primary', 'Chữ trên nền màu chủ đạo', 4.5],
    ['color.text', 'color.background', 'Chữ trên nền trang', elderly ? 7 : 4.5],
    ['#FFFFFF', 'color.danger', 'Chữ trên nền màu cảnh báo', 4.5],
  ];
  return pairs.map(([fgPath, bgPath, label, need]) => {
    const fg = fgPath.startsWith('#')
      ? fgPath
      : fgPath === 'color.onPrimary'
        ? onPrimaryOf(tree)
        : tokenText(tree, fgPath);
    const bg = tokenText(tree, bgPath);
    const ratio = isHex(fg) && isHex(bg) ? contrastRatio(fg, bg) : 0;
    return { fg, bg, label, ratio, need, ok: ratio >= need };
  });
}

/** MSG-18-01 cho một cặp màu chưa đạt. */
export const contrastMessage = (c: ContrastCheck) =>
  `Màu chữ ${c.fg} trên nền ${c.bg} có tương phản ${ratioText(c.ratio)}, chưa đạt mức ${c.need === 7 ? 'dành cho người cao tuổi (7 : 1)' : 'AA (4,5 : 1)'}.`;

export const MAX_HOME_WIDGETS = 12;

/** Khối trên trang chủ (con trực tiếp của lưới), không tính khung lưới. */
export const homeWidgets = (layouts: Record<string, UiSduiNode>): UiSduiNode[] =>
  layouts['home']?.children ?? [];

export function withHomeWidgets(
  layouts: Record<string, UiSduiNode>,
  widgets: UiSduiNode[],
): Record<string, UiSduiNode> {
  const home = layouts['home'] ?? {
    component: 'vt.layout.grid',
    props: { columns: { xs: 1, md: 2, xl: 3 } },
  };
  return { ...layouts, home: { ...home, children: widgets } };
}

export function moveItem<T>(list: readonly T[], from: number, to: number): T[] {
  if (to < 0 || to >= list.length) return [...list];
  const next = [...list];
  const [x] = next.splice(from, 1);
  next.splice(to, 0, x);
  return next;
}
