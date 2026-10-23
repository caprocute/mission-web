package vn.viettel.okrkpi.config.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.application.PeriodConfigService;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.config.infrastructure.persistence.JdbcPeriodRepository;
import vn.viettel.okrkpi.config.infrastructure.persistence.ModulePeriodPartitions;
import vn.viettel.okrkpi.config.infrastructure.persistence.OutboxPeriodEvents;
import vn.viettel.okrkpi.config.infrastructure.web.PeriodCaller;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.datasource.ModuleName;
import vn.viettel.okrkpi.shared.datasource.ReadWriteRoutingDataSource;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-08-02 trên PostgreSQL thật: {@code createPeriod}, {@code listPeriods},
 * {@code getPeriod} khớp {@code config.yaml}; chồng lấn trả 409; phân trang con trỏ; gửi lại cùng
 * Idempotency-Key trả cùng kỳ; chuyển trạng thái ghi nhật ký và outbox cùng giao dịch; {@code
 * PeriodApi} đọc thẳng CSDL.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.server.port=0", "mission.config.period-opening.enabled=false"})
@AutoConfigureMockMvc(addFilters = false)
class PeriodApiIT extends ContractConformanceTest {

  /** mission-core chưa nối xác thực; kiểm thử thay bộ trích chủ thể bằng một người dùng cố định. */
  @TestConfiguration
  static class CallerConfig {
    static final UUID ACTOR = UUID.randomUUID();

    /** Người gọi hiện tại; IT khoá kỳ đổi tạm để thử tách vai trò, rồi trả về {@link #ACTOR}. */
    static volatile UUID current = ACTOR;

    static volatile String acr;
    static volatile Long authTime;

    /** Vai trò của ngữ cảnh đang chọn (IT quản trị vai trò CORE-08-16). */
    static volatile java.util.List<String> roles = java.util.List.of();

    static void reset() {
      current = ACTOR;
      acr = null;
      authTime = null;
      roles = java.util.List.of();
    }

    @Bean
    PeriodCaller periodCaller() {
      return new PeriodCaller() {
        @Override
        public Optional<UUID> currentUserId() {
          return Optional.of(current);
        }

        @Override
        public Optional<String> acr() {
          return Optional.ofNullable(acr);
        }

        @Override
        public Optional<Long> authTime() {
          return Optional.ofNullable(authTime);
        }

        @Override
        public java.util.List<String> roles() {
          return roles;
        }
      };
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private PeriodApi periodApi;
  @Autowired private JdbcPeriodRepository repository;

  @Autowired
  @Qualifier("configDataSource") private DataSource dataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager tm;

  @Autowired
  @Qualifier("moduleDataSources") private Map<ModuleName, ReadWriteRoutingDataSource> moduleDataSources;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID actor = CallerConfig.ACTOR;

  @Override
  protected ContractModule contractModule() {
    return ContractModule.CONFIG;
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return as(() -> mvc.perform(request).andReturn());
  }

  private <T> T as(Callable<T> work) throws Exception {
    return TenantContext.call(tenant, work);
  }

  private MockHttpServletRequestBuilder create(String key, String body) {
    return post("/api/v1/periods")
        .header("Idempotency-Key", key)
        .principal(actor::toString)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static String quarter(String code, String start, String end) {
    return """
        {"code":"%s","name":"Kỳ %s","kind":"QUY","flow":"CA_HAI","startDate":"%s","endDate":"%s"}
        """
        .formatted(code, code, start, end);
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  /**
   * CORE-08-02: tạo kỳ thì mọi bảng phân vùng LIST theo {@code period_bucket} của tám schema có
   * phân vùng cho quý của kỳ (không có phân vùng mặc định, thiếu thì lệnh ghi theo kỳ lỗi 500). Tạo
   * kỳ khác cùng quý và gọi lại bộ tạo phân vùng không lỗi.
   */
  @Test
  void taoKyTaoPhanVungChoMoiBangTheoKyCuaCacMoDun() throws Exception {
    int bucket = 20933;
    List<String> tables = periodTables();
    assertThat(tables)
        .hasSizeGreaterThan(10)
        .anyMatch(t -> t.startsWith("okr."))
        .anyMatch(t -> t.startsWith("task."))
        .anyMatch(t -> t.startsWith("appraisal."));
    assertThat(missingPartitions(bucket)).isNotEmpty();

    MvcResult created =
        perform(
            create(UUID.randomUUID().toString(), quarter("2093-Q3", "2093-07-01", "2093-09-30")));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertThat(body(created).get("periodBucket").asInt()).isEqualTo(bucket);
    assertThat(missingPartitions(bucket)).isEmpty();

    // Kỳ tháng cùng quý dùng lại phân vùng; bộ tạo mới (không nhớ đệm) gọi lại không lỗi.
    MvcResult month =
        perform(
            create(
                UUID.randomUUID().toString(),
                """
                {"code":"2093-08","name":"Tháng 8/2093","kind":"THANG","flow":"CA_HAI",
                 "startDate":"2093-08-01","endDate":"2093-08-31"}
                """));
    assertThat(month.getResponse().getStatus()).isEqualTo(201);
    new ModulePeriodPartitions(moduleDataSources).ensure(bucket);
    assertThat(missingPartitions(bucket)).isEmpty();
  }

  private List<String> periodTables() {
    return new JdbcTemplate(dataSource)
        .queryForList(PERIOD_TABLES, String.class, (Object) schemas());
  }

  /** Bảng theo kỳ của tám schema chưa có phân vùng {@code <bảng>_p<bucket>}. */
  private List<String> missingPartitions(int bucket) {
    return new JdbcTemplate(dataSource)
        .queryForList(
            PERIOD_TABLES
                + " AND NOT EXISTS (SELECT 1 FROM pg_class p WHERE p.relnamespace = c.relnamespace"
                + " AND p.relname = c.relname || '_p' || ?::int)",
            String.class,
            schemas(),
            bucket);
  }

  private static String[] schemas() {
    return Arrays.stream(ModuleName.values()).map(ModuleName::schema).toArray(String[]::new);
  }

  private static final String PERIOD_TABLES =
      "SELECT c.oid::regclass::text FROM pg_partitioned_table pt"
          + " JOIN pg_class c ON c.oid = pt.partrelid"
          + " JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = pt.partattrs[0]"
          + " WHERE n.nspname = ANY (?) AND NOT c.relispartition AND pt.partstrat = 'l'"
          + " AND a.attname = 'period_bucket'";

  @Test
  void createGetListConformToContractAndHandleOverlapPagingAndIdempotency() throws Exception {
    String key = UUID.randomUUID().toString();
    MvcResult created = perform(create(key, quarter("2027-Q1", "2027-01-01", "2027-03-31")));
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    assertResponseConforms(created, "/periods", PathItem.HttpMethod.POST);
    JsonNode period = body(created);
    String id = period.get("id").asText();
    assertThat(created.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    assertThat(created.getResponse().getHeader("Location")).endsWith("/api/v1/periods/" + id);
    assertThat(period.get("periodBucket").asInt()).isEqualTo(20271);
    assertThat(period.get("planDeadline").asText()).isEqualTo("2027-01-08");
    assertThat(period.get("milestones").get("plannedLockDate").asText()).isEqualTo("2027-04-05");
    assertThat(period.get("status").asText()).isEqualTo("MO");

    // Gửi lại cùng khoá: cùng kỳ, không tạo dòng mới.
    MvcResult replay = perform(create(key, quarter("2027-Q1", "2027-01-01", "2027-03-31")));
    assertThat(replay.getResponse().getStatus()).isEqualTo(201);
    assertThat(body(replay).get("id").asText()).isEqualTo(id);
    assertThat(countPeriods()).isEqualTo(1);

    // Chồng lấn cùng loại, cùng luồng.
    MvcResult overlap =
        perform(
            create(UUID.randomUUID().toString(), quarter("2027-X", "2027-03-01", "2027-05-31")));
    assertThat(overlap.getResponse().getStatus()).isEqualTo(409);
    assertThat(body(overlap).get("code").asText()).isEqualTo("PERIOD-409-OVERLAP");

    MvcResult invalid =
        perform(
            create(UUID.randomUUID().toString(), quarter("2027-Y", "2027-09-30", "2027-07-01")));
    assertThat(invalid.getResponse().getStatus()).isEqualTo(422);

    perform(create(UUID.randomUUID().toString(), quarter("2027-Q2", "2027-04-01", "2027-06-30")));
    perform(create(UUID.randomUUID().toString(), quarter("2027-Q3", "2027-07-01", "2027-09-30")));

    MvcResult got = perform(get("/api/v1/periods/" + id));
    assertThat(got.getResponse().getStatus()).isEqualTo(200);
    assertThat(got.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    assertResponseConforms(got, "/periods/{id}", PathItem.HttpMethod.GET);
    assertThat(perform(get("/api/v1/periods/" + UUID.randomUUID())).getResponse().getStatus())
        .isEqualTo(404);

    MvcResult page1 = perform(get("/api/v1/periods").param("limit", "2").param("year", "2027"));
    assertThat(page1.getResponse().getStatus()).isEqualTo(200);
    assertResponseConforms(page1, "/periods", PathItem.HttpMethod.GET);
    JsonNode p1 = body(page1);
    assertThat(p1.get("items"))
        .extracting(n -> n.get("code").asText())
        .containsExactly("2027-Q3", "2027-Q2");
    String cursor = p1.get("nextCursor").asText();
    JsonNode p2 =
        body(
            perform(
                get("/api/v1/periods")
                    .param("limit", "2")
                    .param("year", "2027")
                    .param("cursor", cursor)));
    assertThat(p2.get("items")).extracting(n -> n.get("code").asText()).containsExactly("2027-Q1");
    assertThat(p2.get("nextCursor").isNull()).isTrue();
    assertThat(perform(get("/api/v1/periods").param("limit", "201")).getResponse().getStatus())
        .isEqualTo(400);

    // Người khác tenant không thấy kỳ.
    TenantId other = TenantId.of(UUID.randomUUID());
    assertThat(TenantContext.call(other, () -> periodApi.findById(other, PeriodId.parse(id))))
        .isEmpty();
  }

  @Test
  void transitionsWriteStatusLogAndOutboxInOneTransaction() throws Exception {
    String id =
        body(perform(
                create(
                    UUID.randomUUID().toString(), quarter("2026-Q4", "2026-10-01", "2026-12-31"))))
            .get("id")
            .asText();
    PeriodId periodId = PeriodId.parse(id);
    assertThat(as(() -> periodApi.current(tenant, "QUY").id())).isEqualTo(periodId);

    OutboxWriter writer =
        new OutboxWriter(
            new JdbcTemplate(dataSource),
            "config",
            (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1),
            json,
            Clock.systemUtc());
    PeriodConfigService service =
        new PeriodConfigService(
            repository, new OutboxPeriodEvents(writer), e -> {}, null, Clock.systemUtc());
    TransactionTemplate tx = new TransactionTemplate(tm);
    UUID saga = UUID.randomUUID();
    UUID order = UUID.randomUUID();
    TenantContext.run(
        tenant,
        () ->
            tx.executeWithoutResult(
                s -> {
                  service.transition(
                      tenant, periodId, PeriodState.DANG_KHOA, saga, actor, null, null);
                  service.transition(
                      tenant, periodId, PeriodState.MO, saga, actor, order, "Bù trừ");
                  service.transition(
                      tenant, periodId, PeriodState.DANG_KHOA, saga, actor, null, null);
                }));
    try {
      TenantContext.run(tenant, () -> periodApi.assertWritable(tenant, periodId));
      throw new AssertionError("Kỳ đang khoá phải chặn ghi");
    } catch (PeriodLockedException expected) {
      // đúng
    }
    TenantContext.run(
        tenant,
        () ->
            tx.executeWithoutResult(
                s ->
                    service.transition(
                        tenant, periodId, PeriodState.DA_KHOA, saga, actor, null, null)));

    List<String> log =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT from_status || '>' || to_status || ':' || cause_type"
                            + " FROM config.period_status_log WHERE period_id = ? ORDER BY changed_at, id")
                    .param(periodId.value())
                    .query(String.class)
                    .list());
    assertThat(log)
        .containsExactly(
            "MO>DANG_KHOA:SAGA_CHOT_KY",
            "DANG_KHOA>MO:BU_TRU_SAGA",
            "MO>DANG_KHOA:SAGA_CHOT_KY",
            "DANG_KHOA>DA_KHOA:SAGA_CHOT_KY");
    List<String> topics =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT topic FROM config.outbox_event WHERE aggregate_id = ?"
                            + " ORDER BY aggregate_version")
                    .param(periodId.value())
                    .query(String.class)
                    .list());
    assertThat(topics)
        .containsExactly(
            "period.drain.marker",
            "config.period.unlocked.v1",
            "period.drain.marker",
            "config.period.locked.v1");
    List<String> markers =
        inTenant(
            jdbc ->
                jdbc.sql(
                        "SELECT aggregate_version || ':' || (headers ->> 'classification')"
                            + " FROM config.outbox_event WHERE aggregate_id = ?"
                            + " AND topic = 'period.drain.marker' ORDER BY aggregate_version")
                    .param(periodId.value())
                    .query(String.class)
                    .list());
    assertThat(markers).containsExactly("1:C0", "3:C0");
    JsonNode locked = body(perform(get("/api/v1/periods/" + id)));
    assertThat(locked.get("status").asText()).isEqualTo("DA_KHOA");
    assertThat(locked.get("rowVersion").asInt()).isEqualTo(4);
    assertThat(locked.get("lockSagaId").asText()).isEqualTo(saga.toString());
  }

  private long countPeriods() throws Exception {
    return inTenant(
        jdbc ->
            jdbc.sql("SELECT count(*) FROM config.period WHERE tenant_id = ?")
                .param(tenant.value())
                .query(Long.class)
                .single());
  }

  private <T> T inTenant(java.util.function.Function<JdbcClient, T> query) throws Exception {
    JdbcClient jdbc = JdbcClient.create(dataSource);
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> query.apply(jdbc)));
  }
}
