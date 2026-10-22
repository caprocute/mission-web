package vn.viettel.okrkpi.okr.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodWriteTarget;
import vn.viettel.okrkpi.okr.application.OkrReadService.ObjectiveView;
import vn.viettel.okrkpi.okr.application.OkrRuleChecker.Finding;
import vn.viettel.okrkpi.okr.application.port.OkrApprovalStarter;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository.ObjectiveHead;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Trình phê duyệt mục tiêu (CORE-01-05, QT-02 bước 5-6, SCR-0202 "Trình phê duyệt"): kiểm lại toàn
 * bộ quy tắc BR-02-01..07 bằng {@link OkrCheckService}, còn lỗi chặn thì trả 422; hợp lệ thì chuyển
 * mục tiêu và KR từ {@code NHAP} sang {@code CHO_DUYET}. Tiến trình {@code duyet-okr} khởi tạo sau
 * khi giao dịch commit qua {@link OkrApprovalStarter} (idempotent ở workflow-service), không gọi
 * mạng trong giao dịch; mục tiêu {@code CHO_DUYET} chưa có {@code wf_process_instance_id} là đang
 * chờ khởi tạo.
 */
public class OkrSubmitService {

  private static final Logger log = LoggerFactory.getLogger(OkrSubmitService.class);

  static final String TX = OkrReadService.TX;
  static final String MODULE = "OKR";
  static final String CHO_DUYET = "CHO_DUYET";

  /** Mã quy tắc mục tiêu cấp trên chưa duyệt (MSG-02-03), trả mã lỗi riêng. */
  static final String PARENT_RULE = "MSG-02-03";

  private final OkrWriteRepository writes;
  private final OkrReadService reads;
  private final OkrCheckService checks;
  private final PeriodApi periods;
  private final Supplier<OkrApprovalStarter> starter;
  private final TransactionOperations afterCommitTx;

  public OkrSubmitService(
      OkrWriteRepository writes,
      OkrReadService reads,
      OkrCheckService checks,
      PeriodApi periods,
      Supplier<OkrApprovalStarter> starter,
      TransactionOperations afterCommitTx) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.checks = Objects.requireNonNull(checks, "checks");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.starter = Objects.requireNonNull(starter, "starter");
    this.afterCommitTx = Objects.requireNonNull(afterCommitTx, "afterCommitTx");
  }

  /** Trình mục tiêu {@code id} đang ở phiên bản {@code expectedVersion}; trả phiên bản mới. */
  @Transactional(transactionManager = TX)
  public long submit(TenantId tenant, UUID actor, UUID id, long expectedVersion) {
    ObjectiveHead head =
        writes.lockObjective(tenant, id).orElseThrow(OkrComposeService.NotFound::new);
    if (head.rowVersion() != expectedVersion) {
      throw VersionConflictException.of(MODULE, expectedVersion, head.rowVersion());
    }
    if (!OkrComposeService.NHAP.equals(head.status())) {
      throw new BusinessException(
          MODULE + "-409-INVALID-STATE", "Chỉ trình được mục tiêu đang soạn thảo");
    }
    PeriodSnapshot period =
        periods
            .findById(tenant, PeriodId.of(head.periodId()))
            .orElseThrow(() -> new IllegalStateException("Mục tiêu trỏ tới kỳ không tồn tại"));
    try {
      periods.assertWritable(tenant, period.id(), PeriodWriteTarget.unit(head.ownerUnitId()));
    } catch (PeriodLockedException e) {
      throw new PeriodLockedException(period.id(), period.name(), MODULE);
    }
    ObjectiveView view = reads.objective(tenant, id).orElseThrow(OkrComposeService.NotFound::new);
    rejectBlocking(checks.check(tenant, view));
    long version = writes.transitionObjective(tenant, head, CHO_DUYET, actor);
    afterCommit(() -> launch(tenant, head, version, actor));
    return version;
  }

  /** BR-02-02, BR-02-04, BR-02-05, BR-02-07: còn lỗi chặn thì 422, giữ {@code NHAP}. */
  private static void rejectBlocking(List<Finding> findings) {
    List<Finding> blocking =
        findings.stream().filter(f -> OkrRuleChecker.LOI_CHAN.equals(f.level())).toList();
    if (blocking.isEmpty()) {
      return;
    }
    List<ProblemFieldError> errors = new ArrayList<>();
    for (Finding f : blocking) {
      errors.add(
          new ProblemFieldError(
              f.keyResultId() == null ? "objective" : "keyResults." + f.keyResultId(),
              f.message(),
              f.br()));
    }
    boolean parent = blocking.stream().anyMatch(f -> PARENT_RULE.equals(f.ruleId()));
    throw new BusinessException(
        MODULE + (parent ? "-422-PARENT-NOT-APPROVED" : "-422-VALIDATION"),
        (parent
                ? blocking.stream().filter(f -> PARENT_RULE.equals(f.ruleId())).findFirst()
                : blocking.stream().findFirst())
            .map(Finding::message)
            .orElseThrow(),
        List.copyOf(errors));
  }

  private static void afterCommit(Runnable work) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      work.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            work.run();
          }
        });
  }

  /** Khởi tạo tiến trình sau commit; lỗi chỉ ghi nhật ký, mục tiêu giữ {@code CHO_DUYET}. */
  private void launch(TenantId tenant, ObjectiveHead head, long version, UUID actor) {
    OkrApprovalStarter port = starter.get();
    if (port == null) {
      return;
    }
    try {
      port.start(tenant, head.id(), head.ownerUnitId(), version, actor)
          .ifPresent(
              started ->
                  TenantContext.run(
                      tenant,
                      () ->
                          afterCommitTx.executeWithoutResult(
                              s ->
                                  writes.attachProcessInstance(
                                      tenant,
                                      head.periodBucket(),
                                      head.id(),
                                      started.processInstanceId()))));
    } catch (RuntimeException e) {
      log.warn(
          "Chưa khởi tạo được tiến trình duyệt cho mục tiêu {}, sẽ thử lại: {}",
          head.id(),
          e.toString());
    }
  }
}
