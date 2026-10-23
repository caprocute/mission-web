package vn.viettel.okrkpi.workflow.application.periodclose;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.application.saga.StepInvocation;

/** Tham số saga chốt kỳ do {@code startPeriodCloseSaga} ghi vào {@code saga_instance.context}. */
public record PeriodCloseRequest(
    UUID sagaId,
    UUID tenantId,
    UUID periodId,
    UUID lockOrderId,
    List<UUID> unitIds,
    UUID requestedBy) {

  public PeriodCloseRequest {
    Objects.requireNonNull(sagaId, "sagaId");
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(periodId, "periodId");
    Objects.requireNonNull(lockOrderId, "lockOrderId");
    unitIds = List.copyOf(Objects.requireNonNullElse(unitIds, List.of()));
  }

  public static PeriodCloseRequest of(StepInvocation invocation) {
    Map<String, Object> p = invocation.parameters();
    Object units = p.get("unitIds");
    List<UUID> unitIds =
        units instanceof List<?> list
            ? list.stream().map(Object::toString).map(UUID::fromString).toList()
            : List.of();
    return new PeriodCloseRequest(
        invocation.sagaId(),
        invocation.tenantId(),
        uuid(p.get("periodId")),
        uuid(p.get("lockOrderId")),
        unitIds,
        uuid(p.get("requestedBy")));
  }

  private static UUID uuid(Object value) {
    return value == null ? null : UUID.fromString(value.toString());
  }
}
