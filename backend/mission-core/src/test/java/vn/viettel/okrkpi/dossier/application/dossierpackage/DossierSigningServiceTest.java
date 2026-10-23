package vn.viettel.okrkpi.dossier.application.dossierpackage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.BooleanNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi.Pack;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi.UnitProfile;
import vn.viettel.okrkpi.config.api.TenantSettingApi;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierSigningService.Reopened;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierSigningService.Signed;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.NewForm;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.NewPackage;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.PackageRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.FileHashes;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageEvents;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageSigner;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.Checklist;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.FormStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageKind;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageStatus;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kiểm thử đơn vị CORE-06-07 trên kho trong bộ nhớ: danh sách kiểm tra BR-11-09, lệnh ký, EVT-18
 * nộp gói và khoá hồ sơ, EVT-22 lập phiên bản gói theo phạm vi.
 */
class DossierSigningServiceTest {

  static final TenantId TENANT =
      TenantId.of(UUID.fromString("01927a06-0000-7000-8000-00000000bb01"));
  static final UUID PERIOD = UUID.fromString("01927a06-0000-7000-8000-00000000bb02");
  static final UUID UNIT = UUID.fromString("01927a06-0000-7000-8000-00000000bb03");
  static final UUID ACTOR = UUID.fromString("01927a06-0000-7000-8000-00000000bb04");
  static final int BUCKET = 20260;
  static final Instant NOW = Instant.parse("2027-01-15T02:00:00Z");
  static final Pack PACK =
      new Pack("PP-DANG", "1.3.0", List.of("BANG_CHAM_DIEM"), List.of("MAU_3"), Map.of());

  final InMemoryPackages repo = new InMemoryPackages();
  final InMemoryPackages.Profiles profiles = new InMemoryPackages.Profiles();
  final Map<String, byte[]> stored = new HashMap<>();
  final List<UUID> signRequests = new ArrayList<>();
  final List<String> events = new ArrayList<>();
  boolean bypass;
  PackageSigner signer =
      (tenant, packageId, provider, key, authorization) -> {
        UUID id = UUID.randomUUID();
        signRequests.add(id);
        return id;
      };
  DossierProfilesApi api = profiles;
  TenantSettingApi settings =
      (tenant, key, unit) ->
          bypass && DossierSigningService.COMPLAINT_SETTING.equals(key)
              ? Optional.of(BooleanNode.TRUE)
              : Optional.empty();

  final PackageEvents recorder =
      new PackageEvents() {
        @Override
        public void submitted(
            TenantId tenant, PackageRow pkg, List<UUID> recipients, long version, UUID actor) {
          events.add("NOP:" + pkg.id() + ":" + recipients.size());
        }

        @Override
        public void reopened(TenantId tenant, PackageRow pkg, UUID previousId, UUID actor) {
          events.add("MO_LAI:" + previousId);
        }
      };

  DossierSigningService service() {
    return new DossierSigningService(
        repo,
        () -> api,
        () -> signer,
        (tenant, key) -> Optional.ofNullable(stored.get(key)),
        recorder,
        () -> settings,
        TransactionOperations.withoutTransaction(),
        TransactionOperations.withoutTransaction(),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void clearSync() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  private UnitProfile add(UUID unit, String status) {
    UnitProfile p =
        new UnitProfile(
            UUID.randomUUID(),
            UUID.randomUUID(),
            PERIOD,
            BUCKET,
            unit,
            "DANG",
            status,
            "HTTNV",
            BigDecimal.TEN,
            null,
            false,
            PACK);
    profiles.put(p);
    return p;
  }

  /** Gói CHO_KY: một bảng chấm điểm cho mỗi hồ sơ và Mẫu 3, đều đã sinh. */
  private PackageRow ready(UUID unit, List<UnitProfile> people) {
    PackageRow pkg =
        repo.ensure(
                TENANT,
                new NewPackage(PERIOD, BUCKET, unit, PackageKind.NAM, null),
                UUID.randomUUID(),
                ACTOR)
            .row();
    List<NewForm> forms = new ArrayList<>();
    for (UnitProfile p : people) {
      forms.add(new NewForm(UUID.randomUUID(), p.id(), "BANG_CHAM_DIEM", "1", new byte[32], false));
    }
    forms.add(new NewForm(UUID.randomUUID(), people.get(0).id(), "MAU_3", "1", new byte[32], true));
    repo.insertForms(TENANT, pkg, forms, ACTOR);
    for (var f : repo.formsIn(TENANT, BUCKET, pkg.id(), List.of())) {
      byte[] sha = new byte[32];
      sha[0] = (byte) f.id().hashCode();
      sha[31] = 1;
      repo.setForm(f.id(), FormStatus.DA_SINH, "k/" + f.id(), sha);
    }
    repo.setStatus(pkg.id(), PackageStatus.CHO_KY);
    return repo.packages.get(pkg.id());
  }

  private static Checklist.Item item(Checklist c, String code) {
    return c.items().stream().filter(i -> i.code().equals(code)).findFirst().orElseThrow();
  }

  private DossierSigningService.SignStarted sign(
      DossierSigningService s, PackageRow pkg, String key) {
    return s.sign(
        TENANT,
        ACTOR,
        UNIT,
        pkg.id(),
        repo.packages.get(pkg.id()).rowVersion(),
        null,
        key,
        "Bearer x");
  }

  // ---------------------------------------------------------------- danh sách kiểm tra

  @Test
  void danhSachKiemTraTheoTungMuc() {
    UnitProfile a = add(UNIT, "DA_KY_SO");
    UnitProfile b = add(UNIT, "DA_QUYET_DINH");
    UnitProfile c = add(UNIT, "KHIEU_NAI");
    UnitProfile d = add(UNIT, "CHO_QUYET_DINH");
    repo.settled.add(d.personId());
    PackageRow pkg = ready(UNIT, List.of(a, b, c));
    DossierSigningService s = service();

    Checklist list = s.checklist(TENANT, UNIT, pkg.id());
    assertThat(list.passed()).isFalse();
    assertThat(item(list, Checklist.SIGNED_PROFILES).count()).isEqualTo(1);
    assertThat(item(list, Checklist.PERSONAL_SIGNED).count()).isEqualTo(1);
    assertThat(item(list, Checklist.UNIT_FORM_SIGNED).ok()).isFalse();
    assertThat(item(list, Checklist.NO_OPEN_COMPLAINT).ok()).isFalse();
    assertThat(item(list, Checklist.HASHES_MATCH).ok()).isTrue();
    assertThat(list.evaluatedAt()).isEqualTo(NOW);

    bypass = true;
    profiles.status(b.id(), "DA_KHOA");
    list = s.checklist(TENANT, UNIT, pkg.id());
    assertThat(list.passed()).isTrue();
    assertThat(item(list, Checklist.NO_OPEN_COMPLAINT).label()).contains("nộp kèm");

    // Mẫu 3 đã ký: hết cảnh báo; không còn khiếu nại.
    var head =
        repo.formsIn(TENANT, BUCKET, pkg.id(), List.of()).stream()
            .filter(f -> f.formCode().equals("MAU_3"))
            .findFirst()
            .orElseThrow();
    repo.setForm(head.id(), FormStatus.DA_KY, head.objectKey(), head.pdfSha256());
    profiles.status(c.id(), "DA_KY_SO");
    settings = null;
    list = s.checklist(TENANT, UNIT, pkg.id());
    assertThat(item(list, Checklist.UNIT_FORM_SIGNED).ok()).isTrue();
    assertThat(item(list, Checklist.NO_OPEN_COMPLAINT).ok()).isTrue();

    assertThatThrownBy(() -> s.checklist(TENANT, UUID.randomUUID(), pkg.id()))
        .hasMessage("COMMON-403-OUT-OF-SCOPE");
    assertThatThrownBy(() -> s.checklist(TENANT, null, pkg.id()))
        .hasMessage("COMMON-403-OUT-OF-SCOPE");
    assertThatThrownBy(() -> s.checklist(TENANT, UNIT, UUID.randomUUID()))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void bamTepLechHoacTepChuaSinhLamMucBamChuaDat() {
    UnitProfile a = add(UNIT, "DA_KY_SO");
    PackageRow pkg = ready(UNIT, List.of(a));
    DossierSigningService s = service();
    var forms = repo.formsIn(TENANT, BUCKET, pkg.id(), List.of());
    // Kho trả đúng băm đã ghi: đạt.
    stored.put(forms.get(0).objectKey(), forms.get(0).pdfSha256());
    assertThat(item(s.checklist(TENANT, UNIT, pkg.id()), Checklist.HASHES_MATCH).ok()).isTrue();
    // Kho trả băm khác.
    stored.put(forms.get(0).objectKey(), new byte[] {9});
    assertThat(item(s.checklist(TENANT, UNIT, pkg.id()), Checklist.HASHES_MATCH).count())
        .isEqualTo(1);
    stored.clear();
    // Tệp chưa sinh.
    repo.setForm(forms.get(1).id(), FormStatus.LOI, null, null);
    assertThat(item(s.checklist(TENANT, UNIT, pkg.id()), Checklist.HASHES_MATCH).count())
        .isEqualTo(1);
    repo.setForm(forms.get(1).id(), FormStatus.DA_SINH, "k", forms.get(1).pdfSha256());
    // Băm đã ghi lệch manifest đã chốt.
    sign(s, pkg, "k1");
    repo.setForm(forms.get(0).id(), FormStatus.DA_SINH, "k", new byte[32]);
    assertThat(item(s.checklist(TENANT, UNIT, pkg.id()), Checklist.HASHES_MATCH).ok()).isFalse();
    // Manifest hỏng coi như chưa chốt.
    repo.manifests.put(pkg.id(), "{hong");
    assertThat(item(s.checklist(TENANT, UNIT, pkg.id()), Checklist.HASHES_MATCH).ok()).isTrue();
    assertThat(FileHashes.NONE.sha256(TENANT, "k")).isEmpty();
  }

  // ---------------------------------------------------------------- ký

  @Test
  void kyGoiKiemTrangThaiPhienBanDanhSachVaSvc10() {
    UnitProfile a = add(UNIT, "DA_KY_SO");
    UnitProfile b = add(UNIT, "DA_QUYET_DINH");
    PackageRow pkg = ready(UNIT, List.of(a, b));
    DossierSigningService s = service();

    assertThatThrownBy(() -> sign(s, pkg, "k")).hasMessage("DOSSIER-409-CHECKLIST-NOT-PASSED");
    profiles.status(b.id(), "DA_KY_SO");
    assertThatThrownBy(() -> s.sign(TENANT, ACTOR, UNIT, pkg.id(), 99, null, "k", null))
        .isInstanceOf(VersionConflictException.class);
    assertThatThrownBy(() -> s.sign(TENANT, ACTOR, UNIT, UUID.randomUUID(), 0, null, "k", null))
        .isInstanceOf(NoSuchElementException.class);

    PackageSigner real = signer;
    signer = null;
    assertThatThrownBy(() -> sign(s, pkg, "k"))
        .isInstanceOf(DossierSigningService.SigningUnavailableException.class);
    signer = real;

    DossierSigningService.SignStarted started = sign(s, pkg, null);
    assertThat(signRequests).containsExactly(started.signRequestId());
    PackageRow row = repo.packages.get(pkg.id());
    assertThat(row.signRequestId()).isEqualTo(started.signRequestId());
    assertThat(row.manifestHash()).hasSize(32);
    assertThat(row.updatedBy()).isEqualTo(ACTOR);
    assertThat(PackageManifest.hashes(repo.manifests.get(pkg.id()))).hasSize(3);

    DossierSigningService.SignSource source = s.signSource(TENANT, pkg.id()).orElseThrow();
    assertThat(source.ready()).isTrue();
    assertThat(source.signers()).containsExactly(ACTOR);
    assertThat(s.signSource(TENANT, UUID.randomUUID())).isEmpty();
    assertThat(s.signJob(TENANT, started.signRequestId()).orElseThrow().status())
        .isEqualTo(PackageStatus.CHO_KY);
    assertThat(s.signJob(TENANT, UUID.randomUUID())).isEmpty();

    repo.setStatus(pkg.id(), PackageStatus.DANG_SINH);
    assertThatThrownBy(() -> sign(s, pkg, "k")).hasMessage("DOSSIER-409-INVALID-STATE");
    assertThat(s.signSource(TENANT, pkg.id()).orElseThrow().ready()).isFalse();
    repo.setStatus(pkg.id(), PackageStatus.DA_NOP);
    assertThatThrownBy(() -> sign(s, pkg, "k")).hasMessage("DOSSIER-409-PACKAGE-LOCKED");
  }

  // ---------------------------------------------------------------- EVT-18

  @Test
  void evt18NopGoiKhoaHoSoVaBoQuaSuKienLech() {
    UnitProfile a = add(UNIT, "DA_KY_SO");
    UnitProfile b = add(UNIT, "DA_KY_SO");
    PackageRow pkg = ready(UNIT, List.of(a, b));
    DossierSigningService s = service();

    assertThat(
            s.onSigned(
                TENANT, new Signed(UUID.randomUUID(), UUID.randomUUID(), null, List.of(), null)))
        .isFalse();
    // Chưa chốt manifest.
    assertThat(
            s.onSigned(
                TENANT, new Signed(UUID.randomUUID(), pkg.id(), null, List.of(new byte[32]), null)))
        .isFalse();
    UUID request = sign(s, pkg, "k").signRequestId();
    byte[] hash = repo.packages.get(pkg.id()).manifestHash();
    // Yêu cầu ký khác yêu cầu hiện hành.
    assertThat(
            s.onSigned(TENANT, new Signed(UUID.randomUUID(), pkg.id(), null, List.of(hash), null)))
        .isFalse();
    // Băm lệch.
    assertThat(s.onSigned(TENANT, new Signed(request, pkg.id(), null, List.of(new byte[32]), null)))
        .isFalse();

    TransactionSynchronizationManager.initSynchronization();
    assertThat(s.onSigned(TENANT, new Signed(request, pkg.id(), null, List.of(hash), null)))
        .isTrue();
    assertThat(profiles.locked).isEmpty();
    for (TransactionSynchronization sync :
        TransactionSynchronizationManager.getSynchronizations()) {
      sync.afterCommit();
    }
    TransactionSynchronizationManager.clearSynchronization();
    PackageRow row = repo.packages.get(pkg.id());
    assertThat(row.status()).isEqualTo(PackageStatus.DA_NOP);
    assertThat(row.submittedAt()).isEqualTo(NOW);
    assertThat(row.packageSha256()).isEqualTo(hash);
    assertThat(events).containsExactly("NOP:" + pkg.id() + ":1");
    assertThat(profiles.locked).containsExactlyInAnyOrder(a.id(), b.id());
    assertThat(s.signJob(TENANT, request).orElseThrow().status()).isEqualTo(PackageStatus.DA_NOP);

    // Sự kiện lặp: gói đã nộp, bỏ qua.
    assertThat(s.onSigned(TENANT, new Signed(request, pkg.id(), ACTOR, List.of(hash), NOW)))
        .isFalse();
  }

  @Test
  void khoaHoSoLoiChiGhiNhatKy() {
    UnitProfile a = add(UNIT, "DA_KY_SO");
    PackageRow pkg = ready(UNIT, List.of(a));
    DossierSigningService s = service();
    UUID request = sign(s, pkg, "k").signRequestId();
    byte[] hash = repo.packages.get(pkg.id()).manifestHash();
    profiles.failLock = true;
    UUID signer = UUID.randomUUID();
    Instant at = Instant.parse("2027-01-14T00:00:00Z");
    assertThat(s.onSigned(TENANT, new Signed(request, pkg.id(), signer, List.of(hash), at)))
        .isTrue();
    assertThat(repo.packages.get(pkg.id()).submittedAt()).isEqualTo(at);
    assertThat(events).containsExactly("NOP:" + pkg.id() + ":2");
  }

  @Test
  void thieuCongHoSoMod04ThiBaoLoi() {
    api = null;
    UnitProfile a = add(UNIT, "DA_KY_SO");
    PackageRow pkg = ready(UNIT, List.of(a));
    assertThatThrownBy(() -> service().checklist(TENANT, UNIT, pkg.id()))
        .isInstanceOf(IllegalStateException.class);
  }

  // ---------------------------------------------------------------- EVT-22

  private PackageRow submitted(UUID unit) {
    UnitProfile p = add(unit, "DA_KY_SO");
    PackageRow pkg = ready(unit, List.of(p));
    repo.setStatus(pkg.id(), PackageStatus.DA_NOP);
    return repo.packages.get(pkg.id());
  }

  @Test
  void evt22LapPhienBanTheoPhamViVaGoiCuGiuNguyen() {
    UUID other = UUID.randomUUID();
    PackageRow mine = submitted(UNIT);
    PackageRow theirs = submitted(other);
    UnitProfile inMine = profiles.unitProfiles(TENANT, PERIOD, UNIT).get(0);
    DossierSigningService s = service();

    assertThat(
            s.onReopened(TENANT, new Reopened(PERIOD, "DON_VI", List.of(UUID.randomUUID()), null)))
        .isZero();
    assertThat(s.onReopened(TENANT, new Reopened(PERIOD, "LA", List.of(), ACTOR))).isZero();
    assertThat(
            s.onReopened(
                TENANT,
                new Reopened(PERIOD, "HO_SO", List.of(inMine.id(), UUID.randomUUID()), ACTOR)))
        .isEqualTo(1);
    PackageRow next = repo.findByUnit(TENANT, BUCKET, UNIT, PackageKind.NAM).orElseThrow();
    assertThat(next.versionNo()).isEqualTo(2);
    assertThat(next.status()).isEqualTo(PackageStatus.DANG_LAP);
    assertThat(next.code()).isEqualTo(mine.code());
    assertThat(repo.packages.get(mine.id())).isEqualTo(mine);
    assertThat(events).containsExactly("MO_LAI:" + mine.id());

    // Cả kỳ: chỉ đơn vị còn lại được lập; gói đã có phiên bản mới bỏ qua.
    assertThat(s.onReopened(TENANT, new Reopened(PERIOD, "TOAN_KY", List.of(), ACTOR)))
        .isEqualTo(1);
    assertThat(repo.findByUnit(TENANT, BUCKET, other, PackageKind.NAM).orElseThrow().versionNo())
        .isEqualTo(2);
    assertThat(repo.packages.get(theirs.id())).isEqualTo(theirs);
    assertThat(s.onReopened(TENANT, new Reopened(PERIOD, "DON_VI", List.of(UNIT, other), ACTOR)))
        .isZero();
  }

  @Test
  void evt22DuyetNhieuTrang() {
    for (int i = 0; i < 201; i++) {
      UUID unit = UUID.randomUUID();
      PackageRow pkg =
          repo.ensure(
                  TENANT,
                  new NewPackage(PERIOD, BUCKET, unit, PackageKind.NAM, null),
                  UUID.randomUUID(),
                  ACTOR)
              .row();
      repo.setStatus(pkg.id(), PackageStatus.DA_NOP);
    }
    assertThat(service().onReopened(TENANT, new Reopened(PERIOD, "TOAN_KY", List.of(), ACTOR)))
        .isEqualTo(201);
  }
}
