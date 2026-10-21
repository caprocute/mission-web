package vn.viettel.okrkpi.shared.datasource;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.platform.db.migration.guard.SchemaVersionGuard;
import vn.viettel.okrkpi.shared.datasource.MissionCoreDataSourceProperties.SchemaGuardConfig;

/**
 * Kiểm tra bảng {@code flyway_schema_history} của cả 9 schema ({@code okr}, {@code task}, {@code
 * product}, {@code appraisal}, {@code policy}, {@code dossier}, {@code oversight}, {@code config},
 * {@code org_replica}) ngay khi {@code mission-core} khởi động (TL-01 L2767–2782, TL-03 L183).
 *
 * <p>Nếu bất kỳ schema nào chưa chạy di trú hoặc có phiên bản thấp hơn mức tối thiểu, ứng dụng ném
 * {@link vn.viettel.okrkpi.platform.db.migration.guard.SchemaVersionTooLowException} và từ chối
 * khởi động.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MissionCoreSchemaVersionGuard implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(MissionCoreSchemaVersionGuard.class);

  public static final List<String> REQUIRED_SCHEMAS =
      List.of(
          "okr",
          "task",
          "product",
          "appraisal",
          "policy",
          "dossier",
          "oversight",
          "config",
          "org_replica");

  private final Map<ModuleName, ReadWriteRoutingDataSource> moduleDataSources;
  private final DataSource orgReplicaDataSource;
  private final MissionCoreDataSourceProperties properties;

  public MissionCoreSchemaVersionGuard(
      @Qualifier("moduleDataSources") Map<ModuleName, ReadWriteRoutingDataSource> moduleDataSources,
      @Qualifier("orgReplicaDataSource") DataSource orgReplicaDataSource,
      MissionCoreDataSourceProperties properties) {
    this.moduleDataSources = moduleDataSources;
    this.orgReplicaDataSource = orgReplicaDataSource;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    verifyAllSchemas();
  }

  /** Đối chiếu phiên bản của cả 9 schema trên từng {@link DataSource} tương ứng. */
  public void verifyAllSchemas() {
    SchemaGuardConfig guard = properties.getSchemaGuard();
    if (guard == null || !guard.isEnabled()) {
      log.info("Bỏ qua kiểm tra phiên bản lược đồ vì okrkpi.schema-guard.enabled=false");
      return;
    }
    for (ModuleName module : ModuleName.values()) {
      String schema = module.schema();
      String minVersion = guard.minVersionFor(schema);
      DataSource ds = moduleDataSources.get(module);
      SchemaVersionGuard.verify(ds, schema, minVersion);
    }
    String replicaMin = guard.minVersionFor("org_replica");
    SchemaVersionGuard.verify(orgReplicaDataSource, "org_replica", replicaMin);
  }
}
