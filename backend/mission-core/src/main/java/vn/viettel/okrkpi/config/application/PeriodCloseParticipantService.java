package vn.viettel.okrkpi.config.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.application.port.LockOrderRepository;
import vn.viettel.okrkpi.config.application.port.PeriodRepository;
import vn.viettel.okrkpi.config.domain.IllegalPeriodTransitionException;
import vn.viettel.okrkpi.config.domain.LockOrderStatus;
import vn.viettel.okrkpi.config.domain.Period;
import vn.viettel.okrkpi.config.domain.PeriodLockOrder;
import vn.viettel.okrkpi.config.domain.PeriodState;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * MOD-08 là bên tham gia saga chốt kỳ của SVC-06 (DV-06-06, QT-08 bước 0, 1, 5; QA-06): kiểm tiền
 * điều kiện, lệnh {@code KHOA_KY} ({@code MO → DANG_KHOA}, dòng xả {@code period.drain.marker}) và
 * {@code HOAN_TAT_KY} ({@code DANG_KHOA → DA_KHOA}, EVT-09), bù trừ {@code KHOA_KY} ({@code
 * DANG_KHOA → MO}, EVT-26). Lệnh chạy xong ngay trong lời gọi nên trạng thái tác vụ suy ra từ trạng
 * thái kỳ; giao lại cùng lệnh khi kỳ đã ở đích không làm gì. Đóng mục tiêu và lập hồ sơ là việc của
 * module nhận EVT-09, không làm ở đây (suy luận, wiki trang 14).
 */
public class PeriodCloseParticipantService {

  /** Lệnh saga gửi MOD-08 ({@code PeriodCloseGateway.Command} của workflow-service). */
  public enum Command {
    KHOA_KY,
    HOAN_TAT_KY
  }

  /** Trạng thái tác vụ theo lược đồ {@code Job} ({@code CHUA_CO} khi chưa giao). */
  public enum JobStatus {
    CHUA_CO,
    HOAN_TAT
  }

  /** Kết quả kiểm tiền điều kiện: vi phạm chặn chốt kỳ, cảnh báo không chặn. */
  public record Report(List<String> violations, List<String> warnings) {}

  private final LockOrderRepository orders;
  private final PeriodRepository periods;
  private final PeriodConfigService config;

  public PeriodCloseParticipantService(
      LockOrderRepository orders, PeriodRepository periods, PeriodConfigService config) {
    this.orders = Objects.requireNonNull(orders, "orders");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.config = Objects.requireNonNull(config, "config");
  }

  /** Bước 0: lệnh đang chạy đúng saga này và kỳ còn mở (hoặc đã vào khoá do chính saga này). */
  @Transactional(value = PeriodLockService.TX, readOnly = true)
  public Report preconditions(TenantId tenantId, PeriodId periodId, UUID lockOrderId, UUID sagaId) {
    List<String> violations = new ArrayList<>();
    Optional<PeriodLockOrder> order =
        lockOrderId == null ? Optional.empty() : orders.findById(tenantId, lockOrderId);
    if (order.isEmpty() || !order.get().periodId().equals(periodId)) {
      violations.add("Không có lệnh khoá kỳ của kỳ này.");
    } else if (order.get().status() != LockOrderStatus.DANG_CHAY
        || !Objects.equals(order.get().sagaId(), sagaId)) {
      violations.add("Lệnh khoá kỳ chưa khởi chạy tiến trình này.");
    }
    Optional<Period> period = periods.findById(tenantId, periodId);
    if (period.isEmpty()) {
      violations.add("Không có kỳ đánh giá.");
    } else if (period.get().state() != PeriodState.MO
        && period.get().state() != PeriodState.DANG_KHOA) {
      violations.add("Kỳ đánh giá không còn mở.");
    }
    return new Report(violations, List.of());
  }

  /**
   * Giao lệnh; kỳ đã ở trạng thái đích thì không làm gì.
   *
   * @throws IllegalPeriodTransitionException khi kỳ không ở trạng thái cho lệnh này
   * @throws java.util.NoSuchElementException khi không có kỳ hoặc lệnh khoá
   */
  @Transactional(PeriodLockService.TX)
  public void submit(
      TenantId tenantId, PeriodId periodId, Command command, UUID sagaId, UUID lockOrderId) {
    Period period = periods.findById(tenantId, periodId).orElseThrow();
    PeriodLockOrder order = orders.findById(tenantId, lockOrderId).orElseThrow();
    PeriodState state = period.state();
    switch (command) {
      case KHOA_KY -> {
        if (state == PeriodState.MO) {
          config.transition(
              tenantId, periodId, PeriodState.DANG_KHOA, sagaId, order.startedBy(), null, null);
        } else if (state != PeriodState.DANG_KHOA && state != PeriodState.DA_KHOA) {
          throw new IllegalPeriodTransitionException(state, PeriodState.DANG_KHOA);
        }
      }
      case HOAN_TAT_KY -> {
        if (state == PeriodState.DANG_KHOA) {
          config.transition(
              tenantId, periodId, PeriodState.DA_KHOA, sagaId, order.startedBy(), null, null);
        } else if (state != PeriodState.DA_KHOA) {
          throw new IllegalPeriodTransitionException(state, PeriodState.DA_KHOA);
        }
      }
    }
  }

  /** Trạng thái lệnh suy ra từ trạng thái kỳ. */
  @Transactional(value = PeriodLockService.TX, readOnly = true)
  public JobStatus state(TenantId tenantId, PeriodId periodId, Command command) {
    PeriodState state = periods.findById(tenantId, periodId).orElseThrow().state();
    boolean done =
        switch (command) {
          case KHOA_KY -> state == PeriodState.DANG_KHOA || state == PeriodState.DA_KHOA;
          case HOAN_TAT_KY -> state == PeriodState.DA_KHOA;
        };
    return done ? JobStatus.HOAN_TAT : JobStatus.CHUA_CO;
  }

  /**
   * Bù trừ: {@code KHOA_KY} đưa kỳ {@code DANG_KHOA} về {@code MO} và phát EVT-26; {@code
   * HOAN_TAT_KY} nằm sau điểm không quay lại (BR-08-09) nên không làm gì.
   */
  @Transactional(PeriodLockService.TX)
  public void undo(
      TenantId tenantId,
      PeriodId periodId,
      Command command,
      UUID sagaId,
      UUID lockOrderId,
      String reason) {
    if (command != Command.KHOA_KY) {
      return;
    }
    Period period = periods.findById(tenantId, periodId).orElseThrow();
    if (period.state() != PeriodState.DANG_KHOA) {
      return;
    }
    PeriodLockOrder order = orders.findById(tenantId, lockOrderId).orElseThrow();
    String why =
        order.cancelRequest() != null && order.cancelRequest().reason() != null
            ? order.cancelRequest().reason()
            : reason;
    config.transition(
        tenantId, periodId, PeriodState.MO, sagaId, order.startedBy(), order.id(), why);
  }
}
