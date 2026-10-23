package vn.viettel.okrkpi.dossier.application.dossierpackage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi.Pack;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi.UnitProfile;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierTransferService.BatchStatus;
import vn.viettel.okrkpi.dossier.application.dossierpackage.DossierTransferService.TransferJob;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.CentralTitles;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.NewForm;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.NewPackage;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.PackageRow;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.EdxmlGateway;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.PackageTransferRepository.TransferRow;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.FormStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageKind;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Gửi gói con diện TW (CORE-06-08, {@code createDossierPackageTransfer}) với bản giả SVC-13: gửi
 * thành công tới biên nhận, bên nhận không phản hồi (thử lại theo lịch, MSG-11-06), chứng thư bên
 * nhận hết hạn (hết lượt); gửi lỗi không đổi {@code DA_NOP} của gói chính.
 */
class DossierTransferServiceTest {

  static final TenantId TENANT =
      TenantId.of(UUID.fromString("01927a06-0000-7000-8000-00000000ee01"));
  static final UUID PERIOD = UUID.fromString("01927a06-0000-7000-8000-00000000ee02");
  static final UUID UNIT = UUID.fromString("01927a06-0000-7000-8000-00000000ee03");
  static final UUID ACTOR = UUID.fromString("01927a06-0000-7000-8000-00000000ee04");
  static final int BUCKET = 20260;
  static final Instant NOW = Instant.parse("2027-01-15T02:00:00Z");
  static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
  static final Pack PACK =
      new Pack("PP-DANG", "1.3.0", List.of("BANG_CHAM_DIEM"), List.of("MAU_3"), Map.of());

  final InMemoryPackages repo = new InMemoryPackages();
  final InMemoryPackages.Profiles profiles = new InMemoryPackages.Profiles();
  final TransferFakes.Transfers transfers = new TransferFakes.Transfers();
  final TransferFakes.Files files = new TransferFakes.Files();
  final TransferFakes.Events events = new TransferFakes.Events();
  final TransferFakes.Gateway fake = new TransferFakes.Gateway();
  final Set<UUID> central = new HashSet<>();
  final List<Runnable> queued = new ArrayList<>();
  EdxmlGateway gateway = fake;
  Executor runner = queued::add;
  CentralTitles titles =
      (tenant, persons, on) -> {
        Set<UUID> out = new HashSet<>(persons);
        out.retainAll(central);
        return out;
      };

  DossierTransferService service() {
    return new DossierTransferService(
        repo,
        transfers,
        () -> profiles,
        titles,
        () -> gateway,
        files,
        events,
        TransactionOperations.withoutTransaction(),
        TransactionOperations.withoutTransaction(),
        runner,
        Clock.fixed(NOW, ZoneOffset.UTC),
        ZONE);
  }

  private UnitProfile person(boolean centralManaged) {
    UnitProfile p =
        new UnitProfile(
            UUID.randomUUID(),
            UUID.randomUUID(),
            PERIOD,
            BUCKET,
            UNIT,
            "DANG",
            "DA_KHOA",
            "HTTNV",
            BigDecimal.TEN,
            null,
            false,
            PACK);
    profiles.put(p);
    if (centralManaged) {
      central.add(p.personId());
    }
    return p;
  }

  /** Gói đã nộp: mỗi hồ sơ một bảng chấm điểm, tệp có trên kho, manifest đã chốt, có chữ ký. */
  private PackageRow submitted(List<UnitProfile> people, PackageStatus status) {
    PackageRow pkg =
        repo.ensure(
                TENANT,
                new NewPackage(PERIOD, BUCKET, UNIT, PackageKind.NAM, null),
                UUID.randomUUID(),
                ACTOR)
            .row();
    List<NewForm> forms = new ArrayList<>();
    for (UnitProfile p : people) {
      forms.add(new NewForm(UUID.randomUUID(), p.id(), "BANG_CHAM_DIEM", "1", new byte[32], false));
    }
    repo.insertForms(TENANT, pkg, forms, ACTOR);
    for (var f : repo.formsIn(TENANT, BUCKET, pkg.id(), List.of())) {
      files.put("k/" + f.id(), ("%PDF " + f.id()).getBytes(StandardCharsets.UTF_8));
      repo.setForm(f.id(), FormStatus.DA_KY, "k/" + f.id(), new byte[32]);
    }
    repo.manifests.put(pkg.id(), "[{\"chot\":true}]");
    repo.signatures.put(pkg.id(), "ky/goi.p7s");
    files.put("ky/goi.p7s", new byte[] {7});
    repo.setStatus(pkg.id(), status);
    return repo.packages.get(pkg.id());
  }

  private TransferJob request(PackageRow pkg, List<UUID> ids) {
    return service().request(TENANT, ACTOR, UNIT, pkg.id(), "BTC_TW", ids);
  }

  private void drain() {
    List<Runnable> work = new ArrayList<>(queued);
    queued.clear();
    work.forEach(Runnable::run);
  }

  // ---------------------------------------------------------------- điều kiện

  @Test
  void goiChuaKyBi409() {
    UnitProfile a = person(true);
    PackageRow pkg = submitted(List.of(a), PackageStatus.CHO_KY);
    assertThatThrownBy(() -> request(pkg, List.of()))
        .isInstanceOf(BusinessException.class)
        .hasMessage("DOSSIER-409-PACKAGE-NOT-SIGNED");
  }

  @Test
  void khongCoHoSoDienTwBi409() {
    UnitProfile a = person(false);
    UnitProfile b = person(true);
    PackageRow pkg = submitted(List.of(a, b), PackageStatus.DA_NOP);
    assertThatThrownBy(() -> request(pkg, List.of(a.id())))
        .isInstanceOf(BusinessException.class)
        .hasMessage("DOSSIER-409-NO-CENTRAL-PROFILES");
    central.clear();
    assertThatThrownBy(() -> request(pkg, List.of())).hasMessage("DOSSIER-409-NO-CENTRAL-PROFILES");
  }

  @Test
  void phamViNoiNhanVaCongChuaCauHinh() {
    UnitProfile a = person(true);
    PackageRow pkg = submitted(List.of(a), PackageStatus.DA_NOP);
    assertThatThrownBy(
            () -> service().request(TENANT, ACTOR, UUID.randomUUID(), pkg.id(), "BTC_TW", null))
        .hasMessage("COMMON-403-OUT-OF-SCOPE");
    assertThatThrownBy(() -> service().request(TENANT, ACTOR, UNIT, pkg.id(), "XYZ", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> service().request(TENANT, ACTOR, UNIT, UUID.randomUUID(), "BTC_TW", null))
        .isInstanceOf(NoSuchElementException.class);
    gateway = null;
    assertThatThrownBy(() -> request(pkg, List.of()))
        .isInstanceOf(DossierTransferService.TransferUnavailableException.class);
    assertThat(service().poll(TENANT, 10)).isZero();
  }

  @Test
  void noiNhanChuaCauHinhTra422VaHuyGoiCon() {
    UnitProfile a = person(true);
    UnitProfile b = person(true);
    PackageRow pkg = submitted(List.of(a, b), PackageStatus.DA_NOP);
    fake.sendResults.add(new EdxmlGateway.DestinationNotConfiguredException("BTC_TW"));
    assertThatThrownBy(() -> request(pkg, List.of()))
        .isInstanceOf(BusinessException.class)
        .hasMessage("DOSSIER-422-DESTINATION-NOT-CONFIGURED");
    assertThat(transfers.rows.values()).extracting(TransferRow::status).containsOnly("HUY");
    assertThat(queued).isEmpty();
    // Gói con đã huỷ không chặn lần gửi sau khi nơi nhận đã cấu hình.
    TransferJob again = request(pkg, List.of());
    assertThat(again.rows()).hasSize(2);
  }

  // ---------------------------------------------------------------- gửi thành công

  @Test
  void guiThanhCongToiBienNhanThiGoiChuyenLuuTru() {
    UnitProfile a = person(true);
    UnitProfile b = person(true);
    UnitProfile c = person(false);
    PackageRow pkg = submitted(List.of(a, b, c), PackageStatus.DA_NOP);
    DossierTransferService s = service();

    TransferJob job = s.request(TENANT, ACTOR, UNIT, pkg.id(), "BTC_TW", List.of());
    assertThat(job.rows()).hasSize(2);
    assertThat(job.rows())
        .extracting(TransferRow::personId)
        .containsExactlyInAnyOrder(a.personId(), b.personId());
    assertThat(events.log).containsExactly("GUI:BTC_TW:2");
    // Gói con đầu gửi ngay, gói con còn lại chờ luồng nền.
    assertThat(fake.sent).hasSize(1);
    assertThat(queued).hasSize(1);
    drain();
    assertThat(fake.sent).hasSize(2);
    EdxmlGateway.Outgoing first = fake.sent.get(0);
    assertThat(first.destination()).isEqualTo("BTC_TW");
    assertThat(first.documentNumber()).isEqualTo(pkg.code());
    assertThat(first.attachments())
        .extracting(EdxmlGateway.Attachment::name)
        .contains("manifest.json", "manifest.json.p7s", "thong-tin-goi-con.json")
        .anyMatch(n -> n.startsWith("bieu-mau_BANG_CHAM_DIEM_"));
    assertThat(s.job(TENANT, job.batchId()).orElseThrow().status())
        .isEqualTo(BatchStatus.DANG_CHAY);

    for (EdxmlGateway.Outgoing o : fake.sent) {
      fake.set(o.transferId(), "DA_GUI", 1, null, null);
    }
    s.poll(TENANT, 10);
    assertThat(repo.packages.get(pkg.id()).status()).isEqualTo(PackageStatus.DA_NOP);

    fake.set(fake.sent.get(0).transferId(), "DA_XAC_NHAN", 1, null, null);
    s.poll(TENANT, 10);
    // Mới một gói con có biên nhận: gói chính chưa lưu trữ.
    assertThat(repo.packages.get(pkg.id()).status()).isEqualTo(PackageStatus.DA_NOP);
    fake.set(fake.sent.get(1).transferId(), "DA_XAC_NHAN", 2, null, null);
    s.poll(TENANT, 10);

    PackageRow archived = repo.packages.get(pkg.id());
    assertThat(archived.status()).isEqualTo(PackageStatus.DA_LUU_TRU);
    assertThat(archived.transferredTo()).isEqualTo("BTC_TW");
    TransferJob done = s.job(TENANT, job.batchId()).orElseThrow();
    assertThat(done.status()).isEqualTo(BatchStatus.HOAN_TAT);
    assertThat(done.acknowledged()).isEqualTo(2);
    assertThat(done.message()).contains("biên nhận");
    assertThat(events.log).filteredOn(l -> l.startsWith("BIEN_NHAN")).hasSize(2);

    // Yêu cầu lặp khi mọi hồ sơ đã có biên nhận: trả lại tác vụ cũ, không gửi lại.
    TransferJob repeat = s.request(TENANT, ACTOR, UNIT, pkg.id(), "BTC_TW", List.of());
    assertThat(repeat.batchId()).isEqualTo(job.batchId());
    assertThat(fake.sent).hasSize(2);
  }

  // ---------------------------------------------------------------- gửi lỗi

  @Test
  void benNhanKhongPhanHoiThongBaoMsg1106MotLanMoiLanThu() {
    UnitProfile a = person(true);
    PackageRow pkg = submitted(List.of(a), PackageStatus.DA_NOP);
    DossierTransferService s = service();
    TransferJob job = request(pkg, List.of());
    UUID id = job.rows().get(0).id();

    Instant retry = Instant.parse("2027-01-15T03:30:00Z");
    fake.set(id, "CHO", 1, retry, null);
    s.poll(TENANT, 10);
    s.poll(TENANT, 10);
    assertThat(events.log)
        .filteredOn(l -> l.startsWith("LOI:"))
        .containsExactly("LOI:" + id + ":1:1");
    assertThat(events.messages.get(0))
        .isEqualTo(
            "Gửi gói con tới Ban Tổ chức Trung ương chưa thành công: bên nhận chưa tiếp nhận gói"
                + " con. Hệ thống sẽ tự thử lại lúc 10:30 ngày 15/01/2027.");
    TransferJob running = s.job(TENANT, job.batchId()).orElseThrow();
    assertThat(running.status()).isEqualTo(BatchStatus.DANG_CHAY);
    assertThat(running.message()).startsWith("Gửi gói con tới Ban Tổ chức Trung ương");

    fake.set(id, "CHO", 2, retry.plusSeconds(600), "bên nhận không phản hồi");
    s.poll(TENANT, 10);
    assertThat(events.log).filteredOn(l -> l.startsWith("LOI:")).hasSize(2);
    assertThat(events.messages.get(1)).contains("bên nhận không phản hồi").contains("10:40");
    // Gửi lỗi không đổi trạng thái của gói chính.
    assertThat(repo.packages.get(pkg.id()).status()).isEqualTo(PackageStatus.DA_NOP);
  }

  @Test
  void chungThuBenNhanHetHanThiHetLuotVaThatBai() {
    UnitProfile a = person(true);
    PackageRow pkg = submitted(List.of(a), PackageStatus.DA_NOP);
    DossierTransferService s = service();
    TransferJob job = request(pkg, List.of());
    UUID id = job.rows().get(0).id();

    fake.set(id, "LOI", 5, null, "chứng thư số của bên nhận đã hết hạn hoặc không hợp lệ");
    s.poll(TENANT, 10);
    TransferJob failed = s.job(TENANT, job.batchId()).orElseThrow();
    assertThat(failed.status()).isEqualTo(BatchStatus.THAT_BAI);
    assertThat(failed.message())
        .isEqualTo(
            "Gửi gói con tới Ban Tổ chức Trung ương chưa thành công: chứng thư số của bên nhận đã"
                + " hết hạn hoặc không hợp lệ.");
    assertThat(events.log).contains("LOI:" + id + ":5:1");
    assertThat(repo.packages.get(pkg.id()).status()).isEqualTo(PackageStatus.DA_NOP);
    // Hết lượt thì không còn hỏi lại; yêu cầu mới tạo gói con mới.
    assertThat(transfers.open(TENANT, null, 10)).isEmpty();
    TransferJob retry = request(pkg, List.of());
    assertThat(retry.batchId()).isNotEqualTo(job.batchId());
  }

  @Test
  void goiConVuot20MbThiLoiKhongGuiVaThongBao() {
    UnitProfile a = person(true);
    PackageRow pkg = submitted(List.of(a), PackageStatus.DA_NOP);
    var f = repo.formsOfProfile(TENANT, BUCKET, pkg.id(), a.id()).get(0);
    files.stored.put(
        f.objectKey(),
        () -> new PackageArchiveTest.Generated(new byte[4096], EdxmlGateway.MAX_PAYLOAD_BYTES + 1));
    TransferJob job = request(pkg, List.of());
    TransferRow row = transfers.rows.get(job.rows().get(0).id());
    assertThat(row.status()).isEqualTo("LOI");
    assertThat(row.lastError()).isEqualTo(DossierTransferService.REASON_TOO_LARGE);
    assertThat(fake.sent).isEmpty();
    assertThat(events.messages).hasSize(1);
    assertThat(job.status()).isEqualTo(BatchStatus.THAT_BAI);

    // SVC-13 từ chối vì vượt trần cũng đóng gói con.
    files.put(f.objectKey(), "%PDF nho".getBytes(StandardCharsets.UTF_8));
    fake.sendResults.add(new EdxmlGateway.PayloadTooLargeException());
    TransferJob rejected = request(pkg, List.of(a.id()));
    assertThat(rejected.batchId()).isNotEqualTo(job.batchId());
    assertThat(rejected.status()).isEqualTo(BatchStatus.THAT_BAI);
    assertThat(rejected.rows().get(0).lastError())
        .isEqualTo(DossierTransferService.REASON_TOO_LARGE);
  }

  @Test
  void congTamLoiThiGuiLaiOLuotSau() {
    UnitProfile a = person(true);
    UnitProfile b = person(true);
    PackageRow pkg = submitted(List.of(a, b), PackageStatus.DA_NOP);
    fake.sendResults.add(new EdxmlGateway.UnavailableException("tạm lỗi", null));
    runner =
        r -> {
          throw new RejectedExecutionException("đầy");
        };
    TransferJob job = request(pkg, List.of());
    assertThat(transfers.rows.values()).extracting(TransferRow::status).containsOnly("CHUA_GUI");
    assertThat(job.message()).contains("0/2");

    assertThat(service().poll(TENANT, 10)).isEqualTo(2);
    assertThat(fake.sent).hasSize(2);
    assertThat(transfers.rows.values()).extracting(TransferRow::status).containsOnly("CHO");

    // Tệp mất trên kho: để chờ lượt sau, không đóng gói con.
    UnitProfile c = person(true);
    PackageRow lost = submitted(List.of(c), PackageStatus.DA_NOP);
    files.stored.remove(repo.formsOfProfile(TENANT, BUCKET, lost.id(), c.id()).get(0).objectKey());
    TransferJob waiting = request(lost, List.of());
    assertThat(transfers.rows.get(waiting.rows().get(0).id()).status()).isEqualTo("CHUA_GUI");
  }

  @Test
  void chiGuiHoSoDuocChonVaKhongGuiTrungHoSoDangGui() {
    UnitProfile a = person(true);
    UnitProfile b = person(true);
    PackageRow pkg = submitted(List.of(a, b), PackageStatus.DA_NOP);
    TransferJob first = request(pkg, List.of(a.id()));
    assertThat(first.rows()).extracting(TransferRow::profileId).containsExactly(a.id());
    TransferJob second = request(pkg, List.of());
    assertThat(second.rows()).extracting(TransferRow::profileId).containsExactly(b.id());
    // Gói chính đã lưu trữ vẫn gửi được tới nơi nhận khác.
    repo.setStatus(pkg.id(), PackageStatus.DA_LUU_TRU);
    TransferJob vptw = service().request(TENANT, ACTOR, UNIT, pkg.id(), "VPTW", List.of());
    assertThat(vptw.rows()).hasSize(2);
    assertThat(service().failureText(vptw.rows().get(0))).contains("Văn phòng Trung ương");
    assertThat(service().job(TENANT, UUID.randomUUID())).isEmpty();
  }
}
