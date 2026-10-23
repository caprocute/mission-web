package vn.viettel.okrkpi.workflow.application.saga;

import java.util.Map;
import vn.viettel.okrkpi.workflow.domain.saga.SagaDefinition;

/**
 * Khai báo một loại saga để đăng ký vào khung: định nghĩa bước và cách tính khoá độc quyền từ tham
 * số (ví dụ saga chốt kỳ độc quyền theo {@code periodId}).
 */
public interface SagaBlueprint {

  SagaDefinition definition();

  /**
   * Khoá độc quyền: hai saga cùng loại cùng khoá không được cùng chạy hoặc cùng hoàn tất (409
   * {@code WORKFLOW-409-SAGA-EXISTS}); {@code null} khi loại saga không cần.
   */
  String exclusiveKey(Map<String, Object> parameters);

  /**
   * Mã lỗi trả khi người vận hành yêu cầu bù trừ sau điểm không quay lại; {@code null} dùng mã xung
   * đột chung {@code WORKFLOW-409-VERSION-CONFLICT}.
   */
  default String pointOfNoReturnCode() {
    return null;
  }
}
