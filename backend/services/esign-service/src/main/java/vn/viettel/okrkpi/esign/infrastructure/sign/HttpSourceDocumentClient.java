package vn.viettel.okrkpi.esign.infrastructure.sign;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.HttpClientErrorException;
import vn.viettel.okrkpi.esign.application.sign.SourceDocumentPort;
import vn.viettel.okrkpi.esign.domain.sign.SignPurpose;
import vn.viettel.okrkpi.esign.domain.sign.SignatureFormat;
import vn.viettel.okrkpi.shared.resilience.ResilientClient;

/**
 * Hỏi module nguồn tài liệu cần ký qua cổng nội bộ {@code GET
 * /internal/v1/esign-sources/{module}/{id}?purpose=} với Resilience4j (ADR-24). Hợp đồng công khai
 * không có thao tác này; đường dẫn và thân phản hồi là suy luận (wiki trang 14), module nguồn hiện
 * thực ở task CORE tương ứng.
 */
public class HttpSourceDocumentClient implements SourceDocumentPort {

  static final String PATH = "/internal/v1/esign-sources/{module}/{id}?purpose={purpose}";

  private final Map<String, ResilientClient> clients;

  /** {@code clients}: mã module ({@code task}, {@code dossier}...) → client tới module đó. */
  public HttpSourceDocumentClient(Map<String, ResilientClient> clients) {
    this.clients = Map.copyOf(clients);
  }

  @Override
  public Optional<SourceDocuments> fetch(
      UUID tenantId, String sourceModule, UUID sourceRefId, SignPurpose purpose) {
    ResilientClient client = clients.get(sourceModule);
    if (client == null) {
      return Optional.empty();
    }
    Response body;
    try {
      body =
          client.exchange(
              HttpMethod.GET,
              PATH,
              null,
              callerHeaders(),
              Response.class,
              sourceModule,
              sourceRefId,
              purpose.name());
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
    if (body == null) {
      return Optional.empty();
    }
    List<Document> documents =
        body.documents() == null
            ? List.of()
            : body.documents().stream()
                .map(
                    d ->
                        new Document(
                            d.kind(),
                            d.ref(),
                            HexFormat.of().parseHex(d.sha256()),
                            SignatureFormat.valueOf(d.format())))
                .toList();
    return Optional.of(
        new SourceDocuments(
            body.ready(),
            body.signers() == null ? Set.of() : body.signers(),
            documents,
            Boolean.TRUE.equals(body.batch())));
  }

  /**
   * Chuyển tiếp token Bearer của người gọi (người ký, đã được mission-core chuyển sang khi tạo yêu
   * cầu ký) để module nguồn đọc tenant từ JWT như mọi lời gọi khác; thiếu token thì module nguồn
   * trả 403 "Phiên đăng nhập không gắn với đơn vị triển khai nào" (QA-05 HT-11). Đọc ở luồng gọi,
   * trước khi ResilientClient chuyển sang luồng ảo.
   */
  static HttpHeaders callerHeaders() {
    HttpHeaders headers = new HttpHeaders();
    if (SecurityContextHolder.getContext().getAuthentication()
        instanceof JwtAuthenticationToken jwt) {
      headers.setBearerAuth(jwt.getToken().getTokenValue());
    }
    return headers;
  }

  /**
   * Thân phản hồi của cổng nội bộ (suy luận); {@code batch} đúng với quyết định hàng loạt của
   * MOD-04 (DV-10-06).
   */
  record Response(boolean ready, Set<UUID> signers, List<Doc> documents, Boolean batch) {}

  /** Một tài liệu: băm hex chữ thường như {@code DossierSha256}. */
  record Doc(String kind, String ref, String sha256, String format) {}
}
