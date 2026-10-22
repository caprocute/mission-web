// Nhóm 2: theo service, Four Golden Signals, pool kết nối, Kafka lag, Resilience4j (TL-04 mục 10.6).
local c = import '../lib/chung.libsonnet';
local q = c.q;
local f = 'cell=~"$cell", service=~"$service"';

c.dashboard('vt-service', 'Dịch vụ: tín hiệu vàng', [c.bienCell, c.bienNhan('service', 'service', 'http_server_requests_seconds_count', 'Dịch vụ')], [
  c.bieuDo('Lưu lượng', [q('sum by (method) (rate(http_server_requests_seconds_count{%s}[$__rate_interval]))' % f, '{{method}}')], 'reqps'),
  c.bieuDo('Tỷ lệ lỗi 5xx', [q('sum(rate(http_server_requests_seconds_count{%s, status=~"5.."}[$__rate_interval])) / sum(rate(http_server_requests_seconds_count{%s}[$__rate_interval]))' % [f, f], 'lỗi')], 'percentunit'),
  c.bieuDo('Độ trễ (kèm exemplar sang Tempo)', [
    q('histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{%s}[$__rate_interval])))' % f, 'p95', true),
    q('histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{%s}[$__rate_interval])))' % f, 'p99', true),
  ], 's'),
  c.bieuDo('Bão hoà: CPU và bộ nhớ', [
    q('sum(rate(container_cpu_usage_seconds_total{container=~"%s"}[$__rate_interval]))' % '$service', 'CPU (lõi)'),
    q('sum(container_memory_working_set_bytes{container=~"%s"}) / 2^30' % '$service', 'bộ nhớ (GiB)'),
  ]),
  c.bieuDo('Pool kết nối Hikari', [
    q('sum by (pool) (hikaricp_connections_active{%s})' % f, '{{pool}} đang dùng'),
    q('sum by (pool) (hikaricp_connections_pending{%s})' % f, '{{pool}} chờ'),
  ]),
  c.bieuDo('Kafka lag của consumer group', [q('sum by (consumergroup, topic) (kafka_consumergroup_lag{cell=~"$cell", consumergroup=~"$service.*"})', '{{consumergroup}} / {{topic}}')]),
  c.bieuDo('Circuit breaker đang mở', [q('sum by (name) (resilience4j_circuitbreaker_state{%s, state=~"open|half_open"})' % f, '{{name}}')]),
  c.bieuDo('Bulkhead: chỗ trống', [q('min by (name) (resilience4j_bulkhead_available_concurrent_calls{%s} / resilience4j_bulkhead_max_allowed_concurrent_calls{%s})' % [f, f], '{{name}}')], 'percentunit'),
  c.nhatKy('Log của dịch vụ', '{cell=~"$cell", namespace=~".+", service=~"$service"} | json'),
])
