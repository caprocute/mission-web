package vn.viettel.okrkpi.oversight.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.appraisal.api.ProfileParticipantsApi;
import vn.viettel.okrkpi.appraisal.infrastructure.persistence.JdbcProfileParticipants;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.oversight.api.AnomalyAlertQueryApi;
import vn.viettel.okrkpi.oversight.domain.alert.AnomalyAlert;
import vn.viettel.okrkpi.oversight.infrastructure.persistence.JdbcAnomalyAlertRepository;
import vn.viettel.okrkpi.oversight.infrastructure.web.OversightCaller;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * CORE-07-11 trên PostgreSQL thật, phản hồi đối chiếu {@code oversight.yaml}: đối tượng không thấy
 * cảnh báo trong danh sách và chi tiết (BR-14-03); hai lần GET đầu tiên chỉ một lần chuyển {@code
 * DANG_XEM}, ETag không đổi; ghi chú 19 ký tự 422; AC-07 kết luận 403; giao kiểm tra rồi thanh tra
 * kết luận không vi phạm thì đóng và gỡ cờ chặn; thiếu {@code If-Match} 428.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {
      "mission.outbox.relay.enabled=false",
      "mission.oversight.anomaly-digest.enabled=false"
    })
@AutoConfigureMockMvc(addFilters = false)
@Import(AnomalyAlertApiIT.Beans.class)
@DirtiesContext
class AnomalyAlertApiIT extends ContractConformanceTest {

  private static final String BASE = "/api/v1/oversight/alerts";
  private static final String NOTE =
      "Đề nghị Uỷ ban kiểm tra xác minh việc dùng lại cùng một tệp minh chứng.";
  static final UUID PROFILE = UUID.randomUUID();
  static final UUID SUBJECT_PERSON = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();

  /** Người gọi, đối tượng của hồ sơ và bộ tuần tự outbox kiểm thử. */
  static class Beans {
    static volatile UUID user;
    static volatile List<String> roles = List.of("appraiser");

    @Bean
    OversightCaller alertTestCaller() {
      return new OversightCaller() {
        @Override
        public Optional<UUID> currentUserId() {
          return Optional.of(user);
        }

        @Override
        public List<String> roles() {
          return roles;
        }

        @Override
        public Optional<String> acr() {
          return Optional.of("aal2");
        }

        @Override
        public Optional<UUID> unitId() {
          return Optional.of(UNIT);
        }
      };
    }

    @Bean
    @Primary
    ProfileParticipantsApi alertTestParticipants() {
      return new ProfileParticipantsApi() {
        @Override
        public Set<UUID> reviewersOf(TenantId t, PeriodId p, Collection<UUID> ids) {
          return Set.of();
        }

        @Override
        public Set<UUID> reviewersOfUnits(TenantId t, PeriodId p, Collection<UUID> ids) {
          return Set.of();
        }

        @Override
        public Map<UUID, ProfileSubject> subjectsOf(TenantId t, Collection<UUID> ids) {
          return ids.contains(PROFILE)
              ? Map.of(PROFILE, new ProfileSubject(SUBJECT_PERSON, UNIT))
              : Map.of();
        }
      };
    }

    @Bean
    @Primary
    OutboxPayloadSerializer alertTestSerializer() {
      return (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1);
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private JdbcAnomalyAlertRepository repository;
  @Autowired private AnomalyAlertQueryApi queries;
  @Autowired private JdbcProfileParticipants realParticipants;

  @Autowired
  @Qualifier("oversightDataSource") private DataSource dataSource;

  @Autowired
  @Qualifier("oversightTransactionManager") private PlatformTransactionManager tm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();
  private final UUID appraiser = UUID.randomUUID();
  private final UUID inspector = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.OVERSIGHT;
  }

  @Test
  void hiddenFromSubjectViewedOnceSeparationAndClose() throws Exception {
    UUID evidence = UUID.randomUUID();
    AnomalyAlert unit = seed("DON_VI", UNIT, List.of("ky:" + period, "evidence:" + evidence));
    AnomalyAlert own =
        seed("CA_NHAN", PROFILE, List.of("ky:" + period, "task:" + UUID.randomUUID()));

    // Đối tượng của hồ sơ (cùng đơn vị, cũng là người thẩm định) không thấy cảnh báo của mình.
    as(SUBJECT_PERSON, "appraiser");
    MvcResult mine = perform(get(BASE));
    assertThat(status(mine)).as(text(mine)).isEqualTo(200);
    assertResponseConforms(mine, "/oversight/alerts", PathItem.HttpMethod.GET);
    assertThat(body(mine).get("items"))
        .extracting(n -> n.get("id").asText())
        .containsExactly(unit.id().toString());
    assertThat(status(perform(get(BASE + "/" + own.id())))).isEqualTo(404);

    as(appraiser, "appraiser");
    JsonNode list = body(perform(get(BASE + "?severity=CAO&status=MOI")));
    assertThat(list.get("items")).hasSize(2);
    assertThat(list.at("/counts/newCount").asInt()).isEqualTo(2);
    assertThat(status(perform(get(BASE + "?severity=HIGH")))).isEqualTo(422);

    // Hai lần mở đầu: một lần chuyển DANG_XEM, một bản ghi kiểm toán, ETag giữ nguyên.
    int audits = audits();
    MvcResult first = perform(get(BASE + "/" + unit.id()));
    MvcResult second = perform(get(BASE + "/" + unit.id()));
    assertThat(status(first)).as(text(first)).isEqualTo(200);
    assertResponseConforms(first, "/oversight/alerts/{id}", PathItem.HttpMethod.GET);
    assertThat(body(first).get("status").asText()).isEqualTo("DANG_XEM");
    assertThat(body(second).get("status").asText()).isEqualTo("DANG_XEM");
    assertThat(second.getResponse().getHeader("ETag"))
        .isEqualTo(first.getResponse().getHeader("ETag"))
        .isEqualTo("\"0\"");
    assertThat(audits()).isEqualTo(audits + 1);
    assertThat(body(first).at("/evidenceChain/0/kind").asText()).isEqualTo("MINH_CHUNG");
    assertThat(body(first).at("/actions/0/action").asText()).isEqualTo("TU_DONG");
    assertThat(body(first).at("/actions/0/active").asBoolean()).isTrue();

    String path = BASE + "/" + unit.id() + "/actions";
    MvcResult shortNote =
        perform(write(post(path), action("GHI_CHU", "Mười chín ký tự đây", null, null), "\"0\""));
    assertThat(status(shortNote)).as(text(shortNote)).isEqualTo(422);
    assertResponseConforms(shortNote, "/oversight/alerts/{id}/actions", PathItem.HttpMethod.POST);

    MvcResult conclude =
        perform(write(post(path), action("KET_LUAN", NOTE, null, "CO_VI_PHAM"), "\"0\""));
    assertThat(status(conclude)).as(text(conclude)).isEqualTo(403);
    assertThat(body(conclude).get("code").asText()).isEqualTo("OVERSIGHT-403-SEPARATION-OF-DUTIES");
    assertResponseConforms(conclude, "/oversight/alerts/{id}/actions", PathItem.HttpMethod.POST);

    assertThat(status(perform(write(post(path), action("GHI_CHU", NOTE, null, null), null))))
        .isEqualTo(428);

    MvcResult assigned =
        perform(write(post(path), action("GIAO_KIEM_TRA", NOTE, inspector, null), "\"0\""));
    assertThat(status(assigned)).as(text(assigned)).isEqualTo(200);
    assertResponseConforms(assigned, "/oversight/alerts/{id}/actions", PathItem.HttpMethod.POST);
    assertThat(body(assigned).get("status").asText()).isEqualTo("DA_GIAO_KIEM_TRA");
    assertThat(assigned.getResponse().getHeader("ETag")).isEqualTo("\"1\"");

    as(inspector, "inspector");
    MvcResult done =
        perform(write(post(path), action("KET_LUAN", NOTE, null, "KHONG_VI_PHAM"), "\"1\""));
    assertThat(status(done)).as(text(done)).isEqualTo(200);
    assertThat(body(done).get("status").asText()).isEqualTo("DONG");
    assertThat(body(done).get("actions")).hasSize(3);
    assertThat(TenantContext.call(tenant, () -> queries.blockedEvidence(tenant, List.of(evidence))))
        .isEmpty();
    MvcResult stale = perform(write(post(path), action("GHI_CHU", NOTE, null, null), "\"1\""));
    assertThat(status(stale)).as(text(stale)).isIn(409, 412);

    // Truy vấn cán bộ, đơn vị của hồ sơ chạy được trên schema appraisal.
    assertThat(
            TenantContext.call(
                tenant, () -> realParticipants.subjectsOf(tenant, List.of(UUID.randomUUID()))))
        .isEmpty();
  }

  private AnomalyAlert seed(String type, UUID subject, List<String> evidence) throws Exception {
    AnomalyAlert a =
        new AnomalyAlert(
            UUID.randomUUID(),
            period,
            20264,
            "MINH_CHUNG_TRUNG",
            "CAO",
            type,
            subject,
            evidence,
            Instant.now(),
            UUID.randomUUID(),
            "2026.10.3",
            "MOI",
            0);
    UUID actor = new UUID(0, 0);
    in(
        jdbc -> {
          for (String t : List.of("anomaly_alert", "alert_action")) {
            jdbc.sql("SELECT oversight.fn_create_period_partitions(?::regclass, ?)")
                .params("oversight." + t, 20264)
                .query()
                .listOfRows();
          }
          repository.insert(tenant, a, actor);
          repository.insertAction(
              tenant,
              a,
              "TU_DONG",
              "Tạm chưa tính điểm minh chứng liên quan.",
              true,
              actor,
              Instant.now());
          return null;
        });
    return a;
  }

  private int audits() throws Exception {
    return in(
        jdbc ->
            jdbc.sql("SELECT count(*) FROM oversight.outbox_event WHERE topic = 'sys.audit.v1'")
                .query(Integer.class)
                .single());
  }

  private String action(String type, String note, UUID assignedTo, String verdict)
      throws Exception {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("type", type);
    m.put("note", note);
    if (assignedTo != null) {
      m.put("assignedTo", assignedTo.toString());
    }
    if (verdict != null) {
      m.put("verdict", verdict);
    }
    return json.writeValueAsString(m);
  }

  private <T> T in(Function<JdbcClient, T> work) throws Exception {
    return TenantContext.call(
        tenant,
        () -> new TransactionTemplate(tm).execute(s -> work.apply(JdbcClient.create(dataSource))));
  }

  private static void as(UUID user, String role) {
    Beans.user = user;
    Beans.roles = List.of(role);
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private static MockHttpServletRequestBuilder write(
      MockHttpServletRequestBuilder b, String body, String ifMatch) {
    b.header("Idempotency-Key", UUID.randomUUID().toString());
    if (ifMatch != null) {
      b.header("If-Match", ifMatch);
    }
    return b.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(text(r));
  }

  private static String text(MvcResult r) throws Exception {
    return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private static int status(MvcResult r) {
    return r.getResponse().getStatus();
  }
}
