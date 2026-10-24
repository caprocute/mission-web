package vn.viettel.okrkpi.config.application;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.application.port.LockOrderRepository;
import vn.viettel.okrkpi.config.domain.LockOrderStatus;
import vn.viettel.okrkpi.config.domain.LockStep;
import vn.viettel.okrkpi.config.domain.PeriodLockOrder;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Bản chiếu tiến độ saga chốt kỳ của SVC-06 sang {@code config.period_lock_step} và trạng thái lệnh
 * (SCR-0801, QA-06). Nguồn là vết saga {@code sys.audit.v1} (đối tượng {@code SAGA}, {@code
 * SAGA_BUOC}, mục đích {@code CHOT_KY}) mà workflow-service ghi qua outbox; bản ghi cùng saga mang
 * cùng khoá nên đến đúng thứ tự (suy luận, wiki trang 14). Saga {@code HOAN_TAT} thì lệnh {@code
 * HOAN_TAT}; saga {@code DA_BU_TRU} theo đề nghị huỷ đã duyệt thì gọi {@link
 * PeriodLockControlService#completeCancel}.
 */
public class PeriodLockProgressService {

  private final LockOrderRepository orders;
  private final PeriodLockControlService control;

  public PeriodLockProgressService(LockOrderRepository orders, PeriodLockControlService control) {
    this.orders = Objects.requireNonNull(orders, "orders");
    this.control = Objects.requireNonNull(control, "control");
  }

  /**
   * Áp một bản ghi vết saga.
   *
   * @param action hành động của khung saga, bỏ tiền tố {@code SAGA_} (ví dụ {@code BUOC_XONG})
   * @param stepNo bước 1..6 của saga, {@code null} với hành động của cả saga
   * @return {@code false} khi không có lệnh khởi chạy saga này hoặc hành động không đổi bản chiếu
   */
  @Transactional(PeriodLockService.TX)
  public boolean apply(
      TenantId tenantId,
      UUID sagaId,
      String action,
      Integer stepNo,
      boolean success,
      UUID actorId,
      Instant at) {
    Optional<PeriodLockOrder> found = orders.findBySaga(tenantId, sagaId);
    if (found.isEmpty()) {
      return false;
    }
    PeriodLockOrder order = found.get();
    if (stepNo == null) {
      return sagaFinished(tenantId, order, action, actorId);
    }
    int index = stepNo - 1;
    if (index < 0 || index >= LockStep.NAMES.size()) {
      return false;
    }
    String status = stepStatus(action, success);
    if (status == null) {
      return false;
    }
    LockStep current =
        orders.steps(tenantId, order.id()).stream()
            .filter(s -> s.stepNo() == index)
            .findFirst()
            .orElse(LockStep.pending(index));
    Instant started = current.startedAt() != null ? current.startedAt() : at;
    Instant finished = "DAT".equals(status) || "LOI".equals(status) ? at : null;
    Instant compensated = "DA_BU_TRU".equals(status) ? at : current.compensatedAt();
    if ("DA_BU_TRU".equals(status)) {
      finished = current.finishedAt();
    }
    orders.upsertStep(
        tenantId,
        order.id(),
        new LockStep(
            index,
            current.name(),
            status,
            started,
            finished,
            current.resultSummary() == null ? Map.of() : current.resultSummary(),
            current.compensationAction(),
            compensated));
    return true;
  }

  private boolean sagaFinished(
      TenantId tenantId, PeriodLockOrder order, String action, UUID actorId) {
    if (order.status() != LockOrderStatus.DANG_CHAY) {
      return false;
    }
    switch (action) {
      case "HOAN_TAT" -> {
        int before = order.rowVersion();
        order.completed();
        if (!orders.update(order, before, actorId)) {
          throw new VersionConflictException("CONFIG", (long) before, null);
        }
        return true;
      }
      case "DA_BU_TRU" -> {
        if (order.cancelRequest() == null || !order.cancelRequest().approved()) {
          return false;
        }
        return control.completeCancel(tenantId, order.id(), actorId);
      }
      default -> {
        return false;
      }
    }
  }

  /** Trạng thái bước theo hành động của khung saga; {@code null} là không đổi bản chiếu. */
  static String stepStatus(String action, boolean success) {
    return switch (action) {
      case "BAT_DAU_BUOC", "THU_LAI", "CHO_TRA_LOI", "CHAY_LAI_LOI" -> "DANG_CHAY";
      // Trả lời của bên tham gia: thành công là bước xong (completeStep), lỗi là bước lỗi.
      case "BUOC_XONG", "NHAN_TRA_LOI" -> success ? "DAT" : "LOI";
      case "BUOC_LOI", "QUA_HAN_TRA_LOI" -> "LOI";
      case "BU_TRU_XONG" -> "DA_BU_TRU";
      default -> null;
    };
  }
}
