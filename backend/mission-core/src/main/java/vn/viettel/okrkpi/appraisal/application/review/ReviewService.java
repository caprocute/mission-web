package vn.viettel.okrkpi.appraisal.application.review;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService.Transitioned;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.application.review.port.EvidenceAcceptors;
import vn.viettel.okrkpi.appraisal.application.review.port.ProposalStep;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewNotices;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.AppraisalSign;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.QueueFilter;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.QueueRow;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.ProfileSummary;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.CurrentRating;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.CriteriaLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Jcs;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Thẩm định và đề xuất (CORE-04-05, QT-09, SCR-0902, SCR-0903): hàng đợi trong phạm vi, trả lại có
 * giới hạn số lần, thẩm định có ký (mức {@code THAM_DINH}, tiêu chí chung giai đoạn {@code
 * THAM_DINH}, chuyển {@code DA_THAM_DINH} khi nhận EVT-18), đề xuất của quản lý trực tiếp. Tách vai
 * trò BR-09-14 được kiểm ở đây (người được đánh giá, người đã nghiệm thu minh chứng của hồ sơ,
 * người quyết định) độc lập với OPA ở tầng web.
 */
public class ReviewService {

  static final String TX = "appraisalTransactionManager";
  static final String REASON = "appraisal.rating_proposal.reason_enc";
  static final String COMMENT = "appraisal.rating_proposal.comment_enc";
  static final String LEGAL_REF = "QĐ Điều 16";

  static final String SOD = "APPRAISAL-403-SEPARATION-OF-DUTIES";
  static final String MSG_SOD =
      "Đồng chí đã tham gia hồ sơ này ở bước khác nên không thực hiện được thao tác này.";
  static final String MSG_APPRAISE_REASON =
      "Mức thẩm định khác mức tự đề xuất, đồng chí cần nêu lý do.";
  static final String MSG_PROPOSE_REASON =
      "Mức đề xuất khác mức thẩm định, đồng chí cần nêu lý do.";
  static final String MSG_SUGGESTED_REASON =
      "Mức đồng chí chọn khác mức hệ thống gợi ý, đồng chí cần nêu lý do.";

  private final AppraisalProfileRepository profiles;
  private final SelfAssessmentRepository forms;
  private final ReviewRepository reviews;
  private final AppraisalProfileService steps;
  private final TextSealer sealer;
  private final EvidenceAcceptors acceptors;
  private final ReviewNotices notices;
  private final ProposalStep proposalStep;
  private final Clock clock;

  public ReviewService(
      AppraisalProfileRepository profiles,
      SelfAssessmentRepository forms,
      ReviewRepository reviews,
      AppraisalProfileService steps,
      TextSealer sealer,
      EvidenceAcceptors acceptors,
      ReviewNotices notices,
      ProposalStep proposalStep,
      Clock clock) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.forms = Objects.requireNonNull(forms, "forms");
    this.reviews = Objects.requireNonNull(reviews, "reviews");
    this.steps = Objects.requireNonNull(steps, "steps");
    this.sealer = Objects.requireNonNull(sealer, "sealer");
    this.acceptors = acceptors;
    this.notices = Objects.requireNonNull(notices, "notices");
    this.proposalStep = proposalStep == null ? (t, r) -> false : proposalStep;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Một dòng hàng đợi kèm mức hiện hành của bốn bước. */
  public record QueueItem(QueueRow row, Map<LevelKind, RatingLevel> levels) {}

  /** Kết luận thẩm định người dùng nhập (SCR-0902 trường 6 đến 8). */
  public record AppraisalDraft(
      RatingLevel rating,
      String comment,
      String reason,
      List<SelfAssessmentForm.Criterion> criteria,
      UUID seenSnapshotId) {

    public AppraisalDraft {
      criteria = criteria == null ? List.of() : List.copyOf(criteria);
    }

    public AppraisalDraft(
        RatingLevel rating,
        String comment,
        String reason,
        List<SelfAssessmentForm.Criterion> criteria) {
      this(rating, comment, reason, criteria, null);
    }
  }

  /**
   * Đã ghi mức thẩm định chờ ký; {@code signRequestId} có sẵn khi gửi lại lệnh đã mở yêu cầu ký.
   */
  public record Prepared(UUID profileId, UUID proposalId, int periodBucket, UUID signRequestId) {}

  /** Tài liệu SVC-10 cần ký: người ký là người thẩm định, băm JCS của kết luận thẩm định. */
  public record SignSource(boolean ready, UUID signer, UUID proposalId, byte[] contentHash) {}

  /** Tác vụ theo dõi ký của kết luận thẩm định. */
  public record SignJob(UUID profileId, UUID actorId, boolean signed, Instant signedAt) {}

  /** Hàng đợi trong phạm vi {@code scope}, sắp theo mã hồ sơ sau con trỏ. */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<QueueItem> queue(
      TenantId tenant, QueueFilter filter, Scope scope, UUID after, int limit) {
    List<QueueRow> rows = reviews.queue(tenant, filter, scope, after, limit);
    if (rows.isEmpty()) {
      return List.of();
    }
    Set<UUID> ids = new LinkedHashSet<>();
    rows.forEach(r -> ids.add(r.profile().id()));
    Map<UUID, Map<LevelKind, RatingLevel>> levels = reviews.currentLevels(tenant, ids);
    return rows.stream()
        .map(r -> new QueueItem(r, levels.getOrDefault(r.profile().id(), Map.of())))
        .toList();
  }

  /**
   * Thao tác người gọi còn làm được trên hồ sơ (YC-PT-21), cùng điều kiện tách vai với {@link
   * #returnProfile}, {@link #prepareAppraisal} và {@link #propose}; người nghiệm thu minh chứng chỉ
   * tra khi cần.
   */
  public Set<String> permittedOn(TenantId tenant, ProfileSummary p, UUID actor) {
    return AppraisalPermittedActions.of(
        p,
        actor,
        () -> acceptors == null ? Set.of() : acceptors.of(tenant, p.periodId(), p.personId()));
  }

  /** Đơn vị, luồng và năm vai đã tham gia hồ sơ để kiểm quyền OPA. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<ReviewParticipants> participants(TenantId tenant, UUID profileId) {
    return forms.summary(tenant, profileId).map(p -> participants(tenant, p));
  }

  /**
   * Trả lại bổ sung (MSG-09-09): bắt buộc lý do, không quá số lần tối đa của Policy Pack; hồ sơ về
   * {@code TU_DANH_GIA}, phát EVT-11 và báo người được đánh giá (EVT-21).
   */
  @Transactional(TX)
  public Transitioned returnProfile(
      TenantId tenant, UUID profileId, long expectedVersion, UUID actor, String reason) {
    ProfileSummary summary =
        forms.summary(tenant, profileId).orElseThrow(NoSuchElementException::new);
    if (!participants(tenant, summary).mayAppraise(actor)) {
      throw new BusinessException(SOD, MSG_SOD, List.of(), "MSG-09-08");
    }
    Transitioned done = steps.returnForRevision(tenant, profileId, expectedVersion, actor, reason);
    notices.returned(
        tenant, profileId, summary.personId(), summary.returnCount() + 1, done.rowVersion());
    return done;
  }

  /**
   * Ghi kết luận thẩm định chờ ký: mức {@code THAM_DINH} (lý do bắt buộc khi khác mức tự đề xuất,
   * MSG-09-06, hoặc khác mức gợi ý), nhận xét C2, tiêu chí chung giai đoạn {@code THAM_DINH} (không
   * gửi thì chép kết quả tự nhận). Gửi lại khi kết luận của chính người này đang chờ ký thì trả yêu
   * cầu ký cũ.
   */
  @Transactional(transactionManager = TX, propagation = Propagation.REQUIRES_NEW)
  public Prepared prepareAppraisal(
      TenantId tenant, UUID profileId, long expectedVersion, UUID actor, AppraisalDraft draft) {
    ProfileRow row = profiles.lock(tenant, profileId).orElseThrow(NoSuchElementException::new);
    Optional<AppraisalSign> current =
        reviews.currentAppraisal(tenant, row.periodBucket(), row.id());
    if (current.isPresent()
        && current.get().pending()
        && actor.equals(current.get().actorId())
        && row.status() == AppraisalStatus.CHO_THAM_DINH) {
      AppraisalSign s = current.get();
      return new Prepared(row.id(), s.proposalId(), row.periodBucket(), s.signRequestId());
    }
    if (expectedVersion != row.rowVersion()) {
      throw new VersionConflictException("APPRAISAL", expectedVersion, row.rowVersion());
    }
    ProfileSummary summary = forms.summary(tenant, profileId).orElseThrow();
    if (!participants(tenant, summary).mayAppraise(actor)) {
      throw new BusinessException(SOD, MSG_SOD, List.of(), "MSG-09-08");
    }
    if (draft.rating() == null) {
      throw invalid("ratingCode", "Đồng chí chưa chọn mức xếp loại.");
    }
    if (blank(draft.comment())) {
      throw invalid("comment", "Đồng chí chưa nhập nhận xét thẩm định.");
    }
    String reasonText = blank(draft.reason()) ? null : draft.reason();
    RatingLevel previous = rating(row, LevelKind.TU_DE_XUAT).orElse(row.autoRating());
    requireReasons(
        draft.rating(),
        previous,
        reasonText,
        suggested(tenant, row, draft.seenSnapshotId()),
        MSG_APPRAISE_REASON,
        "MSG-09-06");
    UUID proposalId = UUID.randomUUID();
    CurrentRating rating =
        steps.recordRating(
            tenant,
            profileId,
            null,
            LevelKind.THAM_DINH,
            draft.rating(),
            actor,
            "appraiser",
            sealer.seal(tenant, REASON, proposalId, reasonText),
            sealer.seal(tenant, COMMENT, proposalId, draft.comment()),
            proposalId);
    if (rating.explanationRequired()) {
      steps.recordExplanation(
          tenant,
          profileId,
          LevelKind.THAM_DINH,
          explanationType(draft.rating(), previous),
          null,
          LEGAL_REF,
          null,
          actor);
    }
    Instant now = clock.instant();
    List<SelfAssessmentForm.Criterion> criteria =
        draft.criteria().isEmpty()
            ? reviews.selfCriteria(tenant, row.periodBucket(), row.id())
            : draft.criteria();
    if (!criteria.isEmpty()) {
      long met = criteria.stream().filter(SelfAssessmentForm.Criterion::met).count();
      BigDecimal ratio = CriteriaLevel.ratio(met, criteria.size());
      reviews.replaceAppraisalCriteria(
          tenant, row, criteria, ratio, CriteriaLevel.of(ratio), actor, now);
    }
    forms
        .touchProfile(tenant, row, actor, now)
        .orElseThrow(() -> new VersionConflictException("APPRAISAL", row.rowVersion(), null));
    return new Prepared(row.id(), proposalId, row.periodBucket(), null);
  }

  /** Gắn yêu cầu ký SVC-10 vừa mở vào dòng mức thẩm định. */
  @Transactional(transactionManager = TX, propagation = Propagation.REQUIRES_NEW)
  public void attachSignRequest(TenantId tenant, Prepared prepared, UUID signRequestId) {
    reviews.attachSignRequest(
        tenant, prepared.periodBucket(), prepared.proposalId(), signRequestId, clock.instant());
  }

  /** Tài liệu cần ký: sẵn sàng khi hồ sơ chờ thẩm định và kết luận hiện hành chưa ký. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<SignSource> signSource(TenantId tenant, UUID profileId) {
    return forms
        .summary(tenant, profileId)
        .map(
            p -> {
              AppraisalSign s =
                  reviews.currentAppraisal(tenant, p.periodBucket(), profileId).orElse(null);
              if (s == null) {
                return new SignSource(false, null, null, null);
              }
              boolean ready = s.signedAt() == null && p.status() == AppraisalStatus.CHO_THAM_DINH;
              return new SignSource(ready, s.actorId(), s.proposalId(), contentHash(tenant, s));
            });
  }

  /** Trạng thái tác vụ ký của yêu cầu ký {@code signRequestId}. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<SignJob> signJob(TenantId tenant, UUID signRequestId) {
    return reviews
        .appraisalBySignRequest(tenant, signRequestId)
        .map(s -> new SignJob(s.profileId(), s.actorId(), s.signedAt() != null, s.signedAt()));
  }

  /**
   * EVT-18 của kết luận thẩm định: ghi thời điểm ký, chuyển {@code DA_THAM_DINH} (EVT-11) và bỏ qua
   * cấp đề xuất khi mẫu luồng cho phép. Sự kiện lặp, kết luận đã bị thay hoặc hồ sơ đã rời bước thì
   * bỏ qua.
   *
   * @return {@code true} khi hồ sơ đã chuyển bước
   */
  @Transactional(TX)
  public boolean onAppraisalSigned(TenantId tenant, UUID signRequestId, Instant signedAt) {
    Optional<AppraisalSign> found = reviews.appraisalBySignRequest(tenant, signRequestId);
    if (found.isEmpty() || found.get().signedAt() != null) {
      return false;
    }
    AppraisalSign s = found.get();
    ProfileRow row = profiles.lock(tenant, s.profileId()).orElseThrow();
    reviews.markSigned(tenant, s.periodBucket(), s.proposalId(), signedAt, clock.instant());
    if (row.status() != AppraisalStatus.CHO_THAM_DINH || !s.current()) {
      return false;
    }
    UUID actor = s.actorId();
    steps.transition(tenant, row.id(), null, actor, (p, r) -> p.markAppraised(actor));
    ProfileRow appraised = profiles.lock(tenant, row.id()).orElseThrow();
    if (proposalStep.skipped(tenant, appraised)) {
      steps.transition(tenant, row.id(), null, actor, (p, r) -> p.skipProposal(actor));
    }
    return true;
  }

  /**
   * Đề xuất mức của quản lý trực tiếp (SCR-0903): bản ghi {@code DE_XUAT}, lý do bắt buộc khi khác
   * mức thẩm định (MSG-09-12), hồ sơ chuyển {@code CHO_QUYET_DINH}. Bước này không ký số.
   */
  @Transactional(TX)
  public Transitioned propose(
      TenantId tenant,
      UUID profileId,
      long expectedVersion,
      UUID actor,
      RatingLevel level,
      String reason,
      String comment) {
    return propose(tenant, profileId, expectedVersion, actor, level, reason, comment, null);
  }

  /**
   * Như {@link #propose(TenantId, UUID, long, UUID, RatingLevel, String, String)}, mức gợi ý so
   * sánh lấy từ ảnh chụp người gọi đã xem ({@code seenScoreSnapshotId}, BS-03 DC-05).
   */
  @Transactional(TX)
  public Transitioned propose(
      TenantId tenant,
      UUID profileId,
      long expectedVersion,
      UUID actor,
      RatingLevel level,
      String reason,
      String comment,
      UUID seenSnapshotId) {
    ProfileRow row = profiles.lock(tenant, profileId).orElseThrow(NoSuchElementException::new);
    if (expectedVersion != row.rowVersion()) {
      throw new VersionConflictException("APPRAISAL", expectedVersion, row.rowVersion());
    }
    ProfileSummary summary = forms.summary(tenant, profileId).orElseThrow();
    if (!participants(tenant, summary).mayPropose(actor)) {
      throw new BusinessException(SOD, MSG_SOD, List.of(), "MSG-09-08");
    }
    if (level == null) {
      throw invalid("ratingCode", "Đồng chí chưa chọn mức xếp loại.");
    }
    String reasonText = blank(reason) ? null : reason;
    RatingLevel appraised = rating(row, LevelKind.THAM_DINH).orElse(null);
    requireReasons(
        level,
        appraised,
        reasonText,
        suggested(tenant, row, seenSnapshotId),
        MSG_PROPOSE_REASON,
        "MSG-09-12");
    UUID proposalId = UUID.randomUUID();
    CurrentRating rating =
        steps.recordRating(
            tenant,
            profileId,
            null,
            LevelKind.DE_XUAT,
            level,
            actor,
            "manager",
            sealer.seal(tenant, REASON, proposalId, reasonText),
            blank(comment) ? null : sealer.seal(tenant, COMMENT, proposalId, comment),
            proposalId);
    if (rating.explanationRequired()) {
      steps.recordExplanation(
          tenant,
          profileId,
          LevelKind.DE_XUAT,
          explanationType(level, appraised),
          null,
          LEGAL_REF,
          null,
          actor);
    }
    return steps.transition(tenant, profileId, null, actor, (p, r) -> p.propose(actor));
  }

  // ---------------------------------------------------------------- hỗ trợ

  private ReviewParticipants participants(TenantId tenant, ProfileSummary p) {
    Set<UUID> accepted =
        acceptors == null ? Set.of() : acceptors.of(tenant, p.periodId(), p.personId());
    return new ReviewParticipants(
        tenant.value(),
        p.id(),
        p.orgUnitId(),
        p.flow(),
        p.personId(),
        accepted,
        p.appraiserId(),
        p.deciderId());
  }

  /**
   * Khác mức liền trước mà không có lý do thì 422 {@code REASON-REQUIRED}; khác mức hệ thống gợi ý
   * của ảnh chụp hiện hành mà không có lý do thì 422 {@code SUGGESTED-REASON-REQUIRED} (BS-03
   * DC-05). Kiểm trước khi ghi nên không để lại dòng mức nào.
   */
  /**
   * Mức hệ thống gợi ý để so (BS-03 DC-05): của ảnh chụp người gọi đã xem khi có, ngược lại của ảnh
   * chụp hiện hành của hồ sơ.
   */
  private RatingLevel suggested(TenantId tenant, ProfileRow row, UUID seenSnapshotId) {
    if (seenSnapshotId == null) {
      return row.autoRating();
    }
    return profiles
        .snapshotRating(tenant, row.periodBucket(), row.id(), seenSnapshotId)
        .orElseThrow(
            () -> invalid("seenScoreSnapshotId", "Ảnh chụp điểm không thuộc hồ sơ đánh giá này."));
  }

  static void requireReasons(
      RatingLevel level,
      RatingLevel previous,
      String reason,
      RatingLevel auto,
      String message,
      String msg) {
    if (reason != null) {
      return;
    }
    if (previous != null && previous != level) {
      throw new BusinessException(
          "APPRAISAL-422-REASON-REQUIRED",
          message,
          List.of(new ProblemFieldError("reason", message, "BR-09-12")),
          msg);
    }
    if (auto != null && auto != level) {
      throw new BusinessException(
          "APPRAISAL-422-SUGGESTED-REASON-REQUIRED",
          MSG_SUGGESTED_REASON,
          List.of(new ProblemFieldError("reason", MSG_SUGGESTED_REASON, null)));
    }
  }

  private static Optional<RatingLevel> rating(ProfileRow row, LevelKind kind) {
    return row.currentRatings().stream()
        .filter(r -> r.levelKind() == kind)
        .map(CurrentRating::rating)
        .findFirst();
  }

  /** Mức thấp hơn mức liền trước là hạ mức, còn lại là khác mức trước. */
  public static String explanationType(RatingLevel level, RatingLevel previous) {
    return previous != null && level.ordinal() > previous.ordinal() ? "HA_MUC" : "KHAC_MUC_TRUOC";
  }

  /** Băm JCS (RFC 8785) của kết luận thẩm định: hồ sơ, dòng mức, mức, nhận xét và lý do. */
  byte[] contentHash(TenantId tenant, AppraisalSign s) {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("profileId", s.profileId().toString());
    doc.put("proposalId", s.proposalId().toString());
    doc.put("ratingCode", s.rating().name());
    doc.put("comment", sealer.open(tenant, COMMENT, s.proposalId(), s.comment()));
    doc.put("reason", sealer.open(tenant, REASON, s.proposalId(), s.reason()));
    return Jcs.hash(doc);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static BusinessException invalid(String field, String message) {
    return new BusinessException(
        "COMMON-422-VALIDATION", message, List.of(new ProblemFieldError(field, message, null)));
  }
}
