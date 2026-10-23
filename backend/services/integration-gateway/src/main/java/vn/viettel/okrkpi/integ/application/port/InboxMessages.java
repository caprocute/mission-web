package vn.viettel.okrkpi.integ.application.port;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.integ.application.port.PayloadCipher.Sealed;
import vn.viettel.okrkpi.integ.domain.InboxStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Thông điệp vào ({@code integ.inbox_message}, phân vùng theo ngày nhận). */
public interface InboxMessages {

  /** Dòng đã có hoặc vừa ghi; {@code created} cho biết lần gọi này đã ghi dòng mới. */
  record Stored(UUID id, LocalDate receivedDay, InboxStatus status, boolean created) {}

  /** Dòng đầy đủ để phát lại từ thư chết. */
  record Raw(
      UUID id,
      LocalDate receivedDay,
      UUID connectorId,
      String externalMsgId,
      String messageType,
      byte[] rawEnc,
      InboxStatus status) {}

  /**
   * Ghi thông điệp {@code MOI} nếu chưa có cùng {@code externalMsgId} của kết nối từ ngày {@code
   * since}; có rồi thì trả dòng cũ, không ghi.
   */
  Stored store(
      TenantId tenant,
      UUID id,
      LocalDate today,
      LocalDate since,
      UUID connectorId,
      String externalMsgId,
      String messageType,
      Sealed raw);

  /** Đã xử lý xong ({@code DA_PHAT} hoặc {@code DA_CHUYEN_DOI}) từ ngày {@code since}. */
  boolean processed(TenantId tenant, UUID connectorId, String externalMsgId, LocalDate since);

  Optional<Raw> find(TenantId tenant, UUID id);

  void mark(TenantId tenant, LocalDate day, UUID id, InboxStatus status, UUID emittedEventId);

  /** Sự kiện {@code eventId} do chính gateway phát từ thông điệp vào của kết nối này. */
  boolean emitted(TenantId tenant, UUID connectorId, UUID eventId);
}
