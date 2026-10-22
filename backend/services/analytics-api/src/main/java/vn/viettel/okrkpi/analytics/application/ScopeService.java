package vn.viettel.okrkpi.analytics.application;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.analytics.domain.Scope;
import vn.viettel.okrkpi.analytics.domain.Viewer;

/**
 * Dựng phạm vi dữ liệu của người xem (BR-16-01, TL-03 "Phân quyền phía đọc"): nhánh cây tổ chức của
 * đơn vị công tác trong token. Khi yêu cầu chọn một đơn vị, đơn vị đó phải nằm trong nhánh của
 * người xem và phạm vi thu hẹp về nhánh của đơn vị; ngoài nhánh thì từ chối (403), không bao giờ mở
 * rộng.
 */
public class ScopeService {

  /** Giới hạn {@code viewerUnit} của hợp đồng MOD-08. */
  static final int MAX_VIEWER_UNITS = 64;

  private final OrgScopePort org;

  public ScopeService(OrgScopePort org) {
    this.org = org;
  }

  public Scope resolve(Viewer viewer, UUID unitId) {
    if (viewer.unitId() == null) {
      throw AnalyticsErrors.outOfScope("Tài khoản chưa gắn đơn vị công tác");
    }
    List<String> own = org.paths(viewer.tenant(), viewer.unitId());
    if (own.isEmpty()) {
      throw AnalyticsErrors.outOfScope("Chưa có dữ liệu cây tổ chức của đơn vị công tác");
    }
    List<UUID> units = org.unitAndAncestors(viewer.tenant(), viewer.unitId(), MAX_VIEWER_UNITS);
    Scope base = new Scope(viewer.tenant(), own.get(0), units);
    if (unitId == null || unitId.equals(viewer.unitId())) {
      return base;
    }
    for (String target : org.paths(viewer.tenant(), unitId)) {
      for (String mine : own) {
        if (base.narrowTo(mine).covers(target)) {
          return base.narrowTo(target);
        }
      }
    }
    throw AnalyticsErrors.outOfScope("Đơn vị đã chọn nằm ngoài phạm vi được xem");
  }
}
