-- CORE-02-16 · QT-05 bước 1b–2 · SCR-0502: văn bản chỉ đạo đến (EVT-24) và khoá đoạn giao việc.
--
-- Bổ sung (wiki trang 14): hợp đồng dùng mã văn bản đến ngay khi chưa có nhiệm vụ, nhưng TL-03 đặt
-- task_source_doc.task_id NOT NULL. Bảng incoming_doc giữ văn bản đến chờ xử lý của đơn vị nhận, khoá theo
-- documentId của EVT-24 để ai-gateway và MOD-02 dùng chung một mã; task_source_doc giữ đúng nghĩa liên kết
-- văn bản–nhiệm vụ. Không phân vùng theo kỳ: văn bản đến trước khi biết kỳ của nhiệm vụ và số lượng nhỏ.
--
-- incoming_doc_claim khoá mỗi (văn bản, đoạn) một lần (BR-05-07) cho cả tạo mới và gộp vào nhiệm vụ cũ;
-- khoá duy nhất của task_source_doc phải chứa period_bucket nên không chặn được trùng giữa hai kỳ.

CREATE TABLE ${flyway:defaultSchema}.incoming_doc (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    recipient_unit_id uuid,
    source_system varchar(12) NOT NULL,
    connector_code varchar(64) NOT NULL,
    external_doc_id varchar(128) NOT NULL,
    doc_number varchar(64) NOT NULL,
    doc_date date NOT NULL,
    issuer varchar(500) NOT NULL,
    excerpt varchar(1000) NOT NULL,
    doc_format varchar(16) NOT NULL DEFAULT 'EdXML',
    issuer_header varchar(500),
    title varchar(1000),
    preamble varchar(4000),
    paragraphs jsonb NOT NULL DEFAULT '[]'::jsonb,
    recipients varchar(2000),
    classification varchar(2) NOT NULL DEFAULT 'C1',
    content_loaded boolean NOT NULL DEFAULT false,
    object_key varchar(512),
    doc_version int NOT NULL,
    received_event_id uuid NOT NULL,
    received_at timestamptz NOT NULL,
    status varchar(12) NOT NULL DEFAULT 'CHO_XU_LY',
    dismiss_reason varchar(500),
    dismissed_by uuid,
    dismissed_at timestamptz,
    CONSTRAINT pk_incoming_doc PRIMARY KEY (tenant_id, id),
    CONSTRAINT ck_incoming_doc__source_system CHECK (source_system IN ('VOFFICE', 'IOFFICE', 'EDXML', 'LGSP', 'THU_CONG')),
    CONSTRAINT ck_incoming_doc__status CHECK (status IN ('CHO_XU_LY', 'DA_XU_LY', 'DA_BO_QUA')),
    CONSTRAINT ck_incoming_doc__dismiss CHECK (status <> 'DA_BO_QUA' OR length(dismiss_reason) > 0),
    CONSTRAINT ck_incoming_doc__classification CHECK (classification IN ('C0', 'C1', 'C2', 'C3')),
    CONSTRAINT ck_incoming_doc__paragraphs CHECK (jsonb_typeof(paragraphs) = 'array')
);
CREATE INDEX ix_incoming_doc__unit_status ON ${flyway:defaultSchema}.incoming_doc (tenant_id, recipient_unit_id, status, received_at DESC, id DESC);

COMMENT ON TABLE ${flyway:defaultSchema}.incoming_doc IS 'Văn bản chỉ đạo đến chờ xử lý ở đơn vị nhận (bổ sung, CORE-02-16)';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.id IS '{"cls":"C1","desc":"Mã văn bản nội bộ, bằng documentId của EVT-24"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.created_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.updated_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.updated_by IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.row_version IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.recipient_unit_id IS '{"cls":"C1","desc":"Đơn vị nhận đã ánh xạ ở SVC-13; trống khi chưa lấy được nội dung"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.source_system IS '{"cls":"C1","desc":"Hệ nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.connector_code IS '{"cls":"C1","desc":"Mã kết nối SVC-13"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.external_doc_id IS '{"cls":"C1","desc":"Mã văn bản ở hệ nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.doc_number IS '{"cls":"C0","desc":"Số, ký hiệu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.doc_date IS '{"cls":"C0","desc":"Ngày ban hành"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.issuer IS '{"cls":"C0","desc":"Cơ quan ban hành"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.excerpt IS '{"cls":"C1","desc":"Trích yếu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.doc_format IS '{"cls":"C1","desc":"Định dạng gói tin"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.issuer_header IS '{"cls":"C1","desc":"Tên cơ quan ở phần đầu văn bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.title IS '{"cls":"C1","desc":"Tên loại và tiêu đề văn bản"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.preamble IS '{"cls":"C1","desc":"Phần mở đầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.paragraphs IS '{"cls":"C1","desc":"Các đoạn nội dung [{no, text}]"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.recipients IS '{"cls":"C1","desc":"Nơi nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.classification IS '{"cls":"C1","desc":"Mức dữ liệu của văn bản; khác C0, C1 thì không gửi ai-gateway"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.content_loaded IS '{"cls":"C1","desc":"Đã lấy được toàn văn từ SVC-13"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.object_key IS '{"cls":"C1","desc":"Khoá tệp văn bản ở kho"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.doc_version IS '{"cls":"C1","desc":"Phiên bản văn bản ở hệ nguồn (aggregateVersion của EVT-24)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.received_event_id IS '{"cls":"C1","desc":"EVT-24 gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.received_at IS '{"cls":"C1","desc":"Thời điểm nhận lần đầu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.status IS '{"cls":"C1","desc":"CHO_XU_LY, DA_XU_LY, DA_BO_QUA"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.dismiss_reason IS '{"cls":"C1","desc":"Lý do bỏ qua"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.dismissed_by IS '{"cls":"C1","desc":"Người bỏ qua"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc.dismissed_at IS '{"cls":"C1","desc":"Thời điểm bỏ qua"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.incoming_doc');

CREATE TABLE ${flyway:defaultSchema}.incoming_doc_claim (
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    incoming_doc_id uuid NOT NULL,
    paragraph_no int NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    task_id uuid NOT NULL,
    period_bucket int NOT NULL,
    task_code varchar(32) NOT NULL,
    kind varchar(8) NOT NULL,
    CONSTRAINT pk_incoming_doc_claim PRIMARY KEY (tenant_id, incoming_doc_id, paragraph_no),
    CONSTRAINT fk_incoming_doc_claim__doc FOREIGN KEY (tenant_id, incoming_doc_id)
        REFERENCES ${flyway:defaultSchema}.incoming_doc (tenant_id, id),
    CONSTRAINT ck_incoming_doc_claim__paragraph_no CHECK (paragraph_no >= 1),
    CONSTRAINT ck_incoming_doc_claim__kind CHECK (kind IN ('TAO_MOI', 'GOP'))
);
CREATE INDEX ix_incoming_doc_claim__task ON ${flyway:defaultSchema}.incoming_doc_claim (tenant_id, task_id);

COMMENT ON TABLE ${flyway:defaultSchema}.incoming_doc_claim IS 'Đoạn giao việc đã sinh hoặc gộp nhiệm vụ, mỗi đoạn một lần (BR-05-07, bổ sung)';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc_claim.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc_claim.incoming_doc_id IS '{"cls":"C1","desc":"Văn bản đến"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc_claim.paragraph_no IS '{"cls":"C1","desc":"Số đoạn giao việc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc_claim.created_at IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc_claim.created_by IS '{"cls":"C1","desc":"Người xác nhận hoặc gộp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc_claim.task_id IS '{"cls":"C1","desc":"Nhiệm vụ đã tạo hoặc nhiệm vụ cũ được gộp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc_claim.period_bucket IS '{"cls":"C1","desc":"Phân vùng kỳ của nhiệm vụ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc_claim.task_code IS '{"cls":"C1","desc":"Mã nhiệm vụ, cho MSG-05-09"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.incoming_doc_claim.kind IS '{"cls":"C1","desc":"TAO_MOI hoặc GOP"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.incoming_doc_claim');

-- Liên kết văn bản–nhiệm vụ mang mã văn bản đến, gợi ý AI và vết ô AI giữ nguyên, ô đã sửa (BR-05-05).
ALTER TABLE ${flyway:defaultSchema}.task_source_doc
    ADD COLUMN incoming_doc_id uuid,
    ADD COLUMN suggestion_id uuid,
    ADD COLUMN ai_fields_kept jsonb,
    ADD COLUMN ai_fields_edited jsonb,
    ADD COLUMN similar_reason varchar(1000);
ALTER TABLE ${flyway:defaultSchema}.task_source_doc
    ADD CONSTRAINT ck_task_source_doc__ai_fields
    CHECK ((ai_fields_kept IS NULL OR jsonb_typeof(ai_fields_kept) = 'array')
       AND (ai_fields_edited IS NULL OR jsonb_typeof(ai_fields_edited) = 'array'));
CREATE INDEX ix_task_source_doc__incoming_doc ON ${flyway:defaultSchema}.task_source_doc (tenant_id, incoming_doc_id);

COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.incoming_doc_id IS '{"cls":"C1","desc":"Văn bản đến (incoming_doc)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.suggestion_id IS '{"cls":"C1","desc":"Gợi ý AI đã xác nhận (ai.suggestion_log, tham chiếu logic)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.ai_fields_kept IS '{"cls":"C1","desc":"Ô AI điền người giao giữ nguyên"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.ai_fields_edited IS '{"cls":"C1","desc":"Ô AI điền người giao đã sửa"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_source_doc.similar_reason IS '{"cls":"C1","desc":"Lý do vẫn tạo mới khi có nhiệm vụ gần giống (BR-05-06), dùng khi giao theo lô"}';
