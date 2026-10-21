package vn.viettel.okrkpi.shared.idempotency;

import java.time.LocalDate;
import java.util.Objects;

/** Kết quả {@link IdempotencyStore#begin}: một trong bốn nhánh của TL-03 "Bảng idempotency_key". */
public sealed interface BeginResult {

  /**
   * Khoá mới đã được chèn {@code DANG_XU_LY}; người gọi thực hiện tác dụng rồi {@code complete}.
   */
  record Started(IdempotencyScope scope, String key, LocalDate createdDay) implements BeginResult {
    public Started {
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(createdDay, "createdDay");
    }
  }

  /** Cùng khoá, cùng băm, đã {@code XONG}: phát lại phản hồi đã lưu. */
  record Replay(IdempotencyRecord record) implements BeginResult {
    public Replay {
      Objects.requireNonNull(record, "record");
    }
  }

  /** Cùng khoá, khác băm: 422 {@code COMMON-422-IDEMPOTENCY-MISMATCH}. */
  record Mismatch() implements BeginResult {}

  /** Cùng khoá đang được yêu cầu khác xử lý: 409 kèm {@code Retry-After}. */
  record InProgress() implements BeginResult {}
}
