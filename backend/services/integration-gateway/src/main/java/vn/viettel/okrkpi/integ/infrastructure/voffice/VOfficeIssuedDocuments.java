package vn.viettel.okrkpi.integ.infrastructure.voffice;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vn.viettel.okrkpi.integ.application.IssuedDocumentSource;
import vn.viettel.okrkpi.integ.domain.Connector;

/**
 * Tra cứu văn bản đã ban hành của đơn vị trên vOffice ({@code GET
 * /api/v2/documents?unitId=&q=&limit=&cursor=}) cho minh chứng (BR-07-13): trạng thái chữ ký số lấy
 * theo kết quả vOffice kiểm khi nhận tệp gốc; tệp gốc giữ nguyên ở vOffice, chỉ trả tham chiếu.
 */
public class VOfficeIssuedDocuments implements IssuedDocumentSource {

  private final String code;
  private final VOfficeClient client;
  private final long maxFileBytes;

  VOfficeIssuedDocuments(String code, VOfficeClient client, long maxFileBytes) {
    this.code = Objects.requireNonNull(code, "code");
    this.client = Objects.requireNonNull(client, "client");
    this.maxFileBytes = maxFileBytes;
  }

  @Override
  public String connectorCode() {
    return code;
  }

  @Override
  public Result search(
      Connector connector, String externalUnitId, String q, int limit, String cursor)
      throws IOException {
    java.util.Map<String, String> query = new java.util.LinkedHashMap<>();
    query.put("unitId", externalUnitId);
    query.put("q", q);
    query.put("limit", String.valueOf(limit));
    query.put("cursor", cursor);
    JsonNode body = client.get(connector, VOfficeClient.DOCUMENTS, Map.copyOf(withoutNulls(query)));
    List<IssuedDocument> items = new ArrayList<>();
    for (JsonNode n : body.path("items")) {
      items.add(document(n));
    }
    String next = body.path("nextCursor").asText(null);
    return new Result(items, next == null || next.isBlank() ? null : next);
  }

  /**
   * Tệp gốc qua {@code GET /api/v2/documents/detail?id=&unitId=} (siêu dữ liệu kèm {@code
   * fileName}, {@code mediaType}, {@code sha256} của bản đã ký) và {@code GET
   * /api/v2/documents/content?id=&unitId=} (nội dung). Tham chiếu đi bằng tham số truy vấn vì số ký
   * hiệu có dấu {@code /}. Hai đường này là hợp đồng giả lập (YC-PT-13), suy luận theo DV-13-03.
   */
  @Override
  public Optional<OriginalFile> fetch(Connector connector, String externalUnitId, String ref)
      throws IOException {
    Map<String, String> query = Map.of("id", ref, "unitId", externalUnitId);
    Optional<JsonNode> detail = client.find(connector, VOfficeClient.DOCUMENT_DETAIL, query);
    if (detail.isEmpty() || !externalUnitId.equals(detail.get().path("unitId").asText(""))) {
      return Optional.empty();
    }
    JsonNode n = detail.get();
    byte[] signed;
    try {
      signed = HexFormat.of().parseHex(n.path("sha256").asText("").toLowerCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      signed = null;
    }
    byte[] signedSha256 = signed != null && signed.length == 32 ? signed : null;
    return client
        .download(connector, VOfficeClient.DOCUMENT_CONTENT, query, maxFileBytes)
        .map(
            content ->
                new OriginalFile(
                    document(n),
                    content,
                    n.path("mediaType").asText("application/pdf"),
                    n.path("fileName").asText(null),
                    signedSha256));
  }

  private static IssuedDocument document(JsonNode n) {
    LocalDate date;
    try {
      date = LocalDate.parse(n.path("issuedDate").asText(""));
    } catch (DateTimeParseException e) {
      date = null;
    }
    return new IssuedDocument(
        n.path("id").asText(""),
        n.path("docNumber").asText(""),
        date,
        n.path("summary").asText(""),
        n.path("signerName").asText(null),
        n.path("signatureValid").asBoolean(false));
  }

  private static Map<String, String> withoutNulls(Map<String, String> m) {
    m.values().removeIf(Objects::isNull);
    return m;
  }
}
