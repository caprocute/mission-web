package vn.viettel.okrkpi.shared.authz;

/**
 * Giao diện cho đối tượng đầu vào của tầng {@code application} tự cung cấp thông tin tài nguyên
 * phục vụ kiểm quyền OPA (NEN-014).
 */
@FunctionalInterface
public interface AuthzResourceProvider {

  AuthzResource toAuthzResource();
}
