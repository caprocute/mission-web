package vn.viettel.okrkpi.appraisal.application.selfassessment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.application.profile.AppraisalProfileService;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.ConfirmedEvidence;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentNotices;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.CriteriaRow;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.ProfileSummary;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.SignTarget;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.StoredSelfAssessment;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.TrailRow;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.TextSealer;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalProfile;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalStatus;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.ContentHash;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.CriteriaLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentIssue;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentRules;
import vn.viettel.okrkpi.config.api.FormValidationApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ProblemFieldError;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Phiếu tự đánh giá (CORE-04-04, QT-09, SCR-0901): lưu nháp với {@code If-Match}, mã hoá trường C2,
 * kiểm biểu mẫu động ({@code x-cel}) và quy đổi tiêu chí chung ở máy chủ (BR-09-05), chốt nội dung
 * để ký (BR-09-15), nhận EVT-18 thì ghi mức tự đề xuất và chuyển {@code CHO_THAM_DINH}.
 */
public class SelfAssessmentService {

  static final String TX = "appraisalTransactionManager";

  /** Khoá biểu mẫu động của phiếu tự đánh giá ở {@code config.form_schema} (suy luận). */
  public static final String FORM_KEY = "MAU_TU_DANH_GIA";

  static final String DEFAULT_FORM_VERSION = "1.0";
  static final String TABLE = "appraisal.self_assessment.";
  static final String PROPOSAL_REASON = "appraisal.rating_proposal.reason_enc";

  private final AppraisalProfileRepository profiles;
  private final SelfAssessmentRepository forms;
  private final AppraisalProfileService steps;
  private final TextSealer sealer;
  private final FormValidationApi validator;
  private final ConfirmedEvidence evidence;
  private final SelfAssessmentNotices notices;
  private final ObjectMapper json;
  private final Clock clock;

  public SelfAssessmentService(
      AppraisalProfileRepository profiles,
      SelfAssessmentRepository forms,
      AppraisalProfileService steps,
      TextSealer sealer,
      FormValidationApi validator,
      ConfirmedEvidence evidence,
      SelfAssessmentNotices notices,
      ObjectMapper json,
      Clock clock) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.forms = Objects.requireNonNull(forms, "forms");
    this.steps = Objects.requireNonNull(steps, "steps");
    this.sealer = Objects.requireNonNull(sealer, "sealer");
    this.validator = validator;
    this.evidence = evidence;
    this.notices = Objects.requireNonNull(notices, "notices");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Hồ sơ cùng phiếu hiện hành đã giải mã, tiêu chí chung và vết mức. */
  public record View(
      ProfileSummary profile,
      StoredSelfAssessment stored,
      SelfAssessmentForm form,
      List<CriteriaRow> criteria,
      List<TrailRow> trail) {}

  /** Tác vụ theo dõi ký của một yêu cầu ký: đã ký thì xong, chưa ký thì đang chờ người ký. */
  public record SignJob(UUID profileId, UUID personId, boolean signed, Instant signedAt) {}

  /** Tài liệu SVC-10 cần ký cho một hồ sơ: người ký và băm nội dung phiếu đã chốt. */
  public record SignSource(boolean ready, UUID signer, UUID selfAssessmentId, byte[] contentHash) {}

  /** Đã chốt phiếu để ký; {@code signRequestId} có sẵn khi gửi lại lệnh nộp đã mở yêu cầu ký. */
  public record Prepared(
      UUID profileId, UUID selfAssessmentId, int periodBucket, UUID signRequestId) {}

  /**
   * Đọc hồ sơ và phiếu hiện hành cho {@code actor}: chủ hồ sơ, người thẩm định hoặc người quyết
   * định; người khác nhận 403 (phạm vi đơn vị của {@code manager} thuộc CORE-04-05).
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<View> view(TenantId tenant, UUID profileId, UUID actor) {
    Optional<ProfileSummary> found = forms.summary(tenant, profileId);
    if (found.isPresent() && !found.get().readableBy(actor)) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Đồng chí không có quyền xem hồ sơ đánh giá này.");
    }
    return found.map(p -> view(tenant, p));
  }

  /** Hồ sơ của người đang đăng nhập (thẻ "Tự đánh giá quý"), phân trang con trỏ. */
  @Transactional(transactionManager = TX, readOnly = true)
  public List<ProfileSummary> mine(
      TenantId tenant, UUID personId, UUID periodId, String flow, UUID after, int limit) {
    return forms.mine(tenant, personId, periodId, flow, after, limit);
  }

  /** Trạng thái tác vụ ký của yêu cầu ký {@code signRequestId}. */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<SignJob> signJob(TenantId tenant, UUID signRequestId) {
    return forms
        .findBySignRequest(tenant, signRequestId)
        .flatMap(
            t ->
                forms
                    .summary(tenant, t.profileId())
                    .map(
                        p ->
                            new SignJob(
                                t.profileId(), p.personId(), t.signedAt() != null, t.signedAt())));
  }

  /**
   * Tài liệu cần ký cho cổng nội bộ của SVC-10: chỉ sẵn sàng khi phiếu hiện hành đã chốt nội dung
   * và chưa ký; người ký duy nhất là chủ hồ sơ.
   */
  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<SignSource> signSource(TenantId tenant, UUID profileId) {
    return forms
        .summary(tenant, profileId)
        .map(
            p -> {
              StoredSelfAssessment s =
                  forms.current(tenant, p.periodBucket(), profileId).orElse(null);
              boolean ready = s != null && s.pending() && p.status() == AppraisalStatus.TU_DANH_GIA;
              return new SignSource(
                  ready,
                  p.personId(),
                  s == null ? null : s.id(),
                  s == null ? null : s.contentHash());
            });
  }

  /** Lưu nháp (PUT): chỉ chủ hồ sơ, ở {@code TU_DANH_GIA}, không trong lúc chờ ký. */
  @Transactional(TX)
  public View save(
      TenantId tenant, UUID profileId, long expectedVersion, UUID actor, SelfAssessmentForm form) {
    ProfileRow row = load(tenant, profileId, expectedVersion);
    AppraisalProfile profile = row.toAggregate();
    AppraisalProfileService.run(
        () -> {
          profile.ensureSelfAssessmentEditable(actor);
          return null;
        });
    reject(SelfAssessmentRules.checkDraft(form));
    String formVersion = validateForm(tenant, form);
    Instant now = clock.instant();
    StoredSelfAssessment current =
        forms.current(tenant, row.periodBucket(), profileId).orElse(null);
    if (current != null && current.pending() && current.signRequestId() != null) {
      throw new BusinessException(
          "APPRAISAL-409-INVALID-STATUS", "Phiếu đang chờ ký số, chưa sửa được.");
    }
    boolean fresh = current == null || current.signed();
    UUID id = fresh ? UUID.randomUUID() : current.id();
    int versionNo =
        current == null ? 1 : current.signed() ? current.versionNo() + 1 : current.versionNo();
    StoredSelfAssessment draft = seal(tenant, id, profileId, versionNo, formVersion, form);
    if (fresh) {
      forms.insert(tenant, row, draft, actor, now);
    } else {
      forms.update(tenant, row.periodBucket(), draft, actor, now);
    }
    BigDecimal ratio = CriteriaLevel.ratio(form.metCount(), form.criteria().size());
    forms.replaceCriteria(tenant, row, form.criteria(), ratio, CriteriaLevel.of(ratio), actor, now);
    touch(tenant, row, actor, now);
    return view(tenant, forms.summary(tenant, profileId).orElseThrow());
  }

  /**
   * Chốt phiếu để ký: kiểm đủ trường và quy tắc, tính băm, đặt trạng thái chờ ký. Gửi lại khi đã mở
   * yêu cầu ký thì trả lại yêu cầu cũ, không mở yêu cầu thứ hai.
   *
   * @param clientHash băm client đã xem trước, lệch với máy chủ thì 409 (chống ký nội dung khác)
   */
  @Transactional(TX)
  public Prepared prepareSubmit(
      TenantId tenant, UUID profileId, long expectedVersion, UUID actor, byte[] clientHash) {
    ProfileRow row = profiles.lock(tenant, profileId).orElseThrow(NoSuchElementException::new);
    StoredSelfAssessment current =
        forms.current(tenant, row.periodBucket(), profileId).orElse(null);
    if (current != null && current.pending() && current.signRequestId() != null) {
      return new Prepared(profileId, current.id(), row.periodBucket(), current.signRequestId());
    }
    if (expectedVersion != row.rowVersion()) {
      throw new VersionConflictException("APPRAISAL", expectedVersion, row.rowVersion());
    }
    AppraisalProfile profile = row.toAggregate();
    AppraisalProfileService.run(
        () -> {
          profile.ensureSelfAssessmentEditable(actor);
          return null;
        });
    if (current == null || current.signed()) {
      throw new BusinessException(
          "COMMON-422-VALIDATION",
          "Đồng chí cần lưu phiếu tự đánh giá trước khi nộp.",
          List.of(new ProblemFieldError("content", "Phiếu chưa có nội dung.", null)));
    }
    SelfAssessmentForm form = open(tenant, current);
    Set<UUID> confirmed =
        evidence == null || form.breakthroughEvidenceIds().isEmpty()
            ? Set.of()
            : evidence.confirmed(tenant, row.personId(), form.breakthroughEvidenceIds());
    reject(SelfAssessmentRules.checkSubmit(form, row.autoRating(), confirmed));
    byte[] hash = ContentHash.of(profileId, current.versionNo(), form);
    if (clientHash != null && !java.util.Arrays.equals(clientHash, hash)) {
      throw new BusinessException(
          "APPRAISAL-409-INVALID-STATUS",
          "Nội dung phiếu đã thay đổi so với bản đồng chí đã xem. Vui lòng xem lại trước khi ký.");
    }
    Instant now = clock.instant();
    forms.markPending(tenant, row.periodBucket(), current.id(), hash, now);
    touch(tenant, row, actor, now);
    return new Prepared(profileId, current.id(), row.periodBucket(), null);
  }

  /** Gắn yêu cầu ký SVC-10 vừa mở. */
  @Transactional(TX)
  public void attachSignRequest(TenantId tenant, Prepared prepared, UUID signRequestId) {
    forms.attachSignRequest(
        tenant,
        prepared.periodBucket(),
        prepared.selfAssessmentId(),
        signRequestId,
        clock.instant());
  }

  /** Mở yêu cầu ký lỗi: bỏ trạng thái chờ ký, dữ liệu đã nhập giữ nguyên (MSG-09-05). */
  @Transactional(TX)
  public void abortSubmit(TenantId tenant, Prepared prepared) {
    forms.clearPending(
        tenant, prepared.periodBucket(), prepared.selfAssessmentId(), clock.instant());
  }

  /**
   * EVT-18 của phiếu: ghi thời điểm ký, mức tự đề xuất (lý do mã hoá lại theo dòng mức), giải trình
   * khi khác mức tham khảo, chuyển {@code CHO_THAM_DINH} (phát EVT-11) và báo người nộp (EVT-21).
   * Sự kiện lặp hoặc đến khi hồ sơ đã rời bước tự đánh giá thì bỏ qua.
   *
   * @return {@code true} khi hồ sơ đã chuyển bước
   */
  @Transactional(TX)
  public boolean onSigned(TenantId tenant, UUID signRequestId, Instant signedAt) {
    Optional<SignTarget> found = forms.findBySignRequest(tenant, signRequestId);
    if (found.isEmpty() || found.get().signedAt() != null) {
      return false;
    }
    SignTarget target = found.get();
    ProfileRow row = profiles.lock(tenant, target.profileId()).orElseThrow();
    Instant now = clock.instant();
    forms.markSigned(tenant, target.periodBucket(), target.selfAssessmentId(), signedAt, now);
    if (row.status() != AppraisalStatus.TU_DANH_GIA) {
      return false;
    }
    StoredSelfAssessment stored = forms.current(tenant, row.periodBucket(), row.id()).orElseThrow();
    SelfAssessmentForm form = open(tenant, stored);
    UUID person = row.personId();
    if (form.selfRating() != null) {
      UUID proposalId = UUID.randomUUID();
      boolean differs = SelfAssessmentRules.differs(form, row.autoRating());
      SealedText reason =
          differs ? sealer.seal(tenant, PROPOSAL_REASON, proposalId, form.reason()) : null;
      steps.recordRating(
          tenant,
          row.id(),
          null,
          LevelKind.TU_DE_XUAT,
          form.selfRating(),
          person,
          "staff",
          reason,
          null,
          proposalId);
      if (differs) {
        steps.recordExplanation(
            tenant,
            row.id(),
            LevelKind.TU_DE_XUAT,
            explanationType(form.selfRating(), row.autoRating()),
            null,
            "QĐ Điều 16",
            null,
            person);
      }
    }
    AppraisalProfileService.Transitioned done =
        steps.transition(tenant, row.id(), null, person, (p, r) -> p.markSubmitted(person));
    notices.submitted(tenant, row.id(), person, signedAt, done.rowVersion());
    return true;
  }

  /**
   * Quá hạn tự đánh giá (timer luồng duyệt): chuyển thẩm định với cờ "không tự đánh giá", không tự
   * điền mức (QT-09 ngoại lệ 3).
   */
  @Transactional(TX)
  public void skipOverdue(TenantId tenant, UUID profileId, UUID systemActor) {
    ProfileRow row = profiles.lock(tenant, profileId).orElseThrow(NoSuchElementException::new);
    if (row.status() != AppraisalStatus.TU_DANH_GIA) {
      return;
    }
    steps.transition(
        tenant, profileId, null, systemActor, (p, r) -> p.skipSelfAssessment(systemActor));
    forms.flagMissing(tenant, row);
  }

  // ---------------------------------------------------------------- hỗ trợ

  private View view(TenantId tenant, ProfileSummary p) {
    StoredSelfAssessment stored = forms.current(tenant, p.periodBucket(), p.id()).orElse(null);
    return new View(
        p,
        stored,
        stored == null ? null : open(tenant, stored),
        forms.criteria(tenant, p.periodBucket(), p.id()),
        forms.trail(tenant, p.periodBucket(), p.id()));
  }

  private ProfileRow load(TenantId tenant, UUID profileId, long expectedVersion) {
    ProfileRow row = profiles.lock(tenant, profileId).orElseThrow(NoSuchElementException::new);
    if (expectedVersion != row.rowVersion()) {
      throw new VersionConflictException("APPRAISAL", expectedVersion, row.rowVersion());
    }
    return row;
  }

  private void touch(TenantId tenant, ProfileRow row, UUID actor, Instant now) {
    forms
        .touchProfile(tenant, row, actor, now)
        .orElseThrow(() -> new VersionConflictException("APPRAISAL", row.rowVersion(), null));
  }

  /**
   * Kiểm theo biểu mẫu động khi tenant đã cấu hình {@link #FORM_KEY}; chưa cấu hình thì chỉ dùng
   * quy tắc cố định của {@link SelfAssessmentRules}. Trả phiên bản biểu mẫu đã dùng.
   */
  private String validateForm(TenantId tenant, SelfAssessmentForm form) {
    if (validator == null) {
      return DEFAULT_FORM_VERSION;
    }
    FormValidationApi.Result result = validator.validate(tenant, FORM_KEY, null, payload(form));
    if (result.schemaVersion() == null) {
      return DEFAULT_FORM_VERSION;
    }
    if (!result.valid()) {
      throw new BusinessException(
          "COMMON-422-VALIDATION",
          "Phiếu tự đánh giá chưa hợp lệ.",
          result.errors().stream()
              .map(v -> new ProblemFieldError(v.field(), v.message(), v.rule()))
              .toList());
    }
    return result.schemaVersion();
  }

  private Map<String, Object> payload(SelfAssessmentForm form) {
    Map<String, Object> m = new LinkedHashMap<>(structured(form));
    putText(m, "breakthrough", form.breakthrough());
    putText(m, "reason", form.reason());
    putText(m, "content", form.content());
    putText(m, "strengths", form.strengths());
    putText(m, "weaknesses", form.weaknesses());
    putText(m, "remedialPlan", form.remedialPlan());
    return m;
  }

  private static void putText(Map<String, Object> m, String key, String value) {
    if (value != null) {
      m.put(key, value);
    }
  }

  private static Map<String, Object> structured(SelfAssessmentForm form) {
    Map<String, Object> m = new LinkedHashMap<>();
    List<Map<String, Object>> criteria = new ArrayList<>();
    for (SelfAssessmentForm.Criterion c : form.criteria()) {
      criteria.add(Map.of("criteriaCode", c.code(), "met", c.met()));
    }
    m.put("criteria", criteria);
    if (form.selfRating() != null) {
      m.put("selfRatingCode", form.selfRating().name());
    }
    m.put(
        "breakthroughEvidenceIds",
        form.breakthroughEvidenceIds().stream().map(UUID::toString).toList());
    return m;
  }

  private StoredSelfAssessment seal(
      TenantId tenant,
      UUID id,
      UUID profileId,
      int versionNo,
      String formVersion,
      SelfAssessmentForm form) {
    SealedText content = sealer.seal(tenant, TABLE + "content_enc", id, form.content());
    SealedText strengths = sealer.seal(tenant, TABLE + "strengths_enc", id, form.strengths());
    SealedText weaknesses = sealer.seal(tenant, TABLE + "weaknesses_enc", id, form.weaknesses());
    SealedText remedial = sealer.seal(tenant, TABLE + "remedial_plan_enc", id, form.remedialPlan());
    SealedText breakthrough =
        sealer.seal(tenant, TABLE + "breakthrough_enc", id, form.breakthrough());
    SealedText reason = sealer.seal(tenant, TABLE + "reason_enc", id, form.reason());
    UUID dek =
        java.util.stream.Stream.of(content, strengths, weaknesses, remedial, breakthrough, reason)
            .filter(Objects::nonNull)
            .map(SealedText::dekId)
            .findFirst()
            .orElse(null);
    return new StoredSelfAssessment(
        id,
        profileId,
        versionNo,
        FORM_KEY,
        formVersion,
        write(structured(form)),
        form.claims().isEmpty() ? null : write(form.claims()),
        content,
        strengths,
        weaknesses,
        remedial,
        breakthrough,
        reason,
        dek,
        null,
        null,
        null,
        null,
        null);
  }

  SelfAssessmentForm open(TenantId tenant, StoredSelfAssessment s) {
    Map<String, Object> data = read(s.structuredJson(), new TypeReference<>() {});
    List<SelfAssessmentForm.Criterion> criteria = new ArrayList<>();
    Object rawCriteria = data.get("criteria");
    if (rawCriteria instanceof List<?> list) {
      for (Object o : list) {
        if (o instanceof Map<?, ?> c && c.get("criteriaCode") != null) {
          criteria.add(
              new SelfAssessmentForm.Criterion(
                  c.get("criteriaCode").toString(), Boolean.TRUE.equals(c.get("met"))));
        }
      }
    }
    List<UUID> evidenceIds = new ArrayList<>();
    if (data.get("breakthroughEvidenceIds") instanceof List<?> ids) {
      ids.forEach(i -> evidenceIds.add(UUID.fromString(i.toString())));
    }
    Object rating = data.get("selfRatingCode");
    List<Map<String, Object>> claims =
        s.claimsJson() == null ? List.of() : read(s.claimsJson(), new TypeReference<>() {});
    UUID id = s.id();
    return new SelfAssessmentForm(
        criteria,
        rating == null ? null : RatingLevel.valueOf(rating.toString()),
        sealer.open(tenant, TABLE + "breakthrough_enc", id, s.breakthrough()),
        evidenceIds,
        sealer.open(tenant, TABLE + "reason_enc", id, s.reason()),
        sealer.open(tenant, TABLE + "content_enc", id, s.content()),
        sealer.open(tenant, TABLE + "strengths_enc", id, s.strengths()),
        sealer.open(tenant, TABLE + "weaknesses_enc", id, s.weaknesses()),
        sealer.open(tenant, TABLE + "remedial_plan_enc", id, s.remedialPlan()),
        claims);
  }

  /** Mức thấp hơn mức tham khảo là hạ mức, còn lại là khác mức trước. */
  static String explanationType(RatingLevel self, RatingLevel auto) {
    return auto != null && self.ordinal() > auto.ordinal() ? "HA_MUC" : "KHAC_MUC_TRUOC";
  }

  /** Đổi lỗi kiểm phiếu sang mã lỗi của hợp đồng: giải trình, đột phá, rồi lỗi chung. */
  static void reject(List<SelfAssessmentIssue> issues) {
    if (issues.isEmpty()) {
      return;
    }
    List<ProblemFieldError> errors =
        issues.stream().map(i -> new ProblemFieldError(i.field(), i.message(), i.rule())).toList();
    SelfAssessmentIssue.Kind kind =
        issues.stream()
            .map(SelfAssessmentIssue::kind)
            .min(Enum::compareTo)
            .orElse(SelfAssessmentIssue.Kind.VALIDATION);
    String code =
        switch (kind) {
          case REASON -> "APPRAISAL-422-REASON-REQUIRED";
          case BREAKTHROUGH -> "APPRAISAL-422-BREAKTHROUGH-REQUIRED";
          case VALIDATION -> "COMMON-422-VALIDATION";
        };
    throw new BusinessException(code, issues.get(0).message(), errors);
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private <T> T read(String value, TypeReference<T> type) {
    try {
      return json.readValue(value, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
