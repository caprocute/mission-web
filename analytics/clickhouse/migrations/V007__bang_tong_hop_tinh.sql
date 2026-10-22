-- DV-15-01 · SVC-15: bảng tổng hợp theo đơn vị cấp tỉnh, nguồn cho job đẩy về kho quốc gia (DV-15-11).
-- Task này chỉ tạo bảng; DV-15-11 điền dữ liệu mỗi giờ (suy luận) và áp ngưỡng che nhóm nhỏ trước khi đẩy ra topic
-- agg.*. Bảng chỉ có số đếm và giá trị trung bình, không có person_key hay mã người; n_person giữ lại để DV-15-11
-- áp ngưỡng k. _version là thời điểm tính (mili giây) để lần tính sau thay lần trước.

CREATE TABLE {{db}}.agg_task_tinh ON CLUSTER '{{cluster}}'
(
  tenant_id        UUID,
  period_bucket    Int32,
  period_id        UUID,
  province_unit_id UUID,
  task_group       LowCardinality(String),
  n_task           UInt64,
  n_completed      UInt64,
  n_overdue        UInt64,
  n_key_task       UInt64,
  avg_progress_pct Decimal(6, 4),
  computed_at      DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _version         UInt64
)
ENGINE = ReplicatedReplacingMergeTree(_version)
PARTITION BY period_bucket
ORDER BY (tenant_id, period_id, province_unit_id, task_group);

CREATE TABLE {{db}}.agg_score_tinh ON CLUSTER '{{cluster}}'
(
  tenant_id        UUID,
  period_bucket    Int32,
  period_id        UUID,
  province_unit_id UUID,
  subject_group_id UUID,
  flow             LowCardinality(String),
  n_person         UInt64,
  avg_total_score  Decimal(6, 3),
  p50_total_score  Decimal(6, 3),
  computed_at      DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _version         UInt64
)
ENGINE = ReplicatedReplacingMergeTree(_version)
PARTITION BY period_bucket
ORDER BY (tenant_id, period_id, province_unit_id, subject_group_id, flow);

CREATE TABLE {{db}}.agg_rating_tinh ON CLUSTER '{{cluster}}'
(
  tenant_id        UUID,
  period_bucket    Int32,
  period_id        UUID,
  province_unit_id UUID,
  subject_group_id UUID,
  flow             LowCardinality(String),
  n_person         UInt64,
  n_htxsnv         UInt64,
  n_httnv          UInt64,
  n_htnv           UInt64,
  n_khtnv          UInt64,
  computed_at      DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _version         UInt64
)
ENGINE = ReplicatedReplacingMergeTree(_version)
PARTITION BY period_bucket
ORDER BY (tenant_id, period_id, province_unit_id, subject_group_id, flow);
