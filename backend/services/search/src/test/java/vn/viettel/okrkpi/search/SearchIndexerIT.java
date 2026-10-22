package vn.viettel.okrkpi.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.search.application.IndexingService;
import vn.viettel.okrkpi.search.application.RebuildScope;
import vn.viettel.okrkpi.search.application.RebuildService;
import vn.viettel.okrkpi.search.domain.FieldPolicy;
import vn.viettel.okrkpi.search.domain.IndexNames;
import vn.viettel.okrkpi.search.infrastructure.OpenSearchHttp;

/**
 * DV-16-01 trên OpenSearch 2.19 (có analysis-icu) và Kafka 3.9 thật: tìm không dấu và đồng nghĩa,
 * sự kiện cũ đến muộn không ghi đè, cách ly tenant pool qua bí danh, chỉ mục riêng cho silo, không
 * có trường C2, thư chết, dựng lại chỉ mục và hoán bí danh khi đang truy vấn, độ trễ tới lúc tìm
 * được.
 */
@Testcontainers
@SpringBootTest(
    properties = {
      "mission.search.payload-format=avro-binary",
      "mission.search.replicas=0",
      "mission.search.default-model=POOL",
      "mission.search.concurrency=2"
    })
class SearchIndexerIT {

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(
          DockerImageName.parse("mirror.gcr.io/apache/kafka:3.9.1")
              .asCompatibleSubstituteFor("apache/kafka"));

  @Container static final GenericContainer<?> OPENSEARCH = OpenSearchTestContainer.create();

  private static KafkaProducer<String, byte[]> producer;
  private static OpenSearchHttp os;
  private static final ObjectMapper JSON = new ObjectMapper();

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add(
        "mission.search.opensearch-url",
        () -> "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
  }

  @BeforeAll
  static void topics() throws Exception {
    try (AdminClient admin =
        AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      List<NewTopic> topics = new ArrayList<>();
      for (String t :
          List.of(
              EventTopics.EVT_04_NHIEM_VU_DA_GIAO,
              EventTopics.EVT_06_TRANG_THAI_NHIEM_VU_DOI,
              EventTopics.EVT_24_VAN_BAN_CHI_DAO_DA_NHAN,
              EventTopics.SYS_TENANT_PROVISIONED_V1)) {
        topics.add(new NewTopic(t, 3, (short) 1));
      }
      admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
    }
    producer =
        new KafkaProducer<>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ProducerConfig.LINGER_MS_CONFIG,
                5),
            new StringSerializer(),
            new ByteArraySerializer());
    os =
        new OpenSearchHttp(
            "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200),
            null,
            null,
            Duration.ofSeconds(10),
            JSON);
  }

  @AfterAll
  static void close() {
    producer.close();
  }

  @Autowired RebuildService rebuild;

  @Autowired MeterRegistry meters;

  private static void send(ProducerRecord<String, byte[]> r) {
    try {
      producer.send(r).get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode search(String tenant, Object query) {
    OpenSearchHttp.Response r =
        os.send(
            "POST",
            "/" + IndexNames.tenantAlias(tenant) + "/_search",
            Map.of("query", query, "size", 100));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    return r.body().path("hits").path("hits");
  }

  private static JsonNode match(String tenant, String field, String text) {
    return search(tenant, Map.of("match", Map.of(field, text)));
  }

  private static JsonNode doc(String tenant, String id) {
    JsonNode hits = search(tenant, Map.of("ids", Map.of("values", List.of(id))));
    return hits.size() == 0 ? null : hits.get(0).path("_source");
  }

  @Test
  void timKhongDauVaDongNghia() {
    String tenant = UUID.randomUUID().toString();
    UUID t1 = UUID.randomUUID();
    UUID t2 = UUID.randomUUID();
    send(
        Events.record(
            tenant, Events.assigned(t1, "Báo cáo kết quả giáo dục chính trị năm 2026", 1, false)));
    send(Events.record(tenant, Events.assigned(t2, "Rà soát nhiệm vụ trọng tâm quý IV", 1, false)));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(match(tenant, "title", "giao duc")).hasSize(1);
              assertThat(match(tenant, "title", "GIÁO DỤC chinh tri").get(0).path("_id").asText())
                  .isEqualTo("TASK:" + t1);
              // Đồng nghĩa nghiệp vụ: "công việc" khớp "nhiệm vụ".
              assertThat(match(tenant, "title", "cong viec trong tam").get(0).path("_id").asText())
                  .isEqualTo("TASK:" + t2);
            });
  }

  @Test
  void suKienCuDenMuonKhongGhiDe() {
    String tenant = UUID.randomUUID().toString();
    UUID task = UUID.randomUUID();
    UUID docId = UUID.randomUUID();
    // Trạng thái (v7) tới trước giao việc (v6): hai nhóm trường độc lập, cả hai đều được ghi.
    send(Events.record(tenant, Events.status(task, "DANG_THUC_HIEN", 7)));
    send(Events.record(tenant, Events.assigned(task, "Tiêu đề mới", 6, false)));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              JsonNode d = doc(tenant, "TASK:" + task);
              assertThat(d).isNotNull();
              assertThat(d.path("title").asText()).isEqualTo("Tiêu đề mới");
              assertThat(d.path("status").asText()).isEqualTo("DANG_THUC_HIEN");
            });
    send(Events.record(tenant, Events.assigned(task, "Tiêu đề cũ", 3, false)));
    send(Events.record(tenant, Events.status(task, "DA_GIAO", 4)));
    send(Events.record(tenant, Events.document(docId, "Chỉ thị về chuyển đổi số bản sửa", 2)));
    send(Events.record(tenant, Events.document(docId, "Chỉ thị về chuyển đổi số bản đầu", 1)));
    UUID marker = UUID.randomUUID();
    send(Events.record(tenant, Events.status(task, "CHO_NGHIEM_THU", 8)));
    send(Events.record(tenant, Events.assigned(marker, "Đánh dấu", 1, false)));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(doc(tenant, "TASK:" + marker)).isNotNull();
              JsonNode d = doc(tenant, "TASK:" + task);
              assertThat(d.path("status").asText()).isEqualTo("CHO_NGHIEM_THU");
              assertThat(doc(tenant, "SOURCE_DOC:" + docId)).isNotNull();
            });
    JsonNode d = doc(tenant, "TASK:" + task);
    assertThat(d.path("title").asText()).isEqualTo("Tiêu đề mới");
    assertThat(doc(tenant, "SOURCE_DOC:" + docId).path("summary").asText())
        .isEqualTo("Chỉ thị về chuyển đổi số bản sửa");
  }

  @Test
  void tenantPoolChiThayTaiLieuCuaMinhVaSiloCoChiMucRieng() {
    String a = UUID.randomUUID().toString();
    String b = UUID.randomUUID().toString();
    String silo = UUID.randomUUID().toString();
    send(Events.record(silo, Events.tenant("SILO")));
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> os.send("GET", "/_alias/" + IndexNames.tenantAlias(silo), null).ok());
    send(
        Events.record(
            a, Events.assigned(UUID.randomUUID(), "Kế hoạch kiểm tra giám sát", 1, false)));
    send(
        Events.record(
            b, Events.assigned(UUID.randomUUID(), "Kế hoạch kiểm tra giám sát", 1, false)));
    send(
        Events.record(
            silo, Events.assigned(UUID.randomUUID(), "Kế hoạch kiểm tra giám sát", 1, false)));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(match(a, "title", "kiem tra giam sat")).hasSize(1);
              assertThat(match(b, "title", "kiem tra giam sat")).hasSize(1);
              assertThat(match(silo, "title", "kiem tra giam sat")).hasSize(1);
            });
    assertThat(
            match(a, "title", "kiem tra giam sat")
                .get(0)
                .path("_source")
                .path("tenant_id")
                .asText())
        .isEqualTo(a);
    assertThat(match(a, "title", "kiem tra giam sat").get(0).path("_index").asText())
        .startsWith("pool_task_v");
    assertThat(match(silo, "title", "kiem tra giam sat").get(0).path("_index").asText())
        .isEqualTo(IndexNames.tenantIndex(silo, 1));
    // Truy vấn tường minh tenant khác qua bí danh của mình vẫn không thấy gì: bộ lọc bí danh áp sau
    // truy vấn.
    assertThat(search(a, Map.of("term", Map.of("tenant_id", b)))).isEmpty();
  }

  @Test
  void taiLieuKhongChuaTruongC2VaNhiemVuMatKhongCoTieuDe() {
    String tenant = UUID.randomUUID().toString();
    UUID task = UUID.randomUUID();
    UUID secret = UUID.randomUUID();
    UUID docId = UUID.randomUUID();
    send(Events.record(tenant, Events.assigned(task, "Nhiệm vụ công khai", 1, false)));
    send(Events.record(tenant, Events.status(task, "DANG_THUC_HIEN", 2)));
    send(Events.record(tenant, Events.assigned(secret, "Nhiệm vụ hạn chế", 1, true)));
    send(Events.record(tenant, Events.document(docId, "Thông báo kết luận", 1)));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(doc(tenant, "TASK:" + task)).isNotNull();
              assertThat(doc(tenant, "TASK:" + task).has("status")).isTrue();
              assertThat(doc(tenant, "TASK:" + secret)).isNotNull();
              assertThat(doc(tenant, "SOURCE_DOC:" + docId)).isNotNull();
            });
    for (String id : List.of("TASK:" + task, "TASK:" + secret, "SOURCE_DOC:" + docId)) {
      JsonNode d = doc(tenant, id);
      d.fieldNames()
          .forEachRemaining(
              f ->
                  assertThat(
                          FieldPolicy.allowed(
                              vn.viettel.okrkpi.search.domain.DocType.valueOf(
                                  d.path("doc_type").asText()),
                              f))
                      .as(f)
                      .isTrue());
      assertThat(d.toString())
          .doesNotContain(
              "changedBy",
              "changed_by",
              "reason",
              "R01",
              "connector",
              "VOFFICE",
              "EXT-1",
              "secret-key");
    }
    JsonNode s = doc(tenant, "TASK:" + secret);
    assertThat(s.has("title")).isFalse();
    assertThat(s.path("is_restricted").asBoolean()).isTrue();
    // Mapping strict: cụm từ chối trường lạ, kể cả khi ghi trực tiếp không qua indexer.
    OpenSearchHttp.Response r =
        os.send(
            "POST",
            "/" + IndexNames.tenantAlias(tenant) + "/_doc?require_alias=true",
            Map.of("tenant_id", tenant, "national_id", "001203004567"));
    assertThat(r.status()).isEqualTo(400);
  }

  @Test
  void thongDiepHongSangThuChet() {
    String tenant = UUID.randomUUID().toString();
    var bad =
        new ProducerRecord<String, byte[]>(
            EventTopics.EVT_04_NHIEM_VU_DA_GIAO, "x", new byte[] {1, 2, 3});
    bad.headers().add("tenantid", tenant.getBytes());
    send(bad);
    String dlq = EventTopics.EVT_04_NHIEM_VU_DA_GIAO + ".dlq.search-indexer";
    try (KafkaConsumer<String, byte[]> c =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "dlq-check-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest"),
            new StringDeserializer(),
            new ByteArrayDeserializer())) {
      c.subscribe(List.of(dlq));
      List<String> tenants = new ArrayList<>();
      await()
          .atMost(Duration.ofSeconds(60))
          .until(
              () -> {
                c.poll(Duration.ofMillis(500))
                    .forEach(
                        rec ->
                            tenants.add(new String(rec.headers().lastHeader("tenantid").value())));
                return tenants.contains(tenant);
              });
    }
  }

  @Test
  void dungLaiChiMucVaHoanBiDanhKhongLamTruyVanLoi() throws Exception {
    String tenant = UUID.randomUUID().toString();
    List<UUID> tasks = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      UUID t = UUID.randomUUID();
      tasks.add(t);
      producer.send(
          Events.record(
              tenant,
              Events.assigned(t, "Tổ chức hội nghị giáo dục pháp luật lần " + i, 1, false)));
    }
    producer.flush();
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> match(tenant, "title", "giao duc phap luat").size() == 50);
    String before = match(tenant, "title", "giao duc").get(0).path("_index").asText();

    AtomicBoolean running = new AtomicBoolean(true);
    AtomicInteger queries = new AtomicInteger();
    List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());
    Thread reader =
        Thread.ofVirtual()
            .start(
                () -> {
                  while (running.get()) {
                    OpenSearchHttp.Response r =
                        os.send(
                            "POST",
                            "/" + IndexNames.tenantAlias(tenant) + "/_search",
                            Map.of(
                                "query",
                                Map.of("match", Map.of("title", "giao duc phap luat")),
                                "size",
                                100));
                    queries.incrementAndGet();
                    if (r.status() != 200 || r.body().path("hits").path("hits").size() != 50) {
                      errors.add(r.status() + " " + r.body().path("hits").path("hits").size());
                    }
                  }
                });
    RebuildService.Report report = rebuild.rebuild(RebuildScope.POOL);
    // Sự kiện mới sau khi hoán đi vào chỉ mục mới qua bí danh.
    UUID after = UUID.randomUUID();
    send(Events.record(tenant, Events.assigned(after, "Sau khi dựng lại", 1, false)));
    await().atMost(Duration.ofSeconds(30)).until(() -> doc(tenant, "TASK:" + after) != null);
    running.set(false);
    reader.join();

    assertThat(report.oldIndex()).isEqualTo(before);
    assertThat(report.newIndex()).isEqualTo(IndexNames.next(before));
    assertThat(report.failures()).isZero();
    assertThat(queries.get()).isGreaterThan(5);
    assertThat(errors).isEmpty();
    assertThat(match(tenant, "title", "giao duc phap luat").get(0).path("_index").asText())
        .isEqualTo(report.newIndex());
    assertThat(
            os.send("GET", "/_alias/" + IndexNames.POOL_ALIAS, null).body().has(report.newIndex()))
        .isTrue();
    assertThat(doc(tenant, "TASK:" + after).path("title").asText()).isEqualTo("Sau khi dựng lại");
    Set<String> found = new HashSet<>();
    match(tenant, "title", "giao duc phap luat")
        .forEach(h -> found.add(h.path("_source").path("entity_id").asText()));
    assertThat(found).hasSize(50).contains(tasks.get(0).toString(), tasks.get(49).toString());
  }

  @Test
  void doTreTuLucPhatToiLucTimDuoc() {
    String tenant = UUID.randomUUID().toString();
    int n = 2000;
    List<Long> visible = new ArrayList<>();
    long start = System.nanoTime();
    for (int i = 0; i < n; i++) {
      producer.send(
          Events.record(
              tenant,
              Events.assigned(UUID.randomUUID(), "Đo độ trễ chỉ mục " + i, 1, false),
              Instant.now()));
    }
    producer.flush();
    long sent = System.nanoTime();
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(100))
        .until(
            () -> {
              OpenSearchHttp.Response r =
                  os.send(
                      "POST",
                      "/" + IndexNames.tenantAlias(tenant) + "/_count",
                      Map.of("query", Map.of("match_all", Map.of())));
              return r.body().path("count").asInt() == n;
            });
    visible.add(System.nanoTime() - sent);
    Timer lag = meters.find(IndexingService.LAG_METRIC).timer();
    assertThat(lag).isNotNull();
    double p99 = 0;
    for (ValueAtPercentile v : lag.takeSnapshot().percentileValues()) {
      if (v.percentile() == 0.99) {
        p99 = v.value(TimeUnit.MILLISECONDS);
      }
    }
    long allVisibleMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    org.slf4j.LoggerFactory.getLogger(SearchIndexerIT.class)
        .info(
            "Độ trễ DV-16-01: {} sự kiện, gửi xong sau {} ms, tìm được hết sau {} ms kể từ lúc bắt đầu gửi; p99 độ trễ ghi "
                + "chỉ mục {} ms (cộng tối đa 1 s làm mới)",
            n,
            TimeUnit.NANOSECONDS.toMillis(sent - start),
            allVisibleMs,
            p99);
    assertThat(p99 + 1000).isLessThan(10_000);
    assertThat(allVisibleMs).isLessThan(10_000 + TimeUnit.NANOSECONDS.toMillis(sent - start));
  }
}
