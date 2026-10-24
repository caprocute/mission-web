package vn.viettel.okrkpi.org.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.viettel.okrkpi.org.application.port.AssignmentStore;
import vn.viettel.okrkpi.org.application.port.ChangeOrderStore;
import vn.viettel.okrkpi.org.application.port.OrgUnitStore;
import vn.viettel.okrkpi.org.application.port.OutboxPort;
import vn.viettel.okrkpi.org.application.port.PersonDirectory;
import vn.viettel.okrkpi.org.application.port.RelationStore;
import vn.viettel.okrkpi.org.application.port.SubjectGroupStore;
import vn.viettel.okrkpi.org.domain.AssignmentVersion;
import vn.viettel.okrkpi.org.domain.OrgChangeRules;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.OrgRuleViolation;
import vn.viettel.okrkpi.org.domain.OrgUnitContent;
import vn.viettel.okrkpi.org.domain.OrgUnitVersion;
import vn.viettel.okrkpi.org.domain.Timeline;
import vn.viettel.okrkpi.org.domain.TreeKind;
import vn.viettel.okrkpi.org.domain.UnitRelationVersion;
import vn.viettel.okrkpi.org.domain.UuidV7;
import vn.viettel.okrkpi.org.domain.ValidPeriod;
import vn.viettel.okrkpi.org.domain.VersionPlan;

/**
 * Thành phần dùng chung của các use case đơn vị: dựng phần hiển thị, kiểm quy tắc khi ghi (mã duy nhất, khối đối
 * tượng, chặn vòng) và phát EVT-14/EVT-15 qua outbox. Được gọi bên trong giao dịch của use case.
 */
@Component
public class UnitSupport {

  private final OrgUnitStore units;
  private final RelationStore relations;
  private final AssignmentStore assignments;
  private final SubjectGroupStore groups;
  private final ChangeOrderStore orders;
  private final PersonDirectory persons;
  private final OutboxPort outbox;
  private final Clock clock;

  public UnitSupport(OrgUnitStore units, RelationStore relations, AssignmentStore assignments,
      SubjectGroupStore groups, ChangeOrderStore orders, PersonDirectory persons, OutboxPort outbox, Clock clock) {
    this.units = units;
    this.relations = relations;
    this.assignments = assignments;
    this.groups = groups;
    this.orders = orders;
    this.persons = persons;
    this.outbox = outbox;
    this.clock = clock;
  }

  public LocalDate today() {
    return LocalDate.now(clock);
  }

  public TemporalQuery resolve(LocalDate asOf, java.time.OffsetDateTime recordedAsOf) {
    return new TemporalQuery(asOf == null ? today() : asOf, recordedAsOf);
  }

  /** Cây chứa quan hệ trực thuộc của đơn vị; khi có cả hai hoặc không có, dùng cây mặc định theo loại đơn vị. */
  public TreeKind treeOf(UUID unitId, String unitType, TemporalQuery query) {
    Set<TreeKind> trees = relations.parentTreesAt(unitId, query);
    return trees.size() == 1 ? trees.iterator().next() : TreeKind.defaultFor(unitType);
  }

  public OrgUnitView view(OrgUnitVersion version, TemporalQuery query) {
    UUID id = version.id();
    TreeKind tree = treeOf(id, version.content().unitType(), query);
    UUID parentId = relations.parentAt(id, tree, query).map(UnitRelationVersion::parentUnitId).orElse(null);
    String parentName = parentId == null ? null
        : units.findAt(parentId, query).map(v -> v.content().name()).orElse(null);
    UUID groupId = version.content().subjectGroupId();
    String groupName = groupId == null ? null : groups.names(List.of(groupId)).get(groupId);
    String recordedBy = version.createdBy() == null ? null
        : persons.currentNames(List.of(version.createdBy())).get(version.createdBy());
    int rowVersion = units.entityVersion(id, query.recordedAsOf()).orElse(version.rowVersion());
    return new OrgUnitView(version, parentId, parentName, groupName, orders.predecessorsOf(id), recordedBy,
        assignments.countAt(id, query), rowVersion);
  }

  /** BR-01-05 và mã đơn vị duy nhất trong tenant tại thời điểm hiệu lực (TL-02a SCR-0103 trường 3, 7). */
  public void checkContent(UUID unitId, OrgUnitContent content, LocalDate from, LocalDate to, String fieldPrefix) {
    OrgChangeRules.requireSubjectGroup(content.subjectGroupId(),
        content.subjectGroupId() != null && groups.isValid(content.subjectGroupId(), from),
        fieldPrefix + "subjectGroupId");
    units.lockCode(content.code());
    if (units.codeTakenByOther(content.code(), unitId, from, to)) {
      throw new OrgRuleViolation("ORG-422-CODE-TAKEN", null, null, fieldPrefix + "code",
          "Mã đơn vị đã được dùng trong tenant tại thời điểm hiệu lực.");
    }
  }

  /** Ghi một khoảng hiệu lực mới cho đơn vị (luôn tăng {@code row_version}). */
  public void writeUnit(UUID unitId, VersionPlan<OrgUnitVersion> plan, UUID changeOrderId, Actor actor) {
    if (plan.isEmpty()) {
      return;
    }
    int next = units.entityVersion(unitId, null).orElse(-1) + 1;
    units.apply(plan, next, changeOrderId, actor);
  }

  /** Tạo đơn vị mới với phiên bản đầu tiên từ {@code from}. */
  public UUID createUnit(UUID id, OrgUnitContent content, ValidPeriod period, UUID changeOrderId, Actor actor) {
    checkContent(id, content, period.from(), period.to(), "newUnits.");
    units.apply(new VersionPlan<>(List.of(), List.of(OrgUnitVersion.draft(id, content, period))), 0, changeOrderId,
        actor);
    return id;
  }

  /**
   * Đặt cấp trên trực thuộc mới trong {@code [from, to)} trên cây {@code tree}, chặn vòng (TL-02a SCR-0103 trường 11):
   * cấp trên mới không được thuộc cây con của đơn vị tại mọi thời điểm hiệu lực giao nhau.
   */
  public boolean reparent(UUID child, TreeKind tree, UUID newParent, LocalDate from, LocalDate to,
      List<UnitRelationVersion> currentRelations, UUID changeOrderId, Actor actor) {
    if (child.equals(newParent)) {
      throw cycle();
    }
    if (units.findAt(newParent, new TemporalQuery(from, null)).isEmpty()) {
      throw new OrgRuleViolation("ORG-422-PARENT-INVALID", null, null, "parentId",
          "Đơn vị cấp trên không có hiệu lực tại ngày hiệu lực.");
    }
    List<UnitRelationVersion> onTree = currentRelations.stream()
        .filter(r -> r.childUnitId().equals(child) && r.tree() == tree
            && UnitRelationVersion.TRUC_THUOC.equals(r.relationType()))
        .toList();
    boolean unchanged = !onTree.isEmpty() && onTree.stream()
        .filter(r -> r.validPeriod().overlaps(from, to))
        .allMatch(r -> r.parentUnitId().equals(newParent)
            && !r.validPeriod().from().isAfter(from)
            && (r.validPeriod().to() == null || (to != null && !r.validPeriod().to().isBefore(to))));
    if (unchanged) {
      return false;
    }
    if (relations.wouldCreateCycle(tree, child, newParent, from, to)) {
      throw cycle();
    }
    VersionPlan<UnitRelationVersion> plan = Timeline.removeRange(onTree, from, to)
        .plusInsert(UnitRelationVersion.draftParent(UuidV7.next(clock), tree, newParent, child,
            new ValidPeriod(from, to)));
    relations.apply(plan, changeOrderId, actor);
    return true;
  }

  private static OrgRuleViolation cycle() {
    return new OrgRuleViolation("ORG-422-CYCLE", null, null, "parentId",
        "Đơn vị cấp trên không được là chính đơn vị hoặc đơn vị cấp dưới của nó.");
  }

  /** EVT-14: toàn bộ trạng thái hiện hành của đơn vị (mọi khoảng hiệu lực, cấp trên trên mỗi cây). */
  public void emitUnitChanged(UUID unitId, UUID changeOrderId, Actor actor) {
    List<OrgUnitVersion> versions = units.currentVersionsForUpdate(unitId);
    if (versions.isEmpty()) {
      throw new OrgNotFoundException("Không tìm thấy đơn vị.");
    }
    List<Map<String, Object>> slices = new ArrayList<>();
    for (OrgUnitVersion v : versions) {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("versionId", v.versionId().toString());
      s.put("code", v.content().code());
      s.put("name", v.content().name());
      s.put("shortName", v.content().shortName());
      s.put("unitType", v.content().unitType());
      s.put("subjectGroupId", str(v.content().subjectGroupId()));
      s.put("isCollectiveSubject", v.content().collectiveSubject());
      s.put("flows", v.content().flows());
      s.put("level", v.content().level());
      s.put("validFrom", v.validPeriod().from().toString());
      s.put("validTo", v.validPeriod().to() == null ? null : v.validPeriod().to().toString());
      s.put("recordedFrom", v.recordedFrom() == null ? null : v.recordedFrom().toString());
      s.put("changeOrderId", str(v.changeOrderId()));
      slices.add(s);
    }
    List<Map<String, Object>> parents = new ArrayList<>();
    for (UnitRelationVersion r : relations.currentTouchingForUpdate(List.of(unitId))) {
      if (!r.childUnitId().equals(unitId)) {
        continue;
      }
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("relationId", r.id().toString());
      p.put("tree", r.tree().name());
      p.put("relationType", r.relationType());
      p.put("parentUnitId", r.parentUnitId().toString());
      p.put("validFrom", r.validPeriod().from().toString());
      p.put("validTo", r.validPeriod().to() == null ? null : r.validPeriod().to().toString());
      parents.add(p);
    }
    int version = units.entityVersion(unitId, null).orElse(0);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", unitId.toString());
    payload.put("tenantId", actor.tenantId().toString());
    payload.put("sourceVersion", version);
    payload.put("changeOrderId", str(changeOrderId));
    payload.put("versions", slices);
    payload.put("parents", parents);
    append(OrgEvents.UNIT_CHANGED, unitId, version, payload, actor);
  }

  /** EVT-15: trạng thái hiện hành của phân công (không có trường C2). */
  public void emitAssignmentChanged(UUID assignmentId, UUID changeOrderId, Actor actor) {
    List<AssignmentVersion> versions = assignments.currentVersions(assignmentId);
    Map<UUID, String> titles =
        assignments.jobTitleNames(versions.stream().map(AssignmentVersion::jobTitleId).distinct().toList());
    List<Map<String, Object>> slices = new ArrayList<>();
    int version = 0;
    for (AssignmentVersion a : versions) {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("versionId", a.versionId().toString());
      s.put("personId", a.personId().toString());
      s.put("orgUnitId", a.orgUnitId().toString());
      s.put("positionId", str(a.positionId()));
      s.put("jobTitleId", a.jobTitleId().toString());
      s.put("jobTitleName", titles.get(a.jobTitleId()));
      s.put("kind", a.kind());
      s.put("branch", a.branch());
      s.put("isHead", a.head());
      s.put("validFrom", a.validPeriod().from().toString());
      s.put("validTo", a.validPeriod().to() == null ? null : a.validPeriod().to().toString());
      slices.add(s);
      version = Math.max(version, a.rowVersion());
    }
    Map<String, Object> payload = new HashMap<>();
    payload.put("assignmentId", assignmentId.toString());
    payload.put("tenantId", actor.tenantId().toString());
    payload.put("sourceVersion", version);
    payload.put("changeOrderId", str(changeOrderId));
    payload.put("versions", slices);
    append(OrgEvents.ASSIGNMENT_CHANGED, assignmentId, version, payload, actor);
  }

  private void append(OrgEvents type, UUID aggregateId, long version, Map<String, Object> payload, Actor actor) {
    outbox.append(new OutboxEvent(UuidV7.next(clock), type.topic(), type.eventType(), type.aggregateType(),
        aggregateId, version, actor.tenantId(), actor.actorId(), clock.instant(), actor.traceparent(), "C1",
        payload));
  }

  private static String str(Object o) {
    return o == null ? null : o.toString();
  }
}
