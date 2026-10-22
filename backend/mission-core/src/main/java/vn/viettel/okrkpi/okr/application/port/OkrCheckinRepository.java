package vn.viettel.okrkpi.okr.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.okr.domain.ProgressCalculator.Weighted;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng lưu trữ check-in KR của MOD-01 (CORE-01-08, QT-03): đọc và khoá KR cần check-in, ghi thêm
 * {@code okr.kr_checkin}, cập nhật giá trị hiện tại và tiến độ của {@code okr.key_result}, {@code
 * okr.objective}. Bảng check-in chỉ ghi thêm (TL-03): không có thao tác sửa nội dung hay xoá.
 */
public interface OkrCheckinRepository {

  /** KR kèm mục tiêu chứa nó, đủ để kiểm BR-03-01 và quyền check-in. */
  record CheckinTarget(
      UUID keyResultId,
      UUID periodId,
      int periodBucket,
      String code,
      String metricType,
      String direction,
      BigDecimal baselineValue,
      BigDecimal targetValue,
      BigDecimal currentValue,
      BigDecimal progress,
      String confidence,
      String riskFlag,
      long rowVersion,
      UUID objectiveId,
      String objectiveCode,
      String objectiveStatus,
      BigDecimal objectiveProgress,
      UUID ownerUnitId,
      UUID ownerPersonId,
      UUID objectiveCreatedBy) {

    /** Chủ sở hữu KR: người sở hữu mục tiêu cá nhân, mục tiêu tập thể thì người soạn (suy luận). */
    public UUID owner() {
      return ownerPersonId != null ? ownerPersonId : objectiveCreatedBy;
    }
  }

  /** Một dòng {@code okr.kr_checkin}. */
  record CheckinRow(
      UUID id,
      UUID keyResultId,
      UUID periodId,
      Instant checkinAt,
      BigDecimal value,
      String confidence,
      String highlights,
      String blockerNote,
      String channel,
      String status,
      BigDecimal sourceValue,
      Instant sourceFetchedAt,
      String overrideReason,
      UUID verifiedBy,
      Instant verifiedAt,
      UUID createdBy,
      String idempotencyKey) {}

  /** Check-in mới cần ghi. */
  record NewCheckin(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID keyResultId,
      BigDecimal value,
      String confidence,
      String highlights,
      String blockerNote,
      String channel,
      String status,
      String idempotencyKey,
      BigDecimal sourceValue,
      Instant sourceFetchedAt,
      String overrideReason) {}

  Optional<CheckinTarget> target(TenantId tenant, UUID keyResultId);

  /** Khoá dòng mục tiêu rồi dòng KR ({@code FOR UPDATE}) theo cùng thứ tự để tính lại tiến độ. */
  Optional<CheckinTarget> lockTarget(
      TenantId tenant, int bucket, UUID objectiveId, UUID keyResultId);

  Optional<CheckinRow> lastSubmitted(TenantId tenant, int bucket, UUID keyResultId);

  /**
   * Bản nháp hiệu lực của người tạo: bản {@code NHAP} mới nhất sau lần gửi gần nhất của người đó.
   */
  Optional<CheckinRow> draft(TenantId tenant, int bucket, UUID keyResultId, UUID creator);

  Optional<CheckinRow> byIdempotencyKey(TenantId tenant, int bucket, String key);

  Optional<CheckinRow> byId(TenantId tenant, int bucket, UUID id);

  void insert(TenantId tenant, NewCheckin checkin, UUID actor);

  /** Ghi giá trị hiện tại, mức tự tin, tiến độ, cờ rủi ro KR khi {@code row_version} còn đúng. */
  boolean writeKeyResultProgress(
      TenantId tenant,
      int bucket,
      UUID keyResultId,
      long expectedVersion,
      BigDecimal currentValue,
      String confidence,
      BigDecimal progress,
      String riskFlag,
      UUID actor);

  List<Weighted> objectiveWeights(TenantId tenant, int bucket, UUID objectiveId);

  void writeObjectiveProgress(
      TenantId tenant, int bucket, UUID objectiveId, BigDecimal progress, UUID actor);

  /**
   * Check-in đã gửi của KR, mới nhất trước, kèm bản nháp {@code includeDraftId} nếu có; sau con trỏ
   * {@code (afterAt, afterId)}.
   */
  List<CheckinRow> history(
      TenantId tenant,
      int bucket,
      UUID keyResultId,
      UUID includeDraftId,
      Instant afterAt,
      UUID afterId,
      int limit);
}
