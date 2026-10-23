package vn.viettel.okrkpi.dossier.application.dossierpackage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.NewForm;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.NewPackage;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.PackageRow;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.FormStatus;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageKind;
import vn.viettel.okrkpi.dossier.domain.dossierpackage.PackageStatus;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Xuất gói ZIP (CORE-06-08, {@code exportDossierPackage}) trên kho trong bộ nhớ. */
class DossierExportServiceTest {

  static final TenantId TENANT =
      TenantId.of(UUID.fromString("01927a06-0000-7000-8000-00000000dd01"));
  static final UUID PERIOD = UUID.fromString("01927a06-0000-7000-8000-00000000dd02");
  static final UUID UNIT = UUID.fromString("01927a06-0000-7000-8000-00000000dd03");
  static final UUID ACTOR = UUID.fromString("01927a06-0000-7000-8000-00000000dd04");
  static final int BUCKET = 20260;
  static final Instant NOW = Instant.parse("2027-01-15T02:00:00Z");

  final InMemoryPackages repo = new InMemoryPackages();
  final TransferFakes.Exports exports = new TransferFakes.Exports();
  final TransferFakes.Files files = new TransferFakes.Files();
  final TransferFakes.Events events = new TransferFakes.Events();
  final List<Runnable> queued = new ArrayList<>();
  Executor runner = queued::add;

  DossierExportService service() {
    return new DossierExportService(
        repo,
        exports,
        files,
        events,
        TransactionOperations.withoutTransaction(),
        runner,
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofMinutes(15),
        Duration.ofMinutes(30));
  }

  /** Gói hai biểu mẫu đã sinh, tệp có trên kho, ở trạng thái cho trước. */
  private PackageRow pkg(PackageStatus status) throws Exception {
    PackageRow pkg =
        repo.ensure(
                TENANT,
                new NewPackage(PERIOD, BUCKET, UNIT, PackageKind.NAM, null),
                UUID.randomUUID(),
                ACTOR)
            .row();
    repo.insertForms(
        TENANT,
        pkg,
        List.of(
            new NewForm(
                UUID.randomUUID(), UUID.randomUUID(), "BANG_CHAM_DIEM", "1", new byte[32], false),
            new NewForm(UUID.randomUUID(), UUID.randomUUID(), "MAU_3", "1", new byte[32], true)),
        ACTOR);
    for (var f : repo.formsIn(TENANT, BUCKET, pkg.id(), List.of())) {
      byte[] content = ("%PDF " + f.id()).getBytes(StandardCharsets.UTF_8);
      files.put("k/" + f.id(), content);
      repo.setForm(
          f.id(),
          FormStatus.DA_SINH,
          "k/" + f.id(),
          MessageDigest.getInstance("SHA-256").digest(content));
    }
    repo.setStatus(pkg.id(), status);
    return repo.packages.get(pkg.id());
  }

  private void drain() {
    List<Runnable> work = new ArrayList<>(queued);
    queued.clear();
    work.forEach(Runnable::run);
  }

  @Test
  void goiChuaToiChoKyBi409() throws Exception {
    PackageRow p = pkg(PackageStatus.DANG_SINH);
    assertThatThrownBy(() -> service().request(TENANT, ACTOR, UNIT, p.id()))
        .isInstanceOf(BusinessException.class)
        .hasMessage("DOSSIER-409-PACKAGE-NOT-READY");
    assertThat(exports.rows).isEmpty();
  }

  @Test
  void ngoaiPhamViVaKhongCoGoi() throws Exception {
    PackageRow p = pkg(PackageStatus.CHO_KY);
    assertThatThrownBy(() -> service().request(TENANT, ACTOR, UUID.randomUUID(), p.id()))
        .isInstanceOf(BusinessException.class)
        .hasMessage("COMMON-403-OUT-OF-SCOPE");
    assertThatThrownBy(() -> service().request(TENANT, ACTOR, UNIT, UUID.randomUUID()))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void goiChoKyXuatManifestTucThoiKhongChuKy() throws Exception {
    PackageRow p = pkg(PackageStatus.CHO_KY);
    DossierExportService s = service();

    DossierExportService.ExportJob started = s.request(TENANT, ACTOR, UNIT, p.id());
    assertThat(started.row().status()).isEqualTo("CHO");
    assertThat(started.downloadUrl()).isNull();
    assertThat(events.log).containsExactly("XUAT:" + started.row().id());
    assertThat(queued).hasSize(1);

    drain();
    DossierExportService.ExportJob done = s.job(TENANT, started.row().id()).orElseThrow();
    assertThat(done.row().status()).isEqualTo("HOAN_TAT");
    assertThat(done.row().fileCount()).isEqualTo(2);
    assertThat(done.row().objectKey())
        .isEqualTo(DossierExportService.objectKey(TENANT, p, started.row().id()));
    assertThat(done.downloadUrl()).contains(p.code() + "-v1.zip").contains("han=15");
    Map<String, byte[]> zip = PackageArchiveTest.unzip(files.written.get(done.row().objectKey()));
    assertThat(zip).doesNotContainKey("manifest.json.p7s").hasSize(4);
    String manifest = new String(zip.get("manifest.json"), StandardCharsets.UTF_8);
    assertThat(manifest).contains("\"maMau\":\"MAU_3\"").contains("sha256");
    assertThat(new String(zip.get("thong-tin-goi.json"), StandardCharsets.UTF_8))
        .contains("\"maGoi\":\"" + p.code() + "\"")
        .contains("\"daKy\":false");
  }

  @Test
  void goiDaNopXuatManifestDaChotVaChuKy() throws Exception {
    PackageRow p = pkg(PackageStatus.DA_NOP);
    repo.manifests.put(p.id(), "[{\"chot\":true}]");
    repo.signatures.put(p.id(), "ky/goi.p7s");
    files.put("ky/goi.p7s", new byte[] {9});
    runner = Runnable::run;
    DossierExportService s = service();

    DossierExportService.ExportJob job = s.request(TENANT, ACTOR, UNIT, p.id());
    assertThat(job.row().status()).isEqualTo("HOAN_TAT");
    Map<String, byte[]> zip = PackageArchiveTest.unzip(files.written.get(job.row().objectKey()));
    assertThat(zip.get("manifest.json.p7s")).containsExactly(9);
    assertThat(new String(zip.get("manifest.json"), StandardCharsets.UTF_8))
        .isEqualTo("[{\"chot\":true}]");
  }

  @Test
  void bamLechHoacKhoLoiThiThatBaiVaHuyTep() throws Exception {
    PackageRow p = pkg(PackageStatus.CHO_KY);
    var f = repo.formsIn(TENANT, BUCKET, p.id(), List.of()).get(0);
    files.put(f.objectKey(), "bi sua".getBytes(StandardCharsets.UTF_8));
    runner = Runnable::run;
    DossierExportService s = service();
    DossierExportService.ExportJob hash = s.request(TENANT, ACTOR, UNIT, p.id());
    assertThat(hash.row().status()).isEqualTo("THAT_BAI");
    assertThat(hash.row().errorCode()).isEqualTo(DossierExportService.ERROR_HASH);
    assertThat(files.aborted)
        .containsExactly(DossierExportService.objectKey(TENANT, p, hash.row().id()));

    files.failWrite = true;
    DossierExportService.ExportJob storage = s.request(TENANT, ACTOR, UNIT, p.id());
    assertThat(storage.row().errorCode()).isEqualTo(DossierExportService.ERROR_STORAGE);
  }

  @Test
  void tacVuDaNhanThiKhongChayLaiVaGoiMatThiThatBai() throws Exception {
    PackageRow p = pkg(PackageStatus.CHO_KY);
    DossierExportService s = service();
    UUID id = s.request(TENANT, ACTOR, UNIT, p.id()).row().id();
    drain();
    assertThat(s.run(TENANT, id)).isFalse();

    UUID orphan = UUID.randomUUID();
    exports.insert(TENANT, orphan, BUCKET, UUID.randomUUID(), ACTOR);
    assertThat(s.run(TENANT, orphan)).isTrue();
    assertThat(exports.rows.get(orphan).errorCode()).isEqualTo(DossierExportService.ERROR_MISSING);
  }

  @Test
  void hangDoiDayThiQuetGiaoLai() throws Exception {
    PackageRow p = pkg(PackageStatus.CHO_KY);
    runner =
        r -> {
          throw new RejectedExecutionException("đầy");
        };
    UUID id = service().request(TENANT, ACTOR, UNIT, p.id()).row().id();
    assertThat(exports.rows.get(id).status()).isEqualTo("CHO");

    runner = queued::add;
    assertThat(service().sweep(TENANT, 10)).isEqualTo(1);
    drain();
    assertThat(exports.rows.get(id).status()).isEqualTo("HOAN_TAT");
    assertThat(service().job(TENANT, UUID.randomUUID())).isEmpty();
  }
}
