package vn.viettel.okrkpi.product.application.evidence;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.EvidenceView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.VersionView;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceFile;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceRuleViolation;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceStatus;
import vn.viettel.okrkpi.product.domain.evidence.ReviewRules;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.audit.AuditOutcome;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskSummary;

/**
 * Mở nghiệm thu và kiểm toàn vẹn tệp của QT-07 (SCR-0703, CORE-03-06). Lần mở đầu chuyển phiên bản
 * {@code DA_NOP → DANG_NGHIEM_THU} qua {@link EvidenceLifecycleService}; mọi lần mở đều so mã băm
 * và phiên bản đối tượng của từng tệp với bản ghi của SVC-08 (bản chiếu {@code
 * product.evidence_object} nhận từ sự kiện lưu và quét của SVC-08).
 *
 * <p>Tệp không khớp thì giữ trạng thái, phát cảnh báo qua {@link EvidenceIntegrityAlarm} (giao dịch
 * riêng) rồi trả {@code PRODUCT-409-INTEGRITY-FAILED} (MSG-07-13).
 */
public class EvidenceReviewService {

  static final String TX = EvidenceLifecycleService.TX;
  static final String AUDIT_OPEN = "OPEN_EVIDENCE_REVIEW";

  private final EvidenceSubmissionRepository repository;
  private final EvidenceLifecycleService lifecycle;
  private final TaskApi tasks;
  private final EvidenceIntegrityAlarm alarm;
  private final AuditLogger audit;
  private ReviewerAuthority authority;

  public EvidenceReviewService(
      EvidenceSubmissionRepository repository,
      EvidenceLifecycleService lifecycle,
      TaskApi tasks,
      EvidenceIntegrityAlarm alarm,
      AuditLogger audit) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.alarm = Objects.requireNonNull(alarm, "alarm");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.authority = ReviewerAuthority.withoutDelegations(tasks);
  }

  /** Cho phép người nhận uỷ quyền nghiệm thu thay mở nghiệm thu (CORE-03-08). */
  public void setAuthority(ReviewerAuthority authority) {
    this.authority = Objects.requireNonNull(authority, "authority");
  }

  /** Toàn vẹn không bảo đảm (MSG-07-13); cảnh báo đã ghi trong giao dịch riêng. */
  public static final class IntegrityFailedException extends BusinessException {

    @Serial private static final long serialVersionUID = 1L;

    IntegrityFailedException() {
      super(
          EvidenceRuleViolation.INTEGRITY_FAILED,
          "Tệp minh chứng không còn nguyên vẹn so với lúc nộp; chưa thể nghiệm thu. Cảnh báo đã"
              + " được gửi tới bộ phận an toàn thông tin");
    }
  }

  /**
   * Mở nghiệm thu; trả {@code true} khi lần mở này đổi trạng thái, {@code false} khi phiên bản đã
   * đang nghiệm thu.
   */
  @Transactional(TX)
  public boolean openReview(
      TenantId tenant,
      UUID evidenceId,
      int versionNo,
      long expectedRowVersion,
      EvidenceActor actor) {
    EvidenceView e =
        repository.findEvidence(tenant, evidenceId).orElseThrow(NoSuchElementException::new);
    VersionView v =
        repository
            .findVersion(tenant, e.periodBucket(), evidenceId, versionNo)
            .orElseThrow(NoSuchElementException::new);
    TaskSummary task = tasks.find(tenant, e.taskId()).orElseThrow(NoSuchElementException::new);
    UUID me = actor.personId();
    EvidenceStatus status = EvidenceStatus.valueOf(v.status());
    ReviewerAuthority.Acting acting;
    try {
      acting = authority.resolve(tenant, task, me, v.submittedBy());
      ReviewRules.requireReviewable(status);
    } catch (EvidenceRuleViolation ex) {
      throw new BusinessException(ex.code(), ex.getMessage());
    }
    if (v.rowVersion() != expectedRowVersion) {
      throw new VersionConflictException("PRODUCT", expectedRowVersion, v.rowVersion());
    }
    List<EvidenceFile> files = requireIntact(tenant, e, v, task, me);
    try {
      ReviewRules.requireClean(files);
    } catch (EvidenceRuleViolation ex) {
      throw new BusinessException(ex.code(), ex.getMessage());
    }
    audit.record(
        AuditEvent.builder(AuditEventType.SENSITIVE_DATA_ACCESS, ActorId.of(me), AUDIT_OPEN)
            .outcome(AuditOutcome.SUCCESS)
            .tenant(tenant)
            .object("evidence_version", v.id().toString())
            .classification(v.hasE2ee() ? Classification.C3 : Classification.C2)
            .attribute("evidenceId", e.id().toString())
            .attribute("versionNo", Integer.toString(versionNo))
            .attribute("onBehalfOf", acting.substitute() ? acting.onBehalfOf().toString() : "")
            .attribute("delegationId", acting.substitute() ? acting.delegationId().toString() : "")
            .build());
    if (status == EvidenceStatus.DANG_NGHIEM_THU) {
      return false;
    }
    lifecycle.openReview(tenant, v.id(), expectedRowVersion, actor);
    return true;
  }

  /**
   * Tệp của phiên bản còn khớp bản ghi của SVC-08; lệch thì phát cảnh báo (giao dịch riêng) và ném
   * {@link IntegrityFailedException}. Dùng chung cho mở nghiệm thu và xác nhận nghiệm thu.
   */
  List<EvidenceFile> requireIntact(
      TenantId tenant, EvidenceView e, VersionView v, TaskSummary task, UUID me) {
    List<EvidenceFile> files =
        repository.filesOf(tenant, v.periodBucket(), v.id()).stream()
            .map(EvidenceSubmissionService::file)
            .toList();
    List<EvidenceFile> tampered = ReviewRules.tampered(files);
    if (!tampered.isEmpty()) {
      raiseIntegrityAlert(tenant, e, v, task, me, tampered);
      throw new IntegrityFailedException();
    }
    return files;
  }

  private void raiseIntegrityAlert(
      TenantId tenant,
      EvidenceView e,
      VersionView v,
      TaskSummary task,
      UUID me,
      List<EvidenceFile> tampered) {
    List<UUID> recipients = new ArrayList<>();
    recipients.add(me);
    if (task.assignerId() != null && !task.assignerId().equals(me)) {
      recipients.add(task.assignerId());
    }
    alarm.raise(
        new EvidenceIntegrityAlarm.Alert(
            tenant,
            e.id(),
            v.id(),
            v.versionNo(),
            v.rowVersion(),
            task.code(),
            v.hasE2ee(),
            me,
            recipients,
            tampered.stream().map(EvidenceFile::objectId).toList()));
  }

  /** Người gọi có trong danh sách người thực hiện (chủ trì hoặc phối hợp) của nhiệm vụ. */
  static boolean coordinates(TaskApi tasks, TenantId tenant, TaskSummary task, UUID person) {
    if (task.periodId() == null) {
      return false;
    }
    PeriodId period = PeriodId.of(task.periodId());
    UUID after = null;
    while (true) {
      List<TaskSummary> page =
          tasks.listForPerson(tenant, period, person, after, TaskApi.MAX_LIMIT);
      if (page.stream().anyMatch(t -> t.id().equals(task.id()))) {
        return true;
      }
      if (page.size() < TaskApi.MAX_LIMIT) {
        return false;
      }
      after = page.get(page.size() - 1).id();
    }
  }
}
