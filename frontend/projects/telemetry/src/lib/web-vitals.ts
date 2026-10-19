/**
 * Ghi nhận LCP và INP tối thiểu bằng PerformanceObserver để đối chiếu ngân sách
 * hiệu năng (LCP ≤ 2,5 s, INP ≤ 200 ms). Bản đầy đủ dùng Grafana Faro (ADR-12).
 */
export function observeWebVitals(report: (name: 'LCP' | 'INP', value: number) => void): void {
  if (typeof PerformanceObserver === 'undefined') return;
  try {
    new PerformanceObserver((list) => {
      const last = list.getEntries().at(-1);
      if (last) report('LCP', last.startTime);
    }).observe({ type: 'largest-contentful-paint', buffered: true });
    new PerformanceObserver((list) => {
      for (const e of list.getEntries() as (PerformanceEventTiming & { interactionId?: number })[]) {
        if (e.interactionId) report('INP', e.duration);
      }
    }).observe({ type: 'event', buffered: true, durationThreshold: 40 } as PerformanceObserverInit);
  } catch {
    /* trình duyệt không hỗ trợ loại entry này */
  }
}
