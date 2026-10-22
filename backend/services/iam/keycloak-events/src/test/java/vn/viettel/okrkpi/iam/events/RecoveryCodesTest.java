package vn.viettel.okrkpi.iam.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.keycloak.credential.CredentialModel;
import org.keycloak.models.SubjectCredentialManager;
import org.keycloak.models.UserModel;

/** DV-02-08: sinh, băm, so khớp mã khôi phục; mã một lần, hết hạn, thu hồi trên credential của người dùng. */
class RecoveryCodesTest {

  @Test
  void maDuEntropyVaChiLuuBam() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 200; i++) {
      String code = RecoveryCodes.generate();
      assertThat(code).matches("[2-9A-HJ-NP-Z]{4}(-[2-9A-HJ-NP-Z]{4}){3}");
      seen.add(code);
    }
    assertThat(seen).hasSize(200);
    String code = RecoveryCodes.generate();
    RecoveryCodes.Hashed h = RecoveryCodes.hash(code);
    assertThat(h.hash()).doesNotContain(code.replace("-", ""));
    assertThat(RecoveryCodes.matches(code.toLowerCase().replace("-", " "), h)).isTrue();
    assertThat(RecoveryCodes.matches(RecoveryCodes.generate(), h)).isFalse();
    assertThat(RecoveryCodes.matches("ngan", h)).isFalse();
    assertThat(RecoveryCodes.matches(null, h)).isFalse();
  }

  @Test
  void capMoiThuHoiMaCuVaHetHanThiKhongConHieuLuc() {
    List<CredentialModel> store = new ArrayList<>();
    UserModel user = fakeUser(store);
    UUID admin = UUID.randomUUID();
    Instant now = Instant.now();
    RecoveryCodeStore.Issued first = RecoveryCodeStore.issue(user, admin, "TRUC_TIEP", 15, "k1", now);
    RecoveryCodeStore.Issued second = RecoveryCodeStore.issue(user, admin, "TRUC_TIEP", 15, "k2", now);
    assertThat(store).hasSize(1);
    CredentialModel c = RecoveryCodeStore.active(user, now).orElseThrow();
    assertThat(RecoveryCodeStore.matches(c, first.code())).isFalse();
    assertThat(RecoveryCodeStore.matches(c, second.code())).isTrue();
    assertThat(c.getSecretData()).doesNotContain(second.code()).doesNotContain(second.code().replace("-", ""));
    assertThat(c.getCredentialData()).doesNotContain(second.code().replace("-", ""));
    assertThat(RecoveryCodeStore.byIdempotency(user, "k2")).isPresent();
    assertThat(second.expiresAt()).isEqualTo(now.plusSeconds(900));

    assertThat(RecoveryCodeStore.active(user, now.plusSeconds(901))).as("hết hạn").isEmpty();
    assertThat(RecoveryCodeStore.revokeAll(user)).isEqualTo(1);
    assertThat(RecoveryCodeStore.revokeAll(user)).isZero();
    assertThat(store).isEmpty();
  }

  private static UserModel fakeUser(List<CredentialModel> store) {
    SubjectCredentialManager cm = (SubjectCredentialManager) Proxy.newProxyInstance(getLoader(),
        new Class<?>[] {SubjectCredentialManager.class}, (p, m, a) -> switch (m.getName()) {
          case "createStoredCredential" -> {
            CredentialModel c = (CredentialModel) a[0];
            c.setId(UUID.randomUUID().toString());
            store.add(c);
            yield c;
          }
          case "getStoredCredentialsByTypeStream" ->
              new ArrayList<>(store).stream().filter(c -> a[0].equals(c.getType()));
          case "removeStoredCredentialById" -> store.removeIf(c -> c.getId().equals(a[0]));
          default -> throw new UnsupportedOperationException(m.getName());
        });
    return (UserModel) Proxy.newProxyInstance(getLoader(), new Class<?>[] {UserModel.class}, (p, m, a) -> {
      if ("credentialManager".equals(m.getName())) {
        return cm;
      }
      throw new UnsupportedOperationException(m.getName());
    });
  }

  private static ClassLoader getLoader() {
    return RecoveryCodesTest.class.getClassLoader();
  }
}
