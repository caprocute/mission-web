package vn.viettel.okrkpi.iam.events;

import java.nio.file.Path;
import java.util.List;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Nhà máy của event listener {@value #ID} (DV-02-05). Cấu hình qua biến môi trường của pod Keycloak (chart INF):
 * <ul>
 *   <li>{@code MISSION_EVENTS_KAFKA_BOOTSTRAP}: Kafka của cell; thiếu thì sự kiện chỉ được giữ trên đĩa;
 *   <li>{@code MISSION_EVENTS_REGISTRY_URL}: Apicurio Registry API v3 ({@code .../apis/registry/v3});
 *   <li>{@code MISSION_EVENTS_QUEUE_DIR}: thư mục hàng đợi bền, mặc định {@code /opt/keycloak/data/mission-events}
 *       (volume bền của pod);
 *   <li>{@code MISSION_EVENTS_MEMORY_CAPACITY}: sức chứa hàng đợi bộ nhớ, mặc định 100000.
 * </ul>
 */
public class MissionEventListenerProviderFactory implements EventListenerProviderFactory {

  public static final String ID = "mission-events";
  private static final Logger LOG = Logger.getLogger(MissionEventListenerProviderFactory.class);

  private DurableQueue queue;
  private KafkaSink sink;

  @Override
  public EventListenerProvider create(KeycloakSession session) {
    return new MissionEventListenerProvider(session, queue);
  }

  @Override
  public void init(Config.Scope config) {
  }

  @Override
  public void postInit(KeycloakSessionFactory factory) {
    String bootstrap = env("MISSION_EVENTS_KAFKA_BOOTSTRAP", null);
    String registry = env("MISSION_EVENTS_REGISTRY_URL", null);
    Path dir = Path.of(env("MISSION_EVENTS_QUEUE_DIR", "/opt/keycloak/data/mission-events"));
    int capacity = Integer.parseInt(env("MISSION_EVENTS_MEMORY_CAPACITY", "100000"));
    DurableQueue.Sink target;
    if (bootstrap == null || registry == null) {
      LOG.warn("Chưa cấu hình Kafka hoặc Apicurio cho mission-events: sự kiện chỉ được giữ trên đĩa");
      target = batch -> {
        throw new IllegalStateException("Chưa cấu hình Kafka");
      };
    } else {
      sink = new KafkaSink(bootstrap, registry);
      target = sink;
    }
    queue = new DurableQueue(dir, target, capacity);
    LOG.infof("mission-events sẵn sàng, hàng đợi tại %s", dir);
  }

  /** Hàng đợi bền dùng chung cho sự kiện do SPI khác của Mission phát (mã khôi phục DV-02-08). */
  DurableQueue queue() {
    return queue;
  }

  /** Hàng đợi của listener trong tiến trình Keycloak hiện tại; {@code null} khi listener chưa được nạp. */
  static DurableQueue queue(org.keycloak.models.KeycloakSession session) {
    var f = session.getKeycloakSessionFactory().getProviderFactory(EventListenerProvider.class, ID);
    return f instanceof MissionEventListenerProviderFactory m ? m.queue() : null;
  }

  @Override
  public void close() {
    if (queue != null) {
      queue.close();
    }
    if (sink != null) {
      sink.close();
    }
  }

  @Override
  public String getId() {
    return ID;
  }

  /** Loại sự kiện được đẩy lên topic (để tài liệu và kiểm thử đối chiếu với {@code enabledEventTypes} của realm). */
  public static List<String> eventTypes() {
    return EventMapper.USER_EVENTS.stream().sorted().toList();
  }

  private static String env(String name, String fallback) {
    String v = System.getenv(name);
    return v == null || v.isBlank() ? fallback : v;
  }
}
