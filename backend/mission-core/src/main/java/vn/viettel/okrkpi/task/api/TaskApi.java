package vn.viettel.okrkpi.task.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng công bố của {@code MOD-02 · Task Management} cho {@code product}, {@code appraisal}, {@code
 * oversight} (CORE-02-12, TL-01 "Giao tiếp giữa module").
 *
 * <p>MOD-02 không được phụ thuộc {@code product} (ma trận chỉ cho {@code product → task}), nên mọi
 * thông tin về minh chứng mà máy trạng thái nhiệm vụ cần (biến {@code deliverables} của guard CEL)
 * do MOD-03 đẩy vào qua {@link #requestTransition}. Các phương thức ghi chạy trong giao dịch riêng
 * của {@code taskTransactionManager}; mỗi mô-đun có {@code DataSource} riêng nên không có giao dịch
 * chung giữa hai mô-đun (wiki 21).
 */
public interface TaskApi {

  /** Số dòng tối đa một lần liệt kê (quy ước API mục 13 baseline). */
  int MAX_LIMIT = 200;

  Optional<TaskSummary> find(TenantId tenant, UUID taskId);

  /**
   * Nhiệm vụ đang giao cho một cán bộ trong kỳ (chủ trì hoặc phối hợp), sắp theo id, phân trang con
   * trỏ.
   *
   * @param afterId id cuối của trang trước, {@code null} cho trang đầu
   * @param limit tối đa {@link #MAX_LIMIT}
   */
  List<TaskSummary> listForPerson(
      TenantId tenant, PeriodId period, UUID personId, UUID afterId, int limit);

  /** Nhiệm vụ đang giao cho một đơn vị trong kỳ, như {@link #listForPerson}. */
  List<TaskSummary> listForUnit(
      TenantId tenant, PeriodId period, UUID orgUnitId, UUID afterId, int limit);

  /**
   * Yêu cầu chuyển trạng thái nhiệm vụ do MOD-03 phát sinh khi minh chứng đổi trạng thái (liên động
   * BR-07-10). Lũy đẳng: nhiệm vụ đã ở trạng thái đích thì trả {@link Outcome#ALREADY_IN_TARGET};
   * nhiệm vụ không ở trạng thái nguồn của lệnh thì trả {@link Outcome#NOT_APPLICABLE} và không đổi
   * gì.
   *
   * @throws vn.viettel.okrkpi.shared.error.BusinessException khi bảng chuyển từ chối (vai trò,
   *     guard, kỳ đã khoá)
   * @throws java.util.NoSuchElementException khi không có nhiệm vụ trong tenant
   */
  TransitionOutcome requestTransition(TenantId tenant, TransitionRequest request);

  /** Lệnh mà MOD-03 được yêu cầu (mã của bảng {@code task_lifecycle}). */
  enum Command {
    /** Đủ sản phẩm đã nộp: {@code DANG_THUC_HIEN → CHO_NGHIEM_THU}. */
    SUBMIT("DANG_THUC_HIEN", "CHO_NGHIEM_THU"),
    /** Mọi sản phẩm đã xác nhận: {@code CHO_NGHIEM_THU → HOAN_THANH}. */
    APPROVE("CHO_NGHIEM_THU", "HOAN_THANH"),
    /** Yêu cầu bổ sung hoặc từ chối: {@code CHO_NGHIEM_THU → DANG_THUC_HIEN}. */
    REWORK("CHO_NGHIEM_THU", "DANG_THUC_HIEN");

    private final String from;
    private final String to;

    Command(String from, String to) {
      this.from = from;
      this.to = to;
    }

    public String from() {
      return from;
    }

    public String to() {
      return to;
    }
  }

  /**
   * Trạng thái minh chứng hiện hành của một sản phẩm đầu ra, đưa vào biến {@code deliverables} của
   * guard CEL.
   */
  record DeliverableState(UUID deliverableId, String status) {}

  /**
   * Yêu cầu chuyển.
   *
   * @param taskId nhiệm vụ
   * @param command lệnh
   * @param actorId người thực hiện thao tác minh chứng
   * @param actorRoles vai trò realm của phiên
   * @param reason lý do (bắt buộc với {@link Command#REWORK})
   * @param deliverables trạng thái minh chứng hiện hành do MOD-03 cung cấp
   * @param completedAt ngày hoàn thành ghi khi nhiệm vụ sang {@code HOAN_THANH} (ngày nộp phiên bản
   *     minh chứng cuối, QT-07); {@code null} lấy thời điểm chuyển
   */
  record TransitionRequest(
      UUID taskId,
      Command command,
      UUID actorId,
      Set<String> actorRoles,
      String reason,
      List<DeliverableState> deliverables,
      java.time.Instant completedAt) {

    public TransitionRequest {
      java.util.Objects.requireNonNull(taskId, "taskId");
      java.util.Objects.requireNonNull(command, "command");
      java.util.Objects.requireNonNull(actorId, "actorId");
      actorRoles = Set.copyOf(actorRoles == null ? Set.of() : actorRoles);
      deliverables = List.copyOf(deliverables == null ? List.of() : deliverables);
    }
  }

  enum Outcome {
    APPLIED,
    ALREADY_IN_TARGET,
    NOT_APPLICABLE
  }

  /** Kết quả: nhiệm vụ sau khi xử lý yêu cầu. */
  record TransitionOutcome(Outcome outcome, TaskSummary task) {}
}
