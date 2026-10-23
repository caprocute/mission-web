package vn.viettel.okrkpi.esign.domain.sign;

/** Mốc pháp lý cần ký ({@code esign.sign_request.purpose}). */
public enum SignPurpose {
  TU_DANH_GIA,
  THAM_DINH,
  QUYET_DINH,
  BIEN_BAN,
  GOI_HO_SO,
  PHE_DUYET_MO_LAI,
  PHAT_HANH_POLICY,
  /** Quyết định giải quyết khiếu nại kết quả đánh giá (CORE-07-02). */
  KHIEU_NAI
}
