package vn.viettel.okrkpi.okr.application.port;

import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng tìm nhiệm vụ có nội dung gần giống KR (BR-02-06) qua SVC-16 tìm kiếm. Chưa có bean thì bộ
 * kiểm tra chỉ dùng dấu hiệu động từ nhiệm vụ; lỗi khi gọi thì trả cảnh báo "chưa kiểm được" và
 * không chặn trình (TL-02a QT-02 "Ngoại lệ và xử lý").
 */
public interface OkrSimilarityPort {

  /** Mã nhiệm vụ gần giống nhất (ví dụ {@code NV-2027-Q1-0012}), rỗng khi không có. */
  Optional<String> similarTask(TenantId tenant, UUID periodId, UUID unitId, String krTitle);
}
