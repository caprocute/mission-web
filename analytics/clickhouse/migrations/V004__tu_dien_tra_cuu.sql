-- DV-15-01 · SVC-15: từ điển gắn đơn vị và người cho bảng sự kiện mà dòng nguồn không mang cột đơn vị
-- (task_progress, evidence, kr_checkin, score_snapshot, rating_proposal). Nguồn từ điển là bảng sự kiện, bảng tra
-- cứu trong cùng CSDL; tài khoản nạp chỉ được SELECT đúng các bảng đó (suy luận, ghi ở wiki/14).
-- Ràng buộc nghiệp vụ làm cho tra cứu đúng lúc nạp: tiến độ, minh chứng chỉ có sau khi nhiệm vụ đã giao (dòng
-- task_assignee chủ trì cùng giao dịch giao việc), check-in chỉ có sau khi KR đã lập, ảnh chụp điểm sau hồ sơ.

CREATE USER IF NOT EXISTS {{db}}_dict ON CLUSTER '{{cluster}}'
  IDENTIFIED WITH sha256_password BY '{{dict_password}}'
  SETTINGS readonly = 1;

GRANT ON CLUSTER '{{cluster}}' SELECT ON {{db}}.fact_task_assignee TO {{db}}_dict;
GRANT ON CLUSTER '{{cluster}}' SELECT ON {{db}}.lk_assignment TO {{db}}_dict;
GRANT ON CLUSTER '{{cluster}}' SELECT ON {{db}}.lk_objective TO {{db}}_dict;
GRANT ON CLUSTER '{{cluster}}' SELECT ON {{db}}.lk_key_result TO {{db}}_dict;
GRANT ON CLUSTER '{{cluster}}' SELECT ON {{db}}.lk_appraisal_profile TO {{db}}_dict;

-- Đơn vị chủ trì hiện hành của nhiệm vụ: đơn vị được giao chủ trì, hoặc đơn vị của phân công chính của cá nhân
-- chủ trì.
CREATE DICTIONARY {{db}}.dict_task_unit ON CLUSTER '{{cluster}}'
(
  task_id     UUID,
  org_unit_id UUID
)
PRIMARY KEY task_id
SOURCE(CLICKHOUSE(
  QUERY 'SELECT a.task_id AS task_id, any(coalesce(a.org_unit_id, p.org_unit_id)) AS org_unit_id
           FROM {{db}}.fact_task_assignee AS a FINAL
           LEFT JOIN (SELECT tenant_id, person_key, any(org_unit_id) AS org_unit_id
                        FROM {{db}}.lk_assignment FINAL
                       WHERE kind = ''CHINH'' AND valid_to = toDate32(''2299-12-31'')
                       GROUP BY tenant_id, person_key) AS p
             ON p.tenant_id = a.tenant_id AND p.person_key = a.person_key
          WHERE a.role = ''CHU_TRI'' AND a.removed_at IS NULL AND a.valid_to = toDate32(''2299-12-31'')
            AND coalesce(a.org_unit_id, p.org_unit_id) IS NOT NULL
          GROUP BY a.task_id'
  USER '{{db}}_dict' PASSWORD '{{dict_password}}'))
LIFETIME(MIN 5 MAX 10)
LAYOUT(COMPLEX_KEY_HASHED());

-- Kết quả then chốt → mục tiêu → đơn vị sở hữu mục tiêu.
CREATE DICTIONARY {{db}}.dict_kr_unit ON CLUSTER '{{cluster}}'
(
  key_result_id UUID,
  objective_id  UUID,
  org_unit_id   UUID
)
PRIMARY KEY key_result_id
SOURCE(CLICKHOUSE(
  QUERY 'SELECT k.id AS key_result_id, k.objective_id AS objective_id, o.owner_unit_id AS org_unit_id
           FROM {{db}}.lk_key_result AS k FINAL
           INNER JOIN {{db}}.lk_objective AS o FINAL ON o.tenant_id = k.tenant_id AND o.id = k.objective_id'
  USER '{{db}}_dict' PASSWORD '{{dict_password}}'))
LIFETIME(MIN 5 MAX 10)
LAYOUT(COMPLEX_KEY_HASHED());

-- Hồ sơ đánh giá → người (đã giả danh), đơn vị lúc lập hồ sơ, khối đối tượng, luồng.
CREATE DICTIONARY {{db}}.dict_profile ON CLUSTER '{{cluster}}'
(
  profile_id       UUID,
  person_key       String,
  org_unit_id      UUID,
  subject_group_id Nullable(UUID),
  flow             String
)
PRIMARY KEY profile_id
SOURCE(CLICKHOUSE(
  QUERY 'SELECT id AS profile_id, toString(person_key) AS person_key, org_unit_id, subject_group_id,
                toString(flow) AS flow
           FROM {{db}}.lk_appraisal_profile FINAL'
  USER '{{db}}_dict' PASSWORD '{{dict_password}}'))
LIFETIME(MIN 5 MAX 10)
LAYOUT(COMPLEX_KEY_HASHED());
