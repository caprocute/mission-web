package vn.viettel.okrkpi.workflow.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.viettel.okrkpi.contract.domain.ContractModule;
import vn.viettel.okrkpi.contract.test.ContractConformanceTest;
import vn.viettel.okrkpi.shared.authz.OpaClient;
import vn.viettel.okrkpi.shared.authz.OpaDecision;
import vn.viettel.okrkpi.shared.authz.OpaInput;

/**
 * Nền chung của kiểm thử tích hợp API Workflow: PostgreSQL 17 thật, Flyway V1..V3, engine Flowable
 * thật, bảo mật bật (JWT giả lập bằng {@code spring-security-test}), OPA thay bằng bản giả có luật
 * tách vai trò "người khởi tạo không tự duyệt" như {@code backend/opa/okrkpi/authz.rego} ràng buộc
 * 4.1 của ma trận (BR-02-08).
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class WorkflowIntegrationTest extends ContractConformanceTest {

  public static final String PROCESS_KEY = "kiem-thu-duyet";

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    WorkflowTestDatabase.register(registry);
  }

  @MockitoBean protected OpaClient opa;

  @Autowired protected MockMvc mvc;
  @Autowired protected RuntimeService runtime;
  @Autowired protected TaskService tasks;
  @Autowired protected RepositoryService repository;

  @Override
  protected ContractModule contractModule() {
    return ContractModule.PLATFORM;
  }

  @Override
  protected String ownerServiceCode() {
    return "SVC-06";
  }

  @BeforeEach
  void opaAndDefinition() {
    when(opa.currentBundleRevision()).thenReturn("kiem-thu");
    when(opa.decide(any())).thenAnswer(inv -> decide(inv.getArgument(0)));
    if (repository.createProcessDefinitionQuery().processDefinitionKey(PROCESS_KEY).count() == 0) {
      repository
          .createDeployment()
          .name(PROCESS_KEY)
          .addClasspathResource("bpmn-test/kiem-thu-duyet.bpmn20.xml")
          .deploy();
    }
  }

  /** OPA giả: cho phép mọi thao tác, trừ người khởi tạo tự duyệt (ràng buộc 4.1). */
  static OpaDecision decide(OpaInput input) {
    boolean approve =
        "SCR-0203.duyet".equals(input.action()) || "SCR-0203.duyet-lo".equals(input.action());
    Map<String, List<String>> participants =
        input.resource() == null || input.resource().participants() == null
            ? Map.of()
            : input.resource().participants();
    if (approve
        && participants.getOrDefault("initiator", List.of()).contains(input.subject().userId())) {
      return new OpaDecision(false, Set.of("separation_of_duties"), Set.of(), "kiem-thu", false);
    }
    return new OpaDecision(true, Set.of(), Set.of(), "kiem-thu", false);
  }

  /** JWT của người dùng: tenant, vai trò baseline và đơn vị đang chọn. */
  protected static RequestPostProcessor user(UUID tenant, UUID userId, UUID unit, String... roles) {
    return jwt()
        .jwt(
            j -> {
              j.subject(userId.toString()).claim("tenant_id", tenant.toString());
              j.claim("roles", List.of(roles));
              if (unit != null) {
                j.claim("unit_id", unit.toString());
              }
            });
  }

  /** Mô tả một tiến trình cần khởi tạo. */
  public record Start(
      UUID tenant,
      String businessType,
      UUID businessId,
      UUID initiator,
      UUID assignee,
      String candidateGroup,
      UUID level2,
      UUID period,
      Instant dueAt,
      String title) {}

  /** Khởi tạo tiến trình dùng chung định nghĩa (không gắn tenant), tenant đặt trên thể hiện. */
  protected ProcessInstance start(Start s) {
    Map<String, Object> vars = new HashMap<>();
    vars.put("assigneeId", s.assignee() == null ? null : s.assignee().toString());
    vars.put("candidateGroup", s.candidateGroup() == null ? "" : s.candidateGroup());
    vars.put("level2Id", s.level2() == null ? UUID.randomUUID().toString() : s.level2().toString());
    vars.put("dueAt", s.dueAt() == null ? null : Date.from(s.dueAt()));
    vars.put("title", s.title() == null ? "Phê duyệt OKR Quý I/2027" : s.title());
    if (s.period() != null) {
      vars.put("periodId", s.period().toString());
    }
    Authentication.setAuthenticatedUserId(s.initiator() == null ? null : s.initiator().toString());
    try {
      return runtime
          .createProcessInstanceBuilder()
          .processDefinitionKey(PROCESS_KEY)
          .businessKey(s.businessType() + ":" + s.businessId())
          .overrideProcessDefinitionTenantId(s.tenant().toString())
          .variables(vars)
          .start();
    } finally {
      Authentication.setAuthenticatedUserId(null);
    }
  }

  protected Task openTask(ProcessInstance instance) {
    return tasks.createTaskQuery().processInstanceId(instance.getId()).singleResult();
  }
}
