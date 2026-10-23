-- DV-08-05 · OCR minh chứng qua ai-gateway (TL-01 "Kiến trúc cổng mô hình": OCR bất đồng bộ, văn bản lưu trong
-- schema evidence; QT-07 SCR-0702, BR-07-09, MSG-07-06).
--
-- Bổ sung (suy luận), từ điển TL-03 chưa có bảng hay cột cho văn bản OCR (wiki trang 14, mục DV-08-05):
--   * Trạng thái đọc chữ theo phiên bản: ocr_status NULL khi không cần đọc (kiểu tệp khác, chưa quét sạch hoặc C3),
--     CHO_DOC khi chờ hoặc đang đọc, DA_DOC, KHONG_DOC_DUOC (ảnh không đủ nét, MSG-07-06), LOI (hết lượt thử).
--     Tệp C3 không bao giờ xếp việc.
--   * Bảng ocr_text giữ văn bản trích theo phiên bản, mã hoá phong bì (văn bản minh chứng coi là C2).
--   * Hàng đợi như quét (V3) và xem trước (V4): worker lấy việc qua fn_ban_cho_ocr, nhận việc bằng UPDATE có điều kiện.

ALTER TABLE ${flyway:defaultSchema}.object_version
    ADD COLUMN ocr_status varchar(16),
    ADD COLUMN ocr_attempts int NOT NULL DEFAULT 0,
    ADD COLUMN ocr_next_at timestamptz,
    ADD CONSTRAINT ck_object_version__ocr_status
        CHECK (ocr_status IS NULL OR ocr_status IN ('CHO_DOC', 'DA_DOC', 'KHONG_DOC_DUOC', 'LOI')),
    ADD CONSTRAINT ck_object_version__ocr_attempts CHECK (ocr_attempts >= 0);

COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.ocr_status IS '{"cls":"C1","desc":"Trạng thái đọc chữ (OCR)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.ocr_attempts IS '{"cls":"C1","desc":"Số lần đã nhận việc đọc chữ"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.object_version.ocr_next_at IS '{"cls":"C1","desc":"Mốc sớm nhất được nhận việc đọc chữ (hạn thuê hoặc thử lại)"}';

CREATE INDEX ix_object_version__cho_ocr ON ${flyway:defaultSchema}.object_version (ocr_next_at)
    WHERE ocr_status = 'CHO_DOC';

-- ---------------------------------------------------------------- ocr_text
CREATE TABLE ${flyway:defaultSchema}.ocr_text (
    object_version_id uuid NOT NULL,
    tenant_id uuid NOT NULL DEFAULT nullif(current_setting('app.tenant_id', true), '')::uuid,
    text_enc bytea NOT NULL,
    dek_id uuid NOT NULL,
    model_id varchar(64) NOT NULL,
    page_count int NOT NULL,
    processed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_ocr_text PRIMARY KEY (object_version_id),
    CONSTRAINT uq_ocr_text__tenant_id UNIQUE (tenant_id, object_version_id),
    CONSTRAINT fk_ocr_text__object_version FOREIGN KEY (tenant_id, object_version_id)
        REFERENCES ${flyway:defaultSchema}.object_version (tenant_id, id),
    CONSTRAINT ck_ocr_text__page_count CHECK (page_count >= 0)
);

COMMENT ON TABLE ${flyway:defaultSchema}.ocr_text IS 'Văn bản đọc được từ tệp minh chứng (OCR qua ai-gateway), theo phiên bản';
COMMENT ON COLUMN ${flyway:defaultSchema}.ocr_text.object_version_id IS '{"cls":"C1","desc":"Phiên bản đối tượng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ocr_text.tenant_id IS '{"cls":"C1"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ocr_text.text_enc IS '{"cls":"C2","desc":"Văn bản đọc được (mã hoá phong bì)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ocr_text.dek_id IS '{"cls":"C1","desc":"Khoá dữ liệu (keyring.dek)"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ocr_text.model_id IS '{"cls":"C1","desc":"Mô hình OCR đã dùng"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ocr_text.page_count IS '{"cls":"C1","desc":"Số trang đọc được"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ocr_text.processed_at IS '{"cls":"C1","desc":"Thời điểm ai-gateway đọc xong"}';
COMMENT ON COLUMN ${flyway:defaultSchema}.ocr_text.created_at IS '{"cls":"C1"}';

SELECT ${flyway:defaultSchema}.fn_enable_tenant_rls('${flyway:defaultSchema}.ocr_text');
REVOKE DELETE, TRUNCATE ON ${flyway:defaultSchema}.ocr_text FROM svc_${flyway:defaultSchema};

CREATE FUNCTION ${flyway:defaultSchema}.fn_ban_cho_ocr(p_limit int)
  RETURNS TABLE (id uuid, tenant_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = ${flyway:defaultSchema}, pg_temp
AS $$
  SELECT v.id, v.tenant_id FROM ${flyway:defaultSchema}.object_version v
   WHERE v.ocr_status = 'CHO_DOC' AND v.ocr_next_at <= now()
   ORDER BY v.ocr_next_at
   LIMIT least(greatest(p_limit, 1), 500)
$$;
REVOKE ALL ON FUNCTION ${flyway:defaultSchema}.fn_ban_cho_ocr(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ${flyway:defaultSchema}.fn_ban_cho_ocr(int) TO svc_${flyway:defaultSchema};

-- Như V3, V4: chính sách đọc riêng cho hàm SECURITY DEFINER dưới FORCE RLS.
CREATE POLICY rls_object_version__cho_ocr ON ${flyway:defaultSchema}.object_version
    AS PERMISSIVE FOR SELECT TO mig_${flyway:defaultSchema}
    USING (ocr_status = 'CHO_DOC');

-- Số việc chờ của mọi tenant gộp vào chỉ số co giãn worker (quét, xem trước, đọc chữ dùng chung hàng đợi KEDA).
CREATE OR REPLACE FUNCTION ${flyway:defaultSchema}.fn_dem_cho_quet()
  RETURNS bigint
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = ${flyway:defaultSchema}, pg_temp
AS $$
  SELECT count(*) FROM ${flyway:defaultSchema}.object_version v
   WHERE v.scan_status = 'CHO_QUET' OR v.preview_status = 'DANG_TAO' OR v.ocr_status = 'CHO_DOC'
$$;
