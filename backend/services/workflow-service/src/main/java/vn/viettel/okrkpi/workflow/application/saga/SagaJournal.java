package vn.viettel.okrkpi.workflow.application.saga;

import vn.viettel.okrkpi.workflow.domain.saga.SagaInstance;

/**
 * Ghi vết bước, lần thử và lệnh điều khiển của saga vào sổ cái qua outbox trong cùng giao dịch
 * (BR-08-10).
 */
public interface SagaJournal {

  /** Hành động được ghi. */
  enum Action {
    KHOI_DONG,
    BAT_DAU_BUOC,
    BUOC_XONG,
    CHO_TRA_LOI,
    NHAN_TRA_LOI,
    BUOC_LOI,
    QUA_HAN_TRA_LOI,
    BAT_DAU_BU_TRU,
    BU_TRU_XONG,
    BO_QUA_BU_TRU,
    TAM_DUNG,
    TIEP_TUC,
    THU_LAI,
    YEU_CAU_BU_TRU,
    HOAN_TAT,
    DA_BU_TRU,
    CHO_XU_LY
  }

  /**
   * @param stepNo bước liên quan, {@code null} với hành động của cả saga
   * @param success kết quả của hành động
   */
  void record(SagaInstance saga, Action action, Integer stepNo, boolean success);
}
