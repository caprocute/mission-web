package vn.viettel.okrkpi.workflow.application.port;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;

/** Cổng ra tới engine để khởi tạo tiến trình duyệt dùng chung định nghĩa (không gắn tenant). */
public interface ApprovalProcesses {

  /**
   * Khoá theo (tenant, mẫu, khoá nghiệp vụ) tới hết giao dịch hiện tại, để hai yêu cầu khởi tạo
   * đồng thời của cùng đối tượng không tạo hai tiến trình.
   */
  void lockStart(UUID tenantId, String templateCode, BusinessKey businessKey);

  /**
   * Tiến trình đã khởi tạo (đang chạy hoặc đã kết thúc) cho cùng đối tượng ở cùng phiên bản đối
   * tượng; rỗng khi chưa có. Mỗi lần trình duyệt lại sau khi trả lại, lõi tăng phiên bản đối tượng
   * nên được một tiến trình mới.
   */
  Optional<String> findStarted(
      UUID tenantId, String templateCode, BusinessKey businessKey, long objectVersion);

  /**
   * Khởi tạo tiến trình của mẫu với tenant đặt trên thể hiện ({@code
   * overrideProcessDefinitionTenantId}); trả mã thể hiện.
   */
  String start(
      UUID tenantId,
      String templateCode,
      BusinessKey businessKey,
      UUID initiatorId,
      Map<String, Object> variables);

  /**
   * Kết thúc các tiến trình đang chạy của đối tượng còn chờ người duyệt (có việc người dùng mở),
   * khi lõi đã tự quyết định hoặc người lập rút lại. Tiến trình đang ở bước gọi lại lõi thì để
   * nguyên vì nó tự kết thúc. Trả số tiến trình đã kết thúc; gọi lặp không lỗi.
   */
  int cancelWaiting(UUID tenantId, String templateCode, BusinessKey businessKey, String reason);
}
