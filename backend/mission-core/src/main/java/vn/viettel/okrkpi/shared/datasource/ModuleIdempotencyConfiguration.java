package vn.viettel.okrkpi.shared.datasource;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import vn.viettel.okrkpi.shared.idempotency.IdempotencyTargets;
import vn.viettel.okrkpi.shared.idempotency.JdbcIdempotencyStore;

/**
 * {@code @Idempotent} trong mission-core ghi {@code <schema>.idempotency_key} bằng DataSource và
 * {@code <mô-đun>TransactionManager} của chính mô-đun (vai trò {@code svc_<schema>}), không bằng
 * DataSource mặc định của tiến trình vốn chỉ có quyền trên schema {@code task} (wiki trang 14).
 */
@Configuration(proxyBeanMethods = false)
public class ModuleIdempotencyConfiguration {

  @Bean
  public IdempotencyTargets moduleIdempotencyTargets(
      @Qualifier("moduleDataSources") Map<ModuleName, ReadWriteRoutingDataSource> moduleDataSources,
      BeanFactory beans) {
    Map<String, IdempotencyTargets.Target> targets = new HashMap<>();
    for (ModuleName module : ModuleName.values()) {
      targets.put(
          module.schema(),
          new IdempotencyTargets.Target(
              new JdbcIdempotencyStore(new JdbcTemplate(moduleDataSources.get(module))),
              beans.getBean(
                  module.schema() + "TransactionManager", PlatformTransactionManager.class)));
    }
    Map<String, IdempotencyTargets.Target> bySchema = Map.copyOf(targets);
    return schema -> Optional.ofNullable(bySchema.get(schema));
  }
}
