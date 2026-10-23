package vn.viettel.okrkpi.dossier.application.dossierpackage;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.FormRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.PackageRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageEvents;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageExportRepository;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageExportRepository.ExportRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageFiles;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.FormStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Xuất gói hồ sơ thành tệp ZIP kèm manifest (CORE-06-08, {@code exportDossierPackage}, SCR-1102
 * "Tải gói"): gói từ {@code CHO_KY} trở đi (hợp đồng), tác vụ ghi {@code dossier.package_export}
 * trong giao dịch riêng đã commit rồi mới giao cho luồng chạy nền (mẫu "chốt trước, gọi sau", wiki
 * trang 14), ZIP ghép theo luồng từ kho đối tượng lên kho đối tượng ({@link PackageArchive}), kết
 * quả là URL tải ký có hạn cấp mỗi lần đọc tác vụ. Yêu cầu xuất ghi sổ cái.
 *
 * <p>Tác vụ chỉ một pod chạy (nhận bằng cập nhật có điều kiện); pod dừng giữa chừng thì {@link
 * #sweep} giao chạy lại tác vụ chờ quá lâu hoặc đang chạy mà không cập nhật.
 */
public class DossierExportService {

  static final Set<PackageStatus> EXPORTABLE =
      EnumSet.of(
          PackageStatus.CHO_KY,
          PackageStatus.DA_KY,
          PackageStatus.DA_NOP,
          PackageStatus.DA_LUU_TRU);
  static final Set<FormStatus> RENDERED = EnumSet.of(FormStatus.DA_SINH, FormStatus.DA_KY);
  static final String CONTENT_TYPE = "application/zip";
  static final String ERROR_HASH = "HASH_MISMATCH";
  static final String ERROR_STORAGE = "STORAGE";
  static final String ERROR_MISSING = "PACKAGE_MISSING";

  private static final Logger LOG = LoggerFactory.getLogger(DossierExportService.class);
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private final DossierPackageRepository packages;
  private final PackageExportRepository exports;
  private final PackageFiles files;
  private final PackageEvents events;
  private final TransactionOperations committed;
  private final Executor runner;
  private final Clock clock;
  private final Duration urlTtl;
  private final Duration stale;

  public DossierExportService(
      DossierPackageRepository packages,
      PackageExportRepository exports,
      PackageFiles files,
      PackageEvents events,
      TransactionOperations committed,
      Executor runner,
      Clock clock,
      Duration urlTtl,
      Duration stale) {
    this.packages = Objects.requireNonNull(packages, "packages");
    this.exports = Objects.requireNonNull(exports, "exports");
    this.files = Objects.requireNonNull(files, "files");
    this.events = Objects.requireNonNull(events, "events");
    this.committed = Objects.requireNonNull(committed, "committed");
    this.runner = Objects.requireNonNull(runner, "runner");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.urlTtl = Objects.requireNonNull(urlTtl, "urlTtl");
    this.stale = Objects.requireNonNull(stale, "stale");
  }

  /** Tác vụ xuất kèm URL tải khi xong. */
  public record ExportJob(ExportRow row, String downloadUrl) {}

  /**
   * {@code exportDossierPackage}: gói chưa tới {@code CHO_KY} trả {@code
   * DOSSIER-409-PACKAGE-NOT-READY}.
   */
  public ExportJob request(TenantId tenant, UUID actor, UUID actorUnit, UUID packageId) {
    PackageRow pkg = packages.find(tenant, packageId).orElseThrow(NoSuchElementException::new);
    checkScope(pkg, actorUnit);
    if (!EXPORTABLE.contains(pkg.status())) {
      throw new BusinessException(
          "DOSSIER-409-PACKAGE-NOT-READY",
          "Gói hồ sơ chưa sinh đủ biểu mẫu nên chưa tải được. Vui lòng thử lại sau khi gói chờ ký.");
    }
    UUID id = Ids.newUuidV7();
    committed.execute(
        st -> {
          exports.insert(tenant, id, pkg.periodBucket(), pkg.id(), actor);
          events.exported(tenant, pkg, id, actor);
          return null;
        });
    dispatch(tenant, id);
    return job(tenant, id).orElseThrow();
  }

  /** Giao tác vụ cho luồng nền; hàng đợi đầy thì để {@link #sweep} giao lại. */
  public void dispatch(TenantId tenant, UUID id) {
    try {
      runner.execute(() -> TenantContext.run(tenant, () -> run(tenant, id)));
    } catch (RejectedExecutionException e) {
      LOG.warn("Hàng đợi xuất gói đầy, tác vụ {} chạy ở lượt quét sau", id);
    }
  }

  /**
   * Chạy tác vụ xuất nếu nhận được.
   *
   * @return {@code true} khi pod này đã chạy (xong hoặc lỗi)
   */
  public boolean run(TenantId tenant, UUID id) {
    if (!exports.claim(tenant, id, stale)) {
      return false;
    }
    ExportRow row = exports.find(tenant, id).orElseThrow();
    PackageRow pkg = packages.find(tenant, row.packageId()).orElse(null);
    if (pkg == null) {
      exports.fail(tenant, id, ERROR_MISSING);
      return true;
    }
    List<FormRow> forms =
        packages.formsIn(tenant, pkg.periodBucket(), pkg.id(), RENDERED).stream()
            .filter(f -> f.objectKey() != null)
            .sorted(
                java.util.Comparator.comparing(FormRow::formCode)
                    .thenComparing(f -> f.id().toString()))
            .toList();
    String manifest = manifest(tenant, pkg, forms);
    String signature =
        pkg.status().locked()
            ? packages.signatureKey(tenant, pkg.periodBucket(), pkg.id()).orElse(null)
            : null;
    String key = objectKey(tenant, pkg, id);
    PackageFiles.WritableFile out = null;
    try {
      out = files.create(tenant, key, CONTENT_TYPE);
      PackageArchive.Result result =
          PackageArchive.write(
              files, tenant, out, manifest, info(pkg, signature != null), signature, forms);
      out.close();
      exports.complete(tenant, id, key, result.bytes(), result.files());
      LOG.info(
          "Đã xuất gói {} thành ZIP {} byte, {} tệp", pkg.code(), result.bytes(), result.files());
    } catch (PackageArchive.HashMismatchException e) {
      abort(out);
      LOG.warn("Xuất gói {} dừng: {}", pkg.code(), e.getMessage());
      exports.fail(tenant, id, ERROR_HASH);
    } catch (IOException | RuntimeException e) {
      abort(out);
      LOG.warn("Không xuất được gói {}", pkg.code(), e);
      exports.fail(tenant, id, ERROR_STORAGE);
    }
    return true;
  }

  /** Tác vụ xuất; URL tải có hạn cấp mới mỗi lần đọc khi đã xong. */
  public Optional<ExportJob> job(TenantId tenant, UUID id) {
    return exports
        .find(tenant, id)
        .map(
            r -> {
              String url = null;
              if ("HOAN_TAT".equals(r.status()) && r.objectKey() != null) {
                url = files.downloadUrl(tenant, r.objectKey(), fileName(r.objectKey()), urlTtl);
              }
              return new ExportJob(r, url);
            });
  }

  /** Giao lại tác vụ bị bỏ dở của tenant; trả số tác vụ đã giao. */
  public int sweep(TenantId tenant, int limit) {
    List<UUID> ids = exports.stranded(tenant, Duration.ofMinutes(1), stale, limit);
    ids.forEach(id -> dispatch(tenant, id));
    return ids.size();
  }

  // ---------------------------------------------------------------- hỗ trợ

  /** Manifest đã chốt khi đã ký; gói chờ ký chưa chốt thì lập tức thời từ biểu mẫu đã sinh. */
  private String manifest(TenantId tenant, PackageRow pkg, List<FormRow> forms) {
    String frozen = packages.manifest(tenant, pkg.periodBucket(), pkg.id());
    if (frozen != null && !frozen.isBlank() && !"[]".equals(frozen.strip())) {
      return frozen;
    }
    return PackageManifest.of(forms.stream().filter(f -> f.pdfSha256() != null).toList())
        .canonicalJson();
  }

  private String info(PackageRow pkg, boolean signed) {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("maGoi", pkg.code());
    info.put("phienBan", pkg.versionNo());
    info.put("daKy", signed);
    info.put(
        "sha256Manifest",
        pkg.manifestHash() == null ? null : HexFormat.of().formatHex(pkg.manifestHash()));
    info.put("thoiDiemNop", pkg.submittedAt() == null ? null : pkg.submittedAt().toString());
    info.put("thoiDiemXuat", clock.instant().toString());
    try {
      return JSON.writeValueAsString(info);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  static String objectKey(TenantId tenant, PackageRow pkg, UUID exportId) {
    return "xuat-goi/"
        + tenant.value()
        + "/"
        + pkg.code()
        + "-v"
        + pkg.versionNo()
        + "/"
        + exportId
        + ".zip";
  }

  private static String fileName(String objectKey) {
    String[] parts = objectKey.split("/");
    return parts.length >= 2 ? parts[parts.length - 2] + ".zip" : "goi-ho-so.zip";
  }

  private static void abort(PackageFiles.WritableFile out) {
    if (out != null) {
      out.abort();
    }
  }

  private static void checkScope(PackageRow r, UUID actorUnit) {
    if (actorUnit == null || !actorUnit.equals(r.orgUnitId())) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Đồng chí không có quyền với gói hồ sơ này.");
    }
  }
}
