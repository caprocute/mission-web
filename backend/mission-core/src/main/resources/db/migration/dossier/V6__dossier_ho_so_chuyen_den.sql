-- DV-13-10 · Phần hồ sơ nhận từ cell khác khi cán bộ được điều động liên cell (TL-03 "Đồng bộ giữa các cell",
-- luồng thứ ba: "cell nhận ... nhập vào SVC-04 và MOD-06 của tenant đích").
--
-- Bảng bổ sung (suy luận, wiki trang 14 mục DV-13-10): TL-03 chưa có DDL cho phía nhập của MOD-06. Mỗi dòng là
-- một phần của gói đã được SVC-13 kiểm chữ ký, giải mã và đối chiếu băm: kết quả xếp loại các kỳ, siêu dữ liệu
-- hồ sơ đã ký, kết luận kiểm điểm. Nội dung giữ nguyên tài liệu JSON của cell nguồn, mã hoá phong bì C2 bằng DEK
-- mục đích GOP_Y_KIEM_DIEM (AAD gồm tenant, tên cột đủ và mã dòng). Không theo kỳ vì kỳ của tenant nguồn không
-- có nghĩa ở tenant đích, nên khoá chính là (tenant_id, id). Người được chuyển ghi bằng mã ở tenant nguồn;
-- gắn với người ở tenant đích là việc của SVC-04. Chỉ thêm, không sửa, không xoá (truy vết).
CREATE TABLE ${flyway:defaultSchema}.transferred_record (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    row_version int NOT NULL DEFAULT 0,
    package_id uuid NOT NULL,
    part_name varchar(64) NOT NULL,
    part_kind varchar(32) NOT NULL,
    source_tenant_code varchar(64) NOT NULL,
    source_person_id uuid NOT NULL,
    media_type varchar(100) NOT NULL,
    content_sha256 bytea NOT NULL,
    content_enc bytea NOT NULL,
    dek_id uuid NOT NULL,
    CONSTRAINT pk_transferred_record PRIMARY KEY (tenant_id, id),
    CONSTRAINT uq_transferred_record__part UNIQUE (tenant_id, package_id, part_name),
    CONSTRAINT ck_transferred_record__content_sha256 CHECK (octet_length(content_sha256) = 32)
);
CREATE INDEX ix_transferred_record__person ON ${flyway:defaultSchema}.transferred_record (tenant_id, source_person_id);

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.transferred_record');
REVOKE UPDATE, DELETE, TRUNCATE ON ${flyway:defaultSchema}.transferred_record FROM svc_${flyway:defaultSchema};

COMMENT ON TABLE ${flyway:defaultSchema}.transferred_record IS 'Phần hồ sơ nhận từ cell khác khi điều động liên cell (DV-13-10)';
COMMENT ON COLUMN ${flyway:defaultSchema}.transferred_record.id IS '{"cls":"C1","desc":"Mã dòng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transferred_record.tenant_id IS '{"cls":"C1","desc":"Tenant đích"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transferred_record.created_at IS '{"cls":"C1","desc":"Thời điểm nhập"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transferred_record.created_by IS '{"cls":"C1","desc":"Chủ thể nhập"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transferred_record.updated_at IS '{"cls":"C1","desc":"Cập nhật lần cuối"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transferred_record.updated_by IS '{"cls":"C1","desc":"Người cập nhật"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transferred_record.row_version IS '{"cls":"C1","desc":"Phiên bản dòng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transferred_record.package_id IS '{"cls":"C1","desc":"Mã gói chuyển do cell nguồn cấp"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transferred_record.part_name IS '{"cls":"C1","desc":"Tên phần trong gói"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transferred_record.part_kind IS '{"cls":"C1","desc":"Loại phần: kết quả xếp loại, hồ sơ đã ký, kết luận kiểm điểm"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transferred_record.source_tenant_code IS '{"cls":"C1","desc":"Mã tenant nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transferred_record.source_person_id IS '{"cls":"C1","desc":"Mã cán bộ ở tenant nguồn"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transferred_record.media_type IS '{"cls":"C1","desc":"Kiểu nội dung"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transferred_record.content_sha256 IS '{"cls":"C1","desc":"SHA-256 nội dung rõ, khớp danh mục gói"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transferred_record.content_enc IS '{"cls":"C2","desc":"Nội dung phần, mã hoá phong bì"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.transferred_record.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu đã dùng"}';
