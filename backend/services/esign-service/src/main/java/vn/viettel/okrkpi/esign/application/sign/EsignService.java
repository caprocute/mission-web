package vn.viettel.okrkpi.esign.application.sign;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import vn.viettel.okrkpi.esign.domain.sign.CertificateRef;
import vn.viettel.okrkpi.esign.domain.sign.CertificateRepository;
import vn.viettel.okrkpi.esign.domain.sign.ProviderCode;
import vn.viettel.okrkpi.esign.domain.sign.SignItem;
import vn.viettel.okrkpi.esign.domain.sign.SignPurpose;
import vn.viettel.okrkpi.esign.domain.sign.SignRequest;
import vn.viettel.okrkpi.esign.domain.sign.SignRequestRepository;
import vn.viettel.okrkpi.esign.domain.sign.SignRequestStatus;
import vn.viettel.okrkpi.esign.spi.SignSession;
import vn.viettel.okrkpi.esign.spi.SignStatus;
import vn.viettel.okrkpi.esign.spi.SignatureProvider;
import vn.viettel.okrkpi.esign.spi.SignatureProviderException;
import vn.viettel.okrkpi.esign.spi.SignedItem;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.kernel.Ids;

/**
 * Ca sử dụng ký số của SVC-10 (DV-10-01): mở yêu cầu ký từ xa, xem trạng thái, liệt kê yêu cầu và
 * chứng thư của người gọi, và thăm dò nhà cung cấp tới khi ký xong, bị từ chối hoặc hết hạn.
 */
public class EsignService {

  static final String NOT_SIGNER = "ESIGN-403-NOT-SIGNER";
  static final String SOURCE_NOT_READY = "ESIGN-409-SOURCE-NOT-READY";
  static final String REQUEST_PENDING = "ESIGN-409-REQUEST-PENDING";
  static final String CERTIFICATE_INVALID = "ESIGN-422-CERTIFICATE-INVALID";
  static final String PROVIDER_UNAVAILABLE = "ESIGN-422-PROVIDER-UNAVAILABLE";

  private static final Logger LOG = LoggerFactory.getLogger(EsignService.class);

  private final SignRequestRepository requests;
  private final CertificateRepository certificates;
  private final SourceDocumentPort sources;
  private final ProviderRegistry providers;
  private final SignatureEvents events;
  private final Clock clock;
  private final Duration confirmWindow;

  public EsignService(
      SignRequestRepository requests,
      CertificateRepository certificates,
      SourceDocumentPort sources,
      ProviderRegistry providers,
      SignatureEvents events,
      Clock clock,
      Duration confirmWindow) {
    this.requests = Objects.requireNonNull(requests, "requests");
    this.certificates = Objects.requireNonNull(certificates, "certificates");
    this.sources = Objects.requireNonNull(sources, "sources");
    this.providers = Objects.requireNonNull(providers, "providers");
    this.events = Objects.requireNonNull(events, "events");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.confirmWindow = Objects.requireNonNull(confirmWindow, "confirmWindow");
  }

  /** Lệnh mở yêu cầu ký cho chính người gọi. */
  public record CreateCommand(
      UUID tenantId,
      UUID caller,
      SignPurpose purpose,
      String sourceModule,
      UUID sourceRefId,
      ProviderCode provider,
      UUID certificateId) {}

  /**
   * Mở yêu cầu ký: lấy tài liệu và băm từ module nguồn, kiểm người gọi là chủ thể ký, chọn chứng
   * thư, mở phiên ở nhà cung cấp và trả yêu cầu ở {@code CHO_XAC_NHAN}. Việc chờ người ký xác nhận
   * diễn ra sau, ở worker thăm dò.
   */
  @Transactional
  public SignRequest create(CreateCommand cmd) {
    ProviderCode code = cmd.provider() != null ? cmd.provider() : providers.defaultCode();
    SignatureProvider provider =
        providers
            .forCode(code)
            .orElseThrow(
                () ->
                    new BusinessException(
                        PROVIDER_UNAVAILABLE, "Nhà cung cấp chữ ký số này chưa được cấu hình."));
    if (!provider.available()) {
      throw new BusinessException(
          PROVIDER_UNAVAILABLE, "Dịch vụ ký số đang tạm gián đoạn, vui lòng thử lại sau.");
    }
    SourceDocumentPort.SourceDocuments source =
        sources
            .fetch(cmd.tenantId(), cmd.sourceModule(), cmd.sourceRefId(), cmd.purpose())
            .orElseThrow(() -> new EsignNotFoundException("Không tìm thấy hồ sơ cần ký."));
    if (!source.signers().contains(cmd.caller())) {
      throw new BusinessException(NOT_SIGNER, "Tài khoản này không phải người ký của hồ sơ.");
    }
    if (!source.ready() || source.documents().isEmpty()) {
      throw new BusinessException(SOURCE_NOT_READY, "Hồ sơ chưa sẵn sàng để ký.");
    }
    if (requests.existsPending(cmd.caller(), cmd.sourceModule(), cmd.sourceRefId())) {
      throw new BusinessException(REQUEST_PENDING, "Hồ sơ này đang có yêu cầu ký chờ xác nhận.");
    }
    Instant now = clock.instant();
    CertificateRef certificate = chooseCertificate(cmd.caller(), cmd.certificateId(), now);
    List<SignItem> items = new ArrayList<>();
    List<vn.viettel.okrkpi.esign.spi.SignRequest.Document> documents = new ArrayList<>();
    for (SourceDocumentPort.Document doc : source.documents()) {
      vn.viettel.okrkpi.esign.spi.SignatureFormat format = ProviderRegistry.toSpi(doc.format());
      if (!provider.supportedFormats().contains(format)) {
        throw new BusinessException(
            PROVIDER_UNAVAILABLE, "Nhà cung cấp chữ ký số không hỗ trợ định dạng của tài liệu.");
      }
      items.add(
          SignItem.pending(Ids.newUuidV7(), doc.kind(), doc.ref(), doc.sha256(), doc.format()));
      documents.add(
          new vn.viettel.okrkpi.esign.spi.SignRequest.Document(doc.ref(), doc.sha256(), format));
    }
    SignRequest request =
        SignRequest.create(
            Ids.newUuidV7(),
            cmd.tenantId(),
            cmd.caller(),
            cmd.purpose(),
            cmd.sourceModule(),
            cmd.sourceRefId(),
            code,
            items,
            certificate == null ? null : certificate.id(),
            now,
            now.plus(confirmWindow));
    // DV-10-02: không gọi đồng bộ nhà cung cấp; worker mở phiên ở vòng thăm dò đầu tiên.
    request.awaitProvider();
    requests.insert(request, cmd.caller());
    return reload(request.id());
  }

  /** Yêu cầu của người gọi; yêu cầu của người khác coi như không tồn tại. */
  @Transactional(readOnly = true)
  public SignRequest get(UUID caller, UUID id) {
    return requests
        .findById(id)
        .filter(r -> r.signerPersonId().equals(caller))
        .orElseThrow(() -> new EsignNotFoundException("Không tìm thấy yêu cầu ký."));
  }

  @Transactional(readOnly = true)
  public Optional<CertificateRef> certificate(UUID id) {
    return id == null ? Optional.empty() : certificates.findById(id);
  }

  @Transactional(readOnly = true)
  public List<SignRequest> listMine(
      UUID caller, SignRequestStatus status, SignPurpose purpose, UUID afterId, int limit) {
    return requests.listBySigner(caller, status, purpose, afterId, limit);
  }

  @Transactional(readOnly = true)
  public List<CertificateRef> certificatesOf(UUID caller) {
    return certificates.findByPerson(caller).stream()
        .sorted(Comparator.comparing(CertificateRef::notAfter).reversed())
        .toList();
  }

  /**
   * Một vòng thăm dò cho yêu cầu đang chờ: hết hạn thì {@code HET_HAN}; nhà cung cấp báo đã ký thì
   * lấy chữ ký, lưu chứng thư và ghi EVT-18 trong cùng giao dịch. Lỗi tạm thời của nhà cung cấp để
   * lại cho vòng sau. Trả trạng thái sau vòng này.
   */
  @Transactional
  public SignRequestStatus pollOnce(UUID id) {
    Optional<SignRequest> locked = requests.lockForProcessing(id);
    if (locked.isEmpty()) {
      return null;
    }
    SignRequest request = locked.get();
    if (request.status() != SignRequestStatus.CHO_XAC_NHAN) {
      return request.status();
    }
    Instant now = clock.instant();
    Optional<SignatureProvider> provider = providers.forCode(request.provider());
    if (provider.isEmpty()) {
      request.failed(now);
      requests.update(request, request.signerPersonId());
      return request.status();
    }
    if (request.awaitingProvider()) {
      return begin(request, provider.get(), now);
    }
    SignStatus status;
    try {
      status = provider.get().poll(request.providerTxnId());
    } catch (SignatureProviderException e) {
      return providerFailed(request, e, now);
    } catch (RuntimeException e) {
      LOG.warn("Thăm dò yêu cầu ký {} lỗi: {}", id, e.toString());
      if (request.expireIfDue(now)) {
        requests.update(request, request.signerPersonId());
      }
      return request.status();
    }
    switch (status) {
      case PENDING -> request.expireIfDue(now);
      case REJECTED -> request.rejected(now);
      case EXPIRED -> request.expired(now);
      case SIGNED -> {
        try {
          collect(request, provider.get(), now);
        } catch (SignatureProviderException e) {
          return providerFailed(request, e, now);
        }
      }
    }
    if (request.status() != SignRequestStatus.CHO_XAC_NHAN) {
      requests.update(request, request.signerPersonId());
    }
    return request.status();
  }

  /** Mở phiên ở nhà cung cấp cho yêu cầu vừa tạo; lỗi tạm để lại cho vòng sau tới khi hết hạn. */
  private SignRequestStatus begin(SignRequest request, SignatureProvider provider, Instant now) {
    if (request.expireIfDue(now)) {
      requests.update(request, request.signerPersonId());
      return request.status();
    }
    byte[] certificateSha256 =
        certificate(request.certificateRefId()).map(CertificateRef::certSha256).orElse(null);
    List<vn.viettel.okrkpi.esign.spi.SignRequest.Document> documents =
        request.items().stream()
            .map(
                i ->
                    new vn.viettel.okrkpi.esign.spi.SignRequest.Document(
                        i.documentRef(), i.documentSha256(), ProviderRegistry.toSpi(i.format())))
            .toList();
    SignSession session;
    try {
      session =
          provider.begin(
              new vn.viettel.okrkpi.esign.spi.SignRequest(
                  request.id(),
                  request.tenantId(),
                  request.signerPersonId(),
                  certificateSha256,
                  documents,
                  request.expiresAt()));
    } catch (SignatureProviderException e) {
      return providerFailed(request, e, now);
    } catch (RuntimeException e) {
      LOG.warn("Mở phiên ký {} lỗi: {}", request.id(), e.toString());
      return request.status();
    }
    request.submitted(session.sessionId(), session.expiresAt());
    requests.update(request, request.signerPersonId());
    return request.status();
  }

  private SignRequestStatus providerFailed(
      SignRequest request, SignatureProviderException e, Instant now) {
    LOG.warn("Nhà cung cấp báo lỗi cho yêu cầu ký {}: {}", request.id(), e.getMessage());
    if (e.permanent()) {
      request.failed(now);
    } else {
      request.expireIfDue(now);
    }
    if (request.status() != SignRequestStatus.CHO_XAC_NHAN) {
      requests.update(request, request.signerPersonId());
    }
    return request.status();
  }

  private void collect(SignRequest request, SignatureProvider provider, Instant now) {
    List<SignedItem> signed = provider.collect(request.providerTxnId());
    CertificateRef certificate = null;
    for (SignedItem item : signed) {
      SignedItem.Certificate cert = item.certificate();
      certificate =
          certificates.upsert(
              new CertificateRef(
                  Ids.newUuidV7(),
                  request.tenantId(),
                  request.signerPersonId(),
                  Hashes.sha256(cert.der()),
                  cert.subjectDn(),
                  cert.issuerDn(),
                  cert.serialNo(),
                  cert.notBefore(),
                  cert.notAfter(),
                  cert.der(),
                  cert.ocspStatus(),
                  cert.ocspAt()));
      if (!request.attachSignature(
          item.documentSha256(),
          item.signatureValue(),
          item.signedObjectKey(),
          certificate.id(),
          item.signedAt())) {
        LOG.warn("Nhà cung cấp trả chữ ký cho tài liệu không thuộc yêu cầu {}", request.id());
      }
    }
    if (certificate == null || request.signedCount() != request.items().size()) {
      LOG.warn(
          "Yêu cầu ký {} nhận {} / {} chữ ký", request.id(), signed.size(), request.items().size());
      request.failed(now);
      return;
    }
    request.completeSigned(certificate.id(), now);
    events.completed(request, certificate);
  }

  private CertificateRef chooseCertificate(UUID caller, UUID certificateId, Instant now) {
    if (certificateId != null) {
      return certificates
          .findById(certificateId)
          .filter(c -> c.personId().equals(caller) && c.usableAt(now))
          .orElseThrow(
              () ->
                  new BusinessException(
                      CERTIFICATE_INVALID,
                      "Chứng thư số đã hết hạn, bị thu hồi hoặc không hợp lệ."));
    }
    List<CertificateRef> mine = certificates.findByPerson(caller);
    if (mine.isEmpty()) {
      // Ký từ xa: nhà cung cấp dùng chứng thư mặc định của người ký và trả về khi ký xong.
      return null;
    }
    return mine.stream()
        .filter(c -> c.usableAt(now))
        .max(Comparator.comparing(CertificateRef::notAfter))
        .orElseThrow(
            () ->
                new BusinessException(
                    CERTIFICATE_INVALID, "Chứng thư số đã hết hạn hoặc bị thu hồi."));
  }

  private SignRequest reload(UUID id) {
    return requests.findById(id).orElseThrow();
  }
}
