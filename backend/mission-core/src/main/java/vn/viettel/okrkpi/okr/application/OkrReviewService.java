package vn.viettel.okrkpi.okr.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.config.api.PeriodWriteTarget;
import vn.viettel.okrkpi.okr.application.OkrReadService.ObjectiveView;
import vn.viettel.okrkpi.okr.application.port.OkrAiTraceRepository;
import vn.viettel.okrkpi.okr.application.port.OkrAiTraceRepository.AiTrace;
import vn.viettel.okrkpi.okr.application.port.OkrApprovalEvents;
import vn.viettel.okrkpi.okr.application.port.OkrApprovalSignal;
import vn.viettel.okrkpi.okr.application.port.OkrReviewRepository;
import vn.viettel.okrkpi.okr.application.port.OkrReviewRepository.ReviewRow;
import vn.viettel.okrkpi.okr.application.port.OkrReviewRepository.ReviewTarget;
import vn.viettel.okrkpi.okr.domain.ObjectiveLifecycle;
import vn.viettel.okrkpi.okr.domain.ObjectiveLifecycle.Event;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Quyết định duyệt mục tiêu (CORE-01-06, QT-02 bước 7-8, SCR-0203): phê duyệt, trả lại, rút lại và
 * huỷ có lý do. Mỗi quyết định khoá mục tiêu, đổi trạng thái mục tiêu và KR, ghi {@code
 * okr.objective_review} và outbox trong cùng một giao dịch; báo workflow-service sau khi commit qua
 * {@link OkrApprovalSignal}.
 */
public class OkrReviewService {

  private static final Logger log = LoggerFactory.getLogger(OkrReviewService.class);

  static final String TX = OkrReadService.TX;
  static final String MODULE = "OKR";
  public static final String DUYET = "DUYET";
  public static final String TRA_LAI = "TRA_LAI";
  static final int COMMENT_MAX = 2000;
  static final int CANCEL_REASON_MIN = 10;

  private final OkrReviewRepository reviews;
  private final OkrReadService reads;
  private final OkrCheckService checks;
  private final PeriodApi periods;
  private final OkrApprovalEvents events;
  private final OkrAiTraceRepository traces;
  private final Supplier<OkrApprovalSignal> signal;
  private final Consumer<Object> publisher;

  public OkrReviewService(
      OkrReviewRepository reviews,
      OkrReadService reads,
      OkrCheckService checks,
      PeriodApi periods,
      OkrApprovalEvents events,
      Supplier<OkrApprovalSignal> signal,
      Consumer<Object> publisher) {
    this(reviews, reads, checks, periods, events, signal, publisher, null);
  }

  /** {@code traces} rỗng thì lịch sử và EVT-01 không mang dấu vết nội dung AI (CORE-01-13). */
  public OkrReviewService(
      OkrReviewRepository reviews,
      OkrReadService reads,
      OkrCheckService checks,
      PeriodApi periods,
      OkrApprovalEvents events,
      Supplier<OkrApprovalSignal> signal,
      Consumer<Object> publisher,
      OkrAiTraceRepository traces) {
    this.traces = traces;
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.reviews = Objects.requireNonNull(reviews, "reviews");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.checks = Objects.requireNonNull(checks, "checks");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.events = Objects.requireNonNull(events, "events");
    this.signal = Objects.requireNonNull(signal, "signal");
  }

  /**
   * Phê duyệt (BR-02-08: người duyệt khác người soạn). {@code requireClean} dùng cho duyệt hàng
   * loạt: bộ còn lỗi chặn hay cảnh báo thì không được duyệt theo lô (422).
   */
  @Transactional(transactionManager = TX)
  public long approve(
      TenantId tenant,
      UUID actor,
      UUID id,
      long expectedVersion,
      String comment,
      boolean requireClean) {
    requireCommentLength(comment);
    ReviewTarget target = lock(tenant, id, expectedVersion, Event.DUYET);
    if (actor.equals(target.createdBy())) {
      throw new BusinessException(
          MODULE + "-403-SELF-APPROVAL",
          "Đồng chí là người soạn bộ OKR này nên không thể phê duyệt.");
    }
    requireOpen(tenant, target);
    if (requireClean) {
      ObjectiveView view = reads.objective(tenant, id).orElseThrow(OkrComposeService.NotFound::new);
      if (checks.check(tenant, view).stream()
          .anyMatch(f -> !OkrRuleChecker.DAT.equals(f.level()))) {
        throw new BusinessException(
            MODULE + "-422-VALIDATION",
            "Bộ OKR còn lỗi hoặc cảnh báo, cần xem và phê duyệt riêng từng bộ.");
      }
    }
    long version = reviews.markApproved(tenant, target, actor);
    reviews.appendReview(tenant, target, actor, DUYET, blankToNull(comment));
    List<UUID> basisIds = reviews.basisIds(tenant, target.periodBucket(), id);
    List<AiTrace> aiContent = traces == null ? List.of() : traces.traces(tenant, id);
    if (aiContent.isEmpty()) {
      events.approved(tenant, target, actor, basisIds, version);
    } else {
      events.approved(tenant, target, actor, basisIds, version, aiContent);
    }
    reviews
        .previousApproved(tenant, target)
        .ifPresent(previous -> supersede(tenant, previous, target, actor, version));
    afterCommit(
        () ->
            signal(
                s ->
                    s.decided(
                        tenant,
                        id,
                        target.processInstanceId(),
                        DUYET,
                        actor,
                        target.rowVersion())));
    return version;
  }

  /**
   * BR-02-10: phiên bản điều chỉnh vừa được duyệt thì phiên bản cũ hết hiệu lực từ nay ({@code
   * DONG}, không sửa nội dung); báo người soạn mục tiêu cấp dưới đã duyệt và phát sự kiện nội bộ
   * {@link OkrObjectiveRevisionApproved} để chuyển giá trị check-in (CORE-01-12).
   */
  private void supersede(
      TenantId tenant, ReviewTarget previous, ReviewTarget revision, UUID actor, long version) {
    reviews.markClosed(tenant, previous, actor);
    List<UUID> children = reviews.childAuthors(tenant, previous.id());
    events.parentRevised(tenant, revision, children, actor, version);
    publisher.accept(
        new OkrObjectiveRevisionApproved(
            tenant, previous.id(), revision.id(), revision.periodBucket(), actor));
  }

  /**
   * Tạo phiên bản điều chỉnh của mục tiêu đã duyệt khi kỳ còn {@code MO} (BR-02-10): sao chép mục
   * tiêu, căn cứ, KR và liên kết sang dòng mới ở {@code NHAP}; phiên bản đã duyệt vẫn hiệu lực cho
   * check-in tới khi phiên bản mới được duyệt. Mỗi lúc chỉ một phiên bản điều chỉnh đang mở.
   */
  @Transactional(transactionManager = TX)
  public UUID createRevision(
      TenantId tenant, UUID actor, UUID id, long expectedVersion, String reason) {
    if (reason == null || reason.strip().length() < CANCEL_REASON_MIN) {
      throw new BusinessException(
          MODULE + "-422-VALIDATION", "Lý do điều chỉnh cần ít nhất 10 ký tự.");
    }
    requireCommentLength(reason);
    ReviewTarget target =
        reviews.lockTarget(tenant, id).orElseThrow(OkrComposeService.NotFound::new);
    if (target.rowVersion() != expectedVersion) {
      throw VersionConflictException.of(MODULE, expectedVersion, target.rowVersion());
    }
    if (!ObjectiveLifecycle.DA_DUYET.equals(target.status())) {
      throw new BusinessException(
          MODULE + "-409-INVALID-STATE", "Chỉ điều chỉnh được mục tiêu đã phê duyệt");
    }
    if (reviews.pendingRevision(tenant, target).isPresent()) {
      throw new BusinessException(
          MODULE + "-409-REVISION-PENDING", "Mục tiêu đang có một phiên bản điều chỉnh chưa duyệt");
    }
    PeriodSnapshot period =
        periods
            .findById(tenant, PeriodId.of(target.periodId()))
            .orElseThrow(() -> new IllegalStateException("Mục tiêu trỏ tới kỳ không tồn tại"));
    try {
      if (period.status() != PeriodStatus.MO) {
        throw new PeriodLockedException(period.id(), period.name(), MODULE);
      }
      periods.assertWritable(tenant, period.id(), PeriodWriteTarget.unit(target.ownerUnitId()));
    } catch (PeriodLockedException e) {
      throw new PeriodLockedException(period.id(), period.name(), MODULE);
    }
    UUID revision = reviews.cloneAsRevision(tenant, target, actor);
    log.info("Tạo phiên bản điều chỉnh {} của mục tiêu {}: {}", revision, id, reason.strip());
    return revision;
  }

  /** Trả lại để sửa: ý kiến bắt buộc (BR-02-09, MSG-02-10), mục tiêu về {@code NHAP}. */
  @Transactional(transactionManager = TX)
  public long sendBack(TenantId tenant, UUID actor, UUID id, long expectedVersion, String comment) {
    if (comment == null || comment.isBlank()) {
      throw new BusinessException(
          MODULE + "-422-COMMENT-REQUIRED", "Đồng chí cần nhập ý kiến khi trả lại bộ OKR.");
    }
    requireCommentLength(comment);
    ReviewTarget target = lock(tenant, id, expectedVersion, Event.TRA_LAI);
    requireOpen(tenant, target);
    long version = reviews.reopenDraft(tenant, target, actor, true);
    reviews.appendReview(tenant, target, actor, TRA_LAI, comment.strip());
    events.returned(tenant, target, actor, version);
    afterCommit(
        () ->
            signal(
                s ->
                    s.decided(
                        tenant,
                        id,
                        target.processInstanceId(),
                        TRA_LAI,
                        actor,
                        target.rowVersion())));
    return version;
  }

  /** Người soạn rút lại khi người duyệt chưa quyết định; tiến trình đang mở bị huỷ. */
  @Transactional(transactionManager = TX)
  public long withdraw(TenantId tenant, UUID actor, UUID id, long expectedVersion) {
    ReviewTarget target = lock(tenant, id, expectedVersion, Event.RUT_LAI);
    if (!actor.equals(target.createdBy())) {
      throw new Forbidden("Chỉ người soạn được rút lại bộ OKR đang chờ duyệt");
    }
    requireOpen(tenant, target);
    long version = reviews.reopenDraft(tenant, target, actor, false);
    afterCommit(
        () ->
            signal(
                s ->
                    s.cancelled(
                        tenant, id, target.processInstanceId(), actor, target.rowVersion())));
    return version;
  }

  /**
   * Người duyệt huỷ mục tiêu có lý do ({@code CHO_DUYET|DA_DUYET → DONG}, TL-02a QT-02, {@code
   * cancelObjective}); báo đơn vị sở hữu qua EVT-21. {@code objective_review} chỉ nhận {@code
   * DUYET}, {@code TRA_LAI} nên lý do huỷ chưa có chỗ lưu (cần cột hoặc giá trị mới ở TL-03).
   */
  @Transactional(transactionManager = TX)
  public long cancel(TenantId tenant, UUID actor, UUID id, long expectedVersion, String reason) {
    if (reason == null || reason.strip().length() < CANCEL_REASON_MIN) {
      throw new BusinessException(
          MODULE + "-422-VALIDATION", "Lý do huỷ mục tiêu cần ít nhất 10 ký tự.");
    }
    requireCommentLength(reason);
    ReviewTarget target = lock(tenant, id, expectedVersion, Event.HUY);
    if (actor.equals(target.createdBy())) {
      throw new BusinessException(
          MODULE + "-403-SELF-APPROVAL",
          "Đồng chí là người soạn bộ OKR này nên không thể huỷ thay người duyệt.");
    }
    requireOpen(tenant, target);
    long version = reviews.markClosed(tenant, target, actor);
    events.cancelled(tenant, target, actor, version);
    if (ObjectiveLifecycle.CHO_DUYET.equals(target.status())) {
      afterCommit(
          () ->
              signal(
                  s ->
                      s.cancelled(
                          tenant, id, target.processInstanceId(), actor, target.rowVersion())));
    }
    return version;
  }

  /** Nhãn nghiệp vụ của trường so với gợi ý AI, dùng trong ý kiến của mốc lịch sử. */
  static final Map<String, String> AI_FIELD_LABELS =
      Map.of(
          "title", "tên",
          "unit", "đơn vị đo",
          "baselineValue", "giá trị ban đầu",
          "targetValue", "chỉ tiêu");

  /**
   * Mỗi lần lưu có nội dung chèn từ gợi ý AI thành một mốc {@code LUU_NHAP} mang cờ AI; ý kiến liệt
   * kê trường giữ nguyên, trường đã sửa của mục tiêu và từng KR (SCR-1701 "Lịch sử nháp").
   */
  static List<HistoryEntry> aiSaves(List<AiTrace> traces) {
    Map<String, List<AiTrace>> bySave = new LinkedHashMap<>();
    for (AiTrace t : traces) {
      bySave.computeIfAbsent(t.at() + "|" + t.actor(), k -> new ArrayList<>()).add(t);
    }
    List<HistoryEntry> out = new ArrayList<>();
    for (List<AiTrace> save : bySave.values()) {
      AiTrace first = save.get(0);
      List<String> parts = new ArrayList<>();
      for (AiTrace t : save) {
        String target = t.keyResultId() == null ? "Mục tiêu" : "Kết quả then chốt";
        parts.add(
            target
                + ": giữ nguyên "
                + labels(t.keptFields())
                + "; đã sửa "
                + labels(t.editedFields()));
      }
      out.add(
          new HistoryEntry(
              UUID.nameUUIDFromBytes(
                  (first.objectiveId() + "|" + first.at() + "|" + first.actor())
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              first.at(),
              "LUU_NHAP",
              first.versionNo(),
              ObjectiveLifecycle.NHAP,
              first.actor(),
              null,
              "Chèn từ gợi ý của trợ lý. " + String.join(". ", parts) + ".",
              true));
    }
    return out;
  }

  private static String labels(List<String> fields) {
    return fields.isEmpty()
        ? "không có"
        : String.join(", ", fields.stream().map(f -> AI_FIELD_LABELS.getOrDefault(f, f)).toList());
  }

  /** Một mốc lịch sử của mục tiêu. */
  public record HistoryEntry(
      UUID id,
      Instant at,
      String action,
      int versionNo,
      String status,
      UUID actor,
      String decision,
      String comment,
      boolean aiAssisted) {}

  /**
   * Lịch sử mục tiêu, mới nhất trước: mốc tạo và các quyết định trong {@code objective_review}. Các
   * lần lưu nháp, trình, rút lại nằm ở lịch sử tiến trình của workflow-service, chưa có thao tác
   * đọc giữa các dịch vụ trong hợp đồng nên chưa ghép.
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<HistoryEntry> history(
      TenantId tenant, UUID id, Instant afterAt, UUID afterId, int limit) {
    var row = reads.objectiveRow(tenant, id).orElseThrow(OkrComposeService.NotFound::new);
    List<HistoryEntry> all = new ArrayList<>();
    for (ReviewRow r : reviews.reviews(tenant, id)) {
      boolean approved = DUYET.equals(r.decision());
      all.add(
          new HistoryEntry(
              r.id(),
              r.at(),
              r.decision(),
              r.versionNo(),
              approved ? ObjectiveLifecycle.DA_DUYET : ObjectiveLifecycle.NHAP,
              r.reviewerId(),
              r.decision(),
              r.comment(),
              false));
    }
    if (traces != null) {
      all.addAll(aiSaves(traces.traces(tenant, id)));
    }
    all.add(
        new HistoryEntry(
            id,
            row.createdAt(),
            "TAO",
            1,
            ObjectiveLifecycle.NHAP,
            row.createdBy(),
            null,
            null,
            row.aiAssisted()));
    Comparator<HistoryEntry> newestFirst =
        Comparator.comparing(HistoryEntry::at).thenComparing(HistoryEntry::id).reversed();
    return all.stream()
        .sorted(newestFirst)
        .filter(
            e ->
                afterAt == null
                    || e.at().isBefore(afterAt)
                    || (e.at().equals(afterAt) && e.id().compareTo(afterId) < 0))
        .limit(limit)
        .toList();
  }

  private ReviewTarget lock(TenantId tenant, UUID id, long expectedVersion, Event event) {
    ReviewTarget target =
        reviews.lockTarget(tenant, id).orElseThrow(OkrComposeService.NotFound::new);
    if (target.rowVersion() != expectedVersion) {
      throw VersionConflictException.of(MODULE, expectedVersion, target.rowVersion());
    }
    if (ObjectiveLifecycle.next(target.status(), event).isEmpty()) {
      throw new BusinessException(
          MODULE + "-409-INVALID-STATE",
          event == Event.HUY
              ? "Chỉ huỷ được mục tiêu đang chờ duyệt hoặc đã duyệt"
              : "Mục tiêu không còn ở trạng thái chờ phê duyệt");
    }
    return target;
  }

  private void requireOpen(TenantId tenant, ReviewTarget target) {
    PeriodSnapshot period =
        periods
            .findById(tenant, PeriodId.of(target.periodId()))
            .orElseThrow(() -> new IllegalStateException("Mục tiêu trỏ tới kỳ không tồn tại"));
    try {
      periods.assertWritable(tenant, period.id(), PeriodWriteTarget.unit(target.ownerUnitId()));
    } catch (PeriodLockedException e) {
      throw new PeriodLockedException(period.id(), period.name(), MODULE);
    }
  }

  private static void requireCommentLength(String comment) {
    if (comment != null && comment.length() > COMMENT_MAX) {
      throw new BusinessException(MODULE + "-422-VALIDATION", "Ý kiến không vượt quá 2.000 ký tự.");
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private void signal(java.util.function.Consumer<OkrApprovalSignal> work) {
    OkrApprovalSignal port = signal.get();
    if (port == null) {
      return;
    }
    try {
      work.accept(port);
    } catch (RuntimeException e) {
      log.warn("Chưa báo được workflow-service kết quả duyệt: {}", e.toString());
    }
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

  /** Người gọi không có quyền với thao tác trên mục tiêu (403). */
  public static final class Forbidden extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Forbidden(String message) {
      super(message, null, false, false);
    }
  }
}
