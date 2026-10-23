package vn.viettel.okrkpi.dossier.application.dossierpackage.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.FormStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageKind;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.RenderProgress;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Lưu trữ {@code dossier.dossier_package} và {@code dossier.form_instance} của gói hồ sơ số
 * (CORE-06-06). Mọi câu lệnh đi theo khoá phân vùng {@code period_bucket} khi đã biết.
 */
public interface DossierPackageRepository {

  /** Một gói hồ sơ. */
  record PackageRow(
      UUID id,
      UUID periodId,
      int periodBucket,
      String code,
      UUID orgUnitId,
      UUID personId,
      PackageKind kind,
      int versionNo,
      PackageStatus status,
      LocalDate deadline,
      Instant submittedAt,
      String transferredTo,
      UUID signRequestId,
      byte[] manifestHash,
      byte[] packageSha256,
      UUID updatedBy,
      long rowVersion) {}

  /** Một biểu mẫu của gói. {@code renderJobId} là mã lệnh sinh khi chờ, mã tác vụ khi đã sinh. */
  record FormRow(
      UUID id,
      UUID periodId,
      int periodBucket,
      UUID packageId,
      UUID profileId,
      String formCode,
      String templateVersion,
      byte[] dataSnapshotHash,
      UUID renderJobId,
      String objectKey,
      byte[] pdfSha256,
      FormStatus status,
      Instant signedAt) {}

  /**
   * Biểu mẫu mới ở {@code CHO_SINH}. Biểu mẫu của đơn vị ({@code unitLevel}) chỉ có một bản cho mỗi
   * mã mẫu trong gói, gắn với hồ sơ đại diện; biểu mẫu cá nhân có một bản cho mỗi hồ sơ và mã mẫu.
   */
  record NewForm(
      UUID id,
      UUID profileId,
      String formCode,
      String templateVersion,
      byte[] dataSnapshotHash,
      boolean unitLevel) {}

  /** Lệnh lập gói. */
  record NewPackage(
      UUID periodId, int periodBucket, UUID orgUnitId, PackageKind kind, LocalDate deadline) {}

  /** Kết quả bảo đảm có gói: {@code created} khi vừa lập. */
  record Ensured(PackageRow row, boolean created) {}

  /** Bộ lọc danh sách gói; trường {@code null} là không lọc. */
  record Filter(UUID periodId, UUID orgUnitId, PackageKind kind, PackageStatus status) {}

  Optional<PackageRow> find(TenantId tenant, UUID id);

  /** Khoá dòng gói tới hết giao dịch (đổi trạng thái gói). */
  Optional<PackageRow> lock(TenantId tenant, int periodBucket, UUID id);

  /** Phiên bản mới nhất của gói đơn vị, kỳ, loại. */
  Optional<PackageRow> findByUnit(
      TenantId tenant, int periodBucket, UUID orgUnitId, PackageKind kind);

  /**
   * Gói của đơn vị, kỳ, loại; chưa có thì lập ở {@code DANG_LAP} với mã {@code DP-yyyy-{Q/N}-nnnn}
   * kế tiếp trong tenant. Hai lời gọi đồng thời cho cùng đơn vị chỉ lập một gói.
   */
  Ensured ensure(TenantId tenant, NewPackage cmd, UUID id, UUID actor);

  List<PackageRow> list(TenantId tenant, Filter filter, UUID after, int limit);

  /** Gói đang sinh của tenant theo mã gói, cho tác vụ theo dõi. */
  List<PackageRow> generating(TenantId tenant, UUID after, int limit);

  /**
   * Đổi trạng thái; {@code expectedVersion} {@code null} là không kiểm. Trả phiên bản mới, 0 khi
   * lệch.
   */
  long updateStatus(
      TenantId tenant, PackageRow row, PackageStatus next, Long expectedVersion, UUID actor);

  RenderProgress progress(TenantId tenant, int periodBucket, UUID packageId);

  /** Số hồ sơ có biểu mẫu trong gói. */
  int profileCount(TenantId tenant, int periodBucket, UUID packageId);

  Optional<FormRow> findForm(TenantId tenant, UUID formId);

  List<FormRow> forms(
      TenantId tenant,
      int periodBucket,
      UUID packageId,
      FormStatus status,
      String formCode,
      UUID after,
      int limit);

  /** Biểu mẫu của một hồ sơ trong gói. */
  List<FormRow> formsOfProfile(TenantId tenant, int periodBucket, UUID packageId, UUID profileId);

  /** Biểu mẫu của gói ở các trạng thái cho trước (rỗng là mọi trạng thái). */
  List<FormRow> formsIn(
      TenantId tenant, int periodBucket, UUID packageId, Collection<FormStatus> statuses);

  /** Thêm biểu mẫu chưa có (trùng theo {@link NewForm} thì bỏ qua). Trả số dòng đã thêm. */
  int insertForms(TenantId tenant, PackageRow pkg, List<NewForm> forms, UUID actor);

  /**
   * Ghi lại băm dữ liệu và phiên bản mẫu của biểu mẫu chưa sinh ({@code CHO_SINH}, {@code LOI}).
   */
  int refresh(TenantId tenant, int periodBucket, UUID formId, byte[] hash, String templateVersion);

  /** Gắn mã lệnh sinh cho biểu mẫu {@code CHO_SINH} chưa có lệnh. */
  int markRequested(TenantId tenant, int periodBucket, UUID packageId, UUID requestId);

  /** EVT-17: biểu mẫu chưa sinh chuyển {@code DA_SINH}; đã sinh thì không đổi (trả 0). */
  int markGenerated(
      TenantId tenant,
      int periodBucket,
      UUID formId,
      UUID renderJobId,
      String objectKey,
      byte[] pdfSha256,
      UUID actor);

  /**
   * Biểu mẫu {@code CHO_SINH} sang {@code LOI}: của một lệnh sinh khi {@code requestId} khác {@code
   * null}, ngược lại mọi biểu mẫu chờ của gói.
   */
  int markFailed(TenantId tenant, int periodBucket, UUID packageId, UUID requestId, UUID actor);

  /**
   * Biểu mẫu {@code LOI} (lọc theo {@code formIds} khi khác rỗng) về {@code CHO_SINH}, bỏ lệnh cũ.
   */
  int resetFailed(
      TenantId tenant, int periodBucket, UUID packageId, Collection<UUID> formIds, UUID actor);

  /** Mã lệnh sinh còn biểu mẫu chờ. */
  List<UUID> pendingRequests(TenantId tenant, int periodBucket, UUID packageId);

  /**
   * Người có trường hợp đặc thù đã kết luận trong kỳ (không xếp loại, xếp loại ở đơn vị cũ, bảo
   * lưu): coi như đã xử lý khi xét đủ điều kiện sinh biểu mẫu chính thức.
   */
  Set<UUID> settledSpecialCases(TenantId tenant, int periodBucket, UUID periodId);

  // ---------------------------------------------------------------- ký gói (CORE-06-07)

  /** Manifest đã chốt của gói (mảng JSON, {@code []} khi chưa chốt). */
  String manifest(TenantId tenant, int periodBucket, UUID packageId);

  /**
   * Chốt manifest và băm của gói {@code CHO_KY} đúng phiên bản, bỏ yêu cầu ký cũ; {@code
   * updated_by} là người yêu cầu ký. Trả phiên bản mới, 0 khi lệch.
   */
  long saveManifest(
      TenantId tenant,
      PackageRow row,
      String manifestJson,
      byte[] manifestHash,
      long expectedVersion,
      UUID actor);

  /** Gắn mã yêu cầu ký của SVC-10 cho gói (không tăng phiên bản). */
  int attachSignRequest(TenantId tenant, int periodBucket, UUID packageId, UUID signRequestId);

  /** Gói của yêu cầu ký. */
  Optional<PackageRow> findBySignRequest(TenantId tenant, UUID signRequestId);

  /**
   * Gói {@code DA_KY} sang {@code DA_NOP} với thời điểm nộp và băm gói (gói sau đó bị khoá). Trả
   * phiên bản mới.
   */
  long submit(
      TenantId tenant, PackageRow row, Instant submittedAt, byte[] packageSha256, UUID actor);

  /** Phiên bản kế tiếp của gói ở {@code DANG_LAP}: cùng đơn vị, kỳ, loại, mã và hạn nộp. */
  PackageRow newVersion(TenantId tenant, PackageRow previous, UUID id, UUID actor);
}
