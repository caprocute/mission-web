-- DV-15-03 · QT-16 (BR-16-01, BR-16-08) → SVC-15: bảng dẫn xuất cho lớp ngữ nghĩa Cube, làm mới định kỳ bằng
-- refreshable materialized view ghi đè toàn bộ bảng đích sau mỗi lần tính. Bảng đích dùng MergeTree cục bộ: mỗi
-- bản sao tự tính lại từ bảng đã sao chép.

-- Phạm vi cây tổ chức: mỗi đơn vị đang hiệu lực một dòng, kèm đường dẫn org_path trên hai cây DANG và CHUYEN_MON,
-- ghép từ mã đơn vị (chữ thường) của tổ tiên theo thứ tự từ gốc, ví dụ '/tw/vptw/vu-tong-hop'. Cube so org_path
-- trong securityContext (JWT) với hai cột này để giới hạn số liệu trong nhánh của người xem (suy luận về định dạng
-- org_path: TL chưa chốt, ghi ở wiki/14).
CREATE TABLE {{db}}.dim_org_scope ON CLUSTER '{{cluster}}'
(
  tenant_id           UUID,
  id                  UUID,
  code                String,
  name                String,
  unit_type           LowCardinality(String),
  subject_group_id    Nullable(UUID),
  party_parent_id     Nullable(UUID),
  admin_parent_id     Nullable(UUID),
  org_path_dang       String,
  org_path_chuyen_mon String,
  _source_ts          DateTime64(3, 'Asia/Ho_Chi_Minh')
)
ENGINE = MergeTree
ORDER BY (tenant_id, id);

CREATE MATERIALIZED VIEW {{db}}.mv_dim_org_scope ON CLUSTER '{{cluster}}'
REFRESH EVERY 1 MINUTE
TO {{db}}.dim_org_scope
AS
WITH paths AS
(
  SELECT c.tenant_id AS tenant_id, c.tree AS tree, c.descendant_id AS org_unit_id,
         concat('/', arrayStringConcat(arrayMap(x -> x.2, arrayReverseSort(x -> x.1, groupArray((c.depth, lower(a.code))))),
                                       '/')) AS org_path
    FROM {{db}}.dim_org_closure AS c FINAL
   INNER JOIN (SELECT tenant_id, id, code FROM {{db}}.dim_org_unit FINAL
                WHERE valid_to = toDate32('2299-12-31')) AS a
      ON a.tenant_id = c.tenant_id AND a.id = c.ancestor_id
   GROUP BY c.tenant_id, c.tree, c.descendant_id
)
SELECT u.tenant_id AS tenant_id, u.id AS id, u.code AS code, u.name AS name, u.unit_type AS unit_type,
       u.subject_group_id AS subject_group_id, u.party_parent_id AS party_parent_id,
       u.admin_parent_id AS admin_parent_id,
       anyIf(p.org_path, p.tree = 'DANG') AS org_path_dang,
       anyIf(p.org_path, p.tree = 'CHUYEN_MON') AS org_path_chuyen_mon,
       max(u._source_ts) AS _source_ts
  FROM {{db}}.dim_org_unit AS u FINAL
  LEFT JOIN paths AS p ON p.tenant_id = u.tenant_id AND p.org_unit_id = u.id
 WHERE u.valid_to = toDate32('2299-12-31')
 GROUP BY u.tenant_id, u.id, u.code, u.name, u.unit_type, u.subject_group_id, u.party_parent_id, u.admin_parent_id;

-- Độ mới dữ liệu theo tenant và nguồn (BR-16-08, MSG-16-01): thời điểm thay đổi nguồn mới nhất đã vào kho và thời
-- điểm nạp. analytics-api (DV-15-04) báo stale khi trễ quá 5 phút.
CREATE TABLE {{db}}.data_freshness ON CLUSTER '{{cluster}}'
(
  tenant_id        UUID,
  source           LowCardinality(String),
  last_source_ts   DateTime64(3, 'Asia/Ho_Chi_Minh'),
  last_ingested_at DateTime64(3, 'Asia/Ho_Chi_Minh')
)
ENGINE = MergeTree
ORDER BY (tenant_id, source);

CREATE MATERIALIZED VIEW {{db}}.mv_data_freshness ON CLUSTER '{{cluster}}'
REFRESH EVERY 1 MINUTE
TO {{db}}.data_freshness
AS
SELECT tenant_id, source, max(source_ts) AS last_source_ts, max(ingested_at) AS last_ingested_at
FROM
(
  SELECT tenant_id, 'task' AS source, _source_ts AS source_ts, _ingested_at AS ingested_at FROM {{db}}.fact_task
  UNION ALL
  SELECT tenant_id, 'task_progress', _source_ts, _ingested_at FROM {{db}}.fact_task_progress
  UNION ALL
  SELECT tenant_id, 'evidence', _source_ts, _ingested_at FROM {{db}}.fact_evidence
  UNION ALL
  SELECT tenant_id, 'okr_objective', _source_ts, _ingested_at FROM {{db}}.fact_okr_objective
  UNION ALL
  SELECT tenant_id, 'okr_checkin', _source_ts, _ingested_at FROM {{db}}.fact_okr_checkin
  UNION ALL
  SELECT tenant_id, 'appraisal_profile', _source_ts, _ingested_at FROM {{db}}.fact_appraisal_profile
  UNION ALL
  SELECT tenant_id, 'score', _source_ts, _ingested_at FROM {{db}}.fact_score
  UNION ALL
  SELECT tenant_id, 'rating', _source_ts, _ingested_at FROM {{db}}.fact_rating
)
GROUP BY tenant_id, source;
