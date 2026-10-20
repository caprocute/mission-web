package vn.viettel.okrkpi.org.application.port;

import java.util.Optional;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.IdempotencyRequest;

/** Khoá chống ghi trùng {@code org.idempotency_key}, cùng giao dịch với thao tác nghiệp vụ, giữ 48 giờ. */
public interface IdempotencyPort {

  /**
   * Đăng ký khoá. Trả kết quả đã lưu nếu khoá đã xong với cùng băm; ném {@code IdempotencyMismatchException} nếu băm
   * khác; rỗng nếu là lần đầu.
   */
  <R> Optional<R> begin(Actor actor, IdempotencyRequest request, Class<R> resultType);

  void complete(Actor actor, IdempotencyRequest request, int status, Object result, String resourceRef);
}
