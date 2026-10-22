package vn.viettel.okrkpi.iam.events;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Gửi lô thông điệp lên Kafka (acks=all, idempotent) với tiêu đề {@code apicurio.value.globalId} như outbox của các
 * service, để consumer dùng chung một bộ giải mã. Mã lược đồ tra trong Apicurio Registry theo chiến lược
 * {@code TopicIdStrategy} ({@code <topic>-value}, phiên bản mới nhất); không tự đăng ký lược đồ.
 */
final class KafkaSink implements DurableQueue.Sink, AutoCloseable {

  private static final Pattern GLOBAL_ID = Pattern.compile("\"globalId\"\\s*:\\s*(\\d+)");

  private final KafkaProducer<String, byte[]> producer;
  private final String registryUrl;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final Map<String, Long> globalIds = new ConcurrentHashMap<>();

  KafkaSink(String bootstrapServers, String registryUrl) {
    this.registryUrl = registryUrl.endsWith("/") ? registryUrl.substring(0, registryUrl.length() - 1) : registryUrl;
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    p.put(ProducerConfig.ACKS_CONFIG, "all");
    p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
    p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "3000");
    p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
    p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "10000");
    p.put(ProducerConfig.LINGER_MS_CONFIG, "5");
    p.put(ProducerConfig.CLIENT_ID_CONFIG, "keycloak-mission-events");
    // Lớp tuần tự hoá nạp theo classloader của provider, không theo classloader ngữ cảnh của Keycloak.
    Thread current = Thread.currentThread();
    ClassLoader previous = current.getContextClassLoader();
    current.setContextClassLoader(KafkaSink.class.getClassLoader());
    try {
      this.producer = new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer());
    } finally {
      current.setContextClassLoader(previous);
    }
  }

  @Override
  public void send(List<Outgoing> batch) throws Exception {
    List<Future<RecordMetadata>> pending = new ArrayList<>(batch.size());
    for (Outgoing m : batch) {
      RecordHeaders h = new RecordHeaders();
      m.headers().forEach((k, v) -> h.add(k, v.getBytes(StandardCharsets.UTF_8)));
      h.add("apicurio.value.globalId", ByteBuffer.allocate(Long.BYTES).putLong(globalId(m.topic())).array());
      pending.add(producer.send(new ProducerRecord<>(m.topic(), null, m.key(), m.payload(), h)));
    }
    producer.flush();
    for (Future<RecordMetadata> f : pending) {
      f.get(15, TimeUnit.SECONDS);
    }
  }

  long globalId(String topic) throws Exception {
    Long cached = globalIds.get(topic);
    if (cached != null) {
      return cached;
    }
    HttpRequest request = HttpRequest.newBuilder(URI.create(registryUrl + "/groups/default/artifacts/" + topic
        + "-value/versions/branch=latest")).timeout(Duration.ofSeconds(5)).GET().build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    Matcher m = GLOBAL_ID.matcher(response.body());
    if (response.statusCode() != 200 || !m.find()) {
      throw new IllegalStateException("Chưa có lược đồ đăng ký cho " + topic + " (HTTP " + response.statusCode() + ")");
    }
    long id = Long.parseLong(m.group(1));
    globalIds.put(topic, id);
    return id;
  }

  @Override
  public void close() {
    producer.close(Duration.ofSeconds(5));
  }
}
