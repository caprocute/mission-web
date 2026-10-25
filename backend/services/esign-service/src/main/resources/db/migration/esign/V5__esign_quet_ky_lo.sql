-- =============================================================================
-- esign V5 · DV-10-06 · Mở cho hàm quét ký lô đọc tài liệu chờ ký
-- fn_muc_ky_lo_cho, fn_dem_muc_ky_lo_cho (V4) là SECURITY DEFINER, chủ sở hữu chịu FORCE RLS như
-- fn_tai_lieu_luu_tru (V3): thiếu chính sách đọc sign_item cho chủ sở hữu thì hàm quét luôn rỗng, worker ký lô
-- không ký tài liệu nào và quyết định xếp loại hàng loạt dừng ở chờ ký (QA-06 HT-27). Chỉ mở đúng việc đọc dòng
-- chưa ký; sign_request đã có rls_sign_request__quet_cho_xac_nhan (V1).
-- =============================================================================
CREATE POLICY rls_sign_item__quet_ky_lo ON esign.sign_item AS PERMISSIVE FOR SELECT TO CURRENT_USER
  USING (item_status = 'TAO');
