package vn.viettel.okrkpi.esign.infrastructure.sign;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
      body = client.get(PATH, Response.class, sourceModule, sourceRefId, purpose.name());
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
            body.ready(), body.signers() == null ? Set.of() : body.signers(), documents));
  }

  /** Thân phản hồi của cổng nội bộ (suy luận). */
  record Response(boolean ready, Set<UUID> signers, List<Doc> documents) {}

  /** Một tài liệu: băm hex chữ thường như {@code DossierSha256}. */
  record Doc(String kind, String ref, String sha256, String format) {}
}
