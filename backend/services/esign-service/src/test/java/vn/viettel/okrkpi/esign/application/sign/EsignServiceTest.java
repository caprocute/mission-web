package vn.viettel.okrkpi.esign.application.sign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.esign.domain.sign.CertificateRef;
import vn.viettel.okrkpi.esign.domain.sign.CertificateRepository;
import vn.viettel.okrkpi.esign.domain.sign.ProviderCode;
import vn.viettel.okrkpi.esign.domain.sign.SignItem;
import vn.viettel.okrkpi.esign.domain.sign.SignPurpose;
import vn.viettel.okrkpi.esign.domain.sign.SignRequest;
import vn.viettel.okrkpi.esign.domain.sign.SignRequestRepository;
import vn.viettel.okrkpi.esign.domain.sign.SignRequestStatus;
import vn.viettel.okrkpi.esign.domain.sign.SignatureFormat;
import vn.viettel.okrkpi.esign.infrastructure.sign.FakeSignatureProvider;
import vn.viettel.okrkpi.esign.spi.SignStatus;
import vn.viettel.okrkpi.esign.spi.SignatureProvider;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;
import vn.viettel.okrkpi.shared.error.BusinessException;

/** Ca sử dụng ký số DV-10-01 với kho trong bộ nhớ và nhà cung cấp giả lập. */
class EsignServiceTest {

  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000e001");
  private static final UUID SIGNER = UUID.fromString("0192f1a2-0000-7000-8000-00000000e002");
  private static final UUID OTHER = UUID.fromString("0192f1a2-0000-7000-8000-00000000e003");
  private static final Instant T0 = Instant.parse("2026-10-22T03:00:00Z");

  private final MutableClock clock = new MutableClock(T0);
  private final MemoryRequests requests = new MemoryRequests();
  private final MemoryCertificates certificates = new MemoryCertificates();
  private final List<UUID> events = new ArrayList<>();
  private final Map<UUID, SourceDocumentPort.SourceDocuments> sources = new HashMap<>();
  private FakeSignatureProvider fake;
  private EsignService service;

  @BeforeEach
  void setUp() {
    fake = new FakeSignatureProvider(clock, Duration.ofSeconds(5));
    ProviderRegistry registry =
        new ProviderRegistry(
            List.of(fake),
            Map.of(ProviderCode.MYSIGN, FakeSignatureProvider.ID),
            ProviderCode.MYSIGN);
    service =
        new EsignService(
            requests,
            certificates,
            (tenant, module, ref, purpose) -> Optional.ofNullable(sources.get(ref)),
            registry,
            (request, cert) -> events.add(request.id()),
            clock,
            Duration.ofSeconds(120));
  }

  private static byte[] sha(int seed) {
    byte[] b = new byte[32];
    b[0] = (byte) seed;
    return b;
  }

  private UUID source(boolean ready, Set<UUID> signers, int docs) {
    UUID ref = UUID.randomUUID();
    List<SourceDocumentPort.Document> documents = new ArrayList<>();
    for (int i = 0; i < docs; i++) {
      documents.add(
          new SourceDocumentPort.Document(
              "PDF_A", "dossier/" + ref + "/" + i, sha(i + 1), SignatureFormat.PADES_LTA));
    }
    sources.put(ref, new SourceDocumentPort.SourceDocuments(ready, signers, documents));
    return ref;
  }

  private EsignService.CreateCommand cmd(UUID ref) {
    return new EsignService.CreateCommand(
        TENANT, SIGNER, SignPurpose.BIEN_BAN, "dossier", ref, null, null);
  }

  @Test
  void createdRequestWaitsForConfirmationThenSignsAndEmitsOneEvent() {
    SignRequest created = service.create(cmd(source(true, Set.of(SIGNER), 2)));
    assertThat(created.status()).isEqualTo(SignRequestStatus.CHO_XAC_NHAN);
    assertThat(created.batch()).isTrue();
    assertThat(created.expiresAt()).isEqualTo(T0.plusSeconds(120));
    assertThat(service.pollOnce(created.id())).isEqualTo(SignRequestStatus.CHO_XAC_NHAN);
    clock.advance(Duration.ofSeconds(6));
    assertThat(service.pollOnce(created.id())).isEqualTo(SignRequestStatus.DA_KY);
    assertThat(service.pollOnce(created.id())).isEqualTo(SignRequestStatus.DA_KY);
    SignRequest signed = service.get(SIGNER, created.id());
    assertThat(signed.signedCount()).isEqualTo(2);
    assertThat(signed.completedAt()).isEqualTo(T0.plusSeconds(6));
    assertThat(signed.items()).allMatch(i -> i.certificateRefId() != null);
    assertThat(events).containsExactly(created.id());
    assertThat(service.certificatesOf(SIGNER)).hasSize(1);
    assertThat(service.certificate(signed.certificateRefId())).isPresent();
  }

  @Test
  void unconfirmedRequestExpires() {
    fake.nextOutcome(FakeSignatureProvider.Outcome.NEVER_CONFIRM);
    SignRequest created = service.create(cmd(source(true, Set.of(SIGNER), 1)));
    clock.advance(Duration.ofSeconds(60));
    assertThat(service.pollOnce(created.id())).isEqualTo(SignRequestStatus.CHO_XAC_NHAN);
    clock.advance(Duration.ofSeconds(61));
    assertThat(service.pollOnce(created.id())).isEqualTo(SignRequestStatus.HET_HAN);
    assertThat(events).isEmpty();
  }

  @Test
  void rejectionOnDeviceEndsRequest() {
    fake.nextOutcome(FakeSignatureProvider.Outcome.REJECT);
    SignRequest created = service.create(cmd(source(true, Set.of(SIGNER), 1)));
    assertThat(service.pollOnce(created.id())).isEqualTo(SignRequestStatus.CHO_XAC_NHAN);
    clock.advance(Duration.ofSeconds(6));
    assertThat(service.pollOnce(created.id())).isEqualTo(SignRequestStatus.TU_CHOI);
  }

  @Test
  void callerMustBeSignerAndSourceMustBeReady() {
    assertThatThrownBy(() -> service.create(cmd(source(true, Set.of(OTHER), 1))))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getMessage()).contains("ESIGN-403"));
    assertThatThrownBy(() -> service.create(cmd(source(false, Set.of(SIGNER), 1))))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getMessage()).contains("SOURCE-NOT-READY"));
    assertThatThrownBy(() -> service.create(cmd(UUID.randomUUID())))
        .isInstanceOf(EsignNotFoundException.class);
  }

  @Test
  void secondPendingRequestForSameSourceIsRejected() {
    UUID ref = source(true, Set.of(SIGNER), 1);
    service.create(cmd(ref));
    assertThatThrownBy(() -> service.create(cmd(ref)))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.getMessage()).contains("REQUEST-PENDING"));
  }

  @Test
  void unconfiguredProviderAndInvalidCertificateAre422() {
    UUID ref = source(true, Set.of(SIGNER), 1);
    assertThatThrownBy(
            () ->
                service.create(
                    new EsignService.CreateCommand(
                        TENANT,
                        SIGNER,
                        SignPurpose.BIEN_BAN,
                        "dossier",
                        ref,
                        ProviderCode.BAN_CO_YEU,
                        null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).contains("PROVIDER-UNAVAILABLE"));
    CertificateRef expired = cert(SIGNER, T0.minus(Duration.ofDays(10)), null);
    certificates.upsert(expired);
    assertThatThrownBy(
            () ->
                service.create(
                    new EsignService.CreateCommand(
                        TENANT, SIGNER, SignPurpose.BIEN_BAN, "dossier", ref, null, expired.id())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).contains("CERTIFICATE-INVALID"));
    assertThatThrownBy(() -> service.create(cmd(ref)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getMessage()).contains("CERTIFICATE-INVALID"));
    CertificateRef good = cert(SIGNER, T0.plus(Duration.ofDays(30)), "GOOD");
    certificates.upsert(good);
    assertThat(service.create(cmd(ref)).status()).isEqualTo(SignRequestStatus.CHO_XAC_NHAN);
    assertThat(cert(SIGNER, T0.plusSeconds(60), "REVOKED").usableAt(T0)).isFalse();
  }

  @Test
  void otherPeopleCannotSeeRequestAndListingPagesNewestFirst() {
    SignRequest a = service.create(cmd(source(true, Set.of(SIGNER), 1)));
    SignRequest b = service.create(cmd(source(true, Set.of(SIGNER), 1)));
    assertThatThrownBy(() -> service.get(OTHER, a.id())).isInstanceOf(EsignNotFoundException.class);
    List<SignRequest> first = service.listMine(SIGNER, null, null, null, 1);
    assertThat(first).extracting(SignRequest::id).containsExactly(b.id());
    assertThat(service.listMine(SIGNER, null, null, b.id(), 5))
        .extracting(SignRequest::id)
        .containsExactly(a.id());
  }

  @Test
  void providerFailureWhilePollingIsRetriedNextRound() {
    SignatureProvider broken =
        new SignatureProvider() {
          @Override
          public String id() {
            return FakeSignatureProvider.ID;
          }

          @Override
          public Set<vn.viettel.okrkpi.esign.spi.SignatureFormat> supportedFormats() {
            return fake.supportedFormats();
          }

          @Override
          public vn.viettel.okrkpi.esign.spi.SignSession begin(
              vn.viettel.okrkpi.esign.spi.SignRequest request) {
            return fake.begin(request);
          }

          @Override
          public SignStatus poll(String sessionId) {
            throw new IllegalStateException("mất kết nối");
          }

          @Override
          public List<vn.viettel.okrkpi.esign.spi.SignedItem> collect(String sessionId) {
            return List.of();
          }
        };
    EsignService flaky =
        new EsignService(
            requests,
            certificates,
            (tenant, module, ref, purpose) -> Optional.ofNullable(sources.get(ref)),
            new ProviderRegistry(
                List.of(broken),
                Map.of(ProviderCode.MYSIGN, FakeSignatureProvider.ID),
                ProviderCode.MYSIGN),
            (request, cert) -> events.add(request.id()),
            clock,
            Duration.ofSeconds(120));
    SignRequest created = flaky.create(cmd(source(true, Set.of(SIGNER), 1)));
    assertThat(flaky.pollOnce(created.id())).isEqualTo(SignRequestStatus.CHO_XAC_NHAN);
    clock.advance(Duration.ofSeconds(121));
    assertThat(flaky.pollOnce(created.id())).isEqualTo(SignRequestStatus.HET_HAN);
    assertThat(flaky.pollOnce(UUID.randomUUID())).isNull();
  }

  /** Nhà cung cấp thử nghiệm: đếm lời gọi, ném lỗi theo từng thao tác. */
  private final class Scripted implements SignatureProvider {
    int begins;
    boolean up = true;
    RuntimeException onBegin;
    RuntimeException onPoll;
    RuntimeException onCollect;

    @Override
    public String id() {
      return FakeSignatureProvider.ID;
    }

    @Override
    public Set<vn.viettel.okrkpi.esign.spi.SignatureFormat> supportedFormats() {
      return fake.supportedFormats();
    }

    @Override
    public boolean available() {
      return up;
    }

    @Override
    public vn.viettel.okrkpi.esign.spi.SignSession begin(
        vn.viettel.okrkpi.esign.spi.SignRequest request) {
      begins++;
      if (onBegin != null) {
        throw onBegin;
      }
      return fake.begin(request);
    }

    @Override
    public SignStatus poll(String sessionId) {
      if (onPoll != null) {
        throw onPoll;
      }
      return SignStatus.SIGNED;
    }

    @Override
    public List<vn.viettel.okrkpi.esign.spi.SignedItem> collect(String sessionId) {
      if (onCollect != null) {
        throw onCollect;
      }
      return fake.collect(sessionId);
    }
  }

  private EsignService with(SignatureProvider provider) {
    return new EsignService(
        requests,
        certificates,
        (tenant, module, ref, purpose) -> Optional.ofNullable(sources.get(ref)),
        new ProviderRegistry(
            List.of(provider),
            Map.of(ProviderCode.MYSIGN, FakeSignatureProvider.ID),
            ProviderCode.MYSIGN),
        (request, cert) -> events.add(request.id()),
        clock,
        Duration.ofSeconds(120));
  }

  @Test
  void createDoesNotCallProviderAndRefusesWhenCircuitIsOpen() {
    Scripted provider = new Scripted();
    EsignService s = with(provider);
    SignRequest created = s.create(cmd(source(true, Set.of(SIGNER), 1)));
    assertThat(created.status()).isEqualTo(SignRequestStatus.CHO_XAC_NHAN);
    assertThat(created.providerTxnId()).isNull();
    assertThat(provider.begins).isZero();
    assertThat(s.pollOnce(created.id())).isEqualTo(SignRequestStatus.CHO_XAC_NHAN);
    assertThat(provider.begins).isEqualTo(1);
    assertThat(s.get(SIGNER, created.id()).providerTxnId()).isNotNull();
    assertThat(s.pollOnce(created.id())).isEqualTo(SignRequestStatus.DA_KY);
    assertThat(events).containsExactly(created.id());

    provider.up = false;
    assertThatThrownBy(() -> s.create(cmd(source(true, Set.of(SIGNER), 1))))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.errorCode().code()).isEqualTo(EsignService.PROVIDER_UNAVAILABLE));
  }

  @Test
  void transientBeginFailureRetriesUntilExpiryPermanentOneFails() {
    Scripted provider = new Scripted();
    provider.onBegin = new SignatureProviderException("5xx", false);
    EsignService s = with(provider);
    SignRequest a = s.create(cmd(source(true, Set.of(SIGNER), 1)));
    assertThat(s.pollOnce(a.id())).isEqualTo(SignRequestStatus.CHO_XAC_NHAN);
    provider.onBegin = new IllegalStateException("mạng");
    assertThat(s.pollOnce(a.id())).isEqualTo(SignRequestStatus.CHO_XAC_NHAN);
    assertThat(provider.begins).isEqualTo(2);
    clock.advance(Duration.ofSeconds(121));
    assertThat(s.pollOnce(a.id())).isEqualTo(SignRequestStatus.HET_HAN);
    assertThat(provider.begins).isEqualTo(2);

    provider.onBegin = new SignatureProviderException("chứng thư thu hồi", true);
    SignRequest b = s.create(cmd(source(true, Set.of(SIGNER), 1)));
    assertThat(s.pollOnce(b.id())).isEqualTo(SignRequestStatus.LOI);
    assertThat(events).isEmpty();
  }

  @Test
  void permanentPollOrCollectFailureEndsInErrorTransientPollWaits() {
    Scripted provider = new Scripted();
    EsignService s = with(provider);
    SignRequest a = s.create(cmd(source(true, Set.of(SIGNER), 1)));
    s.pollOnce(a.id());
    provider.onPoll = new SignatureProviderException("tạm", false);
    assertThat(s.pollOnce(a.id())).isEqualTo(SignRequestStatus.CHO_XAC_NHAN);
    provider.onPoll = new SignatureProviderException("tham số sai", true);
    assertThat(s.pollOnce(a.id())).isEqualTo(SignRequestStatus.LOI);

    provider.onPoll = null;
    provider.onCollect = new SignatureProviderException("thiếu chữ ký", true);
    SignRequest b = s.create(cmd(source(true, Set.of(SIGNER), 1)));
    s.pollOnce(b.id());
    assertThat(s.pollOnce(b.id())).isEqualTo(SignRequestStatus.LOI);
    assertThat(events).isEmpty();
  }

  @Test
  void domainGuardsInvalidTransitions() {
    SignRequest created = service.create(cmd(source(true, Set.of(SIGNER), 1)));
    assertThat(created.awaitingProvider()).isTrue();
    assertThatThrownBy(created::awaitProvider).isInstanceOf(IllegalStateException.class);
    service.pollOnce(created.id());
    SignRequest r = service.get(SIGNER, created.id());
    assertThat(r.awaitingProvider()).isFalse();
    assertThatThrownBy(() -> r.submitted("x", null)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> r.completeSigned(null, T0)).isInstanceOf(IllegalStateException.class);
    assertThat(r.attachSignature(sha(99), new byte[] {1}, null, null, T0)).isFalse();
    r.failed(T0);
    assertThat(r.status()).isEqualTo(SignRequestStatus.LOI);
    assertThatThrownBy(() -> r.failed(T0)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> SignItem.pending(UUID.randomUUID(), "k", "r", new byte[3], SignatureFormat.JWS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ProviderRegistry.toSpi(SignatureFormat.CADES_T))
        .isEqualTo(vn.viettel.okrkpi.esign.spi.SignatureFormat.CADES_B_T);
    assertThat(ProviderRegistry.toSpi(SignatureFormat.JWS))
        .isEqualTo(vn.viettel.okrkpi.esign.spi.SignatureFormat.JWS);
  }

  private static CertificateRef cert(UUID person, Instant notAfter, String ocsp) {
    byte[] der = UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    return new CertificateRef(
        UUID.randomUUID(),
        TENANT,
        person,
        Hashes.sha256(der),
        "CN=A",
        "CN=CA",
        "01",
        T0.minus(Duration.ofDays(365)),
        notAfter,
        der,
        ocsp,
        T0);
  }

  // --- Giả lập ---------------------------------------------------------------------------

  static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;

    MutableClock(Instant start) {
      this.now = new AtomicReference<>(start);
    }

    void advance(Duration d) {
      now.updateAndGet(i -> i.plus(d));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  static final class MemoryRequests implements SignRequestRepository {
    private final Map<UUID, SignRequest> rows = new HashMap<>();

    @Override
    public Optional<SignRequest> findById(UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<SignRequest> lockForProcessing(UUID id) {
      return findById(id);
    }

    @Override
    public boolean existsPending(UUID signer, String module, UUID ref) {
      return rows.values().stream()
          .anyMatch(
              r ->
                  r.signerPersonId().equals(signer)
                      && r.sourceRefId().equals(ref)
                      && !r.status().isTerminal());
    }

    @Override
    public List<SignRequest> listBySigner(
        UUID signer, SignRequestStatus status, SignPurpose purpose, UUID afterId, int limit) {
      return rows.values().stream()
          .filter(r -> r.signerPersonId().equals(signer))
          .filter(r -> status == null || r.status() == status)
          .filter(r -> purpose == null || r.purpose() == purpose)
          .filter(r -> afterId == null || r.id().compareTo(afterId) < 0)
          .sorted(Comparator.comparing(SignRequest::id).reversed())
          .limit(limit)
          .toList();
    }

    @Override
    public void insert(SignRequest request, UUID actor) {
      rows.put(request.id(), request);
    }

    @Override
    public void update(SignRequest request, UUID actor) {
      rows.put(request.id(), request);
    }
  }

  static final class MemoryCertificates implements CertificateRepository {
    private final Map<UUID, CertificateRef> rows = new HashMap<>();

    @Override
    public Optional<CertificateRef> findById(UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public List<CertificateRef> findByPerson(UUID personId) {
      return rows.values().stream().filter(c -> c.personId().equals(personId)).toList();
    }

    @Override
    public CertificateRef upsert(CertificateRef c) {
      for (CertificateRef existing : rows.values()) {
        if (java.util.Arrays.equals(existing.certSha256(), c.certSha256())) {
          return existing;
        }
      }
      rows.put(c.id(), c);
      return c;
    }
  }
}
