package vn.viettel.okrkpi.task.application.fromplan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import vn.viettel.okrkpi.cel.application.CelEngine;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.plan.PlanChecker;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;

/**
 * {@link PlanChecker} kèm kiểm tra xác nhận dòng liên đơn vị (CORE-02-11): dòng bị đơn vị chủ trì
 * từ chối bị đánh lỗi mỗi khi kế hoạch được kiểm tra. Tenant lấy từ ngữ cảnh yêu cầu.
 */
public class ConfirmedLinePlanChecker extends PlanChecker {

  private final LeadConfirmationService confirmations;
  private final Supplier<TenantId> tenant;

  public ConfirmedLinePlanChecker(
      CelEngine cel, LeadConfirmationService confirmations, Supplier<TenantId> tenant) {
    super(cel);
    this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
    this.tenant = Objects.requireNonNull(tenant, "tenant");
  }

  @Override
  public List<Check> check(List<ItemRow> items, Context ctx) {
    List<Check> out = new ArrayList<>(super.check(items, ctx));
    TenantId t = tenant.get();
    if (t != null) {
      out.addAll(confirmations.checks(t, items));
    }
    return out;
  }
}
