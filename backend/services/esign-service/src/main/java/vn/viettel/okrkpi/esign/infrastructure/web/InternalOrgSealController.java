package vn.viettel.okrkpi.esign.infrastructure.web;

import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.viettel.okrkpi.esign.application.seal.OrganizationSealService;
import vn.viettel.okrkpi.esign.application.seal.OrganizationSealer;

/**
 * Cổng nội bộ ký tổ chức của SVC-10 (DV-13-10): {@code POST /internal/v1/esign/org-signatures} với
 * mục đích, mã tham chiếu và băm SHA-256 (base64); trả chữ ký, thuật toán JCA và khoá công khai
 * SPKI (base64). 404 khi chưa bật, 422 khi mục đích hoặc băm không hợp lệ. Không nằm trong hợp đồng
 * công khai; chỉ token dịch vụ có vai trò trong {@code okrkpi.esign.org-seal.caller-roles} gọi
 * được.
 */
@RestController
public class InternalOrgSealController {

  public static final String PATH = "/internal/v1/esign/org-signatures";

  private final ObjectProvider<OrganizationSealService> service;

  public InternalOrgSealController(ObjectProvider<OrganizationSealService> service) {
    this.service = Objects.requireNonNull(service, "service");
  }

  /** Yêu cầu ký. */
  public record SealRequest(String purpose, String reference, String sha256) {}

  /** Chữ ký tổ chức. */
  public record SealResponse(String signature, String algorithm, String publicKey) {}

  @PostMapping(PATH)
  public SealResponse seal(
      @RequestHeader(value = "X-Tenant-Id", required = false) UUID tenantId,
      @RequestBody SealRequest body) {
    OrganizationSealService svc = service.getIfAvailable();
    if (svc == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    byte[] hash;
    try {
      hash =
          body == null || body.sha256() == null ? null : Base64.getDecoder().decode(body.sha256());
    } catch (IllegalArgumentException e) {
      hash = null;
    }
    try {
      OrganizationSealer.Seal s =
          svc.seal(
              tenantId,
              body == null ? null : body.purpose(),
              body == null ? null : body.reference(),
              hash);
      Base64.Encoder b64 = Base64.getEncoder();
      return new SealResponse(
          b64.encodeToString(s.signature()), s.algorithm(), b64.encodeToString(s.publicKey()));
    } catch (OrganizationSealService.InvalidSealRequestException e) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
  }
}
