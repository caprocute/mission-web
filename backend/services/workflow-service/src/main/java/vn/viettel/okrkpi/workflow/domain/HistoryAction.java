package vn.viettel.okrkpi.workflow.domain;

/** Loại bước trong lịch sử tiến trình (hợp đồng {@code WorkflowHistoryAction}, suy luận). */
public enum HistoryAction {
  TAO_TIEN_TRINH,
  DUYET,
  TRA_LAI,
  CHUYEN_NGUOI_XU_LY,
  GIA_HAN,
  NHAC_HAN,
  KET_THUC;

  /** Bước tương ứng với quyết định khi hoàn thành user task. */
  public static HistoryAction of(Decision decision) {
    return decision == Decision.TRA_LAI ? TRA_LAI : DUYET;
  }
}
