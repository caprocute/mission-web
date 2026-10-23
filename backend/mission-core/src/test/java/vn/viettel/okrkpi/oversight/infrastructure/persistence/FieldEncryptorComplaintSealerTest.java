package vn.viettel.okrkpi.oversight.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.viettel.okrkpi.oversight.application.port.ComplaintSealer;
import vn.viettel.okrkpi.shared.crypto.DekCache.CachedDek;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;
import vn.viettel.okrkpi.shared.crypto.DekService;
import vn.viettel.okrkpi.shared.crypto.DekStatus;
import vn.viettel.okrkpi.shared.crypto.FieldEncryptor;
import vn.viettel.okrkpi.shared.crypto.JcaAesGcmCryptoProvider;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/** Mỗi lần mở nội dung C2 khiếu nại có đúng một vết giải mã, đúng cột và mã dòng (CORE-07-05). */
class FieldEncryptorComplaintSealerTest {

  private static final String COLUMN = "oversight.complaint.content_enc";
  private final UUID tenantId = UUID.randomUUID();
  private final TenantId tenant = TenantId.of(tenantId);
  private final UUID rowId = UUID.randomUUID();
  private final List<List<String>> decrypts = new ArrayList<>();
  private final FieldEncryptor encryptor = encryptor();
  private final ComplaintSealer sealer = new FieldEncryptorComplaintSealer(encryptor);

  @Test
  void moiLanMoGhiDungMotVetVoiCotVaMaDong() {
    ComplaintSealer.Sealed s = sealer.seal(tenant, COLUMN, rowId, "Nội dung");
    assertThat(decrypts).isEmpty();
    assertThat(sealer.open(tenant, COLUMN, rowId, s.cipher())).isEqualTo("Nội dung");
    assertThat(decrypts).containsExactly(List.of(COLUMN, rowId.toString()));
    sealer.open(tenant, COLUMN, rowId, s.cipher());
    assertThat(decrypts).hasSize(2);
  }

  @Test
  void banMaCuCoTenantDauAadVanMoDuoc() {
    byte[] old =
        encryptor
            .encryptField(
                tenantId,
                DekPurpose.KHIEU_NAI,
                List.of(tenantId.toString(), COLUMN, rowId.toString()),
                "Bản cũ".getBytes(StandardCharsets.UTF_8))
            .ciphertext();
    assertThat(sealer.open(tenant, COLUMN, rowId, old)).isEqualTo("Bản cũ");
    assertThat(decrypts).containsExactly(List.of(COLUMN, rowId.toString()));
  }

  private FieldEncryptor encryptor() {
    DekService deks = Mockito.mock(DekService.class);
    UUID dekId = UUID.randomUUID();
    CachedDek dek =
        new CachedDek(
            dekId,
            tenantId,
            DekPurpose.KHIEU_NAI,
            LocalDate.of(2026, 10, 1),
            DekStatus.ACTIVE,
            new byte[32]);
    Mockito.when(deks.getOrCreateActiveDek(any(), any())).thenReturn(dek);
    Mockito.when(deks.getDekForDecryption(any(), any())).thenReturn(dek);
    FieldEncryptor e = new FieldEncryptor(deks, new JcaAesGcmCryptoProvider());
    e.setDecryptListener((t, p, parts) -> decrypts.add(List.copyOf(parts)));
    return e;
  }
}
