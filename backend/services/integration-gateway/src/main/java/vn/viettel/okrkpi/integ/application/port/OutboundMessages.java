package vn.viettel.okrkpi.integ.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.integ.application.port.PayloadCipher.Sealed;
import vn.viettel.okrkpi.integ.domain.OutboundStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Thông điệp ra ({@code integ.outbound_message}, phân vùng theo ngày tạo). */
public interface OutboundMessages {

  /** Việc đến hạn của mọi tenant (chỉ mã), qua hàm {@code fn_thu_ra_den_han}. */
  record Due(UUID tenantId, LocalDate createdDay, UUID id) {}

  record Row(
      UUID id,
      LocalDate createdDay,
      UUID connectorId,
      UUID sourceEventId,
      String messageType,
      byte[] payloadEnc,
      OutboundStatus status,
      int attempts,
      String ackRef) {}

  /** Trạng thái theo dõi của một thông điệp ra (DV-13-05: bên gọi hỏi kết quả gửi). */
  record Tracked(
      UUID id,
      UUID connectorId,
      OutboundStatus status,
      int attempts,
      Instant nextAttemptAt,
      String ackRef,
      Instant updatedAt) {}

  boolean exists(TenantId tenant, UUID connectorId, UUID sourceEventId);

  /** Thông điệp ra mới nhất sinh từ {@code sourceEventId} trên mọi kết nối của tenant. */
  Optional<Tracked> bySource(TenantId tenant, UUID sourceEventId);

  /**
   * Việc đến hạn lọc theo mã kết nối: {@code include} chỉ lấy các mã trong {@code codes}, ngược lại
   * lấy mọi mã trừ {@code codes}.
   */
  List<Due> due(int limit, java.util.Set<String> codes, boolean include);

  void insert(
      TenantId tenant,
      UUID id,
      LocalDate day,
      UUID connectorId,
      UUID sourceEventId,
      String messageType,
      Sealed payload,
      Instant nextAttemptAt);

  List<Due> due(int limit);

  /** Khoá dòng còn chờ gửi ({@code FOR UPDATE SKIP LOCKED}); rỗng khi pod khác đang gửi. */
  Optional<Row> claim(TenantId tenant, LocalDate day, UUID id);

  Optional<Row> find(TenantId tenant, UUID id);

  void update(
      TenantId tenant,
      LocalDate day,
      UUID id,
      OutboundStatus status,
      int attempts,
      Instant nextAttemptAt,
      String ackRef);
}
