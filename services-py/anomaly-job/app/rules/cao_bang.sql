-- DV-15-07 · QT-14 dấu hiệu 2 CAO_BANG "Xếp loại đồng loạt, cào bằng" (TL-02b, ngưỡng mặc định): trong một đơn vị,
-- một kỳ, một luồng, có ít nhất {min_people} hồ sơ đã có mức xếp loại, và một trong ba điều kiện: tỷ lệ HTXSNV vượt
-- {excellent_pct}% (ngưỡng khối, suy luận), từ {same_pct}% cán bộ trở lên cùng một mức, độ lệch chuẩn điểm hiện hành
-- dưới {min_std} điểm. Mức xếp loại của hồ sơ là mức hiện hành ở cấp cao nhất đã có (quyết định, đề xuất, thẩm định);
-- mức tự đề xuất không tính. Mức cảnh báo theo số điều kiện thoả: 1 THAP, 2 VUA, 3 CAO (suy luận).
INSERT INTO anomaly_run (tenant_id, alert_key, signal_code, severity, subject_type, subject_id, period_id,
                         evidence_chain, model_version, detected_at, run_id, published_at, _version)
WITH
muc AS
(
  SELECT profile_id,
         argMax(rating_code, transform(level_kind, ['THAM_DINH', 'DE_XUAT', 'QUYET_DINH'], [1, 2, 3], 0)) AS muc
  FROM fact_rating FINAL
  WHERE tenant_id = {tenant:UUID} AND is_current = 1 AND level_kind IN ('THAM_DINH', 'DE_XUAT', 'QUYET_DINH')
  GROUP BY profile_id
),
diem AS
(
  SELECT profile_id, any(toFloat64(total_score)) AS total_score
  FROM fact_score FINAL
  WHERE tenant_id = {tenant:UUID} AND is_current = 1
  GROUP BY profile_id
),
don_vi AS
(
  SELECT a.org_unit_id AS org_unit_id, a.period_id AS period_id, a.flow AS flow,
         count() AS n,
         countIf(m.muc = 'HTXSNV') AS n_xs,
         arrayMax(arrayMap(x -> countEqual(groupArray(m.muc), x), ['HTXSNV', 'HTTNV', 'HTNV', 'KHTNV'])) AS n_cung_muc,
         countIf(d.profile_id != toUUID('00000000-0000-0000-0000-000000000000')) AS n_diem,
         stddevPopIf(d.total_score, d.profile_id != toUUID('00000000-0000-0000-0000-000000000000')) AS do_lech,
         arraySort(groupArray(a.id)) AS ho_so
  FROM fact_appraisal_profile AS a FINAL
  INNER JOIN muc AS m ON m.profile_id = a.id
  LEFT JOIN diem AS d ON d.profile_id = a.id
  WHERE a.tenant_id = {tenant:UUID}
  GROUP BY a.org_unit_id, a.period_id, a.flow
  HAVING n >= {min_people:UInt32}
),
phat_hien AS
(
  SELECT *,
         n_xs * 100 > {excellent_pct:Float64} * n AS dk_xuat_sac,
         n_cung_muc * 100 >= {same_pct:Float64} * n AS dk_cung_muc,
         n_diem >= {min_people:UInt32} AND do_lech < {min_std:Float64} AS dk_do_lech,
         toUInt8(dk_xuat_sac) + toUInt8(dk_cung_muc) + toUInt8(dk_do_lech) AS so_dk
  FROM don_vi
  WHERE dk_xuat_sac OR dk_cung_muc OR dk_do_lech
)
SELECT {tenant:UUID} AS tenant_id,
       lower(hex(SHA256(concat(toString({tenant:UUID}), '|CAO_BANG|', toString(org_unit_id), '|', flow, '|',
                               toString(period_id))))) AS alert_key,
       'CAO_BANG' AS signal_code,
       multiIf(so_dk >= 3, 'CAO', so_dk = 2, 'VUA', 'THAP') AS severity,
       'DON_VI' AS subject_type,
       org_unit_id AS subject_id,
       period_id,
       toJSONString(arrayConcat(
         arrayFilter(x -> x != '', [if(dk_xuat_sac, 'dieu_kien:TY_LE_XUAT_SAC_VUOT_NGUONG', ''),
                                    if(dk_cung_muc, 'dieu_kien:CUNG_MOT_MUC', ''),
                                    if(dk_do_lech, 'dieu_kien:DO_LECH_DIEM_THAP', '')]),
         [concat('ky:', toString(period_id))],
         arrayMap(x -> concat('appraisal_profile:', toString(x)), ho_so))) AS evidence_chain,
       {model:String} AS model_version,
       now64(3) AS detected_at,
       {run_id:UUID} AS run_id,
       NULL AS published_at,
       1 AS _version
FROM phat_hien
