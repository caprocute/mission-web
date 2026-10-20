package vn.viettel.okrkpi.contract.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.jobs.api.JobsApi;
import vn.viettel.okrkpi.contract.jobs.model.Job;
import vn.viettel.okrkpi.contract.jobs.model.JobStatus;
import vn.viettel.okrkpi.contract.test.application.OpenApiConformanceEngine;
import vn.viettel.okrkpi.contract.test.domain.ConformanceReport;
import vn.viettel.okrkpi.contract.test.domain.ContractViolation;

@SpringBootTest(
    classes = JobsContractConformanceTest.SampleJobsApplication.class,
    properties = {
      "springdoc.api-docs.enabled=true",
      "springdoc.api-docs.path=/v3/api-docs",
      "spring.main.banner-mode=off"
    })
@AutoConfigureMockMvc
class JobsContractConformanceTest extends ContractConformanceTest {

  @Autowired private MockMvc mockMvc;

  @Override
  protected ContractModule contractModule() {
    return ContractModule.JOBS;
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class SampleJobsApplication {

    @RestController
    @RequestMapping("/api/v1")
    static class CompliantJobsController implements JobsApi {

      @Override
      public ResponseEntity<Job> getJob(String id) {
        Job job =
            new Job(id, "PERIOD_LOCK", JobStatus.DANG_CHAY)
                .progress(45)
                .message("Đang chốt điểm đơn vị 9/20")
                .updatedAt(OffsetDateTime.parse("2026-12-31T17:05:00+07:00"));
        return ResponseEntity.ok().header("Retry-After", "5").body(job);
      }

      @Override
      public ResponseEntity<Void> cancelJob(String idempotencyKey, String id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
      }
    }
  }

  @Test
  void shouldPassOpenApiConformanceAndMockMvcResponseValidationWhenCompliant() throws Exception {
    assertOpenApiConformance(mockMvc);

    MvcResult getResult =
        mockMvc
            .perform(get("/api/v1/jobs/01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77"))
            .andExpect(status().isOk())
            .andExpect(header().string("Retry-After", "5"))
            .andReturn();
    assertResponseConforms(getResult, "/jobs/{id}", PathItem.HttpMethod.GET);

    MvcResult deleteResult =
        mockMvc
            .perform(
                delete("/api/v1/jobs/01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77")
                    .header("Idempotency-Key", "01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a99"))
            .andExpect(status().isAccepted())
            .andReturn();
    assertResponseConforms(deleteResult, "/jobs/{id}", PathItem.HttpMethod.DELETE);
  }

  @Test
  void shouldFailWhenExtraEndpointOrMissingEndpointOrRenamedFieldInOpenApiDocs() throws Exception {
    MvcResult apiDocsResult =
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
    String actualJson = apiDocsResult.getResponse().getContentAsString(StandardCharsets.UTF_8);

    OpenAPI expected = OpenApiConformanceEngine.loadExpectedSpec(ContractModule.JOBS);
    OpenAPI actualWithExtra = OpenApiConformanceEngine.parseSpecContent(actualJson);
    actualWithExtra
        .getPaths()
        .addPathItem(
            "/api/v1/jobs/unofficial",
            new PathItem().post(new Operation().operationId("unofficialJob")));

    ConformanceReport extraReport =
        OpenApiConformanceEngine.verifyConformance(
            ContractModule.JOBS, "SVC-05", expected, actualWithExtra);
    assertThat(extraReport.isCompliant()).isFalse();
    assertThat(extraReport.summaryMessage())
        .contains("EXTRA_ENDPOINT")
        .contains("POST /jobs/unofficial");
    assertThatThrownBy(extraReport::assertCompliant)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("POST /jobs/unofficial");

    OpenAPI actualMissingDelete = OpenApiConformanceEngine.parseSpecContent(actualJson);
    actualMissingDelete.getPaths().get("/api/v1/jobs/{id}").setDelete(null);
    ConformanceReport missingReport =
        OpenApiConformanceEngine.verifyConformance(
            ContractModule.JOBS, "SVC-05", expected, actualMissingDelete);
    assertThat(missingReport.isCompliant()).isFalse();
    assertThat(missingReport.summaryMessage())
        .contains("MISSING_ENDPOINT")
        .contains("DELETE /jobs/{id}");

    OpenAPI actualRenamedField = OpenApiConformanceEngine.parseSpecContent(actualJson);
    var jobSchema = actualRenamedField.getComponents().getSchemas().get("Job");
    var progressProp = jobSchema.getProperties().remove("progress");
    jobSchema.getProperties().put("progressPercent", progressProp);
    jobSchema.getProperties().put("id", new IntegerSchema());
    actualRenamedField.getPaths().get("/api/v1/jobs/{id}").getGet().getResponses().remove("404");

    ConformanceReport renamedReport =
        OpenApiConformanceEngine.verifyConformance(
            ContractModule.JOBS, "SVC-05", expected, actualRenamedField);
    assertThat(renamedReport.isCompliant()).isFalse();
    assertThat(renamedReport.summaryMessage())
        .contains("INCOMPATIBLE_CHANGE")
        .contains("progress")
        .contains("progressPercent")
        .contains("404");
  }

  @Test
  void shouldFailWhenMockMvcResponseRenamesFieldOrViolatesSchemaConstraints() {
    OpenAPI expected = OpenApiConformanceEngine.loadExpectedSpec(ContractModule.JOBS);

    String renamedFieldJson =
        """
        {
          "id": "01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77",
          "type": "PERIOD_LOCK",
          "status": "DANG_CHAY",
          "progressPercent": 45
        }
        """;
    List<ContractViolation> renamedViolations =
        OpenApiConformanceEngine.validateResponseJson(
            expected, "/jobs/{id}", PathItem.HttpMethod.GET, 200, renamedFieldJson);
    assertThat(renamedViolations)
        .extracting(ContractViolation::format)
        .anyMatch(msg -> msg.contains("progressPercent"));

    String invalidConstraintsJson =
        """
        {
          "id": 123,
          "status": "TRANG_THAI_LA",
          "progress": 150
        }
        """;
    List<ContractViolation> constraintViolations =
        OpenApiConformanceEngine.validateResponseJson(
            expected, "/jobs/{id}", PathItem.HttpMethod.GET, 200, invalidConstraintsJson);
    assertThat(constraintViolations).hasSizeGreaterThanOrEqualTo(3);

    String minProgressJson =
        """
        {
          "id": "01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77",
          "type": "PERIOD_LOCK",
          "status": "DANG_CHAY",
          "progress": -5
        }
        """;
    assertThat(
            OpenApiConformanceEngine.validateResponseJson(
                expected, "/jobs/{id}", PathItem.HttpMethod.GET, 200, minProgressJson))
        .isNotEmpty();

    assertThat(
            OpenApiConformanceEngine.validateResponseJson(
                expected, "/jobs/{id}", PathItem.HttpMethod.GET, 200, "[]"))
        .isNotEmpty();
    assertThat(
            OpenApiConformanceEngine.validateResponseJson(
                expected, "/unknown", PathItem.HttpMethod.GET, 200, "{}"))
        .isNotEmpty();
    assertThat(
            OpenApiConformanceEngine.validateResponseJson(
                expected, "/jobs/{id}", PathItem.HttpMethod.PUT, 200, "{}"))
        .isNotEmpty();
    assertThat(
            OpenApiConformanceEngine.validateResponseJson(
                expected, "/jobs/{id}", PathItem.HttpMethod.GET, 299, "{}"))
        .isNotEmpty();

    List<ContractViolation> malformedViolations =
        OpenApiConformanceEngine.validateResponseJson(
            expected, "/jobs/{id}", PathItem.HttpMethod.GET, 200, "{not-valid-json");
    assertThat(malformedViolations).hasSize(1);
  }

  @Test
  void shouldDetectBaselineSection13ConventionViolations() {
    OpenAPI brokenSpec = new OpenAPI();
    brokenSpec.setServers(List.of(new Server().url("/v2")));
    Paths paths = new Paths();
    Operation postOp =
        new Operation()
            .extensions(Map.of("x-mission", Map.of("owner", "SVC-05")))
            .addParametersItem(
                new Parameter().in("header").name("If-Match").schema(new StringSchema()))
            .addParametersItem(
                new Parameter()
                    .in("query")
                    .name("limit")
                    .schema(new IntegerSchema().maximum(BigDecimal.valueOf(500))))
            .responses(
                new ApiResponses()
                    .addApiResponse(
                        "400",
                        new ApiResponse()
                            .content(
                                new Content()
                                    .addMediaType(
                                        "application/json",
                                        new MediaType().schema(new ObjectSchema())))));
    paths.addPathItem("/items", new PathItem().post(postOp));
    brokenSpec.setPaths(paths);

    List<ContractViolation> violations =
        OpenApiConformanceEngine.validateBaselineConventions(brokenSpec, "SVC-05");
    assertThat(violations)
        .extracting(ContractViolation::format)
        .anyMatch(m -> m.contains("/api/v1"))
        .anyMatch(m -> m.contains("Idempotency-Key"))
        .anyMatch(m -> m.contains("409"))
        .anyMatch(m -> m.contains("200"))
        .anyMatch(m -> m.contains("application/problem+json"));
  }

  @Test
  void shouldValidateDomainValueObjectsAndOwnerFiltering() {
    assertThatThrownBy(() -> new ContractViolation("", "loc", "msg"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ContractViolation("cat", "", "msg"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ContractViolation("cat", "loc", ""))
        .isInstanceOf(IllegalArgumentException.class);

    ContractViolation v1 = ContractViolation.missingEndpoint("GET", "/jobs/{id}");
    ContractViolation v2 = ContractViolation.missingEndpoint("GET", "/jobs/{id}");
    ContractViolation v3 = ContractViolation.extraEndpoint("POST", "/jobs");
    assertThat(v1).isEqualTo(v2).isNotEqualTo(v3).isNotEqualTo(null).hasSameHashCodeAs(v2);

    ConformanceReport rNull = new ConformanceReport(ContractModule.JOBS, null, List.of());
    ConformanceReport rBlank = new ConformanceReport(ContractModule.JOBS, "   ", List.of());
    assertThat(rNull)
        .isEqualTo(rBlank)
        .isNotEqualTo(null)
        .hasSameHashCodeAs(rBlank)
        .extracting(ConformanceReport::ownerFilter)
        .isEqualTo("*");
    assertThat(rNull.summaryMessage()).contains("tuân thủ hoàn toàn");
    rNull.assertCompliant();

    OpenAPI dossiersSpec = OpenApiConformanceEngine.loadExpectedSpec(ContractModule.DOSSIERS);
    List<ContractViolation> esignConventions =
        OpenApiConformanceEngine.validateBaselineConventions(dossiersSpec, "SVC-07");
    assertThat(esignConventions).isEmpty();

    List<ContractViolation> esignExamples =
        OpenApiConformanceEngine.validateContractExamples(dossiersSpec, "SVC-07");
    assertThat(esignExamples).isEmpty();

    OpenAPI tasksSpec = OpenApiConformanceEngine.loadExpectedSpec(ContractModule.TASKS);
    assertThat(OpenApiConformanceEngine.validateBaselineConventions(tasksSpec, "*")).isEmpty();
    assertThat(OpenApiConformanceEngine.validateContractExamples(tasksSpec, "*")).isEmpty();

    OpenAPI policiesSpec = OpenApiConformanceEngine.loadExpectedSpec(ContractModule.POLICIES);
    assertThat(OpenApiConformanceEngine.validateBaselineConventions(policiesSpec, "*")).isEmpty();
    assertThat(OpenApiConformanceEngine.validateContractExamples(policiesSpec, "*")).isEmpty();
  }
}
