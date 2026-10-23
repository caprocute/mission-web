-- =============================================================================
-- DV-10-06 · SVC-10 · ADR-31 · Ký lô: một lần xác nhận cho cả lô, worker ký từng tài liệu.
--
-- Cột bổ sung (suy luận, TL-03 chưa có, ghi ở wiki trang 14):
--   sign_request.batch_authorization / batch_authorized_at: cách xác nhận một lần của lô (SAD của
--     nhà cung cấp ký từ xa, hoặc step-up của người quyết định khi ký bằng HSM) và thời điểm xác nhận.
--   sign_item.item_status, attempts, next_attempt_at, last_error: tiến độ, thử lại có giới hạn và lỗi
--     ở mức từng tài liệu, để một mục lỗi không làm hỏng cả lô.
-- Khoá idempotent (sign_request_id, document_sha256): thử lại không ký trùng một tài liệu.
-- =============================================================================

ALTER TABLE esign.sign_request
  ADD COLUMN batch_authorization varchar(8),
  ADD COLUMN batch_authorized_at timestamptz,
  ADD CONSTRAINT ck_sign_request__batch_authorization CHECK (batch_authorization IN ('SAD', 'STEP_UP'));
COMMENT ON COLUMN esign.sign_request.batch_authorization IS '{"cls": "C1", "pii": false, "desc": "Cách xác nhận một lần của lô (SAD hoặc STEP_UP)"}';
COMMENT ON COLUMN esign.sign_request.batch_authorized_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm người ký xác nhận cả lô"}';

ALTER TABLE esign.sign_item
  ADD COLUMN item_status varchar(12) NOT NULL DEFAULT 'TAO',
  ADD COLUMN attempts integer NOT NULL DEFAULT 0,
  ADD COLUMN next_attempt_at timestamptz,
  ADD COLUMN last_error varchar(512),
  ADD CONSTRAINT ck_sign_item__item_status CHECK (item_status IN ('TAO', 'DA_KY', 'LOI'));
COMMENT ON COLUMN esign.sign_item.item_status IS '{"cls": "C1", "pii": false, "desc": "Trạng thái ký của tài liệu trong lô"}';
COMMENT ON COLUMN esign.sign_item.attempts IS '{"cls": "C1", "pii": false, "desc": "Số lần đã thử ký"}';
COMMENT ON COLUMN esign.sign_item.next_attempt_at IS '{"cls": "C1", "pii": false, "desc": "Thời điểm được thử lại"}';
COMMENT ON COLUMN esign.sign_item.last_error IS '{"cls": "C1", "pii": false, "desc": "Lỗi gần nhất khi ký tài liệu"}';

CREATE UNIQUE INDEX uq_sign_item__request_sha256 ON esign.sign_item (sign_request_id, document_sha256);
-- Worker ký lô quét tài liệu chưa ký tới hạn thử.
CREATE INDEX ix_sign_item__cho_ky ON esign.sign_item (next_attempt_at) WHERE item_status = 'TAO';

-- -----------------------------------------------------------------------------
-- Worker ký lô quét mọi tenant như fn_yeu_cau_cho_xac_nhan, rồi ký từng tài liệu trong ngữ cảnh
-- tenant của nó với RLS đầy đủ (suy luận).
-- -----------------------------------------------------------------------------
CREATE FUNCTION esign.fn_muc_ky_lo_cho(p_limit int)
  RETURNS TABLE (id uuid, sign_request_id uuid, tenant_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = esign, pg_temp
AS $$
  SELECT i.id, i.sign_request_id, i.tenant_id
    FROM esign.sign_item i
    JOIN esign.sign_request r ON r.id = i.sign_request_id
   WHERE i.item_status = 'TAO'
     AND (i.next_attempt_at IS NULL OR i.next_attempt_at <= now())
     AND r.status = 'CHO_XAC_NHAN'
     AND r.batch_authorized_at IS NOT NULL
   ORDER BY i.next_attempt_at NULLS FIRST, i.id
   LIMIT least(greatest(p_limit, 1), 5000)
$$;
REVOKE ALL ON FUNCTION esign.fn_muc_ky_lo_cho(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION esign.fn_muc_ky_lo_cho(int) TO svc_esign;

-- Độ dài hàng đợi ký lô (mọi tenant) cho metric co giãn worker.
CREATE FUNCTION esign.fn_dem_muc_ky_lo_cho()
  RETURNS bigint
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = esign, pg_temp
AS $$
  SELECT count(*)
    FROM esign.sign_item i
    JOIN esign.sign_request r ON r.id = i.sign_request_id
   WHERE i.item_status = 'TAO' AND r.status = 'CHO_XAC_NHAN' AND r.batch_authorized_at IS NOT NULL
$$;
REVOKE ALL ON FUNCTION esign.fn_dem_muc_ky_lo_cho() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION esign.fn_dem_muc_ky_lo_cho() TO svc_esign;
