-- DV-11-02, DV-11-03 (phần MOD-06) · Biểu mẫu nháp ngoài gói và dấu sinh lại sau khi mở lại kỳ (bổ sung, suy luận,
-- wiki trang 14).
--
-- form_instance.source_profile_ids: tập hồ sơ người dùng đã chọn khi sinh biểu mẫu nháp của đơn vị (createDossierDraftForms,
-- ví dụ "Xuất Mẫu 3" ở SCR-0904); dữ liệu biểu mẫu tính lại trên đúng tập này để băm khớp lúc SVC-11 hỏi dữ liệu.
-- Biểu mẫu chính thức trong gói để rỗng.
-- dossier_package.reopen_request_id: yêu cầu mở lại kỳ (EVT-22) dẫn tới phiên bản gói; SVC-11 đóng dấu "sinh lại sau
-- khi mở lại kỳ" vào tệp (BR-13-08). Chỉ ghi lúc lập phiên bản nên trigger đóng băng không đổi.
ALTER TABLE ${flyway:defaultSchema}.form_instance ADD COLUMN source_profile_ids uuid[];
ALTER TABLE ${flyway:defaultSchema}.dossier_package ADD COLUMN reopen_request_id uuid;

COMMENT ON COLUMN ${flyway:defaultSchema}.form_instance.source_profile_ids IS '{"cls":"C1","desc":"Hồ sơ đã chọn của biểu mẫu nháp đơn vị","type_exception":"Tập mã hồ sơ đọc nguyên khối cùng biểu mẫu, không truy vấn theo phần tử (suy luận)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.dossier_package.reopen_request_id IS '{"cls":"C1","desc":"Yêu cầu mở lại kỳ dẫn tới phiên bản gói"}';
