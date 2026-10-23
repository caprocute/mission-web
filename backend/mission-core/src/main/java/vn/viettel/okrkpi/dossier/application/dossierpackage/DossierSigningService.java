package vn.viettel.okrkpi.dossier.application.dossierpackage;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi.UnitProfile;
import vn.viettel.okrkpi.config.api.TenantSettingApi;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.Filter;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.FormRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.PackageRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.FileHashes;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageEvents;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageSigner;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.Checklist;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.Checklist.Item;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.FormStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * Ký và khoá gói hồ sơ số (CORE-06-07, QT-11 bước 12–13, SCR-1102): danh sách kiểm tra BR-11-09,
 * chốt manifest SHA-256, yêu cầu ký mục đích {@code GOI_HO_SO} ở SVC-10, nhận EVT-18 để chuyển
 * {@code DA_KY} rồi {@code DA_NOP} (gói bị khoá, BR-11-10), và lập phiên bản gói mới khi kỳ mở lại
 * (EVT-22) mà không sửa gói cũ.
 */
public class DossierSigningService {

  /** Tham số tenant cho phép nộp gói khi còn hồ sơ đang khiếu nại (QT-11 "Ngoại lệ", suy luận). */
  public static final String COMPLAINT_SETTING = "ho_so_so.nop_kem_khieu_nai";

  static final Set<String> SIGNED = Set.of("DA_KY_SO", "DA_KHOA");
  static final String COMPLAINT = "KHIEU_NAI";

  /** Biểu mẫu của tập thể (một bản cho gói); các mẫu còn lại là biểu mẫu cá nhân. */
  static final Set<String> UNIT_FORMS = Set.of("MAU_1A", "MAU_2", "MAU_3");

  static final String HEAD_FORM = "MAU_3";
  static final String PAGE_SCOPE_ALL = "TOAN_KY";
  static final String PAGE_SCOPE_UNIT = "DON_VI";
  static final String PAGE_SCOPE_PROFILE = "HO_SO";

  private static final Logger LOG = LoggerFactory.getLogger(DossierSigningService.class);
  private static final Set<FormStatus> RENDERED = Set.of(FormStatus.DA_SINH, FormStatus.DA_KY);
  private static final int PAGE = 200;

  private final DossierPackageRepository packages;
  private final Supplier<DossierProfilesApi> profiles;
  private final Supplier<PackageSigner> signer;
  private final FileHashes hashes;
  private final PackageEvents events;
  private final Supplier<TenantSettingApi> settings;
  private final TransactionOperations tx;
  private final TransactionOperations committed;
  private final Clock clock;

  public DossierSigningService(
      DossierPackageRepository packages,
      Supplier<DossierProfilesApi> profiles,
      Supplier<PackageSigner> signer,
      FileHashes hashes,
      PackageEvents events,
      Supplier<TenantSettingApi> settings,
      TransactionOperations tx,
      TransactionOperations committed,
      Clock clock) {
    this.packages = Objects.requireNonNull(packages, "packages");
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.signer = Objects.requireNonNull(signer, "signer");
    this.hashes = Objects.requireNonNull(hashes, "hashes");
    this.events = Objects.requireNonNull(events, "events");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.tx = Objects.requireNonNull(tx, "tx");
    this.committed = Objects.requireNonNull(committed, "committed");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Yêu cầu ký đã tạo ở SVC-10. */
  public record SignStarted(UUID signRequestId, PackageRow row) {}

  /**
   * EVT-18 của chữ ký gói; {@code signedObjectKeys} cùng thứ tự với {@code hashes} (phần tử {@code
   * null} khi SVC-10 không trả tệp đã ký).
   */
  public record Signed(
      UUID signRequestId,
      UUID packageId,
      UUID signer,
      List<byte[]> hashes,
      Instant signedAt,
      List<String> signedObjectKeys) {

    public Signed {
      signedObjectKeys =
          signedObjectKeys == null
              ? List.of()
              : java.util.Collections.unmodifiableList(new ArrayList<>(signedObjectKeys));
    }

    public Signed(
        UUID signRequestId, UUID packageId, UUID signer, List<byte[]> hashes, Instant signedAt) {
      this(signRequestId, packageId, signer, hashes, signedAt, List.of());
    }

    /** Khoá tệp đã ký của phần tử có băm {@code hash}. */
    String signedObjectKey(byte[] hash) {
      for (int i = 0; i < hashes.size() && i < signedObjectKeys.size(); i++) {
        if (Arrays.equals(hashes.get(i), hash)) {
          return signedObjectKeys.get(i);
        }
      }
      return null;
    }
  }

  /** EVT-22: kỳ mở lại hậu kiểm theo phạm vi. */
  public record Reopened(
      UUID periodId, String scope, List<UUID> scopeIds, UUID actor, UUID reopenRequestId) {

    public Reopened(UUID periodId, String scope, List<UUID> scopeIds, UUID actor) {
      this(periodId, scope, scopeIds, actor, null);
    }
  }

  /** Tài liệu cần ký trả SVC-10: gói chờ ký có manifest đã chốt, người ký là người yêu cầu. */
  public record SignSource(boolean ready, Set<UUID> signers, byte[] manifestHash) {}

  /** Trạng thái tác vụ ký gói. */
  public record SignJob(
      UUID packageId, UUID requester, PackageStatus status, Instant submittedAt) {}

  /** SVC-10 chưa cấu hình: lớp REST trả 503. */
  public static final class SigningUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SigningUnavailableException() {
      super("Chưa ký số được, vui lòng thử lại sau.");
    }
  }

  // ---------------------------------------------------------------- danh sách kiểm tra

  /** {@code getDossierPackageChecklist}: tính tức thời theo BR-11-09. */
  public Checklist checklist(TenantId tenant, UUID actorUnit, UUID id) {
    PackageRow pkg = packages.find(tenant, id).orElseThrow(NoSuchElementException::new);
    checkScope(pkg, actorUnit);
    return evaluate(tenant, pkg);
  }

  Checklist evaluate(TenantId tenant, PackageRow pkg) {
    DossierProfilesApi api = api();
    Set<UUID> settled = packages.settledSpecialCases(tenant, pkg.periodBucket(), pkg.periodId());
    List<UnitProfile> relevant =
        api.unitProfiles(tenant, pkg.periodId(), pkg.orgUnitId()).stream()
            .filter(p -> !settled.contains(p.personId()))
            .toList();
    boolean bypass = complaintBypass(tenant, pkg.orgUnitId());
    int complaints = (int) relevant.stream().filter(p -> COMPLAINT.equals(p.status())).count();
    int signed = (int) relevant.stream().filter(p -> SIGNED.contains(p.status())).count();
    int unsigned = relevant.size() - signed - complaints;
    List<FormRow> forms = packages.formsIn(tenant, pkg.periodBucket(), pkg.id(), List.of());

    List<Item> items = new ArrayList<>();
    items.add(
        new Item(
            Checklist.SIGNED_PROFILES,
            signed + "/" + relevant.size() + " hồ sơ đã ký số",
            unsigned == 0,
            unsigned,
            true));
    int mismatched = mismatched(tenant, pkg, forms);
    items.add(
        new Item(
            Checklist.HASHES_MATCH,
            mismatched == 0
                ? "Mã băm từng tệp khớp manifest"
                : mismatched + " tệp có mã băm không khớp manifest",
            mismatched == 0,
            mismatched,
            true));
    Map<UUID, String> statusOf = new java.util.HashMap<>();
    relevant.forEach(p -> statusOf.put(p.id(), p.status()));
    Set<UUID> personalMissing = new LinkedHashSet<>();
    int headMissing = 0;
    for (FormRow f : forms) {
      if (UNIT_FORMS.contains(f.formCode())) {
        if (HEAD_FORM.equals(f.formCode()) && f.status() != FormStatus.DA_KY) {
          headMissing++;
        }
      } else if (f.status() != FormStatus.DA_KY
          && statusOf.containsKey(f.profileId())
          && !SIGNED.contains(statusOf.get(f.profileId()))
          && !COMPLAINT.equals(statusOf.get(f.profileId()))) {
        personalMissing.add(f.profileId());
      }
    }
    items.add(
        new Item(
            Checklist.PERSONAL_SIGNED,
            personalMissing.isEmpty()
                ? "Các cá nhân đã ký bản kiểm điểm"
                : "Còn " + personalMissing.size() + " người chưa ký bản kiểm điểm",
            personalMissing.isEmpty(),
            personalMissing.size(),
            true));
    items.add(
        new Item(
            Checklist.UNIT_FORM_SIGNED,
            headMissing == 0 ? "Mẫu 3 đã ký" : "Mẫu 3 chờ người đứng đầu ký",
            headMissing == 0,
            headMissing,
            false));
    items.add(
        new Item(
            Checklist.NO_OPEN_COMPLAINT,
            complaints == 0
                ? "Không còn khiếu nại mở"
                : bypass
                    ? complaints + " hồ sơ đang khiếu nại, nộp kèm theo cấu hình của đơn vị"
                    : "Còn " + complaints + " hồ sơ đang khiếu nại",
            complaints == 0 || bypass,
            complaints,
            true));
    return new Checklist(clock.instant(), items);
  }

  /**
   * Tệp có băm lệch: băm tính lại từ kho đối tượng khác băm đã ghi lúc sinh, hoặc băm đã ghi khác
   * manifest đã chốt, hoặc tệp chưa sinh.
   */
  private int mismatched(TenantId tenant, PackageRow pkg, List<FormRow> forms) {
    Map<UUID, String> frozen =
        PackageManifest.hashes(packages.manifest(tenant, pkg.periodBucket(), pkg.id()));
    int bad = 0;
    for (FormRow f : forms) {
      if (!RENDERED.contains(f.status()) || f.pdfSha256() == null) {
        bad++;
        continue;
      }
      String recorded = HexFormat.of().formatHex(f.pdfSha256());
      if (frozen.containsKey(f.id()) && !recorded.equals(frozen.get(f.id()))) {
        bad++;
        continue;
      }
      Optional<byte[]> stored = hashes.sha256(tenant, f.objectKey());
      if (stored.isPresent() && !Arrays.equals(stored.get(), f.pdfSha256())) {
        bad++;
      }
    }
    return bad;
  }

  private boolean complaintBypass(TenantId tenant, UUID unit) {
    TenantSettingApi s = settings.get();
    if (s == null) {
      return false;
    }
    return s.get(tenant, COMPLAINT_SETTING, unit).map(JsonNode::asBoolean).orElse(false);
  }

  // ---------------------------------------------------------------- ký gói

  /**
   * {@code signDossierPackage}: kiểm lại danh sách, chốt manifest, tạo yêu cầu ký ở SVC-10 cho
   * người gọi. Gói đã nộp trả {@code DOSSIER-409-PACKAGE-LOCKED}, gói không ở {@code CHO_KY} trả
   * {@code DOSSIER-409-INVALID-STATE}, danh sách chưa đạt trả {@code
   * DOSSIER-409-CHECKLIST-NOT-PASSED} (MSG-11-04).
   */
  public SignStarted sign(
      TenantId tenant,
      UUID actor,
      UUID actorUnit,
      UUID id,
      long expectedVersion,
      String provider,
      String idempotencyKey,
      String authorization) {
    PackageRow pkg = packages.find(tenant, id).orElseThrow(NoSuchElementException::new);
    checkScope(pkg, actorUnit);
    if (pkg.status().locked()) {
      throw locked();
    }
    if (pkg.rowVersion() != expectedVersion) {
      throw VersionConflictException.of(
          DossierPackageService.MODULE, expectedVersion, pkg.rowVersion());
    }
    if (pkg.status() != PackageStatus.CHO_KY) {
      throw new BusinessException(
          "DOSSIER-409-INVALID-STATE", "Gói hồ sơ chưa sẵn sàng ký, các biểu mẫu chưa sinh đủ.");
    }
    Checklist checklist = evaluate(tenant, pkg);
    if (!checklist.passed()) {
      throw new BusinessException(
          "DOSSIER-409-CHECKLIST-NOT-PASSED",
          "Chưa thể ký gói: "
              + checklist.failedCount()
              + " mục kiểm tra chưa đạt. Xem chi tiết ở danh sách kiểm tra.",
          List.of(),
          "MSG-11-04");
    }
    PackageSigner s = signer.get();
    if (s == null) {
      throw new SigningUnavailableException();
    }
    PackageManifest.Built manifest =
        PackageManifest.of(packages.formsIn(tenant, pkg.periodBucket(), pkg.id(), RENDERED));
    // Chốt trước, gọi sau (wiki trang 14): manifest commit trong giao dịch riêng trước khi gọi
    // SVC-10, vì SVC-10 đọc lại nguồn ký bằng kết nối khác ngay trong lời gọi tạo yêu cầu, còn
    // giao dịch idempotency của API vẫn mở tới khi trả phản hồi.
    long version =
        committed.execute(
            st ->
                packages.saveManifest(
                    tenant,
                    pkg,
                    manifest.canonicalJson(),
                    manifest.sha256(),
                    expectedVersion,
                    actor));
    if (version == 0) {
      throw VersionConflictException.of(
          DossierPackageService.MODULE, expectedVersion, pkg.rowVersion() + 1);
    }
    String key = idempotencyKey == null ? "goi-ho-so-ky:" + id + ":" + version : idempotencyKey;
    UUID request = s.request(tenant, id, provider, key, authorization);
    committed.execute(st -> packages.attachSignRequest(tenant, pkg.periodBucket(), id, request));
    return new SignStarted(request, packages.find(tenant, id).orElse(pkg));
  }

  /** Tài liệu cần ký của SVC-10 (mục đích {@code GOI_HO_SO}). */
  public Optional<SignSource> signSource(TenantId tenant, UUID packageId) {
    return packages
        .find(tenant, packageId)
        .map(
            p ->
                new SignSource(
                    p.status() == PackageStatus.CHO_KY && p.manifestHash() != null,
                    p.updatedBy() == null ? Set.of() : Set.of(p.updatedBy()),
                    p.manifestHash()));
  }

  /** Tác vụ ký gói theo mã yêu cầu ký. */
  public Optional<SignJob> signJob(TenantId tenant, UUID signRequestId) {
    return packages
        .findBySignRequest(tenant, signRequestId)
        .map(p -> new SignJob(p.id(), p.updatedBy(), p.status(), p.submittedAt()));
  }

  /**
   * EVT-18 của chữ ký gói: gói {@code CHO_KY} có manifest khớp băm đã ký chuyển {@code DA_KY} rồi
   * {@code DA_NOP} (băm gói là băm manifest đã ký, tệp chữ ký ghi vào {@code object_key} để xuất
   * ZIP và gửi gói con kèm chữ ký), ghi sổ cái và MSG-11-05 qua outbox, sau đó báo MOD-04 khoá các
   * hồ sơ trong gói. Sự kiện trùng hoặc lệch bị bỏ qua.
   *
   * @return {@code true} khi gói vừa được nộp
   */
  public boolean onSigned(TenantId tenant, Signed event) {
    PackageRow found = packages.find(tenant, event.packageId()).orElse(null);
    if (found == null) {
      LOG.warn("EVT-18 {} trỏ gói hồ sơ không có", event.signRequestId());
      return false;
    }
    Boolean done =
        inTx(
            () -> {
              PackageRow pkg = packages.lock(tenant, found.periodBucket(), found.id()).orElse(null);
              if (pkg == null || pkg.status() != PackageStatus.CHO_KY) {
                return false;
              }
              if (pkg.signRequestId() != null
                  && !pkg.signRequestId().equals(event.signRequestId())) {
                LOG.warn(
                    "EVT-18 {} không phải yêu cầu ký hiện hành của gói", event.signRequestId());
                return false;
              }
              if (pkg.manifestHash() == null
                  || event.hashes().stream().noneMatch(h -> Arrays.equals(h, pkg.manifestHash()))) {
                LOG.warn("EVT-18 {} có băm lệch manifest gói {}", event.signRequestId(), pkg.id());
                return false;
              }
              UUID signerId = event.signer() != null ? event.signer() : pkg.updatedBy();
              long signedVersion =
                  packages.updateStatus(tenant, pkg, PackageStatus.DA_KY, null, signerId);
              PackageRow signedRow = with(pkg, PackageStatus.DA_KY, signedVersion);
              Instant at = event.signedAt() != null ? event.signedAt() : clock.instant();
              long version =
                  packages.submit(
                      tenant,
                      signedRow,
                      at,
                      pkg.manifestHash(),
                      event.signedObjectKey(pkg.manifestHash()),
                      signerId);
              List<UUID> recipients =
                  new ArrayList<>(new LinkedHashSet<>(nonNull(signerId, pkg.updatedBy())));
              events.submitted(
                  tenant,
                  with(signedRow, PackageStatus.DA_NOP, version),
                  recipients,
                  version,
                  signerId);
              Set<UUID> profileIds = new LinkedHashSet<>();
              packages
                  .formsIn(tenant, pkg.periodBucket(), pkg.id(), List.of())
                  .forEach(f -> profileIds.add(f.profileId()));
              afterCommit(() -> lockProfiles(tenant, pkg.id(), profileIds, signerId));
              return true;
            });
    return Boolean.TRUE.equals(done);
  }

  private void lockProfiles(TenantId tenant, UUID packageId, Collection<UUID> ids, UUID actor) {
    try {
      int n = TenantContext.call(tenant, () -> api().lock(tenant, ids, actor));
      LOG.info("Gói hồ sơ {} đã nộp: khoá {} hồ sơ", packageId, n);
    } catch (Exception e) {
      LOG.warn("Chưa khoá được hồ sơ của gói {}", packageId, e);
    }
  }

  // ---------------------------------------------------------------- mở lại kỳ

  /**
   * EVT-22: mỗi gói {@code DA_NOP} thuộc phạm vi mở lại (cả kỳ, đơn vị hoặc đơn vị của hồ sơ) có
   * phiên bản mới ở {@code DANG_LAP}; gói cũ giữ nguyên. Gói đã có phiên bản mới hơn thì bỏ qua.
   *
   * @return số phiên bản gói vừa lập
   */
  public int onReopened(TenantId tenant, Reopened event) {
    Set<UUID> units = units(tenant, event);
    UUID actor = event.actor() == null ? new UUID(0L, 0L) : event.actor();
    int created = 0;
    UUID after = null;
    while (true) {
      List<PackageRow> page =
          packages.list(
              tenant, new Filter(event.periodId(), null, null, PackageStatus.DA_NOP), after, PAGE);
      for (PackageRow pkg : page) {
        if (units != null && !units.contains(pkg.orgUnitId())) {
          continue;
        }
        Optional<PackageRow> latest =
            packages.findByUnit(tenant, pkg.periodBucket(), pkg.orgUnitId(), pkg.kind());
        if (latest.isPresent() && !latest.get().id().equals(pkg.id())) {
          continue;
        }
        PackageRow next =
            packages.newVersion(tenant, pkg, Ids.newUuidV7(), event.reopenRequestId(), actor);
        events.reopened(tenant, next, pkg.id(), actor);
        created++;
      }
      if (page.size() < PAGE) {
        return created;
      }
      after = page.get(page.size() - 1).id();
    }
  }

  /** Đơn vị trong phạm vi; {@code null} là cả kỳ. */
  private Set<UUID> units(TenantId tenant, Reopened event) {
    if (PAGE_SCOPE_ALL.equals(event.scope())) {
      return null;
    }
    Set<UUID> out = new LinkedHashSet<>();
    if (PAGE_SCOPE_UNIT.equals(event.scope())) {
      out.addAll(event.scopeIds());
    } else if (PAGE_SCOPE_PROFILE.equals(event.scope())) {
      DossierProfilesApi api = api();
      for (UUID profile : event.scopeIds()) {
        api.find(tenant, profile).ifPresent(p -> out.add(p.orgUnitId()));
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- hỗ trợ

  private DossierProfilesApi api() {
    DossierProfilesApi api = profiles.get();
    if (api == null) {
      throw new IllegalStateException("Chưa có cổng hồ sơ đánh giá của MOD-04");
    }
    return api;
  }

  private <T> T inTx(Supplier<T> work) {
    return tx.execute(s -> work.get());
  }

  private static void afterCommit(Runnable work) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              work.run();
            }
          });
    } else {
      work.run();
    }
  }

  private static List<UUID> nonNull(UUID... ids) {
    return Arrays.stream(ids).filter(Objects::nonNull).toList();
  }

  private static PackageRow with(PackageRow r, PackageStatus status, long version) {
    return new PackageRow(
        r.id(),
        r.periodId(),
        r.periodBucket(),
        r.code(),
        r.orgUnitId(),
        r.personId(),
        r.kind(),
        r.versionNo(),
        status,
        r.deadline(),
        r.submittedAt(),
        r.transferredTo(),
        r.signRequestId(),
        r.manifestHash(),
        r.packageSha256(),
        r.updatedBy(),
        version);
  }

  private static void checkScope(PackageRow r, UUID actorUnit) {
    if (actorUnit == null || !actorUnit.equals(r.orgUnitId())) {
      throw new BusinessException(
          "COMMON-403-OUT-OF-SCOPE", "Đồng chí không có quyền với gói hồ sơ này.");
    }
  }

  private static BusinessException locked() {
    return new BusinessException(
        "DOSSIER-409-PACKAGE-LOCKED", "Gói hồ sơ đã ký và nộp, không thay đổi được.");
  }
}
