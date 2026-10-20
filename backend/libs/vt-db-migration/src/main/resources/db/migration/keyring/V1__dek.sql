-- NEN-032 · ADR-06, ADR-30 · TL-03 "Schema keyring và bảng dek" (L910-927), chương 9.
-- Khoá dữ liệu (DEK) đã bọc bằng KEK của OpenBao Transit, mỗi tổ hợp tenant, mục đích, tháng một
-- khoá. Không bao giờ chứa DEK dạng rõ. Mọi cột dek_id của schema nghiệp vụ trỏ tới keyring.dek(id)
-- nên keyring PHẢI được migrate trước mọi schema có cột _enc trong cùng CSDL.
-- Quyền (NEN-031): svc_ của mọi schema chỉ SELECT, INSERT; chỉ svc_keyring (job xoay vòng, xoá
-- bằng mật mã) đổi trạng thái. Chính sách tenant áp cho PUBLIC vì nhiều svc_ cùng dùng bảng.
CREATE TABLE keyring.dek (
    id           uuid         NOT NULL,
    tenant_id    uuid         NOT NULL,
    purpose      varchar(24)  NOT NULL,
    valid_month  date         NOT NULL,
    wrapped_dek  bytea        NOT NULL,
    kek_ref      varchar(200) NOT NULL,
    alg_version  smallint     NOT NULL DEFAULT 1,
    status       varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   timestamptz  NOT NULL DEFAULT now(),
    destroyed_at timestamptz,
    CONSTRAINT pk_dek PRIMARY KEY (id),
    CONSTRAINT uq_dek__tenant_id_purpose_valid_month UNIQUE (tenant_id, purpose, valid_month),
    CONSTRAINT ck_dek__purpose CHECK (purpose IN ('NHAN_XET_DANH_GIA', 'KHIEU_NAI', 'GOP_Y_KIEM_DIEM',
                                                  'DINH_DANH_CA_NHAN', 'TEP_MINH_CHUNG', 'BLIND_INDEX')),
    CONSTRAINT ck_dek__status CHECK (status IN ('ACTIVE', 'DECRYPT_ONLY', 'RETIRED', 'DESTROYED'))
);

COMMENT ON COLUMN keyring.dek.id IS '{"cls":"C1","desc":"dek_id mà các cột _enc tham chiếu"}';
COMMENT ON COLUMN keyring.dek.tenant_id IS '{"cls":"C1","desc":"Tenant sở hữu khoá"}';
COMMENT ON COLUMN keyring.dek.purpose IS '{"cls":"C1","desc":"Mục đích dùng khoá"}';
COMMENT ON COLUMN keyring.dek.valid_month IS '{"cls":"C1","desc":"Tháng khoá được dùng để mã hoá mới"}';
COMMENT ON COLUMN keyring.dek.wrapped_dek IS '{"cls":"C1","desc":"DEK đã bọc bằng KEK (định dạng vault:v<n>:...)"}';
COMMENT ON COLUMN keyring.dek.kek_ref IS '{"cls":"C1","desc":"Tên và phiên bản KEK"}';
COMMENT ON COLUMN keyring.dek.alg_version IS '{"cls":"C1","desc":"Phiên bản thuật toán, trùng byte phiên bản trong bản mã","type_exception":"TL-03 L921 chốt smallint"}';
COMMENT ON COLUMN keyring.dek.status IS '{"cls":"C1","desc":"Vòng đời khoá"}';
COMMENT ON COLUMN keyring.dek.created_at IS '{"cls":"C1","desc":"Thời điểm tạo"}';
COMMENT ON COLUMN keyring.dek.destroyed_at IS '{"cls":"C1","desc":"Thời điểm huỷ (xoá bằng mật mã)"}';

ALTER TABLE keyring.dek ENABLE ROW LEVEL SECURITY;
ALTER TABLE keyring.dek FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_dek__tenant ON keyring.dek AS PERMISSIVE FOR ALL
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
