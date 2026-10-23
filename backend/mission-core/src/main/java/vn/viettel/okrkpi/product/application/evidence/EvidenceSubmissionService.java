package vn.viettel.okrkpi.product.application.evidence;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceEvents;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.DeliverableView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.DraftDetails;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.Duplicate;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.EvidenceFilter;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.EvidenceView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.FileRow;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.FileView;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.NewDeliverable;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.NewEvidence;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.StoredObject;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.VersionSummary;
import vn.viettel.okrkpi.product.application.evidence.port.EvidenceSubmissionRepository.VersionView;
import vn.viettel.okrkpi.product.application.evidence.port.IssuedDocuments;
import vn.viettel.okrkpi.product.domain.evidence.AiPrefillRules;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceFile;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceRuleViolation;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceStatus;
import vn.viettel.okrkpi.product.domain.evidence.EvidenceVersion;
import vn.viettel.okrkpi.product.domain.evidence.SubmissionRules;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.task.api.TaskApi;
import vn.viettel.okrkpi.task.api.TaskApi.Outcome;
import vn.viettel.okrkpi.task.api.TaskSummary;

/**
 * Khai báo sản phẩm, tạo minh chứng, lưu nháp và nộp phiên bản minh chứng của QT-07 (SCR-0701,
 * CORE-03-03). Chỉ người chủ trì nhiệm vụ được ghi (TL-02a SCR-0701); trạng thái phiên bản đổi qua
 * {@link EvidenceLifecycleService}, nên nộp đủ sản phẩm thì nhiệm vụ tự sang chờ nghiệm thu.
 *
 * <p>Giao dịch {@code productTransactionManager}: EVT-07 và EVT-21 ghi vào {@code
 * product.outbox_event} cùng giao dịch với phiên bản.
 */
public class EvidenceSubmissionService {

  static final String TX = EvidenceLifecycleService.TX;

  static final String VALIDATION = "COMMON-422-VALIDATION";
  static final String NOT_TASK_OWNER = "PRODUCT-403-NOT-TASK-OWNER";
  static final String TASK_STATE = "PRODUCT-409-TASK-STATE";
  static final String TASK_NOT_OPEN = "PRODUCT-422-TASK-NOT-OPEN";

  /** Nguồn phiên bản lấy từ văn bản đã ban hành (CORE-03-04). */
  static final String EXTERNAL_SOURCE = "VAN_BAN_HE_THONG";

  /** Quy tắc văn bản đã ban hành làm minh chứng: chữ ký số gốc, không sửa tệp gốc. */
  static final String EXTERNAL_RULE = "BR-07-13";

  /** Nhiệm vụ nhận khai báo sản phẩm. */
  static final Set<String> DECLARE_STATES = Set.of("DA_GIAO", "DANG_THUC_HIEN");

  /** Nhiệm vụ nhận minh chứng (sau khi tiếp nhận, kể cả lúc đã trình nghiệm thu một phần). */
  static final Set<String> EVIDENCE_STATES = Set.of("DANG_THUC_HIEN", "CHO_NGHIEM_THU");

  private final EvidenceSubmissionRepository repository;
  private final EvidenceLifecycleService lifecycle;
  private final PeriodApi periods;
  private final TaskApi tasks;
  private final EvidenceEvents events;
  private final Clock clock;

  public EvidenceSubmissionService(
      EvidenceSubmissionRepository repository,
      EvidenceLifecycleService lifecycle,
      PeriodApi periods,
      TaskApi tasks,
      EvidenceEvents events,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.events = Objects.requireNonNull(events, "events");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Sản phẩm mới của nhiệm vụ. */
  public record DeliverableInput(
      UUID taskId, String name, String deliverableType, String unit, BigDecimal quantityPlanned) {}

  /** Minh chứng mới của sản phẩm. */
  public record EvidenceInput(
      UUID deliverableId, String title, String sourceType, String externalDocRef) {}

  /** Tệp gắn vào nháp. */
  public record FileInput(UUID objectId, Integer objectVersionNo, String reuseJustification) {}

  /** Nội dung nháp. */
  public record DraftInput(
      String source,
      String sourceDocRef,
      List<FileInput> files,
      boolean aiPrefill,
      UUID aiSuggestionId,
      String docNumber,
      LocalDate docDate,
      String docSummary,
      String note,
      List<String> aiFieldsEdited) {}

  /**
   * Việc cần làm với SVC-14 trước khi nộp (CORE-03-05): ghi quyết định của người nộp cho gợi ý
   * {@code aiSuggestionId}; {@code edited} rỗng là giữ nguyên mọi ô.
   */
  public record AiDecisionPlan(UUID versionId, UUID aiSuggestionId, List<String> edited) {}

  /** Minh chứng kèm lịch sử phiên bản. */
  public record EvidenceDetail(
      EvidenceView evidence, TaskSummary task, List<VersionSummary> versions) {}

  /** Tệp trùng: nhiệm vụ đã dùng tệp và ngày nộp. */
  public record DuplicateMatch(String taskCode, Instant submittedAt) {}

  /** Tệp của phiên bản kèm thông tin trùng. */
  public record FileDetail(FileView view, DuplicateMatch duplicate) {}

  /** Phiên bản kèm tệp. */
  public record VersionDetail(VersionView version, List<FileDetail> files) {}

  /** Kết quả nộp. */
  public record SubmitOutcome(VersionDetail version, UUID reviewerId, String taskStatusChanged) {}

  // ---------------------------------------------------------------- sản phẩm

  @Transactional(value = TX, readOnly = true)
  public List<DeliverableView> listDeliverables(
      TenantId tenant, UUID taskId, UUID afterId, int limit) {
    return repository.listDeliverables(tenant, taskId, afterId, limit);
  }

  @Transactional(value = TX, readOnly = true)
  public DeliverableView getDeliverable(TenantId tenant, UUID id) {
    return repository.findDeliverable(tenant, id).orElseThrow(NoSuchElementException::new);
  }

  @Transactional(TX)
  public DeliverableView createDeliverable(
      TenantId tenant, DeliverableInput input, EvidenceActor actor) {
    TaskSummary task =
        tasks
            .find(tenant, input.taskId())
            .orElseThrow(() -> invalid("taskId", "Nhiệm vụ không tồn tại"));
    requireOwner(task, actor);
    if (!DECLARE_STATES.contains(task.status())) {
      throw new BusinessException(
          TASK_NOT_OPEN, "Nhiệm vụ không ở trạng thái cho phép khai báo sản phẩm");
    }
    try {
      periods.assertWritable(tenant, PeriodId.of(task.periodId()));
    } catch (PeriodLockedException e) {
      throw periodLocked(task.periodId());
    }
    UUID id = UUID.randomUUID();
    repository.insertDeliverable(
        tenant,
        new NewDeliverable(
            id,
            task.periodId(),
            task.periodBucket(),
            task.id(),
            input.name().strip(),
            input.deliverableType(),
            input.unit().strip(),
            input.quantityPlanned()),
        actor.personId(),
        clock.instant());
    return repository.findDeliverable(tenant, id).orElseThrow();
  }

  // ---------------------------------------------------------------- minh chứng

  @Transactional(value = TX, readOnly = true)
  public List<EvidenceView> listEvidences(
      TenantId tenant, EvidenceFilter filter, UUID afterId, int limit) {
    return repository.listEvidences(tenant, filter, afterId, limit);
  }

  @Transactional(value = TX, readOnly = true)
  public EvidenceDetail getEvidence(TenantId tenant, UUID id) {
    EvidenceView e = repository.findEvidence(tenant, id).orElseThrow(NoSuchElementException::new);
    return detail(tenant, e);
  }

  /** Nhiệm vụ của minh chứng (để dựng {@code task} của phản hồi). */
  @Transactional(value = TX, readOnly = true)
  public Optional<TaskSummary> taskOf(TenantId tenant, UUID taskId) {
    return tasks.find(tenant, taskId);
  }

  @Transactional(TX)
  public EvidenceDetail createEvidence(TenantId tenant, EvidenceInput input, EvidenceActor actor) {
    DeliverableView d =
        repository
            .findDeliverable(tenant, input.deliverableId())
            .orElseThrow(() -> invalid("deliverableId", "Sản phẩm không tồn tại"));
    TaskSummary task = evidenceTask(tenant, d.taskId(), actor);
    try {
      periods.assertWritable(tenant, PeriodId.of(d.periodId()));
    } catch (PeriodLockedException e) {
      throw periodLocked(d.periodId());
    }
    UUID id = UUID.randomUUID();
    repository.insertEvidence(
        tenant,
        new NewEvidence(
            id,
            d.periodId(),
            d.periodBucket(),
            d.id(),
            task.id(),
            input.title().strip(),
            input.sourceType(),
            blankToNull(input.externalDocRef())),
        actor.personId(),
        clock.instant());
    return detail(tenant, repository.findEvidence(tenant, id).orElseThrow());
  }

  // ---------------------------------------------------------------- phiên bản

  @Transactional(value = TX, readOnly = true)
  public VersionDetail getVersion(TenantId tenant, UUID evidenceId, int versionNo) {
    EvidenceView e =
        repository.findEvidence(tenant, evidenceId).orElseThrow(NoSuchElementException::new);
    VersionView v =
        repository
            .findVersion(tenant, e.periodBucket(), evidenceId, versionNo)
            .orElseThrow(NoSuchElementException::new);
    return versionDetail(tenant, v);
  }

  /** Tạo phiên bản n+1 ở {@code NHAP} ("Lưu nháp" lần đầu). */
  @Transactional(TX)
  public VersionDetail createVersion(
      TenantId tenant, UUID evidenceId, DraftInput input, EvidenceActor actor) {
    if (EXTERNAL_SOURCE.equals(input.source())) {
      throw invalid("source", "Nguồn văn bản trên hệ thống chỉ được tạo khi chọn văn bản");
    }
    EvidenceView e =
        repository.lockEvidence(tenant, evidenceId).orElseThrow(NoSuchElementException::new);
    evidenceTask(tenant, e.taskId(), actor);
    try {
      periods.assertWritable(tenant, PeriodId.of(e.periodId()));
    } catch (PeriodLockedException ex) {
      throw periodLocked(e.periodId());
    }
    return openVersion(tenant, e, input, actor);
  }

  /**
   * Phiên bản n+1 từ văn bản đã ban hành (CORE-03-04, BR-07-13): tệp gốc SVC-13 đã chuyển sang
   * SVC-08 là tệp thứ nhất, nguồn {@code VAN_BAN_HE_THONG}, số ký hiệu, ngày, trích yếu điền từ văn
   * bản; ghi kết quả kiểm chữ ký số vào sổ cái cùng giao dịch. Chữ ký phải đã được kiểm hợp lệ
   * trước khi gọi.
   */
  @Transactional(TX)
  public VersionDetail openExternalDocVersion(
      TenantId tenant,
      UUID evidenceId,
      DraftInput input,
      IssuedDocuments.Transferred original,
      EvidenceActor actor) {
    if (!original.signatureValid()) {
      throw signatureInvalid();
    }
    EvidenceView e =
        repository.lockEvidence(tenant, evidenceId).orElseThrow(NoSuchElementException::new);
    evidenceTask(tenant, e.taskId(), actor);
    try {
      periods.assertWritable(tenant, PeriodId.of(e.periodId()));
    } catch (PeriodLockedException ex) {
      throw periodLocked(e.periodId());
    }
    Instant now = clock.instant();
    repository.upsertStoredObject(
        tenant,
        new StoredObject(
            original.objectId(),
            original.objectVersion(),
            original.sha256(),
            original.sizeBytes(),
            original.mediaType(),
            false,
            "CHO_QUET",
            now),
        actor.personId(),
        now);
    List<FileInput> files = new ArrayList<>();
    files.add(new FileInput(original.objectId(), original.objectVersion(), null));
    if (input.files() != null) {
      input.files().stream()
          .filter(f -> !original.objectId().equals(f.objectId()))
          .forEach(files::add);
    }
    IssuedDocuments.Doc doc = original.doc();
    DraftInput external =
        new DraftInput(
            EXTERNAL_SOURCE,
            doc.ref(),
            files,
            false,
            null,
            doc.docNumber(),
            doc.docDate(),
            doc.summary(),
            input.note(),
            List.of());
    VersionDetail created = openVersion(tenant, e, external, actor);
    events.externalDocVerified(
        new EvidenceEvents.ExternalDocVerified(
            tenant,
            created.version().id(),
            doc.ref(),
            original.objectId(),
            doc.signerName(),
            actor.personId(),
            created.version().rowVersion()));
    return created;
  }

  /** Sửa phiên bản đang {@code NHAP}. */
  @Transactional(TX)
  public VersionDetail updateDraft(
      TenantId tenant,
      UUID evidenceId,
      int versionNo,
      long expectedRowVersion,
      DraftInput input,
      EvidenceActor actor) {
    EvidenceView e =
        repository.findEvidence(tenant, evidenceId).orElseThrow(NoSuchElementException::new);
    VersionView v =
        repository
            .findVersion(tenant, e.periodBucket(), evidenceId, versionNo)
            .orElseThrow(NoSuchElementException::new);
    evidenceTask(tenant, e.taskId(), actor);
    try {
      periods.assertWritable(tenant, PeriodId.of(e.periodId()));
    } catch (PeriodLockedException ex) {
      throw periodLocked(e.periodId());
    }
    return applyDraft(tenant, v, expectedRowVersion, keepOriginal(tenant, v, input), actor);
  }

  /**
   * Nộp phiên bản {@code NHAP}: kiểm cam kết, tệp, mã băm, kết quả quét, giải trình tệp trùng, ô
   * AI; chuyển {@code DA_NOP}, ghi EVT-07 và EVT-21, liên động nhiệm vụ.
   */
  @Transactional(TX)
  public SubmitOutcome submit(
      TenantId tenant,
      UUID evidenceId,
      int versionNo,
      long expectedRowVersion,
      boolean attested,
      boolean aiFieldsConfirmed,
      EvidenceActor actor) {
    EvidenceView e =
        repository.findEvidence(tenant, evidenceId).orElseThrow(NoSuchElementException::new);
    VersionView v =
        repository
            .findVersion(tenant, e.periodBucket(), evidenceId, versionNo)
            .orElseThrow(NoSuchElementException::new);
    TaskSummary task = evidenceTask(tenant, e.taskId(), actor);
    try {
      periods.assertWritable(tenant, PeriodId.of(e.periodId()));
    } catch (PeriodLockedException ex) {
      throw periodLocked(e.periodId());
    }
    if (v.rowVersion() != expectedRowVersion) {
      throw new VersionConflictException("PRODUCT", expectedRowVersion, v.rowVersion());
    }
    if (!EvidenceStatus.NHAP.name().equals(v.status())) {
      throw new BusinessException(
          EvidenceRuleViolation.INVALID_STATE, "Phiên bản minh chứng này đã được nộp trước đó");
    }
    List<FileView> files = repository.filesOf(tenant, v.periodBucket(), v.id());
    try {
      SubmissionRules.check(attested, files.stream().map(EvidenceSubmissionService::file).toList());
    } catch (EvidenceRuleViolation ex) {
      throw new BusinessException(ex.code(), ex.getMessage());
    }
    Instant now = clock.instant();
    repository.markAttested(tenant, v.periodBucket(), v.id(), now);
    EvidenceLifecycleService.Result result =
        lifecycle.submitVersion(tenant, v.id(), expectedRowVersion, actor, aiFieldsConfirmed);

    EvidenceVersion submitted = result.version();
    if (v.aiPrefill() && v.userConfirmedAt() == null && submitted.userConfirmedAt() != null) {
      List<String> edited = repository.aiFieldsEdited(tenant, v.periodBucket(), v.id());
      events.aiFieldsConfirmed(
          new EvidenceEvents.AiFieldsConfirmed(
              tenant,
              v.id(),
              v.aiSuggestionId(),
              AiPrefillRules.kept(edited),
              edited,
              actor.personId(),
              result.rowVersion()));
    }
    events.submitted(
        new EvidenceEvents.Submitted(
            tenant,
            e.id(),
            v.id(),
            e.deliverableId(),
            e.taskId(),
            files.get(0).file().contentSha256(),
            !submitted.aiPrefill() || submitted.userConfirmedAt() != null,
            actor.personId(),
            result.rowVersion()));
    UUID reviewer = designatedReviewer(task);
    if (reviewer != null) {
      repository.markReviewer(tenant, e.periodBucket(), e.id(), reviewer);
      events.reviewRequested(
          tenant,
          reviewer,
          task.code(),
          e.id(),
          v.versionNo(),
          actor.personId(),
          result.rowVersion());
    }
    String taskStatus =
        result
            .taskOutcome()
            .filter(o -> o.outcome() == Outcome.APPLIED)
            .map(o -> o.task().status())
            .orElse(null);
    VersionView after =
        repository.findVersion(tenant, e.periodBucket(), evidenceId, versionNo).orElseThrow();
    return new SubmitOutcome(versionDetail(tenant, after), reviewer, taskStatus);
  }

  /**
   * Gợi ý AI cần ghi quyết định khi nộp với xác nhận: chỉ khi phiên bản {@code NHAP} có ô AI chưa
   * xác nhận, có mã gợi ý và không có tệp C3 (tệp C3 không bao giờ tạo lời gọi tới SVC-14).
   */
  @Transactional(value = TX, readOnly = true)
  public Optional<AiDecisionPlan> aiDecisionPlan(
      TenantId tenant, UUID evidenceId, int versionNo, boolean aiFieldsConfirmed) {
    if (!aiFieldsConfirmed) {
      return Optional.empty();
    }
    return repository
        .findEvidence(tenant, evidenceId)
        .flatMap(e -> repository.findVersion(tenant, e.periodBucket(), evidenceId, versionNo))
        .filter(v -> EvidenceStatus.NHAP.name().equals(v.status()))
        .filter(v -> v.aiPrefill() && v.userConfirmedAt() == null)
        .filter(v -> !v.hasE2ee() && v.aiSuggestionId() != null)
        .map(
            v ->
                new AiDecisionPlan(
                    v.id(),
                    v.aiSuggestionId(),
                    repository.aiFieldsEdited(tenant, v.periodBucket(), v.id())));
  }

  // ---------------------------------------------------------------- nội bộ

  private VersionDetail openVersion(
      TenantId tenant, EvidenceView e, DraftInput input, EvidenceActor actor) {
    Optional<VersionView> latest = repository.latestVersion(tenant, e.periodBucket(), e.id());
    try {
      SubmissionRules.requireCanOpenNext(
          latest.map(v -> EvidenceStatus.valueOf(v.status())).orElse(null));
    } catch (EvidenceRuleViolation ex) {
      throw new BusinessException(ex.code(), ex.getMessage());
    }
    int next = latest.map(VersionView::versionNo).orElse(0) + 1;
    UUID versionId = UUID.randomUUID();
    repository.insertVersion(
        tenant, e, versionId, next, input.source(), actor.personId(), clock.instant());
    VersionView created =
        repository.findVersion(tenant, e.periodBucket(), e.id(), next).orElseThrow();
    return applyDraft(tenant, created, created.rowVersion(), input, actor);
  }

  /**
   * Nháp từ văn bản đã ban hành giữ nguyên tệp gốc ở vị trí thứ nhất, nguồn và tham chiếu văn bản
   * (BR-07-13: không sửa tệp gốc); nháp khác không được tự gán nguồn văn bản trên hệ thống.
   */
  private DraftInput keepOriginal(TenantId tenant, VersionView v, DraftInput input) {
    if (!EXTERNAL_SOURCE.equals(v.source())) {
      if (EXTERNAL_SOURCE.equals(input.source())) {
        throw invalid("source", "Nguồn văn bản trên hệ thống chỉ được tạo khi chọn văn bản");
      }
      return input;
    }
    List<FileView> current = repository.filesOf(tenant, v.periodBucket(), v.id());
    FileInput first =
        input.files() == null || input.files().isEmpty() ? null : input.files().get(0);
    boolean sameFile =
        !current.isEmpty()
            && first != null
            && current.get(0).file().objectId().equals(first.objectId())
            && (first.objectVersionNo() == null
                || first.objectVersionNo() == current.get(0).file().objectVersion());
    if (!sameFile) {
      throw signatureField("files[0].objectId", "Không được thay hoặc bỏ tệp gốc của văn bản");
    }
    if ((input.source() != null && !EXTERNAL_SOURCE.equals(input.source()))
        || (input.sourceDocRef() != null
            && !input.sourceDocRef().isBlank()
            && !input.sourceDocRef().equals(v.sourceDocRef()))) {
      throw signatureField("sourceDocRef", "Không được đổi văn bản nguồn của phiên bản");
    }
    if (input.aiPrefill()) {
      throw invalid("aiPrefill", "Thông tin văn bản đã lấy từ hệ thống, không dùng AI điền");
    }
    return new DraftInput(
        EXTERNAL_SOURCE,
        v.sourceDocRef(),
        input.files(),
        false,
        null,
        input.docNumber(),
        input.docDate(),
        input.docSummary(),
        input.note(),
        List.of());
  }

  /** Chữ ký số gốc không hợp lệ (BR-07-13). */
  static BusinessException signatureInvalid() {
    return signatureField("externalDocRef", "Chữ ký số của văn bản không hợp lệ");
  }

  private static BusinessException signatureField(String field, String message) {
    return new BusinessException(
        VALIDATION, message, List.of(new ProblemFieldError(field, message, EXTERNAL_RULE)));
  }

  private VersionDetail applyDraft(
      TenantId tenant,
      VersionView version,
      long expectedRowVersion,
      DraftInput input,
      EvidenceActor actor) {
    List<FileInput> inputs = input.files() == null ? List.of() : input.files();
    if (inputs.size() > SubmissionRules.MAX_FILES) {
      throw invalid("files", "Mỗi phiên bản có tối đa 20 tệp");
    }
    Set<UUID> seen = new HashSet<>();
    for (int i = 0; i < inputs.size(); i++) {
      FileInput f = inputs.get(i);
      if (f.objectId() == null || !seen.add(f.objectId())) {
        throw invalid("files[" + i + "].objectId", "Tệp bị thiếu hoặc bị chọn trùng");
      }
      if (f.reuseJustification() != null
          && !f.reuseJustification().isBlank()
          && !EvidenceFile.justificationValid(f.reuseJustification())) {
        throw invalid(
            "files[" + i + "].reuseJustification", "Giải trình cần từ 20 đến 1.000 ký tự");
      }
    }
    Map<UUID, StoredObject> objects = repository.storedObjects(tenant, seen);
    List<FileRow> rows = new ArrayList<>();
    for (int i = 0; i < inputs.size(); i++) {
      FileInput f = inputs.get(i);
      StoredObject o = objects.get(f.objectId());
      if (o == null || !o.stored()) {
        throw new BusinessException(
            EvidenceRuleViolation.NO_OBJECT,
            "Tệp chưa được lưu xong; vui lòng thử lại sau ít giây",
            List.of(
                new ProblemFieldError(
                    "files[" + i + "].objectId", "Tệp chưa được lưu xong", null)));
      }
      if (f.objectVersionNo() != null && f.objectVersionNo() != o.objectVersion()) {
        throw new BusinessException(
            EvidenceRuleViolation.HASH_MISMATCH,
            "Tệp minh chứng đã thay đổi so với lúc tải lên; vui lòng tải lại tệp");
      }
      UUID duplicateOf =
          repository
              .firstDuplicate(tenant, o.contentSha256(), version.taskId())
              .map(Duplicate::versionId)
              .orElse(null);
      rows.add(
          new FileRow(
              o.id(),
              i + 1,
              o.objectVersion(),
              o.contentSha256(),
              o.scanStatus(),
              duplicateOf,
              blankToNull(f.reuseJustification())));
    }
    boolean hasE2ee = rows.stream().anyMatch(r -> objects.get(r.objectId()).hasE2ee());
    List<String> aiEdited;
    try {
      aiEdited =
          AiPrefillRules.check(
              input.aiPrefill(), input.aiSuggestionId() != null, input.aiFieldsEdited(), hasE2ee);
    } catch (AiPrefillRules.Violation ex) {
      throw invalid(ex.field(), ex.getMessage());
    }
    String scan = SubmissionRules.aggregateScan(rows.stream().map(FileRow::scanStatus).toList());
    StoredObject first = rows.isEmpty() ? null : objects.get(rows.get(0).objectId());
    FileRow firstDuplicate =
        rows.stream().filter(r -> r.duplicateOfVersionId() != null).findFirst().orElse(null);

    lifecycle.saveDraft(
        tenant,
        version.id(),
        expectedRowVersion,
        new EvidenceVersion.DraftContent(
            first == null ? null : first.id(),
            first == null ? null : first.contentSha256(),
            scan,
            input.aiPrefill(),
            blankToNull(input.note())),
        actor);
    repository.replaceFiles(tenant, version, rows, actor.personId(), clock.instant());
    repository.writeDraftDetails(
        tenant,
        version.periodBucket(),
        version.id(),
        new DraftDetails(
            input.source() == null ? version.source() : input.source(),
            blankToNull(input.sourceDocRef()),
            input.aiPrefill() ? input.aiSuggestionId() : null,
            blankToNull(input.docNumber()),
            input.docDate(),
            blankToNull(input.docSummary()),
            rows.stream()
                .map(r -> objects.get(r.objectId()).sizeBytes())
                .reduce(Long::sum)
                .orElse(null),
            first == null ? null : first.mediaType(),
            hasE2ee,
            firstDuplicate != null,
            firstDuplicate == null ? null : firstDuplicate.duplicateOfVersionId(),
            firstDuplicate == null ? null : firstDuplicate.reuseJustification(),
            aiEdited));
    VersionView saved =
        repository
            .findVersion(tenant, version.periodBucket(), version.evidenceId(), version.versionNo())
            .orElseThrow();
    return versionDetail(tenant, saved);
  }

  private VersionDetail versionDetail(TenantId tenant, VersionView v) {
    List<FileDetail> files = new ArrayList<>();
    for (FileView f : repository.filesOf(tenant, v.periodBucket(), v.id())) {
      DuplicateMatch dup = null;
      if (f.file().duplicateOfVersionId() != null) {
        dup =
            repository
                .versionRef(tenant, f.file().duplicateOfVersionId())
                .map(
                    d ->
                        new DuplicateMatch(
                            tasks.find(tenant, d.taskId()).map(TaskSummary::code).orElse(""),
                            d.submittedAt()))
                .orElse(null);
      }
      files.add(new FileDetail(f, dup));
    }
    return new VersionDetail(v, files);
  }

  private EvidenceDetail detail(TenantId tenant, EvidenceView e) {
    return new EvidenceDetail(
        e,
        tasks.find(tenant, e.taskId()).orElse(null),
        repository.versionSummaries(tenant, e.periodBucket(), e.id()));
  }

  /** Nhiệm vụ của minh chứng: người gọi là chủ trì và nhiệm vụ đang nhận minh chứng. */
  private TaskSummary evidenceTask(TenantId tenant, UUID taskId, EvidenceActor actor) {
    TaskSummary task = tasks.find(tenant, taskId).orElseThrow(NoSuchElementException::new);
    requireOwner(task, actor);
    if (!EVIDENCE_STATES.contains(task.status())) {
      throw new BusinessException(TASK_STATE, "Nhiệm vụ không ở trạng thái nhận minh chứng");
    }
    return task;
  }

  private static void requireOwner(TaskSummary task, EvidenceActor actor) {
    if (!actor.personId().equals(task.leadAssigneeId())) {
      throw new BusinessException(
          NOT_TASK_OWNER, "Chỉ người chủ trì nhiệm vụ được khai báo và nộp minh chứng");
    }
  }

  /** Người nghiệm thu được chỉ định: người nghiệm thu của nhiệm vụ, thiếu thì người giao. */
  static UUID designatedReviewer(TaskSummary task) {
    return task.acceptorId() != null ? task.acceptorId() : task.assignerId();
  }

  static EvidenceFile file(FileView v) {
    FileRow f = v.file();
    StoredObject o = v.object();
    return new EvidenceFile(
        f.objectId(),
        f.ordinal(),
        f.objectVersion(),
        f.contentSha256(),
        o == null || !o.stored() ? null : o.objectVersion(),
        o == null ? null : o.contentSha256(),
        o == null ? f.scanStatus() : o.scanStatus(),
        f.duplicateOfVersionId(),
        f.reuseJustification());
  }

  private static PeriodLockedException periodLocked(UUID periodId) {
    return new PeriodLockedException(PeriodId.of(periodId), null, "PRODUCT");
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        VALIDATION, message, List.of(new ProblemFieldError(field, message, null)));
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
