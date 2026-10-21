package vn.viettel.okrkpi.org.application.port;

import java.util.UUID;
import vn.vtokr.org.v1.OrgBootstrap;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * DV-04-08 · Phát thẳng lên Kafka (không qua outbox) cho lệnh {@code org-republish}: thông điệp {@code
 * org.bootstrap.v1} khoá {@code tenantId:aggregateId} và sự kiện kiểm toán {@code sys.audit.v1}.
 */
public interface BootstrapPublisher {

  /** Gửi bất đồng bộ một thông điệp khởi tạo; lỗi gửi được báo ở {@link #flush()}. */
  void publish(UUID tenant, UUID aggregateId, long aggregateVersion, OrgBootstrap message);

  /** Chờ mọi thông điệp đã gửi được xác nhận; ném ngoại lệ nếu có thông điệp lỗi. */
  void flush();

  /** Gửi và chờ xác nhận một sự kiện kiểm toán; {@code actorId} null khi người chạy không có mã tài khoản. */
  void audit(UUID tenant, UUID batchId, UUID actorId, AuditEvent event);
}
