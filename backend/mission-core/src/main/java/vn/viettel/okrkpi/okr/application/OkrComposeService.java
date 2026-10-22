package vn.viettel.okrkpi.okr.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodLockedException;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodWriteTarget;
import vn.viettel.okrkpi.okr.application.port.OkrAiSuggestions.Suggestion;
import vn.viettel.okrkpi.okr.application.port.OkrAiTraceRepository;
import vn.viettel.okrkpi.okr.application.port.OkrAiTraceRepository.AiTrace;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.LinkRow;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository.KeyResultData;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository.ObjectiveData;
import vn.viettel.okrkpi.okr.application.port.OkrWriteRepository.ObjectiveHead;
import vn.viettel.okrkpi.okr.domain.AiContentDiff;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.etag.ETags;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Phía ghi của {@code MOD-01 · OKR Engine} cho SCR-0202 (CORE-01-03, QT-02 bước 1-3): tạo, lưu
 * nháp, sao chép mục tiêu kỳ trước, thêm KR, tạo và xoá liên kết. Chỉ kiểm ràng buộc trường; bộ
 * kiểm tra BR-02-01..07 đầy đủ thuộc CORE-01-04. Mọi lệnh ghi kiểm kỳ còn ghi được ({@code
 * PeriodApi.assertWritable}) và chạy trong giao dịch {@code okrTransactionManager} của lớp gọi.
 */
public class OkrComposeService {

  static final String TX = OkrReadService.TX;
  static final String MODULE = "OKR";
  static final String NHAP = "NHAP";
  static final int MAX_KEY_RESULTS = 5;
  static final int LABEL_MAX = 200;

  private static final Pattern OBJECTIVE_NO = Pattern.compile("^O([0-9]{1,9})$");

  private final OkrWriteRepository repository;
  private final PeriodApi periods;
  private final OkrAiTraceRepository traces;

  public OkrComposeService(OkrWriteRepository repository, PeriodApi periods) {
    this(repository, periods, null);
  }

  /** {@code traces} rỗng thì không ghi dấu vết nội dung AI (CORE-01-13). */
  public OkrComposeService(
      OkrWriteRepository repository, PeriodApi periods, OkrAiTraceRepository traces) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.traces = traces;
  }

  /** Nội dung sửa được của mục tiêu (SCR-0202 trường 1-6), KR rỗng là "không có KR". */
  public record ObjectiveDraft(
      String title,
      String description,
      Integer tier,
      UUID parentObjectiveId,
      List<UUID> basisIds,
      String overLimitReason,
      UUID aiSuggestionId,
      List<KeyResultData> keyResults) {}

  /** Chủ thể của mục tiêu mới. */
  public record Owner(UUID periodId, String ownerType, UUID ownerUnitId, UUID ownerPersonId) {}

  /** Liên kết cần tạo. */
  public record LinkDraft(
      String fromType,
      UUID fromId,
      String toType,
      UUID toId,
      String linkKind,
      BigDecimal contributionWeight) {}

  // ---------------------------------------------------------------- lệnh

  @Transactional(transactionManager = TX)
  public UUID create(TenantId tenant, UUID actor, Owner owner, ObjectiveDraft draft, int max) {
    return create(tenant, actor, owner, draft, max, Map.of());
  }

  /**
   * Tạo mục tiêu; {@code ai} là các gợi ý đã kiểm bằng {@link OkrAiProvenance} ngoài giao dịch,
   * dùng để ghi dấu vết trường giữ nguyên, đã sửa (CORE-01-13).
   */
  @Transactional(transactionManager = TX)
  public UUID create(
      TenantId tenant,
      UUID actor,
      Owner owner,
      ObjectiveDraft draft,
      int max,
      Map<UUID, Suggestion> ai) {
    Errors errors = new Errors();
    if (owner.periodId() == null) {
      errors.add("periodId", "Chọn kỳ đánh giá");
    }
    if (owner.ownerUnitId() == null) {
      errors.add("ownerUnitId", "Chọn đơn vị sở hữu");
    }
    if ("CA_NHAN".equals(owner.ownerType()) && !actor.equals(owner.ownerPersonId())) {
      errors.add("ownerPersonId", "Chỉ soạn được mục tiêu cá nhân của chính mình");
    }
    errors.throwIfAny();
    PeriodSnapshot period = writablePeriod(tenant, owner.periodId(), owner.ownerUnitId(), true);
    UUID personId = "CA_NHAN".equals(owner.ownerType()) ? owner.ownerPersonId() : null;
    ObjectiveData data = objectiveData(tenant, period, draft, null, errors);
    List<KeyResultData> krs =
        keyResults(period, draft.keyResults(), Set.of(), verifierOwner(personId, actor), errors);
    long existing =
        repository.countOwnerObjectives(
            tenant, period.id().value(), owner.ownerUnitId(), personId, null);
    overLimit(existing, max, data.overLimitReason(), errors);
    errors.throwIfAny();

    int bucket = bucket(period);
    repository.lockObjectiveCodes(tenant, owner.ownerUnitId(), period.id().value());
    int no =
        repository.maxObjectiveNo(tenant, bucket, owner.ownerUnitId(), period.id().value()) + 1;
    UUID id = UUID.randomUUID();
    String code = "O" + no;
    repository.insertObjective(
        tenant,
        id,
        period.id().value(),
        bucket,
        owner.ownerType(),
        owner.ownerUnitId(),
        personId,
        code,
        data,
        actor);
    List<UUID> bases = distinct(draft.basisIds());
    repository.replaceBases(tenant, bucket, period.id().value(), id, bases, actor);
    repository.replaceParentLink(tenant, period.id().value(), id, data.parentObjectiveId(), actor);
    int n = 0;
    Map<UUID, KeyResultData> saved = new LinkedHashMap<>();
    for (KeyResultData k : krs) {
      UUID krId = UUID.randomUUID();
      repository.insertKeyResult(
          tenant, krId, period.id().value(), bucket, id, keyResultCode(code, ++n), k, actor);
      saved.put(krId, k);
    }
    traceAi(tenant, bucket, id, actor, data, saved, ai);
    return id;
  }

  /** PUT toàn bộ mục tiêu kèm KR theo {@code If-Match}; trả {@code row_version} mới. */
  @Transactional(transactionManager = TX)
  public long update(
      TenantId tenant, UUID actor, UUID id, String ifMatch, ObjectiveDraft draft, int max) {
    return update(tenant, actor, id, ifMatch, draft, max, Map.of());
  }

  /**
   * Như {@link #update(TenantId, UUID, UUID, String, ObjectiveDraft, int)}, kèm gợi ý AI đã kiểm.
   */
  @Transactional(transactionManager = TX)
  public long update(
      TenantId tenant,
      UUID actor,
      UUID id,
      String ifMatch,
      ObjectiveDraft draft,
      int max,
      Map<UUID, Suggestion> ai) {
    long expected = ETags.expectedVersion(ifMatch, MODULE);
    ObjectiveHead head = repository.lockObjective(tenant, id).orElseThrow(NotFound::new);
    if (head.rowVersion() != expected) {
      throw VersionConflictException.of(MODULE, expected, head.rowVersion());
    }
    requireDraft(head);
    PeriodSnapshot period = writablePeriod(tenant, head.periodId(), head.ownerUnitId(), false);
    Errors errors = new Errors();
    ObjectiveData data = objectiveData(tenant, period, draft, id, errors);
    List<UUID> existingKrs = repository.keyResultIds(tenant, head.periodBucket(), id);
    List<KeyResultData> krs =
        keyResults(
            period,
            draft.keyResults(),
            new HashSet<>(existingKrs),
            verifierOwner(head.ownerPersonId(), actor),
            errors);
    long count =
        repository.countOwnerObjectives(
            tenant, head.periodId(), head.ownerUnitId(), head.ownerPersonId(), id);
    overLimit(count, max, data.overLimitReason(), errors);
    errors.throwIfAny();

    long version = repository.rewriteObjective(tenant, head, data, actor);
    repository.replaceBases(
        tenant, head.periodBucket(), head.periodId(), id, distinct(draft.basisIds()), actor);
    if (!Objects.equals(head.parentObjectiveId(), data.parentObjectiveId())) {
      repository.replaceParentLink(tenant, head.periodId(), id, data.parentObjectiveId(), actor);
    }
    Set<UUID> kept = new HashSet<>();
    Map<UUID, KeyResultData> saved = new LinkedHashMap<>();
    int n = repository.maxKeyResultNo(tenant, head.periodBucket(), id);
    for (KeyResultData k : krs) {
      if (k.id() != null) {
        kept.add(k.id());
        repository.rewriteKeyResult(tenant, head.periodBucket(), k, actor);
        saved.put(k.id(), k);
      } else {
        UUID krId = UUID.randomUUID();
        repository.insertKeyResult(
            tenant,
            krId,
            head.periodId(),
            head.periodBucket(),
            id,
            keyResultCode(head.code(), ++n),
            k,
            actor);
        saved.put(krId, k);
      }
    }
    List<UUID> removed = existingKrs.stream().filter(k -> !kept.contains(k)).toList();
    repository.dropLinksTouching(tenant, removed);
    repository.dropKeyResults(tenant, head.periodBucket(), removed);
    traceAi(tenant, head.periodBucket(), id, actor, data, saved, ai);
    return version;
  }

  /** Thêm một KR vào mục tiêu nháp; trả id KR. */
  @Transactional(transactionManager = TX)
  public UUID addKeyResult(TenantId tenant, UUID actor, UUID objectiveId, KeyResultData input) {
    return addKeyResult(tenant, actor, objectiveId, input, Map.of());
  }

  /** Thêm một KR kèm gợi ý AI đã kiểm (CORE-01-13). */
  @Transactional(transactionManager = TX)
  public UUID addKeyResult(
      TenantId tenant,
      UUID actor,
      UUID objectiveId,
      KeyResultData input,
      Map<UUID, Suggestion> ai) {
    Errors errors = new Errors();
    if (objectiveId == null) {
      errors.add("objectiveId", "Chọn mục tiêu");
      errors.throwIfAny();
    }
    ObjectiveHead head =
        repository
            .lockObjective(tenant, objectiveId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        MODULE + "-422-VALIDATION",
                        "Mục tiêu không tồn tại",
                        List.of(
                            new ProblemFieldError("objectiveId", "Mục tiêu không tồn tại", null))));
    requireDraft(head);
    PeriodSnapshot period = writablePeriod(tenant, head.periodId(), head.ownerUnitId(), false);
    List<KeyResultData> current = repository.keyResultsOf(tenant, head.periodBucket(), objectiveId);
    if (current.size() >= MAX_KEY_RESULTS) {
      errors.add("keyResults", "Mỗi mục tiêu có tối đa 5 kết quả then chốt");
    }
    KeyResultData k =
        keyResult(period, input, "", Set.of(), verifierOwner(head.ownerPersonId(), actor), errors);
    BigDecimal sum = k.weight() == null ? BigDecimal.ZERO : k.weight();
    for (KeyResultData c : current) {
      sum = sum.add(c.weight());
    }
    if (sum.compareTo(BigDecimal.ONE) > 0) {
      errors.add("weight", "Tổng trọng số các kết quả then chốt không được vượt 100%");
    }
    errors.throwIfAny();
    UUID id = UUID.randomUUID();
    int n = repository.maxKeyResultNo(tenant, head.periodBucket(), objectiveId) + 1;
    repository.insertKeyResult(
        tenant,
        id,
        head.periodId(),
        head.periodBucket(),
        objectiveId,
        keyResultCode(head.code(), n),
        k,
        actor);
    repository.touchObjective(tenant, head, actor);
    traceAi(tenant, head.periodBucket(), objectiveId, actor, null, Map.of(id, k), ai);
    return id;
  }

  /**
   * Ghi dấu vết nội dung AI và đặt lại {@code ai_assisted} của mục tiêu (BR-17-08): mục tiêu mang
   * cờ khi chính nó hoặc bất kỳ KR nào có nguồn AI. Gợi ý không có trong {@code ai} (chưa kiểm) thì
   * không ghi dấu vết.
   */
  private void traceAi(
      TenantId tenant,
      int bucket,
      UUID objectiveId,
      UUID actor,
      ObjectiveData objective,
      Map<UUID, KeyResultData> saved,
      Map<UUID, Suggestion> ai) {
    if (traces == null) {
      return;
    }
    List<AiTrace> rows = new ArrayList<>();
    if (objective != null && objective.aiSuggestionId() != null) {
      Suggestion s = ai.get(objective.aiSuggestionId());
      if (s != null) {
        rows.add(
            trace(
                objectiveId,
                null,
                s,
                AiContentDiff.objective(s.output(), objective.title()),
                actor));
      }
    }
    saved.forEach(
        (krId, k) -> {
          Suggestion s = k.aiSuggestionId() == null ? null : ai.get(k.aiSuggestionId());
          if (s != null && !"HUMAN".equals(k.contentSource())) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("title", k.title());
            fields.put("unit", k.unit());
            fields.put("baselineValue", k.baselineValue());
            fields.put("targetValue", k.targetValue());
            rows.add(
                trace(objectiveId, krId, s, AiContentDiff.keyResult(s.output(), fields), actor));
          }
        });
    if (!rows.isEmpty()) {
      traces.record(tenant, bucket, rows);
    }
    traces.refreshObjectiveAiAssisted(tenant, bucket, objectiveId);
  }

  private static AiTrace trace(
      UUID objectiveId, UUID keyResultId, Suggestion s, AiContentDiff.Result diff, UUID actor) {
    return new AiTrace(
        objectiveId,
        keyResultId,
        s.id(),
        diff.contentSource(),
        diff.kept(),
        diff.edited(),
        null,
        actor,
        0);
  }

  /**
   * Sao chép mục tiêu của đơn vị ở kỳ nguồn sang kỳ đích, trạng thái {@code NHAP}: giữ căn cứ và
   * KR, bỏ giá trị hiện tại, check-in, mục tiêu cấp trên và liên kết khác (okr.yaml); hạn KR đặt
   * ngày cuối kỳ đích (suy luận). Trả id các mục tiêu mới.
   */
  @Transactional(transactionManager = TX)
  public List<UUID> copyFromPeriod(
      TenantId tenant,
      UUID actor,
      UUID fromPeriodId,
      UUID toPeriodId,
      UUID unitId,
      List<UUID> only) {
    Errors errors = new Errors();
    if (fromPeriodId == null) {
      errors.add("fromPeriodId", "Chọn kỳ nguồn");
    }
    if (toPeriodId == null) {
      errors.add("toPeriodId", "Chọn kỳ đích");
    }
    if (unitId == null) {
      errors.add("ownerUnitId", "Chọn đơn vị");
    }
    if (fromPeriodId != null && fromPeriodId.equals(toPeriodId)) {
      errors.add("toPeriodId", "Kỳ đích phải khác kỳ nguồn");
    }
    errors.throwIfAny();
    PeriodSnapshot from =
        periods
            .findById(tenant, PeriodId.of(fromPeriodId))
            .orElseThrow(() -> invalid("fromPeriodId", "Kỳ nguồn không tồn tại"));
    PeriodSnapshot to = writablePeriod(tenant, toPeriodId, unitId, true);
    int toBucket = bucket(to);
    repository.lockObjectiveCodes(tenant, unitId, toPeriodId);
    int no = repository.maxObjectiveNo(tenant, toBucket, unitId, toPeriodId);
    List<UUID> created = new ArrayList<>();
    for (UUID sourceId : repository.objectivesOf(tenant, from.id().value(), unitId, only)) {
      ObjectiveHead src = repository.findObjective(tenant, sourceId).orElseThrow();
      OkrWriteRepository.ObjectiveText s =
          repository.objectiveText(tenant, src.periodBucket(), sourceId);
      UUID id = UUID.randomUUID();
      String code = "O" + (++no);
      repository.insertObjective(
          tenant,
          id,
          toPeriodId,
          toBucket,
          src.ownerType(),
          src.ownerUnitId(),
          src.ownerPersonId(),
          code,
          new ObjectiveData(s.title(), s.description(), s.tier(), null, null, null),
          actor);
      repository.replaceBases(
          tenant,
          toBucket,
          toPeriodId,
          id,
          repository.basisIdsOf(tenant, src.periodBucket(), sourceId),
          actor);
      int n = 0;
      for (KeyResultData k : repository.keyResultsOf(tenant, src.periodBucket(), sourceId)) {
        repository.insertKeyResult(
            tenant,
            UUID.randomUUID(),
            toPeriodId,
            toBucket,
            id,
            keyResultCode(code, ++n),
            new KeyResultData(
                null,
                k.title(),
                k.metricType(),
                k.unit(),
                k.baselineValue(),
                k.targetValue(),
                k.direction(),
                k.weight(),
                to.endDate(),
                k.verifierPersonId(),
                k.dataSource(),
                k.formulaText(),
                k.checkinFrequency(),
                "HUMAN",
                null,
                k.numeratorLabel(),
                k.denominatorLabel()),
            actor);
      }
      created.add(id);
    }
    return created;
  }

  /** Tạo liên kết khi mục tiêu đầu nguồn còn nháp; trả dòng liên kết đã ghi. */
  @Transactional(transactionManager = TX)
  public LinkRow createLink(TenantId tenant, UUID actor, LinkDraft l) {
    Errors errors = new Errors();
    if (l.fromId() == null) {
      errors.add("fromId", "Thiếu đầu nguồn của liên kết");
    }
    if (l.toId() == null) {
      errors.add("toId", "Thiếu đầu đích của liên kết");
    }
    if (l.fromId() != null && l.fromId().equals(l.toId())) {
      errors.add("toId", "Không liên kết một mục với chính nó");
    }
    errors.throwIfAny();
    UUID periodId;
    UUID unitId;
    if ("BASIS".equals(l.fromType())) {
      if (repository.existingBases(tenant, List.of(l.fromId())).isEmpty()) {
        throw invalid("fromId", "Căn cứ không tồn tại");
      }
      ObjectiveHead target = objectiveOf(tenant, l.toType(), l.toId(), "toId");
      requireDraft(target);
      periodId = target.periodId();
      unitId = target.ownerUnitId();
    } else {
      ObjectiveHead source = objectiveOf(tenant, l.fromType(), l.fromId(), "fromId");
      requireDraft(source);
      if ("OBJECTIVE".equals(l.toType()) || "KEY_RESULT".equals(l.toType())) {
        objectiveOf(tenant, l.toType(), l.toId(), "toId");
      }
      periodId = source.periodId();
      unitId = source.ownerUnitId();
    }
    // Gọi trực tiếp assertWritable để quy tắc ArchUnit writesCheckPeriodLock nhận ra (CORE-08-03).
    PeriodSnapshot period = periodOf(tenant, periodId);
    try {
      periods.assertWritable(tenant, period.id(), writeTarget(unitId));
    } catch (PeriodLockedException e) {
      throw new PeriodLockedException(period.id(), period.name(), MODULE);
    }
    if (repository.linkExists(tenant, l.fromType(), l.fromId(), l.toType(), l.toId())) {
      throw new BusinessException(MODULE + "-409-LINK-EXISTS", "Liên kết này đã có");
    }
    UUID id = UUID.randomUUID();
    repository.insertLink(
        tenant,
        id,
        periodId,
        l.fromType(),
        l.fromId(),
        l.toType(),
        l.toId(),
        l.linkKind(),
        l.contributionWeight(),
        actor);
    return repository.findLink(tenant, id).orElseThrow();
  }

  /** Xoá liên kết theo {@code If-Match} khi mục tiêu đầu nguồn còn nháp (BR-02-10). */
  @Transactional(transactionManager = TX)
  public void deleteLink(TenantId tenant, UUID id, String ifMatch) {
    LinkRow link = repository.findLink(tenant, id).orElseThrow(NotFound::new);
    ETags.requireMatch(ifMatch, link.rowVersion(), MODULE);
    UUID unitId = null;
    if (!"BASIS".equals(link.fromType())) {
      ObjectiveHead source = objectiveOf(tenant, link.fromType(), link.fromId(), "fromId");
      requireDraft(source);
      unitId = source.ownerUnitId();
    }
    // Gọi trực tiếp assertWritable để quy tắc ArchUnit writesCheckPeriodLock nhận ra (CORE-08-03).
    PeriodSnapshot period = periodOf(tenant, link.periodId());
    try {
      periods.assertWritable(tenant, period.id(), writeTarget(unitId));
    } catch (PeriodLockedException e) {
      throw new PeriodLockedException(period.id(), period.name(), MODULE);
    }
    repository.dropLink(tenant, id);
  }

  /** Mục tiêu đọc lại để biết đơn vị sở hữu (kiểm phạm vi ở lớp gọi). */
  @Transactional(transactionManager = TX, readOnly = true)
  public java.util.Optional<ObjectiveHead> head(TenantId tenant, UUID objectiveId) {
    return repository.findObjective(tenant, objectiveId);
  }

  // ---------------------------------------------------------------- kiểm tra

  private ObjectiveHead objectiveOf(TenantId tenant, String type, UUID id, String field) {
    UUID objectiveId =
        switch (type) {
          case "OBJECTIVE" -> id;
          case "KEY_RESULT" ->
              repository
                  .objectiveOfKeyResult(tenant, id)
                  .orElseThrow(() -> invalid(field, "Kết quả then chốt không tồn tại"));
          default -> throw invalid(field, "Loại đối tượng không hợp lệ");
        };
    return repository
        .findObjective(tenant, objectiveId)
        .orElseThrow(() -> invalid(field, "Mục tiêu không tồn tại"));
  }

  private static void requireDraft(ObjectiveHead head) {
    if (!NHAP.equals(head.status())) {
      throw new BusinessException(
          MODULE + "-409-INVALID-STATE",
          "Mục tiêu đã trình hoặc đã duyệt nên không sửa trực tiếp được");
    }
  }

  private PeriodSnapshot writablePeriod(
      TenantId tenant, UUID periodId, UUID unitId, boolean asField) {
    PeriodSnapshot period =
        periods
            .findById(tenant, PeriodId.of(periodId))
            .orElseThrow(
                () ->
                    asField
                        ? invalid("periodId", "Kỳ đánh giá không tồn tại")
                        : new IllegalStateException("Mục tiêu trỏ tới kỳ không tồn tại"));
    try {
      periods.assertWritable(tenant, period.id(), writeTarget(unitId));
    } catch (PeriodLockedException e) {
      throw new PeriodLockedException(period.id(), period.name(), MODULE);
    }
    return period;
  }

  private PeriodSnapshot periodOf(TenantId tenant, UUID periodId) {
    return periods
        .findById(tenant, PeriodId.of(periodId))
        .orElseThrow(() -> new IllegalStateException("Mục tiêu trỏ tới kỳ không tồn tại"));
  }

  private static PeriodWriteTarget writeTarget(UUID unitId) {
    return unitId == null ? PeriodWriteTarget.NONE : PeriodWriteTarget.unit(unitId);
  }

  private ObjectiveData objectiveData(
      TenantId tenant, PeriodSnapshot period, ObjectiveDraft d, UUID selfId, Errors errors) {
    String title = trim(d.title());
    if (title == null) {
      errors.add("title", "Nhập tên mục tiêu");
    } else if (title.length() > 200) {
      errors.add("title", "Tên mục tiêu tối đa 200 ký tự");
    }
    String description = trim(d.description());
    if (description != null && description.length() > 4000) {
      errors.add("description", "Mô tả tối đa 4.000 ký tự");
    }
    int tier = d.tier() == null ? 1 : d.tier();
    if (tier < 1 || tier > 3) {
      errors.add("tier", "Cấp mục tiêu từ 1 đến 3");
    }
    String reason = trim(d.overLimitReason());
    if (reason != null && reason.length() > 500) {
      errors.add("overLimitReason", "Lý do tối đa 500 ký tự");
    }
    UUID parent = d.parentObjectiveId();
    if (parent != null) {
      if (parent.equals(selfId)) {
        errors.add("parentObjectiveId", "Mục tiêu cấp trên không được là chính mục tiêu này");
      } else {
        repository
            .findObjective(tenant, parent)
            .filter(p -> p.periodId().equals(period.id().value()))
            .ifPresentOrElse(
                p -> {}, () -> errors.add("parentObjectiveId", "Mục tiêu cấp trên phải cùng kỳ"));
      }
    }
    List<UUID> bases = distinct(d.basisIds());
    if (!bases.isEmpty() && repository.existingBases(tenant, bases).size() != bases.size()) {
      errors.add("basisIds", "Có căn cứ không tồn tại trong thư viện");
    }
    return new ObjectiveData(title, description, tier, parent, reason, d.aiSuggestionId());
  }

  private static void overLimit(long existing, int max, String reason, Errors errors) {
    if (existing >= max && reason == null) {
      errors.code = MODULE + "-422-OVER-LIMIT-REASON-REQUIRED";
      errors.add(
          "overLimitReason",
          "Đơn vị đã có từ " + max + " mục tiêu trong kỳ, cần nêu lý do thêm mục tiêu");
    }
  }

  private List<KeyResultData> keyResults(
      PeriodSnapshot period,
      List<KeyResultData> input,
      Set<UUID> existing,
      UUID owner,
      Errors errors) {
    List<KeyResultData> krs = input == null ? List.of() : input;
    if (krs.size() > MAX_KEY_RESULTS) {
      errors.add("keyResults", "Mỗi mục tiêu có tối đa 5 kết quả then chốt");
    }
    List<KeyResultData> result = new ArrayList<>();
    BigDecimal sum = BigDecimal.ZERO;
    Set<UUID> seen = new HashSet<>();
    for (int i = 0; i < krs.size(); i++) {
      KeyResultData k =
          keyResult(period, krs.get(i), "keyResults[" + i + "].", existing, owner, errors);
      if (k.id() != null && !seen.add(k.id())) {
        errors.add("keyResults[" + i + "].id", "Kết quả then chốt bị lặp");
      }
      if (k.weight() != null) {
        sum = sum.add(k.weight());
      }
      result.add(k);
    }
    if (sum.compareTo(BigDecimal.ONE) > 0) {
      errors.add("keyResults", "Tổng trọng số các kết quả then chốt không được vượt 100%");
    }
    return result;
  }

  private static KeyResultData keyResult(
      PeriodSnapshot period,
      KeyResultData k,
      String prefix,
      Set<UUID> existing,
      UUID owner,
      Errors errors) {
    if (k.id() != null && !existing.contains(k.id())) {
      errors.add(prefix + "id", "Kết quả then chốt không thuộc mục tiêu này");
    }
    String title = trim(k.title());
    if (title == null) {
      errors.add(prefix + "title", "Nhập nội dung kết quả then chốt");
    } else if (title.length() > 300) {
      errors.add(prefix + "title", "Nội dung kết quả then chốt tối đa 300 ký tự");
    }
    String unit = trim(k.unit());
    if (unit == null) {
      errors.add(prefix + "unit", "Nhập đơn vị đo");
    } else if (unit.length() > 32) {
      errors.add(prefix + "unit", "Đơn vị đo tối đa 32 ký tự");
    }
    BigDecimal weight = k.weight();
    if (weight == null) {
      errors.add(prefix + "weight", "Nhập trọng số");
    } else if (weight.signum() < 0 || weight.compareTo(BigDecimal.ONE) > 0) {
      errors.add(prefix + "weight", "Trọng số từ 0 đến 100%");
    }
    BigDecimal baseline = k.baselineValue() == null ? BigDecimal.ZERO : k.baselineValue();
    if (k.targetValue() != null && k.targetValue().compareTo(baseline) == 0) {
      errors.add(prefix + "targetValue", "Chỉ tiêu phải khác giá trị ban đầu");
    }
    LocalDate due = k.dueDate() == null ? period.endDate() : k.dueDate();
    if (due.isBefore(period.startDate()) || due.isAfter(period.endDate())) {
      errors.add(prefix + "dueDate", "Hạn phải nằm trong kỳ đánh giá");
    }
    if (k.verifierPersonId() == null) {
      errors.add(prefix + "verifierPersonId", "Chọn người xác nhận");
    } else if (k.verifierPersonId().equals(owner)) {
      errors.add(prefix + "verifierPersonId", "Người xác nhận phải khác người chịu trách nhiệm");
    }
    String content = k.contentSource() == null ? "HUMAN" : k.contentSource();
    if (!"HUMAN".equals(content) && k.aiSuggestionId() == null) {
      errors.add(prefix + "contentSource", "Nội dung do trợ lý gợi ý phải kèm mã gợi ý");
    }
    if (k.dataSource() != null && k.dataSource().length() > 200) {
      errors.add(prefix + "dataSource", "Nguồn số liệu tối đa 200 ký tự");
    }
    if (k.formulaText() != null && k.formulaText().length() > 1000) {
      errors.add(prefix + "formulaText", "Cách tính tối đa 1.000 ký tự");
    }
    String metric = k.metricType() == null ? "SO_LUONG" : k.metricType();
    String numerator = trim(k.numeratorLabel());
    String denominator = trim(k.denominatorLabel());
    // BS-03 DC-04: nhãn tử số, mẫu số chỉ dành cho KR tỷ lệ và đi theo cặp (suy luận).
    if (numerator != null || denominator != null) {
      if (!"TY_LE".equals(metric)) {
        errors.add(
            prefix + "numeratorLabel", "Nhãn tử số, mẫu số chỉ dùng cho kết quả đo bằng tỷ lệ");
      } else if (numerator == null || denominator == null) {
        errors.add(prefix + "numeratorLabel", "Nhập đủ nhãn tử số và nhãn mẫu số");
      }
      if ((numerator != null && numerator.length() > LABEL_MAX)
          || (denominator != null && denominator.length() > LABEL_MAX)) {
        errors.add(prefix + "numeratorLabel", "Nhãn tử số, mẫu số tối đa 200 ký tự");
      }
    }
    return new KeyResultData(
        k.id(),
        title,
        metric,
        unit,
        baseline,
        k.targetValue(),
        k.direction() == null ? "TANG" : k.direction(),
        weight,
        due,
        k.verifierPersonId(),
        trim(k.dataSource()),
        trim(k.formulaText()),
        k.checkinFrequency() == null ? "TUAN" : k.checkinFrequency(),
        content,
        "HUMAN".equals(content) ? null : k.aiSuggestionId(),
        numerator,
        denominator);
  }

  // ---------------------------------------------------------------- tiện ích

  /**
   * Người chịu trách nhiệm KR: chủ mục tiêu cá nhân, hoặc người soạn với mục tiêu tập thể (suy
   * luận, TL-02a SCR-0202 trường 14 chỉ nói người xác nhận khác chủ sở hữu).
   */
  private static UUID verifierOwner(UUID ownerPersonId, UUID actor) {
    return ownerPersonId != null ? ownerPersonId : actor;
  }

  /** Mã KR {@code KR {o}.{n}}: {@code o} là số của mã mục tiêu {@code O{o}}, ngược lại cả mã. */
  static String keyResultCode(String objectiveCode, int n) {
    Matcher m = OBJECTIVE_NO.matcher(objectiveCode);
    return "KR " + (m.matches() ? m.group(1) : objectiveCode) + "." + n;
  }

  /**
   * {@code period_bucket} của kỳ theo {@code Period.bucketOf}: kỳ năm, năm học (dài hơn bốn tháng)
   * lấy quý 0, kỳ quý, tháng lấy quý của ngày bắt đầu (suy luận từ độ dài vì {@code PeriodSnapshot}
   * không mang loại kỳ).
   */
  public static int bucket(PeriodSnapshot p) {
    LocalDate start = p.startDate();
    boolean yearly = ChronoUnit.DAYS.between(start, p.endDate()) > 120;
    return start.getYear() * 10 + (yearly ? 0 : (start.getMonthValue() - 1) / 3 + 1);
  }

  private static List<UUID> distinct(List<UUID> ids) {
    return ids == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(ids));
  }

  private static String trim(String s) {
    if (s == null) {
      return null;
    }
    String t = s.strip();
    return t.isEmpty() ? null : t;
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        MODULE + "-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }

  /** Không có mục tiêu, liên kết theo id (404). */
  public static final class NotFound extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NotFound() {
      super("Không tìm thấy", null, false, false);
    }
  }

  /** Gom lỗi trường để trả một Problem 422 duy nhất. */
  private static final class Errors {
    private final List<ProblemFieldError> list = new ArrayList<>();
    private String code = MODULE + "-422-VALIDATION";

    void add(String field, String message) {
      list.add(new ProblemFieldError(field, message, null));
    }

    void throwIfAny() {
      if (!list.isEmpty()) {
        throw new BusinessException(code, list.getFirst().message(), List.copyOf(list));
      }
    }
  }
}
