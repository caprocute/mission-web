-- DV-15-07 · QT-14 bước 1 (BR-14-01, BR-14-06) → SVC-15 job phát hiện → EVT-20 (TL-03 L4468): bảng kết quả của job
-- phát hiện bất thường. Mỗi luật ghi bằng INSERT ... SELECT, bỏ qua alert_key đã có, nên chạy lại không sinh dòng mới;
-- producer đọc dòng chưa phát, phát EVT-20 với event_id tất định theo alert_key, rồi ghi bản đã phát (_version lớn hơn).
-- Chỉ chứa mã (đơn vị, hồ sơ, kỳ, bằng chứng), không có tên người hay nội dung C2.
CREATE TABLE {{db}}.anomaly_out ON CLUSTER '{{cluster}}'
(
  tenant_id         UUID,
  alert_key         String,                  -- hex(SHA-256(tenant | dấu hiệu | đối tượng | cửa sổ thời gian))
  signal_code       LowCardinality(String),  -- TU_CHAM_CAO, CAO_BANG, ...
  severity          LowCardinality(String),  -- CAO, VUA, THAP
  subject_type      LowCardinality(String),  -- CA_NHAN, DON_VI, NGUOI_DUYET
  subject_id        UUID,
  period_id         Nullable(UUID),          -- kỳ cuối của cửa sổ phát hiện
  evidence_chain    String,                  -- mảng JSON các mã bằng chứng
  model_version     String,                  -- phiên bản luật và phiên bản ngưỡng
  detected_at       DateTime64(3, 'Asia/Ho_Chi_Minh'),
  run_id            UUID,
  published_at      Nullable(DateTime64(3, 'Asia/Ho_Chi_Minh')),
  _version          UInt64
)
ENGINE = ReplicatedReplacingMergeTree(_version)
PARTITION BY toYYYYMM(detected_at)
ORDER BY (tenant_id, alert_key);

-- Job chạy bằng tài khoản <db>_anomaly (V009: chỉ đọc theo cột, row policy cho toàn cell). Ghi được anomaly_out thì
-- cần readonly = 0; quyền ghi chỉ có trên bảng này, giới hạn tài nguyên khoá bằng ràng buộc.
CREATE SETTINGS PROFILE IF NOT EXISTS {{db}}_anomaly_job ON CLUSTER '{{cluster}}'
  SETTINGS readonly = 0,
           max_execution_time = 1800 MAX 3600,
           max_memory_usage = 8000000000 MAX 8000000000;
ALTER USER {{db}}_anomaly ON CLUSTER '{{cluster}}' SETTINGS PROFILE '{{db}}_anomaly_job';
GRANT ON CLUSTER '{{cluster}}' SELECT, INSERT ON {{db}}.anomaly_out TO {{db}}_anomaly;
