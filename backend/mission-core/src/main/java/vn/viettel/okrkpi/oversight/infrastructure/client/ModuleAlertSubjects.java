package vn.viettel.okrkpi.oversight.infrastructure.client;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.api.ProfileParticipantsApi;
import vn.viettel.okrkpi.appraisal.api.ProfileParticipantsApi.ProfileSubject;
import vn.viettel.okrkpi.config.api.AccessDirectory;
import vn.viettel.okrkpi.oversight.application.port.AlertSubjects;
import vn.viettel.okrkpi.oversight.domain.alert.AnomalyAlert;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đối tượng cảnh báo từ MOD-04 (cán bộ, đơn vị của hồ sơ) và tên, cây đơn vị từ MOD-08
 * (CORE-07-11). Cổng vắng thì đối tượng không có đơn vị, nên chỉ cơ quan kiểm tra thấy cảnh báo.
 */
public class ModuleAlertSubjects implements AlertSubjects {

  private final ProfileParticipantsApi participants;
  private final AccessDirectory directory;

  public ModuleAlertSubjects(ProfileParticipantsApi participants, AccessDirectory directory) {
    this.participants = participants;
    this.directory = directory;
  }

  @Override
  public Map<UUID, Subject> resolve(TenantId tenant, Collection<AnomalyAlert> alerts) {
    Set<UUID> profiles = new HashSet<>();
    Set<UUID> units = new HashSet<>();
    for (AnomalyAlert a : alerts) {
      switch (a.subjectType()) {
        case "CA_NHAN" -> profiles.add(a.subjectId());
        case "DON_VI" -> units.add(a.subjectId());
        default -> {}
      }
    }
    Map<UUID, ProfileSubject> ofProfile =
        participants == null || profiles.isEmpty()
            ? Map.of()
            : participants.subjectsOf(tenant, profiles);
    Set<UUID> people = new HashSet<>();
    ofProfile.values().forEach(p -> people.add(p.personId()));
    Map<UUID, String> personNames =
        directory == null || people.isEmpty() ? Map.of() : directory.personNames(tenant, people);
    Map<UUID, String> unitNames =
        directory == null || units.isEmpty() ? Map.of() : directory.unitNames(tenant, units);
    Map<UUID, Subject> out = new HashMap<>();
    for (AnomalyAlert a : alerts) {
      switch (a.subjectType()) {
        case "CA_NHAN" -> {
          ProfileSubject p = ofProfile.get(a.subjectId());
          out.put(
              a.id(),
              p == null
                  ? new Subject(null, Set.of(), null)
                  : new Subject(p.unitId(), Set.of(p.personId()), personNames.get(p.personId())));
        }
        case "DON_VI" ->
            out.put(a.id(), new Subject(a.subjectId(), Set.of(), unitNames.get(a.subjectId())));
        default -> out.put(a.id(), new Subject(null, Set.of(), null));
      }
    }
    return out;
  }

  @Override
  public Set<UUID> scopeOf(TenantId tenant, UUID unitId) {
    Set<UUID> out = new LinkedHashSet<>(List.of(unitId));
    if (directory != null) {
      out.addAll(directory.subunitsOf(tenant, unitId));
    }
    return out;
  }

  @Override
  public Map<UUID, String> personNames(TenantId tenant, Collection<UUID> people) {
    return directory == null ? Map.of() : directory.personNames(tenant, people);
  }
}
