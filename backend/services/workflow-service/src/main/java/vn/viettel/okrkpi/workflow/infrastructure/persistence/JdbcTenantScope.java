package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;
import vn.viettel.okrkpi.shared.tenant.TenantSql;
import vn.viettel.okrkpi.workflow.application.port.TenantScope;

/**
 * {@link TenantScope}: đặt ngữ cảnh tenant của luồng và {@code app.tenant_id} cục bộ giao dịch sang
 * tenant đích, chạy phần ghi, rồi trả cả hai về tenant của người gọi để bản ghi idempotency của yêu
 * cầu (thuộc tenant người gọi) vẫn ghi được dưới RLS.
 */
@Component
public class JdbcTenantScope implements TenantScope {

  private static final String SET_LOCAL = "select set_config(?, ?, true)";

  private final JdbcTemplate jdbc;

  public JdbcTenantScope(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public <T> T within(UUID tenantId, Supplier<T> work) {
    Optional<TenantId> previous = TenantContext.current();
    if (previous.isPresent() && previous.get().value().equals(tenantId)) {
      return work.get();
    }
    AtomicReference<T> result = new AtomicReference<>();
    try {
      TenantContext.run(
          TenantId.of(tenantId),
          () -> {
            jdbc.queryForObject(
                SET_LOCAL, String.class, TenantSql.DEFAULT_SETTING, tenantId.toString());
            result.set(work.get());
          });
      return result.get();
    } finally {
      jdbc.queryForObject(
          SET_LOCAL,
          String.class,
          TenantSql.DEFAULT_SETTING,
          previous.map(t -> t.value().toString()).orElse(""));
    }
  }
}
