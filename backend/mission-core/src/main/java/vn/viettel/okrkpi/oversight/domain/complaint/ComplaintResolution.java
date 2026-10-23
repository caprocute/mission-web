package vn.viettel.okrkpi.oversight.domain.complaint;

/**
 * Kết quả xử lý ghi ở kết luận (SCR-1202 trường 6). Giữ nguyên thành {@code outcome = GIU_NGUYEN},
 * hai mức chấp nhận thành {@code DIEU_CHINH} (hợp đồng {@code ComplaintResolution}).
 */
public enum ComplaintResolution {
  GIU_NGUYEN,
  CHAP_NHAN_MOT_PHAN,
  CHAP_NHAN;

  /** Có điều chỉnh kết quả. */
  public boolean adjusts() {
    return this != GIU_NGUYEN;
  }

  /** Giá trị {@code outcome} của khiếu nại và bản ghi kết luận. */
  public String outcome() {
    return adjusts() ? "DIEU_CHINH" : "GIU_NGUYEN";
  }
}
