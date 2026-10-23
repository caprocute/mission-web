-- Bổ sung mục đích ký KHIEU_NAI cho quyết định giải quyết khiếu nại (CORE-07-02); chỉ thêm giá trị.
ALTER TABLE ${flyway:defaultSchema}.sign_request DROP CONSTRAINT ck_sign_request__purpose;
ALTER TABLE ${flyway:defaultSchema}.sign_request ADD CONSTRAINT ck_sign_request__purpose CHECK (purpose IN (
    'TU_DANH_GIA', 'THAM_DINH', 'QUYET_DINH', 'BIEN_BAN', 'GOI_HO_SO', 'PHE_DUYET_MO_LAI', 'PHAT_HANH_POLICY',
    'KHIEU_NAI'));
