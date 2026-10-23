package vn.viettel.okrkpi.oversight.infrastructure.client;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.api.ProfileParticipantsApi;
import vn.viettel.okrkpi.oversight.application.port.ReopenScopeProfiles;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenRequest;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScope;
import vn.viettel.okrkpi.oversight.domain.reopen.ReopenScopeItem;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hồ sơ trong phạm vi yêu cầu mở lại: phạm vi hồ sơ là chính các mã đã duyệt (cùng hồ sơ của dòng
 * phạm vi chi tiết); phạm vi đơn vị và toàn kỳ tra hồ sơ của kỳ ở MOD-04.
 */
public class AppraisalScopeProfiles implements ReopenScopeProfiles {

  private final ProfileParticipantsApi profiles;

  public AppraisalScopeProfiles(ProfileParticipantsApi profiles) {
    this.profiles = profiles;
  }

  @Override
  public Set<UUID> profilesOf(TenantId tenant, ReopenRequest r) {
    Set<UUID> out = new LinkedHashSet<>();
    if (r.scope() == ReopenScope.HO_SO) {
      out.addAll(r.scopeIds());
      r.items().stream().map(ReopenScopeItem::profileId).filter(Objects::nonNull).forEach(out::add);
      return out;
    }
    if (profiles == null) {
      return out;
    }
    List<UUID> units = r.scope() == ReopenScope.DON_VI ? r.scopeIds() : List.of();
    out.addAll(profiles.profilesOfUnits(tenant, PeriodId.of(r.periodId()), units));
    return out;
  }
}
