package vn.viettel.okrkpi.task.application.document.port;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.document.port.IncomingDocs.Content;

/**
 * Toàn văn văn bản đến và đơn vị nhận đã ánh xạ ở SVC-13 integration-gateway (EVT-24 chỉ mang siêu
 * dữ liệu). Rỗng khi SVC-13 không có nội dung của văn bản.
 */
public interface IncomingDocContents {

  /**
   * @throws vn.viettel.okrkpi.shared.resilience.DependencyUnavailableException khi SVC-13 lỗi
   */
  Optional<Content> fetch(TenantId tenant, UUID documentId);
}
