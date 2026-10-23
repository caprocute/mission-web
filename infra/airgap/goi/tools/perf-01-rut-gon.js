// PERF-01 rút gọn cho bộ kiểm tra sau cài đặt (INF-23): tải nhỏ, chỉ kiểm ngưỡng p95 đọc 300 ms và tỷ lệ lỗi < 0,2 %
// (TL-04 mục 7.3). Không phải phép đo năng lực; chạy bằng k6 của khách hàng (k6 không đi kèm gói vì giấy phép AGPL).
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 10,
  duration: '2m',
  thresholds: { http_req_duration: ['p(95)<300'], http_req_failed: ['rate<0.002'] },
};

export default function () {
  const r = http.get(`${__ENV.K6_URL}/api/v1/health`);
  check(r, { 'trả 200': (x) => x.status === 200 });
}
