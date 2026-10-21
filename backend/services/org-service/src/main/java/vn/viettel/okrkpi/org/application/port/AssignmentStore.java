package vn.viettel.okrkpi.org.application.port;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.TemporalQuery;
import vn.viettel.okrkpi.org.domain.AssignmentVersion;
import vn.viettel.okrkpi.org.domain.VersionPlan;

/** Truy cập {@code org.assignment}. */
public interface AssignmentStore {

  /** Phiên bản hiện hành (trục ghi nhận) của phân công tại các đơn vị còn hiệu lực từ ngày {@code from}; khoá dòng. */
  List<AssignmentVersion> currentAtUnitsFromForUpdate(Collection<UUID> unitIds, LocalDate from);

  List<AssignmentVersion> currentVersions(UUID assignmentId);

  int countAt(UUID unitId, TemporalQuery query);

  void apply(VersionPlan<AssignmentVersion> plan, UUID changeOrderId, Actor actor);
}
