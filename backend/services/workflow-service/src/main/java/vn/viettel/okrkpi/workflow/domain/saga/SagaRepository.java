package vn.viettel.okrkpi.workflow.domain.saga;

import java.util.Optional;
import java.util.UUID;

/**
 * Kho bền của saga ({@code flowable.saga_instance}, {@code flowable.saga_step}). Mọi thao tác chạy
 * trong giao dịch đã đặt ngữ cảnh tenant (RLS).
 */
public interface SagaRepository {

  Optional<SagaInstance> findById(UUID sagaId);

  Optional<SagaInstance> findByCorrelation(SagaType type, UUID correlationId);

  /**
   * Khoá giao dịch theo khoá độc quyền (ví dụ mã kỳ) để hai lệnh khởi động đồng thời không cùng tạo
   * saga cho một khoá.
   */
  void lockExclusiveKey(SagaType type, String exclusiveKey);

  /** Có saga cùng loại, cùng khoá độc quyền đang chạy hoặc đã hoàn tất (chưa bị bù trừ). */
  boolean existsBlockingFor(SagaType type, String exclusiveKey);

  void insert(SagaInstance saga);

  /**
   * Ghi trạng thái mới; ném {@link ConcurrentSagaUpdateException} khi {@code row_version} đã đổi.
   */
  void update(SagaInstance saga);

  /** Hai lệnh cùng sửa một saga; lệnh thua phải đọc lại và làm lại. */
  final class ConcurrentSagaUpdateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConcurrentSagaUpdateException(UUID sagaId) {
      super("Saga " + sagaId + " vừa được cập nhật bởi lệnh khác", null, false, false);
    }
  }
}
