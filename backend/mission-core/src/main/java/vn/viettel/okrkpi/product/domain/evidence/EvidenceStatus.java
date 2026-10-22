package vn.viettel.okrkpi.product.domain.evidence;

/**
 * Trạng thái chuẩn của {@code product.evidence_version.status} (baseline 9.1, TL-02a QT-07). Ba
 * trạng thái cuối {@code DA_XAC_NHAN}, {@code YEU_CAU_BO_SUNG}, {@code TU_CHOI} không rời được;
 * người nộp tạo phiên bản mới thay vì sửa phiên bản cũ (BR-07-01).
 */
public enum EvidenceStatus {
  NHAP,
  DA_NOP,
  DANG_NGHIEM_THU,
  DA_XAC_NHAN,
  YEU_CAU_BO_SUNG,
  TU_CHOI;

  public boolean isTerminal() {
    return this == DA_XAC_NHAN || this == YEU_CAU_BO_SUNG || this == TU_CHOI;
  }

  /**
   * Phiên bản đã nộp và chưa bị trả lại: {@code DA_NOP}, {@code DANG_NGHIEM_THU}, {@code
   * DA_XAC_NHAN}.
   */
  public boolean isSubmittedAndLive() {
    return this == DA_NOP || this == DANG_NGHIEM_THU || this == DA_XAC_NHAN;
  }

  /** Phiên bản bị trả lại: nhiệm vụ quay về {@code DANG_THUC_HIEN}. */
  public boolean isReturned() {
    return this == YEU_CAU_BO_SUNG || this == TU_CHOI;
  }
}
