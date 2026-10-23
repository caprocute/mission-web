package vn.viettel.okrkpi.appraisal.application.review.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.ProfileRow;
import vn.viettel.okrkpi.appraisal.application.profile.port.AppraisalProfileRepository.SealedText;
import vn.viettel.okrkpi.appraisal.application.selfassessment.port.SelfAssessmentRepository.ProfileSummary;
import vn.viettel.okrkpi.appraisal.domain.profile.LevelKind;
import vn.viettel.okrkpi.appraisal.domain.profile.RatingLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.CriteriaLevel;
import vn.viettel.okrkpi.appraisal.domain.selfassessment.SelfAssessmentForm;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Hàng đợi thẩm định và các dòng mức có ký của bước thẩm định ({@code appraisal} schema). */
public interface ReviewRepository {

  /**
   * Hồ sơ trong phạm vi {@code scope} khớp bộ lọc, sắp theo mã tăng dần sau con trỏ {@code after}.
   */
  List<QueueRow> queue(TenantId tenant, QueueFilter filter, Scope scope, UUID after, int limit);

  /** Mức hiện hành theo bước của các hồ sơ. */
  Map<UUID, Map<LevelKind, RatingLevel>> currentLevels(TenantId tenant, Set<UUID> profileIds);

  /** Thay tiêu chí chung giai đoạn {@code THAM_DINH}. */
  void replaceAppraisalCriteria(
      TenantId tenant,
      ProfileRow profile,
      List<SelfAssessmentForm.Criterion> criteria,
      BigDecimal ratio,
      CriteriaLevel level,
      UUID actor,
      Instant at);

  /** Tiêu chí chung tự nhận ({@code TU_DANH_GIA}) để chép sang bước thẩm định khi không sửa. */
  List<SelfAssessmentForm.Criterion> selfCriteria(
      TenantId tenant, int periodBucket, UUID profileId);

  /** Mức thẩm định hiện hành của hồ sơ cùng tình trạng ký. */
  Optional<AppraisalSign> currentAppraisal(TenantId tenant, int periodBucket, UUID profileId);

  /** Mức thẩm định có yêu cầu ký {@code signRequestId}. */
  Optional<AppraisalSign> appraisalBySignRequest(TenantId tenant, UUID signRequestId);

  /** Gắn yêu cầu ký SVC-10 vào dòng mức thẩm định. */
  void attachSignRequest(
      TenantId tenant, int periodBucket, UUID proposalId, UUID signRequestId, Instant at);

  /** Ghi thời điểm ký của dòng mức (EVT-18). */
  void markSigned(TenantId tenant, int periodBucket, UUID proposalId, Instant signedAt, Instant at);

  /** Bộ lọc của {@code listAppraisalProfiles}. */
  record QueueFilter(
      UUID periodId,
      String flow,
      UUID unitId,
      boolean includeSubunits,
      Set<String> statuses,
      UUID subjectGroupId,
      String q) {}

  /** Điều kiện phạm vi ABAC đã dịch sang SQL có tham số (bí danh bảng hồ sơ là {@code p}). */
  record Scope(String sql, List<Object> params) {

    public static final Scope NONE = new Scope("1 = 0", List.of());

    public Scope {
      params = List.copyOf(params);
    }
  }

  /** Một dòng hàng đợi: tóm tắt hồ sơ, họ tên và tên đơn vị từ bản sao tổ chức. */
  record QueueRow(
      ProfileSummary profile, String personName, String unitName, BigDecimal totalScore) {

    /** Dòng chưa có điểm. */
    public QueueRow(ProfileSummary profile, String personName, String unitName) {
      this(profile, personName, unitName, null);
    }
  }

  /** Dòng mức thẩm định có ký: văn bản C2 ở dạng mã hoá. */
  record AppraisalSign(
      UUID proposalId,
      UUID profileId,
      int periodBucket,
      RatingLevel rating,
      UUID actorId,
      boolean current,
      SealedText reason,
      SealedText comment,
      UUID signRequestId,
      Instant signedAt) {

    /** Đã gắn yêu cầu ký và chưa ký. */
    public boolean pending() {
      return signRequestId != null && signedAt == null;
    }
  }
}
