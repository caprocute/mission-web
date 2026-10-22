-- DV-14-04 · SVC-14 ai-gateway · khoá chống lặp cho API POST của trợ lý và trạng thái trích nhiệm vụ từ văn bản đến.

-- ─────────────────────────── ai.idempotency_key (TL-03 "Bảng idempotency_key") ───────────────────────────
CREATE TABLE ai.idempotency_key (
    tenant_id     uuid         NOT NULL,
    actor_id      uuid         NOT NULL,
    idem_key      varchar(64)  NOT NULL,
    created_day   date         NOT NULL,
    request_hash  bytea        NOT NULL,
    status        varchar(12)  NOT NULL,
    response_code smallint,
    response_body jsonb,
    resource_ref  varchar(128),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_idempotency_key PRIMARY KEY (tenant_id, actor_id, idem_key, created_day),
    CONSTRAINT ck_idempotency_key__status CHECK (status IN ('DANG_XU_LY', 'XONG')),
    CONSTRAINT ck_idempotency_key__hash CHECK (octet_length(request_hash) = 32)
) PARTITION BY RANGE (created_day);

COMMENT ON TABLE ai.idempotency_key IS 'Khoá chống lặp API ghi của SVC-14, giữ 48 giờ (bảng bổ sung TL-03)';
COMMENT ON COLUMN ai.idempotency_key.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ai.idempotency_key.actor_id IS '{"cls":"C1","desc":"Người gọi"}';
COMMENT ON COLUMN ai.idempotency_key.idem_key IS '{"cls":"C1","desc":"Giá trị tiêu đề Idempotency-Key"}';
COMMENT ON COLUMN ai.idempotency_key.created_day IS '{"cls":"C1","desc":"Ngày tạo, khoá phân vùng"}';
COMMENT ON COLUMN ai.idempotency_key.request_hash IS '{"cls":"C1","desc":"SHA-256 phương thức, đường dẫn, thân"}';
COMMENT ON COLUMN ai.idempotency_key.status IS '{"cls":"C1","desc":"Trạng thái"}';
COMMENT ON COLUMN ai.idempotency_key.response_code IS '{"cls":"C1","desc":"Mã HTTP đã trả"}';
COMMENT ON COLUMN ai.idempotency_key.response_body IS '{"cls":"C1","desc":"Phản hồi đã trả, đã che"}';
COMMENT ON COLUMN ai.idempotency_key.resource_ref IS '{"cls":"C1","desc":"Mã gợi ý đã tạo"}';
COMMENT ON COLUMN ai.idempotency_key.created_at IS '{"cls":"C1","desc":"Thời điểm"}';

ALTER TABLE ai.idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai.idempotency_key FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_idempotency_key__tenant ON ai.idempotency_key
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- ─────────────────────────── ai.doc_extraction (bảng bổ sung, suy luận) ───────────────────────────
-- Trạng thái trích nhiệm vụ của một văn bản đến (QT-05 bước 2, SCR-0502); mỗi nhiệm vụ trích được là một dòng
-- ai.suggestion_log use_case GOI_Y_NHIEM_VU, target_ref là mã văn bản.
CREATE TABLE ai.doc_extraction (
    tenant_id     uuid         NOT NULL,
    source_doc_id uuid         NOT NULL,
    status        varchar(16)  NOT NULL,
    doc_version   bigint       NOT NULL DEFAULT 0,
    model         varchar(64)  NOT NULL,
    task_count    int          NOT NULL DEFAULT 0,
    extracted_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_doc_extraction PRIMARY KEY (tenant_id, source_doc_id),
    CONSTRAINT ck_doc_extraction__status CHECK (status IN ('DANG_TRICH', 'XONG', 'KHONG_TRICH_DUOC'))
);

COMMENT ON TABLE ai.doc_extraction IS 'Trạng thái AI trích nhiệm vụ từ văn bản đến (bảng bổ sung)';
COMMENT ON COLUMN ai.doc_extraction.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ai.doc_extraction.source_doc_id IS '{"cls":"C1","desc":"Mã văn bản đến"}';
COMMENT ON COLUMN ai.doc_extraction.status IS '{"cls":"C0","desc":"Tình trạng trích"}';
COMMENT ON COLUMN ai.doc_extraction.doc_version IS '{"cls":"C1","desc":"Phiên bản văn bản đã trích"}';
COMMENT ON COLUMN ai.doc_extraction.model IS '{"cls":"C0","desc":"Mô hình"}';
COMMENT ON COLUMN ai.doc_extraction.task_count IS '{"cls":"C1","desc":"Số nhiệm vụ trích được"}';
COMMENT ON COLUMN ai.doc_extraction.extracted_at IS '{"cls":"C1","desc":"Thời điểm cập nhật"}';

ALTER TABLE ai.doc_extraction ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai.doc_extraction FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_doc_extraction__tenant ON ai.doc_extraction
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE INDEX ix_suggestion_log__target ON ai.suggestion_log (tenant_id, use_case, target_ref)
    WHERE target_ref IS NOT NULL;

GRANT SELECT, INSERT, UPDATE, DELETE ON ai.idempotency_key TO svc_ai;
GRANT SELECT, INSERT, UPDATE ON ai.doc_extraction TO svc_ai;
GRANT SELECT ON ai.doc_extraction TO ro_ai;

-- Thêm idempotency_key vào hàm tạo phân vùng ngày.
CREATE OR REPLACE FUNCTION ai.fn_ensure_day_partitions(p_from date, p_days int DEFAULT 7)
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
        FOREACH v_parent IN ARRAY ARRAY['suggestion_log', 'guardrail_event', 'idempotency_key'] LOOP
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

SELECT ai.fn_ensure_day_partitions(current_date - 3, 11);
