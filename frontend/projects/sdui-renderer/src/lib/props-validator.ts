import { Injectable } from '@angular/core';
import { RegistryEntry } from './component-registry';

/**
 * Lọc thuộc tính theo `propsSchema` của component: thuộc tính lạ hoặc sai kiểu bị bỏ.
 * Không dùng trình biên dịch schema sinh mã lúc chạy (như Ajv ở chế độ mặc định) vì CSP
 * cấm `unsafe-eval`; nếu cần JSON Schema đầy đủ thì dùng mã kiểm tra Ajv biên dịch sẵn lúc build.
 */
@Injectable({ providedIn: 'root' })
export class PropsValidator {
  sanitize(entry: RegistryEntry, props: Record<string, unknown>): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(props)) {
      const rule = entry.propsSchema.properties[key];
      if (!rule) {
        console.warn(`[sdui] Bỏ thuộc tính lạ "${key}" của ${entry.name}`);
        continue;
      }
      if (!this.typeOk(rule.type, value)) continue;
      if (typeof value === 'number') {
        if (rule.minimum !== undefined && value < rule.minimum) continue;
        if (rule.maximum !== undefined && value > rule.maximum) continue;
      }
      if (rule.enum && !rule.enum.includes(value)) continue;
      if (rule.pattern && typeof value === 'string' && !new RegExp(rule.pattern).test(value)) continue;
      if (typeof value === 'string' && /^\s*(javascript|data):/i.test(value)) continue;
      out[key] = value;
    }
    return out;
  }

  private typeOk(type: string, v: unknown): boolean {
    switch (type) {
      case 'integer': return Number.isInteger(v);
      case 'number': return typeof v === 'number' && Number.isFinite(v);
      case 'array': return Array.isArray(v);
      case 'object': return typeof v === 'object' && v !== null && !Array.isArray(v);
      default: return typeof v === type;
    }
  }
}
