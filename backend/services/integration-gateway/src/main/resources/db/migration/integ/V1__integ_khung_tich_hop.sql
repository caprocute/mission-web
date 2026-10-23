-- DV-13-01 · Schema integ (SVC-13 integration-gateway, CSDL cell_shared): lớp chống ô nhiễm giữa nền tảng
-- và hệ ngoài (TL-03 "Schema integ (SVC-13)"; TL-01 "Lớp chống ô nhiễm"; ADR-20; baseline mục 8 cơ chế 8).
-- Hàm RLS từ db/common (NEN-033); outbox_event, inbox_processed, idempotency_key từ db/common-business (NEN-032);
-- vai trò svc_integ, ro_integ, cdc_integ do DatabaseBootstrap (NEN-031) tạo.
--
-- Điểm lệch và lựa chọn (wiki trang 14, mục DV-13-01):
--   * inbox_message, outbound_message phân vùng ngày bằng phân vùng DEFAULT; pg_partman tạo phân vùng ngày ở
--     môi trường thật (job bảo trì phân vùng của NEN-032).
--   * UQ source_event_id của outbound_message phải kèm khoá phân vùng: UQ (created_day, tenant_id, connector_id,
--     source_event_id); chống trùng qua ngày do OutboundPipeline tra trước khi ghi (suy luận).
--   * Bổ sung cột ngoài từ điển: inbox_message.received_at; dead_letter.connector_id, created_at (thư chết
--     riêng mỗi bộ kết nối, liệt kê theo thời gian); outbound_message.message_type, created_at, updated_at;
--     sync_cursor.updated_at; mapping_xref.created_at.
--   * Độ dài TL-03 không ghi: dead_letter.error_code 64, error_detail 1000, sync_cursor.stream 64.
--   * connector.direction varchar(9) thay varchar(8) của TL-03 vì giá trị HAI_CHIEU có 9 ký tự.

-- ---------------------------------------------------------------- connector
CREATE TABLE ${flyway:defaultSchema}.connector (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    connector_code varchar(32) NOT NULL,
    protocol varchar(16) NOT NULL,
    endpoint varchar(512) NOT NULL,
    secret_path varchar(255) NOT NULL,
    direction varchar(9) NOT NULL,
    schedule_cron varchar(64),
    enabled boolean NOT NULL DEFAULT false,
    CONSTRAINT pk_connector PRIMARY KEY (id),
    CONSTRAINT uq_connector__tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_connector__code UNIQUE (tenant_id, connector_code),
    CONSTRAINT ck_connector__code CHECK (connector_code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    CONSTRAINT ck_connector__protocol CHECK (protocol IN ('REST', 'SOAP', 'EDXML', 'XROAD', 'SFTP', 'JDBC_RO')),
    CONSTRAINT ck_connector__direction CHECK (direction IN ('VAO', 'RA', 'HAI_CHIEU'))
);

COMMENT ON TABLE ${flyway:defaultSchema}.connector IS 'Cấu hình từng kết nối hệ ngoài; bí mật ở OpenBao, bảng chỉ lưu đường dẫn';
COMMENT ON COLUMN ${flyway:defaultSchema}.connector.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.connector.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.connector.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.connector.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.connector.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.connector.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.connector.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.connector.connector_code IS '{"cls":"C0","desc":"Mã kết nối, ví dụ VOFFICE, CSDL_CBCCVC"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.connector.protocol IS '{"cls":"C0","desc":"Giao thức"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.connector.endpoint IS '{"cls":"C1","desc":"Địa chỉ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.connector.secret_path IS '{"cls":"C1","desc":"Đường dẫn bí mật ở OpenBao"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.connector.direction IS '{"cls":"C0","desc":"Chiều VAO, RA hoặc HAI_CHIEU"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.connector.schedule_cron IS '{"cls":"C0","desc":"Lịch kéo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.connector.enabled IS '{"cls":"C0","desc":"Bật"}';

-- ---------------------------------------------------------------- inbox_message
CREATE TABLE ${flyway:defaultSchema}.inbox_message (
    received_day date NOT NULL DEFAULT current_date,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    connector_id uuid NOT NULL,
    external_msg_id varchar(128) NOT NULL,
    message_type varchar(48) NOT NULL,
    raw_enc bytea NOT NULL,
    dek_id uuid NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'MOI',
    emitted_event_id uuid,
    received_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_inbox_message PRIMARY KEY (received_day, id),
    CONSTRAINT uq_inbox_message__external UNIQUE (received_day, tenant_id, connector_id, external_msg_id),
    CONSTRAINT fk_inbox_message__connector FOREIGN KEY (tenant_id, connector_id)
        REFERENCES ${flyway:defaultSchema}.connector (tenant_id, id),
    CONSTRAINT ck_inbox_message__status CHECK (status IN ('MOI', 'DA_CHUYEN_DOI', 'DA_PHAT', 'LOI')),
    CONSTRAINT ck_inbox_message__emitted CHECK (status <> 'DA_PHAT' OR emitted_event_id IS NOT NULL)
) PARTITION BY RANGE (received_day);
CREATE TABLE ${flyway:defaultSchema}.inbox_message_default PARTITION OF ${flyway:defaultSchema}.inbox_message DEFAULT;
CREATE INDEX ix_inbox_message__tra_trung ON ${flyway:defaultSchema}.inbox_message (tenant_id, connector_id, external_msg_id);

COMMENT ON TABLE ${flyway:defaultSchema}.inbox_message IS 'Thông điệp nhận từ hệ ngoài, nguyên văn đã mã hoá, ghi trước khi chuyển đổi';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_message.received_day IS '{"cls":"C1","desc":"Ngày nhận; khoá phân vùng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_message.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_message.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_message.connector_id IS '{"cls":"C1","desc":"Kết nối"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_message.external_msg_id IS '{"cls":"C1","desc":"Mã thông điệp phía ngoài"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_message.message_type IS '{"cls":"C1","desc":"Loại"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_message.raw_enc IS '{"cls":"C2","desc":"Nội dung nguyên văn (mã hoá phong bì)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_message.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_message.status IS '{"cls":"C1","desc":"Trạng thái"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_message.emitted_event_id IS '{"cls":"C1","desc":"Sự kiện đã phát (EVT-24)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.inbox_message.received_at IS '{"cls":"C1","desc":"Thời điểm nhận"}';

-- ---------------------------------------------------------------- mapping_xref
CREATE TABLE ${flyway:defaultSchema}.mapping_xref (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    connector_id uuid NOT NULL,
    entity_type varchar(24) NOT NULL,
    external_id varchar(128) NOT NULL,
    internal_id uuid NOT NULL,
    valid_from date NOT NULL,
    valid_to date,
    valid_period daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
    source_note varchar(255),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_mapping_xref PRIMARY KEY (id),
    CONSTRAINT fk_mapping_xref__connector FOREIGN KEY (tenant_id, connector_id)
        REFERENCES ${flyway:defaultSchema}.connector (tenant_id, id),
    CONSTRAINT ck_mapping_xref__entity_type CHECK (entity_type IN ('DON_VI', 'NHAN_SU', 'VAN_BAN')),
    CONSTRAINT ck_mapping_xref__valid CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ex_mapping_xref__hieu_luc EXCLUDE USING gist (
        tenant_id WITH =, connector_id WITH =, entity_type WITH =, external_id WITH =, valid_period WITH &&)
);
CREATE INDEX ix_mapping_xref__internal ON ${flyway:defaultSchema}.mapping_xref (tenant_id, connector_id, entity_type, internal_id);

COMMENT ON TABLE ${flyway:defaultSchema}.mapping_xref IS 'Ánh xạ mã hệ ngoài với mã nội bộ UUIDv7, có hiệu lực theo thời gian';
COMMENT ON COLUMN ${flyway:defaultSchema}.mapping_xref.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.mapping_xref.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.mapping_xref.connector_id IS '{"cls":"C1","desc":"Hệ ngoài"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.mapping_xref.entity_type IS '{"cls":"C1","desc":"Loại (DON_VI, NHAN_SU, VAN_BAN)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.mapping_xref.external_id IS '{"cls":"C1","desc":"Mã ngoài"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.mapping_xref.internal_id IS '{"cls":"C1","desc":"Mã nội bộ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.mapping_xref.valid_from IS '{"cls":"C1","desc":"Hiệu lực từ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.mapping_xref.valid_to IS '{"cls":"C1","desc":"Hiệu lực đến (không gồm)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.mapping_xref.valid_period IS '{"cls":"C1","desc":"Khoảng hiệu lực (cột sinh)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.mapping_xref.source_note IS '{"cls":"C1","desc":"Ghi chú đối chiếu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.mapping_xref.created_at IS '{"cls":"C1"}';

-- ---------------------------------------------------------------- sync_cursor
CREATE TABLE ${flyway:defaultSchema}.sync_cursor (
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    connector_id uuid NOT NULL,
    stream varchar(64) NOT NULL,
    cursor_value varchar(255) NOT NULL,
    last_success_at timestamptz,
    lag_seconds int,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_sync_cursor PRIMARY KEY (tenant_id, connector_id, stream),
    CONSTRAINT fk_sync_cursor__connector FOREIGN KEY (tenant_id, connector_id)
        REFERENCES ${flyway:defaultSchema}.connector (tenant_id, id),
    CONSTRAINT ck_sync_cursor__lag CHECK (lag_seconds IS NULL OR lag_seconds >= 0)
);

COMMENT ON TABLE ${flyway:defaultSchema}.sync_cursor IS 'Con trỏ đồng bộ kéo theo luồng của từng kết nối';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_cursor.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_cursor.connector_id IS '{"cls":"C1","desc":"Kết nối"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_cursor.stream IS '{"cls":"C1","desc":"Luồng đồng bộ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_cursor.cursor_value IS '{"cls":"C1","desc":"Con trỏ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_cursor.last_success_at IS '{"cls":"C1","desc":"Lần thành công"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_cursor.lag_seconds IS '{"cls":"C1","desc":"Độ trễ (giây)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.sync_cursor.updated_at IS '{"cls":"C1"}';

-- ---------------------------------------------------------------- dead_letter
CREATE TABLE ${flyway:defaultSchema}.dead_letter (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    connector_id uuid NOT NULL,
    direction varchar(4) NOT NULL,
    ref_message_id uuid NOT NULL,
    error_code varchar(64) NOT NULL,
    error_detail varchar(1000) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_by uuid,
    resolved_at timestamptz,
    CONSTRAINT pk_dead_letter PRIMARY KEY (id),
    CONSTRAINT fk_dead_letter__connector FOREIGN KEY (tenant_id, connector_id)
        REFERENCES ${flyway:defaultSchema}.connector (tenant_id, id),
    CONSTRAINT ck_dead_letter__direction CHECK (direction IN ('VAO', 'RA')),
    CONSTRAINT ck_dead_letter__resolved CHECK ((resolved_by IS NULL) = (resolved_at IS NULL))
);
CREATE INDEX ix_dead_letter__chua_xu_ly ON ${flyway:defaultSchema}.dead_letter (tenant_id, connector_id, created_at, id)
    WHERE resolved_at IS NULL;

COMMENT ON TABLE ${flyway:defaultSchema}.dead_letter IS 'Thông điệp không xử lý được sau khi hết số lần thử, chờ xử lý tay';
COMMENT ON COLUMN ${flyway:defaultSchema}.dead_letter.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dead_letter.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dead_letter.connector_id IS '{"cls":"C1","desc":"Kết nối"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dead_letter.direction IS '{"cls":"C1","desc":"Vào hoặc ra"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dead_letter.ref_message_id IS '{"cls":"C1","desc":"Thông điệp (inbox_message hoặc outbound_message)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dead_letter.error_code IS '{"cls":"C1","desc":"Mã lỗi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dead_letter.error_detail IS '{"cls":"C1","desc":"Chi tiết lỗi, không chứa nội dung thông điệp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dead_letter.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dead_letter.resolved_by IS '{"cls":"C1","desc":"Người xử lý"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dead_letter.resolved_at IS '{"cls":"C1","desc":"Thời điểm xử lý"}';

-- ---------------------------------------------------------------- outbound_message
CREATE TABLE ${flyway:defaultSchema}.outbound_message (
    created_day date NOT NULL DEFAULT current_date,
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    connector_id uuid NOT NULL,
    source_event_id uuid NOT NULL,
    message_type varchar(48) NOT NULL,
    payload_enc bytea NOT NULL,
    dek_id uuid NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'CHO',
    attempts int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    ack_ref varchar(128),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_outbound_message PRIMARY KEY (created_day, id),
    CONSTRAINT uq_outbound_message__source UNIQUE (created_day, tenant_id, connector_id, source_event_id),
    CONSTRAINT fk_outbound_message__connector FOREIGN KEY (tenant_id, connector_id)
        REFERENCES ${flyway:defaultSchema}.connector (tenant_id, id),
    CONSTRAINT ck_outbound_message__status CHECK (status IN ('CHO', 'DA_GUI', 'DA_XAC_NHAN', 'LOI')),
    CONSTRAINT ck_outbound_message__attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbound_message__ack CHECK (status <> 'DA_XAC_NHAN' OR ack_ref IS NOT NULL)
) PARTITION BY RANGE (created_day);
CREATE TABLE ${flyway:defaultSchema}.outbound_message_default PARTITION OF ${flyway:defaultSchema}.outbound_message DEFAULT;
CREATE INDEX ix_outbound_message__den_han ON ${flyway:defaultSchema}.outbound_message (next_attempt_at)
    WHERE status IN ('CHO', 'DA_GUI');
CREATE INDEX ix_outbound_message__source ON ${flyway:defaultSchema}.outbound_message (tenant_id, connector_id, source_event_id);

COMMENT ON TABLE ${flyway:defaultSchema}.outbound_message IS 'Thông điệp gửi ra hệ ngoài, thử lại theo luỹ thừa đến khi bên nhận xác nhận';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.created_day IS '{"cls":"C1","desc":"Ngày tạo; khoá phân vùng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.connector_id IS '{"cls":"C1","desc":"Đích"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.source_event_id IS '{"cls":"C1","desc":"Sự kiện nguồn (EVT-12, EVT-19)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.message_type IS '{"cls":"C1","desc":"Loại thông điệp phía hệ ngoài"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.payload_enc IS '{"cls":"C2","desc":"Nội dung đã chuyển sang định dạng hệ ngoài (mã hoá phong bì)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.status IS '{"cls":"C1","desc":"Trạng thái"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.attempts IS '{"cls":"C1","desc":"Số lần đã gửi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.next_attempt_at IS '{"cls":"C1","desc":"Lần thử kế tiếp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.ack_ref IS '{"cls":"C1","desc":"Xác nhận phía nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_message.updated_at IS '{"cls":"C1"}';

-- ---------------------------------------------------------------- transfer_package
CREATE TABLE ${flyway:defaultSchema}.transfer_package (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    person_id uuid NOT NULL,
    direction varchar(4) NOT NULL,
    peer_cell_code varchar(32) NOT NULL,
    peer_tenant_code varchar(32) NOT NULL,
    manifest jsonb NOT NULL,
    object_key varchar(512) NOT NULL,
    package_sha256 bytea NOT NULL,
    wrapped_key bytea NOT NULL,
    signature bytea NOT NULL,
    status varchar(16) NOT NULL,
    CONSTRAINT pk_transfer_package PRIMARY KEY (id),
    CONSTRAINT ck_transfer_package__direction CHECK (direction IN ('DI', 'DEN')),
    CONSTRAINT ck_transfer_package__sha256 CHECK (length(package_sha256) = 32),
    CONSTRAINT ck_transfer_package__status CHECK (status IN ('DONG_GOI', 'DA_GUI', 'DA_NHAN', 'DA_NHAP', 'TU_CHOI'))
);

COMMENT ON TABLE ${flyway:defaultSchema}.transfer_package IS 'Gói chuyển hồ sơ cán bộ điều động liên cell (TL-03 mục 10.8)';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.person_id IS '{"cls":"C1","desc":"Cán bộ điều động"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.direction IS '{"cls":"C1","desc":"Chiều DI hoặc DEN"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.peer_cell_code IS '{"cls":"C1","desc":"Cell đối tác"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.peer_tenant_code IS '{"cls":"C1","desc":"Tenant đối tác"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.manifest IS '{"cls":"C1","desc":"Danh mục nội dung và băm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.object_key IS '{"cls":"C1","desc":"Gói mã hoá trên Ceph"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.package_sha256 IS '{"cls":"C1","desc":"Băm gói"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.wrapped_key IS '{"cls":"C1","desc":"Khoá gói bọc bằng khoá công khai của cell nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.signature IS '{"cls":"C1","desc":"Chữ ký của cell gửi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.status IS '{"cls":"C1","desc":"Trạng thái"}';

-- ---------------------------------------------------------------- RLS và quyền
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.connector');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.inbox_message');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.mapping_xref');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.sync_cursor');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.dead_letter');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.outbound_message');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.transfer_package');

-- Phân vùng con (kể cả DEFAULT và phân vùng ngày do pg_partman tạo sau) chỉ truy cập qua bảng cha.
SELECT ${flyway:defaultSchema}.fn_protect_partitions('${flyway:defaultSchema}.inbox_message');
SELECT ${flyway:defaultSchema}.fn_protect_partitions('${flyway:defaultSchema}.outbound_message');

-- Ánh xạ hết hiệu lực được đóng (valid_to), không xoá.
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.mapping_xref FROM svc_${flyway:defaultSchema};

-- ---------------------------------------------------------------- hàm quét liên tenant của worker
-- Worker kéo theo lịch và worker gửi ra cần biết việc của mọi tenant, rồi xử lý từng việc trong ngữ cảnh tenant
-- của nó với RLS đầy đủ; hai hàm chỉ trả mã và tenant (suy luận, cùng cách với esign-service).
CREATE FUNCTION ${flyway:defaultSchema}.fn_ket_noi_dang_bat()
    RETURNS TABLE (tenant_id uuid, id uuid, connector_code varchar)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT c.tenant_id, c.id, c.connector_code FROM ${flyway:defaultSchema}.connector c
     WHERE c.enabled
     ORDER BY c.tenant_id, c.connector_code
$$;
REVOKE ALL ON FUNCTION ${flyway:defaultSchema}.fn_ket_noi_dang_bat() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_ket_noi_dang_bat() TO svc_${flyway:defaultSchema};

CREATE FUNCTION ${flyway:defaultSchema}.fn_thu_ra_den_han(p_limit int)
    RETURNS TABLE (tenant_id uuid, created_day date, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT m.tenant_id, m.created_day, m.id FROM ${flyway:defaultSchema}.outbound_message m
     WHERE m.status IN ('CHO', 'DA_GUI') AND m.next_attempt_at <= now()
     ORDER BY m.next_attempt_at
     LIMIT least(greatest(p_limit, 1), 500)
$$;
REVOKE ALL ON FUNCTION ${flyway:defaultSchema}.fn_thu_ra_den_han(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_thu_ra_den_han(int) TO svc_${flyway:defaultSchema};

-- Chủ sở hữu hàm (mig_integ) cũng chịu FORCE RLS: chỉ mở đúng phần hai hàm quét cần đọc.
CREATE POLICY rls_connector__quet_dang_bat ON ${flyway:defaultSchema}.connector AS PERMISSIVE FOR SELECT TO CURRENT_USER
    USING (enabled);
CREATE POLICY rls_outbound_message__quet_den_han ON ${flyway:defaultSchema}.outbound_message AS PERMISSIVE FOR SELECT TO CURRENT_USER
    USING (status IN ('CHO', 'DA_GUI'));
