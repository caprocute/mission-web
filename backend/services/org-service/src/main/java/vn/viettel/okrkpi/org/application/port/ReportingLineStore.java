package vn.viettel.okrkpi.org.application.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.TemporalQuery;
import vn.viettel.okrkpi.org.domain.ReportingLineVersion;
import vn.viettel.okrkpi.org.domain.VersionPlan;

/** Truy cập {@code org.reporting_line}. */
public interface ReportingLineStore {

  /** Đọc (kèm khoá cập nhật) các phiên bản hiện hành của cán bộ cấp dưới. */
  List<ReportingLineVersion> currentForSubordinateForUpdate(UUID subordinatePersonId);

  /** Đọc (kèm khoá cập nhật) các phiên bản hiện hành theo {@code id}. */
  List<ReportingLineVersion> currentVersionsForUpdate(UUID reportingLineId);

  /** Truy vấn các quan hệ báo cáo của một cán bộ cấp dưới tại thời điểm {@code query}. */
  List<ReportingLineVersion> findBySubordinateAt(UUID subordinatePersonId, TemporalQuery query);

  /** Áp dụng kế hoạch phiên bản lưỡng thời gian: đóng bản ghi cũ và chèn bản ghi mới. */
  void apply(VersionPlan<ReportingLineVersion> plan, UUID changeOrderId, Actor actor);
}
