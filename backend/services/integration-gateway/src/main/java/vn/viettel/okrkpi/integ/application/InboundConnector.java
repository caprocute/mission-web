package vn.viettel.okrkpi.integ.application;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.InboundMessage;

/**
 * SPI một bộ kết nối kéo theo lịch (DV-13-03..08): adapter đọc một trang thông điệp sau con trỏ,
 * translator dịch sang mô hình chuẩn. Khung route lo lịch, circuit breaker, bulkhead, chống trùng,
 * thử lại, thư chết và con trỏ.
 */
public interface InboundConnector {

  /** Một trang thông điệp; {@code nextCursor} lưu khi cả trang đã xử lý (kể cả vào thư chết). */
  record Page(List<InboundMessage> items, String nextCursor, Instant sourceTime) {
    public Page {
      items = List.copyOf(items == null ? List.of() : items);
    }
  }

  /** Mã kết nối ({@code integ.connector.connector_code}). */
  String code();

  /** Tên luồng đồng bộ ({@code integ.sync_cursor.stream}). */
  String stream();

  /** Con trỏ khi luồng chưa từng chạy. */
  default String initialCursor() {
    return "0";
  }

  /** Chu kỳ kéo. */
  default Duration period() {
    return Duration.ofSeconds(30);
  }

  /** Số lời gọi đồng thời tối đa tới đối tác (bulkhead riêng của kết nối). */
  default int maxConcurrentCalls() {
    return 4;
  }

  /** Adapter: gọi đối tác theo giao thức của nó. */
  Page fetch(Connector connector, String cursor) throws IOException;

  Translator translator();
}
