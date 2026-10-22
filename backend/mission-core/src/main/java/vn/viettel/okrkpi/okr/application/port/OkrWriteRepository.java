package vn.viettel.okrkpi.okr.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.okr.application.port.OkrReadRepository.LinkRow;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Cổng ghi của MOD-01 cho SCR-0202 (CORE-01-03) trên {@code okr.objective}, {@code okr.key_result},
 * {@code okr.objective_basis}, {@code okr.okr_link}. Mọi lệnh chạy trong giao dịch {@code
 * okrTransactionManager} do tầng ứng dụng mở.
 */
public interface OkrWriteRepository {

  /** Phần trạng thái mục tiêu cần cho lệnh ghi, đọc kèm khoá dòng {@code FOR UPDATE}. */
  record ObjectiveHead(
      UUID id,
      UUID periodId,
      int periodBucket,
      String ownerType,
      UUID ownerUnitId,
      UUID ownerPersonId,
      String code,
      UUID parentObjectiveId,
      String status,
      long rowVersion) {}

  /** Dòng ghi của mục tiêu. */
  record ObjectiveData(
      String title,
      String description,
      int tier,
      UUID parentObjectiveId,
      String overLimitReason,
      UUID aiSuggestionId) {}

  /** Dòng ghi của KR; {@code id} rỗng khi thêm mới. */
  record KeyResultData(
      UUID id,
      String title,
      String metricType,
      String unit,
      BigDecimal baselineValue,
      BigDecimal targetValue,
      String direction,
      BigDecimal weight,
      LocalDate dueDate,
      UUID verifierPersonId,
      String dataSource,
      String formulaText,
      String checkinFrequency,
      String contentSource,
      UUID aiSuggestionId) {}

  /** Nội dung chữ của mục tiêu nguồn khi sao chép kỳ trước. */
  record ObjectiveText(String title, String description, int tier) {}

  ObjectiveText objectiveText(TenantId tenant, int bucket, UUID objectiveId);

  /** Khoá tuần tự sinh mã {@code O{n}} theo (tenant, đơn vị, kỳ), giữ tới hết giao dịch. */
  void lockObjectiveCodes(TenantId tenant, UUID unitId, UUID periodId);

  /** Số {@code n} lớn nhất của mã {@code O{n}} trong đơn vị và kỳ, 0 khi chưa có. */
  int maxObjectiveNo(TenantId tenant, int bucket, UUID unitId, UUID periodId);

  /** Số {@code n} lớn nhất của mã {@code KR {o}.{n}} trong mục tiêu, 0 khi chưa có. */
  int maxKeyResultNo(TenantId tenant, int bucket, UUID objectiveId);

  /** Số mục tiêu của chủ thể trong kỳ (đơn vị hoặc cá nhân), không tính {@code exceptId}. */
  long countOwnerObjectives(
      TenantId tenant, UUID periodId, UUID unitId, UUID personId, UUID exceptId);

  void insertObjective(
      TenantId tenant,
      UUID id,
      UUID periodId,
      int bucket,
      String ownerType,
      UUID ownerUnitId,
      UUID ownerPersonId,
      String code,
      ObjectiveData data,
      UUID actor);

  /** Ghi đè phần sửa được và tăng {@code row_version}; trả phiên bản mới. */
  long updateObjective(TenantId tenant, ObjectiveHead head, ObjectiveData data, UUID actor);

  /** Tăng {@code row_version} của mục tiêu khi KR của nó đổi qua lệnh riêng. */
  long touchObjective(TenantId tenant, ObjectiveHead head, UUID actor);

  Optional<ObjectiveHead> lockObjective(TenantId tenant, UUID id);

  Optional<ObjectiveHead> findObjective(TenantId tenant, UUID id);

  /** Mục tiêu sở hữu KR, rỗng khi không có KR. */
  Optional<UUID> objectiveOfKeyResult(TenantId tenant, UUID keyResultId);

  List<UUID> objectivesOf(TenantId tenant, UUID periodId, UUID unitId, Collection<UUID> onlyIds);

  /** Mã và id các KR hiện có của mục tiêu. */
  List<UUID> keyResultIds(TenantId tenant, int bucket, UUID objectiveId);

  void insertKeyResult(
      TenantId tenant,
      UUID id,
      UUID periodId,
      int bucket,
      UUID objectiveId,
      String code,
      KeyResultData data,
      UUID actor);

  void updateKeyResult(TenantId tenant, int bucket, KeyResultData data, UUID actor);

  void deleteKeyResults(TenantId tenant, int bucket, Collection<UUID> ids);

  List<KeyResultData> keyResultsOf(TenantId tenant, int bucket, UUID objectiveId);

  List<UUID> basisIdsOf(TenantId tenant, int bucket, UUID objectiveId);

  /** Thay toàn bộ căn cứ của mục tiêu (bảng nối và liên kết {@code CAN_CU}). */
  void replaceBases(
      TenantId tenant,
      int bucket,
      UUID periodId,
      UUID objectiveId,
      List<UUID> basisIds,
      UUID actor);

  /** Đặt liên kết {@code PHAN_RA} từ mục tiêu cấp trên tới mục tiêu, bỏ liên kết cũ. */
  void replaceParentLink(
      TenantId tenant, UUID periodId, UUID objectiveId, UUID parentObjectiveId, UUID actor);

  /** Căn cứ có trong thư viện của tenant trong số {@code ids}. */
  List<UUID> existingBases(TenantId tenant, Collection<UUID> ids);

  boolean linkExists(TenantId tenant, String fromType, UUID fromId, String toType, UUID toId);

  void insertLink(
      TenantId tenant,
      UUID id,
      UUID periodId,
      String fromType,
      UUID fromId,
      String toType,
      UUID toId,
      String linkKind,
      BigDecimal contributionWeight,
      UUID actor);

  Optional<LinkRow> findLink(TenantId tenant, UUID id);

  void deleteLink(TenantId tenant, UUID id);

  /** Xoá mọi liên kết chạm các đối tượng (khi xoá KR). */
  void deleteLinksTouching(TenantId tenant, Collection<UUID> ids);
}
