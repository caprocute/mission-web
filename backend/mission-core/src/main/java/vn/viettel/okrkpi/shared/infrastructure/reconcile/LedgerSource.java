package vn.viettel.okrkpi.shared.infrastructure.reconcile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Nguồn dữ liệu của một module cho đối soát R2 (sổ cái ↔ bảng nguồn, DL-12). Module khai các topic
 * có giá trị pháp lý mình phát cùng lược đồ người ghi, và cách so tải trọng sự kiện với dòng hiện
 * hành.
 */
public interface LedgerSource {

  /** Một sự kiện của đối tượng đọc từ {@code outbox_event}. */
  record SourceEvent(
      UUID eventId,
      String topic,
      GenericRecord payload,
      long aggregateVersion,
      Instant createdAt) {}

  /** Schema chứa {@code outbox_event} và bảng nguồn. */
  String schema();

  JdbcTemplate jdbc();

  /** Bộ quản lý giao dịch đặt ngữ cảnh RLS của schema. */
  PlatformTransactionManager tm();

  /** Topic đối soát → lược đồ người ghi. */
  Map<String, Schema> topics();

  /**
   * So tải trọng các sự kiện (theo thứ tự ghi) với dòng hiện hành của đối tượng; trả tên các trường
   * lệch dạng {@code <topic>.<trường>}, rỗng khi khớp. Chỉ so trường không đổi sau khi phát, hoặc
   * mọi trường khi phiên bản dòng hiện hành bằng {@code aggregateVersion} của sự kiện. Gọi trong
   * giao dịch có ngữ cảnh tenant.
   */
  List<String> verify(UUID aggregateId, List<SourceEvent> events);
}
