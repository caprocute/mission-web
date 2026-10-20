package vn.viettel.okrkpi.org.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.org.application.port.IdempotencyPort;
import vn.viettel.okrkpi.org.application.port.OrgUnitStore;
import vn.viettel.okrkpi.org.application.port.PolicyPort;
import vn.viettel.okrkpi.org.application.port.RelationStore;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.OrgRuleViolation;
import vn.viettel.okrkpi.org.domain.OrgUnitVersion;
import vn.viettel.okrkpi.org.domain.Timeline;
import vn.viettel.okrkpi.org.domain.TreeKind;
import vn.viettel.okrkpi.org.domain.VersionConflictException;
import vn.viettel.okrkpi.org.domain.VersionPlan;

/**
 * Sửa đơn vị (SCR-0103 "Sửa", {@code updateOrgUnit}): tạo phiên bản mới từ ngày hiệu lực, không ghi đè (BR-01-06);
 * If-Match theo {@code row_version}, Idempotency-Key, chặn vòng, mã duy nhất, BR-01-05; EVT-14 qua outbox trong cùng
 * giao dịch.
 */
@Service
public class OrgUnitCommandService {

  private final OrgUnitStore units;
  private final RelationStore relations;
  private final IdempotencyPort idempotency;
  private final PolicyPort policy;
  private final UnitSupport support;

  public OrgUnitCommandService(OrgUnitStore units, RelationStore relations, IdempotencyPort idempotency,
      PolicyPort policy, UnitSupport support) {
    this.units = units;
    this.relations = relations;
    this.idempotency = idempotency;
    this.policy = policy;
    this.support = support;
  }

  @Transactional
  public OrgUnitView update(Actor actor, UUID id, int expectedVersion, IdempotencyRequest idem, UnitCommand cmd) {
    policy.authorize(actor, "updateOrgUnit");
    Optional<OrgUnitView> replay = idempotency.begin(actor, idem, OrgUnitView.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    List<OrgUnitVersion> current = units.currentVersionsForUpdate(id);
    if (current.isEmpty()) {
      throw new OrgNotFoundException("Không tìm thấy đơn vị.");
    }
    int actual = units.entityVersion(id, null).orElse(0);
    if (actual != expectedVersion) {
      throw new VersionConflictException(expectedVersion, actual);
    }
    LocalDate from = cmd.validFrom();
    OrgUnitVersion base = Timeline.versionAt(current, from).orElseThrow(() -> new OrgRuleViolation(
        "ORG-422-NOT-VALID-AT-DATE", "BR-01-06", null, "validFrom",
        "Đơn vị chưa có hiệu lực tại ngày bắt đầu của thay đổi."));
    LocalDate to = cmd.validTo() != null ? cmd.validTo() : base.validPeriod().to();
    if (to != null && !from.isBefore(to)) {
      throw new OrgRuleViolation("ORG-422-VALIDATION", null, null, "validTo",
          "Ngày hết hiệu lực phải sau ngày bắt đầu hiệu lực.");
    }
    support.checkContent(id, cmd.content(), from, to, "");
    VersionPlan<OrgUnitVersion> plan = Timeline.applyChange(current, from, to, 
        v -> v.withContent(cmd.content().withCollectiveSubject(v.content().collectiveSubject()))
            .withChangeOrder(null));
    support.writeUnit(id, plan, null, actor);
    if (cmd.parentId() != null) {
      TreeKind tree = support.treeOf(id, base.content().unitType(), new TemporalQuery(from, null));
      support.reparent(id, tree, cmd.parentId(), from, to, relations.currentTouchingForUpdate(List.of(id)), null,
          actor);
    }
    support.emitUnitChanged(id, null, actor);
    TemporalQuery after = new TemporalQuery(from, null);
    OrgUnitView view = support.view(units.findAt(id, after).orElseThrow(), after);
    idempotency.complete(actor, idem, 200, view, id.toString());
    return view;
  }
}
