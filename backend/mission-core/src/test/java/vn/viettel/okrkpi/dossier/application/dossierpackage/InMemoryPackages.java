package vn.viettel.okrkpi.dossier.application.dossierpackage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi.UnitProfile;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.FormStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageKind;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.RenderProgress;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kho gói và biểu mẫu trong bộ nhớ cho kiểm thử đơn vị của CORE-06-06 và CORE-06-07: cùng ngữ nghĩa
 * với {@code JdbcDossierPackageRepository} ở mức cổng (khoá trùng, trạng thái, phiên bản, gói đã
 * nộp bị đóng băng như trigger {@code fn_freeze_submitted_package}).
 */
final class InMemoryPackages implements DossierPackageRepository {

  final Map<UUID, PackageRow> packages = new LinkedHashMap<>();
  final Map<UUID, FormRow> forms = new LinkedHashMap<>();
  final Map<UUID, String> manifests = new LinkedHashMap<>();
  final Map<UUID, String> signatures = new LinkedHashMap<>();
  final Map<UUID, UUID> reopens = new LinkedHashMap<>();
  final Map<UUID, List<UUID>> sources = new LinkedHashMap<>();
  final Set<UUID> settled = new HashSet<>();
  private int sequence;

  static PackageRow copy(
      PackageRow r, PackageStatus status, long version, UUID signRequestId, UUID updatedBy) {
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
        signRequestId,
        r.manifestHash(),
        r.packageSha256(),
        updatedBy,
        version);
  }

  static FormRow form(FormRow f, FormStatus status, UUID job, String key, byte[] sha) {
    return new FormRow(
        f.id(),
        f.periodId(),
        f.periodBucket(),
        f.packageId(),
        f.profileId(),
        f.formCode(),
        f.templateVersion(),
        f.dataSnapshotHash(),
        job,
        key,
        sha,
        status,
        status == FormStatus.DA_KY ? Instant.EPOCH : f.signedAt());
  }

  /** Đặt thẳng trạng thái một biểu mẫu (dữ liệu dựng sẵn của kiểm thử). */
  void setForm(UUID id, FormStatus status, String key, byte[] sha) {
    FormRow f = forms.get(id);
    forms.put(id, form(f, status, f.renderJobId(), key, sha));
  }

  /** Đặt thẳng trạng thái gói (dữ liệu dựng sẵn của kiểm thử). */
  void setStatus(UUID id, PackageStatus status) {
    PackageRow r = packages.get(id);
    packages.put(id, copy(r, status, r.rowVersion(), r.signRequestId(), r.updatedBy()));
  }

  private static BusinessException frozen() {
    return new BusinessException("DOSSIER-409-PACKAGE-LOCKED", "Gói hồ sơ đã nộp.");
  }

  private void guard(UUID id) {
    PackageRow stored = packages.get(id);
    if (stored != null
        && (stored.status() == PackageStatus.DA_NOP
            || stored.status() == PackageStatus.DA_LUU_TRU)) {
      throw frozen();
    }
  }

  private List<FormRow> formsOf(UUID packageId, Predicate<FormRow> filter) {
    return forms.values().stream()
        .filter(f -> packageId.equals(f.packageId()))
        .filter(filter)
        .sorted(Comparator.comparing(f -> f.id().toString()))
        .toList();
  }

  @Override
  public Optional<PackageRow> find(TenantId tenant, UUID id) {
    return Optional.ofNullable(packages.get(id));
  }

  @Override
  public Optional<PackageRow> lock(TenantId tenant, int periodBucket, UUID id) {
    return find(tenant, id);
  }

  @Override
  public Optional<PackageRow> findByUnit(
      TenantId tenant, int periodBucket, UUID orgUnitId, PackageKind kind) {
    return packages.values().stream()
        .filter(
            p ->
                p.periodBucket() == periodBucket
                    && p.orgUnitId().equals(orgUnitId)
                    && p.kind() == kind
                    && p.personId() == null)
        .max(Comparator.comparingInt(PackageRow::versionNo));
  }

  @Override
  public Ensured ensure(TenantId tenant, NewPackage cmd, UUID id, UUID actor) {
    Optional<PackageRow> existing =
        findByUnit(tenant, cmd.periodBucket(), cmd.orgUnitId(), cmd.kind());
    if (existing.isPresent()) {
      return new Ensured(existing.get(), false);
    }
    String code =
        "DP-" + cmd.periodBucket() / 10 + "-" + cmd.kind().codeLetter() + "-" + ++sequence;
    PackageRow row =
        new PackageRow(
            id,
            cmd.periodId(),
            cmd.periodBucket(),
            code,
            cmd.orgUnitId(),
            null,
            cmd.kind(),
            1,
            PackageStatus.DANG_LAP,
            cmd.deadline(),
            null,
            null,
            null,
            null,
            null,
            actor,
            0);
    packages.put(id, row);
    return new Ensured(row, true);
  }

  @Override
  public List<PackageRow> list(TenantId tenant, Filter filter, UUID after, int limit) {
    return packages.values().stream()
        .filter(p -> filter.periodId() == null || filter.periodId().equals(p.periodId()))
        .filter(p -> filter.orgUnitId() == null || filter.orgUnitId().equals(p.orgUnitId()))
        .filter(p -> filter.kind() == null || filter.kind() == p.kind())
        .filter(p -> filter.status() == null || filter.status() == p.status())
        .filter(p -> after == null || p.id().toString().compareTo(after.toString()) > 0)
        .sorted(Comparator.comparing(p -> p.id().toString()))
        .limit(limit)
        .toList();
  }

  @Override
  public List<PackageRow> generating(TenantId tenant, UUID after, int limit) {
    return list(tenant, new Filter(null, null, null, PackageStatus.DANG_SINH), after, limit);
  }

  @Override
  public long updateStatus(
      TenantId tenant, PackageRow row, PackageStatus next, Long expectedVersion, UUID actor) {
    guard(row.id());
    PackageRow stored = packages.get(row.id());
    if (expectedVersion != null && stored.rowVersion() != expectedVersion) {
      return 0;
    }
    long version = stored.rowVersion() + 1;
    packages.put(row.id(), copy(stored, next, version, stored.signRequestId(), actor));
    return version;
  }

  @Override
  public RenderProgress progress(TenantId tenant, int periodBucket, UUID packageId) {
    List<FormRow> all = formsOf(packageId, f -> true);
    int rendered = (int) all.stream().filter(f -> f.status().rendered()).count();
    int failed = (int) all.stream().filter(f -> f.status() == FormStatus.LOI).count();
    int signed = (int) all.stream().filter(f -> f.status() == FormStatus.DA_KY).count();
    return new RenderProgress(all.size(), rendered, failed, signed);
  }

  @Override
  public int profileCount(TenantId tenant, int periodBucket, UUID packageId) {
    return (int) formsOf(packageId, f -> true).stream().map(FormRow::profileId).distinct().count();
  }

  @Override
  public Optional<FormRow> findForm(TenantId tenant, UUID formId) {
    return Optional.ofNullable(forms.get(formId));
  }

  @Override
  public List<FormRow> forms(
      TenantId tenant,
      int periodBucket,
      UUID packageId,
      FormStatus status,
      String formCode,
      UUID after,
      int limit) {
    return formsOf(
            packageId,
            f ->
                (status == null || f.status() == status)
                    && (formCode == null || f.formCode().equals(formCode))
                    && (after == null || f.id().toString().compareTo(after.toString()) > 0))
        .stream()
        .limit(limit)
        .toList();
  }

  @Override
  public List<FormRow> formsOfProfile(
      TenantId tenant, int periodBucket, UUID packageId, UUID profileId) {
    return formsOf(packageId, f -> f.profileId().equals(profileId));
  }

  @Override
  public List<FormRow> formsIn(
      TenantId tenant, int periodBucket, UUID packageId, Collection<FormStatus> statuses) {
    return formsOf(packageId, f -> statuses.isEmpty() || statuses.contains(f.status()));
  }

  @Override
  public int insertForms(TenantId tenant, PackageRow pkg, List<NewForm> fresh, UUID actor) {
    int n = 0;
    for (NewForm nf : fresh) {
      boolean duplicate =
          formsOf(
                  pkg.id(),
                  f ->
                      f.formCode().equals(nf.formCode())
                          && (nf.unitLevel() || f.profileId().equals(nf.profileId())))
              .stream()
              .findAny()
              .isPresent();
      if (duplicate) {
        continue;
      }
      forms.put(
          nf.id(),
          new FormRow(
              nf.id(),
              pkg.periodId(),
              pkg.periodBucket(),
              pkg.id(),
              nf.profileId(),
              nf.formCode(),
              nf.templateVersion(),
              nf.dataSnapshotHash(),
              null,
              null,
              null,
              FormStatus.CHO_SINH,
              null));
      n++;
    }
    return n;
  }

  @Override
  public int refresh(
      TenantId tenant, int periodBucket, UUID formId, byte[] hash, String templateVersion) {
    FormRow f = forms.get(formId);
    if (f == null || f.status().rendered()) {
      return 0;
    }
    forms.put(
        formId,
        new FormRow(
            f.id(),
            f.periodId(),
            f.periodBucket(),
            f.packageId(),
            f.profileId(),
            f.formCode(),
            templateVersion,
            hash,
            f.renderJobId(),
            f.objectKey(),
            f.pdfSha256(),
            f.status(),
            f.signedAt()));
    return 1;
  }

  @Override
  public int markRequested(TenantId tenant, int periodBucket, UUID packageId, UUID requestId) {
    int n = 0;
    for (FormRow f :
        formsOf(packageId, f -> f.status() == FormStatus.CHO_SINH && f.renderJobId() == null)) {
      forms.put(f.id(), form(f, FormStatus.CHO_SINH, requestId, null, null));
      n++;
    }
    return n;
  }

  @Override
  public int markGenerated(
      TenantId tenant,
      int periodBucket,
      UUID formId,
      UUID renderJobId,
      String objectKey,
      byte[] pdfSha256,
      UUID actor) {
    FormRow f = forms.get(formId);
    if (f == null || f.status().rendered()) {
      return 0;
    }
    forms.put(formId, form(f, FormStatus.DA_SINH, renderJobId, objectKey, pdfSha256));
    return 1;
  }

  @Override
  public int markFailed(
      TenantId tenant, int periodBucket, UUID packageId, UUID requestId, UUID actor) {
    int n = 0;
    for (FormRow f :
        formsOf(
            packageId,
            f ->
                f.status() == FormStatus.CHO_SINH
                    && (requestId == null || requestId.equals(f.renderJobId())))) {
      forms.put(f.id(), form(f, FormStatus.LOI, f.renderJobId(), null, null));
      n++;
    }
    return n;
  }

  @Override
  public int resetFailed(
      TenantId tenant, int periodBucket, UUID packageId, Collection<UUID> formIds, UUID actor) {
    int n = 0;
    for (FormRow f :
        formsOf(
            packageId,
            f -> f.status() == FormStatus.LOI && (formIds.isEmpty() || formIds.contains(f.id())))) {
      forms.put(f.id(), form(f, FormStatus.CHO_SINH, null, null, null));
      n++;
    }
    return n;
  }

  @Override
  public List<UUID> pendingRequests(TenantId tenant, int periodBucket, UUID packageId) {
    return formsOf(packageId, f -> f.status() == FormStatus.CHO_SINH).stream()
        .map(FormRow::renderJobId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  @Override
  public Set<UUID> settledSpecialCases(TenantId tenant, int periodBucket, UUID periodId) {
    return Set.copyOf(settled);
  }

  @Override
  public String manifest(TenantId tenant, int periodBucket, UUID packageId) {
    return manifests.getOrDefault(packageId, "[]");
  }

  @Override
  public long saveManifest(
      TenantId tenant,
      PackageRow row,
      String manifestJson,
      byte[] manifestHash,
      long expectedVersion,
      UUID actor) {
    guard(row.id());
    PackageRow s = packages.get(row.id());
    if (s.status() != PackageStatus.CHO_KY || s.rowVersion() != expectedVersion) {
      return 0;
    }
    manifests.put(row.id(), manifestJson);
    long version = s.rowVersion() + 1;
    packages.put(
        row.id(),
        new PackageRow(
            s.id(),
            s.periodId(),
            s.periodBucket(),
            s.code(),
            s.orgUnitId(),
            s.personId(),
            s.kind(),
            s.versionNo(),
            s.status(),
            s.deadline(),
            s.submittedAt(),
            s.transferredTo(),
            null,
            manifestHash,
            s.packageSha256(),
            actor,
            version));
    return version;
  }

  @Override
  public int attachSignRequest(
      TenantId tenant, int periodBucket, UUID packageId, UUID signRequestId) {
    guard(packageId);
    PackageRow s = packages.get(packageId);
    packages.put(packageId, copy(s, s.status(), s.rowVersion(), signRequestId, s.updatedBy()));
    return 1;
  }

  @Override
  public Optional<PackageRow> findBySignRequest(TenantId tenant, UUID signRequestId) {
    return packages.values().stream()
        .filter(p -> signRequestId.equals(p.signRequestId()))
        .max(Comparator.comparingInt(PackageRow::versionNo));
  }

  @Override
  public long submit(
      TenantId tenant,
      PackageRow row,
      Instant submittedAt,
      byte[] packageSha256,
      String signatureKey,
      UUID actor) {
    guard(row.id());
    if (signatureKey != null) {
      signatures.put(row.id(), signatureKey);
    }
    PackageRow s = packages.get(row.id());
    long version = s.rowVersion() + 1;
    packages.put(
        row.id(),
        new PackageRow(
            s.id(),
            s.periodId(),
            s.periodBucket(),
            s.code(),
            s.orgUnitId(),
            s.personId(),
            s.kind(),
            s.versionNo(),
            PackageStatus.DA_NOP,
            s.deadline(),
            submittedAt,
            s.transferredTo(),
            s.signRequestId(),
            s.manifestHash(),
            packageSha256,
            actor,
            version));
    return version;
  }

  @Override
  public PackageRow newVersion(
      TenantId tenant, PackageRow previous, UUID id, UUID reopenRequestId, UUID actor) {
    if (reopenRequestId != null) {
      reopens.put(id, reopenRequestId);
    }
    PackageRow next =
        new PackageRow(
            id,
            previous.periodId(),
            previous.periodBucket(),
            previous.code(),
            previous.orgUnitId(),
            previous.personId(),
            previous.kind(),
            previous.versionNo() + 1,
            PackageStatus.DANG_LAP,
            previous.deadline(),
            null,
            null,
            null,
            null,
            null,
            actor,
            0);
    packages.put(id, next);
    return next;
  }

  /** Cổng hồ sơ MOD-04 trong bộ nhớ. */
  @Override
  public Optional<UUID> reopenRequest(TenantId tenant, int periodBucket, UUID packageId) {
    return Optional.ofNullable(reopens.get(packageId));
  }

  @Override
  public void insertDraft(
      TenantId tenant,
      UUID periodId,
      int periodBucket,
      NewForm form,
      List<UUID> sourceProfiles,
      UUID actor) {
    forms.put(
        form.id(),
        new FormRow(
            form.id(),
            periodId,
            periodBucket,
            null,
            form.profileId(),
            form.formCode(),
            form.templateVersion(),
            form.dataSnapshotHash(),
            null,
            null,
            null,
            FormStatus.CHO_SINH,
            null));
    if (!sourceProfiles.isEmpty()) {
      sources.put(form.id(), List.copyOf(sourceProfiles));
    }
  }

  @Override
  public List<UUID> sourceProfiles(TenantId tenant, int periodBucket, UUID formId) {
    return sources.getOrDefault(formId, List.of());
  }

  @Override
  public Optional<String> signatureKey(TenantId tenant, int periodBucket, UUID packageId) {
    return Optional.ofNullable(signatures.get(packageId));
  }

  @Override
  public long archive(TenantId tenant, PackageRow row, String transferredTo, UUID actor) {
    PackageRow s = packages.get(row.id());
    if (s == null || s.status() != PackageStatus.DA_NOP) {
      return 0;
    }
    long version = s.rowVersion() + 1;
    packages.put(
        row.id(),
        new PackageRow(
            s.id(),
            s.periodId(),
            s.periodBucket(),
            s.code(),
            s.orgUnitId(),
            s.personId(),
            s.kind(),
            s.versionNo(),
            PackageStatus.DA_LUU_TRU,
            s.deadline(),
            s.submittedAt(),
            s.transferredTo() == null ? transferredTo : s.transferredTo(),
            s.signRequestId(),
            s.manifestHash(),
            s.packageSha256(),
            actor,
            version));
    return version;
  }

  static final class Profiles implements DossierProfilesApi {

    final Map<UUID, UnitProfile> profiles = new LinkedHashMap<>();
    final List<UUID> locked = new ArrayList<>();
    volatile boolean failLock;

    void put(UnitProfile p) {
      profiles.put(p.id(), p);
    }

    void status(UUID id, String status) {
      UnitProfile p = profiles.get(id);
      put(
          new UnitProfile(
              p.id(),
              p.personId(),
              p.periodId(),
              p.periodBucket(),
              p.orgUnitId(),
              p.flow(),
              status,
              p.ratingCode(),
              p.score(),
              p.scoreSnapshotId(),
              p.head(),
              p.pack()));
    }

    @Override
    public Optional<UnitProfile> find(TenantId tenant, UUID profileId) {
      return Optional.ofNullable(profiles.get(profileId));
    }

    @Override
    public List<UnitProfile> unitProfiles(TenantId tenant, UUID periodId, UUID orgUnitId) {
      return profiles.values().stream()
          .filter(p -> p.periodId().equals(periodId) && p.orgUnitId().equals(orgUnitId))
          .toList();
    }

    @Override
    public List<UUID> undecidedPersons(TenantId tenant, UUID periodId, UUID orgUnitId) {
      return unitProfiles(tenant, periodId, orgUnitId).stream()
          .filter(p -> !p.decided())
          .map(UnitProfile::personId)
          .toList();
    }

    @Override
    public boolean anyDecided(TenantId tenant, UUID periodId, UUID orgUnitId) {
      return unitProfiles(tenant, periodId, orgUnitId).stream().anyMatch(UnitProfile::decided);
    }

    @Override
    public int lock(TenantId tenant, Collection<UUID> profileIds, UUID actor) {
      if (failLock) {
        throw new IllegalStateException("MOD-04 lỗi");
      }
      int n = 0;
      for (UUID id : profileIds) {
        UnitProfile p = profiles.get(id);
        if (p != null && "DA_KY_SO".equals(p.status())) {
          status(id, "DA_KHOA");
          locked.add(id);
          n++;
        }
      }
      return n;
    }
  }
}
