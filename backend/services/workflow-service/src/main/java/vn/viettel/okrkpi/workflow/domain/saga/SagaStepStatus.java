package vn.viettel.okrkpi.workflow.domain.saga;

/** Trạng thái một bước saga (TL-03 {@code flowable.saga_step.status}). */
public enum SagaStepStatus {
  CHO,
  DANG_CHAY,
  XONG,
  LOI,
  DA_BU_TRU
}
