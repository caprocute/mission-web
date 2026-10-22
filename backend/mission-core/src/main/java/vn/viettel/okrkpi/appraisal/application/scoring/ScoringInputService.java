package vn.viettel.okrkpi.appraisal.application.scoring;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.application.scoring.port.ScoringInputRepository;
import vn.viettel.okrkpi.appraisal.application.scoring.port.ScoringInputRepository.ScoringInput;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đầu vào tính điểm của MOD-04 (CORE-04-06): SVC-07 đọc theo lô qua {@code
 * listAppraisalScoringInputs}; nhiệm vụ kết thúc thì đánh dấu đầu vào của người được giao đã đổi
 * ({@code RefreshAppraisalInputsUseCase.forAssignees} của TL-01 "Giao tiếp giữa module").
 */
public class ScoringInputService {

  /** Định danh consumer trong {@code appraisal.inbox_processed}. */
  public static final String CONSUMER = "appraisal.scoring-inputs";

  /** Số hồ sơ tối đa một trang (tham số {@code Limit} của hợp đồng). */
  public static final int MAX_LIMIT = 200;

  static final String TX = "appraisalTransactionManager";
  static final String SOURCE = "task.TrangThaiNhiemVuDoi";

  private final ScoringInputRepository repository;
  private final Clock clock;

  public ScoringInputService(ScoringInputRepository repository, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Một trang đầu vào; giao dịch chỉ đọc nên đi bản sao đọc của {@code appraisalDataSource}. */
  @Transactional(value = TX, readOnly = true)
  public List<ScoringInput> page(TenantId tenant, UUID periodId, UUID after, int limit) {
    Objects.requireNonNull(periodId, "periodId");
    if (limit < 1 || limit > MAX_LIMIT + 1) {
      throw new IllegalArgumentException("limit");
    }
    return repository.page(tenant, periodId, after, limit);
  }

  /**
   * Nhiệm vụ kết thúc: đánh dấu đầu vào của người được giao trong kỳ đã đổi, một lần cho mỗi sự
   * kiện.
   *
   * @return số hồ sơ được đánh dấu, 0 khi sự kiện đã xử lý hoặc không có người được giao
   */
  @Transactional(TX)
  public int forAssignees(TenantId tenant, UUID periodId, UUID eventId, List<UUID> assigneeIds) {
    Set<UUID> people = new LinkedHashSet<>();
    if (assigneeIds != null) {
      assigneeIds.stream().filter(Objects::nonNull).forEach(people::add);
    }
    if (periodId == null || people.isEmpty()) {
      return 0;
    }
    if (!repository.claim(tenant, CONSUMER, eventId, SOURCE)) {
      return 0;
    }
    return repository.markChanged(tenant, periodId, people, clock.instant());
  }

  /**
   * Khoá sự kiện trong tiến trình (không có mã sự kiện riêng): nhiệm vụ, trạng thái sau, thời điểm.
   */
  public static UUID eventKey(UUID taskId, String newStatus, java.time.Instant occurredAt) {
    String key = taskId + "|" + newStatus + "|" + (occurredAt == null ? "" : occurredAt);
    return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
  }
}
