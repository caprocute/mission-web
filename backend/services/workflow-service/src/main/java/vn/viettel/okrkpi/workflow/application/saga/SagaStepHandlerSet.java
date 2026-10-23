package vn.viettel.okrkpi.workflow.application.saga;

import java.util.List;

/**
 * Nhóm bộ xử lý bước do cấu hình sinh ra (ví dụ mỗi cell một bước triển khai của saga phát hành mẫu
 * luồng, DV-06-09); khung gộp vào danh mục như các bộ xử lý khai riêng lẻ.
 */
public interface SagaStepHandlerSet {

  List<SagaStepHandler> handlers();
}
