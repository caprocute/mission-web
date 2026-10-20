package vn.viettel.okrkpi.shared.kernel;

import java.util.Objects;
import java.util.UUID;

/**
 * Định danh tenant ({@code registry.tenant.id}, cột {@code tenant_id} của mọi bảng nghiệp vụ).
 * Tenant do tenant-registry (SVC-03) cấp; ngữ cảnh tenant trong yêu cầu thuộc NEN-010.
 *
 * @param value giá trị UUID, không rỗng
 */
public record TenantId(UUID value) {

  public TenantId {
    Objects.requireNonNull(value, "TenantId.value");
  }

  public static TenantId of(UUID value) {
    return new TenantId(value);
  }

  /** Đọc từ chuỗi UUID (ví dụ claim token hoặc header đã được kiểm). */
  public static TenantId parse(String value) {
    return new TenantId(Ids.parse(value));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
