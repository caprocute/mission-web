package vn.viettel.okrkpi.dossier.application.dossierpackage.port;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Tác vụ xuất gói ({@code dossier.package_export}, CORE-06-08). */
public interface PackageExportRepository {

  /** Một tác vụ xuất; {@code status} là tên {@code JobStatus}. */
  record ExportRow(
      UUID id,
      int periodBucket,
      UUID packageId,
      String status,
      String objectKey,
      Long sizeBytes,
      Integer fileCount,
      String errorCode,
      UUID requestedBy,
      Instant updatedAt,
      Instant completedAt) {}

  void insert(TenantId tenant, UUID id, int periodBucket, UUID packageId, UUID actor);

  Optional<ExportRow> find(TenantId tenant, UUID id);

  /**
   * Nhận chạy tác vụ: {@code CHO}, hoặc {@code DANG_CHAY} không cập nhật quá {@code stale} (pod cũ
   * đã dừng), sang {@code DANG_CHAY}. Trả {@code false} khi pod khác đang chạy hoặc đã xong.
   */
  boolean claim(TenantId tenant, UUID id, Duration stale);

  void complete(TenantId tenant, UUID id, String objectKey, long sizeBytes, int fileCount);

  void fail(TenantId tenant, UUID id, String errorCode);

  /** Tác vụ cần chạy lại: {@code CHO} quá {@code waiting}, {@code DANG_CHAY} quá {@code stale}. */
  List<UUID> stranded(TenantId tenant, Duration waiting, Duration stale, int limit);
}
