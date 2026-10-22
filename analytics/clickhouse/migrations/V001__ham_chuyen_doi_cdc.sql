-- DV-15-01 · SVC-15/ADR-14 · TL-03 "CDC phân tích sang ClickHouse".
-- Hàm chuyển giá trị của bản ghi Debezium đã làm phẳng (ExtractNewRecordState, JsonConverter không kèm lược đồ)
-- sang kiểu ClickHouse. Bảng Kafka engine đọc mọi trường dưới dạng String để một thay đổi nhỏ ở cấu hình
-- connector (DV-15-02) không làm hỏng việc giải mã; materialized view gọi các hàm này khi nạp bảng đích.
-- Hàm SQL do người dùng định nghĩa là đối tượng toàn cụm, không thuộc CSDL nào, nên mang tiền tố mission_cdc_.

-- Ngày: io.debezium.time.Date là số ngày kể từ 1970-01-01; 'infinity' của PostgreSQL thành ngày lớn nhất Date32.
-- Chấp nhận cả chuỗi ISO 'YYYY-MM-DD' nếu connector dùng bộ chuyển đổi ngày.
CREATE OR REPLACE FUNCTION mission_cdc_date ON CLUSTER '{{cluster}}' AS (x) ->
  if(match(x, '^-?[0-9]+$'),
     if(toInt64(x) >= 120000, toDate32('2299-12-31'), addDays(toDate32('1970-01-01'), toInt32(x))),
     if(lower(x) = 'infinity', toDate32('2299-12-31'), toDate32OrNull(x)));

-- Mốc thời gian có múi giờ: io.debezium.time.ZonedTimestamp là chuỗi ISO-8601 có 'Z'.
CREATE OR REPLACE FUNCTION mission_cdc_ts ON CLUSTER '{{cluster}}' AS (x) ->
  if(x = '', NULL, parseDateTime64BestEffortOrNull(x, 3, 'Asia/Ho_Chi_Minh'));

-- Mã người đã giả danh: column.mask.hash.SHA-256.with.salt.<muối_tenant> cho ra 64 ký tự hex, lưu 32 byte.
CREATE OR REPLACE FUNCTION mission_cdc_key ON CLUSTER '{{cluster}}' AS (x) ->
  if(length(x) = 64 AND match(x, '^[0-9a-fA-F]+$'), toFixedString(unhex(x), 32), NULL);

-- Dấu xoá mềm do delete.handling.mode=rewrite thêm vào (__deleted = "true" khi dòng nguồn bị xoá).
CREATE OR REPLACE FUNCTION mission_cdc_deleted ON CLUSTER '{{cluster}}' AS (op, deleted) ->
  toUInt8(op = 'd' OR lower(deleted) = 'true');

CREATE OR REPLACE FUNCTION mission_cdc_bool ON CLUSTER '{{cluster}}' AS (x) ->
  toUInt8(lower(x) IN ('true', 't', '1'));
