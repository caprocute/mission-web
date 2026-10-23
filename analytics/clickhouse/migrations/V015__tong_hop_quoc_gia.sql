-- DV-15-11 · Baseline mục 8 cơ chế 7 → SVC-15 → agg_* → topic agg.province.metric.v1 → kho phân tích quốc gia (ADR-14).
-- TL-03 "Đồng bộ giữa các cell": topic agg.* sinh từ ClickHouse của cell bằng job tổng hợp theo đơn vị cấp tỉnh, không
-- có mã người. Tệp này điền ba bảng agg_* của V007 mỗi giờ (suy luận) và dựng khung xuất đã che nhóm nhỏ cho bộ xuất
-- agg_export.py. Không cột nào ở đây chứa person_key hay mã người: số người chỉ còn ở dạng đếm.

-- Đơn vị cấp tỉnh của một đơn vị: tổ tiên xa nhất trên cây DANG (cây CHUYEN_MON khi đơn vị không có trên cây DANG),
-- tức đơn vị gốc của tenant: Tỉnh uỷ, Thành uỷ hoặc Đảng bộ trực thuộc Trung ương (suy luận, org_replica chưa mang cột
-- cấp đơn vị; ghi ở wiki/14). Đơn vị chưa có trong bảng bao đóng thì tự làm đơn vị cấp tỉnh của chính nó.

-- Nhiệm vụ đã giao (không tính bản nháp và nhiệm vụ đã huỷ), gắn đơn vị chủ trì qua dict_task_unit như V006.
CREATE MATERIALIZED VIEW {{db}}.mv_agg_task_tinh ON CLUSTER '{{cluster}}'
REFRESH EVERY 1 HOUR APPEND
TO {{db}}.agg_task_tinh
AS
WITH tinh AS
(
  SELECT tenant_id, descendant_id AS org_unit_id, argMax(ancestor_id, (tree = 'DANG', depth)) AS province_unit_id
    FROM {{db}}.dim_org_closure FINAL
   GROUP BY tenant_id, descendant_id
),
nv AS
(
  SELECT t.tenant_id AS tenant_id, t.period_bucket AS period_bucket, t.period_id AS period_id,
         t.task_group AS task_group, t.status AS status, t.is_overdue AS is_overdue, t.is_key_task AS is_key_task,
         t.progress_pct AS progress_pct,
         dictGetOrDefault('{{db}}.dict_task_unit', 'org_unit_id', tuple(t.id),
                          toUUID('00000000-0000-0000-0000-000000000000')) AS org_unit_id
    FROM {{db}}.fact_task AS t FINAL
   WHERE t.status NOT IN ('NHAP', 'HUY')
)
SELECT nv.tenant_id AS tenant_id, nv.period_bucket AS period_bucket, nv.period_id AS period_id,
       if(empty(p.province_unit_id), nv.org_unit_id, p.province_unit_id) AS province_unit_id,
       nv.task_group AS task_group,
       count() AS n_task,
       countIf(nv.status = 'HOAN_THANH') AS n_completed,
       countIf(nv.is_overdue = 1) AS n_overdue,
       countIf(nv.is_key_task = 1 OR nv.task_group = 'THEN_CHOT') AS n_key_task,
       toDecimal32(avg(nv.progress_pct), 4) AS avg_progress_pct,
       now64(3) AS computed_at,
       toUInt64(toUnixTimestamp64Milli(now64(3))) AS _version
  FROM nv
  LEFT JOIN tinh AS p ON p.tenant_id = nv.tenant_id AND p.org_unit_id = nv.org_unit_id
 WHERE NOT empty(nv.org_unit_id)
 GROUP BY tenant_id, period_bucket, period_id, province_unit_id, task_group;

-- Điểm của bản chốt kỳ hiện hành (CHOT_KY, TINH_LAI_HAU_KIEM); bản tạm tính không lên kho quốc gia (suy luận).
CREATE MATERIALIZED VIEW {{db}}.mv_agg_score_tinh ON CLUSTER '{{cluster}}'
REFRESH EVERY 1 HOUR APPEND
TO {{db}}.agg_score_tinh
AS
WITH tinh AS
(
  SELECT tenant_id, descendant_id AS org_unit_id, argMax(ancestor_id, (tree = 'DANG', depth)) AS province_unit_id
    FROM {{db}}.dim_org_closure FINAL
   GROUP BY tenant_id, descendant_id
)
SELECT s.tenant_id AS tenant_id, s.period_bucket AS period_bucket, s.period_id AS period_id,
       if(empty(p.province_unit_id), s.org_unit_id, p.province_unit_id) AS province_unit_id,
       ifNull(s.subject_group_id, toUUID('00000000-0000-0000-0000-000000000000')) AS subject_group_id,
       s.flow AS flow,
       uniqExact(s.person_key) AS n_person,
       toDecimal32(avg(s.total_score), 3) AS avg_total_score,
       toDecimal32(quantileExact(0.5)(s.total_score), 3) AS p50_total_score,
       now64(3) AS computed_at,
       toUInt64(toUnixTimestamp64Milli(now64(3))) AS _version
  FROM {{db}}.fact_score AS s FINAL
  LEFT JOIN tinh AS p ON p.tenant_id = s.tenant_id AND p.org_unit_id = s.org_unit_id
 WHERE s.is_current = 1 AND s.snapshot_kind IN ('CHOT_KY', 'TINH_LAI_HAU_KIEM') AND NOT empty(s.org_unit_id)
 GROUP BY tenant_id, period_bucket, period_id, province_unit_id, subject_group_id, flow;

-- Mức xếp loại đã quyết định (QUYET_DINH, hiện hành); mức tự đề xuất, thẩm định, đề xuất không lên kho quốc gia.
CREATE MATERIALIZED VIEW {{db}}.mv_agg_rating_tinh ON CLUSTER '{{cluster}}'
REFRESH EVERY 1 HOUR APPEND
TO {{db}}.agg_rating_tinh
AS
WITH tinh AS
(
  SELECT tenant_id, descendant_id AS org_unit_id, argMax(ancestor_id, (tree = 'DANG', depth)) AS province_unit_id
    FROM {{db}}.dim_org_closure FINAL
   GROUP BY tenant_id, descendant_id
)
SELECT r.tenant_id AS tenant_id, r.period_bucket AS period_bucket, r.period_id AS period_id,
       if(empty(p.province_unit_id), r.org_unit_id, p.province_unit_id) AS province_unit_id,
       ifNull(r.subject_group_id, toUUID('00000000-0000-0000-0000-000000000000')) AS subject_group_id,
       r.flow AS flow,
       uniqExact(r.person_key) AS n_person,
       uniqExactIf(r.person_key, r.rating_code = 'HTXSNV') AS n_htxsnv,
       uniqExactIf(r.person_key, r.rating_code = 'HTTNV') AS n_httnv,
       uniqExactIf(r.person_key, r.rating_code = 'HTNV') AS n_htnv,
       uniqExactIf(r.person_key, r.rating_code = 'KHTNV') AS n_khtnv,
       now64(3) AS computed_at,
       toUInt64(toUnixTimestamp64Milli(now64(3))) AS _version
  FROM {{db}}.fact_rating AS r FINAL
  LEFT JOIN tinh AS p ON p.tenant_id = r.tenant_id AND p.org_unit_id = r.org_unit_id
 WHERE r.is_current = 1 AND r.level_kind = 'QUYET_DINH' AND NOT empty(r.org_unit_id)
 GROUP BY tenant_id, period_bucket, period_id, province_unit_id, subject_group_id, flow;

-- Khung xuất: mỗi chỉ số của một ô tổng hợp là một dòng theo lược đồ AggregateMetric. Ngưỡng che nhóm nhỏ k = 10
-- (suy luận từ task; chặt hơn k >= 5 của danh mục topic nên thoả cả hai): ô có số người (nhiệm vụ: số nhiệm vụ) dưới
-- 10 bị bỏ, không gộp, vì gộp ô nhỏ vào ô "tất cả" rồi trừ ngược với ô lớn sẽ lộ lại ô nhỏ. Không xuất dòng tổng
-- của tỉnh vì cùng lý do. provinceUnitRef là mã đơn vị cấp tỉnh hiện hành, không phải UUID nội bộ của tenant.
-- SQL SECURITY DEFINER: tài khoản xuất chỉ cần quyền đọc khung này, không đọc được agg_* hay bảng chiều.
CREATE VIEW {{db}}.v_agg_xuat ON CLUSTER '{{cluster}}'
DEFINER = CURRENT_USER SQL SECURITY DEFINER
AS
SELECT x.source AS source, x.tenant_id AS tenant_id, u.code AS province_unit_ref, x.period_id AS period_id,
       x.cell_id AS cell_id, concat(x.source, '.', x.m.1) AS metric_key, x.m.2 AS value, x.n AS count,
       x.computed_at AS computed_at
  FROM
  (
    SELECT 'task' AS source, tenant_id, province_unit_id, period_id, concat('task|', task_group) AS cell_id,
           n_task AS n, computed_at,
           arrayJoin([('n_task', toFloat64(n_task)), ('n_completed', toFloat64(n_completed)),
                      ('n_overdue', toFloat64(n_overdue)), ('n_key_task', toFloat64(n_key_task)),
                      ('avg_progress_pct', toFloat64(avg_progress_pct))]) AS m
      FROM {{db}}.agg_task_tinh FINAL
     WHERE n_task >= 10
    UNION ALL
    SELECT 'score' AS source, tenant_id, province_unit_id, period_id,
           concat('score|', toString(subject_group_id), '|', flow) AS cell_id, n_person AS n, computed_at,
           arrayJoin([('n_person', toFloat64(n_person)), ('avg_total_score', toFloat64(avg_total_score)),
                      ('p50_total_score', toFloat64(p50_total_score))]) AS m
      FROM {{db}}.agg_score_tinh FINAL
     WHERE n_person >= 10
    UNION ALL
    SELECT 'rating' AS source, tenant_id, province_unit_id, period_id,
           concat('rating|', toString(subject_group_id), '|', flow) AS cell_id, n_person AS n, computed_at,
           arrayJoin([('n_person', toFloat64(n_person)), ('n_htxsnv', toFloat64(n_htxsnv)),
                      ('n_httnv', toFloat64(n_httnv)), ('n_htnv', toFloat64(n_htnv)),
                      ('n_khtnv', toFloat64(n_khtnv))]) AS m
      FROM {{db}}.agg_rating_tinh FINAL
     WHERE n_person >= 10
  ) AS x
 INNER JOIN (SELECT tenant_id, id, code FROM {{db}}.dim_org_unit FINAL WHERE valid_to = toDate32('2299-12-31')) AS u
    ON u.tenant_id = x.tenant_id AND u.id = x.province_unit_id;

-- Mốc đã xuất của từng nguồn: bộ xuất chỉ phát dòng có computed_at sau mốc, rồi ghi mốc mới khi Kafka xác nhận.
CREATE TABLE {{db}}.agg_xuat_moc ON CLUSTER '{{cluster}}'
(
  source      LowCardinality(String),
  computed_at DateTime64(3, 'Asia/Ho_Chi_Minh'),
  exported_at DateTime64(3, 'Asia/Ho_Chi_Minh'),
  n_messages  UInt64,
  _version    UInt64
)
ENGINE = ReplicatedReplacingMergeTree(_version)
ORDER BY source;

-- Vai trò của bộ xuất. Tài khoản đăng nhập (mật khẩu từ OpenBao) do vận hành tạo và gán vai trò này, cùng cách tài
-- khoản quản trị của cụm (INF-11, wiki/14); migration không giữ thêm mật khẩu nào.
CREATE ROLE IF NOT EXISTS {{db}}_agg ON CLUSTER '{{cluster}}';
CREATE SETTINGS PROFILE IF NOT EXISTS {{db}}_agg_job ON CLUSTER '{{cluster}}'
  SETTINGS readonly = 0,
           max_execution_time = 600 MAX 1800,
           max_memory_usage = 4000000000 MAX 4000000000
  TO {{db}}_agg;
GRANT ON CLUSTER '{{cluster}}' SELECT ON {{db}}.v_agg_xuat TO {{db}}_agg;
GRANT ON CLUSTER '{{cluster}}' SELECT, INSERT ON {{db}}.agg_xuat_moc TO {{db}}_agg;
