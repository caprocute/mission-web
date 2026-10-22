-- DV-15-08 · QT-14 dấu hiệu 3 MUC_TIEU_DE "Mục tiêu đặt quá dễ" (TL-02b, ngưỡng mặc định): KR đã duyệt đạt chỉ tiêu
-- (check-in đã gửi) ngay trong {early_weeks} tuần đầu kỳ, hoặc chỉ tiêu kém giá trị xuất phát từ {below_pct}% trở lên
-- theo chiều tốt của KR. Giá trị xuất phát được coi là kết quả kỳ trước (suy luận, wiki/14). Hợp đồng EVT-20 không có
-- loại đối tượng KR nên đối tượng là đơn vị sở hữu mục tiêu, mã KR và mục tiêu nằm trong chuỗi bằng chứng (suy luận).
-- Mức theo độ vượt ngưỡng e (đạt sớm: số ngày cửa sổ / ngày đạt; chỉ tiêu thấp: độ lệch / ngưỡng): thoả cả hai điều
-- kiện hoặc e ≥ 2 là CAO, e ≥ 1,5 là VUA, còn lại THAP (suy luận).
INSERT INTO anomaly_run (tenant_id, alert_key, signal_code, severity, subject_type, subject_id, period_id,
                         evidence_chain, model_version, detected_at, run_id, published_at, _version)
WITH
kr AS
(
  SELECT k.id AS kr_id, k.period_id AS ky_id, k.objective_id AS o_id, k.direction AS chieu,
         toFloat64(k.target_value) AS chi_tieu, toFloat64(k.baseline_value) AS xuat_phat,
         o.org_unit_id AS don_vi, p.start_date AS bat_dau
  FROM fact_okr_key_result AS k FINAL
  INNER JOIN (SELECT id, org_unit_id FROM fact_okr_objective FINAL WHERE tenant_id = {tenant:UUID}) AS o
    ON o.id = k.objective_id
  INNER JOIN (SELECT id, start_date FROM dim_period FINAL WHERE tenant_id = {tenant:UUID}) AS p ON p.id = k.period_id
  WHERE k.tenant_id = {tenant:UUID} AND k.status IN ('DA_DUYET', 'DONG')
),
dat AS
(
  SELECT c.key_result_id AS kr_id, min(toDate32(c.checkin_at)) AS ngay_dat
  FROM fact_okr_checkin AS c FINAL
  INNER JOIN kr ON kr.kr_id = c.key_result_id
  WHERE c.tenant_id = {tenant:UUID} AND c.status = 'DA_GUI'
    AND if(kr.chieu = 'GIAM', toFloat64(c.value) <= kr.chi_tieu, toFloat64(c.value) >= kr.chi_tieu)
  GROUP BY c.key_result_id
),
do_lech AS
(
  SELECT kr.*, d.kr_id != toUUID('00000000-0000-0000-0000-000000000000') AS co_dat, d.ngay_dat AS ngay_dat,
         dateDiff('day', kr.bat_dau, d.ngay_dat) + 1 AS ngay_thu,
         multiIf(kr.xuat_phat IS NULL OR kr.xuat_phat = 0 OR kr.chieu NOT IN ('TANG', 'GIAM'), 0,
                 kr.chieu = 'TANG', (kr.xuat_phat - kr.chi_tieu) / abs(kr.xuat_phat) * 100,
                 (kr.chi_tieu - kr.xuat_phat) / abs(kr.xuat_phat) * 100) AS lech
  FROM kr
  LEFT JOIN dat AS d ON d.kr_id = kr.kr_id
),
phat_hien AS
(
  SELECT *,
         co_dat AND ngay_thu >= 1 AND ngay_thu <= {early_weeks:UInt32} * 7 AS dk_som,
         lech >= {below_pct:Float64} AS dk_thap,
         greatest(if(dk_som, {early_weeks:UInt32} * 7 / ngay_thu, 0), if(dk_thap, lech / {below_pct:Float64}, 0)) AS e
  FROM do_lech
  WHERE dk_som OR dk_thap
)
SELECT {tenant:UUID} AS tenant_id,
       lower(hex(SHA256(concat(toString({tenant:UUID}), '|MUC_TIEU_DE|', toString(kr_id), '|', toString(ky_id)))))
         AS alert_key,
       'MUC_TIEU_DE' AS signal_code,
       multiIf((dk_som AND dk_thap) OR e >= 2, 'CAO', e >= 1.5, 'VUA', 'THAP') AS severity,
       'DON_VI' AS subject_type,
       don_vi AS subject_id,
       ky_id AS period_id,
       toJSONString(arrayConcat(
         arrayFilter(x -> x != '', [if(dk_som, 'dieu_kien:DAT_CHI_TIEU_SOM', ''),
                                    if(dk_thap, 'dieu_kien:CHI_TIEU_THAP_HON_KY_TRUOC', ''),
                                    if(dk_som, concat('so_lieu:NGAY_DAT_TRONG_KY=', toString(ngay_thu)), ''),
                                    if(dk_thap, concat('so_lieu:CHI_TIEU_THAP_HON_PHAN_TRAM=',
                                                       toString(round(lech, 1))), '')]),
         [concat('ky:', toString(ky_id)), concat('key_result:', toString(kr_id)),
          concat('objective:', toString(o_id))])) AS evidence_chain,
       {model:String} AS model_version,
       now64(3) AS detected_at,
       {run_id:UUID} AS run_id,
       NULL AS published_at,
       1 AS _version
FROM phat_hien
