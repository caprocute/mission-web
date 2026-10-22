package vn.viettel.okrkpi.analytics.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Phạm vi dữ liệu của một lượt xem (BR-16-01): {@code orgPath} là nhánh cây tổ chức đưa vào ngữ
 * cảnh an ninh của Cube; {@code viewerUnits} là đơn vị công tác của người xem cùng các đơn vị cấp
 * trên, dùng lọc dashboard chia sẻ theo đơn vị ở MOD-08.
 */
public record Scope(UUID tenant, String orgPath, List<UUID> viewerUnits) {

  /** Đường dẫn mã đơn vị chữ thường, như {@code dim_org_scope} và bộ kiểm của {@code cube.js}. */
  public static final String ORG_PATH = "(/[a-z0-9][a-z0-9._-]*)+";

  public Scope {
    Objects.requireNonNull(tenant, "tenant");
    if (orgPath == null || !orgPath.matches(ORG_PATH)) {
      throw new IllegalArgumentException("org_path không hợp lệ");
    }
    viewerUnits = viewerUnits == null ? List.of() : List.copyOf(viewerUnits);
  }

  /** Đúng khi {@code path} là chính nhánh này hoặc một nhánh con của nó. */
  public boolean covers(String path) {
    return path != null && (path.equals(orgPath) || path.startsWith(orgPath + "/"));
  }

  public Scope narrowTo(String path) {
    return new Scope(tenant, path, viewerUnits);
  }
}
