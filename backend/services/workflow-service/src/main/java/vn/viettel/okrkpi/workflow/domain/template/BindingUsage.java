package vn.viettel.okrkpi.workflow.domain.template;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Một {@code flowable.wf_binding} đang dùng mẫu: tham số của tenant mà bộ test phải chạy lại.
 *
 * @param tenantId tenant
 * @param bindingId mã dòng {@code wf_binding}
 * @param processKind loại quy trình
 * @param flow luồng Đảng hoặc Nhà nước, có thể rỗng
 * @param params tham số ({@code params_json})
 */
public record BindingUsage(
    UUID tenantId, UUID bindingId, String processKind, String flow, Map<String, Object> params) {

  public BindingUsage {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(bindingId, "bindingId");
    params = params == null ? Map.of() : new LinkedHashMap<>(params);
  }

  /** Nhãn của bộ tham số trong kết quả test (không lộ tên tenant). */
  public String label() {
    return "Tham số " + bindingId.toString().substring(0, 8);
  }
}
