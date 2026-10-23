package vn.viettel.okrkpi.dossier.application.dossierpackage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi.Pack;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi.UnitProfile;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.config.api.PeriodStatus;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierPackageService.NewPackageCommand;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.Filter;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.FormRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.PackageRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.RenderRequests;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.FormStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageKind;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageStatus;
import vn.viettel.okrkpi.replica.api.AssignmentView;
import vn.viettel.okrkpi.replica.api.OrgDirectoryApi;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.VersionConflictException;
import vn.viettel.okrkpi.shared.kernel.PeriodId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Kiểm thử đơn vị CORE-06-06 trên kho trong bộ nhớ: lập gói khi EVT-12, điều kiện bắt đầu sinh
 * BR-11-02, EVT-17 tính lại theo lô, theo dõi tệp lỗi, API lập, xem, sinh lại và cổng nội bộ
 * SVC-11.
 */
class DossierPackageServiceTest {

  static final TenantId TENANT =
      TenantId.of(UUID.fromString("01927a06-0000-7000-8000-00000000aa01"));
  static final UUID PERIOD = UUID.fromString("01927a06-0000-7000-8000-00000000aa02");
  static final UUID UNIT = UUID.fromString("01927a06-0000-7000-8000-00000000aa03");
  static final UUID ACTOR = UUID.fromString("01927a06-0000-7000-8000-00000000aa04");
  static final int BUCKET = 20260;
  static final Pack PACK =
      new Pack(
          "PP-DANG",
          "1.3.0",
          List.of("BANG_CHAM_DIEM", "MAU_3"),
          List.of("MAU_3"),
          Map.of("MAU_3", 2));
  static final Instant NOW = Instant.parse("2027-01-10T03:00:00Z");

  final InMemoryPackages repo = new InMemoryPackages();
  final InMemoryPackages.Profiles profiles = new InMemoryPackages.Profiles();
  final FakeRender render = new FakeRender();
  final PeriodApi periodApi = mock(PeriodApi.class);
  final OrgDirectoryApi directory = mock(OrgDirectoryApi.class);
  final AtomicBoolean bound = new AtomicBoolean();
  DossierPackageService service;

  static final class FakeRender implements RenderRequests {
    final List<UUID> sent = new ArrayList<>();
    final Map<UUID, RequestState> states = new java.util.HashMap<>();
    RuntimeException failure;

    @Override
    public UUID request(TenantId tenant, UUID packageId, String idempotencyKey) {
      if (failure != null) {
        throw failure;
      }
      UUID id = UUID.randomUUID();
      sent.add(packageId);
      return id;
    }

    @Override
    public Optional<RequestState> state(TenantId tenant, UUID requestId) {
      return Optional.ofNullable(states.get(requestId));
    }
  }

  @BeforeEach
  void setUp() {
    service = service(() -> profiles, () -> periodApi);
  }

  @AfterEach
  void clearSync() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  private DossierPackageService service(
      java.util.function.Supplier<vn.viettel.okrkpi.appraisal.api.DossierProfilesApi> api,
      java.util.function.Supplier<PeriodApi> periods) {
    return new DossierPackageService(
        repo,
        api,
        render,
        periods,
        () -> directory,
        TransactionOperations.withoutTransaction(),
        bound::get,
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofMinutes(5));
  }

  static UnitProfile profile(UUID unit, String status, boolean head, Pack pack) {
    return new UnitProfile(
        UUID.randomUUID(),
        UUID.randomUUID(),
        PERIOD,
        BUCKET,
        unit,
        "DANG",
        status,
        "HTTNV",
        new BigDecimal("85.50"),
        UUID.randomUUID(),
        head,
        pack);
  }

  private UnitProfile add(String status, boolean head) {
    UnitProfile p = profile(UNIT, status, head, PACK);
    profiles.put(p);
    return p;
  }

  private PackageRow only() {
    return repo.packages.values().iterator().next();
  }

  private List<FormRow> forms(UUID pkg) {
    return repo.formsIn(TENANT, BUCKET, pkg, List.of());
  }

  // ---------------------------------------------------------------- EVT-12

  @Test
  void evt12CuaHoSoKhongCoHoacChuaQuyetDinhBiBoQua() {
    assertThat(service.onRatingDecided(TENANT, UUID.randomUUID(), ACTOR)).isEmpty();
    UnitProfile p = add("CHO_QUYET_DINH", false);
    assertThat(service.onRatingDecided(TENANT, p.id(), ACTOR)).isEmpty();
    assertThat(repo.packages).isEmpty();
    assertThatThrownBy(
            () -> service(() -> null, () -> periodApi).onRatingDecided(TENANT, p.id(), ACTOR))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void duHoSoQuyetDinhThiLapGoiThemMau3VaGuiLenhSinh() {
    UnitProfile head = add("DA_QUYET_DINH", true);
    UnitProfile other = add("CHO_QUYET_DINH", false);
    PackageRow pkg = service.onRatingDecided(TENANT, head.id(), null).orElseThrow();
    assertThat(pkg.status()).isEqualTo(PackageStatus.DANG_LAP);
    assertThat(forms(pkg.id())).extracting(FormRow::formCode).containsExactly("BANG_CHAM_DIEM");
    assertThat(render.sent).isEmpty();

    // Người còn lại là trường hợp đặc thù đã kết luận: đủ điều kiện sinh.
    repo.settled.add(other.personId());
    service.onRatingDecided(TENANT, head.id(), ACTOR);
    assertThat(only().status()).isEqualTo(PackageStatus.DANG_SINH);
    assertThat(forms(pkg.id())).extracting(FormRow::formCode).contains("MAU_3");
    assertThat(forms(pkg.id()))
        .filteredOn(f -> f.formCode().equals("MAU_3"))
        .extracting(FormRow::templateVersion)
        .containsExactly("2");
    assertThat(render.sent).containsExactly(pkg.id());
    assertThat(forms(pkg.id())).allMatch(f -> f.renderJobId() != null);

    // EVT-12 lặp khi gói đang sinh: không thêm biểu mẫu, không gửi lệnh mới.
    int before = forms(pkg.id()).size();
    service.onRatingDecided(TENANT, head.id(), ACTOR);
    assertThat(forms(pkg.id())).hasSize(before);
    assertThat(render.sent).hasSize(1);
  }

  @Test
  void quyetDinhLaiKhiChuaSinhThiGhiLaiBam() {
    UnitProfile p = add("DA_QUYET_DINH", false);
    add("CHO_QUYET_DINH", false);
    PackageRow pkg = service.onRatingDecided(TENANT, p.id(), ACTOR).orElseThrow();
    byte[] first = forms(pkg.id()).get(0).dataSnapshotHash();
    profiles.put(
        new UnitProfile(
            p.id(),
            p.personId(),
            PERIOD,
            BUCKET,
            UNIT,
            "DANG",
            "DA_QUYET_DINH",
            "HTXSNV",
            new BigDecimal("95"),
            p.scoreSnapshotId(),
            false,
            PACK));
    service.onRatingDecided(TENANT, p.id(), ACTOR);
    assertThat(forms(pkg.id())).hasSize(1);
    assertThat(forms(pkg.id()).get(0).dataSnapshotHash()).isNotEqualTo(first);
  }

  @Test
  void svc11KhongNhanLenhThiTepLoiVaGoiVeDangLap() {
    UnitProfile p = add("DA_QUYET_DINH", true);
    render.failure = new RenderRequests.RenderUnavailableException("lỗi", null);
    service.onRatingDecided(TENANT, p.id(), ACTOR);
    assertThat(only().status()).isEqualTo(PackageStatus.DANG_LAP);
    assertThat(forms(only().id())).allMatch(f -> f.status() == FormStatus.LOI);
    // Còn tệp lỗi thì EVT-12 lặp không tự sinh lại.
    render.failure = null;
    service.onRatingDecided(TENANT, p.id(), ACTOR);
    assertThat(render.sent).isEmpty();
  }

  @Test
  void goiKhongCoBieuMauHoacDaSinhDuThiKhongGuiLenh() {
    PackageRow empty =
        repo.ensure(
                TENANT,
                new vn.viettel.okrkpi.dossier.application.dossierpackage.port
                    .DossierPackageRepository.NewPackage(
                    PERIOD, BUCKET, UNIT, PackageKind.NAM, null),
                UUID.randomUUID(),
                ACTOR)
            .row();
    assertThat(service.start(TENANT, empty.id(), ACTOR)).isFalse();
    assertThat(service.start(TENANT, UUID.randomUUID(), ACTOR)).isFalse();

    UnitProfile p = add("DA_QUYET_DINH", true);
    service.onRatingDecided(TENANT, p.id(), ACTOR);
    PackageRow pkg = only();
    assertThat(service.start(TENANT, pkg.id(), ACTOR)).isFalse();
    for (FormRow f : forms(pkg.id())) {
      repo.setForm(f.id(), FormStatus.DA_SINH, "k/" + f.id(), new byte[32]);
    }
    repo.setStatus(pkg.id(), PackageStatus.DANG_LAP);
    assertThat(service.start(TENANT, pkg.id(), ACTOR)).isFalse();
    assertThat(only().status()).isEqualTo(PackageStatus.CHO_KY);
  }

  // ---------------------------------------------------------------- EVT-17 và theo dõi

  private PackageRow generating() {
    UnitProfile p = add("DA_QUYET_DINH", true);
    add("DA_KY_SO", false);
    service.onRatingDecided(TENANT, p.id(), ACTOR);
    assertThat(only().status()).isEqualTo(PackageStatus.DANG_SINH);
    return only();
  }

  @Test
  void evt17TinhLaiTheoLoVaEvt17TrungBiBoQua() {
    PackageRow pkg = generating();
    assertThat(service.onFormGenerated(TENANT, UUID.randomUUID(), null, "k", new byte[32], ACTOR))
        .isFalse();
    List<FormRow> all = forms(pkg.id());
    for (int i = 0; i < all.size() - 1; i++) {
      assertThat(
              service.onFormGenerated(
                  TENANT, all.get(i).id(), UUID.randomUUID(), "k" + i, new byte[32], ACTOR))
          .isTrue();
    }
    assertThat(service.settle()).isZero();
    assertThat(only().status()).isEqualTo(PackageStatus.DANG_SINH);

    // Trong giao dịch: chỉ đánh dấu sau khi commit.
    TransactionSynchronizationManager.initSynchronization();
    FormRow last = all.get(all.size() - 1);
    assertThat(
            service.onFormGenerated(TENANT, last.id(), UUID.randomUUID(), "k", new byte[32], ACTOR))
        .isTrue();
    assertThat(service.unsettled()).isZero();
    for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
      s.afterCommit();
    }
    TransactionSynchronizationManager.clearSynchronization();
    assertThat(service.unsettled()).isEqualTo(1);
    assertThat(service.settle()).isEqualTo(1);
    assertThat(only().status()).isEqualTo(PackageStatus.CHO_KY);
    assertThat(
            service.onFormGenerated(TENANT, last.id(), UUID.randomUUID(), "k", new byte[32], ACTOR))
        .isFalse();
  }

  @Test
  void lenhSinhKetThucMaConTepChoThiTepLoiGoiVeDangLap() {
    PackageRow pkg = generating();
    UUID request = forms(pkg.id()).get(0).renderJobId();
    // Lệnh chưa kết thúc: không đổi.
    render.states.put(request, new RenderRequests.RequestState(false, null));
    assertThat(service.track(TENANT, 50)).isZero();
    // Kết thúc chưa quá thời gian chờ: không đổi.
    render.states.put(request, new RenderRequests.RequestState(true, NOW.minusSeconds(60)));
    assertThat(service.track(TENANT, 50)).isZero();
    render.states.put(request, new RenderRequests.RequestState(true, NOW.minusSeconds(600)));
    assertThat(service.track(TENANT, 1)).isEqualTo(forms(pkg.id()).size());
    assertThat(only().status()).isEqualTo(PackageStatus.DANG_LAP);
  }

  @Test
  void bieuMauChoChuaGanLenhThiTheoDoiGuiLai() {
    PackageRow pkg = generating();
    repo.resetFailed(TENANT, BUCKET, pkg.id(), List.of(), ACTOR);
    for (FormRow f : forms(pkg.id())) {
      repo.forms.put(f.id(), InMemoryPackages.form(f, FormStatus.CHO_SINH, null, null, null));
    }
    assertThat(service.track(TENANT, 50)).isZero();
    assertThat(render.sent).hasSize(2);
    assertThat(forms(pkg.id())).allMatch(f -> f.renderJobId() != null);
  }

  @Test
  void loiKhiTheoDoiMotGoiKhongDungCaVong() {
    generating();
    render.states.clear();
    RenderRequests broken =
        new RenderRequests() {
          @Override
          public UUID request(TenantId tenant, UUID packageId, String key) {
            return UUID.randomUUID();
          }

          @Override
          public Optional<RequestState> state(TenantId tenant, UUID requestId) {
            throw new IllegalStateException("SVC-11 lỗi");
          }
        };
    DossierPackageService other =
        new DossierPackageService(
            repo,
            () -> profiles,
            broken,
            () -> periodApi,
            () -> directory,
            TransactionOperations.withoutTransaction(),
            bound::get,
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ZERO);
    assertThat(other.track(TENANT, 50)).isZero();
  }

  @Test
  void trongGiaoDichGiuKetNoiThiLenhSinhXepHangChoSettle() {
    bound.set(true);
    UnitProfile p = add("DA_QUYET_DINH", true);
    TransactionSynchronizationManager.initSynchronization();
    service.onRatingDecided(TENANT, p.id(), ACTOR);
    assertThat(render.sent).isEmpty();
    for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
      s.afterCommit();
    }
    TransactionSynchronizationManager.clearSynchronization();
    assertThat(service.unsettled()).isEqualTo(1);
    render.failure = new IllegalStateException("không gửi được");
    service.settle();
    assertThat(render.sent).isEmpty();
    assertThat(service.unsettled()).isZero();
  }

  // ---------------------------------------------------------------- API

  private PeriodSnapshot period(LocalDate start, LocalDate end) {
    return new PeriodSnapshot(
        PeriodId.of(PERIOD), TENANT, "N-2026", "Năm 2026", start, end, PeriodStatus.values()[0]);
  }

  @Test
  void lapGoiThuCongKiemPhamViKyLoaiVaTrung() {
    NewPackageCommand cmd =
        new NewPackageCommand(PERIOD, UNIT, PackageKind.NAM, LocalDate.of(2027, 2, 1));
    assertThatThrownBy(() -> service.create(TENANT, ACTOR, UUID.randomUUID(), cmd))
        .hasMessage("COMMON-403-OUT-OF-SCOPE");
    assertThatThrownBy(() -> service.create(TENANT, ACTOR, null, cmd))
        .hasMessage("COMMON-403-OUT-OF-SCOPE");
    assertThatThrownBy(() -> service(() -> profiles, () -> null).create(TENANT, ACTOR, UNIT, cmd))
        .hasMessage("DOSSIER-422-VALIDATION");
    when(periodApi.findById(any(), any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.create(TENANT, ACTOR, UNIT, cmd))
        .hasMessage("DOSSIER-422-VALIDATION");
    when(periodApi.findById(any(), any()))
        .thenReturn(Optional.of(period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))));
    assertThatThrownBy(
            () ->
                service.create(
                    TENANT,
                    ACTOR,
                    UNIT,
                    new NewPackageCommand(PERIOD, UNIT, PackageKind.QUY, null)))
        .hasMessage("DOSSIER-422-VALIDATION");
    assertThatThrownBy(() -> service.create(TENANT, ACTOR, UNIT, cmd))
        .hasMessage("DOSSIER-409-PERIOD-NOT-DECIDED");

    add("DA_QUYET_DINH", true);
    add("CHO_QUYET_DINH", false);
    DossierPackageService.PackageView view = service.create(TENANT, ACTOR, UNIT, cmd);
    assertThat(view.row().status()).isEqualTo(PackageStatus.DANG_LAP);
    assertThat(view.progress().total()).isEqualTo(1);
    assertThat(view.profileCount()).isEqualTo(1);
    assertThatThrownBy(() -> service.create(TENANT, ACTOR, UNIT, cmd))
        .hasMessage("DOSSIER-409-PACKAGE-EXISTS");

    // Gói diện Trung ương không tự thêm biểu mẫu.
    DossierPackageService.PackageView tw =
        service.create(
            TENANT, ACTOR, UNIT, new NewPackageCommand(PERIOD, UNIT, PackageKind.DIEN_TW, null));
    assertThat(tw.progress().total()).isZero();
  }

  @Test
  void xemDanhSachVaBieuMauTheoPhamViDonVi() {
    PackageRow pkg = generating();
    assertThat(service.list(TENANT, null, new Filter(null, null, null, null), null, 10)).isEmpty();
    assertThat(
            service.list(TENANT, UNIT, new Filter(null, UUID.randomUUID(), null, null), null, 10))
        .isEmpty();
    assertThat(service.list(TENANT, UNIT, new Filter(PERIOD, UNIT, null, null), null, 10))
        .hasSize(1);
    assertThat(service.get(TENANT, UNIT, pkg.id())).isPresent();
    assertThat(service.get(TENANT, UNIT, UUID.randomUUID())).isEmpty();
    assertThatThrownBy(() -> service.get(TENANT, UUID.randomUUID(), pkg.id()))
        .isInstanceOf(BusinessException.class);
    assertThat(service.view(TENANT, pkg.id())).isPresent();
    assertThat(service.forms(TENANT, UNIT, pkg.id(), FormStatus.CHO_SINH, null, null, 10))
        .hasSize(3);
    assertThatThrownBy(() -> service.forms(TENANT, UNIT, UUID.randomUUID(), null, null, null, 10))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void sinhLaiChiTepLoiVaKiemTrangThaiPhienBan() {
    PackageRow pkg = generating();
    assertThatThrownBy(
            () -> service.regenerate(TENANT, ACTOR, UNIT, pkg.id(), only().rowVersion(), null))
        .hasMessage("DOSSIER-409-INVALID-STATE");
    repo.markFailed(TENANT, BUCKET, pkg.id(), null, ACTOR);
    repo.setStatus(pkg.id(), PackageStatus.DANG_LAP);
    long version = only().rowVersion();
    assertThatThrownBy(() -> service.regenerate(TENANT, ACTOR, UNIT, pkg.id(), version + 5, null))
        .isInstanceOf(VersionConflictException.class);
    UUID first = forms(pkg.id()).get(0).id();
    DossierPackageService.PackageView view =
        service.regenerate(TENANT, ACTOR, UNIT, pkg.id(), version, List.of(first));
    assertThat(view.row().status()).isEqualTo(PackageStatus.DANG_SINH);
    assertThat(render.sent).hasSize(2);
    assertThat(repo.findForm(TENANT, first).orElseThrow().status()).isEqualTo(FormStatus.CHO_SINH);

    repo.setStatus(pkg.id(), PackageStatus.DANG_LAP);
    repo.forms.replaceAll(
        (id, f) -> InMemoryPackages.form(f, FormStatus.DA_SINH, null, "k", new byte[32]));
    assertThatThrownBy(
            () -> service.regenerate(TENANT, ACTOR, UNIT, pkg.id(), only().rowVersion(), null))
        .hasMessage("DOSSIER-409-NO-FAILED-FORMS");
    repo.setStatus(pkg.id(), PackageStatus.DA_KY);
    assertThatThrownBy(
            () -> service.regenerate(TENANT, ACTOR, UNIT, pkg.id(), only().rowVersion(), null))
        .hasMessage("DOSSIER-409-PACKAGE-LOCKED");
    assertThatThrownBy(() -> service.regenerate(TENANT, ACTOR, UNIT, UUID.randomUUID(), 0, null))
        .isInstanceOf(NoSuchElementException.class);
  }

  // ---------------------------------------------------------------- cổng nội bộ SVC-11

  @Test
  void dauVaoSinhChiTraBieuMauChoCuaGoiDangSinh() {
    assertThat(service.renderInputs(TENANT, null, UUID.randomUUID(), List.of())).isEmpty();
    PackageRow pkg = generating();
    List<DossierPackageService.RenderInput> all =
        service.renderInputs(TENANT, null, pkg.id(), null).orElseThrow();
    assertThat(all).hasSize(3);
    assertThat(service.renderInputs(TENANT, null, pkg.id(), List.of("MAU_3")).orElseThrow())
        .singleElement()
        .satisfies(
            i -> {
              assertThat(i.templateVersion()).isEqualTo(2);
              assertThat(i.policyPackCode()).isEqualTo("PP-DANG");
              assertThat(i.dataSnapshotHash()).hasSize(64);
            });

    // Người gọi có phân công nhưng ở đơn vị khác: không thấy.
    UUID caller = UUID.randomUUID();
    when(directory.assignmentsOf(any(), any(), any()))
        .thenReturn(
            List.of(
                new AssignmentView(
                    UUID.randomUUID(),
                    caller,
                    UUID.randomUUID(),
                    null,
                    null,
                    "CHINH",
                    "DANG",
                    false,
                    null,
                    null)));
    assertThat(service.renderInputs(TENANT, caller, pkg.id(), null)).isEmpty();
    when(directory.assignmentsOf(any(), any(), any()))
        .thenReturn(
            List.of(
                new AssignmentView(
                    UUID.randomUUID(),
                    caller,
                    UNIT,
                    null,
                    null,
                    "CHINH",
                    "DANG",
                    false,
                    null,
                    null)));
    assertThat(service.renderInputs(TENANT, caller, pkg.id(), null)).isPresent();

    repo.setStatus(pkg.id(), PackageStatus.DANG_LAP);
    assertThatThrownBy(() -> service.renderInputs(TENANT, null, pkg.id(), null))
        .hasMessage("DOSSIER-409-INVALID-STATE");
  }

  @Test
  void duLieuChuanTacCuaBieuMauCaNhanVaDonVi() {
    assertThat(service.renderData(TENANT, UUID.randomUUID())).isEmpty();
    PackageRow pkg = generating();
    for (FormRow f : forms(pkg.id())) {
      assertThat(service.renderData(TENANT, f.id())).isPresent();
    }
    FormRow first = forms(pkg.id()).get(0);
    profiles.profiles.remove(first.profileId());
    assertThat(service.renderData(TENANT, first.id())).isEmpty();
    repo.packages.clear();
    assertThat(service.renderData(TENANT, forms(pkg.id()).get(1).id())).isEmpty();
  }

  // ---------------------------------------------------------------- DV-11-02, DV-11-03

  @Test
  void bieuMauNhapDonViTrenTapDaChonVaDuLieuKhopBam() throws Exception {
    UnitProfile head = add("DA_QUYET_DINH", true);
    UnitProfile open = add("DANG_DANH_GIA", false);
    add("DA_QUYET_DINH", false);

    List<DossierPackageService.RenderInput> drafts =
        service
            .draftForms(TENANT, ACTOR, "MAU_3", PERIOD, List.of(open.id(), head.id(), head.id()))
            .orElseThrow();
    assertThat(drafts).hasSize(1);
    DossierPackageService.RenderInput d = drafts.get(0);
    assertThat(d.templateVersion()).isEqualTo(2);
    assertThat(d.policyPackCode()).isEqualTo("PP-DANG");
    assertThat(d.reopenRequestId()).isNull();
    FormRow row = repo.findForm(TENANT, d.formInstanceId()).orElseThrow();
    assertThat(row.packageId()).isNull();
    assertThat(row.profileId()).isEqualTo(head.id());
    assertThat(repo.sources.get(row.id())).containsExactlyInAnyOrder(head.id(), open.id());

    byte[] data = service.renderData(TENANT, d.formInstanceId()).orElseThrow();
    assertThat(java.util.HexFormat.of().formatHex(PackageArchiveTest.sha(data)))
        .isEqualTo(d.dataSnapshotHash());
    String json = new String(data, java.nio.charset.StandardCharsets.UTF_8);
    assertThat(json).contains(open.id().toString()).contains(UNIT.toString());
    assertThat(repo.packages).isEmpty();
  }

  @Test
  void bieuMauNhapCaNhanMoiHoSoMotBieuMauVaKiemDauVao() throws Exception {
    UnitProfile a = add("DA_QUYET_DINH", false);
    UnitProfile other = profile(UUID.randomUUID(), "DA_QUYET_DINH", false, PACK);
    profiles.put(other);

    List<DossierPackageService.RenderInput> drafts =
        service
            .draftForms(TENANT, null, "BANG_CHAM_DIEM", PERIOD, List.of(a.id(), other.id()))
            .orElseThrow();
    assertThat(drafts).hasSize(2);
    for (var d : drafts) {
      byte[] data = service.renderData(TENANT, d.formInstanceId()).orElseThrow();
      assertThat(java.util.HexFormat.of().formatHex(PackageArchiveTest.sha(data)))
          .isEqualTo(d.dataSnapshotHash());
      assertThat(d.templateVersion()).isNull();
    }
    // Biểu mẫu đơn vị trên hồ sơ của nhiều đơn vị: không ghi đơn vị.
    var mixed =
        service
            .draftForms(TENANT, null, "MAU_3", PERIOD, List.of(a.id(), other.id()))
            .orElseThrow();
    assertThat(new String(service.renderData(TENANT, mixed.get(0).formInstanceId()).orElseThrow()))
        .contains("\"donViId\":null");

    assertThat(service.draftForms(TENANT, null, "MAU_3", UUID.randomUUID(), List.of(a.id())))
        .isEmpty();
    assertThat(service.draftForms(TENANT, null, "MAU_3", PERIOD, List.of(UUID.randomUUID())))
        .isEmpty();
    assertThatThrownBy(() -> service.draftForms(TENANT, null, "MAU_9", PERIOD, List.of(a.id())))
        .hasMessage("DOSSIER-422-VALIDATION");
    assertThatThrownBy(() -> service.draftForms(TENANT, null, "MAU_3", PERIOD, List.of()))
        .hasMessage("DOSSIER-422-VALIDATION");
    assertThatThrownBy(() -> service.draftForms(TENANT, null, "MAU_3", null, List.of(a.id())))
        .hasMessage("DOSSIER-422-VALIDATION");

    // Người gọi có phân công ở đơn vị khác: không thấy hồ sơ.
    UUID caller = UUID.randomUUID();
    when(directory.assignmentsOf(any(), any(), any()))
        .thenReturn(
            List.of(
                new AssignmentView(
                    UUID.randomUUID(),
                    caller,
                    UUID.randomUUID(),
                    null,
                    null,
                    "CHINH",
                    "DANG",
                    false,
                    null,
                    null)));
    assertThat(service.draftForms(TENANT, caller, "MAU_3", PERIOD, List.of(a.id()))).isEmpty();
    assertThat(service.preview(TENANT, caller, "BANG_CHAM_DIEM", "appraisal", a.id())).isEmpty();
  }

  @Test
  void xemTruocTheoHoSoHoacBieuMauDaCo() {
    UnitProfile head = add("DA_QUYET_DINH", true);
    UnitProfile open = add("DANG_DANH_GIA", false);

    DossierPackageService.PreviewInput personal =
        service.preview(TENANT, ACTOR, "BANG_CHAM_DIEM", "appraisal", open.id()).orElseThrow();
    assertThat(personal.policyPackVersion()).isEqualTo("1.3.0");
    assertThat(personal.templateVersion()).isNull();
    assertThat(personal.data()).contains(open.id().toString()).doesNotContain(head.id().toString());

    DossierPackageService.PreviewInput unit =
        service.preview(TENANT, ACTOR, "MAU_3", "appraisal", head.id()).orElseThrow();
    assertThat(unit.templateVersion()).isEqualTo(2);
    assertThat(unit.data()).contains(open.id().toString()).contains(head.id().toString());

    var drafts =
        service.draftForms(TENANT, ACTOR, "MAU_3", PERIOD, List.of(head.id())).orElseThrow();
    UUID form = drafts.get(0).formInstanceId();
    assertThat(service.preview(TENANT, ACTOR, "MAU_3", "dossier", form).orElseThrow().data())
        .contains(head.id().toString());
    assertThat(service.preview(TENANT, ACTOR, "MAU_2", "dossier", form)).isEmpty();
    assertThat(service.preview(TENANT, ACTOR, "MAU_3", "dossier", UUID.randomUUID())).isEmpty();
    assertThat(service.preview(TENANT, ACTOR, "MAU_3", "oversight", head.id())).isEmpty();
    assertThat(service.preview(TENANT, ACTOR, "MAU_3", "appraisal", UUID.randomUUID())).isEmpty();
    assertThat(service.preview(TENANT, ACTOR, "MAU_3", "appraisal", null)).isEmpty();
    assertThatThrownBy(() -> service.preview(TENANT, ACTOR, null, "appraisal", head.id()))
        .hasMessage("DOSSIER-422-VALIDATION");
  }

  @Test
  void xemTruocTuNguonModuleKhacChonGoiTheoHoSo() {
    UnitProfile head = add("DA_QUYET_DINH", true);
    byte[] data = "{\"complaint\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    DossierPackageService.PreviewInput withProfile =
        service.previewFromSource(TENANT, "TRA_LOI_KN", head.id(), data);
    assertThat(withProfile.policyPackVersion()).isEqualTo("1.3.0");
    assertThat(withProfile.templateVersion()).isNull();
    assertThat(withProfile.data()).isEqualTo("{\"complaint\":{}}");
    assertThat(service.previewFromSource(TENANT, "TRA_LOI_KN", null, data).policyPackCode())
        .isNull();
    assertThat(
            service
                .previewFromSource(TENANT, "TRA_LOI_KN", UUID.randomUUID(), data)
                .policyPackCode())
        .isNull();
    assertThatThrownBy(() -> service.previewFromSource(TENANT, "tra loi", head.id(), data))
        .hasMessage("DOSSIER-422-VALIDATION");
    assertThatThrownBy(() -> service.previewFromSource(TENANT, null, head.id(), data))
        .hasMessage("DOSSIER-422-VALIDATION");
  }

  @Test
  void phienBanSauMoLaiKyTraMaYeuCauMoLai() {
    PackageRow pkg = generating();
    UUID reopen = UUID.randomUUID();
    repo.reopens.put(pkg.id(), reopen);
    assertThat(service.renderInputs(TENANT, null, pkg.id(), null).orElseThrow())
        .isNotEmpty()
        .allSatisfy(i -> assertThat(i.reopenRequestId()).isEqualTo(reopen));
  }

  @Test
  void goiKhongPolicyPackDungDanhMucMacDinh() {
    UnitProfile p = profile(UNIT, "DA_QUYET_DINH", false, null);
    profiles.put(p);
    service.onRatingDecided(TENANT, p.id(), ACTOR);
    assertThat(forms(only().id()))
        .extracting(FormRow::formCode)
        .containsExactlyInAnyOrder("BANG_CHAM_DIEM", "MAU_3");
    assertThat(forms(only().id())).allMatch(f -> f.templateVersion().isEmpty());
    List<DossierPackageService.RenderInput> inputs =
        service.renderInputs(TENANT, null, only().id(), null).orElseThrow();
    assertThat(inputs).allMatch(i -> i.policyPackCode() == null && i.templateVersion() == null);
  }

  @Test
  void hamTienIch() {
    assertThat(DossierPackageService.templateVersion(null)).isNull();
    assertThat(DossierPackageService.templateVersion("")).isNull();
    assertThat(DossierPackageService.templateVersion("0")).isNull();
    assertThat(DossierPackageService.templateVersion("12")).isEqualTo(12);
    assertThat(
            DossierPackageService.bucket(
                period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))))
        .isEqualTo(20260);
    assertThat(
            DossierPackageService.bucket(
                period(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30))))
        .isEqualTo(20262);
  }

  @Test
  void evt17MuonLamGoiDangLapDuTepThiSangChoKy() {
    PackageRow pkg = generating();
    List<FormRow> all = forms(pkg.id());
    repo.markFailed(TENANT, BUCKET, pkg.id(), null, ACTOR);
    assertThat(service.recompute(TENANT, BUCKET, pkg.id())).isTrue();
    assertThat(only().status()).isEqualTo(PackageStatus.DANG_LAP);
    assertThat(service.recompute(TENANT, BUCKET, pkg.id())).isFalse();
    for (FormRow f : all) {
      repo.markGenerated(TENANT, BUCKET, f.id(), null, "k", new byte[32], ACTOR);
    }
    assertThat(service.recompute(TENANT, BUCKET, pkg.id())).isTrue();
    assertThat(only().status()).isEqualTo(PackageStatus.CHO_KY);
    assertThat(service.recompute(TENANT, BUCKET, UUID.randomUUID())).isFalse();
  }
}
