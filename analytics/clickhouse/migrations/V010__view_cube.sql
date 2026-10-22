-- DV-15-03 · QT-16 (BR-16-01) → SVC-15: view nguồn của lớp ngữ nghĩa Cube. Mỗi cube đọc đúng một view (sql_table),
-- không đọc bảng trực tiếp, vì hai lý do:
--  1. Cube dựng pre-aggregation bằng CREATE TABLE ... AS SELECT. Ở ClickHouse 25.8, câu lệnh đó có truy vấn con mà tài
--     khoản chỉ có quyền theo cột thì bị từ chối (trình phân tích mới đòi quyền SELECT cả bảng); đọc qua view thì không.
--  2. Định nghĩa dòng (FINAL, chỉ dòng hiện hành, đơn vị chủ trì, check-in gần nhất) nằm một chỗ trong kho.
-- View đặt SQL SECURITY INVOKER: truy vấn chạy bằng quyền của Cube, nên quyền theo cột và row policy theo tenant ở V009
-- vẫn áp lên bảng bên dưới. Không dùng DEFINER vì như vậy row policy không áp cho người gọi.

-- Nhiệm vụ gắn đơn vị chủ trì đang hiệu lực (đơn vị được giao, hoặc đơn vị phân công chính của cá nhân chủ trì). Hạn hiệu
-- lực = hạn gia hạn nếu có; ngay_lech là số ngày lịch giữa ngày hoàn thành (hoặc hôm nay nếu chưa xong) và hạn hiệu lực.
CREATE VIEW {{db}}.cube_task ON CLUSTER '{{cluster}}' SQL SECURITY INVOKER AS
SELECT t.tenant_id AS tenant_id, t.id AS id, t.period_id AS period_id, t.task_group AS task_group,
       t.is_key_task AS is_key_task, t.status AS status, coalesce(t.extended_due_date, t.due_date) AS han,
       t.completed_at AS completed_at, t.assigned_at AS assigned_at, a.org_unit_id AS org_unit_id,
       dateDiff('day', coalesce(t.extended_due_date, t.due_date),
                if(t.status = 'HOAN_THANH', toDate32(t.completed_at), toDate32(now('Asia/Ho_Chi_Minh')))) AS ngay_lech,
       t.status NOT IN ('NHAP', 'HUY', 'HOAN_THANH')
         AND (t.is_overdue = 1 OR coalesce(t.extended_due_date, t.due_date) < toDate32(now('Asia/Ho_Chi_Minh')))
         AS dang_cham
  FROM {{db}}.fact_task AS t FINAL
  LEFT JOIN
  (
    SELECT tenant_id, task_id, any(resolved_unit_id) AS org_unit_id
      FROM {{db}}.fact_task_assignee FINAL
     WHERE role = 'CHU_TRI' AND removed_at IS NULL AND valid_to = toDate32('2299-12-31')
     GROUP BY tenant_id, task_id
  ) AS a ON a.tenant_id = t.tenant_id AND a.task_id = t.id
 WHERE t.status NOT IN ('NHAP', 'HUY');

-- Mục tiêu O đã duyệt hoặc đã đóng; progress do mission-core tổng hợp từ KR và check-in.
CREATE VIEW {{db}}.cube_okr ON CLUSTER '{{cluster}}' SQL SECURITY INVOKER AS
SELECT tenant_id, id, period_id, org_unit_id, owner_type, tier, status, progress
  FROM {{db}}.fact_okr_objective FINAL
 WHERE status IN ('DA_DUYET', 'DONG');

-- Lần check-in gần nhất đã gửi của mỗi KR.
CREATE VIEW {{db}}.cube_okr_checkin ON CLUSTER '{{cluster}}' SQL SECURITY INVOKER AS
SELECT c.tenant_id AS tenant_id, c.key_result_id AS key_result_id, argMax(c.period_id, c.checkin_at) AS period_id,
       argMax(c.org_unit_id, c.checkin_at) AS org_unit_id, argMax(c.confidence, c.checkin_at) AS confidence,
       max(c.checkin_at) AS lan_gan_nhat
  FROM {{db}}.fact_okr_checkin AS c FINAL
 WHERE c.status != 'NHAP'
 GROUP BY c.tenant_id, c.key_result_id;

CREATE VIEW {{db}}.cube_appraisal ON CLUSTER '{{cluster}}' SQL SECURITY INVOKER AS
SELECT tenant_id, id, period_id, org_unit_id, flow, status, final_rating_code, is_head, is_leadership
  FROM {{db}}.fact_appraisal_profile FINAL;

-- Ảnh chụp điểm hiện hành của mỗi hồ sơ.
CREATE VIEW {{db}}.cube_score ON CLUSTER '{{cluster}}' SQL SECURITY INVOKER AS
SELECT tenant_id, id, period_id, org_unit_id, flow, snapshot_kind, toFloat64(total_score) AS total_score,
       auto_rating_code
  FROM {{db}}.fact_score FINAL
 WHERE is_current = 1;

-- Mức xếp loại hiện hành ở từng cấp.
CREATE VIEW {{db}}.cube_rating ON CLUSTER '{{cluster}}' SQL SECURITY INVOKER AS
SELECT tenant_id, id, period_id, org_unit_id, flow, level_kind, rating_code, differs_from_prev
  FROM {{db}}.fact_rating FINAL
 WHERE is_current = 1;

CREATE VIEW {{db}}.cube_evidence ON CLUSTER '{{cluster}}' SQL SECURITY INVOKER AS
SELECT tenant_id, id, period_id, org_unit_id, current_status, source_type, created_at
  FROM {{db}}.fact_evidence FINAL;

CREATE VIEW {{db}}.cube_period ON CLUSTER '{{cluster}}' SQL SECURITY INVOKER AS
SELECT tenant_id, id, code, name, kind, flow, start_date, end_date, status
  FROM {{db}}.dim_period FINAL;

CREATE VIEW {{db}}.cube_subject_group ON CLUSTER '{{cluster}}' SQL SECURITY INVOKER AS
SELECT tenant_id, id, code, name, flow
  FROM {{db}}.dim_subject_group FINAL
 WHERE valid_to >= toDate32(now('Asia/Ho_Chi_Minh'));

GRANT ON CLUSTER '{{cluster}}' SELECT ON {{db}}.cube_task TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT ON {{db}}.cube_okr TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT ON {{db}}.cube_okr_checkin TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT ON {{db}}.cube_appraisal TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT ON {{db}}.cube_score TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT ON {{db}}.cube_rating TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT ON {{db}}.cube_evidence TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT ON {{db}}.cube_period TO {{db}}_cube;
GRANT ON CLUSTER '{{cluster}}' SELECT ON {{db}}.cube_subject_group TO {{db}}_cube;
