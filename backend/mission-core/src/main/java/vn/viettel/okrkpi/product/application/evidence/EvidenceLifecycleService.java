package vn.viettel.okrkpi.product.application.evidence;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceLifecycleRepository;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceLifecycleRepository.DeliverableRow;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceLifecycleRepository.LockedVersion;
import vn.viettel.okrkpi.product.domain.evidence.DeliverableRollup;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceRuleViolation;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceVersion;
import vn.viettel.okrkpi.product.domain.evidence.TaskInterlock;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskApi.DeliverableState;
import vn.viettel.okrkpi.task.api.TaskApi.TransitionOutcome;
import vn.viettel.okrkpi.task.api.TaskApi.TransitionRequest;

/**
 * Lệnh của máy trạng thái phiên bản minh chứng và liên động nhiệm vụ (CORE-03-02, QT-07, BR-07-10).
 * API nộp và nghiệm thu (CORE-03-03, CORE-03-06, CORE-03-07) chỉ gọi các lệnh ở đây.
 *
 * <p>Thứ tự trong giao dịch {@code productTransactionManager}: khoá phiên bản, so {@code
 * row_version} (lệch thì 409), kiểm kỳ còn ghi được, chạy lệnh domain, ghi phiên bản, minh chứng và
 * tổng hợp sản phẩm, rồi cuối cùng yêu cầu MOD-02 chuyển trạng thái nhiệm vụ qua {@link TaskApi}.
 * Mỗi mô-đun có {@code DataSource} riêng nên lời gọi {@link TaskApi} chạy trong giao dịch của
 * {@code taskTransactionManager} và commit trước; MOD-02 từ chối thì ngoại lệ đi lên và giao dịch
 * minh chứng quay lui. Yêu cầu là lũy đẳng theo trạng thái, nên khi giao dịch minh chứng hỏng sau
 * khi nhiệm vụ đã chuyển, lần thao tác lại hoặc lần liên động kế tiếp tự khớp (wiki 14).
 */
public class EvidenceLifecycleService {

  private static final Logger LOG = LoggerFactory.getLogger(EvidenceLifecycleService.class);

  static final String TX = "productTransactionManager";

  /** Mã lỗi guard của MOD-02 (COMMON-422-VALIDATION) khi chưa đủ điều kiện chuyển. */
  static final String TASK_GUARD_FAILED = "COMMON-422-VALIDATION";

  private final EvidenceLifecycleRepository repository;
  private final PeriodApi periods;
  private final TaskApi tasks;
  private final Clock clock;

  public EvidenceLifecycleService(
      EvidenceLifecycleRepository repository, PeriodApi periods, TaskApi tasks, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Kết quả: phiên bản sau khi ghi và lệnh nhiệm vụ đã yêu cầu (nếu có). */
  public record Result(
      EvidenceVersion version,
      long rowVersion,
      Optional<TaskInterlock.TaskRequest> taskRequest,
      Optional<TransitionOutcome> taskOutcome) {}

  @Transactional(TX)
  public Result saveDraft(
      TenantId tenant,
      UUID versionId,
      long expectedVersion,
      EvidenceVersion.DraftContent content,
      EvidenceActor actor) {
    LockedVersion locked = lock(tenant, versionId, expectedVersion);
    try {
      periods.assertWritable(tenant, PeriodId.of(locked.periodId()));
    } catch (PeriodLockedException e) {
      throw periodLocked(locked);
    }
    return apply(tenant, locked, actor, null, v -> v.saveDraft(content));
  }

  @Transactional(TX)
  public Result submitVersion(
      TenantId tenant, UUID versionId, long expectedVersion, EvidenceActor actor) {
    LockedVersion locked = lock(tenant, versionId, expectedVersion);
    try {
      periods.assertWritable(tenant, PeriodId.of(locked.periodId()));
    } catch (PeriodLockedException e) {
      throw periodLocked(locked);
    }
    Instant now = clock.instant();
    return apply(tenant, locked, actor, null, v -> v.submit(actor.personId(), now));
  }

  @Transactional(TX)
  public Result openReview(
      TenantId tenant, UUID versionId, long expectedVersion, EvidenceActor actor) {
    LockedVersion locked = lock(tenant, versionId, expectedVersion);
    try {
      periods.assertWritable(tenant, PeriodId.of(locked.periodId()));
    } catch (PeriodLockedException e) {
      throw periodLocked(locked);
    }
    return apply(tenant, locked, actor, null, EvidenceVersion::openReview);
  }

  @Transactional(TX)
  public Result acceptVersion(
      TenantId tenant, UUID versionId, long expectedVersion, EvidenceActor actor) {
    LockedVersion locked = lock(tenant, versionId, expectedVersion);
    try {
      periods.assertWritable(tenant, PeriodId.of(locked.periodId()));
    } catch (PeriodLockedException e) {
      throw periodLocked(locked);
    }
    Instant now = clock.instant();
    return apply(tenant, locked, actor, null, v -> v.accept(now));
  }

  @Transactional(TX)
  public Result requestRevision(
      TenantId tenant, UUID versionId, long expectedVersion, String comment, EvidenceActor actor) {
    LockedVersion locked = lock(tenant, versionId, expectedVersion);
    try {
      periods.assertWritable(tenant, PeriodId.of(locked.periodId()));
    } catch (PeriodLockedException e) {
      throw periodLocked(locked);
    }
    return apply(tenant, locked, actor, comment, v -> v.requestRevision(comment));
  }

  @Transactional(TX)
  public Result rejectVersion(
      TenantId tenant, UUID versionId, long expectedVersion, String reason, EvidenceActor actor) {
    LockedVersion locked = lock(tenant, versionId, expectedVersion);
    try {
      periods.assertWritable(tenant, PeriodId.of(locked.periodId()));
    } catch (PeriodLockedException e) {
      throw periodLocked(locked);
    }
    return apply(tenant, locked, actor, reason, v -> v.reject(reason));
  }

  private LockedVersion lock(TenantId tenant, UUID versionId, long expectedVersion) {
    LockedVersion locked =
        repository.lockVersion(tenant, versionId).orElseThrow(NoSuchElementException::new);
    if (locked.version().rowVersion() != expectedVersion) {
      throw new VersionConflictException("PRODUCT", expectedVersion, locked.version().rowVersion());
    }
    return locked;
  }

  /** Kỳ đã khoá: mã lỗi của MOD-03 ({@code PRODUCT-409-PERIOD-LOCKED}). */
  private static PeriodLockedException periodLocked(LockedVersion locked) {
    return new PeriodLockedException(PeriodId.of(locked.periodId()), null, "PRODUCT");
  }

  private Result apply(
      TenantId tenant,
      LockedVersion locked,
      EvidenceActor actor,
      String reason,
      UnaryOperator<EvidenceVersion> command) {
    EvidenceVersion before = locked.version();
    EvidenceVersion after;
    try {
      after = command.apply(before);
    } catch (EvidenceRuleViolation e) {
      throw new BusinessException(e.code(), e.getMessage());
    }
    Instant now = clock.instant();
    long rowVersion =
        repository
            .updateVersion(
                tenant, locked.periodBucket(), after, before.rowVersion(), actor.personId(), now)
            .orElseThrow(() -> new VersionConflictException("PRODUCT", before.rowVersion(), null));
    if (after.status() != before.status()) {
      repository.updateEvidenceCurrent(
          tenant,
          locked.periodBucket(),
          after.evidenceId(),
          after.id(),
          after.status().name(),
          actor.personId(),
          now);
    }
    if (after.status() == before.status()) {
      return new Result(after, rowVersion, Optional.empty(), Optional.empty());
    }

    List<DeliverableRow> deliverables = repository.lockDeliverablesOfTask(tenant, after.taskId());
    List<EvidenceVersion> current = repository.currentVersionsOfTask(tenant, after.taskId());
    Map<UUID, List<EvidenceVersion>> byDeliverable =
        current.stream().collect(Collectors.groupingBy(EvidenceVersion::deliverableId));
    List<DeliverableRollup> rollups = new ArrayList<>();
    for (DeliverableRow d : deliverables) {
      DeliverableRollup r =
          DeliverableRollup.of(d.quantityPlanned(), byDeliverable.getOrDefault(d.id(), List.of()));
      rollups.add(r);
      repository.updateDeliverable(tenant, d.periodBucket(), d.id(), r, actor.personId(), now);
    }

    Optional<TaskInterlock.TaskRequest> request = TaskInterlock.after(after.status(), rollups);
    Optional<TransitionOutcome> outcome =
        request.flatMap(r -> requestTask(tenant, after.taskId(), r, actor, reason, current));
    return new Result(after, rowVersion, request, outcome);
  }

  /** Ngày nộp phiên bản cuối trong các phiên bản được tính điểm (ngày hoàn thành tạm, QT-07). */
  private static Instant lastSubmittedAt(List<EvidenceVersion> current) {
    return current.stream()
        .filter(EvidenceVersion::countsForScoring)
        .map(EvidenceVersion::submittedAt)
        .filter(Objects::nonNull)
        .max(Instant::compareTo)
        .orElse(null);
  }

  private Optional<TransitionOutcome> requestTask(
      TenantId tenant,
      UUID taskId,
      TaskInterlock.TaskRequest request,
      EvidenceActor actor,
      String reason,
      List<EvidenceVersion> current) {
    // Biến deliverables của guard: minh chứng đã nộp còn hiệu lực (bỏ bản nháp và bản bị trả lại).
    List<DeliverableState> snapshot =
        current.stream()
            .filter(v -> v.status().isSubmittedAndLive())
            .map(v -> new DeliverableState(v.deliverableId(), v.status().name()))
            .toList();
    TransitionRequest call =
        new TransitionRequest(
            taskId,
            TaskApi.Command.valueOf(request.name()),
            actor.personId(),
            actor.roles(),
            reason,
            snapshot,
            request == TaskInterlock.TaskRequest.APPROVE ? lastSubmittedAt(current) : null);
    try {
      return Optional.of(tasks.requestTransition(tenant, call));
    } catch (BusinessException e) {
      if (request == TaskInterlock.TaskRequest.SUBMIT
          && TASK_GUARD_FAILED.equals(e.errorCode().code())) {
        // Đủ sản phẩm theo MOD-03 nhưng bảng chuyển của Policy Pack chưa cho nộp (ví dụ khối lượng
        // kế hoạch của nhiệm vụ lớn hơn số sản phẩm): giữ phiên bản đã nộp, nhiệm vụ chưa đổi.
        LOG.info("Chưa chuyển nhiệm vụ {} sang chờ nghiệm thu: điều kiện chưa đạt", taskId);
        return Optional.empty();
      }
      throw e;
    }
  }
}
