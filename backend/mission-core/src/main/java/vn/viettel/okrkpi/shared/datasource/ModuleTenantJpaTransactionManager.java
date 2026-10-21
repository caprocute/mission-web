package vn.viettel.okrkpi.shared.datasource;

import jakarta.persistence.EntityManagerFactory;
import java.util.Objects;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;
import vn.viettel.okrkpi.shared.tenant.TenantJpaTransactionManager;
import vn.viettel.okrkpi.shared.tenant.TenantProperties;

/**
 * Trình quản lý giao dịch JPA của từng mô-đun trong {@code mission-core}.
 *
 * <p>Kế thừa {@link TenantJpaTransactionManager} để gán biến phiên RLS {@code set_config(dbSetting,
 * tenant, true)} khi mở giao dịch có {@code TenantContext}, đồng thời ghi nhận dấu thời gian ghi
 * vào {@link RecentWriteTracker} đối với mọi giao dịch không phải chỉ đọc ({@code readOnly =
 * false}) nhằm bảo đảm *read-your-own-writes* trong 2 giây.
 */
public class ModuleTenantJpaTransactionManager extends TenantJpaTransactionManager {

  private static final long serialVersionUID = 1L;

  private final ModuleName moduleName;
  private final transient RecentWriteTracker recentWriteTracker;

  public ModuleTenantJpaTransactionManager(
      ModuleName moduleName,
      EntityManagerFactory emf,
      TenantProperties tenantProperties,
      RecentWriteTracker recentWriteTracker) {
    super(emf, tenantProperties);
    this.moduleName = Objects.requireNonNull(moduleName, "moduleName");
    this.recentWriteTracker = Objects.requireNonNull(recentWriteTracker, "recentWriteTracker");
  }

  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {
    super.doBegin(transaction, definition);
    if (!definition.isReadOnly()) {
      recentWriteTracker.recordWrite();
    }
  }

  @Override
  protected void doCommit(DefaultTransactionStatus status) {
    super.doCommit(status);
    if (!status.isReadOnly()) {
      recentWriteTracker.recordWrite();
    }
  }

  /** Mô-đun nghiệp vụ sở hữu trình quản lý giao dịch này. */
  public ModuleName moduleName() {
    return moduleName;
  }
}
