package vn.viettel.okrkpi.workflow.infrastructure.security;

import java.util.HashSet;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.workflow.application.port.CurrentApprover;
import vn.viettel.okrkpi.workflow.domain.Approver;

/**
 * {@link CurrentApprover} từ token đã xác thực: tenant lấy từ {@link TenantContext} (bộ lọc của thư
 * viện nền đọc claim {@code tenant_id}), người dùng là {@code sub}, vai trò và đơn vị đang chọn lấy
 * qua {@link AuthzSubjectResolver} (cùng nguồn với đầu vào OPA).
 */
@Component
public class JwtCurrentApprover implements CurrentApprover {

  private final AuthzSubjectResolver subjects;

  public JwtCurrentApprover(ObjectProvider<AuthzSubjectResolver> subjects) {
    this.subjects = subjects.getIfAvailable(AuthzSubjectResolver::new);
  }

  @Override
  public Approver current() {
    TenantId tenant =
        TenantContext.current()
            .orElseThrow(() -> new IllegalStateException("Yêu cầu không có ngữ cảnh tenant"));
    OpaInput.Subject subject = subjects.currentSubject();
    if (subject.userId() == null) {
      throw new IllegalStateException("Yêu cầu không có người dùng đã xác thực");
    }
    return new Approver(
        tenant.value(),
        UUID.fromString(subject.userId()),
        new HashSet<>(subject.roles()),
        parse(subject.unitId()));
  }

  private static UUID parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
