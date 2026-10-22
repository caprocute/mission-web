package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantSql;

/**
 * Đặt {@code app.tenant_id} cục bộ giao dịch cho bảng {@code wf_*} có RLS.
 *
 * <p>Yêu cầu API đã có ngữ cảnh tenant từ JWT (trình quản lý giao dịch của thư viện nền đặt sẵn);
 * luồng async executor của Flowable không có ngữ cảnh, nên tenant lấy từ chính user task. Nếu ngữ
 * cảnh đã có mà khác tenant của dòng thì từ chối, không bao giờ ghi chéo tenant.
 */
final class TenantBinding {

  private static final String SET_LOCAL = "select set_config(?, ?, true)";

  private TenantBinding() {}

  static void bind(JdbcTemplate jdbc, UUID tenantId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Ghi bảng chiếu phải nằm trong giao dịch của engine");
    }
    Optional<TenantId> context = TenantContext.current();
    if (context.isPresent() && !context.get().value().equals(tenantId)) {
      throw new IllegalStateException("Tenant của user task khác tenant của yêu cầu");
    }
    jdbc.queryForObject(SET_LOCAL, String.class, TenantSql.DEFAULT_SETTING, tenantId.toString());
  }
}
