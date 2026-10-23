package vn.viettel.okrkpi.appraisal.application.annual.port;

import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Thông báo của xếp loại năm qua EVT-21 (CORE-04-11). */
public interface AnnualNotices {

  /**
   * MSG-10-02: cán bộ có hai quý liên tiếp KHTNV, cần lập đề xuất bố trí lại công việc (BR-10-04,
   * QĐ khoản 15.6).
   */
  void reassignFlagged(TenantId tenant, UUID annualProfileId, List<UUID> recipients);
}
