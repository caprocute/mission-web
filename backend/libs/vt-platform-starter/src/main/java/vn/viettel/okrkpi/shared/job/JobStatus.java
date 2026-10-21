package vn.viettel.okrkpi.shared.job;

/**
 * Trạng thái tác vụ dài ({@code common.yaml} lược đồ {@code JobStatus}; BS-02 HD-06 giữ bộ giá trị
 * của WEB-007).
 */
public enum JobStatus {
  CHO,
  DANG_CHAY,
  HOAN_TAT,
  THAT_BAI,
  DA_HUY;

  /** Đúng khi tác vụ đã kết thúc (không còn chạy, không huỷ được nữa). */
  public boolean isTerminal() {
    return this == HOAN_TAT || this == THAT_BAI || this == DA_HUY;
  }
}
