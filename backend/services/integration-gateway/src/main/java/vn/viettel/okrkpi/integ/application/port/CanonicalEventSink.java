package vn.viettel.okrkpi.integ.application.port;

import vn.viettel.okrkpi.integ.application.CanonicalEvent;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Phát sự kiện mô hình chuẩn lên Kafka nội bộ qua outbox trong cùng giao dịch. */
public interface CanonicalEventSink {

  /** Trả mã sự kiện (UUIDv7). */
  java.util.UUID publish(TenantId tenant, CanonicalEvent event);
}
