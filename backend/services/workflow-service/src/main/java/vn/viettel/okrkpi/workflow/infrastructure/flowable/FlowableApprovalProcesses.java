package vn.viettel.okrkpi.workflow.infrastructure.flowable;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.workflow.application.port.ApprovalProcesses;
import vn.viettel.okrkpi.workflow.domain.BusinessKey;
import vn.viettel.okrkpi.workflow.domain.approval.ApprovalVariables;

/**
 * {@link ApprovalProcesses} trên engine Flowable. Định nghĩa triển khai không gắn tenant; tenant
 * đặt trên thể hiện bằng {@code overrideProcessDefinitionTenantId}, nên số định nghĩa không tăng
 * theo số tenant (TL-01 G2 quy tắc 1). Khoá khởi tạo dùng khoá tư vấn giao dịch của PostgreSQL.
 */
@Component
public class FlowableApprovalProcesses implements ApprovalProcesses {

  private final RuntimeService runtime;
  private final HistoryService history;
  private final TaskService tasks;
  private final JdbcTemplate jdbc;

  public FlowableApprovalProcesses(
      RuntimeService runtime, HistoryService history, TaskService tasks, JdbcTemplate jdbc) {
    this.runtime = runtime;
    this.history = history;
    this.tasks = tasks;
    this.jdbc = jdbc;
  }

  @Override
  public void lockStart(UUID tenantId, String templateCode, BusinessKey businessKey) {
    long key =
        UUID.nameUUIDFromBytes(
                ("wf-start:" + tenantId + ':' + templateCode + ':' + businessKey.value())
                    .getBytes(StandardCharsets.UTF_8))
            .getMostSignificantBits();
    jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> {}, key);
  }

  @Override
  public Optional<String> findStarted(
      UUID tenantId, String templateCode, BusinessKey businessKey, long objectVersion) {
    return history
        .createHistoricProcessInstanceQuery()
        .processDefinitionKey(templateCode)
        .processInstanceTenantId(tenantId.toString())
        .processInstanceBusinessKey(businessKey.value())
        .variableValueEquals(ApprovalVariables.PHIEN_BAN_DOI_TUONG, objectVersion)
        .list()
        .stream()
        .map(HistoricProcessInstance::getId)
        .findFirst();
  }

  @Override
  public String start(
      UUID tenantId,
      String templateCode,
      BusinessKey businessKey,
      UUID initiatorId,
      Map<String, Object> variables) {
    Authentication.setAuthenticatedUserId(initiatorId == null ? null : initiatorId.toString());
    try {
      return runtime
          .createProcessInstanceBuilder()
          .processDefinitionKey(templateCode)
          .overrideProcessDefinitionTenantId(tenantId.toString())
          .businessKey(businessKey.value())
          .variables(variables)
          .start()
          .getId();
    } finally {
      Authentication.setAuthenticatedUserId(null);
    }
  }

  @Override
  public int cancelWaiting(
      UUID tenantId, String templateCode, BusinessKey businessKey, String reason) {
    int cancelled = 0;
    for (ProcessInstance p :
        runtime
            .createProcessInstanceQuery()
            .processDefinitionKey(templateCode)
            .processInstanceTenantId(tenantId.toString())
            .processInstanceBusinessKey(businessKey.value())
            .list()) {
      if (tasks.createTaskQuery().processInstanceId(p.getId()).count() > 0) {
        runtime.deleteProcessInstance(p.getId(), reason);
        cancelled++;
      }
    }
    return cancelled;
  }
}
