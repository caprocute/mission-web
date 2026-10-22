package vn.viettel.okrkpi.task.infrastructure.web;

import java.util.Optional;
import vn.viettel.okrkpi.task.domain.lifecycle.TaskActor;

/**
 * Người gọi lệnh chuyển trạng thái (cán bộ và vai trò realm); không có bean thì dùng chủ thể của
 * {@code AuthzSubjectResolver} (JWT). Bean riêng dùng cho kiểm thử hoặc khi cell đặt bộ trích khác.
 */
public interface TaskCaller {

  Optional<TaskActor> current();

  /** Mức xác thực ({@code acr}) của phiên, để kiểm xác thực bước lên (CORE-02-09). */
  default Optional<String> acr() {
    return Optional.empty();
  }

  /** Thời điểm xác thực ({@code auth_time}, giây epoch). */
  default Optional<Long> authTime() {
    return Optional.empty();
  }
}
