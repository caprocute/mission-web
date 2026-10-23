package vn.viettel.okrkpi.scoring.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.viettel.okrkpi.scoring.application.port.ScoreTraceStore;
import vn.viettel.okrkpi.scoring.domain.CanonicalDoc;
import vn.viettel.okrkpi.scoring.domain.ScoreTrace;
import vn.viettel.okrkpi.scoring.domain.TraceStep;
import vn.viettel.okrkpi.shared.authz.OpaClient;
import vn.viettel.okrkpi.shared.authz.OpaDecision;
import vn.viettel.okrkpi.shared.authz.OpaInput;

/**
 * Kiểm thử hợp đồng {@code getAppraisalScoreTrace} (DV-07-03): phản hồi 200, 403, 404 khớp {@code
 * contracts/openapi/modules/appraisals.yaml}; OPA nhận tài nguyên hồ sơ với chủ sở hữu là người
 * được đánh giá; lọc theo nhiệm vụ.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScoreTraceApiContractTest {

  private static final OpenApiInteractionValidator VALIDATOR =
      OpenApiInteractionValidator.createForSpecificationUrl(
              ScoreTraceApiContractTest.class
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

  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000a003");
  private static final UUID PROFILE = UUID.fromString("0192f1a2-0000-7000-8000-0000000000d1");
  private static final UUID PERSON = UUID.fromString("0192f1a2-0000-7000-8000-0000000000e1");
  private static final UUID RESULT = UUID.fromString("0192f1a2-0000-7000-8000-0000000000f1");
  private static final UUID TASK = UUID.fromString("0192a1b0-3c4d-7e5f-8a9b-0c1d2e3f5c01");

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @MockitoBean private ScoreTraceStore store;
  @MockitoBean private OpaClient opa;

  @Test
  void traVetTinhDungLuocDoVaLocTheoNhiemVu() throws Exception {
    when(store.latest(eq(PROFILE), eq("DANG"))).thenReturn(Optional.of(sample()));
    when(opa.decide(any())).thenReturn(new OpaDecision(true, Set.of(), Set.of(), "r1", false));

    MvcResult all =
        call(get("/api/v1/appraisals/profiles/{id}/score-trace", PROFILE).param("flow", "DANG"));
    assertThat(all.getResponse().getStatus()).isEqualTo(200);
    assertContract(all);
    JsonNode body = json.readTree(all.getResponse().getContentAsString());
    assertThat(body.path("scoreResultId").asText()).isEqualTo(RESULT.toString());
    assertThat(body.path("policyVersion").asText()).isEqualTo("2.1.0");
    assertThat(body.path("steps")).hasSize(2);
    assertThat(body.path("steps").get(0).path("taskId").asText()).isEqualTo(TASK.toString());
    assertThat(body.path("steps").get(0).path("output").path("kpi_A").asDouble()).isEqualTo(1.0);
    assertThat(body.path("steps").get(1).has("taskId")).isFalse();

    MvcResult one =
        call(
            get("/api/v1/appraisals/profiles/{id}/score-trace", PROFILE)
                .param("flow", "DANG")
                .param("taskId", TASK.toString()));
    assertContract(one);
    assertThat(json.readTree(one.getResponse().getContentAsString()).path("steps")).hasSize(1);

    var captor = org.mockito.ArgumentCaptor.forClass(OpaInput.class);
    org.mockito.Mockito.verify(opa, org.mockito.Mockito.atLeastOnce()).decide(captor.capture());
    OpaInput input = captor.getValue();
    assertThat(input.resource().type()).isEqualTo("appraisal_profile");
    assertThat(input.resource().id()).isEqualTo(PROFILE.toString());
    assertThat(input.resource().ownerIds()).containsExactly(PERSON.toString());
  }

  @Test
  void opaTuChoiThi403KhongCoKetQuaThi404() throws Exception {
    when(store.latest(eq(PROFILE), any())).thenReturn(Optional.of(sample()));
    when(opa.decide(any())).thenReturn(OpaDecision.denyClosed("out_of_scope"));
    MvcResult denied = call(get("/api/v1/appraisals/profiles/{id}/score-trace", PROFILE));
    assertThat(denied.getResponse().getStatus()).isEqualTo(403);
    assertContract(denied);

    UUID other = UUID.randomUUID();
    when(store.latest(eq(other), any())).thenReturn(Optional.empty());
    MvcResult missing = call(get("/api/v1/appraisals/profiles/{id}/score-trace", other));
    assertThat(missing.getResponse().getStatus()).isEqualTo(404);
    assertContract(missing);
  }

  private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(
            request.with(
                jwt()
                    .jwt(
                        j ->
                            j.subject(PERSON.toString())
                                .claim("tenant_id", TENANT.toString())
                                .claim("realm_access", Map.of("roles", List.of("member"))))))
        .andReturn();
  }

  private static ScoreTrace sample() {
    return new ScoreTrace(
        RESULT,
        TENANT,
        PROFILE,
        PERSON,
        "DANG",
        "2.1.0",
        "scoring-service:0.1.0",
        List.of(
            new TraceStep(
                1,
                "kpi_A",
                "PL 3 mục 1; BR-09-01; BR-09-04",
                CanonicalDoc.of(
                    Map.of(
                        "task_id",
                        TASK.toString(),
                        "nv",
                        Map.of("ke_hoach", 1, "so_luong_duyet", 1))),
                CanonicalDoc.of(Map.of("kpi_A", 1.0)),
                null),
            new TraceStep(
                2,
                "diem",
                "BR-09-03",
                CanonicalDoc.of(Map.of("diem_ket_qua", 88.3)),
                CanonicalDoc.of(Map.of("diem", 88.3)),
                null)));
  }

  private static void assertContract(MvcResult result) throws Exception {
    MockHttpServletRequest req = result.getRequest();
    MockHttpServletResponse res = result.getResponse();
    SimpleRequest.Builder rb =
        new SimpleRequest.Builder(Request.Method.valueOf(req.getMethod()), req.getRequestURI());
    req.getParameterMap().forEach(rb::withQueryParam);
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
