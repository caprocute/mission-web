package vn.viettel.okrkpi.scoring.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.cel.domain.StaticWorkingCalendar;
import vn.viettel.okrkpi.scoring.application.policy.CompiledPolicy;
import vn.viettel.okrkpi.scoring.application.policy.PolicyCompiler;
import vn.viettel.okrkpi.scoring.application.policy.PolicyPackCache;
import vn.viettel.okrkpi.scoring.application.policy.SamplePolicyPack;
import vn.viettel.okrkpi.scoring.application.port.StoredScores;
import vn.viettel.okrkpi.scoring.domain.DelaySegment;
import vn.viettel.okrkpi.scoring.domain.ProductInput;
import vn.viettel.okrkpi.scoring.domain.ScoringInput;
import vn.viettel.okrkpi.scoring.domain.TaskInput;
import vn.viettel.okrkpi.scoring.infrastructure.policy.FlowableDecisionTables;
import vn.viettel.okrkpi.shared.authz.OpaClient;
import vn.viettel.okrkpi.shared.authz.OpaDecision;
import vn.viettel.okrkpi.shared.authz.OpaInput;

/**
 * Kiểm thử hợp đồng {@code simulateAppraisalScore} và {@code previewAppraisalTaskScore} (DV-07-08):
 * phản hồi 200, 403, 404, 422 khớp {@code contracts/openapi/modules/appraisals.yaml}; OPA nhận đúng
 * thao tác và tài nguyên.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScoreSimulationApiContractTest {

  private static final OpenApiInteractionValidator VALIDATOR =
      OpenApiInteractionValidator.createForSpecificationUrl(
              ScoreSimulationApiContractTest.class
                  .getResource("/contract/modules/appraisals.yaml")
                  .toString())
          .withBasePathOverride("/api/v1")
          .withResolveCombinators(true)
          .withLevelResolver(
              LevelResolver.create()
                  .withLevel("validation.request.security.missing", ValidationReport.Level.IGNORE)
                  .withLevel("validation.request.security.invalid", ValidationReport.Level.IGNORE)
                  .withLevel(
                      "validation.schema.additionalProperties", ValidationReport.Level.IGNORE)
                  .build())
          .build();

  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000a009");
  private static final UUID PROFILE = UUID.fromString("0192f1a2-0000-7000-8000-0000000009d1");
  private static final UUID PERSON = UUID.fromString("0192f1a2-0000-7000-8000-0000000009e1");
  private static final UUID TASK = UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f9c01");

  private static FlowableDecisionTables tables;
  private static CompiledPolicy policy;

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @MockitoBean private StoredScores store;
  @MockitoBean private PolicyPackCache cache;
  @MockitoBean private OpaClient opa;

  @BeforeAll
  static void compile() {
    tables = new FlowableDecisionTables();
    policy =
        new PolicyCompiler(new CelEngine(StaticWorkingCalendar.weekendsOnly()), tables)
            .compile(SamplePolicyPack.content());
  }

  @AfterAll
  static void stop() {
    tables.destroy();
  }

  @Test
  void tinhThuVaXemTruocDungLuocDo() throws Exception {
    when(store.latest(PROFILE)).thenReturn(Optional.of(sample()));
    when(store.latestWithTask(TASK)).thenReturn(Optional.of(sample()));
    when(cache.cached(any())).thenReturn(Optional.of(policy));
    when(cache.original(any(), any(), any())).thenReturn(policy);
    when(opa.decide(any())).thenReturn(new OpaDecision(true, Set.of(), Set.of(), "r1", false));

    MvcResult sim =
        call(
            "/api/v1/appraisals/profiles/" + PROFILE + "/simulate",
            "{\"adjustments\":[{\"component\":\"C\",\"taskId\":\""
                + TASK
                + "\",\"oldValue\":0.9,\"newValue\":1,\"reason\":\"Chậm do khách quan\"}]}");
    assertThat(sim.getResponse().getStatus()).isEqualTo(200);
    assertContract(sim);
    JsonNode body = json.readTree(sim.getResponse().getContentAsString());
    assertThat(body.path("policyVersion").asText()).isEqualTo("2.1.0");
    assertThat(new BigDecimal(body.path("after").path("totalScore").asText()))
        .isGreaterThan(new BigDecimal(body.path("before").path("totalScore").asText()));
    assertThat(body.path("explanation").asText()).startsWith("Sau điều chỉnh");

    MvcResult preview =
        call(
            "/api/v1/appraisals/score-preview",
            "{\"taskId\":\"" + TASK + "\",\"qualityLevel\":0.75}");
    assertThat(preview.getResponse().getStatus()).isEqualTo(200);
    assertContract(preview);
    JsonNode p = json.readTree(preview.getResponse().getContentAsString());
    assertThat(p.path("b").decimalValue()).isEqualByComparingTo("0.75");
    assertThat(p.path("kpi").decimalValue()).isEqualByComparingTo("0.883");

    var captor = org.mockito.ArgumentCaptor.forClass(OpaInput.class);
    org.mockito.Mockito.verify(opa, org.mockito.Mockito.atLeast(2)).decide(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(i -> i.resource().type())
        .contains("appraisal_profile", "task");
  }

  @Test
  void ngoaiMienThi422KhongCoThi404TuChoiThi403() throws Exception {
    when(store.latest(PROFILE)).thenReturn(Optional.of(sample()));
    when(cache.cached(any())).thenReturn(Optional.of(policy));
    when(cache.original(any(), any(), any())).thenReturn(policy);
    when(opa.decide(any())).thenReturn(new OpaDecision(true, Set.of(), Set.of(), "r1", false));
    MvcResult bad =
        call(
            "/api/v1/appraisals/profiles/" + PROFILE + "/simulate",
            "{\"adjustments\":[{\"component\":\"C\",\"taskId\":\""
                + TASK
                + "\",\"newValue\":1.5}]}");
    assertThat(bad.getResponse().getStatus()).isEqualTo(422);
    assertContract(bad);
    assertThat(
            json.readTree(bad.getResponse().getContentAsString())
                .path("errors")
                .get(0)
                .path("field")
                .asText())
        .isEqualTo("adjustments[0].newValue");

    UUID other = UUID.randomUUID();
    when(store.latest(other)).thenReturn(Optional.empty());
    MvcResult missing =
        call(
            "/api/v1/appraisals/profiles/" + other + "/simulate",
            "{\"adjustments\":[{\"component\":\"C\",\"newValue\":1}]}");
    assertThat(missing.getResponse().getStatus()).isEqualTo(404);
    assertContract(missing);

    MvcResult noTask =
        call(
            "/api/v1/appraisals/score-preview",
            "{\"taskId\":\"" + other + "\",\"qualityLevel\":1}");
    assertThat(noTask.getResponse().getStatus()).isEqualTo(422);
    assertContract(noTask);

    when(opa.decide(any())).thenReturn(OpaDecision.denyClosed("out_of_scope"));
    MvcResult denied =
        call(
            "/api/v1/appraisals/profiles/" + PROFILE + "/simulate",
            "{\"adjustments\":[{\"component\":\"C\",\"taskId\":\"" + TASK + "\",\"newValue\":1}]}");
    assertThat(denied.getResponse().getStatus()).isEqualTo(403);
    assertContract(denied);
  }

  private MvcResult call(String path, String body) throws Exception {
    return mvc.perform(
            post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(body)
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.subject(PERSON.toString())
                                    .claim("tenant_id", TENANT.toString())
                                    .claim("realm_access", Map.of("roles", List.of("appraiser"))))))
        .andReturn();
  }

  private static StoredScores.Stored sample() {
    TaskInput nv =
        new TaskInput(
            TASK,
            "NV-1",
            "THEN_CHOT",
            1,
            new BigDecimal("30"),
            false,
            List.of(
                new ProductInput(
                    UUID.randomUUID(), ProductInput.CONFIRMED, BigDecimal.ONE, false, 0)),
            List.of(new DelaySegment(2, 2, DelaySegment.Cause.CHU_QUAN)));
    Map<String, Object> party =
        Map.of(
            "ty_le_O", 1.0,
            "ty_le_dap_ung_tieu_chi_chung", 1.0,
            "giai_phap_dot_pha_da_kiem_chung", true,
            "loai_tru", false,
            "la_nguoi_dung_dau", false,
            "tap_the_dat_05_chi_tieu", true,
            "tap_the_dat_tat_ca_O", true,
            "ty_le_don_vi_khtnv", 0.0,
            "giu_chuc_vu", false,
            "xep_loai_nha_nuoc", "");
    ScoringInput input = new ScoringInput(PROFILE, PERSON, "DANG", List.of(nv), party);
    return new StoredScores.Stored(
        UUID.randomUUID(),
        TENANT,
        UUID.randomUUID(),
        PROFILE,
        PERSON,
        "DANG",
        policy.pinned().versionId(),
        policy.contentHash(),
        "2.1.0",
        input.document().json(),
        20271,
        UUID.randomUUID());
  }

  private static void assertContract(MvcResult result) throws Exception {
    MockHttpServletRequest req = result.getRequest();
    MockHttpServletResponse res = result.getResponse();
    SimpleRequest.Builder rb =
        new SimpleRequest.Builder(Request.Method.valueOf(req.getMethod()), req.getRequestURI())
            .withContentType("application/json")
            .withHeader("Idempotency-Key", req.getHeader("Idempotency-Key"))
            .withBody(req.getContentAsString());
    SimpleResponse.Builder sb = SimpleResponse.Builder.status(res.getStatus());
    for (String name : res.getHeaderNames()) {
      sb.withHeader(name, res.getHeaders(name));
    }
    String body = res.getContentAsString(StandardCharsets.UTF_8);
    if (!body.isEmpty()) {
      sb.withBody(body);
    }
    ValidationReport report = VALIDATOR.validate(rb.build(), sb.build());
    assertThat(report.getMessages()).as(report.toString()).isEmpty();
  }
}
