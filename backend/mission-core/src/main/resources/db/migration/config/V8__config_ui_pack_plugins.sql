-- CORE-08-15: manifest plugin chuyên ngành của UI Pack (QT-18, SCR-1801, BR-18-12, ADR-05 lớp 4,
-- ADR-16). TL-03 không có bảng hay cột riêng cho plugin; TL-01 và hợp đồng đặt manifest trong phần
-- plugins của gói (suy luận, wiki trang 14). Cột chốt {name, remoteEntry, integrity} của các plugin
-- đã kiểm chữ ký lúc phát hành, để gói dựng lại từ CSDL vẫn khớp băm đã ký.
ALTER TABLE ${flyway:defaultSchema}.ui_pack_version
    ADD COLUMN plugins_json jsonb NOT NULL DEFAULT '[]'::jsonb;
COMMENT ON COLUMN ${flyway:defaultSchema}.ui_pack_version.plugins_json IS '{"cls": "C0", "desc": "Manifest plugin chốt lúc phát hành (name, remoteEntry, integrity)"}';
