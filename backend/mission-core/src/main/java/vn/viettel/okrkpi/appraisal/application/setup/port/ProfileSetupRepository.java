package vn.viettel.okrkpi.appraisal.application.setup.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Ghi lô hồ sơ từ bản sao tổ chức và ghi ảnh chụp điểm trên {@code appraisalDataSource}. Chỉ {@code
 * ProfileSetupService} (consumer EVT-09, EVT-10) được dùng cổng này (BR-09-16, kiểm bằng ArchUnit).
 */
public interface ProfileSetupRepository {

  /** Cặp đơn vị và khối đối tượng của mọi phân công đang công tác có hiệu lực tại ngày. */
  List<UnitGroup> unitGroups(TenantId tenant, LocalDate validDate);

  /**
   * Lập hồ sơ {@code CHUA_LAP} cho một luồng, mỗi người một hồ sơ theo phân công chính tại ngày
   * khoá kỳ; hồ sơ đã có được giữ nguyên (chạy lại an toàn).
   *
   * @param packs phiên bản Policy Pack theo đơn vị; đơn vị không có gói thì không lập
   * @param excluded người không lập hồ sơ
   * @return số hồ sơ mới
   */
  int createProfiles(
      TenantId tenant,
      PeriodLock lock,
      String flow,
      Map<UUID, UUID> packs,
      Collection<UUID> excluded,
      UUID actor);

  /** Ảnh chụp hiện hành của hồ sơ. */
  Optional<SnapshotHead> currentSnapshot(TenantId tenant, int periodBucket, UUID profileId);

  /** Đã có ảnh chụp cho kết quả tính điểm này. */
  boolean hasResult(TenantId tenant, int periodBucket, UUID scoreResultId);

  /**
   * Thay ảnh chụp hai bước: đặt {@code superseded_by_id} của ảnh chụp cũ trỏ tới mã mới rồi chèn
   * ảnh chụp mới.
   */
  void insertSnapshot(TenantId tenant, NewSnapshot snapshot, UUID previousId, UUID actor);

  /** Trỏ {@code current_snapshot_id} của hồ sơ sang ảnh chụp mới, không đổi trạng thái. */
  void pointCurrent(
      TenantId tenant, int periodBucket, UUID profileId, UUID snapshotId, UUID actor, Instant at);

  record UnitGroup(UUID orgUnitId, UUID subjectGroupId) {}

  record PeriodLock(UUID periodId, int periodBucket, Instant lockedAt, LocalDate validDate) {
    public PeriodLock {
      Objects.requireNonNull(periodId, "periodId");
      Objects.requireNonNull(lockedAt, "lockedAt");
      Objects.requireNonNull(validDate, "validDate");
    }
  }

  record SnapshotHead(UUID id, UUID scoreResultId, Instant computedAt) {}

  /**
   * Ảnh chụp mới; {@code snapshotHash} là SHA-256 dạng JCS của các trường điểm, {@code detailJson}
   * ghi vào {@code detail_json} (ảnh chụp năm: các quý tham chiếu, BR-10-06), có thể {@code null}.
   */
  record NewSnapshot(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID profileId,
      String flow,
      String kind,
      UUID scoreResultId,
      UUID policyPackVersionId,
      byte[] policyContentHash,
      String policyVersion,
      byte[] inputHash,
      BigDecimal totalScore,
      String autoRatingCode,
      Instant computedAt,
      byte[] snapshotHash,
      String detailJson) {}
}
