package vn.viettel.okrkpi.org.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.org.application.AssignmentCommandService.AssignCommand;
import vn.viettel.okrkpi.org.application.port.AssignmentStore;
import vn.viettel.okrkpi.org.application.port.IdempotencyPort;
import vn.viettel.okrkpi.org.application.port.OrgUnitStore;
import vn.viettel.okrkpi.org.application.port.PolicyPort;
import vn.viettel.okrkpi.org.application.port.UserAccountStore;
import vn.viettel.okrkpi.org.domain.AssignmentVersion;
import vn.viettel.okrkpi.org.domain.OrgNotFoundException;
import vn.viettel.okrkpi.org.domain.OrgRuleViolation;
import vn.viettel.okrkpi.org.domain.OrgUnitVersion;
import vn.viettel.okrkpi.org.domain.Timeline;
import vn.viettel.okrkpi.org.domain.TreeKind;
import vn.viettel.okrkpi.org.domain.VersionConflictException;
import vn.viettel.okrkpi.org.domain.VersionPlan;

/**
 * Thêm, điều chỉnh, kết thúc phân công từ quản trị (DV-04-09 cùng DV-04-04; BS-04 ADR-34 mục 3; SCR-0109,
 * SCR-0103): {@code createOrgAssignment}, {@code createOrgUnitAssignment}, {@code updateOrgAssignment},
 * {@code endOrgAssignment}. Ghi qua {@link AssignmentCommandService} để giữ quy tắc hai trục thời gian (BR-01-08) và
 * EVT-15. Nhánh của phân công suy từ cây chứa đơn vị.
 */
@Service
public class AssignmentAdminService {

  private final AssignmentCommandService commands;
  private final AssignmentStore assignments;
  private final OrgUnitStore units;
  private final UserAccountStore people;
  private final UnitSupport support;
  private final IdempotencyPort idempotency;
  private final PolicyPort policy;

  public AssignmentAdminService(AssignmentCommandService commands, AssignmentStore assignments, OrgUnitStore units,
      UserAccountStore people, UnitSupport support, IdempotencyPort idempotency, PolicyPort policy) {
    this.commands = commands;
    this.assignments = assignments;
    this.units = units;
    this.people = people;
    this.support = support;
    this.idempotency = idempotency;
    this.policy = policy;
  }

  public record NewAssignment(UUID personId, UUID orgUnitId, UUID jobTitleId, String kind, LocalDate validFrom,
      LocalDate validTo, String signedDocRef) {
  }

  /** Phân công sau khi ghi, ở dạng {@code OrgUnitAssignment} của hợp đồng. */
  public record AssignmentView(UUID id, UUID personId, String personName, String jobTitleName, String kind,
      String branch, LocalDate validFrom, LocalDate validTo, int rowVersion) {
  }

  @Transactional
  public AssignmentView create(Actor actor, String operationId, IdempotencyRequest idem, NewAssignment cmd) {
    policy.authorize(actor, operationId);
    var replay = idempotency.begin(actor, idem, AssignmentView.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    TemporalQuery at = support.resolve(cmd.validFrom(), null);
    OrgUnitVersion unit = units.findAt(cmd.orgUnitId(), at).orElseThrow(() -> new OrgRuleViolation(
        "ORG-422-NOT-VALID-AT-DATE", "BR-01-08", null, "orgUnitId", "Đơn vị chưa có hiệu lực tại ngày bắt đầu."));
    if (!assignments.personExists(cmd.personId(), at)) {
      throw new OrgRuleViolation("ORG-422-NOT-VALID-AT-DATE", "BR-01-08", null, "personId",
          "Cán bộ chưa có hiệu lực tại ngày bắt đầu.");
    }
    TreeKind tree = support.treeOf(unit.id(), unit.content().unitType(), at);
    boolean primary = "CHINH".equals(cmd.kind());
    UUID id = commands.recordAssignment(actor, new AssignCommand(cmd.personId(), cmd.orgUnitId(), null,
        cmd.jobTitleId(), cmd.kind(), tree == TreeKind.DANG ? "DANG" : "CHINH_QUYEN", false, cmd.validFrom(),
        cmd.validTo(), primary, null, null, cmd.signedDocRef(), null, null));
    AssignmentView view = view(id, cmd.validFrom());
    idempotency.complete(actor, idem, 201, view, id.toString());
    return view;
  }

  @Transactional
  public AssignmentView update(Actor actor, UUID id, int expected, IdempotencyRequest idem, UUID jobTitleId,
      String kind, LocalDate effectiveFrom) {
    policy.authorize(actor, "updateOrgAssignment");
    var replay = idempotency.begin(actor, idem, AssignmentView.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    List<AssignmentVersion> current = requireCurrent(id, expected);
    LocalDate from = effectiveFrom != null ? effectiveFrom : support.today();
    if (Timeline.versionAt(current, from).isEmpty()) {
      throw new OrgRuleViolation("ORG-422-NOT-VALID-AT-DATE", "BR-01-08", null, "effectiveFrom",
          "Phân công không có hiệu lực tại ngày bắt đầu của thay đổi.");
    }
    VersionPlan<AssignmentVersion> plan = Timeline.applyChange(current, from, null, v -> new AssignmentVersion(
        v.versionId(), v.id(), v.personId(), v.orgUnitId(), v.positionId(),
        jobTitleId != null ? jobTitleId : v.jobTitleId(), kind != null ? kind : v.kind(), v.branch(), v.head(),
        null, v.validPeriod(), v.recordedFrom(), v.recordedTo(), v.rowVersion()));
    if (!plan.isEmpty()) {
      assignments.apply(plan, null, actor);
      support.emitAssignmentChanged(id, null, actor);
    }
    AssignmentView view = view(id, from);
    idempotency.complete(actor, idem, 200, view, id.toString());
    return view;
  }

  @Transactional
  public AssignmentView end(Actor actor, UUID id, int expected, IdempotencyRequest idem, LocalDate validTo) {
    policy.authorize(actor, "endOrgAssignment");
    var replay = idempotency.begin(actor, idem, AssignmentView.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    List<AssignmentVersion> current = requireCurrent(id, expected);
    LocalDate start = current.stream().map(v -> v.validPeriod().from()).min(LocalDate::compareTo).orElseThrow();
    if (!validTo.isAfter(start)) {
      throw new OrgRuleViolation("ORG-422-VALIDATION", "BR-01-08", null, "validTo",
          "Ngày kết thúc phải sau ngày bắt đầu phân công.");
    }
    commands.endAssignment(actor, id, validTo, null);
    AssignmentView view = view(id, start);
    idempotency.complete(actor, idem, 200, view, id.toString());
    return view;
  }

  /** Phiên bản lớn nhất của phân công, dùng làm ETag; cần giao dịch để có ngữ cảnh tenant cho RLS. */
  @Transactional(readOnly = true)
  public int version(UUID id) {
    return assignments.currentVersions(id).stream().mapToInt(AssignmentVersion::rowVersion).max()
        .orElseThrow(() -> new OrgNotFoundException("Không tìm thấy phân công."));
  }

  private List<AssignmentVersion> requireCurrent(UUID id, int expected) {
    List<AssignmentVersion> current = assignments.currentVersionsForUpdate(id);
    if (current.isEmpty()) {
      throw new OrgNotFoundException("Không tìm thấy phân công.");
    }
    int actual = current.stream().mapToInt(AssignmentVersion::rowVersion).max().orElse(0);
    if (actual != expected) {
      throw new VersionConflictException(expected, actual);
    }
    return current;
  }

  private AssignmentView view(UUID id, LocalDate day) {
    List<AssignmentVersion> versions = assignments.currentVersions(id);
    AssignmentVersion v = Timeline.versionAt(versions, day)
        .orElseGet(() -> versions.get(versions.size() - 1));
    LocalDate start = versions.stream().map(x -> x.validPeriod().from()).min(LocalDate::compareTo).orElseThrow();
    LocalDate end = versions.stream().map(x -> x.validPeriod().to()).anyMatch(java.util.Objects::isNull) ? null
        : versions.stream().map(x -> x.validPeriod().to()).max(LocalDate::compareTo).orElse(null);
    Map<UUID, String> titles = assignments.jobTitleNames(List.of(v.jobTitleId()));
    String name = people.person(v.personId(), day).map(UserAccountStore.PersonRow::fullName).orElse("");
    int rv = versions.stream().mapToInt(AssignmentVersion::rowVersion).max().orElse(0);
    return new AssignmentView(id, v.personId(), name, titles.getOrDefault(v.jobTitleId(), ""), v.kind(), v.branch(),
        start, end, rv);
  }
}
