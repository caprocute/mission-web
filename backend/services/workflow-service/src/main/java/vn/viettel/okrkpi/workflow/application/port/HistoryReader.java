package vn.viettel.okrkpi.workflow.application.port;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.ProcessHistory;

/** Cổng đọc lịch sử engine ({@code ACT_HI_*}, mức {@code audit}). */
public interface HistoryReader {

  /** Lịch sử tiến trình gần nhất của đối tượng nghiệp vụ trong tenant. */
  Optional<ProcessHistory> find(UUID tenantId, BusinessKey businessKey);
}
