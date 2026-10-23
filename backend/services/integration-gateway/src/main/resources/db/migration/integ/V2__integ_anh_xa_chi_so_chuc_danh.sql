-- DV-13-08 · SVC-13, QT-03 (BR-03-04): mở rộng loại thực thể của integ.mapping_xref.
--   * CHI_SO: chỉ số chuyên môn ở hệ nguồn (HRM, ERP, HIS, quản lý trường học) ↔ KR nội bộ
--     (okr.key_result.id); mã ngoài của chỉ số không nằm trong schema okr (task DV-13-08 mục 3).
--   * CHUC_DANH: mã chức danh ở HRM ↔ org.job_title.id, để quy đổi phân công trước khi chuyển lô
--     nhân sự cho org-service (org.assignment.job_title_id bắt buộc).
-- TL-03 chỉ liệt kê DON_VI, NHAN_SU, VAN_BAN; hai giá trị mới là suy luận, ghi ở wiki trang 14.
ALTER TABLE ${flyway:defaultSchema}.mapping_xref DROP CONSTRAINT ck_mapping_xref__entity_type;
ALTER TABLE ${flyway:defaultSchema}.mapping_xref ADD CONSTRAINT ck_mapping_xref__entity_type
    CHECK (entity_type IN ('DON_VI', 'NHAN_SU', 'VAN_BAN', 'CHI_SO', 'CHUC_DANH'));
COMMENT ON COLUMN ${flyway:defaultSchema}.mapping_xref.entity_type IS '{"cls":"C1","desc":"Loại (DON_VI, NHAN_SU, VAN_BAN, CHI_SO, CHUC_DANH)"}';
-- Tra theo mã nội bộ trên mọi kết nối của tenant (số liệu nguồn của KR không biết trước kết nối).
CREATE INDEX ix_mapping_xref__internal_any ON ${flyway:defaultSchema}.mapping_xref (tenant_id, entity_type, internal_id);
-- Sửa lỗi DV-13-01: trạng thái DA_CHUYEN_DOI có 13 ký tự nhưng cột status chỉ varchar(12), nên
-- thông điệp chuyển đổi xong mà không phát sự kiện (nhân sự HRM chuyển thẳng org-service) không
-- ghi được trạng thái và bị xử lý lại.
ALTER TABLE ${flyway:defaultSchema}.inbox_message ALTER COLUMN status TYPE varchar(13);
