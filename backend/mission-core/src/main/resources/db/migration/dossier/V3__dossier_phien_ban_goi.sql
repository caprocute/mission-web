-- CORE-06-07 · Phiên bản gói hồ sơ khi mở lại kỳ (QT-11 "Trạng thái đối tượng" nhánh EVT-22, BR-11-10).
--
-- Gói DA_NOP chỉ trở lại DANG_LAP qua QT-13: gói cũ giữ nguyên, gói mới cùng đơn vị, kỳ, loại và cùng mã
-- với số phiên bản tăng (version_no), tham chiếu gói cũ qua (code, version_no - 1) (suy luận, wiki trang 14).
-- Hai ràng buộc duy nhất của V1 không gồm version_no nên không chèn được phiên bản thứ hai; thêm version_no
-- vào cả hai. Trigger fn_freeze_submitted_package không đổi.
ALTER TABLE ${flyway:defaultSchema}.dossier_package DROP CONSTRAINT uq_dossier_package__unit;
ALTER TABLE ${flyway:defaultSchema}.dossier_package DROP CONSTRAINT uq_dossier_package__code;
ALTER TABLE ${flyway:defaultSchema}.dossier_package
    ADD CONSTRAINT uq_dossier_package__unit UNIQUE NULLS NOT DISTINCT (period_bucket, tenant_id, org_unit_id,
        package_kind, person_id, version_no),
    ADD CONSTRAINT uq_dossier_package__code UNIQUE (period_bucket, tenant_id, code, version_no);
