package vn.viettel.okrkpi.appraisal.application.annual.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.application.review.port.ReviewRepository.Scope;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Hồ sơ năm và {@code appraisal.annual_rating} trên {@code appraisalDataSource} (CORE-04-10). Kỳ
 * năm có {@code period_bucket = năm × 10}, quý {@code q} có {@code năm × 10 + q}.
 */
public interface AnnualRatingRepository {

  /** Cặp đơn vị và khối đối tượng của các hồ sơ quý IV một luồng, để tìm Policy Pack năm. */
  List<UnitGroup> unitGroups(TenantId tenant, int quarterFourBucket, String flow);

  /**
   * Lập hồ sơ năm {@code CHUA_LAP} và dòng {@code annual_rating} cho người có hồ sơ quý IV ở luồng
   * {@code flow} và đủ bốn quý (quý I–III đã quyết định), hoặc thuộc {@code special} (trường hợp
   * đặc thù PL 6) và có ít nhất một quý. Hồ sơ đã có được giữ nguyên (chạy lại an toàn).
   *
   * @param packs phiên bản Policy Pack năm theo đơn vị; đơn vị không có gói năm dùng gói quý IV
   * @return số hồ sơ năm mới
   */
  int createAnnual(
      TenantId tenant,
      UUID annualPeriodId,
      int year,
      String flow,
      Map<UUID, UUID> packs,
      Collection<UUID> special,
      UUID actor);

  /** Hồ sơ năm cùng người, cùng luồng của năm. */
  Optional<UUID> annualProfileOf(TenantId tenant, int year, UUID personId, String flow);

  /** Ảnh chụp hiện hành của các quý trong năm của người ở luồng, theo thứ tự quý (BR-10-06). */
  List<QuarterRef> quarters(TenantId tenant, int year, UUID personId, String flow);

  /** Ghi điểm năm và mức hệ thống từ ảnh chụp năm (EVT-10 kỳ năm). */
  void recordScore(
      TenantId tenant,
      int year,
      UUID profileId,
      BigDecimal annualScore,
      String systemLevel,
      UUID actor,
      Instant at);

  /** Một trang dòng năm theo bộ lọc, theo thứ tự {@code annual_rating.id}. */
  List<AnnualRow> page(TenantId tenant, Filter filter, int limit);

  /** Bốn số đếm thẻ của SCR-1001 trên cùng bộ lọc (không tính {@code flag}, con trỏ). */
  Counts counts(TenantId tenant, Filter filter);

  /** Một dòng năm theo mã {@code annual_rating.id}. */
  Optional<AnnualRow> find(TenantId tenant, UUID id);

  /**
   * Dòng năm khoá để lập đề xuất bố trí lại ({@code SELECT ... FOR UPDATE} trong giao dịch của bên
   * gọi, CORE-04-11), kèm người tham gia hồ sơ năm để kiểm quyền.
   */
  Optional<ProposalTarget> lockForProposal(TenantId tenant, UUID id);

  /**
   * Đặt {@code relocation_proposed = true} và tăng {@code row_version}.
   *
   * @return {@code row_version} mới
   */
  long markRelocationProposed(TenantId tenant, int periodBucket, UUID id, UUID actor);

  /**
   * Dòng năm có cờ bố trí lại vừa lập trong giao dịch hiện hành ({@code created_at = now()}) của
   * năm và luồng, kèm người có thẩm quyền của hồ sơ quý IV cùng người (MSG-10-02).
   */
  List<Flagged> flaggedInThisTransaction(TenantId tenant, int year, String flow);

  /** Dòng năm có cờ hai quý liên tiếp KHTNV và người nhận cảnh báo. */
  record Flagged(UUID annualProfileId, UUID personId, List<UUID> recipients) {}

  /** Dòng năm đã khoá cho đề xuất bố trí lại. */
  record ProposalTarget(
      UUID id,
      int periodBucket,
      UUID periodId,
      UUID profileId,
      UUID personId,
      UUID orgUnitId,
      String flow,
      boolean reassignFlag,
      boolean relocationProposed,
      long rowVersion,
      UUID appraiserId,
      UUID deciderId) {}

  record UnitGroup(UUID orgUnitId, UUID subjectGroupId) {}

  /** Một quý: hồ sơ, mức quyết định, ảnh chụp hiện hành (có thể thiếu). */
  record QuarterRef(
      int quarter,
      UUID periodId,
      UUID profileId,
      String status,
      String finalRating,
      UUID snapshotId,
      byte[] snapshotHash,
      BigDecimal totalScore,
      String policyVersion,
      UUID policyPackVersionId) {}

  /**
   * Bộ lọc danh sách năm; {@code unitId} lọc theo cây đơn vị của luồng (gồm đơn vị con), {@code
   * scope} là vị từ ABAC trên hồ sơ năm {@code p}, {@code after} là con trỏ {@code
   * annual_rating.id}.
   */
  record Filter(
      UUID periodId,
      UUID unitId,
      String flow,
      boolean linkageOnly,
      String q,
      Scope scope,
      UUID after) {}

  /** Một dòng {@code annual_rating} kèm hồ sơ năm và các quý. */
  record AnnualRow(
      UUID id,
      UUID profileId,
      UUID personId,
      String personName,
      UUID orgUnitId,
      String orgUnitName,
      String flow,
      String profileStatus,
      List<String> quarterRatings,
      BigDecimal annualScore,
      String selfProposedLevel,
      String systemLevel,
      String ratingCode,
      boolean hasKhtnvQuarter,
      boolean consecutiveKhtnv,
      String blockReason,
      boolean reassignFlag,
      boolean relocationProposed,
      long rowVersion,
      UUID periodId,
      int year,
      UUID appraiserId,
      UUID deciderId) {}

  record Counts(
      int profilesCreated,
      int profilesTotal,
      int blockedExcellent,
      int reassignProposed,
      int awaitingProspectGrouping) {}
}
