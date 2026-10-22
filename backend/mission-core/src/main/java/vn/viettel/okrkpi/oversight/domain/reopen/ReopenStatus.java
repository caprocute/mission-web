package vn.viettel.okrkpi.oversight.domain.reopen;

/**
 * Trạng thái {@code oversight.reopen_request.status}: bốn giá trị đầu của TL-02b, TL-03 thêm {@code
 * DA_THUC_HIEN} và {@code DA_DONG}.
 */
public enum ReopenStatus {
  CHO_DUYET_1,
  CHO_DUYET_2,
  DA_DUYET,
  TU_CHOI,
  DA_THUC_HIEN,
  DA_DONG;

  /** Người tạo còn huỷ được: trước khi đủ hai phê duyệt. */
  public boolean cancellable() {
    return this == CHO_DUYET_1 || this == CHO_DUYET_2;
  }
}
