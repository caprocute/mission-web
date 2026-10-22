// Nhóm 3: hạ tầng dữ liệu (CNPG, Strimzi, Valkey, OpenSearch, ClickHouse, Ceph) theo TL-04 mục 10.6.
local c = import '../lib/chung.libsonnet';
local q = c.q;

c.dashboard('vt-ha-tang-du-lieu', 'Hạ tầng dữ liệu', [c.bienCell], [
  c.bieuDo('PostgreSQL: kết nối theo cụm', [q('sum by (cluster) (cnpg_backends_total{cell=~"$cell"})', '{{cluster}}')]),
  c.bieuDo('PostgreSQL: trễ sao chép', [q('max by (cluster) (cnpg_pg_replication_lag{cell=~"$cell"})', '{{cluster}}')], 's'),
  c.bieuDo('PostgreSQL: CPU primary pg-core', [q('ng:pg_core_primary_cpu:ratio{cell=~"$cell"}', 'CPU / giới hạn')], 'percentunit'),
  c.bieuDo('PgBouncer: client chờ', [q('sum by (database) (pgbouncer_pools_client_waiting_connections{cell=~"$cell"})', '{{database}}')]),
  c.bieuDo('Kafka: thông điệp vào mỗi giây', [q('sum by (topic) (rate(kafka_server_brokertopicmetrics_messagesin_total{cell=~"$cell", topic!=""}[$__rate_interval]))', '{{topic}}')]),
  c.bieuDo('Kafka: lag lớn nhất', [q('max by (consumergroup) (kafka_consumergroup_lag{cell=~"$cell"})', '{{consumergroup}}')]),
  c.bieuDo('Valkey: bộ nhớ so với maxmemory', [q('max by (pod) (redis_memory_used_bytes{cell=~"$cell"} / redis_memory_max_bytes{cell=~"$cell"})', '{{pod}}')], 'percentunit'),
  c.bieuDo('OpenSearch: heap', [q('ng:opensearch_heap:ratio{cell=~"$cell"}', 'heap lớn nhất')], 'percentunit'),
  c.bieuDo('ClickHouse: dung lượng đĩa đã dùng', [q('1 - ClickHouseAsyncMetrics_DiskAvailable_default{cell=~"$cell"} / ClickHouseAsyncMetrics_DiskTotal_default{cell=~"$cell"}', '{{pod}}')], 'percentunit'),
  c.bieuDo('Ceph: mức đầy cụm', [q('ceph_cluster_total_used_bytes{cell=~"$cell"} / ceph_cluster_total_bytes{cell=~"$cell"}', 'đã dùng'), q('0.6', 'ngưỡng đặt hàng ổ (NG-07)')], 'percentunit'),
])
