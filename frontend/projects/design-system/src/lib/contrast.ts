/**
 * Tỉ lệ tương phản WCAG 2.1 giữa hai màu (#RGB, #RRGGBB, #RRGGBBFF hoặc rgb()), dùng cho kiểm tra tĩnh
 * token của UI Pack (TL-01: tương phản ≥ 4,5:1; BR-18-09: ≥ 7:1 ở chế độ cao tuổi).
 */
export function contrastRatio(a: string, b: string): number {
  const [la, lb] = [luminance(a), luminance(b)];
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

/** Pha màu `color` với `base` theo tỉ lệ `weight` (0..1), như `color-mix(in srgb, color weight, base)`. */
export function mixHex(color: string, weight: number, base: string): string {
  const [c, d] = [rgb(color), rgb(base)];
  return (
    '#' +
    c
      .map((v, i) =>
        Math.round(v * weight + d[i] * (1 - weight))
          .toString(16)
          .padStart(2, '0'),
      )
      .join('')
  );
}

/**
 * Kênh màu của `#RGB`, `#RRGGBB`, `#RRGGBBFF` (đục hoàn toàn) hoặc `rgb(r, g, b)`, cùng các dạng
 * mà bộ kiểm UI Pack của máy chủ nhận (CORE-08-10); màu trong suốt một phần hay tên màu bị từ chối
 * vì không tính được tương phản.
 */
function rgb(color: string): number[] {
  const fn = /^rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)$/i.exec(color.trim());
  if (fn) {
    const v = fn.slice(1, 4).map(Number);
    if (v.every((x) => x <= 255)) return v;
  }
  let h = color.trim().replace(/^#/, '');
  if (h.length === 3) h = [...h].map((x) => x + x).join('');
  if (h.length === 8 && /ff$/i.test(h)) h = h.slice(0, 6);
  if (!/^[0-9a-f]{6}$/i.test(h)) throw new Error(`Màu không hợp lệ: ${color}`);
  return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16));
}

/** Màu có tính được tương phản không (dạng nhận được, đục hoàn toàn). */
export function isOpaqueColor(color: string): boolean {
  try {
    rgb(color);
    return true;
  } catch {
    return false;
  }
}

function luminance(hex: string): number {
  const [r, g, b] = rgb(hex)
    .map((v) => v / 255)
    .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
