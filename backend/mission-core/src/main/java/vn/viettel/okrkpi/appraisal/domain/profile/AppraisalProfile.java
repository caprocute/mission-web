package vn.viettel.okrkpi.appraisal.domain.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.domain.profile.AppraisalRuleViolation.Code;

/**
 * Aggregate hồ sơ đánh giá (CORE-04-03): một người, một kỳ, một luồng. Mỗi lệnh ứng với một dòng
 * bảng chuyển trạng thái TL-02b QT-09; lệnh được chấp nhận đổi trạng thái và ghi một {@link
 * StepCompleted} để tầng ứng dụng phát EVT-11. Mức xếp loại không bao giờ sửa tại chỗ: {@link
 * #recordRating} sinh dòng mới và chỉ ra dòng cũ bị thay (TL-03 {@code rating_proposal}).
 *
 * <p>Quy tắc nền tảng viết bằng mã: BR-09-12 (mức khác mức liền kề thì phải có lý do và giải trình
 * trước khi chuyển bước), BR-09-14 phần dữ liệu trong hồ sơ (người thẩm định, người quyết định khác
 * người được đánh giá và khác nhau), và không chuỗi lệnh nào đưa hồ sơ ra khỏi {@code DA_KHOA}.
 */
public final class AppraisalProfile {

  private final UUID id;
  private final UUID personId;
  private AppraisalStatus status;
  private UUID appraiserId;
  private UUID deciderId;
  private UUID currentSnapshotId;
  private RatingLevel autoRating;
  private RatingLevel finalRating;
  private int returnCount;
  private final Map<LevelKind, CurrentRating> ratings = new EnumMap<>(LevelKind.class);
  private final List<StepCompleted> steps = new ArrayList<>();

  private AppraisalProfile(UUID id, UUID personId, AppraisalStatus status) {
    this.id = Objects.requireNonNull(id, "id");
    this.personId = Objects.requireNonNull(personId, "personId");
    this.status = Objects.requireNonNull(status, "status");
  }

  /** Hồ sơ mới lập từ EVT-09 ({@code CHUA_LAP}). */
  public static AppraisalProfile open(UUID id, UUID personId) {
    return new AppraisalProfile(id, personId, AppraisalStatus.CHUA_LAP);
  }

  /** Dựng lại từ dữ liệu đã lưu. */
  public static AppraisalProfile restore(
      UUID id,
      UUID personId,
      AppraisalStatus status,
      UUID appraiserId,
      UUID deciderId,
      UUID currentSnapshotId,
      RatingLevel autoRating,
      RatingLevel finalRating,
      int returnCount,
      List<CurrentRating> currentRatings) {
    AppraisalProfile p = new AppraisalProfile(id, personId, status);
    p.appraiserId = appraiserId;
    p.deciderId = deciderId;
    p.currentSnapshotId = currentSnapshotId;
    p.autoRating = autoRating;
    p.finalRating = finalRating;
    p.returnCount = returnCount;
    for (CurrentRating r : currentRatings) {
      p.ratings.put(r.levelKind(), r);
    }
    return p;
  }

  // ---------------------------------------------------------------- lệnh chuyển trạng thái

  /** EVT-10: có ảnh chụp điểm hợp lệ thì mở bước tự đánh giá. */
  public StepCompleted openSelfAssessment(UUID snapshotId, RatingLevel autoRating, UUID actorId) {
    if (snapshotId == null || autoRating == null) {
      throw new AppraisalRuleViolation(
          Code.SNAPSHOT_REQUIRED, "Chưa có kết quả tính điểm hợp lệ cho hồ sơ");
    }
    StepCompleted step = transition(ProfileCommand.OPEN_SELF_ASSESSMENT, actorId, null);
    this.currentSnapshotId = snapshotId;
    this.autoRating = autoRating;
    return step;
  }

  /**
   * EVT-10 khi hồ sơ đã mở: thay ảnh chụp hiện hành (tạm tính lại), không đổi trạng thái. Mức tự đề
   * xuất đã ghi vẫn giữ nguyên căn cứ cũ.
   */
  public void replaceSnapshot(UUID snapshotId, RatingLevel autoRating) {
    ensureNotLocked();
    this.currentSnapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
    this.autoRating = Objects.requireNonNull(autoRating, "autoRating");
  }

  /** Phiếu tự đánh giá đã ký và nộp; tự đề xuất khác mức tham khảo thì phải giải trình. */
  public StepCompleted markSubmitted(UUID actorId) {
    checkCommand(ProfileCommand.SUBMIT);
    requireActor(actorId, personId, "Chỉ người được đánh giá nộp phiếu tự đánh giá");
    requireExplained(LevelKind.TU_DE_XUAT, false);
    return transition(ProfileCommand.SUBMIT, actorId, null);
  }

  /**
   * Quá hạn tự đánh giá (QT-09 ngoại lệ 3, timer của luồng duyệt): chuyển thẩm định với mức tự đề
   * xuất trống, không tự điền mức, không đòi giải trình.
   */
  public StepCompleted skipSelfAssessment(UUID actorId) {
    checkCommand(ProfileCommand.SUBMIT);
    ratings.remove(LevelKind.TU_DE_XUAT);
    return transition(ProfileCommand.SUBMIT, actorId, null);
  }

  /** Chỉ chủ hồ sơ sửa phiếu tự đánh giá, khi hồ sơ ở {@code TU_DANH_GIA} (CORE-04-04). */
  public void ensureSelfAssessmentEditable(UUID actorId) {
    ensureNotLocked();
    if (status != AppraisalStatus.TU_DANH_GIA) {
      throw new AppraisalRuleViolation(
          Code.TRANSITION_NOT_ALLOWED,
          "Phiếu tự đánh giá chỉ sửa được khi hồ sơ ở bước tự đánh giá, hiện ở " + status);
    }
    requireActor(actorId, personId, "Chỉ người được đánh giá lập phiếu tự đánh giá");
  }

  /** Trả lại bổ sung: có lý do, số lần trả lại chưa vượt {@code maxReturns}. */
  public StepCompleted returnForRevision(UUID actorId, String reason, int maxReturns) {
    checkCommand(ProfileCommand.RETURN_FOR_REVISION);
    requireReason(reason);
    if (returnCount >= maxReturns) {
      throw new AppraisalRuleViolation(
          Code.RETURN_LIMIT_REACHED, "Hồ sơ đã bị trả lại tối đa " + maxReturns + " lần");
    }
    StepCompleted step = transition(ProfileCommand.RETURN_FOR_REVISION, actorId, reason);
    returnCount++;
    return step;
  }

  /** Kết quả thẩm định đã ký: cần mức thẩm định đủ giải trình. */
  public StepCompleted markAppraised(UUID actorId) {
    checkCommand(ProfileCommand.APPRAISE);
    requireSeparation(actorId);
    requireExplained(LevelKind.THAM_DINH, true);
    StepCompleted step = transition(ProfileCommand.APPRAISE, actorId, null);
    this.appraiserId = actorId;
    return step;
  }

  /** Đề xuất mức (cấp 3). */
  public StepCompleted propose(UUID actorId) {
    checkCommand(ProfileCommand.PROPOSE);
    requireExplained(LevelKind.DE_XUAT, true);
    return transition(ProfileCommand.PROPOSE, actorId, null);
  }

  /** Bỏ qua bước đề xuất theo điều kiện của mẫu luồng; xoá mức đề xuất cũ khỏi chuỗi liền kề. */
  public StepCompleted skipProposal(UUID actorId) {
    StepCompleted step = transition(ProfileCommand.SKIP_PROPOSAL, actorId, null);
    ratings.remove(LevelKind.DE_XUAT);
    return step;
  }

  /** Người quyết định yêu cầu xem lại, có lý do. */
  public StepCompleted requestReview(UUID actorId, String reason) {
    checkCommand(ProfileCommand.REQUEST_REVIEW);
    requireReason(reason);
    return transition(ProfileCommand.REQUEST_REVIEW, actorId, reason);
  }

  /** Quyết định xếp loại: người quyết định khác người thẩm định và người được đánh giá. */
  public StepCompleted decide(UUID actorId) {
    checkCommand(ProfileCommand.DECIDE);
    requireSeparation(actorId);
    if (appraiserId != null && appraiserId.equals(actorId)) {
      throw new AppraisalRuleViolation(
          Code.SEPARATION_OF_DUTIES, "Người quyết định phải khác người thẩm định");
    }
    CurrentRating decided = requireExplained(LevelKind.QUYET_DINH, true);
    StepCompleted step = transition(ProfileCommand.DECIDE, actorId, null);
    this.deciderId = actorId;
    this.finalRating = decided.rating();
    return step;
  }

  /** EVT-18 của quyết định. */
  public StepCompleted markSigned(UUID actorId) {
    return transition(ProfileCommand.MARK_SIGNED, actorId, null);
  }

  /**
   * Hồ sơ năm về bước quyết định vì ảnh chụp năm tính lại cho mức khác (thi hành kết luận hậu
   * kiểm); mức quyết định cũ không còn hiện hành.
   */
  public StepCompleted reopenDecision(UUID actorId, String reason) {
    checkCommand(ProfileCommand.REOPEN_DECISION);
    requireReason(reason);
    StepCompleted step = transition(ProfileCommand.REOPEN_DECISION, actorId, reason);
    ratings.remove(LevelKind.QUYET_DINH);
    this.finalRating = null;
    return step;
  }

  /** EVT-25: khiếu nại trong thời hạn. */
  public StepCompleted fileComplaint(UUID actorId) {
    return transition(ProfileCommand.FILE_COMPLAINT, actorId, null);
  }

  /**
   * Kết luận khiếu nại: giữ nguyên về {@code DA_KY_SO}, điều chỉnh thì khoá (thi hành qua QT-13).
   */
  public StepCompleted concludeComplaint(UUID actorId, boolean adjusted, String reason) {
    ProfileCommand command =
        adjusted
            ? ProfileCommand.CONCLUDE_COMPLAINT_ADJUST
            : ProfileCommand.CONCLUDE_COMPLAINT_KEEP;
    checkCommand(command);
    requireReason(reason);
    return transition(command, actorId, reason);
  }

  /** Hết hạn khiếu nại hoặc khoá gói. */
  public StepCompleted lock(UUID actorId) {
    return transition(ProfileCommand.LOCK, actorId, null);
  }

  // ---------------------------------------------------------------- mức xếp loại

  /**
   * Ghi mức của một bước (BR-09-12): chỉ ở trạng thái của bước đó, đúng vai trò; tính {@code
   * explanation_required} khi khác mức liền kề trước (mức tham khảo với tự đề xuất).
   */
  public RatingRecorded recordRating(
      UUID proposalId,
      LevelKind kind,
      RatingLevel rating,
      UUID actorId,
      String actorRole,
      boolean hasReason) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(rating, "rating");
    ensureNotLocked();
    if (status != kind.recordableIn()) {
      throw new AppraisalRuleViolation(
          Code.TRANSITION_NOT_ALLOWED,
          "Không ghi được mức " + kind + " khi hồ sơ ở trạng thái " + status);
    }
    if (!kind.roles().contains(actorRole)) {
      throw new AppraisalRuleViolation(
          Code.ROLE_NOT_ALLOWED, "Vai trò không được đưa ra mức ở bước này");
    }
    if (kind == LevelKind.TU_DE_XUAT) {
      requireActor(actorId, personId, "Chỉ người được đánh giá tự đề xuất mức");
    } else {
      requireSeparation(actorId);
    }
    Optional<RatingLevel> previous = previousRating(kind);
    boolean differs = previous.isPresent() && previous.get() != rating;
    CurrentRating current =
        new CurrentRating(proposalId, kind, rating, actorId, differs, hasReason, false);
    CurrentRating old = ratings.put(kind, current);
    return new RatingRecorded(current, old == null ? null : old.proposalId(), differs, actorRole);
  }

  /** Đã có dòng {@code rating_explanation} cho mức hiện hành của bước. */
  public void recordExplanation(LevelKind kind) {
    ensureNotLocked();
    CurrentRating r = ratings.get(kind);
    if (r == null) {
      throw new AppraisalRuleViolation(Code.RATING_REQUIRED, "Chưa có mức để giải trình");
    }
    ratings.put(kind, r.withExplanation());
  }

  /** Mức liền kề trước: tham khảo, tự đề xuất, thẩm định, đề xuất (nếu không bỏ qua). */
  Optional<RatingLevel> previousRating(LevelKind kind) {
    return switch (kind) {
      case TU_DE_XUAT -> Optional.ofNullable(autoRating);
      case THAM_DINH -> rating(LevelKind.TU_DE_XUAT).or(() -> Optional.ofNullable(autoRating));
      case DE_XUAT -> rating(LevelKind.THAM_DINH);
      case QUYET_DINH -> rating(LevelKind.DE_XUAT).or(() -> rating(LevelKind.THAM_DINH));
    };
  }

  private Optional<RatingLevel> rating(LevelKind kind) {
    return Optional.ofNullable(ratings.get(kind)).map(CurrentRating::rating);
  }

  // ---------------------------------------------------------------- kiểm tra

  private StepCompleted transition(ProfileCommand command, UUID actorId, String reason) {
    Objects.requireNonNull(actorId, "actorId");
    checkCommand(command);
    AppraisalStatus from = status;
    status = command.to();
    StepCompleted step = new StepCompleted(command, from, status, command.stage(), actorId, reason);
    steps.add(step);
    return step;
  }

  private void checkCommand(ProfileCommand command) {
    ensureNotLocked();
    if (!command.from().contains(status)) {
      throw new AppraisalRuleViolation(
          Code.TRANSITION_NOT_ALLOWED,
          "Không thực hiện được thao tác " + command + " khi hồ sơ ở trạng thái " + status);
    }
  }

  private void ensureNotLocked() {
    if (status == AppraisalStatus.DA_KHOA) {
      throw new AppraisalRuleViolation(Code.PROFILE_LOCKED, "Hồ sơ đánh giá đã khoá");
    }
  }

  private CurrentRating requireExplained(LevelKind kind, boolean required) {
    CurrentRating r = ratings.get(kind);
    if (r == null) {
      if (required) {
        throw new AppraisalRuleViolation(Code.RATING_REQUIRED, "Chưa có mức xếp loại của bước");
      }
      return null;
    }
    if (!r.explained()) {
      throw new AppraisalRuleViolation(
          Code.EXPLANATION_REQUIRED,
          "Mức khác mức của bước liền trước, cần nêu lý do và giải trình (BR-09-12)");
    }
    return r;
  }

  private void requireSeparation(UUID actorId) {
    if (personId.equals(actorId)) {
      throw new AppraisalRuleViolation(
          Code.SEPARATION_OF_DUTIES, "Người được đánh giá không tự thẩm định, đề xuất, quyết định");
    }
  }

  private static void requireActor(UUID actorId, UUID expected, String message) {
    if (!expected.equals(actorId)) {
      throw new AppraisalRuleViolation(Code.SEPARATION_OF_DUTIES, message);
    }
  }

  private static void requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new AppraisalRuleViolation(Code.REASON_REQUIRED, "Thao tác này bắt buộc nêu lý do");
    }
  }

  // ---------------------------------------------------------------- đọc

  public UUID id() {
    return id;
  }

  public UUID personId() {
    return personId;
  }

  public AppraisalStatus status() {
    return status;
  }

  public UUID appraiserId() {
    return appraiserId;
  }

  public UUID deciderId() {
    return deciderId;
  }

  public UUID currentSnapshotId() {
    return currentSnapshotId;
  }

  public Optional<RatingLevel> autoRating() {
    return Optional.ofNullable(autoRating);
  }

  public Optional<RatingLevel> finalRating() {
    return Optional.ofNullable(finalRating);
  }

  public int returnCount() {
    return returnCount;
  }

  public Optional<CurrentRating> currentRating(LevelKind kind) {
    return Optional.ofNullable(ratings.get(kind));
  }

  /** Các bước đã chấp nhận kể từ khi nạp, theo thứ tự. */
  public List<StepCompleted> steps() {
    return Collections.unmodifiableList(steps);
  }
}
