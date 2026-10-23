package vn.viettel.okrkpi.workflow.domain.monitor;

import java.util.Optional;

/**
 * Ràng buộc chung của thao tác giám sát (BS-01 SCR-1506): lý do bắt buộc, tối đa 1.000 ký tự như
 * {@code WorkflowTaskReassign.reason} và {@code WorkflowTaskExtend.reason} của hợp đồng.
 */
public final class MonitorRules {

  /** Độ dài tối đa của lý do theo hợp đồng. */
  public static final int MAX_REASON = 1000;

  private MonitorRules() {}

  /** Lý do đã bỏ khoảng trắng hai đầu; rỗng khi thiếu hoặc quá dài. */
  public static Optional<String> reason(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String value = raw.strip();
    if (value.isEmpty() || value.length() > MAX_REASON) {
      return Optional.empty();
    }
    return Optional.of(value);
  }
}
