package vn.viettel.okrkpi.esign.infrastructure.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.viettel.okrkpi.esign.application.sign.EsignService;
import vn.viettel.okrkpi.esign.domain.sign.CertificateRef;
import vn.viettel.okrkpi.esign.domain.sign.ProviderCode;
import vn.viettel.okrkpi.esign.domain.sign.SignPurpose;
import vn.viettel.okrkpi.esign.domain.sign.SignRequest;
import vn.viettel.okrkpi.esign.domain.sign.SignRequestStatus;
import vn.viettel.okrkpi.esign.infrastructure.web.api.EsignApi;
import vn.viettel.okrkpi.esign.infrastructure.web.model.EsignCertificate;
import vn.viettel.okrkpi.esign.infrastructure.web.model.EsignPurpose;
import vn.viettel.okrkpi.esign.infrastructure.web.model.EsignRequest;
import vn.viettel.okrkpi.esign.infrastructure.web.model.EsignRequestCreate;
import vn.viettel.okrkpi.esign.infrastructure.web.model.EsignRequestPage;
import vn.viettel.okrkpi.esign.infrastructure.web.model.EsignRequestStatus;
import vn.viettel.okrkpi.esign.infrastructure.web.model.EsignSignatureFormat;
import vn.viettel.okrkpi.esign.infrastructure.web.model.ListMyEsignCertificates200Response;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectResolver;
import vn.viettel.okrkpi.shared.authz.StepUpValidator;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.idempotency.Idempotent;
import vn.viettel.okrkpi.shared.tenant.TenantContext;

/**
 * API ký số của SVC-10 theo {@code dossiers.yaml} (QT-04, QT-09, QT-11, QT-12, QT-13, QT-15):
 * {@code createEsignRequest} (202, AAL2), {@code getEsignRequest}, {@code listMyEsignRequests},
 * {@code listMyEsignCertificates}. Người gọi là {@code sub} của token; chỉ thấy yêu cầu của mình.
 */
@RestController
@RequestMapping("/api/v1")
public class EsignController implements EsignApi {

  static final String OPERATION_CREATE = "createEsignRequest";
  static final long RETRY_AFTER_SECONDS = 2;
  private static final String OUT_OF_SCOPE = "COMMON-403-OUT-OF-SCOPE";
  private static final String VALIDATION = "COMMON-422-VALIDATION";

  private final EsignService service;
  private final StepUpValidator stepUp;
  private final AuthzSubjectResolver subjects;

  public EsignController(
      EsignService service, StepUpValidator stepUp, AuthzSubjectResolver subjects) {
    this.service = service;
    this.stepUp = stepUp;
    this.subjects = subjects;
  }

  @Override
  @Idempotent(schema = "esign")
  public ResponseEntity<EsignRequest> createEsignRequest(
      String idempotencyKey, EsignRequestCreate body) {
    stepUp.verifyStepUp(subjects.currentSubject(), OPERATION_CREATE);
    SignRequest created =
        service.create(
            new EsignService.CreateCommand(
                TenantContext.require().value(),
                caller(),
                SignPurpose.valueOf(body.getPurpose().getValue()),
                body.getSourceModule().getValue(),
                body.getSourceRefId(),
                body.getProvider() == null
                    ? null
                    : ProviderCode.valueOf(body.getProvider().getValue()),
                body.getCertificateId()));
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(java.net.URI.create("/api/v1/esign/requests/" + created.id()))
        .header(HttpHeaders.RETRY_AFTER, Long.toString(RETRY_AFTER_SECONDS))
        .body(toDto(created));
  }

  @Override
  public ResponseEntity<EsignRequest> getEsignRequest(UUID id) {
    SignRequest request = service.get(caller(), id);
    ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
    if (!request.status().isTerminal()) {
      ok.header(HttpHeaders.RETRY_AFTER, Long.toString(RETRY_AFTER_SECONDS));
    }
    return ok.body(toDto(request));
  }

  @Override
  public ResponseEntity<EsignRequestPage> listMyEsignRequests(
      EsignRequestStatus status, EsignPurpose purpose, Integer limit, String cursor) {
    int size = limit == null ? 20 : limit;
    List<SignRequest> page =
        service.listMine(
            caller(),
            status == null ? null : SignRequestStatus.valueOf(status.getValue()),
            purpose == null ? null : SignPurpose.valueOf(purpose.getValue()),
            decodeCursor(cursor),
            size + 1);
    boolean more = page.size() > size;
    List<SignRequest> items = more ? page.subList(0, size) : page;
    String next = more ? encodeCursor(items.get(items.size() - 1).id()) : null;
    return ResponseEntity.ok(new EsignRequestPage(items.stream().map(this::toDto).toList(), next));
  }

  @Override
  public ResponseEntity<ListMyEsignCertificates200Response> listMyEsignCertificates() {
    ListMyEsignCertificates200Response body = new ListMyEsignCertificates200Response();
    body.setItems(
        service.certificatesOf(TenantContext.require().value(), caller()).stream()
            .map(EsignController::toDto)
            .toList());
    return ResponseEntity.ok(body);
  }

  private EsignRequest toDto(SignRequest r) {
    EsignRequest dto =
        new EsignRequest(
            r.id(),
            EsignPurpose.fromValue(r.purpose().name()),
            r.sourceModule(),
            r.sourceRefId(),
            vn.viettel.okrkpi.esign.infrastructure.web.model.EsignProvider.fromValue(
                r.provider().name()),
            r.batch(),
            EsignRequestStatus.fromValue(r.status().name()),
            r.expiresAt().atOffset(ZoneOffset.UTC));
    dto.setCompletedAt(r.completedAt() == null ? null : r.completedAt().atOffset(ZoneOffset.UTC));
    dto.setItemCount(r.items().size());
    dto.setSignedCount(r.signedCount());
    dto.setSignatureFormat(EsignSignatureFormat.fromValue(r.items().get(0).format().name()));
    service
        .certificate(r.certificateRefId())
        .map(EsignController::toDto)
        .ifPresent(dto::setCertificate);
    return dto;
  }

  private static EsignCertificate toDto(CertificateRef c) {
    EsignCertificate dto =
        new EsignCertificate(
            c.id(),
            c.subjectDn(),
            c.issuerDn(),
            c.serialNo(),
            c.notBefore().atOffset(ZoneOffset.UTC),
            c.notAfter().atOffset(ZoneOffset.UTC));
    dto.setCertSha256(HexFormat.of().formatHex(c.certSha256()));
    dto.setLastOcspStatus(c.lastOcspStatus());
    Instant at = c.lastOcspAt();
    dto.setLastOcspAt(at == null ? null : at.atOffset(ZoneOffset.UTC));
    return dto;
  }

  private static UUID caller() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof JwtAuthenticationToken jwt && jwt.getToken().getSubject() != null) {
      try {
        return UUID.fromString(jwt.getToken().getSubject());
      } catch (IllegalArgumentException e) {
        throw new BusinessException(OUT_OF_SCOPE);
      }
    }
    throw new BusinessException(OUT_OF_SCOPE);
  }

  static String encodeCursor(UUID lastId) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(lastId.toString().getBytes(StandardCharsets.US_ASCII));
  }

  static UUID decodeCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(VALIDATION, "Con trỏ phân trang không hợp lệ.");
    }
  }
}
