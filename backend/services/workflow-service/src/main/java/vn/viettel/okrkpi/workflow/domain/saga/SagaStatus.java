package vn.viettel.okrkpi.workflow.domain.saga;

/** Trạng thái saga (TL-03 {@code flowable.saga_instance.status}). */
public enum SagaStatus {
  DANG_CHAY,
  CHO_SU_KIEN,
  HOAN_TAT,
  DANG_BU_TRU,
  DA_BU_TRU,
  LOI_CAN_XU_LY;

  /** Saga đã kết thúc hẳn, không nhận thêm bước hay lệnh điều khiển nào. */
  public boolean isTerminal() {
    return this == HOAN_TAT || this == DA_BU_TRU;
  }
}
