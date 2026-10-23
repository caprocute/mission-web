-- =============================================================================
-- DV-10-03 · SVC-10 · TL-01 "Ký số tại mốc pháp lý" (gia hạn dấu thời gian lưu trữ PAdES-LTA).
--
-- Job gia hạn chạy hằng năm cho mọi tenant: hàm dưới chỉ liệt kê mã, tenant và khoá đối tượng của tài
-- liệu PAdES-LTA đã ký (không trả chữ ký hay băm); job đọc tệp, kiểm hạn chứng thư TSA và ghi lại khoá
-- đối tượng mới trong ngữ cảnh tenant của từng dòng với RLS đầy đủ (suy luận, wiki trang 14).
-- =============================================================================

CREATE FUNCTION esign.fn_tai_lieu_luu_tru(p_after uuid, p_limit int)
  RETURNS TABLE (id uuid, tenant_id uuid, signed_object_key varchar)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = esign, pg_temp
AS $$
  SELECT i.id, i.tenant_id, i.signed_object_key FROM esign.sign_item i
   WHERE i.signature_format = 'PADES_LTA'
     AND i.signed_object_key IS NOT NULL
     AND (p_after IS NULL OR i.id > p_after)
   ORDER BY i.id
   LIMIT least(greatest(p_limit, 1), 500)
$$;
REVOKE ALL ON FUNCTION esign.fn_tai_lieu_luu_tru(uuid, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION esign.fn_tai_lieu_luu_tru(uuid, int) TO svc_esign;

-- Chủ sở hữu hàm chịu FORCE RLS: chỉ mở đúng việc đọc dòng PAdES-LTA đã ký cho hàm quét.
CREATE POLICY rls_sign_item__quet_luu_tru ON esign.sign_item AS PERMISSIVE FOR SELECT TO CURRENT_USER
  USING (signature_format = 'PADES_LTA' AND signed_object_key IS NOT NULL);
