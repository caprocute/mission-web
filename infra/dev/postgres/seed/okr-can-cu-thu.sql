-- Thư viện căn cứ thử (okr.basis, MOD-01, BR-02-02) của tenant thử d0000000-0000-4000-8000-000000000001 cho dev:
-- bản này chưa có API ghi căn cứ (chỉ listOkrBases), nên khung soạn mục tiêu (SCR-0202) trên hệ thống thật không có
-- lựa chọn "Căn cứ" bắt buộc nếu không nạp sẵn. Chỉ dữ liệu giả lập; chạy lại không sinh bản ghi trùng. Nạp bởi
-- scripts/seed-org.sh khi schema okr đã migrate.
INSERT INTO okr.basis (id, tenant_id, created_by, updated_by, basis_kind, doc_number, title, issued_by_unit_id,
                       issued_on, valid_from)
VALUES ('d0000000-0000-4000-8000-0000000b0001', 'd0000000-0000-4000-8000-000000000001',
        '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000', 'NGHI_QUYET',
        '01-NQ/TU', 'Nghị quyết thử về nâng cao chất lượng phục vụ người dân', 'd0000000-0000-4000-8000-000000000100',
        '2026-01-05', '2026-01-05'),
       ('d0000000-0000-4000-8000-0000000b0002', 'd0000000-0000-4000-8000-000000000001',
        '00000000-0000-7000-8000-000000000000', '00000000-0000-7000-8000-000000000000', 'KE_HOACH',
        '12/KH-UBND', 'Kế hoạch thử chuyển đổi số nội bộ năm 2026', 'd0000000-0000-4000-8000-000000000100',
        '2026-01-15', '2026-01-15')
ON CONFLICT (id) DO NOTHING;
