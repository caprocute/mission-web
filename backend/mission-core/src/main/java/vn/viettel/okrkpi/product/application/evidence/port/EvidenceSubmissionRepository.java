package vn.viettel.okrkpi.product.application.evidence.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Đọc và ghi sản phẩm, minh chứng, phiên bản, tệp của phiên bản và bản chiếu đối tượng SVC-08 cho
 * khai báo sản phẩm và nộp minh chứng (CORE-03-03, QT-07, SCR-0701).
 */
public interface EvidenceSubmissionRepository {

  /** Dòng {@code product.deliverable}. */
  record DeliverableView(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID taskId,
      String name,
      String deliverableType,
      String unit,
      BigDecimal quantityPlanned,
      BigDecimal quantityAccepted,
      String status,
      long rowVersion) {}

  /** Sản phẩm mới. */
  record NewDeliverable(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID taskId,
      String name,
      String deliverableType,
      String unit,
      BigDecimal quantityPlanned) {}

  /** Dòng {@code product.evidence} kèm thông tin phiên bản mới nhất. */
  record EvidenceView(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID deliverableId,
      UUID taskId,
      String title,
      String sourceType,
      String externalDocRef,
      Integer currentVersionNo,
      String currentStatus,
      boolean duplicateSuspect,
      UUID submittedBy,
      Instant submittedAt,
      long rowVersion) {}

  /** Minh chứng mới. */
  record NewEvidence(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID deliverableId,
      UUID taskId,
      String title,
      String sourceType,
      String externalDocRef) {}

  /** Bộ lọc danh sách minh chứng; trường {@code null} là không lọc. */
  record EvidenceFilter(
      UUID taskId,
      UUID deliverableId,
      Set<String> statuses,
      UUID submitter,
      UUID reviewer,
      UUID periodId) {}

  /** Dòng rút gọn của lịch sử phiên bản. */
  record VersionSummary(int versionNo, String status, Instant submittedAt, Instant acceptedAt) {}

  /** Dòng {@code product.evidence_version}. */
  record VersionView(
      UUID id,
      UUID evidenceId,
      UUID taskId,
      UUID periodId,
      int periodBucket,
      int versionNo,
      String status,
      String source,
      String sourceDocRef,
      String scanStatus,
      boolean duplicateSuspect,
      boolean hasE2ee,
      boolean aiPrefill,
      UUID aiSuggestionId,
      Instant userConfirmedAt,
      String docNumber,
      LocalDate docDate,
      String docSummary,
      String note,
      UUID submittedBy,
      Instant submittedAt,
      Instant attestedAt,
      Instant acceptedAt,
      UUID excludedPeriodId,
      long rowVersion) {}

  /** Trường của bản nháp ngoài phần do máy trạng thái ghi. */
  record DraftDetails(
      String source,
      String sourceDocRef,
      UUID aiSuggestionId,
      String docNumber,
      LocalDate docDate,
      String docSummary,
      Long sizeBytes,
      String mediaType,
      boolean hasE2ee,
      boolean duplicateSuspect,
      UUID duplicateOfId,
      String reuseJustification,
      List<String> aiFieldsEdited) {}

  /** Bản chiếu đối tượng SVC-08 ({@code product.evidence_object}). */
  record StoredObject(
      UUID id,
      int objectVersion,
      byte[] contentSha256,
      Long sizeBytes,
      String mediaType,
      boolean hasE2ee,
      String scanStatus,
      Instant storedAt) {

    /** Đã nhận sự kiện lưu của SVC-08. */
    public boolean stored() {
      return storedAt != null && contentSha256 != null;
    }
  }

  /** Tệp của phiên bản. */
  record FileRow(
      UUID objectId,
      int ordinal,
      int objectVersion,
      byte[] contentSha256,
      String scanStatus,
      UUID duplicateOfVersionId,
      String reuseJustification) {}

  /** Tệp kèm bản chiếu đối tượng hiện tại ({@code null} khi chưa có). */
  record FileView(FileRow file, StoredObject object) {}

  /** Phiên bản đã nộp của nhiệm vụ khác có tệp trùng mã băm. */
  record Duplicate(UUID versionId, UUID taskId, Instant submittedAt) {}

  /** Phiên bản nháp đang dùng một đối tượng. */
  record DraftRef(UUID versionId, int periodBucket) {}

  void insertDeliverable(TenantId tenant, NewDeliverable d, UUID actorId, Instant at);

  Optional<DeliverableView> findDeliverable(TenantId tenant, UUID id);

  List<DeliverableView> listDeliverables(TenantId tenant, UUID taskId, UUID afterId, int limit);

  void insertEvidence(TenantId tenant, NewEvidence e, UUID actorId, Instant at);

  Optional<EvidenceView> findEvidence(TenantId tenant, UUID id);

  /** Khoá dòng minh chứng để cấp số phiên bản kế tiếp tuần tự. */
  Optional<EvidenceView> lockEvidence(TenantId tenant, UUID id);

  List<EvidenceView> listEvidences(TenantId tenant, EvidenceFilter filter, UUID afterId, int limit);

  List<VersionSummary> versionSummaries(TenantId tenant, int periodBucket, UUID evidenceId);

  Optional<VersionView> latestVersion(TenantId tenant, int periodBucket, UUID evidenceId);

  Optional<VersionView> findVersion(
      TenantId tenant, int periodBucket, UUID evidenceId, int versionNo);

  /** Thêm phiên bản {@code NHAP} số {@code versionNo}. */
  void insertVersion(
      TenantId tenant,
      EvidenceView evidence,
      UUID versionId,
      int versionNo,
      String source,
      UUID actorId,
      Instant at);

  /** Ghi trường nháp (không tăng {@code row_version}; máy trạng thái tăng ở cùng lệnh). */
  void writeDraftDetails(TenantId tenant, int periodBucket, UUID versionId, DraftDetails details);

  /** Ô do AI điền mà người nộp đã sửa (CORE-03-05, BR-07-09); rỗng khi không có. */
  List<String> aiFieldsEdited(TenantId tenant, int periodBucket, UUID versionId);

  /** Thay toàn bộ tệp của phiên bản nháp. */
  void replaceFiles(
      TenantId tenant, VersionView version, List<FileRow> files, UUID actorId, Instant at);

  List<FileView> filesOf(TenantId tenant, int periodBucket, UUID versionId);

  Map<UUID, StoredObject> storedObjects(TenantId tenant, Collection<UUID> objectIds);

  /** Phiên bản đã nộp sớm nhất của nhiệm vụ khác có tệp cùng mã băm (BR-07-03). */
  Optional<Duplicate> firstDuplicate(TenantId tenant, byte[] contentSha256, UUID excludeTaskId);

  /** Nhiệm vụ và ngày nộp của một phiên bản (để hiển thị tệp trùng). */
  Optional<Duplicate> versionRef(TenantId tenant, UUID versionId);

  /** Ghi thời điểm cam kết của người nộp. */
  void markAttested(TenantId tenant, int periodBucket, UUID versionId, Instant at);

  /**
   * Minh chứng trong {@code ids} có phiên bản {@code DA_XAC_NHAN} chưa bị loại, do người này nộp.
   */
  Set<UUID> confirmedOf(TenantId tenant, UUID personId, Set<UUID> ids);

  /** Người đã ra quyết định nghiệm thu một phiên bản minh chứng do người này nộp trong kỳ. */
  Set<UUID> acceptorsOf(TenantId tenant, UUID periodId, UUID personId);

  /** Ghi người nghiệm thu được chỉ định lúc nộp, để lập hàng chờ nghiệm thu (CORE-03-06). */
  void markReviewer(TenantId tenant, int periodBucket, UUID evidenceId, UUID reviewerId);

  /**
   * Ghi bản chiếu khi nhận sự kiện lưu: thêm mới, hoặc cập nhật khi phiên bản đối tượng mới hơn hay
   * dòng chỉ mới có kết quả quét; sự kiện trùng hoặc cũ không ghi đè. Trả {@code true} khi đã ghi.
   */
  boolean upsertStoredObject(TenantId tenant, StoredObject object, UUID actorId, Instant at);

  /**
   * Ghi kết quả quét cho đúng phiên bản đối tượng; chưa có dòng thì thêm dòng chờ sự kiện lưu. Trả
   * {@code true} khi đã ghi.
   */
  boolean applyScanVerdict(
      TenantId tenant,
      UUID objectId,
      int objectVersion,
      String scanStatus,
      UUID actorId,
      Instant at);

  /** Chép kết quả quét của bản chiếu vào tệp của các phiên bản nháp dùng đối tượng. */
  List<DraftRef> syncDraftFileScan(TenantId tenant, UUID objectId);

  /** Ghi kết quả quét gộp của phiên bản nháp. */
  void writeDraftScan(TenantId tenant, int periodBucket, UUID versionId, String scanStatus);
}
