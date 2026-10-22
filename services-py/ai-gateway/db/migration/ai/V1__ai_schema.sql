-- DV-14-01 · SVC-14 ai-gateway · TL-03 "Schema ai (SVC-14)" (L3958-4014), TL-01 chương "Trí tuệ nhân tạo", ADR-28.
-- CSDL `ai` ở cụm pg-sec, tách tenant bằng RLS. Chạy bằng vai trò mig_ai (chủ sở hữu schema). Extension vector
-- (pgvector ≥ 0.8) do quản trị CSDL cài trước vì cần quyền superuser; migration chỉ kiểm sự có mặt.
-- suggestion_log và guardrail_event phân vùng RANGE theo ngày bằng ai.fn_ensure_day_partitions, cùng cách làm
-- với notify (PL/pgSQL thay pg_partman để chạy được trên ảnh không có pg_partman, suy luận).

DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        RAISE EXCEPTION 'Thiếu extension vector (pgvector), quản trị CSDL cần chạy CREATE EXTENSION vector trước';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'svc_ai') THEN
        CREATE ROLE svc_ai NOLOGIN NOINHERIT NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ro_ai') THEN
        CREATE ROLE ro_ai NOLOGIN NOINHERIT NOBYPASSRLS;
    END IF;
END
$do$;

CREATE SCHEMA IF NOT EXISTS ai;
REVOKE ALL ON SCHEMA ai FROM PUBLIC;
GRANT USAGE ON SCHEMA ai TO svc_ai, ro_ai;

-- ─────────────────────────── ai.doc_chunk ───────────────────────────
CREATE TABLE ai.doc_chunk (
    id             uuid         NOT NULL,
    tenant_id      uuid         NOT NULL,
    source_type    varchar(32)  NOT NULL,
    source_id      uuid         NOT NULL,
    chunk_no       int          NOT NULL,
    content        text         NOT NULL,
    embedding      vector(1024) NOT NULL,
    embed_model    varchar(64)  NOT NULL,
    acl_unit_ids   uuid[]       NOT NULL,
    classification varchar(4)   NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_doc_chunk PRIMARY KEY (id),
    CONSTRAINT uq_doc_chunk__source UNIQUE (tenant_id, source_id, chunk_no, embed_model),
    CONSTRAINT ck_doc_chunk__classification CHECK (classification IN ('C0', 'C1')),
    CONSTRAINT ck_doc_chunk__chunk_no CHECK (chunk_no >= 0)
);

CREATE INDEX ix_doc_chunk__source ON ai.doc_chunk (tenant_id, source_type, source_id);
CREATE INDEX ix_doc_chunk__acl ON ai.doc_chunk USING gin (acl_unit_ids);
CREATE INDEX ix_doc_chunk__embedding ON ai.doc_chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

COMMENT ON TABLE ai.doc_chunk IS 'Đoạn tài liệu nguồn cho RAG; chỉ C0, C1, không bao giờ nhúng văn bản C2';
COMMENT ON COLUMN ai.doc_chunk.id IS '{"cls":"C1","desc":"Mã"}';
COMMENT ON COLUMN ai.doc_chunk.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ai.doc_chunk.source_type IS '{"cls":"C1","desc":"Loại nguồn"}';
COMMENT ON COLUMN ai.doc_chunk.source_id IS '{"cls":"C1","desc":"Nguồn"}';
COMMENT ON COLUMN ai.doc_chunk.chunk_no IS '{"cls":"C1","desc":"Thứ tự"}';
COMMENT ON COLUMN ai.doc_chunk.content IS '{"cls":"C1","desc":"Nội dung (chỉ C0, C1)"}';
COMMENT ON COLUMN ai.doc_chunk.embedding IS '{"cls":"C1","desc":"Véc-tơ"}';
COMMENT ON COLUMN ai.doc_chunk.embed_model IS '{"cls":"C0","desc":"Mô hình nhúng"}';
COMMENT ON COLUMN ai.doc_chunk.acl_unit_ids IS '{"cls":"C1","desc":"Đơn vị được xem"}';
COMMENT ON COLUMN ai.doc_chunk.classification IS '{"cls":"C1","desc":"Phân loại"}';
COMMENT ON COLUMN ai.doc_chunk.created_at IS '{"cls":"C1","desc":"Thời điểm lập chỉ mục (suy luận)"}';

-- ─────────────────────────── ai.suggestion_log ───────────────────────────
CREATE TABLE ai.suggestion_log (
    created_day             date          NOT NULL,
    id                      uuid          NOT NULL,
    tenant_id               uuid          NOT NULL,
    user_id                 uuid          NOT NULL,
    use_case                varchar(32)   NOT NULL,
    model                   varchar(64)   NOT NULL,
    prompt_template_version varchar(32)   NOT NULL,
    prompt_sha256           bytea         NOT NULL,
    chunk_ids               uuid[]        NOT NULL DEFAULT '{}',
    output_text             text,
    user_action             varchar(12),
    target_ref              varchar(128),
    kind                    varchar(24),
    prompt_redacted         text,
    sources_json            jsonb,
    confidence              numeric(6, 4),
    decision                varchar(12),
    edited_fields           varchar(64)[],
    accepted_by             uuid,
    created_at              timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_suggestion_log PRIMARY KEY (created_day, id),
    CONSTRAINT ck_suggestion_log__user_action CHECK (user_action IN ('CHAP_NHAN', 'SUA', 'BO_QUA')),
    CONSTRAINT ck_suggestion_log__decision CHECK (decision IN ('CHAP_NHAN', 'SUA', 'BO_QUA')),
    CONSTRAINT ck_suggestion_log__sha256 CHECK (octet_length(prompt_sha256) = 32),
    CONSTRAINT ck_suggestion_log__confidence CHECK (confidence BETWEEN 0 AND 1)
) PARTITION BY RANGE (created_day);

CREATE INDEX ix_suggestion_log__user ON ai.suggestion_log (tenant_id, user_id, created_at DESC);
CREATE INDEX ix_suggestion_log__id ON ai.suggestion_log (tenant_id, id);
CREATE INDEX ix_suggestion_log__kind ON ai.suggestion_log (tenant_id, kind, created_at DESC) WHERE kind IS NOT NULL;

COMMENT ON TABLE ai.suggestion_log IS 'Mọi gợi ý AI và phản hồi của người dùng; không lưu C2 (BR-17-07)';
COMMENT ON COLUMN ai.suggestion_log.created_day IS '{"cls":"C1","desc":"Ngày tạo, khoá phân vùng"}';
COMMENT ON COLUMN ai.suggestion_log.id IS '{"cls":"C1","desc":"Mã"}';
COMMENT ON COLUMN ai.suggestion_log.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ai.suggestion_log.user_id IS '{"cls":"C1","desc":"Người hỏi"}';
COMMENT ON COLUMN ai.suggestion_log.use_case IS '{"cls":"C0","desc":"Ca dùng"}';
COMMENT ON COLUMN ai.suggestion_log.model IS '{"cls":"C0","desc":"Mô hình"}';
COMMENT ON COLUMN ai.suggestion_log.prompt_template_version IS '{"cls":"C0","desc":"Mẫu lời nhắc"}';
COMMENT ON COLUMN ai.suggestion_log.prompt_sha256 IS '{"cls":"C1","desc":"Băm lời nhắc"}';
COMMENT ON COLUMN ai.suggestion_log.chunk_ids IS '{"cls":"C1","desc":"Đoạn đã dùng"}';
COMMENT ON COLUMN ai.suggestion_log.output_text IS '{"cls":"C1","desc":"Gợi ý"}';
COMMENT ON COLUMN ai.suggestion_log.user_action IS '{"cls":"C1","desc":"Phản hồi"}';
COMMENT ON COLUMN ai.suggestion_log.target_ref IS '{"cls":"C1","desc":"Bản ghi nhận gợi ý"}';
COMMENT ON COLUMN ai.suggestion_log.kind IS '{"cls":"C0","desc":"Loại gợi ý chi tiết"}';
COMMENT ON COLUMN ai.suggestion_log.prompt_redacted IS '{"cls":"C1","desc":"Lời nhắc đã che dữ liệu cá nhân"}';
COMMENT ON COLUMN ai.suggestion_log.sources_json IS '{"cls":"C1","desc":"Nguồn trích dẫn"}';
COMMENT ON COLUMN ai.suggestion_log.confidence IS '{"cls":"C1","desc":"Độ tin cậy"}';
COMMENT ON COLUMN ai.suggestion_log.decision IS '{"cls":"C1","desc":"Quyết định của người dùng"}';
COMMENT ON COLUMN ai.suggestion_log.edited_fields IS '{"cls":"C1","desc":"Các ô AI người dùng đã sửa"}';
COMMENT ON COLUMN ai.suggestion_log.accepted_by IS '{"cls":"C1","desc":"Người chấp nhận gợi ý"}';
COMMENT ON COLUMN ai.suggestion_log.created_at IS '{"cls":"C1","desc":"Thời điểm tạo (suy luận)"}';

-- ─────────────────────────── ai.guardrail_event ───────────────────────────
CREATE TABLE ai.guardrail_event (
    created_day       date        NOT NULL,
    id                uuid        NOT NULL,
    tenant_id         uuid        NOT NULL,
    suggestion_log_id uuid,
    rule_code         varchar(32) NOT NULL,
    action            varchar(8)  NOT NULL,
    detail            jsonb,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_guardrail_event PRIMARY KEY (created_day, id),
    CONSTRAINT ck_guardrail_event__action CHECK (action IN ('CHAN', 'CHE', 'CANH_BAO'))
) PARTITION BY RANGE (created_day);

CREATE INDEX ix_guardrail_event__suggestion ON ai.guardrail_event (tenant_id, suggestion_log_id);
CREATE INDEX ix_guardrail_event__rule ON ai.guardrail_event (tenant_id, rule_code, created_at DESC);

COMMENT ON TABLE ai.guardrail_event IS 'Các lần rào chắn chặn, che hoặc cảnh báo';
COMMENT ON COLUMN ai.guardrail_event.created_day IS '{"cls":"C1","desc":"Ngày tạo, khoá phân vùng"}';
COMMENT ON COLUMN ai.guardrail_event.id IS '{"cls":"C1","desc":"Mã"}';
COMMENT ON COLUMN ai.guardrail_event.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ai.guardrail_event.suggestion_log_id IS '{"cls":"C1","desc":"Lần gợi ý"}';
COMMENT ON COLUMN ai.guardrail_event.rule_code IS '{"cls":"C0","desc":"Rào chắn"}';
COMMENT ON COLUMN ai.guardrail_event.action IS '{"cls":"C1","desc":"Hành động"}';
COMMENT ON COLUMN ai.guardrail_event.detail IS '{"cls":"C1","desc":"Chi tiết"}';
COMMENT ON COLUMN ai.guardrail_event.created_at IS '{"cls":"C1","desc":"Thời điểm (suy luận)"}';

-- ─────────────────────────── RLS theo tenant ───────────────────────────
ALTER TABLE ai.doc_chunk ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai.doc_chunk FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_doc_chunk__tenant ON ai.doc_chunk
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE ai.suggestion_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai.suggestion_log FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_suggestion_log__tenant ON ai.suggestion_log
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE ai.guardrail_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai.guardrail_event FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_guardrail_event__tenant ON ai.guardrail_event
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ─────────────────────────── Phân vùng ngày ───────────────────────────
-- Tạo phân vùng ngày [p_from, p_from + p_days) cho suggestion_log và guardrail_event nếu chưa có; trả số
-- phân vùng mới. svc_ai không có quyền trực tiếp trên phân vùng.
CREATE FUNCTION ai.fn_ensure_day_partitions(p_from date, p_days int DEFAULT 7)
    RETURNS int
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $fn$
DECLARE
    v_day     date;
    v_parent  text;
    v_name    text;
    v_created int := 0;
BEGIN
    IF p_from IS NULL OR p_days IS NULL OR p_days NOT BETWEEN 1 AND 120 THEN
        RAISE EXCEPTION 'Số ngày tạo trước phải từ 1 đến 120, nhận %', p_days;
    END IF;
    FOR i IN 0 .. p_days - 1 LOOP
        v_day := p_from + i;
        FOREACH v_parent IN ARRAY ARRAY['suggestion_log', 'guardrail_event'] LOOP
            v_name := v_parent || '_p' || to_char(v_day, 'YYYYMMDD');
            IF to_regclass('ai.' || v_name) IS NULL THEN
                EXECUTE format(
                    'CREATE TABLE ai.%I PARTITION OF ai.%I FOR VALUES FROM (%L) TO (%L)',
                    v_name, v_parent, v_day, v_day + 1);
                v_created := v_created + 1;
            END IF;
        END LOOP;
    END LOOP;
    RETURN v_created;
END
$fn$;

-- ─────────────────────────── Quyền ───────────────────────────
-- Service không có tài khoản ghi vào schema nghiệp vụ nào (TL-01 SVC-14); trong schema ai, nhật ký gợi ý chỉ
-- được thêm và cập nhật phản hồi của người dùng, không xoá.
GRANT SELECT, INSERT, UPDATE, DELETE ON ai.doc_chunk TO svc_ai;
GRANT SELECT, INSERT, UPDATE ON ai.suggestion_log TO svc_ai;
GRANT SELECT, INSERT ON ai.guardrail_event TO svc_ai;
GRANT SELECT ON ai.doc_chunk, ai.suggestion_log, ai.guardrail_event TO ro_ai;

REVOKE EXECUTE ON FUNCTION ai.fn_ensure_day_partitions(date, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ai.fn_ensure_day_partitions(date, int) TO svc_ai;

-- Phân vùng khởi đầu: 3 ngày trước tới 7 ngày sau ngày chạy migration.
SELECT ai.fn_ensure_day_partitions(current_date - 3, 11);
