package vn.viettel.okrkpi.org.application.port;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.TemporalQuery;
import vn.viettel.okrkpi.org.domain.AssignmentVersion;
import vn.viettel.okrkpi.org.domain.VersionPlan;

/** Truy cập {@code org.assignment}. */
public interface AssignmentStore {

  /** Các phiên bản hiện hành của phân công tại các đơn vị còn hiệu lực từ {@code from} trở đi, khoá dòng. */
  List<AssignmentVersion> currentAtUnitsFromForUpdate(Collection<UUID> unitIds, LocalDate from);

  List<AssignmentVersion> currentVersions(UUID assignmentId);

  List<AssignmentVersion> currentVersionsForUpdate(UUID assignmentId);

  List<AssignmentVersion> currentForPersonForUpdate(UUID personId);

  List<AssignmentVersion> findCurrentByPersonAndBranch(UUID personId, String branch);

  int countAt(UUID unitId, TemporalQuery query);

  boolean personExists(UUID personId, TemporalQuery query);

  List<PersonAssignmentRow> findByPersonAt(UUID personId, TemporalQuery query);

  List<UnitAssignmentRow> findByUnitAt(UUID unitId, TemporalQuery query);

  List<PersonSummaryRow> findPersonsInUnitsAt(Collection<UUID> unitIds, TemporalQuery query);

  void apply(VersionPlan<AssignmentVersion> plan, UUID changeOrderId, Actor actor);

  /** Tên chức danh theo mã ({@code org.job_title.name}) để kèm vào EVT-15; mã không có thì không có khoá. */
  default Map<UUID, String> jobTitleNames(Collection<UUID> jobTitleIds) {
    return Map.of();
  }

  record PersonAssignmentRow(
      UUID id,
      UUID orgUnitId,
      String orgUnitName,
      String jobTitleName,
      String kind,
      String branch,
      boolean head,
      LocalDate validFrom,
      LocalDate validTo) {
  }

  record UnitAssignmentRow(
      UUID id,
      UUID personId,
      String personName,
      String jobTitleName,
      String kind,
      String branch,
      LocalDate validFrom,
      LocalDate validTo) {
  }

  record PersonSummaryRow(
      UUID personId,
      String fullName,
      String jobTitleName,
      UUID unitId,
      String unitName) {
  }
}
