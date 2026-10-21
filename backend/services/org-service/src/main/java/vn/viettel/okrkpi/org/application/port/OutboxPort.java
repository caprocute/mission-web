package vn.viettel.okrkpi.org.application.port;

import vn.viettel.okrkpi.org.application.OutboxEvent;

/** Ghi sự kiện vào {@code org.outbox_event} trong giao dịch đang mở (baseline mục 8). */
public interface OutboxPort {

  void append(OutboxEvent event);
}
