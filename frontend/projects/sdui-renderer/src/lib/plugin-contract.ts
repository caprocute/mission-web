import type { Type } from '@angular/core';
import type { Routes } from '@angular/router';
import type { PropsSchema } from './component-registry';

/**
 * Hợp đồng plugin chuyên ngành (ADR-05 lớp 4, BR-18-12, WEB-051). Plugin là remote Native
 * Federation công bố module `./plugin` xuất hằng `VT_PLUGIN` theo kiểu này. Shell chỉ nạp plugin có
 * trong phần `plugins` của UI Pack đã kiểm chữ ký (WEB-009), sau khi `remoteEntry.json` khớp băm
 * `integrity` của gói.
 */
export interface VtPlugin {
  /** Trùng `name` trong UI Pack, dạng `fe-plugin-<ngành>`. */
  name: string;
  /** Nhãn mục menu và tiêu đề trang. */
  title: string;
  /** Mô tả ngắn dưới nhãn menu. */
  subtitle?: string;
  /** Lớp biểu tượng PrimeIcons, ví dụ `pi pi-book`. */
  icon?: string;
  /** Tuyến của plugin; shell gắn dưới `/plugin/<ngành>`, plugin không tự chọn đường dẫn gốc. */
  routes: () => Promise<Routes>;
  /** Widget SDUI; tên bắt buộc có tiền tố `<ngành>.widget.` để không thay được component lõi. */
  widgets?: VtPluginWidget[];
}

export interface VtPluginWidget {
  name: string;
  since: string;
  propsSchema: PropsSchema;
  load: () => Promise<Type<unknown>>;
}

/** Module mà mọi plugin công bố qua Native Federation. */
export const PLUGIN_EXPOSED_MODULE = './plugin';

/** Mục `plugins` của UI Pack (hợp đồng `UiPackRuntime` trong `contracts/openapi/modules/config.yaml`). */
export interface UiPackPluginRef {
  name: string;
  /** URL `remoteEntry.json` của plugin, tuyệt đối hoặc tương đối theo gốc shell. */
  remoteEntry: string;
  /** Băm SRI của `remoteEntry.json`, dạng `sha256-<base64>`; bắt buộc khi nạp ở môi trường có khoá. */
  integrity?: string;
}

/** `fe-plugin-giaoduc` → `giaoduc`; tên sai dạng trả `null`. */
export function pluginShortName(name: string): string | null {
  const m = /^fe-plugin-([a-z][a-z0-9-]{1,30})$/.exec(name);
  return m ? m[1] : null;
}

/** Tên widget hợp lệ của plugin: `<ngành>.widget.<tên>`. */
export function pluginWidgetAllowed(short: string, widget: string): boolean {
  return new RegExp(`^${short}\\.widget\\.[A-Za-z][A-Za-z0-9]{0,40}$`).test(widget);
}

/** SHA-256 của nội dung, dạng SRI `sha256-<base64>`. */
export async function sriSha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  let bin = '';
  for (const b of new Uint8Array(digest)) bin += String.fromCharCode(b);
  return 'sha256-' + btoa(bin);
}

/**
 * Tệp do `remoteEntry.json` trỏ tới phải nằm trong thư mục của plugin: chỉ tên tương đối, không có
 * giao thức, không `//`, không `..`. Nhờ vậy băm của `remoteEntry.json` (đã ký gián tiếp qua UI Pack)
 * cố định được tập tệp sẽ chạy, vì tên tệp do esbuild sinh mang băm nội dung.
 */
export function remoteEntryFilesSafe(entry: unknown): boolean {
  if (!entry || typeof entry !== 'object') return false;
  const e = entry as { exposes?: unknown; shared?: unknown };
  const files: unknown[] = [];
  for (const list of [e.exposes, e.shared]) {
    if (list === undefined) continue;
    if (!Array.isArray(list)) return false;
    for (const x of list) files.push((x as { outFileName?: unknown })?.outFileName);
  }
  return files.every(
    (f) =>
      typeof f === 'string' &&
      f.length > 0 &&
      !/^[a-z][a-z0-9+.-]*:/i.test(f) &&
      !f.startsWith('/') &&
      !f.includes('\\') &&
      !f.split('/').includes('..'),
  );
}

/** Mô tả plugin hợp lệ và đúng tên trong UI Pack. */
export function validPluginDescriptor(p: unknown, expectedName: string): p is VtPlugin {
  if (!p || typeof p !== 'object') return false;
  const d = p as Partial<VtPlugin>;
  return (
    d.name === expectedName &&
    typeof d.title === 'string' &&
    d.title.length > 0 &&
    d.title.length <= 60 &&
    typeof d.routes === 'function' &&
    (d.icon === undefined || /^pi pi-[a-z0-9-]+$/.test(d.icon)) &&
    (d.widgets === undefined || Array.isArray(d.widgets))
  );
}
