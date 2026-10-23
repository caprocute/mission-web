package vn.viettel.okrkpi.esign.application.seal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.esign.application.seal.OrganizationSealService.InvalidSealRequestException;

class OrganizationSealServiceTest {

  private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private static final String PURPOSE = "GOI_CHUYEN_LIEN_CELL";

  private final AtomicReference<byte[]> signed = new AtomicReference<>();
  private final OrganizationSealService service =
      new OrganizationSealService(
          (tenant, hash) -> {
            signed.set(hash);
            return new OrganizationSealer.Seal(
                new byte[] {1, 2}, "SHA256withECDSA", new byte[] {3});
          },
          Set.of(PURPOSE));

  @Test
  void signsDeclaredPurposeWithSha256() {
    byte[] hash = new byte[32];

    OrganizationSealer.Seal seal = service.seal(TENANT, PURPOSE, "pkg:abc-1", hash);

    assertThat(seal.algorithm()).isEqualTo("SHA256withECDSA");
    assertThat(signed.get()).isSameAs(hash);
    assertThat(service.seal(TENANT, PURPOSE, null, hash).signature()).containsExactly(1, 2);
  }

  @Test
  void rejectsUnknownOrMissingPurpose() {
    assertThatThrownBy(() -> service.seal(TENANT, "KHAC", null, new byte[32]))
        .isInstanceOf(InvalidSealRequestException.class);
    assertThatThrownBy(() -> service.seal(TENANT, null, null, new byte[32]))
        .isInstanceOf(InvalidSealRequestException.class);
  }

  @Test
  void rejectsHashThatIsNotSha256() {
    assertThatThrownBy(() -> service.seal(TENANT, PURPOSE, null, null))
        .isInstanceOf(InvalidSealRequestException.class);
    assertThatThrownBy(() -> service.seal(TENANT, PURPOSE, null, new byte[20]))
        .isInstanceOf(InvalidSealRequestException.class);
    assertThat(signed.get()).isNull();
  }

  @Test
  void rejectsMalformedReference() {
    assertThatThrownBy(() -> service.seal(TENANT, PURPOSE, "a b", new byte[32]))
        .isInstanceOf(InvalidSealRequestException.class);
    assertThatThrownBy(() -> service.seal(TENANT, PURPOSE, "x".repeat(129), new byte[32]))
        .isInstanceOf(InvalidSealRequestException.class);
  }
}
