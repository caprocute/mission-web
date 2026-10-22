import { Injectable, inject } from '@angular/core';
import { RegistryEntry } from './component-registry';
import { SduiTelemetry } from './sdui-telemetry';

/** Lược đồ URL có thể chạy mã hoặc nhúng nội dung tuỳ ý; khoảng trắng và ký tự điều khiển bị bỏ trước khi so. */
const UNSAFE_URL = /^(javascript|data|vbscript):/i;
// eslint-disable-next-line no-control-regex
const IGNORED = /[\u0000-\u0020]/g;

/** Chuỗi (kể cả lồng trong mảng, đối tượng) có dạng URL nguy hiểm không. */
function hasUnsafeUrl(value: unknown, depth = 0): boolean {
  if (typeof value === 'string') return UNSAFE_URL.test(value.replace(IGNORED, ''));
  if (depth > 8 || value === null || typeof value !== 'object') return false;
  return Object.values(value).some((v) => hasUnsafeUrl(v, depth + 1));
}

/**
 * Lọc thuộc tính theo `propsSchema` của component: thuộc tính lạ hoặc sai kiểu bị bỏ.
 * Không dùng trình biên dịch schema sinh mã lúc chạy (như Ajv ở chế độ mặc định) vì CSP
 * cấm `unsafe-eval`; nếu cần JSON Schema đầy đủ thì dùng mã kiểm tra Ajv biên dịch sẵn lúc build.
 */
@Injectable({ providedIn: 'root' })
export class PropsValidator {
  private readonly telemetry = inject(SduiTelemetry);

  sanitize(entry: RegistryEntry, props: Record<string, unknown>): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(props)) {
      const reason = this.reject(entry, key, value);
      if (reason) {
        this.telemetry.record('sdui.prop.dropped', { component: entry.name, prop: key, reason });
        continue;
      }
      out[key] = value;
    }
    return out;
  }

  /** Lý do loại thuộc tính, hoặc null nếu hợp lệ. Không trả giá trị để telemetry không mang dữ liệu. */
  private reject(entry: RegistryEntry, key: string, value: unknown): string | null {
    const rule = Object.hasOwn(entry.propsSchema.properties, key)
      ? entry.propsSchema.properties[key]
      : undefined;
    if (!rule) return 'unknown';
    if (!this.typeOk(rule.type, value)) return 'type';
    if (typeof value === 'number') {
      if (rule.minimum !== undefined && value < rule.minimum) return 'minimum';
      if (rule.maximum !== undefined && value > rule.maximum) return 'maximum';
    }
    if (rule.enum && !rule.enum.includes(value)) return 'enum';
    if (rule.pattern && typeof value === 'string' && !new RegExp(rule.pattern).test(value))
      return 'pattern';
    if (hasUnsafeUrl(value)) return 'unsafe-url';
    return null;
  }

  private typeOk(type: string, v: unknown): boolean {
    switch (type) {
      case 'integer':
        return Number.isInteger(v);
      case 'number':
        return typeof v === 'number' && Number.isFinite(v);
      case 'array':
        return Array.isArray(v);
      case 'object':
        return typeof v === 'object' && v !== null && !Array.isArray(v);
      default:
        return typeof v === type;
    }
  }
}
