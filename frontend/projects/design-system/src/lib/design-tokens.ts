/**
 * Kiểu dữ liệu token theo định dạng W3C Design Tokens ($type, $value)
 * dùng trong phần `tokens` và `modes` của UI Pack (TL-01, mục "Cấu trúc UI Pack").
 */
export interface DesignToken {
  $type?: string;
  $value: unknown;
  sha256?: string;
}

export interface TokenGroup {
  [key: string]: DesignToken | TokenGroup;
}

function isToken(node: DesignToken | TokenGroup): node is DesignToken {
  return Object.prototype.hasOwnProperty.call(node, '$value');
}

function formatValue(token: DesignToken): string | null {
  const v = token.$value;
  if (token.$type === 'asset') {
    return null; // tài nguyên (logo) không đưa vào CSS, shell đọc trực tiếp từ UI Pack
  }
  if (Array.isArray(v)) {
    return v.map((x) => (/\s/.test(String(x)) ? `"${x}"` : String(x))).join(', ');
  }
  return typeof v === 'string' || typeof v === 'number' ? String(v) : null;
}

/** Làm phẳng cây token thành cặp `--vt-<đường-dẫn>: giá trị`. */
export function flattenTokens(group: TokenGroup, prefix = '--vt'): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, node] of Object.entries(group)) {
    if (key.startsWith('$') || key === 'flags') continue;
    const name = `${prefix}-${key.replace(/([a-z])([A-Z])/g, '$1-$2').toLowerCase()}`;
    if (isToken(node)) {
      const value = formatValue(node);
      if (value !== null) out[name] = value;
    } else {
      Object.assign(out, flattenTokens(node, name));
    }
  }
  return out;
}

/**
 * Biên dịch token sang CSS custom properties.
 * Bản sản xuất do MOD-08 biên dịch ở máy chủ bằng Style Dictionary khi phát hành UI Pack;
 * hàm này là phương án dự phòng cho môi trường dev khi gói chưa có tệp CSS dựng sẵn.
 */
export function tokensToCss(tokens: TokenGroup, modes: Record<string, TokenGroup> = {}): string {
  const block = (selector: string, vars: Record<string, string>) =>
    `${selector}{${Object.entries(vars)
      .map(([k, v]) => `${k}:${v}`)
      .join(';')}}`;
  const parts = [block(':root[data-tenant]', flattenTokens(tokens))];
  for (const [mode, group] of Object.entries(modes)) {
    parts.push(block(`:root[data-tenant][data-mode="${mode}"]`, flattenTokens(group)));
  }
  return parts.join('\n');
}
