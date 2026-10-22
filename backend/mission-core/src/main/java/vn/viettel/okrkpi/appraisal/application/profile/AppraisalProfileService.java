package vn.viettel.okrkpi.appraisal.application.profile;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalStepEvents;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalProfile;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalRuleViolation;
import vn.viettel.okrkpi.appraisal.domain.profile.CurrentRating;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.ProfileCommand;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingRecorded;
import vn.viettel.okrkpi.appraisal.domain.profile.StepCompleted;
import vn.viettel.okrkpi.policy.api.PolicyQueryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lệnh của máy trạng thái hồ sơ đánh giá trong một giao dịch {@code appraisalTransactionManager}
 * (CORE-04-03): khoá dòng, so {@code row_version} với {@code If-Match}, chạy aggregate, ghi hồ sơ,
 * mức mới (không sửa mức cũ) và EVT-11 vào outbox. Mọi API của CORE-04-04 trở đi gọi qua lớp này.
 * Vi phạm quy tắc được đổi sang mã lỗi của {@code appraisals.yaml}.
 */
public class AppraisalProfileService {

  static final String TX = "appraisalTransactionManager";

  /** Khoá tham số số lần trả lại tối đa trong Policy Pack (suy luận, TL-02b chỉ ghi "tham số"). */
  public static final String MAX_RETURNS_PARAM = "so_lan_tra_lai_toi_da";

  static final int DEFAULT_MAX_RETURNS = 2;

  private final AppraisalProfileRepository profiles;
  private final AppraisalStepEvents events;
  private final PolicyQueryApi policies;
  private final Clock clock;

  public AppraisalProfileService(
      AppraisalProfileRepository profiles,
      AppraisalStepEvents events,
      PolicyQueryApi policies,
      Clock clock) {
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.events = Objects.requireNonNull(events, "events");
    this.policies = policies;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Lệnh chuyển trạng thái chạy trên aggregate đã nạp. */
  @FunctionalInterface
  public interface Command {
    StepCompleted apply(AppraisalProfile profile, ProfileRow row);
  }

  /** Kết quả chuyển: bước đã chấp nhận và phiên bản hồ sơ mới. */
  public record Transitioned(StepCompleted step, long rowVersion) {}

  /**
   * Chạy một lệnh chuyển trạng thái.
   *
   * @param expectedVersion {@code row_version} người gọi đã đọc ({@code If-Match}); {@code null}
   *     với lệnh từ sự kiện (EVT-10, EVT-18, EVT-25)
   */
  @Transactional(TX)
  public Transitioned transition(
      TenantId tenant, UUID profileId, Long expectedVersion, UUID actorId, Command command) {
    ProfileRow row = load(tenant, profileId, expectedVersion);
    AppraisalProfile profile = row.toAggregate();
    StepCompleted step = run(() -> command.apply(profile, row));
    Instant now = clock.instant();
    if (step.command() == ProfileCommand.SKIP_PROPOSAL) {
      row.currentRatings().stream()
          .filter(r -> r.levelKind() == LevelKind.DE_XUAT)
          .forEach(
              r ->
                  profiles.supersedeRating(
                      tenant, row.periodBucket(), r.proposalId(), actorId, now));
    }
    long version = save(tenant, row, profile, actorId, now);
    events.stepCompleted(tenant, profileId, step, version);
    return new Transitioned(step, version);
  }

  /** Trả lại bổ sung với số lần tối đa lấy từ Policy Pack của hồ sơ (mặc định 2). */
  @Transactional(TX)
  public Transitioned returnForRevision(
      TenantId tenant, UUID profileId, Long expectedVersion, UUID actorId, String reason) {
    return transition(
        tenant,
        profileId,
        expectedVersion,
        actorId,
        (p, row) -> p.returnForRevision(actorId, reason, maxReturns(tenant, row)));
  }

  /**
   * Ghi mức của một bước: đặt dòng hiện hành cũ {@code is_current = false} rồi chèn dòng mới gắn
   * ảnh chụp điểm hiện hành. Trả dòng mức mới (có cờ {@code explanationRequired}).
   */
  @Transactional(TX)
  public CurrentRating recordRating(
      TenantId tenant,
      UUID profileId,
      Long expectedVersion,
      LevelKind kind,
      RatingLevel rating,
      UUID actorId,
      String actorRole,
      SealedText reason,
      SealedText comment) {
    return recordRating(
        tenant,
        profileId,
        expectedVersion,
        kind,
        rating,
        actorId,
        actorRole,
        reason,
        comment,
        UUID.randomUUID());
  }

  /**
   * Như {@link #recordRating(TenantId, UUID, Long, LevelKind, RatingLevel, UUID, String,
   * SealedText, SealedText)} với mã dòng mức do bên gọi cấp, để bên gọi mã hoá lý do với AAD của
   * đúng dòng {@code rating_proposal} (CORE-04-04).
   */
  @Transactional(TX)
  public CurrentRating recordRating(
      TenantId tenant,
      UUID profileId,
      Long expectedVersion,
      LevelKind kind,
      RatingLevel rating,
      UUID actorId,
      String actorRole,
      SealedText reason,
      SealedText comment,
      UUID proposalId) {
    return recordRating(
        tenant,
        profileId,
        expectedVersion,
        kind,
        rating,
        actorId,
        actorRole,
        reason,
        comment,
        proposalId,
        null);
  }

  /** Như trên, gắn dòng mức {@code QUYET_DINH} vào lô quyết định (CORE-04-07). */
  @Transactional(TX)
  public CurrentRating recordRating(
      TenantId tenant,
      UUID profileId,
      Long expectedVersion,
      LevelKind kind,
      RatingLevel rating,
      UUID actorId,
      String actorRole,
      SealedText reason,
      SealedText comment,
      UUID proposalId,
      UUID batchDecisionId) {
    ProfileRow row = load(tenant, profileId, expectedVersion);
    if (row.currentSnapshotId() == null) {
      throw new BusinessException("APPRAISAL-409-NOT-COMPUTED", "Hồ sơ chưa có kết quả tính điểm");
    }
    AppraisalProfile profile = row.toAggregate();
    RatingRecorded recorded =
        run(
            () ->
                profile.recordRating(proposalId, kind, rating, actorId, actorRole, reason != null));
    Instant now = clock.instant();
    if (recorded.supersededId() != null) {
      profiles.supersedeRating(tenant, row.periodBucket(), recorded.supersededId(), actorId, now);
    }
    profiles.insertRating(
        tenant, row, row.currentSnapshotId(), recorded, reason, comment, now, batchDecisionId);
    return recorded.rating();
  }

  /** Ghi giải trình cho mức hiện hành của bước (BR-09-12). */
  @Transactional(TX)
  public UUID recordExplanation(
      TenantId tenant,
      UUID profileId,
      LevelKind kind,
      String explanationType,
      String autoText,
      String legalRef,
      SealedText explanation,
      UUID actorId) {
    ProfileRow row = load(tenant, profileId, null);
    AppraisalProfile profile = row.toAggregate();
    run(
        () -> {
          profile.recordExplanation(kind);
          return null;
        });
    CurrentRating current = profile.currentRating(kind).orElseThrow();
    UUID id = UUID.randomUUID();
    profiles.insertExplanation(
        tenant,
        row,
        id,
        current.proposalId(),
        explanationType,
        autoText,
        legalRef,
        explanation,
        actorId,
        clock.instant());
    return id;
  }

  @Transactional(transactionManager = TX, readOnly = true)
  public Optional<ProfileRow> find(TenantId tenant, UUID profileId) {
    return profiles.lock(tenant, profileId);
  }

  // ---------------------------------------------------------------- hỗ trợ

  private ProfileRow load(TenantId tenant, UUID profileId, Long expectedVersion) {
    ProfileRow row = profiles.lock(tenant, profileId).orElseThrow(NoSuchElementException::new);
    if (expectedVersion != null && expectedVersion != row.rowVersion()) {
      throw new VersionConflictException("APPRAISAL", expectedVersion, row.rowVersion());
    }
    return row;
  }

  private long save(
      TenantId tenant, ProfileRow row, AppraisalProfile profile, UUID actor, Instant at) {
    return profiles
        .save(tenant, row, profile, actor, at)
        .orElseThrow(() -> new VersionConflictException("APPRAISAL", row.rowVersion(), null));
  }

  int maxReturns(TenantId tenant, ProfileRow row) {
    if (policies == null || row.policyPackVersionId() == null) {
      return DEFAULT_MAX_RETURNS;
    }
    return policies
        .parameter(tenant, row.policyPackVersionId(), MAX_RETURNS_PARAM)
        .filter(Number.class::isInstance)
        .map(v -> ((Number) v).intValue())
        .orElse(DEFAULT_MAX_RETURNS);
  }

  public static <T> T run(Supplier<T> action) {
    try {
      return action.get();
    } catch (AppraisalRuleViolation v) {
      throw toProblem(v);
    }
  }

  /** Ánh xạ mã từ chối của aggregate sang danh mục lỗi của hợp đồng {@code appraisals.yaml}. */
  public static BusinessException toProblem(AppraisalRuleViolation v) {
    String code =
        switch (v.code()) {
          case TRANSITION_NOT_ALLOWED, PROFILE_LOCKED -> "APPRAISAL-409-INVALID-STATUS";
          case REASON_REQUIRED, EXPLANATION_REQUIRED -> "APPRAISAL-422-REASON-REQUIRED";
          case RATING_REQUIRED -> "COMMON-422-VALIDATION";
          case RETURN_LIMIT_REACHED -> "APPRAISAL-409-RETURN-LIMIT";
          case SEPARATION_OF_DUTIES -> "APPRAISAL-403-SEPARATION-OF-DUTIES";
          case ROLE_NOT_ALLOWED -> "COMMON-403-OUT-OF-SCOPE";
          case SNAPSHOT_REQUIRED -> "APPRAISAL-409-NOT-COMPUTED";
        };
    return new BusinessException(code, v.getMessage());
  }
}
