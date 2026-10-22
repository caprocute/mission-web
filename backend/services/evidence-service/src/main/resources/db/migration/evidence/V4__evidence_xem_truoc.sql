-- DV-08-06 · Ảnh xem trước từng trang (hợp đồng products.yaml getFilePreview; SCR-0703, SCR-0902, SCR-1603).
--
-- Bổ sung (suy luận), TL-03 chưa có (wiki trang 14 mục 50):
--   * Trạng thái dựng ảnh theo phiên bản: preview_status NULL khi chưa xếp việc (chưa quét sạch), DANG_TAO khi
--     chờ hoặc đang dựng, SAN_SANG, LOI; tệp C3 không bao giờ xếp việc (API trả KHONG_HO_TRO).
--   * preview_pages giữ kích thước từng trang [[rộng, cao], ...] để trả cửa sổ trang không cần đọc kho.
--   * Hàng đợi như quét (V3): worker lấy việc qua fn_ban_cho_xem_truoc, nhận việc bằng UPDATE có điều kiện.

ALTER TABLE ${flyway:defaultSchema}.object_version
    ADD COLUMN preview_status varchar(12),
    ADD COLUMN preview_pages jsonb,
    ADD COLUMN preview_attempts int NOT NULL DEFAULT 0,
    ADD COLUMN preview_next_at timestamptz,
    ADD CONSTRAINT ck_object_version__preview_status
        CHECK (preview_status IS NULL OR preview_status IN ('DANG_TAO', 'SAN_SANG', 'LOI')),
    ADD CONSTRAINT ck_object_version__preview_attempts CHECK (preview_attempts >= 0);

COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.preview_status IS '{"cls":"C1","desc":"Trạng thái ảnh xem trước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.preview_pages IS '{"cls":"C1","desc":"Kích thước ảnh từng trang [[rộng, cao], ...]"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.preview_attempts IS '{"cls":"C1","desc":"Số lần đã nhận việc dựng xem trước"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.preview_next_at IS '{"cls":"C1","desc":"Mốc sớm nhất được nhận việc dựng (hạn thuê hoặc thử lại)"}';

CREATE INDEX ix_object_version__cho_xem_truoc ON ${flyway:defaultSchema}.object_version (preview_next_at)
    WHERE preview_status = 'DANG_TAO';

CREATE FUNCTION ${flyway:defaultSchema}.fn_ban_cho_xem_truoc(p_limit int)
  RETURNS TABLE (id uuid, tenant_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = ${flyway:defaultSchema}, pg_temp
AS $$
  SELECT v.id, v.tenant_id FROM ${flyway:defaultSchema}.object_version v
   WHERE v.preview_status = 'DANG_TAO' AND v.preview_next_at <= now()
   ORDER BY v.preview_next_at
   LIMIT least(greatest(p_limit, 1), 500)
$$;
REVOKE ALL ON FUNCTION ${flyway:defaultSchema}.fn_ban_cho_xem_truoc(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_ban_cho_xem_truoc(int) TO svc_${flyway:defaultSchema};

-- Như V3: chính sách đọc riêng cho hàm SECURITY DEFINER dưới FORCE RLS.
CREATE POLICY rls_object_version__cho_xem_truoc ON ${flyway:defaultSchema}.object_version
    AS PERMISSIVE FOR SELECT TO mig_${flyway:defaultSchema}
    USING (preview_status = 'DANG_TAO');

-- Số bản chờ dựng của mọi tenant gộp vào chỉ số co giãn worker (dùng chung hàng đợi KEDA với quét).
CREATE OR REPLACE FUNCTION ${flyway:defaultSchema}.fn_dem_cho_quet()
  RETURNS bigint
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = ${flyway:defaultSchema}, pg_temp
AS $$
  SELECT count(*) FROM ${flyway:defaultSchema}.object_version v
   WHERE v.scan_status = 'CHO_QUET' OR v.preview_status = 'DANG_TAO'
$$;
