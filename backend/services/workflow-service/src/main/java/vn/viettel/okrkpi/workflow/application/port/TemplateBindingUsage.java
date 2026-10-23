package vn.viettel.okrkpi.workflow.application.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vn.viettel.okrkpi.workflow.domain.template.BindingUsage;

/**
 * Đọc {@code flowable.wf_binding} của mọi tenant theo mẫu (phạm vi nền tảng của SCR-1505): đếm số
 * tenant đang dùng và lấy tham số để chạy test. Chỉ đọc.
 */
public interface TemplateBindingUsage {

  /**
   * Các {@code wf_binding} {@code NHAP} hoặc {@code HIEU_LUC} dùng bất kỳ phiên bản nào của mã mẫu.
   */
  List<BindingUsage> bindings(String templateCode);

  /** Số tenant khác nhau đang dùng từng phiên bản. */
  Map<UUID, Integer> tenantCounts(Collection<UUID> templateIds);
}
