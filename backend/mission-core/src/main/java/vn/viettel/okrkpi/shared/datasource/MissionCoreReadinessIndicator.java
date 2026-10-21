package vn.viettel.okrkpi.shared.datasource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Đầu dò sẵn sàng ({@code readinessProbe}) tổng hợp của {@code SVC-05 mission-core} (TL-04 WI-20,
 * L934–969).
 *
 * <p>Kiểm tra:
 *
 * <ul>
 *   <li>Tất cả 8 pool {@link DataSource} của 8 mô-đun nghiệp vụ và pool {@code org_replica} đều
 *       thực thi được {@code SELECT 1};
 *   <li>Trạng thái sẵn sàng của Kafka producer ({@code mission.service.kafka.producer-ready}).
 * </ul>
 *
 * <p>Đầu dò sống ({@code livenessProbe}) tuyệt đối không gọi indicator này mà chỉ kiểm tra nội tại
 * JVM ({@code livenessState,ping}).
 */
@Component("missionCoreReadiness")
public class MissionCoreReadinessIndicator implements HealthIndicator {

  private final Map<ModuleName, ReadWriteRoutingDataSource> moduleDataSources;
  private final DataSource orgReplicaDataSource;
  private final AtomicBoolean kafkaProducerReady;

  public MissionCoreReadinessIndicator(
      @Qualifier("moduleDataSources") Map<ModuleName, ReadWriteRoutingDataSource> moduleDataSources,
      @Qualifier("orgReplicaDataSource") DataSource orgReplicaDataSource,
      @Value("${mission.service.kafka.producer-ready:true}") boolean kafkaProducerReady) {
    this.moduleDataSources = moduleDataSources;
    this.orgReplicaDataSource = orgReplicaDataSource;
    this.kafkaProducerReady = new AtomicBoolean(kafkaProducerReady);
  }

  @Override
  public Health health() {
    Map<String, Object> details = new LinkedHashMap<>();
    boolean allPoolsUp = true;

    for (ModuleName module : ModuleName.values()) {
      String schema = module.schema();
      DataSource ds = moduleDataSources.get(module);
      boolean up = pingDataSource(ds);
      details.put("db." + schema, up ? "UP" : "DOWN");
      if (!up) {
        allPoolsUp = false;
      }
    }

    boolean replicaUp = pingDataSource(orgReplicaDataSource);
    details.put("db.org_replica", replicaUp ? "UP" : "DOWN");
    if (!replicaUp) {
      allPoolsUp = false;
    }

    boolean kafkaUp = kafkaProducerReady.get();
    details.put("kafka.producer", kafkaUp ? "UP" : "DOWN");

    if (allPoolsUp && kafkaUp) {
      return Health.up().withDetails(details).build();
    }
    return Health.down().withDetails(details).build();
  }

  /** Cập nhật trạng thái sẵn sàng của Kafka producer (phục vụ kiểm thử hoặc sự kiện kết nối). */
  public void setKafkaProducerReady(boolean ready) {
    this.kafkaProducerReady.set(ready);
  }

  private static boolean pingDataSource(DataSource dataSource) {
    if (dataSource == null) {
      return false;
    }
    try {
      Integer one = JdbcClient.create(dataSource).sql("SELECT 1").query(Integer.class).single();
      return one != null && one == 1;
    } catch (RuntimeException ex) {
      return false;
    }
  }
}
