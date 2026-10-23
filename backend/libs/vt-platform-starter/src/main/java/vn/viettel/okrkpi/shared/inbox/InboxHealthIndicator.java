package vn.viettel.okrkpi.shared.inbox;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

/** NEN-012 · Báo trạng thái các listener {@link InboxListener} đã đăng ký qua Actuator Health. */
public class InboxHealthIndicator implements HealthIndicator {

  private final String schema;
  private final InboxListenerRegistrar registrar;
  private final boolean autoStartup;

  public InboxHealthIndicator(String schema, InboxListenerRegistrar registrar) {
    this(schema, registrar, true);
  }

  /**
   * @param autoStartup {@code false} khi listener cố ý không tự chạy (TTDL chờ, INF-28): container
   *     dừng không làm health DOWN.
   */
  public InboxHealthIndicator(
      String schema, InboxListenerRegistrar registrar, boolean autoStartup) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.registrar = Objects.requireNonNull(registrar, "registrar");
    this.autoStartup = autoStartup;
  }

  @Override
  public Health health() {
    List<InboxEndpoint> endpoints = registrar.endpoints();
    List<ConcurrentMessageListenerContainer<String, byte[]>> containers = registrar.containers();
    long runningCount =
        containers.stream().filter(ConcurrentMessageListenerContainer::isRunning).count();

    Health.Builder builder =
        autoStartup && !containers.isEmpty() && runningCount < containers.size()
            ? Health.down()
            : Health.up();
    return builder
        .withDetail("schema", schema)
        .withDetail("registeredEndpoints", endpoints.size())
        .withDetail("totalContainers", containers.size())
        .withDetail("runningContainers", runningCount)
        .withDetail("autoStartup", autoStartup)
        .build();
  }
}
