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
  static final String VALIDATION = "COMMON-422-VALIDATION";

  private static final Logger LOG = LoggerFactory.getLogger(EsignService.class);

  private final SignRequestRepository requests;
  private final CertificateRepository certificates;
  private final SourceDocumentPort sources;
  private final ProviderRegistry providers;
  private final SignatureEvents events;
  private final Clock clock;
  private final Duration confirmWindow;
  private final BatchSettings batch;

  /**
   * Tham số ký lô (DV-10-06).
   *
   * @param maxItems số tài liệu tối đa của một lô (ADR-31: 1.000)
   * @param maxAttempts số lần thử tối đa của một tài liệu trước khi để {@code LOI}
   * @param backoff thời gian chờ trước lần thử lại đầu, nhân đôi mỗi lần
   * @param maxBackoff trần thời gian chờ giữa hai lần thử
   */
  public record BatchSettings(
      int maxItems, int maxAttempts, Duration backoff, Duration maxBackoff) {
    public static final BatchSettings DEFAULT =
        new BatchSettings(1_000, 5, Duration.ofSeconds(2), Duration.ofMinutes(5));

    /** Thời gian chờ trước lần thử thứ {@code attempts + 1}. */
    Duration delayAfter(int attempts) {
      long factor = 1L << Math.min(Math.max(attempts - 1, 0), 20);
      Duration delay = backoff.multipliedBy(factor);
      return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
  }

  public EsignService(
      SignRequestRepository requests,
      CertificateRepository certificates,
      SourceDocumentPort sources,
      ProviderRegistry providers,
      SignatureEvents events,
      Clock clock,
      Duration confirmWindow) {
    this(
        requests,
        certificates,
        sources,
        providers,
        events,
        clock,
        confirmWindow,
        BatchSettings.DEFAULT);
  }

  public EsignService(
      SignRequestRepository requests,
      CertificateRepository certificates,
      SourceDocumentPort sources,
      ProviderRegistry providers,
      SignatureEvents events,
      Clock clock,
      Duration confirmWindow,
      BatchSettings batch) {
    this.requests = Objects.requireNonNull(requests, "requests");
    this.certificates = Objects.requireNonNull(certificates, "certificates");
    this.sources = Objects.requireNonNull(sources, "sources");
    this.providers = Objects.requireNonNull(providers, "providers");
    this.events = Objects.requireNonNull(events, "events");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.confirmWindow = Objects.requireNonNull(confirmWindow, "confirmWindow");
    this.batch = Objects.requireNonNull(batch, "batch");
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
    ProviderCode code =
        cmd.provider() != null ? cmd.provider() : providers.defaultCode(cmd.tenantId());
    if (!providers.enabled(cmd.tenantId(), code)) {
      throw new BusinessException(
          PROVIDER_UNAVAILABLE, "Nhà cung cấp chữ ký số này chưa được bật cho đơn vị.");
    }
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
    // DV-10-06: quyết định hàng loạt ký lô một lần xác nhận, mỗi phiếu một chữ ký. Cổng của MOD-04
    // chưa gửi cờ lô thì nhận ra qua mục đích QUYET_DINH của appraisal có nhiều tài liệu (suy
    // luận).
    boolean batchSource =
        source.batch()
            || (cmd.purpose() == SignPurpose.QUYET_DINH
                && "appraisal".equals(cmd.sourceModule())
                && source.documents().size() > 1);
    Optional<SignatureProvider.BatchMode> batchMode =
        batchSource ? provider.batchMode() : Optional.empty();
    if (batchSource && source.documents().size() > batch.maxItems()) {
      throw new BusinessException(
          VALIDATION, "Một lô ký tối đa " + batch.maxItems() + " tài liệu.");
    }
    if (requests.existsPending(cmd.caller(), cmd.sourceModule(), cmd.sourceRefId())) {
      throw new BusinessException(REQUEST_PENDING, "Hồ sơ này đang có yêu cầu ký chờ xác nhận.");
    }
    Instant now = clock.instant();
    CertificateRef certificate =
        cmd.certificateId() == null && provider.checksCertificates()
            ? ownCertificate(provider, cmd.caller(), now)
            : recheck(provider, chooseCertificate(cmd.caller(), cmd.certificateId(), now), now);
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
    batchMode.ifPresent(
        m ->
            request.asBatch(
                vn.viettel.okrkpi.esign.domain.sign.BatchAuthorization.valueOf(m.name()), null));
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
   * Chứng thư của người gọi ở mọi nhà cung cấp tenant được dùng (DV-10-04): hỏi từng nhà cung cấp,
   * lưu chứng thư mới hoặc cập nhật kết quả kiểm tra thu hồi vào {@code esign.certificate_ref}, rồi
   * trả danh sách đã lưu. Nhà cung cấp lỗi thì bỏ qua và dùng dữ liệu đã lưu.
   */
  public List<CertificateRef> certificatesOf(UUID tenantId, UUID caller) {
    for (SignatureProvider provider : providers.enabledFor(tenantId)) {
      try {
        for (SignedItem.Certificate cert : provider.certificates(tenantId, caller)) {
          certificates.upsert(toRef(tenantId, caller, cert));
        }
      } catch (RuntimeException e) {
        LOG.warn(
            "Không liệt kê được chứng thư ở nhà cung cấp {}: {}", provider.id(), e.getMessage());
      }
    }
    return certificatesOf(caller);
  }

  /**
   * Kiểm lại thu hồi của chứng thư đã chọn qua nhà cung cấp; bị thu hồi thì lưu kết quả và trả 422
   * {@code ESIGN-422-CERTIFICATE-INVALID}.
   */
  private CertificateRef recheck(SignatureProvider provider, CertificateRef chosen, Instant now) {
    if (chosen == null) {
      return null;
    }
    Optional<SignedItem.Certificate> fresh = provider.recheck(chosen.certDer());
    if (fresh.isEmpty()) {
      return chosen;
    }
    CertificateRef updated =
        certificates.upsert(toRef(chosen.tenantId(), chosen.personId(), fresh.get()));
    if (!updated.usableAt(now)) {
      throw new BusinessException(
          CERTIFICATE_INVALID, "Chứng thư số đã hết hạn, bị thu hồi hoặc không hợp lệ.");
    }
    return updated;
  }

  /**
   * Chứng thư đã lưu của người ký thuộc chuỗi gốc của {@code provider}, kiểm lại thu hồi và cập
   * nhật trạng thái; rỗng khi chưa có (nhà cung cấp dùng chứng thư mặc định). Có chứng thư mà không
   * chứng thư nào còn dùng được thì báo lỗi (DV-10-04).
   */
  private CertificateRef ownCertificate(SignatureProvider provider, UUID caller, Instant now) {
    List<CertificateRef> own = new ArrayList<>();
    for (CertificateRef stored : certificates.findByPerson(caller)) {
      provider
          .recheck(stored.certDer())
          .ifPresent(
              fresh ->
                  own.add(certificates.upsert(toRef(stored.tenantId(), stored.personId(), fresh))));
    }
    if (own.isEmpty()) {
      return null;
    }
    return own.stream()
        .filter(c -> c.usableAt(now))
        .max(Comparator.comparing(CertificateRef::notAfter))
        .orElseThrow(
            () ->
                new BusinessException(
                    CERTIFICATE_INVALID, "Chứng thư số đã hết hạn, bị thu hồi hoặc không hợp lệ."));
  }

  private static CertificateRef toRef(UUID tenantId, UUID personId, SignedItem.Certificate cert) {
    return new CertificateRef(
        Ids.newUuidV7(),
        tenantId,
        personId,
        Hashes.sha256(cert.der()),
        cert.subjectDn(),
        cert.issuerDn(),
        cert.serialNo(),
        cert.notBefore(),
        cert.notAfter(),
        cert.der(),
        cert.ocspStatus(),
        cert.ocspAt());
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
    if (request.status() != SignRequestStatus.CHO_XAC_NHAN || request.batchAuthorized()) {
      // Lô đã được xác nhận: worker ký lô ký từng tài liệu.
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
        if (request.batchMode()) {
          request.authorizeBatch(now);
        } else {
          try {
            collect(request, provider.get(), now);
          } catch (SignatureProviderException e) {
            return providerFailed(request, e, now);
          }
        }
      }
    }
    if (request.status() != SignRequestStatus.CHO_XAC_NHAN || request.batchAuthorized()) {
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
    vn.viettel.okrkpi.esign.spi.SignRequest spi =
        new vn.viettel.okrkpi.esign.spi.SignRequest(
            request.id(),
            request.tenantId(),
            request.signerPersonId(),
            certificateSha256,
            documents,
            request.expiresAt());
    try {
      session = request.batchMode() ? provider.beginBatch(spi) : provider.begin(spi);
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

  /** Kết quả ký một tài liệu của lô. */
  public enum ItemOutcome {
    SIGNED,
    RETRY,
    FAILED,
    SKIPPED
  }

  /**
   * Ký một tài liệu của lô đã được xác nhận (DV-10-06): khoá tài liệu ({@code SKIP LOCKED}, hai
   * worker không ký cùng một tài liệu), gọi nhà cung cấp, ghi chữ ký và EVT-18 của tài liệu qua
   * outbox trong cùng giao dịch. Lỗi tạm thời hẹn thử lại với thời gian chờ tăng dần; lỗi vĩnh viễn
   * hoặc hết lượt thử để tài liệu {@code LOI} mà không làm hỏng cả lô.
   */
  @Transactional
  public ItemOutcome signBatchItem(UUID itemId) {
    Optional<SignRequestRepository.PendingItem> locked = requests.lockPendingItem(itemId);
    if (locked.isEmpty()) {
      return ItemOutcome.SKIPPED;
    }
    SignItem item = locked.get().item();
    Optional<SignRequestRepository.BatchHeader> header =
        requests.batchHeader(locked.get().requestId());
    if (header.isEmpty()
        || header.get().status() != SignRequestStatus.CHO_XAC_NHAN
        || header.get().batchAuthorizedAt() == null) {
      return ItemOutcome.SKIPPED;
    }
    SignRequestRepository.BatchHeader request = header.get();
    int attempts = item.attempts() + 1;
    Optional<SignatureProvider> provider = providers.forCode(request.provider());
    if (provider.isEmpty()) {
      requests.itemFailed(itemId, attempts, "Nhà cung cấp chưa được cấu hình");
      return ItemOutcome.FAILED;
    }
    SignedItem signed;
    try {
      signed =
          provider
              .get()
              .signItem(
                  request.providerTxnId(),
                  new vn.viettel.okrkpi.esign.spi.SignRequest.Document(
                      item.documentRef(),
                      item.documentSha256(),
                      ProviderRegistry.toSpi(item.format())));
    } catch (SignatureProviderException e) {
      return itemError(itemId, attempts, e.getMessage(), e.permanent());
    } catch (RuntimeException e) {
      return itemError(itemId, attempts, e.toString(), false);
    }
    if (!item.matches(signed.documentSha256())) {
      return itemError(itemId, attempts, "Nhà cung cấp trả chữ ký cho tài liệu khác", true);
    }
    CertificateRef certificate =
        certificates.upsert(
            toRef(request.tenantId(), request.signerPersonId(), signed.certificate()));
    Instant at = signed.signedAt() == null ? clock.instant() : signed.signedAt();
    if (!requests.itemSigned(
        itemId, signed.signatureValue(), signed.signedObjectKey(), certificate.id(), at)) {
      return ItemOutcome.SKIPPED;
    }
    events.itemSigned(
        request,
        new SignItem(
            item.id(),
            item.documentKind(),
            item.documentRef(),
            item.documentSha256(),
            item.format(),
            signed.signatureValue(),
            signed.signedObjectKey(),
            certificate.id(),
            at),
        certificate,
        at);
    return ItemOutcome.SIGNED;
  }

  private ItemOutcome itemError(UUID itemId, int attempts, String error, boolean permanent) {
    if (permanent || attempts >= batch.maxAttempts()) {
      LOG.warn("Tài liệu {} của lô không ký được sau {} lần: {}", itemId, attempts, error);
      requests.itemFailed(itemId, attempts, error);
      return ItemOutcome.FAILED;
    }
    LOG.debug("Tài liệu {} của lô thử lại lần {}: {}", itemId, attempts + 1, error);
    requests.itemRetry(itemId, attempts, clock.instant().plus(batch.delayAfter(attempts)), error);
    return ItemOutcome.RETRY;
  }

  /**
   * Đóng lô khi không còn tài liệu chờ ký: {@code DA_KY} (tài liệu lỗi trả trong {@code problem})
   * hoặc {@code LOI} khi không tài liệu nào ký được. Trả {@code true} khi vừa đóng.
   */
  @Transactional
  public boolean finishBatch(UUID requestId) {
    if (requests.pendingItems(requestId) > 0) {
      return false;
    }
    Optional<SignRequest> locked = requests.lockForProcessing(requestId);
    if (locked.isEmpty()) {
      return false;
    }
    SignRequest request = locked.get();
    if (request.status() != SignRequestStatus.CHO_XAC_NHAN
        || !request.batchAuthorized()
        || requests.pendingItems(requestId) > 0) {
      return false;
    }
    request.completeBatch(clock.instant());
    requests.update(request, request.signerPersonId());
    LOG.info(
        "Lô ký {} xong: {} / {} tài liệu đã ký, {} lỗi",
        requestId,
        request.signedCount(),
        request.items().size(),
        request.failedCount());
    return true;
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
