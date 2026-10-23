package vn.viettel.okrkpi.task.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
   * Việc lá của một cán bộ trong kỳ đưa vào tính điểm theo điểm công việc (CORE-04-13, BS-03
   * DC-01): nhiệm vụ không có việc con mà người đó đang chủ trì, trừ bản nháp, sắp theo id, phân
   * trang con trỏ. Lõi chỉ trả đại lượng thô và mức hệ số; công thức nằm trong Policy Pack.
   *
   * @param afterId id cuối của trang trước, {@code null} cho trang đầu
   * @param limit tối đa {@link #MAX_LIMIT} + 1 (bên gọi đọc dư một dòng để biết còn trang sau)
   */
  default List<WorkItemSnapshot> listWorkItems(
      TenantId tenant, PeriodId period, UUID personId, UUID afterId, int limit) {
    return List.of();
  }

  /**
   * Trang đầu việc lá của nhiều cán bộ trong một lần đọc, để đầu vào lô của MOD-04 không phát sinh
   * một truy vấn mỗi hồ sơ. Mỗi người tối đa {@code limitPerPerson} việc; người không có việc vắng
   * mặt trong kết quả.
   */
  default Map<UUID, List<WorkItemSnapshot>> listWorkItems(
      TenantId tenant, PeriodId period, Collection<UUID> personIds, int limitPerPerson) {
    Map<UUID, List<WorkItemSnapshot>> out = new LinkedHashMap<>();
    for (UUID person : personIds) {
      List<WorkItemSnapshot> items = listWorkItems(tenant, period, person, null, limitPerPerson);
      if (!items.isEmpty()) {
        out.put(person, items);
      }
    }
    return out;
  }

  /**
   * Nhiệm vụ và KPI có việc con mà cán bộ chủ trì trong kỳ, dùng cho phạm vi phụ trách (CORE-04-13,
   * BS-03 DC-03). Mỗi việc lá chỉ được tính cho mục phụ trách gần nhất phía trên nó, nên phần của
   * cấp dưới không bị cộng hai lần khi người đó phụ trách cả mục cha lẫn mục con.
   */
  default List<ResponsibleItem> listResponsibleItems(
      TenantId tenant, PeriodId period, UUID personId) {
    return List.of();
  }

  /**
   * Một việc lá (BS-03 DC-01).
   *
   * @param coefficientLevels mức của từng loại hệ số mở, khoá là mã loại do Policy Pack khai báo
   * @param statusAtLock trạng thái hiện hành; sau khi kỳ khoá nhiệm vụ không đổi được nên đây là
   *     trạng thái tại thời điểm khoá
   * @param qualityLevel mức chất lượng nghiệm thu, {@code null} khi MOD-02 chưa ghi
   * @param exceeded khối lượng hoàn thành vượt khối lượng giao
   * @param deferredByDecision tạm dừng hoặc có đề nghị gia hạn, tạm dừng, huỷ đã được duyệt
   */
  record WorkItemSnapshot(
      UUID taskId,
      UUID parentTaskId,
      BigDecimal standardScore,
      Integer difficulty,
      Map<String, String> coefficientLevels,
      String taskGroup,
      String axisCode,
      BigDecimal componentScore,
      String statusAtLock,
      BigDecimal qualityLevel,
      int subjectiveLateWorkdays,
      int objectiveLateWorkdays,
      boolean exceeded,
      boolean outOfScope,
      boolean deferredByDecision) {

    public WorkItemSnapshot {
      java.util.Objects.requireNonNull(taskId, "taskId");
      standardScore = standardScore == null ? BigDecimal.ZERO : standardScore;
      coefficientLevels = Map.copyOf(coefficientLevels == null ? Map.of() : coefficientLevels);
    }
  }

  /**
   * Một mục cán bộ phụ trách trong MOD-02 (BS-03 DC-03).
   *
   * @param itemType {@code NHIEM_VU} hoặc {@code KPI} (loại nút của cây phân rã)
   * @param leafCount số việc lá tính cho mục này
   * @param sumStandardScore tổng điểm chuẩn của các việc lá đó
   * @param sumCompletedScore tổng điểm chuẩn của các việc lá đã hoàn thành
   * @param targetAchievedRatio khối lượng hoàn thành chia khối lượng giao của chính mục
   * @param ownLateWorkdays số ngày làm việc chậm của chính mục, 0 khi MOD-02 chưa tính
   */
  record ResponsibleItem(
      String itemType,
      UUID itemId,
      int leafCount,
      BigDecimal sumStandardScore,
      BigDecimal sumCompletedScore,
      BigDecimal targetAchievedRatio,
      int ownLateWorkdays) {}

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
