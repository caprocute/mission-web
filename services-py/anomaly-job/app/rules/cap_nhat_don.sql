-- DV-15-08 · QT-14 dấu hiệu 5 CAP_NHAT_DON "Cập nhật dồn vào cuối kỳ" (TL-02b, ngưỡng mặc định): trong một đơn vị,
-- một kỳ, có ít nhất {min_updates} lượt cập nhật tiến độ (suy luận) và từ {last_pct}% lượt trở lên rơi vào {last_days}
-- ngày làm việc cuối kỳ, hoặc vào {week_days} ngày làm việc cuối của tuần chứa lượt cập nhật (suy luận cách hiểu "cuối
-- tuần"). Ngày làm việc theo lịch của tenant (getWorkingCalendar): job truyền ngày nghỉ {off} và ngày làm bù {extra}.
-- Kỳ của lượt cập nhật là kỳ của nhiệm vụ. Mức theo e = tỷ lệ / ngưỡng: thoả cả hai điều kiện hoặc e ≥ 1,5 là CAO,
-- e ≥ 1,25 là VUA, còn lại THAP (suy luận).
INSERT INTO anomaly_run (tenant_id, alert_key, signal_code, severity, subject_type, subject_id, period_id,
                         evidence_chain, model_version, detected_at, run_id, published_at, _version)
WITH
ky AS
(
  SELECT id AS ky_id, toDate(end_date) AS ket_thuc,
         arrayFilter(d -> (toDayOfWeek(d) <= 5 AND NOT has({off:Array(Date)}, d)) OR has({extra:Array(Date)}, d),
                     arrayMap(i -> addDays(toDate(end_date), -i), range(31))) AS ngay_cuoi
  FROM dim_period FINAL
  WHERE tenant_id = {tenant:UUID}
),
luot AS
(
  SELECT pr.org_unit_id AS don_vi, t.period_id AS ky_id, toDate(pr.reported_at) AS ngay,
         length(k.ngay_cuoi) >= {last_days:UInt32} AND ngay >= k.ngay_cuoi[{last_days:UInt32}]
           AND ngay <= k.ket_thuc AS cuoi_ky,
         arrayFilter(d -> (toDayOfWeek(d) <= 5 AND NOT has({off:Array(Date)}, d)) OR has({extra:Array(Date)}, d),
                     arrayMap(i -> addDays(toMonday(ngay), 6 - i), range(7))) AS ngay_tuan,
         length(ngay_tuan) >= {week_days:UInt32} AND ngay >= ngay_tuan[{week_days:UInt32}] AS cuoi_tuan
  FROM fact_task_progress AS pr FINAL
  INNER JOIN (SELECT id, period_id FROM fact_task FINAL WHERE tenant_id = {tenant:UUID}) AS t ON t.id = pr.task_id
  INNER JOIN ky AS k ON k.ky_id = t.period_id
  WHERE pr.tenant_id = {tenant:UUID} AND pr.org_unit_id != toUUID('00000000-0000-0000-0000-000000000000')
),
nhom AS
(
  SELECT don_vi, ky_id, count() AS n, countIf(cuoi_ky) AS n_ky, countIf(cuoi_tuan) AS n_tuan
  FROM luot
  GROUP BY don_vi, ky_id
  HAVING n >= {min_updates:UInt32}
),
phat_hien AS
(
  SELECT *,
         n_ky * 100 >= {last_pct:Float64} * n AS dk_ky,
         n_tuan * 100 >= {last_pct:Float64} * n AS dk_tuan,
         greatest(if(dk_ky, n_ky, 0), if(dk_tuan, n_tuan, 0)) * 100 / n / {last_pct:Float64} AS e
  FROM nhom
  WHERE dk_ky OR dk_tuan
)
SELECT {tenant:UUID} AS tenant_id,
       lower(hex(SHA256(concat(toString({tenant:UUID}), '|CAP_NHAT_DON|', toString(don_vi), '|', toString(ky_id)))))
         AS alert_key,
       'CAP_NHAT_DON' AS signal_code,
       multiIf((dk_ky AND dk_tuan) OR e >= 1.5, 'CAO', e >= 1.25, 'VUA', 'THAP') AS severity,
       'DON_VI' AS subject_type,
       don_vi AS subject_id,
       ky_id AS period_id,
       toJSONString(arrayConcat(
         arrayFilter(x -> x != '', [if(dk_ky, 'dieu_kien:DON_CUOI_KY', ''), if(dk_tuan, 'dieu_kien:DON_CUOI_TUAN', '')]),
         [concat('so_lieu:SO_LUOT_CAP_NHAT=', toString(n)),
          concat('so_lieu:TY_LE_CUOI_KY_PHAN_TRAM=', toString(round(n_ky * 100 / n, 1))),
          concat('so_lieu:TY_LE_CUOI_TUAN_PHAN_TRAM=', toString(round(n_tuan * 100 / n, 1))),
          concat('ky:', toString(ky_id))])) AS evidence_chain,
       {model:String} AS model_version,
       now64(3) AS detected_at,
       {run_id:UUID} AS run_id,
       NULL AS published_at,
       1 AS _version
FROM phat_hien
