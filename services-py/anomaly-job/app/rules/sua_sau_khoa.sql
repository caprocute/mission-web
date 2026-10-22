-- DV-15-09 · QT-14 dấu hiệu 6 SUA_SAU_KHOA "Sửa dữ liệu khi đã biết kết quả" (TL-02b): có thay đổi mục tiêu, nghiệm
-- thu sau thời điểm tính điểm tạm, hoặc thay đổi nhiệm vụ sau khoá kỳ (qua mở lại). Với mỗi hồ sơ đánh giá: mốc tạm
-- tính là ảnh chụp TAM_TINH đầu tiên, mốc khoá là locked_at của hồ sơ. Thay đổi được xét (suy luận, wiki/14):
-- nhiệm vụ cá nhân chủ trì trong kỳ được nghiệm thu (completed_at) sau mốc tạm tính hoặc sửa (updated_at) sau mốc
-- khoá; mục tiêu cá nhân của người đó được duyệt (approved_at) sau mốc tạm tính hoặc sau mốc khoá. Kho không có thời
-- điểm sửa kết quả then chốt và mục kế hoạch nên hai loại đó chưa xét. Luôn mức CAO; so_lieu CHENH_DIEM là độ chênh
-- giữa điểm ảnh chụp mới nhất và điểm tạm tính đầu tiên để MOD-07 xếp theo mức ảnh hưởng điểm. Một cảnh báo mỗi hồ sơ.
INSERT INTO anomaly_run (tenant_id, alert_key, signal_code, severity, subject_type, subject_id, period_id,
                         evidence_chain, model_version, detected_at, run_id, published_at, _version)
WITH
ho_so AS
(
  SELECT a.id AS ho_so_id, a.person_key AS nguoi, a.period_id AS ky_id, a.locked_at AS khoa_luc,
         s.tam_luc AS tam_luc, s.diem_tam AS diem_tam, s.diem_moi AS diem_moi
  FROM fact_appraisal_profile AS a FINAL
  INNER JOIN
  (
    SELECT profile_id,
           minIf(computed_at, snapshot_kind = 'TAM_TINH') AS tam_luc,
           argMinIf(toFloat64(total_score), computed_at, snapshot_kind = 'TAM_TINH') AS diem_tam,
           argMax(toFloat64(total_score), computed_at) AS diem_moi
    FROM fact_score FINAL
    WHERE tenant_id = {tenant:UUID}
    GROUP BY profile_id
    HAVING countIf(snapshot_kind = 'TAM_TINH') > 0
  ) AS s ON s.profile_id = a.id
  WHERE a.tenant_id = {tenant:UUID}
),
nhiem_vu AS
(
  SELECT h.ho_so_id AS ho_so_id, t.id AS ma,
         ifNull(t.completed_at > h.tam_luc, 0) AS sau_tam,
         ifNull(t.updated_at > h.khoa_luc, 0) AS sau_khoa
  FROM fact_task_assignee AS a FINAL
  INNER JOIN
  (
    SELECT id, period_id, completed_at, updated_at
    FROM fact_task FINAL
    WHERE tenant_id = {tenant:UUID} AND status NOT IN ('NHAP', 'HUY')
  ) AS t ON t.id = a.task_id
  INNER JOIN ho_so AS h ON h.nguoi = a.person_key AND h.ky_id = t.period_id
  WHERE a.tenant_id = {tenant:UUID} AND a.role = 'CHU_TRI' AND a.assignee_type = 'CA_NHAN' AND a.removed_at IS NULL
    AND a.person_key IS NOT NULL
),
muc_tieu AS
(
  SELECT h.ho_so_id AS ho_so_id, o.id AS ma,
         ifNull(o.approved_at > h.tam_luc, 0) AS sau_tam,
         ifNull(o.approved_at > h.khoa_luc, 0) AS sau_khoa
  FROM fact_okr_objective AS o FINAL
  INNER JOIN ho_so AS h ON h.nguoi = o.owner_key AND h.ky_id = o.period_id
  WHERE o.tenant_id = {tenant:UUID} AND o.owner_type = 'CA_NHAN' AND o.approved_at IS NOT NULL
),
gop AS
(
  SELECT ho_so_id,
         max(loai = 'nv' AND sau_tam) = 1 AS dk_nghiem_thu,
         max(loai = 'mt' AND sau_tam) = 1 AS dk_muc_tieu,
         max(sau_khoa) = 1 AS dk_khoa,
         arraySort(groupArrayIf(concat(if(loai = 'nv', 'task:', 'objective:'), toString(ma)), sau_tam OR sau_khoa))
           AS bang_chung
  FROM
  (
    SELECT ho_so_id, 'nv' AS loai, ma, sau_tam, sau_khoa FROM nhiem_vu
    UNION ALL
    SELECT ho_so_id, 'mt' AS loai, ma, sau_tam, sau_khoa FROM muc_tieu
  )
  GROUP BY ho_so_id
  HAVING dk_nghiem_thu OR dk_muc_tieu OR dk_khoa
)
SELECT {tenant:UUID} AS tenant_id,
       lower(hex(SHA256(concat(toString({tenant:UUID}), '|SUA_SAU_KHOA|', toString(h.ho_so_id))))) AS alert_key,
       'SUA_SAU_KHOA' AS signal_code,
       'CAO' AS severity,
       'CA_NHAN' AS subject_type,
       h.ho_so_id AS subject_id,
       h.ky_id AS period_id,
       toJSONString(arrayConcat(
         arrayFilter(x -> x != '', [if(g.dk_nghiem_thu, 'dieu_kien:NGHIEM_THU_SAU_TAM_TINH', ''),
                                    if(g.dk_muc_tieu, 'dieu_kien:MUC_TIEU_DUYET_SAU_TAM_TINH', ''),
                                    if(g.dk_khoa, 'dieu_kien:SUA_SAU_KHOA_KY', '')]),
         [concat('so_lieu:CHENH_DIEM=', toString(round(abs(h.diem_moi - h.diem_tam), 2))),
          concat('ky:', toString(h.ky_id)), concat('appraisal_profile:', toString(h.ho_so_id))],
         arraySlice(g.bang_chung, 1, 50))) AS evidence_chain,
       {model:String} AS model_version,
       now64(3) AS detected_at,
       {run_id:UUID} AS run_id,
       NULL AS published_at,
       1 AS _version
FROM gop AS g
INNER JOIN ho_so AS h ON h.ho_so_id = g.ho_so_id
