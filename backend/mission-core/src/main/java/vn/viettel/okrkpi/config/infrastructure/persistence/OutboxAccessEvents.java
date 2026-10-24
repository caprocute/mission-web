package vn.viettel.okrkpi.config.infrastructure.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.config.application.port.AccessEvents;
import vn.viettel.okrkpi.config.domain.Delegation;
import vn.viettel.okrkpi.config.domain.RoleBinding;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.EventEnvelope;
import vn.viettel.okrkpi.shared.outbox.OutboxWriter;
import vn.vtokr.config.v1.AccessChange;
import vn.vtokr.config.v1.VaiTroDaThayDoi;
import vn.vtokr.sys.v1.AuditEvent;

/**
 * EVT-27 {@code config.access.changed.v1} và sự kiện kiểm toán {@code sys.audit.v1} của mỗi thay
 * đổi quyền vào {@code config.outbox_event} trong cùng giao dịch (CORE-08-16). Sự kiện không mang
 * lý do; người cấp, người duyệt nằm ở tiêu đề {@code actorid}. Tác vụ hết hạn ghi {@code system}.
 */
public class OutboxAccessEvents implements AccessEvents {

  public static final String ROLE_AGGREGATE = "role_binding";
  public static final String DELEGATION_AGGREGATE = "delegation";

  private final OutboxWriter outbox;

  public OutboxAccessEvents(OutboxWriter outbox) {
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  @Override
  public void roleChanged(
      TenantId tenantId,
      vn.viettel.okrkpi.config.domain.AccessChange change,
      RoleBinding b,
      UUID actorId,
      Instant now) {
    VaiTroDaThayDoi payload =
        VaiTroDaThayDoi.newBuilder()
            .setChange(AccessChange.valueOf(change.name()))
            .setRefId(b.id())
            .setPersonId(b.personId())
            .setRoleCode(b.roleCode())
            .setDelegationKind(null)
            .setDelegatorId(null)
            .setOrgUnitId(b.orgUnitId())
            .setScope(b.scope().name())
            .setFlow(b.flow())
            .setValidFrom(b.validFrom())
            .setValidTo(b.validTo())
            .setEffective(b.effective(now))
            .setAggregateVersion(b.rowVersion())
            .build();
    append(tenantId, ROLE_AGGREGATE, b.id(), b.rowVersion(), payload, actorId);
    audit(tenantId, change, "config.role_binding", b.id(), b.rowVersion(), actorId);
  }

  @Override
  public void customRoleChanged(
      TenantId tenantId, String action, UUID roleId, int version, UUID actorId) {
    audit(tenantId, action, "config.custom_role", roleId, version, actorId);
  }

  @Override
  public void delegationChanged(
      TenantId tenantId,
      vn.viettel.okrkpi.config.domain.AccessChange change,
      Delegation d,
      UUID actorId,
      Instant now) {
    VaiTroDaThayDoi payload =
        VaiTroDaThayDoi.newBuilder()
            .setChange(AccessChange.valueOf(change.name()))
            .setRefId(d.id())
            .setPersonId(d.delegateId())
            .setRoleCode(null)
            .setDelegationKind(d.kind().name())
            .setDelegatorId(d.delegatorId())
            .setOrgUnitId(d.orgUnitId())
            .setScope(null)
            .setFlow(null)
            .setValidFrom(d.validFrom())
            .setValidTo(d.validTo())
            .setEffective(d.effective(now))
            .setAggregateVersion(d.rowVersion())
            .build();
    append(tenantId, DELEGATION_AGGREGATE, d.id(), d.rowVersion(), payload, actorId);
    audit(tenantId, change, "config.delegation", d.id(), d.rowVersion(), actorId);
  }

  private void append(
      TenantId tenantId,
      String aggregate,
      UUID id,
      int version,
      VaiTroDaThayDoi payload,
      UUID actorId) {
    EventEnvelope e =
        EventEnvelope.of(
            EventTopics.EVT_27_VAI_TRO_DA_THAY_DOI,
            tenantId,
            aggregate,
            id,
            version,
            payload,
            Classification.C1);
    outbox.append(actorId == null ? e : e.withActor(ActorId.of(actorId)));
  }

  private void audit(
      TenantId tenantId,
      vn.viettel.okrkpi.config.domain.AccessChange change,
      String objectType,
      UUID id,
      int version,
      UUID actorId) {
    audit(tenantId, change.name(), objectType, id, version, actorId);
  }

  private void audit(
      TenantId tenantId, String action, String objectType, UUID id, int version, UUID actorId) {
    AuditEvent payload =
        AuditEvent.newBuilder()
            .setAction(action)
            .setObjectType(objectType)
            .setObjectId(id.toString())
            .setOutcome("THANH_CONG")
            .setPurpose(null)
            .setActorAssignmentId(null)
            .setActorRole(null)
            .setSystem(actorId == null)
            .setTargets(null)
            .build();
    EventEnvelope e =
        EventEnvelope.of(
            EventTopics.SYS_AUDIT_V1,
            tenantId,
            objectType,
            id,
            version,
            payload,
            Classification.C1);
    outbox.append(actorId == null ? e : e.withActor(ActorId.of(actorId)));
  }
}
