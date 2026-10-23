-- DV-13-09 · SVC-13: gửi bản neo gốc cell hằng ngày tới các đích độc lập và chia sẻ quyết định xếp loại (EVT-12)
-- lên CSDL CBCCVC. Bốn bảng bổ sung ngoài TL-03 (suy luận, wiki trang 14 mục DV-13-09):
--   * anchor_window: mỗi (cell, cuối ngày) một dòng, ghi khi EVT-19 cuối ngày đầu tiên tới; chờ ledger-service có
--     gói neo cấp cell rồi mới tạo thông điệp ra cho từng đích; giữ dấu đã kiểm đủ biên nhận hoặc đã cảnh báo thiếu.
--   * anchor_dispatch: mỗi (niêm phong cell, đích neo) một dòng; giữ biên nhận đã ký của đích cho tới khi chuyển
--     được cho ledger-service (chỉ svc_ledger ghi ledger.anchor_receipt).
--   * outbound_subject: chuỗi phiên bản theo thực thể của một kết nối, để quyết định bị thay sau hậu kiểm mang
--     tham chiếu bản đã gửi trước và sự kiện đến sai thứ tự bị bỏ qua.
--   * outbound_alert: thông điệp ra đã báo quá hạn thử lại, để mỗi thông điệp chỉ cảnh báo một lần.
-- Bản neo thuộc tenant vận hành của cell (cấu hình), nên hai bảng neo không cần hàm quét liên tenant.
-- anchor_target dùng bộ giá trị của ledger.anchor_receipt (TL-03) trừ OBJECT_LOCK do ledger-service tự ghi.

-- ---------------------------------------------------------------- anchor_window
CREATE TABLE ${flyway:defaultSchema}.anchor_window (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    cell_code varchar(32) NOT NULL,
    window_end timestamptz NOT NULL,
    source_event_id uuid NOT NULL,
    cell_seal_id uuid,
    attempts int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    fanned_out_at timestamptz,
    checked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_anchor_window PRIMARY KEY (id),
    CONSTRAINT uq_anchor_window__tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_anchor_window__ngay UNIQUE (tenant_id, cell_code, window_end),
    CONSTRAINT ck_anchor_window__fanout CHECK ((fanned_out_at IS NULL) = (cell_seal_id IS NULL)),
    CONSTRAINT ck_anchor_window__attempts CHECK (attempts >= 0)
);
CREATE INDEX ix_anchor_window__cho_goi ON ${flyway:defaultSchema}.anchor_window (next_attempt_at)
    WHERE fanned_out_at IS NULL;
CREATE INDEX ix_anchor_window__cho_kiem ON ${flyway:defaultSchema}.anchor_window (window_end)
    WHERE checked_at IS NULL;

COMMENT ON TABLE ${flyway:defaultSchema}.anchor_window IS 'Ngày cần neo gốc cell, chờ gói neo của ledger-service rồi gửi tới từng đích';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_window.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_window.tenant_id IS '{"cls":"C1","desc":"Tenant vận hành cell giữ các kết nối neo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_window.cell_code IS '{"cls":"C1","desc":"Cell"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_window.window_end IS '{"cls":"C1","desc":"Cuối ngày niêm phong"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_window.source_event_id IS '{"cls":"C1","desc":"EVT-19 đầu tiên của ngày"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_window.cell_seal_id IS '{"cls":"C1","desc":"ledger.cell_seal của gói neo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_window.attempts IS '{"cls":"C1","desc":"Số lần hỏi gói neo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_window.next_attempt_at IS '{"cls":"C1","desc":"Lần hỏi kế tiếp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_window.fanned_out_at IS '{"cls":"C1","desc":"Đã tạo thông điệp cho các đích"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_window.checked_at IS '{"cls":"C1","desc":"Đã kiểm số biên nhận (đủ hoặc đã cảnh báo thiếu)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_window.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_window.updated_at IS '{"cls":"C1"}';

-- ---------------------------------------------------------------- anchor_dispatch
CREATE TABLE ${flyway:defaultSchema}.anchor_dispatch (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    window_id uuid NOT NULL,
    cell_seal_id uuid NOT NULL,
    anchor_target varchar(32) NOT NULL,
    connector_id uuid NOT NULL,
    outbound_message_id uuid NOT NULL,
    receipt bytea,
    received_at timestamptz,
    relayed_at timestamptz,
    relay_attempts int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_anchor_dispatch PRIMARY KEY (id),
    CONSTRAINT uq_anchor_dispatch__target UNIQUE (tenant_id, cell_seal_id, anchor_target),
    CONSTRAINT uq_anchor_dispatch__message UNIQUE (tenant_id, outbound_message_id),
    CONSTRAINT fk_anchor_dispatch__anchor_window FOREIGN KEY (tenant_id, window_id)
        REFERENCES ${flyway:defaultSchema}.anchor_window (tenant_id, id),
    CONSTRAINT fk_anchor_dispatch__connector FOREIGN KEY (tenant_id, connector_id)
        REFERENCES ${flyway:defaultSchema}.connector (tenant_id, id),
    CONSTRAINT ck_anchor_dispatch__target CHECK (anchor_target IN ('CELL_KHAC', 'KHO_VPTW', 'TSA_DOC_LAP')),
    CONSTRAINT ck_anchor_dispatch__receipt CHECK ((receipt IS NULL) = (received_at IS NULL)),
    CONSTRAINT ck_anchor_dispatch__relay CHECK (relayed_at IS NULL OR receipt IS NOT NULL),
    CONSTRAINT ck_anchor_dispatch__attempts CHECK (relay_attempts >= 0)
);
CREATE INDEX ix_anchor_dispatch__cho_chuyen ON ${flyway:defaultSchema}.anchor_dispatch (received_at)
    WHERE receipt IS NOT NULL AND relayed_at IS NULL;
CREATE INDEX ix_anchor_dispatch__window ON ${flyway:defaultSchema}.anchor_dispatch (tenant_id, window_id);

COMMENT ON TABLE ${flyway:defaultSchema}.anchor_dispatch IS 'Bản neo gốc cell gửi tới từng đích độc lập và biên nhận chờ chuyển cho ledger-service';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_dispatch.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_dispatch.tenant_id IS '{"cls":"C1","desc":"Tenant vận hành cell"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_dispatch.window_id IS '{"cls":"C1","desc":"Ngày cần neo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_dispatch.cell_seal_id IS '{"cls":"C1","desc":"ledger.cell_seal được neo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_dispatch.anchor_target IS '{"cls":"C1","desc":"Nơi neo theo ledger.anchor_receipt"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_dispatch.connector_id IS '{"cls":"C1","desc":"Kết nối tới đích"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_dispatch.outbound_message_id IS '{"cls":"C1","desc":"Thông điệp ra mang bản neo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_dispatch.receipt IS '{"cls":"C1","desc":"Biên nhận đã ký của đích"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_dispatch.received_at IS '{"cls":"C1","desc":"Thời điểm nhận biên nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_dispatch.relayed_at IS '{"cls":"C1","desc":"Thời điểm ledger-service ghi nhận biên nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_dispatch.relay_attempts IS '{"cls":"C1","desc":"Số lần chuyển biên nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_dispatch.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.anchor_dispatch.updated_at IS '{"cls":"C1"}';

-- ---------------------------------------------------------------- outbound_subject
CREATE TABLE ${flyway:defaultSchema}.outbound_subject (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    connector_id uuid NOT NULL,
    subject_key varchar(128) NOT NULL,
    current_event_id uuid NOT NULL,
    previous_event_id uuid,
    aggregate_version int NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_outbound_subject PRIMARY KEY (id),
    CONSTRAINT uq_outbound_subject__key UNIQUE (tenant_id, connector_id, subject_key),
    CONSTRAINT fk_outbound_subject__connector FOREIGN KEY (tenant_id, connector_id)
        REFERENCES ${flyway:defaultSchema}.connector (tenant_id, id),
    CONSTRAINT ck_outbound_subject__version CHECK (aggregate_version >= 0)
);

COMMENT ON TABLE ${flyway:defaultSchema}.outbound_subject IS 'Phiên bản đã gửi ra theo thực thể, để bản thay thế tham chiếu bản trước';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_subject.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_subject.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_subject.connector_id IS '{"cls":"C1","desc":"Kết nối"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_subject.subject_key IS '{"cls":"C1","desc":"Khoá thực thể nội bộ, ví dụ appraisal-profile/<id>"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_subject.current_event_id IS '{"cls":"C1","desc":"Sự kiện mới nhất đã đưa vào hàng gửi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_subject.previous_event_id IS '{"cls":"C1","desc":"Sự kiện trước đó, là bản bị thay"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_subject.aggregate_version IS '{"cls":"C1","desc":"Phiên bản thực thể của sự kiện mới nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_subject.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_subject.updated_at IS '{"cls":"C1"}';

-- ---------------------------------------------------------------- outbound_alert
CREATE TABLE ${flyway:defaultSchema}.outbound_alert (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    outbound_message_id uuid NOT NULL,
    alerted_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_outbound_alert PRIMARY KEY (id),
    CONSTRAINT uq_outbound_alert__message UNIQUE (tenant_id, outbound_message_id)
);

COMMENT ON TABLE ${flyway:defaultSchema}.outbound_alert IS 'Thông điệp ra đã cảnh báo quá hạn thử lại';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_alert.id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_alert.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_alert.outbound_message_id IS '{"cls":"C1","desc":"Thông điệp ra ở trạng thái LOI"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.outbound_alert.alerted_at IS '{"cls":"C1","desc":"Thời điểm cảnh báo"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.anchor_window');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.anchor_dispatch');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.outbound_subject');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.outbound_alert');

-- Biên nhận và dấu cảnh báo chỉ ghi thêm hoặc cập nhật, không xoá.
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.anchor_window FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.anchor_dispatch FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.outbound_alert FROM svc_${flyway:defaultSchema};

-- ---------------------------------------------------------------- hàm quét thông điệp lỗi chưa cảnh báo
-- Worker cảnh báo cần thấy thông điệp LOI của mọi tenant theo mã kết nối; chỉ trả mã và tenant, việc ghi dấu cảnh
-- báo làm trong ngữ cảnh tenant với RLS đầy đủ (cùng cách với fn_thu_ra_den_han).
CREATE FUNCTION ${flyway:defaultSchema}.fn_thu_ra_loi_chua_bao(p_codes varchar[], p_limit int)
    RETURNS TABLE (tenant_id uuid, id uuid, connector_code varchar)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT m.tenant_id, m.id, c.connector_code FROM ${flyway:defaultSchema}.outbound_message m
      JOIN ${flyway:defaultSchema}.connector c ON c.tenant_id = m.tenant_id AND c.id = m.connector_id
     WHERE m.status = 'LOI' AND c.connector_code = ANY (p_codes)
       AND NOT EXISTS (SELECT 1 FROM ${flyway:defaultSchema}.outbound_alert a
                        WHERE a.tenant_id = m.tenant_id AND a.outbound_message_id = m.id)
     ORDER BY m.updated_at
     LIMIT least(greatest(p_limit, 1), 500)
$$;
REVOKE ALL ON FUNCTION ${flyway:defaultSchema}.fn_thu_ra_loi_chua_bao(varchar[], int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_thu_ra_loi_chua_bao(varchar[], int) TO svc_${flyway:defaultSchema};

-- Chủ sở hữu hàm cũng chịu FORCE RLS: mở đúng phần hàm quét cần đọc.
CREATE POLICY rls_outbound_message__quet_loi ON ${flyway:defaultSchema}.outbound_message AS PERMISSIVE FOR SELECT TO CURRENT_USER
    USING (status = 'LOI');
CREATE POLICY rls_connector__quet_loi ON ${flyway:defaultSchema}.connector AS PERMISSIVE FOR SELECT TO CURRENT_USER
    USING (true);
CREATE POLICY rls_outbound_alert__quet ON ${flyway:defaultSchema}.outbound_alert AS PERMISSIVE FOR SELECT TO CURRENT_USER
    USING (true);
