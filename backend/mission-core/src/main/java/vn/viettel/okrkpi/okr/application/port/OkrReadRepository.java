package vn.viettel.okrkpi.okr.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đọc schema {@code okr} cho SCR-0201 và {@code OkrApi} (CORE-01-02). Truy vấn theo kỳ luôn kèm
 * {@code period_bucket IN (...)} để chỉ chạm phân vùng của kỳ.
 */
public interface OkrReadRepository {

  /** Một dòng {@code okr.objective}. */
  record ObjectiveRow(
      UUID id,
      UUID periodId,
      int periodBucket,
      String ownerType,
      UUID ownerUnitId,
      UUID ownerPersonId,
      String code,
      String title,
      String description,
      UUID parentObjectiveId,
      int tier,
      String status,
      String overLimitReason,
      int versionNo,
      boolean aiAssisted,
      UUID aiSuggestionId,
      UUID approvedBy,
      Instant approvedAt,
      UUID createdBy,
      Instant createdAt,
      Instant updatedAt,
      long rowVersion) {}

  /** Một dòng {@code okr.key_result} kèm check-in {@code DA_GUI} gần nhất. */
  record KeyResultRow(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID objectiveId,
      String code,
      String title,
      String metricType,
      String unit,
      BigDecimal baselineValue,
      BigDecimal targetValue,
      BigDecimal currentValue,
      String direction,
      BigDecimal weight,
      LocalDate dueDate,
      UUID verifierPersonId,
      String dataSource,
      String confidence,
      String status,
      String riskFlag,
      String checkinFrequency,
      String contentSource,
      boolean aiAssisted,
      UUID aiSuggestionId,
      String formulaText,
      long rowVersion,
      LastCheckin lastCheckin) {}

  /** Check-in đã gửi gần nhất. */
  record LastCheckin(BigDecimal value, String confidence, Instant checkinAt, String blockerNote) {}

  /** Một dòng {@code okr.basis}. */
  record BasisRow(
      UUID id,
      String basisKind,
      String docNumber,
      String title,
      UUID issuedByUnitId,
      LocalDate issuedOn,
      String sourceDocRef,
      UUID parentBasisId,
      LocalDate validFrom,
      LocalDate validTo) {}

  /** Một dòng {@code okr.okr_link}. */
  record LinkRow(
      UUID id,
      UUID periodId,
      String fromType,
      UUID fromId,
      String toType,
      UUID toId,
      String linkKind,
      BigDecimal contributionWeight,
      long rowVersion) {}

  /** Bộ lọc mục tiêu; trường rỗng là không lọc. */
  record ObjectiveFilter(
      UUID periodId,
      List<Integer> buckets,
      Collection<UUID> unitIds,
      String ownerType,
      UUID ownerPersonId,
      Collection<String> statuses,
      String query) {}

  /** Bộ lọc KR; trường rỗng là không lọc. */
  record KeyResultFilter(
      UUID periodId,
      List<Integer> buckets,
      Collection<UUID> unitIds,
      UUID ownerPersonId,
      UUID objectiveId,
      String riskFlag,
      boolean approvedOnly,
      boolean needCheckin,
      String query) {}

  /** Mục tiêu sắp theo {@code (code, id)}, sau con trỏ {@code (afterCode, afterId)}. */
  List<ObjectiveRow> objectives(
      TenantId tenant, ObjectiveFilter filter, String afterCode, UUID afterId, int limit);

  long countObjectives(TenantId tenant, UUID periodId, List<Integer> buckets, UUID unitId);

  Optional<ObjectiveRow> objective(TenantId tenant, UUID id);

  List<ObjectiveRow> objectivesByIds(TenantId tenant, Collection<UUID> ids);

  /** KR của các mục tiêu, theo mã. */
  List<KeyResultRow> keyResultsOf(
      TenantId tenant, List<Integer> buckets, Collection<UUID> objectiveIds);

  /** KR sắp theo {@code (code, id)}. */
  List<KeyResultRow> keyResults(
      TenantId tenant, KeyResultFilter filter, String afterCode, UUID afterId, int limit);

  Optional<KeyResultRow> keyResult(TenantId tenant, UUID id);

  /** Số KR khớp bộ lọc, cho {@code counts.needCheckin} (CORE-01-08). */
  long countKeyResults(TenantId tenant, KeyResultFilter filter);

  List<KeyResultRow> keyResultsByIds(TenantId tenant, Collection<UUID> ids);

  /** Căn cứ của các mục tiêu: cặp (mục tiêu, căn cứ). */
  List<ObjectiveBasis> basesOf(
      TenantId tenant, List<Integer> buckets, Collection<UUID> objectiveIds);

  record ObjectiveBasis(UUID objectiveId, BasisRow basis) {}

  /** Căn cứ sắp theo {@code (title, id)}. */
  List<BasisRow> basesByIds(TenantId tenant, Collection<UUID> ids);

  List<BasisRow> bases(
      TenantId tenant,
      String kind,
      LocalDate validOn,
      String query,
      String afterTitle,
      UUID afterId,
      int limit);

  /** Liên kết chạm một trong các đầu {@code ids} (đầu nguồn hoặc đích). */
  List<LinkRow> linksTouching(TenantId tenant, Collection<UUID> ids);

  /** Liên kết theo bộ lọc, sắp theo {@code id}. */
  List<LinkRow> links(
      TenantId tenant,
      String fromType,
      UUID fromId,
      String toType,
      UUID toId,
      String linkKind,
      UUID periodId,
      UUID afterId,
      int limit);
}
