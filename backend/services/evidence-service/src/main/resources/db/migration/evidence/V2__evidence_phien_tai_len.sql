-- DV-08-02 · Phiên tải lên nhiều phần qua evidence-service (TL-01 SVC-08, hợp đồng products.yaml /files/uploads*).
--
-- Điểm lệch và lựa chọn (wiki trang 14 mục 47):
--   * Hợp đồng cho ownerRefId không bắt buộc ("có thể gắn sau khi nộp"), nên object_meta.owner_ref_id cho phép NULL.
--   * Phiên tải lên giữ thông tin khai báo (kiểu tệp, phân loại, chủ sở hữu, tên tệp mã hoá) tới lúc hoàn tất, vì
--     object_meta chỉ được tạo khi tệp đã kiểm băm và kiểu; TL-03 không liệt kê các cột này (bảng bổ sung).
--   * Hàm fn_phien_tai_len_het_han cho job dọn phiên quá hạn của mọi tenant (chỉ mã và tenant), như docgen.

ALTER TABLE ${flyway:defaultSchema}.object_meta ALTER COLUMN owner_ref_id DROP NOT NULL;
COMMENT ON COLUMN ${flyway:defaultSchema}.object_meta.owner_ref_id IS '{"cls":"C1","desc":"Bản ghi nghiệp vụ; có thể gắn sau khi nộp"}';

ALTER TABLE ${flyway:defaultSchema}.upload_session
    ADD COLUMN part_size int NOT NULL,
    ADD COLUMN media_type varchar(100) NOT NULL,
    ADD COLUMN classification varchar(4) NOT NULL,
    ADD COLUMN owner_module varchar(16) NOT NULL,
    ADD COLUMN owner_ref_id uuid,
    ADD COLUMN file_name_enc bytea NOT NULL,
    ADD COLUMN dek_id uuid NOT NULL,
    ADD CONSTRAINT ck_upload_session__part_size CHECK (part_size BETWEEN 5242880 AND 16777216),
    ADD CONSTRAINT ck_upload_session__classification CHECK (classification IN ('C1', 'C2', 'C3')),
    ADD CONSTRAINT ck_upload_session__owner_module CHECK (owner_module IN ('product', 'dossier', 'oversight'));

COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.part_size IS '{"cls":"C1","desc":"Kích thước mỗi phần (byte), phần cuối có thể nhỏ hơn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.media_type IS '{"cls":"C1","desc":"Kiểu MIME khai báo"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.classification IS '{"cls":"C1","desc":"Phân loại tệp; C3 là bản mã đầu cuối"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.owner_module IS '{"cls":"C1","desc":"Module sở hữu"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.owner_ref_id IS '{"cls":"C1","desc":"Bản ghi nghiệp vụ nếu đã có"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.file_name_enc IS '{"cls":"C2","desc":"Tên tệp gốc (mã hoá phong bì)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.upload_session.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';

CREATE FUNCTION ${flyway:defaultSchema}.fn_phien_tai_len_het_han(p_limit int)
  RETURNS TABLE (id uuid, tenant_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = ${flyway:defaultSchema}, pg_temp
AS $$
  SELECT s.id, s.tenant_id FROM ${flyway:defaultSchema}.upload_session s
   WHERE s.status = 'MO' AND s.expires_at < now()
   ORDER BY s.expires_at
   LIMIT least(greatest(p_limit, 1), 500)
$$;
REVOKE ALL ON FUNCTION ${flyway:defaultSchema}.fn_phien_tai_len_het_han(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_phien_tai_len_het_han(int) TO svc_${flyway:defaultSchema};

-- Hàm SECURITY DEFINER chạy với quyền mig_evidence, mà FORCE ROW LEVEL SECURITY không có chính sách cho vai trò
-- này thì không thấy dòng nào; chính sách chỉ đọc phiên đang mở cho chủ sở hữu để hàm dọn thấy mọi tenant.
CREATE POLICY rls_upload_session__don_phien ON ${flyway:defaultSchema}.upload_session
    AS PERMISSIVE FOR SELECT TO mig_${flyway:defaultSchema}
    USING (status = 'MO');
