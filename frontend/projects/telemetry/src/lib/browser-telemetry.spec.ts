import {
  attachTelemetryApi,
  browserTracingActive,
  pushTelemetryEvent,
  sampleRatioOf,
  startBrowserTelemetry,
} from './browser-telemetry';
import { scrubItem, scrubUrl } from './faro-init';
import type { TransportItem } from '@grafana/faro-web-sdk';

describe('Đo lường phía trình duyệt (WEB-011)', () => {
  afterEach(() => attachTelemetryApi(null));

  it('giữ sự kiện trong hàng đợi tới khi Faro sẵn sàng, đổi giá trị số thành chuỗi', () => {
    pushTelemetryEvent('sdui.prop.dropped', { prop: 'href', depth: 2 });
    const sent: unknown[] = [];
    attachTelemetryApi({ pushEvent: (n, a) => sent.push([n, a]) }, true);
    expect(sent).toEqual([['sdui.prop.dropped', { prop: 'href', depth: '2' }]]);
    expect(browserTracingActive()).toBe(true);
    pushTelemetryEvent('ui-pack.signature.invalid');
    expect(sent).toHaveLength(2);
  });

  it('không tải SDK khi config.json không có faroUrl', async () => {
    await startBrowserTelemetry({ environment: 'DEV' });
    expect(browserTracingActive()).toBe(false);
  });

  it('tỷ lệ lấy mẫu mặc định 10% và bị kẹp trong 0..1', () => {
    expect(sampleRatioOf()).toBe(0.1);
    expect(sampleRatioOf({ sampleRatio: 3 })).toBe(1);
    expect(sampleRatioOf({ sampleRatio: -1 })).toBe(0);
  });

  it('bỏ chuỗi truy vấn và phần sau # khỏi địa chỉ trang trước khi gửi', () => {
    expect(scrubUrl('https://okr.test/tasks?q=ho%20so%20mat#x')).toBe('https://okr.test/tasks');
    const item = {
      meta: { page: { url: '/tasks?q=tu-khoa' } },
      payload: {
        name: 'faro.performance.navigation',
        attributes: { name: 'https://okr.test/tasks?q=tu-khoa', note: 'Có dấu ? trong câu' },
        spans: [
          { attributes: [{ key: 'http.url', value: { stringValue: '/api/v1/tasks?q=tu-khoa' } }] },
        ],
      },
    } as unknown as TransportItem;
    Object.freeze(item.payload);
    const out = JSON.stringify(scrubItem(item));
    expect(out).not.toContain('tu-khoa');
    expect(out).toContain('Có dấu ? trong câu');
  });
});
