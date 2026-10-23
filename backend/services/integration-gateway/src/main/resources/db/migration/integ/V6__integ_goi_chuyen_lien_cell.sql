-- DV-13-10 · SVC-13: gói chuyển hồ sơ cán bộ điều động liên cell (TL-03 "Đồng bộ giữa các cell",
-- luồng thứ ba; baseline mục 8 cơ chế 7). Bảng transfer_package có từ V1; bản này thêm các cột cần
-- cho việc gửi lại, nhập và báo kết quả hai phía (suy luận, ghi ở wiki trang 14):
--   * peer_tenant_id: tenant đích (phía DI) hoặc tenant nguồn chưa biết mã nên để trống (phía DEN
--     là tenant của chính dòng).
--   * signed_header: phần đầu gói đã ký (mã gói, cell, tenant, manifest, băm bản mã và khoá bọc);
--     giữ nguyên byte để gửi lại và kiểm lại chữ ký, không có dữ liệu cá nhân.
--   * reason: mã lý do từ chối (TU_CHOI) hoặc lỗi gần nhất khi đang thử lại.
--   * attempts, next_attempt_at: lịch thử lại của worker gửi (DI) và nhập (DEN).
--   * peer_notified_at: thời điểm đã báo kết quả nhập hoặc từ chối về cell nguồn (phía DEN), hoặc đã
--     nhận kết quả từ cell đích (phía DI).
-- Gói đi và gói đến dùng cùng một id (do cell nguồn cấp), nên gửi trùng không sinh dòng thứ hai.

ALTER TABLE ${flyway:defaultSchema}.transfer_package
    ADD COLUMN peer_tenant_id uuid,
    ADD COLUMN signed_header bytea,
    ADD COLUMN reason varchar(64),
    ADD COLUMN attempts int NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN peer_notified_at timestamptz,
    ADD CONSTRAINT ck_transfer_package__attempts CHECK (attempts >= 0),
    ADD CONSTRAINT ck_transfer_package__reason CHECK ((status = 'TU_CHOI') <= (reason IS NOT NULL)),
    ADD CONSTRAINT ck_transfer_package__peer_tenant CHECK (direction = 'DEN' OR peer_tenant_id IS NOT NULL);

COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.peer_tenant_id IS '{"cls":"C1","desc":"Tenant đích của gói đi"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.signed_header IS '{"cls":"C1","desc":"Phần đầu gói đã ký, giữ nguyên byte"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.reason IS '{"cls":"C1","desc":"Mã lý do từ chối hoặc lỗi gần nhất"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.attempts IS '{"cls":"C1","desc":"Số lần gửi hoặc nhập đã thử"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.next_attempt_at IS '{"cls":"C1","desc":"Lần thử kế tiếp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transfer_package.peer_notified_at IS '{"cls":"C1","desc":"Đã báo hoặc đã nhận kết quả từ cell đối tác"}';

CREATE INDEX ix_transfer_package__person ON ${flyway:defaultSchema}.transfer_package (tenant_id, person_id, created_at DESC);
CREATE INDEX ix_transfer_package__can_xu_ly ON ${flyway:defaultSchema}.transfer_package (next_attempt_at)
    WHERE status IN ('DONG_GOI', 'DA_NHAN') OR (direction = 'DEN' AND peer_notified_at IS NULL);

-- Worker gói chuyển cần việc của mọi tenant: gói đi chưa gửi, gói đến chưa nhập, gói đến đã có kết
-- quả nhưng chưa báo được về cell nguồn. Chỉ trả mã và tenant như các hàm quét V1.
CREATE FUNCTION ${flyway:defaultSchema}.fn_goi_chuyen_can_xu_ly(p_limit int)
    RETURNS TABLE (tenant_id uuid, id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp
AS $$
    SELECT p.tenant_id, p.id FROM ${flyway:defaultSchema}.transfer_package p
     WHERE ((p.direction = 'DI' AND p.status = 'DONG_GOI')
            OR (p.direction = 'DEN' AND p.status = 'DA_NHAN')
            OR (p.direction = 'DEN' AND p.status IN ('DA_NHAP', 'TU_CHOI') AND p.peer_notified_at IS NULL))
       AND coalesce(p.next_attempt_at, '-infinity'::timestamptz) <= now()
     ORDER BY p.next_attempt_at NULLS FIRST
     LIMIT least(greatest(p_limit, 1), 100)
$$;
REVOKE ALL ON FUNCTION ${flyway:defaultSchema}.fn_goi_chuyen_can_xu_ly(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_goi_chuyen_can_xu_ly(int) TO svc_${flyway:defaultSchema};

CREATE POLICY rls_transfer_package__quet_can_xu_ly ON ${flyway:defaultSchema}.transfer_package AS PERMISSIVE FOR SELECT TO CURRENT_USER
    USING (status IN ('DONG_GOI', 'DA_NHAN') OR (direction = 'DEN' AND peer_notified_at IS NULL));
