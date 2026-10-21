package vn.viettel.okrkpi.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Kiểm thử tích hợp khởi động Flowable 7 trên PostgreSQL 17 thật qua Flyway V1 + V2 (DV-06-01 ·
 * TL-01 mục 8.7 · TL-03 mục 6.13).
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@AutoConfigureObservability
@Testcontainers
class FlowableBootstrapIT {

  private static final String MINIMAL_BPMN =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                   xmlns:flowable="http://flowable.org/bpmn"
                   targetNamespace="https://okrkpi.vn/bpmn/test">
        <process id="testBootstrapProcess" name="Quy trình kiểm thử khởi động" isExecutable="true">
          <startEvent id="start"/>
          <sequenceFlow id="f1" sourceRef="start" targetRef="userTask1"/>
          <userTask id="userTask1" name="Bước duyệt kiểm thử" flowable:assignee="can-bo-1"/>
          <sequenceFlow id="f2" sourceRef="userTask1" targetRef="end"/>
          <endEvent id="end"/>
        </process>
      </definitions>
      """;

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("mission_wf_test")
          .withUsername("postgres")
          .withPassword("postgres");

  @DynamicPropertySource
  static void registerPgProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.flyway.schemas", () -> "flowable");
    registry.add("spring.flyway.default-schema", () -> "flowable");
    registry.add("spring.flyway.locations", () -> "classpath:db/migration/flowable");
    registry.add("flowable.database-schema", () -> "flowable");
    registry.add("flowable.database-schema-update", () -> "false");
    registry.add("flowable.async-executor-activate", () -> "false");
    registry.add("flowable.check-process-definitions", () -> "false");
    registry.add("management.server.port", () -> "8080");
  }

  @Autowired private RepositoryService repositoryService;
  @Autowired private RuntimeService runtimeService;
  @Autowired private TaskService taskService;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private MockMvc mockMvc;

  @Test
  void flywayV1V2TaoDuBangChiMucFillfactorVaStrongUuidGeneratorHoatDong() {
    List<String> indexes =
        jdbcClient
            .sql(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'flowable'
                  AND indexname IN (
                    'ix_act_ru_task__tenant_assignee',
                    'ix_act_ru_task__tenant_create_time',
                    'ix_act_ru_variable__proc_inst_name'
                  )
                ORDER BY indexname
                """)
            .query(String.class)
            .list();
    assertThat(indexes)
        .containsExactly(
            "ix_act_ru_task__tenant_assignee",
            "ix_act_ru_task__tenant_create_time",
            "ix_act_ru_variable__proc_inst_name");

    List<String> relOptions =
        jdbcClient
            .sql(
                """
                SELECT c.relname || ':' || array_to_string(c.reloptions, ',')
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'flowable'
                  AND c.relname IN ('act_ru_execution', 'act_ru_task')
                ORDER BY c.relname
                """)
            .query(String.class)
            .list();
    assertThat(relOptions)
        .containsExactly("act_ru_execution:fillfactor=70", "act_ru_task:fillfactor=70");

    String tenantId = UUID.randomUUID().toString();
    repositoryService
        .createDeployment()
        .name("test-bootstrap")
        .tenantId(tenantId)
        .addString("test-bootstrap.bpmn20.xml", MINIMAL_BPMN)
        .deploy();

    ProcessInstance instance =
        runtimeService.startProcessInstanceByKeyAndTenantId("testBootstrapProcess", tenantId);
    assertThat(UUID.fromString(instance.getId())).isNotNull();

    Task task =
        taskService
            .createTaskQuery()
            .processInstanceId(instance.getId())
            .taskTenantId(tenantId)
            .singleResult();
    assertThat(task).isNotNull();
    assertThat(UUID.fromString(task.getId())).isNotNull();
    assertThat(task.getAssignee()).isEqualTo("can-bo-1");

    taskService.complete(task.getId());
    assertThat(
            runtimeService.createProcessInstanceQuery().processInstanceId(instance.getId()).count())
        .isZero();
  }

  @Test
  void actuatorProbesVaChiSoPrometheusFlowableHoatDongTrenPostgres() throws Exception {
    mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());

    MvcResult prom =
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk()).andReturn();
    assertThat(prom.getResponse().getContentAsString())
        .contains("flowable_async_jobs_due_unlocked");
  }
}
