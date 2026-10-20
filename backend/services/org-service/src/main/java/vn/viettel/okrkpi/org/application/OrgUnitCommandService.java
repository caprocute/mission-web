package vn.viettel.okrkpi.org.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.org.application.port.AssignmentStore;
import vn.viettel.okrkpi.org.application.port.ChangeOrderStore;
import vn.viettel.okrkpi.org.application.port.IdempotencyPort;
import vn.viettel.okrkpi.org.application.port.OrgUnitStore;
import vn.viettel.okrkpi.org.application.port.PeriodLockPort;
import vn.viettel.okrkpi.org.application.port.PolicyPort;
import vn.viettel.okrkpi.org.application.port.RelationStore;
import vn.viettel.okrkpi.org.domain.AssignmentVersion;
import vn.viettel.okrkpi.org.domain.OrgChangeRules;
import vn.viettel.okrkpi.org.domain.OrgChangeType;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.OrgRuleViolation;
import vn.viettel.okrkpi.org.domain.OrgUnitVersion;
import vn.viettel.okrkpi.org.domain.Timeline;
import vn.viettel.okrkpi.org.domain.TreeKind;
import vn.viettel.okrkpi.org.domain.UnitRelationVersion;
import vn.viettel.okrkpi.org.domain.UuidV7;
import vn.viettel.okrkpi.org.domain.ValidPeriod;
import vn.viettel.okrkpi.org.domain.VersionConflictException;
import vn.viettel.okrkpi.org.domain.VersionPlan;

/**
 * Sửa đơn vị ({@code updateOrgUnit}) và thay đổi tổ chức theo quyết định ({@code changeOrgUnit}, SCR-0103): tạo phiên
 * bản mới từ ngày hiệu lực, không ghi đè (BR-01-06); kiểm tra BR-01-05, BR-01-07 (MSG-01-09), cảnh báo kỳ đã khoá
 * (MSG-01-10), chuyển phân công (MSG-01-11); phát EVT-14/EVT-15 qua outbox trong cùng giao dịch.
 */
@Service
public class OrgUnitCommandService {

  private final OrgUnitStore units;
  private final RelationStore relations;
  private final ChangeOrderStore orders;
  private final AssignmentStore assignments;
  private final PeriodLockPort periodLocks;
  private final IdempotencyPort idempotency;
  private final PolicyPort policy;
  private final UnitSupport support;
  private final Clock clock;

  public OrgUnitCommandService(OrgUnitStore units, RelationStore relations, ChangeOrderStore orders,
      AssignmentStore assignments, PeriodLockPort periodLocks, IdempotencyPort idempotency,
      PolicyPort policy, UnitSupport support, Clock clock) {
    this.units = units;
    this.relations = relations;
    this.orders = orders;
    this.assignments = assignments;
    this.periodLocks = periodLocks;
    this.idempotency = idempotency;
    this.policy = policy;
    this.support = support;
    this.clock = clock;
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

  @Transactional
  public ChangeResult change(Actor actor, UUID id, IdempotencyRequest idem, ChangeCommand cmd) {
    policy.authorize(actor, "changeOrgUnit");
    Optional<ChangeResult> replay = idempotency.begin(actor, idem, ChangeResult.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    OrgChangeRules.validate(cmd.type(), id, cmd.relatedUnitIds(), cmd.newUnits().size(), cmd.newName());
    LocalDate eff = cmd.effectiveOn();
    List<OrgUnitVersion> targetCurrent = units.currentVersionsForUpdate(id);
    if (targetCurrent.isEmpty()) {
      throw new OrgNotFoundException("Không tìm thấy đơn vị.");
    }
    Timeline.versionAt(targetCurrent, eff).orElseThrow(() -> new OrgRuleViolation(
        "ORG-422-NOT-VALID-AT-DATE", "BR-01-06", null, "effectiveOn",
        "Đơn vị chưa có hiệu lực tại ngày hiệu lực của quyết định."));

    Map<UUID, List<OrgUnitVersion>> relatedCurrent = new LinkedHashMap<>();
    for (UUID relId : cmd.relatedUnitIds()) {
      List<OrgUnitVersion> rc = units.currentVersionsForUpdate(relId);
      if (rc.isEmpty() || Timeline.versionAt(rc, eff).isEmpty()) {
        throw new OrgRuleViolation("ORG-422-NOT-VALID-AT-DATE", "BR-01-07", null, "relatedUnitIds",
            "Đơn vị liên quan chưa có hiệu lực tại ngày hiệu lực của quyết định.");
      }
      relatedCurrent.put(relId, rc);
    }

    UUID orderId = UuidV7.next(clock);
    List<UUID> createdUnitIds = new ArrayList<>(cmd.newUnits().size());
    for (int i = 0; i < cmd.newUnits().size(); i++) {
      createdUnitIds.add(UuidV7.next(clock));
    }

    List<UUID> predecessors = switch (cmd.type()) {
      case SAP_NHAP -> cmd.relatedUnitIds();
      case HOP_NHAT -> concat(List.of(id), cmd.relatedUnitIds());
      case CHIA_TACH -> List.of(id);
      default -> List.of();
    };
    List<UUID> successors = switch (cmd.type()) {
      case SAP_NHAP -> List.of(id);
      case HOP_NHAT -> createdUnitIds;
      case CHIA_TACH -> concat(createdUnitIds, cmd.relatedUnitIds());
      default -> List.of();
    };

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("changeType", cmd.type().name());
    payload.put("targetUnitId", id.toString());
    payload.put("predecessorUnitIds", predecessors.stream().map(UUID::toString).toList());
    payload.put("successorUnitIds", successors.stream().map(UUID::toString).toList());
    payload.put("createdUnitIds", createdUnitIds.stream().map(UUID::toString).toList());

    ChangeOrderRecord order = orders.insert(new ChangeOrderRecord(orderId, cmd.orderNo().trim(), cmd.orderDate(),
        cmd.type().orderKind(), eff, cmd.signedDocRef(), payload, "DA_AP_DUNG", null), actor);

    Set<UUID> affected = new LinkedHashSet<>();
    for (int i = 0; i < cmd.newUnits().size(); i++) {
      UnitCommand u = cmd.newUnits().get(i);
      UUID newId = createdUnitIds.get(i);
      LocalDate uFrom = u.validFrom() != null && u.validFrom().isAfter(eff) ? u.validFrom() : eff;
      if (u.validTo() != null && !uFrom.isBefore(u.validTo())) {
        throw new OrgRuleViolation("ORG-422-VALIDATION", null, null, "newUnits.validTo",
            "Ngày hết hiệu lực phải sau ngày bắt đầu hiệu lực.");
      }
      support.createUnit(newId, u.content(), new ValidPeriod(uFrom, u.validTo()), orderId, actor);
      UUID parentId = u.parentId();
      if (parentId == null && cmd.type() == OrgChangeType.THANH_LAP) {
        parentId = id;
      }
      if (parentId != null) {
        TreeKind tree = TreeKind.defaultFor(u.content().unitType());
        support.reparent(newId, tree, parentId, uFrom, u.validTo(), List.of(), orderId, actor);
      }
      affected.add(newId);
    }

    switch (cmd.type()) {
      case THANH_LAP -> {
        // Đơn vị mới đã được tạo ở trên.
      }
      case DOI_TEN -> {
        String newName = cmd.newName().trim();
        VersionPlan<OrgUnitVersion> plan = Timeline.applyChange(targetCurrent, eff, null,
            v -> v.withContent(v.content().withName(newName)).withChangeOrder(orderId));
        support.writeUnit(id, plan, orderId, actor);
        affected.add(id);
      }
      case SAP_NHAP -> {
        VersionPlan<OrgUnitVersion> keepPlan = Timeline.applyChange(targetCurrent, eff, null,
            v -> v.withChangeOrder(orderId));
        support.writeUnit(id, keepPlan, orderId, actor);
        affected.add(id);
        endUnits(relatedCurrent, eff, orderId, actor, affected);
      }
      case HOP_NHAT -> {
        Map<UUID, List<OrgUnitVersion>> toEnd = new LinkedHashMap<>();
        toEnd.put(id, targetCurrent);
        toEnd.putAll(relatedCurrent);
        endUnits(toEnd, eff, orderId, actor, affected);
      }
      case CHIA_TACH -> {
        endUnits(Map.of(id, targetCurrent), eff, orderId, actor, affected);
        affected.addAll(cmd.relatedUnitIds());
      }
      case GIAI_THE -> {
        if (relations.hasChildrenFrom(id, eff)) {
          throw new OrgRuleViolation("ORG-422-HAS-ACTIVE-CHILDREN", null, null, "id",
              "Không thể giải thể đơn vị còn đơn vị cấp dưới đang hiệu lực.");
        }
        endUnits(Map.of(id, targetCurrent), eff, orderId, actor, affected);
      }
      default -> throw new IllegalStateException(cmd.type().name());
    }

    int movedCount = 0;
    if (cmd.moveAssignmentsTo() != null) {
      UUID destId = (cmd.type() == OrgChangeType.HOP_NHAT && cmd.moveAssignmentsTo().equals(id)
          && !createdUnitIds.isEmpty()) ? createdUnitIds.get(0) : cmd.moveAssignmentsTo();
      if (units.findAt(destId, new TemporalQuery(eff, null)).isEmpty()) {
        throw new OrgRuleViolation("ORG-422-MOVE-TARGET-INVALID", null, null, "moveAssignmentsTo",
            "Đơn vị nhận phân công không có hiệu lực tại ngày hiệu lực.");
      }
      List<UUID> sourceUnits = switch (cmd.type()) {
        case SAP_NHAP -> cmd.relatedUnitIds();
        case HOP_NHAT -> concat(List.of(id), cmd.relatedUnitIds());
        default -> concat(List.of(id), cmd.relatedUnitIds()).stream().filter(u -> !u.equals(destId)).toList();
      };
      movedCount = moveAssignments(sourceUnits, destId, eff, orderId, actor);
    }

    for (UUID uId : affected) {
      support.emitUnitChanged(uId, orderId, actor);
    }

    String lockedPeriod = periodLocks.lockedPeriodLabel(actor.tenantId(), eff).orElse(null);
    List<UUID> affectedList = List.copyOf(affected);
    ChangeResult result = new ChangeResult(order.id(), cmd.type(), order.orderNo(), eff, order.status(),
        order.appliedAt(), affectedList, createdUnitIds, movedCount, lockedPeriod);
    idempotency.complete(actor, idem, 201, result, order.id().toString());
    return result;
  }

  private void endUnits(Map<UUID, List<OrgUnitVersion>> toEnd, LocalDate eff, UUID orderId, Actor actor,
      Set<UUID> affected) {
    if (toEnd.isEmpty()) {
      return;
    }
    List<UnitRelationVersion> touching = relations.currentTouchingForUpdate(toEnd.keySet());
    if (!touching.isEmpty()) {
      VersionPlan<UnitRelationVersion> relPlan = Timeline.endAt(touching, eff);
      if (!relPlan.isEmpty()) {
        relations.apply(relPlan, orderId, actor);
      }
    }
    for (var entry : toEnd.entrySet()) {
      VersionPlan<OrgUnitVersion> plan = Timeline.endAt(entry.getValue(), eff);
      support.writeUnit(entry.getKey(), plan, orderId, actor);
      affected.add(entry.getKey());
    }
  }

  private int moveAssignments(List<UUID> sourceUnits, UUID destId, LocalDate eff, UUID orderId, Actor actor) {
    List<AssignmentVersion> active = assignments.currentAtUnitsFromForUpdate(sourceUnits, eff);
    if (active.isEmpty()) {
      return 0;
    }
    Map<UUID, List<AssignmentVersion>> byId = new LinkedHashMap<>();
    for (AssignmentVersion a : active) {
      byId.computeIfAbsent(a.id(), k -> new ArrayList<>()).add(a);
    }
    List<UUID> toClose = new ArrayList<>();
    List<AssignmentVersion> toInsert = new ArrayList<>();
    List<UUID> changedAssignIds = new ArrayList<>();
    int moved = 0;
    for (var entry : byId.entrySet()) {
      UUID oldId = entry.getKey();
      List<AssignmentVersion> slices = entry.getValue();
      VersionPlan<AssignmentVersion> endPlan = Timeline.endAt(slices, eff);
      toClose.addAll(endPlan.toClose());
      toInsert.addAll(endPlan.toInsert());
      changedAssignIds.add(oldId);
      UUID newId = UuidV7.next(clock);
      boolean anyMoved = false;
      for (AssignmentVersion s : slices) {
        var rest = s.validPeriod().intersect(eff, null);
        if (rest.isPresent()) {
          toInsert.add(s.movedTo(newId, destId, rest.get()));
          anyMoved = true;
        }
      }
      if (anyMoved) {
        changedAssignIds.add(newId);
        moved++;
      }
    }
    assignments.apply(new VersionPlan<>(toClose, toInsert), orderId, actor);
    for (UUID assignId : changedAssignIds) {
      support.emitAssignmentChanged(assignId, orderId, actor);
    }
    return moved;
  }

  private static List<UUID> concat(List<UUID> a, List<UUID> b) {
    List<UUID> out = new ArrayList<>(a.size() + b.size());
    out.addAll(a);
    out.addAll(b);
    return out;
  }
}
