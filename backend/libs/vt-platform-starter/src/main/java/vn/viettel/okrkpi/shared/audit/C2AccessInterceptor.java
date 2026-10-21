package vn.viettel.okrkpi.shared.audit;

import java.util.Objects;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.annotation.AnnotationUtils;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Ghi nhật ký truy cập C2 cho phương thức gắn {@link C2Access} sau khi phương thức trả về thành
 * công và trước khi kết quả về tay người gọi (CORE-00-05).
 */
public final class C2AccessInterceptor implements MethodInterceptor {

  private final C2AccessAuditor auditor;

  public C2AccessInterceptor(C2AccessAuditor auditor) {
    this.auditor = Objects.requireNonNull(auditor, "auditor");
  }

  @Override
  public Object invoke(MethodInvocation invocation) throws Throwable {
    Object result = invocation.proceed();
    C2Access spec = AnnotationUtils.findAnnotation(invocation.getMethod(), C2Access.class);
    if (spec != null) {
      Object[] args = invocation.getArguments();
      String objectId =
          spec.idArg() >= 0 && spec.idArg() < args.length && args[spec.idArg()] != null
              ? String.valueOf(args[spec.idArg()])
              : "?";
      auditor.recordAccess(TenantContext.require(), spec.objectType(), objectId, spec.column());
    }
    return result;
  }
}
