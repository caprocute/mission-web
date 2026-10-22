import { Injectable, InjectionToken, inject } from '@angular/core';

/** Sự kiện an toàn của renderer SDUI và bộ nạp UI Pack (WEB-009, ADR-05 lớp 2). */
export type SduiTelemetryEvent =
  | 'sdui.component.unknown'
  | 'sdui.prop.dropped'
  | 'sdui.cel.invalid'
  | 'sdui.cel.error'
  | 'sdui.cel.limit'
  | 'ui-pack.signature.missing'
  | 'ui-pack.signature.invalid'
  | 'ui-pack.registry.incompatible'
  // WEB-051: plugin chuyên ngành bị từ chối (sai băm, sai tên, tệp ngoài thư mục, mô tả lỗi)
  | 'plugin.rejected'
  | 'plugin.widget.rejected'
  | 'plugin.url.blocked';

export interface SduiTelemetryRecord {
  event: SduiTelemetryEvent;
  /** Chỉ tên component, tên thuộc tính, lý do, phiên bản; không bao giờ chứa giá trị dữ liệu. */
  attributes: Record<string, string | number>;
}

/**
 * Nơi nhận sự kiện (ví dụ Grafana Faro khi WEB-011 xong). Không cung cấp thì sự kiện chỉ ghi
 * ra console để người phát triển thấy.
 */
export const SDUI_TELEMETRY_SINK = new InjectionToken<(record: SduiTelemetryRecord) => void>(
  'vt.sduiTelemetrySink',
);

/** Số sự kiện khác nhau tối đa ghi trong một phiên, tránh một bố cục lỗi làm ngập kênh. */
const MAX_DISTINCT = 200;

@Injectable({ providedIn: 'root' })
export class SduiTelemetry {
  private readonly sink = inject(SDUI_TELEMETRY_SINK, { optional: true });
  private readonly seen = new Set<string>();

  record(event: SduiTelemetryEvent, attributes: Record<string, string | number> = {}): void {
    const key = `${event}|${JSON.stringify(attributes)}`;
    if (this.seen.has(key) || this.seen.size >= MAX_DISTINCT) return;
    this.seen.add(key);
    console.warn(`[sdui] ${event}`, attributes);
    try {
      this.sink?.({ event, attributes });
    } catch {
      /* lỗi của kênh telemetry không được làm hỏng giao diện */
    }
  }
}
