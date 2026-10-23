package vn.viettel.okrkpi.oversight.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSealer;
import vn.viettel.okrkpi.oversight.infrastructure.persistence.FieldEncryptorComplaintSealer;
import vn.viettel.okrkpi.oversight.infrastructure.web.OversightCaller;
import vn.viettel.okrkpi.shared.crypto.DekCache.CachedDek;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.DekService;
import vn.viettel.okrkpi.shared.crypto.DekStatus;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.crypto.JcaAesGcmCryptoProvider;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer.SerializedPayload;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Kiểm thử tích hợp CORE-07-09 (phần hậu kiểm có bằng chứng sổ cái) trên PostgreSQL thật: người
 * thẩm định lập đợt cho đơn vị trong cây (đơn vị ngoài cây 403); cơ quan kiểm tra lập, xem, danh
 * sách theo trạng thái; đóng cần {@code If-Match} (428, 409 lệch phiên bản), ghi kết luận, phát
 * hiện mã hoá trong {@code findings_enc}, yêu cầu mở lại cùng kỳ và tệp bằng chứng xuất từ sổ cái;
 * gửi lại cùng khoá phát lại; mọi bước ghi kiểm toán vào {@code oversight.outbox_event}; phản hồi
 * khớp hợp đồng {@code oversight.yaml}.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = "mission.outbox.relay.enabled=false")
@AutoConfigureMockMvc(addFilters = false)
@Import(PostAuditApiIT.Beans.class)
@DirtiesContext
class PostAuditApiIT extends ContractConformanceTest {

  private static final String BASE = "/api/v1/oversight/post-audits";
  private static final String FINDINGS =
      "Hồ sơ đơn vị chấm sai hệ số chất lượng của nhiệm vụ số 3.";

  /** Người gọi, bộ mã hoá C2 và bộ tuần tự outbox kiểm thử. */
  static class Beans {
    static volatile UUID user = UUID.randomUUID();
    static volatile List<String> roles = List.of("inspector");
    static volatile UUID unit;

    @Bean
    OversightCaller postAuditTestCaller() {
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
          return Optional.ofNullable(unit);
        }
      };
    }

    @Bean
    @Primary
    ComplaintSealer postAuditTestSealer() {
      DekService deks = Mockito.mock(DekService.class);
      UUID dekId = UUID.randomUUID();
      Mockito.when(deks.getOrCreateActiveDek(any(), any()))
          .thenAnswer(inv -> dek(dekId, inv.getArgument(0)));
      Mockito.when(deks.getDekForDecryption(any(), any()))
          .thenAnswer(inv -> dek(dekId, inv.getArgument(0)));
      return new FieldEncryptorComplaintSealer(
          new FieldEncryptor(deks, new JcaAesGcmCryptoProvider()));
    }

    private static CachedDek dek(UUID dekId, UUID tenant) {
      return new CachedDek(
          dekId,
          tenant,
          DekPurpose.KHIEU_NAI,
          LocalDate.of(2026, 10, 1),
          DekStatus.ACTIVE,
          new byte[32]);
    }

    @Bean
    @Primary
    OutboxPayloadSerializer postAuditTestSerializer() {
      return (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1);
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Autowired
  @Qualifier("oversightDataSource") private DataSource oversightDataSource;

  @Autowired
  @Qualifier("oversightTransactionManager") private PlatformTransactionManager oversightTm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  @Autowired
  @Qualifier("orgReplicaDataSource") private DataSource replicaDataSource;

  @Autowired
  @Qualifier("orgReplicaTransactionManager") private PlatformTransactionManager replicaTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();
  private final UUID parent = UUID.randomUUID();
  private final UUID child = UUID.randomUUID();
  private final UUID elsewhere = UUID.randomUUID();
  private final UUID inspector = UUID.randomUUID();
  private final UUID reopen = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.OVERSIGHT;
  }

  @Test
  void lapXemDanhSachVaDongDotHauKiemCoBangChungSoCai() throws Exception {
    seed();

    // Người thẩm định: đơn vị ngoài cây 403, đơn vị trong cây 201.
    as(UUID.randomUUID(), List.of("appraiser"), parent);
    MvcResult outside = perform(write(post(BASE), create(elsewhere), null));
    assertThat(status(outside)).as(text(outside)).isEqualTo(403);
    MvcResult own = perform(write(post(BASE), create(child), null));
    assertThat(status(own)).as(text(own)).isEqualTo(201);
    assertResponseConforms(own, "/oversight/post-audits", PathItem.HttpMethod.POST);

    // Cơ quan kiểm tra lập đợt; gửi lại cùng khoá phát lại cùng đợt.
    as(inspector, List.of("inspector"), null);
    String key = UUID.randomUUID().toString();
    MvcResult created = perform(write(post(BASE), create(elsewhere), null, key));
    assertThat(status(created)).as(text(created)).isEqualTo(201);
    JsonNode a = body(created);
    String id = a.get("id").asText();
    assertThat(a.get("basis").asText()).isEqualTo("QĐ 12-QĐ/UBKT ngày 05/02/2027");
    assertThat(a.get("findings").isNull()).isTrue();
    assertThat(created.getResponse().getHeader("Location")).isEqualTo(BASE + "/" + id);
    MvcResult replay = perform(write(post(BASE), create(elsewhere), null, key));
    assertThat(body(replay).get("id").asText()).isEqualTo(id);

    MvcResult list = perform(get(BASE).param("period", period.toString()).param("open", "true"));
    assertThat(status(list)).isEqualTo(200);
    assertThat(body(list).get("items")).hasSize(2);
    assertResponseConforms(list, "/oversight/post-audits", PathItem.HttpMethod.GET);

    // Đóng: thiếu If-Match 428, lệch phiên bản 409, yêu cầu mở lại khác kỳ 422.
    String close = close(reopen);
    assertThat(status(perform(write(post(BASE + "/" + id + "/close"), close, null))))
        .isEqualTo(428);
    assertThat(status(perform(write(post(BASE + "/" + id + "/close"), close, "\"5\""))))
        .isEqualTo(409);
    MvcResult badReopen =
        perform(write(post(BASE + "/" + id + "/close"), close(UUID.randomUUID()), "\"0\""));
    assertThat(status(badReopen)).as(text(badReopen)).isEqualTo(422);

    MvcResult closed = perform(write(post(BASE + "/" + id + "/close"), close, "\"0\""));
    assertThat(status(closed)).as(text(closed)).isEqualTo(200);
    assertResponseConforms(closed, "/oversight/post-audits/{id}/close", PathItem.HttpMethod.POST);
    JsonNode c = body(closed);
    assertThat(c.get("conclusion").asText()).isEqualTo("DANH_GIA_LAI");
    assertThat(c.get("findings").asText()).isEqualTo(FINDINGS);
    assertThat(c.get("reopenRequestId").asText()).isEqualTo(reopen.toString());
    assertThat(c.get("closedAt").isNull()).isFalse();
    assertThat(closed.getResponse().getHeader("ETag")).isEqualTo("\"1\"");

    MvcResult got = perform(get(BASE + "/" + id));
    assertThat(body(got).get("findings").asText()).isEqualTo(FINDINGS);
    assertResponseConforms(got, "/oversight/post-audits/{id}", PathItem.HttpMethod.GET);

    // Bản mã, bằng chứng và kiểm toán trong CSDL.
    byte[] cipher =
        in(
            jdbc ->
                jdbc.sql("SELECT findings_enc FROM oversight.post_audit WHERE id = ?::uuid")
                    .param(id)
                    .query(byte[].class)
                    .single());
    assertThat(new String(cipher, StandardCharsets.ISO_8859_1)).doesNotContain("chấm sai");
    assertThat(
            this.<Integer>in(
                jdbc ->
                    jdbc.sql(
                            "SELECT cardinality(evidence_object_ids) FROM oversight.post_audit"
                                + " WHERE id = ?::uuid")
                        .param(id)
                        .query(Integer.class)
                        .single()))
        .isEqualTo(2);
    assertThat(
            this.<Long>in(
                jdbc ->
                    jdbc.sql(
                            "SELECT count(*) FROM oversight.outbox_event"
                                + " WHERE aggregate_type = 'OversightAudit'")
                        .query(Long.class)
                        .single()))
        .isEqualTo(5L);

    // Đã đóng: danh sách đợt đóng, người thẩm định khác cây không xem được.
    assertThat(body(perform(get(BASE).param("open", "false"))).get("items")).hasSize(1);
    as(UUID.randomUUID(), List.of("appraiser"), parent);
    assertThat(status(perform(get(BASE + "/" + id)))).isEqualTo(404);
    assertThat(body(perform(get(BASE))).get("items")).hasSize(1);
  }

  private String create(UUID target) throws Exception {
    return json.writeValueAsString(
        java.util.Map.of(
            "periodId", period,
            "auditKind", "DOT_XUAT",
            "targetType", "DON_VI",
            "targetId", target,
            "basis", "QĐ 12-QĐ/UBKT ngày 05/02/2027"));
  }

  private String close(UUID reopenRequest) throws Exception {
    return json.writeValueAsString(
        java.util.Map.of(
            "conclusion",
            "DANH_GIA_LAI",
            "findings",
            FINDINGS,
            "reopenRequestId",
            reopenRequest,
            "evidenceObjectIds",
            List.of(UUID.randomUUID(), UUID.randomUUID())));
  }

  private void seed() throws Exception {
    TenantContext.call(
        tenant,
        () ->
            new TransactionTemplate(configTm)
                .execute(
                    s -> {
                      UUID actor = UUID.randomUUID();
                      JdbcClient.create(configDataSource)
                          .sql(
                              "INSERT INTO config.period (id, code, name, kind, flow, start_date,"
                                  + " end_date, period_bucket, status, locked_at, created_by,"
                                  + " updated_by) VALUES (?, '2026-Q4', 'Quý IV/2026', 'QUY',"
                                  + " 'NHA_NUOC', DATE '2026-10-01', DATE '2026-12-31', 20264,"
                                  + " 'DA_KHOA', now(), ?, ?)")
                          .params(period, actor, actor)
                          .update();
                      return null;
                    }));
    in(
        jdbc -> {
          for (String t : List.of("reopen_request", "post_audit")) {
            jdbc.sql("SELECT oversight.fn_create_period_partitions(?::regclass, ?)")
                .params("oversight." + t, 20264)
                .query()
                .listOfRows();
          }
          UUID actor = UUID.randomUUID();
          jdbc.sql(
                  "INSERT INTO oversight.reopen_request (id, tenant_id, created_by, updated_by,"
                      + " period_id, period_bucket, code, scope, scope_ids, reason, basis_ref,"
                      + " audit_kind, requested_by, approvals_required, status, open_until)"
                      + " VALUES (?, ?, ?, ?, ?, 20264, 'MLK-2027-001', 'DON_VI', ARRAY[?]::uuid[],"
                      + " repeat('x', 60), 'QĐ 12', 'DOT_XUAT', ?, 2, 'CHO_DUYET_1',"
                      + " now() + interval '3 days')")
              .params(reopen, tenant.value(), actor, actor, period, elsewhere, actor)
              .update();
          return null;
        });
    TenantContext.call(
        tenant,
        () ->
            new TransactionTemplate(replicaTm)
                .execute(
                    s -> {
                      JdbcClient jdbc = JdbcClient.create(replicaDataSource);
                      for (UUID u : List.of(parent, child, elsewhere)) {
                        jdbc.sql(
                                "INSERT INTO org_replica.org_unit_v (id, valid_from, code, name,"
                                    + " unit_type, source_version, source_event_id)"
                                    + " VALUES (?, DATE '2020-01-01', ?, 'Đơn vị', 'SO', 1, ?)")
                            .params(u, u.toString().substring(0, 8), UUID.randomUUID())
                            .update();
                        closure(jdbc, u, u, 0);
                      }
                      closure(jdbc, parent, child, 1);
                      jdbc.sql(
                              "INSERT INTO org_replica.person_v (id, full_name, employment_status,"
                                  + " source_version, source_event_id)"
                                  + " VALUES (?, 'Đỗ Văn Lâm', 'DANG_LAM_VIEC', 1, ?)")
                          .params(inspector, UUID.randomUUID())
                          .update();
                      return null;
                    }));
  }

  private static void closure(JdbcClient jdbc, UUID ancestor, UUID descendant, int depth) {
    jdbc.sql(
            "INSERT INTO org_replica.unit_closure_v (tree, ancestor_id, descendant_id, depth)"
                + " VALUES ('CHUYEN_MON', ?, ?, ?)")
        .params(ancestor, descendant, depth)
        .update();
  }

  private <T> T in(Function<JdbcClient, T> work) throws Exception {
    return TenantContext.call(
        tenant,
        () ->
            new TransactionTemplate(oversightTm)
                .execute(s -> work.apply(JdbcClient.create(oversightDataSource))));
  }

  private static void as(UUID user, List<String> roles, UUID unit) {
    Beans.user = user;
    Beans.roles = roles;
    Beans.unit = unit;
  }

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return TenantContext.call(tenant, () -> mvc.perform(request).andReturn());
  }

  private static MockHttpServletRequestBuilder write(
      MockHttpServletRequestBuilder b, String body, String ifMatch) {
    return write(b, body, ifMatch, UUID.randomUUID().toString());
  }

  private static MockHttpServletRequestBuilder write(
      MockHttpServletRequestBuilder b, String body, String ifMatch, String key) {
    b.header("Idempotency-Key", key);
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
