package vn.viettel.okrkpi.appraisal.application.annual;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository;
import vn.viettel.okrkpi.appraisal.application.annual.port.AnnualRatingRepository.ProposalTarget;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.shared.audit.AuditEvent;
import vn.viettel.okrkpi.shared.audit.AuditEventType;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.audit.AuditOutcome;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;

/**
 * Lập đề xuất bố trí lại công việc (QT-10 bước 7, SCR-1001, CORE-04-11): với dòng năm có cờ hai quý
 * liên tiếp KHTNV (BR-10-04, QĐ khoản 15.6), tạo việc cho cơ quan tổ chức cán bộ trong MOD-02 qua
 * {@link TaskApi} (MOD-02 phát EVT-04), rồi đặt {@code relocation_proposed}. Không đổi mức xếp
 * loại. Dòng năm được khoá trong suốt thao tác nên hai lần gọi đồng thời chỉ tạo một việc.
 */
public class AnnualReassignmentService {

  static final String TX = "appraisalTransactionManager";

  public static final String NO_FLAG = "APPRAISAL-409-NO-REASSIGN-FLAG";
  public static final String EXISTS = "APPRAISAL-409-REASSIGN-EXISTS";
  public static final String VERSION_CONFLICT = "APPRAISAL-409-VERSION-CONFLICT";

  static final String AUDIT_ACTION = "createAnnualReassignmentProposal";
  static final String TITLE =
      "Xem xét bố trí lại công việc đối với cán bộ có hai quý liên tiếp"
          + " không hoàn thành nhiệm vụ";
  static final String BASIS =
      "Quy định 366-QĐ/TW khoản 15.6: hai quý liên tiếp xếp loại không"
          + " hoàn thành nhiệm vụ thì xem xét bố trí lại công việc";
  static final String PRODUCT = "Văn bản đề xuất bố trí lại công việc gửi cấp có thẩm quyền";
  static final String QUALITY = "Nêu rõ căn cứ, kết quả xếp loại các quý và phương án bố trí";
  static final String EVIDENCE = "Văn bản đề xuất đã trình cấp có thẩm quyền";

  /** Kết quả: việc đã tạo và phiên bản mới của dòng năm. */
  public record Proposal(UUID annualRatingId, UUID taskId, long rowVersion) {}

  /** Người gọi: mã cán bộ và vai trò realm. */
  public record Caller(UUID personId, Set<String> roles) {
    public Caller {
      Objects.requireNonNull(personId, "personId");
      roles = Set.copyOf(roles == null ? Set.of() : roles);
    }
  }

  private final AnnualRatingRepository repository;
  private final TaskApi tasks;
  private final PeriodApi periods;
  private final AuditLogger audit;
  private final Clock clock;
  private final ZoneId zone;
  private final int dueDays;

  public AnnualReassignmentService(
      AnnualRatingRepository repository,
      TaskApi tasks,
      PeriodApi periods,
      AuditLogger audit,
      Clock clock,
      ZoneId zone,
      int dueDays) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.audit = audit;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.zone = Objects.requireNonNull(zone, "zone");
    if (dueDays < 1) {
      throw new IllegalArgumentException("Hạn đề xuất bố trí lại phải từ một ngày");
    }
    this.dueDays = dueDays;
  }

  /**
   * Lập đề xuất.
   *
   * @param expectedVersion {@code rowVersion} của dòng năm trong {@code If-Match}
   * @param authorize kiểm quyền trên dòng đã khoá (OPA {@code SCR-1001.bo-tri-lai}); ném lỗi khi từ
   *     chối
   * @throws NoSuchElementException không có dòng năm trong tenant
   * @throws BusinessException 409 khi lệch phiên bản, không có cờ hoặc đã lập
   */
  @Transactional(TX)
  public Proposal propose(
      TenantId tenant,
      UUID annualRatingId,
      long expectedVersion,
      Caller caller,
      Consumer<ProposalTarget> authorize) {
    ProposalTarget row =
        repository.lockForProposal(tenant, annualRatingId).orElseThrow(NoSuchElementException::new);
    authorize.accept(row);
    if (row.rowVersion() != expectedVersion) {
      throw new BusinessException(
          VERSION_CONFLICT, "Dữ liệu xếp loại năm đã thay đổi, đồng chí vui lòng tải lại.");
    }
    if (!row.reassignFlag()) {
      throw new BusinessException(
          NO_FLAG, "Cán bộ không có hai quý liên tiếp không hoàn thành nhiệm vụ.");
    }
    if (row.relocationProposed()) {
      throw new BusinessException(EXISTS, "Đề xuất bố trí lại của cán bộ này đã được lập.");
    }
    LocalDate today = LocalDate.now(clock.withZone(zone));
    LocalDate due = today.plusDays(dueDays);
    PeriodSnapshot period = periodFor(tenant, today, due);
    LocalDate dueInPeriod = due.isAfter(period.endDate()) ? period.endDate() : due;
    UUID acceptor = acceptorOf(row, caller.personId());
    UUID taskId =
        tasks.openDirectedTask(
            tenant,
            new TaskApi.DirectedTask(
                period.id().value(),
                TITLE,
                BASIS,
                PRODUCT,
                QUALITY,
                EVIDENCE,
                dueInPeriod,
                caller.personId(),
                acceptor,
                caller.personId(),
                caller.roles()));
    long version =
        repository.markRelocationProposed(tenant, row.periodBucket(), row.id(), caller.personId());
    if (audit != null) {
      audit.record(
          AuditEvent.builder(
                  AuditEventType.ADMIN_ACTION, ActorId.of(caller.personId()), AUDIT_ACTION)
              .outcome(AuditOutcome.SUCCESS)
              .tenant(tenant)
              .object("annual_rating", row.id().toString())
              .attribute("taskId", taskId.toString())
              .attribute("profileId", row.profileId().toString())
              .attribute("rule", "BR-10-04")
              .build());
    }
    return new Proposal(row.id(), taskId, version);
  }

  /**
   * Kỳ quý chứa hạn 30 ngày; chưa có thì kỳ quý hiện hành và hạn rút về cuối kỳ (suy luận, wiki
   * 14).
   */
  private PeriodSnapshot periodFor(TenantId tenant, LocalDate today, LocalDate due) {
    Optional<PeriodSnapshot> covering = periods.findCovering(tenant, "QUY", due);
    if (covering.isPresent()) {
      return covering.get();
    }
    return periods
        .findCovering(tenant, "QUY", today)
        .or(() -> periods.findCurrent(tenant, "QUY"))
        .orElseThrow(
            () ->
                new BusinessException(
                    "TASK-409-PERIOD-LOCKED",
                    "Chưa có kỳ đang mở để giao việc đề xuất bố trí lại."));
  }

  /**
   * Người nghiệm thu khác người chủ trì (BR-05-02): người quyết định của hồ sơ năm, nếu trùng người
   * gọi thì người thẩm định; không có thì để MOD-02 lấy người nghiệm thu mặc định.
   */
  static UUID acceptorOf(ProposalTarget row, UUID caller) {
    if (row.deciderId() != null && !row.deciderId().equals(caller)) {
      return row.deciderId();
    }
    if (row.appraiserId() != null && !row.appraiserId().equals(caller)) {
      return row.appraiserId();
    }
    return null;
  }
}
