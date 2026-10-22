package vn.viettel.okrkpi.dossier.application.specialcase.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Lưu trữ {@code dossier.special_case} (TL-03), phân vùng theo {@code period_bucket}. */
public interface SpecialCaseRepository {

  /** Một dòng; {@code detailCipher} là {@code detail_enc} (C2). */
  record SpecialCaseRow(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID personId,
      String caseCode,
      String detectedBy,
      byte[] detailCipher,
      UUID dekId,
      String effect,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      String legalRef,
      UUID confirmedBy,
      Instant confirmedAt,
      long rowVersion) {

    public SpecialCaseRow withVersion(long version) {
      return new SpecialCaseRow(
          id,
          periodId,
          periodBucket,
          personId,
          caseCode,
          detectedBy,
          detailCipher,
          dekId,
          effect,
          effectiveFrom,
          effectiveTo,
          legalRef,
          confirmedBy,
          confirmedAt,
          version);
    }
  }

  /**
   * Bộ lọc danh sách: kỳ và đơn vị tổ chức hội nghị; người thuộc phạm vi là người được kiểm điểm
   * hoặc thành phần của hội nghị kiểm điểm của đơn vị trong kỳ. {@code personId} khác rỗng khi gói
   * hồ sơ thuộc một cá nhân.
   */
  record Filter(UUID periodId, int periodBucket, UUID orgUnitId, UUID personId) {}

  /** Số trường hợp theo mã. */
  record GroupCount(String caseCode, String legalRef, long count) {}

  /** Đơn vị, kỳ và chủ hồ sơ (nếu có) của một gói hồ sơ. */
  record PackageScope(UUID periodId, int periodBucket, UUID orgUnitId, UUID personId) {}

  /**
   * Khoá tư vấn theo giao dịch cho bộ (kỳ, người) để hai lần phát hiện song song (tạo hội nghị,
   * EVT-15, lập hồ sơ) không cùng ghi một trường hợp.
   */
  void lockPerson(TenantId tenant, UUID periodId, UUID personId);

  List<SpecialCaseRow> listForPerson(TenantId tenant, int bucket, UUID periodId, UUID personId);

  void insert(TenantId tenant, SpecialCaseRow row, UUID actor);

  /** Ghi đè các cột có thể đổi; trả phiên bản mới, 0 khi phiên bản đã đổi. */
  long update(TenantId tenant, SpecialCaseRow row, long expectedVersion, UUID actor);

  Optional<SpecialCaseRow> find(TenantId tenant, UUID id);

  /** Đọc và khoá dòng ({@code FOR UPDATE}). */
  Optional<SpecialCaseRow> lock(TenantId tenant, UUID id);

  List<SpecialCaseRow> list(TenantId tenant, Filter filter, UUID after, int limit);

  List<GroupCount> groups(TenantId tenant, Filter filter);

  Optional<PackageScope> packageScope(TenantId tenant, UUID packageId);

  /** Đơn vị tổ chức các hội nghị kiểm điểm trong kỳ có người {@code personId}. */
  List<UUID> meetingUnitsOf(TenantId tenant, int bucket, UUID periodId, UUID personId);
}
