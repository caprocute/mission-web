package vn.viettel.okrkpi.esign.infrastructure.provider.bcy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.esign.domain.sign.ProviderCode;
import vn.viettel.okrkpi.esign.domain.sign.SignItem;
import vn.viettel.okrkpi.esign.domain.sign.SignPurpose;
import vn.viettel.okrkpi.esign.domain.sign.SignRequest;
import vn.viettel.okrkpi.esign.domain.sign.SignRequestRepository;
import vn.viettel.okrkpi.esign.domain.sign.SignRequestStatus;
import vn.viettel.okrkpi.esign.domain.sign.SignatureFormat;
import vn.viettel.okrkpi.esign.infrastructure.sign.EsignConfiguration.EsignProperties;
import vn.viettel.okrkpi.esign.infrastructure.sign.FakeSignatureProvider;
import vn.viettel.okrkpi.esign.spi.SignSession;
import vn.viettel.okrkpi.esign.spi.SignStatus;
import vn.viettel.okrkpi.esign.spi.SignatureProvider;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;
import vn.viettel.okrkpi.esign.spi.SignedItem;
import vn.viettel.okrkpi.shared.crypto.bcy.BcyCryptoProperties;
import vn.viettel.okrkpi.shared.crypto.bcy.BcyCryptoProvider;
import vn.viettel.okrkpi.shared.crypto.bcy.BcyLibraryLoader;
import vn.viettel.okrkpi.shared.crypto.bcy.GiaLapBcyCryptoLibrary;

/** {@link BcySignatureProvider} và chốt chặn chế độ {@code bcy} của esign-service (DV-10-05). */
class BcySignatureProviderTest {

  private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");
  private static final UUID SIGNER = UUID.fromString("0192f1a2-0000-7000-8000-00000000b002");

  private final Map<UUID, SignRequest> store = new HashMap<>();
  private final BcyCryptoProvider crypto =
      new BcyCryptoProvider(
          BcyLibraryLoader.load(
              new BcyCryptoProperties(
                  List.of(),
                  GiaLapBcyCryptoLibrary.class.getName(),
                  Map.of("slot", "0", "key-labels", "ky-van-ban," + SIGNER),
                  Set.of(
                      "GIA_LAP_AEAD",
                      "GIA_LAP_MAC",
                      "GIA_LAP_BAM",
                      "GIA_LAP_KY",
                      "GIA_LAP_DAU_THOI_GIAN",
                      "GIA_LAP_NGAU_NHIEN"))));
  private final BcySignatureProvider provider =
      new BcySignatureProvider(crypto, repo(), "{signer}", Clock.fixed(NOW, ZoneOffset.UTC));

  @AfterEach
  void close() {
    crypto.close();
  }

  private SignRequestRepository repo() {
    return new SignRequestRepository() {
      @Override
      public Optional<SignRequest> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
      }

      @Override
      public Optional<SignRequest> lockForProcessing(UUID id) {
        return findById(id);
      }

      @Override
      public boolean existsPending(UUID signer, String module, UUID ref) {
        return false;
      }

      @Override
      public List<SignRequest> listBySigner(
          UUID signer, SignRequestStatus s, SignPurpose p, UUID after, int limit) {
        return List.of();
      }

      @Override
      public void insert(SignRequest request, UUID actor) {
        store.put(request.id(), request);
      }

      @Override
      public void update(SignRequest request, UUID actor) {
        store.put(request.id(), request);
      }
    };
  }

  private SignRequest request(Instant expiresAt, SignatureFormat format) {
    byte[] sha = new byte[32];
    sha[0] = 7;
    SignRequest r =
        SignRequest.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            SIGNER,
            SignPurpose.BIEN_BAN,
            "dossier",
            UUID.randomUUID(),
            ProviderCode.BAN_CO_YEU,
            List.of(SignItem.pending(UUID.randomUUID(), "PDF_A", "dossier/x", sha, format)),
            null,
            NOW,
            expiresAt);
    store.put(r.id(), r);
    return r;
  }

  private static vn.viettel.okrkpi.esign.spi.SignRequest spi(
      SignRequest r, vn.viettel.okrkpi.esign.spi.SignatureFormat format) {
    return new vn.viettel.okrkpi.esign.spi.SignRequest(
        r.id(),
        r.tenantId(),
        r.signerPersonId(),
        null,
        List.of(
            new vn.viettel.okrkpi.esign.spi.SignRequest.Document(
                "dossier/x", r.items().get(0).documentSha256(), format)),
        r.expiresAt());
  }

  @Test
  void signsStoredDocumentsWithSignerKeyStatelessly() {
    SignRequest r = request(NOW.plus(Duration.ofMinutes(2)), SignatureFormat.CADES_T);
    assertThat(provider.id()).isEqualTo("bcy");
    assertThat(provider.supportedFormats())
        .containsExactly(vn.viettel.okrkpi.esign.spi.SignatureFormat.CADES_B_T);
    SignSession session =
        provider.begin(spi(r, vn.viettel.okrkpi.esign.spi.SignatureFormat.CADES_B_T));
    assertThat(session.sessionId()).isEqualTo("bcy:" + r.id());
    assertThat(provider.poll(session.sessionId())).isEqualTo(SignStatus.SIGNED);
    List<SignedItem> items = provider.collect(session.sessionId());
    assertThat(items).hasSize(1);
    SignedItem item = items.get(0);
    assertThat(item.certificate().subjectDn()).isEqualTo("CN=Khoá ký giả lập " + SIGNER);
    assertThat(item.certificate().ocspStatus()).isEqualTo("UNKNOWN");
    assertThat(item.signedObjectKey()).isNull();
    assertThat(provider.verify(item.signatureValue(), item.documentSha256(), item.certificate()))
        .isTrue();
    assertThat(provider.verify(item.signatureValue(), new byte[32], item.certificate())).isFalse();
  }

  @Test
  void unknownExpiredOrMalformedSessionsAreExpired() {
    SignRequest old = request(NOW.minusSeconds(1), SignatureFormat.CADES_T);
    assertThat(provider.poll("bcy:" + old.id())).isEqualTo(SignStatus.EXPIRED);
    assertThat(provider.poll("bcy:" + UUID.randomUUID())).isEqualTo(SignStatus.EXPIRED);
    assertThat(provider.poll("bcy:khong-phai-uuid")).isEqualTo(SignStatus.EXPIRED);
    assertThat(provider.poll("mysign-1")).isEqualTo(SignStatus.EXPIRED);
    assertThat(provider.poll(null)).isEqualTo(SignStatus.EXPIRED);
    assertThatThrownBy(() -> provider.collect("bcy:" + old.id()))
        .isInstanceOf(SignatureProviderException.class)
        .matches(e -> ((SignatureProviderException) e).permanent());
  }

  @Test
  void padesIsRefusedPermanently() {
    SignRequest r = request(NOW.plusSeconds(60), SignatureFormat.PADES_LTA);
    assertThatThrownBy(
            () -> provider.begin(spi(r, vn.viettel.okrkpi.esign.spi.SignatureFormat.PADES_B_LTA)))
        .isInstanceOf(SignatureProviderException.class)
        .matches(e -> ((SignatureProviderException) e).permanent());
  }

  @Test
  void missingDeviceKeyIsTemporaryFailure() {
    BcySignatureProvider other =
        new BcySignatureProvider(crypto, repo(), "khong-co-khoa", Clock.fixed(NOW, ZoneOffset.UTC));
    SignRequest r = request(NOW.plusSeconds(60), SignatureFormat.CADES_T);
    assertThatThrownBy(() -> other.collect("bcy:" + r.id()))
        .isInstanceOf(SignatureProviderException.class)
        .matches(e -> !((SignatureProviderException) e).permanent());
  }

  @Test
  void bcyModeGuardAllowsOnlyBanCoYeu() {
    Map<ProviderCode, String> ids = new EnumMap<>(ProviderCode.class);
    ids.put(ProviderCode.BAN_CO_YEU, "bcy");
    EsignProperties ok = new EsignProperties(ids, ProviderCode.BAN_CO_YEU, null, null);
    SignatureProvider fake = new FakeSignatureProvider(Clock.systemUTC(), Duration.ZERO);
    assertThatCode(() -> BcyEsignConfiguration.check(List.of(provider, fake), ok))
        .doesNotThrowAnyException();
    SignatureProvider mysign = named("mysign");
    assertThatThrownBy(() -> BcyEsignConfiguration.check(List.of(provider, mysign), ok))
        .hasMessageContaining("không được nạp nhà cung cấp chữ ký [mysign]");
    assertThatThrownBy(
            () ->
                BcyEsignConfiguration.check(
                    List.of(provider), new EsignProperties(ids, ProviderCode.MYSIGN, null, null)))
        .hasMessageContaining("mặc định phải là BAN_CO_YEU");
    Map<ProviderCode, String> wrong = new EnumMap<>(ProviderCode.class);
    wrong.put(ProviderCode.BAN_CO_YEU, "fake");
    assertThatThrownBy(
            () ->
                BcyEsignConfiguration.check(
                    List.of(provider),
                    new EsignProperties(wrong, ProviderCode.BAN_CO_YEU, null, null)))
        .hasMessageContaining("phải trỏ tới nhà cung cấp bcy");
  }

  private static SignatureProvider named(String id) {
    return new FakeSignatureProvider(Clock.systemUTC(), Duration.ZERO) {
      @Override
      public String id() {
        return id;
      }
    };
  }
}
