package vn.viettel.okrkpi.oversight.application.port;

import java.util.List;
import vn.viettel.okrkpi.oversight.domain.AuditDocument;

/**
 * Ghi chỉ mục kiểm toán; chỉ consumer {@code oversight.audit-indexer} giữ tài khoản có quyền ghi
 * (TL-01 G6). Ghi lại cùng {@code docId} ghi đè, nên nhận trùng không tạo tài liệu trùng.
 */
public interface AuditIndexWriter {

  /**
   * @throws AuditIndexUnavailableException khi cụm OpenSearch không nhận ghi (thử lại sau)
   */
  void index(List<AuditDocument> documents);
}
