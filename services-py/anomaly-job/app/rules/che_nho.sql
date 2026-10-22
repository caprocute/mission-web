-- DV-15-08 · QT-14 dấu hiệu 7 CHE_NHO "Chẻ nhỏ công việc" (TL-02b, ngưỡng mặc định): số nhiệm vụ cá nhân chủ trì
-- trong kỳ tăng từ {inc_pct}% so với kỳ trước cùng loại (kỳ trước có ít nhất {min_tasks} nhiệm vụ, suy luận) trong
-- khi tổng khối lượng (tổng điểm chuẩn, suy luận) lệch không quá {tol_pct}%, hoặc có từ {same_due} nhiệm vụ trở lên
-- cùng mục kế hoạch và cùng hạn (suy luận cho "cùng sản phẩm"). Nhiệm vụ nháp và đã huỷ không tính. Đối tượng là cá
-- nhân: subject_id là mã hồ sơ đánh giá của người đó trong kỳ như dấu hiệu 1; chưa có hồ sơ thì đối tượng là đơn vị
-- của người đó lúc được giao (suy luận). Mức theo e (tỷ lệ tăng / ngưỡng; số nhiệm vụ cùng hạn / ngưỡng): thoả cả hai
-- điều kiện hoặc e ≥ 2 là CAO, e ≥ 1,5 là VUA, còn lại THAP (suy luận).
INSERT INTO anomaly_out (tenant_id, alert_key, signal_code, severity, subject_type, subject_id, period_id,
                         evidence_chain, model_version, detected_at, run_id, published_at, _version)
WITH
ky AS
(
  SELECT id AS ky_id, dense_rank() OVER (PARTITION BY kind, flow ORDER BY start_date) AS stt, kind, flow
  FROM dim_period FINAL
  WHERE tenant_id = {tenant:UUID}
),
nv AS
(
  SELECT assumeNotNull(a.person_key) AS nguoi, t.period_id AS ky_id, t.id AS nv_id, t.kl AS kl, t.ke_hoach AS ke_hoach,
         t.han AS han, a.resolved_unit_id AS don_vi
  FROM fact_task_assignee AS a FINAL
  INNER JOIN
  (
    SELECT id, period_id, toFloat64(ifNull(standard_score, 0)) AS kl, ifNull(plan_item_id,
           toUUID('00000000-0000-0000-0000-000000000000')) AS ke_hoach, due_date AS han
    FROM fact_task FINAL
    WHERE tenant_id = {tenant:UUID} AND status NOT IN ('NHAP', 'HUY')
  ) AS t ON t.id = a.task_id
  WHERE a.tenant_id = {tenant:UUID} AND a.role = 'CHU_TRI' AND a.assignee_type = 'CA_NHAN' AND a.removed_at IS NULL
    AND a.person_key IS NOT NULL
),
theo_ky AS
(
  SELECT nv.nguoi AS nguoi, nv.ky_id AS ky_id, k.kind AS kind, k.flow AS flow, toInt64(k.stt) AS stt, toInt64(k.stt) - 1 AS stt_truoc,
         count() AS so_nv, sum(nv.kl) AS tong_kl, groupArray(nv.nv_id) AS ds
  FROM nv
  INNER JOIN ky AS k ON k.ky_id = nv.ky_id
  GROUP BY nv.nguoi, nv.ky_id, k.kind, k.flow, k.stt
),
tang AS
(
  SELECT c.nguoi AS nguoi, c.ky_id AS ky_id, c.so_nv AS so_nv, p.so_nv AS so_nv_truoc, c.ds AS ds
  FROM theo_ky AS c
  INNER JOIN theo_ky AS p ON p.nguoi = c.nguoi AND p.kind = c.kind AND p.flow = c.flow AND p.stt = c.stt_truoc
  WHERE p.so_nv >= {min_tasks:UInt32}
    AND (toInt64(c.so_nv) - toInt64(p.so_nv)) * 100 >= {inc_pct:Float64} * p.so_nv
    AND abs(c.tong_kl - p.tong_kl) * 100 <= {tol_pct:Float64} * p.tong_kl
),
cung_han AS
(
  SELECT nguoi, ky_id, count() AS so, groupArray(nv_id) AS ds
  FROM nv
  WHERE ke_hoach != toUUID('00000000-0000-0000-0000-000000000000')
  GROUP BY nguoi, ky_id, ke_hoach, han
  HAVING so >= {same_due:UInt32}
),
gop AS
(
  SELECT nguoi, ky_id, max(a) = 1 AS dk_tang, max(b) = 1 AS dk_cung_han, max(so_nv) AS so_nv,
         max(so_nv_truoc) AS so_nv_truoc, max(so_cung_han) AS so_cung_han,
         arraySort(arrayDistinct(arrayFlatten(groupArray(ds)))) AS ds
  FROM
  (
    SELECT nguoi, ky_id, 1 AS a, 0 AS b, so_nv, so_nv_truoc, toUInt64(0) AS so_cung_han, ds FROM tang
    UNION ALL
    SELECT nguoi, ky_id, 0 AS a, 1 AS b, toUInt64(0) AS so_nv, toUInt64(0) AS so_nv_truoc, so AS so_cung_han, ds
    FROM cung_han
  )
  GROUP BY nguoi, ky_id
),
ho_so AS
(
  SELECT person_key, period_id, any(id) AS ho_so_id
  FROM fact_appraisal_profile FINAL
  WHERE tenant_id = {tenant:UUID}
  GROUP BY person_key, period_id
),
don_vi AS
(
  SELECT nguoi, ky_id, any(don_vi) AS dv FROM nv WHERE don_vi IS NOT NULL GROUP BY nguoi, ky_id
),
phat_hien AS
(
  SELECT g.nguoi AS nguoi, g.ky_id AS ky_id, g.dk_tang AS dk_tang, g.dk_cung_han AS dk_cung_han, g.so_nv AS so_nv,
         g.so_nv_truoc AS so_nv_truoc, g.so_cung_han AS so_cung_han, g.ds AS ds, h.ho_so_id AS ho_so_id, d.dv AS don_vi,
         greatest(if(g.dk_tang, (toInt64(g.so_nv) - toInt64(g.so_nv_truoc)) * 100 / g.so_nv_truoc / {inc_pct:Float64}, 0),
                  if(g.dk_cung_han, g.so_cung_han / {same_due:UInt32}, 0)) AS e
  FROM gop AS g
  LEFT JOIN ho_so AS h ON h.person_key = g.nguoi AND h.period_id = g.ky_id
  LEFT JOIN don_vi AS d ON d.nguoi = g.nguoi AND d.ky_id = g.ky_id
)
SELECT {tenant:UUID} AS tenant_id,
       lower(hex(SHA256(concat(toString({tenant:UUID}), '|CHE_NHO|', hex(nguoi), '|', toString(ky_id))))) AS alert_key,
       'CHE_NHO' AS signal_code,
       multiIf((dk_tang AND dk_cung_han) OR e >= 2, 'CAO', e >= 1.5, 'VUA', 'THAP') AS severity,
       if(ho_so_id != toUUID('00000000-0000-0000-0000-000000000000'), 'CA_NHAN', 'DON_VI') AS subject_type,
       if(ho_so_id != toUUID('00000000-0000-0000-0000-000000000000'), ho_so_id, assumeNotNull(don_vi)) AS subject_id,
       ky_id AS period_id,
       toJSONString(arrayConcat(
         arrayFilter(x -> x != '', [if(dk_tang, 'dieu_kien:SO_NHIEM_VU_TANG_KHOI_LUONG_KHONG_DOI', ''),
                                    if(dk_cung_han, 'dieu_kien:NHIEU_NHIEM_VU_CUNG_KE_HOACH_CUNG_HAN', ''),
                                    if(dk_tang, concat('so_lieu:SO_NHIEM_VU=', toString(so_nv)), ''),
                                    if(dk_tang, concat('so_lieu:SO_NHIEM_VU_KY_TRUOC=', toString(so_nv_truoc)), ''),
                                    if(dk_cung_han, concat('so_lieu:SO_NHIEM_VU_CUNG_HAN=', toString(so_cung_han)), '')]),
         [concat('ky:', toString(ky_id))],
         arrayMap(x -> concat('task:', toString(x)), arraySlice(ds, 1, 50)))) AS evidence_chain,
       {model:String} AS model_version,
       now64(3) AS detected_at,
       {run_id:UUID} AS run_id,
       NULL AS published_at,
       1 AS _version
FROM phat_hien
WHERE (ho_so_id != toUUID('00000000-0000-0000-0000-000000000000') OR don_vi IS NOT NULL)
  AND alert_key NOT IN (SELECT alert_key FROM anomaly_out WHERE tenant_id = {tenant:UUID})
