-- DV-14-02 · SVC-14 ai-gateway · kho RAG và inbox cho consumer EVT-24 (TL-03 "Schema ai", "Inbox idempotent").
-- Tài liệu dùng chung (quy định, hướng dẫn, danh mục mẫu) mang tenant_id dùng chung
-- 00000000-0000-0000-0000-000000000000 và đọc được ở mọi tenant; ACL mở trong tenant biểu diễn bằng phần tử
-- 00000000-0000-0000-0000-000000000000 trong acl_unit_ids để truy vấn vẫn dùng một phép && có chỉ mục GIN
-- (suy luận, ghi ở wiki/14).

CREATE POLICY rls_doc_chunk__shared_read ON ai.doc_chunk AS PERMISSIVE FOR SELECT
    USING (tenant_id = '00000000-0000-0000-0000-000000000000'::uuid);

-- Lọc theo mô hình nhúng khi truy xuất và khi lập chỉ mục lại một nguồn.
CREATE INDEX ix_doc_chunk__model_source ON ai.doc_chunk (tenant_id, embed_model, source_id);

-- ─────────────────────────── Inbox (cùng cấu trúc V0_21__inbox.sql của NEN-032) ───────────────────────────
CREATE TABLE ai.inbox_processed (
    event_id        uuid         NOT NULL,
    consumer        varchar(100) NOT NULL,
    processed_at    timestamptz  NOT NULL DEFAULT now(),
    tenant_id       uuid         NOT NULL,
    source_topic    varchar(128) NOT NULL,
    kafka_partition int          NOT NULL,
    kafka_offset    bigint       NOT NULL,
    CONSTRAINT pk_inbox_processed PRIMARY KEY (consumer, event_id)
);
CREATE INDEX ix_inbox_processed__processed_at ON ai.inbox_processed (processed_at);

CREATE TABLE ai.inbox_aggregate_version (
    consumer       varchar(100) NOT NULL,
    aggregate_type varchar(64)  NOT NULL,
    aggregate_id   uuid         NOT NULL,
    tenant_id      uuid         NOT NULL,
    last_version   bigint       NOT NULL,
    last_event_id  uuid         NOT NULL,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_inbox_aggregate_version PRIMARY KEY (consumer, aggregate_type, aggregate_id)
);

ALTER TABLE ai.inbox_processed ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai.inbox_processed FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_inbox_processed__tenant ON ai.inbox_processed
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE ai.inbox_aggregate_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai.inbox_aggregate_version FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_inbox_aggregate_version__tenant ON ai.inbox_aggregate_version
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON ai.inbox_processed TO svc_ai;
GRANT SELECT, INSERT, UPDATE ON ai.inbox_aggregate_version TO svc_ai;
