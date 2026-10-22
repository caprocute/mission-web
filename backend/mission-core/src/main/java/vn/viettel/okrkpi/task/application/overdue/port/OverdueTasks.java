package vn.viettel.okrkpi.task.application.overdue.port;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Nhiệm vụ đang mở ({@code DA_GIAO}, {@code DANG_THUC_HIEN}, {@code TAM_DUNG}) của tenant. */
public interface OverdueTasks {

  /** Một nhiệm vụ đang mở; {@code leadId} là chủ trì còn hiệu lực. */
  record OpenTask(
      UUID id,
      int periodBucket,
      String code,
      String title,
      UUID assignerId,
      UUID leadId,
      LocalDate scoreDueDate,
      String status,
      boolean overdue,
      long rowVersion) {}

  /** Một lô theo khoá {@code id} tăng dần, sau {@code afterId} (rỗng là từ đầu). */
  List<OpenTask> page(TenantId tenant, UUID afterId, int limit);

  /**
   * Đổi cờ quá hạn khi khác giá trị hiện tại; trả {@code true} nếu lời gọi này đã đổi (chỉ một bản
   * sao thắng khi chạy đồng thời). Không tăng {@code row_version} vì cờ là thuộc tính tính theo
   * lịch, không phải thay đổi của người dùng.
   */
  boolean flag(TenantId tenant, int periodBucket, UUID taskId, boolean overdue);
}
