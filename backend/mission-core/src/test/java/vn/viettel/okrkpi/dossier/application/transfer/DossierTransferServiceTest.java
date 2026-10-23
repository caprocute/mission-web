package vn.viettel.okrkpi.dossier.application.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.dossier.application.comment.port.CommentSealer;
import vn.viettel.okrkpi.dossier.application.transfer.port.DossierHistory;
import vn.viettel.okrkpi.dossier.application.transfer.port.TransferredRecords;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Thu thập và nhập phần hồ sơ của gói chuyển liên cell (DV-13-10). */
class DossierTransferServiceTest {

  final TenantId tenant = TenantId.of(UUID.randomUUID());
  final UUID person = UUID.randomUUID();

  /** Mã hoá giả: đảo chuỗi, đủ để kiểm luồng. */
  final CommentSealer sealer =
      new CommentSealer() {
        @Override
        public Sealed seal(TenantId t, String column, UUID rowId, String plain) {
          return new Sealed(
              new StringBuilder(plain).reverse().toString().getBytes(StandardCharsets.UTF_8),
              UUID.randomUUID());
        }

        @Override
        public String open(TenantId t, String column, UUID rowId, byte[] cipher) {
          return new StringBuilder(new String(cipher, StandardCharsets.UTF_8)).reverse().toString();
        }
      };

  @Test
  void collectsSignedPackagesAndConclusions() {
    UUID period = UUID.randomUUID();
    DossierHistory history =
        new DossierHistory() {
          @Override
          public List<SignedPackage> signedPackages(TenantId t, UUID p) {
            return List.of(
                new SignedPackage(
                    period, "G1", "NAM", 1, "DA_NOP", new byte[32], new byte[32], Instant.EPOCH),
                new SignedPackage(period, "G2", "QUY", 1, "DA_KY", null, null, null));
          }

          @Override
          public List<Conclusion> conclusions(TenantId t, UUID p) {
            return List.of(
                new Conclusion(
                    UUID.randomUUID(),
                    period,
                    "HAN_CHE",
                    "HOI_NGHI",
                    new StringBuilder("sở an")
                        .reverse()
                        .toString()
                        .getBytes(StandardCharsets.UTF_8),
                    LocalDate.EPOCH,
                    Instant.EPOCH));
          }
        };
    AuditLogger audit = mock(AuditLogger.class);
    DossierTransferService svc = new DossierTransferService(history, sealer, null, audit);
    DossierTransferService.SignedDossiers d = svc.signedDossiers(tenant, person).orElseThrow();
    assertThat(d.packages()).hasSize(2);
    assertThat(d.packages().get(0).packageSha256()).hasSize(64);
    assertThat(d.packages().get(1).manifestSha256()).isNull();
    assertThat(d.packages().get(0).period().code()).isEqualTo(period.toString());
    assertThat(svc.conclusions(tenant, person).orElseThrow().items().get(0).content())
        .isEqualTo("sở an");
    verify(audit).record(any());
  }

  @Test
  void emptyHistoryGivesNothingAndNoAudit() {
    DossierHistory none =
        new DossierHistory() {
          @Override
          public List<SignedPackage> signedPackages(TenantId t, UUID p) {
            return List.of();
          }

          @Override
          public List<Conclusion> conclusions(TenantId t, UUID p) {
            return List.of();
          }
        };
    AuditLogger audit = mock(AuditLogger.class);
    DossierTransferService svc = new DossierTransferService(none, sealer, null, audit);
    assertThat(svc.signedDossiers(tenant, person)).isEmpty();
    assertThat(svc.conclusions(tenant, person)).isEmpty();
    verify(audit, never()).record(any());
  }

  @Test
  void importIsIdempotentPerPart() {
    Map<String, byte[]> stored = new HashMap<>();
    List<byte[]> ciphers = new ArrayList<>();
    boolean[] raceLost = {false};
    TransferredRecords records =
        new TransferredRecords() {
          @Override
          public boolean insert(TenantId t, NewRecord r, UUID actor) {
            if (raceLost[0]) {
              stored.put(r.packageId() + r.partName(), r.contentSha256());
              return false;
            }
            ciphers.add(r.contentEnc());
            return stored.putIfAbsent(r.packageId() + r.partName(), r.contentSha256()) == null;
          }

          @Override
          public Optional<byte[]> contentSha256(TenantId t, UUID packageId, String partName) {
            return Optional.ofNullable(stored.get(packageId + partName));
          }
        };
    TransferImportService svc = new TransferImportService(records, sealer);
    UUID pkg = UUID.randomUUID();
    byte[] body = " {\"personId\":\"x\"}".getBytes(StandardCharsets.UTF_8);
    TransferImportService.Part p =
        new TransferImportService.Part(
            pkg, "a.json", "HO_SO_DA_KY", "HN", person, "application/json; charset=utf-8", body);
    UUID actor = UUID.randomUUID();
    assertThat(svc.importPart(tenant, p, actor).created()).isTrue();
    assertThat(new String(ciphers.get(0), StandardCharsets.UTF_8)).doesNotStartWith(" {");
    assertThat(svc.importPart(tenant, p, actor).created()).isFalse();
    // Ghi đồng thời: chèn thua thì đối chiếu băm của dòng đã có.
    raceLost[0] = true;
    TransferImportService.Part q =
        new TransferImportService.Part(
            pkg, "b.json", "HO_SO_DA_KY", "HN", person, "application/json", body);
    assertThat(svc.importPart(tenant, q, actor).created()).isFalse();
    assertThatThrownBy(
            () ->
                svc.importPart(
                    tenant,
                    new TransferImportService.Part(
                        pkg,
                        "a.json",
                        "HO_SO_DA_KY",
                        "HN",
                        person,
                        "application/json",
                        "[1]".getBytes(StandardCharsets.UTF_8)),
                    actor))
        .isInstanceOf(TransferImportService.ConflictException.class);
    for (TransferImportService.Part bad :
        List.of(
            new TransferImportService.Part(
                pkg, "c", "HO_SO_DA_KY", "HN", null, "application/json", body),
            new TransferImportService.Part(
                pkg, null, "HO_SO_DA_KY", "HN", person, "application/json", body),
            new TransferImportService.Part(pkg, "c", null, "HN", person, "application/json", body),
            new TransferImportService.Part(
                pkg, "c", "HO_SO_DA_KY", null, person, "application/json", body),
            new TransferImportService.Part(
                pkg, "c", "HO_SO_DA_KY", "x".repeat(65), person, "application/json", body),
            new TransferImportService.Part(pkg, "c", "HO_SO_DA_KY", "HN", person, null, body),
            new TransferImportService.Part(
                pkg, "c", "HO_SO_DA_KY", "HN", person, "application/json", null),
            new TransferImportService.Part(
                pkg, "c", "HO_SO_DA_KY", "HN", person, "application/json", new byte[0]),
            new TransferImportService.Part(
                pkg,
                "c",
                "HO_SO_DA_KY",
                "HN",
                person,
                "application/json",
                new byte[TransferImportService.MAX_BYTES + 1]),
            new TransferImportService.Part(
                pkg,
                "c",
                "HO_SO_DA_KY",
                "HN",
                person,
                "application/json",
                "   ".getBytes(StandardCharsets.UTF_8)))) {
      assertThatThrownBy(() -> svc.importPart(tenant, bad, actor))
          .isInstanceOf(TransferImportService.InvalidPartException.class);
    }
  }
}
