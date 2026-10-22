-- DV-15-09 · QT-14 dấu hiệu 8 MIEN_TRU_LAP "Miễn trừ khách quan lặp lại" (TL-02b), gộp luật BR-06-12 (tỷ lệ chấp nhận
-- khách quan bất thường của một người duyệt, QT-06) vì cùng ý nghĩa (suy luận, wiki/14). Miễn trừ chậm khách quan là
-- đề nghị gia hạn đã duyệt với nguyên nhân người duyệt kết luận KHACH_QUAN (hệ số C). Với mỗi người duyệt (mã giả danh
-- decider_key) và cửa sổ {window} quý kết thúc ở một quý mà người đó có miễn trừ:
--   1. số lần miễn trừ từ {exemptions} trở lên; hoặc
--   2. trong các đề nghị gia hạn khai nguyên nhân khách quan mà người đó đã quyết định (ít nhất {min_decisions}), tỷ
--      lệ chấp nhận cao hơn tỷ lệ chung của tenant trong cùng cửa sổ từ {rate_gap} điểm phần trăm trở lên. Kho chưa có
--      khối của người duyệt nên "ngưỡng khối" lấy tỷ lệ chung của tenant (suy luận).
-- Đối tượng NGUOI_DUYET: kho không có mã người dạng rõ nên subject_id là mã đề nghị miễn trừ gần nhất của người đó
-- trong cửa sổ (MOD-07 tra ra người quyết định), mọi đề nghị liên quan nằm trong chuỗi bằng chứng (suy luận, wiki/14).
-- Thoả cả hai điều kiện là CAO, còn lại VUA (suy luận). Đề nghị ở kỳ không phải quý không xét.
INSERT INTO anomaly_run (tenant_id, alert_key, signal_code, severity, subject_type, subject_id, period_id,
                         evidence_chain, model_version, detected_at, run_id, published_at, _version)
WITH
quy AS
(
  SELECT id AS ky_id, toInt64(dense_rank() OVER (ORDER BY start_date)) AS stt
  FROM dim_period FINAL
  WHERE tenant_id = {tenant:UUID} AND kind = 'QUY'
),
de_nghi AS
(
  SELECT r.id AS ma, assumeNotNull(r.decider_key) AS nguoi_duyet, q.stt AS stt_ky, r.decided_at AS luc,
         r.status = 'DA_DUYET' AND ifNull(r.cause_decided, '') = 'KHACH_QUAN' AS mien_tru,
         ifNull(r.cause_claimed, '') = 'KHACH_QUAN' OR r.is_objective_reason = 1 AS khai_khach_quan
  FROM fact_task_change_request AS r FINAL
  INNER JOIN quy AS q ON q.ky_id = r.period_id
  WHERE r.tenant_id = {tenant:UUID} AND r.request_type = 'GIA_HAN' AND r.status IN ('DA_DUYET', 'TU_CHOI')
    AND r.decider_key IS NOT NULL
),
-- Mỗi đề nghị thuộc các cửa sổ kết thúc ở quý của nó và {window} - 1 quý sau đó.
theo_cua_so AS
(
  SELECT *, stt_ky + arrayJoin(range(toUInt64({window:UInt32}))) AS stt_cuoi
  FROM de_nghi
),
nguoi AS
(
  SELECT nguoi_duyet, stt_cuoi,
         countIf(mien_tru) AS so_mien_tru,
         countIf(khai_khach_quan) AS so_khai,
         countIf(khai_khach_quan AND mien_tru) AS so_chap_nhan,
         countIf(mien_tru AND stt_ky = stt_cuoi) AS mien_tru_cuoi,
         argMaxIf(ma, luc, mien_tru) AS mien_tru_gan_nhat,
         arraySort(groupArrayIf(ma, mien_tru OR khai_khach_quan)) AS ds
  FROM theo_cua_so
  GROUP BY nguoi_duyet, stt_cuoi
),
chung AS
(
  SELECT stt_cuoi, countIf(khai_khach_quan AND mien_tru) / countIf(khai_khach_quan) AS ty_le_chung
  FROM theo_cua_so
  GROUP BY stt_cuoi
  HAVING countIf(khai_khach_quan) > 0
),
phat_hien AS
(
  SELECT n.nguoi_duyet AS nguoi_duyet, k.ky_id AS ky_id, n.so_mien_tru AS so_mien_tru, n.so_khai AS so_khai,
         if(n.so_khai > 0, n.so_chap_nhan / n.so_khai, 0) AS ty_le, c.ty_le_chung AS ty_le_chung,
         n.mien_tru_gan_nhat AS mien_tru_gan_nhat, n.ds AS ds,
         n.so_mien_tru >= {exemptions:UInt32} AS dk_lap,
         n.so_khai >= {min_decisions:UInt32} AND (ty_le - c.ty_le_chung) * 100 >= {rate_gap:Float64} AS dk_ty_le
  FROM nguoi AS n
  INNER JOIN quy AS k ON k.stt = n.stt_cuoi
  INNER JOIN chung AS c ON c.stt_cuoi = n.stt_cuoi
  WHERE n.mien_tru_cuoi > 0
)
SELECT {tenant:UUID} AS tenant_id,
       lower(hex(SHA256(concat(toString({tenant:UUID}), '|MIEN_TRU_LAP|', hex(nguoi_duyet), '|', toString(ky_id)))))
         AS alert_key,
       'MIEN_TRU_LAP' AS signal_code,
       if(dk_lap AND dk_ty_le, 'CAO', 'VUA') AS severity,
       'NGUOI_DUYET' AS subject_type,
       mien_tru_gan_nhat AS subject_id,
       ky_id AS period_id,
       toJSONString(arrayConcat(
         arrayFilter(x -> x != '', [if(dk_lap, 'dieu_kien:MIEN_TRU_KHACH_QUAN_LAP_LAI', ''),
                                    if(dk_ty_le, 'dieu_kien:TY_LE_CHAP_NHAN_KHACH_QUAN_CAO', ''),
                                    if(dk_ty_le, concat('so_lieu:SO_DE_NGHI_KHACH_QUAN=', toString(so_khai)), ''),
                                    if(dk_ty_le, concat('so_lieu:TY_LE_CHAP_NHAN_PHAN_TRAM=',
                                                        toString(round(ty_le * 100, 1))), ''),
                                    if(dk_ty_le, concat('so_lieu:TY_LE_CHUNG_PHAN_TRAM=',
                                                        toString(round(ty_le_chung * 100, 1))), '')]),
         [concat('so_lieu:SO_LAN_MIEN_TRU=', toString(so_mien_tru)), concat('ky:', toString(ky_id))],
         arrayMap(x -> concat('task_change_request:', toString(x)), arraySlice(ds, 1, 50)))) AS evidence_chain,
       {model:String} AS model_version,
       now64(3) AS detected_at,
       {run_id:UUID} AS run_id,
       NULL AS published_at,
       1 AS _version
FROM phat_hien
WHERE dk_lap OR dk_ty_le
