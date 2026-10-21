package vn.viettel.okrkpi.shared.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.swagger.v3.oas.models.PathItem;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.viettel.okrkpi.MissionCoreApplication;
import vn.viettel.okrkpi.MissionCoreTestDatabase;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.job.JobStatus;
import vn.viettel.okrkpi.shared.job.TrackedJob;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kiểm thử tuân thủ hợp đồng OpenAPI 3.1 ({@link ContractModule#JOBS}) và hành vi nghiệp vụ cho
 * {@code GET /api/v1/jobs/{id}} & {@code DELETE /api/v1/jobs/{id}} ({@code x-mission.owner:
 * SVC-05}) trong {@code mission-core} (CORE-00-02).
 */
@SpringBootTest(
    classes = MissionCoreApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "springdoc.api-docs.enabled=true",
      "springdoc.api-docs.path=/v3/api-docs",
      "springdoc.packages-to-scan=vn.viettel.okrkpi.shared.infrastructure.web"
    })
@AutoConfigureMockMvc(addFilters = false)
class JobsContractConformanceIT extends ContractConformanceTest {

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    MissionCoreTestDatabase.registerDynamicProperties(registry);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private InMemoryJobRegistry jobRegistry;

  @BeforeEach
  void resetDefaultJob() {
    jobRegistry.seedDefaultExampleJob();
  }

  @Override
  protected ContractModule contractModule() {
    return ContractModule.JOBS;
  }

  @Test
  void getAndCancelJobEnforceContractConformanceCreatorIsolationAndCancellability()
      throws Exception {
    assertOpenApiConformance(mockMvc);

    TenantId tenantId = InMemoryJobRegistry.DEFAULT_TENANT_ID;
    ActorId stranger = ActorId.of(UUID.randomUUID());

    // 1. Người tạo truy vấn tác vụ đang chạy nhận 200 OK kèm Retry-After và khớp schema OpenAPI
    MvcResult getResult =
        mockMvc
            .perform(get("/api/v1/jobs/" + InMemoryJobRegistry.CONTRACT_EXAMPLE_JOB_ID))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"))
            .andExpect(jsonPath("$.id").value(InMemoryJobRegistry.CONTRACT_EXAMPLE_JOB_ID))
            .andExpect(jsonPath("$.status").value("DANG_CHAY"))
            .andReturn();
    assertResponseConforms(getResult, "/jobs/{id}", PathItem.HttpMethod.GET);

    // 2. Người dùng khác truy vấn nhận 404 Not Found (chống dò ID)
    InMemoryJobRegistry.setActorOverride(stranger);
    try {
      mockMvc
          .perform(get("/api/v1/jobs/" + InMemoryJobRegistry.CONTRACT_EXAMPLE_JOB_ID))
          .andExpect(status().isNotFound());
    } finally {
      InMemoryJobRegistry.clearActorOverride();
    }

    // 3. Người tạo yêu cầu huỷ tác vụ nhận 202 Accepted và trạng thái chuyển sang DA_HUY
    MvcResult deleteResult =
        mockMvc
            .perform(
                delete("/api/v1/jobs/" + InMemoryJobRegistry.CONTRACT_EXAMPLE_JOB_ID)
                    .header("Idempotency-Key", UUID.randomUUID().toString()))
            .andExpect(status().isAccepted())
            .andReturn();
    assertResponseConforms(deleteResult, "/jobs/{id}", PathItem.HttpMethod.DELETE);

    assertThat(
            jobRegistry
                .find(tenantId, InMemoryJobRegistry.CONTRACT_EXAMPLE_JOB_ID)
                .map(TrackedJob::job)
                .map(vn.viettel.okrkpi.shared.job.Job::status))
        .contains(JobStatus.DA_HUY);

    // 4. Huỷ lại tác vụ đã ở trạng thái kết thúc (DA_HUY) nhận 409 Conflict
    // (JOB-409-NOT-CANCELLABLE)
    mockMvc
        .perform(
            delete("/api/v1/jobs/" + InMemoryJobRegistry.CONTRACT_EXAMPLE_JOB_ID)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("JOB-409-NOT-CANCELLABLE"));
  }
}
