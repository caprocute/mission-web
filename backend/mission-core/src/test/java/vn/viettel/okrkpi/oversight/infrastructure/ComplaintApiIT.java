package vn.viettel.okrkpi.oversight.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.PathItem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalStepEvents;
import vn.viettel.okrkpi.config.api.RoleHolderApi;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.oversight.application.complaint.ComplaintService;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSealer;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSigner;
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
 * Kiểm thử tích hợp CORE-07-02 trên PostgreSQL thật: danh sách kết quả còn khiếu nại được (quá hạn
 * chỉ còn bảo lưu); lưu nháp khiếu nại quá hạn 422 {@code OVERSIGHT-422-DEADLINE-PASSED}; cột
 * {@code _enc} không chứa bản rõ; gửi thiếu AAL2 401; gửi thiếu cấu phần 422 {@code
 * OVERSIGHT-422-NO-TARGET}; gửi 202 chỉ mở yêu cầu ký, hồ sơ vẫn {@code DA_KY_SO}; cổng tài liệu
 * ký; hai EVT-18 đồng thời chỉ một lần chuyển hồ sơ {@code KHIEU_NAI} và đúng một EVT-25; gửi lại
 * 409.
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    properties = {
      "mission.outbox.relay.enabled=false",
      "mission.oversight.reopen-execution.enabled=false"
    })
@AutoConfigureMockMvc(addFilters = false)
@Import(ComplaintApiIT.Beans.class)
@DirtiesContext
class ComplaintApiIT extends ContractConformanceTest {

  private static final String BASE = "/api/v1/oversight/complaints";
  private static final int BUCKET = 20264;
  private static final String CONTENT =
      "Nhiệm vụ then chốt 3 bị trừ hệ số chất lượng do chậm 6 ngày; nguyên nhân là hồ sơ đầu vào"
          + " gửi muộn, có công văn xác nhận.";
  private static final String REQUEST =
      "Đề nghị không trừ hệ số chất lượng của nhiệm vụ then chốt 3.";

  /** Người gọi, bộ mã hoá, bộ ký số giả, outbox kiểm thử và bộ ghi EVT-11 của MOD-04. */
  static class Beans {
    static volatile UUID user = UUID.randomUUID();
    static volatile String acr = "aal2";
    static volatile List<String> roles = List.of("member");
    static volatile UUID unitId;
    static final Map<UUID, List<UUID>> APPRAISERS = new ConcurrentHashMap<>();
    static final List<UUID> SIGNED = new CopyOnWriteArrayList<>();

    @Bean
    OversightCaller complaintTestCaller() {
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
        public Optional<UUID> unitId() {
          return Optional.ofNullable(unitId);
        }

        @Override
        public Optional<String> acr() {
          return Optional.ofNullable(acr);
        }

        @Override
        public Optional<Long> authTime() {
          return Optional.of(Instant.now().getEpochSecond() - 30);
        }
      };
    }

    /** Người giữ vai trò {@code appraiser} theo đơn vị, thay {@code config.role_binding}. */
    @Bean
    @Primary
    RoleHolderApi complaintTestRoles() {
      return (tenant, role, unit, at) ->
          APPRAISERS.getOrDefault(unit, List.of()).stream()
              .map(p -> new RoleHolderApi.Holder(p, false))
              .toList();
    }

    @Bean
    ComplaintSigner complaintTestSigner() {
      return (tenant, id, key, authorization) -> {
        UUID sign = UUID.randomUUID();
        SIGNED.add(sign);
        return sign;
      };
    }

    @Bean
    @Primary
    ComplaintSealer complaintTestSealer() {
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
    OutboxPayloadSerializer complaintTestSerializer() {
      return (topic, payload) -> new SerializedPayload(new byte[] {1}, 1L, 1);
    }

    @Bean
    @Primary
    AppraisalStepEvents complaintTestStepEvents() {
      return (tenant, profileId, step, version) -> {};
    }
  }

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private ComplaintService complaints;

  @Autowired
  @Qualifier("oversightDataSource") private DataSource oversightDataSource;

  @Autowired
  @Qualifier("oversightTransactionManager") private PlatformTransactionManager oversightTm;

  @Autowired
  @Qualifier("appraisalDataSource") private DataSource appraisalDataSource;

  @Autowired
  @Qualifier("appraisalTransactionManager") private PlatformTransactionManager appraisalTm;

  @Autowired
  @Qualifier("configDataSource") private DataSource configDataSource;

  @Autowired
  @Qualifier("configTransactionManager") private PlatformTransactionManager configTm;

  private final TenantId tenant = TenantId.of(UUID.randomUUID());
  private final UUID period = UUID.randomUUID();
  private final UUID person = UUID.randomUUID();
  private final UUID fresh = UUID.randomUUID();
  private final UUID overdue = UUID.randomUUID();
  private final UUID unit = UUID.randomUUID();

  @Override
  protected ContractModule contractModule() {
    return ContractModule.OVERSIGHT;
  }

  @Test
  void nhapMaHoaGuiCoKyVaChuyenHoSoKhieuNai() throws Exception {
    seed();
    as(person, "member");

    // Kết quả còn khiếu nại được: hồ sơ quá hạn chỉ còn bảo lưu ý kiến.
    MvcResult eligible = perform(get(BASE + "/eligible-profiles"));
    assertThat(status(eligible)).as(text(eligible)).isEqualTo(200);
    assertResponseConforms(
        eligible, "/oversight/complaints/eligible-profiles", PathItem.HttpMethod.GET);
    JsonNode items = body(eligible).get("items");
    assertThat(items).hasSize(2);
    JsonNode f = byProfile(items, fresh);
    JsonNode o = byProfile(items, overdue);
    assertThat(f.get("allowedKinds")).hasSize(3);
    assertThat(f.get("workingDaysLeft").asInt()).isPositive();
    assertThat(f.get("rating").asText()).isEqualTo("HTTNV");
    assertThat(f.at("/period/label").asText()).isEqualTo("Quý IV/2026");
    assertThat(f.get("targets").toString()).contains("TONG_DIEM");
    assertThat(o.get("allowedKinds").toString()).isEqualTo("[\"BAO_LUU\"]");
    assertThat(o.get("workingDaysLeft").asInt()).isNegative();

    // Quá hạn: không lưu được hình thức khiếu nại, vẫn lưu được bảo lưu ý kiến.
    MvcResult late = perform(write(post(BASE), input(overdue, "KHIEU_NAI", false), null));
    assertThat(status(late)).as(text(late)).isEqualTo(422);
    assertThat(body(late).get("code").asText()).isEqualTo("OVERSIGHT-422-DEADLINE-PASSED");
    assertResponseConforms(late, "/oversight/complaints", PathItem.HttpMethod.POST);
    MvcResult reserve = perform(write(post(BASE), input(overdue, "BAO_LUU", true), null));
    assertThat(status(reserve)).as(text(reserve)).isEqualTo(201);

    // Nháp chưa chọn cấu phần; nội dung mã hoá, không có bản rõ trong cột _enc.
    MvcResult created = perform(write(post(BASE), input(fresh, "KHIEU_NAI", false), null));
    assertThat(status(created)).as(text(created)).isEqualTo(201);
    assertResponseConforms(created, "/oversight/complaints", PathItem.HttpMethod.POST);
    assertThat(created.getResponse().getHeader("ETag")).isEqualTo("\"0\"");
    JsonNode draft = body(created);
    String id = draft.get("id").asText();
    assertThat(draft.get("status").isNull()).isTrue();
    assertThat(draft.get("code").asText()).matches("KN-\\d{4}-\\d{4}");
    assertThat(draft.get("content").asText()).isEqualTo(CONTENT);
    assertThat(plaintextStored(UUID.fromString(id))).isFalse();

    // Gửi thiếu AAL2: 401; thiếu cấu phần: 422 OVERSIGHT-422-NO-TARGET.
    Beans.acr = "aal1";
    MvcResult aal1 = perform(write(post(BASE + "/" + id + "/submit"), "", "\"0\""));
    assertThat(status(aal1)).as(text(aal1)).isEqualTo(401);
    assertThat(aal1.getResponse().getHeader("WWW-Authenticate")).contains("aal2");
    Beans.acr = "aal2";
    MvcResult noTarget = perform(write(post(BASE + "/" + id + "/submit"), "", "\"0\""));
    assertThat(status(noTarget)).as(text(noTarget)).isEqualTo(422);
    assertThat(body(noTarget).get("code").asText()).isEqualTo("OVERSIGHT-422-NO-TARGET");
    assertResponseConforms(noTarget, "/oversight/complaints/{id}/submit", PathItem.HttpMethod.POST);

    // Chọn cấu phần rồi gửi: 202, chỉ mở yêu cầu ký, hồ sơ vẫn DA_KY_SO.
    Map<String, Object> withTarget = inputMap(fresh, "KHIEU_NAI", false);
    withTarget.put(
        "targets", List.of(Map.of("key", "TONG_DIEM", "stage", "THAM_DINH", "label", "Tổng điểm")));
    MvcResult updated =
        perform(write(put(BASE + "/" + id), json.writeValueAsString(withTarget), "\"0\""));
    assertThat(status(updated)).as(text(updated)).isEqualTo(200);
    assertResponseConforms(updated, "/oversight/complaints/{id}", PathItem.HttpMethod.PUT);
    assertThat(updated.getResponse().getHeader("ETag")).isEqualTo("\"1\"");
    MvcResult submitted = perform(write(post(BASE + "/" + id + "/submit"), "", "\"1\""));
    assertThat(status(submitted)).as(text(submitted)).isEqualTo(202);
    assertResponseConforms(
        submitted, "/oversight/complaints/{id}/submit", PathItem.HttpMethod.POST);
    assertThat(Beans.SIGNED).hasSize(1);
    UUID sign = Beans.SIGNED.get(0);
    assertThat(body(submitted).get("id").asText()).isEqualTo(sign.toString());
    assertThat(profileStatus(fresh)).isEqualTo("DA_KY_SO");
    assertThat(body(perform(get(BASE + "/" + id))).get("status").isNull()).isTrue();

    // Cổng tài liệu ký nội bộ cho SVC-10.
    MvcResult source =
        perform(get("/internal/v1/esign-sources/oversight/" + id + "?purpose=KHIEU_NAI"));
    assertThat(status(source)).as(text(source)).isEqualTo(200);
    assertThat(body(source).at("/signers/0").asText()).isEqualTo(person.toString());
    assertThat(body(source).at("/documents/0/sha256").asText()).hasSize(64);

    // Hai EVT-18 đồng thời: một lần chuyển, hồ sơ KHIEU_NAI, đúng một EVT-25.
    List<Boolean> applied =
        parallel(() -> signed(UUID.fromString(id), sign), () -> signed(UUID.fromString(id), sign));
    assertThat(applied).containsExactlyInAnyOrder(true, false);
    assertThat(profileStatus(fresh)).isEqualTo("KHIEU_NAI");
    assertThat(outbox("oversight.complaint.filed.v1")).isEqualTo(1);
    assertThat(outbox("notify.message.requested.v1")).isEqualTo(1);
    MvcResult got = perform(get(BASE + "/" + id));
    assertThat(status(got)).as(text(got)).isEqualTo(200);
    assertResponseConforms(got, "/oversight/complaints/{id}", PathItem.HttpMethod.GET);
    JsonNode filed = body(got);
    assertThat(filed.get("status").asText()).isEqualTo("MOI");
    assertThat(filed.get("responseDeadline").isNull()).isFalse();
    assertThat(filed.get("requestText").asText()).isEqualTo(REQUEST);

    // Danh sách của chính người gọi; gửi lại khiếu nại đã gửi: 409.
    MvcResult list = perform(get(BASE + "?status=MOI"));
    assertThat(status(list)).as(text(list)).isEqualTo(200);
    assertResponseConforms(list, "/oversight/complaints", PathItem.HttpMethod.GET);
    assertThat(body(list).get("items")).hasSize(1);
    MvcResult again = perform(write(post(BASE + "/" + id + "/submit"), "", "\"3\""));
    assertThat(status(again)).as(text(again)).isEqualTo(409);
    assertThat(body(again).get("code").asText()).isEqualTo("OVERSIGHT-409-INVALID-STATE");

    // Người khác không thấy khiếu nại.
    Beans.user = UUID.randomUUID();
    assertThat(status(perform(get(BASE + "/" + id)))).isEqualTo(404);
  }

  @Test
  void tiepNhanTachVaiTroKhongThuLyVaRut() throws Exception {
    seed();
    UUID handler = UUID.randomUUID();
    UUID reviewer = UUID.randomUUID();
    Beans.APPRAISERS.put(unit, List.of(handler, reviewer));
    reviewed(fresh, reviewer);
    String id = fileFresh();
    assertThat(profileStatus(fresh)).isEqualTo("KHIEU_NAI");

    // Người đã thẩm định hồ sơ: 403 tách vai trò (MSG-12-05), khiếu nại giữ MOI.
    as(reviewer, "appraiser");
    MvcResult sod = perform(write(post(BASE + "/" + id + "/accept"), "{}", etag(id)));
    assertThat(status(sod)).as(text(sod)).isEqualTo(403);
    assertThat(body(sod).get("code").asText()).isEqualTo("OVERSIGHT-403-SEPARATION-OF-DUTIES");
    assertThat(body(sod).get("detail").asText()).contains("thẩm định");
    assertResponseConforms(sod, "/oversight/complaints/{id}/accept", PathItem.HttpMethod.POST);

    // Người xử lý hợp lệ thấy khiếu nại trong danh sách, đọc được nội dung.
    as(handler, "appraiser");
    MvcResult list = perform(get(BASE));
    assertThat(status(list)).as(text(list)).isEqualTo(200);
    assertResponseConforms(list, "/oversight/complaints", PathItem.HttpMethod.GET);
    assertThat(body(list).get("items")).hasSize(1);
    MvcResult detail = perform(get(BASE + "/" + id));
    assertThat(status(detail)).as(text(detail)).isEqualTo(200);
    assertThat(body(detail).get("content").asText()).isEqualTo(CONTENT);

    // Không thụ lý thiếu lý do: 422.
    MvcResult noReason =
        perform(
            write(
                post(BASE + "/" + id + "/reject"),
                "{\"reasonCode\":\"QUA_HAN\",\"reason\":\"ngắn\"}",
                etag(id)));
    assertThat(status(noReason)).as(text(noReason)).isEqualTo(422);
    assertResponseConforms(noReason, "/oversight/complaints/{id}/reject", PathItem.HttpMethod.POST);

    // Tiếp nhận: DA_TIEP_NHAN, có người xử lý và dòng thời gian.
    MvcResult accepted =
        perform(write(post(BASE + "/" + id + "/accept"), "{\"note\":\"Đã nhận\"}", etag(id)));
    assertThat(status(accepted)).as(text(accepted)).isEqualTo(200);
    assertResponseConforms(accepted, "/oversight/complaints/{id}/accept", PathItem.HttpMethod.POST);
    JsonNode a = body(accepted);
    assertThat(a.get("status").asText()).isEqualTo("DA_TIEP_NHAN");
    assertThat(a.at("/handler/id").asText()).isEqualTo(handler.toString());
    assertThat(a.get("timeline")).hasSize(2);

    // Rút sau khi đã có văn bản trả lời: 409; trước kết luận: DONG, hồ sơ về DA_KY_SO.
    as(person, "member");
    setStatus(id, "DA_TRA_LOI");
    MvcResult late =
        perform(
            write(
                post(BASE + "/" + id + "/withdraw"),
                reason("Đồng chí muốn rút khiếu nại."),
                etag(id)));
    assertThat(status(late)).as(text(late)).isEqualTo(409);
    assertThat(body(late).get("code").asText()).isEqualTo("OVERSIGHT-409-INVALID-STATE");
    setStatus(id, "DA_TIEP_NHAN");
    MvcResult withdrawn =
        perform(
            write(
                post(BASE + "/" + id + "/withdraw"),
                reason("Đã trao đổi trực tiếp với người nghiệm thu và thống nhất kết quả."),
                etag(id)));
    assertThat(status(withdrawn)).as(text(withdrawn)).isEqualTo(200);
    assertResponseConforms(
        withdrawn, "/oversight/complaints/{id}/withdraw", PathItem.HttpMethod.POST);
    assertThat(body(withdrawn).get("status").asText()).isEqualTo("DONG");
    assertThat(profileStatus(fresh)).isEqualTo("DA_KY_SO");
    assertThat(plaintextStored(UUID.fromString(id))).isFalse();

    // Khiếu nại thứ hai không thụ lý vì trùng nội dung: DONG, hồ sơ về DA_KY_SO.
    String second = fileFresh();
    as(handler, "appraiser");
    MvcResult rejected =
        perform(
            write(
                post(BASE + "/" + second + "/reject"),
                "{\"reasonCode\":\"TRUNG_NOI_DUNG\",\"reason\":\"Nội dung trùng khiếu nại đã"
                    + " được trả lời, không có căn cứ mới.\"}",
                etag(second)));
    assertThat(status(rejected)).as(text(rejected)).isEqualTo(200);
    assertResponseConforms(rejected, "/oversight/complaints/{id}/reject", PathItem.HttpMethod.POST);
    assertThat(body(rejected).get("status").asText()).isEqualTo("DONG");
    assertThat(closeReason(second)).isEqualTo("TRUNG_NOI_DUNG");
    assertThat(profileStatus(fresh)).isEqualTo("DA_KY_SO");
    assertThat(outbox("notify.message.requested.v1")).isEqualTo(5);
  }

  /** Lưu nháp có cấu phần, gửi và nhận EVT-18: khiếu nại MOI. */
  private String fileFresh() throws Exception {
    as(person, "member");
    Map<String, Object> in = inputMap(fresh, "KHIEU_NAI", false);
    in.put("targets", List.of(Map.of("key", "TONG_DIEM", "stage", "THAM_DINH")));
    MvcResult created = perform(write(post(BASE), json.writeValueAsString(in), null));
    assertThat(status(created)).as(text(created)).isEqualTo(201);
    String id = body(created).get("id").asText();
    int before = Beans.SIGNED.size();
    MvcResult submitted = perform(write(post(BASE + "/" + id + "/submit"), "", etag(id)));
    assertThat(status(submitted)).as(text(submitted)).isEqualTo(202);
    assertThat(signed(UUID.fromString(id), Beans.SIGNED.get(before))).isTrue();
    return id;
  }

  private void as(UUID user, String role) {
    Beans.unitId = "member".equals(role) ? null : unit;
    Beans.user = user;
    Beans.roles = List.of(role);
    Beans.acr = "aal2";
  }

  private String reason(String text) throws Exception {
    return json.writeValueAsString(Map.of("reason", text));
  }

  private String etag(String id) throws Exception {
    return "\""
        + in(
            oversightDataSource,
            oversightTm,
            jdbc ->
                jdbc.sql("SELECT row_version FROM oversight.complaint WHERE id = ?")
                    .params(UUID.fromString(id))
                    .query(Integer.class)
                    .single())
        + "\"";
  }

  private void setStatus(String id, String status) throws Exception {
    in(
        oversightDataSource,
        oversightTm,
        jdbc ->
            jdbc.sql("UPDATE oversight.complaint SET status = ? WHERE id = ?")
                .params(status, UUID.fromString(id))
                .update());
  }

  private String closeReason(String id) throws Exception {
    return in(
        oversightDataSource,
        oversightTm,
        jdbc ->
            jdbc.sql("SELECT close_reason_code FROM oversight.complaint WHERE id = ?")
                .params(UUID.fromString(id))
                .query(String.class)
                .single());
  }

  /** Người thẩm định của hồ sơ ({@code rating_proposal} mức {@code THAM_DINH}). */
  private void reviewed(UUID profile, UUID reviewer) throws Exception {
    in(
        appraisalDataSource,
        appraisalTm,
        jdbc ->
            jdbc.sql(
                    "INSERT INTO appraisal.rating_proposal (id, created_by, updated_by, period_id,"
                        + " period_bucket, profile_id, level_kind, rating_code, score_snapshot_id,"
                        + " actor_id, actor_role, proposed_at)"
                        + " SELECT gen_random_uuid(), ?, ?, period_id, period_bucket, id,"
                        + " 'THAM_DINH', 'HTTNV', current_snapshot_id, ?, 'appraiser', now()"
                        + " FROM appraisal.appraisal_profile WHERE id = ?")
                .params(reviewer, reviewer, reviewer, profile)
                .update());
  }

  private boolean signed(UUID id, UUID signRequest) throws Exception {
    return TenantContext.call(
        tenant, () -> complaints.onSigned(tenant, id, signRequest, Instant.now()));
  }

  private boolean plaintextStored(UUID id) throws Exception {
    return in(
        oversightDataSource,
        oversightTm,
        jdbc ->
            jdbc.sql(
                    "SELECT position(convert_to(?, 'UTF8') in content_enc) > 0"
                        + " OR position(convert_to(?, 'UTF8') in request_text_enc) > 0"
                        + " FROM oversight.complaint WHERE id = ?")
                .params("then chốt", "Đề nghị", id)
                .query(Boolean.class)
                .single());
  }

  private String profileStatus(UUID profile) throws Exception {
    return in(
        appraisalDataSource,
        appraisalTm,
        jdbc ->
            jdbc.sql("SELECT status FROM appraisal.appraisal_profile WHERE id = ?")
                .params(profile)
                .query(String.class)
                .single());
  }

  private long outbox(String topic) throws Exception {
    return in(
        oversightDataSource,
        oversightTm,
        jdbc ->
            jdbc.sql("SELECT count(*) FROM oversight.outbox_event WHERE topic = ?")
                .params(topic)
                .query(Long.class)
                .single());
  }

  private static JsonNode byProfile(JsonNode items, UUID profile) {
    for (JsonNode i : items) {
      if (i.get("profileId").asText().equals(profile.toString())) {
        return i;
      }
    }
    throw new AssertionError("Không có hồ sơ " + profile);
  }

  private Map<String, Object> inputMap(UUID profile, String kind, boolean reserve) {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("profileId", profile);
    d.put("kind", kind);
    d.put("targetStage", "THAM_DINH");
    d.put("content", CONTENT);
    d.put("requestText", REQUEST);
    d.put("reserveOpinion", reserve);
    return d;
  }

  private String input(UUID profile, String kind, boolean reserve) throws Exception {
    return json.writeValueAsString(inputMap(profile, kind, reserve));
  }

  private void seed() throws Exception {
    in(
        configDataSource,
        configTm,
        jdbc -> {
          UUID actor = UUID.randomUUID();
          jdbc.sql(
                  "INSERT INTO config.period (id, code, name, kind, flow, start_date, end_date,"
                      + " period_bucket, status, locked_at, created_by, updated_by)"
                      + " VALUES (?, '2026-Q4', 'Quý IV/2026', 'QUY', 'DANG', DATE '2026-10-01',"
                      + " DATE '2026-12-31', ?, 'DA_KHOA', now(), ?, ?)")
              .params(period, BUCKET, actor, actor)
              .update();
          return null;
        });
    in(
        oversightDataSource,
        oversightTm,
        jdbc -> {
          jdbc.sql("SELECT oversight.fn_create_period_partitions(?::regclass, ?)")
              .params("oversight.complaint", BUCKET)
              .query()
              .listOfRows();
          return null;
        });
    in(
        appraisalDataSource,
        appraisalTm,
        jdbc -> {
          for (String t : List.of("appraisal_profile", "score_snapshot", "rating_proposal")) {
            jdbc.sql("SELECT appraisal.fn_create_period_partitions(?::regclass, ?)")
                .params("appraisal." + t, BUCKET)
                .query()
                .listOfRows();
          }
          profile(jdbc, fresh, "now() - interval '1 day'", "DANG");
          profile(jdbc, overdue, "now() - interval '40 days'", "NHA_NUOC");
          return null;
        });
  }

  private void profile(JdbcClient jdbc, UUID id, String decidedAt, String flow) {
    UUID snapshot = UUID.randomUUID();
    String sha = "'\\x" + "ab".repeat(32) + "'::bytea";
    jdbc.sql(
            "INSERT INTO appraisal.appraisal_profile (id, created_by, updated_by, period_id,"
                + " period_bucket, person_id, flow, org_unit_id, assignment_id, job_title_id,"
                + " policy_pack_version_id, status, final_rating_code, decided_at)"
                + " VALUES (?, gen_random_uuid(), gen_random_uuid(), ?, ?, ?, ?, ?,"
                + " gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'DA_KY_SO', 'HTTNV', "
                + decidedAt
                + ")")
        .params(id, period, BUCKET, person, flow, unit)
        .update();
    jdbc.sql(
            "INSERT INTO appraisal.score_snapshot (id, created_by, updated_by, period_id,"
                + " period_bucket, profile_id, flow, snapshot_kind, score_result_id,"
                + " policy_pack_version_id, policy_content_hash, policy_version, input_hash,"
                + " total_score, auto_rating_code, rule_checks, group_scores_json, computed_at,"
                + " snapshot_hash, general_criteria_score, kpi_score)"
                + " VALUES (?, gen_random_uuid(), gen_random_uuid(), ?, ?, ?, ?,"
                + " 'TAM_TINH', gen_random_uuid(), gen_random_uuid(), "
                + sha
                + ", 'KHOI_DANG v2.0', "
                + sha
                + ", 91.2, 'HTTNV', '[]', '{}', now(), "
                + sha
                + ", 90, 91.2)")
        .params(snapshot, period, BUCKET, id, flow)
        .update();
    jdbc.sql("UPDATE appraisal.appraisal_profile SET current_snapshot_id = ? WHERE id = ?")
        .params(snapshot, id)
        .update();
  }

  @SafeVarargs
  private static <T> List<T> parallel(Callable<T>... tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<T>> futures = new ArrayList<>();
      for (Callable<T> t : tasks) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return t.call();
                }));
      }
      start.countDown();
      List<T> out = new ArrayList<>();
      for (Future<T> fut : futures) {
        out.add(fut.get());
      }
      return out;
    } finally {
      pool.shutdownNow();
    }
  }

  private <T> T in(DataSource ds, PlatformTransactionManager tm, Function<JdbcClient, T> work)
      throws Exception {
    return TenantContext.call(
        tenant, () -> new TransactionTemplate(tm).execute(s -> work.apply(JdbcClient.create(ds))));
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
