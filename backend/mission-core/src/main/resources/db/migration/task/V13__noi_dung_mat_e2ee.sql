-- CORE-07-06 · Nội dung nhiệm vụ mật mã hoá đầu cuối C3 (ADR-27, TL-03 "Mã hoá đầu cuối cho nội dung mật",
-- bảng bổ sung task.task_secret_content và task.e2ee_key_wrap; tasks.yaml getTaskSecretContent,
-- saveTaskSecretContent, shareTaskSecretContent, revokeTaskSecretShare).
--
-- Máy chủ chỉ giữ bản mã và khoá nội dung đã bọc; không có cột nào chứa nội dung rõ.
--
-- Điểm lệch và lựa chọn (wiki trang 14):
--   * Đánh số V13 vì V11, V12 đã có ở nhánh khác và Flyway chạy với outOfOrder = false.
--   * Khoá chính, khoá duy nhất, khoá ngoại có period_bucket và tenant_id như các bảng task khác (TL-03 ghi
--     PK (period_bucket, id), UQ (period_bucket, task_id)).
--   * Thêm content_sha256 (SHA-256 của bản mã) vì hợp đồng TaskSecretContentInput bắt buộc sha256 mà TL-03
--     chưa có cột; máy chủ tính lại để kiểm toàn vẹn bản mã.
--   * e2ee_key_wrap không phân vùng theo kỳ, như product.e2ee_key_wrap (CORE-03-01).
--   * Không xoá dòng: thu hồi đặt revoked_at, nên thu hồi DELETE và TRUNCATE của svc_task.

-- ---------------------------------------------------------------- task_secret_content
CREATE TABLE ${flyway:defaultSchema}.task_secret_content (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    period_id uuid NOT NULL,
    period_bucket int NOT NULL,
    task_id uuid NOT NULL,
    ciphertext bytea NOT NULL,
    nonce bytea NOT NULL,
    content_alg varchar(16) NOT NULL DEFAULT 'A256GCM',
    aad_hash bytea NOT NULL,
    content_sha256 bytea NOT NULL,
    title_bidx bytea,
    sender_key_fingerprint bytea NOT NULL,
    CONSTRAINT pk_task_secret_content PRIMARY KEY (period_bucket, tenant_id, id),
    CONSTRAINT uq_task_secret_content__task UNIQUE (period_bucket, tenant_id, task_id),
    CONSTRAINT fk_task_secret_content__task FOREIGN KEY (period_bucket, tenant_id, task_id)
        REFERENCES ${flyway:defaultSchema}.task (period_bucket, tenant_id, id),
    CONSTRAINT ck_task_secret_content__nonce CHECK (length(nonce) = 12),
    CONSTRAINT ck_task_secret_content__content_alg CHECK (content_alg IN ('A256GCM')),
    CONSTRAINT ck_task_secret_content__aad_hash CHECK (length(aad_hash) = 32),
    CONSTRAINT ck_task_secret_content__sha256 CHECK (length(content_sha256) = 32),
    CONSTRAINT ck_task_secret_content__sender CHECK (length(sender_key_fingerprint) = 32)
) PARTITION BY LIST (period_bucket);
CREATE INDEX ix_task_secret_content__title_bidx ON ${flyway:defaultSchema}.task_secret_content (tenant_id, title_bidx)
    WHERE title_bidx IS NOT NULL;

COMMENT ON TABLE ${flyway:defaultSchema}.task_secret_content IS 'Nội dung nhiệm vụ mật dạng bản mã đầu cuối (C3, ADR-27)';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.id IS '{"cls":"C1","desc":"Mã bản ghi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.created_at IS '{"cls":"C1","desc":"Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.created_by IS '{"cls":"C1","desc":"Người tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.updated_at IS '{"cls":"C1","desc":"Thời điểm sửa"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.updated_by IS '{"cls":"C1","desc":"Người sửa"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.row_version IS '{"cls":"C1","desc":"Phiên bản dòng (ETag)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.period_id IS '{"cls":"C1","desc":"Kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.period_bucket IS '{"cls":"C1","desc":"Khoá phân vùng của kỳ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.task_id IS '{"cls":"C1","desc":"Nhiệm vụ mật"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.ciphertext IS '{"cls":"C3","desc":"Nội dung mã hoá AES-256-GCM tại client, máy chủ không giải mã được"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.nonce IS '{"cls":"C1","desc":"Nonce 96 bit"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.content_alg IS '{"cls":"C1","desc":"Thuật toán nội dung"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.aad_hash IS '{"cls":"C1","desc":"Băm dữ liệu xác thực kèm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.content_sha256 IS '{"cls":"C1","desc":"SHA-256 của bản mã (bổ sung theo hợp đồng)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.title_bidx IS '{"cls":"C1","desc":"Chỉ mục mù HMAC do client tính"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.task_secret_content.sender_key_fingerprint IS '{"cls":"C1","desc":"Vân tay khoá của người lưu"}';

-- ---------------------------------------------------------------- e2ee_key_wrap
CREATE TABLE ${flyway:defaultSchema}.e2ee_key_wrap (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    object_type varchar(16) NOT NULL DEFAULT 'TASK_CONTENT',
    object_id uuid NOT NULL,
    recipient_person_id uuid NOT NULL,
    recipient_key_fingerprint bytea NOT NULL,
    wrap_alg varchar(24) NOT NULL,
    wrapped_cek bytea NOT NULL,
    ephemeral_pub bytea,
    granted_by uuid NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT pk_e2ee_key_wrap PRIMARY KEY (id),
    CONSTRAINT uq_e2ee_key_wrap__recipient UNIQUE (tenant_id, object_id, recipient_key_fingerprint),
    CONSTRAINT ck_e2ee_key_wrap__object_type CHECK (object_type IN ('TASK_CONTENT')),
    CONSTRAINT ck_e2ee_key_wrap__wrap_alg CHECK (wrap_alg IN ('ECDH-ES+A256KW', 'RSA-OAEP-256', 'BCY')),
    CONSTRAINT ck_e2ee_key_wrap__fingerprint CHECK (length(recipient_key_fingerprint) = 32),
    CONSTRAINT ck_e2ee_key_wrap__ephemeral CHECK (wrap_alg <> 'ECDH-ES+A256KW' OR ephemeral_pub IS NOT NULL)
);
CREATE INDEX ix_e2ee_key_wrap__recipient ON ${flyway:defaultSchema}.e2ee_key_wrap (tenant_id, recipient_person_id, object_id);

COMMENT ON TABLE ${flyway:defaultSchema}.e2ee_key_wrap IS 'Khoá nội dung nhiệm vụ mật đã bọc cho từng người nhận và khoá phục hồi tổ chức';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.id IS '{"cls":"C1","desc":"Mã bản ghi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.tenant_id IS '{"cls":"C1","desc":"Tenant"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.created_at IS '{"cls":"C1","desc":"Thời điểm tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.created_by IS '{"cls":"C1","desc":"Người tạo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.updated_at IS '{"cls":"C1","desc":"Thời điểm sửa"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.updated_by IS '{"cls":"C1","desc":"Người sửa"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.row_version IS '{"cls":"C1","desc":"Phiên bản dòng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.object_type IS '{"cls":"C1","desc":"Loại đối tượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.object_id IS '{"cls":"C1","desc":"Mã nhiệm vụ mật"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.recipient_person_id IS '{"cls":"C1","desc":"Người nhận"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.recipient_key_fingerprint IS '{"cls":"C1","desc":"Vân tay khoá công khai đã dùng (org.person_public_key.fingerprint)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.wrap_alg IS '{"cls":"C1","desc":"Thuật toán bọc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.wrapped_cek IS '{"cls":"C1","desc":"Khoá nội dung đã bọc, chỉ người nhận mở được"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.ephemeral_pub IS '{"cls":"C1","desc":"Khoá công khai tạm thời (ECDH-ES)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.granted_by IS '{"cls":"C1","desc":"Người cấp quyền đọc"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.e2ee_key_wrap.revoked_at IS '{"cls":"C1","desc":"Thu hồi, chặn tải bản mã về"}';

-- ---------------------------------------------------------------- RLS (NEN-033), không xoá dòng
-- Kỳ đang khoá hoặc đã khoá thì không ghi nội dung mật (cùng trigger với các bảng theo kỳ khác, V4).
CREATE TRIGGER tg_task_secret_content__period_guard BEFORE INSERT OR UPDATE OR DELETE
    ON ${flyway:defaultSchema}.task_secret_content
    FOR EACH ROW EXECUTE FUNCTION ${flyway:defaultSchema}.fn_guard_period_write();

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.task_secret_content');
SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.e2ee_key_wrap');

REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.task_secret_content FROM svc_${flyway:defaultSchema};
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.e2ee_key_wrap FROM svc_${flyway:defaultSchema};
