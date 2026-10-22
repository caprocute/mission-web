package vn.viettel.okrkpi.task.application.lifecycle.port;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Phiên bản minh chứng hiện hành của từng sản phẩm, cho biến {@code deliverables} của guard CEL
 * (mỗi phần tử có {@code status}). MOD-03 cung cấp ở CORE-03; khi chưa có bean, danh sách rỗng.
 */
@FunctionalInterface
public interface TaskDeliverables {

  List<Map<String, Object>> of(TenantId tenant, UUID taskId);
}
