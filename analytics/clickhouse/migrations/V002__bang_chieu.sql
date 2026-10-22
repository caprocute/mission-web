-- DV-15-01 · QT-14, QT-16 → SVC-15 · TL-03 "Mô hình sao trong ClickHouse" (Hình 14.1).
-- Năm bảng chiều. Mọi bảng có tenant_id đứng đầu khoá sắp xếp để Cube áp bộ lọc an ninh và row policy theo
-- tenant hoạt động. _version lấy từ __lsn của Debezium, _is_deleted từ __deleted; đọc trạng thái cuối bằng FINAL.

-- Chiều thay đổi chậm loại 2 dựng từ trục hiệu lực của org_replica.org_unit_v: mỗi phiên bản đơn vị là một dòng
-- (valid_from, valid_to), báo cáo của kỳ cũ nối theo ngày cuối kỳ nằm trong khoảng hiệu lực.
CREATE TABLE {{db}}.dim_org_unit ON CLUSTER '{{cluster}}'
(
  tenant_id        UUID,
  id               UUID,
  valid_from       Date32,
  valid_to         Date32,
  code             String,
  name             String,
  unit_type        LowCardinality(String),
  party_parent_id  Nullable(UUID),
  admin_parent_id  Nullable(UUID),
  subject_group_id Nullable(UUID),
  source_version   Int64,
  _version         UInt64,
  _is_deleted      UInt8,
  _source_ts       DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at     DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
ORDER BY (tenant_id, id, valid_from);

-- Chỉ khoá giả danh và thuộc tính không định danh. org_replica.person_v không có năm sinh, giới tính nên hai
-- thuộc tính làm thô này chưa có nguồn (ghi ở wiki/14); họ tên và mã CBCCVC không bao giờ vào đây.
CREATE TABLE {{db}}.dim_person ON CLUSTER '{{cluster}}'
(
  tenant_id         UUID,
  person_key        FixedString(32),
  is_party_member   UInt8,
  employment_status LowCardinality(String),
  _version          UInt64,
  _is_deleted       UInt8,
  _source_ts        DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at      DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
ORDER BY (tenant_id, person_key);

CREATE TABLE {{db}}.dim_period ON CLUSTER '{{cluster}}'
(
  tenant_id        UUID,
  id               UUID,
  code             String,
  name             String,
  kind             LowCardinality(String),
  flow             LowCardinality(String),
  parent_period_id Nullable(UUID),
  start_date       Date32,
  end_date         Date32,
  period_bucket    Int32,
  status           LowCardinality(String),
  _version         UInt64,
  _is_deleted      UInt8,
  _source_ts       DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at     DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
ORDER BY (tenant_id, id);

-- Khối đối tượng theo PL 2A, nguồn org.subject_group của org-service (CSDL riêng của SVC-04).
CREATE TABLE {{db}}.dim_subject_group ON CLUSTER '{{cluster}}'
(
  tenant_id    UUID,
  id           UUID,
  code         LowCardinality(String),
  name         String,
  flow         LowCardinality(String),
  valid_from   Date32,
  valid_to     Date32,
  _version     UInt64,
  _is_deleted  UInt8,
  _source_ts   DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
ORDER BY (tenant_id, id);

-- Phiên bản Policy Pack dùng để tính điểm (policy.policy_pack_version, chỉ siêu dữ liệu, không nội dung gói).
CREATE TABLE {{db}}.dim_policy_version ON CLUSTER '{{cluster}}'
(
  tenant_id      UUID,
  id             UUID,
  policy_pack_id UUID,
  version        String,
  status         LowCardinality(String),
  effective_from Nullable(Date32),
  published_at   Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh')),
  _version       UInt64,
  _is_deleted    UInt8,
  _source_ts     DateTime64(3, 'Asia/Ho_Chi_Minh'),
  _ingested_at   DateTime64(3, 'Asia/Ho_Chi_Minh') DEFAULT now64(3)
)
ENGINE = ReplicatedReplacingMergeTree(_version, _is_deleted)
ORDER BY (tenant_id, id);
