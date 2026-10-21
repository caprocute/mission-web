-- NEN-032 · baseline mục 13 · BS-02 HD-02 · TL-03 "Bảng idempotency_key" (L875-892).
-- Kho Idempotency-Key của API ghi. Giữ 48 giờ theo HD-02 (TL-01 ghi 24 giờ, HD-02 thắng):
-- phân vùng RANGE theo created_day bằng pg_partman, retention 2 ngày nên mỗi khoá còn từ 48 đến 72
-- giờ. response_body không chứa trường C2, chỉ mã tham chiếu.
CREATE TABLE ${flyway:defaultSchema}.idempotency_key (
    tenant_id     uuid         NOT NULL,
    actor_id      uuid         NOT NULL,
    idem_key      varchar(64)  NOT NULL,
    created_day   date         NOT NULL DEFAULT current_date,
    request_hash  bytea        NOT NULL,
    status        varchar(12)  NOT NULL,
    response_code smallint,
    response_body jsonb,
    resource_ref  varchar(128),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_idempotency_key PRIMARY KEY (tenant_id, actor_id, idem_key, created_day),
    CONSTRAINT ck_idempotency_key__status CHECK (status IN ('DANG_XU_LY', 'XONG'))
) PARTITION BY RANGE (created_day);

COMMENT ON COLUMN ${flyway:defaultSchema}.idempotency_key.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.idempotency_key.actor_id IS '{"cls":"C1","desc":"Người gọi (sub của JWT)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.idempotency_key.idem_key IS '{"cls":"C1","desc":"Giá trị tiêu đề Idempotency-Key"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.idempotency_key.created_day IS '{"cls":"C1","desc":"Ngày tạo, khoá phân vùng theo ngày"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.idempotency_key.request_hash IS '{"cls":"C1","desc":"SHA-256 của phương thức, đường dẫn, thân yêu cầu chuẩn hoá"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.idempotency_key.status IS '{"cls":"C1","desc":"Trạng thái xử lý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.idempotency_key.response_code IS '{"cls":"C1","desc":"Mã HTTP đã trả","type_exception":"TL-03 L889 chốt smallint"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.idempotency_key.response_body IS '{"cls":"C1","desc":"Phản hồi đã trả, không chứa trường C2"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.idempotency_key.resource_ref IS '{"cls":"C1","desc":"Mã tài nguyên đã tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.idempotency_key.created_at IS '{"cls":"C1","desc":"Thời điểm tạo"}';

SELECT partman.create_parent(
    p_parent_table => '${flyway:defaultSchema}.idempotency_key',
    p_control      => 'created_day',
    p_interval     => '1 day',
    p_premake      => 7,
    p_jobmon       => false);
UPDATE partman.part_config
   SET retention = '2 days', retention_keep_table = false, infinite_time_partitions = true
 WHERE parent_table = '${flyway:defaultSchema}.idempotency_key';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.idempotency_key');
SELECT ${flyway:defaultSchema}.fn_protect_partitions('${flyway:defaultSchema}.idempotency_key');
