package vn.viettel.okrkpi.oversight.application.port;

import java.time.Instant;
import java.util.List;
import vn.viettel.okrkpi.oversight.domain.AccessLogQuery;
import vn.viettel.okrkpi.oversight.domain.AuditDocument;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Đọc chỉ mục kiểm toán bằng tài khoản chỉ đọc của API (TL-01 G6). */
public interface AuditIndexReader {

  /**
   * Lần xem C2 của một tenant, mới nhất trước, sau {@code after} nếu có.
   *
   * @throws AuditIndexUnavailableException khi không truy cập được chỉ mục
   */
  List<AuditDocument> searchAccess(TenantId tenantId, AccessLogQuery query, After after, int limit);

  /** Vị trí của tài liệu cuối trang trước. */
  record After(Instant occurredAt, String docId) {}
}
