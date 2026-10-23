package vn.viettel.okrkpi.appraisal.application.decision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.BatchRating;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.BatchRow;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.NewBatch;
import vn.viettel.okrkpi.appraisal.application.decision.port.BatchDecisionRepository.ProfileFacts;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.application.review.ReviewService;
import vn.viettel.okrkpi.appraisal.application.review.port.EvidenceAcceptors;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.CurrentRating;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.Jcs;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Quyết định xếp loại hàng loạt (CORE-04-07, QT-09, SCR-0904): kiểm cả lô trước khi ghi (phiên bản
 * từng mục, trạng thái {@code CHO_QUYET_DINH}, phạm vi, tách vai trò BR-09-14, lý do BR-09-12,
 * ngưỡng HTXSNV BR-09-17, người đứng đầu BR-09-13), rồi ghi {@code batch_decision} cùng các dòng
 * mức {@code QUYET_DINH} trong một giao dịch. Hồ sơ giữ {@code CHO_QUYET_DINH} cho tới khi nhận
 * EVT-18 (CORE-04-08). Gửi lại lô {@code NHAP} hoặc {@code CHO_KY} cùng số quyết định, cùng danh
 * sách của chính người quyết định thì tiếp tục lô cũ, không tạo lô hay chữ ký mới.
 */
public class BatchDecisionService {

  static final String TX = "appraisalTransactionManager";
  static final String REASON = "appraisal.rating_proposal.reason_enc";
  static final String LEGAL_REF = "QĐ Điều 16";
  static final String ROLE = "decider";
  public static final int MAX_ITEMS = 1000;
  static final int MIN_REASON = 20;
  static final int MAX_REASON = 2000;
  static final String THRESHOLD_PARAM = "phan_bo.toi_da_htxsnv";
  static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("0.20");

  public static final String NHAP = "NHAP";
  public static final String CHO_KY = "CHO_KY";
  public static final String DA_KY = "DA_KY";
  public static final String LOI_MOT_PHAN = "LOI_MOT_PHAN";
  public static final String HUY = "HUY";

  static final String MSG_SOD =
      "Đồng chí đã tham gia hồ sơ này ở bước khác nên không quyết định được.";
  static final String MSG_REASON = "Mức quyết định khác mức đề xuất, đồng chí cần nêu lý do.";
  static final String MSG_SUGGESTED =
      "Mức đồng chí chọn khác mức hệ thống gợi ý, đồng chí cần nêu lý do.";
  static final String MSG_COMPLAINT =
      "Chưa thể quyết định xếp loại năm vì khiếu nại về kết quả một quý trong năm chưa có kết"
          + " luận.";
  static final String MSG_HEAD =
      "Hồ sơ của người đứng đầu chỉ được quyết định sau khi các đơn vị trực thuộc đã có mức"
          + " (QĐ khoản 14.3.3).";

  private final AppraisalProfileRepository profiles;
  private final BatchDecisionRepository batches;
  private final AppraisalProfileService steps;
  private final TextSealer sealer;
  private final EvidenceAcceptors acceptors;
  private final PolicyQueryApi policies;
  private final Clock clock;

  public BatchDecisionService(
      AppraisalProfileRepository profiles,
      BatchDecisionRepository batches,
      AppraisalProfileService steps,
      TextSealer sealer,
      EvidenceAcceptors acceptors,
      PolicyQueryApi policies,
      Clock clock) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.batches = Objects.requireNonNull(batches, "batches");
    this.steps = Objects.requireNonNull(steps, "steps");
    this.sealer = Objects.requireNonNull(sealer, "sealer");
    this.acceptors = acceptors;
    this.policies = policies;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Một hồ sơ của lô: mức quyết định, lý do và phiên bản người dùng đã thấy. */
  public record Item(UUID profileId, RatingLevel rating, String reason, Long rowVersion) {}

  /** Lệnh quyết định lô (thân {@code AppraisalBatchDecisionInput}). */
  public record Command(
      UUID periodId,
      String flow,
      String decisionNo,
      LocalDate decisionDate,
      boolean calibrationAcknowledged,
      List<Item> items) {

    public Command {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  /** Lô đã ghi; {@code signRequestId} có sẵn khi tiếp tục lô đã mở yêu cầu ký. */
  public record Prepared(UUID batchId, int periodBucket, UUID signRequestId) {}

  /** Lô kèm số hồ sơ đã ký. */
  public record View(BatchRow batch, int signedCount) {}

  /** Tài liệu SVC-10 cần ký: mỗi hồ sơ một tài liệu, người ký là người quyết định. */
  public record SignSource(boolean ready, UUID signer, List<SignDoc> documents) {}

  /** Một tài liệu: mã hồ sơ và băm JCS của quyết định cho hồ sơ đó. */
  public record SignDoc(UUID profileId, byte[] contentHash) {}

  /**
   * Kiểm và ghi lô {@code NHAP}. {@code scope} là phạm vi {@code SCR-0904.ky-lo} của người gọi đã
   * dịch sang SQL.
   */
  @Transactional(TX)
  public Prepared prepare(TenantId tenant, UUID actor, Command command, Scope scope) {
    validate(command);
    List<Item> items = command.items();
    List<UUID> ids = items.stream().map(Item::profileId).sorted().toList();
    Map<UUID, ProfileRow> rows = new LinkedHashMap<>();
    List<ProblemFieldError> missing = new ArrayList<>();
    for (UUID id : ids) {
      profiles.lock(tenant, id).ifPresent(r -> rows.put(id, r));
    }
    for (int i = 0; i < items.size(); i++) {
      ProfileRow r = rows.get(items.get(i).profileId());
      if (r == null
          || !r.periodId().equals(command.periodId())
          || !r.flow().equals(command.flow())) {
        missing.add(
            field(i, "profileId", "Hồ sơ không thuộc kỳ và luồng đánh giá của lô quyết định."));
      }
    }
    if (!missing.isEmpty()) {
      throw new BusinessException(
          "COMMON-422-VALIDATION", "Danh sách hồ sơ của lô không hợp lệ.", missing);
    }
    int bucket = rows.values().iterator().next().periodBucket();
    byte[] hash = profilesHash(items);
    Optional<BatchRow> same = batches.findByNumber(tenant, bucket / 10, command.decisionNo());
    if (same.isPresent()) {
      BatchRow b = same.get();
      if (actor.equals(b.deciderId())
          && (NHAP.equals(b.status()) || CHO_KY.equals(b.status()))
          && java.util.Arrays.equals(hash, b.profilesHash())) {
        return new Prepared(b.id(), b.periodBucket(), b.signRequestId());
      }
      throw duplicate();
    }
    checkVersions(items, rows);
    checkQuarterComplaints(tenant, bucket, items);
    Map<UUID, ProfileFacts> facts = batches.facts(tenant, bucket, ids);
    checkPending(items, facts);
    Set<UUID> outside = batches.outOfScope(tenant, bucket, ids, scope);
    if (!outside.isEmpty()) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Đồng chí không có quyền quyết định một số hồ sơ đã chọn.");
    }
    checkSeparation(tenant, actor, items, rows);
    checkReasons(items, rows);
    checkCalibration(tenant, command, rows);
    checkHeads(tenant, bucket, command, items, facts);

    UUID batchId = Ids.newUuidV7();
    Instant now = clock.instant();
    try {
      batches.insert(
          tenant,
          new NewBatch(
              batchId,
              command.periodId(),
              bucket,
              command.flow(),
              command.decisionNo(),
              command.decisionDate(),
              actor,
              items.size(),
              hash,
              now));
    } catch (DuplicateKeyException e) {
      throw duplicate();
    }
    for (Item item : items) {
      ProfileRow row = rows.get(item.profileId());
      RatingLevel previous = previous(row);
      String reason = blank(item.reason()) ? null : item.reason();
      UUID proposalId = UUID.randomUUID();
      CurrentRating rating =
          steps.recordRating(
              tenant,
              row.id(),
              null,
              LevelKind.QUYET_DINH,
              item.rating(),
              actor,
              ROLE,
              reason == null ? null : sealer.seal(tenant, REASON, proposalId, reason),
              null,
              proposalId,
              batchId);
      if (rating.explanationRequired()) {
        steps.recordExplanation(
            tenant,
            row.id(),
            LevelKind.QUYET_DINH,
            ReviewService.explanationType(item.rating(), previous),
            null,
            LEGAL_REF,
            null,
            actor);
      }
    }
    batches.touchProfiles(tenant, bucket, ids, actor, now);
    return new Prepared(batchId, bucket, null);
  }

  /** Gắn yêu cầu ký SVC-10 vừa mở, lô chuyển {@code CHO_KY}. */
  @Transactional(TX)
  public void attachSignRequest(
      TenantId tenant, Prepared prepared, UUID signRequestId, UUID actor) {
    batches.attachSignRequest(
        tenant, prepared.periodBucket(), prepared.batchId(), signRequestId, actor, clock.instant());
  }

  /** Lô của chính người quyết định. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<View> view(TenantId tenant, UUID id, UUID actor) {
    return batches
        .find(tenant, id, false)
        .filter(b -> actor == null || actor.equals(b.deciderId()))
        .map(b -> new View(b, batches.signedCount(tenant, b.periodBucket(), b.id())));
  }

  /** Trang lô của người quyết định, mới nhất trước. */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<View> page(
      TenantId tenant,
      UUID actor,
      UUID periodId,
      String flow,
      Set<String> statuses,
      UUID after,
      int limit) {
    return batches.page(tenant, actor, periodId, flow, statuses, after, limit).stream()
        .map(b -> new View(b, batches.signedCount(tenant, b.periodBucket(), b.id())))
        .toList();
  }

  /**
   * Huỷ phần chưa ký: lô chưa ký hồ sơ nào thành {@code HUY}, còn lại {@code LOI_MOT_PHAN}; các
   * dòng mức chưa ký thôi hiện hành nên hồ sơ trở lại danh sách chờ quyết định. Lô đã {@code DA_KY}
   * trả 409 {@code APPRAISAL-409-BATCH-COMPLETED}; lô đã huỷ trả nguyên trạng.
   */
  @Transactional(TX)
  public View cancel(TenantId tenant, UUID id, long expectedVersion, UUID actor) {
    BatchRow b =
        batches
            .find(tenant, id, true)
            .filter(x -> actor.equals(x.deciderId()))
            .orElseThrow(NoSuchElementException::new);
    if (expectedVersion != b.rowVersion()) {
      throw new VersionConflictException("APPRAISAL", expectedVersion, b.rowVersion());
    }
    if (DA_KY.equals(b.status())) {
      throw new BusinessException(
          "APPRAISAL-409-BATCH-COMPLETED", "Lô quyết định đã ký xong, không huỷ được.");
    }
    if (HUY.equals(b.status()) || LOI_MOT_PHAN.equals(b.status())) {
      return view(tenant, id, actor).orElseThrow();
    }
    Instant now = clock.instant();
    int signed = batches.signedCount(tenant, b.periodBucket(), b.id());
    List<UUID> released = batches.supersedeUnsigned(tenant, b.periodBucket(), b.id(), actor, now);
    batches.touchProfiles(tenant, b.periodBucket(), released, actor, now);
    batches.setStatus(
        tenant, b.periodBucket(), b.id(), signed == 0 ? HUY : LOI_MOT_PHAN, actor, now);
    return view(tenant, id, actor).orElseThrow();
  }

  /** Trạng thái lô cho tác vụ theo dõi (mọi người gọi; kiểm người khởi tạo ở tầng tác vụ). */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<View> job(TenantId tenant, UUID id) {
    return view(tenant, id, null);
  }

  /**
   * Tài liệu cần ký của lô: sẵn sàng khi lô {@code NHAP} hoặc {@code CHO_KY}; mỗi dòng mức hiện
   * hành chưa ký là một tài liệu.
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<SignSource> signSource(TenantId tenant, UUID id) {
    return batches
        .find(tenant, id, false)
        .map(
            b -> {
              boolean ready = NHAP.equals(b.status()) || CHO_KY.equals(b.status());
              List<SignDoc> docs = new ArrayList<>();
              if (ready) {
                for (BatchRating r : batches.ratings(tenant, b.periodBucket(), b.id())) {
                  if (r.signedAt() == null) {
                    docs.add(new SignDoc(r.profileId(), contentHash(b, r)));
                  }
                }
              }
              return new SignSource(ready && !docs.isEmpty(), b.deciderId(), docs);
            });
  }

  // ---------------------------------------------------------------- kiểm tra

  static void validate(Command c) {
    List<ProblemFieldError> errors = new ArrayList<>();
    if (c.periodId() == null) {
      errors.add(new ProblemFieldError("periodId", "Đồng chí chưa chọn kỳ đánh giá.", null));
    }
    if (!"DANG".equals(c.flow()) && !"NHA_NUOC".equals(c.flow())) {
      errors.add(new ProblemFieldError("flow", "Luồng đánh giá không hợp lệ.", null));
    }
    if (blank(c.decisionNo()) || c.decisionNo().length() > 64) {
      errors.add(
          new ProblemFieldError("decisionNo", "Số quyết định bắt buộc, tối đa 64 ký tự.", null));
    }
    if (c.decisionDate() == null) {
      errors.add(
          new ProblemFieldError("decisionDate", "Đồng chí chưa nhập ngày quyết định.", null));
    }
    if (c.items().isEmpty() || c.items().size() > MAX_ITEMS) {
      errors.add(
          new ProblemFieldError("items", "Mỗi lô quyết định có từ 1 đến 1.000 hồ sơ.", "SCR-0904"));
    }
    if (!errors.isEmpty()) {
      throw new BusinessException("COMMON-422-VALIDATION", errors.get(0).message(), errors);
    }
    Set<UUID> seen = new HashSet<>();
    for (int i = 0; i < c.items().size(); i++) {
      Item item = c.items().get(i);
      if (item.profileId() == null || !seen.add(item.profileId())) {
        errors.add(field(i, "profileId", "Hồ sơ bị thiếu hoặc lặp trong lô."));
      }
      if (item.rating() == null) {
        errors.add(field(i, "ratingCode", "Đồng chí chưa chọn mức xếp loại."));
      }
      if (item.rowVersion() == null) {
        errors.add(field(i, "rowVersion", "Thiếu phiên bản hồ sơ."));
      }
      if (item.reason() != null && item.reason().length() > MAX_REASON) {
        errors.add(field(i, "reason", "Lý do tối đa 2.000 ký tự."));
      }
    }
    if (!errors.isEmpty()) {
      throw new BusinessException("COMMON-422-VALIDATION", errors.get(0).message(), errors);
    }
  }

  private static void checkVersions(List<Item> items, Map<UUID, ProfileRow> rows) {
    List<ProblemFieldError> errors = new ArrayList<>();
    List<ProblemFieldError> status = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      ProfileRow r = rows.get(items.get(i).profileId());
      if (items.get(i).rowVersion() != r.rowVersion()) {
        errors.add(field(i, "rowVersion", "Hồ sơ đã được cập nhật, đồng chí tải lại danh sách."));
      } else if (r.status() != AppraisalStatus.CHO_QUYET_DINH) {
        status.add(field(i, "profileId", "Hồ sơ không còn ở bước chờ quyết định."));
      }
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "APPRAISAL-409-VERSION-CONFLICT",
          "Một số hồ sơ đã được cập nhật sau khi đồng chí mở danh sách.",
          errors);
    }
    if (!status.isEmpty()) {
      throw new BusinessException(
          "APPRAISAL-409-INVALID-STATUS", "Một số hồ sơ không còn ở bước chờ quyết định.", status);
    }
  }

  /**
   * QT-10 ngoại lệ 2: hồ sơ năm còn quý đang khiếu nại chưa kết luận thì chưa quyết định năm
   * (MSG-10-03). Kiểm trên trạng thái {@code KHIEU_NAI} của hồ sơ quý trong MOD-04, không hỏi
   * MOD-07.
   */
  private void checkQuarterComplaints(TenantId tenant, int bucket, List<Item> items) {
    if (bucket % 10 != 0) {
      return;
    }
    Set<UUID> open =
        batches.withOpenQuarterComplaint(
            tenant, bucket, items.stream().map(Item::profileId).toList());
    if (open == null || open.isEmpty()) {
      return;
    }
    List<ProblemFieldError> errors = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      if (open.contains(items.get(i).profileId())) {
        errors.add(field(i, "profileId", MSG_COMPLAINT));
      }
    }
    throw new BusinessException("APPRAISAL-409-COMPLAINT-OPEN", MSG_COMPLAINT, errors, "MSG-10-03");
  }

  private static void checkPending(List<Item> items, Map<UUID, ProfileFacts> facts) {
    List<ProblemFieldError> errors = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      ProfileFacts f = facts.get(items.get(i).profileId());
      if (f != null && f.pendingBatchId() != null) {
        errors.add(field(i, "profileId", "Hồ sơ đang ở một lô quyết định chưa hoàn tất."));
      }
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "APPRAISAL-409-INVALID-STATUS",
          "Một số hồ sơ đang ở lô quyết định khác chưa hoàn tất.",
          errors);
    }
  }

  /** BR-09-14: người quyết định khác người được đánh giá, người thẩm định, người nghiệm thu. */
  private void checkSeparation(
      TenantId tenant, UUID actor, List<Item> items, Map<UUID, ProfileRow> rows) {
    List<ProblemFieldError> errors = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      ProfileRow r = rows.get(items.get(i).profileId());
      boolean involved = actor.equals(r.personId()) || actor.equals(r.appraiserId());
      if (!involved && acceptors != null) {
        involved = acceptors.of(tenant, r.periodId(), r.personId()).contains(actor);
      }
      if (involved) {
        errors.add(field(i, "profileId", MSG_SOD));
      }
    }
    if (!errors.isEmpty()) {
      throw new BusinessException(
          "APPRAISAL-403-SEPARATION-OF-DUTIES", MSG_SOD, errors, "MSG-09-08");
    }
  }

  /** BR-09-12 và BS-03 DC-05: lý do khi khác mức liền trước (≥ 20 ký tự) hoặc khác mức gợi ý. */
  private static void checkReasons(List<Item> items, Map<UUID, ProfileRow> rows) {
    List<ProblemFieldError> required = new ArrayList<>();
    List<ProblemFieldError> suggested = new ArrayList<>();
    List<ProblemFieldError> tooShort = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      Item item = items.get(i);
      ProfileRow r = rows.get(item.profileId());
      RatingLevel previous = previous(r);
      boolean changed = previous != null && previous != item.rating();
      if (blank(item.reason())) {
        if (changed) {
          required.add(field(i, "reason", MSG_REASON, "BR-09-12"));
        } else if (r.autoRating() != null && r.autoRating() != item.rating()) {
          suggested.add(field(i, "reason", MSG_SUGGESTED));
        }
      } else if (changed && item.reason().strip().length() < MIN_REASON) {
        tooShort.add(field(i, "reason", "Lý do đổi mức tối thiểu 20 ký tự."));
      }
    }
    if (!required.isEmpty()) {
      throw new BusinessException(
          "APPRAISAL-422-REASON-REQUIRED", MSG_REASON, required, "MSG-09-12");
    }
    if (!tooShort.isEmpty()) {
      throw new BusinessException("COMMON-422-VALIDATION", tooShort.get(0).message(), tooShort);
    }
    if (!suggested.isEmpty()) {
      throw new BusinessException(
          "APPRAISAL-422-SUGGESTED-REASON-REQUIRED", MSG_SUGGESTED, suggested);
    }
  }

  /** BR-09-17: tỷ lệ HTXSNV của danh sách vượt ngưỡng khối mà chưa xác nhận đã hiệu chỉnh. */
  private void checkCalibration(TenantId tenant, Command command, Map<UUID, ProfileRow> rows) {
    if (command.calibrationAcknowledged()) {
      return;
    }
    long excellent = command.items().stream().filter(i -> i.rating() == RatingLevel.HTXSNV).count();
    if (excellent == 0) {
      return;
    }
    BigDecimal share =
        BigDecimal.valueOf(excellent)
            .divide(BigDecimal.valueOf(command.items().size()), 4, RoundingMode.HALF_UP);
    BigDecimal limit = threshold(tenant, rows.values().iterator().next());
    if (share.compareTo(limit) > 0) {
      String message =
          "Tỷ lệ HTXSNV của danh sách là "
              + percent(share)
              + "%, vượt giới hạn "
              + percent(limit)
              + "% của khối. Đề nghị tổ chức phiên hiệu chỉnh trước khi ký.";
      throw new BusinessException(
          "APPRAISAL-422-CALIBRATION-REQUIRED",
          message,
          List.of(new ProblemFieldError("calibrationAcknowledged", message, "BR-09-17")),
          "MSG-09-13");
    }
  }

  BigDecimal threshold(TenantId tenant, ProfileRow row) {
    if (policies == null || row.policyPackVersionId() == null) {
      return DEFAULT_THRESHOLD;
    }
    return policies
        .parameter(tenant, row.policyPackVersionId(), THRESHOLD_PARAM)
        .filter(Number.class::isInstance)
        .map(v -> new BigDecimal(v.toString()))
        .map(v -> v.compareTo(BigDecimal.ONE) > 0 ? v.movePointLeft(2) : v)
        .orElse(DEFAULT_THRESHOLD);
  }

  /** BR-09-13: hồ sơ người đứng đầu chỉ quyết định khi đơn vị trực thuộc đã có mức. */
  private void checkHeads(
      TenantId tenant,
      int bucket,
      Command command,
      List<Item> items,
      Map<UUID, ProfileFacts> facts) {
    List<UUID> inBatch = items.stream().map(Item::profileId).toList();
    List<ProblemFieldError> errors = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      ProfileFacts f = facts.get(items.get(i).profileId());
      if (f != null
          && f.head()
          && batches.undecidedSubordinates(
                  tenant, bucket, command.periodId(), command.flow(), f.orgUnitId(), inBatch)
              > 0) {
        errors.add(field(i, "profileId", MSG_HEAD, "BR-09-13"));
      }
    }
    if (!errors.isEmpty()) {
      throw new BusinessException("APPRAISAL-422-HEAD-TOO-EARLY", MSG_HEAD, errors, "MSG-09-14");
    }
  }

  // ---------------------------------------------------------------- hỗ trợ

  /** Mức liền trước của quyết định: đề xuất, không có thì thẩm định. */
  static RatingLevel previous(ProfileRow row) {
    RatingLevel appraised = null;
    for (CurrentRating r : row.currentRatings()) {
      if (r.levelKind() == LevelKind.DE_XUAT) {
        return r.rating();
      }
      if (r.levelKind() == LevelKind.THAM_DINH) {
        appraised = r.rating();
      }
    }
    return appraised;
  }

  /** Băm JCS danh sách (mã hồ sơ, mức) sắp theo mã hồ sơ; không phụ thuộc thứ tự gửi. */
  static byte[] profilesHash(List<Item> items) {
    List<Map<String, Object>> list = new ArrayList<>();
    items.stream()
        .sorted(Comparator.comparing(i -> i.profileId().toString()))
        .forEach(
            i -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("profileId", i.profileId().toString());
              m.put("ratingCode", i.rating().name());
              list.add(m);
            });
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("items", list);
    return Jcs.hash(doc);
  }

  /** Băm JCS của quyết định cho một hồ sơ: lô, số và ngày quyết định, dòng mức, mức. */
  static byte[] contentHash(BatchRow b, BatchRating r) {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("batchId", b.id().toString());
    doc.put("decisionNo", b.decisionNo());
    doc.put("decisionDate", b.decisionDate().toString());
    doc.put("profileId", r.profileId().toString());
    doc.put("proposalId", r.proposalId().toString());
    doc.put("ratingCode", r.ratingCode());
    return Jcs.hash(doc);
  }

  private static String percent(BigDecimal ratio) {
    return ratio
        .movePointRight(2)
        .setScale(1, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString();
  }

  private static BusinessException duplicate() {
    return new BusinessException(
        "APPRAISAL-409-DECISION-NO-DUPLICATE",
        "Số quyết định đã được dùng trong năm, đồng chí kiểm tra lại.",
        List.of(
            new ProblemFieldError("decisionNo", "Số quyết định đã được dùng trong năm.", null)));
  }

  private static ProblemFieldError field(int i, String name, String message) {
    return field(i, name, message, null);
  }

  private static ProblemFieldError field(int i, String name, String message, String rule) {
    return new ProblemFieldError("items[" + i + "]." + name, message, rule);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
