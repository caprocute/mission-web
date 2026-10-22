-- DV-15-07 · QT-14 dấu hiệu 1 TU_CHAM_CAO "Tự chấm cao hơn thẩm định có hệ thống" (TL-02b, ngưỡng mặc định):
-- mức tự đề xuất cao hơn mức thẩm định ở {consecutive} quý liên tiếp, hoặc điểm của ảnh chụp mà cán bộ thấy khi tự đề
-- xuất cao hơn điểm của ảnh chụp mà người thẩm định thấy từ {gap} điểm trở lên ở {score_quarters} quý liên tiếp.
-- Đối tượng là cán bộ (mã giả danh person_key), một cảnh báo cho mỗi (cán bộ, luồng, quý cuối của chuỗi);
-- subject_id là mã hồ sơ của quý cuối vì kho không có mã người dạng rõ (suy luận, wiki/14).
INSERT INTO anomaly_run (tenant_id, alert_key, signal_code, severity, subject_type, subject_id, period_id,
                         evidence_chain, model_version, detected_at, run_id, published_at, _version)
WITH
quy AS
(
  SELECT id AS period_id, dense_rank() OVER (ORDER BY start_date) AS stt
  FROM dim_period FINAL
  WHERE tenant_id = {tenant:UUID} AND kind = 'QUY'
),
muc AS
(
  SELECT r.profile_id AS profile_id,
         maxIf(transform(r.rating_code, ['KHTNV', 'HTNV', 'HTTNV', 'HTXSNV'], [1, 2, 3, 4], 0),
               r.level_kind = 'TU_DE_XUAT') AS muc_tu,
         maxIf(transform(r.rating_code, ['KHTNV', 'HTNV', 'HTTNV', 'HTXSNV'], [1, 2, 3, 4], 0),
               r.level_kind = 'THAM_DINH') AS muc_td,
         anyIf(r.score_snapshot_id, r.level_kind = 'TU_DE_XUAT') AS anh_tu,
         anyIf(r.score_snapshot_id, r.level_kind = 'THAM_DINH') AS anh_td
  FROM fact_rating AS r FINAL
  WHERE r.tenant_id = {tenant:UUID} AND r.is_current = 1 AND r.level_kind IN ('TU_DE_XUAT', 'THAM_DINH')
  GROUP BY r.profile_id
),
diem AS
(
  SELECT id, toFloat64(total_score) AS total_score
  FROM fact_score FINAL
  WHERE tenant_id = {tenant:UUID}
),
ho_so AS
(
  SELECT a.id AS profile_id, a.person_key AS person_key, a.flow AS flow, a.period_id AS period_id, q.stt AS stt,
         m.muc_tu > 0 AND m.muc_td > 0 AND m.muc_tu > m.muc_td AS cao_muc,
         s1.id != toUUID('00000000-0000-0000-0000-000000000000')
           AND s2.id != toUUID('00000000-0000-0000-0000-000000000000')
           AND s1.total_score - s2.total_score >= {gap:Float64} AS cao_diem
  FROM fact_appraisal_profile AS a FINAL
  INNER JOIN quy AS q ON q.period_id = a.period_id
  INNER JOIN muc AS m ON m.profile_id = a.id
  LEFT JOIN diem AS s1 ON s1.id = m.anh_tu
  LEFT JOIN diem AS s2 ON s2.id = m.anh_td
  WHERE a.tenant_id = {tenant:UUID}
),
-- Chuỗi liên tiếp theo kiểu "đảo và khoảng trống": trong các quý có cờ, stt - số thứ tự không đổi trong một chuỗi.
chuoi AS
(
  SELECT profile_id, person_key, flow, period_id, cao_muc, cao_diem,
         row_number() OVER (PARTITION BY person_key, flow, cao_muc, stt - rn_muc ORDER BY stt) AS dai_muc,
         row_number() OVER (PARTITION BY person_key, flow, cao_diem, stt - rn_diem ORDER BY stt) AS dai_diem,
         groupArray(profile_id) OVER (PARTITION BY person_key, flow, cao_muc, stt - rn_muc ORDER BY stt
                                      ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS hs_muc,
         groupArray(profile_id) OVER (PARTITION BY person_key, flow, cao_diem, stt - rn_diem ORDER BY stt
                                      ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS hs_diem
  FROM
  (
    SELECT *,
           row_number() OVER (PARTITION BY person_key, flow, cao_muc ORDER BY stt) AS rn_muc,
           row_number() OVER (PARTITION BY person_key, flow, cao_diem ORDER BY stt) AS rn_diem
    FROM ho_so
  )
),
phat_hien AS
(
  SELECT profile_id, person_key, flow, period_id,
         cao_muc AND dai_muc >= {consecutive:UInt32} AS dk_muc,
         cao_diem AND dai_diem >= {score_quarters:UInt32} AS dk_diem,
         arrayDistinct(arrayConcat(if(dk_muc, hs_muc, []), if(dk_diem, hs_diem, []))) AS ho_so_lien_quan
  FROM chuoi
  WHERE dk_muc OR dk_diem
)
SELECT {tenant:UUID} AS tenant_id,
       lower(hex(SHA256(concat(toString({tenant:UUID}), '|TU_CHAM_CAO|', hex(person_key), '|', flow, '|',
                               toString(period_id))))) AS alert_key,
       'TU_CHAM_CAO' AS signal_code,
       if(dk_muc AND dk_diem, 'CAO', 'VUA') AS severity,
       'CA_NHAN' AS subject_type,
       profile_id AS subject_id,
       period_id,
       toJSONString(arrayConcat(
         arrayFilter(x -> x != '', [if(dk_muc, 'dieu_kien:MUC_TU_DE_XUAT_CAO_HON', ''),
                                    if(dk_diem, 'dieu_kien:DIEM_TU_TINH_CAO_HON', '')]),
         [concat('ky:', toString(period_id))],
         arrayMap(x -> concat('appraisal_profile:', toString(x)), arraySort(ho_so_lien_quan)))) AS evidence_chain,
       {model:String} AS model_version,
       now64(3) AS detected_at,
       {run_id:UUID} AS run_id,
       NULL AS published_at,
       1 AS _version
FROM phat_hien
