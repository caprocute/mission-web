package vn.viettel.okrkpi.shared.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * {@link JpaTransactionManager} đặt {@code app.tenant_id} cục bộ giao dịch ngay sau {@code BEGIN}
 * (TL-03 "Đặt ngữ cảnh tenant an toàn qua PgBouncer", mẫu {@code TenantJpaTransactionManager}).
 * Khác mẫu TL-03: thiếu tenant thì đặt chuỗi rỗng (RLS trả rỗng) thay vì ném lỗi, theo TL-01 chặng
 * bốn, để job khởi động hay health check không làm hỏng giao dịch (NEN-010 mục 3).
 */
public class TenantJpaTransactionManager extends JpaTransactionManager {

  private static final long serialVersionUID = 1L;

  private final String setting;

  public TenantJpaTransactionManager(EntityManagerFactory emf, TenantProperties properties) {
    super(emf);
    this.setting = properties.dbSetting();
  }

  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {
    super.doBegin(transaction, definition);
    EntityManager em =
        EntityManagerFactoryUtils.getTransactionalEntityManager(obtainEntityManagerFactory());
    if (em == null) {
      throw new IllegalStateException("Không lấy được EntityManager của giao dịch vừa mở");
    }
    em.unwrap(Session.class).doWork(connection -> TenantSql.apply(connection, setting));
  }
}
