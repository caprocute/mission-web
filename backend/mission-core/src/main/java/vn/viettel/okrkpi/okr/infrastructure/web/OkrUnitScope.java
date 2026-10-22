package vn.viettel.okrkpi.okr.infrastructure.web;

import java.util.UUID;
import vn.viettel.okrkpi.shared.authz.AuthzResource;
import vn.viettel.okrkpi.shared.authz.AuthzResourceProvider;

/**
 * Tham số {@code unit} của API đọc OKR, gắn làm đơn vị chủ quản của tài nguyên khi kiểm OPA (phạm
 * vi Đv theo ma trận SCR-0201.xem). Không truyền đơn vị thì lớp OPA dùng đơn vị của phân công.
 */
public record OkrUnitScope(UUID unit) implements AuthzResourceProvider {

  @Override
  public AuthzResource toAuthzResource() {
    return unit == null
        ? null
        : AuthzResource.builder().type("OKR").ownerUnitId(unit.toString()).build();
  }
}
