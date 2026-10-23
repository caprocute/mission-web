package vn.viettel.okrkpi.platform.reconcile;

/** Trạng thái lần đối soát theo CHECK của {@code org_replica.reconcile_run} (TL-03). */
public enum RunStatus {
  /** Đang chạy hoặc chờ đủ thời gian lắng trước khi so. */
  DANG_CHAY,
  /** Hai phía khớp. */
  KHOP,
  /** Có lệch và đã xử lý tự động (phát lại), hoặc khớp lại sau lần lệch trước. */
  DA_SUA,
  /** Có lệch chưa xử lý được; cảnh báo và chờ người vận hành theo runbook. */
  LOI
}
