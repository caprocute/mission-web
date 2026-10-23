package vn.viettel.okrkpi.task.application.fromplan;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.application.fromplan.port.LeadConfirmations;
import vn.viettel.okrkpi.task.application.fromplan.port.LeadConfirmations.LineRow;
import vn.viettel.okrkpi.task.application.plan.PlanChecker.Check;
import vn.viettel.okrkpi.task.application.plan.PlanChecker.Level;
import vn.viettel.okrkpi.task.application.plan.port.PlanRepository.ItemRow;
import vn.viettel.okrkpi.task.domain.fromplan.LeadConfirmation;

/**
 * Đơn vị chủ trì xác nhận hoặc từ chối dòng kế hoạch của đơn vị khác (CORE-02-11, TL-02a QT-04
 * "Ngoại lệ và xử lý") và kết quả kiểm tra tương ứng cho {@code PlanChecker}. Hợp đồng {@code
 * tasks.yaml} chưa có thao tác xác nhận dòng nên dịch vụ chưa có endpoint (đề nghị bổ sung hợp đồng
 * ghi ở wiki trang 14).
 */
public class LeadConfirmationService {

  static final String TX = "taskTransactionManager";
  static final String VALIDATION = "COMMON-422-VALIDATION";
  static final String FORBIDDEN = "COMMON-403-OUT-OF-SCOPE";
  static final String NOT_OPEN = "TASK-409-INVALID-STATE";

  private final LeadConfirmations confirmations;
  private final Clock clock;

  public LeadConfirmationService(LeadConfirmations confirmations, Clock clock) {
    this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Ghi quyết định của đơn vị chủ trì; {@code actorUnitIds} là các đơn vị người gọi đang quản lý
   * (do tầng web lấy từ phạm vi dữ liệu). Chỉ dòng của phiên bản chưa duyệt mới xác nhận được.
   */
  @Transactional(TX)
  public void decide(
      TenantId tenant,
      UUID itemId,
      boolean accept,
      String reason,
      UUID actor,
      Collection<UUID> actorUnitIds) {
    LineRow line = confirmations.line(tenant, itemId).orElseThrow(NoSuchElementException::new);
    if (!LeadConfirmation.required(line.ownerUnitId(), line.leadUnitId())) {
      throw new BusinessException(
          VALIDATION,
          "Dòng do chính đơn vị lập kế hoạch chủ trì, không cần xác nhận.",
          List.of(new ProblemFieldError("planItemId", "Dòng không cần xác nhận", null)));
    }
    if (actorUnitIds == null || !actorUnitIds.contains(line.leadUnitId())) {
      throw new BusinessException(FORBIDDEN, "Chỉ đơn vị chủ trì của dòng được xác nhận.");
    }
    if (!"NHAP".equals(line.versionStatus()) && !"CHO_DUYET".equals(line.versionStatus())) {
      throw new BusinessException(NOT_OPEN, "Kế hoạch đã duyệt, không xác nhận lại dòng được.");
    }
    String error = LeadConfirmation.reasonError(accept, reason);
    if (error != null) {
      throw new BusinessException(
          VALIDATION, error, List.of(new ProblemFieldError("reason", error, null)));
    }
    confirmations.record(
        tenant,
        line,
        accept ? LeadConfirmation.CONFIRMED : LeadConfirmation.REJECTED,
        reason == null || reason.isBlank() ? null : reason.strip(),
        actor,
        clock.instant());
  }

  /**
   * Kết quả kiểm tra xác nhận của các dòng đã lưu: từ chối là {@code CHAN}, chưa xác nhận là {@code
   * CANH_BAO} (suy luận, để kế hoạch vẫn lưu và gửi được trong lúc chờ).
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<Check> checks(TenantId tenant, List<ItemRow> items) {
    List<UUID> ids = items.stream().map(ItemRow::id).filter(Objects::nonNull).toList();
    if (ids.isEmpty()) {
      return List.of();
    }
    List<Check> out = new ArrayList<>();
    for (LineRow l : confirmations.lines(tenant, ids)) {
      switch (LeadConfirmation.issue(l.ownerUnitId(), l.leadUnitId(), l.confirmation())) {
        case PENDING ->
            out.add(
                new Check(
                    LeadConfirmation.RULE,
                    Level.CANH_BAO,
                    l.itemNo(),
                    String.format(LeadConfirmation.PENDING_MESSAGE, l.itemNo()),
                    null));
        case REJECTED ->
            out.add(
                new Check(
                    LeadConfirmation.RULE,
                    Level.CHAN,
                    l.itemNo(),
                    String.format(LeadConfirmation.REJECTED_MESSAGE, l.itemNo()),
                    null));
        case NONE -> {
          // dòng không cần hoặc đã xác nhận
        }
      }
    }
    return out;
  }
}
