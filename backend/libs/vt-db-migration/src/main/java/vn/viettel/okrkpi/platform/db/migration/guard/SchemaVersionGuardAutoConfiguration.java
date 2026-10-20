package vn.viettel.okrkpi.platform.db.migration.guard;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Tự cấu hình {@link SchemaVersionGuard}: khi có DataSource và khai {@code
 * vt.db.schema-guard.schemas}, kiểm từng schema lúc dựng ngữ cảnh; schema nào thấp hơn mức tối
 * thiểu thì ngữ cảnh không khởi động được (pod không bao giờ sẵn sàng).
 *
 * <p>Ứng dụng đi kèm phải đặt {@code spring.flyway.enabled=false} và {@code
 * spring.jpa.hibernate.ddl-auto=validate} (TL-03 L183).
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(
    prefix = "vt.db.schema-guard",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(SchemaVersionGuardProperties.class)
public class SchemaVersionGuardAutoConfiguration {

  private static final Logger LOG =
      LoggerFactory.getLogger(SchemaVersionGuardAutoConfiguration.class);

  @Bean
  InitializingBean schemaVersionGuard(
      DataSource dataSource, SchemaVersionGuardProperties properties) {
    return () -> {
      for (SchemaVersionGuardProperties.SchemaRequirement r : properties.getSchemas()) {
        var current = SchemaVersionGuard.verify(dataSource, r.getName(), r.getMinVersion());
        LOG.info(
            "Lược đồ {} ở phiên bản {} (tối thiểu {})", r.getName(), current, r.getMinVersion());
      }
    };
  }
}
