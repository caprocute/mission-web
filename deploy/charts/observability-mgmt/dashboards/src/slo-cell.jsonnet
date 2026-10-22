// Nhóm 1: SLO theo cell (TL-04 mục 7.1, 10.6): ngân sách lỗi còn lại, tốc độ tiêu, phân vị độ trễ.
// Cảnh báo SLO trỏ về dashboard này qua dashboard_url; từ đây tới trace: chấm exemplar trên biểu đồ độ trễ
// hoặc dòng log lỗi ở cuối trang, rồi liên kết Tempo (B5, không quá 3 thao tác).
local c = import '../lib/chung.libsonnet';
local q = c.q;
local loi = 'sum(rate(apisix_http_status{cell=~"$cell", code=~"5.."}[$__rate_interval])) / sum(rate(apisix_http_status{cell=~"$cell"}[$__rate_interval]))';

c.dashboard('vt-slo-cell', 'SLO theo cell', [c.bienCell], [
  c.so('Ngân sách lỗi khả dụng còn lại (30 ngày)',
       [q('1 - (avg_over_time(slo:api_availability_errors:ratio_rate1h{cell=~"$cell"}[30d]) / 0.001)')], 'percentunit',
       [{ color: 'red', value: null }, { color: 'orange', value: 0.25 }, { color: 'green', value: 0.5 }]),
  c.so('Tốc độ tiêu 1 giờ', [q('slo:api_availability_errors:ratio_rate1h{cell=~"$cell"} / 0.001')], 'none',
       [{ color: 'green', value: null }, { color: 'orange', value: 6 }, { color: 'red', value: 14.4 }]),
  c.so('Tốc độ tiêu 6 giờ', [q('slo:api_availability_errors:ratio_rate6h{cell=~"$cell"} / 0.001')], 'none',
       [{ color: 'green', value: null }, { color: 'orange', value: 6 }, { color: 'red', value: 14.4 }]),
  c.so('Request đọc nhanh (≤ 300 ms)', [q('slo:api_read_fast:ratio_rate5m{cell=~"$cell"}')], 'percentunit',
       [{ color: 'red', value: null }, { color: 'green', value: 0.95 }]),
  c.bieuDo('Tỷ lệ lỗi khả dụng', [q(loi, 'tỷ lệ lỗi'), q('0.001', 'mục tiêu 0,1%')], 'percentunit'),
  c.bieuDo('Tỷ lệ request trong ngưỡng độ trễ', [
    q('slo:api_read_fast:ratio_rate5m{cell=~"$cell"}', 'đọc ≤ 300 ms'),
    q('slo:api_write_fast:ratio_rate5m{cell=~"$cell"}', 'ghi ≤ 800 ms'),
    q('slo:api_async_fast:ratio_rate5m{cell=~"$cell"}', 'tác vụ dài ≤ 200 ms'),
  ], 'percentunit'),
  c.bieuDo('Phân vị độ trễ tại cổng', [
    q('histogram_quantile(0.95, sum by (le) (rate(apisix_http_latency_bucket{cell=~"$cell", type="request"}[$__rate_interval])))', 'p95'),
    q('histogram_quantile(0.99, sum by (le) (rate(apisix_http_latency_bucket{cell=~"$cell", type="request"}[$__rate_interval])))', 'p99'),
  ], 'ms'),
  c.bieuDo('Độ trễ dịch vụ (kèm exemplar sang Tempo)', [
    q('histogram_quantile(0.99, sum by (le, service) (rate(http_server_requests_seconds_bucket{cell=~"$cell"}[$__rate_interval])))', '{{service}} p99', true),
  ], 's'),
  c.bieuDo('Lưu lượng theo loại request', [q('sum by (kind) (slo:api_requests:rate5m{cell=~"$cell"})', '{{kind}}')], 'reqps'),
  c.bieuDo('Độ trễ tính điểm và ghi sổ cái', [
    q('histogram_quantile(0.99, sum by (le) (rate(vt_scoring_lag_seconds_bucket{cell=~"$cell"}[$__rate_interval])))', 'tính điểm p99 (mục tiêu 60 s)'),
    q('histogram_quantile(0.999, sum by (le) (rate(vt_ledger_lag_seconds_bucket{cell=~"$cell"}[$__rate_interval])))', 'sổ cái p99,9 (mục tiêu 30 s)'),
  ], 's'),
  c.nhatKy('Log lỗi của cell (mở trace qua trace_id)', '{cell=~"$cell", namespace=~".+"} | json | level=~"(?i)error|warn"'),
])
